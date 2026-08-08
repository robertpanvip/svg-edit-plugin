package com.example.svgeditor.core

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

    override fun layoutJson(svg: String): String = Samples.LAYOUT_JSON
}
