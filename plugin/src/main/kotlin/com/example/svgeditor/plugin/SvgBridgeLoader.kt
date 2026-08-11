package com.example.svgeditor.plugin

import com.example.svgeditor.core.ResvgBridge
import com.example.svgeditor.core.SvgRenderer

/**
 * Loads the bundled `resvg_bridge` native library (JNA) used by [com.example.svgeditor.core.SvgEditorPanel]
 * for SVG rendering. Shared by both the tool window and the editor so the lookup logic lives in one place.
 */
object SvgBridgeLoader {
    fun load(): SvgRenderer {
        val candidates =
            listOf(
                "resvg_bridge", // bundled on java.library.path
                "../native/resvg_bridge/target/debug/resvg_bridge",
                "../native/resvg_bridge/target/release/resvg_bridge",
            )
        var last: Throwable? = null
        for (c in candidates) {
            try {
                return ResvgBridge.load(c)
            } catch (t: Throwable) {
                last = t
            }
        }
        throw IllegalStateException("Could not load resvg_bridge native library (tried $candidates)", last)
    }
}
