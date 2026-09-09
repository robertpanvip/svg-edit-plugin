package com.pan.svg.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.Component
import java.awt.Cursor
import java.awt.event.MouseEvent

/**
 * Exercises [SvgEditorPanel]'s real AWT input path: events are built as genuine [MouseEvent]s
 * and pushed through `canvas.dispatchEvent(...)`, so they flow through the listeners installed
 * by [installMouse] (hover -> press -> drag -> release -> double-click) exactly as they would
 * from a physical mouse. This is deliberately separate from the `debug*` hooks, which call the
 * `handle*` methods directly and never touch the dispatcher.
 *
 * The view is pinned with [SvgEditorPanel.debugSetViewportSize] so that panel pixels equal SVG
 * coordinates (scale 1, origin 0), making coordinate assertions straightforward.
 */
class MouseEventDispatchTest {
    private fun move(
        c: Component,
        x: Int,
        y: Int,
    ) = MouseEvent(c, MouseEvent.MOUSE_MOVED, 0, 0, x, y, 0, false)

    private fun press(
        c: Component,
        x: Int,
        y: Int,
    ) = MouseEvent(c, MouseEvent.MOUSE_PRESSED, 0, MouseEvent.BUTTON1_DOWN_MASK, x, y, 1, false, MouseEvent.BUTTON1)

    private fun drag(
        c: Component,
        x: Int,
        y: Int,
    ) = MouseEvent(c, MouseEvent.MOUSE_DRAGGED, 0, MouseEvent.BUTTON1_DOWN_MASK, x, y, 0, false, MouseEvent.BUTTON1)

    private fun release(
        c: Component,
        x: Int,
        y: Int,
    ) = MouseEvent(c, MouseEvent.MOUSE_RELEASED, 0, 0, x, y, 1, false, MouseEvent.BUTTON1)

    private fun doubleClick(
        c: Component,
        x: Int,
        y: Int,
    ) = MouseEvent(c, MouseEvent.MOUSE_CLICKED, 0, 0, x, y, 2, false, MouseEvent.BUTTON1)

    private fun newPanel(): SvgEditorPanel {
        val p = SvgEditorPanel(FakeSvgRenderer())
        p.loadSvg(Samples.SIMPLE)
        // Pin a fixed viewport so the fit-to-view transform is deterministic (the canvas pad
        // means it is not exactly scale 1, but it is stable across runs).
        p.debugSetViewportSize(200, 120)
        return p
    }

    @Test
    fun `MOVED over an element shows the move cursor`() {
        val panel = newPanel()
        val c = panel.debugCanvas()
        val s = panel.debugElementCenterPx("box-a")!!
        c.dispatchEvent(move(c, s.x, s.y))
        assertEquals(Cursor.MOVE_CURSOR, c.cursor.type)
        panel.dispose()
    }

    @Test
    fun `MOVED over empty canvas restores the default cursor`() {
        val panel = newPanel()
        val c = panel.debugCanvas()
        val s = panel.debugElementCenterPx("box-a")!!
        c.dispatchEvent(move(c, s.x, s.y))
        assertEquals(Cursor.MOVE_CURSOR, c.cursor.type)
        c.dispatchEvent(move(c, 320, 320))
        assertEquals(Cursor.DEFAULT_CURSOR, c.cursor.type)
        panel.dispose()
    }

    @Test
    fun `MOVE press-drag-release edits the element`() {
        val panel = newPanel()
        val c = panel.debugCanvas()
        val s = panel.debugElementCenterPx("box-a")!!
        val preDrag = panel.svgSource
        // Double-click first to enter edit mode on box-a (matching the other tool tests).
        c.dispatchEvent(doubleClick(c, s.x, s.y))
        assertEquals("box-a", panel.selectedElementId)
        c.dispatchEvent(press(c, s.x, s.y))
        c.dispatchEvent(drag(c, s.x + 40, s.y + 40))
        c.dispatchEvent(release(c, s.x + 40, s.y + 40))
        // A committed move is written back to the SVG as a `translate(dx,dy)` on the element.
        // `debugElementCenterPx` would NOT see it (it ignores the element's own transform), so
        // assert on the exported source instead; snap may round the exact dx/dy.
        val after = panel.svgSource
        val m = Regex("id=\"box-a\"[^>]*transform=\"translate\\(([-\\d.]+),\\s*([-\\d.]+)\\)\"")
            .find(after)
        assertTrue(after != preDrag, "drag should change the exported SVG")
        assertTrue(m != null, "expected a translate on box-a, got: $after")
        assertTrue(m!!.groupValues[1].toDouble() > 0, "expected box-a to move right, got ${m.groupValues}")
        assertTrue(m.groupValues[2].toDouble() > 0, "expected box-a to move down, got ${m.groupValues}")
        panel.dispose()
    }

    @Test
    fun `MARQUEE drag selects without moving`() {
        val panel = newPanel()
        val c = panel.debugCanvas()
        panel.setTool(SvgEditorPanel.Tool.MARQUEE)
        val before = panel.svgSource
        val s = panel.debugElementCenterPx("box-a")!!
        c.dispatchEvent(press(c, 30, 20))
        c.dispatchEvent(drag(c, s.x + 40, s.y + 40))
        c.dispatchEvent(release(c, s.x + 40, s.y + 40))
        assertEquals("box-a", panel.selectedElementId)
        assertEquals(before, panel.svgSource) // No move/resize commit.
        panel.dispose()
    }

    @Test
    fun `MARQUEE click selects the exact element under the pointer`() {
        val panel = newPanel()
        val c = panel.debugCanvas()
        panel.setTool(SvgEditorPanel.Tool.MARQUEE)
        val d = panel.debugElementCenterPx("dot")!!
        c.dispatchEvent(press(c, d.x, d.y))
        c.dispatchEvent(release(c, d.x, d.y))
        assertEquals("dot", panel.selectedElementId)
        panel.dispose()
    }

    @Test
    fun `MOVE click on empty canvas deselects`() {
        val panel = newPanel()
        val c = panel.debugCanvas()
        val s = panel.debugElementCenterPx("box-a")!!
        c.dispatchEvent(doubleClick(c, s.x, s.y))
        assertEquals("box-a", panel.selectedElementId)
        val before = panel.svgSource
        c.dispatchEvent(press(c, 320, 320))
        c.dispatchEvent(release(c, 320, 320))
        assertNull(panel.selectedElementId)
        assertEquals(before, panel.svgSource)
        panel.dispose()
    }
}