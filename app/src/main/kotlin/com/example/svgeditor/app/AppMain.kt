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
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JSeparator
import javax.swing.JSplitPane
import javax.swing.JTextArea
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

        fun capture(tag: String) {
            val img = BufferedImage(imgW, imgH, BufferedImage.TYPE_INT_RGB)
            panel.debugRenderTo(img)
            val out = File("dragtest_$tag.png")
            ImageIO.write(img, "png", out)
            println("dragtest: wrote $out")
        }

        val restBox = panel.layout.byId("box-a")!!

        capture("rest") // nothing selected yet

        // SINGLE-CLICK drag is now the default (press on an element body starts a move drag).
        val center = panel.debugElementCenterPx("box-a") ?: error("box-a center is null")
        println("dragtest: box-a center(px) = $center")
        println("dragtest: selected BEFORE press = ${panel.selectedElementId}")
        panel.debugPressDrag(center.x, center.y, center.x + 80, center.y + 60)
        println("dragtest: selected AFTER press  = ${panel.selectedElementId}")
        capture("mid") // live (resvg-free) preview frame

        panel.debugRelease()
        capture("after")

        val afterBox = panel.layout.byId("box-a")!!
        val src = panel.svgSource
        val tm = Regex("""id="box-a"[^>]*transform="([^"]*)"""").find(src)
        println("dragtest: box-a before = (${restBox.x}, ${restBox.y})")
        println("dragtest: box-a after  = (${afterBox.x}, ${afterBox.y})")
        println("dragtest: box-a transform in source = ${tm?.groupValues?.get(1)}")

        require(afterBox.x > restBox.x + 10) { "box-a did not move right enough (x=${afterBox.x})" }
        require(afterBox.y > restBox.y + 8) { "box-a did not move down enough (y=${afterBox.y})" }
        require("translate(" in src) { "no translate(..) written to source SVG" }

        println("DRAGTEST OK (moved box-a by ~(${"%.1f".format(afterBox.x - restBox.x)}, ${"%.1f".format(afterBox.y - restBox.y)}) svg units)")
        return 0
    } catch (e: Throwable) {
        println("DRAGTEST FAILED: ${e.message}")
        e.printStackTrace()
        return 1
    }
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
                item("Fit to Window", KeyEvent.VK_0) { panel.fitView() },
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

    return JToolBar().apply {
        isFloatable = false
        // FlatLaf constant name differs by version; use the stable property key.
        putClientProperty("JToolBar.isFlat", true)
        border = BorderFactory.createMatteBorder(0, 0, 1, 0, UIManager.getColor("Component.borderColor"))
        add(tbButton("Open", "Open SVG file (Ctrl+O)") { openFile(frame, sourceArea, panel) })
        add(tbButton("Save", "Save SVG file (Ctrl+S)") { saveFile(frame, sourceArea) })
        addSeparator(Dimension(8, 0))
        add(tbButton("Apply", "Apply source changes") { applySource(sourceArea, panel) })
        add(tbButton("Sample", "Load sample SVG") {
            sourceArea.text = Samples.SIMPLE
            applySource(sourceArea, panel)
        })
        addSeparator(Dimension(8, 0))
        add(tbButton("+", "Zoom in") { panel.zoomIn() })
        add(tbButton("-", "Zoom out") { panel.zoomOut() })
        add(tbButton("Fit", "Fit to window") { panel.fitView() })
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
