//! `resvg_bridge` — a small C-ABI shim around [`usvg`] + [`resvg`].
//!
//! The Kotlin side (via JNA) calls two things:
//!
//! 1. [`svg_render_png_bytes`] — render an SVG string to **PNG bytes**. The Kotlin
//!    panel decodes these into an off-screen [`java.awt.image.BufferedImage`] which is
//!    then blitted to the visible panel. This is the "off-screen canvas".
//!
//! 2. [`svg_layout_json`] — parse the SVG and emit a JSON document describing every
//!    rendered element: its `id`, its **absolute bounding box** in canvas coordinates,
//!    and its affine `transform`. The Kotlin [`CollisionDetector`] uses these boxes to
//!    hit-test the mouse pointer and to drive drag-to-move editing.
//!
//! Both functions are `extern "C"` so they are callable from any FFI bridge (JNA/JNI).

use std::collections::HashMap;
use std::ffi::{c_char, CStr, CString};
use std::ptr;
use std::sync::{LazyLock, Mutex};

use resvg::tiny_skia;
use usvg::Node;

// ---------------------------------------------------------------------------
// Buffer registry
//
// Memory allocated here and handed to C must be freed here. We keep a registry
// keyed by the returned pointer so the free functions know the real (len, cap).
// ---------------------------------------------------------------------------
type BufKey = usize;
static BUF_REGISTRY: LazyLock<Mutex<HashMap<BufKey, (usize, usize)>>> = LazyLock::new(|| Mutex::new(HashMap::new()));

fn register_buffer(ptr: *mut u8, len: usize, cap: usize) -> *mut u8 {
    if ptr.is_null() {
        return ptr;
    }
    BUF_REGISTRY.lock().unwrap().insert(ptr as usize, (len, cap));
    ptr
}

fn free_registered(ptr: *mut u8) {
    if let Some((len, cap)) = BUF_REGISTRY.lock().unwrap().remove(&(ptr as usize)) {
        unsafe {
            drop(Vec::from_raw_parts(ptr, len, cap));
        }
    }
}

// ---------------------------------------------------------------------------
// Layout model + extraction
// ---------------------------------------------------------------------------

/// One renderable element with its absolute (canvas-space) geometry.
struct ElementLayout {
    index: usize,
    id: String,
    kind: &'static str,
    x: f32,
    y: f32,
    width: f32,
    height: f32,
    /// Column-major 2x3 affine matrix: [sx, kx, ky, sy, tx, ty].
    transform: [f32; 6],
}

fn kind_of(node: &Node) -> &'static str {
    match node {
        Node::Group(_) => "group",
        Node::Path(_) => "path",
        Node::Image(_) => "image",
        Node::Text(_) => "text",
    }
}

/// Recursively collect non-empty elements starting from `node`.
fn collect(node: &Node, index: &mut usize, out: &mut Vec<ElementLayout>) {
    let bbox = node.abs_bounding_box();
    let w = bbox.width();
    let h = bbox.height();
    // Skip zero-area nodes (e.g. empty groups) — they are not hit-testable.
    if w > 0.0 && h > 0.0 {
        let t = node.abs_transform();
        out.push(ElementLayout {
            index: *index,
            id: node.id().to_string(),
            kind: kind_of(node),
            x: bbox.x(),
            y: bbox.y(),
            width: w,
            height: h,
            transform: [t.sx, t.kx, t.ky, t.sy, t.tx, t.ty],
        });
        *index += 1;
    }
    if let Node::Group(g) = node {
        for child in g.children() {
            collect(child, index, out);
        }
    }
}

/// Escape a string for inclusion inside a JSON string literal.
fn json_str(s: &str) -> String {
    let mut out = String::with_capacity(s.len() + 2);
    out.push('"');
    for c in s.chars() {
        match c {
            '"' => out.push_str("\\\""),
            '\\' => out.push_str("\\\\"),
            '\n' => out.push_str("\\n"),
            '\r' => out.push_str("\\r"),
            '\t' => out.push_str("\\t"),
            c if (c as u32) < 0x20 => out.push_str(&format!("\\u{:04x}", c as u32)),
            c => out.push(c),
        }
    }
    out.push('"');
    out
}

/// Build the layout JSON document for a parsed tree.
fn layout_json(tree: &usvg::Tree) -> String {
    let mut elements: Vec<ElementLayout> = Vec::new();
    let mut index = 0usize;
    for child in tree.root().children() {
        collect(child, &mut index, &mut elements);
    }
    let size = tree.size();
    let mut s = String::from("{\"width\":");
    s.push_str(&format!("{}", size.width()));
    s.push_str(",\"height\":");
    s.push_str(&format!("{}", size.height()));
    s.push_str(",\"elements\":[");
    for (i, e) in elements.iter().enumerate() {
        if i > 0 {
            s.push(',');
        }
        s.push_str(&format!(
            "{{\"index\":{},\"id\":{},\"kind\":\"{}\",\"x\":{},\"y\":{},\"width\":{},\"height\":{},\"transform\":[{},{},{},{},{},{}]}}",
            e.index,
            json_str(&e.id),
            e.kind,
            e.x,
            e.y,
            e.width,
            e.height,
            e.transform[0],
            e.transform[1],
            e.transform[2],
            e.transform[3],
            e.transform[4],
            e.transform[5]
        ));
    }
    s.push_str("]}");
    s
}

// ---------------------------------------------------------------------------
// C-ABI entry points
// ---------------------------------------------------------------------------

/// Render `svg` to PNG bytes. Returns a heap-allocated buffer (free with
/// [`svg_free_bytes`]) and reports its length, and the produced pixel size.
///
/// `fit_w`/`fit_h` of 0 means "render at the SVG's natural size". Otherwise the
/// image is scaled uniformly to fit inside `(fit_w, fit_h)`.
///
/// Returns a null pointer on any failure.
///
/// # Safety
/// `svg` must be a valid NUL-terminated UTF-8 string. `out_len`, `out_w`,
/// `out_h` must be non-null.
#[no_mangle]
pub unsafe extern "C" fn svg_render_png_bytes(
    svg: *const c_char,
    fit_w: u32,
    fit_h: u32,
    out_len: *mut u32,
    out_w: *mut u32,
    out_h: *mut u32,
) -> *mut u8 {
    if svg.is_null() || out_len.is_null() || out_w.is_null() || out_h.is_null() {
        return ptr::null_mut();
    }
    let cstr = CStr::from_ptr(svg);
    let svg_str = match cstr.to_str() {
        Ok(s) => s,
        Err(_) => return ptr::null_mut(),
    };

    let opts = usvg::Options::default();
    let tree = match usvg::Tree::from_str(svg_str, &opts) {
        Ok(t) => t,
        Err(_) => return ptr::null_mut(),
    };

    let size = tree.size();
    let (sw, sh) = (size.width(), size.height());
    let scale = if fit_w > 0 && fit_h > 0 {
        let s = (fit_w as f32 / sw).min(fit_h as f32 / sh);
        if s.is_finite() && s > 0.0 {
            s
        } else {
            1.0
        }
    } else {
        1.0
    };
    let pw = (sw * scale).ceil().max(1.0) as u32;
    let ph = (sh * scale).ceil().max(1.0) as u32;

    let mut pixmap = match tiny_skia::Pixmap::new(pw, ph) {
        Some(p) => p,
        None => return ptr::null_mut(),
    };
    let transform = usvg::Transform {
        sx: scale,
        kx: 0.0,
        ky: 0.0,
        sy: scale,
        tx: 0.0,
        ty: 0.0,
    };
    resvg::render(&tree, transform, &mut pixmap.as_mut());

    let png = match pixmap.encode_png() {
        Ok(p) => p,
        Err(_) => return ptr::null_mut(),
    };

    let len = png.len();
    let cap = png.capacity();
    let ptr_out = png.as_ptr() as *mut u8;
    std::mem::forget(png); // ownership transferred to the caller (via registry)

    *out_len = len as u32;
    *out_w = pw;
    *out_h = ph;
    register_buffer(ptr_out, len, cap)
}

/// Extract the per-element layout of `svg` as a JSON string.
/// Returns a heap-allocated NUL-terminated string (free with [`svg_free_string`]),
/// or null on failure.
///
/// # Safety
/// `svg` must be a valid NUL-terminated UTF-8 string.
#[no_mangle]
pub unsafe extern "C" fn svg_layout_json(svg: *const c_char) -> *mut c_char {
    if svg.is_null() {
        return ptr::null_mut();
    }
    let cstr = CStr::from_ptr(svg);
    let svg_str = match cstr.to_str() {
        Ok(s) => s,
        Err(_) => return ptr::null_mut(),
    };
    let opts = usvg::Options::default();
    let tree = match usvg::Tree::from_str(svg_str, &opts) {
        Ok(t) => t,
        Err(_) => return ptr::null_mut(),
    };
    let json = layout_json(&tree);
    match CString::new(json) {
        Ok(c) => c.into_raw(),
        Err(_) => ptr::null_mut(),
    }
}

/// Free a string returned by [`svg_layout_json`].
///
/// # Safety
/// `s` must have been returned by [`svg_layout_json`] (or be null).
#[no_mangle]
pub unsafe extern "C" fn svg_free_string(s: *mut c_char) {
    if !s.is_null() {
        drop(CString::from_raw(s));
    }
}

/// Free a buffer returned by [`svg_render_png_bytes`].
///
/// # Safety
/// `ptr` must have been returned by [`svg_render_png_bytes`] (or be null).
#[no_mangle]
pub unsafe extern "C" fn svg_free_bytes(ptr: *mut u8) {
    free_registered(ptr);
}

// ---------------------------------------------------------------------------
// Tests
// ---------------------------------------------------------------------------
#[cfg(test)]
mod tests {
    use super::*;

    const SAMPLE: &str = r##"<svg xmlns="http://www.w3.org/2000/svg" width="200" height="120" viewBox="0 0 200 120">
      <rect id="bg" x="0" y="0" width="200" height="120" fill="#fafafa"/>
      <rect id="box-a" x="10" y="10" width="80" height="60" fill="#4caf50"/>
      <circle id="dot" cx="150" cy="60" r="30" fill="#e91e63"/>
      <g id="grp" transform="translate(120,80)">
        <rect id="inner" x="0" y="0" width="40" height="20" fill="#2196f3"/>
      </g>
    </svg>"##;

    #[test]
    fn parses_and_lists_elements() {
        let opts = usvg::Options::default();
        let tree = usvg::Tree::from_str(SAMPLE, &opts).expect("parse");
        let mut els = Vec::new();
        let mut idx = 0usize;
        for child in tree.root().children() {
            collect(child, &mut idx, &mut els);
        }
        // bg, box-a, dot, grp(translate -> has bbox from inner), inner
        let ids: Vec<&str> = els.iter().map(|e| e.id.as_str()).collect();
        assert!(ids.contains(&"bg"));
        assert!(ids.contains(&"box-a"));
        assert!(ids.contains(&"dot"));
        assert!(ids.contains(&"inner"));
    }

    #[test]
    fn bounding_boxes_are_in_canvas_coordinates() {
        let opts = usvg::Options::default();
        let tree = usvg::Tree::from_str(SAMPLE, &opts).expect("parse");
        let mut els = Vec::new();
        let mut idx = 0usize;
        for child in tree.root().children() {
            collect(child, &mut idx, &mut els);
        }
        let box_a = els.iter().find(|e| e.id == "box-a").unwrap();
        // box-a is at (10,10) size 80x60 -> abs bbox should match (no transform).
        assert!((box_a.x - 10.0).abs() < 0.5);
        assert!((box_a.y - 10.0).abs() < 0.5);
        assert!((box_a.width - 80.0).abs() < 0.5);
        assert!((box_a.height - 60.0).abs() < 0.5);

        // The group translated (120,80) so `inner` (0,0,40,20) ends up at (120,80).
        let inner = els.iter().find(|e| e.id == "inner").unwrap();
        assert!((inner.x - 120.0).abs() < 0.5);
        assert!((inner.y - 80.0).abs() < 0.5);
    }

    #[test]
    fn renders_to_non_empty_png() {
        let c_svg = CString::new(SAMPLE).unwrap();
        let mut len: u32 = 0;
        let mut w: u32 = 0;
        let mut h: u32 = 0;
        let ptr = unsafe { svg_render_png_bytes(c_svg.as_ptr(), 0, 0, &mut len, &mut w, &mut h) };
        assert!(!ptr.is_null(), "render should succeed");
        assert!(len > 8, "png must have a header");
        // PNG magic: 89 50 4E 47 0D 0A 1A 0A
        unsafe {
            let bytes = std::slice::from_raw_parts(ptr, len as usize);
            assert_eq!(&bytes[0..8], &[0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A]);
            assert_eq!(w, 200);
            assert_eq!(h, 120);
            svg_free_bytes(ptr);
        }
    }
}
