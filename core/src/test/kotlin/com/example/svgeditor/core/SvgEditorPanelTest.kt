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

    @Test
    fun `load assigns synthetic ids so id-less elements become editable`() {
        val panel = SvgEditorPanel(IdAwareSvgRenderer())
        panel.loadSvg(Samples.NO_ID)
        assertTrue(panel.svgSource.contains("""id="svg-el-1""""))
        assertTrue(
            panel.layout.elements.none { it.id.isBlank() },
            "layout must have no blank ids after the patch",
        )
    }

    @Test
    fun `drag on an element without an id commits the move instead of snapping back`() {
        val panel = SvgEditorPanel(IdAwareSvgRenderer())
        panel.loadSvg(Samples.NO_ID)
        // Same coordinates as the FakeSvgRenderer drag test: (50,40) -> (90,80) => delta (+40,+40).
        panel.debugDrag(Point(50, 40), Point(90, 80))
        assertTrue(
            panel.svgSource.contains("translate(40, 40)"),
            "the move must be committed to the source (no snap-back)",
        )
    }

    @Test
    fun `zoom math fits the viewport and stays stable across zoom steps`() {
        val panel = SvgEditorPanel(IdAwareSvgRenderer())
        panel.debugSetViewportSize(620, 460)
        panel.loadSvg(Samples.SIMPLE)
        panel.fitView()
        val fitPercent = panel.getZoomPercent()
        assertTrue(fitPercent in 100..400, "fit should be a sane percentage, got $fitPercent")
        repeat(3) { panel.zoomIn() }
        assertEquals(fitPercent * 1.728, panel.getZoomPercent().toDouble(), 2.0)
        panel.actualSize()
        assertEquals(100.0, panel.getZoomPercent().toDouble(), 1.0)
    }
}
