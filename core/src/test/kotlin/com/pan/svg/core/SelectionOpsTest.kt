package com.pan.svg.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.event.KeyEvent

/**
 * Exercises the structural editing API the panel exposes for keyboard / layers / alignment:
 * select-all, copy/paste/delete, nudge, layer reorder and align/distribute. These call the same
 * public methods the key and toolbar handlers invoke, so they cover the interaction paths the
 * user asked to align with typical vector editors.
 *
 * Assertions are made on the exported SVG **source** (the movable element gains a `translate(...)`
 * / the document order changes). The panel is driven with [IdAwareSvgRenderer] so duplicated ids
 * also land in the layout and receive their paste offset.
 */
class SelectionOpsTest {
    private fun newPanel(): SvgEditorPanel {
        // IdAwareSvgRenderer re-derives the layout from the source, so freshly duplicated ids
        // land in the layout and receive their paste-offset translate (the static fake would
        // report them as unknown and drop the move).
        val p = SvgEditorPanel(IdAwareSvgRenderer())
        p.loadSvg(Samples.SIMPLE)
        p.debugSetViewportSize(200, 120)
        return p
    }

    private fun Transform(tag: String) = Regex("""transform="translate\(([-\d.]+),\s*([-\d.]+)\)"""")
    private fun translateOf(source: String, id: String): Pair<Double, Double>? {
        val tag = Regex("""<[^>]*id="\Q$id\E"[^>]*>""").find(source) ?: return null
        val m = Transform(tag.value).find(tag.value) ?: return null
        return m.groupValues[1].toDouble() to m.groupValues[2].toDouble()
    }

    private fun posOf(source: String, id: String) = source.indexOf("""id="$id"""")

    // ---- keyboard: select-all (Ctrl+A) / copy / paste / delete -------------------
    //
    // Headless AWT drops synthetic KeyEvents sent through Component.dispatchEvent (mouse events
    // do get delivered), so keyboard commands are exercised through the panel's onKeyPressed —
    // the exact method the canvas key listener forwards to, i.e. the same key-to-command mapping.

    private fun key(
        p: SvgEditorPanel,
        code: Int,
        modifiers: Int = 0,
    ): KeyEvent {
        val c = p.debugCanvas()
        return KeyEvent(c, KeyEvent.KEY_PRESSED, 0, modifiers, code, code.toChar())
    }

    private fun pressKey(
        p: SvgEditorPanel,
        code: Int,
        modifiers: Int = 0,
    ) = p.onKeyPressed(key(p, code, modifiers))

    @Test
    fun `Ctrl+A selects every id'd element`() {
        val panel = newPanel()
        pressKey(panel, KeyEvent.VK_A, KeyEvent.CTRL_DOWN_MASK)
        assertEquals(setOf("bg", "box-a", "dot", "grp", "inner"), panel.selectedElementIds.toSet())
        panel.dispose()
    }

    @Test
    fun `Ctrl+C then Ctrl+V duplicates the selection at an offset`() {
        val panel = newPanel()
        // Select box-a + dot, copy, then paste -> two copies selected on top of the originals.
        panel.debugSetSelection(listOf("box-a", "dot"))
        pressKey(panel, KeyEvent.VK_C, KeyEvent.CTRL_DOWN_MASK)
        pressKey(panel, KeyEvent.VK_V, KeyEvent.CTRL_DOWN_MASK)
        val sel = panel.selectedElementIds
        assertEquals(2, sel.size)
        assertTrue(sel.none { it == "box-a" || it == "dot" }, "pasted copies carry fresh ids: $sel")
        for (id in sel) {
            val t = translateOf(panel.svgSource, id)
            assertTrue(t != null && t.first > 0 && t.second > 0, "copy offset visible on $id, got $t")
        }
        panel.dispose()
    }

    @Test
    fun `DELETE removes the selected element from the source`() {
        val panel = newPanel()
        panel.debugSetSelection(listOf("dot"))
        pressKey(panel, KeyEvent.VK_DELETE)
        assertTrue(panel.selectedElementIds.isEmpty())
        assertTrue(!panel.svgSource.contains("""id="dot""""), "dot should be gone")
        assertTrue(panel.svgSource.contains("""id="box-a""""), "neighbours survive")
        panel.dispose()
    }

    @Test
    fun `arrow key nudges the selection by one canvas unit`() {
        val panel = newPanel()
        panel.debugSetSelection(listOf("dot"))
        pressKey(panel, KeyEvent.VK_RIGHT)
        // dot currently at x=120; a +1 nudge appears as translate(1, 0) in the source.
        assertEquals(1.0 to 0.0, translateOf(panel.svgSource, "dot"))
        panel.dispose()
    }

    // ---- layers: reorder ------------------------------------------------

    @Test
    fun `reorder FRONT brings the selection to the top of its siblings`() {
        val panel = newPanel()
        panel.debugSetSelection(listOf("dot"))
        panel.reorderSelection(SvgEditorPanel.Reorder.FRONT)
        assertTrue(
            posOf(panel.svgSource, "dot") > posOf(panel.svgSource, "grp"),
            "dot must draw above grp after going to front",
        )
        panel.dispose()
    }

    @Test
    fun `reorder BACK sends the selection to the bottom of its siblings`() {
        val panel = newPanel()
        panel.debugSetSelection(listOf("dot"))
        panel.reorderSelection(SvgEditorPanel.Reorder.BACK)
        assertTrue(
            posOf(panel.svgSource, "dot") < posOf(panel.svgSource, "bg"),
            "dot must draw below bg after going to back",
        )
        panel.dispose()
    }

    // ---- alignment ---------------------------------------------------------

    @Test
    fun `align LEFT snaps every selected element to the selection's left edge`() {
        val panel = newPanel()
        // box-a left=10, dot left=120. Aligning left moves dot to x=10 (dx=-110) and leaves box-a.
        panel.debugSetSelection(listOf("box-a", "dot"))
        panel.alignSelection(SvgEditorPanel.Align.LEFT)
        assertEquals(null, translateOf(panel.svgSource, "box-a"), "box-a is already on the left edge")
        assertEquals(-110.0 to 0.0, translateOf(panel.svgSource, "dot"))
        panel.dispose()
    }

    @Test
    fun `align TOP snaps every selected element to the selection's top edge`() {
        val panel = newPanel()
        // box-a top=10, dot top=30. Aligning top moves dot to y=10 (dy=-20).
        panel.debugSetSelection(listOf("box-a", "dot"))
        panel.alignSelection(SvgEditorPanel.Align.TOP)
        assertEquals(0.0 to -20.0, translateOf(panel.svgSource, "dot"))
        panel.dispose()
    }

    @Test
    fun `distribute HORIZONTAL requires at least three elements (no-op below three)`() {
        val panel = newPanel()
        val before = panel.svgSource
        panel.debugSetSelection(listOf("box-a", "dot"))
        panel.distributeSelection(SvgEditorPanel.Distribute.HORIZONTAL)
        assertEquals(before, panel.svgSource, "two elements cannot be distributed evenly")
        panel.dispose()
    }

    @Test
    fun `distribute VERTICAL spreads at least three selected elements evenly`() {
        val panel = newPanel()
        // Select box-a (top 10), dot (top 30) and inner (top 80): three stacked elements are
        // evenly distributed, so the middle one (dot) must move.
        panel.debugSetSelection(listOf("box-a", "dot", "inner"))
        panel.distributeSelection(SvgEditorPanel.Distribute.VERTICAL)
        val dt = translateOf(panel.svgSource, "dot")
        assertTrue(dt != null && dt.second != 0.0, "middle element moves to even the gaps, got $dt")
        panel.dispose()
    }
}