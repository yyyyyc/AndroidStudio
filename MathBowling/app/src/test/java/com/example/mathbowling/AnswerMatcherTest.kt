package com.example.mathbowling

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AnswerMatcherTest {

    @Test
    fun numbersAsDigitsOrWords() {
        val labels = listOf("12", "7", "17")
        assertEquals(2, AnswerMatcher.match(listOf("17"), labels))
        assertEquals(1, AnswerMatcher.match(listOf("שבע"), labels))
        assertEquals(1, AnswerMatcher.match(listOf("seven"), labels))
        assertEquals(0, AnswerMatcher.match(listOf("התשובה היא 12"), labels))
        // the last number that is one of the cones wins
        assertEquals(0, AnswerMatcher.match(listOf("7 ועוד 5 זה 12"), labels))
        assertNull(AnswerMatcher.match(listOf("לא יודע"), labels))
        assertNull(AnswerMatcher.match(listOf("99"), labels))
        // falls back to the recognizer's second guess
        assertEquals(2, AnswerMatcher.match(listOf("hello", "17"), labels))
    }

    @Test
    fun hebrewWords() {
        val labels = listOf("כחול", "צהוב", "שחור")
        assertEquals(1, AnswerMatcher.match(listOf("צהוב"), labels))
        assertEquals(1, AnswerMatcher.match(listOf("זה צהוב"), labels))
        assertNull(AnswerMatcher.match(listOf("אדום"), labels))
    }

    @Test
    fun longestLabelWins() {
        val labels = listOf("לא", "על לא דבר", "תודה")
        assertEquals(1, AnswerMatcher.match(listOf("על לא דבר"), labels))
        assertEquals(0, AnswerMatcher.match(listOf("לא"), labels))
    }

    @Test
    fun ignoresMarksAndPunctuation() {
        assertEquals(0, AnswerMatcher.match(listOf("كتب"), listOf("كَتَبَ", "أَكَلَ", "شَرِبَ")))
        assertEquals(1, AnswerMatcher.match(listOf("Собака"), listOf("кот", "соба́ка", "слон")))
        assertEquals(2, AnswerMatcher.match(listOf("מה שמך"), listOf("כן", "תודה", "מה שמך?")))
        assertEquals(0, AnswerMatcher.match(listOf("жёлтый"), listOf("желтый", "синий", "белый")))
    }

    @Test
    fun partialPhrase() {
        val labels = listOf("Computers that learn", "A plastic robot", "A kind of internet")
        assertEquals(0, AnswerMatcher.match(listOf("computers learn"), labels))
        assertEquals(1, AnswerMatcher.match(listOf("plastic robot"), labels))
    }

    @Test
    fun thePhonesOwnReadingIsNotTakenAsTheAnswer() {
        val spoken = "כמה זה? 5 ועוד 3 שווה כמה"
        val labels = listOf("8", "3", "9")
        // the microphone picked up only the reading: nothing is left, so nothing is answered
        val echo = AnswerMatcher.withoutEcho("כמה זה חמש עוד שלוש", spoken)
        assertEquals("", echo)
        // without the filter the reading itself would have "answered" 3
        assertEquals(1, AnswerMatcher.match(listOf("כמה זה חמש עוד שלוש"), labels))
        // the player shouted over the reading
        val mixed = AnswerMatcher.withoutEcho("כמה זה חמש ועוד שמונה", spoken)
        assertEquals("שמונה", mixed)
        assertEquals(0, AnswerMatcher.match(listOf(mixed), labels))
        assertEquals("9", AnswerMatcher.withoutEcho("5 ועוד 3 9", spoken))

        // words: the question's words go, the player's answer stays
        val q = "What is the capital of France?"
        assertEquals("paris", AnswerMatcher.withoutEcho("what is the capital of paris", q))
        assertEquals(0, AnswerMatcher.match(listOf("paris"), listOf("Paris", "London", "Rome")))
    }
}
