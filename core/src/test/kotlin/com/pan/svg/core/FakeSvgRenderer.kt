package com.pan.svg.core

import java.util.Base64

/**
 * A [SvgRenderer] that returns a tiny static PNG and a fixed layout. Used to exercise the
 * engine and panel without a Rust toolchain or the resvg native library.
 */
class FakeSvgRenderer : SvgRenderer {
    // 1x1 transparent PNG, decodable by javax.imageio.
    private val png =
        Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAAC0lEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==",
        )

    override fun render(
        svg: String,
        fitW: Int,
        fitH: Int,
    ): RenderResult = RenderResult(png, 1, 1)

    // 1x1 transparent premultiplied RGBA pixel (matches the static PNG above).
    override fun renderRgba(
        svg: String,
        fitW: Int,
        fitH: Int,
    ): RgbaResult = RgbaResult(ByteArray(4), 1, 1)

    override fun layoutJson(svg: String): String = Samples.LAYOUT_JSON
}

/**
 * A [SvgRenderer] whose layout is derived from the actual SVG source: ids are extracted from the
 * document (in order) and mapped onto the fixed geometry slots of [Samples.LAYOUT_JSON]. This
 * lets tests exercise the empty-id path ([Samples.NO_ID]) that the static [FakeSvgRenderer]
 * cannot express.
 */
class IdAwareSvgRenderer : SvgRenderer {
    private val delegate = FakeSvgRenderer()

    override fun render(
        svg: String,
        fitW: Int,
        fitH: Int,
    ): RenderResult = delegate.render(svg, fitW, fitH)

    override fun renderRgba(
        svg: String,
        fitW: Int,
        fitH: Int,
    ): RgbaResult = delegate.renderRgba(svg, fitW, fitH)

    override fun layoutJson(svg: String): String {
        val ids = idsInDocumentOrder(svg)
        var i = 0
        return Regex(""""id":"([^"]*)"""").replace(Samples.LAYOUT_JSON) {
            val id = if (ids.isEmpty()) null else ids[i % ids.size]
            i++
            """"id":"${id ?: ""}""""
        }
    }

    /** All `id="..."` values in document order, excluding the root `<svg>` tag's attributes. */
    private fun idsInDocumentOrder(svg: String): List<String> {
        val root = Regex("""<svg\b[^>]*""").find(svg)
        return Regex("""\bid\s*=\s*["']([^"']*)["']""")
            .findAll(svg)
            .filter { m -> root == null || !root.range.contains(m.range.first) }
            .map { it.groupValues[1] }
            .toList()
    }
}
