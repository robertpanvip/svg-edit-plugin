package com.pan.svg.core

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class SvgoTest {
    @Test
    fun `optimizePasses parses name label and group preserving order`() {
        FakeSidecar().use { sc ->
            sc.optimizePassesReply =
                listOf(
                    linkedMapOf("name" to "removeDoctype", "label" to "Remove doctype", "group" to "Document"),
                    linkedMapOf("name" to "removeComments", "label" to "Remove comments", "group" to "Document"),
                    linkedMapOf("name" to "convertShapeToPath", "label" to "Convert shape to path", "group" to "Shapes"),
                )
            val passes = sc.optimizePasses()
            assertEquals(3, passes.size)
            assertEquals("removeDoctype", passes[0].name)
            assertEquals("Remove doctype", passes[0].label)
            assertEquals("Document", passes[0].group)
            assertEquals("convertShapeToPath", passes[2].name)
            assertEquals(listOf("Document", "Document", "Shapes"), passes.map { it.group })
        }
    }

    @Test
    fun `optimize parses svg byte counts and pass count`() {
        FakeSidecar().use { sc ->
            sc.optimizeReply =
                linkedMapOf("svg" to "<svg/>", "beforeBytes" to 182L, "afterBytes" to 134L, "passes" to 34)
            val result = sc.optimize("<svg></svg>", emptyMap())
            assertEquals("<svg/>", result.svg)
            assertEquals(182L, result.beforeBytes)
            assertEquals(134L, result.afterBytes)
            assertEquals(34, result.passes)
        }
    }

    @Test
    fun `optimize sends the options map through unchanged`() {
        FakeSidecar().use { sc ->
            val options = linkedMapOf("removeComments" to false, "cleanupIds" to true)
            sc.optimize(Samples.SIMPLE, options)
            val params = sc.paramsOf("optimize")
            assertEquals(Samples.SIMPLE, params["svg"])
            @Suppress("UNCHECKED_CAST")
            val sent = params["options"] as Map<String, Boolean>
            assertEquals(options, sent)
        }
    }

    @Test
    fun `optimize surfaces a malformed reply as SidecarException`() {
        FakeSidecar().use { sc ->
            sc.optimizeReply = "not-a-map"
            assertThrows(SidecarException::class.java) { sc.optimize("<svg/>", emptyMap()) }
        }
    }

    @Test
    fun `optimize surfaces an RPC error as SidecarException`() {
        FakeSidecar().use { sc ->
            sc.failOn = "optimize"
            assertThrows(SidecarException::class.java) { sc.optimize("<svg/>", emptyMap()) }
        }
    }

    @Test
    fun `saved bytes and percent handle shrink growth and empty input`() {
        val shrunk = SvgoResult("<svg/>", 182L, 134L, 34)
        assertEquals(48L, shrunk.savedBytes)
        assertEquals(48.0 / 182.0 * 100.0, shrunk.savedPercent, 1e-9)

        val grown = SvgoResult("<svg/>", 100L, 130L, 12)
        assertEquals(-30L, grown.savedBytes)
        assertTrue(grown.savedPercent < 0.0)

        val empty = SvgoResult("<svg/>", 0L, 0L, 0)
        assertEquals(0L, empty.savedBytes)
        assertEquals(0.0, empty.savedPercent, 0.0)
    }

    @Test
    fun `formatBytes renders human readable sizes`() {
        assertEquals("182 B", formatBytes(182L))
        assertEquals("1.0 KB", formatBytes(1024L))
        assertEquals("1.2 KB", formatBytes(1229L))
        assertEquals("1.0 MB", formatBytes(1024L * 1024L))
        assertEquals("-48 B", formatBytes(-48L))
    }
}
