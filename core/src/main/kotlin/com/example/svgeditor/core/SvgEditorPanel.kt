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
    /** Foreground cropped to the element's bounding box (+rotation padding) for a cheap blit. */
    private var fgCrop: BufferedImage? = null
    private var fgCropX = 0.0
    private var fgCropY = 0.0
    private var layerId: String? = null

    /** Static composite (background + grid + base raster) baked once per view/selection change. */
    private var staticLayer: BufferedImage? = null
    private var staticDirty = true
    private var staticDrag = false
    private var staticBgColor: Color? = null

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
        staticDirty = true // base raster changed; re-bake the static composite
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
            buildFgCrop()
        } else {
            clearLayers()
        }
    }

    /**
     * Crop [fgImage] down to the selected element's bounding box (plus padding so an arbitrary
     * rotation never clips), so the per-frame foreground blit draws a small image instead of the
     * whole canvas-sized raster.
     */
    private fun buildFgCrop() {
        val fg = fgImage ?: run { fgCrop = null; return }
        val el = engine.layout.byId(layerId ?: return) ?: run { fgCrop = null; return }
        val dpr = dpiScale
        val sx0 = el.x * viewScale * dpr
        val sy0 = el.y * viewScale * dpr
        val sw0 = el.width * viewScale * dpr
        val sh0 = el.height * viewScale * dpr
        if (sw0 <= 0.0 || sh0 <= 0.0) {
            fgCrop = null
            return
        }
        // Pad by the larger dimension so the rect keeps all its corners inside the crop for any angle.
        val pad = kotlin.math.max(sw0, sh0)
        var cx0 = (sx0 - pad).coerceAtLeast(0.0)
        var cy0 = (sy0 - pad).coerceAtLeast(0.0)
        val right = (sx0 + sw0 + pad).coerceAtMost(fg.width.toDouble())
        val bottom = (sy0 + sh0 + pad).coerceAtMost(fg.height.toDouble())
        val cw0 = right - cx0
        val ch0 = bottom - cy0
        if (cw0 < 1 || ch0 < 1) {
            fgCrop = null
            return
        }
        fgCrop = fg.getSubimage(cx0.toInt(), cy0.toInt(), cw0.toInt(), ch0.toInt())
        fgCropX = cx0
        fgCropY = cy0
    }

    private fun clearLayers() {
        engine.clearLayers()
        bgImage = null
        fgImage = null
        fgCrop = null
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
        // repaint() (not paintImmediately) lets Swing coalesce multiple mouse-dragged events
        // into a single paint aligned with the display refresh — so the EDT is never saturated
        // by synchronous full-canvas repaints (the previous cause of dropped frames / stutter).
        canvas.repaint()
    }

    private fun handlePress(
        x: Int,
        y: Int,
    ) {
        val (ix, iy) = toImage(x, y)
        val tol = 6.0 / viewScale
        // The rotate handle (a circle above the selection box) takes priority over body/resize
        // hits. It is only reachable once an element is already selected.
        selectedId?.let { sid ->
            engine.layout.byId(sid)?.let { el ->
                val rx = offsetX + el.x * viewScale
                val ry = offsetY + el.y * viewScale
                val rw = el.width * viewScale
                val rh = el.height * viewScale
                val hcx = rx + rw / 2.0
                val hcy = ry - ROTATE_OFFSET
                if (kotlin.math.hypot(x - hcx, y - hcy) <= (ROTATE_R + 6.0)) {
                    val cxSvg = el.x + el.width / 2.0
                    val cySvg = el.y + el.height / 2.0
                    val ang = kotlin.math.atan2(iy - cySvg, ix - cxSvg) * 180.0 / Math.PI
                    selectedId = sid
                    interaction.startRotate(cxSvg, cySvg, ang)
                    rebuildLayers()
                    canvas.repaint()
                    return
                }
            }
        }
        interaction.onMousePressed(engine.layout, ix, iy, tol)
        val newSel = interaction.selected?.id
        selectedId = newSel
        // Always re-bake the drag layers on press. The cached layers are resolution-dependent
        // (viewScale/dpiScale), so they must be refreshed even if the selection id did not change
        // (e.g. after zooming, or pressing an already-selected element to drag again).
        if (newSel != null) rebuildLayers() else clearLayers()
        staticDirty = true // drag state / base raster changed; re-bake the static composite
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
            is InteractionController.EditResult.Rotate -> {
                engine.rotateElement(res.element.id, res.angle, res.cx, res.cy)
                offscreen = decode(engine.png)
                selectedId = res.element.id
                rebuildLayers()
            }
            null -> {}
        }
        staticDirty = true // offscreen / selection changed; re-bake the static composite
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

    /** Test hook: force a DPI scale (simulates a HiDPI display in headless tests). */
    fun debugSetDpi(d: Double) {
        dpiScale = d
        renderAtDeviceSize()
        staticDirty = true
        canvas.repaint()
    }

    /** Test hook: render the canvas onto an off-screen image for visual inspection.
     *  `scale` simulates a HiDPI device: the canvas is painted through a `scale(scale,scale)`
     *  transform, exactly like a Retina/HiDPI Graphics2D, into an image sized logical*scale. */
    fun debugRenderTo(
        img: BufferedImage,
        scale: Double = 1.0,
    ) {
        val g2 = img.createGraphics()
        g2.scale(scale, scale)
        g2.color = Color.WHITE
        g2.fillRect(0, 0, (img.width / scale).toInt(), (img.height / scale).toInt())
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
        // Use the layered compositing (baked background + cropped foreground) whenever a
        // selected element has cached layers — both DURING a drag and while it is at rest.
        // This keeps the rendering representation identical across the drag->release boundary,
        // so the element never "pops" from the raster preview to a re-rendered offscreen (the
        // flash + apparent position jump reported at drag end). The foreground crop is simply
        // re-baked at the committed position on release, and committed == last preview, so it
        // lands in exactly the same place.
        val useLayers = layerId != null && fgImage != null
        if (useLayers) {
            staticDrag = true // base raster for the layers is the bg layer (element hidden)
            if (staticLayer == null || staticDirty || staticBgColor != background) {
                rebuildStaticLayer()
            }
            staticLayer?.let { g.drawImage(it, 0, 0, null) }
                ?: run { g.color = background; g.fillRect(0, 0, width, height) }
            if (fgCrop != null) {
                drawFg(g)
            }
        } else {
            // Idle path: draw the full render directly. We skip the intermediate static layer for
            // idle frames because routing `offscreen` through an extra BufferedImage can trigger
            // Java2D colour-management conversions that shift exact pixel values and break the
            // headless pixel-consistency checks. Drag frames are unaffected because the dragged
            // element is supplied by `fgCrop`, not by the base raster.
            g.color = background
            g.fillRect(0, 0, width, height)
            drawGrid(g)
            offscreen?.let { drawScaled(g, it) }
            staticDirty = true // ensure the next drag re-bakes with the current base raster
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
        // Float placement (no integer truncation) so the committed/offscreen raster is composited
        // at the exact same sub-pixel position as the drag preview — eliminating the last source
        // of a systematic "position different" shift at drag end on fractional-DPI displays.
        val dw = img.width / dpiScale
        val dh = img.height / dpiScale
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
        val at = java.awt.geom.AffineTransform()
        at.translate(offsetX, offsetY)
        at.scale(dw / img.width, dh / img.height)
        g.drawImage(img, at, null)
    }

    /**
     * Bake the static composite — background fill + grid + the base raster — into a single
     * [BufferedImage] sized to the panel. During a drag the base raster is [bgImage] (the
     * selected element hidden, drawn separately as the foreground); otherwise it is [offscreen]
     * (the full render). Rebuilt only when the view / selection / theme changes.
     */
    private fun rebuildStaticLayer() {
        if (width <= 0 || height <= 0) {
            staticLayer = null
            return
        }
        val img = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val g2 = img.createGraphics()
        g2.color = background
        g2.fillRect(0, 0, width, height)
        drawGrid(g2)
        val base = if (staticDrag && bgImage != null) bgImage else offscreen
        if (base != null) {
            val dw = base.width / dpiScale
            val dh = base.height / dpiScale
            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
            val at = java.awt.geom.AffineTransform()
            at.translate(offsetX, offsetY)
            at.scale(dw / base.width, dh / base.height)
            g2.drawImage(base, at, null)
        }
        g2.dispose()
        staticLayer = img
        staticBgColor = background
        staticDirty = false
    }

    /**
     * Composite the foreground (selected element) at the preview box using a single floating
     * point [java.awt.geom.AffineTransform] over the pre-cropped [fgCrop] raster. The crop is
     * just the element's bounding box (plus rotation padding), so this blits a small image
     * instead of the whole canvas-sized raster — cheap enough to run every frame at full fps.
     * The continuous transform means move/resize/rotate follow the cursor smoothly.
     */
    private fun drawFg(g: Graphics2D) {
        val fg = fgCrop ?: return
        val el = engine.layout.byId(layerId!!) ?: return
        // While dragging, `previewBox` is the live (snapped) box; at rest it is null, so fall
        // back to the element's committed box. Both are in SVG units.
        val box = interaction.previewBox
            ?: InteractionController.Box(el.x, el.y, el.width, el.height)
        val dpr = dpiScale
        val sw0 = el.width * viewScale * dpr
        val sh0 = el.height * viewScale * dpr
        if (sw0 <= 0.0 || sh0 <= 0.0) return
        // Destination box in logical (panel) pixels.
        val dw = box.w * viewScale
        val dh = box.h * viewScale
        val dx = offsetX + box.x * viewScale
        val dy = offsetY + box.y * viewScale
        // Element top-left within the crop, in device pixels.
        val ex = el.x * viewScale * dpr - fgCropX
        val ey = el.y * viewScale * dpr - fgCropY
        val s = dw / sw0
        val d = dh / sh0
        val rad = Math.toRadians(interaction.previewAngle)
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
        // Map the element centre (crop-local) -> the destination centre, scaling then rotating
        // about that centre. Applied right-to-left, so the centre lands exactly on the preview
        // box centre regardless of the padding offset baked into the crop.
        val at = java.awt.geom.AffineTransform()
        at.translate(dx + dw / 2.0, dy + dh / 2.0)
        at.rotate(rad)
        at.scale(s, d)
        at.translate(-(ex + sw0 / 2.0), -(ey + sh0 / 2.0))
        g.drawImage(fg, at, null)
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
        selectedId?.let { id ->
            engine.layout.byId(id)?.let { el ->
                // While dragging, the box tracks the (snapped) preview box; otherwise it sits on
                // the element's resting geometry. previewAngle rotates the whole overlay.
                val box = interaction.previewBox
                    ?: InteractionController.Box(el.x, el.y, el.width, el.height)
                val rad = Math.toRadians(interaction.previewAngle)
                val bcx = offsetX + (box.x + box.w / 2) * viewScale
                val bcy = offsetY + (box.y + box.h / 2) * viewScale
                val rot: (Double, Double) -> Pair<Double, Double> = { px, py -> rotatePt(px, py, bcx, bcy, rad) }

                // Selection outline (rotated polygon).
                val corners =
                    arrayOf(
                        rot(offsetX + box.x * viewScale, offsetY + box.y * viewScale),
                        rot(offsetX + (box.x + box.w) * viewScale, offsetY + box.y * viewScale),
                        rot(offsetX + (box.x + box.w) * viewScale, offsetY + (box.y + box.h) * viewScale),
                        rot(offsetX + box.x * viewScale, offsetY + (box.y + box.h) * viewScale),
                    )
                g.color = ACCENT
                g.stroke = BasicStroke(1.5f)
                for (i in 0..3) {
                    val a = corners[i]
                    val b = corners[(i + 1) % 4]
                    g.drawLine(a.first.toInt(), a.second.toInt(), b.first.toInt(), b.second.toInt())
                }

                // 8 control points (white fill, accent outline).
                val s = HANDLE
                for (h in InteractionController.Handle.entries) {
                    val (hx, hy) = interaction.handlePoint(box, h)
                    val (px, py) = rot(offsetX + hx * viewScale, offsetY + hy * viewScale)
                    g.color = Color.WHITE
                    g.fillRect(px.toInt() - s / 2, py.toInt() - s / 2, s, s)
                    g.color = ACCENT
                    g.stroke = BasicStroke(1.5f)
                    g.drawRect(px.toInt() - s / 2, py.toInt() - s / 2, s, s)
                }

                // Rotate handle: a line from the top-centre of the box up to a circular grip.
                val topCx = offsetX + (box.x + box.w / 2) * viewScale
                val topCy = offsetY + box.y * viewScale
                val (lx, ly) = rot(topCx, topCy)
                val (hx, hy) = rot(topCx, topCy - ROTATE_OFFSET)
                g.color = ACCENT
                g.stroke = BasicStroke(1.5f)
                g.drawLine(lx.toInt(), ly.toInt(), hx.toInt(), hy.toInt())
                g.color = Color.WHITE
                g.fillOval(hx.toInt() - ROTATE_R, hy.toInt() - ROTATE_R, ROTATE_R * 2, ROTATE_R * 2)
                g.color = ACCENT
                g.stroke = BasicStroke(1.5f)
                g.drawOval(hx.toInt() - ROTATE_R, hy.toInt() - ROTATE_R, ROTATE_R * 2, ROTATE_R * 2)
            }
        }
    }

    /** Rotate a panel-space point `(px,py)` about `(cx,cy)` by `rad` radians (y-down / clockwise). */
    private fun rotatePt(
        px: Double,
        py: Double,
        cx: Double,
        cy: Double,
        rad: Double,
    ): Pair<Double, Double> {
        val s = kotlin.math.sin(rad)
        val c = kotlin.math.cos(rad)
        val dx = px - cx
        val dy = py - cy
        return (cx + dx * c - dy * s) to (cy + dx * s + dy * c)
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
