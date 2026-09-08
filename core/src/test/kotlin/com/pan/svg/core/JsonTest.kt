package com.pan.svg.core

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class JsonTest {
    @Test
    fun `writes scalars`() {
        assertEquals("null", Json.write(null))
        assertEquals("true", Json.write(true))
        assertEquals("false", Json.write(false))
        assertEquals("\"hi\"", Json.write("hi"))
        assertEquals("42", Json.write(42L))
        assertEquals("1.5", Json.write(1.5))
    }

    @Test
    fun `non finite doubles become null`() {
        assertEquals("null", Json.write(Double.NaN))
        assertEquals("null", Json.write(Double.POSITIVE_INFINITY))
        assertEquals("null", Json.write(Double.NEGATIVE_INFINITY))
    }

    @Test
    fun `escapes strings per the json spec`() {
        assertEquals("\"a\\\"b\\\\c\\nd\\te\"", Json.write("a\"b\\c\nd\te"))
        assertEquals("\"\\u0001\"", Json.write("\u0001"))
        assertEquals("\"\\r\\b\\f\"", Json.write("\r\b\u000C"))
    }

    @Test
    fun `writes maps in insertion order`() {
        val m = linkedMapOf("b" to 1L, "a" to "x")
        assertEquals("""{"b":1,"a":"x"}""", Json.write(m))
    }

    @Test
    fun `writes lists and nested structures`() {
        val v = listOf(1L, listOf("x", null), linkedMapOf("k" to true))
        assertEquals("""[1,["x",null],{"k":true}]""", Json.write(v))
    }

    @Test
    fun `writes double and int arrays compactly`() {
        assertEquals("[1.0,0.0,0.0,1.0,40.0,40.0]", Json.write(doubleArrayOf(1.0, 0.0, 0.0, 1.0, 40.0, 40.0)))
        assertEquals("[1,2,3]", Json.write(intArrayOf(1, 2, 3)))
    }

    @Test
    fun `rejects unsupported types`() {
        assertThrows(IllegalArgumentException::class.java) { Json.write(Any()) }
    }

    @Test
    fun `parse produces longs for integers and doubles for fractions`() {
        val o = Json.parse("""{"i":7,"d":2.5,"e":1e3,"neg":-3}""") as Map<*, *>
        assertEquals(7L, o["i"])
        assertEquals(2.5, o["d"])
        assertEquals(1000.0, o["e"])
        assertEquals(-3L, o["neg"])
    }

    @Test
    fun `parse handles unicode and solidus escapes`() {
        assertEquals("é/", Json.parse(""""é\/""""))
    }

    @Test
    fun `write then parse round trips`() {
        val v = linkedMapOf("a" to listOf(1L, 2.5, null, true), "b" to "quote\"\\slash\n")
        assertEquals(v, Json.parse(Json.write(v)))
    }

    @Test
    fun `parse tolerates whitespace`() {
        val o = Json.parse("  { \"a\" : [ 1 , 2 ] }  ") as Map<*, *>
        assertEquals(listOf(1L, 2L), o["a"])
    }
}
