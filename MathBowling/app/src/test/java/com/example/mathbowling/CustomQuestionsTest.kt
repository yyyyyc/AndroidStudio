package com.example.mathbowling

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class CustomQuestionsTest {

    @Test
    fun samplesParseWithoutErrors() {
        for (english in listOf(false, true)) {
            val r = CustomQuestions.parse(CustomQuestions.sample(english))
            assertTrue("bad lines in sample: ${r.badLines}", r.badLines.isEmpty())
            assertEquals(7, r.items.size)
            assertTrue(r.items.first().explanation.isNotEmpty())
            assertEquals(Level.EASY, r.items.first().level)
            assertEquals(Level.HARD, r.items.last().level)
        }
    }

    @Test
    fun parsesLevelsCommentsExplanationsAndReportsBadLines() {
        val text = "﻿# comment\r\n" +
            "2+2? | 4 | 5 | 3\r\n" +
            "> because\r\n" +
            "> two and two\r\n" +
            "[קשה]\n" +
            "only one wrong | a | b\n" +           // line 6: too few answers
            "dup | a | b | b\n" +                  // line 7: the two wrong answers are the same
            "Q | yes | no | maybe | never | nope\n" +
            "[nonsense]\n" +                       // line 9: unknown level
            "[ALL]\n" +
            "last | 1 | 2 | 3\n"
        val r = CustomQuestions.parse(text)
        assertEquals(listOf(6, 7, 9), r.badLines)
        assertEquals(3, r.items.size)
        assertEquals("because two and two", r.items[0].explanation)
        assertEquals(null, r.items[0].level)
        assertEquals(Level.HARD, r.items[1].level)
        assertEquals(listOf("no", "maybe", "never", "nope"), r.items[1].wrong)
        assertEquals(null, r.items[2].level)
    }

    @Test
    fun generatorServesEveryQuestionBeforeRepeating() {
        val items = CustomQuestions.parse(CustomQuestions.sample(false)).items
        val gen = CustomQuestionGenerator(items, Random(3))
        val easyPool = items.filter { it.level == null || it.level == Level.EASY }
        val seen = (1..easyPool.size).map { gen.next(Level.EASY).prompt }
        assertEquals(easyPool.map { it.question }.toSet(), seen.toSet())
        repeat(200) {
            val q = gen.next(Level.HARD)
            assertTrue(q.options.size in 3..4)
            assertEquals(q.options.size, q.labels.toSet().size)
            val item = items.first { it.question == q.prompt }
            assertEquals(item.correct, q.answerLabel)
        }
    }

    @Test
    fun levelWithoutQuestionsUsesTheWholeFile() {
        val items = CustomQuestions.parse("[HARD]\na | 1 | 2 | 3\nb | 1 | 2 | 3\n").items
        val q = CustomQuestionGenerator(items, Random(1)).next(Level.EASY)
        assertTrue(q.prompt == "a" || q.prompt == "b")
    }

    @Test
    fun manyWrongAnswersMakeAtMostFourCones() {
        val items = CustomQuestions.parse("q | r | w1 | w2 | w3 | w4 | w5\n").items
        val gen = CustomQuestionGenerator(items, Random(9))
        repeat(50) {
            val q = gen.next(Level.MEDIUM)
            assertEquals(4, q.options.size)
            assertEquals("r", q.answerLabel)
        }
    }

    @Test
    fun startMessageCarriesTheQuestionsFile() {
        val text = CustomQuestions.sample(false)
        val msg = NetMsg.Start(42L, GameMode.CUSTOM, Level.MEDIUM, true, text)
        val line = msg.encode()
        assertTrue("one line", !line.contains('\n'))
        assertEquals(msg, NetMsg.decode(line))
        // older messages without a file still decode
        assertEquals(NetMsg.Start(1L, GameMode.MATH, Level.EASY, false), NetMsg.decode("START|1|MATH|EASY|0"))
    }
}
