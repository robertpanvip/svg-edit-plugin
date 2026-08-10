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
     * Prepend `rotate(a, cx, cy)` to the element's `transform` list (placed BEFORE any existing
     * transform). Because SVG applies transforms right-to-left, a prepended transform is the LAST
     * to be applied — i.e. it operates in the element's already-transformed (canvas) coordinate
     * space. That is exactly what "rotate around the element's canvas-space centre" needs, and it
     * preserves any prior `translate`/`rotate` already on the element so repeated edits compose
     * instead of overwriting each other.
     */
    fun prependRotate(
        svg: String,
        id: String,
        angleDeg: Double,
        cx: Double,
        cy: Double,
    ): String = prependTransform(svg, id, "rotate(${fmt(angleDeg)}, ${fmt(cx)}, ${fmt(cy)})")

    /** Prepend a `matrix(...)` value to the element's transform list (see [prependRotate]). */
    fun prependMatrix(
        svg: String,
        id: String,
        matrixAttr: String,
    ): String = prependTransform(svg, id, matrixAttr)

    /**
     * Insert `value` as the FIRST entry of the element's `transform` attribute, or create the
     * attribute with just `value` when absent. Internal helper shared by [prependRotate] /
     * [prependMatrix].
     */
    private fun prependTransform(
        svg: String,
        id: String,
        value: String,
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
                val combined = "$value ${tm.groupValues[1]}"
                tag.replace(tm.value, """transform="$combined"""")
            } ?: run {
                if (selfClosing) {
                    tag.substring(0, tag.length - 2) + """ transform="$value" />"""
                } else {
                    tag.substring(0, tag.length - 1) + """ transform="$value" >"""
                }
            }
        return svg.substring(0, tagStart) + newTag + svg.substring(tagEnd + 1)
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

    /**
     * Build a minimal SVG that renders ONLY the element with `id` (and its ancestor `<g>`
     * groups, so a nested element keeps its correct absolute position). Everything else is
     * dropped. This is used for the foreground drag layer: unlike [hideElement], it never
     * hides an ancestor group (which would also hide the element we actually want to show).
     *
     * The result keeps the original `<svg>` root attributes (width/height/viewBox) so the
     * solo element renders at exactly the same coordinates as in the full document.
     */
    fun soloElement(
        svg: String,
        id: String,
    ): String {
        val spans = scanTags(svg)
        val target = spans.firstOrNull { it.id == id } ?: return svg

        // Outermost-first chain of ancestor groups.
        val chain = mutableListOf<TagSpan>()
        var p = target.parentId
        while (p != null) {
            val ps = spans.firstOrNull { it.id == p } ?: break
            chain.add(0, ps)
            p = ps.parentId
        }

        val rootOpen = svgRootOpenTag(svg)
        val sb = StringBuilder()
        sb.append(rootOpen)
        for (a in chain) sb.append(svg.substring(a.openStart, a.openEnd))
        val targetEnd = if (target.closeStart >= 0) target.closeEnd else target.openEnd
        sb.append(svg.substring(target.openStart, targetEnd))
        for (a in chain.reversed()) {
            if (a.closeStart >= 0) sb.append(svg.substring(a.closeStart, a.closeEnd)) else sb.append("</g>")
        }
        sb.append("</svg>")
        return sb.toString()
    }

    // ---- private tag-scanning helpers (used by soloElement) ----

    private data class TagSpan(
        var id: String?,
        var isGroup: Boolean,
        var openStart: Int,
        var openEnd: Int,
        var closeStart: Int,
        var closeEnd: Int,
        var parentId: String?,
    )

    private fun idAttr(tag: String): String? {
        val m = Regex("""\bid\s*=\s*["']([^"']*)["']""").find(tag)
        return m?.groupValues?.get(1)
    }

    private fun isGroupOpen(tag: String): Boolean {
        val t = tag.trimStart()
        if (!t.startsWith("<g", ignoreCase = true)) return false
        val after = t.substring(2)
        return after.isEmpty() || after[0] == '>' || after[0].isWhitespace()
    }

    /** Flat list of every tag in `svg`, with parent (enclosing group) ids and open/close spans. */
    private fun scanTags(svg: String): List<TagSpan> {
        val spans = mutableListOf<TagSpan>()
        val openGroups = ArrayDeque<Int>() // span indices of currently-open groups
        val idStack = ArrayDeque<String>() // ids of currently-open id'd groups
        val allOpen = ArrayDeque<Boolean>() // open groups (id'd or not) for close-matching
        var i = 0
        while (i < svg.length) {
            val lt = svg.indexOf('<', i)
            if (lt < 0) break
            val gt = svg.indexOf('>', lt)
            if (gt < 0) break
            val tag = svg.substring(lt, gt + 1)
            if (tag.startsWith("</")) {
                if (allOpen.isNotEmpty() && allOpen.removeLast()) {
                    if (idStack.isNotEmpty()) idStack.removeLast()
                    if (openGroups.isNotEmpty()) {
                        val gi = openGroups.removeLast()
                        spans[gi].closeStart = lt
                        spans[gi].closeEnd = gt + 1
                    }
                }
                i = gt + 1
                continue
            }
            val selfClosing = tag.endsWith("/>")
            val isG = isGroupOpen(tag)
            val tid = idAttr(tag)
            val parentId =
                if (isG) {
                    idStack.run { if (size >= 2) get(size - 2) else null }
                } else {
                    idStack.lastOrNull()
                }
            val span = TagSpan(tid, isG, lt, gt + 1, -1, -1, parentId)
            val idx = spans.size
            spans.add(span)
            if (isG) {
                allOpen.addLast(tid != null)
                if (tid != null) idStack.addLast(tid)
                openGroups.addLast(idx)
            }
            i = gt + 1
        }
        return spans
    }

    private fun svgRootOpenTag(svg: String): String {
        val lt = svg.indexOf("<svg", ignoreCase = true)
        if (lt < 0) return "<svg>"
        val gt = svg.indexOf('>', lt)
        if (gt < 0) return "<svg>"
        var tag = svg.substring(lt, gt + 1)
        // Normalise a self-closing root (extremely rare) to an open tag so we can nest children.
        if (tag.trimEnd().endsWith("/>")) tag = tag.substring(0, tag.length - 2) + ">"
        return tag
    }
}
