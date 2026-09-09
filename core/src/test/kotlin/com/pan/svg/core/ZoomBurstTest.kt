package com.pan.svg.core

import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.event.InputEvent
import java.awt.event.MouseEvent
import java.awt.event.MouseWheelEvent

/**
 * Ctrl+wheel zoom on a heavy document must render each burst notch at reduced resolution and
 * return to a full-resolution render once the burst ends, while light documents always render at
 * full resolution. The raster budget is verified through the renderViewport parameters the panel
 * sends to the sidecar (recorded on FakeSidecar by the panel's async render worker).
 */
class ZoomBurstTest {
    private val heavySvg =
        "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"200\" height=\"120\"><!--" +
            "x".repeat(100_000) +
            "--></svg>"

    private fun awaitTrue(
        what: String,
        timeoutMs: Long = 5_000,
        cond: () -> Boolean,
    ) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (cond()) return
            Thread.sleep(10)
        }
        throw AssertionError("timed out waiting for $what")
    }

    /** Snapshot of every recorded `renderViewport` param map, in call order. */
    private fun viewportCalls(fake: FakeSidecar): List<Map<String, Any?>> =
        synchronized(fake.calls) {
            fake.calls.filter { it.first == "renderViewport" }.map { it.second }
        }

    private fun vw(params: Map<String, Any?>): Int =
        (params["vw"] as? Number)?.toInt() ?: -1

    /** Dispatch one Ctrl+wheel zoom-out notch to the panel's canvas (synchronous handler). */
    private fun wheelNotch(canvas: java.awt.Component) {
        val e =
            MouseWheelEvent(
                canvas,
                MouseEvent.MOUSE_WHEEL,
                System.currentTimeMillis(),
                InputEvent.CTRL_DOWN_MASK,
                400,
                400,
                0,
                false,
                MouseWheelEvent.WHEEL_UNIT_SCROLL,
                1,
                1, // positive rotation = zoom out
            )
        canvas.dispatchEvent(e)
    }

    @Test
    fun `heavy doc wheel burst renders half-resolution frames then settles to full resolution`() {
        assertTrue(heavySvg.length > 96 * 1024, "test svg must be classified as heavy")
        FakeSidecar().use { fake ->
            val panel = SvgEditorPanel(FakeSvgRenderer(), asyncRendering = true, sidecar = fake)
            try {
                panel.debugSetViewportSize(800, 800)
                panel.loadSvg(heavySvg)
                val canvas = panel.debugCanvas()
                // The 200x120 doc fits an 800x800 viewport, so every frame is a viewport crop of
                // 800x800 device px at dpr 1.0 (full) or 400x400 (burst downscale = half).
                awaitTrue("initial full-resolution frame") {
                    viewportCalls(fake).any { vw(it) == 800 }
                }
                val zoomBefore = panel.getZoom()
                repeat(4) { wheelNotch(canvas) }
                val zoomAfter = panel.getZoom()
                assertTrue(
                    zoomAfter < zoomBefore,
                    "ctrl+wheel must zoom out (before=$zoomBefore after=$zoomAfter)",
                )
                awaitTrue("a reduced-resolution burst frame") {
                    viewportCalls(fake).any { vw(it) == 400 }
                }
                val calls = viewportCalls(fake)
                val downIndex = calls.indexOfFirst { vw(it) == 400 }
                assertTrue(downIndex >= 0, "the burst must submit downscaled viewport frames")
                // Ending the burst (a discrete zoom action) must re-request full resolution.
                panel.zoomIn()
                awaitTrue("full-resolution frame after the burst ends") {
                    viewportCalls(fake).let { list ->
                        list.indexOfLast { vw(it) == 800 } > downIndex
                    }
                }
            } finally {
                panel.dispose()
            }
        }
    }

    @Test
    fun `light docs zoom without any downscaled frames`() {
        FakeSidecar().use { fake ->
            val panel = SvgEditorPanel(FakeSvgRenderer(), asyncRendering = true, sidecar = fake)
            try {
                panel.debugSetViewportSize(800, 800)
                panel.loadSvg(Samples.SIMPLE) // small doc: below the heavy thresholds
                val canvas = panel.debugCanvas()
                awaitTrue("initial full-resolution frame") {
                    viewportCalls(fake).any { vw(it) == 800 }
                }
                repeat(3) { wheelNotch(canvas) }
                // Cheap documents never enter burst mode, so no half-resolution frame appears.
                Thread.sleep(200)
                assertNull(
                    viewportCalls(fake).firstOrNull { vw(it) == 400 },
                    "a light document must always render at full resolution",
                )
            } finally {
                panel.dispose()
            }
        }
    }
}
