//! SVG Easy — a standalone SVG editor: one native binary, no JVM, no IDE host process.
//!
//! The shape of it:
//!
//! - **`document`** owns the document. The SVG *source text* is the single source of truth; the
//!   renderer's DOM is parsed from it and every edit is written back into it, so the XML pane
//!   always shows exactly what will be saved.
//! - **`canvas`** paints the rendered document and turns mouse and keyboard input into selection,
//!   pan, zoom and move edits.
//! - **`app`** is the shell: toolbar, split layout, and the two-way sync between the panes.
//! - **`image_conv`** adapts the renderer's pixel format to GPUI's.
//! - **`resvg_bridge`** (a sibling crate, shared with the IntelliJ plugin) does the actual
//!   rendering, hit-testing and document editing. It is linked in-process: no sidecar, no
//!   JSON-RPC.
//!
//! # Running
//!
//! ```text
//! cargo run                     # opens the built-in sample
//! cargo run -- path/to/file.svg # opens a file (Ctrl+S writes it back)
//! cargo test                    # document model + engine unit tests
//! cargo check                   # CI-friendly: type-checks, opens no window
//! ```
//!
//! The toolchain is pinned in `rust-toolchain.toml` — `gpui-pre` 0.3.x needs Rust >= 1.98.

mod app;
mod canvas;
mod document;
mod image_conv;
mod theme;

use std::path::PathBuf;

use gpui::{App, AppContext as _, Bounds, WindowBounds, WindowOptions, px, size};
use gpui_component::highlighter::{LanguageConfig, LanguageRegistry};
use gpui_platform::application;

use app::SvgEasyApp;

/// Registers the SVG/XML grammar that gpui-component does not bundle.
///
/// SVG is XML, and the component library ships a fixed grammar set without it — but
/// `LanguageRegistry` accepts any `LanguageConfig`, so `tree-sitter-xml` plugs straight in. No
/// fork of the component library is needed.
fn register_xml_language() {
    LanguageRegistry::singleton().register(
        "xml",
        &LanguageConfig::new(
            "xml",
            tree_sitter_xml::LANGUAGE_XML.into(),
            vec![],
            tree_sitter_xml::XML_HIGHLIGHT_QUERY,
            "",
            "",
        ),
    );
}

fn main() {
    // Optional: the document to open. Without one the app shows its built-in sample.
    let path = std::env::args_os().nth(1).map(PathBuf::from);

    application().run(move |cx: &mut App| {
        // Must run before any gpui-component widget is built (theme, Root machinery, input, ...).
        gpui_component::init(cx);
        register_xml_language();

        let bounds = Bounds::centered(None, size(px(1280.), px(860.)), cx);
        let open = path.clone();
        cx.open_window(
            WindowOptions {
                window_bounds: Some(WindowBounds::Windowed(bounds)),
                ..Default::default()
            },
            move |window, cx| {
                let view = cx.new(|cx| SvgEasyApp::new(open, window, cx));
                // The window's first layer must be `Root`, or gpui-component panics.
                cx.new(|cx| gpui_component::Root::new(view, window, cx))
            },
        )
        .expect("failed to open the editor window");

        cx.activate(true);
    });
}
