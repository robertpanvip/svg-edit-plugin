//! Spike: can the standalone SvgEasy app be rebuilt on **GPUI + gpui-component** with no
//! JVM/Kotlin?
//!
//! It intentionally exercises the four things that decide that question:
//!
//! 1. **Bootstrap** — a native window with `gpui_platform::application()` + `gpui_component::init`
//!    and the `Root` wrapper (mandatory first layer).
//! 2. **A real code editor** — `gpui_component::input::Editor` bound to plain text, with a change
//!    event, and **real XML/SVG syntax highlighting**. gpui-component ships no XML grammar, but
//!    `LanguageRegistry::register` accepts any `LanguageConfig`, so `tree-sitter-xml` is wired in
//!    here directly (see `register_xml_language`). This is the escape hatch for grammars the
//!    component library does not bundle.
//! 3. **The design canvas** — the existing Rust engine (`resvg_bridge::session`) renders the SVG
//!    **in-process** (no sidecar, no JSON-RPC) and the pixels are painted with GPUI's low-level
//!    `canvas` + `Window::paint_image`.
//! 4. **Interaction** — pointer drag pans, wheel zooms.
//!
//! Deliberately NOT here (this is a feasibility spike, not the app): selection handles, the
//! document model wiring, background rendering, file open/save, undo.
//!
//! # Building
//!
//! ```text
//! cd native/gpui_spike
//! cargo run            # a GUI window; needs a real display + GPU
//! cargo check          # CI-friendly: type-checks everything, opens no window
//! ```
//!
//! The crate pins its toolchain in `rust-toolchain.toml` — `gpui-pre` 0.3.x uses std-library
//! features that are not stable on older compilers and fails to build below 1.98.
//!
//! # Verification status
//!
//! Checked in a headless CPU-only sandbox. `cargo build` **compiles and links on Linux**, and the
//! binary was actually run against an `Xvfb` display with Mesa's software renderers
//! (`llvmpipe`/`lavapipe`): the window opens, all six editor lines render with their syntax colours,
//! and the canvas shows the three shapes at their exact `#4f46e5`/`#22c55e`/`#f59e0b` fills — which
//! also proves the un-premultiply + channel swap in `to_render_image` is right.
//!
//! What that setup cannot exercise is **input**: there is no pointer or wheel device, so pan/zoom
//! are wired but unverified. They need a real desktop session.
//!
//! Rendering note: a plain software X server renders through `libEGL`/Vulkan with no DRI3, so the
//! console logs `DRI3 error: Could not get DRI3 device` before falling back to software. That is an
//! environment message, not a defect.

use std::sync::Arc;

use gpui::{
    App, Bounds, Context, Corners, Entity, IntoElement, MouseButton, MouseDownEvent,
    MouseMoveEvent, MouseUpEvent, ParentElement, Point, Render, ScrollWheelEvent, Styled,
    Subscription, Window, WindowBounds, WindowOptions, canvas, div, point, prelude::*, px, rgb,
    size,
};
use gpui_component::{
    Root, h_resizable,
    highlighter::{LanguageConfig, LanguageRegistry},
    input::{Editor, EditorState, InputEvent},
    resizable_panel,
};
use gpui_platform::application;
use image::{Frame, RgbaImage};

/// Register the SVG document language that gpui-component does not bundle.
///
/// SVG is XML, and `tree-sitter-xml` gives a grammar plus highlight queries. The registry accepts
/// a fully custom `LanguageConfig`, so no fork of the component library is needed.
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

/// Small stand-in document so the spike draws something without touching the filesystem.
const SAMPLE: &str = r##"<?xml version="1.0" encoding="UTF-8"?>
<!-- SVG Easy: GpUI spike -->
<svg xmlns="http://www.w3.org/2000/svg" width="320" height="200">
  <rect x="10" y="10" width="130" height="80" rx="14" fill="#4f46e5"/>
  <circle cx="235" cy="72" r="52" fill="#22c55e"/>
  <rect x="80" y="112" width="180" height="60" fill="#f59e0b" transform="rotate(8 170 142)"/>
</svg>"##;

/// Render `svg` with the real engine and wrap the pixels for GPUI.
///
/// Two conversions matter and both are easy to get wrong:
/// - `resvg`/`tiny_skia` hand back **premultiplied** RGBA8, while GPUI's `RenderImage` expects
///   **straight** (un-premultiplied) alpha — semi-transparent pixels render too dark otherwise.
/// - `RenderImage` is documented as **BGRA**, not RGBA (`gpui/src/assets.rs`), so R and B are
///   swapped.
fn render_now(svg: &str) -> Result<(Arc<gpui::RenderImage>, u32, u32), String> {
    let (rgba, w, h) = resvg_bridge::session::render_fit_rgba(svg, 0, 0)?;
    Ok((to_render_image(w, h, rgba), w, h))
}

fn to_render_image(w: u32, h: u32, mut rgba: Vec<u8>) -> Arc<gpui::RenderImage> {
    for px in rgba.chunks_exact_mut(4) {
        let a = px[3];
        if a != 0 && a != 255 {
            // un-premultiply (rounded)
            for c in 0..3 {
                px[c] = (((px[c] as u32 * 255) + (a as u32 / 2)) / a as u32).min(255) as u8;
            }
        }
        px.swap(0, 2); // RGBA -> BGRA
    }
    let buffer = RgbaImage::from_raw(w, h, rgba).expect("buffer must be w*h*4 bytes");
    Arc::new(gpui::RenderImage::new(vec![Frame::new(buffer)]))
}

struct SvgSpike {
    editor: Entity<EditorState>,
    image: Option<Arc<gpui::RenderImage>>,
    /// Natural (document) size of the rendered image, in pixels.
    image_size: (f32, f32),
    pan: Point<gpui::Pixels>,
    zoom: f32,
    dragging: bool,
    drag_origin: Point<gpui::Pixels>,
    pan_at_drag_start: Point<gpui::Pixels>,
    error: Option<String>,
    _editor_sub: Subscription,
}

impl SvgSpike {
    fn new(window: &mut Window, cx: &mut Context<Self>) -> Self {
        let editor = cx.new(|cx| EditorState::new(window, cx).language("xml").default_value(SAMPLE));
        // The text -> canvas direction: re-parse and re-render whenever the source changes.
        // (The real app debounces this and re-renders off the UI thread.)
        let editor_sub = cx.subscribe(
            &editor,
            |this: &mut Self, editor: Entity<EditorState>, ev: &InputEvent, cx: &mut Context<Self>| {
                if matches!(ev, InputEvent::Change) {
                    let text = editor.read(cx).value().to_string();
                    this.set_svg(&text);
                    cx.notify();
                }
            },
        );

        let mut this = Self {
            editor,
            image: None,
            image_size: (1.0, 1.0),
            pan: point(px(0.), px(0.)),
            zoom: 1.0,
            dragging: false,
            drag_origin: point(px(0.), px(0.)),
            pan_at_drag_start: point(px(0.), px(0.)),
            error: None,
            _editor_sub: editor_sub,
        };
        this.set_svg(SAMPLE);
        this
    }

    /// Re-render from `svg` and swap in the new raster.
    fn set_svg(&mut self, svg: &str) {
        match render_now(svg) {
            Ok((image, w, h)) => {
                self.image = Some(image);
                self.image_size = (w as f32, h as f32);
                self.error = None;
            }
            Err(e) => {
                self.image = None;
                self.error = Some(e);
            }
        }
    }
}

impl Render for SvgSpike {
    fn render(&mut self, _window: &mut Window, cx: &mut Context<Self>) -> impl IntoElement {
        // The paint closure must be 'static, so everything it needs is captured up front.
        let image = self.image.clone();
        let (iw, ih) = self.image_size;
        let pan = self.pan;
        let zoom = self.zoom;
        let error = self.error.clone();

        let canvas_view = div()
            .size_full()
            .bg(rgb(0x1e1e1e))
            .child(
                canvas(
                    |_, _, _| (),
                    move |bounds, _, window, _cx| {
                        let Some(img) = image.as_ref() else {
                            return;
                        };
                        let avail_w = bounds.size.width.as_f32();
                        let avail_h = bounds.size.height.as_f32();
                        // Fit the document into the viewport, then apply the zoom factor.
                        let fit = (avail_w / iw).min(avail_h / ih).max(0.0) * zoom;
                        let dw = iw * fit;
                        let dh = ih * fit;
                        let origin = point(
                            px(bounds.origin.x.as_f32() + (avail_w - dw) / 2.0 + pan.x.as_f32()),
                            px(bounds.origin.y.as_f32() + (avail_h - dh) / 2.0 + pan.y.as_f32()),
                        );
                        let image_bounds = Bounds {
                            origin,
                            size: size(px(dw), px(dh)),
                        };
                        let _ = window.paint_image(
                            bounds,
                            image_bounds,
                            Corners::default(),
                            img.clone(),
                            0,
                            false,
                        );
                    },
                )
                .size_full(),
            )
            .on_mouse_down(
                MouseButton::Left,
                cx.listener(|this, ev: &MouseDownEvent, _window, cx| {
                    this.dragging = true;
                    this.drag_origin = ev.position;
                    this.pan_at_drag_start = this.pan;
                    cx.notify();
                }),
            )
            .on_mouse_move(cx.listener(|this, ev: &MouseMoveEvent, _window, cx| {
                if !this.dragging {
                    return;
                }
                this.pan = this.pan_at_drag_start + (ev.position - this.drag_origin);
                cx.notify();
            }))
            .on_mouse_up(
                MouseButton::Left,
                cx.listener(|this, _ev: &MouseUpEvent, _window, cx| {
                    this.dragging = false;
                    cx.notify();
                }),
            )
            .on_scroll_wheel(cx.listener(|this, ev: &ScrollWheelEvent, _window, cx| {
                let dy = ev.delta.pixel_delta(px(20.)).y.as_f32();
                this.zoom = (this.zoom * (1.0 - dy * 0.002)).clamp(0.1, 8.0);
                cx.notify();
            }));

        let right = div()
            .size_full()
            .relative()
            .child(canvas_view)
            .when_some(error, |el, msg| {
                el.child(
                    div()
                        .absolute()
                        .top_2()
                        .left_2()
                        .px_2()
                        .bg(rgb(0x7f1d1d))
                        .text_color(rgb(0xffffff))
                        .child(format!("parse error: {msg}")),
                )
            });

        h_resizable("svg-spike-split")
            .child(
                resizable_panel()
                    .size(px(460.))
                    // The editor does not stretch on its own: inside a flex column it would collapse
                    // to a single line, so the full height has to be requested explicitly.
                    .child(div().size_full().child(Editor::new(&self.editor).h_full())),
            )
            .child(resizable_panel().child(right))
    }
}

fn main() {
    application().run(|cx: &mut App| {
        // Must run before any gpui-component widget is built (initializes the theme, Root
        // machinery, input, dock, ...).
        gpui_component::init(cx);
        register_xml_language();

        let bounds = Bounds::centered(None, size(px(1200.), px(820.)), cx);
        cx.open_window(
            WindowOptions {
                window_bounds: Some(WindowBounds::Windowed(bounds)),
                ..Default::default()
            },
            |window, cx| {
                let view = cx.new(|cx| SvgSpike::new(window, cx));
                // The window's first layer must be `Root` or gpui-component panics.
                cx.new(|cx| Root::new(view, window, cx))
            },
        )
        .expect("failed to open the spike window");

        cx.activate(true);
    });
}
