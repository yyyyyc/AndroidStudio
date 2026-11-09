package com.example.voicemimic

import android.content.Context
import android.content.res.AssetManager
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractor
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class VoiceEntry(
    val id: String,
    val name: String,
    val created: Long,
    val durationSec: Float,
    val bestPhrase: Int = -1,
    val bestFree: Int = -1,
    val bestLive: Int = -1,
)

/** Saved target voices: filesDir/voices/<id>.wav + index.json. */
class VoiceStore(context: Context) {
    private val dir = File(context.filesDir, "voices").apply { mkdirs() }
    private val index = File(dir, "index.json")

    fun wav(id: String) = File(dir, "$id.wav")

    fun load(): List<VoiceEntry> = runCatching {
        val a = JSONArray(index.readText())
        (0 until a.length()).map { a.getJSONObject(it) }.map {
            VoiceEntry(it.getString("id"), it.getString("name"), it.getLong("created"),
                it.getDouble("dur").toFloat(), it.optInt("bestPhrase", -1), it.optInt("bestFree", -1), it.optInt("bestLive", -1))
        }.filter { wav(it.id).exists() }
    }.getOrDefault(emptyList())

    fun save(list: List<VoiceEntry>) {
        val a = JSONArray()
        for (v in list) a.put(JSONObject().put("id", v.id).put("name", v.name).put("created", v.created)
            .put("dur", v.durationSec.toDouble()).put("bestPhrase", v.bestPhrase).put("bestFree", v.bestFree).put("bestLive", v.bestLive))
        val tmp = File(dir, "index.json.tmp")
        tmp.writeText(a.toString())
        if (!tmp.renameTo(index)) { index.delete(); tmp.renameTo(index) }
    }

    fun add(name: String, audio: FloatArray, list: List<VoiceEntry>): List<VoiceEntry> {
        val id = System.currentTimeMillis().toString(36)
        Wav.write(wav(id), audio)
        val n = listOf(VoiceEntry(id, name, System.currentTimeMillis(), audio.size / Dsp.SR.toFloat())) + list
        save(n); return n
    }

    fun delete(id: String, list: List<VoiceEntry>): List<VoiceEntry> {
        wav(id).delete()
        val n = list.filter { it.id != id }
        save(n); return n
    }
}

/** Speaker embedding via sherpa-onnx (ERes2Net, trained on VoxCeleb). Not thread-safe: call from one worker. */
class Embedder(private val assets: AssetManager) {
    private val extractor by lazy {
        SpeakerEmbeddingExtractor(assets, SpeakerEmbeddingExtractorConfig("speaker.onnx", 2, false, "cpu"))
    }

    @Synchronized
    fun warmUp() { extractor.dim() }

    @Synchronized
    fun embed(x: FloatArray): FloatArray? {
        if (x.size < Dsp.SR / 2) return null
        val s = extractor.createStream()
        return try {
            s.acceptWaveform(x, Dsp.SR)
            s.inputFinished()
            if (extractor.isReady(s)) extractor.compute(s) else null
        } catch (e: Throwable) {
            null
        } finally {
            s.release()
        }
    }
}
