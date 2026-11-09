package com.example.cameratextlogger

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.SystemClock
import android.util.Log
import androidx.annotation.OptIn
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.view.TransformExperimental
import androidx.camera.view.transform.ImageProxyTransformFactory
import androidx.camera.view.transform.OutputTransform
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.concurrent.Executor

/** Gives the frame text was read from, as an upright image. Only valid during the callback. */
fun interface FrameCapture {
    fun capture(): Bitmap
}

/** ML Kit's result as log entries: one per line, or one per block with its lines joined. */
fun Text.toEntries(readBy: ReadBy): List<String> = when (readBy) {
    ReadBy.LINES -> textBlocks.flatMap { block -> block.lines.map { it.text } }
    ReadBy.PARAGRAPHS -> textBlocks.map { block -> block.lines.joinToString(" ") { it.text } }
}

/** ML Kit's lines and where each one is. */
fun Text.toTextLines(): List<TextLine> = textBlocks.flatMap { block ->
    block.lines.mapNotNull { line ->
        line.boundingBox?.let { TextLine(line.text, it.left, it.top, it.right, it.bottom) }
    }
}

private fun ImageProxy.toUprightBitmap(): Bitmap {
    val frame = toBitmap()
    val rotation = imageInfo.rotationDegrees
    if (rotation == 0) return frame
    val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
    return Bitmap.createBitmap(frame, 0, 0, frame.width, frame.height, matrix, true)
}

/** Maps positions in an upright frame, as the recognizers give them, to the preview. */
@OptIn(TransformExperimental::class)
private fun ImageProxy.uprightTransform(): OutputTransform =
    ImageProxyTransformFactory().apply { isUsingRotationDegrees = true }.getOutputTransform(this)

/**
 * Reads the text in camera frames, skipping frames to keep to [intervalMs]. Uses ML Kit for
 * English and Tesseract for Hebrew. [executor] must be single-threaded and the same one the
 * analyzer is registered on, so [onLines] calls never overlap and Tesseract stays on one thread.
 * [onPositions], if given, gets the lines read with where each one is in the frame.
 */
class TextAnalyzer(
    private val context: Context,
    private val executor: Executor,
    private val language: RecognitionLanguage,
    private val readBy: ReadBy,
    intervalMs: Long,
    private val onPositions: ((List<TextLine>, OutputTransform) -> Unit)?,
    private val onLines: (List<String>, FrameCapture) -> Unit,
) : ImageAnalysis.Analyzer {

    private val throttle = ScanThrottle(intervalMs)
    // Used for Hebrew too, for the numbers Tesseract misses.
    private val mlKit = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    // Created on first use, on the analysis thread: loading the language data takes a while.
    private var tesseract: TesseractRecognizer? = null
    private var closed = false

    override fun analyze(imageProxy: ImageProxy) {
        if (closed || mlKitBusy || !throttle.shouldScan(SystemClock.elapsedRealtime())) {
            imageProxy.close()
            return
        }
        val languages = language.tesseractLanguages
        if (languages == null) analyzeWithMlKit(imageProxy) else analyzeWithTesseract(imageProxy, languages)
    }

    // Set while ML Kit reads a frame, so frames arriving meanwhile are skipped, not queued.
    private var mlKitBusy = false

    // ML Kit reads the same upright picture that is saved: given the camera's raw image instead,
    // the positions it reported were shifted sideways, at least on the emulator.
    private fun analyzeWithMlKit(imageProxy: ImageProxy) {
        val frame: Bitmap
        val transform: OutputTransform
        try {
            frame = imageProxy.toUprightBitmap()
            transform = imageProxy.uprightTransform()
        } finally {
            imageProxy.close()
        }
        mlKitBusy = true
        mlKit.process(InputImage.fromBitmap(frame, 0))
            .addOnSuccessListener(executor) { result ->
                onPositions?.invoke(result.toTextLines(), transform)
                onLines(result.toEntries(readBy)) { frame }
            }
            .addOnFailureListener(executor) { Log.e(TAG, "Text recognition failed", it) }
            .addOnCompleteListener(executor) { mlKitBusy = false }
    }

    private fun analyzeWithTesseract(imageProxy: ImageProxy, languages: String) {
        val frame: Bitmap
        val transform: OutputTransform
        try {
            frame = imageProxy.toUprightBitmap()
            transform = imageProxy.uprightTransform()
        } finally {
            imageProxy.close()
        }
        val lines = try {
            val recognizer = tesseract ?: TesseractRecognizer(context, languages).also { tesseract = it }
            readHebrewAndEnglish(recognizer, mlKit, frame)
        } catch (e: Exception) {
            Log.e(TAG, "Text recognition failed", e)
            emptyList()
        }
        onPositions?.invoke(lines, transform)
        onLines(lines.toEntries(readBy)) { frame }
    }

    fun close() {
        mlKit.close()
        // Queued behind any frame still being read, so Tesseract is never freed mid-recognition.
        executor.execute {
            closed = true
            tesseract?.close()
            tesseract = null
        }
    }
}
