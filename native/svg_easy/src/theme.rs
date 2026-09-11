//! The editor's palette, in one place so the toolbar, canvas and overlay agree.

use gpui::{Hsla, rgb, rgba};

/// Backdrop behind the document.
pub fn canvas_bg() -> Hsla {
    rgb(0x1e1e1e).into()
}

/// Toolbar strip.
pub fn chrome_bg() -> Hsla {
    rgb(0x252526).into()
}

/// Selection outline and handles.
pub fn selection() -> Hsla {
    rgb(0x2f81f7).into()
}

/// The darker squares of the transparency chessboard. The lighter ones are the canvas backdrop
/// showing through, so "transparent" reads as "nothing was painted here".
pub fn chess_dark() -> Hsla {
    rgb(0x333333).into()
}

/// Document-unit grid lines over the image.
pub fn grid_line() -> Hsla {
    rgba(0xffffff1f).into()
}

/// Fill of the rubber-band selection rectangle.
pub fn band_fill() -> Hsla {
    rgba(0x2f81f73d).into()
}

/// Parse-error banner.
pub fn error_bg() -> Hsla {
    rgb(0x7f1d1d).into()
}

pub fn error_fg() -> Hsla {
    rgb(0xfecaca).into()
}
