package com.pan.svg.core

/**
 * Minimal, dependency-free JSON reader supporting exactly the subset produced by the
 * `resvg_bridge` native library: objects, arrays, numbers, strings (with escapes) and
 * the literals `true` / `false` / `null`.
 *
 * We deliberately avoid pulling in a serialization framework so the `core` module stays
 * lean and trivially testable on the JVM.
 */
object Json {
    fun parse(src: String): Any? = Parser(src).run {
        skipWs()
        val v = parseValue()
        skipWs()
        v
    }

    /**
     * Serialize a value tree to compact JSON. Supports the same types [parse] produces
     * (`Map` / `List` / `String` / `Number` / `Boolean` / `null`) plus `DoubleArray` and
     * `IntArray` for compact affine-matrix payloads. Non-finite doubles become `null`.
     */
    fun write(value: Any?): String {
        val sb = StringBuilder()
        writeValue(sb, value)
        return sb.toString()
    }

    private fun writeValue(
        sb: StringBuilder,
        value: Any?,
    ) {
        when (value) {
            null -> sb.append("null")
            is String -> writeString(sb, value)
            is Boolean -> sb.append(value)
            is Double -> {
                if (value.isFinite()) sb.append(value.toString()) else sb.append("null")
            }
            is Number -> sb.append(value.toString())
            is Map<*, *> -> {
                sb.append('{')
                var first = true
                for ((k, v) in value) {
                    if (!first) sb.append(',')
                    first = false
                    writeString(sb, k.toString())
                    sb.append(':')
                    writeValue(sb, v)
                }
                sb.append('}')
            }
            is List<*> -> {
                sb.append('[')
                value.forEachIndexed { i, v ->
                    if (i > 0) sb.append(',')
                    writeValue(sb, v)
                }
                sb.append(']')
            }
            is DoubleArray -> writeValue(sb, value.toList())
            is IntArray -> writeValue(sb, value.toList())
            else -> throw IllegalArgumentException("unsupported JSON value type: ${value::class.java.name}")
        }
    }

    private fun writeString(
        sb: StringBuilder,
        s: String,
    ) {
        sb.append('"')
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                else ->
                    if (c < ' ') {
                        sb.append("\\u")
                        sb.append(String.format("%04x", c.code))
                    } else {
                        sb.append(c)
                    }
            }
        }
        sb.append('"')
    }

    private class Parser(private val s: String) {
        var i = 0

        fun skipWs() {
            while (i < s.length && s[i].isWhitespace()) i++
        }

        fun parseValue(): Any? {
            skipWs()
            if (i >= s.length) return null
            return when (s[i]) {
                '{' -> parseObject()
                '[' -> parseArray()
                '"' -> parseString()
                't', 'f' -> parseBool()
                'n' -> {
                    i += 4
                    null
                }
                else -> parseNumber()
            }
        }

        fun parseObject(): Map<String, Any?> {
            expect('{')
            skipWs()
            val map = LinkedHashMap<String, Any?>()
            if (peek() == '}') {
                i++
                return map
            }
            while (true) {
                skipWs()
                val key = parseString()
                skipWs()
                expect(':')
                val v = parseValue()
                map[key] = v
                skipWs()
                if (peek() == ',') {
                    i++
                    continue
                }
                expect('}')
                break
            }
            return map
        }

        fun parseArray(): List<Any?> {
            expect('[')
            skipWs()
            val list = mutableListOf<Any?>()
            if (peek() == ']') {
                i++
                return list
            }
            while (true) {
                val v = parseValue()
                list.add(v)
                skipWs()
                if (peek() == ',') {
                    i++
                    continue
                }
                expect(']')
                break
            }
            return list
        }

        fun parseString(): String {
            expect('"')
            val sb = StringBuilder()
            while (i < s.length) {
                val c = s[i++]
                if (c == '"') return sb.toString()
                if (c == '\\') {
                    val e = s[i++]
                    sb.append(
                        when (e) {
                            '"' -> '"'
                            '\\' -> '\\'
                            '/' -> '/'
                            'b' -> '\b'
                            'f' -> '\u000C'
                            'n' -> '\n'
                            'r' -> '\r'
                            't' -> '\t'
                            'u' -> {
                                val hex = s.substring(i, i + 4)
                                i += 4
                                hex.toInt(16).toChar()
                            }
                            else -> e
                        },
                    )
                } else {
                    sb.append(c)
                }
            }
            return sb.toString()
        }

        fun parseNumber(): Number {
            val start = i
            while (i < s.length && s[i] in "+-0123456789.eE") i++
            val str = s.substring(start, i)
            return if ('.' in str || 'e' in str || 'E' in str) str.toDouble() else str.toLong()
        }

        fun parseBool(): Boolean =
            if (s.startsWith("true", i)) {
                i += 4
                true
            } else {
                i += 5
                false
            }

        fun peek(): Char = if (i < s.length) s[i] else '\u0000'

        fun expect(c: Char) {
            if (i < s.length && s[i] == c) {
                i++
            } else {
                throw RuntimeException("JSON: expected '$c' at index $i, got '${if (i < s.length) s[i] else "EOF"}'")
            }
        }
    }
}
