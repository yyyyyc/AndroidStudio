package com.example.cameratextlogger

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

private const val CHANNEL_ID = "text_found"
private const val COOLDOWN_MS = 30_000L

/** Tells the user a session found its text, at most once per [COOLDOWN_MS]. */
class FoundNotifier(private val context: Context, private val session: Session) {

    private val manager = NotificationManagerCompat.from(context)
    private var lastNotifiedMs: Long? = null

    init {
        manager.createNotificationChannel(
            NotificationChannelCompat.Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_HIGH)
                .setName("Text found")
                .build()
        )
    }

    @SuppressLint("MissingPermission") // areNotificationsEnabled covers POST_NOTIFICATIONS.
    fun notifyFound(text: String, nowMs: Long) {
        val last = lastNotifiedMs
        if (last != null && nowMs - last < COOLDOWN_MS) return
        if (!manager.areNotificationsEnabled()) return
        lastNotifiedMs = nowMs

        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle("Found: ${session.findText}")
            .setContentText(text)
            .setSubText(session.name)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(
                PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_IMMUTABLE)
            )
            .build()
        manager.notify(session.id.hashCode(), notification)
    }
}
