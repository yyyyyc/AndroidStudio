package com.example.voicereminders

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDateTime

class HebrewReminderParserTest {

    // A Thursday.
    private val now = LocalDateTime.of(2026, 11, 5, 10, 0)

    private fun check(text: String, title: String, start: String) {
        val parsed = HebrewReminderParser.parse(text, now)
        assertEquals(text, ParsedReminder(title, LocalDateTime.parse(start)), parsed)
    }

    @Test fun tomorrowAtHour() =
        check("תזכיר לי מחר בשעה 5 להתקשר לאמא", "להתקשר לאמא", "2026-11-06T17:00")

    @Test fun titleBeforeTime() =
        check("תזכיר לי לקנות חלב מחר בבוקר", "לקנות חלב", "2026-11-06T09:00")

    @Test fun weekday() =
        check("תזכורת על פגישה עם דני ביום שלישי ב-10", "פגישה עם דני", "2026-11-10T10:00")

    @Test fun minutesFromNow() =
        check("תזכיר לי בעוד 20 דקות להוציא את העוגה", "להוציא את העוגה", "2026-11-05T10:20")

    @Test fun halfAnHourFromNow() =
        check("תזכורת בעוד חצי שעה לצאת", "לצאת", "2026-11-05T10:30")

    @Test fun anHourFromNowWithoutTitle() =
        check("תזכיר לי בעוד שעה", "תזכורת", "2026-11-05T11:00")

    @Test fun monthNameAndClock() =
        check("תזכיר לי שיש לי רופא שיניים ב-15 בנובמבר ב-16:30", "יש לי רופא שיניים", "2026-11-15T16:30")

    @Test fun ambiguousHourTodayIsTheNextOne() =
        check("תזכיר לי ב-8 לצפות במשחק", "לצפות במשחק", "2026-11-05T20:00")

    @Test fun thisEvening() =
        check("תזכיר לי הערב לסגור את הגז", "לסגור את הגז", "2026-11-05T20:00")

    @Test fun quarterTo() =
        check("תזכיר לי ברבע לשש לאסוף את הילדים", "לאסוף את הילדים", "2026-11-05T17:45")

    @Test fun saturdayMorning() =
        check("תזכיר לי בשבת בבוקר להשקות את העציצים", "להשקות את העציצים", "2026-11-07T09:00")

    @Test fun hourInWordsWithHalfAndAfternoon() =
        check("תזכיר לי מחרתיים בחמש וחצי אחרי הצהריים לאסוף חבילה", "לאסוף חבילה", "2026-11-07T17:30")

    @Test fun daysFromNowWithClock() =
        check("תזכיר לי בעוד יומיים בשעה 14:00 ישיבת צוות", "ישיבת צוות", "2026-11-07T14:00")

    @Test fun numericDate() =
        check("תזכורת 1/12 בשעה 9 בבוקר חידוש ביטוח", "חידוש ביטוח", "2026-12-01T09:00")

    @Test fun pastDateIsNextYear() =
        check("תזכורת ב-3/2 לחדש דרכון", "לחדש דרכון", "2027-02-03T09:00")

    @Test fun evening() =
        check("תזכיר לי בשעה 7 בערב לשים כביסה", "לשים כביסה", "2026-11-05T19:00")

    @Test fun nextMonday() =
        check("תזכיר לי יום שני הבא בשעה 8:30 לשלם ארנונה", "לשלם ארנונה", "2026-11-09T08:30")

    @Test fun textBeforeTriggerIsIgnored() =
        check("אה כן תזכיר לי מחר ב 9 בבוקר לשלם חשבון", "לשלם חשבון", "2026-11-06T09:00")

    @Test fun timeBeforeTrigger() =
        check("מחר ב 9 תזכיר לי לקנות חלב", "לקנות חלב", "2026-11-06T09:00")

    @Test fun hourWithoutPrefixBeforeDayPart() =
        check("תזכיר לי מחר 8 בערב לצלצל לסבתא", "לצלצל לסבתא", "2026-11-06T20:00")

    @Test fun sheIsDropped() =
        check("תזכיר לי שאני צריך לקנות מתנה מחר", "אני צריך לקנות מתנה", "2026-11-06T09:00")

    @Test fun noTriggerNeededWhenAsked() =
        check("לקנות לחם מחר ב-12 בצהריים", "לקנות לחם", "2026-11-06T12:00")

    @Test fun numbersInTitleAreNotTimes() =
        assertNull(HebrewReminderParser.parse("תזכיר לי לקנות 5 ביצים", now))

    @Test fun noTrigger() =
        assertNull(HebrewReminderParser.afterTrigger("מה השעה עכשיו"))

    @Test fun trigger() =
        assertEquals(" לקנות חלב", HebrewReminderParser.afterTrigger("תוכל להזכיר לי לקנות חלב"))
}
