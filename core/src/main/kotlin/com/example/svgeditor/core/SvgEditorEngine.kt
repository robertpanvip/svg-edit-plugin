package com.example.svgeditor.core

/**
 * The editor engine: the single source of truth that wires `resvg` rendering, layout
 * extraction and source-level editing together.
 *
 * It is deliberately IntelliJ/Swing-free — the plugin panel only reads `png` / `layout`
 * and calls [moveElement] / [setElementBox]. This keeps the whole engine unit-testable on a
 * plain JVM.
 *
 * Two rendering paths:
 *  - [reload] re-parses the SVG (used on load / after an edit). It renders at the **last
 *    requested device size** so re-renders stay sharp (the panel drives this size from the
 *    panel's true pixel resolution + DPI, eliminating the upscaling blur).
 *  - [renderAt] re-renders the PNG at an explicit device-pixel size without re-parsing the
 *    layout (used on zoom / resize for a crisp, fast redraw).
 */
class SvgEditorEngine(
    private val renderer: SvgRenderer,
) {
    var svg: String = ""
        private set

    /** Current SVG source (after edits). */
    val svgSource: String get() = svg
    var layout: SvgLayout = SvgLayout(0.0, 0.0, emptyList())
        private set
    var png: ByteArray = ByteArray(0)
        private set
    var imageWidth: Int = 0
        private set
    var imageHeight: Int = 0
        private set

    /** Last requested render pixel size (0 = natural size). Keeps re-renders crisp. */
    private var renderW = 0
    private var renderH = 0

    /**
     * Geometry captured at [load]: for each element id, its original absolute transform and
     * bounding box. Resize editing rewrites the transform relative to this, so repeated
     * edits stay consistent regardless of prior moves/scales.
     */
    private val geom = mutableMapOf<String, Pair<DoubleArray, InteractionController.Box>>()

    fun load(svgText: String) {
        svg = svgText
        renderW = 0
        renderH = 0
        reload()
        captureGeom()
    }

    private fun reload() {
        val r = renderer.render(svg, renderW, renderH)
        png = r.png
        imageWidth = r.width
        imageHeight = r.height
        layout = SvgLayout.parse(renderer.layoutJson(svg))
    }

    /**
     * Re-render the PNG at an explicit device-pixel size (the panel computes this from the
     * canvas resolution × DPI so the result is 1:1 on screen → no aliasing). Layout is
     * unchanged by a pure re-render.
     */
    fun renderAt(
        w: Int,
        h: Int,
    ) {
        require(w > 0 && h > 0) { "render size must be positive" }
        renderW = w
        renderH = h
        val r = renderer.render(svg, w, h)
        png = r.png
        imageWidth = r.width
        imageHeight = r.height
    }

    // ---- layered rendering (smooth, resvg-free drag preview) -------------------
    var bgPng: ByteArray = ByteArray(0)
        private set
    var fgPng: ByteArray = ByteArray(0)
        private set

    /**
     * Build two cached rasters so dragging never re-rasterizes with resvg:
     *  - `bgPng`: every element EXCEPT [id] (the dragged element is composited separately).
     *  - `fgPng`: ONLY [id] visible.
     * During a drag the panel just blits `bgPng` (static) and offsets/scales `fgPng`, giving a
     * 60fps follow-cursor feel instead of the old "yellow preview box only" jank.
     */
    fun selectForEditing(id: String) {
        if (id.isBlank()) {
            clearLayers()
            return
        }
        val w = if (renderW > 0) renderW else imageWidth
        val h = if (renderH > 0) renderH else imageHeight
        if (w <= 0 || h <= 0) {
            clearLayers()
            return
        }
        val bgSvg = SvgUtils.hideElement(svg, id)
        // Solo the selected element (keep it + its ancestor groups) so a nested element is not
        // hidden along with an ancestor group. This keeps the foreground layer correct for
        // elements inside <g> containers — fixing "drag preview vanishes for grouped elements".
        val fgSvg = SvgUtils.soloElement(svg, id)
        bgPng = renderer.render(bgSvg, w, h).png
        fgPng = renderer.render(fgSvg, w, h).png
    }

    fun clearLayers() {
        bgPng = ByteArray(0)
        fgPng = ByteArray(0)
    }

    private fun captureGeom() {
        geom.clear()
        for (el in layout.elements) {
            if (el.id.isBlank()) continue
            geom[el.id] = el.transform.copyOf(6) to
                InteractionController.Box(el.x, el.y, el.width, el.height)
        }
    }

    /**
     * Move an element (by its SVG `id`) by `(dx, dy)` **canvas (root) units**, then re-render.
     *
     * Prepending a plain `translate(dx, dy)` to the element's own transform moves it in its
     * *local* space, which an ancestor group then scales/rotates — so the committed position
     * drifts from the drag preview (computed in root space). That drift is exactly the "the box
     * jumps on release" symptom for transformed/nested elements.
     *
     * The fix: prepend `P = G⁻¹ ∘ Translate(dx,dy) ∘ G`, where `G` is the element's ancestor-group
     * transform (parsed from the source). Prepending `P` to the element's own transform makes its
     * absolute transform become `Translate(dx,dy) ∘ G ∘ E`, i.e. the element moves by exactly
     * `(dx, dy)` in root space — matching the preview — whether or not the element or its groups
     * carry scale/rotation.
     */
    fun moveElement(
        id: String,
        dx: Double,
        dy: Double,
    ): Boolean {
        if (dx == 0.0 && dy == 0.0) return false
        if (layout.byId(id) == null) return false
        val g = SvgUtils.ancestorTransform(svg, id) // ancestor group transform (root space)
        val gInv = SvgUtils.affineInverse(g)
        val t = doubleArrayOf(1.0, 0.0, 0.0, 1.0, dx, dy)
        // P = G⁻¹ ∘ T ∘ G : applied to the element's own transform, this moves it by (dx,dy) in
        // root/canvas space regardless of any group scale/rotation or the element's own transform.
        val p = SvgUtils.affineMultiply(gInv, SvgUtils.affineMultiply(t, g))
        // Emit a plain translate when the move is a pure translation (no group scale/rotation) so
        // the source stays human-readable and existing translate-based edits keep working.
        val pStr =
            if (SvgUtils.isTranslationMatrix(p)) {
                "translate(${SvgUtils.fmt(p[4])}, ${SvgUtils.fmt(p[5])})"
            } else {
                SvgUtils.matrixAttr(p)
            }
        val updated = SvgUtils.prependMatrix(svg, id, pStr)
        if (updated == svg) return false
        svg = updated
        reload()
        return true
    }

    /**
     * Rotate the element about the canvas-space point `(cx, cy)` by `angleDeg` degrees. The
     * rotation is PREPENDED to the element's existing transform, so repeated rotations compose
     * (each is about the element's centre, which never moves under rotation) and any prior
     * translate/scale is preserved.
     */
    fun rotateElement(
        id: String,
        angleDeg: Double,
        cx: Double,
        cy: Double,
    ): Boolean {
        if (angleDeg == 0.0) return false
        val updated = SvgUtils.prependRotate(svg, id, angleDeg, cx, cy)
        if (updated == svg) return false
        svg = updated
        reload()
        return true
    }

    /**
     * Resize/move an element so its absolute bounding box becomes `(x, y, w, h)` (SVG units).
     * Implemented by PREPENDING a `matrix(...)` that maps the element's CURRENT box onto the
     * target box, so the edit composes with any prior transform (translate/rotate/scale) instead
     * of overwriting it — e.g. rotating then resizing keeps the rotation.
     */
    fun setElementBox(
        id: String,
        x: Double,
        y: Double,
        w: Double,
        h: Double,
    ): Boolean {
        if (w <= 0 || h <= 0) return false
        val el = layout.byId(id) ?: return false
        val cbw = el.width
        val cbh = el.height
        if (cbw <= 0 || cbh <= 0) return false
        val sx = w / cbw
        val sy = h / cbh
        // Affine maps the current box -> the target box (scale then translate).
        val xform =
            doubleArrayOf(
                sx,
                0.0,
                0.0,
                sy,
                x - sx * el.x,
                y - sy * el.y,
            )
        val attr = SvgUtils.matrixAttr(xform)
        val updated = SvgUtils.prependMatrix(svg, id, attr)
        if (updated == svg) return false
        svg = updated
        reload()
        return true
    }
}

/** Multiply two 2x3 affine matrices `[a,b,c,d,e,f]` (column-major: [[a,c,e],[b,d,f]]). */
private fun affineMultiply(
    m1: DoubleArray,
    m2: DoubleArray,
): DoubleArray {
    val a = m1[0] * m2[0] + m1[2] * m2[1]
    val b = m1[1] * m2[0] + m1[3] * m2[1]
    val c = m1[0] * m2[2] + m1[2] * m2[3]
    val d = m1[1] * m2[2] + m1[3] * m2[3]
    val e = m1[0] * m2[4] + m1[2] * m2[5] + m1[4]
    val f = m1[1] * m2[4] + m1[3] * m2[5] + m1[5]
    return doubleArrayOf(a, b, c, d, e, f)
}
