package com.pan.svg.core

import java.util.Locale

/**
 * One SVGO optimization pass as advertised by the sidecar's `optimizePasses` RPC: [name] is the
 * pass identity (and the key used in the `options` map), [label] is the wording shown to the user
 * and [group] the section it is listed under.
 */
data class SvgoPass(val name: String, val label: String, val group: String)

/**
 * Outcome of one `optimize` RPC: the optimized [svg], the byte sizes before/after and how many
 * passes actually ran. The computed helpers exist so the result dialog can phrase the outcome
 * without re-deriving the arithmetic at each call site.
 */
data class SvgoResult(
    val svg: String,
    val beforeBytes: Long,
    val afterBytes: Long,
    val passes: Int,
) {
    /** Bytes removed by the optimizer; negative when the optimized file grew. */
    val savedBytes: Long get() = beforeBytes - afterBytes

    /** Percentage of the original size that was removed; `0.0` for an empty original. */
    val savedPercent: Double
        get() = if (beforeBytes == 0L) 0.0 else savedBytes * 100.0 / beforeBytes
}

/**
 * Human-readable byte size (`182 B`, `1.2 KB`, `3.0 MB`) for the SVGO result wording. A negative
 * value keeps its sign so a file that grew reads honestly.
 */
fun formatBytes(bytes: Long): String {
    val negative = bytes < 0
    val magnitude = if (negative) -bytes else bytes
    val text =
        when {
            magnitude < 1024L -> "$magnitude B"
            magnitude < 1024L * 1024L -> String.format(Locale.ROOT, "%.1f KB", magnitude / 1024.0)
            else -> String.format(Locale.ROOT, "%.1f MB", magnitude / 1024.0 / 1024.0)
        }
    return if (negative) "-$text" else text
}
