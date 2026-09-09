package com.pan.svg.core

import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * A plain click-to-select must never change the *content* pixels of the canvas: the frame at
 * rest is the exact idle frame plus the selection overlay. Any switch into the bg+fg layered
 * composite at rest (or a stale/zoom-mismatched layer pair) re-pastes the shape through a second
 * rasterisation path and reads as the subtle "the shape jumps/jitters when I select it" bug.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SelectionCompositeTest {
    private lateinit var bridge: ResvgBridge
    private var sidecar: Path? = null

    @BeforeAll
    private fun loadNative() {
        val lib = findNativeLibrary()
        Assumptions.assumeTrue(lib != null, "native lib not built")
        bridge = ResvgBridge.load(lib!!.toString())
        sidecar = findSidecar()
    }

    private fun snapshot(p: SvgEditorPanel): BufferedImage {
        p.setSize(800, 800)
        p.doLayout()
        p.debugCanvas().setSize(800, 800)
        val img = BufferedImage(800, 800, BufferedImage.TYPE_INT_ARGB)
        p.debugRenderTo(img, 1.0)
        return img
    }

    /** Count differing pixels whose SELECTED-frame colour is content-like (dark, not overlay). */
    private fun contentDiffCount(
        idle: BufferedImage,
        selected: BufferedImage,
    ): Int {
        var n = 0
        for (y in 0 until idle.height) for (x in 0 until idle.width) {
            if (idle.getRGB(x, y) != selected.getRGB(x, y)) {
                val c = selected.getRGB(x, y)
                val r = c ushr 16 and 0xFF
                val g = c ushr 8 and 0xFF
                val b = c and 0xFF
                // Content fill in the test icons is #666 (102). The selection overlay (accent
                // #836DFF frame + white handles + their AA blends) always keeps a channel high,
                // so anything dark in all three channels is content the overlay cannot produce.
                if (r < 90 && g < 90 && b < 90) n++
            }
        }
        return n
    }

    @Test
    fun `clicking to select does not displace content pixels`() {
        val scPath = sidecar
        Assumptions.assumeTrue(scPath != null, "no sidecar")
        SidecarClient(listOf(scPath.toString())).use { sc ->
            val p = SvgEditorPanel(bridge, asyncRendering = false, sidecar = sc)
            p.loadSvg(RingIconSvg.TEXT)
            p.debugSetViewportSize(800, 800)
            p.fitView()
            val idle = snapshot(p)
            // Click the ring band (doc 590,522) -> selects the ring leaf, layers get cached.
            val c = p.debugCanvas()
            val px = (p.debugOffsetX() + 590.0 * p.debugViewScale()).toInt()
            val py = (p.debugOffsetY() + 522.0 * p.debugViewScale()).toInt()
            c.dispatchEvent(java.awt.event.MouseEvent(c, java.awt.event.MouseEvent.MOUSE_PRESSED, 0, java.awt.event.MouseEvent.BUTTON1_DOWN_MASK, px, py, 1, false, java.awt.event.MouseEvent.BUTTON1))
            c.dispatchEvent(java.awt.event.MouseEvent(c, java.awt.event.MouseEvent.MOUSE_RELEASED, 0, 0, px, py, 1, false, java.awt.event.MouseEvent.BUTTON1))
            org.junit.jupiter.api.Assertions.assertEquals("3", p.selectedElementId)
            val selected = snapshot(p)
            // Any dark content pixel that appears/disappears between the two frames means the
            // shape itself was re-pasted at a different place — the selection-frame overlay alone
            // can never add or remove such pixels.
            org.junit.jupiter.api.Assertions.assertEquals(
                0,
                contentDiffCount(idle, selected),
                "selecting changed content pixels: the layered composite or a stale layer pair " +
                    "displaced the shape",
            )
            p.dispose()
        }
    }

    @Test
    fun `marquee selecting a group does not displace content pixels`() {
        val scPath = sidecar
        Assumptions.assumeTrue(scPath != null, "no sidecar")
        SidecarClient(listOf(scPath.toString())).use { sc ->
            val p = SvgEditorPanel(bridge, asyncRendering = false, sidecar = sc)
            p.loadSvg(RingIconSvg.TEXT)
            p.debugSetViewportSize(800, 800)
            p.fitView()
            val idle = snapshot(p)
            p.setTool(SvgEditorPanel.Tool.MARQUEE)
            val c = p.debugCanvas()
            val press = java.awt.Point((p.debugOffsetX() + 150.0 * p.debugViewScale()).toInt(), (p.debugOffsetY() + 150.0 * p.debugViewScale()).toInt())
            val release = java.awt.Point((p.debugOffsetX() + 900.0 * p.debugViewScale()).toInt(), (p.debugOffsetY() + 900.0 * p.debugViewScale()).toInt())
            c.dispatchEvent(java.awt.event.MouseEvent(c, java.awt.event.MouseEvent.MOUSE_PRESSED, 0, java.awt.event.MouseEvent.BUTTON1_DOWN_MASK, press.x, press.y, 1, false, java.awt.event.MouseEvent.BUTTON1))
            c.dispatchEvent(java.awt.event.MouseEvent(c, java.awt.event.MouseEvent.MOUSE_DRAGGED, 0, java.awt.event.MouseEvent.BUTTON1_DOWN_MASK, release.x, release.y, 0, false, java.awt.event.MouseEvent.BUTTON1))
            c.dispatchEvent(java.awt.event.MouseEvent(c, java.awt.event.MouseEvent.MOUSE_RELEASED, 0, 0, release.x, release.y, 1, false, java.awt.event.MouseEvent.BUTTON1))
            org.junit.jupiter.api.Assertions.assertTrue(p.selectedElementIds.size >= 2)
            val selected = snapshot(p)
            org.junit.jupiter.api.Assertions.assertEquals(
                0,
                contentDiffCount(idle, selected),
                "marquee select changed content pixels",
            )
            p.dispose()
        }
    }

    @Test
    fun `delete removes the selected sidecar leaf from the document`() {
        val scPath = sidecar
        Assumptions.assumeTrue(scPath != null, "no sidecar")
        SidecarClient(listOf(scPath.toString())).use { sc ->
            val p = SvgEditorPanel(bridge, asyncRendering = false, sidecar = sc)
            p.loadSvg(RingIconSvg.TEXT)
            p.debugSetViewportSize(800, 800)
            p.fitView()
            val before = p.layout.elements.size
            org.junit.jupiter.api.Assertions.assertTrue(before >= 2)
            // Select the ring leaf (blank source id -> node-id selection key), then Delete.
            val c = p.debugCanvas()
            val px = (p.debugOffsetX() + 590.0 * p.debugViewScale()).toInt()
            val py = (p.debugOffsetY() + 522.0 * p.debugViewScale()).toInt()
            c.dispatchEvent(java.awt.event.MouseEvent(c, java.awt.event.MouseEvent.MOUSE_PRESSED, 0, java.awt.event.MouseEvent.BUTTON1_DOWN_MASK, px, py, 1, false, java.awt.event.MouseEvent.BUTTON1))
            c.dispatchEvent(java.awt.event.MouseEvent(c, java.awt.event.MouseEvent.MOUSE_RELEASED, 0, 0, px, py, 1, false, java.awt.event.MouseEvent.BUTTON1))
            org.junit.jupiter.api.Assertions.assertNotNull(p.selectedElementId)
            p.deleteSelected()
            org.junit.jupiter.api.Assertions.assertNull(p.selectedElementId, "delete clears the selection")
            org.junit.jupiter.api.Assertions.assertEquals(
                before - 1,
                p.layout.elements.size,
                "the sidecar leaf must be removed from the layout",
            )
            org.junit.jupiter.api.Assertions.assertFalse(
                p.svgSource.contains("translate(-62.060606"),
                "the deleted ring's source path must leave the document",
            )
            p.dispose()
        }
    }

    private fun nativeLibName(): String {
        val os = System.getProperty("os.name").lowercase()
        return when {
            os.contains("win") -> "resvg_bridge.dll"
            os.contains("mac") || os.contains("darwin") -> "libresvg_bridge.dylib"
            else -> "libresvg_bridge.so"
        }
    }

    private fun findNativeLibrary(): Path? {
        val base = Paths.get(System.getProperty("user.dir"), "..", "native", "resvg_bridge", "target")
        val name = nativeLibName()
        for (profile in listOf("debug", "release")) {
            val p = base.resolve(profile).resolve(name)
            if (Files.exists(p)) return p
        }
        return null
    }

    private fun findSidecar(): Path? {
        val os = System.getProperty("os.name").lowercase()
        val name = if (os.contains("win")) "svg_easy_sidecar.exe" else "svg_easy_sidecar"
        val base = Paths.get(System.getProperty("user.dir"), "..", "native", "resvg_bridge", "target")
        return listOf(
            base.resolve("release").resolve(name),
            base.resolve("sidecar").resolve("linux").resolve(name),
        ).firstOrNull { Files.isExecutable(it) }
    }
}
