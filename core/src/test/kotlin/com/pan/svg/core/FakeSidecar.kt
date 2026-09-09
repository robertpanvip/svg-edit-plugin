package com.pan.svg.core

import java.util.Base64

open class FakeSidecar : SidecarClient(listOf("fake-sidecar")) {
    // Async panels record calls from the render-worker thread while the test reads them from the
    // main thread, so the log must be thread-safe.
    val calls = java.util.Collections.synchronizedList(mutableListOf<Pair<String, Map<String, Any?>>>())

    var failOn: String? = null

    var hitNodeId: Long? = null

    var commitSvg: String =
        """<svg xmlns="http://www.w3.org/2000/svg" width="200" height="120"><rect id="committed"/></svg>"""

    var commitElements: List<Map<String, Any?>> = emptyList()

    private val pngB64 =
        Base64.getEncoder().encodeToString(
            Base64.getDecoder().decode(
                "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAAC0lEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==",
            ),
        )

    override fun exchange(
        method: String,
        params: Map<String, Any?>,
        timeoutMs: Long,
    ): Any? {
        calls.add(method to params)
        if (method == failOn) throw SidecarException("injected failure for '$method'")
        return when (method) {
            "ping" -> linkedMapOf("version" to "fake-1.0", "protocol" to 1L)
            "open" ->
                linkedMapOf(
                    "width" to 200.0,
                    "height" to 120.0,
                    "elements" to
                        listOf(
                            linkedMapOf(
                                "nodeId" to 1L, "tag" to "rect", "id" to "bg",
                                "x" to 0.0, "y" to 0.0, "w" to 200.0, "h" to 120.0,
                            ),
                            linkedMapOf(
                                "nodeId" to 2L, "tag" to "rect", "id" to "box-a",
                                "x" to 10.0, "y" to 10.0, "w" to 80.0, "h" to 60.0,
                            ),
                            linkedMapOf(
                                "nodeId" to 3L, "tag" to "circle", "id" to "dot",
                                "x" to 120.0, "y" to 30.0, "w" to 60.0, "h" to 60.0,
                            ),
                        ),
                )
            "hitTest" -> hitNodeId?.let { linkedMapOf("nodeId" to it) } ?: emptyMap<Any?, Any?>()
            "startDrag" -> linkedMapOf("bgPng" to pngB64, "ghostPng" to pngB64, "w" to 200, "h" to 120)
            "commit" ->
                linkedMapOf(
                    "svg" to commitSvg,
                    "png" to pngB64,
                    "w" to 200,
                    "h" to 120,
                    "elements" to commitElements,
                )
            "renderViewport" -> linkedMapOf("png" to pngB64)
            else -> throw SidecarException("fake sidecar: unexpected method '$method'")
        }
    }

    fun count(method: String): Int = synchronized(calls) { calls.count { it.first == method } }

    fun paramsOf(
        method: String,
        n: Int = 0,
    ): Map<String, Any?> {
        val matches = synchronized(calls) { calls.filter { it.first == method }.map { it.second } }
        return matches[n]
    }
}
