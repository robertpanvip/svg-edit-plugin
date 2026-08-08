package com.example.svgeditor.core

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
