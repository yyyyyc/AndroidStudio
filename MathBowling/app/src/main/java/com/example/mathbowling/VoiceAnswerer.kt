package com.example.mathbowling

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import java.util.Locale

/**
 * Listens for a spoken answer, restarting itself after silences, until [stop] is called.
 * [onHeard] gets the recognizer's guesses (partial results too); [onListening] reports the mic state.
 * Must be used on the main thread.
 */
class VoiceAnswerer(
    private val context: Context,
    private val onHeard: (guesses: List<String>, final: Boolean) -> Unit,
    private val onListening: (Boolean) -> Unit
) {
    private val main = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var active = false
    private var locale: Locale = Locale.getDefault()
    /** The answers on the cones, given to the recognizer as likely words. */
    private var hints: List<String> = emptyList()
    /** Ask for on-device recognition: no round trip to a server, so results come sooner. */
    var preferOffline = true
    /** Languages whose on-device model turned out to be missing: these use the server. */
    private val offlineMissing = HashSet<String>()
    private var offlineThisTime = false

    val available: Boolean get() = SpeechRecognizer.isRecognitionAvailable(context)

    fun start(locale: Locale, hints: List<String> = emptyList()) {
        this.locale = locale
        this.hints = hints
        active = true
        main.removeCallbacksAndMessages(null)
        listen()
    }

    fun stop() {
        active = false
        main.removeCallbacksAndMessages(null)
        recognizer?.cancel()
        onListening(false)
    }

    fun release() {
        stop()
        recognizer?.destroy()
        recognizer = null
    }

    private fun listen() {
        if (!active) return
        val r = recognizer ?: SpeechRecognizer.createSpeechRecognizer(context).also {
            it.setRecognitionListener(listener)
            recognizer = it
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, locale.toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
            offlineThisTime = preferOffline && locale.toLanguageTag() !in offlineMissing
            if (offlineThisTime) putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            if (Build.VERSION.SDK_INT >= 33 && hints.isNotEmpty()) {
                putStringArrayListExtra(RecognizerIntent.EXTRA_BIASING_STRINGS, ArrayList(hints.take(10)))
            }
        }
        r.cancel()
        r.startListening(intent)
    }

    // listen again almost at once, so words said right after a pause aren't lost
    private fun restartSoon(delayMs: Long = 60) {
        if (active) main.postDelayed({ listen() }, delayMs)
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) { onListening(true) }
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onEvent(eventType: Int, params: Bundle?) {}

        override fun onPartialResults(partialResults: Bundle?) {
            val list = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION) ?: return
            if (active && list.isNotEmpty()) onHeard(list, false)
        }

        override fun onResults(results: Bundle?) {
            val list = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
            if (active && list.isNotEmpty()) onHeard(list, true)
            restartSoon()
        }

        override fun onError(error: Int) {
            onListening(false)
            // silence / no match / busy: just listen again; give up on permission problems
            if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) { active = false; return }
            // no on-device model for this language (12 = not supported, 13 = not downloaded): use the server
            if (offlineThisTime && (error == 12 || error == 13)) {
                offlineMissing.add(locale.toLanguageTag())
                restartSoon(0)
                return
            }
            restartSoon(if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) 300 else 60)
        }
    }
}
