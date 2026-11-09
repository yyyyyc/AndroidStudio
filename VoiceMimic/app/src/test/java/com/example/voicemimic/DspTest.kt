package com.example.voicemimic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

class DspTest {
    private fun tone(hz: Double, sec: Double, amp: Double = 0.4) =
        FloatArray((sec * Dsp.SR).toInt()) { (amp * sin(2 * PI * hz * it / Dsp.SR)).toFloat() }

    /** A crude voiced "vowel": harmonics of a gliding f0. */
    private fun vowel(f0a: Double, f0b: Double, sec: Double): FloatArray {
        val n = (sec * Dsp.SR).toInt()
        var ph = 0.0
        return FloatArray(n) { i ->
            val f0 = f0a + (f0b - f0a) * i / n
            ph += 2 * PI * f0 / Dsp.SR
            var s = 0.0
            for (h in 1..8) s += sin(h * ph) / h
            (0.25 * s).toFloat()
        }
    }

    @Test fun yinFindsToneFrequency() {
        for (hz in listOf(90.0, 150.0, 220.0, 440.0)) {
            val x = tone(hz, 0.5)
            val f = Dsp.yin(x, 1000)
            assertEquals("tone $hz", hz, f.toDouble(), hz * 0.01)
        }
    }

    @Test fun yinSilenceIsUnvoiced() {
        assertEquals(0f, Dsp.yin(FloatArray(4000), 0))
    }

    @Test fun resampleKeepsFrequency() {
        val sr = 44100
        val x = FloatArray(sr) { (0.4 * sin(2 * PI * 200 * it / sr)).toFloat() }
        val y = Dsp.resample(x, sr)
        assertTrue(abs(y.size - Dsp.SR) <= 2)
        assertEquals(200.0, Dsp.yin(y, 2000).toDouble(), 2.0)
    }

    @Test fun speechBoundsTrimsSilence() {
        val x = FloatArray(Dsp.SR) + vowel(150.0, 150.0, 1.0) + FloatArray(Dsp.SR)
        val r = Dsp.speechBounds(x)
        assertNotNull(r); r!!
        assertTrue(r.first in 13000..16000)
        assertTrue(r.last in 32000..35500)
    }

    @Test fun dtwIdenticalIsZeroAndDiagonal() {
        val m = Dsp.mfcc(vowel(120.0, 200.0, 1.0))
        val r = Dsp.dtw(m, m)
        assertEquals(0f, r.avgCost, 1e-4f)
        assertTrue(r.path.all { it[0] == it[1] })
    }

    @Test fun waveRoundTrip() {
        val x = tone(300.0, 0.2)
        val f = File.createTempFile("wavtest", ".wav")
        Wav.write(f, x)
        val y = Wav.read(f)
        f.delete()
        assertEquals(x.size, y.size)
        assertTrue(x.indices.all { abs(x[it] - y[it]) < 1e-3 })
    }

    @Test fun pitchOffSuggestsHigher() {
        val target = VoiceProfile.build(vowel(220.0, 260.0, 1.5)) { null }
        val low = VoiceProfile.build(vowel(110.0, 130.0, 1.5)) { null }
        val same = VoiceProfile.build(vowel(220.0, 260.0, 1.5)) { null }
        val good = Scorer.score(target, same, Mode.PHRASE)
        val bad = Scorer.score(target, low, Mode.PHRASE)
        assertTrue("good=${good.total} bad=${bad.total}", good.total > bad.total)
        assertTrue(bad.tips.any { it is Tip.PitchHigher })
        assertTrue(good.total >= 90)
    }

    @Test fun liveScoresOngoingSpeechOnly() {
        val target = VoiceProfile.build(vowel(200.0, 240.0, 2.0)) { null }
        val talking = FloatArray(Dsp.SR / 2) + vowel(200.0, 240.0, 3.0)
        val r = Scorer.live(target, talking) { null }
        assertNotNull(r)
        assertTrue("live score ${r!!.total}", r.total >= 80)
        assertEquals(null, Scorer.live(target, FloatArray(4 * Dsp.SR)) { null })
        // spoke, then paused for 1.5 s: wait instead of re-scoring the old speech
        val paused = vowel(200.0, 240.0, 2.5) + FloatArray(Dsp.SR * 3 / 2)
        assertEquals(null, Scorer.live(target, paused) { null })
    }

    @Test fun speechOnlyDropsPauses() {
        val x = vowel(150.0, 150.0, 1.0) + FloatArray(Dsp.SR) + vowel(150.0, 150.0, 1.0)
        val y = Dsp.speechOnly(x, Dsp.speechMask(Dsp.frameDb(x)))
        assertTrue("len ${y.size}", y.size in (Dsp.SR * 19 / 10)..(Dsp.SR * 22 / 10))
    }

    /**
     * Calibration on real speech: set CAL_DIR to a folder with tts-<voice>-<phrase>-r<rate>.wav files.
     * Prints content ("words") and melody scores for same vs different phrases.
     */
    @Test fun calibration() {
        val dir = System.getenv("CAL_DIR")?.let(::File)
        assumeTrue(dir != null && dir.isDirectory)
        val files = dir!!.listFiles { f -> f.name.startsWith("tts-") }!!.sortedBy { it.name }
        val profiles = files.associate { f ->
            val x = Wav.read(f)
            val b = Dsp.speechBounds(x)!!
            f.nameWithoutExtension to VoiceProfile.build(x.copyOfRange(b.first, b.last + 1)) { null }
        }
        val groups = sortedMapOf<String, MutableList<Map<Part, Int>>>()
        val keys = profiles.keys.toList()
        for (i in keys.indices) for (j in keys.indices) {
            if (i == j) continue
            val (_, va, pa, ra) = keys[i].split("-"); val (_, vb, pb, rb) = keys[j].split("-")
            if (va == vb && pa == pb && ra == rb) continue
            val r = Scorer.score(profiles[keys[i]]!!, profiles[keys[j]]!!, Mode.PHRASE)
            val g = (if (pa == pb) "samePhrase" else "diffPhrase") + "/" + (if (va == vb) "sameVoice" else "diffVoice")
            groups.getOrPut(g) { ArrayList() } += r.parts.toMap() + mapOf(Part.EXPRESSION to r.total)
        }
        for ((g, list) in groups) {
            val cols = listOf(Part.EXPRESSION to "total", Part.WORDS to "words", Part.MELODY to "melody",
                Part.RHYTHM to "rhythm", Part.PITCH to "pitch")
            println("CAL %-22s ".format(g) + cols.joinToString("  ") { (p, n) ->
                val v = list.mapNotNull { it[p] }
                "%s %3d/%3d/%3d".format(n, v.min(), v.average().toInt(), v.max())
            })
        }
    }
}
