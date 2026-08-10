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
import javax.swing.JPanel
import javax.swing.JScrollPane

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
 *  4. Interaction: click / double-click selects an element and shows a Leafier-style edit box
 *     (thin accent outline, 8 control points, a rotate handle above). Dragging the body moves
 *     it, dragging a handle resizes it.
 *
 * Smooth dragging: when an element is selected we build TWO cached rasters — a background
 * layer (everything except the selected element) and a foreground layer (only the selected
 * element). During a drag we blit the static background and offset/scale the foreground with a
 * plain drawImage — zero resvg calls — so the element follows the cursor at 60fps instead of
 * only a yellow preview box moving. The edit is committed (and the whole SVG re-rasterized)
 * once on release.
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

    /** Drag layers (only populated while an element is selected). */
    private var bgImage: BufferedImage? = null
    private var fgImage: BufferedImage? = null
    private var layerId: String? = null

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

    companion object {
        private val ACCENT = Color(0x32, 0xCD, 0x79) // Leafier green
        private val ROTATE_OFFSET = 22 // px above the box top
        private val ROTATE_R = 5 // rotate handle radius
        private val HANDLE = 8 // control point size
        private val GRID_STEP = 24 // px
    }

    private val canvas =
        object : JPanel() {
            override fun paintComponent(g: Graphics) {
                super.paintComponent(g)
                renderCanvas(g as Graphics2D)
            }
        }

    init {
        setLayout(java.awt.BorderLayout())
        // No hard-coded background: FlatLaf drives the canvas background so the editor follows
        // the active theme (IntelliJ Light = near-white, Darcula = dark grey).
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
        installMouse()
    }

    // ---- public API -------------------------------------------------------

    fun loadSvg(text: String) {
        engine.load(text)
        selectedId = null
        hoveredId = null
        interaction.selected = null
        interaction.previewBox = null
        interaction.selectedHandle = null
        clearLayers()
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
    fun debugCanvas(): java.awt.Component = canvas

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
        // Keep drag layers crisp after zoom / resize / DPI changes.
        if (selectedId != null) rebuildLayers()
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

    private fun rebuildLayers() {
        if (selectedId != null) {
            engine.selectForEditing(selectedId!!)
            bgImage = decode(engine.bgPng)
            fgImage = decode(engine.fgPng)
            layerId = selectedId
        } else {
            clearLayers()
        }
    }

    private fun clearLayers() {
        engine.clearLayers()
        bgImage = null
        fgImage = null
        layerId = null
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
        // paintImmediately is synchronous on the EDT, so the preview follows the cursor
        // without the lag of an asynchronous repaint().
        if (canvas.isShowing) {
            canvas.paintImmediately(0, 0, canvas.width, canvas.height)
        } else {
            canvas.repaint()
        }
    }

    private fun handlePress(
        x: Int,
        y: Int,
    ) {
        val (ix, iy) = toImage(x, y)
        val tol = 6.0 / viewScale
        interaction.onMousePressed(engine.layout, ix, iy, tol)
        val newSel = interaction.selected?.id
        selectedId = newSel
        // Always re-bake the drag layers on press. The cached layers are resolution-dependent
        // (viewScale/dpiScale), so they must be refreshed even if the selection id did not change
        // (e.g. after zooming, or pressing an already-selected element to drag again).
        if (newSel != null) rebuildLayers() else clearLayers()
        canvas.repaint()
    }

    private fun handleRelease() {
        when (val res = interaction.onMouseReleased()) {
            is InteractionController.EditResult.Move -> {
                engine.moveElement(res.element.id, res.dx, res.dy)
                offscreen = decode(engine.png)
                selectedId = res.element.id
                rebuildLayers()
            }
            is InteractionController.EditResult.Resize -> {
                engine.setElementBox(res.element.id, res.x, res.y, res.w, res.h)
                offscreen = decode(engine.png)
                selectedId = res.element.id
                rebuildLayers()
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
            rebuildLayers()
            canvas.repaint()
            emitStatus()
        }
    }

    /** Test hook: select an element via double-click. */
    fun debugDoubleClick(
        x: Int,
        y: Int,
    ) = handleDoubleClick(x, y)

    /** Test hook: drive a full press-drag-release cycle deterministically. */
    fun debugDrag(
        p1: Point,
        p2: Point,
    ) {
        handlePress(p1.x, p1.y)
        handleDrag(p2.x, p2.y)
        handleRelease()
    }

    /** Test hook: panel-pixel center of an element (uses the same view math as rendering). */
    fun debugElementCenterPx(id: String): Point? {
        val el = engine.layout.byId(id) ?: return null
        return Point(
            (offsetX + (el.x + el.width / 2) * viewScale).toInt(),
            (offsetY + (el.y + el.height / 2) * viewScale).toInt(),
        )
    }

    /** Test hook: run press + drag but NOT release (so callers can capture the mid-drag frame). */
    fun debugPressDrag(
        x1: Int,
        y1: Int,
        x2: Int,
        y2: Int,
    ) {
        handlePress(x1, y1)
        handleDrag(x2, y2)
    }

    /** Test hook: finish a press-drag started with [debugPressDrag]. */
    fun debugRelease() = handleRelease()

    /** Test hook: render the canvas onto an off-screen image for visual inspection. */
    fun debugRenderTo(img: BufferedImage) {
        val g2 = img.createGraphics()
        g2.color = Color.WHITE
        g2.fillRect(0, 0, img.width, img.height)
        g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
        g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
        canvas.paint(g2)
        g2.dispose()
    }

    private fun emitStatus() {
        val sel = selectedId?.let { "Selected: $it" } ?: "No selection"
        onStatus?.invoke("Zoom: ${(zoom * 100).toInt()}% · $sel")
    }

    // ---- rendering --------------------------------------------------------

    private fun renderCanvas(g: Graphics2D) {
        g.color = background
        g.fillRect(0, 0, width, height)

        drawGrid(g)

        val dragging = interaction.state == InteractionController.State.DRAG
        if (dragging && layerId != null && bgImage != null && fgImage != null) {
            // Smooth path: static background + offset/scaled foreground, no resvg.
            drawScaled(g, bgImage!!)
            drawFg(g)
        } else {
            offscreen?.let { drawScaled(g, it) }
        }

        drawHover(g)
        drawSelection(g)
        drawSnap(g)
        drawCrosshair(g)
    }

    private fun drawScaled(
        g: Graphics2D,
        img: BufferedImage,
    ) {
        val dw = (img.width / dpiScale).toInt().coerceAtLeast(1)
        val dh = (img.height / dpiScale).toInt().coerceAtLeast(1)
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
        g.drawImage(img, offsetX.toInt(), offsetY.toInt(), dw, dh, null)
    }

    /**
     * Composite the foreground (selected element) at the preview box, by cropping its region
     * out of the foreground raster and drawing it at the target box. Works for both move and
     * resize because [InteractionController.previewBox] already holds the target geometry.
     */
    private fun drawFg(g: Graphics2D) {
        val el = engine.layout.byId(layerId!!) ?: return
        val preview = interaction.previewBox ?: return
        val dpr = dpiScale
        // Source crop in device pixels within the cached foreground raster.
        val sxp = (el.x * viewScale * dpr).toInt().coerceAtLeast(0)
        val syp = (el.y * viewScale * dpr).toInt().coerceAtLeast(0)
        val swp = (el.width * viewScale * dpr).toInt().coerceAtLeast(1)
        val shp = (el.height * viewScale * dpr).toInt().coerceAtLeast(1)
        // Destination box in panel pixels.
        val txp = offsetX + preview.x * viewScale
        val typ = offsetY + preview.y * viewScale
        val twp = preview.w * viewScale
        val thp = preview.h * viewScale
        if (sxp + swp > (fgImage?.width ?: 0) || syp + shp > (fgImage?.height ?: 0)) return
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
        // Use a subimage so we get x,y,width,height semantics instead of the error-prone
        // drawImage(..., sx1, sy1, sx2, sy2) overload where x2/y2 are coordinates.
        val crop = fgImage!!.getSubimage(sxp, syp, swp, shp)
        g.drawImage(crop, txp.toInt(), typ.toInt(), twp.toInt(), thp.toInt(), null)
    }

    private fun drawGrid(g: Graphics2D) {
        val bg = background
        val lum = 0.299 * bg.red + 0.587 * bg.green + 0.114 * bg.blue
        g.color = if (lum > 140) Color(0xE2, 0xE2, 0xE8) else Color(0x2C, 0x2C, 0x34)
        g.stroke = BasicStroke(1f)
        val startX = offsetX.toInt() % GRID_STEP
        val startY = offsetY.toInt() % GRID_STEP
        var x = startX
        while (x < width) {
            g.drawLine(x, 0, x, height)
            x += GRID_STEP
        }
        var y = startY
        while (y < height) {
            g.drawLine(0, y, width, y)
            y += GRID_STEP
        }
    }

    private fun drawHover(g: Graphics2D) {
        hoveredId?.takeIf { it != selectedId }?.let { engine.layout.byId(it) }?.let { el ->
            val rx = (offsetX + el.x * viewScale).toInt()
            val ry = (offsetY + el.y * viewScale).toInt()
            val rw = (el.width * viewScale).toInt()
            val rh = (el.height * viewScale).toInt()
            g.color = Color(ACCENT.red, ACCENT.green, ACCENT.blue, 130)
            g.stroke = BasicStroke(1.5f)
            g.drawRect(rx, ry, rw, rh)
        }
    }

    private fun drawSelection(g: Graphics2D) {
        selectedId?.let { engine.layout.byId(it) }?.let { el ->
            // While dragging, the box tracks the (snapped) preview box so the edit box follows
            // the element; otherwise it sits on the element's resting geometry.
            val box = interaction.previewBox
                ?: InteractionController.Box(el.x, el.y, el.width, el.height)
            val rx = (offsetX + box.x * viewScale).toInt()
            val ry = (offsetY + box.y * viewScale).toInt()
            val rw = (box.w * viewScale).toInt()
            val rh = (box.h * viewScale).toInt()

            // Selection rectangle.
            g.color = ACCENT
            g.stroke = BasicStroke(1.5f)
            g.drawRect(rx, ry, rw, rh)

            // Rotate handle: a line up from the top-centre to a circular grip.
            val cx = rx + rw / 2
            val handleY = ry - ROTATE_OFFSET
            g.drawLine(cx, ry, cx, handleY)
            g.color = Color.WHITE
            g.fillOval(cx - ROTATE_R, handleY - ROTATE_R, ROTATE_R * 2, ROTATE_R * 2)
            g.color = ACCENT
            g.stroke = BasicStroke(1.5f)
            g.drawOval(cx - ROTATE_R, handleY - ROTATE_R, ROTATE_R * 2, ROTATE_R * 2)

            // 8 control points (white fill, accent outline).
            val s = HANDLE
            for (h in InteractionController.Handle.entries) {
                val (hx, hy) = interaction.handlePoint(box, h)
                val px = (offsetX + hx * viewScale).toInt()
                val py = (offsetY + hy * viewScale).toInt()
                g.color = Color.WHITE
                g.fillRect(px - s / 2, py - s / 2, s, s)
                g.color = ACCENT
                g.stroke = BasicStroke(1.5f)
                g.drawRect(px - s / 2, py - s / 2, s, s)
            }
        }
    }

    private fun drawSnap(g: Graphics2D) {
        if (interaction.snapLines.isEmpty()) return
        g.color = Color(0xFF, 0x3B, 0x3B)
        g.stroke = BasicStroke(1f)
        for (line in interaction.snapLines) {
            if (line.vertical) {
                val x = (offsetX + line.pos * viewScale).toInt()
                g.drawLine(x, 0, x, height)
            } else {
                val y = (offsetY + line.pos * viewScale).toInt()
                g.drawLine(0, y, width, y)
            }
        }
    }

    private fun drawCrosshair(g: Graphics2D) {
        val bg = canvas.background
        val lum = 0.299 * bg.red + 0.587 * bg.green + 0.114 * bg.blue
        g.color = if (lum > 140) Color(0x33, 0x33, 0x33) else Color.WHITE
        g.drawLine(pointer.x - 8, pointer.y, pointer.x + 8, pointer.y)
        g.drawLine(pointer.x, pointer.y - 8, pointer.x, pointer.y + 8)
    }
}
