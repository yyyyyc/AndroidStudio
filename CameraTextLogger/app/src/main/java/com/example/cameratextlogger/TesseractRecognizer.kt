package com.example.cameratextlogger

import android.content.Context
import android.graphics.Bitmap
import com.googlecode.tesseract.android.TessBaseAPI
import java.io.File

// Below this mean confidence a line is usually Tesseract reading shapes in non-text, like wood grain.
private const val MIN_LINE_CONFIDENCE = 60f

/**
 * Reads text with Tesseract, for languages ML Kit doesn't support. Tesseract is slow to load and
 * not thread-safe: create, use and close it on one background thread.
 */
class TesseractRecognizer(context: Context, languages: String) {

    private val api = TessBaseAPI()

    init {
        val dataDir = File(context.filesDir, "tesseract")
        copyLanguageData(context, File(dataDir, "tessdata"), languages)
        check(api.init(dataDir.path, languages, TessBaseAPI.OEM_LSTM_ONLY)) {
            "Could not load Tesseract data for $languages"
        }
        // Camera frames are mostly not text. Page layout analysis (PSM_AUTO) finds no text at all
        // in a photo of a sign on a wall; sparse-text mode reads it.
        api.pageSegMode = TessBaseAPI.PageSegMode.PSM_SPARSE_TEXT
    }

    /**
     * Reads the lines in [frame], which must be upright, and where each one is. Sparse-text mode
     * puts each line in its own paragraph, so [toEntries] finds paragraphs from the positions.
     */
    fun recognizeLines(frame: Bitmap): List<TextLine> {
        api.setImage(frame)
        api.getUTF8Text() // Runs recognition; the text comes from the iterator.

        val lines = mutableListOf<TextLine>()
        val iterator = api.resultIterator ?: return emptyList()
        val level = TessBaseAPI.PageIteratorLevel.RIL_TEXTLINE
        try {
            iterator.begin()
            do {
                val text = iterator.getUTF8Text(level)
                if (text != null && iterator.confidence(level) >= MIN_LINE_CONFIDENCE) {
                    val box = iterator.getBoundingRect(level)
                    lines += TextLine(text, box.left, box.top, box.right, box.bottom)
                }
            } while (iterator.next(level))
        } finally {
            iterator.delete()
            api.clear()
        }
        return lines
    }

    fun close() = api.recycle()

    // Tesseract reads its data from files, so the copies bundled in assets are unpacked once.
    private fun copyLanguageData(context: Context, tessdata: File, languages: String) {
        tessdata.mkdirs()
        for (language in languages.split("+")) {
            val file = File(tessdata, "$language.traineddata")
            if (file.exists()) continue
            val partial = File(tessdata, "$language.traineddata.partial")
            context.assets.open("tessdata/$language.traineddata").use { input ->
                partial.outputStream().use { input.copyTo(it) }
            }
            partial.renameTo(file)
        }
    }
}
