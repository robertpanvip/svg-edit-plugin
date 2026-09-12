// The SVGO engine, bundled for QuickJS.
//
// `optimize.rs` embeds the esbuild output (`resvg_bridge/assets/svgo.browser.js`) and calls the
// globals defined below. SVGO is JavaScript, so a JS engine is the only way to run it from Rust;
// this file is the seam between the two.
//
// The entry is `svgo/lib/svgo.js`, NOT the package default. `svgo`'s `main` is
// `lib/svgo-node.js`, which `require`s `fs`/`path`/`url` — Node builtins that esbuild cannot
// resolve for a browser target and that QuickJS does not have at all. `lib/svgo.js` is the
// browser-safe core: same parser, same plugin pipeline, no CLI. Dropping `lib/svgo-node.js` and
// `lib/svgo/coa.js` leaves the optimiser itself untouched.
//
// Build:  npm install && npm run build
const { optimize } = require("svgo/lib/svgo.js");
const { builtin } = require("svgo/lib/builtin.js");

// SVGO reports "you are trying to configure X which is not part of preset-default" and its
// infinite-loop guards through `console`, which QuickJS does not provide at all. Collecting the
// messages instead of dropping them serves two purposes: nothing throws a `ReferenceError` where
// SVGO expects a console to exist, and the Rust tests can prove that each catalogue entry really
// belongs to the preset. An override for a plugin the preset does not carry is only warned
// about — never an error — so without this check a mistyped settings checkbox would silently do
// nothing.
const warnings = [];
globalThis.console = {
  warn: (...args) => warnings.push(args.join(" ")),
  error: (...args) => warnings.push(args.join(" ")),
  log: () => {},
  info: () => {},
};

/** Every plugin this SVGO build ships, preset or not. Used by the catalogue tests. */
globalThis.__svgoPluginNames = () => builtin.map((plugin) => plugin.name);

/** Drains the warnings collected since the last call. */
globalThis.__svgoTakeWarnings = () => warnings.splice(0, warnings.length);

/**
 * Optimises `svg` and returns the result.
 *
 * The Rust side owns every configuration decision, so this only translates them into SVGO's
 * config shape.
 *
 * @param {string} svg the document, as the editor holds it.
 * @param {string} overridesJson JSON object of `{pluginName: false}` for each pass to switch off.
 *   SVGO's `preset-default` is the base, so a pass is disabled by overriding it.
 * @returns {string}
 */
globalThis.__svgoOptimize = function (svg, overridesJson) {
  const overrides = JSON.parse(overridesJson);
  return optimize(svg, {
    multipass: false,
    plugins: [{ name: "preset-default", params: { overrides } }],
  }).data;
};
