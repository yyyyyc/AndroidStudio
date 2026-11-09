package com.example.facefilters

import android.annotation.SuppressLint
import android.graphics.Canvas
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaRecorder
import android.util.Log
import android.view.Surface
import java.io.File
import java.nio.ByteBuffer
import kotlin.concurrent.thread
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private const val TAG = "FaceFilters"

/**
 * Records a video of what is drawn with [drawFrame], and the microphone if [withAudio], to an
 * MP4 [file]. Frames are drawn on the main thread; encoding runs on threads of its own.
 */
class VideoRecorder private constructor(
    val file: File,
    val width: Int,
    val height: Int,
    private val withAudio: Boolean,
) {
    private val videoCodec: MediaCodec
    private val inputSurface: Surface
    private val muxer = MediaMuxer(file.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    private var audioCodec: MediaCodec? = null
    private var audioRecord: AudioRecord? = null

    private val lock = Object()
    private var videoTrack = -1
    private var audioTrack = -1
    private var muxerStarted = false
    // Samples encoded before the muxer could start: it needs both tracks first.
    private val pending = mutableListOf<Triple<Boolean, ByteArray, MediaCodec.BufferInfo>>()
    private var lastVideoUs = -1L
    private var lastAudioUs = -1L
    private var wroteVideo = false
    // Times in the video count from here, in the clock frames and sound are stamped with.
    private val startUs = System.nanoTime() / 1000

    @Volatile
    private var stopping = false
    private val threads = mutableListOf<Thread>()

    init {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, (width * height * 5f).roundToInt().coerceIn(2_000_000, 10_000_000))
            setInteger(MediaFormat.KEY_FRAME_RATE, 30)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        videoCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        try {
            videoCodec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            inputSurface = videoCodec.createInputSurface()
            videoCodec.start()
        } catch (e: Exception) {
            videoCodec.release()
            muxer.release()
            throw e
        }
        threads += thread(name = "VideoEncoder") { drain(videoCodec, video = true) }
        if (withAudio) startAudio()
    }

    @SuppressLint("MissingPermission") // Only made with the microphone allowed.
    private fun startAudio() {
        try {
            val sampleRate = 44100
            val minBuffer = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val record = AudioRecord(
                MediaRecorder.AudioSource.CAMCORDER, sampleRate, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, max(minBuffer, 8192) * 2,
            )
            if (record.state != AudioRecord.STATE_INITIALIZED) {
                record.release()
                return
            }
            val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, 1).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, 128_000)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
            }
            val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            record.startRecording()
            audioRecord = record
            audioCodec = codec
            threads += thread(name = "AudioIn") { feedAudio(record, codec) }
            threads += thread(name = "AudioEncoder") { drain(codec, video = false) }
        } catch (e: Exception) {
            // A video without sound beats no video.
            Log.w(TAG, "Recording without sound", e)
            audioRecord?.release()
            audioRecord = null
            audioCodec?.release()
            audioCodec = null
        }
    }

    private val hasAudio get() = audioCodec != null

    /** Draws the next frame with [draw]. Main thread. */
    fun drawFrame(draw: (Canvas) -> Unit) {
        synchronized(lock) {
            if (stopping) return
            val canvas = try {
                inputSurface.lockHardwareCanvas()
            } catch (e: Exception) {
                Log.w(TAG, "Could not draw a video frame", e)
                return
            }
            try {
                draw(canvas)
            } finally {
                inputSurface.unlockCanvasAndPost(canvas)
            }
        }
    }

    private fun feedAudio(record: AudioRecord, codec: MediaCodec) {
        while (true) {
            // Even when stopping: the end of the sound has to be sent in a buffer.
            val index = codec.dequeueInputBuffer(10_000)
            if (index < 0) continue
            val buffer = codec.getInputBuffer(index) ?: continue
            buffer.clear()
            val read = record.read(buffer, min(buffer.capacity(), 4096))
            val nowUs = System.nanoTime() / 1000
            // Stamped with when the sound started, not when it was read.
            val durationUs = max(read, 0) / 2 * 1_000_000L / 44100
            if (stopping) {
                codec.queueInputBuffer(index, 0, 0, nowUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                break
            }
            codec.queueInputBuffer(index, 0, max(read, 0), nowUs - durationUs, 0)
        }
    }

    private fun drain(codec: MediaCodec, video: Boolean) {
        val info = MediaCodec.BufferInfo()
        while (true) {
            val index = try {
                codec.dequeueOutputBuffer(info, 10_000)
            } catch (e: IllegalStateException) {
                break
            }
            when {
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> addTrack(codec.outputFormat, video)
                index >= 0 -> {
                    val buffer = codec.getOutputBuffer(index)
                    val config = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                    if (buffer != null && info.size > 0 && !config) write(video, buffer, info)
                    codec.releaseOutputBuffer(index, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
            }
        }
    }

    private fun addTrack(format: MediaFormat, video: Boolean) {
        synchronized(lock) {
            if (video) videoTrack = muxer.addTrack(format) else audioTrack = muxer.addTrack(format)
            if (videoTrack >= 0 && (audioTrack >= 0 || !hasAudio)) {
                muxer.start()
                muxerStarted = true
                for ((isVideo, data, sampleInfo) in pending) writeNow(isVideo, ByteBuffer.wrap(data), sampleInfo)
                pending.clear()
            }
        }
    }

    private fun write(video: Boolean, buffer: ByteBuffer, info: MediaCodec.BufferInfo) {
        synchronized(lock) {
            if (!muxerStarted) {
                val data = ByteArray(info.size)
                buffer.position(info.offset)
                buffer.get(data)
                val copy = MediaCodec.BufferInfo().apply { set(0, info.size, info.presentationTimeUs, info.flags) }
                pending += Triple(video, data, copy)
                return
            }
            writeNow(video, buffer, info)
        }
    }

    private fun writeNow(video: Boolean, buffer: ByteBuffer, info: MediaCodec.BufferInfo) {
        // From the start of the recording, and always moving forward, as MP4 needs.
        var timeUs = max(0L, info.presentationTimeUs - startUs)
        val last = if (video) lastVideoUs else lastAudioUs
        if (timeUs <= last) timeUs = last + 1
        if (video) lastVideoUs = timeUs else lastAudioUs = timeUs
        val out = MediaCodec.BufferInfo().apply { set(info.offset, info.size, timeUs, info.flags) }
        buffer.position(info.offset)
        buffer.limit(info.offset + info.size)
        muxer.writeSampleData(if (video) videoTrack else audioTrack, buffer, out)
        if (video) wroteVideo = true
    }

    /**
     * Finishes the video. True if it has something in it. Slow: call off the main thread.
     * Afterwards the file is complete, or deleted.
     */
    fun stop(): Boolean {
        synchronized(lock) {
            if (stopping) return false
            stopping = true
            try {
                videoCodec.signalEndOfInputStream()
            } catch (e: Exception) {
                Log.w(TAG, "Could not end the video", e)
            }
        }
        threads.forEach { it.join(3000) }
        val ok = synchronized(lock) {
            val good = muxerStarted && wroteVideo
            try {
                if (muxerStarted) muxer.stop()
            } catch (e: Exception) {
                Log.w(TAG, "Could not finish the video", e)
            }
            good
        }
        runCatching { muxer.release() }
        runCatching { videoCodec.stop() }
        videoCodec.release()
        inputSurface.release()
        audioRecord?.run {
            runCatching { stop() }
            release()
        }
        audioCodec?.run {
            runCatching { stop() }
            release()
        }
        if (!ok) file.delete()
        return ok
    }

    companion object {
        /**
         * Starts recording a video the shape of a [viewWidth] by [viewHeight] screen, about 720
         * pixels across. Null if the phone can't record that.
         */
        fun start(file: File, viewWidth: Int, viewHeight: Int, withAudio: Boolean): VideoRecorder? {
            if (viewWidth <= 0 || viewHeight <= 0) return null
            val portrait = viewHeight >= viewWidth
            val shortView = min(viewWidth, viewHeight).toFloat()
            val longView = max(viewWidth, viewHeight).toFloat()
            // Encoders like sizes in steps of 16, and most stop at 1920.
            var short = 720f
            var long = short * longView / shortView
            if (long > 1920f) {
                long = 1920f
                short = long * shortView / longView
            }
            val s = (short / 16).roundToInt() * 16
            val l = (long / 16).roundToInt() * 16
            return try {
                if (portrait) VideoRecorder(file, s, l, withAudio) else VideoRecorder(file, l, s, withAudio)
            } catch (e: Exception) {
                Log.e(TAG, "Could not start recording", e)
                file.delete()
                null
            }
        }
    }
}
