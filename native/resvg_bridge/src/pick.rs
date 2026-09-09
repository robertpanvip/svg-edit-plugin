//! Colour-ID "hit canvas" rendering.
//!
//! Rasterizes an SVG into a picking map: every paint leaf (path / image / text) is drawn
//! **flat** in a colour that encodes its position within the layout's paint-leaf list, so the
//! Kotlin side can answer "which element is at this pixel?" with a single sample — the same
//! offscreen colour-picking technique LeaferJS uses, and a precise replacement for the
//! bounding-box fallback hit test used when the sidecar is unavailable.
//!
//! The colour value of a pixel is `leafIndex + 1`, where `leafIndex` counts the paint leaves
//! in the exact pre-order used by the layout extraction (paths / images / texts whose absolute
//! bounding box is non-empty, walking children in document order, groups transparent). Elements
//! that are invisible or carry neither fill nor stroke are counted but never painted — they
//! occupy a stable ordinal yet can never trap a pointer.
//!
//! Text and images are approximated by their absolute bounding box — the same approximation the
//! geometry hit tests already use for images, and a tight enough fit for text glyph clusters.

use resvg::tiny_skia;
use usvg::{tiny_skia_path, Node};

const MAX_PX: u32 = 16384;

/// Convert a colour ordinal `v` (>= 1) into opaque `(r, g, b)`.
fn color_of(v: u32) -> (u8, u8, u8) {
    let v = v.max(1);
    (((v >> 16) & 0xFF) as u8, ((v >> 8) & 0xFF) as u8, (v & 0xFF) as u8)
}

/// usvg (column-major: `x' = sx*x + kx*y + tx`, `y' = ky*x + sy*y + ty`) -> tiny-skia.
fn to_ts(t: &usvg::Transform) -> tiny_skia::Transform {
    tiny_skia::Transform::from_row(t.sx, t.ky, t.kx, t.sy, t.tx, t.ty)
}

/// Node-space transform followed by the view scale.
///
/// tiny-skia's `self.post_concat(other)` maps points through `self` FIRST and then `other`
/// (it builds `other ∘ self`), so composing the node's absolute transform and then `view`
/// must be written `node.post_concat(view)`. The earlier `view.post_concat(node)` applied
/// the view scale first and left the node's translation un-scaled in output pixels — exact
/// only at scale 1, and off by `(1 - scale) * t` for every translated/scaled leaf at fit
/// sizes (the visible ring band of a fitted gear icon was ~26 px away from the pick canvas).
fn to_view_ts(node: &usvg::Transform, view: tiny_skia::Transform) -> tiny_skia::Transform {
    to_ts(node).post_concat(view)
}

/// Fill a path with the flat ordinal colour (anti-aliased, opaque).
fn fill_path_pick(
    pix: &mut tiny_skia::PixmapMut<'_>,
    path: &tiny_skia_path::Path,
    value: u32,
    ts: tiny_skia::Transform,
) {
    let (r, g, b) = color_of(value);
    let mut paint = tiny_skia::Paint::default();
    paint.set_color_rgba8(r, g, b, 255);
    paint.anti_alias = true;
    pix.fill_path(path, &paint, tiny_skia::FillRule::Winding, ts, None);
}

/// Outline a path with the same ordinal colour (for stroke-only shapes and outlines).
fn stroke_path_pick(
    pix: &mut tiny_skia::PixmapMut<'_>,
    path: &tiny_skia_path::Path,
    value: u32,
    ts: tiny_skia::Transform,
    stroke: &usvg::Stroke,
) {
    if stroke.width().get() <= 0.0 {
        return;
    }
    let (r, g, b) = color_of(value);
    let mut paint = tiny_skia::Paint::default();
    paint.set_color_rgba8(r, g, b, 255);
    paint.anti_alias = true;
    let ts_stroke = stroke.to_tiny_skia();
    pix.stroke_path(path, &paint, &ts_stroke, ts, None);
}

/// Fill a rectangle (images and text approximations) with the ordinal colour.
fn fill_rect_pick(
    pix: &mut tiny_skia::PixmapMut<'_>,
    rect: usvg::Rect,
    value: u32,
    ts: tiny_skia::Transform,
) {
    let (r, g, b) = color_of(value);
    let mut paint = tiny_skia::Paint::default();
    paint.set_color_rgba8(r, g, b, 255);
    paint.anti_alias = true;
    let Some(builder) = tiny_skia_path::Rect::from_xywh(rect.x(), rect.y(), rect.width(), rect.height()) else {
        return;
    };
    let path = tiny_skia_path::PathBuilder::from_rect(builder);
    pix.fill_path(&path, &paint, tiny_skia::FillRule::Winding, ts, None);
}

/// Pre-order visit that assigns ordinals and paints visible, paintable leaves. Every leaf's
/// `abs_transform` is already root-space, so we only compose the uniform view scale.
fn visit(
    node: &Node,
    pix: &mut tiny_skia::PixmapMut<'_>,
    ordinal: &mut u32,
    view: tiny_skia::Transform,
) {
    match node {
        Node::Group(g) => {
            for child in g.children() {
                visit(child, pix, ordinal, view);
            }
        }
        Node::Path(p) => {
            let b = p.abs_bounding_box();
            if b.width() <= 0.0 || b.height() <= 0.0 {
                return; // zero-area leaves are not in the layout list -> keep no ordinal
            }
            *ordinal += 1;
            if !p.is_visible() {
                return;
            }
            let has_fill = p.fill().is_some();
            let has_stroke = p.stroke().is_some();
            if !has_fill && !has_stroke {
                return; // never a pointer target (visiblePainted)
            }
            let ts = to_view_ts(&p.abs_transform(), view);
            if has_fill {
                fill_path_pick(pix, p.data(), *ordinal, ts);
            }
            if has_stroke {
                stroke_path_pick(pix, p.data(), *ordinal, ts, p.stroke().unwrap());
            }
        }
        Node::Image(img) => {
            let b = img.abs_bounding_box();
            if b.width() <= 0.0 || b.height() <= 0.0 {
                return;
            }
            *ordinal += 1;
            let ts = to_view_ts(&img.abs_transform(), view);
            fill_rect_pick(pix, b, *ordinal, ts);
        }
        Node::Text(txt) => {
            let b = txt.abs_bounding_box();
            if b.width() <= 0.0 || b.height() <= 0.0 {
                return;
            }
            *ordinal += 1;
            let ts = to_view_ts(&txt.abs_transform(), view);
            fill_rect_pick(pix, b, *ordinal, ts);
        }
    }
}

/// Render `svg` into a colour-ID hit canvas. Sized like the shared render pipeline: uniform fit
/// into `(fit_w, fit_h)` when positive, natural size otherwise. Returns premultiplied RGBA8.
pub fn render_pick(svg: &str, fit_w: u32, fit_h: u32) -> Option<(Vec<u8>, u32, u32)> {
    let tree = usvg::Tree::from_str(svg, &usvg::Options::default()).ok()?;
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
    if pw > MAX_PX || ph > MAX_PX {
        return None;
    }
    let mut pixmap = tiny_skia::Pixmap::new(pw, ph)?;
    let view = tiny_skia::Transform::from_row(scale, 0.0, 0.0, scale, 0.0, 0.0);
    {
        let mut pmut = pixmap.as_mut();
        let mut ordinal = 0u32;
        for child in tree.root().children() {
            visit(child, &mut pmut, &mut ordinal, view);
        }
    }
    Some((pixmap.data().to_vec(), pw, ph))
}

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

    fn rgb_at(rgba: &[u8], w: u32, x: u32, y: u32) -> u32 {
        if x >= w {
            return 0;
        }
        let o = ((y * w + x) * 4) as usize;
        if rgba[o + 3] == 0 {
            return 0; // transparent -> no element painted here
        }
        ((rgba[o] as u32) << 16) | ((rgba[o + 1] as u32) << 8) | (rgba[o + 2] as u32)
    }

    #[test]
    fn paints_leaves_in_layout_order_with_distinct_colours() {
        let (rgba, w, h) = render_pick(SAMPLE, 0, 0).expect("pick render");
        assert_eq!((w, h), (200, 120));
        // Leaves in paint order: bg(1) box-a(2) dot(3) grp->inner(4). Colours are 1-based ordinals.
        assert_eq!(rgb_at(&rgba, w, 5, 5), 1); // bg fill
        assert_eq!(rgb_at(&rgba, w, 30, 30), 2); // box-a interior
        assert_eq!(rgb_at(&rgba, w, 150, 60), 3); // dot center
        assert_eq!(rgb_at(&rgba, w, 140, 90), 4); // inner inside the translated group (abs 120..160 x 80..100)
        assert_eq!(rgb_at(&rgba, w, 199, 119), 1); // bottom-right still bg
    }

    #[test]
    fn respects_fit_size() {
        let (rgba, w, h) = render_pick(SAMPLE, 100, 60).expect("pick render");
        assert_eq!((w, h), (100, 60));
        // svg (25,20) is inside box-a (10..90 x 10..70); at scale 0.5 that is pick px (12,10).
        assert_eq!(rgb_at(&rgba, w, 12, 10), 2);
        // svg (150,60) = dot center -> pick px (75,30).
        assert_eq!(rgb_at(&rgba, w, 75, 30), 3);
        // grp->inner sits at abs (120..160, 80..100) -> pick px (60..80, 40..50). Its group
        // translate must be scaled WITH the view (regression: an inverted compose order left
        // the translate un-scaled, pushing inner off the 100x60 canvas at px ~(120..140, 80..90)).
        assert_eq!(rgb_at(&rgba, w, 70, 45), 4, "scaled translate must land inside (60..80,40..50)");
        assert_eq!(rgb_at(&rgba, w, 5, 55), 1); // bottom-left stays bg (nothing leaked off-canvas)
    }

    #[test]
    fn translates_scale_with_fit_scale_factor() {
        // The user's gear icon shape: a path whose own transform carries a large translate
        // combined with a fit scale < 1. The painted band must sit at view*t, never at t alone.
        let svg = r##"<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 1024 1024">
          <rect id="a" x="512" y="512" width="200" height="200" transform="translate(-100 -50)" fill="#333"/>
        </svg>"##;
        let (rgba, w, h) = render_pick(svg, 512, 512).expect("pick render");
        assert_eq!((w, h), (512, 512));
        // rect abs box = (412..612, 462..662); scale 0.5 -> px (206..306, 231..331).
        assert_eq!(rgb_at(&rgba, w, 256, 281), 1, "translate must be scaled to view space");
        // An UN-scaled translate paints at px (156..256, 206..306): x=200 sits inside that
        // wrong-order band but 6 px left of the correct band's edge (206), so it must be empty.
        assert_eq!(rgb_at(&rgba, w, 200, 281), 0, "translate must not shift un-scaled in px");
    }
}
