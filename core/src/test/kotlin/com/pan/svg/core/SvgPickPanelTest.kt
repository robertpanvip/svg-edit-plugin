package com.pan.svg.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.awt.Color
import java.awt.Graphics2D
import java.awt.image.BufferedImage
import java.util.Base64

/**
 * Tests for the colour-ID hit canvas wiring in the legacy (no-sidecar) pipeline.
 *
 * Geometry under test (a group whose two children leave a transparent gap INSIDE the group's
 * bounding box, and no background element):
 *   <g id="grp">  <rect id="a" 20,30,60x60/>  <rect id="b" 120,30,60x60/>  </g>
 * The old bounding-box detector returns the GROUP for a click inside that gap; the colour-ID
 * canvas must return nothing there (no paint leaf), and the exact child on the children.
 */

private val TEST_XML =
    """
    <svg xmlns="http://www.w3.org/2000/svg" width="200" height="120" viewBox="0 0 200 120">
      <g id="grp">
        <rect id="a" x="20" y="30" width="60" height="60" fill="#ff0000"/>
        <rect id="b" x="120" y="30" width="60" height="60" fill="#00ff00"/>
      </g>
    </svg>
    """

// Layout in bridge order: [grp(union bbox), a, b] — leaf ordinals (kind != "group") are a=1, b=2.
private val TEST_LAYOUT =
    """
    {"width":200.0,"height":120.0,"elements":[
      {"index":0,"id":"grp","kind":"group","x":20.0,"y":30.0,"width":160.0,"height":60.0,"transform":[1,0,0,1,0,0]},
      {"index":1,"id":"a","kind":"path","x":20.0,"y":30.0,"width":60.0,"height":60.0,"transform":[1,0,0,1,0,0]},
      {"index":2,"id":"b","kind":"path","x":120.0,"y":30.0,"width":60.0,"height":60.0,"transform":[1,0,0,1,0,0]}
    ]}
    """

/** Same layout/render as [PickCanvasRenderer] but WITHOUT the [SvgPickRenderer] capability. */
private class PlainSvgRenderer : SvgRenderer {
    private val png =
        Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAAC0lEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==",
        )

    override fun render(svg: String, fitW: Int, fitH: Int) = RenderResult(png, 1, 1)

    override fun renderRgba(svg: String, fitW: Int, fitH: Int) = RgbaResult(ByteArray(4), 1, 1)

    override fun layoutJson(svg: String): String = TEST_LAYOUT
}

/** Provides [SvgPickRenderer]: paints each paint leaf flat in its ordinal colour. */
private class PickCanvasRenderer(
    private val layout: SvgLayout,
) : SvgRenderer, SvgPickRenderer {
    override fun render(svg: String, fitW: Int, fitH: Int) = PlainSvgRenderer().render(svg, fitW, fitH)

    override fun renderRgba(svg: String, fitW: Int, fitH: Int) = PlainSvgRenderer().renderRgba(svg, fitW, fitH)

    override fun layoutJson(svg: String): String = TEST_LAYOUT

    override fun renderPickRgba(svg: String, fitW: Int, fitH: Int): RgbaResult {
        val scale =
            if (fitW > 0 && fitH > 0) {
                kotlin.math.min(fitW.toDouble() / layout.width, fitH.toDouble() / layout.height)
            } else {
                1.0
            }
        val pw = (layout.width * scale).toInt().coerceAtLeast(1)
        val ph = (layout.height * scale).toInt().coerceAtLeast(1)
        val img = BufferedImage(pw, ph, BufferedImage.TYPE_INT_ARGB_PRE)
        val g: Graphics2D = img.createGraphics()
        g.scale(scale, scale)
        var leaf = 0
        for (el in layout.elements) {
            if (el.kind == "group") continue
            leaf++
            val c = Color((leaf shr 16) and 0xFF, (leaf shr 8) and 0xFF, leaf and 0xFF, 255)
            g.color = c
            g.fillRect(el.x.toInt(), el.y.toInt(), el.width.toInt(), el.height.toInt())
        }
        g.dispose()
        // TYPE_INT_ARGB_PRE data is premultiplied; the colours are opaque so premul == straight.
        val bytes = ByteArray(pw * ph * 4)
        var i = 0
        for (y in 0 until ph) {
            for (x in 0 until pw) {
                val v = img.getRGB(x, y)
                bytes[i++] = ((v shr 16) and 0xFF).toByte()
                bytes[i++] = ((v shr 8) and 0xFF).toByte()
                bytes[i++] = (v and 0xFF).toByte()
                bytes[i++] = ((v ushr 24) and 0xFF).toByte()
            }
        }
        return RgbaResult(bytes, pw, ph)
    }
}

class SvgPickPanelTest {
    @Test
    fun `hit canvas is path-exact - gaps inside a group box select nothing`() {
        val renderer = PickCanvasRenderer(SvgLayout.parse(TEST_LAYOUT))
        val panel = SvgEditorPanel(renderer)
        panel.loadSvg(TEST_XML)
        // Inside the children -> exact leaves (not the enclosing group).
        assertEquals("a", panel.elementAt(50, 60))
        assertEquals("b", panel.elementAt(150, 60))
        // Inside the group's bounding box but in the transparent gap between a and b -> nothing.
        assertNull(panel.elementAt(100, 60))
        panel.dispose()
    }

    @Test
    fun `without a hit canvas the bounding-box fallback still selects the group over the gap`() {
        val panel = SvgEditorPanel(PlainSvgRenderer())
        panel.loadSvg(TEST_XML)
        assertEquals("grp", panel.elementAt(100, 60))
        panel.dispose()
    }
}
