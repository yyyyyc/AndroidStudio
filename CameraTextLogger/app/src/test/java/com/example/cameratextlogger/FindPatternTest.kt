package com.example.cameratextlogger

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FindPatternTest {

    @Test
    fun `plain text is found anywhere, ignoring case`() {
        val p = FindPattern("world")
        assertTrue(p.matches("HELLO WORLD"))
        assertFalse(p.matches("HELLO"))
    }

    @Test
    fun `alternatives find any of them`() {
        val p = FindPattern("AAA|BBB|CCC")
        assertTrue(p.matches("xx BBB yy"))
        assertTrue(p.matches("ccc"))
        assertFalse(p.matches("AB"))
    }

    @Test
    fun `spaces around alternatives and empty alternatives are ignored`() {
        val p = FindPattern(" AAA | | BBB ")
        assertTrue(p.matches("BBB"))
        assertFalse(p.matches("x"))
        assertTrue(FindPattern(" | ").isEmpty)
    }

    @Test
    fun `percent at the end means starts with`() {
        val p = FindPattern("ab%")
        assertTrue(p.matches("abc def"))
        assertTrue(p.matches("AB"))
        assertFalse(p.matches("x abc"))
    }

    @Test
    fun `percent at the start means ends with, on both sides contains`() {
        assertTrue(FindPattern("%ab").matches("xxab"))
        assertFalse(FindPattern("%ab").matches("abx"))
        assertTrue(FindPattern("%ab%").matches("xabx"))
    }

    @Test
    fun `underscore is one character and hash is one digit`() {
        assertTrue(FindPattern("a_c").matches("abc"))
        assertFalse(FindPattern("a_c").matches("abbc"))
        assertTrue(FindPattern("###-####").matches("555-1234"))
        assertFalse(FindPattern("###-####").matches("555-12x4"))
    }

    @Test
    fun `backslash makes wildcards and bars literal`() {
        val percent = FindPattern("100\\%")
        assertTrue(percent.matches("SALE 100% OFF"))
        assertFalse(percent.matches("1000"))
        assertTrue(FindPattern("A\\|B").matches("A|B"))
        assertFalse(FindPattern("A\\|B").matches("A"))
    }

    @Test
    fun `regex characters in the pattern are literal`() {
        assertTrue(FindPattern("(1.5)%").matches("(1.5) kg"))
        assertFalse(FindPattern("(1.5)%").matches("(125) kg"))
    }

    @Test
    fun `Hebrew patterns work`() {
        val shalom = "שלום" // "shalom"
        assertTrue(FindPattern("של%").matches(shalom))
        assertTrue(FindPattern("abc|$shalom").matches("x $shalom"))
    }

    @Test
    fun `highlight covers each plain occurrence`() {
        assertEquals(listOf(0..1, 3..4), FindPattern("ab").ranges("ab AB"))
    }

    @Test
    fun `highlight covers the whole line for wildcard matches`() {
        assertEquals(listOf(0..6), FindPattern("ab%").ranges("abc def"))
        assertEquals(emptyList<IntRange>(), FindPattern("ab%").ranges("x ab"))
    }

    @Test
    fun `overlapping highlights are merged`() {
        assertEquals(listOf(0..5), FindPattern("abc|cde|ef").ranges("abcdef"))
    }

    @Test
    fun `separators between digits match any separator or none`() {
        val plate = FindPattern("###-##-###")
        assertTrue(plate.matches("472.48.004"))
        assertTrue(plate.matches("472:48-004")) // How ML Kit read a real plate.
        assertTrue(plate.matches("472 48 004"))
        assertTrue(plate.matches("47248004"))
        assertFalse(plate.matches("472-48-00"))
        assertTrue(FindPattern("472-48-004").matches("472:48-004"))
    }

    @Test
    fun `number patterns are found inside a line but not inside a longer number`() {
        val plate = FindPattern("##-###-##|###-##-###")
        assertTrue(plate.matches("IL 472.48.004"))
        assertEquals(listOf(3..12), plate.ranges("IL 472.48.004"))
        assertFalse(plate.matches("1472.48.0045"))
        assertTrue(plate.matches("12-345-67"))
    }

    @Test
    fun `separators next to letters stay literal`() {
        assertFalse(FindPattern("A-1").matches("A:1"))
        assertTrue(FindPattern("A-1").matches("xA-1x"))
    }
}
