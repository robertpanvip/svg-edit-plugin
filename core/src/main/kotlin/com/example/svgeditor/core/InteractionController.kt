package com.example.svgeditor.core

/**
 * Mouse-interaction state machine for the editor panel.
 *
 * Interaction model (a conventional vector editor, Leafier-style):
 *  - Hover: pointer over an element highlights it (thin accent outline).
 *  - Select (single or double click): an element becomes "selected" and shows an edit box
 *    with 8 control points plus a rotate handle above it.
 *  - Drag the body of a selected element: move it.
 *  - Drag a control point: resize it, anchored on the opposite corner.
 *  - Click empty space: deselect.
 *  - While dragging, edges/centres snap to other elements and guide lines are reported.
 *
 * The controller is pure (no Swing/IntelliJ dependency) so it is unit-testable on a plain JVM.
 * All coordinates are SVG/canvas units (the panel converts pointer pixels to these before
 * calling in, and the hit-tolerance is passed in the same units).
 */
class InteractionController {
    enum class State { IDLE, DRAG }
    enum class EditMode { NONE, MOVE, RESIZE, ROTATE }
    enum class Handle { NW, N, NE, E, SE, S, SW, W }

    /** Axis-aligned box in SVG/canvas units. */
    data class Box(
        val x: Double,
        val y: Double,
        val w: Double,
        val h: Double,
    )

    /** A snap guideline at SVG-unit coordinate `pos` (vertical = an x line, else a y line). */
    data class SnapLine(val vertical: Boolean, val pos: Double)

    sealed class EditResult {
        data class Move(
            val element: SvgElement,
            val dx: Double,
            val dy: Double,
        ) : EditResult()

        data class Resize(
            val element: SvgElement,
            val x: Double,
            val y: Double,
            val w: Double,
            val h: Double,
        ) : EditResult()

        /**
         * Rotation about a canvas-space point `(cx, cy)` by `angle` degrees (relative delta from
         * the grab angle). The centre does NOT move, so the rotation is idempotent about it.
         */
        data class Rotate(
            val element: SvgElement,
            val angle: Double,
            val cx: Double,
            val cy: Double,
        ) : EditResult()
    }

    var state: State = State.IDLE
        private set
    var pointerX: Double = 0.0
        private set
    var pointerY: Double = 0.0
        private set
    var hovered: SvgElement? = null
        private set
    var selected: SvgElement? = null
        internal set
    var selectedHandle: Handle? = null
        internal set
    var previewBox: Box? = null
        internal set
    var snapLines: List<SnapLine> = emptyList()
        internal set
    var editMode: EditMode = EditMode.NONE
        private set
    var previewAngle: Double = 0.0
        internal set
    private var rotateCenterX = 0.0
    private var rotateCenterY = 0.0
    private var rotateStartPointerAngle = 0.0

    private var dragStartX = 0.0
    private var dragStartY = 0.0
    private var startBox: Box? = null
    private var resizeHandle: Handle? = null

    private val handles: List<Handle> = Handle.entries.toList()
    private val snapThreshold = 4.0 // svg units
    /** Engaged snap targets (absolute svg-unit positions) for hysteresis between frames. */
    private var snapXTarget: Double? = null
    private var snapYTarget: Double? = null

    /** Pointer moved with no button pressed: refresh hover (+ which handle is under it). */
    fun onHoverMove(
        layout: SvgLayout,
        x: Double,
        y: Double,
        tol: Double = 6.0,
    ): SvgElement? {
        pointerX = x
        pointerY = y
        if (state == State.DRAG) return null
        hovered = CollisionDetector.hitTest(layout, x, y)
        selectedHandle = selected?.let { handleAt(it, x, y, tol) }
        return hovered
    }

    /** Double-click: enter edit mode on the element under the cursor (or deselect). */
    fun onDoubleClick(
        layout: SvgLayout,
        x: Double,
        y: Double,
    ): Boolean {
        snapLines = emptyList()
        val hit = CollisionDetector.hitTest(layout, x, y)
        return if (hit != null) {
            selected = hit
            selectedHandle = null
            editMode = EditMode.NONE
            state = State.IDLE
            previewAngle = 0.0
            true
        } else {
            selected = null
            selectedHandle = null
            false
        }
    }

    /** Begin a rotation drag: the pointer is on the rotate handle above the selection box. */
    fun startRotate(
        centerX: Double,
        centerY: Double,
        pointerAngleDeg: Double,
    ) {
        snapLines = emptyList()
        snapXTarget = null
        snapYTarget = null
        selectedHandle = null
        editMode = EditMode.ROTATE
        state = State.DRAG
        rotateCenterX = centerX
        rotateCenterY = centerY
        rotateStartPointerAngle = pointerAngleDeg
        previewAngle = 0.0
        startBox = selected?.let { boxOf(it) }
        previewBox = selected?.let { boxOf(it) }
    }

    /**
     * Button pressed. Selection rules:
     *  - If an element is already selected and the pointer is on one of its handles → start a
     *    RESIZE drag.
     *  - Else if on the selected element's body → start a MOVE drag.
     *  - Else if another element is under the pointer → select it (no drag).
     *  - Else → deselect.
     */
    fun onMousePressed(
        layout: SvgLayout,
        x: Double,
        y: Double,
        tol: Double = 6.0,
    ): Boolean {
        pointerX = x
        pointerY = y
        snapLines = emptyList()
        snapXTarget = null
        snapYTarget = null
        if (selected != null) {
            val h = handleAt(selected!!, x, y, tol)
            if (h != null) {
                editMode = EditMode.RESIZE
                resizeHandle = h
                startBox = boxOf(selected!!)
                dragStartX = x
                dragStartY = y
                state = State.DRAG
                previewBox = null
                return true
            }
            if (selected!!.contains(x, y)) {
                editMode = EditMode.MOVE
                startBox = boxOf(selected!!)
                dragStartX = x
                dragStartY = y
                state = State.DRAG
                previewBox = null
                return true
            }
        }
        val hit = CollisionDetector.hitTest(layout, x, y)
        return if (hit != null) {
            // Leafier/Figma-style: pressing on an element immediately starts a MOVE drag.
            // If the user releases without moving, onMouseReleased sees a null previewBox and
            // simply leaves the element selected (behaves like a plain click-to-select).
            selected = hit
            selectedHandle = null
            editMode = EditMode.MOVE
            startBox = boxOf(hit)
            dragStartX = x
            dragStartY = y
            state = State.DRAG
            previewBox = null
            true
        } else {
            selected = null
            selectedHandle = null
            editMode = EditMode.NONE
            state = State.IDLE
            false
        }
    }

    /** Pointer moved with the button pressed: update drag delta / preview box (+ snap). */
    fun onDragMove(
        _layout: SvgLayout,
        x: Double,
        y: Double,
    ): EditResult? {
        pointerX = x
        pointerY = y
        if (state != State.DRAG) return null
        val sb = startBox ?: return null
        val sel = selected ?: return null
        return when (editMode) {
            EditMode.MOVE -> {
                val dx = x - dragStartX
                val dy = y - dragStartY
                val raw = Box(sb.x + dx, sb.y + dy, sb.w, sb.h)
                val (snapped, lines) = applySnap(_layout, sel, raw, EditMode.MOVE, null)
                previewBox = snapped
                snapLines = lines
                EditResult.Move(sel, snapped.x - sb.x, snapped.y - sb.y)
            }
            EditMode.RESIZE -> {
                val dx = x - dragStartX
                val dy = y - dragStartY
                val raw = computeResize(sb, resizeHandle!!, dx, dy)
                val (snapped, lines) = applySnap(_layout, sel, raw, EditMode.RESIZE, resizeHandle)
                previewBox = snapped
                snapLines = lines
                EditResult.Resize(sel, snapped.x, snapped.y, snapped.w, snapped.h)
            }
            EditMode.ROTATE -> {
                val cur = kotlin.math.atan2(y - rotateCenterY, x - rotateCenterX) * 180.0 / Math.PI
                var delta = cur - rotateStartPointerAngle
                while (delta > 180) delta -= 360.0
                while (delta <= -180) delta += 360.0
                previewAngle = delta
                previewBox = boxOf(sel)
                snapLines = emptyList()
                EditResult.Rotate(sel, previewAngle, rotateCenterX, rotateCenterY)
            }
            EditMode.NONE -> null
        }
    }

    /** Button released: return the committed edit (or null). */
    fun onMouseReleased(): EditResult? {
        snapLines = emptyList()
        if (state == State.DRAG && selected != null && previewBox != null) {
            val res =
                when (editMode) {
                    EditMode.MOVE ->
                        EditResult.Move(
                            selected!!,
                            previewBox!!.x - startBox!!.x,
                            previewBox!!.y - startBox!!.y,
                        )
                    EditMode.RESIZE ->
                        EditResult.Resize(
                            selected!!,
                            previewBox!!.x,
                            previewBox!!.y,
                            previewBox!!.w,
                            previewBox!!.h,
                        )
                    EditMode.ROTATE ->
                        EditResult.Rotate(selected!!, previewAngle, rotateCenterX, rotateCenterY)
                    EditMode.NONE -> null
                }
            state = State.IDLE
            editMode = EditMode.NONE
            resizeHandle = null
            hovered = null
            previewBox = null
            previewAngle = 0.0
            return res
        }
        state = State.IDLE
        editMode = EditMode.NONE
        resizeHandle = null
        previewBox = null
        return null
    }

    /** Current accumulated drag delta (svg units), useful for move previews. */
    val dragDelta: Pair<Double, Double> get() = (pointerX - dragStartX) to (pointerY - dragStartY)

    // ---- helpers ---------------------------------------------------------

    private fun boxOf(el: SvgElement) = Box(el.x, el.y, el.width, el.height)

    /** SVG-unit coordinate of a handle on box `b`. */
    fun handlePoint(
        b: Box,
        h: Handle,
    ): Pair<Double, Double> =
        when (h) {
            Handle.NW -> b.x to b.y
            Handle.N -> (b.x + b.w / 2) to b.y
            Handle.NE -> b.x + b.w to b.y
            Handle.E -> b.x + b.w to (b.y + b.h / 2)
            Handle.SE -> b.x + b.w to b.y + b.h
            Handle.S -> (b.x + b.w / 2) to b.y + b.h
            Handle.SW -> b.x to b.y + b.h
            Handle.W -> b.x to (b.y + b.h / 2)
        }

    private fun handleAt(
        el: SvgElement,
        x: Double,
        y: Double,
        tol: Double,
    ): Handle? {
        val b = boxOf(el)
        return handles.firstOrNull { h ->
            val (hx, hy) = handlePoint(b, h)
            kotlin.math.abs(hx - x) <= tol && kotlin.math.abs(hy - y) <= tol
        }
    }

    /** Compute the resized box, anchored on the corner/edge opposite `h`. */
    private fun computeResize(
        sb: Box,
        h: Handle,
        dx: Double,
        dy: Double,
    ): Box {
        val min = 1.0
        var x = sb.x
        var y = sb.y
        var w = sb.w
        var ht = sb.h
        when (h) {
            Handle.NW -> {
                x = sb.x + dx
                y = sb.y + dy
                w = sb.w - dx
                ht = sb.h - dy
            }
            Handle.N -> {
                y = sb.y + dy
                ht = sb.h - dy
            }
            Handle.NE -> {
                y = sb.y + dy
                w = sb.w + dx
                ht = sb.h - dy
            }
            Handle.E -> w = sb.w + dx
            Handle.SE -> {
                w = sb.w + dx
                ht = sb.h + dy
            }
            Handle.S -> ht = sb.h + dy
            Handle.SW -> {
                x = sb.x + dx
                w = sb.w - dx
                ht = sb.h + dy
            }
            Handle.W -> {
                x = sb.x + dx
                w = sb.w - dx
            }
        }
        // Keep the correct anchor edge fixed (not just the bottom-right corner) when the size
        // would drop below `min`. Each resize handle anchors a different corner/edge, so the
        // clamp must preserve that specific anchor — otherwise shrinking, say, the S handle past
        // `min` would flip the box to anchor at its bottom and the top would jump.
        when (h) {
            Handle.NW -> { // anchor = SE (right + bottom)
                if (w < min) { x = sb.x + sb.w - min; w = min }
                if (ht < min) { y = sb.y + sb.h - min; ht = min }
            }
            Handle.N -> { // anchor = bottom edge
                if (ht < min) { y = sb.y + sb.h - min; ht = min }
            }
            Handle.NE -> { // anchor = SW (left + bottom)
                if (w < min) { x = sb.x; w = min }
                if (ht < min) { y = sb.y + sb.h - min; ht = min }
            }
            Handle.E -> { // anchor = left edge
                if (w < min) { x = sb.x; w = min }
            }
            Handle.SE -> { // anchor = NW (left + top)
                if (w < min) { x = sb.x; w = min }
                if (ht < min) { y = sb.y; ht = min }
            }
            Handle.S -> { // anchor = top edge
                if (ht < min) { y = sb.y; ht = min }
            }
            Handle.SW -> { // anchor = NE (right + top)
                if (w < min) { x = sb.x + sb.w - min; w = min }
                if (ht < min) { y = sb.y; ht = min }
            }
            Handle.W -> { // anchor = right edge
                if (w < min) { x = sb.x + sb.w - min; w = min }
            }
        }
        return Box(x, y, w, ht)
    }

    /**
     * Snap the box to other elements' edges (within [snapThreshold] svg units).
     *
     * The set of edges that may snap depends on the edit:
     *  - MOVE: any of the box's left/centre/right and top/middle/bottom edges — the whole box
     *    translates by the snap delta.
     *  - RESIZE: ONLY the edge(s) actually being dragged (the opposite corner/side is the
     *    anchor and must stay fixed). The snap delta is applied to the size on the moving side,
     *    never as a translation of the whole element — this is what prevents the element from
     *    "teleporting" while you resize a corner.
     *
     * A little hysteresis (a release band of 2×[snapThreshold]) keeps an engaged snap from
     * flickering on/off at the band boundary, which otherwise reads as a jump.
     */
    private fun applySnap(
        layout: SvgLayout,
        el: SvgElement,
        box: Box,
        mode: EditMode,
        handle: Handle?,
    ): Pair<Box, List<SnapLine>> {
        val others = layout.elements.filter { it.id != el.id && it.id.isNotBlank() }
        if (others.isEmpty()) {
            snapXTarget = null
            snapYTarget = null
            return box to emptyList()
        }

        // Which element edges are allowed to move (and therefore may snap) for this edit.
        val xEdges: List<(Box) -> Double>
        val yEdges: List<(Box) -> Double>
        when (mode) {
            EditMode.MOVE -> {
                xEdges = listOf({ b -> b.x }, { b -> b.x + b.w / 2 }, { b -> b.x + b.w })
                yEdges = listOf({ b -> b.y }, { b -> b.y + b.h / 2 }, { b -> b.y + b.h })
            }
            EditMode.RESIZE -> {
                xEdges =
                    when (handle) {
                        Handle.NW, Handle.W, Handle.SW -> listOf({ b -> b.x })
                        Handle.NE, Handle.E, Handle.SE -> listOf({ b -> b.x + b.w })
                        else -> emptyList()
                    }
                yEdges =
                    when (handle) {
                        Handle.NW, Handle.N, Handle.NE -> listOf({ b -> b.y })
                        Handle.SW, Handle.S, Handle.SE -> listOf({ b -> b.y + b.h })
                        else -> emptyList()
                    }
            }
            else -> {
                snapXTarget = null
                snapYTarget = null
                return box to emptyList()
            }
        }

        val release = snapThreshold * 2.0

        // X axis: prefer an already-engaged target while still within the wider release band.
        var bestX = snapThreshold
        var lineX: Double? = null
        if (xEdges.isNotEmpty()) {
            snapXTarget?.let { tgt ->
                val e = xEdges.first()(box)
                if (kotlin.math.abs(e - tgt) <= release) {
                    bestX = tgt - e
                    lineX = tgt
                }
            }
            if (lineX == null) {
                for (o in others) {
                    val ox = doubleArrayOf(o.x, o.x + o.width / 2, o.x + o.width)
                    for (tx in ox) for (sxF in xEdges) {
                        val d = tx - sxF(box)
                        if (kotlin.math.abs(d) < kotlin.math.abs(bestX)) {
                            bestX = d
                            lineX = tx
                        }
                    }
                }
            }
        }

        // Y axis.
        var bestY = snapThreshold
        var lineY: Double? = null
        if (yEdges.isNotEmpty()) {
            snapYTarget?.let { tgt ->
                val e = yEdges.first()(box)
                if (kotlin.math.abs(e - tgt) <= release) {
                    bestY = tgt - e
                    lineY = tgt
                }
            }
            if (lineY == null) {
                for (o in others) {
                    val oy = doubleArrayOf(o.y, o.y + o.height / 2, o.y + o.height)
                    for (ty in oy) for (syF in yEdges) {
                        val d = ty - syF(box)
                        if (kotlin.math.abs(d) < kotlin.math.abs(bestY)) {
                            bestY = d
                            lineY = ty
                        }
                    }
                }
            }
        }

        snapXTarget = lineX
        snapYTarget = lineY

        val newBox: Box
        if (mode == EditMode.MOVE) {
            val dx = if (lineX != null) bestX else 0.0
            val dy = if (lineY != null) bestY else 0.0
            newBox = Box(box.x + dx, box.y + dy, box.w, box.h)
        } else {
            // RESIZE: only the moving edge(s) shift; the anchor edge is preserved.
            var bx = box.x
            var by = box.y
            var bw = box.w
            var bh = box.h
            if (lineX != null) {
                when (handle) {
                    Handle.NW, Handle.W, Handle.SW -> {
                        bx = box.x + bestX
                        bw = box.w - bestX
                    }
                    Handle.NE, Handle.E, Handle.SE -> {
                        bw = box.w + bestX
                    }
                    else -> {}
                }
            }
            if (lineY != null) {
                when (handle) {
                    Handle.NW, Handle.N, Handle.NE -> {
                        by = box.y + bestY
                        bh = box.h - bestY
                    }
                    Handle.SW, Handle.S, Handle.SE -> {
                        bh = box.h + bestY
                    }
                    else -> {}
                }
            }
            if (bw <= 0.0 || bh <= 0.0) return box to emptyList() // snap would invert the box; skip
            newBox = Box(bx, by, bw, bh)
        }

        val lines = mutableListOf<SnapLine>()
        val lx = lineX
        val ly = lineY
        if (lx != null) lines.add(SnapLine(true, lx))
        if (ly != null) lines.add(SnapLine(false, ly))
        return newBox to lines
    }
}
