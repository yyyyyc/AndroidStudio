package com.example.voicereminders

import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

private const val NOTIFICATION_ID = 1
private const val ACTION_STOP = "com.example.voicereminders.STOP_LISTENING"
private const val ACTION_PAUSE = "com.example.voicereminders.PAUSE_LISTENING"
private const val ACTION_RESUME = "com.example.voicereminders.RESUME_LISTENING"

/** Longest a pause may last, in case whoever paused never resumes. */
private const val MAX_PAUSE_MS = 60_000L

/**
 * Listens all the time and adds a reminder whenever "תזכיר לי ..." is heard. Android requires
 * this to be a foreground service with a notification, started while the app is on screen.
 */
class ListenService : Service(), RecognitionListener {

    companion object {
        @Volatile
        var isRunning = false
            private set

        fun start(context: Context) =
            ContextCompat.startForegroundService(context, Intent(context, ListenService::class.java))

        fun stop(context: Context) = context.stopService(Intent(context, ListenService::class.java))

        /** Frees the microphone, for [VoiceActivity]. */
        fun pause(context: Context) = send(context, ACTION_PAUSE)

        fun resume(context: Context) = send(context, ACTION_RESUME)

        private fun send(context: Context, action: String) {
            if (isRunning) context.startService(Intent(context, ListenService::class.java).setAction(action))
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var paused = false
    private val listenNow = Runnable { listen() }
    private val resumeAfterPause = Runnable { paused = false; listen() }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                SettingsStore(this).handsFree = false
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_PAUSE -> {
                paused = true
                handler.removeCallbacks(listenNow)
                handler.removeCallbacks(resumeAfterPause)
                recognizer?.cancel()
                handler.postDelayed(resumeAfterPause, MAX_PAUSE_MS)
                return START_NOT_STICKY
            }
            ACTION_RESUME -> {
                handler.removeCallbacks(resumeAfterPause)
                handler.postDelayed(resumeAfterPause, 500)
                return START_NOT_STICKY
            }
        }
        if (isRunning) return START_NOT_STICKY
        Notifications.createChannels(this)
        try {
            val type = if (Build.VERSION.SDK_INT >= 30) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(), type)
        } catch (e: RuntimeException) {
            // No microphone permission, or started from the background, which Android forbids.
            stopSelf()
            return START_NOT_STICKY
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        isRunning = true
        listen()
        // Not sticky: after being killed, Android wouldn't let it use the microphone from the background.
        return START_NOT_STICKY
    }

    private fun notification() = NotificationCompat.Builder(this, Notifications.CHANNEL_LISTENING)
        .setSmallIcon(android.R.drawable.ic_btn_speak_now)
        .setContentTitle("מאזין לתזכורות")
        .setContentText("אמרו \"תזכיר לי...\" בכל רגע")
        .setOngoing(true)
        .setContentIntent(
            PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        )
        .addAction(
            0, "עצירה",
            PendingIntent.getService(
                this, 1, Intent(this, ListenService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
            ),
        )
        .build()

    private fun listen() {
        if (!isRunning || paused) return
        val recognizer = recognizer ?: SpeechRecognizer.createSpeechRecognizer(this).also {
            it.setRecognitionListener(this)
            recognizer = it
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "he-IL")
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
            .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
        recognizer.startListening(intent)
    }

    private fun listenAgain(delayMs: Long) {
        handler.removeCallbacks(listenNow)
        handler.postDelayed(listenNow, delayMs)
    }

    override fun onResults(results: Bundle?) {
        val heard = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
        // The recognizer's guesses, best first: take the best one that asks for a reminder.
        val request = heard.firstOrNull { HebrewReminderParser.afterTrigger(it) != null }
        if (request != null) {
            val outcome = ReminderCreator.create(this, request, requireTrigger = true)
            Notifications.showOutcome(this, outcome, request)
        }
        listenAgain(200)
    }

    override fun onError(error: Int) {
        when (error) {
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                stopSelf()
                return
            }
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY, SpeechRecognizer.ERROR_CLIENT -> {
                // Start over with a new recognizer.
                recognizer?.destroy()
                recognizer = null
                listenAgain(1000)
            }
            SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
            SpeechRecognizer.ERROR_SERVER -> listenAgain(5000)
            // Mostly "nothing was said": the usual case when listening all the time.
            else -> listenAgain(200)
        }
    }

    override fun onDestroy() {
        isRunning = false
        handler.removeCallbacksAndMessages(null)
        recognizer?.destroy()
        recognizer = null
        super.onDestroy()
    }

    override fun onReadyForSpeech(params: Bundle?) {}
    override fun onBeginningOfSpeech() {}
    override fun onRmsChanged(rmsdB: Float) {}
    override fun onBufferReceived(buffer: ByteArray?) {}
    override fun onEndOfSpeech() {}
    override fun onPartialResults(partialResults: Bundle?) {}
    override fun onEvent(eventType: Int, params: Bundle?) {}
}
