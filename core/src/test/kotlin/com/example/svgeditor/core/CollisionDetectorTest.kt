package com.example.svgeditor.core

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class CollisionDetectorTest {
    private val layout = SvgLayout.parse(Samples.LAYOUT_JSON)

    @Test
    fun `point inside box-a hits box-a`() {
        assertEquals("box-a", CollisionDetector.hitTest(layout, 50.0, 40.0)?.id)
    }

    @Test
    fun `point over the dot hits the dot`() {
        // dot bbox is x:120..180, y:30..90
        assertEquals("dot", CollisionDetector.hitTest(layout, 150.0, 60.0)?.id)
    }

    @Test
    fun `point in empty space hits nothing`() {
        assertNull(CollisionDetector.hitTest(layout, -10.0, -10.0))
    }

    @Test
    fun `intersecting returns elements overlapping a rectangle`() {
        val hits = CollisionDetector.intersecting(layout, 0.0, 0.0, 30.0, 30.0)
        // box-a (10..90,10..70) and bg overlap the top-left 30x30 square.
        assertTrue(hits.any { it.id == "bg" })
        assertTrue(hits.any { it.id == "box-a" })
        assertFalse(hits.any { it.id == "dot" })
    }

    @Test
    fun `overlaps detects element-to-element overlap`() {
        val boxA = layout.byId("box-a")!!
        val bg = layout.byId("bg")!!
        val dot = layout.byId("dot")!!
        assertTrue(CollisionDetector.overlaps(boxA, bg))
        assertFalse(CollisionDetector.overlaps(boxA, dot))
    }
}
