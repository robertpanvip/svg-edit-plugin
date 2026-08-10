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

    @Test
    fun `prependRotate inserts rotate before an existing transform`() {
        val s = """<svg><rect id="a" transform="translate(10, 20)"/></svg>"""
        val out = SvgUtils.prependRotate(s, "a", 45.0, 5.0, 6.0)
        assertTrue(out.contains("""transform="rotate(45, 5, 6) translate(10, 20)""""), out)
    }

    @Test
    fun `prependRotate creates the transform attribute when absent`() {
        val s = """<svg><rect id="a"/></svg>"""
        val out = SvgUtils.prependRotate(s, "a", 30.0, 0.0, 0.0)
        assertTrue(out.contains("""transform="rotate(30, 0, 0)""""), out)
        assertTrue(out.startsWith("<svg") && out.trimEnd().endsWith("</svg>"))
    }

    @Test
    fun `parseTransformToMatrix parses common transforms`() {
        assertArrayEquals(
            doubleArrayOf(1.0, 0.0, 0.0, 1.0, 5.0, 7.0),
            SvgUtils.parseTransformToMatrix("translate(5,7)"),
            1e-12,
        )
        assertArrayEquals(
            doubleArrayOf(2.0, 0.0, 0.0, 2.0, 0.0, 0.0),
            SvgUtils.parseTransformToMatrix("scale(2)"),
            1e-12,
        )
        // rotate(90) about origin: cos90=0, sin90=1 -> [0, 1, -1, 0, 0, 0]
        assertArrayEquals(
            doubleArrayOf(0.0, 1.0, -1.0, 0.0, 0.0, 0.0),
            SvgUtils.parseTransformToMatrix("rotate(90 0 0)"),
            1e-12,
        )
        assertArrayEquals(
            doubleArrayOf(1.0, 0.0, 0.0, 1.0, 0.0, 0.0),
            SvgUtils.parseTransformToMatrix(""),
            1e-12,
        )
        // chained: translate(5,7) rotate(90) -> apply rotate first, then translate
        val chained = SvgUtils.parseTransformToMatrix("translate(5,7) rotate(90 0 0)")
        assertArrayEquals(
            doubleArrayOf(0.0, 1.0, -1.0, 0.0, 5.0, 7.0),
            chained,
            1e-12,
        )
    }

    @Test
    fun `affineInverse inverts a rotation`() {
        val rot = SvgUtils.parseTransformToMatrix("rotate(90 0 0)")
        val inv = SvgUtils.affineInverse(rot)
        assertArrayEquals(
            doubleArrayOf(0.0, -1.0, 1.0, 0.0, 0.0, 0.0),
            inv,
            1e-12,
        )
        // inverse of the inverse is the original
        assertArrayEquals(rot, SvgUtils.affineInverse(inv), 1e-12)
    }

    /**
     * The heart of the transform-aware move: prepending `P = E ∘ A⁻¹ ∘ T ∘ A ∘ E⁻¹` must make the
     * element's absolute transform become `Translate(dx,dy) ∘ A`. We verify `A' ∘ A⁻¹ == T` for a
     * top-level rotated element (where A == E), proving the committed element lands exactly at the
     * root-space drag delta (no local-space drift).
     */
    @Test
    fun `move prepend matrix yields a root-space translation for a rotated element`() {
        val e = SvgUtils.parseTransformToMatrix("rotate(35 50 40)")
        val a = e.copyOf() // top-level element: absolute == own transform
        val aInv = SvgUtils.affineInverse(a)
        val eInv = SvgUtils.affineInverse(e)
        val dx = 12.0
        val dy = -7.0
        val t = doubleArrayOf(1.0, 0.0, 0.0, 1.0, dx, dy)
        var p = eInv
        p = SvgUtils.affineMultiply(a, p)
        p = SvgUtils.affineMultiply(t, p)
        p = SvgUtils.affineMultiply(aInv, p)
        p = SvgUtils.affineMultiply(e, p)
        // New absolute A' = P ∘ E (top-level: G = identity).
        val aPrime = SvgUtils.affineMultiply(p, e)
        // Expect A' ∘ A⁻¹ == Translate(dx,dy).
        val composed = SvgUtils.affineMultiply(aPrime, aInv)
        assertEquals(1.0, composed[0], 1e-9)
        assertEquals(1.0, composed[3], 1e-9)
        assertEquals(0.0, composed[1], 1e-9)
        assertEquals(0.0, composed[2], 1e-9)
        assertEquals(dx, composed[4], 1e-6)
        assertEquals(dy, composed[5], 1e-6)
    }
}
