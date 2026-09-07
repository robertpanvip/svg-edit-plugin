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
