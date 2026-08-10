package com.example.svgeditor.core

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class InteractionControllerTest {
    private val layout = SvgLayout.parse(Samples.LAYOUT_JSON)

    @Test
    fun `double-click selects the element under the cursor`() {
        val ctrl = InteractionController()
        val ok = ctrl.onDoubleClick(layout, 50.0, 40.0) // center of box-a
        assertTrue(ok)
        assertEquals("box-a", ctrl.selected?.id)
    }

    @Test
    fun `single press on an element selects it and starts a potential drag`() {
        val ctrl = InteractionController()
        val started = ctrl.onMousePressed(layout, 50.0, 40.0)
        assertTrue(started)
        assertEquals("box-a", ctrl.selected?.id)
        assertEquals(InteractionController.State.DRAG, ctrl.state)
        assertEquals(InteractionController.EditMode.MOVE, ctrl.editMode)
        // Releasing without moving leaves the element selected (click-to-select).
        val res = ctrl.onMouseReleased()
        assertNull(res)
        assertEquals(InteractionController.State.IDLE, ctrl.state)
        assertEquals("box-a", ctrl.selected?.id)
    }

    @Test
    fun `single click on empty space deselects`() {
        val ctrl = InteractionController()
        ctrl.onMousePressed(layout, 50.0, 40.0) // select box-a
        val started = ctrl.onMousePressed(layout, -10.0, -10.0)
        assertFalse(started)
        assertNull(ctrl.selected)
    }

    @Test
    fun `drag on a selected element moves it`() {
        val ctrl = InteractionController()
        ctrl.onDoubleClick(layout, 50.0, 40.0) // select box-a (edit mode)
        ctrl.onMousePressed(layout, 50.0, 40.0) // press on body -> MOVE drag
        assertEquals(InteractionController.State.DRAG, ctrl.state)
        ctrl.onDragMove(layout, 90.0, 80.0) // +40, +40
        val res = ctrl.onMouseReleased()
        assertTrue(res is InteractionController.EditResult.Move)
        res as InteractionController.EditResult.Move
        assertEquals("box-a", res.element.id)
        assertEquals(40.0, res.dx, 1e-9)
        assertEquals(40.0, res.dy, 1e-9)
        assertEquals(InteractionController.State.IDLE, ctrl.state)
    }

    @Test
    fun `drag on a handle resizes the element`() {
        val ctrl = InteractionController()
        ctrl.onDoubleClick(layout, 50.0, 40.0) // select box-a (box 10,10,80,60)
        // SE handle is at (90, 70); press there -> RESIZE drag
        ctrl.onMousePressed(layout, 90.0, 70.0)
        assertEquals(InteractionController.EditMode.RESIZE, ctrl.editMode)
        // drag +20, +20 -> width 80->100, height 60->80, anchored at (10,10)
        ctrl.onDragMove(layout, 110.0, 90.0)
        val res = ctrl.onMouseReleased()
        assertTrue(res is InteractionController.EditResult.Resize)
        res as InteractionController.EditResult.Resize
        assertEquals("box-a", res.element.id)
        assertEquals(10.0, res.x, 1e-9)
        assertEquals(10.0, res.y, 1e-9)
        assertEquals(100.0, res.w, 1e-9)
        assertEquals(80.0, res.h, 1e-9)
    }

    @Test
    fun `resize snaps only the dragged edge, keeping the anchor fixed`() {
        val ctrl = InteractionController()
        ctrl.onDoubleClick(layout, 50.0, 40.0) // select box-a (10,10,80,60)
        ctrl.onMousePressed(layout, 90.0, 70.0) // SE handle -> RESIZE (anchor = NW 10,10)
        // Drag so the bottom edge (raw y = 10 + 60 + dy) approaches inner's top edge (y = 80).
        // dy = 12 -> raw bottom = 82, within snapThreshold(4) of inner top(80). The bottom edge
        // should snap to 80, but the NW anchor must stay at (10,10) — no whole-element jump.
        ctrl.onDragMove(layout, 95.0, 82.0)
        val res = ctrl.onMouseReleased()
        assertTrue(res is InteractionController.EditResult.Resize)
        res as InteractionController.EditResult.Resize
        assertEquals(10.0, res.x, 1e-9) // anchor x unchanged
        assertEquals(10.0, res.y, 1e-9) // anchor y unchanged
        assertEquals(85.0, res.w, 1e-9) // SE moved +5 in x
        assertEquals(70.0, res.h, 1e-9) // bottom snapped 82 -> 80: 60 + 12 - 2
    }

    @Test
    fun `repro S handle dragged down keeps top fixed (grows downward)`() {
        val ctrl = InteractionController()
        ctrl.onDoubleClick(layout, 50.0, 40.0) // select box-a (10,10,80,60)
        ctrl.onMousePressed(layout, 50.0, 70.0) // S handle at (50,70)
        assertEquals(InteractionController.EditMode.RESIZE, ctrl.editMode)
        ctrl.onDragMove(layout, 50.0, 110.0) // dy = +40 (downward)
        val box = ctrl.previewBox!!
        println("REPRO S-down previewBox = $box")
        assertEquals(10.0, box.x, 1e-9)
        assertEquals(10.0, box.y, 1e-9) // top must NOT move up
        assertEquals(80.0, box.w, 1e-9)
        assertEquals(100.0, box.h, 1e-9)
    }

    @Test
    fun `repro SW handle dragged down updates height`() {
        val ctrl = InteractionController()
        ctrl.onDoubleClick(layout, 50.0, 40.0)
        ctrl.onMousePressed(layout, 10.0, 70.0) // SW handle at (10,70)
        assertEquals(InteractionController.EditMode.RESIZE, ctrl.editMode)
        ctrl.onDragMove(layout, 10.0, 110.0) // dx=0, dy=+40 (downward)
        val box = ctrl.previewBox!!
        println("REPRO SW-down previewBox = $box")
        assertEquals(10.0, box.x, 1e-9)
        assertEquals(10.0, box.y, 1e-9)
        assertEquals(80.0, box.w, 1e-9)
        assertEquals(100.0, box.h, 1e-9) // height MUST grow when dragging SW down
    }

    @Test
    fun `repro S handle downward near a lower neighbour still keeps top fixed`() {
        // Custom layout: box-a (10,10,80,60) with a neighbour just below it.
        val custom =
            SvgLayout.parse(
                """{"width":200.0,"height":300.0,"elements":[
            {"index":0,"id":"box-a","kind":"path","x":10.0,"y":10.0,"width":80.0,"height":60.0,"transform":[1,0,0,1,0,0]},
            {"index":1,"id":"below","kind":"path","x":10.0,"y":200.0,"width":80.0,"height":30.0,"transform":[1,0,0,1,0,0]}
          ]}""",
            )
        val ctrl = InteractionController()
        ctrl.onDoubleClick(custom, 50.0, 40.0) // select box-a
        ctrl.onMousePressed(custom, 50.0, 70.0) // S handle
        ctrl.onDragMove(custom, 50.0, 190.0) // dy = +120, bottom edge approaches 'below' top (y=200)
        val box = ctrl.previewBox!!
        println("REPRO S-down-neighbour previewBox = $box")
        assertEquals(10.0, box.x, 1e-9)
        assertEquals(10.0, box.y, 1e-9) // anchor top must stay put even when snapping the bottom edge
    }

    @Test
    fun `hover highlights the element under the pointer`() {
        val ctrl = InteractionController()
        assertEquals("dot", ctrl.onHoverMove(layout, 150.0, 60.0)?.id)
        assertEquals("bg", ctrl.onHoverMove(layout, 5.0, 5.0)?.id)
        assertNull(ctrl.onHoverMove(layout, -50.0, -50.0))
    }

    @Test
    fun `hover is ignored while dragging`() {
        val ctrl = InteractionController()
        ctrl.onDoubleClick(layout, 50.0, 40.0) // select
        ctrl.onMousePressed(layout, 50.0, 40.0) // start MOVE drag
        val h = ctrl.onHoverMove(layout, 5.0, 5.0) // a hover far away
        assertEquals(InteractionController.State.DRAG, ctrl.state)
        assertEquals("box-a", ctrl.selected?.id)
        assertNull(h) // hover logic is suppressed during drag
    }

    @Test
    fun `rotate handle drag rotates about the element centre`() {
        val ctrl = InteractionController()
        ctrl.onDoubleClick(layout, 50.0, 40.0) // select box-a (centre 50,40)
        // Start a rotation with the pointer to the right of the centre (angle 0 deg).
        ctrl.startRotate(50.0, 40.0, 0.0)
        assertEquals(InteractionController.EditMode.ROTATE, ctrl.editMode)
        assertEquals(InteractionController.State.DRAG, ctrl.state)
        // Swing the pointer to the bottom of the centre (angle 90 deg) -> +90 deg rotation.
        val res = ctrl.onDragMove(layout, 50.0, 140.0)
        assertTrue(res is InteractionController.EditResult.Rotate)
        res as InteractionController.EditResult.Rotate
        assertEquals(90.0, res.angle, 1e-6)
        assertEquals(50.0, res.cx, 1e-9)
        assertEquals(40.0, res.cy, 1e-9)
        assertEquals(90.0, ctrl.previewAngle, 1e-6)
        val rel = ctrl.onMouseReleased()
        assertTrue(rel is InteractionController.EditResult.Rotate)
        assertEquals(InteractionController.State.IDLE, ctrl.state)
        assertEquals(0.0, ctrl.previewAngle, 1e-9)
    }

    @Test
    fun `rotate angle is normalised across the 180 degree seam`() {
        val ctrl = InteractionController()
        ctrl.onDoubleClick(layout, 50.0, 40.0)
        ctrl.startRotate(50.0, 40.0, 170.0) // pointer near +170
        // Drag straight up: angle -90. Swing of -260 deg normalises to +100.
        val res = ctrl.onDragMove(layout, 50.0, -60.0)
        assertTrue(res is InteractionController.EditResult.Rotate)
        res as InteractionController.EditResult.Rotate
        assertEquals(100.0, res.angle, 1e-6)
    }
}
