package com.example.cameratextlogger

/**
 * What a session looks for. Case is ignored.
 *
 * - `|` separates alternatives: `AAA|BBB` finds either.
 * - Plain text is found anywhere in a line: `world` finds "HELLO WORLD".
 * - With a wildcard the whole line must match: `%` is any text, `_` any one character and `#` any
 *   digit. So `ab%` finds lines starting with "ab", `%ab` lines ending with it, `###-####` numbers
 *   like "555-1234".
 * - `\` makes the next character literal: `100\%` finds "100%".
 * - A separator between digits (`-`, `.`, `:`, `·`, `,` or a space) matches any of them, or none:
 *   license plates use dots that are read as dashes or colons, so `###-##-###` finds
 *   "472.48.004", "472:48-004" and "47248004".
 * - A pattern of only digits, `#`s and separators is a number, found anywhere in a line but not
 *   inside a longer number: `###-##-###` finds "IL 472.48.004" but not "1472.48.0045".
 * - `@name` is a ready-made [Detector]: `@phone|@email` finds phone numbers and email addresses.
 */
class FindPattern(source: String) {

    /**
     * One of [literal], [regex] or [detector] is set. An [anchored] regex must match the whole
     * line; one that isn't, plain text with flexible separators, is found anywhere in it.
     */
    private class Alternative(
        val literal: String?,
        val regex: Regex?,
        val anchored: Boolean = true,
        val detector: Detector? = null,
    )

    private val alternatives = splitAlternatives(source).mapNotNull(::compile)

    val isEmpty get() = alternatives.isEmpty()

    fun matches(line: String) = alternatives.any { alternative ->
        val regex = alternative.regex
        when {
            alternative.detector != null -> alternative.detector.ranges(line).isNotEmpty()
            regex == null -> line.contains(alternative.literal!!, ignoreCase = true)
            alternative.anchored -> regex.matches(line)
            else -> regex.containsMatchIn(line)
        }
    }

    /** The parts of [line] to highlight, in order and not overlapping. */
    fun ranges(line: String): List<IntRange> {
        val ranges = alternatives.flatMap { alternative ->
            val regex = alternative.regex
            when {
                alternative.detector != null -> alternative.detector.ranges(line)
                regex == null -> occurrences(line, alternative.literal!!)
                !alternative.anchored -> regex.findAll(line).map { it.range }.toList()
                regex.matches(line) -> listOf(line.indices)
                else -> emptyList()
            }
        }
        return merge(ranges)
    }

    private companion object {

        const val SEPARATORS = "-.:·•, "
        const val ANY_SEPARATOR = "[-.:·•,\\s]?"

        fun splitAlternatives(source: String): List<String> {
            val parts = mutableListOf<String>()
            val current = StringBuilder()
            var i = 0
            while (i < source.length) {
                val c = source[i]
                when {
                    c == '\\' && i + 1 < source.length -> {
                        current.append(c).append(source[i + 1])
                        i++
                    }
                    c == '|' -> {
                        parts += current.toString()
                        current.clear()
                    }
                    else -> current.append(c)
                }
                i++
            }
            parts += current.toString()
            return parts
        }

        fun compile(part: String): Alternative? {
            val trimmed = part.trim()
            if (trimmed.isEmpty()) return null
            Detector.forToken(trimmed)?.let { return Alternative(null, null, detector = it) }
            val literal = StringBuilder()
            val regex = StringBuilder()
            var hasWildcard = false
            var hasSeparator = false
            var afterDigit = false

            // Whether a digit, or #, any digit, starts at [index].
            fun digitAt(index: Int): Boolean {
                val c = trimmed.getOrNull(index) ?: return false
                return c == '#' || c.isDigit() || (c == '\\' && trimmed.getOrNull(index + 1)?.isDigit() == true)
            }

            var i = 0
            while (i < trimmed.length) {
                val c = trimmed[i]
                if (c == '\\' && i + 1 < trimmed.length) {
                    literal.append(trimmed[i + 1])
                    regex.append(Regex.escape(trimmed[i + 1].toString()))
                    afterDigit = trimmed[i + 1].isDigit()
                    i += 2
                    continue
                }
                val wildcard = when (c) {
                    '%' -> ".*"
                    '_' -> "."
                    '#' -> "\\d"
                    else -> null
                }
                when {
                    wildcard != null -> {
                        hasWildcard = true
                        regex.append(wildcard)
                    }
                    c in SEPARATORS && afterDigit && digitAt(i + 1) -> {
                        hasSeparator = true
                        literal.append(c)
                        regex.append(ANY_SEPARATOR)
                    }
                    else -> {
                        literal.append(c)
                        regex.append(Regex.escape(c.toString()))
                    }
                }
                afterDigit = c == '#' || c.isDigit()
                i++
            }
            // Only digits, #s and separators: a number such as a license plate, found anywhere in
            // a line ("IL 472.48.004") but not inside a longer number.
            val numberOnly = trimmed.all { it.isDigit() || it == '#' || it in SEPARATORS || it == '\\' }
            return when {
                numberOnly && (hasWildcard || hasSeparator) -> Alternative(
                    null, Regex("(?<!\\d)$regex(?!\\d)", RegexOption.IGNORE_CASE), anchored = false
                )
                hasWildcard -> Alternative(null, Regex(regex.toString(), RegexOption.IGNORE_CASE))
                hasSeparator ->
                    Alternative(null, Regex(regex.toString(), RegexOption.IGNORE_CASE), anchored = false)
                else -> Alternative(literal.toString(), null)
            }
        }

        fun occurrences(line: String, literal: String): List<IntRange> {
            val ranges = mutableListOf<IntRange>()
            var start = line.indexOf(literal, ignoreCase = true)
            while (start >= 0) {
                ranges += start until start + literal.length
                start = line.indexOf(literal, start + literal.length, ignoreCase = true)
            }
            return ranges
        }

        fun merge(ranges: List<IntRange>): List<IntRange> {
            val merged = mutableListOf<IntRange>()
            for (range in ranges.sortedBy { it.first }) {
                val last = merged.lastOrNull()
                if (last != null && range.first <= last.last + 1) {
                    merged[merged.lastIndex] = last.first..maxOf(last.last, range.last)
                } else {
                    merged += range
                }
            }
            return merged
        }
    }
}
