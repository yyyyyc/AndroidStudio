package com.example.cameratextlogger

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TextRulesTest {

    private fun session(
        findText: String = "",
        scanRate: ScanRate = ScanRate.EVERY_5_SECONDS,
        saveMode: SaveMode = SaveMode.ALL,
        saveMinCharacters: Int = 5,
        saveContains: String = "",
    ) = Session("1", "Test", 0, findText, false, scanRate, saveMode, saveMinCharacters, saveContains)

    private fun texts(
        session: Session,
        lines: List<String>,
        settings: GeneralSettings = GeneralSettings(),
    ) = LineProcessor(session, settings).process(lines, 0).map { it.text }

    @Test
    fun `general settings drop short text`() {
        assertEquals(listOf("ABC"), texts(session(), listOf("A", "AB", "ABC")))
    }

    @Test
    fun `character count ignores spaces`() {
        assertEquals(emptyList<String>(), texts(session(), listOf("A B")))
    }

    @Test
    fun `general settings drop symbol-only text unless allowed`() {
        assertEquals(emptyList<String>(), texts(session(), listOf("-----")))
        val allowed = GeneralSettings(ignoreSymbolsOnly = false)
        assertEquals(listOf("-----"), texts(session(), listOf("-----"), allowed))
    }

    @Test
    fun `save mode keeps only long enough text`() {
        val s = session(saveMode = SaveMode.MIN_CHARACTERS, saveMinCharacters = 5)
        assertEquals(listOf("HELLO"), texts(s, listOf("HELL", "HELLO")))
    }

    @Test
    fun `general minimum still applies when session minimum is lower`() {
        val s = session(saveMode = SaveMode.MIN_CHARACTERS, saveMinCharacters = 1)
        assertEquals(emptyList<String>(), texts(s, listOf("AB")))
    }

    @Test
    fun `save mode keeps only text containing the string, ignoring case`() {
        val s = session(saveMode = SaveMode.CONTAINS, saveContains = "price")
        assertEquals(listOf("PRICE 90"), texts(s, listOf("PRICE 90", "HELLO")))
    }

    @Test
    fun `found text bypasses general and save rules`() {
        val s = session(findText = "ab", saveMode = SaveMode.CONTAINS, saveContains = "zzz")
        val result = LineProcessor(s, GeneralSettings()).process(listOf("AB", "HELLO"), 0)
        assertEquals(listOf(ProcessedLine("AB", true)), result)
    }

    @Test
    fun `without find text nothing is found`() {
        val result = LineProcessor(session(), GeneralSettings()).process(listOf("HELLO"), 0)
        assertEquals(listOf(ProcessedLine("HELLO", false)), result)
    }

    @Test
    fun `fast scan rates wait for a second sighting and slow ones do not`() {
        val fast = LineProcessor(session(scanRate = ScanRate.EVERY_SECOND), GeneralSettings())
        assertEquals(emptyList<ProcessedLine>(), fast.process(listOf("HELLO"), 0))
        assertEquals(1, fast.process(listOf("HELLO"), 1_000).size)

        val slow = LineProcessor(session(scanRate = ScanRate.EVERY_2_SECONDS), GeneralSettings())
        assertEquals(1, slow.process(listOf("HELLO"), 0).size)
    }

    @Test
    fun `text still in view is not logged again at the slowest rate`() {
        val p = LineProcessor(session(scanRate = ScanRate.EVERY_10_SECONDS), GeneralSettings())
        assertEquals(1, p.process(listOf("HELLO"), 0).size)
        assertEquals(0, p.process(emptyList(), 10_000).size)
        assertEquals(0, p.process(listOf("HELLO"), 20_000).size)
    }

    @Test
    fun `match ranges find every occurrence ignoring case`() {
        assertEquals(listOf(0..1, 3..4), matchRanges("ab AB", "ab"))
        assertEquals(emptyList<IntRange>(), matchRanges("ab", ""))
    }

    @Test
    fun `isFound ignores case and needs a search text`() {
        assertTrue(isFound("Hello World", "WORLD"))
        assertFalse(isFound("Hello", "WORLD"))
        assertFalse(isFound("Hello", ""))
    }

    @Test
    fun `Hebrew text is found, counted and highlighted`() {
        val line = "שלום 123" // "shalom 123"
        val find = "לו" // "lo"
        assertTrue(isFound(line, find))
        assertEquals(listOf(1..2), matchRanges(line, find))
        assertTrue(GeneralSettings(minCharacters = 7).accepts(line))
        val result = LineProcessor(session(findText = find), GeneralSettings()).process(listOf(line), 0)
        assertEquals(listOf(ProcessedLine(line, true)), result)
    }

    @Test
    fun `log lines round trip`() {
        val entry = LogEntry("2026-11-02 21:43:03", "HELLO WORLD")
        assertEquals(entry, parseLogLine(formatLogLine(entry)))
    }

    @Test
    fun `log limit caps lines and never goes negative`() {
        val limited = session().copy(maxLogEntries = 5)
        assertEquals(3, limited.linesThatFit(3, 0))
        assertEquals(2, limited.linesThatFit(3, 3))
        assertEquals(0, limited.linesThatFit(3, 5))
        assertEquals(0, limited.linesThatFit(3, 9))
    }

    @Test
    fun `no log limit keeps every line`() {
        assertEquals(3, session().linesThatFit(3, 1_000_000))
    }

    @Test
    fun `image log lines round trip`() {
        val entry = ImageEntry("2026-11-03 21:43:03", "1793687526989.jpg", "HELLO  WORLD")
        assertEquals(entry, parseImageLine(formatImageLine(entry)))
        assertEquals(null, parseImageLine("broken"))
    }

    @Test
    fun `processor uses find patterns`() {
        val result = LineProcessor(session(findText = "AB%|###"), GeneralSettings())
            .process(listOf("ABC", "123", "XAB"), 0)
        assertEquals(
            listOf(ProcessedLine("ABC", true), ProcessedLine("123", true), ProcessedLine("XAB", false)),
            result,
        )
    }

    @Test
    fun `lines close under each other form one paragraph, top to bottom`() {
        val lines = listOf(
            TextLine("WORLD", 100, 150, 400, 200),
            TextLine("HELLO", 100, 80, 400, 130),
        )
        assertEquals(listOf("HELLO WORLD"), groupIntoParagraphs(lines))
    }

    @Test
    fun `a line far below starts a new paragraph`() {
        val lines = listOf(
            TextLine("TITLE", 100, 0, 400, 50),
            TextLine("FOOTER", 100, 500, 400, 550),
        )
        assertEquals(listOf("TITLE", "FOOTER"), groupIntoParagraphs(lines))
    }

    @Test
    fun `side by side columns are separate paragraphs`() {
        val lines = listOf(
            TextLine("LEFT 1", 0, 0, 200, 50),
            TextLine("RIGHT 1", 500, 0, 700, 50),
            TextLine("LEFT 2", 0, 60, 200, 110),
            TextLine("RIGHT 2", 500, 60, 700, 110),
        )
        assertEquals(listOf("LEFT 1 LEFT 2", "RIGHT 1 RIGHT 2"), groupIntoParagraphs(lines))
    }

    @Test
    fun `throttle allows one scan per interval`() {
        val throttle = ScanThrottle(2_000)
        assertTrue(throttle.shouldScan(0))
        assertFalse(throttle.shouldScan(1_999))
        assertTrue(throttle.shouldScan(2_000))
    }

    @Test
    fun `throttle with no interval allows every scan`() {
        val throttle = ScanThrottle(0)
        assertTrue(throttle.shouldScan(0))
        assertTrue(throttle.shouldScan(0))
    }

    @Test
    fun `ml kit adds the numbers tesseract missed`() {
        val tesseract = listOf(TextLine("שלום", 0, 0, 10, 10), TextLine("12345", 0, 20, 10, 30))
        val mlKit = listOf(
            TextLine("Divu", 0, 0, 10, 10), // Hebrew read as Latin: dropped.
            TextLine("12 345", 0, 20, 10, 30), // Tesseract read these digits already.
            TextLine("472:48-004", 0, 40, 10, 50),
        )
        assertEquals(
            listOf("שלום", "12345", "472:48-004"),
            withMlKitNumbers(tesseract, mlKit).map { it.text },
        )
    }

    @Test
    fun `throttle says how long until the next scan`() {
        val throttle = ScanThrottle(2_000)
        assertEquals(0, throttle.waitMs(0))
        throttle.shouldScan(0)
        assertEquals(1_500, throttle.waitMs(500))
        assertEquals(0, throttle.waitMs(2_500))
    }

    @Test
    fun `a still screen is logged the first time it is read`() {
        val camera = session(scanRate = ScanRate.EVERY_SECOND)
        val screen = camera.copy(source = SessionSource.SCREEN)
        assertEquals(emptyList<String>(), texts(camera, listOf("HELLO")))
        assertEquals(listOf("HELLO"), texts(screen, listOf("HELLO")))
    }
}
