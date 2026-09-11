//! Pixel-format conversion between the renderer and GPUI.
//!
//! Two conversions matter and both are easy to get wrong:
//!
//! - `resvg`/`tiny-skia` hand back **premultiplied** RGBA8, while GPUI's `RenderImage` expects
//!   **straight** (un-premultiplied) alpha — semi-transparent pixels render too dark otherwise.
//! - `RenderImage` is documented as **BGRA**, not RGBA (`gpui/src/assets.rs`), so R and B are
//!   swapped.
//!
//! Getting this wrong is visible: an opaque `#4f46e5` swatch must come back byte-identical, and a
//! 50%-alpha black must not turn into a dark smudge.

use std::sync::Arc;

use gpui::RenderImage;
use image::{Frame, RgbaImage};

/// Wraps raw premultiplied RGBA8 pixels in a one-frame `RenderImage`.
pub fn rgba_to_render_image(w: u32, h: u32, mut rgba: Vec<u8>) -> Arc<RenderImage> {
    debug_assert_eq!(
        rgba.len(),
        (w as usize) * (h as usize) * 4,
        "rgba buffer must be exactly w*h*4 bytes",
    );

    for px in rgba.chunks_exact_mut(4) {
        let a = px[3];
        if a != 0 && a != 255 {
            // Un-premultiply, rounding to nearest.
            for c in 0..3 {
                px[c] = (((px[c] as u32 * 255) + (a as u32 / 2)) / a as u32).min(255) as u8;
            }
        }
        px.swap(0, 2); // RGBA -> BGRA
    }

    let buffer = RgbaImage::from_raw(w, h, rgba).expect("buffer must be w*h*4 bytes");
    Arc::new(RenderImage::new(vec![Frame::new(buffer)]))
}
