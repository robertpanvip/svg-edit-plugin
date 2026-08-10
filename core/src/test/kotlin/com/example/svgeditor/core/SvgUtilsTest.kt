package com.example.svgeditor.core

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class SvgUtilsTest {
    private val svg = Samples.SIMPLE

    @Test
    fun `inserts a transform on an element without one`() {
        val out = SvgUtils.applyTranslate(svg, "box-a", 5.0, 7.0)
        assertTrue(out.contains("""id="box-a""""))
        assertTrue(out.contains("""transform="translate(5, 7)""""))
        // tag must remain well-formed
        assertTrue(out.contains("<rect"))
    }

    @Test
    fun `prepends translate to an existing transform`() {
        val out = SvgUtils.applyTranslate(svg, "grp", 0.0, 0.0)
        assertTrue(out.contains("""transform="translate(0, 0) translate(120,80)""""))
    }

    @Test
    fun `chained moves accumulate into the transform`() {
        val once = SvgUtils.applyTranslate(svg, "inner", 3.0, 4.0)
        val twice = SvgUtils.applyTranslate(once, "inner", 10.0, 20.0)
        assertTrue(twice.contains("""transform="translate(10, 20) translate(3, 4)""""))
    }

    @Test
    fun `no-op when id is absent`() {
        val out = SvgUtils.applyTranslate(svg, "nope", 1.0, 2.0)
        assertEquals(svg, out)
    }

    @Test
    fun `fmt drops trailing zeros`() {
        assertEquals("5", SvgUtils.fmt(5.0))
        assertEquals("7", SvgUtils.fmt(7.0))
        assertTrue(SvgUtils.fmt(3.12340001).matches(Regex("3(\\.\\d+)?")))
    }

    @Test
    fun `soloElement keeps the ancestor group for a nested element`() {
        // `inner` lives inside <g id="grp" transform="translate(120,80)">. Soloing it must keep
        // that ancestor group (so the element keeps its absolute position) and must NOT keep the
        // unrelated elements (bg / box-a / dot).
        val solo = SvgUtils.soloElement(svg, "inner")
        assertTrue(solo.startsWith("<svg"), "solo must keep the <svg> root")
        assertTrue(solo.trimEnd().endsWith("</svg>"), "solo must be closed")
        assertTrue(solo.contains("""id="grp""""), "ancestor group must be kept")
        assertTrue(solo.contains("translate(120,80)"), "ancestor transform must be preserved")
        assertTrue(solo.contains("""id="inner""""), "target element must be kept")
        assertFalse(solo.contains("box-a"), "unrelated element must be dropped")
        assertFalse(solo.contains(""""id="dot""""), "unrelated element must be dropped")
        assertFalse(solo.contains(""""id="bg""""), "background must be dropped")
    }

    @Test
    fun `soloElement for a top-level element drops everything else`() {
        val solo = SvgUtils.soloElement(svg, "box-a")
        assertTrue(solo.contains("""id="box-a""""))
        assertFalse(solo.contains("""id="grp""""), "other groups must be dropped")
        assertFalse(solo.contains("""id="inner""""), "other elements must be dropped")
        assertFalse(solo.contains(""""id="dot""""))
    }

    @Test
    fun `soloElement for a group keeps its descendants`() {
        // Selecting the group must keep its child `inner` so the whole group is dragged.
        val solo = SvgUtils.soloElement(svg, "grp")
        assertTrue(solo.contains("""id="grp""""))
        assertTrue(solo.contains("""id="inner""""), "group's child must be kept")
        assertFalse(solo.contains("box-a"))
    }
}
