package com.pan.svg.core

import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.awt.Point
import java.awt.event.MouseEvent
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * End-to-end guard for the sidecar hit-test path (the plugin's production pointer route, used
 * whenever a bundled `svg_easy_sidecar` exists). Reproduces the report "clicking the middle ring
 * band of a transformed icon selects nothing / the wrong element": the ring's leaf sat under an
 * id-less usvg wrapper group, which the Rust hit test refused to descend through.
 *
 * Runs only when a real sidecar binary is present (local native builds / plugin CI which
 * downloads the sidecar into `target/sidecar/<os>/`); otherwise it is skipped.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SidecarRingHitTest {
    private lateinit var bridge: ResvgBridge
    private lateinit var sidecar: Path

    @BeforeAll
    private fun loadNative() {
        val lib = findNativeLibrary()
        Assumptions.assumeTrue(lib != null, "native lib not built")
        bridge = ResvgBridge.load(lib!!.toString())
        val sc = findSidecar()
        Assumptions.assumeTrue(sc != null, "sidecar binary not built")
        sidecar = sc!!
    }

    private fun px(
        p: SvgEditorPanel,
        x: Double,
        y: Double,
    ): Point =
        Point(
            (p.debugOffsetX() + x * p.debugViewScale()).toInt(),
            (p.debugOffsetY() + y * p.debugViewScale()).toInt(),
        )

    private fun singleClick(
        p: SvgEditorPanel,
        docX: Double,
        docY: Double,
    ) {
        val c = p.debugCanvas()
        val pt = px(p, docX, docY)
        c.dispatchEvent(MouseEvent(c, MouseEvent.MOUSE_PRESSED, 0, MouseEvent.BUTTON1_DOWN_MASK, pt.x, pt.y, 1, false, MouseEvent.BUTTON1))
        c.dispatchEvent(MouseEvent(c, MouseEvent.MOUSE_RELEASED, 0, 0, pt.x, pt.y, 1, false, MouseEvent.BUTTON1))
    }

    private fun pressAt(
        p: SvgEditorPanel,
        docX: Double,
        docY: Double,
        count: Int = 1,
    ) {
        val c = p.debugCanvas()
        val pt = px(p, docX, docY)
        c.dispatchEvent(MouseEvent(c, MouseEvent.MOUSE_PRESSED, 0, MouseEvent.BUTTON1_DOWN_MASK, pt.x, pt.y, count, false, MouseEvent.BUTTON1))
    }

    private fun releaseAt(
        p: SvgEditorPanel,
        docX: Double,
        docY: Double,
        count: Int = 1,
    ) {
        val c = p.debugCanvas()
        val pt = px(p, docX, docY)
        c.dispatchEvent(MouseEvent(c, MouseEvent.MOUSE_RELEASED, 0, 0, pt.x, pt.y, count, false, MouseEvent.BUTTON1))
    }

    private fun clickedAt(
        p: SvgEditorPanel,
        docX: Double,
        docY: Double,
        count: Int,
    ) {
        val c = p.debugCanvas()
        val pt = px(p, docX, docY)
        c.dispatchEvent(MouseEvent(c, MouseEvent.MOUSE_CLICKED, 0, 0, pt.x, pt.y, count, false, MouseEvent.BUTTON1))
    }

    private fun dragAt(
        p: SvgEditorPanel,
        docX: Double,
        docY: Double,
    ) {
        val c = p.debugCanvas()
        val pt = px(p, docX, docY)
        c.dispatchEvent(MouseEvent(c, MouseEvent.MOUSE_DRAGGED, 0, MouseEvent.BUTTON1_DOWN_MASK, pt.x, pt.y, 0, false, MouseEvent.BUTTON1))
    }

    @Test
    fun `sidecar single click on the ring band selects the ring leaf`() {
        SidecarClient(listOf(sidecar.toString())).use { sc ->
            val p = SvgEditorPanel(bridge, asyncRendering = false, sidecar = sc)
            p.loadSvg(RingIconSvg.TEXT)
            p.debugSetViewportSize(800, 800)
            p.fitView()
            // The ring bbox is (279..621, 351..693); its band wraps it at radii ~98..171.
            singleClick(p, 590.0, 522.0) // right band
            val sel = p.selectedElementId
            assertNotNull(sel, "clicking the ring band through the sidecar must select it")
            val selNode =
                p.layout.elements
                    .firstOrNull { it.id.ifBlank { it.nodeId.toString() } == sel }
                    ?.nodeId
            assertTrue(selNode == 3L, "ring band click must pick the ring leaf (node 3), got $selNode")
            // A click on the small gear (top-right, bbox 658..984 x 313..641) must select the
            // gear leaf (node 2) instead — proving the pointer maps to the exact shape.
            singleClick(p, 760.0, 340.0)
            val gearSel = p.selectedElementId
            assertNotNull(gearSel, "clicking the gear through the sidecar must select it")
            val gearNode =
                p.layout.elements
                    .firstOrNull { it.id.ifBlank { it.nodeId.toString() } == gearSel }
                    ?.nodeId
            assertTrue(gearNode == 2L, "gear click must pick the gear leaf (node 2), got $gearNode")
            p.dispose()
        }
    }

    @Test
    fun `sidecar double-click and drag keep the ring selected throughout`() {
        SidecarClient(listOf(sidecar.toString())).use { sc ->
            val p = SvgEditorPanel(bridge, asyncRendering = false, sidecar = sc)
            p.loadSvg(RingIconSvg.TEXT)
            p.debugSetViewportSize(800, 800)
            p.fitView()
            val ringKey = "3"
            // Real double-click sequence on the ring band. The SECOND press must not drop the
            // selection: the controller's element id was re-bound to a layout copy whose blank
            // source id failed selectOnly, so the outline jumped away mid-double-click.
            pressAt(p, 590.0, 522.0); releaseAt(p, 590.0, 522.0)
            clickedAt(p, 590.0, 522.0, 1)
            pressAt(p, 590.0, 522.0, 2)
            assertTrue(p.selectedElementId == ringKey, "2nd press of the double-click must keep the ring, got ${p.selectedElementId}")
            releaseAt(p, 590.0, 522.0, 2)
            clickedAt(p, 590.0, 522.0, 2)
            assertTrue(p.selectedElementId == ringKey, "double-click must end with the ring selected, got ${p.selectedElementId}")
            // Drag the ring by (60,30) — the same element must stay selected every frame.
            val before = p.svgSource
            pressAt(p, 590.0, 522.0)
            dragAt(p, 620.0, 537.0)
            dragAt(p, 650.0, 552.0)
            assertTrue(p.selectedElementId == ringKey, "mid-drag must still be the ring, got ${p.selectedElementId}")
            releaseAt(p, 650.0, 552.0)
            assertTrue(p.selectedElementId == ringKey, "after release the ring must stay selected, got ${p.selectedElementId}")
            assertTrue(p.svgSource != before, "dragging the ring band must commit a move")
            p.dispose()
        }
    }

    private fun assertTrue(cond: Boolean, msg: String) {
        org.junit.jupiter.api.Assertions.assertTrue(cond, msg)
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
        val candidates =
            listOf(
                base.resolve("release").resolve(name),
                base.resolve("debug").resolve(name),
                base.resolve("sidecar").resolve("linux").resolve(name),
                base.resolve("sidecar").resolve("windows").resolve(name),
                base.resolve("sidecar").resolve("macos").resolve(name),
            )
        return candidates.firstOrNull { Files.isExecutable(it) }
    }
}
