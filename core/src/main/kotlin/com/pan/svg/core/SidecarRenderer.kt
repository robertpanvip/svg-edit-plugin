package com.pan.svg.core

import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

/**
 * [SvgRenderer] backed by the `svg_easy_sidecar` process instead of an in-process native library.
 *
 * The plugin used to bundle BOTH engines for every platform — the JNA cdylib (render + layout +
 * colour-ID picking) and the sidecar (exact hit test / drag pre-render / commit). They are two
 * statically linked copies of resvg+usvg that together accounted for ~82% of the install zip,
 * while only one of them ever ran. Serving render and layout from the sidecar too removes the
 * cdylib — and with it JNA plus the `jnidispatch` extraction dance — from the plugin entirely,
 * leaving a single engine to keep in sync.
 *
 * Colour-ID picking is deliberately NOT provided: exact hit testing already goes through the
 * sidecar's own `hitTest`, so the panel's pick-canvas fallback is unused here, and omitting it
 * avoids a third stateless render variant that nothing would call.
 */
class SidecarRenderer(private val client: SidecarClient) : SvgRenderer {
    /** PNG bytes at the fit size. Only the standalone app/tests use PNG; the panel wants RGBA. */
    override fun render(
        svg: String,
        fitW: Int,
        fitH: Int,
    ): RenderResult {
        val r = renderRgba(svg, fitW, fitH)
        return RenderResult(toPng(r), r.width, r.height)
    }

    override fun renderRgba(
        svg: String,
        fitW: Int,
        fitH: Int,
    ): RgbaResult = client.renderFitRgba(svg, fitW, fitH)

    override fun layoutJson(svg: String): String = Json.write(client.layoutOf(svg))

    private fun toPng(r: RgbaResult): ByteArray {
        val img: BufferedImage = RgbaImages.fromRgba(r.rgba, r.width, r.height)
        val out = ByteArrayOutputStream()
        ImageIO.write(img, "png", out)
        return out.toByteArray()
    }
}
