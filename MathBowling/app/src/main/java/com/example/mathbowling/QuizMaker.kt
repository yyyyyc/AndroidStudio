package com.example.mathbowling

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Base64
import java.util.zip.ZipInputStream

/** What the quiz is made from. */
sealed class QuizSource(val name: String) {
    class Text(val text: String, name: String) : QuizSource(name)
    /** Sent to the model as a file: it reads PDF text and page images itself. */
    class Pdf(val bytes: ByteArray, name: String) : QuizSource(name)
    class Image(val bytes: ByteArray, val mime: String, name: String) : QuizSource(name)
}

class QuizMakerException(message: String) : Exception(message)

/**
 * Makes a quiz from a document, a web page or text, using the OpenAI Responses API with a strict JSON schema,
 * and turns it into the game's questions-file format (see [CustomQuestions]).
 */
object QuizMaker {

    const val DEFAULT_MODEL = "gpt-5-mini"
    private const val ENDPOINT = "https://api.openai.com/v1/responses"
    /** Longer text is cut, to keep the request (and the bill) reasonable. */
    const val MAX_TEXT_CHARS = 120_000
    const val MAX_FILE_BYTES = 20 * 1024 * 1024

    // ------------------------------------------------------------------ reading sources

    /** Turns a picked file into a source, by its type or name. */
    fun sourceFromFile(bytes: ByteArray, mime: String?, name: String): QuizSource {
        if (bytes.size > MAX_FILE_BYTES) throw QuizMakerException("too_big")
        val lower = name.lowercase()
        val type = mime?.lowercase().orEmpty()
        return when {
            type == "application/pdf" || lower.endsWith(".pdf") -> QuizSource.Pdf(bytes, name)
            type.startsWith("image/") -> QuizSource.Image(bytes, type, name)
            lower.endsWith(".docx") || type.contains("wordprocessingml") -> QuizSource.Text(docxToText(bytes), name)
            lower.endsWith(".html") || lower.endsWith(".htm") || type == "text/html" ->
                QuizSource.Text(htmlToText(decodeText(bytes, type)).second, name)
            else -> QuizSource.Text(decodeText(bytes, type), name)
        }
    }

    /** Downloads a web page (or a PDF / image link). Blocking: call off the main thread. */
    fun sourceFromUrl(url: String): QuizSource {
        val fixed = if (url.contains("://")) url.trim() else "https://" + url.trim()
        val conn = (URL(fixed).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36")
            setRequestProperty("Accept", "text/html,application/pdf,text/plain,image/*;q=0.8,*/*;q=0.5")
        }
        try {
            val code = conn.responseCode
            if (code !in 200..299) throw QuizMakerException("http_$code")
            val type = conn.contentType.orEmpty().lowercase()
            val bytes = conn.inputStream.use { readCapped(it, MAX_FILE_BYTES) }
            val name = fixed.substringAfter("://").substringBefore("?").trimEnd('/')
            return when {
                type.startsWith("application/pdf") || fixed.lowercase().endsWith(".pdf") -> QuizSource.Pdf(bytes, name.substringAfterLast('/'))
                type.startsWith("image/") -> QuizSource.Image(bytes, type.substringBefore(';'), name)
                type.startsWith("text/plain") -> QuizSource.Text(decodeText(bytes, type), name)
                else -> {
                    val (title, text) = htmlToText(decodeText(bytes, type))
                    QuizSource.Text(text, title.ifBlank { name })
                }
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun readCapped(input: java.io.InputStream, cap: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            if (out.size() > cap) throw QuizMakerException("too_big")
        }
        return out.toByteArray()
    }

    /** Text bytes in the charset the server or the page declares (Hebrew sites still use windows-1255 sometimes). */
    fun decodeText(bytes: ByteArray, contentType: String = ""): String {
        val declared = Regex("charset=[\"']?([A-Za-z0-9_-]+)", RegexOption.IGNORE_CASE).let { re ->
            re.find(contentType)?.groupValues?.get(1)
                ?: re.find(String(bytes, 0, minOf(bytes.size, 4096), Charsets.ISO_8859_1))?.groupValues?.get(1)
        }
        val cs = try { declared?.let { charset(it) } } catch (e: Exception) { null } ?: Charsets.UTF_8
        return String(bytes, cs).removePrefix("﻿")
    }

    private val entityRe = Regex("&(#x?[0-9a-fA-F]+|[a-zA-Z]+);")
    private val named = mapOf("amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to " ",
        "ndash" to "–", "mdash" to "—", "hellip" to "…", "rsquo" to "’", "lsquo" to "‘", "rdquo" to "”", "ldquo" to "“",
        "shy" to "", "lrm" to "", "rlm" to "")

    fun decodeEntities(s: String): String = entityRe.replace(s) { m ->
        val e = m.groupValues[1]
        fun cp(v: Int?) = v?.let { runCatching { String(Character.toChars(it)) }.getOrNull() } ?: m.value
        when {
            e.startsWith("#x") || e.startsWith("#X") -> cp(e.substring(2).toIntOrNull(16))
            e.startsWith("#") -> cp(e.substring(1).toIntOrNull())
            else -> named[e.lowercase()] ?: m.value
        }
    }

    /** The readable text of a web page, and its title. */
    fun htmlToText(html: String): Pair<String, String> {
        val title = Regex("<title[^>]*>(.*?)</title>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            .find(html)?.groupValues?.get(1)?.let { decodeEntities(it).trim().replace(Regex("\\s+"), " ") }.orEmpty()
        var s = html
        s = s.replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), " ")
        s = s.replace(Regex("<(script|style|noscript|svg|template|head|nav|footer)\\b.*?</\\1\\s*>",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)), " ")
        s = s.replace(Regex("<(br|/p|/div|/li|/h[1-6]|/tr|/section|/article)\\b[^>]*>", RegexOption.IGNORE_CASE), "\n")
        s = s.replace(Regex("<[^>]+>"), " ")
        s = decodeEntities(s)
        val text = s.lines().map { it.replace(Regex("[ \\t\\u00A0]+"), " ").trim() }.filter { it.isNotEmpty() }.joinToString("\n")
        return title to text
    }

    /** The paragraphs of a Word (.docx) file. */
    fun docxToText(bytes: ByteArray): String {
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.name == "word/document.xml") {
                    val xml = String(zip.readBytes(), Charsets.UTF_8)
                    val s = xml.replace(Regex("</w:p>"), "\n").replace(Regex("<w:tab/>"), " ").replace(Regex("<[^>]+>"), "")
                    return decodeEntities(s).lines().map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n")
                }
            }
        }
        throw QuizMakerException("bad_docx")
    }

    // ------------------------------------------------------------------ the request

    /**
     * [hasMaterial]: a document / page / text is attached. [request]: what the player asked for, e.g.
     * "a quiz about World War II" (the whole subject when there is no material, extra instructions when there is).
     */
    fun prompt(count: Int, english: Boolean, hasMaterial: Boolean = true, request: String = "", avoid: List<String> = emptyList()): String {
        val lang = if (english) "English" else "Hebrew"
        val req = request.trim()
        val basis = when {
            hasMaterial && req.isNotEmpty() ->
                "based ONLY on the attached material. The player also asked (follow it, as long as it fits the material): \"$req\""
            hasMaterial -> "based ONLY on the attached material."
            else -> "about what the player asked for: \"$req\".\n" +
                "Use only well-established facts you are sure about (no guesses, no disputed claims, no very recent events). " +
                "If the request mentions an age, a grade or a focus, follow it; otherwise aim at a curious teenager."
        }
        val answerable = if (hasMaterial) "can be answered from the material" else "has one clearly correct answer"
        return """
You are writing a multiple-choice quiz for a quiz game, $basis
Write exactly $count questions, all in $lang (translate if needed).
Rules:
- Each question is short and clear (at most about 120 characters) and $answerable.
- Give the correct answer and 2 or 3 wrong answers. Wrong answers must be believable, about the same length and style as the correct one, different from each other, and clearly wrong.
- Answers are short: at most 6 words / 40 characters. No "all of the above" / "none of the above".
- Never use the character | in any text.
- explanation: one short sentence saying why the answer is right.
- level: about a third "easy" (main facts), a third "medium", a third "hard" (details, connections between ideas).
- Cover the whole subject, not only its beginning. Do not repeat questions.
- title: a short name for the quiz (2-5 words, in $lang).
""".trim() + if (avoid.isEmpty()) "" else
            "\n\nThese questions already exist. Write NEW ones about other facts (do not repeat or rephrase them):\n" +
                avoid.joinToString("\n") { "- $it" }
    }

    fun schema(): JSONObject {
        val item = JSONObject()
            .put("type", "object")
            .put("additionalProperties", false)
            .put("required", JSONArray(listOf("level", "question", "correct", "wrong", "explanation")))
            .put("properties", JSONObject()
                .put("level", JSONObject().put("type", "string").put("enum", JSONArray(listOf("easy", "medium", "hard"))))
                .put("question", JSONObject().put("type", "string"))
                .put("correct", JSONObject().put("type", "string"))
                .put("wrong", JSONObject().put("type", "array").put("items", JSONObject().put("type", "string")))
                .put("explanation", JSONObject().put("type", "string")))
        return JSONObject()
            .put("type", "object")
            .put("additionalProperties", false)
            .put("required", JSONArray(listOf("title", "questions")))
            .put("properties", JSONObject()
                .put("title", JSONObject().put("type", "string"))
                .put("questions", JSONObject().put("type", "array").put("items", item)))
    }

    fun buildRequest(model: String, source: QuizSource?, count: Int, english: Boolean, request: String = "",
                     avoid: List<String> = emptyList()): JSONObject {
        if (source == null && request.isBlank()) throw QuizMakerException("no_source")
        val content = JSONArray()
        content.put(JSONObject().put("type", "input_text").put("text", prompt(count, english, source != null, request, avoid)))
        when (source) {
            null -> {}
            is QuizSource.Text -> {
                val text = source.text.take(MAX_TEXT_CHARS)
                content.put(JSONObject().put("type", "input_text").put("text", "MATERIAL (${source.name}):\n$text"))
            }
            is QuizSource.Pdf -> content.put(JSONObject()
                .put("type", "input_file")
                .put("filename", source.name.ifBlank { "document.pdf" }.let { if (it.lowercase().endsWith(".pdf")) it else "$it.pdf" })
                .put("file_data", "data:application/pdf;base64," + Base64.getEncoder().encodeToString(source.bytes)))
            is QuizSource.Image -> content.put(JSONObject()
                .put("type", "input_image")
                .put("image_url", "data:${source.mime};base64," + Base64.getEncoder().encodeToString(source.bytes)))
        }
        val body = JSONObject()
            .put("model", model)
            .put("input", JSONArray().put(JSONObject().put("role", "user").put("content", content)))
            .put("text", JSONObject().put("format", JSONObject()
                .put("type", "json_schema")
                .put("name", "quiz")
                .put("strict", true)
                .put("schema", schema())))
        // reasoning models: a little thinking is enough for a quiz, and much faster
        val m = model.lowercase()
        if (m.startsWith("gpt-5") || m.startsWith("o")) body.put("reasoning", JSONObject().put("effort", "low"))
        return body
    }

    /** The model's text from a Responses API reply. */
    fun outputText(response: String): String {
        val root = JSONObject(response)
        val sb = StringBuilder()
        val output = root.optJSONArray("output") ?: JSONArray()
        for (i in 0 until output.length()) {
            val item = output.optJSONObject(i) ?: continue
            if (item.optString("type") != "message") continue
            val parts = item.optJSONArray("content") ?: continue
            for (j in 0 until parts.length()) {
                val part = parts.optJSONObject(j) ?: continue
                when (part.optString("type")) {
                    "output_text" -> sb.append(part.optString("text"))
                    "refusal" -> throw QuizMakerException("refused:" + part.optString("refusal"))
                }
            }
        }
        if (sb.isEmpty()) {
            val reason = root.optJSONObject("incomplete_details")?.optString("reason").orEmpty()
            throw QuizMakerException(if (reason.isNotEmpty()) "incomplete:$reason" else "empty")
        }
        return sb.toString()
    }

    /** A big quiz is asked for in parts of this size (one huge answer is slow and may be cut off). */
    const val BATCH = 40
    const val MAX_QUESTIONS = 300

    /**
     * Makes [count] questions, in parts of [BATCH]; each part is told which questions already exist so it adds new ones.
     * [onPart] reports (part, parts) before each request. When a later part fails, the questions made so far are kept.
     * Blocking: call off the main thread.
     */
    fun generate(apiKey: String, model: String, source: QuizSource?, count: Int, english: Boolean, request: String = "",
                 onPart: (part: Int, parts: Int) -> Unit = { _, _ -> },
                 isCancelled: () -> Boolean = { false },
                 onConnection: (HttpURLConnection) -> Unit = {}): String =
        generateWith(model, source, count, english, request, onPart, isCancelled) { body -> requestOnce(apiKey, body, onConnection) }

    /** The batching, with [send] doing the request (the network, or a stand-in in tests). */
    internal fun generateWith(model: String, source: QuizSource?, count: Int, english: Boolean, request: String,
                              onPart: (part: Int, parts: Int) -> Unit, isCancelled: () -> Boolean,
                              send: (JSONObject) -> String): String {
        val total = count.coerceIn(1, MAX_QUESTIONS)
        val parts = (total + BATCH - 1) / BATCH
        val all = ArrayList<QuizQ>()
        val seen = HashSet<String>()
        var title = ""
        var part = 0
        // a part may bring back fewer new questions (repeats are dropped), so allow a couple of extra rounds
        while (all.size < total && part < parts + 2) {
            if (isCancelled()) throw QuizMakerException("cancelled")
            part++
            onPart(minOf(part, parts), parts)
            val want = minOf(BATCH, total - all.size)
            val reply = try {
                send(buildRequest(model, source, want, english, request, all.map { it.question }))
            } catch (e: QuizMakerException) {
                if (all.isEmpty() || isCancelled()) throw e
                break // keep what we have
            }
            val (t, qs) = parseQuiz(reply)
            if (title.isEmpty()) title = t
            var added = 0
            for (q in qs) {
                if (all.size >= total) break
                if (seen.add(normalize(q.question))) { all.add(q); added++ }
            }
            if (added == 0 && part >= parts) break
        }
        if (all.isEmpty()) throw QuizMakerException("empty")
        val name = source?.name ?: request.trim().take(80)
        return formatFile(title.ifEmpty { name }, name, all)
    }

    private fun requestOnce(apiKey: String, body: JSONObject, onConnection: (HttpURLConnection) -> Unit): String {
        val bytes = body.toString().toByteArray(Charsets.UTF_8)
        val conn = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 30_000
            readTimeout = 300_000
            setRequestProperty("Authorization", "Bearer " + apiKey.trim())
            setRequestProperty("Content-Type", "application/json")
        }
        onConnection(conn)
        try {
            conn.outputStream.use { it.write(bytes) }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)?.use { String(it.readBytes(), Charsets.UTF_8) }.orEmpty()
            if (code !in 200..299) {
                val msg = try { JSONObject(text).optJSONObject("error")?.optString("message").orEmpty() } catch (e: Exception) { "" }
                throw QuizMakerException("api_$code:" + msg.take(300))
            }
            return outputText(text)
        } catch (e: IOException) {
            throw QuizMakerException("network:" + (e.message ?: ""))
        } finally {
            conn.disconnect()
        }
    }

    // ------------------------------------------------------------------ to the game's file format

    class QuizQ(val level: String, val question: String, val correct: String, val wrong: List<String>, val explanation: String)

    private fun clean(s: String) = s.replace("|", "/").replace(Regex("\\s+"), " ").trim().trimStart('#', '[', '>', ' ')

    /** For spotting the same question twice. */
    private fun normalize(s: String) = s.lowercase().filter { it.isLetterOrDigit() }

    /** The quiz JSON: its title and the usable questions (each with a right answer and 2-3 different wrong ones). */
    fun parseQuiz(json: String): Pair<String, List<QuizQ>> {
        val root = JSONObject(json)
        val arr = root.optJSONArray("questions") ?: throw QuizMakerException("empty")
        val out = ArrayList<QuizQ>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val q = clean(o.optString("question"))
            val correct = clean(o.optString("correct"))
            val wrongArr = o.optJSONArray("wrong") ?: JSONArray()
            val wrong = (0 until wrongArr.length()).map { clean(wrongArr.optString(it)) }
                .filter { it.isNotEmpty() && !it.equals(correct, ignoreCase = true) }
                .distinctBy { it.lowercase() }
                .take(3)
            if (q.isEmpty() || correct.isEmpty() || wrong.size < 2) continue
            val level = o.optString("level").takeIf { it == "easy" || it == "medium" || it == "hard" } ?: "medium"
            out.add(QuizQ(level, q, correct, wrong, clean(o.optString("explanation"))))
        }
        return clean(root.optString("title")) to out
    }

    fun formatFile(title: String, sourceName: String, questions: List<QuizQ>): String {
        if (questions.isEmpty()) throw QuizMakerException("empty")
        val out = StringBuilder()
        out.append("# ").append(title).append('\n')
        out.append("# Made by AI from: ").append(sourceName.replace('\n', ' ')).append('\n')
        out.append("# question | right answer | wrong answer | wrong answer\n")
        for (level in listOf("easy", "medium", "hard")) {
            val qs = questions.filter { it.level == level }
            if (qs.isEmpty()) continue
            out.append('\n').append('[').append(level.uppercase()).append("]\n")
            for (q in qs) {
                out.append(q.question).append(" | ").append(q.correct)
                q.wrong.forEach { out.append(" | ").append(it) }
                out.append('\n')
                if (q.explanation.isNotEmpty()) out.append("> ").append(q.explanation).append('\n')
            }
        }
        return out.toString()
    }

    /** The quiz JSON as a questions file the player can also open and edit. */
    fun toQuestionsFile(json: String, sourceName: String): String {
        val (title, qs) = parseQuiz(json)
        return formatFile(title.ifEmpty { sourceName }, sourceName, qs)
    }

    /** The quiz title from a file made by [toQuestionsFile]. */
    fun titleOf(file: String): String = file.lineSequence().firstOrNull()?.removePrefix("#")?.trim().orEmpty()
}
