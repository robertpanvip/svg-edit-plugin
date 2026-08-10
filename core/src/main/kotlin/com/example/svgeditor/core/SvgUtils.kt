package com.example.svgeditor.core

/**
 * Source-level SVG editing helpers.
 *
 * Drag-to-move editing is implemented by rewriting the target element's `transform`
 * attribute in the **original SVG source** (prepending a `translate(dx, dy)`), then asking
 * `resvg` to re-parse and re-render. Because we keep the source of truth as text, the
 * editor stays compatible with any SVG tooling and the change is visible in the document.
 */
object SvgUtils {
    /** Insert/append `translate(dx, dy)` onto the element with the given `id`. Returns the
     *  new SVG, or the original string unchanged if no element with that id exists. */
    fun applyTranslate(
        svg: String,
        id: String,
        dx: Double,
        dy: Double,
    ): String {
        val t = "translate(${fmt(dx)}, ${fmt(dy)})"
        val idRegex = Regex("""id\s*=\s*["']${Regex.escape(id)}["']""")
        val m = idRegex.find(svg) ?: return svg
        val idStart = m.range.first
        val tagStart = svg.lastIndexOf('<', idStart)
        if (tagStart < 0) return svg
        val tagEnd = svg.indexOf('>', idStart)
        if (tagEnd < 0) return svg

        val tag = svg.substring(tagStart, tagEnd + 1)
        val selfClosing = tag.trimEnd().endsWith("/>")
        val transformRegex = Regex("""transform\s*=\s*["']([^"']*)["']""")
        val newTag =
            transformRegex.find(tag)?.let { tm ->
                val combined = "$t ${tm.groupValues[1]}"
                tag.replace(tm.value, """transform="$combined"""")
            } ?: run {
                if (selfClosing) {
                    tag.substring(0, tag.length - 2) + """ transform="$t" />"""
                } else {
                    tag.substring(0, tag.length - 1) + """ transform="$t" >"""
                }
            }
        return svg.substring(0, tagStart) + newTag + svg.substring(tagEnd + 1)
    }

    /** Pretty format a translation coordinate (drops trailing zeros). */
    fun fmt(d: Double): String {
        if (d == d.toInt().toDouble()) return d.toInt().toString()
        return "%.4f".format(d).trimEnd('0').trimEnd('.')
    }

    /**
     * Replace (or insert, when absent) the `transform` attribute on the element with the
     * given `id`. Used by resize editing, which rewrites the element to a `matrix(...)`.
     */
    fun setTransform(
        svg: String,
        id: String,
        transformAttr: String,
    ): String {
        val idRegex = Regex("""id\s*=\s*["']${Regex.escape(id)}["']""")
        val m = idRegex.find(svg) ?: return svg
        val idStart = m.range.first
        val tagStart = svg.lastIndexOf('<', idStart)
        if (tagStart < 0) return svg
        val tagEnd = svg.indexOf('>', idStart)
        if (tagEnd < 0) return svg

        val tag = svg.substring(tagStart, tagEnd + 1)
        val selfClosing = tag.trimEnd().endsWith("/>")
        val transformRegex = Regex("""transform\s*=\s*["']([^"']*)["']""")
        val newTag =
            transformRegex.find(tag)?.let { tm ->
                tag.replace(tm.value, """transform="$transformAttr"""")
            } ?: run {
                if (selfClosing) {
                    tag.substring(0, tag.length - 2) + """ transform="$transformAttr" />"""
                } else {
                    tag.substring(0, tag.length - 1) + """ transform="$transformAttr" >"""
                }
            }
        return svg.substring(0, tagStart) + newTag + svg.substring(tagEnd + 1)
    }

    /** Format a 2x3 affine matrix `[a,b,c,d,e,f]` as an SVG `matrix(...)` value. */
    fun matrixAttr(m: DoubleArray): String =
        "matrix(${fmt(m[0])}, ${fmt(m[1])}, ${fmt(m[2])}, ${fmt(m[3])}, ${fmt(m[4])}, ${fmt(m[5])})"

    /**
     * Return a copy of `svg` where the element with the given `id` is hidden via
     * `display="none"`. Used to render the two drag layers: the background layer hides the
     * dragged element, the foreground layer hides everything else.
     */
    fun hideElement(
        svg: String,
        id: String,
    ): String {
        val idRegex = Regex("""id\s*=\s*["']${Regex.escape(id)}["']""")
        val m = idRegex.find(svg) ?: return svg
        val idStart = m.range.first
        val tagStart = svg.lastIndexOf('<', idStart)
        if (tagStart < 0) return svg
        val tagEnd = svg.indexOf('>', idStart)
        if (tagEnd < 0) return svg

        val tag = svg.substring(tagStart, tagEnd + 1)
        val selfClose = tag.trimEnd().endsWith("/>")
        val dispRegex = Regex("""\sdisplay\s*=\s*["'][^"']*["']""")
        val newTag =
            if (dispRegex.containsMatchIn(tag)) {
                dispRegex.replace(tag) { """ display="none"""" }
            } else if (selfClose) {
                tag.substring(0, tag.length - 2) + """ display="none" />"""
            } else {
                tag.substring(0, tag.length - 1) + """ display="none">"""
            }
        return svg.substring(0, tagStart) + newTag + svg.substring(tagEnd + 1)
    }
}
