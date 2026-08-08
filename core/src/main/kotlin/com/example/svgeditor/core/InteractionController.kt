package com.example.svgeditor.core

/**
 * Mouse-interaction state machine for the editor panel.
 *
 * Interaction model (a conventional vector editor):
 *  - Hover: pointer over an element highlights it.
 *  - Select (single or double click): an element becomes "selected" and shows 8 control
 *    points (handles) around its bounding box. Double-click also selects (enters edit mode).
 *  - Drag the body of a selected element: move it.
 *  - Drag a control point (handle): resize it, anchored on the opposite corner.
 *  - Click empty space: deselect.
 *
 * The controller is pure (no Swing/IntelliJ dependency) so it is unit-testable on a plain JVM.
 * All coordinates are SVG/canvas units (the panel converts pointer pixels to these before
 * calling in, and the hit-tolerance is passed in the same units).
 */
class InteractionController {
    enum class State { IDLE, DRAG }
    enum class EditMode { NONE, MOVE, RESIZE }
    enum class Handle { NW, N, NE, E, SE, S, SW, W }

    /** Axis-aligned box in SVG/canvas units. */
    data class Box(
        val x: Double,
        val y: Double,
        val w: Double,
        val h: Double,
    )

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
    var editMode: EditMode = EditMode.NONE
        private set

    private var dragStartX = 0.0
    private var dragStartY = 0.0
    private var startBox: Box? = null
    private var resizeHandle: Handle? = null

    private val handles: List<Handle> = Handle.entries.toList()

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
        val hit = CollisionDetector.hitTest(layout, x, y)
        return if (hit != null) {
            selected = hit
            selectedHandle = null
            editMode = EditMode.NONE
            state = State.IDLE
            true
        } else {
            selected = null
            selectedHandle = null
            false
        }
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
            selected = hit
            selectedHandle = null
            editMode = EditMode.NONE
            state = State.IDLE
            true
        } else {
            selected = null
            selectedHandle = null
            editMode = EditMode.NONE
            state = State.IDLE
            false
        }
    }

    /** Pointer moved with the button pressed: update the drag delta / preview box. */
    fun onDragMove(
        _layout: SvgLayout,
        x: Double,
        y: Double,
    ): EditResult? {
        pointerX = x
        pointerY = y
        if (state != State.DRAG) return null
        val sb = startBox ?: return null
        val dx = x - dragStartX
        val dy = y - dragStartY
        return when (editMode) {
            EditMode.MOVE -> {
                previewBox = Box(sb.x + dx, sb.y + dy, sb.w, sb.h)
                EditResult.Move(selected!!, dx, dy)
            }
            EditMode.RESIZE -> {
                val b = computeResize(sb, resizeHandle!!, dx, dy)
                previewBox = b
                EditResult.Resize(selected!!, b.x, b.y, b.w, b.h)
            }
            EditMode.NONE -> null
        }
    }

    /** Button released: return the committed edit (or null). */
    fun onMouseReleased(): EditResult? {
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
                    EditMode.NONE -> null
                }
            state = State.IDLE
            editMode = EditMode.NONE
            resizeHandle = null
            hovered = null
            previewBox = null
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
}
