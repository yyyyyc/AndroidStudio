package com.example.cameratextlogger

import org.junit.Assert.assertEquals
import org.junit.Test

class TextDeduplicatorTest {

    private val dedup = TextDeduplicator(forgetAfterMs = 5_000)

    @Test
    fun `line seen in one frame is not reported`() {
        assertEquals(emptyList<String>(), dedup.newLines(listOf("misread"), 0))
    }

    @Test
    fun `line seen in two frames is reported once`() {
        dedup.newLines(listOf("12345"), 0)
        assertEquals(listOf("12345"), dedup.newLines(listOf("12345"), 100))
        assertEquals(emptyList<String>(), dedup.newLines(listOf("12345"), 200))
    }

    @Test
    fun `duplicates within one frame count as one sighting`() {
        assertEquals(emptyList<String>(), dedup.newLines(listOf("EXIT", "EXIT"), 0))
    }

    @Test
    fun `whitespace differences are the same line`() {
        dedup.newLines(listOf("  hello   world "), 0)
        assertEquals(listOf("hello world"), dedup.newLines(listOf("hello world"), 100))
    }

    @Test
    fun `blank lines are ignored`() {
        dedup.newLines(listOf("   "), 0)
        assertEquals(emptyList<String>(), dedup.newLines(listOf("   "), 100))
    }

    @Test
    fun `line stays quiet while in view longer than the forget window`() {
        dedup.newLines(listOf("STOP"), 0)
        dedup.newLines(listOf("STOP"), 100)
        for (t in 1_000L..20_000L step 1_000L) {
            assertEquals(emptyList<String>(), dedup.newLines(listOf("STOP"), t))
        }
    }

    @Test
    fun `line is reported once when frames take longer than the forget window`() {
        dedup.newLines(listOf("90210"), 0)
        assertEquals(listOf("90210"), dedup.newLines(listOf("90210"), 8_000))
        assertEquals(emptyList<String>(), dedup.newLines(listOf("90210"), 16_000))
    }

    @Test
    fun `line is reported on first sighting when one is enough`() {
        val eager = TextDeduplicator(sightingsRequired = 1)
        assertEquals(listOf("12345"), eager.newLines(listOf("12345"), 0))
        assertEquals(emptyList<String>(), eager.newLines(listOf("12345"), 100))
    }

    @Test
    fun `line is reported again after leaving view`() {
        dedup.newLines(listOf("STOP"), 0)
        dedup.newLines(listOf("STOP"), 100)
        dedup.newLines(emptyList(), 6_000)
        dedup.newLines(listOf("STOP"), 6_100)
        assertEquals(listOf("STOP"), dedup.newLines(listOf("STOP"), 6_200))
    }

    private val isPlate = { line: String -> Regex("\\d{2}-\\d{3}-\\d{2}").matches(line) }

    @Test
    fun `found text is reported on first sighting`() {
        assertEquals(listOf("12-345-67"), dedup.newLines(listOf("12-345-67", "misread"), 0, isPlate))
        assertEquals(emptyList<String>(), dedup.newLines(listOf("12-345-67"), 100, isPlate))
    }

    @Test
    fun `found text misread by one character is not reported again`() {
        dedup.newLines(listOf("12-345-67"), 0, isPlate)
        assertEquals(emptyList<String>(), dedup.newLines(listOf("12-345-61"), 100, isPlate))
        assertEquals(emptyList<String>(), dedup.newLines(listOf("12-345-67"), 200, isPlate))
    }

    @Test
    fun `found text differing by two characters is reported`() {
        dedup.newLines(listOf("12-345-67"), 0, isPlate)
        assertEquals(listOf("12-345-11"), dedup.newLines(listOf("12-345-11"), 100, isPlate))
    }

    @Test
    fun `short found text is never taken for a misread`() {
        val isFound = { _: String -> true }
        dedup.newLines(listOf("A1"), 0, isFound)
        assertEquals(listOf("A2"), dedup.newLines(listOf("A2"), 100, isFound))
    }
}
