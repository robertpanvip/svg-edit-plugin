//! The editing document.
//!
//! The **SVG source text is the single source of truth**. The engine's fidelity DOM is parsed from
//! it, and every structural edit (move, delete) re-serializes back into it, so the text in the XML
//! pane is always exactly what gets written to disk. There is no second object model that can
//! drift out of sync with the file.
//!
//! Edits are performed by the engine (`Session`), never by string surgery here, because the engine
//! already implements the hard parts: resolving an element's ancestor transform, composing a delta
//! into its own `transform` attribute, and preserving the untouched source verbatim.

use std::fs;
use std::path::{Path, PathBuf};
use std::time::{Duration, Instant};

use resvg_bridge::dom::Stack;
use resvg_bridge::geom::Mat;
use resvg_bridge::session::{DragLayers, Session};

/// Opened when the app starts with no file argument, so it is never showing an empty window.
pub const DEFAULT_DOC: &str = r##"<?xml version="1.0" encoding="UTF-8"?>
<!-- SVG Easy：拖动图形即可移动，或在左侧编辑源码 -->
<svg xmlns="http://www.w3.org/2000/svg" width="420" height="280" viewBox="0 0 420 280">
  <rect id="card" x="24" y="24" width="180" height="120" rx="16" fill="#4f46e5"/>
  <circle id="dot" cx="318" cy="92" r="62" fill="#22c55e"/>
  <g id="bars" transform="translate(60,170)">
    <rect id="bar-a" x="0" y="0" width="48" height="72" rx="6" fill="#f59e0b"/>
    <rect id="bar-b" x="64" y="24" width="48" height="48" rx="6" fill="#ef4444"/>
    <rect id="bar-c" x="128" y="44" width="48" height="28" rx="6" fill="#0ea5e9"/>
  </g>
</svg>"##;

/// What the New action starts from: an empty canvas at a sensible default size, so the document is
/// immediately valid SVG rather than an empty pane.
pub const NEW_DOC: &str = r##"<?xml version="1.0" encoding="UTF-8"?>
<svg xmlns="http://www.w3.org/2000/svg" width="400" height="300" viewBox="0 0 400 300"></svg>"##;

pub struct Editor {
    /// `None` until the document has been saved somewhere.
    pub path: Option<PathBuf>,
    /// The document text.
    pub source: String,
    /// Parsed projection of `source`. `None` only before the first successful parse.
    pub session: Option<Session>,
    /// Why the last parse failed. The canvas keeps painting the last good session meanwhile, so a
    /// half-typed document never blanks the window.
    pub parse_error: Option<String>,
    /// Node ids of the selected elements, in the order they were added.
    pub selection: Vec<usize>,
    /// The text as of the last successful save (or load). "Dirty" is derived from it rather than
    /// tracked separately, so undoing back to the saved state clears the marker honestly.
    saved_source: String,
    /// Undo steps, oldest first. Each holds the state to return to.
    history: Vec<Snapshot>,
    /// Steps undone but not yet superseded, newest first.
    future: Vec<Snapshot>,
    /// When the last text edit landed, for merging a burst of keystrokes into one undo step.
    last_text_edit: Option<Instant>,
}

/// A restorable document state.
///
/// The source text *is* the document, so snapshotting it captures the whole edit. The selection
/// rides along so that undoing a move or a delete leaves the shape selected again.
#[derive(Clone)]
struct Snapshot {
    source: String,
    selection: Vec<usize>,
}

/// How many undo steps are remembered. Deep enough for a session's worth of edits, bounded so a
/// long-lived editor cannot grow without limit.
const HISTORY_LIMIT: usize = 200;

/// Text edits closer together than this merge into a single undo step, so a typed word undoes as a
/// word rather than one character at a time.
const TEXT_EDIT_COALESCE: Duration = Duration::from_millis(600);

impl Editor {
    pub fn new(source: String, path: Option<PathBuf>) -> Self {
        let mut editor = Self {
            path,
            saved_source: source.clone(),
            source,
            session: None,
            parse_error: None,
            selection: Vec::new(),
            history: Vec::new(),
            future: Vec::new(),
            last_text_edit: None,
        };
        editor.reparse();
        editor
    }

    /// Loads `path`, or falls back to [`DEFAULT_DOC`]. The returned message is shown in the status
    /// strip — a failed open must not look like an empty document.
    pub fn open(path: Option<PathBuf>) -> (Self, Option<String>) {
        match path {
            Some(p) => match fs::read_to_string(&p) {
                Ok(source) => (Self::new(source, Some(p)), None),
                Err(e) => (
                    Self::new(DEFAULT_DOC.to_string(), None),
                    Some(format!("无法打开 {}：{e} —— 已显示示例文档", p.display())),
                ),
            },
            None => (Self::new(DEFAULT_DOC.to_string(), None), None),
        }
    }

    /// Re-parses `source` into a fresh session.
    pub fn reparse(&mut self) {
        match Session::new(&self.source) {
            Ok(session) => {
                self.session = Some(session);
                self.parse_error = None;
                self.drop_dead_selection();
            }
            Err(e) => self.parse_error = Some(e),
        }
    }

    /// Node ids are handed out in document order, so they survive any edit that does not add or
    /// remove elements; only drop the ids that no longer resolve.
    fn drop_dead_selection(&mut self) {
        let Some(session) = self.session.as_ref() else {
            self.selection.clear();
            return;
        };
        self.selection
            .retain(|id| session.doc.find_by_node_id(*id).is_some());
    }

    /// Document size in SVG user units.
    pub fn doc_size(&self) -> (f64, f64) {
        self.session
            .as_ref()
            .map(|s| (s.width, s.height))
            .unwrap_or((1.0, 1.0))
    }

    /// Topmost element under (`x`, `y`) in document units, or `None` over empty canvas.
    pub fn hit(&self, x: f64, y: f64, tol: f64) -> Option<usize> {
        self.session.as_ref()?.hit_test(x, y, tol)
    }

    /// Bounding boxes (document units) of the current selection, for the canvas overlay.
    pub fn selection_boxes(&self) -> Vec<[f64; 4]> {
        let Some(session) = self.session.as_ref() else {
            return Vec::new();
        };
        let layout = session.layout_json();
        self.selection
            .iter()
            .filter_map(|id| box_of(&layout, *id))
            .collect()
    }

    /// The union of the selection's boxes in document units — `[x, y, w, h]` — or `None` when
    /// nothing is selected.
    ///
    /// Scaling and rotating act on the selection as a whole, so they need a single frame around
    /// all of it rather than one per element.
    pub fn selection_frame(&self) -> Option<[f64; 4]> {
        let boxes = self.selection_boxes();
        let first = *boxes.first()?;
        let (mut left, mut top) = (first[0], first[1]);
        let (mut right, mut bottom) = (first[0] + first[2], first[1] + first[3]);
        for b in &boxes[1..] {
            left = left.min(b[0]);
            top = top.min(b[1]);
            right = right.max(b[0] + b[2]);
            bottom = bottom.max(b[1] + b[3]);
        }
        Some([left, top, right - left, bottom - top])
    }

    /// Pre-renders the drag's background/ghost pair, in document space at `scale`.
    pub fn drag_layers(&self, nodes: &[usize], scale: f64) -> Result<DragLayers, String> {
        let session = self
            .session
            .as_ref()
            .ok_or_else(|| "当前没有可拖动的文档".to_string())?;
        session.drag_layers_rgba(nodes, scale)
    }

    pub fn select_only(&mut self, node_id: usize) {
        self.selection = vec![node_id];
    }

    pub fn toggle(&mut self, node_id: usize) {
        match self.selection.iter().position(|&id| id == node_id) {
            Some(at) => {
                self.selection.remove(at);
            }
            None => self.selection.push(node_id),
        }
    }

    pub fn clear_selection(&mut self) {
        self.selection.clear();
    }

    pub fn has_selection(&self) -> bool {
        !self.selection.is_empty()
    }

    /// Selects every element whose box intersects `rect` (document units), for a rubber-band
    /// selection. Returns true when the selection changed.
    ///
    /// Intersection rather than containment is deliberate: a band drawn over a large compound
    /// shape only covers part of it, so "must be fully inside" would make the band look broken on
    /// anything bigger than itself. An element covering the whole document is skipped — that is a
    /// page background, and a band over the artwork must not start dragging it along.
    pub fn select_in_rect(&mut self, rect: [f64; 4]) -> bool {
        let Some(session) = self.session.as_ref() else {
            return false;
        };
        let (page_w, page_h) = (session.width, session.height);
        let layout = session.layout_json();
        let hits: Vec<(usize, usize, [f64; 4])> = layout["elements"]
            .as_array()
            .map(|elements| {
                elements
                    .iter()
                    .filter_map(|e| {
                        let node_id = e["nodeId"].as_u64()? as usize;
                        let depth = e["depth"].as_u64().unwrap_or(0) as usize;
                        let b = [
                            e["x"].as_f64()?,
                            e["y"].as_f64()?,
                            e["w"].as_f64()?,
                            e["h"].as_f64()?,
                        ];
                        let meets = b[0] < rect[0] + rect[2]
                            && b[0] + b[2] > rect[0]
                            && b[1] < rect[1] + rect[3]
                            && b[1] + b[3] > rect[1];
                        let covers_page = b[0] <= 0.0
                            && b[1] <= 0.0
                            && b[0] + b[2] >= page_w - 0.5
                            && b[1] + b[3] >= page_h - 0.5;
                        (meets && !covers_page).then_some((node_id, depth, b))
                    })
                    .collect()
            })
            .unwrap_or_default();

        // A group and the children inside it can both meet the band. Keeping only the outermost
        // is what stops one root-space matrix from being applied twice to a child: the group
        // already carries it down. The listing is in tree order, so a parent is always seen
        // before its descendants and the open ancestors can be tracked as a stack.
        let mut ancestors: Vec<(usize, [f64; 4])> = Vec::new();
        let mut picked: Vec<usize> = Vec::new();
        for (node_id, depth, b) in hits {
            while ancestors.last().is_some_and(|(d, _)| depth <= *d) {
                ancestors.pop();
            }
            let inside = |outer: &[f64; 4]| {
                outer[0] <= b[0] + 0.01
                    && outer[1] <= b[1] + 0.01
                    && outer[0] + outer[2] >= b[0] + b[2] - 0.01
                    && outer[1] + outer[3] >= b[1] + b[3] - 0.01
            };
            if ancestors.iter().any(|(_, outer)| inside(outer)) {
                continue;
            }
            picked.push(node_id);
            ancestors.push((depth, b));
        }

        if picked == self.selection {
            return false;
        }
        self.selection = picked;
        true
    }

    /// Moves every selected element by (`dx`, `dy`) document units.
    pub fn translate_selection(&mut self, dx: f64, dy: f64) -> bool {
        self.transform_selection(Mat::translate(dx, dy))
    }

    /// Applies `m` — a matrix in **root** space — to every selected element.
    ///
    /// Each element is transformed through its *own* local transform (the engine compensates for
    /// the ancestor chain), so a shape nested in nested groups keeps its relative geometry. One
    /// matrix shared by the whole selection is what makes a multi-selection scale and rotate as a
    /// single unit rather than each member about its own centre.
    /// Returns true when the document actually changed.
    pub fn transform_selection(&mut self, m: Mat) -> bool {
        if self.selection.is_empty() {
            return false;
        }
        let before = self.snapshot();
        let ids = self.selection.clone();

        let mut updated = None;
        if let Some(session) = self.session.as_mut() {
            for id in ids {
                // A selection can outlive its element (e.g. after a source edit); skip those
                // rather than aborting the whole transform.
                if let Ok(source) = session.apply_transform(id, m) {
                    updated = Some(source);
                }
            }
        }

        match updated {
            Some(source) => {
                self.source = source;
                // One undo step per completed gesture, not per frame: this is called once on
                // mouse-up.
                self.record_structural(before);
                true
            }
            None => false,
        }
    }

    /// Deletes every selected element. Returns true when the document actually changed.
    pub fn delete_selection(&mut self) -> bool {
        if self.selection.is_empty() {
            return false;
        }
        let before = self.snapshot();
        let ids = self.selection.clone();

        let mut updated = None;
        if let Some(session) = self.session.as_mut() {
            for id in ids {
                if let Ok(source) = session.remove_subtree(id) {
                    updated = Some(source);
                }
            }
        }

        match updated {
            Some(source) => {
                self.source = source;
                self.selection.clear();
                self.record_structural(before);
                true
            }
            None => false,
        }
    }

    /// Restacks the selected element among its siblings, as one undo step.
    ///
    /// Only a single selection has a stack position to speak of: with several elements picked
    /// there is no one slot to move them to.
    pub fn restack_selection(&mut self, to: Stack) -> bool {
        if self.selection.len() != 1 {
            return false;
        }
        let node_id = self.selection[0];
        let before = self.snapshot();
        let source = match self.session.as_mut() {
            Some(session) => match session.restack(node_id, to) {
                Ok(source) => source,
                Err(_) => return false,
            },
            None => return false,
        };
        // Restacking onto the position the element already holds returns the text unchanged.
        if source == self.source {
            return false;
        }
        self.source = source;
        self.record_structural(before);
        true
    }

    /// Adopts `text` as the new document (the XML pane was edited). Returns true when it differs.
    pub fn set_source(&mut self, text: String) -> bool {
        if text == self.source {
            return false;
        }
        let before = self.snapshot();
        self.source = text;
        self.record_text_edit(before);
        self.reparse();
        true
    }

    /// Replaces the whole document with `text` — how an action that produces a new document (SVGO,
    /// Format) lands its result.
    ///
    /// Deliberately not [`Self::set_source`], which records a *text* edit and so can merge into the
    /// previous undo step when the user presses the button right after typing. A generated document
    /// is always its own edit: one Ctrl+Z must put the previous one back, whole.
    ///
    /// The selection is left as it was: only the caller knows whether the new text still means the
    /// same thing by node id ([`Self::clear_selection`] if it does not).
    pub fn replace_source(&mut self, text: String) -> bool {
        if text == self.source {
            return false;
        }
        let before = self.snapshot();
        self.source = text;
        self.record_structural(before);
        self.reparse();
        true
    }

    /// True while the document differs from the last saved (or loaded) state.
    pub fn dirty(&self) -> bool {
        self.source != self.saved_source
    }

    pub fn can_undo(&self) -> bool {
        !self.history.is_empty()
    }

    pub fn can_redo(&self) -> bool {
        !self.future.is_empty()
    }

    /// Steps back one edit. Returns true when the document changed.
    pub fn undo(&mut self) -> bool {
        let Some(previous) = self.history.pop() else {
            return false;
        };
        self.future.push(self.snapshot());
        self.restore(previous);
        true
    }

    /// Re-applies the edit that the last [`Self::undo`] stepped back over.
    pub fn redo(&mut self) -> bool {
        let Some(next) = self.future.pop() else {
            return false;
        };
        self.history.push(self.snapshot());
        self.restore(next);
        true
    }

    fn snapshot(&self) -> Snapshot {
        Snapshot {
            source: self.source.clone(),
            selection: self.selection.clone(),
        }
    }

    /// Installs a snapshot as the live document, re-parsing it so the canvas follows.
    fn restore(&mut self, snapshot: Snapshot) {
        self.source = snapshot.source;
        self.selection = snapshot.selection;
        // `reparse` also prunes ids that no longer resolve in the restored document.
        self.reparse();
        // A restore is a hard boundary: typing after an undo starts a fresh step.
        self.last_text_edit = None;
    }

    /// Records a completed move or delete. One gesture, one step.
    fn record_structural(&mut self, before: Snapshot) {
        self.push_step(before);
        self.last_text_edit = None;
    }

    /// Records a text edit, merging it into the previous step when the two arrive in quick
    /// succession (someone typing) so a burst of keystrokes undoes as one edit.
    fn record_text_edit(&mut self, before: Snapshot) {
        let now = Instant::now();
        let merging = self
            .last_text_edit
            .is_some_and(|previous| now.duration_since(previous) < TEXT_EDIT_COALESCE);
        if !merging {
            // The first keystroke of a burst captures the text as it was before the burst.
            self.push_step(before);
        }
        self.last_text_edit = Some(now);
    }

    /// Pushes an undo step and drops the redo branch: a new edit invalidates it.
    fn push_step(&mut self, before: Snapshot) {
        self.history.push(before);
        if self.history.len() > HISTORY_LIMIT {
            self.history.remove(0);
        }
        self.future.clear();
    }

    /// Writes the document back to the file it came from.
    ///
    /// Errors when the document has never been saved: the caller is expected to ask the user for
    /// a path (Save As) rather than invent one.
    pub fn save(&mut self) -> Result<PathBuf, String> {
        let path = self
            .path
            .clone()
            .ok_or_else(|| "此文档还没有保存到文件".to_string())?;
        self.save_as(path)
    }

    /// Writes the document to `path` and adopts it as the current file.
    pub fn save_as(&mut self, path: PathBuf) -> Result<PathBuf, String> {
        fs::write(&path, &self.source)
            .map_err(|e| format!("无法保存 {}：{e}", path.display()))?;
        self.path = Some(path.clone());
        self.saved_source = self.source.clone();
        Ok(path)
    }

    /// The directory a file dialog should open in: next to the current file, else the process's
    /// working directory.
    pub fn dialog_directory(&self) -> PathBuf {
        self.path
            .as_ref()
            .and_then(|p| p.parent())
            .map(Path::to_path_buf)
            .filter(|p| !p.as_os_str().is_empty())
            .or_else(|| std::env::current_dir().ok())
            .unwrap_or_else(|| PathBuf::from("."))
    }

    /// The name a Save As dialog should suggest.
    pub fn suggested_file_name(&self) -> String {
        self.path
            .as_ref()
            .and_then(|p| p.file_name())
            .map(|n| n.to_string_lossy().into_owned())
            .unwrap_or_else(|| "未命名.svg".to_string())
    }

    /// Window title: file name (or `未命名`), with a marker while there are unsaved changes.
    pub fn title(&self) -> String {
        let name = self
            .path
            .as_deref()
            .and_then(Path::file_name)
            .map(|n| n.to_string_lossy().into_owned())
            .unwrap_or_else(|| "未命名".to_string());
        if self.dirty() {
            format!("{name} •")
        } else {
            name
        }
    }
}

/// Reads one element's box out of the engine's layout document.
fn box_of(layout: &serde_json::Value, node_id: usize) -> Option<[f64; 4]> {
    layout["elements"]
        .as_array()?
        .iter()
        .find(|e| e["nodeId"].as_u64() == Some(node_id as u64))
        .and_then(|e| {
            Some([
                e["x"].as_f64()?,
                e["y"].as_f64()?,
                e["w"].as_f64()?,
                e["h"].as_f64()?,
            ])
        })
}

#[cfg(test)]
mod tests {
    use super::*;

    fn editor() -> Editor {
        Editor::new(DEFAULT_DOC.to_string(), None)
    }

    /// Compares frames element-wise: the engine's boxes come back from a fresh render, so exact
    /// bit equality would be brittle.
    fn assert_frame(got: [f64; 4], want: [f64; 4]) {
        for i in 0..4 {
            assert!(
                (got[i] - want[i]).abs() < 1e-6,
                "expected {want:?}, got {got:?}",
            );
        }
    }

    #[test]
    fn sample_parses_and_exposes_the_document_size() {
        let e = editor();
        assert!(e.parse_error.is_none(), "{:?}", e.parse_error);
        assert_eq!(e.doc_size(), (420.0, 280.0));
        assert!(e.session.is_some());
    }

    #[test]
    fn hit_test_picks_the_topmost_shape_and_misses_outside() {
        let e = editor();
        // Inside the indigo card.
        let card = e.hit(60.0, 60.0, 2.0).expect("card must be hit");
        // Well outside every shape.
        assert_eq!(e.hit(410.0, 270.0, 2.0), None);
        assert!(e.selection_boxes().is_empty());
        assert!(card > 0);
    }

    #[test]
    fn translate_moves_the_element_and_round_trips_the_source() {
        let mut e = editor();
        let card = e.hit(60.0, 60.0, 2.0).unwrap();
        e.select_only(card);
        let before = e.selection_boxes();
        assert!(e.translate_selection(10.0, 4.0));
        let after = e.selection_boxes();
        assert!((after[0][0] - before[0][0] - 10.0).abs() < 0.01);
        assert!((after[0][1] - before[0][1] - 4.0).abs() < 0.01);
        // The edit landed in the source text, and the untouched parts are verbatim.
        assert!(e.source.contains("transform=\"translate(10 4)\""));
        assert!(e.source.contains("<!-- SVG Easy：拖动图形"));
        assert!(e.dirty());
        // …and the edited text still parses.
        assert!(e.parse_error.is_none());
    }

    #[test]
    fn selection_frame_covers_every_selected_element() {
        let mut e = editor();
        assert_eq!(e.selection_frame(), None, "nothing selected, no frame");

        let card = e.hit(60.0, 60.0, 2.0).expect("card must be hit");
        e.select_only(card);
        // The card alone: exactly its own box.
        assert_frame(e.selection_frame().unwrap(), [24.0, 24.0, 180.0, 120.0]);

        // Adding the dot (centred at 318,92 with r=62) grows the frame to hold both.
        let dot = e.hit(318.0, 92.0, 2.0).expect("dot must be hit");
        e.toggle(dot);
        assert_frame(e.selection_frame().unwrap(), [24.0, 24.0, 356.0, 130.0]);
    }

    #[test]
    fn scaling_the_selection_keeps_its_anchor_fixed() {
        let mut e = editor();
        let card = e.hit(60.0, 60.0, 2.0).expect("card must be hit");
        e.select_only(card);

        // What the south-east grip does: double the card about its north-west corner.
        let anchor = (24.0, 24.0);
        let m = Mat::translate(anchor.0, anchor.1)
            .mul(Mat::scale(2.0, 2.0))
            .mul(Mat::translate(-anchor.0, -anchor.1));
        assert!(e.transform_selection(m));

        assert_frame(e.selection_boxes()[0], [24.0, 24.0, 360.0, 240.0]);
        assert!(e.parse_error.is_none(), "{:?}", e.parse_error);
        assert!(e.dirty());
    }

    #[test]
    fn rotating_the_selection_turns_it_about_its_middle() {
        let mut e = editor();
        let card = e.hit(60.0, 60.0, 2.0).expect("card must be hit");
        e.select_only(card);

        // A quarter turn about the card's middle (24+90, 24+60).
        let centre = (114.0, 84.0);
        let m = Mat::translate(centre.0, centre.1)
            .mul(Mat::rotate(std::f64::consts::FRAC_PI_2))
            .mul(Mat::translate(-centre.0, -centre.1));
        assert!(e.transform_selection(m));

        // The 180x120 box becomes 120x180 around the same centre.
        assert_frame(e.selection_boxes()[0], [54.0, -6.0, 120.0, 180.0]);
    }

    #[test]
    fn one_matrix_transforms_a_whole_multi_selection() {
        let mut e = editor();
        let card = e.hit(60.0, 60.0, 2.0).expect("card must be hit");
        let dot = e.hit(318.0, 92.0, 2.0).expect("dot must be hit");
        e.select_only(card);
        e.toggle(dot);

        let frame = e.selection_frame().unwrap();
        let anchor = (frame[0], frame[1]);
        assert!(e.transform_selection(
            Mat::translate(anchor.0, anchor.1)
                .mul(Mat::scale(2.0, 2.0))
                .mul(Mat::translate(-anchor.0, -anchor.1)),
        ));

        // Sharing one root-space matrix is what makes the members scale as a unit: the frame
        // doubles about the same anchor, and both members stay inside it.
        assert_frame(
            e.selection_frame().unwrap(),
            [frame[0], frame[1], frame[2] * 2.0, frame[3] * 2.0],
        );
    }

    #[test]
    fn a_band_selects_every_shape_it_touches_once() {
        let mut e = editor();
        let card = e.hit(60.0, 60.0, 2.0).expect("card must be hit");
        let dot = e.hit(318.0, 92.0, 2.0).expect("dot must be hit");

        // A band down the left of the sample: over the card and the `bars` group.
        assert!(e.select_in_rect([20.0, 20.0, 220.0, 260.0]));
        assert!(e.selection.contains(&card));
        assert!(
            !e.selection.contains(&dot),
            "the dot sits outside the band",
        );
        // The group is picked, not the group *and* its three children: one root-space matrix
        // applied to both a parent and its child would move the child twice.
        assert_eq!(e.selection.len(), 2, "{:?}", e.selection);

        // A selection needs no edit, so it does not touch the source or the undo stack.
        assert!(!e.dirty());
        assert!(!e.can_undo());

        // The same band over the same shapes is not a change.
        assert!(!e.select_in_rect([20.0, 20.0, 220.0, 260.0]));

        // A band over empty canvas clears the selection.
        assert!(e.select_in_rect([400.0, 260.0, 10.0, 10.0]));
        assert!(e.selection.is_empty());
    }

    #[test]
    fn a_band_ignores_a_page_background() {
        let source = r##"<svg xmlns="http://www.w3.org/2000/svg" width="100" height="100" viewBox="0 0 100 100">
  <rect id="page" x="0" y="0" width="100" height="100" fill="#ffffff"/>
  <circle id="dot" cx="50" cy="50" r="10" fill="#000000"/>
</svg>"##;
        let mut e = Editor::new(source.to_string(), None);
        let dot = e.hit(50.0, 50.0, 2.0).expect("dot must be hit");

        // A band over the whole document must not start dragging the page background along.
        assert!(e.select_in_rect([0.0, 0.0, 100.0, 100.0]));
        assert_eq!(e.selection, vec![dot]);
    }

    #[test]
    fn delete_removes_the_subtree_and_clears_the_selection() {
        let mut e = editor();
        // The three bars live inside the `bars` group; deleting one leaves its siblings.
        let bar = e.hit(84.0, 200.0, 2.0).expect("bar-a must be hit");
        e.select_only(bar);
        assert!(e.delete_selection());
        assert!(e.selection.is_empty());
        assert!(!e.source.contains("id=\"bar-a\""));
        assert!(e.source.contains("id=\"bar-b\""));
        // Deleting nothing is a no-op, not an error.
        assert!(!e.delete_selection());
    }

    #[test]
    fn save_requires_a_path_and_save_as_adopts_one() {
        let mut e = editor();
        // A never-saved document must not silently invent a filename.
        assert!(e.save().is_err());
        assert_eq!(e.suggested_file_name(), "未命名.svg");

        e.select_only(e.hit(60.0, 60.0, 2.0).unwrap());
        assert!(e.translate_selection(3.0, 0.0));
        assert!(e.dirty());

        let path = std::env::temp_dir().join("svg_easy_save_test.svg");
        let written = e.save_as(path.clone()).expect("save_as must write");
        assert_eq!(written, path);
        assert!(!e.dirty());
        assert_eq!(e.suggested_file_name(), "svg_easy_save_test.svg");
        // The file on disk is the edited source, verbatim.
        assert_eq!(std::fs::read_to_string(&path).unwrap(), e.source);
        // And a plain save now works because the path is known.
        assert!(e.save().is_ok());
        let _ = std::fs::remove_file(&path);
    }

    /// The `id` of the element the source writes last — the one that paints on top.
    ///
    /// Paint order *is* source order in an SVG, so the source is where to read it: the layer
    /// panel that used to report it is gone.
    fn topmost(source: &str) -> Option<&str> {
        source
            .rmatch_indices("id=\"")
            .next()
            .and_then(|(at, _)| source[at + "id=\"".len()..].split('"').next())
    }

    #[test]
    fn restacking_moves_the_element_through_the_stack() {
        let mut e = editor();
        let card = e.hit(60.0, 60.0, 2.0).expect("card must be hit");
        e.select_only(card);
        let before = e.source.clone();
        assert_eq!(topmost(&e.source), Some("bar-c"), "bar-c paints last");

        // `card` is painted first, so bringing it to the front moves it behind every sibling.
        assert!(e.restack_selection(Stack::Front), "card jumps to the top");
        assert_ne!(e.source, before);
        assert!(e.source.find("id=\"card\"").unwrap() > e.source.find("id=\"dot\"").unwrap());
        assert_eq!(topmost(&e.source), Some("card"), "card paints last now");
        assert!(
            e.source.contains("rx=\"16\" fill=\"#4f46e5\"/>"),
            "a restack must not disturb the element itself",
        );

        // A restack is a document edit like any other: one step, undoable, and it dirties the file.
        assert!(e.dirty());
        assert!(e.undo());
        assert_eq!(e.source, before, "undo restores the source verbatim");
        assert_eq!(topmost(&e.source), Some("bar-c"));

        // Nothing to do at the end of the stack, and nothing to do without a selection.
        assert!(!e.restack_selection(Stack::Back), "already at the bottom");
        e.clear_selection();
        assert!(!e.restack_selection(Stack::Front));
    }

    #[test]
    fn a_multi_selection_has_no_single_stack_position() {
        let mut e = editor();
        let card = e.hit(60.0, 60.0, 2.0).expect("card must be hit");
        let dot = e.hit(318.0, 92.0, 2.0).expect("dot must be hit");
        e.select_only(card);
        e.toggle(dot);
        let before = e.source.clone();
        assert!(!e.restack_selection(Stack::Front));
        assert_eq!(e.source, before);
    }

    #[test]
    fn a_broken_edit_keeps_the_last_good_session() {
        let mut e = editor();
        let card = e.hit(60.0, 60.0, 2.0).expect("card must be hit");
        e.select_only(card);
        assert!(!e.selection_boxes().is_empty());

        // Now break the source: the session (and therefore the frame on screen) must survive.
        assert!(e.set_source("<svg><unclosed>".to_string()));
        assert!(e.parse_error.is_some(), "the failure is reported");
        assert!(e.session.is_some(), "the last good projection is kept");
        assert!(
            e.translate_selection(1.0, 1.0),
            "edits still apply to the good session",
        );
    }

    #[test]
    fn undo_and_redo_round_trip_a_move() {
        let mut e = editor();
        let card = e.hit(60.0, 60.0, 2.0).expect("card must be hit");
        e.select_only(card);

        let original = e.source.clone();
        assert!(e.translate_selection(10.0, 4.0));
        let moved = e.source.clone();
        assert_ne!(moved, original);

        assert!(e.undo());
        assert_eq!(e.source, original, "undo restores the text verbatim");
        assert_eq!(e.selection, vec![card], "…and keeps the shape selected");
        assert!(e.parse_error.is_none());

        assert!(e.redo());
        assert_eq!(e.source, moved);
        assert!(!e.redo(), "the redo branch is exhausted");
    }

    #[test]
    fn undoing_a_delete_brings_the_element_back() {
        let mut e = editor();
        let bar = e.hit(84.0, 200.0, 2.0).expect("bar-a must be hit");
        e.select_only(bar);
        assert!(e.delete_selection());
        assert!(e.selection.is_empty());
        assert!(!e.source.contains("id=\"bar-a\""));

        assert!(e.undo());
        assert!(e.source.contains("id=\"bar-a\""));
        assert_eq!(e.selection, vec![bar]);
        assert_eq!(e.selection_boxes().len(), 1, "the restored element resolves");
    }

    #[test]
    fn rapid_text_edits_undo_as_a_single_step() {
        let mut e = editor();
        let original = e.source.clone();

        // Two keystrokes in quick succession: one edit as far as undo is concerned.
        assert!(e.set_source(e.source.replace("#4f46e5", "#101010")));
        assert!(e.set_source(e.source.replace("#101010", "#111111")));
        assert!(e.source.contains("#111111"));

        assert!(e.undo());
        assert_eq!(e.source, original);
        assert!(!e.can_undo(), "the burst was a single step");
    }

    #[test]
    fn a_structural_edit_ends_the_text_edit_burst() {
        let mut e = editor();
        let original = e.source.clone();

        assert!(e.set_source(e.source.replace("#4f46e5", "#101010")));
        let card = e.hit(60.0, 60.0, 2.0).expect("card must be hit");
        e.select_only(card);
        assert!(e.translate_selection(8.0, 0.0));
        let moved = e.source.clone();
        // Far enough after the move to be its own step regardless of timing.
        assert!(e.set_source(e.source.replace("#101010", "#121212")));
        assert_ne!(e.source, moved);

        assert!(e.undo());
        assert_eq!(e.source, moved);
        assert!(e.undo());
        assert!(e.source.contains("#101010"), "the move is undone next");
        assert!(e.undo());
        assert_eq!(e.source, original);
    }

    /// A generated document (SVGO, Format) is always its own undo step, even when it lands inside
    /// the window that coalesces text edits — and it leaves the selection to the caller, which is
    /// what lets a re-indent keep the selected shape while an optimisation drops it.
    #[test]
    fn a_replaced_document_is_its_own_undo_step_and_keeps_the_selection() {
        let mut e = editor();
        let original = e.source.clone();
        let card = e.hit(60.0, 60.0, 2.0).expect("card must be hit");
        e.select_only(card);

        assert!(e.set_source(e.source.replace("#f59e0b", "#f59e0c")));
        let typed = e.source.clone();
        assert!(e.replace_source(original.replace("id=\"card\"", "id=\"card-1\"")));
        assert_ne!(e.source, typed);

        assert_eq!(e.selection, vec![card], "replacing text does not drop the selection");

        assert!(e.undo());
        assert_eq!(e.source, typed, "the typed text comes back first");
        assert!(e.undo());
        assert_eq!(e.source, original);
    }

    #[test]
    fn a_new_edit_drops_the_redo_branch() {
        let mut e = editor();
        let card = e.hit(60.0, 60.0, 2.0).expect("card must be hit");
        e.select_only(card);

        assert!(e.translate_selection(1.0, 0.0));
        assert!(e.undo());
        assert!(e.can_redo());

        assert!(e.translate_selection(2.0, 0.0));
        assert!(!e.can_redo(), "the abandoned future is gone");
    }

    #[test]
    fn a_new_document_is_a_blank_canvas() {
        let e = Editor::new(NEW_DOC.to_string(), None);
        assert!(e.parse_error.is_none(), "{:?}", e.parse_error);
        assert_eq!(e.doc_size(), (400.0, 300.0));
        assert_eq!(e.selection_boxes().len(), 0);
        assert!(e.hit(200.0, 150.0, 2.0).is_none(), "nothing to select yet");
        assert!(!e.dirty());
        assert!(!e.can_undo());
    }

    #[test]
    fn undo_and_redo_are_noops_without_history() {
        let mut e = editor();
        let source = e.source.clone();
        assert!(!e.can_undo());
        assert!(!e.undo());
        assert!(!e.redo());
        assert!(!e.can_redo());
        assert_eq!(e.source, source);
    }

    #[test]
    fn undo_back_to_the_saved_text_clears_the_dirty_marker() {
        let mut e = editor();
        assert!(!e.dirty(), "a freshly opened document is clean");

        let card = e.hit(60.0, 60.0, 2.0).expect("card must be hit");
        e.select_only(card);
        assert!(e.translate_selection(3.0, 0.0));

        let path = std::env::temp_dir().join("svg_easy_undo_dirty_test.svg");
        e.save_as(path.clone()).expect("save_as must write");
        assert!(!e.dirty());

        assert!(e.translate_selection(5.0, 0.0));
        assert!(e.dirty());
        assert!(e.undo());
        assert!(!e.dirty(), "undoing back to the saved text is clean again");

        let _ = std::fs::remove_file(&path);
    }
}
