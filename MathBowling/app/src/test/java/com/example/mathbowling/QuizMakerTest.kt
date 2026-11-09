package com.example.mathbowling

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class QuizMakerTest {

    @Test
    fun webPageBecomesReadableText() {
        val html = """
            <html><head><title>מערכת השמש &amp; כוכבים</title>
            <style>p { color: red }</style><script>var x = "<p>not text</p>";</script></head>
            <body><nav>תפריט</nav><h1>כוכבי לכת</h1><p>כדור הארץ הוא&nbsp;כוכב הלכת ה&#1513;לישי.</p>
            <!-- comment --><ul><li>מאדים</li><li>נוגה</li></ul><footer>זכויות</footer></body></html>
        """.trimIndent()
        val (title, text) = QuizMaker.htmlToText(html)
        assertEquals("מערכת השמש & כוכבים", title)
        assertTrue(text, text.contains("כדור הארץ הוא כוכב הלכת השלישי."))
        assertTrue(text.lines().contains("מאדים"))
        for (junk in listOf("color", "not text", "comment", "תפריט", "זכויות")) assertFalse(junk, text.contains(junk))
    }

    @Test
    fun oldHebrewEncodingIsDetected() {
        val html = "<meta charset=\"windows-1255\"><p>שלום</p>"
        val bytes = html.toByteArray(charset("windows-1255"))
        assertTrue(QuizMaker.decodeText(bytes).contains("שלום"))
        assertEquals("abc", QuizMaker.decodeText("﻿abc".toByteArray()))
        assertEquals("&#99999999;", QuizMaker.decodeEntities("&#99999999;"))
    }

    @Test
    fun wordFileParagraphs() {
        val xml = """<w:document><w:body><w:p><w:r><w:t>פסקה ראשונה</w:t></w:r></w:p>""" +
            """<w:p><w:r><w:t>Second &amp; last</w:t></w:r></w:p></w:body></w:document>"""
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("[Content_Types].xml")); zip.write("<x/>".toByteArray()); zip.closeEntry()
            zip.putNextEntry(ZipEntry("word/document.xml")); zip.write(xml.toByteArray()); zip.closeEntry()
        }
        assertEquals("פסקה ראשונה\nSecond & last", QuizMaker.docxToText(out.toByteArray()))
        val src = QuizMaker.sourceFromFile(out.toByteArray(), null, "notes.docx")
        assertTrue(src is QuizSource.Text)
    }

    @Test
    fun filesAreSortedByType() {
        assertTrue(QuizMaker.sourceFromFile(byteArrayOf(1), "application/pdf", "a") is QuizSource.Pdf)
        assertTrue(QuizMaker.sourceFromFile(byteArrayOf(1), null, "b.PDF") is QuizSource.Pdf)
        assertTrue(QuizMaker.sourceFromFile(byteArrayOf(1), "image/jpeg", "c.jpg") is QuizSource.Image)
        val txt = QuizMaker.sourceFromFile("hello".toByteArray(), "text/plain", "d.txt")
        assertEquals("hello", (txt as QuizSource.Text).text)
        try {
            QuizMaker.sourceFromFile(ByteArray(QuizMaker.MAX_FILE_BYTES + 1), "application/pdf", "big.pdf")
            fail("too big")
        } catch (e: QuizMakerException) {
            assertEquals("too_big", e.message)
        }
    }

    @Test
    fun requestUsesStrictSchemaAndTheRightInput() {
        val pdf = QuizMaker.buildRequest("gpt-5-mini", QuizSource.Pdf(byteArrayOf(1, 2, 3), "lesson"), 15, false)
        assertEquals("gpt-5-mini", pdf.getString("model"))
        assertEquals("low", pdf.getJSONObject("reasoning").getString("effort"))
        val format = pdf.getJSONObject("text").getJSONObject("format")
        assertEquals("json_schema", format.getString("type"))
        assertTrue(format.getBoolean("strict"))
        val content = pdf.getJSONArray("input").getJSONObject(0).getJSONArray("content")
        assertTrue(content.getJSONObject(0).getString("text").contains("exactly 15 questions, all in Hebrew"))
        val file = content.getJSONObject(1)
        assertEquals("input_file", file.getString("type"))
        assertEquals("lesson.pdf", file.getString("filename"))
        assertEquals("data:application/pdf;base64,AQID", file.getString("file_data"))

        val text = QuizMaker.buildRequest("gpt-4.1-mini", QuizSource.Text("x".repeat(QuizMaker.MAX_TEXT_CHARS + 50), "t"), 10, true)
        assertFalse("no reasoning setting for a non-reasoning model", text.has("reasoning"))
        val material = text.getJSONArray("input").getJSONObject(0).getJSONArray("content").getJSONObject(1).getString("text")
        assertTrue(material.length < QuizMaker.MAX_TEXT_CHARS + 100)

        // strict schemas need every property required and no extra properties, at every level
        fun check(o: JSONObject) {
            if (o.optString("type") == "object") {
                assertFalse(o.getBoolean("additionalProperties"))
                val props = o.getJSONObject("properties")
                assertEquals(props.keySet(), (0 until o.getJSONArray("required").length()).map { o.getJSONArray("required").getString(it) }.toSet())
                props.keySet().forEach { check(props.getJSONObject(it)) }
            }
            o.optJSONObject("items")?.let { check(it) }
        }
        check(QuizMaker.schema())
    }

    @Test
    fun aTopicAloneOrAsInstructionsForTheMaterial() {
        val topic = QuizMaker.buildRequest("gpt-5-mini", null, 10, false, "תעשה לי מבחן על מלחמת העולם השנייה")
        val parts = topic.getJSONArray("input").getJSONObject(0).getJSONArray("content")
        assertEquals("only the instructions, no material", 1, parts.length())
        val prompt = parts.getJSONObject(0).getString("text")
        assertTrue(prompt.contains("\"תעשה לי מבחן על מלחמת העולם השנייה\""))
        assertTrue(prompt.contains("well-established facts"))
        assertFalse(prompt.contains("attached material"))

        val both = QuizMaker.buildRequest("gpt-5-mini", QuizSource.Text("x".repeat(500), "notes"), 10, false, "רק על פרק 3")
        val bothPrompt = both.getJSONArray("input").getJSONObject(0).getJSONArray("content").getJSONObject(0).getString("text")
        assertTrue(bothPrompt.contains("ONLY on the attached material"))
        assertTrue(bothPrompt.contains("\"רק על פרק 3\""))

        try {
            QuizMaker.buildRequest("gpt-5-mini", null, 10, false, "  ")
            fail("nothing to make a quiz from")
        } catch (e: QuizMakerException) {
            assertEquals("no_source", e.message)
        }
    }

    /** A stand-in for OpenAI: answers each request with [n] questions numbered from a running counter. */
    private class FakeModel(val repeatFirst: Int = 0, val failOnCall: Int = -1) {
        var calls = 0
        var next = 0
        val asked = ArrayList<Int>()
        val avoidSeen = ArrayList<Int>()
        fun send(body: JSONObject): String {
            calls++
            if (calls == failOnCall) throw QuizMakerException("network:down")
            val prompt = body.getJSONArray("input").getJSONObject(0).getJSONArray("content").getJSONObject(0).getString("text")
            val n = Regex("exactly (\\d+) questions").find(prompt)!!.groupValues[1].toInt()
            asked.add(n)
            avoidSeen.add(prompt.substringAfter("already exist", "").lines().count { it.startsWith("- Q") })
            val qs = JSONArray()
            repeat(n) { i ->
                // on the second call, the first [repeatFirst] questions repeat old ones
                val id = if (calls == 2 && i < repeatFirst) i else next++
                qs.put(JSONObject().put("level", listOf("easy", "medium", "hard")[id % 3]).put("question", "Q$id?")
                    .put("correct", "a$id").put("wrong", JSONArray(listOf("b$id", "c$id"))).put("explanation", ""))
            }
            return JSONObject().put("title", "Big quiz").put("questions", qs).toString()
        }
    }

    @Test
    fun bigQuizzesComeInPartsWithoutRepeats() {
        val fake = FakeModel()
        val parts = ArrayList<Pair<Int, Int>>()
        val file = QuizMaker.generateWith("gpt-5-mini", null, 300, true, "anything", { p, t -> parts.add(p to t) }, { false }, fake::send)
        val items = CustomQuestions.parse(file).items
        assertEquals(300, items.size)
        assertEquals(300, items.map { it.question }.toSet().size)
        assertEquals(List(7) { 40 } + 20, fake.asked)
        assertEquals((1..8).map { it to 8 }, parts)
        // each part is told about everything made before it
        assertEquals((0 until 8).map { it * 40 }, fake.avoidSeen)
        assertEquals("Big quiz", QuizMaker.titleOf(file))

        // repeats are dropped and made up for with an extra round
        val rep = FakeModel(repeatFirst = 5)
        val file2 = QuizMaker.generateWith("gpt-5-mini", null, 80, true, "x", { _, _ -> }, { false }, rep::send)
        assertEquals(80, CustomQuestions.parse(file2).items.map { it.question }.toSet().size)
        assertEquals(listOf(40, 40, 5), rep.asked)

        // a later part failing keeps what was made; the first one failing is an error
        val flaky = FakeModel(failOnCall = 2)
        assertEquals(40, CustomQuestions.parse(QuizMaker.generateWith("m", null, 100, true, "x", { _, _ -> }, { false }, flaky::send)).items.size)
        try {
            QuizMaker.generateWith("m", null, 10, true, "x", { _, _ -> }, { false }, FakeModel(failOnCall = 1)::send)
            fail()
        } catch (e: QuizMakerException) {
            assertEquals("network:down", e.message)
        }
        // cancelling stops before the next part
        var calls = 0
        try {
            QuizMaker.generateWith("m", null, 100, true, "x", { _, _ -> }, { calls >= 1 }) { b -> calls++; fake.send(b) }
            fail()
        } catch (e: QuizMakerException) {
            assertEquals("cancelled", e.message)
            assertEquals(1, calls)
        }
    }

    private fun reply(text: String) = JSONObject()
        .put("status", "completed")
        .put("output", JSONArray()
            .put(JSONObject().put("type", "reasoning").put("summary", JSONArray()))
            .put(JSONObject().put("type", "message").put("role", "assistant").put("content", JSONArray()
                .put(JSONObject().put("type", "output_text").put("text", text)))))
        .toString()

    @Test
    fun readsTheModelsReply() {
        assertEquals("{\"a\":1}", QuizMaker.outputText(reply("{\"a\":1}")))
        val refusal = JSONObject().put("output", JSONArray().put(JSONObject().put("type", "message")
            .put("content", JSONArray().put(JSONObject().put("type", "refusal").put("refusal", "no"))))).toString()
        try { QuizMaker.outputText(refusal); fail() } catch (e: QuizMakerException) { assertEquals("refused:no", e.message) }
        val cut = JSONObject().put("status", "incomplete").put("output", JSONArray())
            .put("incomplete_details", JSONObject().put("reason", "max_output_tokens")).toString()
        try { QuizMaker.outputText(cut); fail() } catch (e: QuizMakerException) { assertEquals("incomplete:max_output_tokens", e.message) }
    }

    @Test
    fun quizBecomesAPlayableQuestionsFile() {
        fun q(level: String, question: String, correct: String, wrong: List<String>, exp: String = "") = JSONObject()
            .put("level", level).put("question", question).put("correct", correct)
            .put("wrong", JSONArray(wrong)).put("explanation", exp)
        val quiz = JSONObject().put("title", "כוכבי לכת").put("questions", JSONArray()
            .put(q("easy", "מה הכוכב השלישי מהשמש?", "כדור הארץ", listOf("מאדים", "נוגה"), "הוא השלישי."))
            .put(q("hard", "#איזה | כוכב הכי חם?", "נוגה", listOf("כוכב חמה", "מאדים", "צדק", "שבתאי")))
            .put(q("medium", "only one wrong", "a", listOf("b", "A")))          // dropped: one real wrong answer
            .put(q("weird", "level unknown?", "yes", listOf("no", "maybe"))))   // goes to medium
        val file = QuizMaker.toQuestionsFile(quiz.toString(), "planets.pdf")
        assertEquals("כוכבי לכת", QuizMaker.titleOf(file))
        val parsed = CustomQuestions.parse(file)
        assertTrue("bad lines: ${parsed.badLines}\n$file", parsed.badLines.isEmpty())
        assertEquals(3, parsed.items.size)
        val first = parsed.items[0]
        assertEquals(Level.EASY, first.level)
        assertEquals("כדור הארץ", first.correct)
        assertEquals("הוא השלישי.", first.explanation)
        val hot = parsed.items.first { it.correct == "נוגה" }
        assertEquals(Level.HARD, hot.level)
        assertEquals("איזה / כוכב הכי חם?", hot.question)
        assertEquals(3, hot.wrong.size)
        assertEquals(Level.MEDIUM, parsed.items.first { it.question == "level unknown?" }.level)
        try {
            QuizMaker.toQuestionsFile(JSONObject().put("title", "x").put("questions", JSONArray()).toString(), "s")
            fail()
        } catch (e: QuizMakerException) {
            assertEquals("empty", e.message)
        }
    }
}
