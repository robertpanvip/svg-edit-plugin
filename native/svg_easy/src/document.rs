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

use resvg_bridge::geom::Mat;
use resvg_bridge::session::Session;

/// Opened when the app starts with no file argument, so it is never showing an empty window.
pub const DEFAULT_DOC: &str = r##"<?xml version="1.0" encoding="UTF-8"?>
<!-- SVG Easy: drag a shape to move it, or edit the source on the left -->
<svg xmlns="http://www.w3.org/2000/svg" width="420" height="280" viewBox="0 0 420 280">
  <rect id="card" x="24" y="24" width="180" height="120" rx="16" fill="#4f46e5"/>
  <circle id="dot" cx="318" cy="92" r="62" fill="#22c55e"/>
  <g id="bars" transform="translate(60,170)">
    <rect id="bar-a" x="0" y="0" width="48" height="72" rx="6" fill="#f59e0b"/>
    <rect id="bar-b" x="64" y="24" width="48" height="48" rx="6" fill="#ef4444"/>
    <rect id="bar-c" x="128" y="44" width="48" height="28" rx="6" fill="#0ea5e9"/>
  </g>
</svg>"##;

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
    pub dirty: bool,
}

impl Editor {
    pub fn new(source: String, path: Option<PathBuf>) -> Self {
        let mut editor = Self {
            path,
            source,
            session: None,
            parse_error: None,
            selection: Vec::new(),
            dirty: false,
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
                    Some(format!("could not open {}: {e} — showing the sample", p.display())),
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

    /// Moves every selected element by (`dx`, `dy`) document units.
    ///
    /// Each element is moved through its *own* local transform (the engine compensates for the
    /// ancestor chain), so a shape nested in nested groups keeps its relative geometry.
    /// Returns true when the document actually changed.
    pub fn translate_selection(&mut self, dx: f64, dy: f64) -> bool {
        if self.selection.is_empty() {
            return false;
        }
        let ids = self.selection.clone();
        let delta = Mat::translate(dx, dy);

        let mut updated = None;
        if let Some(session) = self.session.as_mut() {
            for id in ids {
                // A selection can outlive its element (e.g. after a source edit); skip those
                // rather than aborting the whole move.
                if let Ok(source) = session.apply_transform(id, delta) {
                    updated = Some(source);
                }
            }
        }

        match updated {
            Some(source) => {
                self.source = source;
                self.dirty = true;
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
                self.dirty = true;
                true
            }
            None => false,
        }
    }

    /// Adopts `text` as the new document (the XML pane was edited). Returns true when it differs.
    pub fn set_source(&mut self, text: String) -> bool {
        if text == self.source {
            return false;
        }
        self.source = text;
        self.dirty = true;
        self.reparse();
        true
    }

    /// Writes the document back to the file it came from.
    pub fn save(&mut self) -> Result<PathBuf, String> {
        let path = match self.path.clone() {
            Some(p) => p,
            None => PathBuf::from("untitled.svg"),
        };
        fs::write(&path, &self.source)
            .map_err(|e| format!("could not save {}: {e}", path.display()))?;
        self.path = Some(path.clone());
        self.dirty = false;
        Ok(path)
    }

    /// Window title: file name (or `untitled`), with a marker while there are unsaved changes.
    pub fn title(&self) -> String {
        let name = self
            .path
            .as_deref()
            .and_then(Path::file_name)
            .map(|n| n.to_string_lossy().into_owned())
            .unwrap_or_else(|| "untitled".to_string());
        if self.dirty {
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
        assert!(e.source.contains("<!-- SVG Easy: drag a shape"));
        assert!(e.dirty);
        // …and the edited text still parses.
        assert!(e.parse_error.is_none());
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
}
