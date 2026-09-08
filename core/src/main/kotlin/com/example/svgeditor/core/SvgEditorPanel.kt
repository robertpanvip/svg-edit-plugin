package com.example.svgeditor.core

import java.awt.BasicStroke
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Point
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.TexturePaint
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.MouseMotionAdapter
import java.awt.event.MouseWheelEvent
import java.awt.geom.AffineTransform
import java.awt.geom.Ellipse2D
import java.awt.geom.Line2D
import java.awt.geom.Path2D
import java.awt.geom.Rectangle2D
import java.awt.geom.RoundRectangle2D
import java.awt.image.BufferedImage
import java.util.concurrent.Executors
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.SwingUtilities
import javax.swing.Timer

/**
 * The SVG editor panel — a LeaferJS-style canvas surface.
 *
 * Rendering pipeline (async mode):
 *  1. The panel owns ALL raster production. Rasters are produced as raw premultiplied
 *     RGBA ([SvgRenderer.renderRgba]) on a single background thread and packed into
 *     `TYPE_INT_ARGB_PRE` images — no PNG encode/decode round-trips on any path.
 *  2. A [RenderScheduler] (latest-wins, CONTENT beats LAYERS) feeds results back on the EDT,
 *     guarded by staleness tags (a reference to the exact `svgSource` string + device size),
 *     so a slow render for an outdated view is never painted.
 *  3. Wheel zoom / window resize only resample the existing bitmap for instant feedback;
 *     a 160 ms debounce timer then re-renders once at the new device resolution.
 *  4. Hovering an element pre-heats its drag layers (background / foreground) after 120 ms,
 *     so the first drag frame is already warm — press-and-drag never blocks.
 *
 * Editing commits ([InteractionController] preview -> [SvgEditorEngine] edit) re-parse the
 * LAYOUT only; the raster refresh is scheduled through the same background pipeline.
 *
 * Interaction model:
 *  - hover highlight, click to select, LeaferJS-style accent frame with round handles + rotate grip
 *  - drag body = move, drag handle = resize, drag rotate grip = rotate (with snapping)
 *  - marquee selection on empty canvas (rubber-band, topmost element wins)
 *  - space / middle-mouse pans the scroll viewport, wheel zooms around the cursor
 *
 * Sync mode ([asyncRendering] = false, used by unit tests) performs every render inline so
 * behavior is fully deterministic — the interaction logic is shared by both modes.
 *
 * The class depends only on Swing + [SvgRenderer], so it is fully unit-testable with a fake
 * renderer (no IntelliJ SDK, no Rust toolchain required).
 */
class SvgEditorPanel(
    private val renderer: SvgRenderer,
    asyncRendering: Boolean = false,
    private val sidecar: SidecarClient? = null,
) : JPanel() {
    private val engine = SvgEditorEngine(renderer)
    private val interaction = InteractionController().apply {
        preciseHitTest = { x, y -> hitTestAt(x, y) }
    }

    /** Single background producer for all rasters; null = synchronous test mode. */
    private val scheduler: RenderScheduler? =
        if (asyncRendering) {
            RenderScheduler(
                Executors.newSingleThreadExecutor { r ->
                    Thread(r, "svg-editor-render").apply { isDaemon = true }
                },
            )
        } else {
            null
        }

    /** Off-screen canvas: the full SVG render at device resolution. */
    private var offscreen: BufferedImage? = null

    /** Drag layers: background (element hidden) + foreground (element solo), device-sized. */
    private var bgImage: BufferedImage? = null
    private var fgImage: BufferedImage? = null

    /** Foreground cropped to the element's bounding box (+rotation padding) for a cheap blit. */
    private var fgCrop: BufferedImage? = null
    private var fgCropX = 0.0
    private var fgCropY = 0.0
    private var layerId: String? = null

    /** The layer request the panel is currently waiting for (staleness guard). */
    private var wantedLayer: LayerTag? = null

    /** Static composite (background + grid + base raster) baked once per view/selection change. */
    private var staticLayer: BufferedImage? = null
    private var staticDirty = true
    private var staticDrag = false
    private var staticBgColor: Color? = null

    /** Sidecar-rendered content frame for the current view; null = legacy in-process raster. */
    private var vpImage: BufferedImage? = null

    /** True when [vpImage] is a viewport frame (pasted at 0,0); false = full-canvas region frame. */
    private var vpViewportMode = false

    /** Frozen zoom snapshot shown during wheel/resize bursts until the crisp frame lands. */
    private var vpPreview: VpPreview? = null

    /** Cleared after the sidecar fails at load time; everything then runs the legacy pipeline. */
    private var sidecarActive = sidecar != null

    private var viewScale = 1.0
    private var offsetX = 0.0
    private var offsetY = 0.0
    private var zoom = 1.0
    private var dpiScale = 1.0
    private val pad = 24.0

    private var hoveredId: String? = null
    private var pendingHoverId: String? = null
    private var selectedId: String? = null

    /** Transparency chessboard (IDEA-style) drawn behind the image. On by default. */
    private var chessboardEnabled = true

    /** Image-pixel grid, shown only at >=100%. Off by default. */
    private var gridEnabled = false

    /** Marquee (rubber-band) selection state, in panel pixels. */
    private var marqueeOrigin: Point? = null
    private var marqueeRect: Rectangle? = null

    private var spaceDown = false
    private var panLast: Point? = null

    /** Status callback (zoom % + selection) for the host application. */
    var onStatus: ((String) -> Unit)? = null

    /** Most recent render failure (async path), so a blank canvas is diagnosable instead of silent. */
    private var lastRenderError: Throwable? = null

    /** Fired when the off-EDT rasterization throws. Hosts show this instead of an empty canvas. */
    var onRenderError: ((Throwable) -> Unit)? = null

    /**
     * Fired after an interactive edit (move / resize / rotate) is committed to the SVG model.
     * Hosts that bind the panel to a document use this to write the updated [svgSource] back.
     */
    var onEdit: (() -> Unit)? = null

    /** View parameters for one sidecar content frame (Rust maps px = svg*scale + tx). */
    private data class VpTag(
        val vw: Int,
        val vh: Int,
        val scale: Double,
        val tx: Double,
        val ty: Double,
    )

    /** Frozen zoom snapshot: the last content frame + the transform that displays it at the new zoom. */
    private class VpPreview(
        val img: BufferedImage,
        val at: AffineTransform,
    )

    private class RenderTag(
        val svg: String,
        val w: Int,
        val h: Int,
    )

    private class LayerTag(
        val svg: String,
        val id: String,
        val w: Int,
        val h: Int,
    )

    private val canvas =
        object : JPanel() {
            override fun paintComponent(g: Graphics) {
                super.paintComponent(g)
                renderCanvas(g as Graphics2D)
            }
        }

    private val scrollPane = JScrollPane(canvas)

    /** Coalesces wheel-zoom / resize bursts into a single crisp re-render. */
    private val crispTimer: Timer? =
        scheduler?.let {
            Timer(CRISP_DELAY_MS) {
                renderAtDeviceSize()
                canvas.repaint()
            }.apply { isRepeats = false }
        }

    /** Pre-heats the drag layers shortly after hover, so press-and-drag never waits. */
    private val preheatTimer: Timer? =
        scheduler?.let {
            Timer(PREHEAT_DELAY_MS) {
                val id = pendingHoverId
                if (selectedId == null && id != null && id != layerId) requestLayers(id)
            }.apply { isRepeats = false }
        }

    companion object {
        private const val CRISP_DELAY_MS = 160
        private const val PREHEAT_DELAY_MS = 120

        // --- Transparency chessboard (mirrors IDEA ImageComponent defaults) ---
        private const val CHESS_CELL = 8 // px per half-cell on screen
        private val CHESS_WHITE = Color.WHITE
        private val CHESS_GRAY = Color(0xCC, 0xCC, 0xCC)

        // --- Image-pixel grid ---
        private const val GRID_SPAN = 10 // image px between grid lines
        private const val GRID_ZOOM_MIN = 1.0 // show grid only at >=100%
    }

    init {
        setLayout(BorderLayout())
        canvas.preferredSize = Dimension(640, 420)
        canvas.isFocusable = true
        canvas.addComponentListener(
            object : ComponentAdapter() {
                override fun componentResized(e: ComponentEvent) {
                    recomputeView()
                    if (scheduler != null) crispTimer?.restart() else renderAtDeviceSize()
                    canvas.repaint()
                }

                override fun componentShown(e: ComponentEvent) {
                    if (scheduler != null && offscreen == null && engine.layout.width > 0) {
                        renderAtDeviceSize()
                        canvas.repaint()
                    }
                }
            },
        )
        scrollPane.border = null
        scrollPane.verticalScrollBarPolicy = JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
        scrollPane.horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED
        add(scrollPane, BorderLayout.CENTER)
        installMouse()
        installKeys()
    }

    // ---- public API -------------------------------------------------------

    fun loadSvg(text: String) {
        val sc = sidecar
        if (sc != null && sidecarActive) {
            try {
                engine.adoptSource(text, sc.open(text))
            } catch (e: Exception) {
                // Sidecar unusable (missing binary / crashed) -> permanent fallback to the
                // legacy in-process pipeline so the panel stays fully functional.
                sidecarActive = false
                engine.loadLayoutOnly(text)
            }
        } else {
            engine.loadLayoutOnly(text)
        }
        selectedId = null
        hoveredId = null
        pendingHoverId = null
        interaction.selected = null
        interaction.previewBox = null
        interaction.selectedHandle = null
        interaction.previewAngle = 0.0
        clearLayers()
        recomputeView()
        renderAtDeviceSize()
        canvas.repaint()
        emitStatus()
    }

    /** Current layout (empty until an SVG is loaded). */
    val layout: SvgLayout get() = engine.layout

    /** SVG source after edits. */
    val svgSource: String get() = engine.svgSource

    /** Id of the currently selected element, or null. */
    val selectedElementId: String? get() = selectedId

    /** Current zoom factor (1.0 = fit). */
    fun getZoom(): Double = zoom

    fun zoomIn() = zoomBy(1.2)

    fun zoomOut() = zoomBy(1.0 / 1.2)

    /** Zoom to 100%: 1 SVG user unit == 1 screen px. */
    fun actualSize() {
        val w = engine.layout.width
        val h = engine.layout.height
        if (w <= 0 || h <= 0) return
        val cw = viewW().takeIf { it > 0 } ?: 640
        val ch = viewH().takeIf { it > 0 } ?: 420
        val fit = ((cw - 2 * pad) / w).coerceAtMost((ch - 2 * pad) / h).coerceAtLeast(0.01)
        zoom = (1.0 / fit).coerceIn(0.1, 16.0)
        recomputeView()
        renderAtDeviceSize()
        canvas.repaint()
        emitStatus()
    }

    /** Current zoom as a percentage relative to actual size. */
    fun getZoomPercent(): Int = (viewScale * 100).toInt()

    fun setChessboard(on: Boolean) {
        chessboardEnabled = on
        staticDirty = true
        canvas.repaint()
    }

    fun isChessboard(): Boolean = chessboardEnabled

    fun setGrid(on: Boolean) {
        gridEnabled = on
        staticDirty = true
        canvas.repaint()
    }

    fun isGrid(): Boolean = gridEnabled

    /** Reset zoom to the fit view. */
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
        return hitTestAt(ix, iy)?.id
    }

    /** Test hook: the inner canvas component (for synthetic event dispatch in tests). */
    fun debugCanvas(): java.awt.Component = canvas

    /** Release background resources (render thread + timers). */
    fun dispose() {
        crispTimer?.stop()
        preheatTimer?.stop()
        scheduler?.dispose()
        // The sidecar process is owned by the host (plugin), not by this panel.
    }

    // ---- internals: render pipeline ---------------------------------------

    /**
     * Canvas-space hit test: exact path hit via the sidecar when active (element ids are
     * normalized to node-id strings so selection keys stay unique), else the local
     * bounding-box detector.
     */
    private fun hitTestAt(ix: Double, iy: Double): SvgElement? {
        val sc = sidecar
        if (sc != null && sidecarActive) {
            val tol = EditorTheme.HANDLE_TOLERANCE / viewScale
            val nodeId = sc.hitTest(ix, iy, tol) ?: return null
            val el = engine.layout.byNodeId(nodeId) ?: return null
            return el.copy(id = el.id.ifBlank { nodeId.toString() })
        }
        return CollisionDetector.hitTest(engine.layout, ix, iy)
    }

    /** Logical unit -> device pixels at the current zoom + DPI. */
    private fun devicePx(u: Double): Int = kotlin.math.max(1, kotlin.math.round(u * viewScale * dpiScale).toInt())

    private fun currentDpi(): Double {
        val s = canvas.graphicsConfiguration?.defaultTransform?.scaleX ?: 1.0
        return if (s.isFinite() && s > 0) s else 1.0
    }

    /**
     * Re-request every raster for the current view. In async mode this submits background
     * jobs (never blocks the EDT); in sync mode it renders inline.
     */
    private fun renderAtDeviceSize() {
        val w = engine.layout.width
        val h = engine.layout.height
        if (w <= 0 || h <= 0) return
        dpiScale = currentDpi()
        requestContent()
        selectedId?.let { requestLayers(it) }
        staticDirty = true
    }

    private fun requestContent() {
        val sc = sidecar
        if (sc != null && sidecarActive) {
            requestContentViewport(sc)
            return
        }
        if (scheduler == null) {
            offscreen = renderNow()
            vpImage = null
            vpPreview = null
            return
        }
        val tag = RenderTag(engine.svgSource, devicePx(engine.layout.width), devicePx(engine.layout.height))
        val src = tag.svg
        val rw = tag.w
        val rh = tag.h
        scheduler.submit(
            RenderScheduler.Slot.CONTENT,
            tag,
            {
                val r =
                    try {
                        renderer.renderRgba(src, rw, rh)
                    } catch (t: Throwable) {
                        lastRenderError = t
                        null
                    }
                if (r == null) null else RgbaImages.fromRgba(r.rgba, r.width, r.height)
            },
        ) { result, t ->
            val tt = t as? RenderTag
            if (
                tt != null &&
                tt.svg === engine.svgSource &&
                tt.w == devicePx(engine.layout.width) &&
                tt.h == devicePx(engine.layout.height)
            ) {
                if (result != null) {
                    offscreen = result
                    vpImage = null
                    vpPreview = null
                    staticDirty = true
                    canvas.repaint()
                } else {
                    // Async raster failed → surface it instead of leaving a silent blank canvas.
                    lastRenderError?.let { onRenderError?.invoke(it) }
                }
            }
        }
    }

    /**
     * Sidecar content frame for the current view. Two modes:
     *  - viewport mode (the canvas fits the viewport): render exactly the visible viewport
     *    at device resolution and paste it at (0,0) — viewBox-level zooming, never a
     *    stretched bitmap, however deep the zoom;
     *  - region mode (canvas larger than the viewport): render the full canvas exactly like
     *    the legacy offscreen (tx=ty=0), so `drawScaled` pastes it at the pan offset.
     */
    private fun requestContentViewport(sc: SidecarClient) {
        val wanted = contentParams() ?: return
        val isViewport = vpViewportMode
        val task = {
            try {
                sc.renderViewport(wanted.vw, wanted.vh, wanted.scale, wanted.tx, wanted.ty)
            } catch (t: Throwable) {
                lastRenderError = t
                null
            }
        }
        val apply: (BufferedImage?) -> Unit = { result ->
            if (result != null && contentParams() == wanted && vpViewportMode == isViewport) {
                vpImage = result
                offscreen = null
                vpPreview = null
                staticDirty = true
                canvas.repaint()
            } else if (result == null) {
                lastRenderError?.let { onRenderError?.invoke(it) }
            }
        }
        if (scheduler == null) {
            apply(task())
            return
        }
        scheduler.submit(RenderScheduler.Slot.CONTENT, wanted, task) { result, _ -> apply(result) }
    }

    /** Current view parameters for a sidecar frame; also refreshes [vpViewportMode]. */
    private fun contentParams(): VpTag? {
        val w = engine.layout.width
        val h = engine.layout.height
        if (w <= 0 || h <= 0) return null
        val dpr = dpiScale
        return if (w * viewScale <= viewW() && h * viewScale <= viewH()) {
            vpViewportMode = true
            VpTag(
                vw = kotlin.math.max(1, kotlin.math.round(viewW() * dpr).toInt()),
                vh = kotlin.math.max(1, kotlin.math.round(viewH() * dpr).toInt()),
                scale = viewScale * dpr,
                tx = offsetX * dpr,
                ty = offsetY * dpr,
            )
        } else {
            vpViewportMode = false
            VpTag(
                vw = devicePx(w),
                vh = devicePx(h),
                scale = viewScale * dpr,
                tx = 0.0,
                ty = 0.0,
            )
        }
    }

    /** Inline render (sync mode / immediate paths). Returns null when there is nothing to show. */
    private fun renderNow(): BufferedImage? {
        val w = engine.layout.width
        val h = engine.layout.height
        if (w <= 0 || h <= 0) return null
        val r = renderer.renderRgba(engine.svgSource, devicePx(w), devicePx(h))
        return try {
            RgbaImages.fromRgba(r.rgba, r.width, r.height)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Build the drag layers for `id`: a background raster with the element hidden plus a
     * foreground raster with only the element (and its ancestor groups) visible. In async mode
     * both renders share ONE background job so they always arrive as a consistent pair.
     */
    private fun requestLayers(id: String) {
        val sc = sidecar
        if (sc != null && sidecarActive) {
            requestLayersSidecar(sc, id)
            return
        }
        if (scheduler == null) {
            rebuildLayersSync(id)
            return
        }
        val tag = LayerTag(engine.svgSource, id, devicePx(engine.layout.width), devicePx(engine.layout.height))
        wantedLayer = tag
        val src = tag.svg
        val rw = tag.w
        val rh = tag.h
        scheduler.submit(
            RenderScheduler.Slot.LAYERS,
            tag,
            {
                val bg = renderer.renderRgba(SvgUtils.hideElement(src, id), rw, rh)
                val fg = renderer.renderRgba(SvgUtils.soloElement(src, id), rw, rh)
                RgbaImages.fromRgba(bg.rgba, bg.width, bg.height) to
                    RgbaImages.fromRgba(fg.rgba, fg.width, fg.height)
            },
        ) { result, t ->
            val tt = t as? LayerTag
            if (result != null && tt != null && tt === wantedLayer && tt.svg === engine.svgSource) {
                layerId = tt.id
                bgImage = result.first
                fgImage = result.second
                buildFgCrop()
                staticDirty = true
                canvas.repaint()
            }
        }
    }

    /**
     * Sidecar drag layers: a single startDrag round-trip returns the element-hidden background
     * and the element-only ghost, pre-rendered by Rust at full device resolution with region-mode
     * parameters (tx=ty=0), so the legacy buildFgCrop / static-layer math applies unchanged.
     */
    private fun requestLayersSidecar(
        sc: SidecarClient,
        id: String,
    ) {
        val el = engine.layout.byId(id) ?: return
        val nodeId = el.nodeId
        val w = engine.layout.width
        val h = engine.layout.height
        if (nodeId == 0L || w <= 0.0 || h <= 0.0) return
        val tag = LayerTag(engine.svgSource, id, devicePx(w), devicePx(h))
        wantedLayer = tag
        val dpr = dpiScale
        val task = {
            try {
                sc.startDrag(nodeId, tag.w, tag.h, viewScale * dpr, 0.0, 0.0)
            } catch (t: Throwable) {
                lastRenderError = t
                null
            }
        }
        val apply: (SidecarDragImages?) -> Unit = { result ->
            if (result != null && wantedLayer === tag && tag.svg === engine.svgSource) {
                layerId = id
                bgImage = result.background
                fgImage = result.ghost
                buildFgCrop()
                staticDirty = true
                canvas.repaint()
            } else if (result == null) {
                lastRenderError?.let { onRenderError?.invoke(it) }
            }
        }
        if (scheduler == null) {
            apply(task())
            return
        }
        scheduler.submit(RenderScheduler.Slot.LAYERS, tag, task) { result, _ -> apply(result) }
    }

    private fun rebuildLayersSync(id: String) {
        val w = engine.layout.width
        val h = engine.layout.height
        if (w <= 0 || h <= 0) return
        val rw = devicePx(w)
        val rh = devicePx(h)
        val bg = renderer.renderRgba(SvgUtils.hideElement(engine.svgSource, id), rw, rh)
        val fg = renderer.renderRgba(SvgUtils.soloElement(engine.svgSource, id), rw, rh)
        layerId = id
        bgImage = RgbaImages.fromRgba(bg.rgba, bg.width, bg.height)
        fgImage = RgbaImages.fromRgba(fg.rgba, fg.width, fg.height)
        buildFgCrop()
        staticDirty = true
    }

    /**
     * Crop [fgImage] down to the selected element's bounding box (plus padding so an arbitrary
     * rotation never clips), so the per-frame foreground blit draws a small image.
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
        val padPx = kotlin.math.max(sw0, sh0)
        val cx0 = (sx0 - padPx).coerceAtLeast(0.0)
        val cy0 = (sy0 - padPx).coerceAtLeast(0.0)
        val right = (sx0 + sw0 + padPx).coerceAtMost(fg.width.toDouble())
        val bottom = (sy0 + sh0 + padPx).coerceAtMost(fg.height.toDouble())
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
        wantedLayer = null
        scheduler?.cancel(RenderScheduler.Slot.LAYERS)
        bgImage = null
        fgImage = null
        fgCrop = null
        layerId = null
    }

    /**
     * Refresh rasters after a committed edit (engine re-parsed the layout, zero raster work).
     *
     * Async mode: render the full scene **immediately and synchronously** into [offscreen] and
     * drop the stale drag layers BEFORE re-queuing async refinements. Otherwise, on release the
     * canvas keeps painting the pre-commit drag-layer composite (old base raster + old crop) and
     * the moved element looks like it snaps back until the async render lands — the "it jumps
     * back on mouse-up" symptom. A single synchronous raster here (small SVG + resvg is
     * thread-safe) closes that window; [requestContent]/[requestLayers] then refine at full
     * device resolution and re-warm the next drag.
     */
    private fun refreshAfterEdit(id: String) {
        if (sidecarActive) {
            // Sidecar path: the committed frame already replaced the content raster inside
            // commitSidecar — just drop the stale drag layers and re-warm them.
            clearLayers()
            staticDirty = true
            requestLayers(id)
            canvas.repaint()
            return
        }
        if (scheduler == null) {
            offscreen = renderNow()
            rebuildLayersSync(id)
        } else {
            offscreen = renderNow()
            clearLayers()
            staticDirty = true
            requestContent()
            requestLayers(id)
            canvas.repaint()
        }
    }

    // ---- internals: view math ----------------------------------------------

    /** Debug-only viewport override for headless tests (null = use the real viewport). */
    private var debugViewport: Dimension? = null

    /**
     * Visible width the fit/zoom math must fill: the scroll pane's viewport, falling back to the
     * canvas size when the hierarchy was never laid out (headless tests). This must NOT be
     * `canvas.width`: the canvas preferred size tracks the zoomed document, so feeding it back
     * into the fit computation makes every recompute multiply the previous result by the zoom
     * factor again (zoom-in explodes, zoom-out collapses to the minimum clamp).
     */
    private fun viewW(): Int =
        debugViewport?.width?.takeIf { it > 0 }
            ?: scrollPane.viewport.width.takeIf { it > 0 }
            ?: canvas.width.takeIf { it > 0 }
            ?: 0

    /** Visible height for the fit/zoom math — see [viewW]. */
    private fun viewH(): Int =
        debugViewport?.height?.takeIf { it > 0 }
            ?: scrollPane.viewport.height.takeIf { it > 0 }
            ?: canvas.height.takeIf { it > 0 }
            ?: 0

    /** Recompute viewScale + offset + canvas preferred size from the current zoom. */
    private fun recomputeView() {
        val w = engine.layout.width
        val h = engine.layout.height
        if (w <= 0 || h <= 0) return
        val cw = viewW()
        val ch = viewH()
        if (cw <= 0 || ch <= 0) return
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

    /** Zoom by `factor`, keeping the SVG point under `(ax, ay)` (panel px) fixed when given. */
    private fun zoomBy(
        factor: Double,
        ax: Double? = null,
        ay: Double? = null,
    ) {
        val w = engine.layout.width
        val h = engine.layout.height
        if (w <= 0 || h <= 0) return
        val cw = viewW().takeIf { it > 0 } ?: 640
        val ch = viewH().takeIf { it > 0 } ?: 420
        val oldView = viewScale
        val oldOffsetX = offsetX
        val oldOffsetY = offsetY
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
        // Instant feedback while zooming: freeze the current sidecar frame and paste it under
        // the new view transform until the crisp re-render lands. A viewport frame moves with
        // the old pan, so it needs the −k·oldOffset correction; a region frame is pasted at the
        // plain new pan. Both keep the SVG point under the cursor visually fixed.
        if (scheduler != null && oldView > 0.0 && viewScale > 0.0 && oldView != viewScale) {
            val src = vpImage ?: offscreen
            if (src != null) {
                val k = viewScale / oldView
                val at = AffineTransform()
                if (src === vpImage && vpViewportMode) {
                    at.translate(offsetX - k * oldOffsetX, offsetY - k * oldOffsetY)
                } else {
                    at.translate(offsetX, offsetY)
                }
                at.scale(k / dpiScale, k / dpiScale)
                vpPreview = VpPreview(src, at)
            }
        }
        // Instant feedback: the existing bitmap is resampled by drawScaled. One crisp
        // re-render follows once the wheel/resize burst settles.
        if (scheduler != null) crispTimer?.restart() else renderAtDeviceSize()
        canvas.repaint()
        emitStatus()
    }

    /** Panel pixels -> SVG/canvas coordinates (DPI-independent). */
    private fun toImage(
        mx: Int,
        my: Int,
    ): Pair<Double, Double> = ((mx - offsetX) / viewScale) to ((my - offsetY) / viewScale)

    // ---- internals: input --------------------------------------------------

    private fun installMouse() {
        canvas.addMouseMotionListener(
            object : MouseMotionAdapter() {
                override fun mouseMoved(e: MouseEvent) {
                    if (spaceDown || panLast != null) return
                    handleHover(e.x, e.y)
                }

                override fun mouseDragged(e: MouseEvent) {
                    when {
                        panLast != null -> panTo(viewportPoint(e))
                        marqueeOrigin != null -> updateMarquee(e.x, e.y)
                        else -> handleDrag(e.x, e.y)
                    }
                }
            },
        )

        canvas.addMouseListener(
            object : MouseAdapter() {
                override fun mousePressed(e: MouseEvent) {
                    canvas.requestFocusInWindow()
                    when {
                        SwingUtilities.isMiddleMouseButton(e) -> {
                            panLast = viewportPoint(e)
                            updateCursor()
                        }
                        spaceDown -> {
                            panLast = viewportPoint(e)
                            updateCursor()
                        }
                        SwingUtilities.isLeftMouseButton(e) -> handlePress(e.x, e.y)
                    }
                }

                override fun mouseReleased(e: MouseEvent) {
                    if (panLast != null) {
                        panLast = null
                        updateCursor()
                    } else if (SwingUtilities.isLeftMouseButton(e)) {
                        handleRelease()
                    }
                }

                override fun mouseClicked(e: MouseEvent) {
                    if (e.clickCount == 2 && SwingUtilities.isLeftMouseButton(e)) handleDoubleClick(e.x, e.y)
                }
            },
        )

        canvas.addMouseWheelListener { e ->
            // Zoom only when Ctrl (⌘ on macOS) is held; otherwise the wheel scrolls the
            // viewport like a normal editor. Unconditional zoom made plain scrolling zoom in
            // unexpectedly.
            if (e.isControlDown || e.isMetaDown) {
                e.consume()
                val f = if (e.wheelRotation < 0) 1.1 else 1.0 / 1.1
                zoomBy(f, e.x.toDouble(), e.y.toDouble())
            } else {
                scrollViewportByWheel(e)
            }
        }
    }

    /** Plain wheel = scroll the preview viewport (Shift = horizontal), mirroring a normal editor. */
    private fun scrollViewportByWheel(e: MouseWheelEvent) {
        val bar =
            if (e.isShiftDown) scrollPane.horizontalScrollBar else scrollPane.verticalScrollBar
        val delta = e.wheelRotation * e.scrollAmount * 3
        bar.value = (bar.value + delta).coerceIn(bar.minimum, bar.maximum - bar.visibleAmount)
    }

    private fun installKeys() {
        canvas.addKeyListener(
            object : KeyAdapter() {
                override fun keyPressed(e: KeyEvent) {
                    if (e.keyCode == KeyEvent.VK_SPACE && !spaceDown) {
                        spaceDown = true
                        updateCursor()
                    }
                }

                override fun keyReleased(e: KeyEvent) {
                    if (e.keyCode == KeyEvent.VK_SPACE) {
                        spaceDown = false
                        updateCursor()
                    }
                }
            },
        )
    }

    private fun viewportPoint(e: MouseEvent): Point = SwingUtilities.convertPoint(e.component, e.point, scrollPane.viewport)

    private fun panTo(p: Point) {
        val last = panLast ?: return
        val vp = scrollPane.viewport
        val pos = vp.viewPosition
        vp.viewPosition =
            Point(
                (pos.x + last.x - p.x).coerceAtLeast(0),
                (pos.y + last.y - p.y).coerceAtLeast(0),
            )
        panLast = p
    }

    private fun updateCursor() {
        canvas.cursor =
            when {
                panLast != null || spaceDown -> Cursor(Cursor.HAND_CURSOR)
                interaction.selectedHandle != null -> when (interaction.selectedHandle) {
                    InteractionController.Handle.NW, InteractionController.Handle.SE -> Cursor(Cursor.NW_RESIZE_CURSOR)
                    InteractionController.Handle.NE, InteractionController.Handle.SW -> Cursor(Cursor.NE_RESIZE_CURSOR)
                    InteractionController.Handle.N, InteractionController.Handle.S -> Cursor(Cursor.N_RESIZE_CURSOR)
                    InteractionController.Handle.E, InteractionController.Handle.W -> Cursor(Cursor.E_RESIZE_CURSOR)
                    else -> Cursor.getDefaultCursor()
                }
                hoveredId != null -> Cursor(Cursor.MOVE_CURSOR)
                else -> Cursor.getDefaultCursor()
            }
    }

    // --- event handlers (also exercised directly by tests via debug* hooks) -------

    private fun handleHover(
        x: Int,
        y: Int,
    ) {
        val (ix, iy) = toImage(x, y)
        interaction.onHoverMove(engine.layout, ix, iy, EditorTheme.HANDLE_TOLERANCE / viewScale)
        val newHover = interaction.hovered?.id
        if (newHover != hoveredId) {
            hoveredId = newHover
            pendingHoverId = newHover
            // Pre-heat the drag layers shortly after hover so the first drag is already warm.
            if (scheduler != null && newHover != null && selectedId == null && newHover != layerId) {
                preheatTimer?.restart()
            }
        }
        updateCursor()
        canvas.repaint()
    }

    private fun handleDrag(
        x: Int,
        y: Int,
    ) {
        val (ix, iy) = toImage(x, y)
        interaction.onDragMove(engine.layout, ix, iy)
        // repaint() (not paintImmediately) lets Swing coalesce drag events into one paint per
        // frame; the moving element comes from the cached fgCrop blit, never from resvg.
        canvas.repaint()
    }

    private fun handlePress(
        x: Int,
        y: Int,
    ) {
        val (ix, iy) = toImage(x, y)
        val tol = EditorTheme.HANDLE_TOLERANCE / viewScale
        // The rotate handle (a circle above the selection box) takes priority.
        selectedId?.let { sid ->
            engine.layout.byId(sid)?.let { el ->
                val hcx = offsetX + (el.x + el.width / 2.0) * viewScale
                val hcy = offsetY + el.y * viewScale - EditorTheme.ROTATE_OFFSET
                if (kotlin.math.hypot(x - hcx, y - hcy) <= (EditorTheme.ROTATE_R + EditorTheme.HANDLE_TOLERANCE)) {
                    val cxSvg = el.x + el.width / 2.0
                    val cySvg = el.y + el.height / 2.0
                    val ang = kotlin.math.atan2(iy - cySvg, ix - cxSvg) * 180.0 / Math.PI
                    interaction.startRotate(cxSvg, cySvg, ang)
                    selectedId = sid
                    if (layerId != sid) requestLayers(sid)
                    canvas.repaint()
                    return
                }
            }
        }
        // Empty canvas away from the current selection's handles: start a marquee (and clear
        // the selection, preserving the click-empty-deselect behavior for <3px drags).
        val hit = hitTestAt(ix, iy)
        if (hit == null && !nearSelectionHandles(x, y)) {
            marqueeOrigin = Point(x, y)
            marqueeRect = null
            interaction.selected = null
            interaction.selectedHandle = null
            selectedId = null
            clearLayers()
            canvas.repaint()
            return
        }
        interaction.onMousePressed(engine.layout, ix, iy, tol)
        val newSel = interaction.selected?.id
        selectedId = newSel
        if (newSel != null) {
            if (layerId != newSel) requestLayers(newSel)
        } else {
            clearLayers()
        }
        staticDirty = true
        canvas.repaint()
    }

    /** True when `(x, y)` in panel px sits on one of the current selection's control points. */
    private fun nearSelectionHandles(
        x: Int,
        y: Int,
    ): Boolean {
        val sid = selectedId ?: return false
        val el = engine.layout.byId(sid) ?: return false
        val box = interaction.previewBox ?: InteractionController.Box(el.x, el.y, el.width, el.height)
        for (h in InteractionController.Handle.entries) {
            val (hx, hy) = interaction.handlePoint(box, h)
            val px = offsetX + hx * viewScale
            val py = offsetY + hy * viewScale
            if (kotlin.math.hypot(x - px, y - py) <= EditorTheme.HANDLE_TOLERANCE) return true
        }
        return false
    }

    private fun updateMarquee(
        x: Int,
        y: Int,
    ) {
        val o = marqueeOrigin ?: return
        marqueeRect =
            Rectangle(
                kotlin.math.min(o.x, x),
                kotlin.math.min(o.y, y),
                kotlin.math.abs(x - o.x),
                kotlin.math.abs(y - o.y),
            )
        canvas.repaint()
    }

    private fun finishMarquee() {
        val r = marqueeRect
        marqueeOrigin = null
        marqueeRect = null
        if (r == null) return
        if (r.width < 3 && r.height < 3) {
            clearLayers()
            canvas.repaint()
            return
        }
        val sx = (r.x - offsetX) / viewScale
        val sy = (r.y - offsetY) / viewScale
        val sw = r.width / viewScale
        val sh = r.height / viewScale
        // lastOrNull = topmost element intersecting the band, matching the z-order feel.
        val top = engine.layout.intersecting(sx, sy, sw, sh).lastOrNull()
        if (top != null) {
            interaction.selected = top
            interaction.selectedHandle = null
            selectedId = top.id
            requestLayers(top.id)
        } else {
            interaction.selected = null
            selectedId = null
            clearLayers()
        }
        canvas.repaint()
        emitStatus()
    }

    private fun handleRelease() {
        if (marqueeOrigin != null || marqueeRect != null) {
            finishMarquee()
            return
        }
        val res = interaction.onMouseReleased()
        var committed = false
        when (res) {
            is InteractionController.EditResult.Move -> {
                committed = commitSidecar(res) ?: engine.moveElement(res.element.id, res.dx, res.dy)
                selectedId = res.element.id
                if (committed) refreshAfterEdit(res.element.id)
            }
            is InteractionController.EditResult.Resize -> {
                committed =
                    commitSidecar(res) ?: engine.setElementBox(res.element.id, res.x, res.y, res.w, res.h)
                selectedId = res.element.id
                if (committed) refreshAfterEdit(res.element.id)
            }
            is InteractionController.EditResult.Rotate -> {
                committed =
                    commitSidecar(res) ?: engine.rotateElement(res.element.id, res.angle, res.cx, res.cy)
                selectedId = res.element.id
                if (committed) refreshAfterEdit(res.element.id)
            }
            null -> {}
        }
        // Fire onEdit only for real commits: a failed edit left the source unchanged, and
        // re-rendering the unchanged source is exactly the drag-then-snap-back symptom.
        if (committed) onEdit?.invoke()
        staticDirty = true
        canvas.repaint()
        emitStatus()
    }

    /**
     * Commit an edit through the sidecar (Rust writes `transform`, returns the round-trip SVG
     * and the re-rendered frame). Returns null when the sidecar path is unavailable (the legacy
     * engine edit applies instead), otherwise whether the edit was committed. On failure the
     * sidecar is permanently retired ([sidecarActive] = false) and the panel falls back — the
     * drag simply "does not stick" instead of corrupting the document.
     */
    private fun commitSidecar(res: InteractionController.EditResult): Boolean? {
        val nodeId =
            when (res) {
                is InteractionController.EditResult.Move -> res.element.nodeId
                is InteractionController.EditResult.Resize -> res.element.nodeId
                is InteractionController.EditResult.Rotate -> res.element.nodeId
            }
        val sc = sidecar
        if (sc == null || !sidecarActive || nodeId == 0L) return null
        val matrix: DoubleArray =
            when (res) {
                is InteractionController.EditResult.Move ->
                    doubleArrayOf(1.0, 0.0, 0.0, 1.0, res.dx, res.dy)
                is InteractionController.EditResult.Resize -> {
                    val el = res.element
                    val sx = if (el.width > 0.0) res.w / el.width else 1.0
                    val sy = if (el.height > 0.0) res.h / el.height else 1.0
                    doubleArrayOf(sx, 0.0, 0.0, sy, res.x - sx * el.x, res.y - sy * el.y)
                }
                is InteractionController.EditResult.Rotate -> {
                    val rad = Math.toRadians(res.angle)
                    val c = kotlin.math.cos(rad)
                    val s = kotlin.math.sin(rad)
                    doubleArrayOf(
                        c,
                        s,
                        -s,
                        c,
                        (1.0 - c) * res.cx + s * res.cy,
                        (1.0 - c) * res.cy - s * res.cx,
                    )
                }
            }
        // A degenerate (identity) matrix would be a no-op commit — skip the round-trip and let
        // the legacy path re-render the unchanged source.
        if (matrix.contentEquals(doubleArrayOf(1.0, 0.0, 0.0, 1.0, 0.0, 0.0))) return false
        return try {
            val view = contentParams()
            val c =
                sc.commit(
                    nodeId,
                    matrix.toList(),
                    view?.vw ?: devicePx(engine.layout.width),
                    view?.vh ?: devicePx(engine.layout.height),
                    view?.scale ?: (viewScale * dpiScale),
                    view?.tx ?: 0.0,
                    view?.ty ?: 0.0,
                )
            // The commit frame's w/h are the render size, not the document size — rebuild the
            // layout around the engine's document dimensions.
            engine.adoptSource(c.svg, SvgLayout(engine.layout.width, engine.layout.height, c.elements))
            if (vpViewportMode) {
                vpImage = c.png
                offscreen = null
            } else {
                offscreen = c.png
                vpImage = null
            }
            vpPreview = null
            staticDirty = true
            true
        } catch (t: Throwable) {
            sidecarActive = false
            null
        }
    }

    private fun handleDoubleClick(
        x: Int,
        y: Int,
    ) {
        val (ix, iy) = toImage(x, y)
        if (interaction.onDoubleClick(engine.layout, ix, iy)) {
            selectedId = interaction.selected?.id
            selectedId?.let { requestLayers(it) }
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

    /** Test hook: pin the viewport size used by the fit/zoom math (headless has no layout). */
    fun debugSetViewportSize(
        w: Int,
        h: Int,
    ) {
        debugViewport = Dimension(w, h)
        recomputeView()
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
        onStatus?.invoke("Zoom: ${getZoomPercent()}% · $sel")
    }

    // ---- rendering --------------------------------------------------------

    private fun renderCanvas(g: Graphics2D) {
        // Layered compositing (baked bg + cropped fg) whenever a selected element has cached
        // layers — both DURING a drag and at rest — so the representation is identical across
        // the drag->release boundary and the element never "pops" between preview and commit.
        val useLayers = layerId != null && bgImage != null && fgImage != null
        if (useLayers) {
            staticDrag = true // the base raster for the layers is the bg layer (element hidden)
            if (staticLayer == null || staticDirty || staticBgColor != background) {
                rebuildStaticLayer()
            }
            staticLayer?.let { g.drawImage(it, 0, 0, null) }
                ?: run { g.color = background; g.fillRect(0, 0, width, height) }
            if (fgCrop != null) drawFg(g)
        } else {
            // Idle path: draw the full render directly. Skipping the intermediate static layer
            // avoids Java2D colour-management conversions that can shift exact pixel values and
            // break the headless pixel-consistency checks.
            paintBackground(g)
            if (gridEnabled) drawGrid(g)
            // Content raster priority: frozen zoom preview > sidecar frame (viewport frames
            // paste at (0,0), region frames go through the legacy placement) > legacy offscreen.
            val pv = vpPreview
            val vp = vpImage
            when {
                pv != null -> drawVpPreview(g, pv)
                vp != null -> if (vpViewportMode) drawScaledAt(g, vp, 0.0, 0.0) else drawScaled(g, vp)
                else -> offscreen?.let { drawScaled(g, it) }
            }
            staticDirty = true // ensure the next drag re-bakes with the current base raster
        }

        drawHover(g)
        drawSelection(g)
        drawSnap(g)
        drawMarquee(g)
    }

    private fun drawScaled(
        g: Graphics2D,
        img: BufferedImage,
    ) {
        drawScaledAt(g, img, offsetX, offsetY)
    }

    /** Paste a device-resolution raster at `(tx, ty)` logical px, scaled back by [dpiScale]. */
    private fun drawScaledAt(
        g: Graphics2D,
        img: BufferedImage,
        tx: Double,
        ty: Double,
    ) {
        // Float placement (no integer truncation) so the committed/offscreen raster is composited
        // at the exact same sub-pixel position as the drag preview — eliminating the last source
        // of a systematic "position different" shift at drag end on fractional-DPI displays.
        val dw = img.width / dpiScale
        val dh = img.height / dpiScale
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
        val at = AffineTransform()
        at.translate(tx, ty)
        at.scale(dw / img.width, dh / img.height)
        g.drawImage(img, at, null)
    }

    /** Paint a frozen zoom preview with its baked-in transform (until the crisp render lands). */
    private fun drawVpPreview(
        g: Graphics2D,
        preview: VpPreview,
    ) {
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
        g.drawImage(preview.img, preview.at, null)
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
        paintBackground(g2)
        if (gridEnabled) drawGrid(g2)
        val base = if (staticDrag && bgImage != null) bgImage else offscreen
        if (base != null) {
            val dw = base.width / dpiScale
            val dh = base.height / dpiScale
            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
            val at = AffineTransform()
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
     * point [AffineTransform] over the pre-cropped [fgCrop] raster. The crop is just the
     * element's bounding box (plus rotation padding), so this blits a small image instead of
     * the whole canvas-sized raster — cheap enough to run every frame at full fps.
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
        val at = AffineTransform()
        at.translate(dx + dw / 2.0, dy + dh / 2.0)
        at.rotate(rad)
        at.scale(s, d)
        at.translate(-(ex + sw0 / 2.0), -(ey + sh0 / 2.0))
        // The committed resvg render is clipped to the SVG viewBox. Without the same clip here,
        // the live preview can show pixels that resvg will later discard — clip the foreground
        // blit to the viewBox bounds so the preview always matches the committed output.
        val oldClip = g.clip
        val vbW = (engine.layout.width * viewScale).toInt().coerceAtLeast(1)
        val vbH = (engine.layout.height * viewScale).toInt().coerceAtLeast(1)
        g.clipRect(offsetX.toInt(), offsetY.toInt(), vbW, vbH)
        g.drawImage(fg, at, null)
        g.clip = oldClip
    }

    /** Fill the panel background; paint the IDEA-style transparency chessboard when enabled. */
    private fun paintBackground(g: Graphics2D) {
        g.color = background
        g.fillRect(0, 0, width, height)
        if (chessboardEnabled) drawChessboard(g)
    }

    /**
     * Paint the IDEA-style transparency checkerboard behind the image. Uses a [TexturePaint]
     * tile (white with two grey squares) anchored at the image origin so the pattern pans with
     * the canvas — exactly how `org.intellij.images.ui.ImageComponentUI` visualises alpha.
     */
    private fun drawChessboard(g: Graphics2D) {
        val w = engine.layout.width
        val h = engine.layout.height
        if (w <= 0 || h <= 0) return
        val cell = CHESS_CELL.toDouble()
        val tile = BufferedImage(2 * CHESS_CELL, 2 * CHESS_CELL, BufferedImage.TYPE_INT_RGB)
        val tg = tile.createGraphics()
        tg.color = CHESS_WHITE
        tg.fillRect(0, 0, tile.width, tile.height)
        tg.color = CHESS_GRAY
        tg.fillRect(cell.toInt(), 0, cell.toInt(), cell.toInt())
        tg.fillRect(0, cell.toInt(), cell.toInt(), cell.toInt())
        tg.dispose()
        val ax = offsetX
        val ay = offsetY
        val texture =
            TexturePaint(
                tile,
                Rectangle2D.Double(ax, ay, tile.width.toDouble(), tile.height.toDouble()),
            )
        val old = g.paint
        g.paint = texture
        g.fillRect(ax.toInt(), ay.toInt(), (w * viewScale).toInt(), (h * viewScale).toInt())
        g.paint = old
    }

    /**
     * IDEA-aligned image-pixel grid: a line every [GRID_SPAN] image (SVG) pixels, drawn only
     * when zoomed in enough that 1 image px >= 1 screen px ([GRID_ZOOM_MIN]). Lines are scoped
     * to the image bounds.
     */
    private fun drawGrid(g: Graphics2D) {
        val w = engine.layout.width
        val h = engine.layout.height
        if (w <= 0 || h <= 0) return
        if (viewScale < GRID_ZOOM_MIN) return
        val step = GRID_SPAN * viewScale
        if (step < 3) return
        val bg = background
        val lum = 0.299 * bg.red + 0.587 * bg.green + 0.114 * bg.blue
        g.color = if (lum > 140) Color(0xE2, 0xE2, 0xE8) else Color(0x2C, 0x2C, 0x34)
        g.stroke = BasicStroke(1f)
        val left = offsetX
        val top = offsetY
        val right = left + w * viewScale
        val bottom = top + h * viewScale
        var i = 0
        var x = left
        while (x <= right) {
            val lx = x.toInt()
            g.drawLine(lx, top.toInt(), lx, bottom.toInt())
            i++
            x = left + i * step
        }
        i = 0
        var y = top
        while (y <= bottom) {
            val ly = y.toInt()
            g.drawLine(left.toInt(), ly, right.toInt(), ly)
            i++
            y = top + i * step
        }
    }

    /** LeaferJS-style hover highlight: a soft rounded accent outline (float precision). */
    private fun drawHover(g: Graphics2D) {
        hoveredId?.takeIf { it != selectedId }?.let { engine.layout.byId(it) }?.let { el ->
            val rx = offsetX + el.x * viewScale
            val ry = offsetY + el.y * viewScale
            val rw = el.width * viewScale
            val rh = el.height * viewScale
            g.color = EditorTheme.ACCENT_HOVER
            g.stroke = BasicStroke(EditorTheme.STROKE)
            g.draw(RoundRectangle2D.Double(rx, ry, rw, rh, 4.0, 4.0))
        }
    }

    /** LeaferJS-style selection: float outline, round dot handles, rotate lever + grip. */
    private fun drawSelection(g: Graphics2D) {
        selectedId?.let { id ->
            engine.layout.byId(id)?.let { el ->
                // While dragging, the box tracks the (snapped) preview box; otherwise it sits
                // on the element's resting geometry. previewAngle rotates the whole overlay.
                val box = interaction.previewBox
                    ?: InteractionController.Box(el.x, el.y, el.width, el.height)
                val rad = Math.toRadians(interaction.previewAngle)
                val bcx = offsetX + (box.x + box.w / 2) * viewScale
                val bcy = offsetY + (box.y + box.h / 2) * viewScale
                val rot: (Double, Double) -> Pair<Double, Double> = { px, py -> rotatePt(px, py, bcx, bcy, rad) }

                // Selection outline as a floating-point rotated path (crisper than int lines).
                val c0 = rot(offsetX + box.x * viewScale, offsetY + box.y * viewScale)
                val c1 = rot(offsetX + (box.x + box.w) * viewScale, offsetY + box.y * viewScale)
                val c2 = rot(offsetX + (box.x + box.w) * viewScale, offsetY + (box.y + box.h) * viewScale)
                val c3 = rot(offsetX + box.x * viewScale, offsetY + (box.y + box.h) * viewScale)
                val outline = Path2D.Double()
                outline.moveTo(c0.first, c0.second)
                outline.lineTo(c1.first, c1.second)
                outline.lineTo(c2.first, c2.second)
                outline.lineTo(c3.first, c3.second)
                outline.closePath()
                g.color = EditorTheme.ACCENT
                g.stroke = BasicStroke(EditorTheme.STROKE)
                g.draw(outline)

                // 8 round control points (white fill, accent outline).
                val r = EditorTheme.HANDLE / 2.0
                for (h in InteractionController.Handle.entries) {
                    val (hx, hy) = interaction.handlePoint(box, h)
                    val (px, py) = rot(offsetX + hx * viewScale, offsetY + hy * viewScale)
                    val dot =
                        Ellipse2D.Double(px - r, py - r, EditorTheme.HANDLE.toDouble(), EditorTheme.HANDLE.toDouble())
                    g.color = EditorTheme.HANDLE_FILL
                    g.fill(dot)
                    g.color = EditorTheme.ACCENT
                    g.draw(dot)
                }

                // Rotate handle: a lever from the top-centre of the box up to a circular grip.
                val topCx = offsetX + (box.x + box.w / 2) * viewScale
                val topCy = offsetY + box.y * viewScale
                val (lx, ly) = rot(topCx, topCy)
                val (hx2, hy2) = rot(topCx, topCy - EditorTheme.ROTATE_OFFSET)
                g.color = EditorTheme.ACCENT
                g.draw(Line2D.Double(lx, ly, hx2, hy2))
                val grip =
                    Ellipse2D.Double(
                        hx2 - EditorTheme.ROTATE_R,
                        hy2 - EditorTheme.ROTATE_R,
                        EditorTheme.ROTATE_R * 2.0,
                        EditorTheme.ROTATE_R * 2.0,
                    )
                g.color = EditorTheme.HANDLE_FILL
                g.fill(grip)
                g.color = EditorTheme.ACCENT
                g.draw(grip)
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
        g.color = EditorTheme.SNAP
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

    /** Rubber-band rectangle while marquee-selecting (dashed accent, translucent fill). */
    private fun drawMarquee(g: Graphics2D) {
        val r = marqueeRect ?: return
        g.color = EditorTheme.MARQUEE_FILL
        g.fill(r)
        g.color = EditorTheme.ACCENT
        g.stroke = EditorTheme.marqueeStroke()
        g.draw(r)
    }
}
