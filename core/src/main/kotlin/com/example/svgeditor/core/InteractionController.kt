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
                val (snapped, lines) = applySnap(_layout, sel, raw)
                previewBox = snapped
                snapLines = lines
                EditResult.Move(sel, snapped.x - sb.x, snapped.y - sb.y)
            }
            EditMode.RESIZE -> {
                val dx = x - dragStartX
                val dy = y - dragStartY
                val raw = computeResize(sb, resizeHandle!!, dx, dy)
                val (snapped, lines) = applySnap(_layout, sel, raw)
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
                ht = sb.h
            }
            Handle.W -> {
                x = sb.x + dx
                w = sb.w - dx
            }
        }
        if (w < min) {
            x = sb.x + sb.w - min
            w = min
        }
        if (ht < min) {
            y = sb.y + sb.h - min
            ht = min
        }
        return Box(x, y, w, ht)
    }

    /**
     * Snap the box's left/centre/right edges and top/middle/bottom edges to the corresponding
     * edges of other elements (within [snapThreshold] svg units). Returns the corrected box
     * plus the guide lines to draw.
     */
    private fun applySnap(
        layout: SvgLayout,
        el: SvgElement,
        box: Box,
    ): Pair<Box, List<SnapLine>> {
        val others = layout.elements.filter { it.id != el.id && it.id.isNotBlank() }
        if (others.isEmpty()) return box to emptyList()

        val selX = doubleArrayOf(box.x, box.x + box.w / 2, box.x + box.w)
        val selY = doubleArrayOf(box.y, box.y + box.h / 2, box.y + box.h)
        var bestX = snapThreshold
        var bestY = snapThreshold
        var lineX: Double? = null
        var lineY: Double? = null
        for (o in others) {
            val ox = doubleArrayOf(o.x, o.x + o.width / 2, o.x + o.width)
            val oy = doubleArrayOf(o.y, o.y + o.height / 2, o.y + o.height)
            for (tx in ox) for (sx in selX) {
                val d = tx - sx
                if (kotlin.math.abs(d) < kotlin.math.abs(bestX)) {
                    bestX = d
                    lineX = tx
                }
            }
            for (ty in oy) for (sy in selY) {
                val d = ty - sy
                if (kotlin.math.abs(d) < kotlin.math.abs(bestY)) {
                    bestY = d
                    lineY = ty
                }
            }
        }
        val dx = if (lineX != null) bestX else 0.0
        val dy = if (lineY != null) bestY else 0.0
        val newBox = Box(box.x + dx, box.y + dy, box.w, box.h)
        val lines = mutableListOf<SnapLine>()
        if (lineX != null) lines.add(SnapLine(true, lineX))
        if (lineY != null) lines.add(SnapLine(false, lineY))
        return newBox to lines
    }
}
