package com.example.cameratextlogger

enum class ScanRate(val label: String, val intervalMs: Long) {
    CONTINUOUS("Continuous", 0),
    EVERY_SECOND("Every second", 1_000),
    EVERY_2_SECONDS("Every 2 seconds", 2_000),
    EVERY_5_SECONDS("Every 5 seconds", 5_000),
    EVERY_10_SECONDS("Every 10 seconds", 10_000),
}

/** Which language a session reads, and so which recognizer it uses. */
enum class RecognitionLanguage(val label: String, val tesseractLanguages: String?) {
    ENGLISH("English", null),
    // Not Hebrew alone: the Hebrew model misreads numbers, e.g. "12345" as "15".
    HEBREW_AND_ENGLISH("Hebrew + English", "heb+eng"),
}

/** Where a session reads text from. */
enum class SessionSource(val label: String) {
    CAMERA("Camera"),
    SCREEN("Screen"),
    FILE("Photo or video"),
}

/** Whether each log entry is one line of text, or a paragraph with its lines joined. */
enum class ReadBy(val label: String) {
    LINES("Lines"),
    PARAGRAPHS("Paragraphs"),
}

/** Which scanned lines a session keeps in its log. Found text is always kept. */
enum class SaveMode { ALL, MIN_CHARACTERS, CONTAINS }

data class Session(
    val id: String,
    val name: String,
    val startedAtMs: Long,
    /** Normalized text to look for, empty if the session only records. */
    val findText: String,
    val notifyWhenFound: Boolean,
    val scanRate: ScanRate,
    val saveMode: SaveMode,
    val saveMinCharacters: Int,
    val saveContains: String,
    /** Saving stops once the log holds this many entries. Null means no limit. */
    val maxLogEntries: Int? = null,
    val language: RecognitionLanguage = RecognitionLanguage.ENGLISH,
    val beepWhenFound: Boolean = false,
    val captureImageWhenFound: Boolean = false,
    val readBy: ReadBy = ReadBy.LINES,
    val source: SessionSource = SessionSource.CAMERA,
    /** Marks found text on the camera preview, or on the screen over other apps. */
    val highlightFound: Boolean = false,
    /** Camera sessions only: the back and front cameras together, on phones that can. */
    val bothCameras: Boolean = false,
) {
    val findPattern by lazy { FindPattern(findText) }
}

/** App-wide rules for which text is handled at all, before any session rule applies. */
data class GeneralSettings(
    val minCharacters: Int = 3,
    val ignoreSymbolsOnly: Boolean = true,
)

data class LogEntry(val timestamp: String, val text: String)

/** A camera frame saved when a session found its text. */
data class ImageEntry(val timestamp: String, val fileName: String, val text: String)

data class SessionSummary(
    val session: Session,
    val entryCount: Int,
    val foundCount: Int,
    val imageCount: Int,
)
