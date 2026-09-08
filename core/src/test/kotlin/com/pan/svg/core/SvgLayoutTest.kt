package com.pan.svg.core

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

    private val sidecarJson =
        """
        {"width":200,"height":120,"elements":[
          {"nodeId":1,"tag":"rect","id":"bg","x":0,"y":0,"w":200,"h":120},
          {"nodeId":2,"tag":"circle","id":"dot","x":120,"y":30,"w":60,"h":60}
        ]}
        """.trimIndent()

    @Test
    fun `parses the sidecar element format with defaults`() {
        val layout = SvgLayout.parse(sidecarJson)
        assertEquals(200.0, layout.width)
        assertEquals(120.0, layout.height)
        assertEquals(2, layout.elements.size)
        val bg = layout.elements[0]
        assertEquals(0, bg.index)
        assertEquals(1L, bg.nodeId)
        assertEquals("rect", bg.kind)
        assertEquals("bg", bg.id)
        assertTrue(bg.transform.contentEquals(doubleArrayOf(1.0, 0.0, 0.0, 1.0, 0.0, 0.0)))
    }

    @Test
    fun `byNodeId resolves sidecar elements and rejects zero`() {
        val layout = SvgLayout.parse(sidecarJson)
        assertEquals("bg", layout.byNodeId(1L)?.id)
        assertEquals("dot", layout.byNodeId(2L)?.id)
        assertNull(layout.byNodeId(0L))
        assertNull(layout.byNodeId(99L))
    }

    @Test
    fun `byId falls back to node id strings only for sidecar layouts`() {
        val sidecar = SvgLayout.parse(sidecarJson)
        assertEquals("dot", sidecar.byId("2")?.id)
        assertNull(sidecar.byId("77"))
        val legacy = SvgLayout.parse(Samples.LAYOUT_JSON)
        assertNull(legacy.byId("0"))
        assertNull(legacy.byId("1"))
        assertEquals("dot", legacy.byId("dot")?.id)
    }
}
