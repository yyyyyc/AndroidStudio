package com.example.voicereminders

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

object Permissions {

    /** Every permission the app asks for, with what it's for. */
    val all: Map<String, String> = buildMap {
        put(Manifest.permission.READ_CALENDAR, "יומן")
        put(Manifest.permission.WRITE_CALENDAR, "יומן")
        put(Manifest.permission.RECORD_AUDIO, "מיקרופון")
        if (Build.VERSION.SDK_INT >= 33) put(Manifest.permission.POST_NOTIFICATIONS, "התראות")
    }

    fun granted(context: Context, permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    fun canNotify(context: Context) =
        Build.VERSION.SDK_INT < 33 || granted(context, Manifest.permission.POST_NOTIFICATIONS)

    /** Names of what's not allowed yet. */
    fun missing(context: Context): List<String> =
        all.filterKeys { !granted(context, it) }.values.distinct()
}
