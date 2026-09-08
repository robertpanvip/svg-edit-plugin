package com.example.svgeditor.core

/**
 * One renderable SVG element with its **absolute** (canvas-space) geometry, as extracted
 * by `resvg`/`usvg`. The coordinates are in the same units as the rendered image pixels,
 * so the panel can hit-test the mouse pointer directly against them.
 *
 * @param index Stable order index (drawn back-to-front; later = on top).
 * @param id    The element's SVG `id` attribute (may be empty if the source had none).
 * @param kind  One of `group` / `path` / `image` / `text`.
 * @param x,y,width,height Absolute bounding box in canvas coordinates.
 * @param transform Column-major 2x3 affine matrix `[sx, kx, ky, sy, tx, ty]`.
 * @param nodeId Editor-tree node id assigned by the Rust sidecar at parse time (memory-only,
 *   never written to the file). Always `0` for legacy in-process layouts.
 */
data class SvgElement(
    val index: Int,
    val id: String,
    val kind: String,
    val x: Double,
    val y: Double,
    val width: Double,
    val height: Double,
    val transform: DoubleArray,
    val nodeId: Long = 0,
) {
    val right: Double get() = x + width
    val bottom: Double get() = y + height

    /** Is the point `(px, py)` inside this element's bounding box? */
    fun contains(px: Double, py: Double): Boolean = px in x..right && py in y..bottom

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SvgElement) return false
        return index == other.index && id == other.id && kind == other.kind &&
            x == other.x && y == other.y && width == other.width && height == other.height &&
            transform.contentEquals(other.transform) && nodeId == other.nodeId
    }

    override fun hashCode(): Int {
        var result = index
        result = 31 * result + id.hashCode()
        result = 31 * result + kind.hashCode()
        result = 31 * result + x.hashCode()
        result = 31 * result + y.hashCode()
        result = 31 * result + width.hashCode()
        result = 31 * result + height.hashCode()
        result = 31 * result + transform.contentHashCode()
        result = 31 * result + nodeId.hashCode()
        return result
    }
}

/**
 * The full layout of an SVG document: its canvas size and the list of elements.
 */
data class SvgLayout(
    val width: Double,
    val height: Double,
    val elements: List<SvgElement>,
) {
    /** Topmost element whose bounding box contains the point (last drawn = on top). */
    fun hitTest(px: Double, py: Double): SvgElement? = elements.lastOrNull { it.contains(px, py) }

    /** All elements whose bounding box intersects the rectangle `(rx, ry, rw, rh)`. */
    fun intersecting(rx: Double, ry: Double, rw: Double, rh: Double): List<SvgElement> =
        elements.filter { it.x < rx + rw && it.right > rx && it.y < ry + rh && it.bottom > ry }

    /**
     * Look up an element by its SVG `id`. Sidecar layouts also answer their node id as a
     * string (selection keys fall back to it when the element has no `id` attribute);
     * legacy layouts always have `nodeId == 0`, so numeric fallbacks can never misfire.
     */
    fun byId(id: String): SvgElement? =
        elements.firstOrNull { it.id == id }
            ?: elements.firstOrNull { it.nodeId != 0L && it.nodeId.toString() == id }

    /** Look up an element by its editor-tree node id (null for `0` / unknown ids). */
    fun byNodeId(nodeId: Long): SvgElement? =
        if (nodeId == 0L) null else elements.firstOrNull { it.nodeId == nodeId }

    companion object {
        /**
         * Parse a layout JSON document. Accepts both the legacy `resvg_bridge` field names
         * (`index`/`kind`/`width`/`height`/`transform` — transform required) and the Rust
         * sidecar names (`nodeId`/`tag`/`w`/`h`, `index`/`transform` optional).
         */
        fun parse(json: String): SvgLayout {
            val root = Json.parse(json) as? Map<*, *> ?: throw IllegalArgumentException("layout JSON root must be an object")
            val w = (root["width"] as? Number)?.toDouble() ?: 0.0
            val h = (root["height"] as? Number)?.toDouble() ?: 0.0
            val arr = root["elements"] as? List<*> ?: emptyList<Any?>()
            val els =
                arr.mapIndexed { i, e ->
                    val m = e as Map<*, *>
                    val transform =
                        (m["transform"] as? List<*>)
                            ?.map { (it as Number).toDouble() }
                            ?.toDoubleArray()
                            ?: doubleArrayOf(1.0, 0.0, 0.0, 1.0, 0.0, 0.0)
                    SvgElement(
                        index = (m["index"] as? Number)?.toInt() ?: i,
                        id = m["id"] as? String ?: "",
                        kind = m["tag"] as? String ?: m["kind"] as? String ?: "path",
                        x = (m["x"] as Number).toDouble(),
                        y = (m["y"] as Number).toDouble(),
                        width = (m["w"] as? Number ?: m["width"] as? Number)?.toDouble() ?: 0.0,
                        height = (m["h"] as? Number ?: m["height"] as? Number)?.toDouble() ?: 0.0,
                        transform = transform,
                        nodeId = (m["nodeId"] as? Number)?.toLong() ?: 0L,
                    )
                }
            return SvgLayout(w, h, els)
        }
    }
}
