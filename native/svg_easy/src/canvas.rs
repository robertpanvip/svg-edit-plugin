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
    Bounds, Context, Corners, CursorStyle, IntoElement, KeyDownEvent, MouseButton, MouseDownEvent,
    MouseMoveEvent, MouseUpEvent, ParentElement, PathBuilder, Pixels, Point, RenderImage,
    ScrollWheelEvent, Styled, Window, canvas, div, point, prelude::*, px, size,
};
use gpui_component::menu::ContextMenuExt;
use resvg_bridge::geom::Mat;
use resvg_bridge::session::{DragLayers, render_scaled_rgba};

use crate::app::SvgEasyApp;
use crate::image_conv::rgba_to_render_image;
use crate::theme;

/// Screen-pixel slop when hit-testing a shape, so thin strokes are still clickable.
const HIT_SLOP_PX: f64 = 4.0;
/// A press that moves less than this is a click, not a drag.
const DRAG_THRESHOLD_PX: f32 = 2.0;
/// Side of a selection grip, in pixels.
const HANDLE: f32 = 7.0;
/// How far the rotate grip floats above the frame's top edge.
const ROTATE_OFFSET: f32 = 22.0;
/// Smallest factor a grip drag may produce, so the matrix never becomes singular.
const MIN_SCALE: f32 = 0.01;
/// Width of the selection frame's outline.
const FRAME_STROKE: f32 = 1.5;
/// Side of one transparency-chessboard square, in screen pixels. Fixed, like the image viewer's:
/// the board marks "nothing is painted here", not a document-space measurement.
const CHESS_CELL: f32 = 12.0;
/// Smallest on-screen spacing the document grid is drawn at. Below this the lines merge into a
/// grey wash, so the grid steps up to the next round number instead.
const GRID_MIN_SPACING: f32 = 8.0;
/// Document-unit spacings the grid snaps to, so the lines stay on round numbers while zooming.
const GRID_STEPS: [f64; 12] = [
    1.0, 2.0, 5.0, 10.0, 20.0, 50.0, 100.0, 200.0, 500.0, 1000.0, 2000.0, 5000.0,
];

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
    /// What a plain drag on the canvas does.
    pub tool: Tool,
    /// Draw the document-unit grid over the image.
    pub grid: bool,
    /// Draw the transparency chessboard behind the image.
    pub chessboard: bool,
    /// What the pointer is currently over, as a cursor. Kept here rather than recomputed during
    /// paint: the answer only changes when the pointer crosses a grip, so the canvas repaints then
    /// and not on every one of the mouse's moves.
    pub hover_cursor: CursorStyle,
}

/// What a drag on the canvas means.
///
/// The two are mutually exclusive, the way the IntelliJ-side editor's toolbar presents them: with
/// [`Tool::Move`] the pointer works on shapes, with [`Tool::Marquee`] it only draws a selection
/// band.
#[derive(Clone, Copy, PartialEq, Debug, Default)]
pub enum Tool {
    /// Select, move, resize and rotate; a drag over empty canvas pans.
    #[default]
    Move,
    /// Rubber-band selection; a drag anywhere draws the band.
    Marquee,
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
    /// Dragging one of the eight scale grips. `frame` is the selection box as it was when the
    /// gesture started and `cursor` is the live pointer, from which the live box is derived.
    Scale {
        grip: Grip,
        frame: [f64; 4],
        from: Point<Pixels>,
        cursor: Point<Pixels>,
    },
    /// Dragging the rotate grip. `start` is the angle from `center` to the cursor on mouse-down.
    Rotate {
        center: (f64, f64),
        start: f32,
        from: Point<Pixels>,
        cursor: Point<Pixels>,
    },
    /// Drawing a selection band. Both points are window pixels so the band tracks the pointer
    /// without a document-space round trip on every move.
    Marquee {
        from: Point<Pixels>,
        cursor: Point<Pixels>,
    },
}

/// A scale grip, named for the side of the frame it sits on.
#[derive(Clone, Copy, PartialEq, Debug)]
pub enum Grip {
    Nw,
    N,
    Ne,
    E,
    Se,
    S,
    Sw,
    W,
}

impl Grip {
    /// Where the grip sits inside the frame, as fractions: 0 is the left/top edge, 1 the
    /// right/bottom.
    fn unit(self) -> (f32, f32) {
        match self {
            Grip::Nw => (0.0, 0.0),
            Grip::N => (0.5, 0.0),
            Grip::Ne => (1.0, 0.0),
            Grip::E => (1.0, 0.5),
            Grip::Se => (1.0, 1.0),
            Grip::S => (0.5, 1.0),
            Grip::Sw => (0.0, 1.0),
            Grip::W => (0.0, 0.5),
        }
    }

    /// The point the grip holds still: the opposite corner, or the opposite edge's middle.
    fn anchor(self) -> (f32, f32) {
        let (x, y) = self.unit();
        (1.0 - x, 1.0 - y)
    }

    /// Corner grips scale both axes; edge grips scale only the one they sit on.
    fn scales(self) -> (bool, bool) {
        let (x, y) = self.unit();
        (x != 0.5, y != 0.5)
    }
}

/// What the cursor grabbed on mouse-down.
#[derive(Clone, Copy, PartialEq, Debug)]
pub enum Handle {
    Scale(Grip),
    Rotate,
}

/// The frame's four corners in screen pixels, clockwise from the top-left.
fn corners_of(m: &Mapping, r: [f64; 4]) -> [Point<Pixels>; 4] {
    [
        m.doc_to_screen(r[0], r[1]),
        m.doc_to_screen(r[0] + r[2], r[1]),
        m.doc_to_screen(r[0] + r[2], r[1] + r[3]),
        m.doc_to_screen(r[0], r[1] + r[3]),
    ]
}

/// The eight scale grips followed by the rotate grip, all in screen pixels.
fn grips(corners: &[Point<Pixels>; 4]) -> [(Handle, Point<Pixels>); 9] {
    let mid = |a: Point<Pixels>, b: Point<Pixels>| {
        point(
            px((a.x.as_f32() + b.x.as_f32()) / 2.0),
            px((a.y.as_f32() + b.y.as_f32()) / 2.0),
        )
    };
    let [c0, c1, c2, c3] = *corners;
    let top = mid(c0, c1);
    let centre = mid(c0, c2);
    // The stem points away from the frame, whichever way the frame is rotated.
    let (ex, ey) = (
        c1.x.as_f32() - c0.x.as_f32(),
        c1.y.as_f32() - c0.y.as_f32(),
    );
    let len = (ex * ex + ey * ey).sqrt().max(1e-6);
    let (mut nx, mut ny) = (-ey / len, ex / len);
    if nx * (top.x.as_f32() - centre.x.as_f32()) + ny * (top.y.as_f32() - centre.y.as_f32()) < 0.0 {
        nx = -nx;
        ny = -ny;
    }
    [
        (Handle::Scale(Grip::Nw), c0),
        (Handle::Scale(Grip::N), top),
        (Handle::Scale(Grip::Ne), c1),
        (Handle::Scale(Grip::E), mid(c1, c2)),
        (Handle::Scale(Grip::Se), c2),
        (Handle::Scale(Grip::S), mid(c2, c3)),
        (Handle::Scale(Grip::Sw), c3),
        (Handle::Scale(Grip::W), mid(c3, c0)),
        (
            Handle::Rotate,
            point(
                px(top.x.as_f32() + nx * ROTATE_OFFSET),
                px(top.y.as_f32() + ny * ROTATE_OFFSET),
            ),
        ),
    ]
}

/// The grip under `p`, if any.
///
/// Grips are tested before the shapes underneath: they sit on the frame's edge, so a shape that
/// fills the frame would otherwise swallow every press meant for one.
fn grip_at(corners: &[Point<Pixels>; 4], p: Point<Pixels>) -> Option<Handle> {
    let reach = HANDLE / 2.0 + HIT_SLOP_PX as f32;
    grips(corners)
        .into_iter()
        .find(|(_, at)| {
            (at.x.as_f32() - p.x.as_f32()).abs() <= reach
                && (at.y.as_f32() - p.y.as_f32()).abs() <= reach
        })
        .map(|(handle, _)| handle)
}

/// The cursor to show while the pointer sits at `p`.
///
/// Mirrors what a press at `p` would do, so the pointer promises exactly what it delivers: in
/// [`Tool::Marquee`] every press draws a band, in [`Tool::Move`] a grip resizes or rotates, and
/// anywhere else the press is the plain arrow's business.
fn cursor_for(
    tool: Tool,
    frame: Option<[f64; 4]>,
    m: &Mapping,
    p: Point<Pixels>,
) -> CursorStyle {
    if tool == Tool::Marquee {
        return CursorStyle::Crosshair;
    }
    match frame.and_then(|frame| grip_at(&corners_of(m, frame), p)) {
        Some(Handle::Scale(grip)) => grip_cursor(grip),
        // A rotate handle is not a resize, and no cursor says "turn"; a pointing hand at least
        // says the dot is grabbable.
        Some(Handle::Rotate) => CursorStyle::PointingHand,
        None => CursorStyle::Arrow,
    }
}

/// The resize cursor for a scale grip: each one pulls along its own edge or diagonal.
///
/// The diagonals are named for the corners they join, which is also how X11 and CSS name them —
/// `UpLeftDownRight` is the `\` of the north-west and south-east corners.
fn grip_cursor(grip: Grip) -> CursorStyle {
    match grip {
        Grip::Nw | Grip::Se => CursorStyle::ResizeUpLeftDownRight,
        Grip::Ne | Grip::Sw => CursorStyle::ResizeUpRightDownLeft,
        Grip::N | Grip::S => CursorStyle::ResizeUpDown,
        Grip::E | Grip::W => CursorStyle::ResizeLeftRight,
    }
}

/// A point inside the frame, given its position as fractions of the width/height.
fn point_in_frame(frame: [f64; 4], unit: (f32, f32)) -> (f64, f64) {
    (
        frame[0] + unit.0 as f64 * frame[2],
        frame[1] + unit.1 as f64 * frame[3],
    )
}

/// The live scale factors for a grip drag, from the frozen frame and the cursor.
///
/// The grip holds its anchor still, so each factor is the cursor's distance from the anchor over
/// the grip's original distance from it. Edge grips leave the perpendicular axis alone, and the
/// floor keeps a dragged-past-the-anchor frame from collapsing to a singular matrix.
fn scale_factors(grip: Grip, frame: [f64; 4], cursor: (f64, f64)) -> (f32, f32) {
    let anchor = point_in_frame(frame, grip.anchor());
    let held = point_in_frame(frame, grip.unit());
    let factor = |c: f64, a: f64, h: f64| {
        let span = h - a;
        if span.abs() < 1e-9 {
            1.0
        } else {
            (c - a) as f32 / span as f32
        }
    };
    let (on_x, on_y) = grip.scales();
    (
        if on_x {
            factor(cursor.0, anchor.0, held.0).max(MIN_SCALE)
        } else {
            1.0
        },
        if on_y {
            factor(cursor.1, anchor.1, held.1).max(MIN_SCALE)
        } else {
            1.0
        },
    )
}

/// The frame as it stands mid-scale: the anchor stays put and the rest follows the factors.
fn scaled_frame(grip: Grip, frame: [f64; 4], sx: f32, sy: f32) -> [f64; 4] {
    let (ax, ay) = grip.anchor();
    [
        frame[0] + ax as f64 * frame[2] * (1.0 - sx as f64),
        frame[1] + ay as f64 * frame[3] * (1.0 - sy as f64),
        frame[2] * sx as f64,
        frame[3] * sy as f64,
    ]
}

/// The angle from `from` to `to`, in radians. Document space is y-down, like the screen, so this
/// is the same angle the user sees.
fn angle_between(from: (f64, f64), to: (f64, f64)) -> f32 {
    ((to.1 - from.1) as f32).atan2((to.0 - from.0) as f32)
}

/// Whether a pointer gesture travelled far enough to count as a drag rather than a click.
///
/// A press and release land on slightly different pixels even when the hand is still, so a
/// gesture is only committed once the pointer has actually moved.
fn moved_enough(from: Point<Pixels>, to: Point<Pixels>) -> bool {
    (to.x.as_f32() - from.x.as_f32()).abs() >= DRAG_THRESHOLD_PX
        || (to.y.as_f32() - from.y.as_f32()).abs() >= DRAG_THRESHOLD_PX
}

/// Rotates a screen rectangle's corners about `pivot` by `radians`.
fn rotate_corners(
    corners: [Point<Pixels>; 4],
    pivot: Point<Pixels>,
    radians: f32,
) -> [Point<Pixels>; 4] {
    let (sin, cos) = radians.sin_cos();
    corners.map(|c| {
        let (dx, dy) = (
            c.x.as_f32() - pivot.x.as_f32(),
            c.y.as_f32() - pivot.y.as_f32(),
        );
        point(
            px(pivot.x.as_f32() + dx * cos - dy * sin),
            px(pivot.y.as_f32() + dx * sin + dy * cos),
        )
    })
}

/// Translates a rectangle by `delta`.
fn shift(rect: Bounds<Pixels>, delta: Point<Pixels>) -> Bounds<Pixels> {
    Bounds {
        origin: point(rect.origin.x + delta.x, rect.origin.y + delta.y),
        size: rect.size,
    }
}

/// Scales `rect` about `anchor`.
///
/// The mapping is a uniform zoom plus a translation, so a document-space scale about a point is
/// the screen-space scale about that point's image — the frame's own scale can be applied
/// directly to the pixels' rectangle.
fn scale_about(rect: Bounds<Pixels>, anchor: Point<Pixels>, sx: f32, sy: f32) -> Bounds<Pixels> {
    Bounds {
        origin: point(
            px(anchor.x.as_f32() + (rect.origin.x.as_f32() - anchor.x.as_f32()) * sx),
            px(anchor.y.as_f32() + (rect.origin.y.as_f32() - anchor.y.as_f32()) * sy),
        ),
        size: size(
            px(rect.size.width.as_f32() * sx),
            px(rect.size.height.as_f32() * sy),
        ),
    }
}

/// Where the selection frame and the in-flight shape preview go this frame, in screen pixels.
struct Overlay {
    /// Frame corners, clockwise from the top-left.
    corners: [Point<Pixels>; 4],
    /// Where the transformed shape is blitted. `None` means the gesture leaves the pixels alone
    /// and the static raster is painted as it is.
    ghost: Option<Bounds<Pixels>>,
}

/// Works out the overlay for this frame from the resting frame and the gesture in flight.
fn overlay(
    m: &Mapping,
    doc: (f64, f64),
    frame: Option<[f64; 4]>,
    gesture: Option<Drag>,
) -> Option<Overlay> {
    let frame = frame?;
    let image = m.image_bounds(doc);
    match gesture {
        Some(Drag::Move { delta, .. }) => Some(Overlay {
            corners: corners_of(m, frame).map(|c| point(c.x + delta.x, c.y + delta.y)),
            ghost: Some(shift(image, delta)),
        }),
        Some(Drag::Scale {
            grip,
            frame: start,
            cursor,
            ..
        }) => {
            let (sx, sy) = scale_factors(grip, start, m.screen_to_doc(cursor));
            let anchor = point_in_frame(start, grip.anchor());
            let at = m.doc_to_screen(anchor.0, anchor.1);
            Some(Overlay {
                corners: corners_of(m, scaled_frame(grip, start, sx, sy)),
                ghost: Some(scale_about(image, at, sx, sy)),
            })
        }
        Some(Drag::Rotate {
            center,
            start,
            cursor,
            ..
        }) => {
            // A bitmap cannot be rotated, so a rotate gesture previews its frame instead: the
            // shape keeps its place and snaps to the new angle on release.
            let now = angle_between(center, m.screen_to_doc(cursor));
            Some(Overlay {
                corners: rotate_corners(
                    corners_of(m, frame),
                    m.doc_to_screen(center.0, center.1),
                    now - start,
                ),
                ghost: None,
            })
        }
        _ => Some(Overlay {
            corners: corners_of(m, frame),
            ghost: None,
        }),
    }
}

/// Draws the selection frame, the stem and the nine grips.
fn paint_frame(window: &mut Window, corners: [Point<Pixels>; 4]) {
    let handles = grips(&corners);

    let mut frame = PathBuilder::stroke(px(FRAME_STROKE));
    frame.move_to(corners[0]);
    for corner in &corners[1..] {
        frame.line_to(*corner);
    }
    frame.close();
    // The rotate grip floats off the top edge, so a stem ties it back to the frame.
    frame.move_to(handles[1].1);
    frame.line_to(handles[8].1);
    if let Ok(path) = frame.build() {
        window.paint_path(path, theme::selection());
    }

    for (handle, at) in handles {
        let side = if handle == Handle::Rotate {
            HANDLE + 1.0
        } else {
            HANDLE
        };
        window.paint_quad(gpui::fill(
            Bounds {
                origin: point(at.x - px(side / 2.0), at.y - px(side / 2.0)),
                size: size(px(side), px(side)),
            },
            theme::selection(),
        ));
    }
}

/// The part of `a` that lies inside `b`, or `None` when they do not meet.
///
/// The document rectangle routinely runs past the viewport once the user zooms in, so the overlay
/// chrome is drawn over the overlap only: that keeps the work proportional to the window, not to
/// how far the image extends off-screen.
fn overlap(a: Bounds<Pixels>, b: Bounds<Pixels>) -> Option<Bounds<Pixels>> {
    let left = a.origin.x.as_f32().max(b.origin.x.as_f32());
    let top = a.origin.y.as_f32().max(b.origin.y.as_f32());
    let right = (a.origin.x.as_f32() + a.size.width.as_f32())
        .min(b.origin.x.as_f32() + b.size.width.as_f32());
    let bottom = (a.origin.y.as_f32() + a.size.height.as_f32())
        .min(b.origin.y.as_f32() + b.size.height.as_f32());
    if right <= left || bottom <= top {
        return None;
    }
    Some(Bounds {
        origin: point(px(left), px(top)),
        size: size(px(right - left), px(bottom - top)),
    })
}

/// The screen rectangle a selection band spans, whichever way the pointer was dragged.
fn band_rect(from: Point<Pixels>, to: Point<Pixels>) -> Bounds<Pixels> {
    let left = from.x.as_f32().min(to.x.as_f32());
    let top = from.y.as_f32().min(to.y.as_f32());
    Bounds {
        origin: point(px(left), px(top)),
        size: size(
            px((to.x.as_f32() - from.x.as_f32()).abs()),
            px((to.y.as_f32() - from.y.as_f32()).abs()),
        ),
    }
}

/// The document-unit spacing the grid is drawn at for a given zoom.
fn grid_step(scale: f32) -> f64 {
    GRID_STEPS
        .iter()
        .copied()
        .find(|step| *step as f32 * scale >= GRID_MIN_SPACING)
        .unwrap_or(*GRID_STEPS.last().expect("the ladder is not empty"))
}

/// Draws the transparency chessboard over `image`, clipped to the viewport.
///
/// Every dark square goes into a single path, so the board costs one draw call however many
/// squares it has — a per-square quad would be thousands of them at this cell size.
fn paint_chessboard(window: &mut Window, viewport: Bounds<Pixels>, image: Bounds<Pixels>) {
    let Some(vis) = overlap(image, viewport) else {
        return;
    };
    let (ix, iy) = (image.origin.x.as_f32(), image.origin.y.as_f32());
    let (ir, ib) = (image.origin.x.as_f32() + image.size.width.as_f32(),
                    image.origin.y.as_f32() + image.size.height.as_f32());
    let (vr, vb) = (vis.origin.x.as_f32() + vis.size.width.as_f32(),
                    vis.origin.y.as_f32() + vis.size.height.as_f32());
    // The board is anchored to the document's own corner, so it stays put while panning.
    let first_col = ((vis.origin.x.as_f32() - ix) / CHESS_CELL).floor().max(0.0) as i32;
    let last_col = ((ir - ix) / CHESS_CELL).ceil() as i32 - 1;
    let first_row = ((vis.origin.y.as_f32() - iy) / CHESS_CELL).floor().max(0.0) as i32;
    let last_row = ((ib - iy) / CHESS_CELL).ceil() as i32 - 1;

    let mut path = PathBuilder::fill();
    for row in first_row..=last_row {
        // Clipped to the visible part of the document, so the last row and column stop at the
        // page edge rather than spilling a whole cell past it.
        let y0 = (iy + row as f32 * CHESS_CELL).max(vis.origin.y.as_f32());
        let y1 = (iy + (row + 1) as f32 * CHESS_CELL).min(ib).min(vb);
        for col in first_col..=last_col {
            if (row + col) % 2 != 0 {
                continue;
            }
            let x0 = (ix + col as f32 * CHESS_CELL).max(vis.origin.x.as_f32());
            let x1 = (ix + (col + 1) as f32 * CHESS_CELL).min(ir).min(vr);
            if x1 <= x0 || y1 <= y0 {
                continue;
            }
            path.move_to(point(px(x0), px(y0)));
            path.line_to(point(px(x1), px(y0)));
            path.line_to(point(px(x1), px(y1)));
            path.line_to(point(px(x0), px(y1)));
            path.close();
        }
    }
    if let Ok(path) = path.build() {
        window.paint_path(path, theme::chess_dark());
    }
}

/// Draws the document-unit grid over the image, clipped to the viewport.
fn paint_grid(
    window: &mut Window,
    viewport: Bounds<Pixels>,
    image: Bounds<Pixels>,
    scale: f32,
) {
    let Some(vis) = overlap(image, viewport) else {
        return;
    };
    let step = grid_step(scale);
    let spacing = step as f32 * scale;
    if spacing < 1.0 {
        return;
    }
    let (ix, iy) = (image.origin.x.as_f32(), image.origin.y.as_f32());

    let mut path = PathBuilder::stroke(px(1.0));
    // Only the lines crossing the visible part of the document are drawn.
    let first_col = ((vis.origin.x.as_f32() - ix) / spacing).ceil().max(0.0) as i32;
    let last_col = ((vis.origin.x.as_f32() + vis.size.width.as_f32() - ix) / spacing).floor() as i32;
    for col in first_col..=last_col {
        let x = ix + col as f32 * spacing;
        path.move_to(point(px(x), vis.origin.y));
        path.line_to(point(px(x), px(vis.origin.y.as_f32() + vis.size.height.as_f32())));
    }
    let first_row = ((vis.origin.y.as_f32() - iy) / spacing).ceil().max(0.0) as i32;
    let last_row = ((vis.origin.y.as_f32() + vis.size.height.as_f32() - iy) / spacing).floor() as i32;
    for row in first_row..=last_row {
        let y = iy + row as f32 * spacing;
        path.move_to(point(vis.origin.x, px(y)));
        path.line_to(point(px(vis.origin.x.as_f32() + vis.size.width.as_f32()), px(y)));
    }
    if let Ok(path) = path.build() {
        window.paint_path(path, theme::grid_line());
    }
}

/// Draws the band a rubber-band selection is currently spanning.
fn paint_band(window: &mut Window, rect: Bounds<Pixels>) {
    window.paint_quad(gpui::fill(rect, theme::band_fill()));
    let mut path = PathBuilder::stroke(px(1.0));
    path.move_to(rect.origin);
    path.line_to(point(px(rect.origin.x.as_f32() + rect.size.width.as_f32()), rect.origin.y));
    path.line_to(point(
        px(rect.origin.x.as_f32() + rect.size.width.as_f32()),
        px(rect.origin.y.as_f32() + rect.size.height.as_f32()),
    ));
    path.line_to(point(rect.origin.x, px(rect.origin.y.as_f32() + rect.size.height.as_f32())));
    path.close();
    if let Ok(path) = path.build() {
        window.paint_path(path, theme::selection());
    }
}

impl View {
    pub fn new() -> Self {
        Self {
            zoom: 1.0,
            pan: point(px(0.), px(0.)),
            drag: None,
            tool: Tool::default(),
            grid: false,
            // On by default, the way the image viewer shows a transparent document.
            chessboard: true,
            hover_cursor: CursorStyle::Arrow,
        }
    }

    /// Back to "fit", without touching the tool or the view toggles.
    pub fn reset(&mut self) {
        self.zoom = 1.0;
        self.pan = point(px(0.), px(0.));
    }
}

/// Pixels per document unit for the current viewport and zoom.
pub fn view_scale(bounds: Bounds<Pixels>, doc: (f64, f64), zoom: f32) -> f32 {
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

/// The background/ghost pair for the drag in flight.
///
/// Rendered once when the gesture starts. During the drag the canvas only translates the ghost, so
/// the shape follows the cursor without re-rendering the document on every mouse move.
struct DragCache {
    /// Document revision the pair was rendered from. A different revision means it is stale — a
    /// drag should never span an edit, but a released-outside-the-window gesture can leave one
    /// behind.
    revision: u64,
    /// The document with the dragged subtrees cut out.
    bg: Arc<RenderImage>,
    /// Just the dragged subtrees, to be blitted on top with the live delta.
    ghost: Arc<RenderImage>,
}

/// State shared between the paint closures and the input handlers.
#[derive(Default)]
pub struct CanvasShared {
    /// Mapping used by the most recent frame — how input finds its way back to document space.
    pub mapping: Option<Mapping>,
    /// Viewport used by the most recent frame, for zoom-about-the-cursor arithmetic.
    pub bounds: Option<Bounds<Pixels>>,
    raster: Option<Raster>,
    drag: Option<DragCache>,
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
        let frame_doc = self.editor.selection_frame();
        let gesture = self.view.drag;
        let grid = self.view.grid;
        let chessboard = self.view.chessboard;

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

                // The drag pair, pre-rendered on mouse-down. It only applies while the document is
                // the one it was rendered from.
                let layers = {
                    let state = shared.borrow();
                    state
                        .drag
                        .as_ref()
                        .filter(|d| d.revision == revision)
                        .map(|d| (d.bg.clone(), d.ghost.clone()))
                };

                let overlay = overlay(&m, doc, frame_doc, gesture);
                let band = match gesture {
                    Some(Drag::Marquee { from, cursor }) => Some(band_rect(from, cursor)),
                    _ => None,
                };

                (m, image, layers, overlay, band)
            },
            move |bounds, (m, image, layers, overlay, band), window, _cx| {
                let image_rect = m.image_bounds(doc);
                // The board sits between the canvas backdrop and the document, so transparent
                // regions of the SVG show it and painted regions cover it.
                if chessboard {
                    paint_chessboard(window, bounds, image_rect);
                }
                // The shape being transformed is blitted separately, so the static raster — which
                // still shows it where it started — is replaced by the background with it cut
                // out. Painting both would double it.
                let ghost = overlay.as_ref().and_then(|o| o.ghost);
                match (&layers, ghost) {
                    (Some((bg, _)), Some(_)) => {
                        let _ = window.paint_image(
                            bounds,
                            image_rect,
                            Corners::default(),
                            bg.clone(),
                            0,
                            false,
                        );
                    }
                    _ => {
                        if let Some(image) = image {
                            let _ = window.paint_image(
                                bounds,
                                image_rect,
                                Corners::default(),
                                image,
                                0,
                                false,
                            );
                        }
                    }
                }

                if let (Some((_, ghost_image)), Some(at)) = (&layers, ghost) {
                    let _ = window.paint_image(
                        bounds,
                        at,
                        Corners::default(),
                        ghost_image.clone(),
                        0,
                        false,
                    );
                }

                // Over the image, the way the image viewer draws it.
                if grid {
                    paint_grid(window, bounds, image_rect, m.scale);
                }

                if let Some(o) = &overlay {
                    paint_frame(window, o.corners);
                }

                if let Some(rect) = band {
                    paint_band(window, rect);
                }
            },
        )
        .size_full();

        let canvas = div()
            .size_full()
            .bg(theme::canvas_bg())
            // Attached to the canvas so it applies only while the pointer is over it — the XML
            // pane's own cursor takes over the moment the pointer leaves.
            .cursor(self.view.hover_cursor)
            // Focusable so Delete / Escape / Ctrl+S land here after a click.
            .track_focus(&self.focus_handle)
            .on_mouse_down(MouseButton::Left, cx.listener(Self::on_canvas_down))
            .on_mouse_down(MouseButton::Right, cx.listener(Self::on_canvas_right_down))
            .on_mouse_move(cx.listener(Self::on_canvas_move))
            .on_mouse_up(MouseButton::Left, cx.listener(Self::on_canvas_up))
            .on_scroll_wheel(cx.listener(Self::on_canvas_wheel))
            .on_key_down(cx.listener(Self::on_canvas_key))
            .child(raster);

        // Attached unconditionally, even with nothing selected: this is the element that receives
        // the right-click that *makes* the selection, so it cannot be gated on the selection it is
        // about to change. The gate lives in the builder instead, which returns an empty menu —
        // and an empty menu is never opened.
        canvas.context_menu(self.restack_menu(cx)).into_any_element()
    }

    /// Pre-renders the background/ghost pair for the drag that is starting, so the shape can
    /// follow the cursor.
    ///
    /// Called on mouse-down, where the previous frame's mapping supplies the scale. A failure is
    /// not fatal: with no pair the canvas keeps painting the static raster and the move still lands
    /// on release.
    fn begin_drag_layers(&mut self) {
        let Some(m) = self.mapping() else {
            return;
        };
        let nodes = self.editor.selection.clone();
        let cache = self
            .editor
            .drag_layers(&nodes, bucket_scale(m.scale) as f64)
            .ok()
            .map(|DragLayers { bg, ghost }| DragCache {
                revision: self.source_revision,
                bg: rgba_to_render_image(bg.1, bg.2, bg.0),
                ghost: rgba_to_render_image(ghost.1, ghost.2, ghost.0),
            });
        self.shared.borrow_mut().drag = cache;
    }

    fn on_canvas_down(&mut self, ev: &MouseDownEvent, window: &mut Window, cx: &mut Context<Self>) {
        // Taking focus here is what makes the keyboard shortcuts work; it also drops focus from
        // the XML pane so typing does not fight the canvas.
        let handle = self.focus_handle.clone();
        window.focus(&handle, cx);

        let Some(m) = self.mapping() else {
            return;
        };

        // Any pair left over from an earlier gesture belongs to a document state we are past.
        self.shared.borrow_mut().drag = None;

        // Band mode owns the whole canvas: the pointer is only ever drawing a selection, so the
        // grips and the shapes underneath are all out of play until the band is released.
        if self.view.tool == Tool::Marquee {
            self.view.drag = Some(Drag::Marquee {
                from: ev.position,
                cursor: ev.position,
            });
            cx.notify();
            return;
        }

        // A grip wins over whatever is under it, so a selected shape can be resized even though
        // its own edge is the busiest part of the canvas.
        if let Some(frame) = self.editor.selection_frame() {
            if let Some(grip) = grip_at(&corners_of(&m, frame), ev.position) {
                let centre = (frame[0] + frame[2] / 2.0, frame[1] + frame[3] / 2.0);
                let drag = match grip {
                    Handle::Rotate => Drag::Rotate {
                        center: centre,
                        start: angle_between(centre, m.screen_to_doc(ev.position)),
                        from: ev.position,
                        cursor: ev.position,
                    },
                    Handle::Scale(grip) => Drag::Scale {
                        grip,
                        frame,
                        from: ev.position,
                        cursor: ev.position,
                    },
                };
                // A scale moves the pixels, so it wants the ghost pair; a rotate previews its
                // frame and leaves the raster alone.
                if matches!(drag, Drag::Scale { .. }) {
                    self.begin_drag_layers();
                }
                self.view.drag = Some(drag);
                cx.notify();
                return;
            }
        }

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
                // After the selection is settled, so the pair matches what will actually move.
                self.begin_drag_layers();
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

    /// A right-click names the element it lands on, exactly as a left-click does.
    ///
    /// Without this the context menu could only ever act on a selection made earlier, which is why
    /// right-clicking straight onto a shape appeared to do nothing. Landing on nothing leaves the
    /// selection alone — a right-click in the margin is not a request to deselect, and the menu
    /// that follows will simply find nothing to act on and stay shut.
    fn on_canvas_right_down(
        &mut self,
        ev: &MouseDownEvent,
        _window: &mut Window,
        cx: &mut Context<Self>,
    ) {
        let Some(m) = self.mapping() else {
            return;
        };
        let (x, y) = m.screen_to_doc(ev.position);
        let tolerance = HIT_SLOP_PX / m.scale.max(1e-6) as f64;
        let Some(node) = self.editor.hit(x, y, tolerance) else {
            return;
        };
        // Collapses a multi-selection onto the shape that was clicked: the menu's four moves are
        // only meaningful for one element, so the click has to leave exactly one.
        if self.editor.selection.len() != 1 || self.editor.selection[0] != node {
            self.editor.select_only(node);
            cx.notify();
        }
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
            // Scale and rotate both derive their live geometry from the cursor at paint time, so
            // all that is needed here is to record where the pointer is now.
            Some(Drag::Scale { grip, frame, from, .. }) => {
                self.view.drag = Some(Drag::Scale {
                    grip,
                    frame,
                    from,
                    cursor: ev.position,
                });
                cx.notify();
            }
            Some(Drag::Rotate { center, start, from, .. }) => {
                self.view.drag = Some(Drag::Rotate {
                    center,
                    start,
                    from,
                    cursor: ev.position,
                });
                cx.notify();
            }
            Some(Drag::Marquee { from, .. }) => {
                self.view.drag = Some(Drag::Marquee {
                    from,
                    cursor: ev.position,
                });
                cx.notify();
            }
            None => {
                // Nothing is being dragged, so the pointer is only being read: keep the cursor in
                // step with whatever it is over. Only a change repaints — gpui re-resolves the
                // cursor from the last frame, so following every move would rebuild the whole
                // element tree, XML pane included, for a pixel of arrow.
                let wanted = match self.mapping() {
                    Some(m) => {
                        cursor_for(self.view.tool, self.editor.selection_frame(), &m, ev.position)
                    }
                    None => CursorStyle::Arrow,
                };
                if wanted != self.view.hover_cursor {
                    self.view.hover_cursor = wanted;
                    cx.notify();
                }
            }
        }
    }

    fn on_canvas_up(&mut self, _ev: &MouseUpEvent, window: &mut Window, cx: &mut Context<Self>) {
        let Some(drag) = self.view.drag.take() else {
            // No gesture in flight, but a pair may still be cached if the previous release happened
            // outside the window.
            self.shared.borrow_mut().drag = None;
            return;
        };
        // The gesture is over: the next frame paints the freshly rendered document instead.
        self.shared.borrow_mut().drag = None;
        match drag {
            Drag::Pan { .. } => cx.notify(),
            Drag::Move { from, delta } => {
                let moved = moved_enough(from, from + delta);
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
            Drag::Scale {
                grip,
                frame,
                from,
                cursor,
            } => {
                // A press on a grip that never moved is a click on the selection, not a resize:
                // the pointer jitters by a fraction of a pixel even on a plain click.
                if !moved_enough(from, cursor) {
                    cx.notify();
                    return;
                }
                let Some(m) = self.mapping() else {
                    cx.notify();
                    return;
                };
                let (sx, sy) = scale_factors(grip, frame, m.screen_to_doc(cursor));
                // Scaling about the grip's anchor, in root space: the engine folds this into each
                // element's own transform, so the whole selection scales as one.
                let anchor = point_in_frame(frame, grip.anchor());
                let m = Mat::translate(anchor.0, anchor.1)
                    .mul(Mat::scale(sx as f64, sy as f64))
                    .mul(Mat::translate(-anchor.0, -anchor.1));
                if self.editor.transform_selection(m) {
                    self.after_document_edit(window, cx);
                } else {
                    cx.notify();
                }
            }
            Drag::Rotate {
                center,
                start,
                from,
                cursor,
            } => {
                if !moved_enough(from, cursor) {
                    cx.notify();
                    return;
                }
                let Some(m) = self.mapping() else {
                    cx.notify();
                    return;
                };
                let radians = angle_between(center, m.screen_to_doc(cursor)) - start;
                let m = Mat::translate(center.0, center.1)
                    .mul(Mat::rotate(radians as f64))
                    .mul(Mat::translate(-center.0, -center.1));
                if self.editor.transform_selection(m) {
                    self.after_document_edit(window, cx);
                } else {
                    cx.notify();
                }
            }
            Drag::Marquee { from, cursor } => {
                let Some(m) = self.mapping() else {
                    cx.notify();
                    return;
                };
                if !moved_enough(from, cursor) {
                    // A click in band mode still means "pick what is under the pointer".
                    let (x, y) = m.screen_to_doc(cursor);
                    let tolerance = HIT_SLOP_PX / m.scale.max(1e-6) as f64;
                    match self.editor.hit(x, y, tolerance) {
                        Some(node) => self.editor.select_only(node),
                        None => self.editor.clear_selection(),
                    }
                    cx.notify();
                    return;
                }
                let (ax, ay) = m.screen_to_doc(from);
                let (bx, by) = m.screen_to_doc(cursor);
                self.editor.select_in_rect([
                    ax.min(bx),
                    ay.min(by),
                    (bx - ax).abs(),
                    (by - ay).abs(),
                ]);
                cx.notify();
            }
        }
    }

    /// Ctrl (Cmd on macOS) + wheel zooms about the pointer.
    ///
    /// The modifier is required rather than optional: a bare wheel is the gesture people use to
    /// scroll *whatever is under the pointer*, so binding zoom to it meant a scroll meant for an
    /// overlay — or a reflexive scroll while reading the XML pane with the pointer drifting over
    /// the canvas — silently rescaled the document. Zoom is a deliberate act; scrolling is not.
    fn on_canvas_wheel(
        &mut self,
        ev: &ScrollWheelEvent,
        _window: &mut Window,
        cx: &mut Context<Self>,
    ) {
        if !ev.modifiers.secondary() {
            return;
        }
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

    /// Canvas-scoped keys only.
    ///
    /// Delete and Escape mean "edit the text" while the XML pane has focus, so they stay scoped to
    /// the canvas; the document-level shortcuts (Ctrl+S/O/0/±) live on the root and reach this
    /// element by bubbling.
    fn on_canvas_key(&mut self, ev: &KeyDownEvent, window: &mut Window, cx: &mut Context<Self>) {
        if ev.keystroke.modifiers.secondary() {
            return;
        }
        match ev.keystroke.key.as_str() {
            "delete" | "backspace" => {
                if self.editor.delete_selection() {
                    self.after_document_edit(window, cx);
                }
            }
            "escape" => {
                self.editor.clear_selection();
                cx.notify();
            }
            _ => {}
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// A mapping that puts document (0, 0) at screen (100, 50) with one pixel per unit, so the
    /// expected screen coordinates can be read straight off the document numbers.
    fn mapping() -> Mapping {
        Mapping {
            scale: 1.0,
            origin: point(px(100.), px(50.)),
        }
    }

    #[test]
    fn the_grid_snaps_to_a_round_spacing_that_stays_legible() {
        // A finer spacing than the floor would read as a grey wash, so the ladder steps up.
        assert_eq!(grid_step(1.0), 10.0);
        assert_eq!(grid_step(4.0), 2.0);
        assert_eq!(grid_step(0.5), 20.0);
        // Zoomed far enough in, the finest spacing on the ladder is the honest one.
        assert_eq!(grid_step(16.0), 1.0);
    }

    #[test]
    fn a_band_covers_the_same_rectangle_whichever_way_it_was_dragged() {
        let down_right = band_rect(point(px(10.), px(10.)), point(px(30.), px(40.)));
        let up_left = band_rect(point(px(30.), px(40.)), point(px(10.), px(10.)));
        assert!((down_right.origin.x.as_f32() - up_left.origin.x.as_f32()).abs() < 1e-3);
        assert!((down_right.origin.y.as_f32() - up_left.origin.y.as_f32()).abs() < 1e-3);
        assert!((down_right.size.width.as_f32() - 20.0).abs() < 1e-3);
        assert!((down_right.size.height.as_f32() - 30.0).abs() < 1e-3);
    }

    #[test]
    fn a_gesture_only_counts_once_the_pointer_actually_moves() {
        let at = point(px(100.), px(100.));
        // A still hand still lands on a slightly different pixel between press and release.
        assert!(!moved_enough(at, point(px(100.6), px(99.4))));
        assert!(moved_enough(at, point(px(102.5), px(100.))));
        assert!(moved_enough(at, point(px(100.), px(97.5))));
    }

    #[test]
    fn the_grips_sit_on_the_frame_corners_edges_and_above_the_top() {
        let corners = corners_of(&mapping(), [0.0, 0.0, 200.0, 100.0]);
        let at = |x: f32, y: f32| grip_at(&corners, point(px(x), px(y)));

        assert_eq!(at(100., 50.), Some(Handle::Scale(Grip::Nw)));
        assert_eq!(at(300., 50.), Some(Handle::Scale(Grip::Ne)));
        assert_eq!(at(300., 150.), Some(Handle::Scale(Grip::Se)));
        assert_eq!(at(100., 150.), Some(Handle::Scale(Grip::Sw)));
        assert_eq!(at(200., 50.), Some(Handle::Scale(Grip::N)));
        assert_eq!(at(300., 100.), Some(Handle::Scale(Grip::E)));
        assert_eq!(at(200., 150.), Some(Handle::Scale(Grip::S)));
        assert_eq!(at(100., 100.), Some(Handle::Scale(Grip::W)));
        // The stem hangs off the top edge, so the rotate grip sits above the frame's middle.
        assert_eq!(at(200., 50. - ROTATE_OFFSET), Some(Handle::Rotate));
        // The middle of the frame belongs to whatever shape is drawn there.
        assert_eq!(at(200., 100.), None);
    }

    #[test]
    fn the_cursor_names_the_grip_under_the_pointer() {
        let m = mapping();
        let frame = Some([0.0, 0.0, 200.0, 100.0]);
        let at = |x: f32, y: f32| cursor_for(Tool::Move, frame, &m, point(px(x), px(y)));

        // Each corner pulls along its own diagonal: north-west and south-east share the `\` one,
        // north-east and south-west the `/`.
        assert_eq!(at(100., 50.), CursorStyle::ResizeUpLeftDownRight);
        assert_eq!(at(300., 150.), CursorStyle::ResizeUpLeftDownRight);
        assert_eq!(at(300., 50.), CursorStyle::ResizeUpRightDownLeft);
        assert_eq!(at(100., 150.), CursorStyle::ResizeUpRightDownLeft);
        // An edge grip only scales the one axis it sits on, so it gets that axis's two-way cursor.
        assert_eq!(at(200., 50.), CursorStyle::ResizeUpDown);
        assert_eq!(at(200., 150.), CursorStyle::ResizeUpDown);
        assert_eq!(at(300., 100.), CursorStyle::ResizeLeftRight);
        assert_eq!(at(100., 100.), CursorStyle::ResizeLeftRight);
        assert_eq!(at(200., 50. - ROTATE_OFFSET), CursorStyle::PointingHand);
        // Away from the grips nothing extra is promised.
        assert_eq!(at(200., 100.), CursorStyle::Arrow);
        assert_eq!(at(600., 600.), CursorStyle::Arrow);

        // A band only ever draws a band, wherever it starts.
        assert_eq!(
            cursor_for(Tool::Marquee, frame, &m, point(px(100.), px(50.))),
            CursorStyle::Crosshair
        );
        // With nothing selected there are no grips to be over.
        assert_eq!(
            cursor_for(Tool::Move, None, &m, point(px(100.), px(50.))),
            CursorStyle::Arrow
        );
    }

    #[test]
    fn a_corner_grip_scales_both_axes_from_its_opposite_corner() {
        let frame = [10.0, 20.0, 100.0, 50.0];
        // The south-east grip sits at (110, 70) with the north-west corner held at (10, 20).
        assert_eq!(scale_factors(Grip::Se, frame, (110.0, 70.0)), (1.0, 1.0));
        assert_eq!(scale_factors(Grip::Se, frame, (210.0, 120.0)), (2.0, 2.0));
        // The north-west grip anchors the south-east corner instead.
        assert_eq!(scale_factors(Grip::Nw, frame, (-90.0, -30.0)), (2.0, 2.0));
    }

    #[test]
    fn an_edge_grip_scales_one_axis_and_leaves_the_other_alone() {
        let frame = [10.0, 20.0, 100.0, 50.0];
        // East: the west edge stays put, so the width follows the cursor and y is ignored.
        assert_eq!(scale_factors(Grip::E, frame, (160.0, 999.0)), (1.5, 1.0));
        // North: the bottom edge stays put and x is ignored.
        assert_eq!(scale_factors(Grip::N, frame, (999.0, -30.0)), (1.0, 2.0));
        // Dragging a grip past its own anchor stops at the floor, so the matrix stays invertible.
        assert_eq!(scale_factors(Grip::E, frame, (-1000.0, 0.0)), (MIN_SCALE, 1.0));
    }

    #[test]
    fn the_scaled_frame_holds_the_anchor_still() {
        let frame = [10.0, 20.0, 100.0, 50.0];
        // South-east drag: the north-west corner is the anchor, so it does not move.
        assert_eq!(
            scaled_frame(Grip::Se, frame, 2.0, 3.0),
            [10.0, 20.0, 200.0, 150.0]
        );
        // North-west drag: the south-east corner is.
        assert_eq!(
            scaled_frame(Grip::Nw, frame, 2.0, 1.0),
            [-90.0, 20.0, 200.0, 50.0]
        );
    }

    #[test]
    fn a_rotate_preview_turns_the_frame_about_its_centre() {
        let pivot = point(px(100.), px(100.));
        let corners = [
            point(px(50.), px(50.)),
            point(px(150.), px(50.)),
            point(px(150.), px(150.)),
            point(px(50.), px(150.)),
        ];
        let turned = rotate_corners(corners, pivot, std::f32::consts::FRAC_PI_2);
        // A quarter turn takes the top-left corner round to the top-right.
        assert!((turned[0].x.as_f32() - 150.0).abs() < 1e-3, "{turned:?}");
        assert!((turned[0].y.as_f32() - 50.0).abs() < 1e-3, "{turned:?}");
        // …and the frame keeps its centre.
        let centre = point(
            px((turned[0].x.as_f32() + turned[2].x.as_f32()) / 2.0),
            px((turned[0].y.as_f32() + turned[2].y.as_f32()) / 2.0),
        );
        assert!((centre.x.as_f32() - 100.0).abs() < 1e-3);
        assert!((centre.y.as_f32() - 100.0).abs() < 1e-3);
    }
}