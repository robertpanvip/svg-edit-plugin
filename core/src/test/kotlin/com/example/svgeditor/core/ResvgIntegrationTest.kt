package com.example.svgeditor.core

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * End-to-end test that exercises the **real** `resvg_bridge` native library.
 *
 * It is skipped automatically when the native library has not been built (run
 * `cargo build` / `cargo build --release` in `native/resvg_bridge` first). This keeps the
 * pure-Kotlin suite green on machines without a Rust toolchain while still proving the
 * full render -> layout -> collision -> edit loop against resvg itself.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ResvgIntegrationTest {
    private lateinit var bridge: ResvgBridge

    @BeforeAll
    fun loadNative() {
        val lib = findNativeLibrary()
        Assumptions.assumeTrue(lib != null, "resvg_bridge native library not built; skipping (run cargo build in native/resvg_bridge)")
        bridge = ResvgBridge.load(lib!!.toString())
    }

    @Test
    fun `renders svg to a valid png`() {
        val r = bridge.render(Samples.SIMPLE)
        assertEquals(200, r.width)
        assertEquals(120, r.height)
        val magic = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        assertTrue(r.png.sliceArray(0..7).contentEquals(magic), "output should be a PNG")
    }

    @Test
    fun `extracts per-element layout from resvg`() {
        val layout = SvgLayout.parse(bridge.layoutJson(Samples.SIMPLE))
        assertEquals(200.0, layout.width)
        assertEquals(120.0, layout.height)
        val ids = layout.elements.map { it.id }
        assertTrue(ids.containsAll(listOf("bg", "box-a", "dot", "inner")))

        val boxA = layout.byId("box-a")!!
        assertEquals(10.0, boxA.x, 0.5)
        assertEquals(10.0, boxA.y, 0.5)
        assertEquals(80.0, boxA.width, 0.5)
        assertEquals(60.0, boxA.height, 0.5)

        // the group translates (120,80), so `inner` ends up at (120,80)
        val inner = layout.byId("inner")!!
        assertEquals(120.0, inner.x, 0.5)
        assertEquals(80.0, inner.y, 0.5)
    }

    @Test
    fun `engine hit-tests using resvg layout`() {
        val engine = SvgEditorEngine(bridge)
        engine.load(Samples.SIMPLE)
        assertEquals("box-a", CollisionDetector.hitTest(engine.layout, 50.0, 40.0)?.id)
        assertEquals("dot", CollisionDetector.hitTest(engine.layout, 150.0, 60.0)?.id)
    }

    @Test
    fun `engine can move an element and re-render`() {
        val engine = SvgEditorEngine(bridge)
        engine.load(Samples.SIMPLE)
        assertTrue(engine.moveElement("box-a", 30.0, 0.0))
        // after prepending translate(30,0) to box-a the absolute x shifts by 30
        assertEquals(40.0, engine.layout.byId("box-a")!!.x, 0.5)
        assertTrue(engine.svg.contains("""transform="translate(30, 0)""""))
    }

    @Test
    fun `renders at the requested device size (crisp, no upscaling)`() {
        val r = bridge.render(Samples.SIMPLE, 400, 240)
        assertEquals(400, r.width)
        assertEquals(240, r.height)
    }

    @Test
    fun `engine can resize an element via setElementBox`() {
        val engine = SvgEditorEngine(bridge)
        engine.load(Samples.SIMPLE)
        val before = engine.layout.byId("box-a")!!
        assertTrue(engine.setElementBox("box-a", before.x, before.y, before.width * 2, before.height * 2))
        val after = engine.layout.byId("box-a")!!
        // width should roughly double (resvg rounding)
        assertTrue(after.width > before.width * 1.5, "width ${after.width} not ~2x ${before.width}")
        assertTrue(engine.svg.contains("matrix("), "resize should emit a matrix(...) transform")
    }

    private fun findNativeLibrary(): Path? {
        val base = Paths.get(System.getProperty("user.dir"), "..", "native", "resvg_bridge", "target")
        val names = listOf("resvg_bridge.dll", "libresvg_bridge.so", "libresvg_bridge.dylib")
        for (profile in listOf("debug", "release")) {
            for (name in names) {
                val p = base.resolve(profile).resolve(name)
                if (Files.exists(p)) return p
            }
        }
        return null
    }
}
