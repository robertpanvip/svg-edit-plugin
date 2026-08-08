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
}
