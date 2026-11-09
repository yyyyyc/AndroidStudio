package com.example.voicemimic

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** LIVE is scored like FREE, but continuously on the last few seconds while recording. */
enum class Mode { PHRASE, FREE, LIVE, SAY }   // SAY: type text, hear it in the target voice (not scored)

/** Everything we know about one voice clip (already trimmed to speech). */
class VoiceProfile(
    val audio: FloatArray,
    val pitch: FloatArray,          // Hz per frame, 0 = unvoiced
    val mfcc: Array<FloatArray>,
    val speechFrames: Int,
    val embedding: FloatArray?,     // speaker embedding, null if it could not be computed
) {
    val voiced: List<Float> = pitch.filter { it > 0f }
    val medianPitch: Float = if (voiced.isEmpty()) 0f else voiced.sorted()[voiced.size / 2]

    /** Spread of the intonation in semitones (how "melodic" the speech is). */
    val pitchSpread: Float = if (voiced.size < 5) 0f else {
        val st = voiced.map { Dsp.semitones(it, medianPitch) }
        val m = st.average()
        sqrt(st.sumOf { (it - m) * (it - m) } / st.size).toFloat()
    }

    val speechSec get() = speechFrames * Dsp.HOP / Dsp.SR.toFloat()

    companion object {
        fun build(audio: FloatArray, embed: (FloatArray) -> FloatArray?): VoiceProfile {
            val db = Dsp.frameDb(audio)
            val mask = Dsp.speechMask(db)
            return VoiceProfile(
                audio = audio,
                pitch = Dsp.pitchTrack(audio, mask),
                mfcc = Dsp.mfcc(audio),
                speechFrames = mask.count { it },
                embedding = embed(audio),
            )
        }
    }
}

enum class Part { TIMBRE, PITCH, MELODY, RHYTHM, WORDS, EXPRESSION }

sealed interface Tip {
    data class PitchHigher(val semis: Float) : Tip
    data class PitchLower(val semis: Float) : Tip
    data object SlowDown : Tip
    data object SpeedUp : Tip
    data object MoreMelody : Tip
    data object LessMelody : Tip
    data object FollowIntonation : Tip
    data object WordsDiffer : Tip
    data object VoiceColor : Tip
    data object Great : Tip
}

class ScoreResult(
    val total: Int,
    val parts: List<Pair<Part, Int>>,
    val tips: List<Tip>,
    /** Attempt pitch mapped onto the target's time axis (only for phrase mode). */
    val alignedPitch: FloatArray?,
    val error: String? = null,
)

object Scorer {
    // Timbre calibration, measured on the bundled ERes2Net model:
    // same speaker 0.66..0.94, different speakers -0.21..0.42 (mean 0.06).
    const val COS_ZERO = 0.15
    const val COS_FULL = 0.75
    private val WARPS = doubleArrayOf(0.80, 0.86, 0.93, 1.07, 1.15, 1.24)

    fun cosine(a: FloatArray, b: FloatArray): Double {
        var d = 0.0; var na = 0.0; var nb = 0.0
        for (i in a.indices) { d += a[i] * b[i]; na += a[i] * a[i]; nb += b[i] * b[i] }
        return if (na == 0.0 || nb == 0.0) 0.0 else d / sqrt(na * nb)
    }

    fun timbreScore(cos: Double) = 100 * Dsp.clamp01((cos - COS_ZERO) / (COS_FULL - COS_ZERO))

    /** Average pitch: within half a semitone is perfect, 7+ semitones off scores zero. */
    fun pitchScore(semis: Double) = 100 * Dsp.clamp01((7.0 - abs(semis)) / 6.5)

    fun score(target: VoiceProfile, attempt: VoiceProfile, mode: Mode): ScoreResult {
        if (attempt.speechFrames < 30 || attempt.voiced.size < 15) {
            return ScoreResult(0, emptyList(), emptyList(), null, error = "no_speech")
        }
        val parts = ArrayList<Pair<Part, Double>>()
        val tips = ArrayList<Tip>()
        var aligned: FloatArray? = null

        // --- timbre
        val cos = if (target.embedding != null && attempt.embedding != null)
            cosine(target.embedding, attempt.embedding) else null
        val timbre = cos?.let { timbreScore(it) }
        if (timbre != null) parts += Part.TIMBRE to timbre

        // --- average pitch
        val semis = if (target.medianPitch > 0f) Dsp.semitones(attempt.medianPitch, target.medianPitch).toDouble() else 0.0
        val pitch = pitchScore(semis)
        parts += Part.PITCH to pitch
        if (semis < -1.0) tips += Tip.PitchHigher((-semis).toFloat())
        if (semis > 1.0) tips += Tip.PitchLower(semis.toFloat())

        if (mode == Mode.PHRASE) {
            val ta = trimToSpeech(target); val aa = trimToSpeech(attempt)
            // --- words / content: how much better than random the alignment is. The attempt's
            // spectrum is warped (VTLN) so a deeper/higher voice is not mistaken for other words.
            var dtw = Dsp.dtw(ta.mfcc, aa.mfcc)
            var ratio = dtw.avgCost / Dsp.meanPairDist(ta.mfcc, aa.mfcc)
            for (w in WARPS) {
                val wm = Dsp.mfcc(attempt.audio, w)
                val n = minOf(wm.size, aa.mfcc.size)
                val m = Array(n) { wm[it] }
                val d = Dsp.dtw(ta.mfcc, m)
                val r = d.avgCost / Dsp.meanPairDist(ta.mfcc, m)
                if (r < ratio) { ratio = r; dtw = d }
            }
            val words = 100 * Dsp.clamp01((0.80 - ratio) / 0.25)
            parts += Part.WORDS to words
            if (words < 40) tips += Tip.WordsDiffer

            // --- melody: intonation shape (relative to each speaker's own median) along the path
            val diffs = ArrayList<Double>()
            val al = FloatArray(ta.pitch.size)
            val cnt = IntArray(ta.pitch.size)
            for (p in dtw.path) {
                val ft = ta.pitch[p[0]]; val fa = aa.pitch[p[1]]
                if (fa > 0f) { al[p[0]] += fa; cnt[p[0]]++ }
                if (ft > 0f && fa > 0f) {
                    diffs += abs(Dsp.semitones(ft, target.medianPitch) - Dsp.semitones(fa, attempt.medianPitch)).toDouble()
                }
            }
            for (i in al.indices) if (cnt[i] > 0) al[i] /= cnt[i]
            aligned = al
            val mad = if (diffs.size < 10) 6.0 else diffs.average()
            val melody = 100 * Dsp.clamp01((3.5 - mad) / 2.5)
            parts += Part.MELODY to melody
            if (melody < 60) tips += Tip.FollowIntonation

            // --- rhythm: overall tempo + how evenly the timing follows the target
            val tempo = ln(attempt.speechSec.coerceAtLeast(0.1f) / target.speechSec.coerceAtLeast(0.1f).toDouble())
            val n = ta.mfcc.size.toDouble(); val m = aa.mfcc.size.toDouble()
            var dev = 0.0
            for (p in dtw.path) dev += abs(p[0] / n - p[1] / m)
            dev /= dtw.path.size
            val rhythm = 100 * (0.6 * Dsp.clamp01(1 - abs(tempo) / 0.5) + 0.4 * Dsp.clamp01(1 - dev / 0.12))
            parts += Part.RHYTHM to rhythm
            if (tempo > 0.15) tips += Tip.SpeedUp
            if (tempo < -0.15) tips += Tip.SlowDown
        } else {
            val d = (attempt.pitchSpread - target.pitchSpread).toDouble()
            val expr = 100 * Dsp.clamp01(1 - abs(d) / 3.0)
            parts += Part.EXPRESSION to expr
            if (d < -1.0) tips += Tip.MoreMelody
            if (d > 1.0) tips += Tip.LessMelody
        }

        if (timbre != null && timbre < 50) tips += Tip.VoiceColor

        val weights = if (mode == Mode.PHRASE)
            mapOf(Part.TIMBRE to .35, Part.PITCH to .20, Part.MELODY to .20, Part.RHYTHM to .15, Part.WORDS to .10)
        else mapOf(Part.TIMBRE to .55, Part.PITCH to .30, Part.EXPRESSION to .15)
        val wSum = parts.sumOf { weights[it.first] ?: 0.0 }
        var total = parts.sumOf { (weights[it.first] ?: 0.0) * it.second } / wSum
        // In phrase mode saying something else should not still score high.
        if (mode == Mode.PHRASE) {
            val words = parts.first { it.first == Part.WORDS }.second
            if (words < 30) total *= 0.6 + 0.4 * words / 30
        }
        val result = parts.map { it.first to it.second.roundToInt() }
        if (tips.isEmpty() && total >= 80) tips += Tip.Great
        return ScoreResult(total.roundToInt(), result, tips, aligned)
    }

    /** Seconds of recent audio that one live score looks at. */
    const val LIVE_WINDOW_SEC = 4

    /**
     * Scores the most recent [window] of a running recording. Returns null while the
     * speaker is pausing or there is not yet enough speech.
     */
    fun live(target: VoiceProfile, window: FloatArray, embed: (FloatArray) -> FloatArray?): ScoreResult? {
        if (window.size < Dsp.SR) return null
        val mask = Dsp.speechMask(Dsp.frameDb(window))
        val n = mask.size
        // need speech within the last second, otherwise the user has paused
        var recent = 0
        for (i in maxOf(0, n - 100) until n) if (mask[i]) recent++
        if (recent < 25) return null
        val speech = Dsp.speechOnly(window, mask)
        if (speech.size < Dsp.SR * 6 / 5) return null
        val r = score(target, VoiceProfile.build(speech, embed), Mode.LIVE)
        return if (r.error != null) null else r
    }

    /** Drop leading/trailing non-speech frames so DTW aligns speech with speech. */
    private class Trimmed(val mfcc: Array<FloatArray>, val pitch: FloatArray)

    private fun trimToSpeech(p: VoiceProfile): Trimmed {
        // profiles are built from audio already trimmed by Dsp.speechBounds, so keep as is
        val n = minOf(p.mfcc.size, p.pitch.size)
        return Trimmed(Array(n) { p.mfcc[it] }, p.pitch.copyOf(n))
    }
}
