package com.example.mathbowling

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.SoundPool
import android.os.Handler
import android.os.Looper
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.tanh

/**
 * All audio is synthesised at start-up, so the APK carries no sound files.
 * Every public method must be called on the main thread.
 */
class SoundEngine(context: Context) {
    private val appContext = context.applicationContext
    private val sr = 44100
    private val main = Handler(Looper.getMainLooper())

    private var pool: SoundPool? = null
    private var blastId = 0
    private var wrongId = 0
    private var tickId = 0
    private var goId = 0
    private var fanfareId = 0
    private var jumpId = 0
    private var landId = 0
    private var ducked = false
    private var music: AudioTrack? = null
    private var rumble: AudioTrack? = null
    private var released = false
    private var active = false
    private var rolling = false

    var musicOn = true
        set(v) { field = v; updateMusic() }

    fun load() {
        Thread {
            val dir = File(appContext.cacheDir, "sfx").apply { mkdirs() }
            val files = mapOf(
                "blast" to synthBlast(), "wrong" to synthWrong(), "tick" to synthTick(), "go" to synthGo(), "fanfare" to synthFanfare(),
                "jump" to synthJump(), "land" to synthLand()
            ).mapValues { (name, pcm) -> File(dir, "$name.wav").also { writeWav(it, pcm) } }
            val m = staticTrack(toPcm(music()), loop = true)
            val r = staticTrack(toPcm(rumbleLoop()), loop = true)
            main.post {
                if (released) { m.release(); r.release(); return@post }
                val p = SoundPool.Builder()
                    .setMaxStreams(6)
                    .setAudioAttributes(attrs(AudioAttributes.CONTENT_TYPE_SONIFICATION))
                    .build()
                blastId = p.load(files.getValue("blast").path, 1)
                wrongId = p.load(files.getValue("wrong").path, 1)
                tickId = p.load(files.getValue("tick").path, 1)
                goId = p.load(files.getValue("go").path, 1)
                fanfareId = p.load(files.getValue("fanfare").path, 1)
                jumpId = p.load(files.getValue("jump").path, 1)
                landId = p.load(files.getValue("land").path, 1)
                pool = p
                music = m.also { it.setVolume(musicVolume()) }
                rumble = r
                updateMusic()
                updateRumble()
            }
        }.start()
    }

    fun resume() { active = true; updateMusic(); updateRumble() }
    fun pause() { active = false; updateMusic(); updateRumble() }

    fun release() {
        released = true
        pool?.release(); pool = null
        music?.release(); music = null
        rumble?.release(); rumble = null
    }

    fun blast() = play(blastId, 1f)
    fun wrong() = play(wrongId, 0.9f)
    fun tick() = play(tickId, 0.5f)
    fun go() = play(goId, 0.6f)
    fun fanfare() = play(fanfareId, 0.8f)
    fun jump() = play(jumpId, 0.8f)
    fun land() = play(landId, 0.9f)

    /** Lower the music while a question is being read aloud. */
    fun duck(on: Boolean) {
        ducked = on
        music?.setVolume(musicVolume())
    }

    private fun musicVolume() = if (ducked) 0.12f else 0.45f

    fun setRolling(on: Boolean) { rolling = on; updateRumble() }

    private fun play(id: Int, vol: Float) {
        if (id != 0) pool?.play(id, vol, vol, 1, 0, 1f)
    }

    private fun updateMusic() {
        val t = music ?: return
        if (musicOn && active) {
            if (t.playState != AudioTrack.PLAYSTATE_PLAYING) t.play()
        } else if (t.playState == AudioTrack.PLAYSTATE_PLAYING) {
            t.pause()
        }
    }

    private fun updateRumble() {
        val t = rumble ?: return
        if (rolling && active) {
            t.setVolume(0.55f)
            if (t.playState != AudioTrack.PLAYSTATE_PLAYING) t.play()
        } else if (t.playState == AudioTrack.PLAYSTATE_PLAYING) {
            t.pause()
        }
    }

    // ------------------------------------------------------------------ helpers

    private fun attrs(content: Int) = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_GAME)
        .setContentType(content)
        .build()

    private fun staticTrack(data: ShortArray, loop: Boolean): AudioTrack {
        val t = AudioTrack.Builder()
            .setAudioAttributes(attrs(AudioAttributes.CONTENT_TYPE_MUSIC))
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sr)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(data.size * 2)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()
        t.write(data, 0, data.size)
        if (loop) t.setLoopPoints(0, data.size, -1)
        return t
    }

    private fun toPcm(f: FloatArray, gain: Float = 0.9f): ShortArray {
        var peak = 1e-6f
        for (v in f) peak = max(peak, abs(v))
        val k = gain / peak * 32767f
        return ShortArray(f.size) { (f[it] * k).toInt().coerceIn(-32768, 32767).toShort() }
    }

    private fun writeWav(file: File, pcm: ShortArray) {
        val bb = ByteBuffer.allocate(44 + pcm.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        bb.put("RIFF".toByteArray()); bb.putInt(36 + pcm.size * 2); bb.put("WAVE".toByteArray())
        bb.put("fmt ".toByteArray()); bb.putInt(16); bb.putShort(1); bb.putShort(1)
        bb.putInt(sr); bb.putInt(sr * 2); bb.putShort(2); bb.putShort(16)
        bb.put("data".toByteArray()); bb.putInt(pcm.size * 2)
        for (s in pcm) bb.putShort(s)
        file.writeBytes(bb.array())
    }

    private fun midi(m: Int) = 440.0 * 2.0.pow((m - 69) / 12.0)

    private val rnd = java.util.Random(1234)
    private fun noise() = rnd.nextFloat() * 2f - 1f

    /** wave: 0 sine, 1 square, 2 triangle, 3 pulse 25%, 4 saw */
    private fun tone(
        buf: FloatArray, start: Double, dur: Double, hz: Double, amp: Float, wave: Int,
        attack: Double = 0.005, decay: Double = 3.0, wrap: Boolean = true
    ) {
        val s0 = (start * sr).toInt()
        val n = (dur * sr).toInt()
        val rel = (0.02 * sr).toInt()
        var ph = 0.0
        for (i in 0 until n) {
            val t = i.toDouble() / sr
            ph += hz / sr
            val p = ph - floor(ph)
            val w = when (wave) {
                0 -> sin(2 * PI * p)
                1 -> if (p < 0.5) 1.0 else -1.0
                2 -> 4 * abs(p - 0.5) - 1
                3 -> if (p < 0.25) 1.0 else -1.0
                else -> 2 * p - 1
            }
            var env = min(1.0, t / attack) * exp(-t * decay)
            if (i > n - rel) env *= (n - i).toDouble() / rel
            var idx = s0 + i
            if (wrap) idx %= buf.size else if (idx >= buf.size) break
            buf[idx] += (w * env * amp).toFloat()
        }
    }

    // ------------------------------------------------------------------ music

    private fun music(): FloatArray {
        val beat = 60.0 / 128.0
        val bars = 8
        val buf = FloatArray((bars * 4 * beat * sr).toInt())
        val chords = arrayOf(
            intArrayOf(60, 64, 67), intArrayOf(57, 60, 64), intArrayOf(53, 57, 60), intArrayOf(55, 59, 62),
            intArrayOf(60, 64, 67), intArrayOf(57, 60, 64), intArrayOf(53, 57, 60), intArrayOf(55, 59, 62)
        )
        val melody = arrayOf(
            intArrayOf(72, 76, 79, 76, 72, 76, 79, 84),
            intArrayOf(81, 79, 76, 72, 76, -1, 72, 69),
            intArrayOf(69, 72, 77, 72, 81, 79, 77, 76),
            intArrayOf(74, 79, 83, 79, 74, -1, 79, -1),
            intArrayOf(79, -1, 76, 79, 84, 83, 81, 79),
            intArrayOf(76, -1, 72, 76, 81, -1, 76, 72),
            intArrayOf(77, 76, 74, 72, 74, 76, 77, 81),
            intArrayOf(79, -1, 74, 71, 74, 79, 83, 86)
        )
        for (bar in 0 until bars) {
            val barStart = bar * 4 * beat
            // lead
            for (i in 0 until 8) {
                val note = melody[bar][i]
                if (note < 0) continue
                tone(buf, barStart + i * 0.5 * beat, 0.5 * beat * 0.95, midi(note), 0.16f, 3, decay = 4.0)
                // soft echo
                tone(buf, barStart + i * 0.5 * beat + 0.75 * beat, 0.5 * beat * 0.9, midi(note), 0.05f, 2, decay = 4.0)
            }
            // bass: root / octave eighths
            val root = chords[bar][0] - 24
            for (i in 0 until 8) {
                val n = if (i % 2 == 0) root else root + 12
                tone(buf, barStart + i * 0.5 * beat, 0.5 * beat * 0.85, midi(n), 0.22f, 2, decay = 2.5)
            }
            // pad
            for (n in chords[bar]) {
                tone(buf, barStart, 4 * beat, midi(n), 0.035f, 2, attack = 0.3, decay = 0.3)
            }
            // drums
            for (b in 0 until 4) {
                val t = barStart + b * beat
                if (b == 0 || b == 2) kick(buf, t)
                if (b == 1 || b == 3) snare(buf, t)
                hat(buf, t); hat(buf, t + beat / 2)
            }
            if (bar % 2 == 1) kick(buf, barStart + 2.5 * beat)
            if (bar == bars - 1) { snare(buf, barStart + 3.5 * beat); snare(buf, barStart + 3.75 * beat) }
        }
        return buf
    }

    private fun kick(buf: FloatArray, start: Double) {
        val s0 = (start * sr).toInt(); val n = (0.3 * sr).toInt()
        var ph = 0.0
        for (i in 0 until n) {
            val t = i.toDouble() / sr
            ph += (50 + 110 * exp(-t * 22)) / sr
            buf[(s0 + i) % buf.size] += (sin(2 * PI * ph) * exp(-t * 9) * 0.55).toFloat()
        }
    }

    private fun snare(buf: FloatArray, start: Double) {
        val s0 = (start * sr).toInt(); val n = (0.2 * sr).toInt()
        for (i in 0 until n) {
            val t = i.toDouble() / sr
            val v = noise() * exp(-t * 20) * 0.22 + sin(2 * PI * 185 * t) * exp(-t * 25) * 0.15
            buf[(s0 + i) % buf.size] += v.toFloat()
        }
    }

    private fun hat(buf: FloatArray, start: Double) {
        val s0 = (start * sr).toInt(); val n = (0.05 * sr).toInt()
        var prev = 0f
        for (i in 0 until n) {
            val t = i.toDouble() / sr
            val w = noise()
            buf[(s0 + i) % buf.size] += ((w - prev) * exp(-t * 70) * 0.05).toFloat()
            prev = w
        }
    }

    // ------------------------------------------------------------------ effects

    private fun synthBlast(): ShortArray {
        val buf = FloatArray((1.9 * sr).toInt())
        var lp = 0.0
        var lp2 = 0.0
        var boomPh = 0.0
        var crackle = 0
        var crackleAmp = 0.0
        for (i in buf.indices) {
            val t = i.toDouble() / sr
            val w = noise().toDouble()
            // rumbling filtered noise, cutoff falling
            val a = 0.04 + 0.6 * exp(-t * 6)
            lp += a * (w - lp)
            lp2 += a * (lp - lp2)
            var v = lp2 * exp(-t * 2.8) * 1.6 * min(1.0, t / 0.004)
            // initial crack
            v += w * exp(-t * 55) * 0.9
            // low boom
            boomPh += (32 + 120 * exp(-t * 7)) / sr
            v += sin(2 * PI * boomPh) * exp(-t * 2.3) * 1.1
            // debris crackles
            if (crackle <= 0 && t in 0.08..1.2 && rnd.nextFloat() < 0.0025 * (1.3 - t)) {
                crackle = 300; crackleAmp = 0.25 + rnd.nextFloat() * 0.35
            }
            if (crackle > 0) { v += w * crackleAmp * (crackle / 300.0); crackle-- }
            buf[i] = tanh(v * 1.4).toFloat()
        }
        // victory chime on top
        val notes = intArrayOf(84, 88, 91, 96)
        notes.forEachIndexed { k, n ->
            tone(buf, 0.22 + k * 0.09, 0.9, midi(n), 0.22f, 0, decay = 4.5, wrap = false)
            tone(buf, 0.22 + k * 0.09, 0.9, midi(n) * 2, 0.06f, 0, decay = 6.0, wrap = false)
        }
        return toPcm(buf, 0.95f)
    }

    private fun synthWrong(): ShortArray {
        val buf = FloatArray((1.75 * sr).toInt())
        // thud of the ball hitting the cone
        for (i in 0 until (0.25 * sr).toInt()) {
            val t = i.toDouble() / sr
            buf[i] += (sin(2 * PI * 95 * t) * exp(-t * 22) * 0.8 + noise() * exp(-t * 90) * 0.4).toFloat()
        }
        // sad trombone: Bb3 A3 Ab3 G3
        val hz = doubleArrayOf(233.08, 220.0, 207.65, 196.0)
        val starts = doubleArrayOf(0.15, 0.45, 0.75, 1.05)
        for (k in 0 until 4) {
            val last = k == 3
            val dur = if (last) 0.68 else 0.29
            val s0 = (starts[k] * sr).toInt()
            val n = (dur * sr).toInt()
            var ph = 0.0
            var lpf = 0.0
            for (i in 0 until n) {
                val t = i.toDouble() / sr
                val vib = if (last) 1 + 0.025 * sin(2 * PI * 5.5 * t) * min(1.0, t / 0.15) else 1.0
                ph += hz[k] * vib / sr
                val saw = 2 * (ph - floor(ph)) - 1
                var env = min(1.0, t / 0.03)
                if (last) env *= exp(-max(0.0, t - 0.3) * 4)
                val tail = min(1.0, (n - i).toDouble() / (0.03 * sr))
                env *= tail
                val cutoff = 0.03 + 0.22 * env
                lpf += cutoff * (saw - lpf)
                val idx = s0 + i
                if (idx < buf.size) buf[idx] += (lpf * env * 0.9).toFloat()
            }
        }
        return toPcm(buf, 0.9f)
    }

    private fun synthTick(): ShortArray {
        val buf = FloatArray((0.09 * sr).toInt())
        tone(buf, 0.0, 0.09, 1000.0, 0.8f, 0, decay = 35.0, wrap = false)
        return toPcm(buf, 0.7f)
    }

    private fun synthGo(): ShortArray {
        val buf = FloatArray((0.35 * sr).toInt())
        tone(buf, 0.0, 0.35, 1320.0, 0.6f, 0, decay = 8.0, wrap = false)
        tone(buf, 0.0, 0.35, 1760.0, 0.4f, 0, decay = 9.0, wrap = false)
        return toPcm(buf, 0.75f)
    }

    private fun synthFanfare(): ShortArray {
        val buf = FloatArray((1.6 * sr).toInt())
        val seq = intArrayOf(67, 72, 76, 79, 84)
        seq.forEachIndexed { k, n ->
            val d = if (k == seq.size - 1) 0.9 else 0.16
            tone(buf, k * 0.14, d, midi(n), 0.3f, 3, decay = if (k == seq.size - 1) 2.5 else 6.0, wrap = false)
            tone(buf, k * 0.14, d, midi(n - 12), 0.15f, 2, decay = 3.0, wrap = false)
        }
        return toPcm(buf, 0.85f)
    }

    private fun synthJump(): ShortArray {
        val buf = FloatArray((0.3 * sr).toInt())
        var ph = 0.0
        for (i in buf.indices) {
            val t = i.toDouble() / sr
            val f = 260 + 650 * (t / 0.3) + 25 * sin(2 * PI * 30 * t)
            ph += f / sr
            val env = min(1.0, t / 0.01) * exp(-t * 7)
            buf[i] = (sin(2 * PI * ph) * env + sin(4 * PI * ph) * env * 0.25).toFloat()
        }
        return toPcm(buf, 0.7f)
    }

    private fun synthLand(): ShortArray {
        val buf = FloatArray((0.22 * sr).toInt())
        for (i in buf.indices) {
            val t = i.toDouble() / sr
            buf[i] = (sin(2 * PI * (70 + 60 * exp(-t * 30)) * t) * exp(-t * 18) + noise() * exp(-t * 70) * 0.35).toFloat()
        }
        return toPcm(buf, 0.85f)
    }

    /** Seamless 2-second loop of low rolling rumble. */
    private fun rumbleLoop(): FloatArray {
        val n = 2 * sr
        val fade = (0.2 * sr).toInt()
        val raw = FloatArray(n + fade)
        var brown = 0.0
        var lp = 0.0
        for (i in raw.indices) {
            val t = i.toDouble() / sr
            brown = (brown + noise() * 0.02) * 0.998
            lp += 0.08 * (brown - lp)
            val am = 0.8 + 0.2 * sin(2 * PI * 7 * t)
            raw[i] = (lp * am + sin(2 * PI * 55 * t) * 0.004).toFloat()
        }
        val out = FloatArray(n)
        for (i in 0 until n) {
            out[i] = if (i < fade) {
                val k = i.toFloat() / fade
                raw[i] * k + raw[n + i] * (1 - k)
            } else raw[i]
        }
        return out
    }
}
