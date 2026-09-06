package com.example.svgeditor.core

import java.awt.image.BufferedImage
import java.awt.image.DataBufferInt

/**
 * Converts raw **premultiplied RGBA8** bytes (the native layout of tiny-skia pixmaps,
 * produced by `SvgRenderer.renderRgba`) into a [BufferedImage] of type
 * [BufferedImage.TYPE_INT_ARGB_PRE].
 *
 * tiny-skia stores each pixel as `R,G,B,A` bytes, already premultiplied — the same
 * semantics as Java's `TYPE_INT_ARGB_PRE`. We compose the ARGB_PRE int
 * `(a shl 24) or (r shl 16) or (g shl 8) or b` per pixel, so the result is
 * correct on any JVM endianness and requires no color conversion, no
 * `ImageIO` PNG decode and no intermediate streams.
 */
object RgbaImages {
    fun fromRgba(
        rgba: ByteArray,
        width: Int,
        height: Int,
    ): BufferedImage {
        require(width > 0 && height > 0) { "invalid image size ${width}x$height" }
        require(rgba.size == width * height * 4) {
            "rgba buffer must be exactly width*height*4 bytes (got ${rgba.size} for ${width}x$height)"
        }
        val img = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB_PRE)
        val raster = (img.raster.dataBuffer as DataBufferInt).data
        val pixelCount = width * height
        var src = 0
        var dst = 0
        while (dst < pixelCount) {
            val r = rgba[src].toInt() and 0xFF
            val g = rgba[src + 1].toInt() and 0xFF
            val b = rgba[src + 2].toInt() and 0xFF
            val a = rgba[src + 3].toInt() and 0xFF
            raster[dst] = (a shl 24) or (r shl 16) or (g shl 8) or b
            src += 4
            dst++
        }
        return img
    }
}
