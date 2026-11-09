package com.example.voicemimic

import android.content.Context
import com.k2fsa.sherpa.onnx.GenerationConfig
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsPocketModelConfig
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Zero-shot voice cloning text-to-speech: Kyutai Pocket TTS (int8, English) via sherpa-onnx.
 * It only needs a few seconds of the voice — no transcript. The ~200 MB model is downloaded
 * once into filesDir instead of being bundled in the APK.
 */
class Cloner(context: Context) {
    private val dir = File(context.filesDir, "pocket-tts")
    private var tts: OfflineTts? = null

    val isDownloaded get() = FILES.all { (name, size) -> File(dir, name).length() == size }

    /** Downloads missing files; [onProgress] gets 0..1. Throws on failure. Resumes partial files. */
    fun download(onProgress: (Float) -> Unit, cancelled: () -> Boolean) {
        dir.mkdirs()
        val total = FILES.sumOf { it.second }.toFloat()
        var done = 0L
        for ((name, size) in FILES) {
            val f = File(dir, name)
            if (f.length() == size) { done += size; continue }
            val part = File(dir, "$name.part")
            var have = part.length().takeIf { it < size } ?: 0L
            if (have == 0L) part.delete()
            val c = openFollowingRedirects("$BASE/$name", have)
            if (c.responseCode == 200) { have = 0L; part.delete() }   // server ignored the range
            else if (c.responseCode != 206) throw IOException("HTTP ${c.responseCode} for $name")
            c.inputStream.use { inp ->
                java.io.FileOutputStream(part, have > 0).use { out ->
                    val buf = ByteArray(64 * 1024)
                    var got = have
                    var last = 0L
                    while (true) {
                        if (cancelled()) throw IOException("cancelled")
                        val n = inp.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n); got += n
                        if (got - last > 512 * 1024) { last = got; onProgress((done + got) / total) }
                    }
                }
            }
            c.disconnect()
            if (part.length() != size) throw IOException("incomplete $name")
            if (!part.renameTo(f)) throw IOException("rename $name")
            done += size
            onProgress(done / total)
        }
    }

    private fun openFollowingRedirects(url: String, from: Long): HttpURLConnection {
        var u = URL(url)
        repeat(6) {
            val c = u.openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false
            c.connectTimeout = 15000; c.readTimeout = 30000
            if (from > 0) c.setRequestProperty("Range", "bytes=$from-")
            val code = c.responseCode
            if (code in 300..399) {
                u = URL(u, c.getHeaderField("Location")); c.disconnect()
            } else return c
        }
        throw IOException("too many redirects")
    }

    private fun engine(): OfflineTts = tts ?: run {
        val p = { n: String -> File(dir, n).absolutePath }
        val cfg = OfflineTtsConfig(model = OfflineTtsModelConfig(
            pocket = OfflineTtsPocketModelConfig(
                lmFlow = p("lm_flow.int8.onnx"), lmMain = p("lm_main.int8.onnx"),
                encoder = p("encoder.onnx"), decoder = p("decoder.int8.onnx"),
                textConditioner = p("text_conditioner.onnx"),
                vocabJson = p("vocab.json"), tokenScoresJson = p("token_scores.json")),
            numThreads = 4))
        OfflineTts(null, cfg).also { tts = it }
    }

    /**
     * Says [text] in the voice of [reference] (16 kHz). Returns 16 kHz audio.
     * [onProgress] gets seconds of audio generated so far; return false from [keepGoing] to stop.
     */
    @Synchronized
    fun say(text: String, reference: FloatArray, speed: Float, onProgress: (Float) -> Unit, keepGoing: () -> Boolean): FloatArray {
        val e = engine()
        val ref = if (reference.size > MAX_REF_SEC * Dsp.SR) reference.copyOf(MAX_REF_SEC * Dsp.SR) else reference
        val g = GenerationConfig()
        g.referenceAudio = ref; g.referenceSampleRate = Dsp.SR
        g.speed = speed; g.numSteps = 5
        val sr = e.sampleRate().toFloat()
        // A real class (not a lambda): the JNI side looks up invoke([F)Ljava/lang/Integer; by name.
        val cb = object : (FloatArray) -> Int {
            var produced = 0
            override fun invoke(chunk: FloatArray): Int {
                produced += chunk.size
                onProgress(produced / sr)
                return if (keepGoing()) 1 else 0
            }
        }
        val out = e.generateWithConfigAndCallback(text, g, cb)
        return Dsp.resample(out.samples, out.sampleRate)
    }

    fun release() { tts?.release(); tts = null }

    companion object {
        const val MAX_REF_SEC = 12
        const val MAX_CHARS = 400
        private const val BASE = "https://huggingface.co/csukuangfj2/sherpa-onnx-pocket-tts-int8-2026-01-26/resolve/main"
        /** name to exact size, so a finished download can be verified without hashing. */
        val FILES = listOf(
            "vocab.json" to 69478L,
            "token_scores.json" to 123616L,
            "lm_flow.int8.onnx" to 9962530L,
            "text_conditioner.onnx" to 16388343L,
            "decoder.int8.onnx" to 22693618L,
            "encoder.onnx" to 72713165L,
            "lm_main.int8.onnx" to 76341079L,
        )
        val TOTAL_MB = FILES.sumOf { it.second } / 1_000_000

        /** Pocket TTS only knows English; reject Hebrew/other scripts up front. */
        fun isEnglish(text: String) = text.none { it.isLetter() && it.code > 0x24F }
    }
}
