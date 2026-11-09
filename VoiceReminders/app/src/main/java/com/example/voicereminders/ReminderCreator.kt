package com.example.voicereminders

import android.content.Context
import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

sealed interface Outcome {
    data class Created(val eventId: Long, val reminder: ParsedReminder) : Outcome
    /** Nothing was asked: no "תזכיר לי" in what was heard. */
    data object NoTrigger : Outcome
    data object NoTime : Outcome
    data object NoCalendar : Outcome
    data object Failed : Outcome
}

object ReminderCreator {

    /** Adds the reminder in [text] to the calendar. With [requireTrigger], only if one was asked for. */
    fun create(context: Context, text: String, requireTrigger: Boolean): Outcome {
        if (requireTrigger && HebrewReminderParser.afterTrigger(text) == null) return Outcome.NoTrigger
        val reminder = HebrewReminderParser.parse(text, LocalDateTime.now()) ?: return Outcome.NoTime
        val calendars = CalendarRepository(context)
        if (!calendars.hasPermission()) return Outcome.NoCalendar
        val available = calendars.list()
        val calendarId = SettingsStore(context).calendarId?.takeIf { id -> available.any { it.id == id } }
            ?: available.firstOrNull()?.id
            ?: return Outcome.NoCalendar
        val eventId = calendars.insert(calendarId, reminder, text) ?: return Outcome.Failed
        return Outcome.Created(eventId, reminder)
    }

    fun message(outcome: Outcome, text: String): String = when (outcome) {
        is Outcome.Created ->
            "נקבעה תזכורת: ${outcome.reminder.title}, ${describe(outcome.reminder.start, LocalDateTime.now())}"
        Outcome.NoTrigger, Outcome.NoTime -> "לא הבנתי מתי לקבוע את התזכורת: \"$text\""
        Outcome.NoCalendar -> "אין גישה ליומן. פתחו את האפליקציה, אשרו הרשאות ובחרו יומן."
        Outcome.Failed -> "לא הצלחתי לשמור את התזכורת ביומן."
    }

    private val DAY_NAMES = mapOf(
        DayOfWeek.SUNDAY to "ראשון", DayOfWeek.MONDAY to "שני", DayOfWeek.TUESDAY to "שלישי",
        DayOfWeek.WEDNESDAY to "רביעי", DayOfWeek.THURSDAY to "חמישי", DayOfWeek.FRIDAY to "שישי",
    )

    /** "מחר ב-17:00", "ביום שלישי ב-10:00", "ב-15/12 ב-9:00" */
    fun describe(start: LocalDateTime, now: LocalDateTime): String {
        val days = ChronoUnit.DAYS.between(now.toLocalDate(), start.toLocalDate())
        val day = when {
            days == 0L -> "היום"
            days == 1L -> "מחר"
            days == 2L -> "מחרתיים"
            days in 3L..6L -> DAY_NAMES[start.dayOfWeek]?.let { "ביום $it" } ?: "בשבת"
            else -> "ב-" + start.format(DateTimeFormatter.ofPattern(if (start.year == now.year) "d/M" else "d/M/yyyy"))
        }
        return "$day ב-${start.format(DateTimeFormatter.ofPattern("H:mm"))}"
    }
}
