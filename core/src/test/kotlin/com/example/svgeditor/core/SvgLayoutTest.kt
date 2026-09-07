package com.example.svgeditor.core

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class SvgLayoutTest {
    @Test
    fun `parses layout json into elements`() {
        val layout = SvgLayout.parse(Samples.LAYOUT_JSON)
        assertEquals(200.0, layout.width)
        assertEquals(120.0, layout.height)
        assertEquals(5, layout.elements.size)
        val ids = layout.elements.map { it.id }
        assertTrue(ids.containsAll(listOf("bg", "box-a", "dot", "grp", "inner")))
    }

    @Test
    fun `hitTest returns topmost element under the point`() {
        val layout = SvgLayout.parse(Samples.LAYOUT_JSON)
        // (50, 40) is inside box-a (10..90, 10..70) and also inside bg. box-a is on top of bg.
        val hit = layout.hitTest(50.0, 40.0)
        assertEquals("box-a", hit?.id)
    }

    @Test
    fun `hitTest returns null outside everything`() {
        val layout = SvgLayout.parse(Samples.LAYOUT_JSON)
        // (199,119) is inside bg, so it hits bg (the whole canvas is covered by bg).
        assertEquals("bg", layout.hitTest(199.0, 119.0)?.id)
        // A point well outside the canvas hits nothing.
        assertNull(layout.hitTest(250.0, 250.0))
    }

    @Test
    fun `intersecting finds overlapping elements`() {
        val layout = SvgLayout.parse(Samples.LAYOUT_JSON)
        // a rectangle covering the dot's area
        val hits = layout.intersecting(120.0, 30.0, 60.0, 60.0)
        assertTrue(hits.any { it.id == "dot" })
        assertTrue(hits.any { it.id == "bg" })
    }

    @Test
    fun `byId lookup works`() {
        val layout = SvgLayout.parse(Samples.LAYOUT_JSON)
        assertEquals("box-a", layout.byId("box-a")?.id)
        assertNull(layout.byId("does-not-exist"))
    }

    @Test
    fun `full-canvas background is excluded from interaction but content stays selectable`() {
        val raw = SvgLayout.parse(Samples.LAYOUT_JSON)
        assertNotNull(raw.byId("bg"))
        val filtered = raw.withoutFullCanvasBackground()
        // bg covers the whole canvas (0,0,200,120) → dropped from the interactive list.
        assertNull(filtered.byId("bg"))
        // Content elements are untouched.
        assertEquals("box-a", filtered.byId("box-a")?.id)
        assertEquals(4, filtered.elements.size)
        // Empty canvas space no longer swallows clicks; content still hit-tests.
        assertNull(filtered.hitTest(199.0, 119.0))
        assertEquals("box-a", filtered.hitTest(50.0, 40.0)?.id)
    }

    @Test
    fun `tolerance controls how close to the edge counts as background`() {
        val inset = SvgElement(0, "inset", "path", 1.0, 1.0, 198.0, 118.0, doubleArrayOf(1.0, 0.0, 0.0, 1.0, 0.0, 0.0))
        val layout = SvgLayout(200.0, 120.0, listOf(inset))
        // 1 svg-unit inset: dropped at the default tolerance of 1.0 (edge-to-edge coverage)…
        assertNull(layout.withoutFullCanvasBackground().byId("inset"))
        // …but kept when the tolerance is tightened to 0 (must touch the exact edges).
        assertNotNull(layout.withoutFullCanvasBackground(tolerance = 0.0).byId("inset"))
    }

    @Test
    fun `json parser handles nested arrays and escaped strings`() {
        val json = """{"a":[1,2,3],"b":"he said \"hi\"","c":true,"d":null,"e":1.5}"""
        val root = Json.parse(json) as Map<*, *>
        assertEquals(listOf(1L, 2L, 3L), root["a"])
        assertEquals("""he said "hi"""", root["b"])
        assertEquals(true, root["c"])
        assertNull(root["d"])
        assertEquals(1.5, root["e"])
    }
}
