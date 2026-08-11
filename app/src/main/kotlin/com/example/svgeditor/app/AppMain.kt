package com.example.svgeditor.app

import com.example.svgeditor.core.CollisionDetector
import com.example.svgeditor.core.ResvgBridge
import com.example.svgeditor.core.Samples
import com.example.svgeditor.core.SvgEditorEngine
import com.example.svgeditor.core.SvgEditorPanel
import com.example.svgeditor.core.SvgRenderer
import com.formdev.flatlaf.FlatClientProperties
import com.formdev.flatlaf.FlatDarculaLaf
import com.formdev.flatlaf.FlatIntelliJLaf
import com.formdev.flatlaf.FlatLaf
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.Font
import java.awt.Insets
import java.awt.RenderingHints
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.dnd.DnDConstants
import java.awt.dnd.DropTarget
import java.awt.dnd.DropTargetAdapter
import java.awt.dnd.DropTargetDropEvent
import java.awt.event.KeyEvent
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JFileChooser
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JMenu
import javax.swing.JMenuBar
import javax.swing.JMenuItem
import javax.swing.JOptionPane
import javax.swing.JCheckBoxMenuItem
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JSeparator
import javax.swing.JSplitPane
import javax.swing.JTextArea
import javax.swing.JToggleButton
import javax.swing.JToolBar
import javax.swing.KeyStroke
import javax.swing.SwingUtilities
import javax.swing.UIManager
import javax.swing.filechooser.FileNameExtensionFilter

/**
 * Standalone runtime for the SVG editor.
 *
 * This is the small, IntelliJ-free runtime the user asked for: it wires the same `core`
 * (resvg render + layout + collision + off-screen canvas) into a plain Swing window so the
 * plugin can be exercised by double-clicking an `.exe`, with no IDEA SDK download.
 *
 * Two modes:
 *  - GUI (default): a JFrame with a source editor + the [SvgEditorPanel], plus drag-and-drop
 *    of `.svg` files.
 *  - `--smoke`: headless self-check (render -> layout -> hit-test -> move -> re-render) used to
 *    verify the runtime in CI / sandboxes where no display is available.
 *  - `--dragtest`: headless visual check that drives the real panel through a single-click
 *    select -> drag -> release cycle and writes `dragtest_*.png` for inspection.
 */
fun main(args: Array<String>) {
    if (args.contains("--smoke")) {
        System.setProperty("java.awt.headless", "true")
        val code = runSmoke()
        System.exit(code)
        return
    }
    if (args.contains("--dragtest")) {
        System.setProperty("java.awt.headless", "true")
        val code = runDragTest()
        System.exit(code)
        return
    }
    SwingUtilities.invokeLater {
        // Use the same Look & Feel IntelliJ IDEA ships with.
        FlatIntelliJLaf.setup()
        launchGui()
    }
}

/**
 * Load the resvg native bridge. The `resvg_bridge` shared library is bundled as a classpath
 * resource so the app is self-contained; we extract it to a temp file and load by absolute
 * path. Falls back to a library-path lookup when no resource is bundled.
 */
fun loadRenderer(): SvgRenderer {
    val os = System.getProperty("os.name").lowercase()
    val libName =
        when {
            os.contains("win") -> "resvg_bridge.dll"
            os.contains("mac") -> "libresvg_bridge.dylib"
            else -> "libresvg_bridge.so"
        }
    val loader = Thread.currentThread().contextClassLoader ?: ClassLoader.getSystemClassLoader()
    val url = loader.getResource("native/$libName")
    if (url != null) {
        val suffix = if (libName.endsWith("dll")) ".dll" else ".so"
        val tmp = File.createTempFile("resvg_bridge", suffix)
        tmp.deleteOnExit()
        url.openStream().use { input -> tmp.outputStream().use { dst -> input.copyTo(dst) } }
        return ResvgBridge.load(tmp.absolutePath)
    }
    return ResvgBridge.load()
}

/** Headless end-to-end check of the real runtime (loads the native dll, no display needed). */
fun runSmoke(): Int {
    try {
        val renderer = loadRenderer()

        // 1) Panel pipeline: load + decode PNG + build layout.
        val panel = SvgEditorPanel(renderer)
        panel.loadSvg(Samples.SIMPLE)
        require(panel.layout.elements.isNotEmpty()) { "layout is empty after load" }
        println("smoke: panel rendered ${panel.layout.elements.size} elements")

        // 2) Collision detection: hit-test the center of `box-a`.
        val box = panel.layout.byId("box-a")!!
        val hit = CollisionDetector.hitTest(panel.layout, box.x + box.width / 2, box.y + box.height / 2)
        require(hit?.id == "box-a") { "hit-test at box-a center returned ${hit?.id}" }
        println("smoke: hit-test at box-a center -> ${hit?.id}")

        // 3) Move + source-level edit: engine rewrites transform and re-renders.
        val engine = SvgEditorEngine(renderer).also { it.load(Samples.SIMPLE) }
        require(engine.moveElement("box-a", 20.0, 0.0)) { "moveElement returned false" }
        val movedBox = engine.layout.byId("box-a")!!
        require(movedBox.x > box.x + 10) { "box-a x did not increase after move (${movedBox.x})" }
        require("translate(20" in engine.svgSource) { "translate(..) not applied to source SVG" }
        println("smoke: moved box-a -> x=${movedBox.x}, source now contains translate(20..)")

        // 4) Crisp rendering: the engine renders at the requested device-pixel size (so the
        //    panel can composite 1:1 with no upscaling aliasing).
        val hi = SvgEditorEngine(renderer).also { it.load(Samples.SIMPLE) }
        hi.renderAt(400, 240)
        require(hi.imageWidth == 400 && hi.imageHeight == 240) {
            "renderAt did not honour device size (${hi.imageWidth}x${hi.imageHeight})"
        }
        println("smoke: renderAt device size -> ${hi.imageWidth}x${hi.imageHeight}")

        // 5) Resize via setElementBox: rewrite transform to matrix(...) and re-render.
        val rz = SvgEditorEngine(renderer).also { it.load(Samples.SIMPLE) }
        require(rz.setElementBox("box-a", 10.0, 10.0, 160.0, 120.0)) { "setElementBox returned false" }
        val rb = rz.layout.byId("box-a")!!
        require(rb.width > 100) { "box-a width did not grow after resize (${rb.width})" }
        require("matrix(" in rz.svgSource) { "setElementBox did not emit a matrix(...) transform" }
        println("smoke: resized box-a -> w=${rb.width}, source contains matrix(..)")

        println("SMOKE OK")
        return 0
    } catch (e: Throwable) {
        println("SMOKE FAILED: ${e.message}")
        e.printStackTrace()
        return 1
    }
}

/**
 * Headless drag test: drives the REAL panel rendering + interaction code (with the real resvg
 * dll) through a full select -> press -> drag -> release cycle on `box-a`, and writes three
 * PNGs so the drag behaviour can be inspected visually:
 *   - dragtest_rest.png    : resting state (nothing selected)
 *   - dragtest_mid.png     : mid-drag (foreground layer previewed at the new position)
 *   - dragtest_after.png   : after release (full re-render)
 * It also prints the element's geometry before/after and the transform written to the source.
 */
fun runDragTest(): Int {
    try {
        FlatIntelliJLaf.setup()
        println("dragtest: LookAndFeel = ${UIManager.getLookAndFeel().name} (${UIManager.getLookAndFeel().javaClass.name})")
        val renderer = loadRenderer()
        val panel = SvgEditorPanel(renderer)
        val canvas = panel.debugCanvas()
        canvas.setSize(640, 420)

        val imgW = 640
        val imgH = 420
        panel.loadSvg(Samples.SIMPLE)

        fun capture(
            tag: String,
            scale: Double = 1.0,
        ): BufferedImage {
            // Render through a `scale(scale,scale)` Graphics2D — exactly what a HiDPI/Retina
            // display does — so the drag-preview blit is exercised under a real transform.
            val img = BufferedImage((imgW * scale).toInt(), (imgH * scale).toInt(), BufferedImage.TYPE_INT_RGB)
            panel.debugRenderTo(img, scale)
            val out = File("dragtest_$tag.png")
            ImageIO.write(img, "png", out)
            println("dragtest: wrote $out (scale=$scale)")
            return img
        }

        val restBox = panel.layout.byId("box-a")!!

        capture("rest") // nothing selected yet

        // SINGLE-CLICK drag is now the default (press on an element body starts a move drag).
        val center = panel.debugElementCenterPx("box-a") ?: error("box-a center is null")
        println("dragtest: box-a center(px) = $center")
        println("dragtest: selected BEFORE press = ${panel.selectedElementId}")
        panel.debugPressDrag(center.x, center.y, center.x + 80, center.y + 60)
        println("dragtest: selected AFTER press  = ${panel.selectedElementId}")
        val midImg = capture("mid") // live (resvg-free) preview frame

        // Bench: measure per-frame paint cost (proxy for drag smoothness). box-a is selected and
        // mid-drag here, so this exercises the bgImage + fgCrop + selection path.
        run {
            val bench = BufferedImage(imgW, imgH, BufferedImage.TYPE_INT_RGB)
            val t0 = System.nanoTime()
            repeat(60) { panel.debugRenderTo(bench, 1.0) }
            val per = (System.nanoTime() - t0) / 1_000_000.0 / 60.0
            println("dragtest: paint cost ~= %.2f ms/frame (cached static layer)".format(per))
        }

        panel.debugRelease()
        val afterImg = capture("after")

        val afterBox = panel.layout.byId("box-a")!!
        val src = panel.svgSource
        val tm = Regex("""id="box-a"[^>]*transform="([^"]*)"""").find(src)
        println("dragtest: box-a before = (${restBox.x}, ${restBox.y})")
        println("dragtest: box-a after  = (${afterBox.x}, ${afterBox.y})")
        println("dragtest: box-a transform in source = ${tm?.groupValues?.get(1)}")

        // Pixel-level check: the dragged element (box-a, green) must sit at the SAME pixel
        // location in the mid-drag frame and the post-release frame. A mismatch here is the
        // exact "dropped position != final position" bug. Centroids are computed from the
        // in-memory images to avoid Java's PNG color-space round-trip shifting pixel values.
        val midC = greenCentroid(midImg)
        val afterC = greenCentroid(afterImg)
        println("dragtest: box-a green centroid  mid=$midC  after=$afterC")
        if (midC != null && afterC != null) {
            val dpx = kotlin.math.abs(midC.first - afterC.first)
            val dpy = kotlin.math.abs(midC.second - afterC.second)
            println("dragtest: mid<->after pixel delta = (${"%.1f".format(dpx)}, ${"%.1f".format(dpy)})")
            require(dpx < 4 && dpy < 4) { "dragged element landed at a different pixel than the preview (delta=($dpx,$dpy))" }
        }

        require(afterBox.x > restBox.x + 10) { "box-a did not move right enough (x=${afterBox.x})" }
        require(afterBox.y > restBox.y + 8) { "box-a did not move down enough (y=${afterBox.y})" }
        require("translate(" in src) { "no translate(..) written to source SVG" }

        println("DRAGTEST OK (moved box-a by ~(${"%.1f".format(afterBox.x - restBox.x)}, ${"%.1f".format(afterBox.y - restBox.y)}) svg units)")

        // ---- HiDPI phase: a real Retina/HiDPI display renders the raster at 2x AND paints
        // through a scale(2,2) Graphics2D. We force the raster to 2x (debugSetDpi) and render
        // the frames through a scale=2.0 context so the preview blit is exercised under a real
        // transform. This is where a getSubimage-on-HiDPI offset would surface as a drop-vs-final gap.
        panel.loadSvg(Samples.SIMPLE)
        panel.debugSetDpi(2.0)
        val center2 = panel.debugElementCenterPx("box-a") ?: error("box-a center is null (dpr=2)")
        println("dragtest[dpr=2]: box-a center(px) = $center2")
        panel.debugPressDrag(center2.x, center2.y, center2.x + 80, center2.y + 60)
        val mid2Img = capture("mid2", scale = 2.0)
        panel.debugRelease()
        val after2Img = capture("after2", scale = 2.0)
        // Centroids are in device px here (image is 2x); compare within the same scale.
        val mid2 = greenCentroid(mid2Img)
        val after2 = greenCentroid(after2Img)
        println("dragtest[dpr=2]: box-a green centroid  mid=$mid2  after=$after2")
        if (mid2 != null && after2 != null) {
            val dpx = kotlin.math.abs(mid2.first - after2.first)
            val dpy = kotlin.math.abs(mid2.second - after2.second)
            println("dragtest[dpr=2]: mid<->after pixel delta = (${"%.1f".format(dpx)}, ${"%.1f".format(dpy)})")
            // Allow up to ~1 logical px (2 device px) of anti-alias jitter.
            require(dpx < 2.5 && dpy < 2.5) { "dpr=2: dragged element landed at a different pixel than the preview (delta=($dpx,$dpy))" }
        }
        println("DRAGTEST OK (dpr=2 phase consistent)")

        // ---- Resize phase: drag the SE handle of box-a and verify the stretched preview
        // lands exactly where the committed matrix(..) resize renders it. ----
        panel.loadSvg(Samples.SIMPLE)
        val rCenter = panel.debugElementCenterPx("box-a")!!
        panel.debugDoubleClick(rCenter.x, rCenter.y) // select box-a
        // SE handle sits at the element's bottom-right corner in panel pixels.
        val seX = (24.0 + (10.0 + 80.0) * 2.96).toInt() // offsetX + (x+w)*viewScale (~290)
        val seY = (32.4 + (10.0 + 60.0) * 2.96).toInt() // offsetY + (y+h)*viewScale (~240)
        panel.debugPressDrag(seX, seY, seX + 60, seY + 40)
        val midResizeImg = capture("midResize")
        panel.debugRelease()
        val afterResizeImg = capture("afterResize")
        val rMid = greenCentroid(midResizeImg)
        val rAfter = greenCentroid(afterResizeImg)
        println("dragtest[resize]: box-a green centroid  mid=$rMid  after=$rAfter")
        if (rMid != null && rAfter != null) {
            val dpx = kotlin.math.abs(rMid.first - rAfter.first)
            val dpy = kotlin.math.abs(rMid.second - rAfter.second)
            println("dragtest[resize]: mid<->after pixel delta = (${"%.1f".format(dpx)}, ${"%.1f".format(dpy)})")
            require(dpx < 4 && dpy < 4) { "resize: dragged element landed at a different pixel than the preview (delta=($dpx,$dpy))" }
        }
        println("DRAGTEST OK (resize phase consistent)")

        // ---- S-edge downward phase: grab the SOUTH (bottom-centre) handle and drag straight
        // down. The top edge (anchor) MUST stay put and the box must grow downward — this is the
        // exact "resize downward but the box jumps up" report. We also assert the committed
        // (x,y) so any position shift surfaces directly. ----
        panel.loadSvg(Samples.SIMPLE)
        val sCenter = panel.debugElementCenterPx("box-a")!!
        panel.debugDoubleClick(sCenter.x, sCenter.y) // select box-a
        val sHx = (24.0 + 50.0 * 2.96).toInt() // S handle x = bottom-centre, svg (50,70)
        val sHy = (32.4 + 70.0 * 2.96).toInt() // svg y = 70
        panel.debugPressDrag(sHx, sHy, sHx, sHy + 178) // drag straight down (+178px ~ +60 svg)
        val midSEdgeImg = capture("midSEdge")
        panel.debugRelease()
        val afterSEdgeImg = capture("afterSEdge")
        val sEdgeBox = panel.layout.byId("box-a")!!
        val sMid = greenCentroid(midSEdgeImg)
        val sAfter = greenCentroid(afterSEdgeImg)
        val sSrc = panel.svgSource
        val sMatrix = Regex("""id="box-a"[^>]*transform="([^"]*)"""").find(sSrc)
        println("dragtest[S-down]: box-a transform in source = ${sMatrix?.groupValues?.get(1)}")
        println("dragtest[S-down]: committed box-a = (${sEdgeBox.x}, ${sEdgeBox.y}, ${sEdgeBox.width}, ${sEdgeBox.height})")
        println("dragtest[S-down]: box-a green centroid  mid=$sMid  after=$sAfter")
        if (sMid != null && sAfter != null) {
            val dpx = kotlin.math.abs(sMid.first - sAfter.first)
            val dpy = kotlin.math.abs(sMid.second - sAfter.second)
            println("dragtest[S-down]: mid<->after pixel delta = (${"%.1f".format(dpx)}, ${"%.1f".format(dpy)})")
            require(dpx < 4 && dpy < 4) { "S-down: element landed at a different pixel than the preview (delta=($dpx,$dpy))" }
        }
        require(sEdgeBox.x > 9.0 && sEdgeBox.x < 11.0) { "S-down: box-a x moved (${sEdgeBox.x})" }
        require(sEdgeBox.y > 9.0 && sEdgeBox.y < 11.0) { "S-down: box-a TOP moved UP/DOWN unexpectedly (y=${sEdgeBox.y})" }
        require(sEdgeBox.height > 110.0) { "S-down: box-a did not grow downward (h=${sEdgeBox.height})" }
        println("DRAGTEST OK (S-edge downward: top stays, grows down)")

        // ---- SW-corner downward phase: grab the SOUTH-WEST (bottom-left) handle and drag down.
        // Regression for a bug where the SW branch froze the height (ht = sb.h) so dragging the
        // bottom-left handle vertically did nothing. ----
        panel.loadSvg(Samples.SIMPLE)
        val swCenter = panel.debugElementCenterPx("box-a")!!
        panel.debugDoubleClick(swCenter.x, swCenter.y) // select box-a
        val swHx = (24.0 + 10.0 * 2.96).toInt() // SW handle x = svg (10,70)
        val swHy = (32.4 + 70.0 * 2.96).toInt()
        panel.debugPressDrag(swHx, swHy, swHx, swHy + 178) // drag straight down
        val midSWImg = capture("midSW")
        panel.debugRelease()
        val afterSWImg = capture("afterSW")
        val swBox = panel.layout.byId("box-a")!!
        val swMid = greenCentroid(midSWImg)
        val swAfter = greenCentroid(afterSWImg)
        println("dragtest[SW-down]: committed box-a = (${swBox.x}, ${swBox.y}, ${swBox.width}, ${swBox.height})")
        println("dragtest[SW-down]: box-a green centroid  mid=$swMid  after=$swAfter")
        if (swMid != null && swAfter != null) {
            val dpx = kotlin.math.abs(swMid.first - swAfter.first)
            val dpy = kotlin.math.abs(swMid.second - swAfter.second)
            println("dragtest[SW-down]: mid<->after pixel delta = (${"%.1f".format(dpx)}, ${"%.1f".format(dpy)})")
            require(dpx < 4 && dpy < 4) { "SW-down: element landed at a different pixel than the preview (delta=($dpx,$dpy))" }
        }
        require(swBox.height > 110.0) { "SW-down: box-a height did not grow (h=${swBox.height})" }
        require(swBox.y > 9.0 && swBox.y < 11.0) { "SW-down: box-a TOP moved unexpectedly (y=${swBox.y})" }
        println("DRAGTEST OK (SW-corner downward: height grows)")

        // ---- Nested phase: drag `inner`, which lives inside <g transform="translate(120,80)">.
        // The preview (absolute SVG space) must match the committed, group-aware translate. ----
        panel.loadSvg(Samples.SIMPLE)
        val nCenter = panel.debugElementCenterPx("inner")!!
        panel.debugPressDrag(nCenter.x, nCenter.y, nCenter.x + 50, nCenter.y + 30)
        val midNestedImg = capture("midNested")
        panel.debugRelease()
        val afterNestedImg = capture("afterNested")
        val nMid = blueCentroid(midNestedImg)
        val nAfter = blueCentroid(afterNestedImg)
        println("dragtest[nested]: inner blue centroid  mid=$nMid  after=$nAfter")
        if (nMid != null && nAfter != null) {
            val dpx = kotlin.math.abs(nMid.first - nAfter.first)
            val dpy = kotlin.math.abs(nMid.second - nAfter.second)
            println("dragtest[nested]: mid<->after pixel delta = (${"%.1f".format(dpx)}, ${"%.1f".format(dpy)})")
            require(dpx < 4 && dpy < 4) { "nested: dragged element landed at a different pixel than the preview (delta=($dpx,$dpy))" }
        }
        println("DRAGTEST OK (nested phase consistent)")

        // ---- Rotate phase: grab the rotate handle above box-a and swing it. The element must
        // rotate about its own centre; the affine preview (mid) and the committed resvg render
        // (after) must land the element at the SAME pixels (no drop-vs-final gap, no jitter). ----
        panel.loadSvg(Samples.SIMPLE)
        val rotCenter = panel.debugElementCenterPx("box-a")!!
        panel.debugDoubleClick(rotCenter.x, rotCenter.y) // select box-a
        // Rotate handle sits directly above the box top-centre (ROTATE_OFFSET = 22 px up).
        val rhx = (24.0 + (10.0 + 80.0 / 2.0) * 2.96)
        val rhy = (32.4 + 10.0 * 2.96) - 22.0
        panel.debugPressDrag(rhx.toInt(), rhy.toInt(), rhx.toInt() + 80, rhy.toInt())
        val midRotateImg = capture("midRotate")
        panel.debugRelease()
        val afterRotateImg = capture("afterRotate")
        val srcR = panel.svgSource
        println("dragtest[rotate]: box-a transform in source = ${Regex("""id="box-a"[^>]*transform="([^"]*)"""").find(srcR)?.groupValues?.get(1)}")
        val rotMid = greenCentroid(midRotateImg)
        val rotAfter = greenCentroid(afterRotateImg)
        println("dragtest[rotate]: box-a green centroid  mid=$rotMid  after=$rotAfter")
        if (rotMid != null && rotAfter != null) {
            val dpx = kotlin.math.abs(rotMid.first - rotAfter.first)
            val dpy = kotlin.math.abs(rotMid.second - rotAfter.second)
            println("dragtest[rotate]: mid<->after pixel delta = (${"%.1f".format(dpx)}, ${"%.1f".format(dpy)})")
            require(dpx < 6 && dpy < 6) { "rotate: element landed at a different pixel than the preview (delta=($dpx,$dpy))" }
        }
        require("rotate(" in srcR) { "no rotate(..) written to source SVG" }
        println("DRAGTEST OK (rotate phase consistent)")

        // ---- Transformed move phase: drag `rot` (own rotate transform) and `scaled` (inside a
        // scaled group). A plain translate prepend would move these in *local* space and drift on
        // release; the transform-aware move must keep the committed render pinned to the preview.
        // This is the exact "the box jumps up on release" report for transformed/nested elements. ----
        for ((id, color) in listOf("rot" to "green", "scaled" to "blue")) {
            panel.loadSvg(Samples.TRANSFORMED)
            val restT = panel.layout.byId(id)!!
            val c = panel.debugElementCenterPx(id) ?: error("$id center is null (transformed)")
            panel.debugPressDrag(c.x, c.y, c.x + 60, c.y + 40)
            val midT = capture("midT_$id")
            panel.debugRelease()
            val afterT = capture("afterT_$id")
            val movedT = panel.layout.byId(id)!!
            val midC = if (color == "green") greenCentroid(midT) else blueCentroid(midT)
            val afterC = if (color == "green") greenCentroid(afterT) else blueCentroid(afterT)
            println("dragtest[transformed $id]: centroid  mid=$midC  after=$afterC")
            if (midC != null && afterC != null) {
                val dpx = kotlin.math.abs(midC.first - afterC.first)
                val dpy = kotlin.math.abs(midC.second - afterC.second)
                println("dragtest[transformed $id]: mid<->after pixel delta = (${"%.1f".format(dpx)}, ${"%.1f".format(dpy)})")
                require(dpx < 4 && dpy < 4) { "transformed $id: landed at a different pixel than the preview (delta=($dpx,$dpy))" }
            }
            // Committed bbox must equal the resting bbox plus the root-space drag delta.
            val dxSvg = 60.0 / 2.96
            val dySvg = 40.0 / 2.96
            require(kotlin.math.abs((movedT.x - restT.x) - dxSvg) < 2.0) {
                "transformed $id: x did not move by the root-space drag delta (got ${movedT.x - restT.x}, expected ~$dxSvg)"
            }
            require(kotlin.math.abs((movedT.y - restT.y) - dySvg) < 2.0) {
                "transformed $id: y did not move by the root-space drag delta (got ${movedT.y - restT.y}, expected ~$dySvg)"
            }
            println("dragtest[transformed $id]: box (${restT.x},${restT.y}) -> (${movedT.x},${movedT.y})  [~+(${"%.1f".format(dxSvg)},${"%.1f".format(dySvg)})]")
            println("DRAGTEST OK (transformed $id move consistent)")
        }

        return 0
    } catch (e: Throwable) {
        println("DRAGTEST FAILED: ${e.message}")
        e.printStackTrace()
        return 1
    }
}

/**
 * Find the centroid (in image pixels) of box-a's green fill (#4caf50). box-a is the only
 * strongly-green element in the sample, so this isolates it from the background, the pink dot
 * and the blue inner rect.
 */
private fun greenCentroid(img: BufferedImage): Pair<Double, Double>? {
    var sx = 0L
    var sy = 0L
    var n = 0L
    for (y in 0 until img.height) {
        for (x in 0 until img.width) {
            val p = img.getRGB(x, y)
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            if (g > 140 && r < 110 && b < 120 && g > r && g > b) {
                sx += x
                sy += y
                n++
            }
        }
    }
    if (n == 0L) return null
    return (sx.toDouble() / n) to (sy.toDouble() / n)
}

/** Centroid of `inner`'s blue fill (#2196f3), used by the nested-drag phase. */
private fun blueCentroid(img: BufferedImage): Pair<Double, Double>? {
    var sx = 0L
    var sy = 0L
    var n = 0L
    for (y in 0 until img.height) {
        for (x in 0 until img.width) {
            val p = img.getRGB(x, y)
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            if (b > 200 && r < 100 && g < 200 && b > r && b > g) {
                sx += x
                sy += y
                n++
            }
        }
    }
    if (n == 0L) return null
    return (sx.toDouble() / n) to (sy.toDouble() / n)
}

private fun launchGui() {
    val renderer = loadRenderer()
    val panel = SvgEditorPanel(renderer)
    val sourceArea =
        JTextArea(Samples.SIMPLE, 24, 60).apply {
            font = Font(Font.MONOSPACED, Font.PLAIN, 13)
            tabSize = 2
            border = BorderFactory.createEmptyBorder(4, 6, 4, 6)
        }

    val frame =
        JFrame("SVG Editor").apply {
            defaultCloseOperation = JFrame.EXIT_ON_CLOSE
            size = Dimension(1200, 760)
        }

    // IDEA-style main menu bar.
    frame.jMenuBar = buildMenuBar(frame, sourceArea, panel)

    // IDEA-style flat main toolbar.
    val toolbar = buildToolBar(frame, sourceArea, panel)

    // Wrap the source editor in a panel that resembles an IntelliJ tool window.
    val sourcePane =
        JPanel(BorderLayout()).apply {
            border = BorderFactory.createMatteBorder(0, 0, 0, 1, UIManager.getColor("Component.borderColor"))
            add(createToolWindowHeader("SVG Source"), BorderLayout.NORTH)
            add(JScrollPane(sourceArea), BorderLayout.CENTER)
        }

    // Initial render from the source editor.
    panel.loadSvg(sourceArea.text)

    val split =
        JSplitPane(JSplitPane.HORIZONTAL_SPLIT, sourcePane, panel).apply {
            dividerLocation = 420
            dividerSize = 4
            border = null
        }

    val statusBar = buildStatusBar()
    panel.onStatus = { statusBar.text = it }

    frame.contentPane.apply {
        layout = BorderLayout()
        add(toolbar, BorderLayout.NORTH)
        add(split, BorderLayout.CENTER)
        add(statusBar, BorderLayout.SOUTH)
    }

    // Drag-and-drop .svg files anywhere on the window.
    frame.dropTarget =
        DropTarget(
            frame,
            object : DropTargetAdapter() {
                override fun drop(e: DropTargetDropEvent) {
                    e.acceptDrop(DnDConstants.ACTION_COPY)
                    val transfer = e.transferable
                    if (transfer.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
                        @Suppress("UNCHECKED_CAST")
                        val files = transfer.getTransferData(DataFlavor.javaFileListFlavor) as List<File>
                        files
                            .filterIsInstance<File>()
                            .firstOrNull { it.extension.equals("svg", ignoreCase = true) }
                            ?.let {
                                val text = it.readText()
                                sourceArea.text = text
                                panel.loadSvg(text)
                            }
                    }
                    e.dropComplete(true)
                }
            },
        )

    frame.setLocationRelativeTo(null)
    frame.isVisible = true
}

private fun createToolWindowHeader(title: String): JPanel {
    val bg = UIManager.getColor("ToolWindow.header.background") ?: UIManager.getColor("Panel.background")
    val fg = UIManager.getColor("ToolWindow.header.foreground") ?: UIManager.getColor("Label.foreground")
    return JPanel(BorderLayout()).apply {
        background = bg
        border = BorderFactory.createMatteBorder(0, 0, 1, 0, UIManager.getColor("Component.borderColor"))
        add(
            JLabel(title).apply {
                foreground = fg
                font = font.deriveFont(Font.BOLD, font.size2D - 1f)
                border = BorderFactory.createEmptyBorder(6, 8, 6, 8)
            },
            BorderLayout.WEST,
        )
    }
}

private fun buildMenuBar(
    frame: JFrame,
    sourceArea: JTextArea,
    panel: SvgEditorPanel,
): JMenuBar {
    fun menu(title: String, vararg items: JComponent): JMenu =
        JMenu(title).apply { items.forEach { add(it) } }

    fun item(
        title: String,
        key: Int? = null,
        mask: Int = Toolkit.getDefaultToolkit().menuShortcutKeyMaskEx,
        action: () -> Unit,
    ): JMenuItem =
        JMenuItem(title).apply {
            addActionListener { action() }
            if (key != null) accelerator = KeyStroke.getKeyStroke(key, mask)
        }

    fun checkItem(
        title: String,
        initial: Boolean,
        action: (Boolean) -> Unit,
    ): JCheckBoxMenuItem =
        JCheckBoxMenuItem(title, initial).apply { addActionListener { action(isSelected) } }

    return JMenuBar().apply {
        add(
            menu(
                "File",
                item("Open…", KeyEvent.VK_O) { openFile(frame, sourceArea, panel) },
                item("Save…", KeyEvent.VK_S) { saveFile(frame, sourceArea) },
                JSeparator(),
                item("Exit", KeyEvent.VK_Q) { System.exit(0) },
            ),
        )
        add(
            menu(
                "View",
                item("Zoom In", KeyEvent.VK_EQUALS) { panel.zoomIn() },
                item("Zoom Out", KeyEvent.VK_MINUS) { panel.zoomOut() },
                item("Actual Size", KeyEvent.VK_1) { panel.actualSize() },
                item("Fit to Window", KeyEvent.VK_0) { panel.fitView() },
                JSeparator(),
                checkItem("Show Chessboard", true) { panel.setChessboard(it) },
                checkItem("Show Grid", false) { panel.setGrid(it) },
                JSeparator(),
                item("Toggle Theme") { toggleTheme() },
            ),
        )
        add(
            menu(
                "Help",
                item("About") {
                    JOptionPane.showMessageDialog(
                        frame,
                        "SVG Editor (standalone)\nPowered by resvg + FlatLaf",
                        "About",
                        JOptionPane.INFORMATION_MESSAGE,
                    )
                },
            ),
        )
    }
}

private fun buildToolBar(
    frame: JFrame,
    sourceArea: JTextArea,
    panel: SvgEditorPanel,
): JToolBar {
    fun tbButton(
        text: String,
        tooltip: String = text,
        action: () -> Unit,
    ): JButton =
        JButton(text).apply {
            toolTipText = tooltip
            isFocusable = false
            margin = Insets(4, 8, 4, 8)
            putClientProperty(FlatClientProperties.BUTTON_TYPE, FlatClientProperties.BUTTON_TYPE_TOOLBAR_BUTTON)
            addActionListener { action() }
        }

    fun tbToggle(
        text: String,
        tooltip: String = text,
        initial: Boolean,
        action: (Boolean) -> Unit,
    ): JToggleButton =
        JToggleButton(text).apply {
            toolTipText = tooltip
            isFocusable = false
            margin = Insets(4, 8, 4, 8)
            isSelected = initial
            putClientProperty(FlatClientProperties.BUTTON_TYPE, FlatClientProperties.BUTTON_TYPE_TOOLBAR_BUTTON)
            addActionListener { action(isSelected) }
        }

    return JToolBar().apply {
        isFloatable = false
        // FlatLaf constant name differs by version; use the stable property key.
        putClientProperty("JToolBar.isFlat", true)
        border = BorderFactory.createMatteBorder(0, 0, 1, 0, UIManager.getColor("Component.borderColor"))
        add(tbButton("Open", "Open SVG file (Ctrl+O)") { openFile(frame, sourceArea, panel) })
        add(tbButton("Save", "Save SVG file (Ctrl+S)") { saveFile(frame, sourceArea) })
        addSeparator(Dimension(8, 0))
        add(tbButton("-", "Zoom out") { panel.zoomOut() })
        add(tbButton("+", "Zoom in") { panel.zoomIn() })
        add(tbButton("100%", "Actual size (100%)") { panel.actualSize() })
        add(tbButton("Fit", "Fit to window") { panel.fitView() })
        addSeparator(Dimension(8, 0))
        add(tbToggle("Grid", "Toggle image-pixel grid", false) { panel.setGrid(it) })
        add(tbToggle("Chess", "Toggle transparency chessboard", true) { panel.setChessboard(it) })
        addSeparator(Dimension(8, 0))
        add(tbButton("Theme", "Toggle IntelliJ Light / Darcula") { toggleTheme() })
    }
}

private var isDarkTheme = false

private fun toggleTheme() {
    isDarkTheme = !isDarkTheme
    if (isDarkTheme) FlatDarculaLaf.setup() else FlatIntelliJLaf.setup()
    FlatLaf.updateUI()
}

private fun buildStatusBar(): JLabel {
    val bg = UIManager.getColor("Panel.background")
    val fg = UIManager.getColor("Label.foreground")
    return JLabel("Zoom: 100% · No selection").apply {
        background = bg
        foreground = fg
        isOpaque = true
        border =
            BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, UIManager.getColor("Component.borderColor")),
                BorderFactory.createEmptyBorder(4, 10, 4, 10),
            )
    }
}

private fun applySource(
    area: JTextArea,
    panel: SvgEditorPanel,
) {
    try {
        panel.loadSvg(area.text)
    } catch (e: Throwable) {
        JOptionPane.showMessageDialog(null, "Failed to render SVG:\n${e.message}", "SVG error", JOptionPane.ERROR_MESSAGE)
    }
}

private fun openFile(
    parent: JFrame,
    area: JTextArea,
    panel: SvgEditorPanel,
) {
    val chooser = JFileChooser().apply { fileFilter = FileNameExtensionFilter("SVG files", "svg") }
    if (chooser.showOpenDialog(parent) == JFileChooser.APPROVE_OPTION) {
        val text = chooser.selectedFile.readText()
        area.text = text
        applySource(area, panel)
    }
}

private fun saveFile(
    parent: JFrame,
    area: JTextArea,
) {
    val chooser = JFileChooser().apply { fileFilter = FileNameExtensionFilter("SVG files", "svg") }
    if (chooser.showSaveDialog(parent) == JFileChooser.APPROVE_OPTION) {
        chooser.selectedFile.writeText(area.text)
    }
}
