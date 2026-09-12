//! SVG optimisation — the SVGO engine, running as JavaScript.
//!
//! Both front ends offer a *SVGO* button and a *SVGO settings* dialog, and the engine behind them
//! is the real thing: SVGO 3, bundled to a single JavaScript file
//! (`assets/svgo.browser.js`, built from `native/svgo_bundle/entry.js`) and executed by a QuickJS
//! interpreter embedded through [`rquickjs`]. QuickJS is compiled from its vendored C sources, so
//! the standalone app spends no Node process on the job and the plugin reaches the same engine
//! through the sidecar it already talks to — one optimiser, no second runtime, on all three
//! shipped platforms.
//!
//! The engine used to be [`oxvg`](https://crates.io/crates/oxvg), a Rust re-implementation of
//! SVGO. It worked, but it was an entire second implementation of the same pipeline: ~10.7 MB of
//! linked code (3.9 MB deflated) versus 2.1 MB (0.9 MB deflated) for QuickJS plus the bundle, and
//! the plugin ships that once per OS. The trade is interpreter speed — measured 16–19x slower
//! than the Rust engine on the same documents, which lands a 3 KB icon at ~14 ms and a 180 KB
//! drawing at ~0.8 s. Optimising is an explicit button press in both front ends rather than
//! something that happens while drawing, so the size win was taken.
//!
//! Configuration is by *plugin name*, not by struct field: [`PASSES`] is the catalogue the
//! dialogs render, and [`OptimizeOptions`] carries the user's on/off choices as SVGO plugin
//! names. The names travel over the sidecar as JSON, so the Kotlin side never needs to know how
//! the engine is implemented — `entry.js` is the only place that translates a name into SVGO
//! configuration.

use std::cell::RefCell;
use std::collections::BTreeMap;

use rquickjs::{Context, Ctx, Function, Runtime};
use serde::{Deserialize, Serialize};

/// SVGO, bundled by esbuild. See `native/svgo_bundle/entry.js`.
const SVGO_BUNDLE: &str = include_str!("../assets/svgo.browser.js");

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
/// Every entry is a plugin of SVGO's `preset-default`, which is what makes "tick it back on"
/// possible: the base is the full preset and a choice can only turn plugins *off*. Both halves of
/// that are guarded by tests — `every_catalogue_name_is_a_real_svgo_plugin` catches a name SVGO
/// no longer ships, and `every_catalogue_entry_is_part_of_the_default_preset` catches one that is
/// not in the preset, which SVGO only warns about and which would leave a checkbox doing nothing.
///
/// SVGO 2's standalone `applyTransforms` plugin is deliberately absent: SVGO 3 folded it into
/// `convertPathData` as a parameter, so there is no separate pass left to configure.
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
    OptimizePass { name: "moveElemsAttrsToGroup", label: "Move element attributes to group", group: "Shapes" },
    OptimizePass { name: "moveGroupAttrsToElems", label: "Move group attributes to elements", group: "Shapes" },
];

/// Plugins `preset-default` has switched on that must stay off here.
///
/// Both are on in the stock preset, and both are wrong for a drawing editor:
///
/// * `removeViewBox` deletes the `viewBox`, which is what makes the document scale — the editors
///   re-render through it on every resize, so losing it turns a responsive drawing into a fixed
///   one.
/// * `removeTitle` deletes `<title>`, the accessible name a screen reader announces.
///
/// Forced off rather than offered as checkboxes: they are not optimisations the user would want
/// to trade away, and forcing them needs no per-entry default, so the dialogs keep their simple
/// "everything is on" model.
const NEVER: &[&str] = &["removeViewBox", "removeTitle"];

/// What the user ticked in the settings dialog.
///
/// A name missing from `passes` means *on* — the catalogue's defaults are all on, so an empty map
/// is the stock preset and a map only ever records the exceptions. Unknown names are ignored
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

thread_local! {
    /// The interpreter, built on first use and kept for the life of the thread.
    ///
    /// Evaluating the bundle costs ~80 ms — more than most documents take to optimise — so it
    /// must not be paid per call, and the settings dialog optimises again on every "OK". Both
    /// callers are single-threaded (the sidecar's request loop and the app's UI thread), so one
    /// engine per thread is enough. A [`Context`] holds its own clone of the [`Runtime`], so
    /// storing it alone keeps both alive.
    static ENGINE: RefCell<Option<Context>> = const { RefCell::new(None) };
}

/// Runs `f` against the thread's engine, creating it if this is the first call.
fn with_engine<T>(f: impl FnOnce(&Context) -> Result<T, String>) -> Result<T, String> {
    ENGINE.with(|slot| {
        if slot.borrow().is_none() {
            let runtime = Runtime::new().map_err(|e| format!("QuickJS runtime: {e}"))?;
            let context = Context::full(&runtime).map_err(|e| format!("QuickJS context: {e}"))?;
            context
                .with(|ctx| ctx.eval::<(), _>(SVGO_BUNDLE))
                .map_err(|e| format!("SVGO bundle: {e}"))?;
            *slot.borrow_mut() = Some(context);
        }
        let slot = slot.borrow();
        f(slot.as_ref().expect("the engine was just created"))
    })
}

/// Runs the enabled passes over `svg` and serialises the result.
///
/// An error is reported rather than swallowed: the callers show it in a dialog, so the user
/// learns the document could not be optimised instead of seeing the optimiser "do nothing".
pub fn optimize(svg: &str, options: &OptimizeOptions) -> Result<OptimizeResult, String> {
    let before_bytes = svg.len();

    // Only the exceptions travel. `NEVER` is applied on top of the user's choices, so the two
    // plugins that break the editors can never be switched back on by a settings file.
    let mut overrides = serde_json::Map::new();
    for name in NEVER {
        overrides.insert((*name).to_string(), serde_json::Value::Bool(false));
    }
    for pass in PASSES {
        if !options.enabled(pass.name) {
            overrides.insert(pass.name.to_string(), serde_json::Value::Bool(false));
        }
    }
    let overrides = serde_json::Value::Object(overrides).to_string();

    let out = with_engine(|context| {
        context.with(|ctx| {
            let call = || -> rquickjs::Result<String> {
                let optimize: Function = ctx.globals().get("__svgoOptimize")?;
                optimize.call((svg, overrides.as_str()))
            };
            call().map_err(|e| js_error(&ctx, e))
        })
    })?;

    Ok(OptimizeResult {
        after_bytes: out.len(),
        svg: out,
        before_bytes,
        passes: options.enabled_count(),
    })
}

/// Turns a JavaScript failure into the message the dialogs show.
///
/// A thrown exception carries the useful text — SVGO's parser reports
/// `"<input>:1:5: Unexpected close tag"` — and `catch` is what hands it back; the remaining
/// errors (allocation, a missing global) describe themselves.
fn js_error(ctx: &Ctx, error: rquickjs::Error) -> String {
    if matches!(error, rquickjs::Error::Exception) {
        let thrown = ctx.catch();
        if let Some(message) = thrown
            .as_exception()
            .and_then(|exception| exception.message())
        {
            return message;
        }
        return "SVGO threw a non-Error value".to_string();
    }
    error.to_string()
}

#[cfg(test)]
mod tests {
    use super::*;

    const SAMPLE: &str = r##"<?xml version="1.0" encoding="UTF-8"?>
<!-- a comment -->
<svg xmlns="http://www.w3.org/2000/svg" width="200" height="120" viewBox="0 0 200 120">
  <title>a title</title>
  <metadata>generated</metadata>
  <rect id="box-a" x="10.000" y="10" width="80" height="60" fill="#4caf50"/>
  <circle id="dot" cx="150" cy="60" r="30" fill="#e91e63"/>
</svg>"##;

    /// Calls one of the bundle's zero-argument probe globals. Tests only — the shipped build has
    /// no caller for `__svgoPluginNames` or `__svgoTakeWarnings`.
    fn probe<T>(name: &'static str) -> T
    where
        T: for<'js> rquickjs::FromJs<'js>,
    {
        with_engine(|context| {
            context.with(|ctx| {
                let call = || -> rquickjs::Result<T> {
                    let f: Function = ctx.globals().get(name)?;
                    f.call(())
                };
                call().map_err(|e| js_error(&ctx, e))
            })
        })
        .unwrap_or_else(|e| panic!("{name} failed: {e}"))
    }

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

    /// `removeViewBox` and `removeTitle` are on in SVGO's preset and would both be destructive
    /// here, so [`NEVER`] has to win over the preset even though neither is a catalogue entry (a
    /// user therefore cannot switch them on, either).
    #[test]
    fn the_viewbox_and_the_title_are_never_removed() {
        let result = optimize(SAMPLE, &OptimizeOptions::default()).expect("optimise");
        assert!(result.svg.contains("viewBox"), "{}", result.svg);
        assert!(result.svg.contains("<title>"), "{}", result.svg);
    }

    /// SVGO's parser tolerates a document that merely stops mid-element (it reports "Unexpected
    /// end" and SVGO deliberately ignores that one), but a genuinely broken document has to come
    /// back as an error rather than as a half-parsed file the editor would then load.
    #[test]
    fn mismatched_tags_report_an_error() {
        let error = match optimize("<svg><rect></svg>", &OptimizeOptions::default()) {
            Ok(result) => panic!("expected an error, got {}", result.svg),
            Err(error) => error,
        };
        assert!(error.contains("Unexpected close tag"), "{error}");
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

    /// A catalogue typo used to be a compile error against the Rust engine's fields. Against
    /// SVGO it would be silent — an unknown name is not an error — so the names are checked
    /// against the plugin list the bundle itself reports.
    #[test]
    fn every_catalogue_name_is_a_real_svgo_plugin() {
        let known: Vec<String> = probe("__svgoPluginNames");
        for name in PASSES
            .iter()
            .map(|p| p.name)
            .chain(NEVER.iter().copied())
        {
            assert!(known.iter().any(|k| k == name), "SVGO has no plugin {name}");
        }
    }

    /// The other half of the same guard: an override for a plugin that is *not* part of
    /// `preset-default` is only warned about, so a catalogue entry that stopped being part of the
    /// preset would leave its checkbox doing nothing at all.
    #[test]
    fn every_catalogue_entry_is_part_of_the_default_preset() {
        let _: Vec<String> = probe("__svgoTakeWarnings");

        for pass in PASSES {
            let mut options = OptimizeOptions::default();
            options.passes.insert(pass.name.to_string(), false);
            optimize(SAMPLE, &options).expect("optimise");
            let warnings: Vec<String> = probe("__svgoTakeWarnings");
            assert!(
                warnings.is_empty(),
                "switching {} off did nothing: {warnings:?}",
                pass.name
            );
        }
    }
}
