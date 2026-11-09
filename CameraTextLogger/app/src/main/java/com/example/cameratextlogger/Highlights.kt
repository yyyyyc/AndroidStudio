package com.example.cameratextlogger

import java.text.Bidi

/** A rectangle in an image or on the screen, in pixels. */
data class HighlightBox(val left: Int, val top: Int, val right: Int, val bottom: Int)

/**
 * Where [pattern] was found among [lines], for drawing over the screen. A match inside a line gets
 * a box over just the matching characters, estimated from where they sit in the line: the
 * recognizers say where lines are, not letters. In paragraph mode, text found only once a
 * paragraph's lines are joined gets a box around the whole paragraph.
 */
fun highlightBoxes(lines: List<TextLine>, pattern: FindPattern, readBy: ReadBy): List<HighlightBox> {
    if (pattern.isEmpty) return emptyList()
    val paragraphs = when (readBy) {
        ReadBy.LINES -> lines.map { listOf(it) }
        ReadBy.PARAGRAPHS -> paragraphsOf(lines)
    }
    return paragraphs.flatMap { paragraph ->
        val boxes = paragraph.flatMap { lineBoxes(it, pattern) }
        val joined = paragraph.joinToString(" ") { normalizeLine(it.text) }
        if (boxes.isEmpty() && paragraph.size > 1 && pattern.matches(joined)) {
            listOf(HighlightBox(
                paragraph.minOf { it.left }, paragraph.minOf { it.top },
                paragraph.maxOf { it.right }, paragraph.maxOf { it.bottom },
            ))
        } else {
            boxes
        }
    }
}

private fun lineBoxes(line: TextLine, pattern: FindPattern): List<HighlightBox> {
    val text = normalizeLine(line.text)
    if (text.isEmpty()) return emptyList()
    val columns = visualColumns(text)
    val width = line.right - line.left
    return pattern.ranges(text).map { range ->
        val first = range.minOf { columns[it] }
        val last = range.maxOf { columns[it] }
        HighlightBox(
            line.left + width * first / text.length, line.top,
            line.left + width * (last + 1) / text.length, line.bottom,
        )
    }
}

/**
 * Which column, counting from the left, each character of [text] is drawn in. Hebrew runs right
 * to left, so in "שלום 123" the Hebrew word is on the right and the number on the left.
 */
internal fun visualColumns(text: String): IntArray {
    val bidi = Bidi(text, Bidi.DIRECTION_DEFAULT_LEFT_TO_RIGHT)
    if (bidi.isLeftToRight) return IntArray(text.length) { it }
    val runCount = bidi.runCount
    val levels = ByteArray(runCount) { bidi.getRunLevel(it).toByte() }
    val runs = Array<Any>(runCount) { it }
    Bidi.reorderVisually(levels, 0, runs, 0, runCount)
    val columns = IntArray(text.length)
    var column = 0
    for (run in runs) {
        val index = run as Int
        val start = bidi.getRunStart(index)
        val limit = bidi.getRunLimit(index)
        val characters = if (levels[index] % 2 == 1) (limit - 1 downTo start) else (start until limit)
        for (character in characters) columns[character] = column++
    }
    return columns
}
