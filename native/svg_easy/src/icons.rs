//! The toolbar's glyphs.
//!
//! Geometry comes from the IntelliJ Platform's **expui** set
//! (`platform/icons/src/expui`, Apache-2.0 — the copyright header travels with each file). Expui
//! ships every glyph twice, `foo.svg` inked for light themes and `foo_dark.svg` for dark ones, and
//! both hard-code that ink. These copies are the light-theme files with the colour swapped for
//! `currentColor`, so the toolbar tints them from the theme instead — which is also why dark
//! chrome needs no second copy.
//!
//! The three glyphs JetBrains has no expui equivalent for — the two interaction tools and the
//! transparency chessboard, which the IntelliJ-side editor draws in code — are drawn to the same
//! 16×16 conventions.
//!
//! The application's own mark lives here too ([`app_icon`]), because it comes from the same
//! place: one SVG, rasterised by the engine the app already links against.

use std::sync::Arc;

use gpui_component::Icon;
use image::RgbaImage;

/// A toolbar glyph, resolved to its embedded SVG.
#[derive(Clone, Copy, PartialEq, Debug)]
pub enum ToolbarIcon {
    /// Move / resize / rotate the selection.
    MoveTool,
    /// Rubber-band selection.
    BoxSelect,
    Undo,
    Redo,
    New,
    Open,
    Save,
    ZoomOut,
    ZoomIn,
    /// Zoom to 1 document unit per pixel.
    ActualSize,
    /// Fit the document to the window.
    Fit,
    Grid,
    Chessboard,
    Delete,
    /// Re-indent the document.
    Format,
    /// Configure which SVGO passes run.
    SvgoSettings,
    /// Run SVGO over the document.
    Svgo,
}

impl ToolbarIcon {
    /// The embedded SVG for this glyph.
    fn bytes(self) -> &'static [u8] {
        match self {
            Self::MoveTool => include_bytes!("../assets/icons/tool-move.svg"),
            Self::BoxSelect => include_bytes!("../assets/icons/tool-marquee.svg"),
            Self::Undo => include_bytes!("../assets/icons/undo.svg"),
            Self::Redo => include_bytes!("../assets/icons/redo.svg"),
            Self::New => include_bytes!("../assets/icons/new.svg"),
            Self::Open => include_bytes!("../assets/icons/open.svg"),
            Self::Save => include_bytes!("../assets/icons/save.svg"),
            Self::ZoomOut => include_bytes!("../assets/icons/zoom-out.svg"),
            Self::ZoomIn => include_bytes!("../assets/icons/zoom-in.svg"),
            Self::ActualSize => include_bytes!("../assets/icons/actual-size.svg"),
            Self::Fit => include_bytes!("../assets/icons/fit.svg"),
            Self::Grid => include_bytes!("../assets/icons/grid.svg"),
            Self::Chessboard => include_bytes!("../assets/icons/chessboard.svg"),
            Self::Delete => include_bytes!("../assets/icons/delete.svg"),
            Self::Format => include_bytes!("../assets/icons/format.svg"),
            Self::SvgoSettings => include_bytes!("../assets/icons/svgo-settings.svg"),
            Self::Svgo => include_bytes!("../assets/icons/svgo.svg"),
        }
    }
}

impl From<ToolbarIcon> for Icon {
    fn from(icon: ToolbarIcon) -> Self {
        // `data` embeds the bytes directly, so the app needs no asset bundle registered.
        Icon::default().data(icon.bytes())
    }
}

/// The application's window icon, rasterised from `assets/app-icon.svg`.
///
/// X11 takes a window's icon from `_NET_WM_ICON`, which is set from the window options — so the
/// pixels have to be ready before the window opens. Rendering them here rather than committing a
/// PNG keeps the SVG the single source of truth: the same file is what the Windows build packs
/// into the `.exe` (see `build.rs`), so the title bar, the taskbar and the Alt-Tab switcher all
/// show one mark. Wayland has no window-icon concept and macOS reads the bundle, so there the
/// result is simply unused.
pub fn app_icon() -> Option<Arc<RgbaImage>> {
    /// The largest size the shell is likely to ask for; it scales down cleanly from here.
    const SIZE: u32 = 256;
    const SVG: &str = include_str!("../assets/app-icon.svg");

    let (mut rgba, w, h) = resvg_bridge::session::render_fit_rgba(SVG, SIZE, SIZE).ok()?;

    // tiny-skia hands back premultiplied pixels, while `RgbaImage` — like `_NET_WM_ICON` — is
    // straight alpha. Left alone, the antialiased edges of the tile would come out too dark.
    for px in rgba.chunks_exact_mut(4) {
        let a = px[3];
        if a != 0 && a != 255 {
            for c in 0..3 {
                px[c] = (((px[c] as u32 * 255) + (a as u32 / 2)) / a as u32).min(255) as u8;
            }
        }
    }

    RgbaImage::from_raw(w, h, rgba).map(Arc::new)
}
