package com.pan.svg.core

import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.awt.event.MouseEvent
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * While a multi-selection is being group-dragged, EVERY member must visibly move together —
 * not just the element under the pointer. The drag preview is a background with all members
 * hidden plus one ghost containing all of them, so mid-drag each member's colour must appear at
 * its ORIGINAL position + delta and disappear from its original position.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class GroupDragVisualTest {
    private lateinit var bridge: ResvgBridge
    private var sidecar: Path? = null

    @BeforeAll
    private fun loadNative() {
        val lib = findNativeLibrary()
        Assumptions.assumeTrue(lib != null, "native lib not built")
        bridge = ResvgBridge.load(lib!!.toString())
        sidecar = findSidecar()
    }

    private val pairSvg =
        """<svg xmlns="http://www.w3.org/2000/svg" width="400" height="200" viewBox="0 0 400 200">
  <rect id="left" x="40" y="60" width="60" height="60" fill="#ff0000"/>
  <rect id="right" x="240" y="60" width="60" height="60" fill="#0000ff"/>
</svg>"""

    private fun px(p: SvgEditorPanel, svgX: Double, svgY: Double): Pair<Int, Int> =
        (p.debugOffsetX() + svgX * p.debugViewScale()).toInt() to
            (p.debugOffsetY() + svgY * p.debugViewScale()).toInt()

    private fun snapshot(p: SvgEditorPanel): BufferedImage {
        // The panel is sized once before the interaction starts; this only paints a frame, so
        // it never triggers a resize -> layer re-request mid-preview.
        val img = BufferedImage(p.width, p.height, BufferedImage.TYPE_INT_ARGB)
        p.debugRenderTo(img, 1.0)
        return img
    }

    private fun isRedish(c: Int): Boolean {
        val r = c ushr 16 and 0xFF
        val g = c ushr 8 and 0xFF
        val b = c and 0xFF
        return r > 200 && g < 100 && b < 100
    }

    private fun isBluish(c: Int): Boolean {
        val r = c ushr 16 and 0xFF
        val g = c ushr 8 and 0xFF
        val b = c and 0xFF
        return b > 200 && r < 100 && g < 100
    }

    private fun press(p: SvgEditorPanel, sx: Double, sy: Double) {
        val (x, y) = px(p, sx, sy)
        val c = p.debugCanvas()
        c.dispatchEvent(
            MouseEvent(c, MouseEvent.MOUSE_PRESSED, 0, MouseEvent.BUTTON1_DOWN_MASK, x, y, 1, false, MouseEvent.BUTTON1),
        )
    }

    private fun drag(p: SvgEditorPanel, sx: Double, sy: Double) {
        val (x, y) = px(p, sx, sy)
        val c = p.debugCanvas()
        c.dispatchEvent(
            MouseEvent(c, MouseEvent.MOUSE_DRAGGED, 0, MouseEvent.BUTTON1_DOWN_MASK, x, y, 0, false, MouseEvent.BUTTON1),
        )
    }

    private fun release(p: SvgEditorPanel, sx: Double, sy: Double) {
        val (x, y) = px(p, sx, sy)
        val c = p.debugCanvas()
        c.dispatchEvent(
            MouseEvent(c, MouseEvent.MOUSE_RELEASED, 0, 0, x, y, 1, false, MouseEvent.BUTTON1),
        )
    }

    @Test
    fun `startDragGroup protocol hides and ghosts both members`() {
        val scPath = sidecar
        Assumptions.assumeTrue(scPath != null, "no sidecar")
        SidecarClient(listOf(scPath.toString())).use { sc ->
            val layout = sc.open(pairSvg)
            val ids = listOf("left", "right").map { id -> layout.byId(id)!!.nodeId }
            val d = sc.startDragGroup(ids, 752, 376, 1.88, 0.0, 0.0)
            org.junit.jupiter.api.Assertions.assertTrue(d.background.width > 0 && d.ghost.width > 0)
            // Left rect centre in the render: svg (70,90) * scale 1.88.
            val sx = (70 * 1.88).toInt()
            val sy = (90 * 1.88).toInt()
            val bg = d.background.getRGB(sx, sy)
            val gh = d.ghost.getRGB(sx, sy)
            org.junit.jupiter.api.Assertions.assertTrue(
                bg ushr 24 and 0xFF < 40,
                "the group background must hide the left member, bg alpha was ${bg ushr 24 and 0xFF}",
            )
            // Sanity: the SINGLE-element background for the left rect must hide it at this pixel.
            val single = sc.startDrag(ids[0], 752, 376, 1.88, 0.0, 0.0)
            val sb = single.background.getRGB(sx, sy)
            org.junit.jupiter.api.Assertions.assertTrue(
                sb ushr 24 and 0xFF < 40,
                "even the single background must hide the left member, alpha ${sb ushr 24 and 0xFF}",
            )
            org.junit.jupiter.api.Assertions.assertTrue(
                (gh ushr 16 and 0xFF) > 200 && (gh and 0xFF) < 100,
                "the group ghost must keep the left member, ghost pixel was " + Integer.toHexString(gh),
            )
        }
    }

    @Test
    fun `both members move together during a group drag`() {
        val scPath = sidecar
        Assumptions.assumeTrue(scPath != null, "no sidecar")
        SidecarClient(listOf(scPath.toString())).use { sc ->
            val p = SvgEditorPanel(bridge, asyncRendering = false, sidecar = sc)
            try {
                p.loadSvg(pairSvg)
                p.debugSetViewportSize(800, 800)
                p.fitView()
                p.setSize(800, 800)
                p.doLayout()
                p.debugCanvas().setSize(800, 800)

                // Box-select both rects (full-document band), then switch to the MOVE tool.
                p.setTool(SvgEditorPanel.Tool.MARQUEE)
                press(p, -10.0, -10.0)
                drag(p, 420.0, 220.0)
                release(p, 420.0, 220.0)
                p.setTool(SvgEditorPanel.Tool.MOVE)
                val selected = p.selectedElementIds
                Assumptions.assumeTrue(
                    selected.containsAll(listOf("left", "right")),
                    "the marquee must select both rects, got $selected",
                )

                // Start a group drag on the RIGHT rect and move it +30 canvas units…
                press(p, 270.0, 90.0)
                drag(p, 300.0, 90.0)
                // …mid-drag snapshot: the ghost must show BOTH rects at original+delta.
                val mid = snapshot(p)
                val rowY = px(p, 0.0, 90.0).second
                val redX = (0 until mid.width).filter { x -> isRedish(mid.getRGB(x, rowY)) }
                val blueX = (0 until mid.width).filter { x -> isBluish(mid.getRGB(x, rowY)) }
                // Red moved 40..100 -> 70..130, blue 240..300 -> 270..330 (canvas units).
                val redSpan = px(p, 70.0, 90.0).first - 3..px(p, 130.0, 90.0).first + 3
                val blueSpan = px(p, 270.0, 90.0).first - 3..px(p, 330.0, 90.0).first + 3
                org.junit.jupiter.api.Assertions.assertTrue(
                    redX.isNotEmpty() && redX.all { it in redSpan },
                    "red must appear ONLY at its moved position, found at: $redX (row $rowY)",
                )
                org.junit.jupiter.api.Assertions.assertTrue(
                    blueX.isNotEmpty() && blueX.all { it in blueSpan },
                    "blue must appear ONLY at its moved position, found at: $blueX (row $rowY)",
                )

                // Releasing commits the group move for both members.
                release(p, 300.0, 90.0)
                org.junit.jupiter.api.Assertions.assertTrue(
                    p.selectedElementIds.containsAll(listOf("left", "right")),
                    "the selection must survive the group drag",
                )
                val left = p.layout.byId("left")!!
                val right = p.layout.byId("right")!!
                org.junit.jupiter.api.Assertions.assertTrue(
                    left.x > 60.0 && right.x > 260.0,
                    "both members must land at the moved position, got left=${left.x} right=${right.x}",
                )
            } finally {
                p.dispose()
            }
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
            base.resolve("debug").resolve(name),
            base.resolve("sidecar").resolve("linux").resolve(name),
        ).firstOrNull { Files.isExecutable(it) }
    }
}
