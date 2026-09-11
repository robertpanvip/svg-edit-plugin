//! The design canvas: paints the rendered document and turns mouse / keyboard input into
//! selection, pan, zoom and move edits.
//!
//! # Coordinates
//!
//! Two spaces are in play:
//!
//! - **doc** — SVG user units, origin at the document's top-left.
//! - **screen** — window pixels.
//!
//! They are related by `screen = base(bounds, scale) + pan + doc * scale`, where `base` centres the
//! document in the viewport. [`Mapping`] is the only thing that converts between them.
//!
//! # Why the raster lives in a `RefCell`
//!
//! The viewport size is only known inside the paint closures, but the raster resolution depends on
//! it (a document fitted into a 300px panel needs fewer pixels than the same document in a
//! 1200px one). So the resolution decision is made in `prepaint` — which *does* receive the final
//! bounds — and the result is cached in [`CanvasShared`] so the raster survives between frames.
//! The same cache hands the last [`Mapping`] to the mouse handlers, which run outside paint.

use std::cell::RefCell;
use std::rc::Rc;
use std::sync::Arc;

use gpui::{
    BorderStyle, Bounds, Context, Corners, IntoElement, KeyDownEvent, MouseButton, MouseDownEvent,
    MouseMoveEvent, MouseUpEvent, ParentElement, Pixels, Point, RenderImage, ScrollWheelEvent,
    Styled, Window, canvas, div, point, prelude::*, px, quad, size, transparent_black,
};
use resvg_bridge::session::render_scaled_rgba;

use crate::app::SvgEasyApp;
use crate::image_conv::rgba_to_render_image;
use crate::theme;

/// Screen-pixel slop when hit-testing a shape, so thin strokes are still clickable.
const HIT_SLOP_PX: f64 = 4.0;
/// A press that moves less than this is a click, not a drag.
const DRAG_THRESHOLD_PX: f32 = 2.0;

/// Document units <-> window pixels.
#[derive(Clone, Copy, Debug)]
pub struct Mapping {
    /// Pixels per document unit.
    pub scale: f32,
    /// Where the document's origin lands on screen.
    pub origin: Point<Pixels>,
}

impl Mapping {
    pub fn doc_to_screen(&self, x: f64, y: f64) -> Point<Pixels> {
        point(
            px(self.origin.x.as_f32() + x as f32 * self.scale),
            px(self.origin.y.as_f32() + y as f32 * self.scale),
        )
    }

    pub fn screen_to_doc(&self, p: Point<Pixels>) -> (f64, f64) {
        let s = if self.scale.abs() < 1e-6 { 1.0 } else { self.scale };
        (
            ((p.x.as_f32() - self.origin.x.as_f32()) / s) as f64,
            ((p.y.as_f32() - self.origin.y.as_f32()) / s) as f64,
        )
    }

    /// Where the raster is drawn on screen.
    pub fn image_bounds(&self, doc: (f64, f64)) -> Bounds<Pixels> {
        Bounds {
            origin: self.origin,
            size: size(px(doc.0 as f32 * self.scale), px(doc.1 as f32 * self.scale)),
        }
    }
}

/// How the document sits in the viewport, plus any in-flight gesture.
pub struct View {
    /// Zoom relative to "fit": 1.0 means the document exactly fills the viewport.
    pub zoom: f32,
    /// Offset applied after centring, in window pixels.
    pub pan: Point<Pixels>,
    pub drag: Option<Drag>,
}

#[derive(Clone, Copy)]
pub enum Drag {
    /// Panning; the gesture started over empty canvas.
    Pan {
        from: Point<Pixels>,
        pan_at_start: Point<Pixels>,
    },
    /// Moving the selection. `delta` is in window pixels (which is also how the overlay offsets
    /// itself), so the committed document-space delta is derived at release time.
    Move {
        from: Point<Pixels>,
        delta: Point<Pixels>,
    },
}

impl View {
    pub fn new() -> Self {
        Self {
            zoom: 1.0,
            pan: point(px(0.), px(0.)),
            drag: None,
        }
    }

    pub fn reset(&mut self) {
        self.zoom = 1.0;
        self.pan = point(px(0.), px(0.));
    }
}

/// Pixels per document unit for the current viewport and zoom.
fn view_scale(bounds: Bounds<Pixels>, doc: (f64, f64), zoom: f32) -> f32 {
    let avail_w = bounds.size.width.as_f32().max(1.0);
    let avail_h = bounds.size.height.as_f32().max(1.0);
    let fit = (avail_w / (doc.0.max(1e-6) as f32)).min(avail_h / (doc.1.max(1e-6) as f32));
    (fit * zoom).clamp(1e-4, 64.0)
}

/// Where the *centred* document origin would sit, before `pan` is applied.
fn base_origin(bounds: Bounds<Pixels>, doc: (f64, f64), scale: f32) -> (f32, f32) {
    (
        bounds.origin.x.as_f32() + (bounds.size.width.as_f32() - doc.0 as f32 * scale) / 2.0,
        bounds.origin.y.as_f32() + (bounds.size.height.as_f32() - doc.1 as f32 * scale) / 2.0,
    )
}

pub fn mapping(
    bounds: Bounds<Pixels>,
    doc: (f64, f64),
    zoom: f32,
    pan: Point<Pixels>,
) -> Mapping {
    let scale = view_scale(bounds, doc, zoom);
    let (bx, by) = base_origin(bounds, doc, scale);
    Mapping {
        scale,
        origin: point(px(bx + pan.x.as_f32()), px(by + pan.y.as_f32())),
    }
}

/// Quantises a scale to half-octave steps.
///
/// A zoom gesture then re-renders a handful of times instead of on every wheel tick, while the
/// raster is never undersampled by more than sqrt(2) — which is around the point where the GPU's
/// bilinear upscale stops being visible.
pub fn bucket_scale(scale: f32) -> f32 {
    let s = scale.clamp(1.0 / 16.0, 16.0);
    2.0f32.powf((s.log2() / 0.5).round() * 0.5)
}

/// The rendered raster plus what it was rendered from.
struct Raster {
    /// Document revision the pixels came from.
    revision: u64,
    /// [`bucket_scale`] bits this raster was rendered at.
    bucket: u32,
    image: Arc<RenderImage>,
}

/// State shared between the paint closures and the input handlers.
#[derive(Default)]
pub struct CanvasShared {
    /// Mapping used by the most recent frame — how input finds its way back to document space.
    pub mapping: Option<Mapping>,
    /// Viewport used by the most recent frame, for zoom-about-the-cursor arithmetic.
    pub bounds: Option<Bounds<Pixels>>,
    raster: Option<Raster>,
    /// A `(revision, bucket)` whose render already failed. Without this, an unparseable document
    /// would re-attempt (and fail) a render on every frame while the user types.
    failed: Option<(u64, u32)>,
}

pub type Shared = Rc<RefCell<CanvasShared>>;

pub fn new_shared() -> Shared {
    Rc::new(RefCell::new(CanvasShared::default()))
}

impl SvgEasyApp {
    /// The canvas element: raster + selection overlay, with every input handler attached.
    pub fn canvas_view(&mut self, cx: &mut Context<Self>) -> impl IntoElement {
        let doc = self.editor.doc_size();
        let zoom = self.view.zoom;
        let pan = self.view.pan;
        let revision = self.source_revision;
        let shared = self.shared.clone();
        // Owned copies so the closures can be `'static`: the paint closures outlive this borrow.
        let source: Arc<str> = Arc::from(self.editor.source.as_str());
        let selection_doc = self.editor.selection_boxes();
        let drag_offset = match self.view.drag {
            Some(Drag::Move { delta, .. }) => delta,
            _ => point(px(0.), px(0.)),
        };

        let raster = canvas(
            move |bounds, _window, _cx| {
                let m = mapping(bounds, doc, zoom, pan);
                let bucket = bucket_scale(m.scale);
                let bucket_bits = bucket.to_bits();

                let image = {
                    let mut state = shared.borrow_mut();
                    let stale = state
                        .raster
                        .as_ref()
                        .map_or(true, |r| r.revision != revision || r.bucket != bucket_bits);
                    let already_failed = state.failed == Some((revision, bucket_bits));

                    if stale && !already_failed {
                        match render_scaled_rgba(&source, bucket as f64) {
                            Ok((rgba, w, h)) => {
                                state.raster = Some(Raster {
                                    revision,
                                    bucket: bucket_bits,
                                    image: rgba_to_render_image(w, h, rgba),
                                });
                                state.failed = None;
                            }
                            // Unparseable source: keep the last good frame on screen; the banner
                            // in the app explains why nothing is updating.
                            Err(_) => state.failed = Some((revision, bucket_bits)),
                        }
                    }

                    state.mapping = Some(m);
                    state.bounds = Some(bounds);
                    state.raster.as_ref().map(|r| r.image.clone())
                };

                let selection: Vec<Bounds<Pixels>> = selection_doc
                    .iter()
                    .map(|b| {
                        let top_left = m.doc_to_screen(b[0], b[1]);
                        Bounds {
                            origin: point(top_left.x + drag_offset.x, top_left.y + drag_offset.y),
                            size: size(px(b[2] as f32 * m.scale), px(b[3] as f32 * m.scale)),
                        }
                    })
                    .collect();

                (m, image, selection)
            },
            move |bounds, (m, image, selection), window, _cx| {
                if let Some(image) = image {
                    let _ = window.paint_image(
                        bounds,
                        m.image_bounds(doc),
                        Corners::default(),
                        image,
                        0,
                        false,
                    );
                }

                for rect in &selection {
                    // Outline only: a filled overlay would hide the shape being edited.
                    window.paint_quad(quad(
                        *rect,
                        Corners::default(),
                        transparent_black(),
                        px(1.5),
                        theme::selection(),
                        BorderStyle::Solid,
                    ));

                    // Corner handles, so the box reads as a selection rather than an artefact.
                    for (x, y) in [
                        (rect.origin.x, rect.origin.y),
                        (rect.origin.x + rect.size.width, rect.origin.y),
                        (rect.origin.x, rect.origin.y + rect.size.height),
                        (rect.origin.x + rect.size.width, rect.origin.y + rect.size.height),
                    ] {
                        window.paint_quad(gpui::fill(
                            Bounds {
                                origin: point(x - px(HANDLE / 2.0), y - px(HANDLE / 2.0)),
                                size: size(px(HANDLE), px(HANDLE)),
                            },
                            theme::selection(),
                        ));
                    }
                }
            },
        )
        .size_full();

        div()
            .size_full()
            .bg(theme::canvas_bg())
            // Focusable so Delete / Escape / Ctrl+S land here after a click.
            .track_focus(&self.focus_handle)
            .on_mouse_down(MouseButton::Left, cx.listener(Self::on_canvas_down))
            .on_mouse_move(cx.listener(Self::on_canvas_move))
            .on_mouse_up(MouseButton::Left, cx.listener(Self::on_canvas_up))
            .on_scroll_wheel(cx.listener(Self::on_canvas_wheel))
            .on_key_down(cx.listener(Self::on_canvas_key))
            .child(raster)
    }

    fn on_canvas_down(&mut self, ev: &MouseDownEvent, window: &mut Window, cx: &mut Context<Self>) {
        // Taking focus here is what makes the keyboard shortcuts work; it also drops focus from
        // the XML pane so typing does not fight the canvas.
        let handle = self.focus_handle.clone();
        window.focus(&handle, cx);

        let Some(m) = self.mapping() else {
            return;
        };
        let (x, y) = m.screen_to_doc(ev.position);
        let tolerance = HIT_SLOP_PX / m.scale.max(1e-6) as f64;

        match self.editor.hit(x, y, tolerance) {
            Some(node) => {
                if ev.modifiers.shift {
                    self.editor.toggle(node);
                } else if !self.editor.selection.contains(&node) {
                    // Clicking inside an existing multi-selection keeps it, so a group can be
                    // dragged as a unit.
                    self.editor.select_only(node);
                }
                self.view.drag = Some(Drag::Move {
                    from: ev.position,
                    delta: point(px(0.), px(0.)),
                });
            }
            None => {
                if !ev.modifiers.shift {
                    self.editor.clear_selection();
                }
                self.view.drag = Some(Drag::Pan {
                    from: ev.position,
                    pan_at_start: self.view.pan,
                });
            }
        }
        cx.notify();
    }

    fn on_canvas_move(&mut self, ev: &MouseMoveEvent, _window: &mut Window, cx: &mut Context<Self>) {
        match self.view.drag {
            Some(Drag::Pan { from, pan_at_start }) => {
                self.view.pan = pan_at_start + (ev.position - from);
                cx.notify();
            }
            Some(Drag::Move { from, .. }) => {
                self.view.drag = Some(Drag::Move {
                    from,
                    delta: ev.position - from,
                });
                cx.notify();
            }
            None => {}
        }
    }

    fn on_canvas_up(&mut self, _ev: &MouseUpEvent, window: &mut Window, cx: &mut Context<Self>) {
        let Some(drag) = self.view.drag.take() else {
            return;
        };
        match drag {
            Drag::Pan { .. } => cx.notify(),
            Drag::Move { delta, .. } => {
                let moved = delta.x.as_f32().abs() >= DRAG_THRESHOLD_PX
                    || delta.y.as_f32().abs() >= DRAG_THRESHOLD_PX;
                let Some(m) = self.mapping() else {
                    cx.notify();
                    return;
                };
                if !moved {
                    // A plain click: selection already happened on mouse-down.
                    cx.notify();
                    return;
                }
                let scale = m.scale.max(1e-6) as f64;
                if self
                    .editor
                    .translate_selection(delta.x.as_f32() as f64 / scale, delta.y.as_f32() as f64 / scale)
                {
                    self.after_document_edit(window, cx);
                } else {
                    cx.notify();
                }
            }
        }
    }

    fn on_canvas_wheel(
        &mut self,
        ev: &ScrollWheelEvent,
        _window: &mut Window,
        cx: &mut Context<Self>,
    ) {
        let (Some(m), Some(bounds)) = (self.mapping(), self.bounds()) else {
            return;
        };
        let doc = self.editor.doc_size();
        let dy = ev.delta.pixel_delta(px(20.)).y.as_f32();
        if dy == 0.0 {
            return;
        }

        let zoom = (self.view.zoom * (1.0 - dy * 0.002)).clamp(0.05, 32.0);
        if (zoom - self.view.zoom).abs() < 1e-6 {
            return;
        }

        // Keep the document point under the cursor pinned while zooming: solve
        // `cursor = base(new_scale) + pan_new + doc * new_scale` for `pan_new`.
        let (doc_x, doc_y) = m.screen_to_doc(ev.position);
        let scale = view_scale(bounds, doc, zoom);
        let (bx, by) = base_origin(bounds, doc, scale);
        self.view.pan = point(
            px(ev.position.x.as_f32() - bx - doc_x as f32 * scale),
            px(ev.position.y.as_f32() - by - doc_y as f32 * scale),
        );
        self.view.zoom = zoom;
        cx.notify();
    }

    fn on_canvas_key(&mut self, ev: &KeyDownEvent, window: &mut Window, cx: &mut Context<Self>) {
        let key = ev.keystroke.key.as_str();
        let command = ev.keystroke.modifiers.secondary();

        match (command, key) {
            (false, "delete") | (false, "backspace") => {
                if self.editor.delete_selection() {
                    self.after_document_edit(window, cx);
                }
            }
            (false, "escape") => {
                self.editor.clear_selection();
                cx.notify();
            }
            (true, "s") => self.save(window, cx),
            (true, "0") => {
                self.view.reset();
                cx.notify();
            }
            (true, "=") | (true, "+") => {
                self.view.zoom = (self.view.zoom * 1.25).clamp(0.05, 32.0);
                cx.notify();
            }
            (true, "-") => {
                self.view.zoom = (self.view.zoom * 0.8).clamp(0.05, 32.0);
                cx.notify();
            }
            _ => {}
        }
    }
}

/// Selection handle side, in pixels.
const HANDLE: f32 = 7.0;
