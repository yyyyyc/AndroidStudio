package com.example.cameratextlogger

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class HighlightsTest {

    private fun boxes(lines: List<TextLine>, find: String, readBy: ReadBy = ReadBy.LINES) =
        highlightBoxes(lines, FindPattern(find), readBy)

    @Test
    fun `a match inside a line gets a box over just its characters`() {
        val line = TextLine("HELLO WORLD", 0, 10, 1100, 50)
        assertEquals(listOf(HighlightBox(600, 10, 1100, 50)), boxes(listOf(line), "world"))
    }

    @Test
    fun `each match gets its own box`() {
        val line = TextLine("AB AB", 0, 0, 500, 20)
        assertEquals(
            listOf(HighlightBox(0, 0, 200, 20), HighlightBox(300, 0, 500, 20)),
            boxes(listOf(line), "ab"),
        )
    }

    @Test
    fun `lines without the text get no box`() {
        val lines = listOf(TextLine("HELLO", 0, 0, 100, 20), TextLine("WORLD", 0, 30, 100, 50))
        assertEquals(listOf(HighlightBox(0, 30, 100, 50)), boxes(lines, "world"))
    }

    @Test
    fun `nothing is highlighted without search text`() {
        assertEquals(emptyList<HighlightBox>(), boxes(listOf(TextLine("HELLO", 0, 0, 100, 20)), ""))
    }

    @Test
    fun `a wildcard pattern highlights the whole line`() {
        val line = TextLine("555-1234", 40, 0, 840, 20)
        assertEquals(listOf(HighlightBox(40, 0, 840, 20)), boxes(listOf(line), "###-####"))
    }

    @Test
    fun `hebrew is matched from the right`() {
        val line = TextLine("שלום עולם", 0, 0, 900, 20)
        assertEquals(listOf(HighlightBox(500, 0, 900, 20)), boxes(listOf(line), "שלום"))
    }

    @Test
    fun `a number after hebrew text is drawn on the left`() {
        val line = TextLine("מחיר 12345", 0, 0, 1000, 20)
        assertEquals(listOf(HighlightBox(0, 0, 500, 20)), boxes(listOf(line), "12345"))
    }

    @Test
    fun `english columns are in reading order`() {
        assertArrayEquals(intArrayOf(0, 1, 2), visualColumns("abc"))
    }

    @Test
    fun `text spanning a paragraph's lines highlights the paragraph`() {
        val lines = listOf(TextLine("HELLO", 0, 0, 100, 20), TextLine("WORLD", 0, 25, 120, 45))
        assertEquals(listOf(HighlightBox(0, 0, 120, 45)), boxes(lines, "hello world", ReadBy.PARAGRAPHS))
        assertEquals(emptyList<HighlightBox>(), boxes(lines, "hello world", ReadBy.LINES))
    }

    @Test
    fun `in paragraphs a match inside one line still gets a small box`() {
        val lines = listOf(TextLine("HELLO", 0, 0, 100, 20), TextLine("WORLD", 0, 25, 100, 45))
        assertEquals(listOf(HighlightBox(0, 25, 100, 45)), boxes(lines, "world", ReadBy.PARAGRAPHS))
    }
}
