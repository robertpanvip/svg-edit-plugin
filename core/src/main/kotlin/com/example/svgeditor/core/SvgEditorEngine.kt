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
        val others = layout.elements.filter { it.id.isNotBlank() && it.id != id }.map { it.id }
        val w = if (renderW > 0) renderW else imageWidth
        val h = if (renderH > 0) renderH else imageHeight
        if (w <= 0 || h <= 0) {
            clearLayers()
            return
        }
        val bgSvg = SvgUtils.hideElement(svg, id)
        var fgSvg = svg
        for (oid in others) fgSvg = SvgUtils.hideElement(fgSvg, oid)
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

    /** Move an element (by its SVG `id`) by `(dx, dy)` canvas units, then re-render. */
    fun moveElement(
        id: String,
        dx: Double,
        dy: Double,
    ): Boolean {
        if (dx == 0.0 && dy == 0.0) return false
        val updated = SvgUtils.applyTranslate(svg, id, dx, dy)
        if (updated == svg) return false
        svg = updated
        reload()
        return true
    }

    /**
     * Resize/move an element so its absolute bounding box becomes `(x, y, w, h)` (SVG units).
     * Implemented by rewriting the element's `transform` to `matrix(...)` derived from its
     * original geometry, so the edit is exact and composes with prior transforms.
     */
    fun setElementBox(
        id: String,
        x: Double,
        y: Double,
        w: Double,
        h: Double,
    ): Boolean {
        if (w <= 0 || h <= 0) return false
        val g = geom[id] ?: return false
        val (origMatrix, origBox) = g
        val sx = w / origBox.w
        val sy = h / origBox.h
        // Affine X maps the original box -> the target box (scale then translate).
        val xform =
            doubleArrayOf(
                sx,
                0.0,
                0.0,
                sy,
                x - sx * origBox.x,
                y - sy * origBox.y,
            )
        val combined = affineMultiply(xform, origMatrix)
        val attr = SvgUtils.matrixAttr(combined)
        val updated = SvgUtils.setTransform(svg, id, attr)
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
