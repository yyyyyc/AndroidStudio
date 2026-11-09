package com.example.voicemimic

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaRecorder
import android.net.Uri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.nio.ByteOrder
import kotlin.math.log10
import kotlin.math.sqrt

/** Growable float buffer. */
class FloatBuf(cap: Int = 16000 * 10) {
    var data = FloatArray(cap); private set
    var size = 0; private set
    fun add(v: Float) {
        if (size == data.size) data = data.copyOf(data.size * 2)
        data[size++] = v
    }
    fun toArray() = data.copyOf(size)
}

/** Records mono 16 kHz audio and reports level + live pitch every 50 ms. */
class Recorder {
    @Volatile private var running = false
    private var thread: Thread? = null
    private val lock = Any()
    private var buf = FloatBuf()

    /** The last [n] samples recorded so far (safe to call from any thread while recording). */
    fun tail(n: Int): FloatArray = synchronized(lock) {
        val a = maxOf(0, buf.size - n)
        buf.data.copyOfRange(a, buf.size)
    }

    val isRecording get() = running

    /**
     * Starts recording. [onChunk] gets (seconds so far, level 0..1, pitch Hz or 0) on the recording thread.
     * [onDone] gets the full recording when stopped or when [maxSec] is reached.
     */
    @SuppressLint("MissingPermission")
    fun start(maxSec: Float, onChunk: (Float, Float, Float) -> Unit, onDone: (FloatArray) -> Unit) {
        if (running) return
        running = true
        thread = Thread {
            val minBuf = AudioRecord.getMinBufferSize(Dsp.SR, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val rec = try {
                AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, Dsp.SR, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, Dsp.SR))
            } catch (e: Exception) { null }
            val out = FloatBuf()
            synchronized(lock) { buf = out }
            if (rec == null || rec.state != AudioRecord.STATE_INITIALIZED) {
                running = false; rec?.release(); onDone(FloatArray(0)); return@Thread
            }
            val chunk = ShortArray(800)
            val maxSamples = (maxSec * Dsp.SR).toInt()
            rec.startRecording()
            try {
                while (running && out.size < maxSamples) {
                    val n = rec.read(chunk, 0, chunk.size)
                    if (n <= 0) continue
                    var s = 0.0
                    synchronized(lock) { for (i in 0 until n) { val v = chunk[i] / 32768f; out.add(v); s += v * v } }
                    val db = 10 * log10(s / n + 1e-10)
                    val level = ((db + 60) / 60).toFloat().coerceIn(0f, 1f)
                    val pitch = if (out.size > Dsp.YIN_N && db > -50) Dsp.yin(out.data, out.size - Dsp.YIN_N) else 0f
                    onChunk(out.size / Dsp.SR.toFloat(), level, pitch)
                }
            } finally {
                rec.stop(); rec.release()
                running = false
            }
            onDone(out.toArray())
        }.apply { name = "recorder"; start() }
    }

    fun stop() { running = false }
}

/** Plays a float clip; reports playback position for a cursor. */
class Player(private val scope: CoroutineScope) {
    private var track: AudioTrack? = null
    private var job: Job? = null

    fun play(x: FloatArray, onPos: (Float?) -> Unit) {
        stop(); onPos(null)
        if (x.isEmpty()) return
        val t = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                .setSampleRate(Dsp.SR).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(x.size * 4)
            .build()
        t.write(x, 0, x.size, AudioTrack.WRITE_BLOCKING)
        t.play()
        track = t
        job = scope.launch {
            while (isActive) {
                val p = t.playbackHeadPosition
                if (p >= x.size || t.playState != AudioTrack.PLAYSTATE_PLAYING) break
                onPos(p / Dsp.SR.toFloat())
                delay(30)
            }
            onPos(null)
        }
    }

    fun stop() {
        job?.cancel(); job = null
        track?.let { runCatching { it.stop() }; it.release() }
        track = null
    }
}

/** Decodes any audio (or video's audio track) the device supports into mono 16 kHz floats. */
object Decoder {
    const val MAX_SEC = 180

    fun decode(context: Context, uri: Uri): FloatArray {
        val ex = MediaExtractor()
        val afd = runCatching { context.contentResolver.openAssetFileDescriptor(uri, "r") }.getOrNull()
        try {
            if (afd != null) {
                if (afd.declaredLength >= 0) ex.setDataSource(afd.fileDescriptor, afd.startOffset, afd.declaredLength)
                else ex.setDataSource(afd.fileDescriptor)
            } else ex.setDataSource(context, uri, null)
            val track = (0 until ex.trackCount).firstOrNull {
                ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: error("no audio track")
            ex.selectTrack(track)
            val fmt = ex.getTrackFormat(track)
            val mime = fmt.getString(MediaFormat.KEY_MIME)!!
            if (mime == "audio/raw" || mime == "audio/wav" || mime == "audio/x-wav") {
                // Let the codec path handle it if available; otherwise fall back to our WAV parser.
                runCatching { MediaCodec.createDecoderByType(mime).release() }.onFailure {
                    return context.contentResolver.openInputStream(uri)!!.use { Wav.parse(it.readBytes()) }
                }
            }
            var sr = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var ch = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var floatPcm = false
            val codec = MediaCodec.createDecoderByType(mime)
            codec.configure(fmt, null, null, 0)
            codec.start()
            val out = FloatBuf()
            val info = MediaCodec.BufferInfo()
            var inDone = false; var outDone = false
            try {
                while (!outDone) {
                    if (!inDone) {
                        val i = codec.dequeueInputBuffer(10_000)
                        if (i >= 0) {
                            val buf = codec.getInputBuffer(i)!!
                            val n = ex.readSampleData(buf, 0)
                            if (n < 0) {
                                codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inDone = true
                            } else {
                                codec.queueInputBuffer(i, 0, n, ex.sampleTime, 0); ex.advance()
                            }
                        }
                    }
                    val o = codec.dequeueOutputBuffer(info, 10_000)
                    when {
                        o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val f = codec.outputFormat
                            sr = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                            ch = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                            floatPcm = f.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                                f.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                        }
                        o >= 0 -> {
                            val b = codec.getOutputBuffer(o)!!.order(ByteOrder.LITTLE_ENDIAN)
                            b.position(info.offset); b.limit(info.offset + info.size)
                            if (floatPcm) {
                                val fb = b.asFloatBuffer()
                                while (fb.remaining() >= ch) { var s = 0f; repeat(ch) { s += fb.get() }; out.add(s / ch) }
                            } else {
                                val sb = b.asShortBuffer()
                                while (sb.remaining() >= ch) { var s = 0f; repeat(ch) { s += sb.get() / 32768f }; out.add(s / ch) }
                            }
                            codec.releaseOutputBuffer(o, false)
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outDone = true
                            if (out.size > sr * MAX_SEC) outDone = true
                        }
                    }
                }
            } finally {
                codec.stop(); codec.release()
            }
            return Dsp.resample(out.toArray(), sr)
        } finally {
            ex.release()
            runCatching { afd?.close() }
        }
    }
}

/** Peak-normalise quiet clips so both voices play back at a similar loudness. */
fun normalize(x: FloatArray): FloatArray {
    var peak = 0f
    for (v in x) peak = maxOf(peak, kotlin.math.abs(v))
    if (peak < 1e-4f) return x
    val g = (0.9f / peak).coerceAtMost(8f)
    return FloatArray(x.size) { x[it] * g }
}

fun rms(x: FloatArray): Float {
    var s = 0.0
    for (v in x) s += v * v
    return sqrt(s / x.size.coerceAtLeast(1)).toFloat()
}

/** Run blocking work off the main thread. */
suspend fun <T> background(block: () -> T): T = kotlinx.coroutines.withContext(Dispatchers.Default) { block() }
