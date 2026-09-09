package com.pan.svg.core

import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.awt.Point
import java.awt.event.MouseEvent
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/** TEMP diagnostic - sidecar drag flips selection? */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SidecarDragDiagTempTest {
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

    private fun px(p: SvgEditorPanel, x: Double, y: Double): Point =
        Point((p.debugOffsetX() + x * p.debugViewScale()).toInt(), (p.debugOffsetY() + y * p.debugViewScale()).toInt())

    private fun me(c: java.awt.Component, id: Int, x: Int, y: Int, down: Boolean) =
        MouseEvent(c, id, 0, if (down) MouseEvent.BUTTON1_DOWN_MASK else 0, x, y, 1, false, MouseEvent.BUTTON1)

    @Test
    fun `diag sidecar drag ring`() {
        SidecarClient(listOf(sidecar.toString())).use { sc ->
            val p = SvgEditorPanel(bridge, asyncRendering = false, sidecar = sc)
            p.loadSvg(RingIconSvg.TEXT)
            p.debugSetViewportSize(800, 800)
            p.fitView()
            val c = p.debugCanvas()
            val band = px(p, 590.0, 522.0)
            println("scale=${p.debugViewScale()} band px=$band")
            // select via single click on band
            c.dispatchEvent(me(c, MouseEvent.MOUSE_PRESSED, band.x, band.y, true))
            c.dispatchEvent(me(c, MouseEvent.MOUSE_RELEASED, band.x, band.y, false))
            println("after click selected=${p.selectedElementId} ids=${p.selectedElementIds}")
            val beforeBox = p.layout.elements.firstOrNull { it.nodeId == 3L }?.let { "(${it.x},${it.y}..${it.right},${it.bottom})" }
            println("ring box before=$beforeBox")
            // drag from band by +60,+60 then release
            val to = Point(band.x + 60, band.y + 60)
            c.dispatchEvent(me(c, MouseEvent.MOUSE_PRESSED, band.x, band.y, true))
            c.dispatchEvent(me(c, MouseEvent.MOUSE_DRAGGED, to.x, to.y, true))
            println("  mid-drag selected=${p.selectedElementId} ids=${p.selectedElementIds}")
            c.dispatchEvent(me(c, MouseEvent.MOUSE_RELEASED, to.x, to.y, false))
            val selAfter = p.selectedElementId
            val afterBox = p.layout.elements.firstOrNull { it.nodeId == 3L }?.let { "(${it.x},${it.y}..${it.right},${it.bottom})" }
            println("after drag selected=$selAfter ids=${p.selectedElementIds}")
            println("ring box after=$afterBox")
            val elOfSel = p.layout.elements.firstOrNull { it.id.ifBlank { it.nodeId.toString() } == selAfter }
            println("selected resolves to nodeId=${elOfSel?.nodeId} box=${elOfSel?.let { "(${it.x},${it.y}..${it.right},${it.bottom})" }}")
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
        val name = "svg_easy_sidecar"
        val base = Paths.get(System.getProperty("user.dir"), "..", "native", "resvg_bridge", "target")
        val candidates = listOf(base.resolve("release").resolve(name), base.resolve("debug").resolve(name))
        return candidates.firstOrNull { Files.isExecutable(it) }
    }
}
