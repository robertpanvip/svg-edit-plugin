package com.pan.svg.core

import java.awt.BasicStroke
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.KeyboardFocusManager
import java.awt.KeyEventDispatcher
import java.awt.Point
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.TexturePaint
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.FocusAdapter
import java.awt.event.FocusEvent
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
import javax.swing.JComponent
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
 *  3. Wheel zoom / window resize resample the existing bitmap for instant feedback; wheel zoom
 *     additionally re-submits a crisp frame at every step (latest-wins), while a resize still
 *     debounces into a single re-render once it settles.
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

    /**
     * Keys of the members the cached [bgImage]/[fgImage] were produced for when they cover a
     * whole multi-selection (group drag): the background hides EVERY member and the ghost keeps
     * all of them, so the whole group previews moving together. Null = the layers belong to the
     * single [layerId] element only.
     */
    private var layerGroup: List<String>? = null

    /** True when the cached drag layers were rendered for the current view scale × dpr. */
    private fun layersCurrent(): Boolean = layerViewScale == viewScale && layerDpi == dpiScale

    /** The layer request the panel is currently waiting for (staleness guard). */
    private var wantedLayer: LayerTag? = null

    /** Static composite (background + grid + base raster) baked once per view/selection change. */
    private var staticLayer: BufferedImage? = null
    private var staticDirty = true
    private var staticDrag = false
    private var staticBgColor: Color? = null

    /** Cached transparency-checkerboard tile (constant colours) — rebuilt never, blitted every frame. */
    private var chessTile: BufferedImage? = null

    /** Sidecar-rendered content frame for the current view; null = legacy in-process raster. */
    private var vpImage: BufferedImage? = null

    /**
     * Document source each cached content raster was rendered from (null = nothing cached).
     *
     * A raster must never outlive the document it depicts: an edit can be committed by a path
     * that does not re-produce the frame (e.g. the element has no sidecar node id, so the move
     * is applied to the local source only), and the canvas would then keep painting the
     * pre-edit picture — the object visibly "snaps back to where it was" after the drag. The
     * idle draw path compares these stamps against the live `engine.svgSource` and simply skips
     * a stale raster; [refreshAfterEdit] then re-requests a frame for the new source.
     */
    private var vpSvg: String? = null
    private var offscreenSvg: String? = null

    /** True when [vpImage] is a viewport frame (pasted at 0,0); false = full-canvas region frame. */
    private var vpViewportMode = false

    /**
     * Downscale factor the current [vpImage] was produced with during a heavy-document zoom
     * burst (1.0 normally). The raster is up-scaled by `1/down` on display, so the picture stays
     * correct while each burst frame renders in a fraction of the device-pixel budget.
     */
    private var contentDown = 1.0

    /**
     * True while a Ctrl+wheel zoom burst is rendering a heavy document. During the burst,
     * content frames use [contentDown]'s reduced resolution and hover/drag-layer pre-heat is
     * skipped (both cost work that only matters once the zoom stops). Cleared by [crispTimer]
     * once the wheel settles, which then re-renders at full resolution.
     */
    private var zoomBurst = false

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

    /**
     * Deferred arrow-key nudge: the primary element it was applied to and the accumulated delta
     * (canvas units). Arrow keys move ONLY the top-most selected layer through a cheap composite
     * preview — the committed source stays untouched until [commitPendingNudge] lands (when the
     * panel loses focus, or the selection/gesture changes), so holding an arrow key never triggers
     * a full copy of the document per keypress.
     */
    private var nudgePrimary: String? = null
    private var nudgeDx = 0.0
    private var nudgeDy = 0.0

    /** Transparency chessboard (IDEA-style) drawn behind the image. On by default. */
    private var chessboardEnabled = true

    /** Image-pixel grid, shown only at >=100%. Off by default. */
    private var gridEnabled = false

    /** Marquee (rubber-band) selection state, in panel pixels. */
    private var marqueeOrigin: Point? = null
    private var marqueeRect: Rectangle? = null

    /**
     * Dirty region accumulated across the current drag, in panel pixels. A drag only moves the
     * foreground blit + selection overlay, so repainting just the union of the previous and new
     * moving bounds (instead of the whole canvas) is what keeps a CPU-only device smooth: the
     * background raster is a full-window blit, and painting it every mouse move is the drag's
     * dominant cost. `null` = no partial region yet (repaint everything).
     */
    private var dragDirty: Rectangle? = null

    /** True while the previous drag frame drew full-width/height snap guides (forces a full repaint). */
    private var dragSnapFull = false

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

    /** View parameters for one sidecar content frame (Rust maps px = svg*scale + tx).
     *  `down` is the burst downscale used to produce this frame (1.0 at rest; display
     *  compensates by up-scaling by `1/down`). `svg` stamps the document the frame was
     *  requested from: a frame that was still rendering when the user released an edit would
     *  otherwise come back painted from the PRE-edit document and overwrite the committed
     *  frame — the "it moves, then snaps back on mouse-up" symptom on slow machines. */
    private data class VpTag(
        val svg: String,
        val vw: Int,
        val vh: Int,
        val scale: Double,
        val tx: Double,
        val ty: Double,
        val down: Double = 1.0,
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

    /**
     * Lands a pending arrow-key nudge when the drawing panel loses focus — the explicit "save"
     * boundary for keyboard moves (see [nudgeSelection] / [commitPendingNudge]). Attached to both
     * this panel and the canvas so it fires regardless of which one holds the focus owner.
     */
    private val nudgeFocusListener =
        object : FocusAdapter() {
            override fun focusLost(e: FocusEvent) {
                // Focus loss is the one place an arrow-key nudge both commits AND echoes to the
                // editor — the user's "save when I leave the drawing panel" boundary.
                commitPendingNudge(echo = true)
            }
        }

    /**
     * Application-wide key gate for the editing keys.
     *
     * A plain [KeyAdapter] on the canvas only fires when the canvas is the focus owner, and in the
     * IDE two things get in the way: focus may land on the panel (or stay on the IDE's own
     * component), and the IDE installs its keymap dispatcher on the same [KeyboardFocusManager].
     * A dispatcher added later runs first (LIFO), so this one sees the key before the IDE keymap and
     * before Swing's key bindings — but it only claims events whose focus owner is inside this
     * panel, so typing anywhere else in the IDE is untouched.
     */
    private val keyDispatcher =
        KeyEventDispatcher { e ->
            if (e.id != KeyEvent.KEY_PRESSED) {
                false
            } else {
                val owner = KeyboardFocusManager.getCurrentKeyboardFocusManager().focusOwner
                val ours = owner != null && (owner === this || SwingUtilities.isDescendingFrom(owner, this))
                if (ours) onKeyPressed(e) else false
            }
        }

    /** Coalesces wheel-zoom / resize bursts into a single crisp re-render. */
    private val crispTimer: Timer? =
        scheduler?.let {
            Timer(CRISP_DELAY_MS) {
                // The wheel-zoom burst is over: drop the reduced-resolution content state and
                // re-render one crisp full-resolution frame for the settled view.
                zoomBurst = false
                renderAtDeviceSize()
                canvas.repaint()
                // Re-warm hover drag layers that were skipped while the burst was running.
                val pid = pendingHoverId
                if (selectedId == null && pid != null && pid != layerId) preheatTimer?.restart()
            }.apply { isRepeats = false }
        }

    /** Pre-heats the drag layers shortly after hover, so press-and-drag never waits. */
    private val preheatTimer: Timer? =
        scheduler?.let {
            Timer(PREHEAT_DELAY_MS) {
                val id = pendingHoverId
                if (!zoomBurst && selectedId == null && id != null && id != layerId) requestLayers(id)
            }.apply { isRepeats = false }
        }

    companion object {
        private const val CRISP_DELAY_MS = 160
        private const val PREHEAT_DELAY_MS = 120
        /** Burst content-frame downscale for heavy documents (0.5 ⇒ 25% of the device pixels). */
        private const val BURST_DOWNSCALE = 0.5
        /** Documents at/above either threshold are treated as heavy for zoom-burst rendering. */
        private const val HEAVY_SOURCE_BYTES = 96 * 1024
        private const val HEAVY_ELEMENTS = 500

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
        // Focusable itself so an IDE host can hand keyboard focus to the editor (its
        // `getPreferredFocusedComponent`); [keyDispatcher] then routes the editing keys in.
        isFocusable = true
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
        disableScrollArrowKeys()
        add(scrollPane, BorderLayout.CENTER)
        installMouse()
        installKeys()
        addFocusListener(nudgeFocusListener)
        canvas.addFocusListener(nudgeFocusListener)
        KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(keyDispatcher)
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

    /**
     * SVG source after edits, with synthetic anchors stripped — what a host should write into
     * the user's document ([svgSource] keeps them for internal editing; the written text must
     * not grow `svg-el-N` attributes on id-less files).
     */
    val svgSourceForWrite: String get() = engine.sourceForWrite()

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
        commitPendingNudge()
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

    /**
     * Arrow-key nudge step in canvas units: 1% of the visible viewport on the given axis. Expressing
     * the step in viewport terms (rather than a fixed SVG unit) keeps a keypress moving the
     * selection by the same fraction of the window at any zoom. When the viewport is unknown
     * (no layout yet) fall back to 1% of the document extent so the key never becomes a no-op.
     */
    private fun nudgeStep(vertical: Boolean): Double {
        val viewPx = (if (vertical) viewH() else viewW()).toDouble().takeIf { it > 0 }
        val px = viewPx ?: (if (vertical) engine.layout.height else engine.layout.width)
        val s = if (viewScale > 0.0) viewScale else 1.0
        return px * 0.01 / s
    }

    /** Multiplier applied to the arrow-key nudge: Shift gives a coarse 10x step. */
    private fun nudgeFactor(e: KeyEvent): Double = if (e.isShiftDown) 10.0 else 1.0

    /** Nudge the selection by `(dx, dy)` canvas units (arrow keys). */
    fun nudgeSelection(dx: Double, dy: Double) {
        if (dx == 0.0 && dy == 0.0) return
        // Arrow keys move ONLY the top-most selected layer (the primary), exactly like a drag's
        // preview. The committed source is untouched until [commitPendingNudge] — holding an arrow
        // key just shifts the already-rendered ghost, never a full document round-trip per press.
        val primary = selectedId ?: return
        nudgePrimary = primary
        nudgeDx += dx
        nudgeDy += dy
        // Ensure the single-element preview layers for the primary are ready; the composite then
        // blits the pre-rendered ghost at the shifted box (see the renderCanvas layered branch).
        if (layerId != primary || !layersCurrent()) {
            clearLayers()
            requestLayers(primary)
        }
        val el = engine.layout.byId(primary) ?: return
        interaction.previewBox =
            InteractionController.Box(el.x + nudgeDx, el.y + nudgeDy, el.width, el.height)
        interaction.previewAngle = 0.0
        canvas.repaint()
        emitStatus()
    }

    /**
     * Land a pending arrow-key nudge: commit the accumulated move ONCE (through the same sidecar
     * path as a drag so it reaches the source actually rendered) and re-render the frame. When
     * [echo] is set the move is also echoed to the host editor — reserved for the panel losing
     * focus (the "save" boundary), so selection changes / new gestures settle the source without
     * churning the text editor. A no-op when nothing is pending.
     */
    private fun commitPendingNudge(echo: Boolean = false) {
        val wasNudging = nudgePrimary != null
        val primary = nudgePrimary
        val dx = nudgeDx
        val dy = nudgeDy
        // When a nudge preview is/was in effect, drop it so the composite returns to the
        // committed frame. Never touch `previewBox` for a live drag — handleRelease re-reads it
        // to fold the release point into the drag's final commit, and clearing it there would
        // silently discard that (making the element snap back).
        nudgePrimary = null
        nudgeDx = 0.0
        nudgeDy = 0.0
        if (wasNudging) {
            interaction.previewBox = null
            interaction.previewAngle = 0.0
        }
        if (primary == null || (dx == 0.0 && dy == 0.0)) return
        val el = engine.layout.byId(primary) ?: return
        val sc = sidecar
        val viaSidecar = sc != null && sidecarActive && el.nodeId != 0L
        val committed =
            if (viaSidecar) {
                commitSidecar(InteractionController.EditResult.Move(el, dx, dy))
                    ?: engine.moveElement(primary, dx, dy)
            } else {
                engine.moveElement(primary, dx, dy)
            }
        if (committed) {
            // A local-only edit re-renders the frame once; a sidecar commit already produced one.
            if (!viaSidecar) {
                offscreen = renderNow()
                offscreenSvg = engine.svgSource
                vpImage = null
                vpSvg = null
                vpPreview = null
                staticDirty = true
            }
            clearLayers()
            requestLayers(primary)
            canvas.repaint()
            emitStatus()
            if (echo) onEdit?.invoke() // echo the settled position to the editor (one write, on focus loss)
        } else {
            clearLayers()
            canvas.repaint()
        }
    }

    /** Move the selected elements onto the given alignment edge/axis of the selection bounds. */
    fun alignSelection(a: Align) {
        commitPendingNudge()
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
        commitPendingNudge()
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
        // A nudged-but-uncommitted element must settle before the selection moves on, or the
        // pending preview offset would be drawn against the new primary's ghost.
        commitPendingNudge()
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
        // Pre-heat the drag layers for the settled selection so the first press is warm:
        // a multi-selection pre-renders the whole-group pair (background hides every member),
        // a single selection its own pair.
        if (selectedIds.size > 1) {
            requestGroupLayers()
        } else if (layerId != p) {
            requestLayers(p)
        }
        canvas.repaint()
        return p
    }

    /** Collapse the selection to the single element `id` (and make it primary). */
    private fun selectOnly(id: String): String? = setSelection(listOf(id), id)

    /** Clear the selection entirely. */
    private fun clearSelection() {
        commitPendingNudge()
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
        removeFocusListener(nudgeFocusListener)
        canvas.removeFocusListener(nudgeFocusListener)
        KeyboardFocusManager.getCurrentKeyboardFocusManager().removeKeyEventDispatcher(keyDispatcher)
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
        // Pre-heating the selection's drag layers is wasted raster churn while a zoom burst is
        // re-submitting the content frame on every notch — the zoomed frame would invalidate
        // them instantly. Defer until the wheel settles (crispTimer re-requests them).
        if (!zoomBurst) {
            // A group drag must keep its whole-selection pair even when the view re-renders
            // (resize/zoom mid-drag): re-warm the GROUP layers, not just the primary's.
            if (groupDrag && selectedIds.size > 1) {
                requestGroupLayers()
            } else {
                selectedId?.let { requestLayers(it) }
            }
        }
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
            offscreenSvg = engine.svgSource
            vpImage = null
            vpSvg = null
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
                    offscreenSvg = src
                    pickImage = result?.second
                    vpImage = null
                    vpSvg = null
                    vpPreview = null
                    contentDown = 1.0 // legacy frames are always produced at full resolution
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
        val down = wanted.down
        // A burst frame is rendered at `down`× the device pixel budget: the pixel grid AND the
        // view transform (scale/tx/ty) shrink together so exactly the same SVG region is
        // covered. The draw path then up-scales the raster by 1/down, so the picture stays
        // geometrically correct while each notch costs a fraction of a full-res render.
        val rw = kotlin.math.max(1, kotlin.math.round(wanted.vw * down).toInt())
        val rh = kotlin.math.max(1, kotlin.math.round(wanted.vh * down).toInt())
        val rscale = wanted.scale * down
        val rtx = wanted.tx * down
        val rty = wanted.ty * down
        val task = {
            try {
                sc.renderViewport(rw, rh, rscale, rtx, rty)
            } catch (t: Throwable) {
                lastRenderError = t
                null
            }
        }
        val apply: (BufferedImage?) -> Unit = { result ->
            if (result != null && contentParams() == wanted && vpViewportMode == isViewport) {
                vpImage = result
                vpSvg = wanted.svg
                offscreen = null
                offscreenSvg = null
                vpPreview = null
                contentDown = wanted.down
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

    /** Current view parameters for a sidecar frame; also refreshes [vpViewportMode].
     *  The fields carry the FULL-resolution view (used for staleness checks, hit testing and
     *  edit round-trips); the render path applies [VpTag.down] itself. */
    private fun contentParams(): VpTag? {
        val w = engine.layout.width
        val h = engine.layout.height
        if (w <= 0 || h <= 0) return null
        val dpr = dpiScale
        val down = if (zoomBurst) BURST_DOWNSCALE else 1.0
        return if (w * viewScale <= viewW() && h * viewScale <= viewH()) {
            vpViewportMode = true
            VpTag(
                svg = engine.svgSource,
                vw = kotlin.math.max(1, kotlin.math.round(viewW() * dpr).toInt()),
                vh = kotlin.math.max(1, kotlin.math.round(viewH() * dpr).toInt()),
                scale = viewScale * dpr,
                tx = offsetX * dpr,
                ty = offsetY * dpr,
                down = down,
            )
        } else {
            vpViewportMode = false
            VpTag(
                svg = engine.svgSource,
                vw = devicePx(w),
                vh = devicePx(h),
                scale = viewScale * dpr,
                tx = 0.0,
                ty = 0.0,
                down = down,
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
        // Switching from group layers (one pair covering a whole multi-selection) back to the
        // single-element pair: drop the group pair first, or it could be matched as a stale
        // single pair while the fresh single pair is still rendering.
        if (layerGroup != null) clearLayers()
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
                layerGroup = null
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
                layerGroup = null
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

    /**
     * Group drag layers: one startDragGroup round-trip returns a background with EVERY selected
     * member's subtree hidden plus a single ghost containing all of them (ancestor groups
     * preserved). Cropping the ghost to the members' union box and blitting it translated by the
     * group's drag delta previews the whole selection moving together. Falls back to the
     * single-element layers when the sidecar can't represent the set (non-sidecar/legacy path).
     */
    private fun requestGroupLayers() {
        val sc = sidecar
        val primary = selectedId ?: return
        if (sc == null || !sidecarActive) {
            requestLayers(primary)
            return
        }
        val ids = selectedIds.toList()
        val els = ids.mapNotNull { engine.layout.byId(it) }
        val nodeIds = els.map { it.nodeId }
        if (ids.size < 2 || els.size != ids.size || nodeIds.any { it == 0L }) {
            requestLayers(primary)
            return
        }
        val w = engine.layout.width
        val h = engine.layout.height
        if (w <= 0.0 || h <= 0.0) return
        val tag = LayerTag(engine.svgSource, primary, devicePx(w), devicePx(h), viewScale, dpiScale)
        wantedLayer = tag
        val dpr = dpiScale
        val groupSnapshot = ids // the exact set this pair will cover
        val task = {
            try {
                val imgs = sc.startDragGroup(nodeIds, tag.w, tag.h, viewScale * dpr, 0.0, 0.0)
                // The tight crop needs an alpha scan of the ghost; keep it on the render worker
                // so the EDT never pays for it (the drag would hitch on a HiDPI/large canvas).
                imgs to ghostBounds(imgs.ghost)
            } catch (t: Throwable) {
                lastRenderError = t
                null
            }
        }
        val apply: (Pair<SidecarDragImages, IntArray>?) -> Unit = { result ->
            val imgs = result?.first
            if (
                imgs != null &&
                wantedLayer === tag &&
                tag.svg === engine.svgSource &&
                tag.viewScale == viewScale &&
                tag.dpr == dpiScale
            ) {
                layerId = primary
                layerGroup = groupSnapshot
                layerViewScale = tag.viewScale
                layerDpi = tag.dpr
                bgImage = imgs.background
                fgImage = imgs.ghost
                buildGroupCrop(result.second)
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
        layerGroup = null
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

    /**
     * Tight painted extent of `img` as `[x0, y0, x1, y1)` (x1/y1 exclusive), or an empty array
     * when nothing is painted. Used for the group ghost, whose non-transparent bbox IS the exact
     * box of the selected members (strokes and antialiasing included). Runs on the render worker.
     */
    private fun ghostBounds(img: BufferedImage): IntArray {
        val w = img.width
        val h = img.height
        if (w <= 0 || h <= 0) return IntArray(0)
        var minX = w
        var minY = h
        var maxX = -1
        var maxY = -1
        val row = IntArray(w)
        for (y in 0 until h) {
            img.getRGB(0, y, w, 1, row, 0, w)
            var x = 0
            while (x < w && (row[x] ushr 24) <= 8) x++
            if (x == w) continue // fully transparent row
            if (x < minX) minX = x
            var last = w - 1
            while (last > x && (row[last] ushr 24) <= 8) last--
            if (last > maxX) maxX = last
            if (y < minY) minY = y
            maxY = y
        }
        if (maxX < minX || maxY < minY) return IntArray(0)
        return intArrayOf(minX, minY, maxX + 1, maxY + 1)
    }

    /**
     * Crop the group ghost to the pre-computed painted extent ([ghostBounds]). Keeping the crop
     * tight is what keeps the per-frame blit cheap: a loose union-based padding made it
     * canvas-sized (members are often far apart) and the drag stuttered.
     */
    private fun buildGroupCrop(bounds: IntArray) {
        val fg = fgImage ?: run { fgCrop = null; return }
        if (layerGroup == null || bounds.size != 4) {
            fgCrop = null
            return
        }
        val pad = 2
        val cx0 = (bounds[0] - pad).coerceAtLeast(0)
        val cy0 = (bounds[1] - pad).coerceAtLeast(0)
        val cx1 = (bounds[2] + pad).coerceAtMost(fg.width)
        val cy1 = (bounds[3] + pad).coerceAtMost(fg.height)
        if (cx1 - cx0 < 1 || cy1 - cy0 < 1) {
            fgCrop = null
            return
        }
        fgCrop = fg.getSubimage(cx0, cy0, cx1 - cx0, cy1 - cy0)
        fgCropX = cx0.toDouble()
        fgCropY = cy0.toDouble()
    }

    private fun clearLayers() {
        wantedLayer = null
        scheduler?.cancel(RenderScheduler.Slot.LAYERS)
        bgImage = null
        fgImage = null
        fgCrop = null
        layerId = null
        layerGroup = null
        layerViewScale = -1.0
        layerDpi = -1.0
    }

    /**
     * Whether the cached drag layers cover the current selection: a single-element pair must
     * belong to the selected element, a group pair must cover exactly the whole selection.
     */
    private fun layersMatchSelection(): Boolean {
        val g = layerGroup
        if (g != null) {
            if (layerId == null || selectedIds.size != g.size) return false
            return g.all { it in selectedIds }
        }
        return layerId != null && layerId == selectedId
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
            // ...unless the edit did NOT go through the sidecar (the element carries no node id,
            // so the move was rewritten into the local source only). Then the cached frame still
            // depicts the pre-edit document and would paint the object back at its old spot;
            // re-produce it for the new source instead.
            if (vpSvg != engine.svgSource && offscreenSvg != engine.svgSource) requestContent()
            requestLayers(id)
            canvas.repaint()
            return
        }
        if (scheduler == null) {
            offscreen = renderNow()
            offscreenSvg = engine.svgSource
            refreshPickNow()
            rebuildLayersSync(id)
        } else {
            offscreen = renderNow()
            offscreenSvg = engine.svgSource
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

    /**
     * Whether the loaded document is expensive enough to rasterize to earn the burst
     * optimisations (reduced burst resolution + skipped pre-heat churn). Cheap documents render
     * fast enough at full resolution that downscaling would only add blur.
     */
    private fun isHeavySource(): Boolean =
        engine.layout.elements.size >= HEAVY_ELEMENTS || engine.svgSource.length >= HEAVY_SOURCE_BYTES

    /** Zoom by `factor`, keeping the SVG point under `(ax, ay)` (panel px) fixed when given.
     *  `burst` = true when the caller expects repeated notches (Ctrl+wheel): a heavy document
     *  then renders each notch at reduced resolution and defers the crisp frame until the burst
     *  settles. */
    private fun zoomBy(
        factor: Double,
        ax: Double? = null,
        ay: Double? = null,
        burst: Boolean = false,
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
        // Heavy documents on a wheel burst render each notch at reduced resolution and get the
        // crisp frame when the wheel settles (crispTimer clears the burst flag). Any other zoom
        // ends an ongoing burst immediately so a discrete action is answered with a crisp frame.
        if (scheduler != null) {
            val wasBurst = zoomBurst
            zoomBurst = burst && isHeavySource()
            when {
                zoomBurst -> crispTimer?.restart()
                wasBurst -> crispTimer?.stop()
            }
        }
        // Instant feedback while zooming: freeze the current sidecar frame and paste it under
        // the new view transform until the crisp re-render lands. A viewport frame moves with
        // the old pan, so it needs the −k·oldOffset correction; a region frame is pasted at the
        // plain new pan. Both keep the SVG point under the cursor visually fixed. The source may
        // be a reduced-resolution burst frame, so its transform is up-scaled by 1/contentDown to
        // compensate (the contentDown invariant: 1.0 whenever the source is [offscreen]).
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
                at.scale(k / dpiScale / contentDown, k / dpiScale / contentDown)
                vpPreview = VpPreview(src, at)
            }
        }
        // Instant feedback: the existing bitmap is resampled by drawScaled while a crisp frame
        // for the NEW view is rendered immediately. No 160ms debounce during wheel zoom: every
        // notch re-submits at the latest parameters and the render scheduler is latest-wins, so
        // older in-flight frames are dropped instead of queueing — the content stops looking
        // "one frame behind" (old-size picture until the burst settles).
        renderAtDeviceSize()
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
                    // Ask for focus twice: immediately, and again once the IDE's own focus handling
                    // for this click has run (it can otherwise restore focus to its own component,
                    // leaving the canvas unfocused and the editing keys dead).
                    canvas.requestFocusInWindow()
                    SwingUtilities.invokeLater { canvas.requestFocusInWindow() }
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
                        handleRelease(e.x, e.y)
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
                zoomBy(f, e.x.toDouble(), e.y.toDouble(), burst = true)
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

    /**
     * Drop the [JScrollPane]'s default arrow-key scroll bindings.
     *
     * Swing resolves key bindings on the focused component's ancestors (WHEN_ANCESTOR_OF_FOCUSED_COMPONENT)
     * *before* it fires the component's KeyListeners. The scroll pane binds the arrows to its scroll
     * actions, so with focus on the canvas the arrows were consumed by the pane and never reached
     * [onKeyPressed] — the selection could not be nudged. Scrollbars are disabled (the canvas is
     * pinned to the viewport and panning is pointer/space driven), so those bindings are dead weight
     * here; mapping them to `"none"` lets the key listener see the arrows again.
     */
    private fun disableScrollArrowKeys() {
        val arrows = intArrayOf(KeyEvent.VK_UP, KeyEvent.VK_DOWN, KeyEvent.VK_LEFT, KeyEvent.VK_RIGHT)
        val im = scrollPane.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT)
        for (ks in im.allKeys() ?: emptyArray()) {
            if (ks != null && ks.keyCode in arrows) im.put(ks, "none")
        }
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

            // Arrow keys: nudge by 1% of the viewport, 10x with Shift held.
            e.keyCode == KeyEvent.VK_UP -> {
                nudgeSelection(0.0, -nudgeStep(true) * nudgeFactor(e))
                e.consume()
                true
            }
            e.keyCode == KeyEvent.VK_DOWN -> {
                nudgeSelection(0.0, nudgeStep(true) * nudgeFactor(e))
                e.consume()
                true
            }
            e.keyCode == KeyEvent.VK_LEFT -> {
                nudgeSelection(-nudgeStep(false) * nudgeFactor(e), 0.0)
                e.consume()
                true
            }
            e.keyCode == KeyEvent.VK_RIGHT -> {
                nudgeSelection(nudgeStep(false) * nudgeFactor(e), 0.0)
                e.consume()
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
            // risk the composite flash) on mere hover. A zoom burst skips the pre-heat too — the
            // frame is re-rendering every notch and the layers would be invalidated instantly.
            if (scheduler != null && !zoomBurst && tool == Tool.MOVE && newHover != null && selectedId == null && newHover != layerId) {
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
        //
        // Only the moving foreground + overlay change during a drag, so repaint just the union of
        // the previous and the new moving bounds. Snap guides span the full canvas, so while they
        // are (or just were) on screen fall back to a full repaint — otherwise their old strokes
        // would leave trails.
        if (interaction.snapLines.isNotEmpty() || dragSnapFull) {
            dragSnapFull = interaction.snapLines.isNotEmpty()
            dragDirty = null
            canvas.repaint()
            return
        }
        val cur = movingPanelBounds()
        val prev = dragDirty
        if (prev == null) {
            // First moving frame: the current frame still shows the overlay/foreground at the
            // committed position, which can fall outside `cur`. One full repaint clears it; from
            // here on the union of the previous and new bounds is enough.
            dragDirty = cur
            canvas.repaint()
            return
        }
        val union = prev.union(cur)
        dragDirty = union
        canvas.repaint(union.x, union.y, union.width, union.height)
    }

    /**
     * Panel-pixel bounds of everything that moves with the current drag: the primary's preview
     * box, the other members' (translated) boxes, plus the selection overlay's handles / rotate
     * lever. Deliberately conservative (a few px of slack) so a partial repaint can never clip the
     * moving pixels into a visible trail.
     *
     * A non-rotating edit (move / resize) only needs the box itself: the foreground crop's
     * `max(w,h)` padding (see [buildFgCrop]) is transparent and never rotated into view, so folding
     * it into the dirty rect would mean repainting ~3x more area than anything that changes. Only a
     * live rotation needs the padded square, because then the crop really does sweep that area.
     */
    private fun movingPanelBounds(): Rectangle {
        val ids = selectedIds
        if (ids.isEmpty()) return Rectangle(0, 0, width, height)
        // Live group translation (SVG units), or null outside a group move.
        val groupDelta: Pair<Double, Double>? =
            if (groupDrag && ids.size > 1 && interaction.previewBox != null) {
                selectedId?.let { pid ->
                    engine.layout.byId(pid)?.let { pri ->
                        val pb = interaction.previewBox!!
                        (pb.x - pri.x) to (pb.y - pri.y)
                    }
                }
            } else {
                null
            }
        var x0 = Double.MAX_VALUE
        var y0 = Double.MAX_VALUE
        var x1 = -Double.MAX_VALUE
        var y1 = -Double.MAX_VALUE
        for (id in ids) {
            val el = engine.layout.byId(id) ?: continue
            val isPrimary = id == selectedId
            val pb = if (isPrimary) interaction.previewBox else null
            val bx = when {
                pb != null -> pb.x
                groupDelta != null -> el.x + groupDelta.first
                else -> el.x
            }
            val by = when {
                pb != null -> pb.y
                groupDelta != null -> el.y + groupDelta.second
                else -> el.y
            }
            val bw = pb?.w ?: el.width
            val bh = pb?.h ?: el.height
            var ex0 = bx
            var ey0 = by
            var ex1 = bx + bw
            var ey1 = by + bh
            if (isPrimary && groupDelta == null && interaction.previewAngle != 0.0) {
                // Rotating: the foreground crop is padded by max(w,h) per side, and its rotated AABB
                // can reach the crop's diagonal — widen to a square that can never clip the sweep.
                val pad = kotlin.math.max(bw, bh)
                val half = (bw / 2.0 + pad) + (bh / 2.0 + pad)
                val cx = bx + bw / 2.0
                val cy = by + bh / 2.0
                ex0 = cx - half
                ex1 = cx + half
                ey0 = cy - half
                ey1 = cy + half
            }
            if (ex0 < x0) x0 = ex0
            if (ey0 < y0) y0 = ey0
            if (ex1 > x1) x1 = ex1
            if (ey1 > y1) y1 = ey1
        }
        if (x1 <= x0 || y1 <= y0) return Rectangle(0, 0, width, height)
        // Overlay slack: handles, stroke width and the rotate lever above the box.
        val m = (EditorTheme.ROTATE_OFFSET + EditorTheme.ROTATE_R + 8).toDouble()
        val rx0 = (offsetX + x0 * viewScale - m).coerceAtLeast(0.0)
        val ry0 = (offsetY + y0 * viewScale - m).coerceAtLeast(0.0)
        val rx1 = (offsetX + x1 * viewScale + m).coerceAtMost(width.toDouble())
        val ry1 = (offsetY + y1 * viewScale + m).coerceAtMost(height.toDouble())
        if (rx1 <= rx0 || ry1 <= ry0) return Rectangle(0, 0, width, height)
        val ix = kotlin.math.floor(rx0).toInt()
        val iy = kotlin.math.floor(ry0).toInt()
        return Rectangle(ix, iy, (kotlin.math.ceil(rx1) - ix).toInt(), (kotlin.math.ceil(ry1) - iy).toInt())
    }

    private fun handlePress(
        x: Int,
        y: Int,
    ) {
        // A pending arrow-key nudge must settle before a new gesture starts: the drag it may
        // initiate would otherwise build its ghost / commit from a pre-nudge sidecar tree, and a
        // stray preview box must not leak into the fresh gesture.
        commitPendingNudge()
        // A new gesture starts from a clean slate: the previous drag's dirty region must not be
        // unioned into this one's.
        dragDirty = null
        dragSnapFull = false
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
            // Only a body MOVE drags the set as a group; grabbing the primary's resize/rotate
            // handle edits that one element and must keep the single-element drag layers.
            val groupMove = interaction.editMode == InteractionController.EditMode.MOVE
            groupDrag = groupMove
            if (groupMove) {
                requestGroupLayers()
            } else if (layerId != newSel) {
                requestLayers(newSel)
            }
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

    private fun handleRelease(x: Int, y: Int) {
        if (marqueeOrigin != null || marqueeRect != null) {
            finishMarquee()
            return
        }
        // Sync the controller's preview to the exact release point before committing. Swing
        // coalesces mouseDragged events, so the preview box often sits a few px behind the
        // pointer that was actually released; committing that stale box made the object visibly
        // "jump back" the instant the button came up. A final onDragMove folds the release
        // position (through the same snap hysteresis) into previewBox so what you see — the last
        // preview — is exactly what you get. A pure click (no motion) leaves previewBox null and
        // is skipped so click-to-select keeps behaving like a plain selection.
        val (ix, iy) = toImage(x, y)
        if (interaction.state == InteractionController.State.DRAG && interaction.previewBox != null) {
            interaction.onDragMove(engine.layout, ix, iy)
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
        dragDirty = null
        dragSnapFull = false
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
            // Drop any content frame still queued for the pre-edit document: it renders the OLD
            // geometry, and on a slow (CPU-only) machine it can land after this commit frame and
            // make the element visibly snap back to where it was before the drag. The frame's own
            // tag would reject it anyway; cancelling also saves the wasted render.
            scheduler?.cancel(RenderScheduler.Slot.CONTENT)
            contentDown = 1.0 // commit frames are always full resolution
            if (vpViewportMode) {
                vpImage = c.png
                vpSvg = c.svg
                offscreen = null
                offscreenSvg = null
            } else {
                offscreen = c.png
                offscreenSvg = c.svg
                vpImage = null
                vpSvg = null
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
        handleRelease(p2.x, p2.y)
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
    fun debugRelease() =
        handleRelease(
            kotlin.math.round(interaction.pointerX * viewScale + offsetX).toInt(),
            kotlin.math.round(interaction.pointerY * viewScale + offsetY).toInt(),
        )

    /** Test hook: release at an explicit panel point (even one beyond the last drag frame). */
    fun debugReleaseAt(x: Int, y: Int) = handleRelease(x, y)

    /** Test hook: disable edge snapping so tests can assert a pure delta. */
    fun debugSetSnapEnabled(enabled: Boolean) {
        interaction.snapEnabled = enabled
    }

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

    /**
     * Test hook: the document source the cached content raster depicts (null = no raster yet).
     * The canvas only paints a raster whose stamp equals the live `svgSource`, so equal values
     * here mean the picture on screen belongs to the current document.
     */
    fun debugContentSvg(): String? = vpSvg ?: offscreenSvg

    /** Test hook: land a pending arrow-key nudge (same as the focus-loss save), so tests can assert the
     *  deferred commit deterministically without simulating a focus event. */
    fun debugFlushNudge() = commitPendingNudge(echo = true)

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
            bgImage != null &&
                fgImage != null &&
                layersMatchSelection() &&
                layersCurrent()
        val useLayers = layersReady && manipulationActive
        if (useLayers) {
            staticDrag = true // the base raster for the layers is the bg layer (element hidden)
            if (staticLayer == null || staticDirty || staticBgColor != background) {
                rebuildStaticLayer()
            }
            staticLayer?.let { g.drawImage(it, 0, 0, null) }
                ?: run { g.color = background; g.fillRect(0, 0, width, height) }
            if (fgCrop != null) {
                if (layerGroup != null) drawGroupFg(g) else drawFg(g)
            }
        } else {
            // Idle path: draw the full render directly. Skipping the intermediate static layer
            // avoids Java2D colour-management conversions that can shift exact pixel values and
            // break the headless pixel-consistency checks.
            paintBackground(g)
            if (gridEnabled) drawGrid(g)
            // Content raster priority: frozen zoom preview > sidecar frame (viewport frames
            // paste at (0,0), region frames go through the legacy placement) > legacy offscreen.
            // A sidecar frame may be a reduced-resolution burst frame: its natural draw size is
            // `contentDown`× the full size, so it is up-scaled by 1/contentDown to compensate.
            val pv = vpPreview
            // Only paint a raster that still depicts the CURRENT document: one rendered from a
            // source that has since been edited would show the object at its previous position
            // (the "it moved, then went back" symptom). A stale raster is skipped entirely; the
            // edit paths re-request a fresh frame for the new source.
            val src = engine.svgSource
            val vp = vpImage?.takeIf { vpSvg == src }
            val off = offscreen?.takeIf { offscreenSvg == src }
            when {
                pv != null -> drawVpPreview(g, pv)
                vp != null -> {
                    val up = 1.0 / contentDown
                    if (vpViewportMode) drawScaledAt(g, vp, 0.0, 0.0, up) else drawScaled(g, vp, up)
                }
                off != null -> drawScaled(g, off)
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
        up: Double = 1.0,
    ) {
        drawScaledAt(g, img, offsetX, offsetY, up)
    }

    /** Paste a device-resolution raster at `(tx, ty)` logical px, scaled back by [dpiScale].
     *  `up` additionally up-scales the paste (used to compensate reduced-resolution burst
     *  frames: pass `1 / down` so they fill the same screen rectangle as a full-res frame). */
    private fun drawScaledAt(
        g: Graphics2D,
        img: BufferedImage,
        tx: Double,
        ty: Double,
        up: Double = 1.0,
    ) {
        // Float placement (no integer truncation) so the committed/offscreen raster is composited
        // at the exact same sub-pixel position as the drag preview — eliminating the last source
        // of a systematic "position different" shift at drag end on fractional-DPI displays.
        val dw = img.width / dpiScale * up
        val dh = img.height / dpiScale * up
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
        // This is the temporary "instant feedback" frame shown between wheel notches (a resample of
        // the previous frame while the crisp one renders). It is repainted on every notch, so it
        // must be cheap: NEAREST + SPEED resampling keeps fast zooming from dropping frames on a
        // CPU-only machine, and the quality is restored the moment the real frame lands.
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR)
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_SPEED)
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

    /**
     * Blit the group ghost ([fgCrop], the union crop of all selected members) at the live group
     * delta. A group drag is a pure translation: every member keeps its committed offset
     * relative to the primary, so the whole union crop shifts by exactly the primary's preview
     * delta and all members visibly move together at full fps.
     */
    private fun drawGroupFg(g: Graphics2D) {
        val crop = fgCrop ?: return
        val primaryId = selectedId ?: return
        val primary = engine.layout.byId(primaryId) ?: return
        val pb = interaction.previewBox ?: return
        val dxSvg = pb.x - primary.x
        val dySvg = pb.y - primary.y
        val dpr = dpiScale
        // The crop is a device-resolution raster whose top-left sits at fgCropX/Y device px
        // inside the full-canvas ghost. Drawing it at its natural position (delta 0) realigns
        // with the background; shifting by the drag delta moves the whole group live.
        val gx = offsetX + dxSvg * viewScale + fgCropX / dpr
        val gy = offsetY + dySvg * viewScale + fgCropY / dpr
        val oldClip = g.clip
        val vbW = (engine.layout.width * viewScale).toInt().coerceAtLeast(1)
        val vbH = (engine.layout.height * viewScale).toInt().coerceAtLeast(1)
        g.clipRect(offsetX.toInt(), offsetY.toInt(), vbW, vbH)
        drawScaledAt(g, crop, gx, gy)
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
        // The tile's colours are fixed, so build it once and reuse it across every frame instead
        // of allocating a BufferedImage + Graphics2D per paint (a real cost during a drag).
        val tile =
            chessTile ?: BufferedImage(2 * CHESS_CELL, 2 * CHESS_CELL, BufferedImage.TYPE_INT_RGB).also {
                val tg = it.createGraphics()
                tg.color = CHESS_WHITE
                tg.fillRect(0, 0, it.width, it.height)
                tg.color = CHESS_GRAY
                tg.fillRect(cell.toInt(), 0, cell.toInt(), cell.toInt())
                tg.fillRect(0, cell.toInt(), cell.toInt(), cell.toInt())
                tg.dispose()
                chessTile = it
            }
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
     * Only the primary's box tracks the live (snapped) preview during a manipulation; during a
     * group move the other members' outlines follow the same delta so the overlay and the moving
     * ghost stay aligned (members keep their relative offsets).
     */
    private fun drawSelection(g: Graphics2D) {
        if (selectedIds.isEmpty()) return
        val primaryId = selectedId
        // Live translation of a group move, in canvas units (null outside a group MOVE preview).
        val groupDelta: Pair<Double, Double>? =
            if (groupDrag && selectedIds.size > 1 && interaction.previewBox != null) {
                primaryId?.let { pid ->
                    engine.layout.byId(pid)?.let { pri ->
                        val pb = interaction.previewBox!!
                        (pb.x - pri.x) to (pb.y - pri.y)
                    }
                }
            } else {
                null
            }
        for (id in selectedIds.toList()) {
            val el = engine.layout.byId(id) ?: continue
            val isPrimary = id == primaryId
            val committed = InteractionController.Box(el.x, el.y, el.width, el.height)
            val box =
                if (isPrimary) {
                    interaction.previewBox ?: committed
                } else {
                    val d = groupDelta
                    if (d != null) {
                        InteractionController.Box(el.x + d.first, el.y + d.second, el.width, el.height)
                    } else {
                        committed
                    }
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
