package com.example.cameratextlogger

import android.media.AudioManager
import android.media.ToneGenerator
import android.util.Log

private const val BEEP_MS = 300

/**
 * Beeps when a session finds its text. Uses the media volume, so it is heard even when the phone
 * is on silent. [beep] runs on the analysis thread and [release] on the main thread.
 */
class FoundBeeper {

    private var tone: ToneGenerator? = try {
        ToneGenerator(AudioManager.STREAM_MUSIC, ToneGenerator.MAX_VOLUME)
    } catch (e: RuntimeException) {
        Log.e(TAG, "Could not create beep", e)
        null
    }

    @Synchronized
    fun beep() {
        tone?.startTone(ToneGenerator.TONE_PROP_BEEP2, BEEP_MS)
    }

    @Synchronized
    fun release() {
        tone?.release()
        tone = null
    }
}
