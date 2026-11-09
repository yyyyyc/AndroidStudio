package com.example.cameratextlogger

// Lines this long or longer can be told apart by one character; shorter ones are too alike.
private const val MIN_LENGTH_FOR_NEAR_MATCH = 5

/**
 * Decides which recognized lines are worth logging. The camera produces many frames per
 * second, so without this the same text would be logged over and over.
 *
 * A line is reported once it has been seen in [sightingsRequired] frames; two drops most
 * one-frame misreads. It is not reported again until it has been out of view for longer than
 * [forgetAfterMs]. A line from the previous frame is never forgotten: on a slow device one frame
 * can take longer than [forgetAfterMs], and the line would otherwise never reach two sightings.
 *
 * Not thread-safe: call from a single thread.
 */
class TextDeduplicator(
    private val forgetAfterMs: Long = 5_000,
    private val sightingsRequired: Int = 2,
) {

    private class Entry(
        var lastSeenMs: Long,
        var lastSeenFrame: Long,
        var frames: Int = 0,
        var reported: Boolean = false,
    )

    private val entries = HashMap<String, Entry>()
    private var frame = 0L

    /**
     * Lines that [isFound] are text the user is looking for. They are reported on their first
     * sighting, since a license plate may be in view for a single scan. A found line differing
     * by one character from one already reported, like "12-345-67" read as "12-345-61", is the
     * same text misread and is not reported again.
     */
    fun newLines(
        lines: List<String>,
        nowMs: Long,
        isFound: (String) -> Boolean = { false },
    ): List<String> {
        frame++
        entries.values.removeAll {
            nowMs - it.lastSeenMs > forgetAfterMs && frame - it.lastSeenFrame > 1
        }

        val result = mutableListOf<String>()
        for (line in lines.map(::normalizeLine).filter { it.isNotEmpty() }.distinct()) {
            val found = isFound(line)
            val entry = entries[line]
                ?: (if (found) nearMatch(line) else null)
                ?: Entry(nowMs, frame).also { entries[line] = it }
            if (entry.lastSeenFrame == frame && entry.frames > 0) continue // Two misreads of one line.
            entry.lastSeenMs = nowMs
            entry.lastSeenFrame = frame
            entry.frames++
            if (!entry.reported && (found || entry.frames >= sightingsRequired)) {
                entry.reported = true
                result += line
            }
        }
        return result
    }

    // A reported line of the same length that differs from [line] in one character.
    private fun nearMatch(line: String): Entry? {
        if (line.length < MIN_LENGTH_FOR_NEAR_MATCH) return null
        return entries.entries.firstOrNull { (text, entry) ->
            entry.reported && text.length == line.length &&
                text.indices.count { !text[it].equals(line[it], ignoreCase = true) } == 1
        }?.value
    }
}
