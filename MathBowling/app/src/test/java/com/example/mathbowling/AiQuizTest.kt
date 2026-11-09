package com.example.mathbowling

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class AiQuizTest {

    @Test
    fun quizContentIsComplete() {
        val items = AiQuiz.items
        for (level in Level.values()) {
            assertTrue("questions for $level", items.count { it.first == level } >= 10)
        }
        for ((_, he, en) in items) {
            for (item in listOf(he, en)) {
                val answers = listOf(item.correct, item.wrong1, item.wrong2)
                assertEquals("distinct answers: $item", 3, answers.toSet().size)
                assertTrue("question text: $item", item.question.length >= 8)
                assertTrue("explanation: $item", item.explanation.length >= 20)
                // answers must fit on a cone sign (at most two short lines)
                for (a in answers) assertTrue("answer too long for a sign: '$a'", a.length <= 28)
            }
            assertTrue("Hebrew item is in Hebrew: $he", he.question.any { it.code in 0x05D0..0x05EA })
        }
        assertEquals("no duplicate questions", items.size, items.map { it.second.question }.toSet().size)
    }

    @Test
    fun questionsAreWellFormed() {
        for (english in listOf(false, true)) for (level in Level.values()) {
            val gen = AiQuestionGenerator(english, Random(3))
            val seen = HashSet<Int>()
            repeat(500) {
                val q = gen.next(level)
                assertEquals(3, q.options.size)
                assertEquals(3, q.labels.toSet().size)
                assertTrue(q.correctIndex in 0..2)
                assertTrue(q.explanation.isNotEmpty())
                assertTrue(q.textExpression)
                seen.add(q.correctIndex)
            }
            assertEquals("the right answer appears on every cone position", setOf(0, 1, 2), seen)
        }
    }
}
