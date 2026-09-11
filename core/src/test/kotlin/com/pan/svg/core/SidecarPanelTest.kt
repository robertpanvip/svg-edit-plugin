package com.pan.svg.core

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
    fun `release lands at the release point even when it lags the last drag frame`() {
        FakeSidecar().use { fake ->
            val panel = SvgEditorPanel(FakeSvgRenderer(), sidecar = fake)
            panel.loadSvg(Samples.SIMPLE)
            fake.hitNodeId = 3L
            panel.debugSetSnapEnabled(false) // isolate the frame-sync check from edge snapping
            panel.debugDoubleClick(50, 40)
            // can drop the final frame, so the last preview sits behind the released pointer. The
            // commit must use the actual release position, not the stale drag frame.
            panel.debugPressDrag(50, 40, 90, 80)
            panel.debugReleaseAt(95, 84)
            val params = fake.paramsOf("commit")
            assertEquals(listOf(1.0, 0.0, 0.0, 1.0, 45.0, 44.0), params["matrix"])
            panel.dispose()
        }
    }

    @Test
    fun `identity drags skip the commit round trip`() {
        FakeSidecar().use { fake ->
            val panel = SvgEditorPanel(FakeSvgRenderer(), sidecar = fake)
            panel.loadSvg(Samples.SIMPLE)
            fake.hitNodeId = 3L
            val before = panel.svgSource
            panel.debugDoubleClick(50, 40)
            panel.debugDrag(Point(50, 40), Point(50, 40))
            assertEquals(0, fake.count("commit"))
            // A zero-delta drag must not rewrite the document (note: the sample itself contains a
            // `<g transform=...>`, so compare snapshots rather than searching for the substring).
            assertEquals(before, panel.svgSource)
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

    @Test
    fun `second drag after a commit does not grab the stale selection box`() {
        FakeSidecar().use { fake ->
            val panel = SvgEditorPanel(FakeSvgRenderer(), sidecar = fake)
            panel.loadSvg(Samples.SIMPLE)
            // After the first commit the adopted layout has box-a at (50,50) (moved +40,+40),
            // while the controller's selection snapshot would still reference (10,10).
            fake.commitElements =
                listOf(
                    linkedMapOf("nodeId" to 1L, "tag" to "rect", "id" to "bg", "x" to 0.0, "y" to 0.0, "w" to 200.0, "h" to 120.0),
                    linkedMapOf("nodeId" to 2L, "tag" to "rect", "id" to "box-a", "x" to 50.0, "y" to 50.0, "w" to 80.0, "h" to 60.0),
                    linkedMapOf("nodeId" to 3L, "tag" to "circle", "id" to "dot", "x" to 120.0, "y" to 30.0, "w" to 60.0, "h" to 60.0),
                )
            // Select and drag box-a from its old (10,10) spot by (+40,+40).
            fake.hitNodeId = 2L
            panel.debugDoubleClick(50, 40)
            panel.debugDrag(Point(50, 40), Point(90, 80))
            assertEquals(1, fake.count("commit"))
            assertEquals("box-a", panel.selectedElementId)
            val moved = panel.layout.byId("box-a")!!
            assertEquals(50.0, moved.x, 1e-9)
            assertEquals(50.0, moved.y, 1e-9)

            // Second drag: press at (20,20), which only lies inside box-a's OLD bounding box
            // (10..90 x 10..70) — after the move that area is empty. It must NOT start a move
            // from the stale snapshot (which made the element jump under the cursor); pressing
            // there now deselects instead.
            fake.hitNodeId = null
            panel.debugDrag(Point(20, 20), Point(21, 21))
            assertEquals(1, fake.count("commit"), "a press on the vacated old box area must not move box-a")
            assertNull(panel.selectedElementId)
            panel.dispose()
        }
    }

    @Test
    fun `group drag pre-renders layers covering every selected member`() {
        FakeSidecar().use { fake ->
            val panel = SvgEditorPanel(FakeSvgRenderer(), sidecar = fake)
            panel.loadSvg(Samples.SIMPLE)
            // Multi-select box-a + dot (dot = primary), then press-drag the dot: the panel must
            // request ONE drag-layer pair whose background hides BOTH members.
            panel.debugSetSelection(listOf("box-a", "dot"))
            fake.hitNodeId = 3L
            panel.debugPressDrag(150, 60, 170, 70) // press on the dot, keep the button held
            assertEquals(listOf("box-a", "dot"), panel.selectedElementIds)
            // The settled multi-selection already pre-heats the group pair, and the press keeps
            // using it — every call must cover BOTH members.
            val calls = fake.count("startDragGroup")
            assertTrue(calls >= 1, "a group drag must pre-render group layers")
            val nodeIds =
                (fake.paramsOf("startDragGroup", calls - 1)["nodeIds"] as List<*>)
                    .map { (it as Number).toLong() }
                    .toSet()
            assertEquals(setOf(2L, 3L), nodeIds, "the group pair must cover both selected members")
            panel.dispose()
        }
    }
}
