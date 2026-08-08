package com.example.svgeditor.core

import java.awt.BasicStroke
import java.awt.Color
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Point
import java.awt.RenderingHints
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.MouseMotionAdapter
import java.awt.event.MouseWheelEvent
import java.awt.event.MouseWheelListener
import java.awt.image.BufferedImage
import javax.imageio.ImageIO
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JToolBar

/**
 * The SVG editor panel.
 *
 * Pipeline:
 *  1. `resvg` renders the SVG into an off-screen [BufferedImage] (the "off-screen canvas").
 *     The image is rendered at the panel's **true device resolution** (`svgSize × viewScale
 *     × DPI`), so it is composited 1:1 on screen — no upscaling blur / aliasing.
 *  2. The same render produces an [SvgLayout] (per-element absolute bounding boxes).
 *  3. A [MouseMotionListener] tracks the pointer; we convert pointer pixels to SVG/canvas
 *     coordinates and run [CollisionDetector] to find the element under the cursor.
 *  4. Interaction: click / double-click selects an element and shows 8 control points;
 *     dragging the body moves it, dragging a handle resizes it. The engine rewrites the
 *     element's `transform` in the source SVG and re-renders, so the canvas updates.
 *
 * The class depends only on Swing + [SvgRenderer], so it is fully unit-testable with a fake
 * renderer (no IntelliJ SDK, no Rust toolchain required).
 */
class SvgEditorPanel(
    renderer: SvgRenderer,
) : JPanel() {
    private val engine = SvgEditorEngine(renderer)
    private val interaction = InteractionController()

    /** Off-screen canvas: the resvg render, composited onto the visible panel. */
    private var offscreen: BufferedImage? = null

    private var viewScale = 1.0
    private var offsetX = 0.0
    private var offsetY = 0.0
    private var zoom = 1.0
    private var dpiScale = 1.0
    private val pad = 24.0

    private var hoveredId: String? = null
    private var selectedId: String? = null
    private val pointer = Point(0, 0)

    /** Status callback (zoom % + selection) for the host application. */
    var onStatus: ((String) -> Unit)? = null

    private val canvas =
        object : JPanel() {
            override fun paintComponent(g: Graphics) {
                super.paintComponent(g)
                renderCanvas(g as Graphics2D)
            }
        }

    init {
        setLayout(java.awt.BorderLayout())
        // No hard-coded background: FlatLaf drives canvas.background so the editor follows the
        // active theme (IntelliJ Light = near-white, Darcula = dark grey).
        canvas.preferredSize = Dimension(640, 420)
        canvas.addComponentListener(
            object : ComponentAdapter() {
                override fun componentResized(e: ComponentEvent) {
                    recomputeView()
                    renderAtDeviceSize()
                    canvas.repaint()
                }
            },
        )
        add(JScrollPane(canvas), java.awt.BorderLayout.CENTER)
        add(buildToolbar(), java.awt.BorderLayout.NORTH)
        installMouse()
    }

    private fun buildToolbar(): JToolBar {
        val bar = JToolBar()
        bar.add(
            JButton("Load sample").apply {
                addActionListener { loadSvg(Samples.SIMPLE) }
            },
        )
        bar.add(
            JButton("Zoom +").apply {
                addActionListener { zoomIn() }
            },
        )
        bar.add(
            JButton("Zoom -").apply {
                addActionListener { zoomOut() }
            },
        )
        bar.add(
            JButton("Fit").apply {
                addActionListener { fitView() }
            },
        )
        return bar
    }

    // ---- public API -------------------------------------------------------

    fun loadSvg(text: String) {
        engine.load(text)
        selectedId = null
        hoveredId = null
        interaction.selected = null
        interaction.previewBox = null
        interaction.selectedHandle = null
        recomputeView()
        renderAtDeviceSize()
        canvas.repaint()
        emitStatus()
    }

    /** Current layout (empty until an SVG is loaded). */
    val layout: SvgLayout get() = engine.layout

    /** SVG source after edits. */
    val svgSource: String get() = engine.svg

    /** Id of the currently selected element, or null. */
    val selectedElementId: String? get() = selectedId

    /** Current zoom factor (1.0 = fit). */
    fun getZoom(): Double = zoom

    fun zoomIn() = zoomBy(1.2)

    fun zoomOut() = zoomBy(1.0 / 1.2)

    /** Reset zoom to 100% of the fit view. */
    fun fitView() {
        zoom = 1.0
        recomputeView()
        renderAtDeviceSize()
        canvas.repaint()
        emitStatus()
    }

    /** Hit-test in panel-pixel coordinates, returning the element id under the point. */
    fun elementAt(
        panelX: Int,
        panelY: Int,
    ): String? {
        val (ix, iy) = toImage(panelX, panelY)
        return CollisionDetector.hitTest(engine.layout, ix, iy)?.id
    }

    /** Test hook: the inner canvas component (for synthetic event dispatch in tests). */
    internal fun debugCanvas(): java.awt.Component = canvas

    // ---- internals --------------------------------------------------------

    private fun decode(png: ByteArray): BufferedImage? =
        try {
            ImageIO.read(java.io.ByteArrayInputStream(png))
        } catch (e: Exception) {
            null
        }

    /** Recompute viewScale + offset + canvas preferred size from the current zoom. */
    private fun recomputeView() {
        val w = engine.layout.width
        val h = engine.layout.height
        if (w <= 0 || h <= 0) return
        // Until the canvas is laid out (e.g. headless tests) keep identity mapping so panel
        // pixels equal SVG coordinates; the real size is picked up on the first resize event.
        val cw = canvas.width.takeIf { it > 0 } ?: return
        val ch = canvas.height.takeIf { it > 0 } ?: return
        val fit = ((cw - 2 * pad) / w).coerceAtMost((ch - 2 * pad) / h).coerceAtLeast(0.01)
        viewScale = fit * zoom
        offsetX = (cw - w * viewScale) / 2.0
        offsetY = (ch - h * viewScale) / 2.0
        canvas.preferredSize =
            Dimension(
                (w * viewScale).toInt().coerceAtLeast(1),
                (h * viewScale).toInt().coerceAtLeast(1),
            )
        canvas.revalidate()
    }

    /**
     * Render the off-screen PNG at the panel's true device resolution. The image is then
     * drawn 1:1 (in device pixels) which removes the upscaling aliasing. DPI is read from
     * the graphics configuration so HiDPI screens get a correspondingly larger render.
     */
    private fun renderAtDeviceSize() {
        val w = engine.layout.width
        val h = engine.layout.height
        if (w <= 0 || h <= 0) return
        dpiScale = currentDpi()
        val rw = kotlin.math.max(1, kotlin.math.round(w * viewScale * dpiScale).toInt())
        val rh = kotlin.math.max(1, kotlin.math.round(h * viewScale * dpiScale).toInt())
        engine.renderAt(rw, rh)
        offscreen = decode(engine.png)
    }

    private fun currentDpi(): Double {
        val s = canvas.graphicsConfiguration?.defaultTransform?.scaleX ?: 1.0
        return if (s.isFinite() && s > 0) s else 1.0
    }

    /** Zoom by `factor`, keeping the SVG point under `(ax, ay)` (panel px) fixed when given. */
    private fun zoomBy(
        factor: Double,
        ax: Double? = null,
        ay: Double? = null,
    ) {
        val w = engine.layout.width
        val h = engine.layout.height
        if (w <= 0 || h <= 0) return
        val cw = canvas.width.takeIf { it > 0 } ?: 640
        val ch = canvas.height.takeIf { it > 0 } ?: 420
        val oldView = viewScale
        val svgX = if (ax != null) (ax - offsetX) / oldView else null
        val svgY = if (ay != null) (ay - offsetY) / oldView else null
        zoom = (zoom * factor).coerceIn(0.1, 16.0)
        val fit = ((cw - 2 * pad) / w).coerceAtMost((ch - 2 * pad) / h).coerceAtLeast(0.01)
        viewScale = fit * zoom
        if (svgX != null && svgY != null) {
            offsetX = ax!! - svgX * viewScale
            offsetY = ay!! - svgY * viewScale
        } else {
            offsetX = (cw - w * viewScale) / 2.0
            offsetY = (ch - h * viewScale) / 2.0
        }
        canvas.preferredSize =
            Dimension(
                (w * viewScale).toInt().coerceAtLeast(1),
                (h * viewScale).toInt().coerceAtLeast(1),
            )
        canvas.revalidate()
        renderAtDeviceSize()
        canvas.repaint()
        emitStatus()
    }

    /** Panel pixels -> SVG/canvas coordinates (DPI-independent). */
    private fun toImage(
        mx: Int,
        my: Int,
    ): Pair<Double, Double> = ((mx - offsetX) / viewScale) to ((my - offsetY) / viewScale)

    private fun installMouse() {
        canvas.addMouseMotionListener(
            object : MouseMotionAdapter() {
                override fun mouseMoved(e: MouseEvent) = handleHover(e.x, e.y)

                override fun mouseDragged(e: MouseEvent) = handleDrag(e.x, e.y)
            },
        )

        canvas.addMouseListener(
            object : MouseAdapter() {
                override fun mousePressed(e: MouseEvent) = handlePress(e.x, e.y)

                override fun mouseReleased(e: MouseEvent) = handleRelease()

                override fun mouseClicked(e: MouseEvent) {
                    if (e.clickCount == 2) handleDoubleClick(e.x, e.y)
                }
            },
        )

        canvas.addMouseWheelListener(
            object : MouseWheelListener {
                override fun mouseWheelMoved(e: MouseWheelEvent) {
                    val f = if (e.wheelRotation < 0) 1.1 else 1.0 / 1.1
                    zoomBy(f, e.x.toDouble(), e.y.toDouble())
                }
            },
        )
    }

    // --- event handlers (also exercised directly by tests via debug* hooks) -------

    private fun handleHover(
        x: Int,
        y: Int,
    ) {
        pointer.x = x
        pointer.y = y
        val (ix, iy) = toImage(x, y)
        val tol = 6.0 / viewScale
        interaction.onHoverMove(engine.layout, ix, iy, tol)
        hoveredId = interaction.hovered?.id
        canvas.cursor =
            when (interaction.selectedHandle) {
                InteractionController.Handle.NW, InteractionController.Handle.SE -> Cursor(Cursor.NW_RESIZE_CURSOR)
                InteractionController.Handle.NE, InteractionController.Handle.SW -> Cursor(Cursor.NE_RESIZE_CURSOR)
                InteractionController.Handle.N, InteractionController.Handle.S -> Cursor(Cursor.N_RESIZE_CURSOR)
                InteractionController.Handle.E, InteractionController.Handle.W -> Cursor(Cursor.E_RESIZE_CURSOR)
                null ->
                    if (interaction.hovered != null) {
                        Cursor(Cursor.MOVE_CURSOR)
                    } else {
                        Cursor.getDefaultCursor()
                    }
            }
        canvas.repaint()
    }

    private fun handleDrag(
        x: Int,
        y: Int,
    ) {
        pointer.x = x
        pointer.y = y
        val (ix, iy) = toImage(x, y)
        interaction.onDragMove(engine.layout, ix, iy)
        canvas.repaint()
    }

    private fun handlePress(
        x: Int,
        y: Int,
    ) {
        val (ix, iy) = toImage(x, y)
        val tol = 6.0 / viewScale
        interaction.onMousePressed(engine.layout, ix, iy, tol)
        selectedId = interaction.selected?.id
        canvas.repaint()
    }

    private fun handleRelease() {
        when (val res = interaction.onMouseReleased()) {
            is InteractionController.EditResult.Move -> {
                engine.moveElement(res.element.id, res.dx, res.dy)
                offscreen = decode(engine.png)
                selectedId = res.element.id
            }
            is InteractionController.EditResult.Resize -> {
                engine.setElementBox(res.element.id, res.x, res.y, res.w, res.h)
                offscreen = decode(engine.png)
                selectedId = res.element.id
            }
            null -> {}
        }
        canvas.repaint()
        emitStatus()
    }

    private fun handleDoubleClick(
        x: Int,
        y: Int,
    ) {
        val (ix, iy) = toImage(x, y)
        if (interaction.onDoubleClick(engine.layout, ix, iy)) {
            selectedId = interaction.selected?.id
            canvas.repaint()
            emitStatus()
        }
    }

    /** Test hook: select an element via double-click. */
    internal fun debugDoubleClick(
        x: Int,
        y: Int,
    ) = handleDoubleClick(x, y)

    /** Test hook: drive a full press-drag-release cycle deterministically. */
    internal fun debugDrag(
        p1: Point,
        p2: Point,
    ) {
        handlePress(p1.x, p1.y)
        handleDrag(p2.x, p2.y)
        handleRelease()
    }

    private fun emitStatus() {
        val sel = selectedId?.let { "Selected: $it" } ?: "No selection"
        onStatus?.invoke("Zoom: ${(zoom * 100).toInt()}% · $sel")
    }

    private fun renderCanvas(g: Graphics2D) {
        g.color = background
        g.fillRect(0, 0, width, height)

        offscreen?.let { img ->
            val dw = (img.width / dpiScale).toInt().coerceAtLeast(1)
            val dh = (img.height / dpiScale).toInt().coerceAtLeast(1)
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
            g.drawImage(img, offsetX.toInt(), offsetY.toInt(), dw, dh, null)
        } ?: run {
            g.color = Color.GRAY
            g.drawString("No SVG loaded", 16, 24)
        }

        // Hovered element (blue) — unless it is the selected one.
        hoveredId?.takeIf { it != selectedId }?.let { engine.layout.byId(it) }?.let { drawRect(g, it, Color(0x42, 0xA5, 0xF5), 1.5f) }

        // Selected element: yellow box + 8 control points.
        selectedId?.let { engine.layout.byId(it) }?.let { el ->
            val rx = (offsetX + el.x * viewScale).toInt()
            val ry = (offsetY + el.y * viewScale).toInt()
            val rw = (el.width * viewScale).toInt()
            val rh = (el.height * viewScale).toInt()
            g.color = Color(0xFF, 0xEB, 0x3B)
            g.stroke = BasicStroke(1.5f)
            g.drawRect(rx, ry, rw, rh)
            drawHandles(g, el)
        }

        // Live drag preview (translucent) for move/resize.
        interaction.previewBox?.let { b ->
            val rx = (offsetX + b.x * viewScale).toInt()
            val ry = (offsetY + b.y * viewScale).toInt()
            val rw = (b.w * viewScale).toInt()
            val rh = (b.h * viewScale).toInt()
            g.color = Color(0xFF, 0xEB, 0x3B, 120)
            g.fillRect(rx, ry, rw, rh)
            g.color = Color(0xFF, 0xEB, 0x3B)
            g.stroke = BasicStroke(1.0f)
            g.drawRect(rx, ry, rw, rh)
        }

        // Crosshair uses a theme-aware contrast colour so it stays visible in both
        // IntelliJ Light and Darcula.
        val bg = canvas.background
        val lum = 0.299 * bg.red + 0.587 * bg.green + 0.114 * bg.blue
        g.color = if (lum > 140) Color(0x33, 0x33, 0x33) else Color.WHITE
        g.drawLine(pointer.x - 8, pointer.y, pointer.x + 8, pointer.y)
        g.drawLine(pointer.x, pointer.y - 8, pointer.x, pointer.y + 8)
    }

    private fun drawRect(
        g: Graphics2D,
        el: SvgElement,
        color: Color,
        lw: Float,
    ) {
        g.color = color
        g.stroke = BasicStroke(lw)
        g.drawRect(
            (offsetX + el.x * viewScale).toInt(),
            (offsetY + el.y * viewScale).toInt(),
            (el.width * viewScale).toInt(),
            (el.height * viewScale).toInt(),
        )
    }

    private fun drawHandles(
        g: Graphics2D,
        el: SvgElement,
    ) {
        val box = InteractionController.Box(el.x, el.y, el.width, el.height)
        val s = 7
        for (h in InteractionController.Handle.entries) {
            val (hx, hy) = interaction.handlePoint(box, h)
            val px = (offsetX + hx * viewScale).toInt()
            val py = (offsetY + hy * viewScale).toInt()
            val active = interaction.selectedHandle == h
            g.color = if (active) Color.WHITE else Color(0xFF, 0xEB, 0x3B)
            g.fillRect(px - s / 2, py - s / 2, s, s)
            g.color = Color(0x33, 0x33, 0x33)
            g.drawRect(px - s / 2, py - s / 2, s, s)
        }
    }
}
