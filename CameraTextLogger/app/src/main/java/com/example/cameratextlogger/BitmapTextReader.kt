package com.example.cameratextlogger

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.concurrent.Executor

/**
 * Reads [image] with Tesseract, adding the numbers ML Kit reads in it. Waits for ML Kit, so call
 * it on a background thread.
 */
internal fun readHebrewAndEnglish(
    tesseract: TesseractRecognizer,
    mlKit: TextRecognizer,
    image: Bitmap,
): List<TextLine> {
    val lines = tesseract.recognizeLines(image)
    val numbers = try {
        Tasks.await(mlKit.process(InputImage.fromBitmap(image, 0))).toTextLines()
    } catch (e: Exception) {
        Log.w(TAG, "ML Kit could not read numbers", e)
        emptyList()
    }
    return withMlKitNumbers(lines, numbers)
}

/** Text read from an image: the entries to log, and the lines with where each one is. */
class ReadText(val entries: List<String>, val lines: List<TextLine>) {
    companion object {
        val EMPTY = ReadText(emptyList(), emptyList())
    }
}

/**
 * Reads the text in upright images, such as screenshots, with ML Kit or Tesseract. Call [read]
 * on [executor], which must be single-threaded.
 */
class BitmapTextReader(
    private val context: Context,
    private val executor: Executor,
    private val language: RecognitionLanguage,
    private val readBy: ReadBy,
) {

    // Used for Hebrew too, for the numbers Tesseract misses.
    private val mlKit = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private var tesseract: TesseractRecognizer? = null
    private var closed = false

    /** Calls [onDone] exactly once, on [executor], with the text found (empty on failure). */
    fun read(image: Bitmap, onDone: (ReadText) -> Unit) {
        val languages = language.tesseractLanguages
        if (closed) {
            onDone(ReadText.EMPTY)
        } else if (languages == null) {
            mlKit.process(InputImage.fromBitmap(image, 0))
                .addOnSuccessListener(executor) { onDone(ReadText(it.toEntries(readBy), it.toTextLines())) }
                .addOnFailureListener(executor) {
                    Log.e(TAG, "Text recognition failed", it)
                    onDone(ReadText.EMPTY)
                }
                .addOnCanceledListener(executor) { onDone(ReadText.EMPTY) }
        } else {
            val text = try {
                val recognizer = tesseract ?: TesseractRecognizer(context, languages).also { tesseract = it }
                val lines = readHebrewAndEnglish(recognizer, mlKit, image)
                ReadText(lines.toEntries(readBy), lines)
            } catch (e: Exception) {
                Log.e(TAG, "Text recognition failed", e)
                ReadText.EMPTY
            }
            onDone(text)
        }
    }

    fun close() {
        mlKit.close()
        // Queued behind any image still being read, so Tesseract is never freed mid-recognition.
        executor.execute {
            closed = true
            tesseract?.close()
            tesseract = null
        }
    }
}
