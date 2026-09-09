package com.pan.svg.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.Color
import java.awt.Point
import java.awt.image.BufferedImage
import javax.swing.Icon
import javax.swing.JPanel

/**
 * Strict separation of the two interaction tools on [SvgEditorPanel]:
 *  - MOVE (default): press/drag an element = select + move; clicking empty space deselects and
 *    NEVER opens a rubber band.
 *  - MARQUEE: pressing anywhere starts a rubber band; a drag selects the topmost element inside
 *    it, a click selects the exact element under the pointer — and nothing is ever moved.
 */
class ToolsPanelTest {
    @Test
    fun `default tool is MOVE`() {
        val panel = SvgEditorPanel(FakeSvgRenderer())
        panel.loadSvg(Samples.SIMPLE)
        assertEquals(SvgEditorPanel.Tool.MOVE, panel.getTool())
        panel.dispose()
    }

    @Test
    fun `MOVE tool drags and edits elements`() {
        val panel = SvgEditorPanel(FakeSvgRenderer())
        panel.loadSvg(Samples.SIMPLE)
        panel.debugDoubleClick(50, 40)
        panel.debugDrag(Point(50, 40), Point(90, 80))
        assertTrue(panel.svgSource.contains("translate(40, 40)"))
        panel.dispose()
    }

    @Test
    fun `MOVE tool on empty space deselects without dragging anything`() {
        val panel = SvgEditorPanel(FakeSvgRenderer())
        panel.loadSvg(Samples.SIMPLE)
        panel.debugDoubleClick(50, 40) // select box-a
        assertEquals("box-a", panel.selectedElementId)
        val before = panel.svgSource
        // Far outside the document: in MOVE mode this is a plain click-to-deselect — no marquee,
        // no document change.
        panel.debugDrag(Point(320, 320), Point(330, 330))
        assertNull(panel.selectedElementId)
        assertEquals(before, panel.svgSource)
        panel.dispose()
    }

    @Test
    fun `MARQUEE tool drag starting on an element selects instead of moving it`() {
        val panel = SvgEditorPanel(FakeSvgRenderer())
        panel.loadSvg(Samples.SIMPLE)
        panel.setTool(SvgEditorPanel.Tool.MARQUEE)
        val before = panel.svgSource
        // Rubber band from INSIDE box-a across it: selects the topmost intersecting element and
        // must NOT rewrite the document (no move/resize commit).
        panel.debugDrag(Point(30, 20), Point(90, 80))
        assertEquals("box-a", panel.selectedElementId)
        assertEquals(before, panel.svgSource)
        panel.dispose()
    }

    @Test
    fun `MARQUEE tool click selects the exact element under the pointer`() {
        val panel = SvgEditorPanel(FakeSvgRenderer())
        panel.loadSvg(Samples.SIMPLE)
        panel.setTool(SvgEditorPanel.Tool.MARQUEE)
        panel.debugDrag(Point(150, 60), Point(150, 60)) // click on the dot, no drag
        assertEquals("dot", panel.selectedElementId)
        panel.debugDrag(Point(320, 320), Point(320, 320)) // click empty -> deselect
        assertNull(panel.selectedElementId)
        panel.dispose()
    }

    @Test
    fun `MARQUEE tool rubber band over empty canvas selects nothing`() {
        val panel = SvgEditorPanel(FakeSvgRenderer())
        panel.loadSvg(Samples.SIMPLE)
        panel.setTool(SvgEditorPanel.Tool.MARQUEE)
        val before = panel.svgSource
        // Band entirely in the empty area beyond the document (no element there).
        panel.debugDrag(Point(200, 200), Point(260, 260))
        assertNull(panel.selectedElementId)
        assertEquals(before, panel.svgSource)
        panel.dispose()
    }

    /**
     * Renders [icon] onto a (theme-simulating) background and returns the set of painted pixels.
     * `paintIcon` picks its ink from the target component's background luminance — pass an
     * opaque panel so the theme is fully deterministic, both light and dark.
     */
    private fun paintedPixels(
        icon: Icon,
        dark: Boolean,
    ): Set<Long> {
        val bg = if (dark) Color(0x2B, 0x2B, 0x2B) else Color.WHITE
        val target = JPanel().apply {
            isOpaque = true
            background = bg
        }
        val img = BufferedImage(icon.iconWidth, icon.iconHeight, BufferedImage.TYPE_INT_ARGB)
        val g = img.createGraphics()
        g.color = bg
        g.fillRect(0, 0, img.width, img.height)
        icon.paintIcon(target, g, 0, 0)
        g.dispose()
        val painted = HashSet<Long>()
        for (y in 0 until img.height) {
            for (x in 0 until img.width) {
                if ((img.getRGB(x, y) and 0x00FF_FFFF) != (bg.rgb and 0x00FF_FFFF)) {
                    painted.add((x.toLong() shl 32) or y.toLong())
                }
            }
        }
        return painted
    }

    @Test
    fun `Move and Box Select tool icons are visible and distinct on both themes`() {
        for (dark in listOf(false, true)) {
            val move = paintedPixels(EditorIcons.moveTool(), dark)
            val box = paintedPixels(EditorIcons.boxSelectTool(), dark)
            assertTrue(move.isNotEmpty(), "Move icon must be visible on ${if (dark) "dark" else "light"} bg")
            assertTrue(box.isNotEmpty(), "Box Select icon must be visible on ${if (dark) "dark" else "light"} bg")
            assertNotEquals(move, box, "the two tool icons must look different")
        }
    }
}
