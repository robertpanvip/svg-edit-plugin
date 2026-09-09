package com.pan.svg.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Covers the structural source edits (delete / duplicate / layer reorder) plus the engine-level
 * entry points that back the keyboard-driven panel API. Assertions are made on the SVG **source**
 * — like the other engine tests, this stays deterministic even though the fake renderer reports a
 * fixed layout, because the tests never depend on post-edit geometry, only on the rewritten text.
 */
class SourceEditingTest {
    private val svg = Samples.SIMPLE

    // IdAwareSvgRenderer re-derives the layout from the source, so a freshly duplicated id is
    // present in the layout and downstream edits (e.g. the paste offset in moveElement) resolve it.
    private fun engine() =
        SvgEditorEngine(IdAwareSvgRenderer()).apply { loadLayoutOnly(Samples.SIMPLE) }

    /** Index of the element's own `id="..."` attribute in `text`, or -1. */
    private fun posOf(text: String, id: String) = text.indexOf("id=\"$id\"")

    // ---- SvgUtils.removeElement ---------------------------------------------------

    @Test
    fun `removeElement drops a self-closing element and its tag`() {
        val out = SvgUtils.removeElement(svg, "box-a")
        assertFalse(out.contains("id=\"box-a\""))
        assertTrue(out.contains("id=\"bg\"")) // neighbours untouched
        assertTrue(out.contains("id=\"dot\""))
    }

    @Test
    fun `removeElement removes a group and its whole subtree`() {
        val out = SvgUtils.removeElement(svg, "grp")
        assertFalse(out.contains("id=\"grp\""))
        assertFalse(out.contains("id=\"inner\"")) // nested child goes with the group
        assertTrue(out.contains("id=\"dot\""))
    }

    @Test
    fun `removeElement is a no-op for an unknown id`() {
        assertEquals(svg, SvgUtils.removeElement(svg, "nope"))
    }

    // ---- SvgUtils.duplicateElement -------------------------------------------------

    @Test
    fun `duplicateElement clones the element with a fresh unique id`() {
        val (out, newId) = SvgUtils.duplicateElement(svg, "box-a")!!
        assertTrue(newId != "box-a", "copy gets a new id")
        assertTrue(out.contains("id=\"$newId\" x=\"10\""), "copy keeps the element's attributes")
        assertTrue(out.contains("id=\"box-a\" x=\"10\""), "original untouched")
        assertTrue(posOf(out, newId) > posOf(out, "box-a"), "copy sits right after the original")
    }

    @Test
    fun `duplicateElement clones a group subtree and retags only the outer element`() {
        val (out, newId) = SvgUtils.duplicateElement(svg, "grp")!!
        assertTrue(out.contains("""id="$newId" transform="translate(120,80)""""))
        assertTrue(out.contains("""id="inner""""), "nested child keeps its own id")
        // The nested id is intentionally duplicated, so scope the check to the clone's own
        // subtree: everything from the new group's open tag through its close must be present.
        val fromClone = out.substring(out.lastIndexOf('<', posOf(out, newId)))
        assertTrue(fromClone.startsWith("""<g id="$newId""""), "clone's outer tag is the retagged group")
        assertTrue(fromClone.contains("""<rect id="inner""""), "cloned subtree keeps its nested child")
        assertTrue(fromClone.contains("</g>"), "cloned group is well formed and closed")
    }

    @Test
    fun `duplicateElement returns null for an unknown id`() {
        assertNull(SvgUtils.duplicateElement(svg, "nope"))
    }

    @Test
    fun `nextUniqueId avoids collisions`() {
        assertEquals("copy-of-box-a-1", SvgUtils.nextUniqueId(svg, "copy-of-box-a"))
        val out = SvgUtils.duplicateElement(svg, "box-a")!!.first
        assertEquals(1, out.split("copy-of-box-a-").size - 1)
    }

    // ---- SvgUtils.reorderElement ---------------------------------------------------

    @Test
    fun `reorder FRONT moves an element to the top of its siblings`() {
        val out = SvgUtils.reorderElement(svg, "box-a", SvgUtils.ReorderDir.FRONT)
        assertTrue(posOf(out, "box-a") > posOf(out, "grp"), out)
    }

    @Test
    fun `reorder BACK moves an element below its siblings`() {
        val out = SvgUtils.reorderElement(svg, "dot", SvgUtils.ReorderDir.BACK)
        assertTrue(posOf(out, "dot") < posOf(out, "bg"), out)
    }

    @Test
    fun `reorder FORWARD shifts by one position`() {
        val out = SvgUtils.reorderElement(svg, "box-a", SvgUtils.ReorderDir.FORWARD)
        // box-a was just before dot in source; forward makes dot come first
        assertTrue(posOf(out, "dot") < posOf(out, "box-a"), out)
    }

    @Test
    fun `reorder is a no-op when already at the back`() {
        assertEquals(svg, SvgUtils.reorderElement(svg, "bg", SvgUtils.ReorderDir.BACK))
    }

    // ---- engine entry points -------------------------------------------------------

    @Test
    fun `deleteElement removes the id from the source`() {
        val e = engine()
        assertTrue(e.deleteElement("box-a"))
        assertFalse(e.svgSource.contains("id=\"box-a\""))
        assertTrue(e.svgSource.contains("id=\"dot\""))
    }

    @Test
    fun `deleteElement is a no-op for an unknown id`() {
        val e = engine()
        assertFalse(e.deleteElement("nope"))
        assertEquals(Samples.SIMPLE, e.svgSource)
    }

    @Test
    fun `duplicateElement creates a translated copy and returns its id`() {
        val e = engine()
        val newId = e.duplicateElement("box-a", 15.0, 10.0)
        assertNotNull(newId)
        assertTrue(newId != "box-a", "copy gets a fresh id")
        assertTrue(e.svgSource.contains("id=\"$newId\""))
        assertTrue(e.svgSource.contains("translate(15, 10)")) // copy carries the paste offset
    }

    @Test
    fun `reorderElement rewrites the sibling order`() {
        val e = engine()
        assertTrue(e.reorderElement("box-a", SvgUtils.ReorderDir.FRONT))
        assertTrue(posOf(e.svgSource, "box-a") > posOf(e.svgSource, "grp"))
    }
}