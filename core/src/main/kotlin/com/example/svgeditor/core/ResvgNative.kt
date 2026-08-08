package com.example.svgeditor.core

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.ptr.IntByReference

/**
 * JNA binding to the `resvg_bridge` native library.
 *
 * The native side speaks C strings / byte buffers; this wrapper encodes the Kotlin
 * `String` as **UTF-8 + NUL terminator** (JNA does not add the terminator for `ByteArray`)
 * and decodes the returned layout JSON back into a Kotlin `String`, freeing native memory
 * immediately after copying it out.
 */
interface ResvgLibrary : Library {
    fun svg_render_png_bytes(
        svg: ByteArray,
        fitW: Int,
        fitH: Int,
        outLen: IntByReference,
        outW: IntByReference,
        outH: IntByReference,
    ): Pointer

    fun svg_free_bytes(ptr: Pointer)

    fun svg_layout_json(svg: ByteArray): Pointer

    fun svg_free_string(s: Pointer)
}

/** Result of rendering an SVG to an off-screen PNG image. */
data class RenderResult(
    val png: ByteArray,
    val width: Int,
    val height: Int,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is RenderResult) return false
        return width == other.width && height == other.height && png.contentEquals(other.png)
    }

    override fun hashCode(): Int = 31 * (width + 31 * height) + png.contentHashCode()
}

/**
 * High-level, allocation-safe facade over [ResvgLibrary]. This is the production
 * implementation of [SvgRenderer].
 */
class ResvgBridge private constructor(
    private val lib: ResvgLibrary,
) : SvgRenderer {
    /** Render `svg` to PNG bytes. `fitW`/`fitH` of 0 keeps the natural size. */
    override fun render(
        svg: String,
        fitW: Int,
        fitH: Int,
    ): RenderResult {
        val bytes = svg.toByteArray(Charsets.UTF_8) + 0.toByte()
        val outLen = IntByReference()
        val outW = IntByReference()
        val outH = IntByReference()
        val ptr = lib.svg_render_png_bytes(bytes, fitW, fitH, outLen, outW, outH)
        require(ptr != Pointer.NULL) { "resvg_bridge: render failed (invalid SVG or native error)" }
        val data = ptr.getByteArray(0, outLen.value)
        lib.svg_free_bytes(ptr)
        return RenderResult(data, outW.value, outH.value)
    }

    /** Extract the per-element layout JSON for `svg`. */
    override fun layoutJson(svg: String): String {
        val bytes = svg.toByteArray(Charsets.UTF_8) + 0.toByte()
        val ptr = lib.svg_layout_json(bytes)
        require(ptr != Pointer.NULL) { "resvg_bridge: layout extraction failed" }
        val json = ptr.getString(0, "UTF-8")
        lib.svg_free_string(ptr)
        return json
    }

    companion object {
        /** Load the bridge from an explicit path, or from `resvg_bridge` on the library path. */
        fun load(libPath: String? = null): ResvgBridge {
            val lib =
                if (libPath != null) {
                    Native.load(libPath, ResvgLibrary::class.java)
                } else {
                    Native.load("resvg_bridge", ResvgLibrary::class.java)
                }
            return ResvgBridge(lib)
        }
    }
}
