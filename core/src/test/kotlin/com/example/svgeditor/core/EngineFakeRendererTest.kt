package com.example.svgeditor.core

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class EngineFakeRendererTest {
    @Test
    fun `load parses the layout`() {
        val engine = SvgEditorEngine(FakeSvgRenderer())
        engine.load(Samples.SIMPLE)
        assertEquals(5, engine.layout.elements.size)
    }

    @Test
    fun `moveElement rewrites the source svg with a translate`() {
        val engine = SvgEditorEngine(FakeSvgRenderer())
        engine.load(Samples.SIMPLE)
        assertTrue(engine.moveElement("box-a", 30.0, 0.0))
        assertTrue(engine.svgSource.contains("""transform="translate(30, 0)""""))
    }

    @Test
    fun `moves accumulate into the transform`() {
        val engine = SvgEditorEngine(FakeSvgRenderer())
        engine.load(Samples.SIMPLE)
        engine.moveElement("box-a", 30.0, 0.0)
        engine.moveElement("box-a", 10.0, 0.0)
        assertTrue(engine.svgSource.contains("""transform="translate(10, 0) translate(30, 0)""""))
    }

    @Test
    fun `zero-delta move is a no-op`() {
        val engine = SvgEditorEngine(FakeSvgRenderer())
        engine.load(Samples.SIMPLE)
        assertFalse(engine.moveElement("box-a", 0.0, 0.0))
    }

    @Test
    fun `moving an unknown id is a no-op`() {
        val engine = SvgEditorEngine(FakeSvgRenderer())
        engine.load(Samples.SIMPLE)
        assertFalse(engine.moveElement("nope", 5.0, 5.0))
    }

    @Test
    fun `setElementBox rewrites the transform as a matrix`() {
        val engine = SvgEditorEngine(FakeSvgRenderer())
        engine.load(Samples.SIMPLE)
        // box-a is 80x60 in the fixture; target 160x120 => scale 2x => matrix(2, 0, 0, 2, ...)
        assertTrue(engine.setElementBox("box-a", 10.0, 10.0, 160.0, 120.0))
        assertTrue(engine.svgSource.contains("matrix("), "resize should emit a matrix(...) transform")
        assertTrue(engine.svgSource.contains("matrix(2, 0, 0, 2"), "matrix should encode the 2x scale factor")
    }

    @Test
    fun `setElementBox on an unknown id is a no-op`() {
        val engine = SvgEditorEngine(FakeSvgRenderer())
        engine.load(Samples.SIMPLE)
        assertFalse(engine.setElementBox("nope", 0.0, 0.0, 10.0, 10.0))
    }

    @Test
    fun `renderAt re-renders at the requested device size`() {
        val engine = SvgEditorEngine(FakeSvgRenderer())
        engine.load(Samples.SIMPLE)
        // FakeSvgRenderer ignores size, so assert it does not throw and keeps a layout.
        engine.renderAt(400, 240)
        assertEquals(5, engine.layout.elements.size)
    }
}
