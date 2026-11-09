package com.example.voicereminders

import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.speech.RecognizerIntent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts

/**
 * Asks for one reminder by voice, adds it, and closes. Started from its launcher icon (so also
 * by "Hey Google, open תזכורת חדשה"), the quick settings tile, or text shared to the app.
 */
class VoiceActivity : ComponentActivity() {

    private val speech = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val text = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (result.resultCode == RESULT_OK && !text.isNullOrBlank()) handle(text)
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Already asking: the activity was only recreated.
        if (savedInstanceState != null) return

        if (!CalendarRepository(this).hasPermission()) {
            startActivity(Intent(this, MainActivity::class.java))
            finish()
            return
        }

        val shared = intent.takeIf { it.action == Intent.ACTION_SEND }?.getStringExtra(Intent.EXTRA_TEXT)
        if (shared != null) {
            handle(shared)
            finish()
            return
        }

        ListenService.pause(this)
        val ask = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "he-IL")
            .putExtra(RecognizerIntent.EXTRA_PROMPT, "על מה ומתי להזכיר?")
        try {
            speech.launch(ask)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, "לא נמצא שירות זיהוי דיבור בטלפון", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    override fun onDestroy() {
        if (isFinishing) ListenService.resume(this)
        super.onDestroy()
    }

    private fun handle(text: String) {
        val outcome = ReminderCreator.create(this, text, requireTrigger = false)
        Toast.makeText(applicationContext, ReminderCreator.message(outcome, text), Toast.LENGTH_LONG).show()
        // The notification is where a reminder can be undone.
        if (outcome is Outcome.Created) Notifications.showOutcome(applicationContext, outcome, text)
    }
}
