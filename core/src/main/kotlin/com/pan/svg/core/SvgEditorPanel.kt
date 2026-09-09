package com.pan.svg.core

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
 *     so the first drag frame is already warm — press-and-drag never blocks. Hover draws
 *     nothing (only the cursor changes); the selection frame appears after a click.
 *
 * Editing commits ([InteractionController] preview -> [SvgEditorEngine] edit) re-parse the
 * LAYOUT only; the raster refresh is scheduled through the same background pipeline.
 *
 * Interaction model (two strictly separated tools, default = MOVE):
 *  - click to select, LeaferJS-style accent frame with round handles + rotate grip
 *  - drag body = move, drag handle = resize, drag rotate grip = rotate (with snapping);
 *    click empty space in MOVE mode deselects (no implicit rubber band)
 *  - MARQUEE tool: press anywhere → rubber band; a drag box-selects every element the band
 *    intersects (moved as a group, topmost is the primary), a click without a drag selects the
 *    exact element under the pointer
 *  - hit tests are path-exact via the sidecar (equivalent to LeaferJS offscreen colour
 *    picking): a pointer only targets an element whose filled/stroked geometry it touches
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
    /** Active interaction tool: MOVE = direct manipulation, MARQUEE = rubber-band box select. */
    enum class Tool { MOVE, MARQUEE }

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

    /**
     * Colour-ID hit canvas (legacy pipeline only, when [pickRenderer] is available): the same
     * document rasterized with every paint leaf drawn flat in a colour encoding its position in
     * the layout list. Path-exact hover/click/box-select without the sidecar.
     */
    private val pickRenderer: SvgPickRenderer? = renderer as? SvgPickRenderer
    private var pickImage: BufferedImage? = null

    /** Drag layers: background (element hidden) + foreground (element solo), device-sized. */
    private var bgImage: BufferedImage? = null
    private var fgImage: BufferedImage? = null

    /** Foreground cropped to the element's bounding box (+rotation padding) for a cheap blit. */
    private var fgCrop: BufferedImage? = null
    private var fgCropX = 0.0
    private var fgCropY = 0.0
    private var layerId: String? = null

    /**
     * View parameters (scale × device-pixel ratio) the cached [bgImage]/[fgImage]/[fgCrop] were
     * rendered for; -1 when no layers are cached. The drag layers are produced with the CURRENT
     * [viewScale] baked in (region-mode renders), so painting them after a zoom would paste the
     * element at the old geometry — exactly the "shape jumps a little when the composite kicks
     * in" flicker. -1 = not the current view (forces a refresh before the layers may be used).
     */
    private var layerViewScale = -1.0
    private var layerDpi = -1.0

    /** True when the cached drag layers were rendered for the current view scale × dpr. */
    private fun layersCurrent(): Boolean = layerViewScale == viewScale && layerDpi == dpiScale

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

    /**
     * Ordered set of currently selected element ids (insertion order = selection order; the
     * last added is the primary, mirrored by [selectedId]). Size 1 is a plain single selection;
     * >1 is a multi-selection that moves as a group.
     */
    private val selectedIds = LinkedHashSet<String>()

    /** Ids captured by Ctrl+C, pasted by [pasteClipboard]. */
    private val clipboard = mutableListOf<String>()

    /** True while a multi-element selection is being dragged as one group in MOVE tool. */
    private var groupDrag = false

    /** Transparency chessboard (IDEA-style) drawn behind the image. On by default. */
    private var chessboardEnabled = true

    /** Image-pixel grid, shown only at >=100%. Off by default. */
    private var gridEnabled = false

    /** Marquee (rubber-band) selection state, in panel pixels. */
    private var marqueeOrigin: Point? = null
    private var marqueeRect: Rectangle? = null

    /** Active tool. Defaults to [Tool.MOVE]; switch via [setTool]. */
    private var tool = Tool.MOVE

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
        val viewScale: Double,
        val dpr: Double,
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
        private const val MIN_GRID_PX = 3 // smallest on-screen spacing before the pitch doubles
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
        // The canvas is pinned to the viewport (its preferred size == the viewport), so the doc
        // never outgrows the pane — disable scrollbars entirely. Zooming frames the fixed
        // viewport; panning is done by moving the draw origin (see [panTo]).
        scrollPane.verticalScrollBarPolicy = JScrollPane.VERTICAL_SCROLLBAR_NEVER
        scrollPane.horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
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
        clearSelection()
        hoveredId = null
        pendingHoverId = null
        interaction.selected = null
        interaction.previewBox = null
        interaction.selectedHandle = null
        interaction.previewAngle = 0.0
        clearLayers()
        pickImage = null
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

    /** Ids of all currently selected elements, in selection order. */
    val selectedElementIds: List<String> get() = selectedIds.toList()

    // ---- structural editing API (keyboard-driven, also callable from host menus) ----

    /** Alignment axis/mode for [alignSelection]. */
    enum class Align { LEFT, CENTER_H, RIGHT, TOP, MIDDLE, BOTTOM }

    /** Distribution axis for [distributeSelection]. */
    enum class Distribute { HORIZONTAL, VERTICAL }

    /** Layer-reorder direction, mapping to [SvgUtils.ReorderDir]. */
    enum class Reorder { FRONT, FORWARD, BACKWARD, BACK }

    /** Select every element that has an id (removes the previous selection). */
    fun selectAll() {
        val ids = engine.layout.elements.filter { it.id.isNotBlank() }.map { it.id }
        if (ids.isEmpty()) return
        setSelection(ids, ids.last())
        emitStatus()
    }

    /** Delete all selected elements from the document. */
    fun deleteSelected() {
        if (selectedIds.isEmpty()) return
        val sc = sidecar
        val viaSidecar = sc != null && sidecarActive
        var changed = false
        // Sidecar leaves carry a blank source id, so they can only be deleted through the native
        // document by node id (the legacy source-rewriting engine edit is a no-op for them).
        for (id in selectedIds.toList()) {
            val el = engine.layout.byId(id) ?: continue
            if (viaSidecar && el.nodeId != 0L) {
                if (removeSidecar(el.nodeId)) changed = true
            } else {
                changed = engine.deleteElement(id) || changed
            }
        }
        if (changed) {
            clearSelection()
            onEdit?.invoke()
        }
        refreshStructural(null)
    }

    /**
     * Delete the element with `nodeId` through the sidecar (Rust removes the subtree and returns
     * the round-trip SVG). Adopts the result into the engine on success; on failure the sidecar
     * is retired and the delete is dropped (mirrors [commitSidecar]'s safety behaviour).
     */
    private fun removeSidecar(nodeId: Long): Boolean {
        val sc = sidecar
        if (sc == null || !sidecarActive) return false
        return try {
            val view = contentParams()
            val c =
                sc.remove(
                    nodeId,
                    view?.vw ?: devicePx(engine.layout.width),
                    view?.vh ?: devicePx(engine.layout.height),
                    view?.scale ?: (viewScale * dpiScale),
                    view?.tx ?: 0.0,
                    view?.ty ?: 0.0,
                )
            engine.adoptSource(c.svg, SvgLayout(engine.layout.width, engine.layout.height, c.elements))
            true
        } catch (t: Throwable) {
            sidecarActive = false
            false
        }
    }

    /** Duplicate (paste) the given element ids at a small offset; new copies become the selection. */
    fun duplicateIds(ids: Collection<String>) {
        if (ids.isEmpty()) return
        val newIds = mutableListOf<String>()
        var n = 1
        for (id in ids.distinct()) {
            val newId = engine.duplicateElement(id, 10.0 * n, 10.0 * n)
            if (newId != null) newIds.add(newId)
            n++
        }
        if (newIds.isEmpty()) return
        onEdit?.invoke()
        setSelection(newIds, newIds.last())
        refreshStructural(selectedId)
    }

    /** Pause the current id-based copy buffer (Ctrl+C). */
    fun copySelection() {
        clipboard.clear()
        clipboard.addAll(selectedIds)
    }

    /** Paste the last [copySelection] buffer at an offset (Ctrl+V). */
    fun pasteClipboard() = duplicateIds(clipboard)

    /** Nudge the selection by `(dx, dy)` canvas units (arrow keys). */
    fun nudgeSelection(dx: Double, dy: Double) {
        if (selectedIds.isEmpty()) return
        var changed = false
        for (id in selectedIds.toList()) changed = engine.moveElement(id, dx, dy) || changed
        if (changed) onEdit?.invoke()
        if (changed) refreshStructural(selectedId)
        emitStatus()
    }

    /** Move the selected elements onto the given alignment edge/axis of the selection bounds. */
    fun alignSelection(a: Align) {
        val els = selectedIds.mapNotNull { engine.layout.byId(it) }
        if (els.size < 2) return
        val minX = els.minOf { it.x }
        val maxRight = els.maxOf { it.right }
        val minY = els.minOf { it.y }
        val maxBottom = els.maxOf { it.bottom }
        val midX = minX + (maxRight - minX) / 2.0
        val midY = minY + (maxBottom - minY) / 2.0
        var changed = false
        for (el in els) {
            val tx =
                when (a) {
                    Align.LEFT -> minX
                    Align.CENTER_H -> midX - el.width / 2.0
                    Align.RIGHT -> maxRight - el.width
                    else -> el.x
                }
            val ty =
                when (a) {
                    Align.TOP -> minY
                    Align.MIDDLE -> midY - el.height / 2.0
                    Align.BOTTOM -> maxBottom - el.height
                    else -> el.y
                }
            val dx = tx - el.x
            val dy = ty - el.y
            if (dx != 0.0 || dy != 0.0) changed = engine.moveElement(el.id, dx, dy) || changed
        }
        if (changed) {
            onEdit?.invoke()
            refreshStructural(selectedId)
        }
        emitStatus()
    }

    /**
     * Distribute the selected elements with even gaps along an axis (the two outermost elements
     * stay fixed; the middle ones are spaced so the gap between consecutive edges is uniform).
     */
    fun distributeSelection(d: Distribute) {
        val els = selectedIds.mapNotNull { engine.layout.byId(it) }
        if (els.size < 3) return
        val horizontal = d == Distribute.HORIZONTAL
        val sorted = if (horizontal) els.sortedBy { it.x } else els.sortedBy { it.y }
        val first = sorted.first()
        val last = sorted.last()
        val span = if (horizontal) (last.right - first.x) else (last.bottom - first.y)
        val total = if (horizontal) sorted.sumOf { it.width } else sorted.sumOf { it.height }
        val gap = (span - total) / (sorted.size - 1)
        var changed = false
        var cursor = if (horizontal) first.x + first.width + gap else first.y + first.height + gap
        for (el in sorted.subList(1, sorted.size - 1)) {
            val tx = if (horizontal) cursor else el.x
            val ty = if (horizontal) el.y else cursor
            val dx = tx - el.x
            val dy = ty - el.y
            if (dx != 0.0 || dy != 0.0) changed = engine.moveElement(el.id, dx, dy) || changed
            cursor += (if (horizontal) el.width else el.height) + gap
        }
        if (changed) {
            onEdit?.invoke()
            refreshStructural(selectedId)
        }
        emitStatus()
    }

    /** Reorder the selected elements in the layer stack (source document order). */
    fun reorderSelection(r: Reorder) {
        if (selectedIds.isEmpty()) return
        val dir =
            when (r) {
                Reorder.FRONT -> SvgUtils.ReorderDir.FRONT
                Reorder.FORWARD -> SvgUtils.ReorderDir.FORWARD
                Reorder.BACKWARD -> SvgUtils.ReorderDir.BACKWARD
                Reorder.BACK -> SvgUtils.ReorderDir.BACK
            }
        var changed = false
        for (id in selectedIds.toList()) changed = engine.reorderElement(id, dir) || changed
        if (changed) {
            onEdit?.invoke()
            refreshStructural(selectedId)
        }
        emitStatus()
    }

    // ---- selection helpers -------------------------------------------------

    /** Whether [id] is part of the current selection. */
    private fun isSelected(id: String): Boolean = selectedId == id || id in selectedIds

    /**
     * Replace the whole selection with `ids` and make `primary` the active element. `ids` must
     * contain `primary`. This is the single entry point for changing the selected set so the
     * panel renders and layers stay consistent. Returns the primary id (or null if empty).
     */
    private fun setSelection(
        ids: Collection<String>,
        primary: String,
    ): String? {
        selectedIds.clear()
        for (id in ids.distinct()) {
            if (engine.layout.byId(id) == null) continue // drop stale/unknown ids
            selectedIds.add(id)
        }
        if (selectedIds.isEmpty()) {
            selectedId = null
            groupDrag = false
            clearLayers()
            canvas.repaint()
            return null
        }
        // Primary must be in the set (fall back to the topmost if it was dropped as unknown).
        val p = if (primary in selectedIds) primary else selectedIds.last()
        selectedId = p
        groupDrag = false
        if (layerId != p) requestLayers(p)
        canvas.repaint()
        return p
    }

    /** Collapse the selection to the single element `id` (and make it primary). */
    private fun selectOnly(id: String): String? = setSelection(listOf(id), id)

    /** Clear the selection entirely. */
    private fun clearSelection() {
        selectedIds.clear()
        selectedId = null
        groupDrag = false
        interaction.selected = null
        interaction.selectedHandle = null
        clearLayers()
    }

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

    /** Current interaction tool. */
    fun getTool(): Tool = tool

    /**
     * Switch the active interaction tool.
     *
     *  - [Tool.MOVE]: press/drag an element to select + move it, drag its handles to resize /
     *    rotate, click empty space to deselect. This is the default.
     *  - [Tool.MARQUEE]: pressing ANYWHERE (on or off an element) starts a rubber-band box;
     *    a drag box-selects every element the band intersects (group drag), a plain click
     *    without dragging selects the exact element under the pointer. Element dragging via the
     *    rubber band is disabled; selected elements move as a group under the MOVE tool.
     */
    fun setTool(t: Tool) {
        if (tool == t) return
        tool = t
        // A switch never leaves a half-finished marquee or hover state behind.
        marqueeOrigin = null
        marqueeRect = null
        if (t == Tool.MARQUEE) {
            interaction.selectedHandle = null
        }
        updateCursor()
        canvas.repaint()
    }

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
     * normalized to node-id strings so selection keys stay unique), else the local colour-ID
     * hit canvas when the renderer provides one, else the bounding-box detector.
     */
    private fun hitTestAt(ix: Double, iy: Double): SvgElement? {
        val sc = sidecar
        if (sc != null && sidecarActive) {
            val tol = EditorTheme.HANDLE_TOLERANCE / viewScale
            val nodeId = sc.hitTest(ix, iy, tol) ?: return null
            val el = engine.layout.byNodeId(nodeId) ?: return null
            return el.copy(id = el.id.ifBlank { nodeId.toString() })
        }
        val img = pickImage
        if (img != null && vpPreview == null) {
            val lw = engine.layout.width
            val lh = engine.layout.height
            if (lw > 0.0 && lh > 0.0) {
                val dx = (ix * img.width / lw).toInt()
                val dy = (iy * img.height / lh).toInt()
                if (dx in 0 until img.width && dy in 0 until img.height) {
                    val value = pickPixelValue(img, dx, dy)
                    // The canvas is authoritative: a transparent pixel means no element paints
                    // there (a concave shape's hole / a gap), regardless of any bounding boxes.
                    if (value == 0) return null
                    return elementForPickValue(value)
                }
            }
        }
        return CollisionDetector.hitTest(engine.layout, ix, iy)
    }

    /** Read a colour ordinal out of an `ARGB_PRE` pick pixel, un-premultiplying the alpha edge. */
    private fun pickPixelValue(
        img: BufferedImage,
        dx: Int,
        dy: Int,
    ): Int {
        val v = img.getRGB(dx, dy)
        val a = (v ushr 24) and 0xFF
        if (a < 40) return 0
        val r = (v ushr 16) and 0xFF
        val g = (v ushr 8) and 0xFF
        val b = v and 0xFF
        if (a >= 255) return (r shl 16) or (g shl 8) or b
        return ((r * 255 / a) shl 16) or ((g * 255 / a) shl 8) or (b * 255 / a)
    }

    /**
     * Map a pick ordinal (1-based) to the matching layout element: the `value`-th element whose
     * `kind != "group"` (groups never paint and are transparent to pointer events).
     */
    private fun elementForPickValue(value: Int): SvgElement? {
        if (value <= 0) return null
        var leaf = 0
        for (el in engine.layout.elements) {
            if (el.kind == "group") continue
            leaf++
            if (leaf == value) return el
        }
        return null
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
        // Cached drag layers embed the PREVIOUS view scale (region renders bake `scale` into
        // their pixels). After a zoom they are stale: re-requested below, but never painted in
        // the gap — drop them so the composite can't flash an old-size element (the selection
        // "jump"). The fresh pair lands via requestLayers' staleness-guarded callback.
        if (layerId != null && !layersCurrent()) clearLayers()
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
            refreshPickNow()
            return
        }
        val tag = RenderTag(engine.svgSource, devicePx(engine.layout.width), devicePx(engine.layout.height))
        val src = tag.svg
        val rw = tag.w
        val rh = tag.h
        val pr = pickRenderer
        scheduler.submit(
            RenderScheduler.Slot.CONTENT,
            tag,
            {
                // Render content + colour-ID hit canvas in ONE background job so they always
                // arrive as a consistent pair at the same device resolution.
                val content =
                    try {
                        renderer.renderRgba(src, rw, rh)
                    } catch (t: Throwable) {
                        lastRenderError = t
                        null
                    }
                val cimg =
                    if (content == null) {
                        null
                    } else {
                        RgbaImages.fromRgba(content.rgba, content.width, content.height)
                    }
                cimg to pickOf(pr, src, rw, rh)
            },
        ) { result, t ->
            val tt = t as? RenderTag
            if (
                tt != null &&
                tt.svg === engine.svgSource &&
                tt.w == devicePx(engine.layout.width) &&
                tt.h == devicePx(engine.layout.height)
            ) {
                val cimg = result?.first
                if (cimg != null) {
                    offscreen = cimg
                    pickImage = result?.second
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

    /** Render a colour-ID canvas via [SvgPickRenderer], or null when unavailable/failed. */
    private fun pickOf(
        pr: SvgPickRenderer?,
        src: String,
        rw: Int,
        rh: Int,
    ): BufferedImage? {
        if (pr == null) return null
        return try {
            val r = pr.renderPickRgba(src, rw, rh) ?: return null
            RgbaImages.fromRgba(r.rgba, r.width, r.height)
        } catch (_: Throwable) {
            null
        }
    }

    /** Rebuild the hit canvas synchronously at the current device size (legacy pipeline). */
    private fun refreshPickNow() {
        val w = engine.layout.width
        val h = engine.layout.height
        val pr = pickRenderer
        if (pr == null || w <= 0 || h <= 0) {
            pickImage = null
            return
        }
        pickImage = pickOf(pr, engine.svgSource, devicePx(w), devicePx(h))
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
        val tag = LayerTag(engine.svgSource, id, devicePx(engine.layout.width), devicePx(engine.layout.height), viewScale, dpiScale)
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
            if (
                result != null &&
                tt != null &&
                tt === wantedLayer &&
                tt.svg === engine.svgSource &&
                tt.viewScale == viewScale &&
                tt.dpr == dpiScale
            ) {
                layerId = tt.id
                layerViewScale = tt.viewScale
                layerDpi = tt.dpr
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
        val tag = LayerTag(engine.svgSource, id, devicePx(w), devicePx(h), viewScale, dpiScale)
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
            if (
                result != null &&
                wantedLayer === tag &&
                tag.svg === engine.svgSource &&
                tag.viewScale == viewScale &&
                tag.dpr == dpiScale
            ) {
                layerId = id
                layerViewScale = tag.viewScale
                layerDpi = tag.dpr
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
        layerViewScale = viewScale
        layerDpi = dpiScale
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
        layerViewScale = -1.0
        layerDpi = -1.0
    }

    /**
     * Refresh the base raster + colour-id pick and re-warm drag layers after a structural edit
     * (delete / duplicate / reorder / align / nudge) that changed the source out-of-band. Unlike
     * [refreshAfterEdit], `primary` may be null (a deleted selection) — in that case the layers
     * are cleared and only the content raster is re-produced.
     */
    private fun refreshStructural(primary: String?) {
        clearLayers()
        staticDirty = true
        requestContent()
        if (primary != null) {
            if (scheduler == null) rebuildLayersSync(primary) else requestLayers(primary)
        }
        canvas.repaint()
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
            refreshPickNow()
            rebuildLayersSync(id)
        } else {
            offscreen = renderNow()
            refreshPickNow()
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

    /** Recompute viewScale + offset + canvas size from the current zoom and reset any pan.
     *
     * The canvas is always exactly the size of the (scroll-pane) viewport, so no scrollbars ever
     * appear: the document is centred inside the fixed viewport and, when zoomed larger than it,
     * is simply clipped. Panning moves [offsetX]/[offsetY] directly (see [panTo]). */
    private fun recomputeView() {
        val w = engine.layout.width
        val h = engine.layout.height
        if (w <= 0 || h <= 0) return
        val cw = viewW()
        val ch = viewH()
        if (cw <= 0 || ch <= 0) return
        val fit = ((cw - 2 * pad) / w).coerceAtMost((ch - 2 * pad) / h).coerceAtLeast(0.01)
        viewScale = fit * zoom
        // Fit-to-view is the resting state: centre the document and drop any prior pan.
        offsetX = (cw - w * viewScale) / 2.0
        offsetY = (ch - h * viewScale) / 2.0
        canvas.preferredSize = Dimension(cw.coerceAtLeast(1), ch.coerceAtLeast(1))
        canvas.revalidate()
    }

    /** Keep [offsetX]/[offsetY] within the fixed viewport: when the zoomed document fits the
     * viewport it stays centred; when it overflows, clamp so the content always covers the
     * visible area (and the user can pan across it) instead of drifting off-screen. */
    private fun clampOffset() {
        val w = engine.layout.width
        val h = engine.layout.height
        if (w <= 0 || h <= 0) return
        val cw = viewW()
        val ch = viewH()
        if (cw <= 0 || ch <= 0) return
        val dw = w * viewScale
        val dh = h * viewScale
        offsetX = if (dw <= cw) (cw - dw) / 2.0 else offsetX.coerceIn(cw - dw, 0.0)
        offsetY = if (dh <= ch) (ch - dh) / 2.0 else offsetY.coerceIn(ch - dh, 0.0)
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
        clampOffset()
        // The canvas is pinned to the fixed viewport (no scrollbars), so its pref size never
        // needs to grow with zoom — only the visible framing changes.
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

    /** Plain wheel = pan the document (Shift = horizontal); no scrollbars exist in this view. */
    private fun scrollViewportByWheel(e: MouseWheelEvent) {
        val delta = e.wheelRotation * e.scrollAmount * 3
        if (e.isShiftDown) offsetX -= delta else offsetY -= delta
        clampOffset()
        canvas.repaint()
    }

    private fun installKeys() {
        canvas.addKeyListener(
            object : KeyAdapter() {
                override fun keyPressed(e: KeyEvent) {
                    onKeyPressed(e)
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

    /**
     * Handle a keyboard command on the canvas. This is the single entry point for editing keys
     * (delete/copy/paste/select-all/arrows). The canvas key listener forwards here, and hosts /
     * tests can also dispatch into it directly — headless AWT drops synthetic `KeyEvent`s sent via
     * `Component.dispatchEvent` (unlike mouse events), so testing through this method exercises the
     * exact same match on the key mapping without needing focus.
     *
     * @return true when the key was an editing command (consumed).
     */
    fun onKeyPressed(e: KeyEvent): Boolean {
        // End any in-flight mouse edit before a structural keyboard command so the
        // move/resize/rotate result is committed first.
        if (interaction.state != InteractionController.State.IDLE) return false
        return when {
            e.keyCode == KeyEvent.VK_SPACE && !spaceDown -> {
                spaceDown = true
                updateCursor()
                false
            }

            e.keyCode == KeyEvent.VK_ESCAPE -> {
                clearSelection()
                canvas.repaint()
                emitStatus()
                true
            }

            // Delete / Backspace: remove the selection from the document.
            e.keyCode == KeyEvent.VK_DELETE || e.keyCode == KeyEvent.VK_BACK_SPACE -> {
                deleteSelected()
                e.consume()
                true
            }

            e.isControlDown && e.keyCode == KeyEvent.VK_C -> {
                copySelection()
                e.consume()
                true
            }

            e.isControlDown && e.keyCode == KeyEvent.VK_V -> {
                pasteClipboard()
                e.consume()
                true
            }

            e.isControlDown && e.keyCode == KeyEvent.VK_A -> {
                selectAll()
                e.consume()
                true
            }

            // Arrow keys: nudge by 1 canvas unit, 10 with Shift held.
            e.keyCode == KeyEvent.VK_UP -> {
                nudgeSelection(0.0, if (e.isShiftDown) -10.0 else -1.0)
                true
            }
            e.keyCode == KeyEvent.VK_DOWN -> {
                nudgeSelection(0.0, if (e.isShiftDown) 10.0 else 1.0)
                true
            }
            e.keyCode == KeyEvent.VK_LEFT -> {
                nudgeSelection(if (e.isShiftDown) -10.0 else -1.0, 0.0)
                true
            }
            e.keyCode == KeyEvent.VK_RIGHT -> {
                nudgeSelection(if (e.isShiftDown) 10.0 else 1.0, 0.0)
                true
            }

            else -> false
        }
    }

    private fun viewportPoint(e: MouseEvent): Point = SwingUtilities.convertPoint(e.component, e.point, scrollPane.viewport)

    private fun panTo(p: Point) {
        val last = panLast ?: return
        // Pan by moving the draw origin directly. The canvas is pinned to the viewport, so the
        // doc no longer grows past it (no scrollbars); [clampOffset] keeps the content inside.
        offsetX += last.x - p.x
        offsetY += last.y - p.y
        clampOffset()
        panLast = p
        canvas.repaint()
        emitStatus()
    }

    private fun updateCursor() {
        canvas.cursor =
            when {
                tool == Tool.MARQUEE -> Cursor(Cursor.CROSSHAIR_CURSOR)
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
            // Only the MOVE tool ever drags; the MARQUEE tool must not spend raster budget (or
            // risk the composite flash) on mere hover.
            if (scheduler != null && tool == Tool.MOVE && newHover != null && selectedId == null && newHover != layerId) {
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
        // MARQUEE tool: pressing ANYWHERE — on an element or on empty canvas — begins a rubber
        // band. Move/resize/rotate are disabled in this mode; what gets selected is resolved on
        // release (finishMarquee): a drag selects the topmost element inside the band, a click
        // without a drag selects the exact element under the pointer.
        if (tool == Tool.MARQUEE) {
            marqueeOrigin = Point(x, y)
            marqueeRect = null
            interaction.selectedHandle = null
            canvas.repaint()
            return
        }

        val (ix, iy) = toImage(x, y)
        val tol = EditorTheme.HANDLE_TOLERANCE / viewScale
        // Re-bind the controller's selection to the CURRENT layout before any geometry
        // decision. After a committed edit the layout moved, but interaction.selected would
        // otherwise still hold the pre-edit snapshot: pressing inside the overlap of old & new
        // boxes re-uses the stale origin and the second drag starts with a visible jump.
        // Sidecar leaves carry a blank source id (selection keys are nodeId strings), so keep
        // the controller's element id when re-binding — a blank id would fail selectOnly and
        // the press would silently deselect instead of grabbing the element.
        interaction.selected?.let { sel ->
            val fresh = engine.layout.byNodeId(sel.nodeId) ?: engine.layout.byId(sel.id)
            fresh?.let { f ->
                interaction.selected = if (f.id.isBlank()) f.copy(id = sel.id) else f
            }
        }
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
        // MOVE tool on empty canvas away from the selection's handles: deselect. The rubber
        // band is NOT auto-started here — the two tools are strictly separated, so a drag on
        // empty space in MOVE mode is a plain click-to-deselect (the first click of a "box
        // select" only exists under the MARQUEE tool).
        val hit = hitTestAt(ix, iy)
        // A path-exact hit test misses the element's own hollow/concave regions (a ring's
        // centre hole, a gear tooth gap, the transparent box of a stroke-only icon). Pressing
        // inside the SELECTED element's bounding box must still grab it — otherwise a user who
        // double-clicked a ring then presses its (empty) centre to drag loses the selection and
        // nothing moves. Match that by treating a miss inside the current selection's box as a
        // press on the selection itself.
        val selectedBoxHit =
            hit == null &&
                selectedId?.let { sid -> engine.layout.byId(sid)?.contains(ix, iy) } == true
        if (hit == null && !selectedBoxHit && !nearSelectionHandles(x, y)) {
            clearSelection()
            canvas.repaint()
            return
        }
        if (hit == null && selectedBoxHit) {
            // Re-target the controller at the currently selected element so onMousePressed
            // enters MOVE on it (its box contains the pointer) instead of falling through to
            // deselect. The selection itself is unchanged. Keep the selected key as the element
            // id (sidecar leaves have a blank source id).
            selectedId?.let { sid ->
                engine.layout.byId(sid)?.let { fresh ->
                    interaction.selected = if (fresh.id.isBlank()) fresh.copy(id = sid) else fresh
                }
            }
        }
        interaction.onMousePressed(engine.layout, ix, iy, tol)
        val newSel = interaction.selected?.id
        if (newSel != null && newSel in selectedIds && selectedIds.size > 1) {
            // Pressed on an element of an existing multi-selection (primary or not): keep the
            // whole set and make this element the moving primary — a group drag moves them all
            // together. A single selection falls through to the normal (idempotent) path.
            selectedId = newSel
            groupDrag = true
            if (layerId != newSel) requestLayers(newSel)
            staticDirty = true
            canvas.repaint()
            return
        }
        if (newSel != null) {
            // A fresh single selection collapses the set (or, if the currently-selected single
            // element was re-pressed, selectOnly just keeps it as-is).
            selectOnly(newSel)
        } else {
            clearSelection()
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
        val o = marqueeOrigin ?: return
        val r = marqueeRect
        marqueeOrigin = null
        marqueeRect = null
        if (r == null || (r.width < 3 && r.height < 3)) {
            // Click without a drag: select the exact element under the pointer (or deselect).
            val (ix, iy) = toImage(o.x, o.y)
            val hit = hitTestAt(ix, iy)
            if (hit != null) {
                interaction.selected = hit
                selectOnly(hit.id)
            } else {
                clearSelection()
            }
            canvas.repaint()
            emitStatus()
            return
        }
        val sx = (r.x - offsetX) / viewScale
        val sy = (r.y - offsetY) / viewScale
        val sw = r.width / viewScale
        val sh = r.height / viewScale
        // Box-select: every element whose bounding box INTERSECTS the band becomes selected and
        // moves as a group. (Intersection — not "fully contained" — is what makes a band over a
        // large compound icon like a gear actually select it: its outer silhouette bbox usually
        // sticks out past the drag rectangle on every side, so "contained" would only ever pick
        // small interior leaves, which reads as "box select does nothing".) Transparent usvg
        // group wrappers are excluded by the blank-id filter, and a rect that backdrops the
        // ENTIRE document (a common `<rect>` page background) never joins a marquee — dragging a
        // box over content must not silently start moving the page background with it.
        val w = engine.layout.width
        val h = engine.layout.height
        // Sidecar leaves keep their selection key as the node-id string (their source id is
        // blank), so blank ids are selectable whenever a non-zero node id exists — same key the
        // exact hit tests use. Transparent usvg group wrappers (blank id AND no node id) stay out.
        fun selectable(el: SvgElement): Boolean =
            el.id.isNotBlank() || el.nodeId != 0L
        fun keyOf(el: SvgElement): String = el.id.ifBlank { el.nodeId.toString() }
        val hit =
            engine.layout.elements.filter { el ->
                selectable(el) &&
                    el.x < sx + sw &&
                    el.right > sx &&
                    el.y < sy + sh &&
                    el.bottom > sy &&
                    !(el.x <= 0.0 && el.y <= 0.0 && el.right >= w - 0.5 && el.bottom >= h - 0.5)
            }
        val primary = hit.maxByOrNull { it.index }
        if (primary != null) {
            val primaryKey = keyOf(primary)
            interaction.selected = keyedElement(engine.layout.byId(primaryKey) ?: primary)
            setSelection(hit.map { keyOf(it) }, primaryKey)
        } else {
            clearSelection()
        }
        canvas.repaint()
        emitStatus()
    }

    /**
     * Sidecar leaves keep their selection key in the node-id string; their source `id` is blank.
     * Give the controller an element carrying that key, or a later press re-binding / group move
     * would fail selectOnly("") and silently drop the selection.
     */
    private fun keyedElement(el: SvgElement): SvgElement =
        if (el.id.isBlank() && el.nodeId != 0L) el.copy(id = el.nodeId.toString()) else el

    /**
     * Topmost PAINTED leaf inside the SVG-space rectangle `(sx, sy, sw, sh)`, read from the
     * colour-ID canvas. Returns null when nothing paints inside (concave holes / gaps are
     * respected, unlike a bounding-box intersect) or when the canvas is unavailable.
     */
    private fun regionPickTop(
        sx: Double,
        sy: Double,
        sw: Double,
        sh: Double,
    ): SvgElement? {
        val img = pickImage ?: return null
        val lw = engine.layout.width
        val lh = engine.layout.height
        if (lw <= 0.0 || lh <= 0.0) return null
        val kx = img.width / lw
        val ky = img.height / lh
        val x0 = (sx * kx).toInt().coerceIn(0, img.width)
        val y0 = (sy * ky).toInt().coerceIn(0, img.height)
        val x1 = ((sx + sw) * kx).toInt().coerceIn(0, img.width)
        val y1 = ((sy + sh) * ky).toInt().coerceIn(0, img.height)
        if (x1 <= x0 || y1 <= y0) return null
        var best = 0
        var y = y0
        while (y < y1) {
            var x = x0
            while (x < x1) {
                val v = pickPixelValue(img, x, y)
                if (v > best) best = v
                x++
            }
            y++
        }
        if (best <= 0) return null
        return elementForPickValue(best)
    }

    /**
     * Apply `(dx, dy)` (canvas units) to every selected element, committing each move to the
     * source. Returns true when every element moved. Used by a multi-selection group drag.
     */
    private fun applyGroupMove(
        dx: Double,
        dy: Double,
    ): Boolean {
        if (dx == 0.0 && dy == 0.0) return false
        val sc = sidecar
        val viaSidecar = sc != null && sidecarActive
        var allCommitted = true
        // Each commit refreshes the source + layout (sidecar round-trips / local re-parse), so
        // resolve every element fresh inside the loop. Sidecar leaves are moved by their node id
        // (their blank source id can't be matched by the legacy source-rewriting engine edits).
        for (id in selectedIds.toList()) {
            val el = engine.layout.byId(id)
            if (el == null) {
                allCommitted = false
                continue
            }
            val moved =
                if (viaSidecar && el.nodeId != 0L) {
                    commitSidecar(InteractionController.EditResult.Move(el, dx, dy)) == true
                } else {
                    engine.moveElement(id, dx, dy)
                }
            if (!moved) allCommitted = false
        }
        return allCommitted
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
                if (groupDrag && selectedIds.size > 1) {
                    committed = applyGroupMove(res.dx, res.dy)
                } else {
                    committed =
                        commitSidecar(res) ?: engine.moveElement(res.element.id, res.dx, res.dy)
                    selectedId = res.element.id
                }
                groupDrag = false
                if (committed) refreshAfterEdit(selectedId ?: res.element.id)
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

    /** Test hook: current pan offset (panel px). */
    fun debugOffsetX(): Double = offsetX

    /** Test hook: current pan offset (panel px). */
    fun debugOffsetY(): Double = offsetY

    /** Test hook: current view scale (panel px per SVG unit). */
    fun debugViewScale(): Double = viewScale

    /** Test hook: replace the selection with `ids` (last member becomes primary). */
    fun debugSetSelection(ids: List<String>) {
        if (ids.isEmpty()) {
            clearSelection()
            canvas.repaint()
        } else {
            setSelection(ids, ids.last())
        }
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
        // Layered compositing (baked bg + cropped fg) exists to preview a move/resize/rotate at
        // full fps: the background raster hides the element and the cropped foreground is blitted
        // at the live (snapped) preview box. The composite must ONLY drive the picture while such
        // a manipulation is actually in progress.
        //
        // A plain selection (click without moving) must keep painting the exact same content
        // frame as the idle path — the full raster — so selecting an element can never make its
        // pixels re-appear through a separate bg+fg pair (which is rasterised in a different
        // pass, at a possibly stale view scale, and blitted through its own sub-pixel transform).
        // That re-composite is what read as a subtle one-frame "jump" of the shape at the instant
        // of selection. Hover pre-heats the same layers but must never switch the frame either.
        val manipulationActive =
            interaction.previewBox != null || interaction.previewAngle != 0.0
        val layersReady =
            layerId != null &&
                layerId == selectedId &&
                bgImage != null &&
                fgImage != null &&
                layersCurrent()
        val useLayers = layersReady && manipulationActive
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
     * Grid drawn over the document when toggled on. The line pitch is the image (SVG) pixel
     * pitch [GRID_SPAN] scaled to screen, but at low zoom (a large doc fit into a small viewport)
     * that would collapse to sub-pixel lines — so the pitch is multiplied by the smallest power of
     * two that keeps the on-screen spacing readable. The toggle therefore always has a visible
     * effect, at any zoom level.
     */
    private fun drawGrid(g: Graphics2D) {
        val w = engine.layout.width
        val h = engine.layout.height
        if (w <= 0 || h <= 0) return
        var span = GRID_SPAN
        while (span * viewScale < MIN_GRID_PX) span *= 2
        val step = span * viewScale
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

    /**
     * Selection overlay. Every selected element gets an accent outline, so a multi-selection
     * (marquee box-select) visibly marks ALL members instead of hiding all but the primary. The
     * primary member additionally carries the 8 control handles and the rotate lever.
     *
     * Only the primary's box tracks the live (snapped) preview during a manipulation; the other
     * members sit on their resting geometry until the group edit commits.
     */
    private fun drawSelection(g: Graphics2D) {
        if (selectedIds.isEmpty()) return
        val primaryId = selectedId
        for (id in selectedIds.toList()) {
            val el = engine.layout.byId(id) ?: continue
            val isPrimary = id == primaryId
            val box =
                if (isPrimary) {
                    interaction.previewBox
                        ?: InteractionController.Box(el.x, el.y, el.width, el.height)
                } else {
                    InteractionController.Box(el.x, el.y, el.width, el.height)
                }
            val rad = if (isPrimary) Math.toRadians(interaction.previewAngle) else 0.0
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
            if (!isPrimary) continue

            // 8 round control points (white fill, accent outline) on the primary only.
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
