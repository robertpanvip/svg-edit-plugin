package com.example.svgeditor.core

/**
 * Abstraction over the SVG rendering + layout backend.
 *
 * `ResvgBridge` is the production implementation (JNA → `resvg_bridge` native lib). Tests
 * inject a fake implementation so the engine and panel can run without a Rust toolchain.
 */
interface SvgRenderer {
    fun render(
        svg: String,
        fitW: Int = 0,
        fitH: Int = 0,
    ): RenderResult

    fun layoutJson(svg: String): String
}
