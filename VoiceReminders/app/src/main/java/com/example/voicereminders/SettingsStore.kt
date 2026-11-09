package com.example.voicereminders

import android.content.Context

class SettingsStore(context: Context) {

    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    /** The calendar to add reminders to; null means the first one [CalendarRepository.list] gives. */
    var calendarId: Long?
        get() = prefs.getLong("calendarId", -1).takeIf { it >= 0 }
        set(value) = prefs.edit().putLong("calendarId", value ?: -1).apply()

    /** Whether to keep listening for "תזכיר לי ..." in the background. */
    var handsFree: Boolean
        get() = prefs.getBoolean("handsFree", false)
        set(value) = prefs.edit().putBoolean("handsFree", value).apply()
}
