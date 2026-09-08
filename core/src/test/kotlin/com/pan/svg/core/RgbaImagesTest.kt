package com.pan.svg.core

import java.awt.image.BufferedImage
import java.awt.image.DataBufferInt
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class RgbaImagesTest {
    @Test
    fun `packs premultiplied rgba into argb_pre raster`() {
        // pixel 0: opaque red (r=255,g=0,b=0,a=255) -> 0xFFFF0000
        // pixel 1: half-alpha white premultiplied (128,128,128,128) -> 0x80808080
        val rgba =
            byteArrayOf(
                0xFF.toByte(), 0x00, 0x00, 0xFF.toByte(),
                0x80.toByte(), 0x80.toByte(), 0x80.toByte(), 0x80.toByte(),
            )
        val img = RgbaImages.fromRgba(rgba, 2, 1)
        assertEquals(BufferedImage.TYPE_INT_ARGB_PRE, img.type)
        assertEquals(2, img.width)
        assertEquals(1, img.height)
        val raster = (img.raster.dataBuffer as DataBufferInt).data
        assertEquals(0xFFFF0000.toInt(), raster[0])
        assertEquals(0x80808080.toInt(), raster[1])
    }

    @Test
    fun `fully transparent pixels stay transparent`() {
        val img = RgbaImages.fromRgba(byteArrayOf(0, 0, 0, 0), 1, 1)
        val raster = (img.raster.dataBuffer as DataBufferInt).data
        assertEquals(0, raster[0])
    }

    @Test
    fun `round-trips through getRGB for opaque colors`() {
        // Opaque colors are unaffected by premultiplication, so getRGB must
        // reproduce the source color exactly.
        val rgba =
            byteArrayOf(
                0x4C.toByte(), 0xAF.toByte(), 0x50.toByte(), 0xFF.toByte(),
            )
        val img = RgbaImages.fromRgba(rgba, 1, 1)
        assertEquals(0xFF4CAF50.toInt(), img.getRGB(0, 0))
    }

    @Test
    fun `rejects mismatched buffer or degenerate size`() {
        assertThrows(IllegalArgumentException::class.java) { RgbaImages.fromRgba(ByteArray(3), 1, 1) }
        assertThrows(IllegalArgumentException::class.java) { RgbaImages.fromRgba(ByteArray(4), 0, 1) }
        assertThrows(IllegalArgumentException::class.java) { RgbaImages.fromRgba(ByteArray(4), 1, 0) }
    }
}
