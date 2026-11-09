package com.example.voicemimic

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** Pure signal-processing helpers. All audio is mono float in [-1, 1] at [SR] Hz. */
object Dsp {
    const val SR = 16000
    const val HOP = 160          // 10 ms per analysis frame
    const val WIN = 400          // 25 ms MFCC window
    const val NFFT = 512
    const val YIN_N = 1024       // 64 ms pitch window (reaches ~60 Hz)
    const val MIN_F0 = 65.0
    const val MAX_F0 = 900.0

    fun frameCount(n: Int) = if (n < WIN) 0 else (n - WIN) / HOP + 1
    fun frameToSec(i: Int) = i * HOP / SR.toFloat()

    // ---------------------------------------------------------------- FFT

    /** In-place radix-2 FFT; size must be a power of two. */
    fun fft(re: DoubleArray, im: DoubleArray) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) {
                var t = re[i]; re[i] = re[j]; re[j] = t
                t = im[i]; im[i] = im[j]; im[j] = t
            }
        }
        var len = 2
        while (len <= n) {
            val ang = -2 * PI / len
            val wr = cos(ang); val wi = sin(ang)
            var i = 0
            while (i < n) {
                var cr = 1.0; var ci = 0.0
                for (k in 0 until len / 2) {
                    val a = i + k; val b = a + len / 2
                    val xr = re[b] * cr - im[b] * ci
                    val xi = re[b] * ci + im[b] * cr
                    re[b] = re[a] - xr; im[b] = im[a] - xi
                    re[a] += xr; im[a] += xi
                    val ncr = cr * wr - ci * wi
                    ci = cr * wi + ci * wr; cr = ncr
                }
                i += len
            }
            len = len shl 1
        }
    }

    // ---------------------------------------------------------------- energy / VAD

    /** Frame loudness in dB (RMS over the MFCC window). */
    fun frameDb(x: FloatArray): FloatArray {
        val n = frameCount(x.size)
        return FloatArray(n) { f ->
            var s = 0.0
            val o = f * HOP
            for (k in 0 until WIN) { val v = x[o + k]; s += v * v }
            (10 * log10(s / WIN + 1e-10)).toFloat()
        }
    }

    /** Speech/non-speech per frame, adaptive to the recording's noise floor. */
    fun speechMask(db: FloatArray): BooleanArray {
        if (db.isEmpty()) return BooleanArray(0)
        val sorted = db.sortedArray()
        val floor = sorted[(sorted.size * 0.1).toInt().coerceAtMost(sorted.size - 1)]
        val peak = sorted[(sorted.size * 0.98).toInt().coerceAtMost(sorted.size - 1)]
        val thr = max(max(min(floor + 8f, peak - 6f), peak - 35f), -55f)
        val raw = BooleanArray(db.size) { db[it] > thr }
        // close tiny gaps (<150 ms) and drop tiny blips (<50 ms)
        val closed = raw.copyOf()
        var i = 0
        while (i < raw.size) {
            if (!raw[i]) {
                var j = i
                while (j < raw.size && !raw[j]) j++
                if (i > 0 && j < raw.size && j - i < 15) for (k in i until j) closed[k] = true
                i = j
            } else i++
        }
        i = 0
        while (i < closed.size) {
            if (closed[i]) {
                var j = i
                while (j < closed.size && closed[j]) j++
                if (j - i < 5) for (k in i until j) closed[k] = false
                i = j
            } else i++
        }
        return closed
    }

    /** Sample range [start, end) containing speech, with a small margin; null if silent. */
    fun speechBounds(x: FloatArray, marginSec: Float = 0.12f): IntRange? {
        val mask = speechMask(frameDb(x))
        val first = mask.indexOfFirst { it }
        if (first < 0) return null
        val last = mask.indexOfLast { it }
        val m = (marginSec * SR).toInt()
        val s = max(0, first * HOP - m)
        val e = min(x.size, last * HOP + WIN + m)
        return s until e
    }

    /** Concatenates only the speech runs of [x] (pauses removed). */
    fun speechOnly(x: FloatArray, mask: BooleanArray): FloatArray {
        val out = ArrayList<FloatArray>()
        var i = 0
        while (i < mask.size) {
            if (mask[i]) {
                var j = i
                while (j < mask.size && mask[j]) j++
                val a = i * HOP; val b = min(x.size, (j - 1) * HOP + WIN)
                if (b > a) out += x.copyOfRange(a, b)
                i = j
            } else i++
        }
        val r = FloatArray(out.sumOf { it.size })
        var o = 0
        for (c in out) { c.copyInto(r, o); o += c.size }
        return r
    }

    // ---------------------------------------------------------------- pitch (YIN)

    /** YIN pitch of one window starting at [off] (zero-padded past the end). 0 = unvoiced. */
    fun yin(x: FloatArray, off: Int, thresh: Double = 0.15): Float {
        val w = YIN_N / 2
        val tauMin = (SR / MAX_F0).toInt()
        val tauMax = min((SR / MIN_F0).toInt(), w - 1)
        fun s(i: Int): Double { val k = off + i; return if (k < x.size) x[k].toDouble() else 0.0 }
        var energy = 0.0
        for (j in 0 until w) { val v = s(j); energy += v * v }
        if (energy / w < 1e-6) return 0f                  // ~ -60 dBFS: silence
        val buf = DoubleArray(w + tauMax)
        for (i in buf.indices) buf[i] = s(i)
        val d = DoubleArray(tauMax + 1)
        for (tau in 1..tauMax) {
            var sum = 0.0
            for (j in 0 until w) { val t = buf[j] - buf[j + tau]; sum += t * t }
            d[tau] = sum
        }
        val cm = DoubleArray(tauMax + 1); cm[0] = 1.0
        var running = 0.0
        for (tau in 1..tauMax) {
            running += d[tau]
            cm[tau] = if (running == 0.0) 1.0 else d[tau] * tau / running
        }
        var best = -1
        var tau = tauMin
        while (tau <= tauMax) {
            if (cm[tau] < thresh) {
                while (tau + 1 <= tauMax && cm[tau + 1] < cm[tau]) tau++
                best = tau; break
            }
            tau++
        }
        if (best < 0) {
            // fall back to the global minimum if it is still fairly periodic
            var mi = tauMin
            for (t in tauMin..tauMax) if (cm[t] < cm[mi]) mi = t
            if (cm[mi] > 0.3) return 0f
            best = mi
        }
        var refined = best.toDouble()
        if (best in 2 until tauMax) {
            val a = cm[best - 1]; val b = cm[best]; val c = cm[best + 1]
            val den = a - 2 * b + c
            if (abs(den) > 1e-12) refined = best + 0.5 * (a - c) / den
        }
        return (SR / refined).toFloat()
    }

    /** Pitch per 10 ms frame (Hz, 0 = unvoiced), median-smoothed, octave jumps tamed. */
    fun pitchTrack(x: FloatArray, mask: BooleanArray? = null): FloatArray {
        val n = frameCount(x.size)
        val raw = FloatArray(n) { f -> if (mask != null && !mask[f]) 0f else yin(x, f * HOP) }
        val out = FloatArray(n)
        for (i in 0 until n) {
            if (raw[i] == 0f) continue
            val win = ArrayList<Float>(5)
            for (k in i - 2..i + 2) if (k in 0 until n && raw[k] > 0f) win.add(raw[k])
            win.sort()
            out[i] = win[win.size / 2]
        }
        // drop isolated voiced frames (< 40 ms runs) which are mostly noise
        var i = 0
        while (i < n) {
            if (out[i] > 0f) {
                var j = i
                while (j < n && out[j] > 0f) j++
                if (j - i < 4) for (k in i until j) out[k] = 0f
                i = j
            } else i++
        }
        return out
    }

    fun semitones(hz: Float, refHz: Float) = (12.0 * ln(hz.toDouble() / refHz) / ln(2.0)).toFloat()

    // ---------------------------------------------------------------- MFCC

    private val hamming = DoubleArray(WIN) { 0.54 - 0.46 * cos(2 * PI * it / (WIN - 1)) }
    private const val N_MEL = 26
    private const val N_CEP = 13

    private fun melOf(f: Double) = 2595 * log10(1 + f / 700)
    private fun hzOf(m: Double) = 700 * (Math.pow(10.0, m / 2595) - 1)

    private val melBanks = HashMap<Int, Array<DoubleArray>>()

    /** Mel filterbank; [warp] scales the filter frequencies (vocal-tract length normalisation). */
    private fun melBank(warp: Double): Array<DoubleArray> = synchronized(melBanks) {
        melBanks.getOrPut((warp * 1000).toInt()) {
            val lo = melOf(60.0); val hi = melOf(7600.0)
            val pts = DoubleArray(N_MEL + 2) {
                (hzOf(lo + (hi - lo) * it / (N_MEL + 1)) * warp).coerceAtMost(SR / 2.0 - 1) * NFFT / SR
            }
            Array(N_MEL) { m ->
                DoubleArray(NFFT / 2 + 1) { k ->
                    val l = pts[m]; val c = pts[m + 1]; val r = pts[m + 2]
                    when {
                        k < l || k > r -> 0.0
                        k <= c -> (k - l) / (c - l)
                        else -> (r - k) / (r - c)
                    }
                }
            }
        }
    }

    /** MFCC c1..c12 per frame, normalised per utterance (CMVN) so loudness/mic matter less. */
    fun mfcc(x: FloatArray, warp: Double = 1.0): Array<FloatArray> {
        val bank = melBank(warp)
        val n = frameCount(x.size)
        val re = DoubleArray(NFFT); val im = DoubleArray(NFFT)
        val logMel = DoubleArray(N_MEL)
        val out = Array(n) { FloatArray(N_CEP - 1) }
        for (f in 0 until n) {
            val o = f * HOP
            var prev = if (o > 0) x[o - 1].toDouble() else 0.0
            for (k in 0 until NFFT) {
                if (k < WIN) {
                    val v = x[o + k].toDouble()
                    re[k] = (v - 0.97 * prev) * hamming[k]; prev = v
                } else re[k] = 0.0
                im[k] = 0.0
            }
            fft(re, im)
            for (m in 0 until N_MEL) {
                var e = 0.0
                val w = bank[m]
                for (k in 0..NFFT / 2) if (w[k] != 0.0) e += w[k] * (re[k] * re[k] + im[k] * im[k])
                logMel[m] = ln(e + 1e-10)
            }
            for (c in 1 until N_CEP) {
                var s = 0.0
                for (m in 0 until N_MEL) s += logMel[m] * cos(PI * c * (m + 0.5) / N_MEL)
                out[f][c - 1] = s.toFloat()
            }
        }
        cmvn(out)
        return out
    }

    private fun cmvn(m: Array<FloatArray>) {
        if (m.isEmpty()) return
        val d = m[0].size
        for (c in 0 until d) {
            var mean = 0.0
            for (r in m) mean += r[c]
            mean /= m.size
            var v = 0.0
            for (r in m) { val t = r[c] - mean; v += t * t }
            val sd = sqrt(v / m.size).coerceAtLeast(1e-6)
            for (r in m) r[c] = ((r[c] - mean) / sd).toFloat()
        }
    }

    // ---------------------------------------------------------------- DTW

    class DtwResult(val path: List<IntArray>, val avgCost: Float)

    fun dist(a: FloatArray, b: FloatArray): Float {
        var s = 0f
        for (i in a.indices) { val t = a[i] - b[i]; s += t * t }
        return sqrt(s)
    }

    /** Classic DTW (steps right/down/diagonal) returning the alignment path [i(a), j(b)]. */
    fun dtw(a: Array<FloatArray>, b: Array<FloatArray>): DtwResult {
        val n = a.size; val m = b.size
        require(n > 0 && m > 0)
        val inf = Float.MAX_VALUE / 4
        val acc = FloatArray(n * m)
        for (i in 0 until n) for (j in 0 until m) {
            val c = dist(a[i], b[j])
            val best = when {
                i == 0 && j == 0 -> 0f
                i == 0 -> acc[j - 1]
                j == 0 -> acc[(i - 1) * m]
                else -> min(acc[(i - 1) * m + j - 1], min(acc[(i - 1) * m + j], acc[i * m + j - 1]))
            }
            acc[i * m + j] = if (best >= inf) inf else best + c
        }
        val path = ArrayList<IntArray>()
        var i = n - 1; var j = m - 1
        path.add(intArrayOf(i, j))
        while (i > 0 || j > 0) {
            if (i == 0) j-- else if (j == 0) i-- else {
                val d = acc[(i - 1) * m + j - 1]; val u = acc[(i - 1) * m + j]; val l = acc[i * m + j - 1]
                if (d <= u && d <= l) { i--; j-- } else if (u <= l) i-- else j--
            }
            path.add(intArrayOf(i, j))
        }
        path.reverse()
        return DtwResult(path, acc[n * m - 1] / path.size)
    }

    /** Mean distance between random frame pairs — a baseline for "no alignment at all". */
    fun meanPairDist(a: Array<FloatArray>, b: Array<FloatArray>, samples: Int = 4000): Float {
        val rnd = java.util.Random(7)
        var s = 0.0
        repeat(samples) { s += dist(a[rnd.nextInt(a.size)], b[rnd.nextInt(b.size)]) }
        return (s / samples).toFloat()
    }

    // ---------------------------------------------------------------- resampling

    /** Band-limited (windowed-sinc) resampling to [SR]. */
    fun resample(x: FloatArray, from: Int): FloatArray {
        if (from == SR) return x
        val ratio = from.toDouble() / SR
        val outLen = floor(x.size / ratio).toInt()
        val cutoff = min(1.0, 1.0 / ratio) * 0.95
        val half = 16
        val out = FloatArray(outLen)
        for (o in 0 until outLen) {
            val t = o * ratio
            val c = floor(t).toInt()
            var s = 0.0; var wsum = 0.0
            val span = (half / cutoff).toInt()
            for (k in c - span + 1..c + span) {
                if (k < 0 || k >= x.size) continue
                val d = (t - k) * cutoff
                val sinc = if (abs(d) < 1e-9) 1.0 else sin(PI * d) / (PI * d)
                val win = 0.5 + 0.5 * cos(PI * (t - k) / (span + 1))
                val w = sinc * win
                s += x[k] * w; wsum += w
            }
            out[o] = if (wsum != 0.0) (s / wsum).toFloat() else 0f
        }
        return out
    }

    fun clamp01(v: Double) = v.coerceIn(0.0, 1.0)
    fun gauss(x: Double, sigma: Double) = exp(-(x * x) / (2 * sigma * sigma))
}
