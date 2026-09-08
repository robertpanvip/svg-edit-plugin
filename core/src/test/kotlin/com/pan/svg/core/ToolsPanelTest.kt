package com.pan.svg.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.Point

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
}
