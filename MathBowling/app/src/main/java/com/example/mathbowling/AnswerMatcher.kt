package com.example.mathbowling

/**
 * Decides which cone the player named out loud.
 * Speech recognizers return a few guesses; numbers usually come back as digits, but small ones may come as words.
 */
object AnswerMatcher {

    private val numberWords: Map<String, Int> = buildMap {
        // Hebrew, both genders, as recognizers usually write them
        val he = listOf(
            listOf("אפס"), listOf("אחד", "אחת"), listOf("שניים", "שתיים", "שני", "שתי"), listOf("שלושה", "שלוש"),
            listOf("ארבעה", "ארבע"), listOf("חמישה", "חמש"), listOf("שישה", "שש"), listOf("שבעה", "שבע"),
            listOf("שמונה"), listOf("תשעה", "תשע"), listOf("עשרה", "עשר")
        )
        he.forEachIndexed { n, words -> words.forEach { put(it, n) } }
        val en = listOf(
            "zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten",
            "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen", "seventeen", "eighteen", "nineteen", "twenty"
        )
        en.forEachIndexed { n, w -> put(w, n) }
    }

    /** Lower-case, no vowel marks / stress marks / punctuation, single spaces. */
    fun normalize(s: String): String {
        val sb = StringBuilder()
        for (ch in s.lowercase()) {
            val c = ch.code
            when {
                ch == '־' || ch == '-' -> sb.append(' ')            // maqaf / hyphen join words
                c in 0x0591..0x05C7 -> {}                           // Hebrew niqqud and cantillation
                c in 0x064B..0x065F || c == 0x0670 -> {}            // Arabic harakat
                c == 0x0301 -> {}                                   // stress mark
                ch == 'ё' -> sb.append('е')
                ch == 'ـ' -> {}                                     // Arabic tatweel
                ch.isLetterOrDigit() || ch == ' ' -> sb.append(ch)
                else -> sb.append(' ')
            }
        }
        return sb.toString().trim().replace(Regex("\\s+"), " ")
    }

    /** Hebrew prefixes like ו/ה ("והשבע") are dropped before looking a word up. */
    private fun bare(w: String) = if (w.length > 2 && (w[0] == 'ו' || w[0] == 'ה')) w.substring(1) else w

    private fun numbersIn(text: String): List<Int> {
        val out = ArrayList<Int>()
        Regex("\\d+").findAll(text).forEach { m -> m.value.toIntOrNull()?.let(out::add) }
        for (w in text.split(' ')) {
            (numberWords[w] ?: numberWords[bare(w)])?.let(out::add)
        }
        return out
    }

    /**
     * While the phone reads the question aloud, the microphone hears it too. This drops from [heard] every word
     * and number that is in [spoken] (the text being read), leaving only what the player added.
     */
    fun withoutEcho(heard: String, spoken: String): String {
        val sp = normalize(spoken)
        val spokenWords = sp.split(' ').filter { it.isNotEmpty() }.flatMap { listOf(it, bare(it)) }.toSet()
        val spokenNumbers = numbersIn(sp).toSet()
        return normalize(heard).split(' ').filter { w ->
            if (w.isEmpty()) return@filter false
            val b = bare(w)
            if (w in spokenWords || b in spokenWords) return@filter false
            val n = w.toIntOrNull() ?: numberWords[w] ?: numberWords[b]
            n == null || n !in spokenNumbers
        }.joinToString(" ")
    }

    /**
     * Returns the index of the label the player said, or null if no label clearly matches.
     * [heard] = the recognizer's guesses, best first.
     */
    fun match(heard: List<String>, labels: List<String>): Int? {
        if (labels.isEmpty()) return null
        val numeric = labels.all { it.trim().toIntOrNull() != null }
        for (guess in heard) {
            val g = normalize(guess)
            if (g.isEmpty()) continue
            if (numeric) {
                val values = labels.map { it.trim().toInt() }
                // the last number said is the answer ("seven plus five is twelve" -> 12)
                val said = numbersIn(g).lastOrNull { it in values } ?: continue
                return values.indexOf(said)
            }
            val norm = labels.map { normalize(it) }
            // a label said in full; prefer the longest so "לא" doesn't beat "על לא דבר"
            val contained = norm.indices.filter { norm[it].isNotEmpty() && " $g ".contains(" ${norm[it]} ") }
            if (contained.isNotEmpty()) return contained.maxByOrNull { norm[it].length }
            // otherwise the label with the most words in common, if it is a clear winner
            val gWords = g.split(' ').toSet()
            val scores = norm.map { l ->
                val lw = l.split(' ').filter { it.length > 1 }
                if (lw.isEmpty()) 0.0 else lw.count { it in gWords }.toDouble() / lw.size
            }
            val best = scores.indices.maxByOrNull { scores[it] } ?: continue
            val second = scores.filterIndexed { i, _ -> i != best }.maxOrNull() ?: 0.0
            if (scores[best] >= 0.5 && scores[best] > second) return best
        }
        return null
    }
}
