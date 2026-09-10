package com.pan.svg.core

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

    /** Column-major 2x3 identity affine `[a,b,c,d,e,f]` = `[[a,c,e],[b,d,f]]`. */
    val IDENTITY_MATRIX: DoubleArray get() = doubleArrayOf(1.0, 0.0, 0.0, 1.0, 0.0, 0.0)

    /**
     * Matrix product `m1 ∘ m2` (apply `m2` first, then `m1`). Both are 2x3 affine matrices in
     * column-major order `[a,b,c,d,e,f]`.
     */
    fun affineMultiply(
        m1: DoubleArray,
        m2: DoubleArray,
    ): DoubleArray {
        val a = m1[0] * m2[0] + m1[2] * m2[1]
        val b = m1[1] * m2[0] + m1[3] * m2[1]
        val c = m1[0] * m2[2] + m1[2] * m2[3]
        val d = m1[1] * m2[2] + m1[3] * m2[3]
        val e = m1[0] * m2[4] + m1[2] * m2[5] + m1[4]
        val f = m1[1] * m2[4] + m1[3] * m2[5] + m1[5]
        return doubleArrayOf(a, b, c, d, e, f)
    }

    /** Inverse of a 2x3 affine matrix (column-major). Returns identity for a singular matrix. */
    fun affineInverse(m: DoubleArray): DoubleArray {
        val a = m[0]
        val b = m[1]
        val c = m[2]
        val d = m[3]
        val e = m[4]
        val f = m[5]
        val det = a * d - b * c
        if (det == 0.0) return IDENTITY_MATRIX
        val id = 1.0 / det
        val m00 = d * id
        val m01 = -c * id
        val m10 = -b * id
        val m11 = a * id
        val tx = -(m00 * e + m01 * f)
        val ty = -(m10 * e + m11 * f)
        return doubleArrayOf(m00, m10, m01, m11, tx, ty)
    }

    /**
     * Parse an SVG `transform` attribute string (e.g. `translate(5,7) rotate(30 50 40)`) into a
     * single 2x3 affine matrix. Supports `translate`, `scale`, `rotate` (with optional centre),
     * `matrix`, `skewX` and `skewY`. Returns identity for an empty/absent spec.
     */
    fun parseTransformToMatrix(spec: String): DoubleArray {
        val trimmed = spec.trim()
        if (trimmed.isEmpty()) return IDENTITY_MATRIX
        var m = IDENTITY_MATRIX
        val re = Regex("""(\w+)\s*\(([^)]*)\)""")
        for (mt in re.findAll(trimmed)) {
            val name = mt.groupValues[1]
            val nums =
                mt.groupValues[2]
                    .split(Regex("""[,\s]+"""))
                    .filter { it.isNotBlank() }
                    .mapNotNull { it.toDoubleOrNull() }
            m = affineMultiply(m, transformMatrix(name, nums))
        }
        return m
    }

    private fun transformMatrix(
        name: String,
        nums: List<Double>,
    ): DoubleArray =
        when (name) {
            "translate" ->
                doubleArrayOf(1.0, 0.0, 0.0, 1.0, nums.getOrElse(0) { 0.0 }, nums.getOrElse(1) { 0.0 })
            "scale" -> {
                val sx = nums.getOrElse(0) { 1.0 }
                val sy = nums.getOrElse(1) { sx }
                doubleArrayOf(sx, 0.0, 0.0, sy, 0.0, 0.0)
            }
            "rotate" -> {
                val ang = Math.toRadians(nums.getOrElse(0) { 0.0 })
                val cx = nums.getOrElse(1) { 0.0 }
                val cy = nums.getOrElse(2) { 0.0 }
                val ca = kotlin.math.cos(ang)
                val sa = kotlin.math.sin(ang)
                val rot = doubleArrayOf(ca, sa, -sa, ca, 0.0, 0.0)
                val t1 = doubleArrayOf(1.0, 0.0, 0.0, 1.0, cx, cy)
                val t2 = doubleArrayOf(1.0, 0.0, 0.0, 1.0, -cx, -cy)
                affineMultiply(t1, affineMultiply(rot, t2))
            }
            "matrix" ->
                doubleArrayOf(
                    nums.getOrElse(0) { 1.0 },
                    nums.getOrElse(1) { 0.0 },
                    nums.getOrElse(2) { 0.0 },
                    nums.getOrElse(3) { 1.0 },
                    nums.getOrElse(4) { 0.0 },
                    nums.getOrElse(5) { 0.0 },
                )
            "skewX" -> {
                val ang = Math.toRadians(nums.getOrElse(0) { 0.0 })
                doubleArrayOf(1.0, 0.0, kotlin.math.tan(ang), 1.0, 0.0, 0.0)
            }
            "skewY" -> {
                val ang = Math.toRadians(nums.getOrElse(0) { 0.0 })
                doubleArrayOf(1.0, kotlin.math.tan(ang), 0.0, 1.0, 0.0, 0.0)
            }
            else -> IDENTITY_MATRIX
        }

    /**
     * Return the `transform` attribute value of the element with the given `id` (empty string if
     * the element has no transform). Used by move editing to convert a root-canvas drag delta
     * into the element's local coordinate space.
     */
    fun ownTransformOf(
        svg: String,
        id: String,
    ): String {
        val idRegex = Regex("""id\s*=\s*["']${Regex.escape(id)}["']""")
        val m = idRegex.find(svg) ?: return ""
        val idStart = m.range.first
        val tagStart = svg.lastIndexOf('<', idStart)
        if (tagStart < 0) return ""
        val tagEnd = svg.indexOf('>', idStart)
        if (tagEnd < 0) return ""
        return transformAttrOfTag(svg.substring(tagStart, tagEnd + 1))
    }

    /** Extract the `transform` attribute value from a single tag string (empty if absent). */
    private fun transformAttrOfTag(tag: String): String {
        val transformRegex = Regex("""transform\s*=\s*["']([^"']*)["']""")
        return transformRegex.find(tag)?.groupValues?.get(1) ?: ""
    }

    /**
     * The cumulative transform of the ANCESTOR groups of `id` (root/canvas space), product of all
     * enclosing `<g>` transforms (outer-most applied last). Identity when the element is not inside
     * any group. This is what move editing needs to convert a root-canvas drag delta into the
     * element's local space — derived purely from the source so it is independent of how `resvg`
     * reports the element's own (possibly geometry-baked) transform.
     */
    fun ancestorTransform(
        svg: String,
        id: String,
    ): DoubleArray {
        val spans = scanTags(svg)
        val target = spans.firstOrNull { it.id == id } ?: return IDENTITY_MATRIX
        val groups = mutableListOf<TagSpan>()
        var p = target.parentId
        while (p != null) {
            val ps = spans.firstOrNull { it.id == p } ?: break
            if (ps.isGroup) groups.add(ps)
            p = ps.parentId
        }
        // `groups` is collected inner-to-outer (walking up the parent chain). The absolute group
        // transform applies the outer-most last, so fold as cur ∘ g over [outer … inner].
        var g = IDENTITY_MATRIX
        for (grp in groups.asReversed()) {
            val spec = transformAttrOfTag(svg.substring(grp.openStart, grp.openEnd))
            g = affineMultiply(g, parseTransformToMatrix(spec))
        }
        return g
    }

    /** True when `m` is a pure translation (no scale/rotation/shear). */
    fun isTranslationMatrix(m: DoubleArray): Boolean =
        kotlin.math.abs(m[0] - 1.0) < 1e-9 &&
            kotlin.math.abs(m[3] - 1.0) < 1e-9 &&
            kotlin.math.abs(m[1]) < 1e-9 &&
            kotlin.math.abs(m[2]) < 1e-9

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
     * Assign a unique synthetic `id` to every renderable element that has none.
     *
     * `usvg` reports elements without an `id` attribute with an empty id: they are still
     * hit-testable and draggable, but every source-level edit locates its target by
     * `id="..."` and would silently fail — the element visually snaps back on mouse-up (and
     * the drag layers break the same way). Hosts call this once at load; a document whose
     * elements all carry ids is returned unchanged.
     */
    fun ensureElementIds(svg: String): String = ensureElementIdsWithSynthetic(svg).first

    /**
     * [ensureElementIds] plus the set of synthetic ids it injected, so a host can later strip
     * them ([stripElementIds]) when writing the edited document back.
     */
    fun ensureElementIdsWithSynthetic(svg: String): Pair<String, Set<String>> {
        val spans = scanTags(svg)
        val missing =
            spans.filter { s ->
                s.id == null && isEditableTag(svg.substring(s.openStart, s.openEnd))
            }
        if (missing.isEmpty()) return svg to emptySet()
        val used = HashSet<String>()
        for (s in spans) s.id?.let { used.add(it) }
        // Assign numbers in document order (first element -> svg-el-1), then apply the
        // insertions back-to-front so the spans of not-yet-patched tags keep their offsets.
        val attrs =
            missing.map { s ->
                var n = 1
                while (!used.add("svg-el-$n")) n++
                s to """id="svg-el-$n""""
            }
        val injected =
            attrs.map { (_, attr) -> attr.substringAfter('"').substringBeforeLast('"') }.toSet()
        var out = svg
        for ((s, attr) in attrs.asReversed()) {
            out = insertIntoOpenTag(out, s.openStart, s.openEnd, attr)
        }
        return out to injected
    }

    /**
     * Remove every `id="…"` attribute whose value is in [synthetic]. Synthetic ids exist only
     * so source-level edits can locate id-less elements; they must not leak into the user's
     * document when the panel writes an edited SVG back. The ids in [synthetic] were chosen to
     * avoid the user's real ids ([ensureElementIdsWithSynthetic]), so this never removes an
     * attribute the user wrote. Ids that are no longer present (e.g. an element was deleted)
     * are skipped.
     */
    fun stripElementIds(svg: String, synthetic: Set<String>): String {
        if (synthetic.isEmpty()) return svg
        var out = svg
        for (id in synthetic) {
            // The injected attribute is always ` id="<id>"` (insertIntoOpenTag format).
            val re = Regex("""\s+id\s*=\s*"${Regex.escape(id)}"""")
            out = re.replaceFirst(out, "")
        }
        return out
    }

    /** Element tags that appear in the layout and are therefore editable targets. */
    private val EDITABLE_TAG_NAMES =
        setOf(
            "g",
            "path",
            "rect",
            "circle",
            "ellipse",
            "line",
            "polyline",
            "polygon",
            "text",
            "tspan",
            "image",
            "use",
        )

    private fun isEditableTag(tag: String): Boolean = tagNameOf(tag) in EDITABLE_TAG_NAMES

    /** Local name of a tag string (e.g. `"rect"` for `"<rect …"`), lowercase, null if not a tag. */
    private fun tagNameOf(tag: String): String? {
        val t = tag.trimStart()
        if (!t.startsWith("<") || t.startsWith("</") || t.startsWith("<!")) return null
        val sb = StringBuilder()
        for (c in t.substring(1)) {
            if (c.isLetterOrDigit() || c == '-' || c == '_') sb.append(c.lowercaseChar()) else break
        }
        return sb.toString().takeIf { it.isNotEmpty() }
    }

    /** Insert an attribute into the open tag spanning `[openStart, openEnd)` (handles `/>`). */
    private fun insertIntoOpenTag(
        svg: String,
        openStart: Int,
        openEnd: Int,
        attr: String,
    ): String {
        val tag = svg.substring(openStart, openEnd)
        val selfClosing = tag.trimEnd().endsWith("/>")
        val patched =
            if (selfClosing) {
                tag.substring(0, tag.length - 2) + " " + attr + " />"
            } else {
                tag.substring(0, tag.length - 1) + " " + attr + ">"
            }
        return svg.substring(0, openStart) + patched + svg.substring(openEnd)
    }

    /**
     * Build a minimal SVG that renders ONLY the element with `id` (and its ancestor `<g>`
     * groups, so a nested element keeps its correct absolute position). Everything else is
     * dropped. This is used for the foreground drag layer: unlike [hideElement], it never
     * hides an ancestor group (which would also hide the element we actually want to show).
     *
     * The result keeps the original `<svg>` root attributes (width/height/viewBox) so the
     * solo element renders at exactly the same coordinates as in the full document. It also
     * keeps every `<defs>` block: `<style>` class rules, gradients, symbols, clip paths and
     * `<use>` targets live there, and without them a class-styled element would drag as an
     * unstyled black shape and only "fix itself" once the full document re-renders.
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
        // Preserve <defs> (in document order) so class styles and referenced definitions
        // still apply to the solo element. <defs> itself never renders, so hoisting it
        // right after the root is safe.
        for ((ds, de) in defsBlocks(svg)) sb.append(svg.substring(ds, de))
        for (a in chain) sb.append(svg.substring(a.openStart, a.openEnd))
        val targetEnd = if (target.closeStart >= 0) target.closeEnd else target.openEnd
        sb.append(svg.substring(target.openStart, targetEnd))
        for (a in chain.reversed()) {
            if (a.closeStart >= 0) sb.append(svg.substring(a.closeStart, a.closeEnd)) else sb.append("</g>")
        }
        sb.append("</svg>")
        return sb.toString()
    }

    // ---- structural editing helpers (delete / duplicate / reorder) ----

    /** How to move an element among its siblings in the source (`FRONT` = topmost/last). */
    enum class ReorderDir { FRONT, FORWARD, BACKWARD, BACK }

    /**
     * Remove the element with `id` from the source (its open tag plus, when present, its entire
     * children through the matching close tag). Returns the new SVG, or the original string
     * unchanged when no element with that id exists.
     */
    fun removeElement(
        svg: String,
        id: String,
    ): String {
        val spans = scanTags(svg)
        val target = spans.firstOrNull { it.id == id } ?: return svg
        val start = target.openStart
        val end = if (target.closeStart >= 0) target.closeEnd else target.openEnd
        return svg.substring(0, start) + svg.substring(end)
    }

    /**
     * Clone the element with `id` (its open tag and, when present, its whole subtree through the
     * matching close tag), giving the copy a fresh unique id, and insert it immediately after the
     * original in the same parent. Returns the resulting SVG together with the new element's id,
     * or null when no element with that id exists. The clone renders at the original's position;
     * hosts normally translate it by calling [applyTranslate] afterwards so the paste is visible.
     */
    fun duplicateElement(
        svg: String,
        id: String,
    ): Pair<String, String>? {
        val spans = scanTags(svg)
        val target = spans.firstOrNull { it.id == id } ?: return null
        val end = if (target.closeStart >= 0) target.closeEnd else target.openEnd
        val chunk = svg.substring(target.openStart, end)
        val newId = nextUniqueId(svg, "copy-of-$id")
        val cloned = retagFirstId(chunk, newId)
        return (svg.substring(0, end) + cloned + svg.substring(end)) to newId
    }

    /**
     * Move the element with `id` among its siblings by [dir]. `FRONT`/`BACK` jump to the top/
     * bottom of the sibling list; `FORWARD`/`BACKWARD` shift by one position (toward the front/
     * back — later siblings draw on top). Returns the new SVG, or the original unchanged when the
     * element is missing, sole child, or already at the requested end.
     */
    fun reorderElement(
        svg: String,
        id: String,
        dir: ReorderDir,
    ): String {
        val spans = scanTags(svg)
        val target = spans.firstOrNull { it.id == id } ?: return svg
        // Siblings = other elements sharing the target's immediate parent context. The root
        // <svg> element must be excluded: top-level elements share its (null) parent context,
        // but the document root is a container, never a reorderable sibling.
        val isRootSvg: (TagSpan) -> Boolean = { s ->
            svg.substring(s.openStart, s.openEnd).trimStart().startsWith("<svg", ignoreCase = true)
        }
        val siblings = spans.filter { it !== target && !isRootSvg(it) && it.parentId == target.parentId }
        if (siblings.isEmpty()) return svg
        val ordered =
            (siblings + target)
                .sortedBy { it.openStart }
        val idx = ordered.indexOf(target)
        val n = ordered.size
        val newIdx =
            when (dir) {
                ReorderDir.FRONT -> n - 1
                ReorderDir.BACK -> 0
                ReorderDir.FORWARD -> (idx + 1).coerceAtMost(n - 1)
                ReorderDir.BACKWARD -> (idx - 1).coerceAtLeast(0)
            }
        if (newIdx == idx) return svg
        val chunkStart = target.openStart
        val chunkEnd = if (target.closeStart >= 0) target.closeEnd else target.openEnd
        val chunk = svg.substring(chunkStart, chunkEnd)
        val s2 = svg.substring(0, chunkStart) + svg.substring(chunkEnd)
        // Re-locate siblings in the trimmed source (the target is gone, so sibs2 has n-1 entries).
        val sibs2 = scanTags(s2).filter { !isRootSvg(it) && it.parentId == target.parentId }
        val ins =
            if (newIdx < sibs2.size) {
                sibs2[newIdx].openStart
            } else {
                val last = sibs2.last()
                if (last.closeStart >= 0) last.closeEnd else last.openEnd
            }
        return s2.substring(0, ins) + chunk + s2.substring(ins)
    }

    /** A unique `id` (relative to the existing ids in `svg`) whose name starts with `base`. */
    fun nextUniqueId(
        svg: String,
        base: String,
    ): String {
        val used = scanTags(svg).mapNotNull { it.id }.toHashSet()
        val root = if (base.isBlank()) "svg-el" else base
        var n = 1
        while (used.contains("$root-$n")) n++
        return "$root-$n"
    }

    /** Replace the FIRST `id="..."` attribute in `tag`/`chunk` with `newId` (the element's own id). */
    private fun retagFirstId(chunk: String, newId: String): String {
        val m = Regex("""\bid\s*=\s*["']([^"']*)["']""").find(chunk) ?: return chunk
        return chunk.replaceRange(m.range, "id=\"$newId\"")
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

    /**
     * Spans of every `<defs>` block in `svg` (open tag through matching `</defs>`, or just the
     * open tag when it is self-closing). Used by [soloElement] so class `<style>` rules and
     * referenced definitions (gradients, symbols, clip paths, `<use>` targets) survive the
     * slice. `scanTags` cannot supply this directly: it matches close tags for `<g>` groups
     * only, and `<defs>` is not a group.
     */
    private fun defsBlocks(svg: String): List<Pair<Int, Int>> {
        val out = mutableListOf<Pair<Int, Int>>()
        var i = 0
        while (i < svg.length) {
            val lt = svg.indexOf("<defs", i, ignoreCase = true)
            if (lt < 0) break
            val afterName = lt + 5
            val boundary = afterName >= svg.length || svg[afterName] == '>' || svg[afterName].isWhitespace()
            val gt = svg.indexOf('>', lt)
            if (!boundary || gt < 0) {
                i = afterName
                continue
            }
            if (svg.substring(lt, gt + 1).trimEnd().endsWith("/>")) {
                out.add(lt to gt + 1)
                i = gt + 1
                continue
            }
            // Depth-scan to the matching close tag (nested <defs> is illegal but harmless to count).
            var depth = 1
            var j = gt + 1
            var end = gt + 1
            while (j < svg.length && depth > 0) {
                val nlt = svg.indexOf('<', j)
                if (nlt < 0) break
                val ngt = svg.indexOf('>', nlt)
                if (ngt < 0) break
                val t = svg.substring(nlt, ngt + 1)
                val name = Regex("""^</?([A-Za-z][\w-]*)""").find(t)?.groupValues?.get(1)?.lowercase()
                if (name == "defs") {
                    if (t.startsWith("</")) {
                        depth--
                        if (depth == 0) end = ngt + 1
                    } else {
                        depth++
                    }
                }
                j = ngt + 1
            }
            out.add(lt to end)
            i = gt + 1
        }
        return out
    }
}
