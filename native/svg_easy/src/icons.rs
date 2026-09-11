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

use gpui_component::Icon;

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
        }
    }
}

impl From<ToolbarIcon> for Icon {
    fn from(icon: ToolbarIcon) -> Self {
        // `data` embeds the bytes directly, so the app needs no asset bundle registered.
        Icon::default().data(icon.bytes())
    }
}
