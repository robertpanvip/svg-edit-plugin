//! Regenerates the raster forms of `assets/app-icon.svg`.
//!
//! `app-icon.svg` is the source of truth, but Windows needs the icon as a file to embed in the
//! `.exe` (see `build.rs`). This renders the SVG at every size Windows asks for and packs them
//! into a PNG-compressed `.ico`, so the derived artifact can always be rebuilt from the SVG
//! instead of being an unexplained blob:
//!
//! ```text
//! cargo run --example gen-app-icon
//! ```
//!
//! The window icon on X11 needs no artifact — the app rasterises the SVG itself at startup —
//! so this only has to produce the two files below.

use std::fs;
use std::path::Path;

use image::{ImageFormat, ImageBuffer, Rgba, RgbaImage};

/// Sizes Windows picks from, largest first. `_NET_WM_ICON` caps out here too, so this is also what
/// the `.ico` offers the shell for the taskbar and Alt-Tab.
const SIZES: [u32; 7] = [256, 128, 64, 48, 32, 24, 16];

fn main() {
    let assets = Path::new(env!("CARGO_MANIFEST_DIR")).join("assets");
    let svg = fs::read_to_string(assets.join("app-icon.svg")).expect("read app-icon.svg");

    let pngs: Vec<(u32, Vec<u8>)> = SIZES.iter().map(|&s| (s, png(&svg, s))).collect();

    fs::write(assets.join("app-icon.png"), &pngs[0].1).expect("write app-icon.png");
    fs::write(assets.join("app-icon.ico"), ico(&pngs)).expect("write app-icon.ico");

    println!("wrote assets/app-icon.png ({}px) and assets/app-icon.ico", SIZES[0]);
}

/// Renders `svg` at exactly `size`x`size` and PNG-encodes it with straight alpha.
fn png(svg: &str, size: u32) -> Vec<u8> {
    let (mut rgba, w, h) =
        resvg_bridge::session::render_fit_rgba(svg, size, size).expect("render app-icon.svg");

    // tiny-skia hands back premultiplied pixels; both PNG and `RgbaImage` are straight alpha, so
    // a translucent edge would come out too dark without this.
    for px in rgba.chunks_exact_mut(4) {
        let a = px[3];
        if a != 0 && a != 255 {
            for c in 0..3 {
                px[c] = (((px[c] as u32 * 255) + (a as u32 / 2)) / a as u32).min(255) as u8;
            }
        }
    }

    let image: RgbaImage = ImageBuffer::<Rgba<u8>, _>::from_raw(w, h, rgba)
        .expect("render must be exactly w*h*4 bytes");

    let mut out = std::io::Cursor::new(Vec::new());
    image.write_to(&mut out, ImageFormat::Png).expect("encode PNG");
    out.into_inner()
}

/// Packs the PNGs into an `.ico`.
///
/// Every entry is PNG-compressed rather than a BMP: Windows has read PNG payloads since Vista and
/// every size the shell wants is present explicitly, so nothing is resampled. The format is the
/// 6-byte `ICONDIR`, one 16-byte `ICONDIRENTRY` per image, then the payloads themselves.
fn ico(pngs: &[(u32, Vec<u8>)]) -> Vec<u8> {
    let mut out = Vec::new();
    out.extend_from_slice(&0u16.to_le_bytes()); // reserved
    out.extend_from_slice(&1u16.to_le_bytes()); // 1 = icon
    out.extend_from_slice(&(pngs.len() as u16).to_le_bytes());

    let mut offset = 6 + 16 * pngs.len() as u32;
    for (size, png) in pngs {
        // 256 is written as 0: the field is a single byte, and 0 is the format's escape for it.
        let edge = if *size >= 256 { 0 } else { *size as u8 };
        out.push(edge); // width
        out.push(edge); // height
        out.push(0); // palette size (0 = true colour)
        out.push(0); // reserved
        out.extend_from_slice(&1u16.to_le_bytes()); // colour planes
        out.extend_from_slice(&32u16.to_le_bytes()); // bits per pixel
        out.extend_from_slice(&(png.len() as u32).to_le_bytes());
        out.extend_from_slice(&offset.to_le_bytes());
        offset += png.len() as u32;
    }

    for (_, png) in pngs {
        out.extend_from_slice(png);
    }
    out
}
