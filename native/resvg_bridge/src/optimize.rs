//! SVG optimisation — the SVGO engine, in-process.
//!
//! Both front ends offer a *SVGO* button and a *SVGO settings* dialog. The engine behind them is
//! [`oxvg`](https://crates.io/crates/oxvg), a Rust implementation of SVGO that runs the same
//! plugin pipeline (35 jobs in its `preset-default` equivalent). Building it into this crate is
//! what lets the standalone app spend no Node process on the job, and lets the IntelliJ plugin
//! reach the same engine through the sidecar it already talks to — one optimiser, no second
//! runtime.
//!
//! Configuration is by *job name*, not by struct field: [`passes`] is the catalogue the dialogs
//! render, and [`OptimizeOptions`] carries the user's on/off choices as the SVGO plugin names.
//! The names travel over the sidecar as JSON, so the Kotlin side never needs the Rust field
//! layout — only [`exec`] has to know how a name maps onto a [`Jobs`] field.

use std::collections::BTreeMap;

use oxvg_ast::{parse::roxmltree::parse, serialize::Node as _, visitor::Info};
use oxvg_optimiser::Jobs;
use serde::{Deserialize, Serialize};

/// One configurable optimisation, as the settings dialogs present it.
///
/// `name` is SVGO's plugin name — the stable identity, and what [`OptimizeOptions`] keys on.
/// `label` and `group` are for display only; the standalone app translates the label, the plugin
/// shows it as-is.
pub struct OptimizePass {
    pub name: &'static str,
    pub label: &'static str,
    pub group: &'static str,
}

/// The catalogue, in the order the dialogs list it.
///
/// Every entry is a job that `Jobs::default()` — oxvg's `preset-default` — has switched on, which
/// is what makes "tick it back on" possible: the base is the full preset and a choice can only
/// turn jobs *off*. `catalog_covers_the_default_preset` keeps that true.
pub const PASSES: &[OptimizePass] = &[
    OptimizePass { name: "removeDoctype", label: "Remove doctype", group: "Document" },
    OptimizePass { name: "removeXMLProcInst", label: "Remove XML declaration", group: "Document" },
    OptimizePass { name: "removeComments", label: "Remove comments", group: "Document" },
    OptimizePass { name: "removeMetadata", label: "Remove metadata", group: "Document" },
    OptimizePass { name: "removeEditorsNSData", label: "Remove editor namespaces", group: "Document" },
    OptimizePass { name: "removeDesc", label: "Remove desc", group: "Document" },
    OptimizePass { name: "cleanupIds", label: "Minify ids", group: "Structure" },
    OptimizePass { name: "removeUselessDefs", label: "Remove useless defs", group: "Structure" },
    OptimizePass { name: "removeUnusedNS", label: "Remove unused namespaces", group: "Structure" },
    OptimizePass { name: "removeEmptyAttrs", label: "Remove empty attributes", group: "Structure" },
    OptimizePass { name: "removeEmptyContainers", label: "Remove empty containers", group: "Structure" },
    OptimizePass { name: "removeEmptyText", label: "Remove empty text", group: "Structure" },
    OptimizePass { name: "removeHiddenElems", label: "Remove hidden elements", group: "Structure" },
    OptimizePass { name: "collapseGroups", label: "Collapse useless groups", group: "Structure" },
    OptimizePass { name: "sortDefsChildren", label: "Sort defs children", group: "Structure" },
    OptimizePass { name: "cleanupAttrs", label: "Clean up attributes", group: "Attributes" },
    OptimizePass { name: "cleanupNumericValues", label: "Round numeric values", group: "Attributes" },
    OptimizePass { name: "cleanupEnableBackground", label: "Clean up enable-background", group: "Attributes" },
    OptimizePass { name: "convertColors", label: "Shorten colours", group: "Attributes" },
    OptimizePass { name: "convertTransform", label: "Shorten transforms", group: "Attributes" },
    OptimizePass { name: "removeDeprecatedAttrs", label: "Remove deprecated attributes", group: "Attributes" },
    OptimizePass { name: "removeUnknownsAndDefaults", label: "Remove defaults and unknowns", group: "Attributes" },
    OptimizePass { name: "removeNonInheritableGroupAttrs", label: "Remove non-inheritable group attributes", group: "Attributes" },
    OptimizePass { name: "removeUselessStrokeAndFill", label: "Remove useless stroke and fill", group: "Attributes" },
    OptimizePass { name: "sortAttrs", label: "Sort attributes", group: "Attributes" },
    OptimizePass { name: "inlineStyles", label: "Inline styles", group: "Styles" },
    OptimizePass { name: "minifyStyles", label: "Minify styles", group: "Styles" },
    OptimizePass { name: "mergeStyles", label: "Merge styles", group: "Styles" },
    OptimizePass { name: "convertShapeToPath", label: "Convert shapes to paths", group: "Shapes" },
    OptimizePass { name: "convertEllipseToCircle", label: "Convert ellipse to circle", group: "Shapes" },
    OptimizePass { name: "convertPathData", label: "Shorten path data", group: "Shapes" },
    OptimizePass { name: "mergePaths", label: "Merge paths", group: "Shapes" },
    OptimizePass { name: "applyTransforms", label: "Apply transforms", group: "Shapes" },
    OptimizePass { name: "moveElemsAttrsToGroup", label: "Move element attributes to group", group: "Shapes" },
    OptimizePass { name: "moveGroupAttrsToElems", label: "Move group attributes to elements", group: "Shapes" },
];

/// What the user ticked in the settings dialog.
///
/// A name missing from `passes` means *on* — the catalogue's defaults are all on, so an empty map
/// is the stock SVGO preset and a map only ever records the exceptions. Unknown names are ignored
/// rather than rejected, so a settings file written by a newer build still loads.
#[derive(Clone, Debug, Default, Serialize, Deserialize)]
#[serde(transparent)]
pub struct OptimizeOptions {
    pub passes: BTreeMap<String, bool>,
}

impl OptimizeOptions {
    /// Whether `name` should run.
    pub fn enabled(&self, name: &str) -> bool {
        self.passes.get(name).copied().unwrap_or(true)
    }

    /// The passes that will actually run, in catalogue order.
    pub fn enabled_count(&self) -> usize {
        PASSES.iter().filter(|p| self.enabled(p.name)).count()
    }
}

/// The outcome of a run: the new document plus the numbers the result dialog reports.
pub struct OptimizeResult {
    pub svg: String,
    /// UTF-8 byte length of the input.
    pub before_bytes: usize,
    /// UTF-8 byte length of the optimised document.
    pub after_bytes: usize,
    /// Catalogue entries that ran.
    pub passes: usize,
}

impl OptimizeResult {
    /// Bytes saved. Negative when the optimised document somehow grew.
    pub fn saved_bytes(&self) -> i64 {
        self.before_bytes as i64 - self.after_bytes as i64
    }

    /// Fraction saved, in `0.0..=1.0`. Zero for a document that had no bytes to begin with.
    pub fn saved_ratio(&self) -> f64 {
        if self.before_bytes == 0 {
            0.0
        } else {
            self.saved_bytes() as f64 / self.before_bytes as f64
        }
    }
}

/// Runs the enabled passes over `svg` and serialises the result.
///
/// A parse failure is reported rather than swallowed: the callers show it in a dialog, so the
/// user learns their document is malformed instead of seeing the optimiser "do nothing".
pub fn optimize(svg: &str, options: &OptimizeOptions) -> Result<OptimizeResult, String> {
    let before_bytes = svg.len();

    let mut jobs = Jobs::default();
    for pass in PASSES {
        if !options.enabled(pass.name) {
            disable(&mut jobs, pass.name);
        }
    }

    // The pipeline runs inside `parse`'s callback, which has no way to fail, so a job error is
    // parked here and returned once the document has been handed back.
    let mut failure: Option<String> = None;
    let out = parse(svg, |dom, allocator| {
        if let Err(e) = jobs.run(dom, &Info::new(allocator)) {
            failure = Some(e.to_string());
        }
        match dom.serialize() {
            Ok(svg) => svg,
            Err(e) => {
                failure = Some(e.to_string());
                String::new()
            }
        }
    })
    .map_err(|e| e.to_string())?;

    if let Some(e) = failure {
        return Err(e);
    }

    Ok(OptimizeResult {
        after_bytes: out.len(),
        svg: out,
        before_bytes,
        passes: options.enabled_count(),
    })
}

/// Switches one job off. Field assignment rather than a config round-trip: [`Jobs`] is a plain
/// struct of `Option<Job>`, and `None` is what "not in the pipeline" means.
fn disable(jobs: &mut Jobs, name: &str) {
    match name {
        "removeDoctype" => jobs.remove_doctype = None,
        "removeXMLProcInst" => jobs.remove_x_m_l_proc_inst = None,
        "removeComments" => jobs.remove_comments = None,
        "removeMetadata" => jobs.remove_metadata = None,
        "removeEditorsNSData" => jobs.remove_editors_n_s_data = None,
        "removeDesc" => jobs.remove_desc = None,
        "cleanupIds" => jobs.cleanup_ids = None,
        "removeUselessDefs" => jobs.remove_useless_defs = None,
        "removeUnusedNS" => jobs.remove_unused_n_s = None,
        "removeEmptyAttrs" => jobs.remove_empty_attrs = None,
        "removeEmptyContainers" => jobs.remove_empty_containers = None,
        "removeEmptyText" => jobs.remove_empty_text = None,
        "removeHiddenElems" => jobs.remove_hidden_elems = None,
        "collapseGroups" => jobs.collapse_groups = None,
        "sortDefsChildren" => jobs.sort_defs_children = None,
        "cleanupAttrs" => jobs.cleanup_attrs = None,
        "cleanupNumericValues" => jobs.cleanup_numeric_values = None,
        "cleanupEnableBackground" => jobs.cleanup_enable_background = None,
        "convertColors" => jobs.convert_colors = None,
        "convertTransform" => jobs.convert_transform = None,
        "removeDeprecatedAttrs" => jobs.remove_deprecated_attrs = None,
        "removeUnknownsAndDefaults" => jobs.remove_unknowns_and_defaults = None,
        "removeNonInheritableGroupAttrs" => jobs.remove_non_inheritable_group_attrs = None,
        "removeUselessStrokeAndFill" => jobs.remove_useless_stroke_and_fill = None,
        "sortAttrs" => jobs.sort_attrs = None,
        "inlineStyles" => jobs.inline_styles = None,
        "minifyStyles" => jobs.minify_styles = None,
        "mergeStyles" => jobs.merge_styles = None,
        "convertShapeToPath" => jobs.convert_shape_to_path = None,
        "convertEllipseToCircle" => jobs.convert_ellipse_to_circle = None,
        "convertPathData" => jobs.convert_path_data = None,
        "mergePaths" => jobs.merge_paths = None,
        "applyTransforms" => jobs.apply_transforms = None,
        "moveElemsAttrsToGroup" => jobs.move_elems_attrs_to_group = None,
        "moveGroupAttrsToElems" => jobs.move_group_attrs_to_elems = None,
        // Unknown name: nothing to switch off. `OptimizeOptions` stays forward-compatible.
        _ => {}
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    const SAMPLE: &str = r##"<?xml version="1.0" encoding="UTF-8"?>
<!-- a comment -->
<svg xmlns="http://www.w3.org/2000/svg" width="200" height="120" viewBox="0 0 200 120">
  <metadata>generated</metadata>
  <rect id="box-a" x="10.000" y="10" width="80" height="60" fill="#4caf50"/>
  <circle id="dot" cx="150" cy="60" r="30" fill="#e91e63"/>
</svg>"##;

    #[test]
    fn the_default_options_shrink_a_document() {
        let result = optimize(SAMPLE, &OptimizeOptions::default()).expect("optimise");
        assert!(
            result.after_bytes < result.before_bytes,
            "{} -> {}",
            result.before_bytes,
            result.after_bytes
        );
        assert_eq!(result.before_bytes, SAMPLE.len());
        assert_eq!(result.after_bytes, result.svg.len());
        assert_eq!(result.passes, PASSES.len());
        assert!(result.saved_ratio() > 0.0);
    }

    /// The whole catalogue is on by default, so switching one off has to be observable — that is
    /// what makes the settings dialog real rather than decorative.
    #[test]
    fn a_disabled_pass_is_left_out() {
        let mut options = OptimizeOptions::default();
        options.passes.insert("removeComments".into(), false);
        let result = optimize(SAMPLE, &options).expect("optimise");
        assert!(result.svg.contains("a comment"), "{}", result.svg);
        assert_eq!(result.passes, PASSES.len() - 1);

        let removed = optimize(SAMPLE, &OptimizeOptions::default()).expect("optimise");
        assert!(!removed.svg.contains("a comment"));
    }

    #[test]
    fn unknown_option_names_are_ignored() {
        let mut options = OptimizeOptions::default();
        options.passes.insert("notAPass".into(), false);
        let result = optimize(SAMPLE, &options);
        assert!(result.is_ok(), "{:?}", result.err());
    }

    /// The optimised document must still be valid SVG — the editors re-parse it immediately.
    #[test]
    fn the_output_still_parses() {
        let result = optimize(SAMPLE, &OptimizeOptions::default()).expect("optimise");
        assert!(usvg::Tree::from_str(&result.svg, &usvg::Options::default()).is_ok());
    }

    #[test]
    fn the_output_keeps_the_viewport() {
        let result = optimize(SAMPLE, &OptimizeOptions::default()).expect("optimise");
        let tree = usvg::Tree::from_str(&result.svg, &usvg::Options::default()).expect("parse");
        assert_eq!(tree.size().width().round(), 200.0);
        assert_eq!(tree.size().height().round(), 120.0);
    }

    #[test]
    fn malformed_input_reports_an_error() {
        assert!(optimize("<svg><unclosed>", &OptimizeOptions::default()).is_err());
    }

    /// Guards the invariant the settings dialog rests on: anything the catalogue offers can be
    /// ticked back on, which is only true while the base preset still has it switched on. Also
    /// proves each name reaches a real, distinct field — a typo or a copy-paste in [`disable`]
    /// would otherwise leave some job running no matter what the user chose.
    ///
    /// Observed through the serialised job set rather than by name: `Jobs` skips its `None` fields,
    /// so switching a job off removes exactly one key.
    #[test]
    fn catalog_covers_the_default_preset() {
        let names = |jobs: &Jobs| -> std::collections::BTreeSet<String> {
            serde_json::to_value(jobs)
                .expect("serialize")
                .as_object()
                .expect("jobs is a struct")
                .keys()
                .cloned()
                .collect()
        };

        let base = names(&Jobs::default());
        let mut switched: Vec<String> = Vec::new();
        for pass in PASSES {
            let mut jobs = Jobs::default();
            disable(&mut jobs, pass.name);
            let after = names(&jobs);

            let gone: Vec<&String> = base.difference(&after).collect();
            assert_eq!(
                gone.len(),
                1,
                "disabling {} should switch off exactly one job, off went {gone:?}",
                pass.name
            );
            switched.push(gone[0].clone());
        }

        let unique: std::collections::HashSet<&String> = switched.iter().collect();
        assert_eq!(
            unique.len(),
            PASSES.len(),
            "two catalogue entries switch off the same job"
        );
    }

    #[test]
    fn disabling_every_pass_leaves_the_document_alone() {
        let options = OptimizeOptions {
            passes: PASSES.iter().map(|p| (p.name.to_string(), false)).collect(),
        };
        let result = optimize(SAMPLE, &options).expect("optimise");
        // Serialisation alone may normalise whitespace, but nothing may be dropped.
        for needle in ["a comment", "generated", "box-a", "dot"] {
            assert!(result.svg.contains(needle), "{needle} missing from {}", result.svg);
        }
    }

    /// SVGO's camelCase plugin name -> the field name `serde` derives for it.
    fn snake_case(name: &str) -> String {
        let mut out = String::new();
        let mut prev_lower = false;
        for c in name.chars() {
            if c.is_ascii_uppercase() {
                if prev_lower {
                    out.push('_');
                }
                out.push(c.to_ascii_lowercase());
                prev_lower = false;
            } else {
                out.push(c);
                prev_lower = c.is_ascii_lowercase() || c.is_ascii_digit();
            }
        }
        out
    }
}
