//! The editor's palette, in one place so the toolbar, canvas and overlay agree.

use gpui::{Hsla, rgb};

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

/// Parse-error banner.
pub fn error_bg() -> Hsla {
    rgb(0x7f1d1d).into()
}

pub fn error_fg() -> Hsla {
    rgb(0xfecaca).into()
}
