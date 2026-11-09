package com.example.voicereminders

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.temporal.ChronoUnit

data class ParsedReminder(val title: String, val start: LocalDateTime)

private enum class DayPart(val defaultHour: Int) {
    MORNING(9), NOON(13), AFTERNOON(16), EVENING(20), NIGHT(22)
}

/**
 * Reads a reminder request in Hebrew, such as "תזכיר לי מחר ב-5 להתקשר לאמא", into a title and
 * a start time. The parts understood as a date or time are cut out; what's left is the title.
 */
object HebrewReminderParser {

    // Hebrew letters aren't word characters to \b, so words are bounded by hand.
    private const val B = "(?<![\\p{L}\\d])"
    private const val E = "(?![\\p{L}\\d])"
    private const val DASH = "[-־–]?"

    private val TRIGGER = Regex(
        B + "(?:(?:תזכיר|תזכירי|תזכירו|להזכיר|הזכר|הזכירי|תזכור|תזכרי)\\s+לי" +
            "|(?:תקבע|תקבעי|קבע|קבעי|לקבוע|צור|תיצור|תיצרי|ליצור|שים|תשים|תשימי|לשים" +
            "|הוסף|תוסיף|תוסיפי|להוסיף)\\s+(?:לי\\s+)?תזכורת" +
            "|תזכורת)" + E
    )

    private val HOUR_WORDS = mapOf(
        "אחת עשרה" to 11, "שתים עשרה" to 12, "שתיים עשרה" to 12,
        "אחת" to 1, "שתיים" to 2, "שלוש" to 3, "ארבע" to 4, "חמש" to 5, "שש" to 6,
        "שבע" to 7, "שמונה" to 8, "תשע" to 9, "עשר" to 10,
    )
    private val AMOUNT_WORDS = mapOf(
        "אחד" to 1, "אחת" to 1, "שני" to 2, "שתי" to 2, "שניים" to 2, "שתיים" to 2,
        "שלושה" to 3, "שלוש" to 3, "ארבעה" to 4, "ארבע" to 4, "חמישה" to 5, "חמש" to 5,
        "שישה" to 6, "שש" to 6, "שבעה" to 7, "שבע" to 7, "שמונה" to 8, "תשעה" to 9,
        "תשע" to 9, "עשרה" to 10, "עשר" to 10, "חמש עשרה" to 15, "עשרים" to 20,
        "שלושים" to 30, "ארבעים" to 40, "חמישים" to 50,
    )
    private val MONTHS = mapOf(
        "ינואר" to 1, "פברואר" to 2, "מרץ" to 3, "מרס" to 3, "אפריל" to 4, "מאי" to 5,
        "יוני" to 6, "יולי" to 7, "אוגוסט" to 8, "ספטמבר" to 9, "אוקטובר" to 10,
        "נובמבר" to 11, "דצמבר" to 12,
    )
    private val WEEKDAYS = mapOf(
        "ראשון" to DayOfWeek.SUNDAY, "שני" to DayOfWeek.MONDAY, "שלישי" to DayOfWeek.TUESDAY,
        "רביעי" to DayOfWeek.WEDNESDAY, "חמישי" to DayOfWeek.THURSDAY,
        "שישי" to DayOfWeek.FRIDAY, "שבת" to DayOfWeek.SATURDAY,
        "א" to DayOfWeek.SUNDAY, "ב" to DayOfWeek.MONDAY, "ג" to DayOfWeek.TUESDAY,
        "ד" to DayOfWeek.WEDNESDAY, "ה" to DayOfWeek.THURSDAY, "ו" to DayOfWeek.FRIDAY,
    )

    private fun alternation(words: Collection<String>) =
        words.sortedByDescending { it.length }.joinToString("|") { it.replace(" ", "\\s+") }

    private val HW = alternation(HOUR_WORDS.keys)
    private val AW = alternation(AMOUNT_WORDS.keys)
    private val MW = alternation(MONTHS.keys)

    /** "בעוד חצי שעה" */
    private val PART_OF_HOUR_FROM_NOW = Regex("${B}ו?בעוד\\s+(חצי|רבע|שלושת\\s+רבעי)\\s+שעה$E")

    /** "בעוד 20 דקות", "בעוד שעתיים", "בעוד שלושה ימים" */
    private val FROM_NOW = Regex(
        "${B}ו?בעוד\\s+(?:(\\d+|$AW)\\s+)?" +
            "(דקה|דקות|שעה|שעות|שעתיים|יום|ימים|יומיים|שבוע|שבועות|שבועיים|חודש|חודשים|חודשיים)" +
            "(\\s+וחצי)?$E"
    )
    private val DAY_WORD = Regex("${B}ו?[בל]?(מחרתיים|מחר|היום|הערב|הלילה|הבוקר)$E")
    private val WEEKDAY = Regex(
        "${B}ו?(?:[בל]?יום\\s+(ראשון|שני|שלישי|רביעי|חמישי|שישי|שבת|[אבגדהו](?=['׳]))['׳]?" +
            "|[בל](שבת))(\\s+(?:הבא|הקרוב|הקרובה))?$E"
    )

    /** "15/11", "ב-15.11.2026" */
    private val NUMERIC_DATE =
        Regex("${B}ו?(?:[בל]$DASH\\s*)?(\\d{1,2})[./](\\d{1,2})(?:[./](\\d{4}|\\d{2}))?$E")

    /** "ב-15 בנובמבר" */
    private val MONTH_DATE =
        Regex("${B}ו?(?:[בל]$DASH\\s*)?(\\d{1,2})\\s*(?:[בל]$DASH\\s*)?($MW)(?:\\s+(\\d{4}))?$E")

    /** "ב-17:30" */
    private val CLOCK = Regex("${B}(?:ו?[בל]?שעה\\s*$DASH\\s*|ו?[בל]$DASH\\s*)?(\\d{1,2}):(\\d{2})$E")

    /** "ברבע לשש", "בעשרה ל-8" */
    private val MINUTES_TO = Regex(
        "${B}(?:ו?[בל]?שעה\\s*)?ו?ב?(רבע|עשרה|עשר\\s+דקות|חמש\\s+דקות|עשרים(?:\\s+דקות)?|\\d{1,2}\\s+דקות)" +
            "\\s+ל$DASH\\s*(\\d{1,2}|$HW)$E"
    )

    private val MINUTE_SUFFIX =
        "(?:\\s+(וחצי|ורבע|ועשרה|ועשרים|וחמישה|וחמש|ו$DASH\\s*(\\d{1,2})(?:\\s+דקות)?))?"

    /** "בשעה 5", "ב-8 וחצי", "בחמש" */
    private val HOUR = Regex(
        "${B}(?:ו?[בל]?שעה\\s*$DASH\\s*(\\d{1,2}|$HW)|ו?[בל]$DASH\\s*(\\d{1,2})|ו?ב($HW))$MINUTE_SUFFIX$E"
    )

    /** "מחר 8 בבוקר": an hour with no "ב" before it, only when a part of the day follows. */
    private val HOUR_BEFORE_DAY_PART = Regex(
        "${B}(\\d{1,2})$MINUTE_SUFFIX(?=\\s+ו?(?:בבוקר|לפנות|בצהריי?ם|ב?אחרי?\\s|ב?אחה|ב?אחר[\"״]|בערב|בלילה))"
    )
    private val DAY_PART = Regex(
        "${B}ו?(?:(בבוקר|לפנות\\s+בוקר)|(ב?אחרי?\\s+ה?צהריי?ם|ב?אחה[\"״]צ|ב?אחר[\"״]צ)|(בצהריי?ם)|(בערב)|(בלילה))$E"
    )

    private val STRAY = Regex("(?<!\\S)(?:[בלוש][-־]?|בשעה)(?!\\S)")
    private val LEADING = Regex("^(?:(?:על|את|לגבי|כדי|לי|ו|ל|ב|ש)(?=\\s|$)|[-־,.:]|[לב][-־])\\s*")
    private val LEADING_SHE = Regex(
        "^ש(?=(?:יש|צריך|צריכה|אני|אתה|את|יהיה|תהיה|אסור|לא|הוא|היא|נצטרך|אצטרך)(?:\\s|$))"
    )
    private val TRAILING = Regex("(?:\\s+(?:על|את|ו|של))+$")
    private val SPACES = Regex("\\s+")

    /** What was said after the trigger phrase ("תזכיר לי", "תזכורת", ...), or null without one. */
    fun afterTrigger(text: String): String? =
        TRIGGER.find(text)?.let { text.substring(it.range.last + 1) }

    /** Returns null when no date or time is found in [text]. */
    fun parse(text: String, now: LocalDateTime): ParsedReminder? {
        val trigger = TRIGGER.find(text)
            ?: return parseRequest(text, now)
        // Usually the request follows the trigger, but "מחר ב-9 תזכיר לי ..." puts the time first.
        return parseRequest(text.substring(trigger.range.last + 1), now)
            ?: parseRequest(text.replaceRange(trigger.range, " "), now)
    }

    private fun parseRequest(request: String, now: LocalDateTime): ParsedReminder? {
        val text = Blanker(request)
        val today = now.toLocalDate()
        val nowTime = now.toLocalTime().truncatedTo(ChronoUnit.MINUTES)
        var exact: LocalDateTime? = null
        var date: LocalDate? = null
        var weekday: DayOfWeek? = null
        var nextWeek = false
        var dayPart: DayPart? = null
        var hour: Int? = null
        var minute = 0
        var ambiguous = false
        var defaultTime = LocalTime.of(9, 0)

        text.take(PART_OF_HOUR_FROM_NOW) { m ->
            val minutes = when (m.groupValues[1]) {
                "חצי" -> 30L
                "רבע" -> 15L
                else -> 45L
            }
            exact = now.plusMinutes(minutes)
            true
        } || text.take(FROM_NOW) { m ->
            val unit = m.groupValues[2]
            val count = m.groupValues[1].let { if (it.isEmpty()) 1 else amount(it) }.toLong()
            val half = m.groupValues[3].isNotEmpty()
            when (unit) {
                "דקה", "דקות" -> exact = now.plusMinutes(count)
                "שעה", "שעות", "שעתיים" ->
                    exact = now.plusMinutes((if (unit == "שעתיים") 2 else count) * 60 + if (half) 30 else 0)
                "יום", "ימים", "יומיים" -> date = today.plusDays(if (unit == "יומיים") 2 else count)
                "שבוע", "שבועות", "שבועיים" -> date = today.plusWeeks(if (unit == "שבועיים") 2 else count)
                else -> date = today.plusMonths(if (unit == "חודשיים") 2 else count)
            }
            // "בעוד יומיים" with no time: at the same time as now.
            defaultTime = nowTime
            true
        }

        exact?.let { return ParsedReminder(title(text.toString()), it.truncatedTo(ChronoUnit.MINUTES)) }

        if (date == null) {
            text.take(DAY_WORD) { m ->
                when (m.groupValues[1]) {
                    "מחרתיים" -> date = today.plusDays(2)
                    "מחר" -> date = today.plusDays(1)
                    "היום" -> date = today
                    "הערב" -> { date = today; dayPart = DayPart.EVENING }
                    "הלילה" -> { date = today; dayPart = DayPart.NIGHT }
                    "הבוקר" -> { date = today; dayPart = DayPart.MORNING }
                }
                true
            } || text.take(WEEKDAY) { m ->
                weekday = WEEKDAYS[m.groupValues[1].ifEmpty { m.groupValues[2] }]
                nextWeek = m.groupValues[3].isNotEmpty()
                weekday != null
            } || text.take(NUMERIC_DATE) { m ->
                date = dateOf(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3], today)
                date != null
            } || text.take(MONTH_DATE) { m ->
                date = dateOf(m.groupValues[1].toInt(), MONTHS.getValue(m.groupValues[2]), m.groupValues[3], today)
                date != null
            }
        }

        text.take(CLOCK) { m ->
            val h = m.groupValues[1].toInt()
            val min = m.groupValues[2].toInt()
            if (h > 23 || min > 59) return@take false
            hour = h
            minute = min
            ambiguous = h in 1..11 && !m.groupValues[1].startsWith("0")
            true
        } || text.take(MINUTES_TO) { m ->
            val before = when (val word = m.groupValues[1].replace(SPACES, " ")) {
                "רבע" -> 15
                "עשרה", "עשר דקות" -> 10
                "חמש דקות" -> 5
                "עשרים", "עשרים דקות" -> 20
                else -> word.substringBefore(' ').toInt()
            }
            val of = hourValue(m.groupValues[2]) ?: return@take false
            if (of !in 1..23 || before !in 1..59) return@take false
            hour = (of - 1).takeIf { it > 0 } ?: 12
            minute = 60 - before
            ambiguous = hour in 1..11
            true
        } || text.take(HOUR) { m ->
            hourOf(m)?.let { (h, min) -> hour = h; minute = min; ambiguous = h in 1..11 } != null
        } || text.take(HOUR_BEFORE_DAY_PART) { m ->
            hourOf(m)?.let { (h, min) -> hour = h; minute = min; ambiguous = h in 1..11 } != null
        }

        text.take(DAY_PART) { m ->
            val found = when {
                m.groups[1] != null -> DayPart.MORNING
                m.groups[2] != null -> DayPart.AFTERNOON
                m.groups[3] != null -> DayPart.NOON
                m.groups[4] != null -> DayPart.EVENING
                else -> DayPart.NIGHT
            }
            dayPart = dayPart ?: found
            true
        }

        val day = date
        val onWeekday = weekday
        val h = hour
        val part = dayPart
        if (day == null && onWeekday == null && h == null && part == null) return null

        fun at(on: LocalDate): LocalDateTime {
            if (h == null) return on.atTime(part?.let { LocalTime.of(it.defaultHour, 0) } ?: defaultTime)
            val h24 = when {
                part != null -> to24(h, part)
                !ambiguous -> h
                // "ב-8" today means whichever of 8:00 and 20:00 is still ahead.
                on == today -> listOf(h, h + 12).firstOrNull { on.atTime(it, minute).isAfter(now) } ?: h
                // Few reminders are for 1-6 at night.
                h <= 6 -> h + 12
                else -> h
            }
            return on.atTime(h24, minute)
        }

        val start = when {
            day != null -> at(day)
            onWeekday != null -> {
                var ahead = (onWeekday.value - today.dayOfWeek.value + 7) % 7
                if (ahead == 0 && (nextWeek || !at(today).isAfter(now))) ahead = 7
                at(today.plusDays(ahead.toLong()))
            }
            else -> at(today).takeIf { it.isAfter(now) } ?: at(today.plusDays(1))
        }
        return ParsedReminder(title(text.toString()), start)
    }

    /** The hour and minute in a match of [HOUR] or [HOUR_BEFORE_DAY_PART], if valid. */
    private fun hourOf(m: MatchResult): Pair<Int, Int>? {
        val g = m.groupValues
        // HOUR has three hour groups then the suffix; HOUR_BEFORE_DAY_PART has one.
        val hourGroups = if (g.size == 6) 1..3 else 1..1
        val hourText = hourGroups.map { g[it] }.firstOrNull { it.isNotEmpty() } ?: return null
        val suffix = g[hourGroups.last + 1]
        val suffixDigits = g[hourGroups.last + 2]
        val h = hourValue(hourText) ?: return null
        val min = when (suffix) {
            "" -> 0
            "וחצי" -> 30
            "ורבע" -> 15
            "ועשרה" -> 10
            "ועשרים" -> 20
            "וחמישה", "וחמש" -> 5
            else -> suffixDigits.toIntOrNull() ?: return null
        }
        return if (h > 23 || min > 59) null else h to min
    }

    private fun hourValue(text: String): Int? =
        text.toIntOrNull() ?: HOUR_WORDS[text.replace(SPACES, " ")]

    private fun amount(text: String): Int =
        text.toIntOrNull() ?: AMOUNT_WORDS[text.replace(SPACES, " ")] ?: 1

    private fun to24(hour: Int, part: DayPart): Int = when (part) {
        DayPart.MORNING -> hour
        DayPart.NOON -> if (hour in 1..5) hour + 12 else hour
        DayPart.AFTERNOON, DayPart.EVENING -> if (hour in 1..11) hour + 12 else hour
        DayPart.NIGHT -> when (hour) {
            in 6..11 -> hour + 12
            12 -> 0
            else -> hour
        }
    }

    /** A date with no year is the next one to come. */
    private fun dateOf(day: Int, month: Int, yearText: String, today: LocalDate): LocalDate? {
        val year = yearText.toIntOrNull()?.let { if (it < 100) 2000 + it else it }
        return try {
            val date = LocalDate.of(year ?: today.year, month, day)
            if (year == null && date.isBefore(today)) date.plusYears(1) else date
        } catch (e: java.time.DateTimeException) {
            null
        }
    }

    private fun title(rest: String): String {
        var t = rest.replace(STRAY, " ").replace(SPACES, " ").trim()
        do {
            val previous = t
            t = t.replaceFirst(LEADING, "").replaceFirst(LEADING_SHE, "").trim()
        } while (t != previous)
        t = t.replace(TRAILING, "").trim(' ', ',', '.', '-', '־', ':', '?', '!')
        return t.ifEmpty { "תזכורת" }
    }
}

/** The request being parsed, with the parts already understood blanked out. */
private class Blanker(text: String) {

    private val chars = StringBuilder(text)

    /** Blanks out the first match of [regex] that [accept] takes. */
    fun take(regex: Regex, accept: (MatchResult) -> Boolean): Boolean {
        for (match in regex.findAll(chars)) {
            if (accept(match)) {
                for (i in match.range) chars.setCharAt(i, ' ')
                return true
            }
        }
        return false
    }

    override fun toString() = chars.toString()
}
