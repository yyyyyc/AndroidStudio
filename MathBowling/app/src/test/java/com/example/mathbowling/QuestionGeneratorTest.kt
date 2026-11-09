package com.example.mathbowling

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class QuestionGeneratorTest {

    /** Tiny recursive-descent evaluator for + − × ÷ ( ) ² √ %. */
    private class Eval(src: String) {
        private val s = src.replace(" ", "")
        private var i = 0

        fun parse(): Double {
            val v = expr()
            check(i == s.length) { "Trailing input in '$s' at $i" }
            return v
        }

        private fun expr(): Double {
            var v = term()
            while (i < s.length && (s[i] == '+' || s[i] == '−' || s[i] == '-')) {
                val op = s[i++]
                val r = term()
                v = if (op == '+') v + r else v - r
            }
            return v
        }

        private fun term(): Double {
            var v = factor()
            while (i < s.length && (s[i] == '×' || s[i] == '÷')) {
                val op = s[i++]
                val r = factor()
                v = if (op == '×') v * r else v / r
            }
            return v
        }

        private fun factor(): Double {
            var v: Double
            if (s[i] == '√') {
                i++
                return kotlin.math.sqrt(factor())
            }
            if (s[i] == '(') {
                i++
                v = expr()
                check(s[i] == ')')
                i++
            } else {
                val start = i
                while (i < s.length && s[i].isDigit()) i++
                v = s.substring(start, i).toDouble()
            }
            if (i < s.length && s[i] == '²') { i++; v *= v }
            if (i < s.length && s[i] == '%') { i++; v /= 100 }
            return v
        }
    }

    @Test
    fun questionsAreWellFormedAndCorrect() {
        for (english in listOf(false, true)) for (level in Level.values()) {
            val gen = QuestionGenerator(english, Random(42))
            repeat(3000) {
                val q = gen.next(level)
                assertEquals("option count for $level", level.options, q.options.size)
                assertEquals("options must be distinct: $q", q.options.size, q.options.toSet().size)
                assertTrue("answer among options: $q", q.correctIndex >= 0)
                assertTrue("non-negative options: $q", q.options.all { it >= 0 })
                assertTrue("prompt present: $q", q.prompt.isNotBlank())

                val e = q.expression
                if (e.contains("=")) {
                    val (left, right) = e.replace("?", q.answer.toString()).split("=")
                    assertEquals("expression '$e' answer ${q.answer}", Eval(left).parse(), Eval(right).parse(), 1e-9)
                } else if (e.endsWith(", ?")) {
                    val terms = e.removeSuffix(", ?").split(", ").map { it.trim().toInt() }
                    val d = terms[1] - terms[0]
                    val arithmetic = terms.zipWithNext().all { (a, b) -> b - a == d }
                    val expected = if (arithmetic) terms.last() + d else terms.last() * (terms[1] / terms[0])
                    assertEquals("series '$e'", expected, q.answer)
                }
            }
        }
    }
}
