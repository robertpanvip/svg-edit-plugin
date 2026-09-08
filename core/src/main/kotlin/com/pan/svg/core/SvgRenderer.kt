package com.pan.svg.core

/**
 * Result of rendering an SVG to **raw premultiplied RGBA8** pixels
 * (row-major, `width * height * 4` bytes). This is the hot-path output used by the
 * async render pipeline — no PNG encode/decode in between.
 */
data class RgbaResult(
    val rgba: ByteArray,
    val width: Int,
    val height: Int,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is RgbaResult) return false
        return width == other.width && height == other.height && rgba.contentEquals(other.rgba)
    }

    override fun hashCode(): Int = 31 * (width + 31 * height) + rgba.contentHashCode()
}

/** Abstraction over the SVG rendering + layout backend.
 *
 * `ResvgBridge` is the production implementation (JNA → `resvg_bridge` native lib). Tests
 * inject a fake implementation so the engine and panel can run without a Rust toolchain.
 */
interface SvgRenderer {
    fun render(
        svg: String,
        fitW: Int = 0,
        fitH: Int = 0,
    ): RenderResult

    /** Render `svg` to raw premultiplied RGBA8 pixels (see [RgbaResult]). */
    fun renderRgba(
        svg: String,
        fitW: Int = 0,
        fitH: Int = 0,
    ): RgbaResult

    fun layoutJson(svg: String): String
}

/**
 * Optional capability of an [SvgRenderer]: produce a **colour-ID hit canvas** for `svg`.
 *
 * Every paint leaf (path / image / text, groups transparent) is rasterized flat in a colour
 * encoding its 1-based position within the layout's paint-leaf list (the elements whose
 * `kind != "group"`), so a point hit is a single pixel sample — path-exact, independent of any
 * sidecar, and the precise replacement for the bounding-box fallback. Text and images are
 * approximated by their absolute bounding box, exactly as the geometry hit tests do.
 *
 * Implementations return `null` (or throw) when the canvas cannot be produced; callers then
 * fall back to the bounding-box detector.
 */
interface SvgPickRenderer {
    /** Render `svg` to a colour-ID canvas at the same fit size as [SvgRenderer.renderRgba]. */
    fun renderPickRgba(
        svg: String,
        fitW: Int = 0,
        fitH: Int = 0,
    ): RgbaResult?
}
