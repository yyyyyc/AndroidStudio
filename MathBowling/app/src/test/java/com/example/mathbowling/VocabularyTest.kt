package com.example.mathbowling

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class VocabularyTest {

    private fun inScript(lang: ForeignLanguage, c: Char) = when (lang) {
        ForeignLanguage.ARABIC -> c.code in 0x0600..0x06FF
        ForeignLanguage.RUSSIAN -> c.code in 0x0400..0x04FF
    }

    @Test
    fun vocabularyIsConsistent() {
        for (lang in ForeignLanguage.values()) {
            val words = lang.words
            assertTrue("$lang vocabulary is loaded", words.size > 100)
            for (level in Level.values()) {
                assertTrue("$lang words for $level", words.count { it.level == level } >= 20)
            }
            // inside a category, meanings and words must be unique, or a question could have two right cones
            for ((cat, list) in words.groupBy { it.category }) {
                assertEquals("$lang Hebrew meanings unique in $cat", list.size, list.map { it.he }.toSet().size)
                assertEquals("$lang English meanings unique in $cat", list.size, list.map { it.en }.toSet().size)
                assertEquals("$lang words unique in $cat", list.size, list.map { it.word }.toSet().size)
            }
            for (w in words) {
                assertTrue("$lang script in ${w.word}", w.word.any { inScript(lang, it) })
                assertTrue("Hebrew transliteration for ${w.word}", w.translitHe.any { it.code in 0x05D0..0x05EA })
                assertTrue("Hebrew meaning for ${w.word}", w.he.any { it.code in 0x05D0..0x05EA })
                assertTrue("Latin transliteration for ${w.word}", w.translitEn.isNotBlank())
                assertTrue("English meaning for ${w.word}", w.en.isNotBlank())
            }
        }
        // both languages teach the same topics, so the modes feel alike
        assertEquals(
            ForeignLanguage.ARABIC.words.map { it.category }.toSet(),
            ForeignLanguage.RUSSIAN.words.map { it.category }.toSet()
        )
    }

    @Test
    fun questionsAreWellFormed() {
        for (lang in ForeignLanguage.values()) for (english in listOf(false, true)) for (level in Level.values()) {
            val words = lang.words
            val gen = VocabQuestionGenerator(lang, english, Random(7))
            var reverse = 0
            repeat(2000) {
                val q = gen.next(level)
                assertEquals("three cones: $q", 3, q.options.size)
                assertEquals("distinct options: $q", 3, q.options.toSet().size)
                assertEquals("distinct labels: $q", 3, q.labels.toSet().size)
                assertTrue("answer among options: $q", q.correctIndex >= 0)
                assertTrue("text question: $q", q.textExpression)
                assertEquals(lang.ttsTag, q.foreignLang)
                val w = words[q.answer]
                assertEquals("spoken answer is the foreign word", w.word, q.foreignAnswer)
                if (q.expression == w.word) {
                    assertEquals(if (english) w.en else w.he, q.answerLabel)
                    assertEquals(w.word, q.foreignSpeech)
                    assertEquals(if (english) w.translitEn else w.translitHe, q.hint)
                    assertEquals("forward questions use the level's words", level, w.level)
                } else {
                    reverse++
                    assertEquals(if (english) w.en else w.he, q.expression)
                    assertEquals(w.word, q.answerLabel)
                }
            }
            if (level == Level.EASY) assertEquals("no reverse questions on easy", 0, reverse)
            else assertTrue("some reverse questions on $level", reverse > 200)
        }
    }
}
