package com.example.voicereminders

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.provider.CalendarContract
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import java.util.concurrent.atomic.AtomicInteger

object Notifications {

    const val CHANNEL_LISTENING = "listening"
    private const val CHANNEL_RESULTS = "results"
    private val nextId = AtomicInteger(1000)

    fun createChannels(context: Context) {
        val manager = NotificationManagerCompat.from(context)
        manager.createNotificationChannel(
            NotificationChannelCompat.Builder(CHANNEL_LISTENING, NotificationManagerCompat.IMPORTANCE_LOW)
                .setName("האזנה ברקע")
                .build()
        )
        manager.createNotificationChannel(
            NotificationChannelCompat.Builder(CHANNEL_RESULTS, NotificationManagerCompat.IMPORTANCE_HIGH)
                .setName("תזכורות שנקבעו")
                .build()
        )
    }

    /** Tells what became of a request. A created reminder can be opened or undone from it. */
    @SuppressLint("MissingPermission")
    fun showOutcome(context: Context, outcome: Outcome, heard: String) {
        if (outcome == Outcome.NoTrigger || !Permissions.canNotify(context)) return
        createChannels(context)
        val id = nextId.getAndIncrement()
        val builder = NotificationCompat.Builder(context, CHANNEL_RESULTS)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle(ReminderCreator.message(outcome, heard))
            .setContentText("שמעתי: $heard")
            .setStyle(NotificationCompat.BigTextStyle().bigText("שמעתי: $heard"))
            .setAutoCancel(true)
        if (outcome is Outcome.Created) {
            val event = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, outcome.eventId)
            val view = Intent(Intent.ACTION_VIEW, event)
            builder.setContentIntent(PendingIntent.getActivity(context, id, view, PendingIntent.FLAG_IMMUTABLE))
            val undo = Intent(context, UndoReceiver::class.java)
                .putExtra(UndoReceiver.EXTRA_EVENT_ID, outcome.eventId)
                .putExtra(UndoReceiver.EXTRA_NOTIFICATION_ID, id)
            val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            builder.addAction(0, "ביטול", PendingIntent.getBroadcast(context, id, undo, flags))
        } else {
            val open = Intent(context, MainActivity::class.java)
            builder.setContentIntent(PendingIntent.getActivity(context, id, open, PendingIntent.FLAG_IMMUTABLE))
        }
        NotificationManagerCompat.from(context).notify(id, builder.build())
    }
}
