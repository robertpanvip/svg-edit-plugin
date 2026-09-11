package com.pan.svg.core

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class SidecarClientTest {
    @Test
    fun `ping parses version and protocol`() {
        FakeSidecar().use { sc ->
            val info = sc.ping()
            assertEquals("fake-1.0", info.version)
            assertEquals(1, info.protocol)
        }
    }

    @Test
    fun `open builds a layout carrying node ids`() {
        FakeSidecar().use { sc ->
            val layout = sc.open(Samples.SIMPLE)
            assertEquals(200.0, layout.width)
            assertEquals(120.0, layout.height)
            assertEquals(listOf(1L, 2L, 3L), layout.elements.map { it.nodeId })
            assertEquals("rect", layout.elements[0].kind)
            assertEquals("box-a", layout.elements[1].id)
        }
    }

    @Test
    fun `hit test returns the node id or null`() {
        FakeSidecar().use { sc ->
            sc.hitNodeId = 7L
            assertEquals(7L, sc.hitTest(50.0, 40.0, 5.0))
            sc.hitNodeId = null
            assertNull(sc.hitTest(50.0, 40.0, 5.0))
        }
    }

    @Test
    fun `start drag decodes both images plus the frame size`() {
        FakeSidecar().use { sc ->
            val imgs = sc.startDrag(2L, 200, 120, 1.0, 0.0, 0.0)
            assertTrue(imgs.background.width >= 1)
            assertTrue(imgs.ghost.height >= 1)
            assertEquals(200, imgs.width)
            assertEquals(120, imgs.height)
        }
    }

    @Test
    fun `commit returns svg png and fresh elements`() {
        FakeSidecar().use { sc ->
            sc.commitSvg = """<svg id="edited"/>"""
            sc.commitElements =
                listOf(
                    linkedMapOf(
                        "nodeId" to 2L, "tag" to "rect", "id" to "box-a",
                        "x" to 50.0, "y" to 50.0, "w" to 80.0, "h" to 60.0,
                    ),
                )
            val c = sc.commit(2L, listOf(1.0, 0.0, 0.0, 1.0, 40.0, 40.0), 200, 120, 1.0, 0.0, 0.0)
            assertEquals("""<svg id="edited"/>""", c.svg)
            assertTrue(c.png.width >= 1)
            assertEquals(2L, c.elements.single().nodeId)
            assertEquals(50.0, c.elements.single().x)
        }
    }

    @Test
    fun `render viewport returns an image`() {
        FakeSidecar().use { sc ->
            val img = sc.renderViewport(200, 120, 1.0, 0.0, 0.0)
            assertTrue(img.width >= 1)
            assertTrue(img.height >= 1)
        }
    }

    @Test
    fun `format sends the source and returns the re-indented svg`() {
        FakeSidecar().use { sc ->
            sc.formatReply = linkedMapOf("svg" to "<svg>\n  <rect/>\n</svg>\n")
            val pretty = sc.format("<svg><rect/></svg>")
            assertEquals("<svg>\n  <rect/>\n</svg>\n", pretty)
            assertEquals("<svg><rect/></svg>", sc.paramsOf("format")["svg"])
        }
    }

    @Test
    fun `format surfaces a malformed reply as SidecarException`() {
        FakeSidecar().use { sc ->
            sc.formatReply = linkedMapOf("nope" to 1)
            assertThrows(SidecarException::class.java) { sc.format("<svg/>") }
        }
    }

    @Test
    fun `failures surface as SidecarException after one restart retry`() {
        FakeSidecar().use { sc ->
            sc.failOn = "ping"
            assertThrows(SidecarException::class.java) { sc.ping() }
            assertEquals(2, sc.count("ping"))
        }
    }

    @Test
    fun `close is idempotent and rejects further exchanges`() {
        val sc = FakeSidecar()
        sc.ping()
        sc.close()
        sc.close()
        assertThrows(IllegalStateException::class.java) { sc.ping() }
    }
}
