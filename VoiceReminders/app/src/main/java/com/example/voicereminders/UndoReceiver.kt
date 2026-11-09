package com.example.voicereminders

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.core.app.NotificationManagerCompat

/** Deletes a reminder that was just added, from the "ביטול" button on its notification. */
class UndoReceiver : BroadcastReceiver() {

    companion object {
        const val EXTRA_EVENT_ID = "eventId"
        const val EXTRA_NOTIFICATION_ID = "notificationId"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val eventId = intent.getLongExtra(EXTRA_EVENT_ID, -1)
        val deleted = eventId >= 0 && CalendarRepository(context).delete(eventId)
        NotificationManagerCompat.from(context).cancel(intent.getIntExtra(EXTRA_NOTIFICATION_ID, 0))
        val message = if (deleted) "התזכורת בוטלה" else "לא הצלחתי לבטל את התזכורת"
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }
}
