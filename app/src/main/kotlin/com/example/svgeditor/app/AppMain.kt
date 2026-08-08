package com.example.svgeditor.app

import com.example.svgeditor.core.CollisionDetector
import com.example.svgeditor.core.ResvgBridge
import com.example.svgeditor.core.Samples
import com.example.svgeditor.core.SvgEditorEngine
import com.example.svgeditor.core.SvgEditorPanel
import com.example.svgeditor.core.SvgRenderer
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.Font
import java.awt.datatransfer.DataFlavor
import java.awt.dnd.DnDConstants
import java.awt.dnd.DropTarget
import java.awt.dnd.DropTargetAdapter
import java.awt.dnd.DropTargetDropEvent
import java.io.File
import javax.swing.JButton
import javax.swing.JFileChooser
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JOptionPane
import javax.swing.JScrollPane
import javax.swing.JSplitPane
import javax.swing.JTextArea
import javax.swing.JToolBar
import javax.swing.SwingUtilities
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
 */
fun main(args: Array<String>) {
    if (args.contains("--smoke")) {
        System.setProperty("java.awt.headless", "true")
        val code = runSmoke()
        System.exit(code)
        return
    }
    SwingUtilities.invokeLater { launchGui() }
}

/**
 * Load the resvg native bridge. The `resvg_bridge` shared library is bundled as a classpath
 * resource so the app is self-contained; we extract it to a temp file and load by absolute
 * path. Falls back to a library-path lookup when no resource is bundled (e.g. running from an
 * IDE without the resource copied).
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

private fun launchGui() {
    val renderer = loadRenderer()
    val panel = SvgEditorPanel(renderer)
    val sourceArea =
        JTextArea(Samples.SIMPLE, 24, 60).apply {
            font = Font(Font.MONOSPACED, Font.PLAIN, 12)
        }

    val frame =
        JFrame("SVG Editor (standalone)").apply {
            defaultCloseOperation = JFrame.EXIT_ON_CLOSE
            size = Dimension(1120, 660)
        }

    val toolbar = JToolBar()
    toolbar.add(JButton("Open…").apply { addActionListener { openFile(frame, sourceArea, panel) } })
    toolbar.add(JButton("Save…").apply { addActionListener { saveFile(frame, sourceArea) } })
    toolbar.add(JButton("Apply source").apply { addActionListener { applySource(sourceArea, panel) } })
    toolbar.add(
        JButton("Load sample").apply {
            addActionListener {
                sourceArea.text = Samples.SIMPLE
                applySource(sourceArea, panel)
            }
        },
    )
    toolbar.addSeparator()
    toolbar.add(JButton("Zoom +").apply { addActionListener { panel.zoomIn() } })
    toolbar.add(JButton("Zoom -").apply { addActionListener { panel.zoomOut() } })
    toolbar.add(JButton("Fit").apply { addActionListener { panel.fitView() } })

    // Initial render from the source editor.
    panel.loadSvg(sourceArea.text)

    val split =
        JSplitPane(JSplitPane.HORIZONTAL_SPLIT, JScrollPane(sourceArea), panel).apply {
            dividerLocation = 440
        }

    val statusBar = JLabel("Zoom: 100% · No selection")
    panel.onStatus = { statusBar.text = it }

    frame.contentPane.add(toolbar, BorderLayout.NORTH)
    frame.contentPane.add(split, BorderLayout.CENTER)
    frame.contentPane.add(statusBar, BorderLayout.SOUTH)

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
