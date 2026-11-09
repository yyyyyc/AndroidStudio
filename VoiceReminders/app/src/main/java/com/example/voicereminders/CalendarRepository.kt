package com.example.voicereminders

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract.Calendars
import android.provider.CalendarContract.Events
import android.provider.CalendarContract.Reminders
import androidx.core.content.ContextCompat
import java.time.ZoneId

data class CalendarInfo(val id: Long, val name: String, val account: String, val isGoogle: Boolean)

/**
 * Writes to the calendars synced to the phone. Events added to a Google account's calendar
 * here are synced by Android to Google Calendar.
 */
class CalendarRepository(private val context: Context) {

    fun hasPermission(): Boolean = listOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)
        .all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }

    /** Calendars that can be written to, Google ones first, each account's main calendar first. */
    fun list(): List<CalendarInfo> {
        if (!hasPermission()) return emptyList()
        val projection = arrayOf(
            Calendars._ID, Calendars.CALENDAR_DISPLAY_NAME, Calendars.ACCOUNT_NAME,
            Calendars.ACCOUNT_TYPE, Calendars.OWNER_ACCOUNT,
        )
        val selection = "${Calendars.CALENDAR_ACCESS_LEVEL} >= ? AND ${Calendars.VISIBLE} = 1"
        val args = arrayOf(Calendars.CAL_ACCESS_CONTRIBUTOR.toString())
        val found = mutableListOf<Pair<CalendarInfo, Boolean>>()
        context.contentResolver.query(Calendars.CONTENT_URI, projection, selection, args, null)?.use { c ->
            while (c.moveToNext()) {
                val account = c.getString(2).orEmpty()
                val info = CalendarInfo(
                    id = c.getLong(0),
                    name = c.getString(1) ?: account,
                    account = account,
                    isGoogle = c.getString(3) == "com.google",
                )
                // A Google account's main calendar is owned by the account itself.
                found += info to (c.getString(4) == account)
            }
        }
        return found.sortedWith(compareBy({ !it.first.isGoogle }, { !it.second }, { it.first.name }))
            .map { it.first }
    }

    /** Adds a 30-minute event with an alert at its start. Returns the event's ID. */
    fun insert(calendarId: Long, reminder: ParsedReminder, description: String): Long? {
        if (!hasPermission()) return null
        val zone = ZoneId.systemDefault()
        val start = reminder.start.atZone(zone).toInstant().toEpochMilli()
        val event = ContentValues().apply {
            put(Events.CALENDAR_ID, calendarId)
            put(Events.TITLE, reminder.title)
            put(Events.DESCRIPTION, description)
            put(Events.DTSTART, start)
            put(Events.DTEND, start + 30 * 60_000L)
            put(Events.EVENT_TIMEZONE, zone.id)
            put(Events.HAS_ALARM, 1)
        }
        val uri = context.contentResolver.insert(Events.CONTENT_URI, event) ?: return null
        val eventId = ContentUris.parseId(uri)
        val alert = ContentValues().apply {
            put(Reminders.EVENT_ID, eventId)
            put(Reminders.MINUTES, 0)
            put(Reminders.METHOD, Reminders.METHOD_ALERT)
        }
        context.contentResolver.insert(Reminders.CONTENT_URI, alert)
        return eventId
    }

    fun delete(eventId: Long): Boolean = hasPermission() &&
        context.contentResolver.delete(ContentUris.withAppendedId(Events.CONTENT_URI, eventId), null, null) > 0
}
