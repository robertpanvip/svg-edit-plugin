package com.example.svgeditor.core

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.awt.Point

class SidecarPanelTest {
    @Test
    fun `load adopts the sidecar layout with node ids`() {
        FakeSidecar().use { fake ->
            val panel = SvgEditorPanel(FakeSvgRenderer(), sidecar = fake)
            panel.loadSvg(Samples.SIMPLE)
            assertEquals(1, fake.count("open"))
            assertEquals(listOf(1L, 2L, 3L), panel.layout.elements.map { it.nodeId })
            panel.dispose()
        }
    }

    @Test
    fun `hit test prefers the sidecar over local bounding boxes`() {
        FakeSidecar().use { fake ->
            val panel = SvgEditorPanel(FakeSvgRenderer(), sidecar = fake)
            panel.loadSvg(Samples.SIMPLE)
            fake.hitNodeId = 3L
            assertEquals("dot", panel.elementAt(50, 40))
            fake.hitNodeId = null
            assertNull(panel.elementAt(50, 40))
            panel.dispose()
        }
    }

    @Test
    fun `drag commits through the sidecar and adopts the returned svg`() {
        FakeSidecar().use { fake ->
            val panel = SvgEditorPanel(FakeSvgRenderer(), sidecar = fake)
            panel.loadSvg(Samples.SIMPLE)
            fake.hitNodeId = 3L
            panel.debugDoubleClick(50, 40)
            panel.debugDrag(Point(50, 40), Point(90, 80))
            assertEquals(fake.commitSvg, panel.svgSource)
            val params = fake.paramsOf("commit")
            assertEquals(3L, (params["nodeId"] as Number).toLong())
            assertEquals(listOf(1.0, 0.0, 0.0, 1.0, 40.0, 40.0), params["matrix"])
            panel.dispose()
        }
    }

    @Test
    fun `identity drags skip the commit round trip`() {
        FakeSidecar().use { fake ->
            val panel = SvgEditorPanel(FakeSvgRenderer(), sidecar = fake)
            panel.loadSvg(Samples.SIMPLE)
            fake.hitNodeId = 3L
            panel.debugDoubleClick(50, 40)
            panel.debugDrag(Point(50, 40), Point(50, 40))
            assertEquals(0, fake.count("commit"))
            assertFalse(panel.svgSource.contains("transform="))
            panel.dispose()
        }
    }

    @Test
    fun `open failure permanently falls back to the legacy pipeline`() {
        FakeSidecar().use { fake ->
            val panel = SvgEditorPanel(FakeSvgRenderer(), sidecar = fake)
            fake.failOn = "open"
            panel.loadSvg(Samples.SIMPLE)
            assertEquals(2, fake.count("open"))
            assertEquals(5, panel.layout.elements.size)
            assertTrue(panel.layout.elements.all { it.nodeId == 0L })
            assertEquals("box-a", panel.elementAt(50, 40))
            panel.debugDrag(Point(50, 40), Point(90, 80))
            assertTrue(panel.svgSource.contains("translate(40, 40)"))
            assertEquals(0, fake.count("commit"))
            panel.dispose()
        }
    }

    @Test
    fun `commit failure falls back to the legacy in-process edit`() {
        FakeSidecar().use { fake ->
            val panel = SvgEditorPanel(FakeSvgRenderer(), sidecar = fake)
            panel.loadSvg(Samples.SIMPLE)
            fake.hitNodeId = 3L
            fake.failOn = "commit"
            panel.debugDrag(Point(50, 40), Point(90, 80))
            assertEquals(2, fake.count("commit"))
            assertTrue(panel.svgSource.contains("translate(40, 40)"))
            panel.dispose()
        }
    }

    @Test
    fun `startDrag images flow into the drag layer`() {
        FakeSidecar().use { fake ->
            val panel = SvgEditorPanel(FakeSvgRenderer(), sidecar = fake)
            panel.loadSvg(Samples.SIMPLE)
            fake.hitNodeId = 3L
            panel.debugDoubleClick(50, 40)
            panel.debugPressDrag(50, 40, 90, 80)
            assertEquals("dot", panel.selectedElementId)
            panel.dispose()
        }
    }
}
