package com.example.superprices

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.concurrent.TimeUnit

/** Downloads the chosen stores' prices and promotions, then sends price-drop alerts. */
class RefreshWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val repo = Repository.get(applicationContext)
        if (repo.db.myStores().isEmpty()) return@withContext Result.success()
        val report = try {
            repo.refreshMyStores { msg, f -> setProgressAsync(workDataOf(KEY_MSG to msg, KEY_PROGRESS to f)) }
        } catch (e: Exception) {
            return@withContext if (runAttemptCount < 2) Result.retry() else Result.failure(workDataOf(KEY_MSG to e.message))
        }
        Alerts.show(applicationContext, report.alerts)
        val msg = when {
            report.failed.isNotEmpty() -> "לא הצלחתי לעדכן: ${report.failed.joinToString(", ")}"
            report.updated == 0 -> "המחירים כבר מעודכנים"
            else -> "עודכנו ${report.updated} חנויות"
        }
        Result.success(workDataOf(KEY_MSG to msg))
    }

    companion object {
        const val KEY_MSG = "msg"
        const val KEY_PROGRESS = "progress"
        const val NOW = "refresh-now"
        private const val DAILY = "refresh-daily"

        private val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        fun runNow(context: Context): java.util.UUID {
            // No network constraint: the user asked now, and Android's "validated internet"
            // check can stay false on networks that work fine. Without a network it just fails.
            val req = OneTimeWorkRequestBuilder<RefreshWorker>().build()
            WorkManager.getInstance(context).enqueueUniqueWork(NOW, ExistingWorkPolicy.REPLACE, req)
            return req.id
        }

        /** Chains publish full files early each morning; once a day is enough. */
        fun scheduleDaily(context: Context) {
            val req = PeriodicWorkRequestBuilder<RefreshWorker>(1, TimeUnit.DAYS).setConstraints(constraints).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(DAILY, ExistingPeriodicWorkPolicy.KEEP, req)
        }
    }
}

object Alerts {
    private const val CHANNEL = "price_drops"

    fun canNotify(context: Context) = Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun show(context: Context, alerts: List<PriceAlert>) {
        if (alerts.isEmpty() || !canNotify(context)) return
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "ירידות מחיר", NotificationManager.IMPORTANCE_DEFAULT))
        for (a in alerts) {
            val open = PendingIntent.getActivity(
                context, a.code.hashCode(),
                Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_CODE, a.code)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val was = a.previous?.let { " (היה ${money(it)})" }.orEmpty()
            val n = NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_notify_more)
                .setContentTitle("ירידת מחיר: ${a.name}")
                .setContentText("${money(a.price)}$was ב${a.storeTitle}")
                .setStyle(NotificationCompat.BigTextStyle().bigText("${money(a.price)}$was ב${a.storeTitle}"))
                .setContentIntent(open)
                .setAutoCancel(true)
                .build()
            try {
                NotificationManagerCompat.from(context).notify(a.code.hashCode(), n)
            } catch (_: SecurityException) {
            }
        }
    }
}

/** Wrapped in a left-to-right isolate so "₪15.90" stays intact inside Hebrew text. */
fun money(v: Double): String = "⁦₪" + String.format(Locale.US, "%.2f", v) + "⁩"
