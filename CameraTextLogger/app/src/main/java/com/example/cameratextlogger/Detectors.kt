package com.example.cameratextlogger

// Building blocks shared by the detectors below.
private const val SEP = """[-.\s]?"""
private const val PLATE_SEP = """[-.:·•\s]"""
private const val NOT_LETTER_BEFORE = """(?<!\p{L})"""
private const val NOT_LETTER_AFTER = """(?!\p{L})"""
private const val EN_MONTH =
    """(?:jan|feb|mar|apr|may|jun|jul|aug|sep|sept|oct|nov|dec)[a-z]*\.?"""
private const val HE_MONTH =
    "(?:ינואר|פברואר|מרץ|מרס|אפריל|מאי|יוני|יולי|אוגוסט|ספטמבר|אוקטובר|נובמבר|דצמבר)"
private const val DAY = """(?:0?[1-9]|[12]\d|3[01])"""
private const val MONTH = """(?:0?[1-9]|1[0-2])"""
private const val AMOUNT = """(?:\d{1,3}(?:,\d{3})+|\d+)(?:\.\d{1,2})?"""
private const val CURRENCY_BEFORE = "(?:[₪${'$'}€£]|NIS|ILS|USD|EUR)"
private const val CURRENCY_AFTER =
    "(?:[₪${'$'}€£]|ש[\"״']?ח|שקל(?:ים)?|דולר(?:ים)?|אירו|אגורות|NIS|ILS|USD|EUR)"

private fun regex(vararg alternatives: String) =
    Regex(alternatives.joinToString("|") { "(?:$it)" }, RegexOption.IGNORE_CASE)

private fun digits(text: String) = text.filter { it.isDigit() }

/** The Luhn checksum credit card numbers carry. */
private fun luhn(number: String): Boolean {
    var sum = 0
    number.reversed().forEachIndexed { i, c ->
        val d = c.digitToInt() * (if (i % 2 == 1) 2 else 1)
        sum += if (d > 9) d - 9 else d
    }
    return sum % 10 == 0
}

/** The check digit of an Israeli ID number: Luhn on all nine digits. */
private fun israeliId(number: String) = number.length == 9 && luhn(number)

/** The mod-97 checksum of an IBAN. */
private fun iban(text: String): Boolean {
    val compact = text.filterNot { it.isWhitespace() }.uppercase()
    if (compact.length !in 15..34) return false
    val rearranged = compact.drop(4) + compact.take(4)
    var remainder = 0
    for (c in rearranged) {
        val value = if (c.isDigit()) c.digitToInt().toString() else (c - 'A' + 10).toString()
        for (d in value) remainder = (remainder * 10 + d.digitToInt()) % 97
    }
    return remainder == 1
}

/**
 * Ready-made searches for common kinds of text, used in a search as `@name`: `@phone` finds phone
 * numbers. They look at the text alone, so they work offline and in Hebrew and English, but only
 * find the formats written here.
 */
enum class Detector(
    val label: String,
    private val regex: Regex,
    private val valid: (String) -> Boolean = { true },
) {
    PHONE(
        "Phone",
        regex(
            // Israeli: 050-1234567, 03-1234567, +972 50 123 4567, 1-800-123-456, *2700.
            """(?<![\d+])(?:\+|00)972$SEP(?:\(0\))?$SEP(?:5\d|7\d|[23489])$SEP\d{3}$SEP\d{4}(?!\d)""",
            """(?<![\d+])0(?:5\d|7\d|[23489])$SEP\d{3}$SEP\d{4}(?!\d)""",
            """(?<!\d)1$SEP[5789]00$SEP\d{2,3}$SEP\d{3,4}(?!\d)""",
            """(?<![\d*])\*\d{4}(?!\d)""",
            // Elsewhere: +44 20 7946 0958, (212) 555-1234, 212-555-1234.
            """(?<![\d+])\+\d{1,3}(?:$SEP\(?\d{1,4}\)?){2,5}(?!\d)""",
            """(?<!\d)\(\d{3}\)\s?\d{3}$SEP\d{4}(?!\d)""",
            """(?<!\d)\d{3}[-.]\d{3}[-.]\d{4}(?!\d)""",
        ),
        valid = { it.startsWith("*") || digits(it).length in 8..15 },
    ),
    EMAIL(
        "Email",
        regex("""[A-Za-z0-9._%+-]+@[A-Za-z0-9-]+(?:\.[A-Za-z0-9-]+)*\.[A-Za-z]{2,}"""),
    ),
    URL(
        "Web address",
        regex(
            """(?:https?://|www\.)\S*[^\s.,;:!?)]""",
            """(?<![@\w.])[A-Za-z0-9][A-Za-z0-9-]*(?:\.[A-Za-z0-9-]+)*""" +
                """\.(?:com|net|org|il|io|co|gov|edu|info|biz|app|dev|me|tv|uk|de|fr|ru)(?![\w-])""" +
                """(?:/\S*[^\s.,;:!?)])?""",
        ),
    ),
    DATE(
        "Date",
        regex(
            // 5/11/2026, 05.11.26, 11-05-2026, 2026-11-05.
            """(?<![\d./-])$DAY[./-]$MONTH[./-](?:\d{4}|\d{2})(?![\d./-])""",
            """(?<![\d./-])$MONTH[./-]$DAY[./-](?:\d{4}|\d{2})(?![\d./-])""",
            """(?<![\d-])\d{4}-$MONTH-$DAY(?![\d-])""",
            // 5 Nov 2026, November 5, 2026, Nov 2026.
            """(?<![\d\w])\d{1,2}(?:st|nd|rd|th)?\s+$EN_MONTH(?:,?\s+\d{4})?(?![\w])""",
            """(?<![\w])$EN_MONTH\s+\d{1,2}(?:st|nd|rd|th)?(?:,?\s+\d{4})?(?![\d\w])""",
            """(?<![\w])$EN_MONTH\s+\d{4}(?!\d)""",
            // 5 בנובמבר 2026, ה-5 לנובמבר, נובמבר 2026.
            """(?<!\d)(?:ה-?)?\d{1,2}\s+[בל]?$HE_MONTH(?:\s+\d{4})?$NOT_LETTER_AFTER""",
            """$NOT_LETTER_BEFORE[בל]?$HE_MONTH\s+\d{4}(?!\d)""",
        ),
    ),
    TIME(
        "Time",
        regex("""(?<![\d:])(?:[01]?\d|2[0-3]):[0-5]\d(?::[0-5]\d)?(?:\s?[ap]\.?m\.?)?(?![\d:])"""),
    ),
    MONEY(
        "Money",
        regex(
            """$CURRENCY_BEFORE\s?$AMOUNT(?![\d,])""",
            """(?<![\d.,])$AMOUNT\s?$CURRENCY_AFTER$NOT_LETTER_AFTER""",
        ),
    ),
    CARD(
        "Card number",
        regex("""(?<!\d)(?:\d[ -]?){12,18}\d(?!\d)"""),
        valid = { digits(it).length in 13..19 && luhn(digits(it)) },
    ),
    IBAN(
        "IBAN",
        regex("""(?<![A-Za-z0-9])[A-Za-z]{2}\d{2}(?: ?[A-Za-z0-9]){11,30}(?![A-Za-z0-9])"""),
        valid = ::iban,
    ),
    ID(
        "ID number",
        regex("""(?<![\d-])\d{9}(?![\d-])"""),
        valid = ::israeliId,
    ),
    PLATE(
        "License plate",
        // Israeli: 12-345-67 and 123-45-678. The separators are dots, often read as - or :.
        regex(
            """(?<!\d)\d{2}$PLATE_SEP\d{3}$PLATE_SEP\d{2}(?!\d)""",
            """(?<!\d)\d{3}$PLATE_SEP\d{2}$PLATE_SEP\d{3}(?!\d)""",
        ),
    ),
    ADDRESS(
        "Street address",
        regex(
            // רחוב הרצל 12, שד' רוטשילד 5א, דרך מנחם בגין 132.
            """$NOT_LETTER_BEFORE(?:רחוב|רח['׳]|שדרות|שד['׳]|דרך|סמטת|כיכר)\s+""" +
                """[\p{L}"״'׳-]+(?:\s+[\p{L}"״'׳-]+){0,3}\s+\d{1,4}[א-ת]?(?!\d)""",
            // 12 Main St, 221B Baker Street.
            """(?<!\d)\d{1,5}[A-Za-z]?\s+(?:[A-Z][a-z]+\s+){1,3}""" +
                """(?:St|Street|Ave|Avenue|Rd|Road|Blvd|Boulevard|Ln|Lane|Dr|Drive|Way|Ct|Court|Pl|Place)\b\.?""",
        ),
    );

    /** How the detector is written in a search: `@phone`. */
    val token get() = "@" + name.lowercase()

    /** Where in [line] this kind of text is. */
    fun ranges(line: String): List<IntRange> =
        regex.findAll(line).filter { valid(it.value) }.map { it.range }.toList()

    companion object {
        fun forToken(token: String): Detector? =
            entries.firstOrNull { it.token.equals(token, ignoreCase = true) }
    }
}
