package com.example.svgeditor.core

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.awt.Point

class SvgEditorPanelTest {
    @Test
    fun `loads svg and parses the layout`() {
        val panel = SvgEditorPanel(FakeSvgRenderer())
        panel.loadSvg(Samples.SIMPLE)
        assertEquals(5, panel.layout.elements.size)
        assertTrue(panel.layout.elements.map { it.id }.containsAll(listOf("bg", "box-a", "dot", "grp", "inner")))
    }

    @Test
    fun `hit test maps panel pixels to the layout`() {
        val panel = SvgEditorPanel(FakeSvgRenderer())
        panel.loadSvg(Samples.SIMPLE)
        // In headless tests the canvas is not laid out, so viewScale stays 1.0 with zero offset
        // (panel pixels == SVG coordinates).
        assertEquals("box-a", panel.elementAt(50, 40))
        assertEquals("dot", panel.elementAt(150, 60))
        assertNull(panel.elementAt(-5, -5))
    }

    @Test
    fun `double-click selects the element and shows control points`() {
        val panel = SvgEditorPanel(FakeSvgRenderer())
        panel.loadSvg(Samples.SIMPLE)
        panel.debugDoubleClick(50, 40) // center of box-a
        assertEquals("box-a", panel.selectedElementId)
    }

    @Test
    fun `drag on the selected element rewrites it in the source`() {
        val panel = SvgEditorPanel(FakeSvgRenderer())
        panel.loadSvg(Samples.SIMPLE)
        // Select first (double-click), then drag box-a from (50,40) to (90,80) => delta (+40,+40).
        panel.debugDoubleClick(50, 40)
        panel.debugDrag(Point(50, 40), Point(90, 80))
        assertTrue(panel.svgSource.contains("transform="), "drag should have inserted a transform")
        assertTrue(panel.svgSource.contains("translate(40, 40)"))
    }

    @Test
    fun `zoom changes the zoom factor`() {
        val panel = SvgEditorPanel(FakeSvgRenderer())
        panel.loadSvg(Samples.SIMPLE)
        val z0 = panel.getZoom()
        panel.zoomIn()
        assertTrue(panel.getZoom() > z0, "zoom should increase after zoomIn")
        panel.fitView()
        assertEquals(1.0, panel.getZoom(), 1e-9)
    }
}
