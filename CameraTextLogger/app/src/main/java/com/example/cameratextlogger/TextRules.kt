package com.example.cameratextlogger

private const val LOG_SEPARATOR = "  "

fun normalizeLine(line: String) = line.trim().replace(Regex("\\s+"), " ")

/** Counts visible characters, so "A B" has two. */
fun characterCount(line: String) = line.count { !it.isWhitespace() }

fun isFound(line: String, findText: String) = FindPattern(findText).matches(line)

/** Where [findText] matches in [line]. */
fun matchRanges(line: String, findText: String) = FindPattern(findText).ranges(line)

fun GeneralSettings.accepts(line: String) =
    characterCount(line) >= minCharacters && (!ignoreSymbolsOnly || line.any { it.isLetterOrDigit() })

fun Session.saves(line: String) = when (saveMode) {
    SaveMode.ALL -> true
    SaveMode.MIN_CHARACTERS -> characterCount(line) >= saveMinCharacters
    SaveMode.CONTAINS -> line.contains(saveContains, ignoreCase = true)
}

/** How many of [lines] fit in the log when it already holds [saved] entries. */
fun Session.linesThatFit(lines: Int, saved: Int) =
    if (maxLogEntries == null) lines else lines.coerceAtMost(maxLogEntries - saved).coerceAtLeast(0)

// Normalized text never contains two spaces in a row, so the first one ends the timestamp.
fun formatLogLine(entry: LogEntry) = entry.timestamp + LOG_SEPARATOR + entry.text

fun parseLogLine(line: String): LogEntry {
    val parts = line.split(LOG_SEPARATOR, limit = 2)
    return if (parts.size == 2) LogEntry(parts[0], parts[1]) else LogEntry("", line)
}

// File names have no spaces, so the image log uses the same separator.
fun formatImageLine(entry: ImageEntry) =
    listOf(entry.timestamp, entry.fileName, entry.text).joinToString(LOG_SEPARATOR)

fun parseImageLine(line: String): ImageEntry? {
    val parts = line.split(LOG_SEPARATOR, limit = 3)
    return if (parts.size == 3) ImageEntry(parts[0], parts[1], parts[2]) else null
}

/** A line of recognized text and where it is in the frame, in pixels. */
data class TextLine(val text: String, val left: Int, val top: Int, val right: Int, val bottom: Int)

// Lines this many line-heights apart or less, and overlapping sideways, are one paragraph.
private const val MAX_LINE_GAP = 1.2

/**
 * Joins lines that sit one under another into paragraphs, top to bottom. Used for Tesseract,
 * whose sparse-text mode reports every line as its own paragraph.
 */
fun groupIntoParagraphs(lines: List<TextLine>): List<String> =
    paragraphsOf(lines).map { paragraph -> paragraph.joinToString(" ") { it.text.trim() } }

/**
 * Adds to Tesseract's lines the [mlKit] lines that have digits Tesseract didn't read. Tesseract
 * misses numbers in photos that ML Kit reads, like license plates. Only lines with digits: ML Kit
 * reads Hebrew letters as Latin nonsense.
 */
fun withMlKitNumbers(tesseract: List<TextLine>, mlKit: List<TextLine>): List<TextLine> {
    fun digits(text: String) = text.filter { it.isDigit() }
    val read = tesseract.map { digits(it.text) }.filter { it.isNotEmpty() }.toSet()
    return tesseract + mlKit.filter { line -> digits(line.text).let { it.isNotEmpty() && it !in read } }
}

/** The log entries for [this] lines: one per line, or one per paragraph. */
fun List<TextLine>.toEntries(readBy: ReadBy): List<String> = when (readBy) {
    ReadBy.LINES -> map { it.text }
    ReadBy.PARAGRAPHS -> groupIntoParagraphs(this)
}

/** The lines of each paragraph, as [groupIntoParagraphs] groups them. */
fun paragraphsOf(lines: List<TextLine>): List<List<TextLine>> {
    val paragraphs = mutableListOf<MutableList<TextLine>>()
    for (line in lines.sortedBy { it.top }) {
        val paragraph = paragraphs.lastOrNull { paragraph ->
            val last = paragraph.last()
            val gap = line.top - last.bottom
            gap <= (last.bottom - last.top) * MAX_LINE_GAP &&
                line.left < last.right && line.right > last.left
        }
        if (paragraph != null) paragraph += line else paragraphs += mutableListOf(line)
    }
    return paragraphs
}

data class ProcessedLine(val text: String, val found: Boolean)

/**
 * Turns the lines read from one camera frame into the lines a session should log.
 * Text the session is looking for skips the general and save rules: the user asked for it, even
 * if it is short, and it has to be in the log to be highlighted.
 *
 * Not thread-safe: call from a single thread.
 */
class LineProcessor(private val session: Session, private val settings: GeneralSettings) {

    private val deduplicator = TextDeduplicator(
        // Scans at a slow rate are too far apart to wait for a second sighting. A screen is read
        // without camera blur, and a screen that doesn't change is read only once, as is a photo.
        sightingsRequired =
            if (session.source == SessionSource.CAMERA && session.scanRate.intervalMs <= 1_000) 2 else 1,
        forgetAfterMs = maxOf(5_000, session.scanRate.intervalMs * 3),
    )

    private val pattern = session.findPattern

    fun process(lines: List<String>, nowMs: Long): List<ProcessedLine> {
        val handled = lines.map(::normalizeLine)
            .filter { pattern.matches(it) || settings.accepts(it) }
        return deduplicator.newLines(handled, nowMs, pattern::matches)
            .map { ProcessedLine(it, pattern.matches(it)) }
            .filter { it.found || session.saves(it.text) }
    }
}

/** Limits how often frames are scanned. An interval of 0 scans every frame it is offered. */
class ScanThrottle(private val intervalMs: Long) {

    private var lastScanMs: Long? = null

    /** How long until a scan is due, 0 if it is. */
    fun waitMs(nowMs: Long): Long {
        val last = lastScanMs ?: return 0
        return (last + intervalMs - nowMs).coerceAtLeast(0)
    }

    fun shouldScan(nowMs: Long): Boolean {
        val last = lastScanMs
        if (last != null && nowMs - last < intervalMs) return false
        lastScanMs = nowMs
        return true
    }
}
