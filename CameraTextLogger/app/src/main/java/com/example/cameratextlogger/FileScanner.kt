package com.example.cameratextlogger

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.media.ExifInterface
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.util.Log
import kotlin.math.max
import kotlin.math.roundToInt

// Larger photos and video frames are shrunk to this many pixels on their long side: enough for
// small text, without running out of memory on 4K video or 50-megapixel photos.
private const val MAX_IMAGE_SIZE = 2560

// How far apart video frames are read when the scan rate is Continuous.
private const val CONTINUOUS_VIDEO_STEP_MS = 500L

/** A photo, or one frame of a video, as it is read. [positionMs] is null for a photo. */
class FileFrame(val image: Bitmap, val positionMs: Long?, val durationMs: Long?)

/**
 * Reads the text in a photo or video the user chose, the way camera frames are read. A video is
 * read one frame per scan interval of video time, as fast as the frames can be read.
 */
class FileScanner(
    private val context: Context,
    private val uri: Uri,
    session: Session,
    private val listener: Listener,
) {

    interface Listener {
        /** Called on the analysis thread for each photo or video frame read. */
        fun onFrame(frame: FileFrame, text: ReadText)

        /** Called on the analysis thread when the whole file is read, or it can't be. */
        fun onFinished(error: String?)
    }

    @Volatile
    private var cancelled = false
    private val reader = BitmapTextReader(context, analysisExecutor, session.language, session.readBy)
    private val stepMs = session.scanRate.intervalMs.takeIf { it > 0 } ?: CONTINUOUS_VIDEO_STEP_MS

    // Used on the analysis thread only.
    private var retriever: MediaMetadataRetriever? = null
    private var durationMs = 0L
    private var positionMs = 0L

    fun start() = analysisExecutor.execute {
        try {
            if (context.contentResolver.getType(uri)?.startsWith("video/") == true) {
                openVideo()
                readNextVideoFrame()
            } else {
                readPhoto()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Could not open $uri", e)
            finish("Could not open the file")
        }
    }

    /** Stops reading. No more calls reach the listener. */
    fun cancel() {
        cancelled = true
    }

    private fun readPhoto() {
        val image = decodePhoto()
        reader.read(image) { text ->
            if (!cancelled) listener.onFrame(FileFrame(image, null, null), text)
            finish(null)
        }
    }

    private fun openVideo() {
        val newRetriever = MediaMetadataRetriever()
        retriever = newRetriever
        newRetriever.setDataSource(context, uri)
        durationMs = newRetriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            ?.toLongOrNull() ?: 0
    }

    // Reads frames one after another, each queued once the last is read.
    private fun readNextVideoFrame() {
        if (cancelled || positionMs > durationMs) {
            finish(null)
            return
        }
        val at = positionMs
        positionMs += stepMs
        val frame = try {
            videoFrame(at)
        } catch (e: RuntimeException) {
            Log.w(TAG, "Could not get the video frame at $at ms", e)
            null
        }
        if (frame == null) {
            analysisExecutor.execute(::readNextVideoFrame)
            return
        }
        reader.read(frame) { text ->
            if (!cancelled) listener.onFrame(FileFrame(frame, at, durationMs), text)
            analysisExecutor.execute(::readNextVideoFrame)
        }
    }

    private fun finish(error: String?) {
        retriever?.release()
        retriever = null
        reader.close()
        if (!cancelled) listener.onFinished(error)
    }

    private fun videoFrame(atMs: Long): Bitmap? {
        val source = retriever ?: return null
        fun metadata(key: Int) = source.extractMetadata(key)?.toIntOrNull() ?: 0
        val width = metadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
        val height = metadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
        val rotation = metadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
        val scale = scaleFor(width, height)
        val atUs = atMs * 1000
        val frame = if (scale < 1f && Build.VERSION.SDK_INT >= 27) {
            // Fitted within a square, so it comes out right whether or not it's turned upright.
            source.getScaledFrameAtTime(
                atUs, MediaMetadataRetriever.OPTION_CLOSEST, MAX_IMAGE_SIZE, MAX_IMAGE_SIZE,
            )
        } else {
            source.getFrameAtTime(atUs, MediaMetadataRetriever.OPTION_CLOSEST)?.let(::shrunk)
        } ?: return null
        // Phone videos are stored sideways with a rotation to apply. Some Android versions apply
        // it to the frames and some don't: a frame still shaped like the stored video needs it.
        val stillSideways = rotation % 180 != 0 && width != height &&
            (frame.width > frame.height) == (width > height)
        return softwareCopy(if (stillSideways) rotated(frame, rotation) else frame)
    }

    private fun decodePhoto(): Bitmap {
        val resolver = context.contentResolver
        if (Build.VERSION.SDK_INT >= 28) {
            // ImageDecoder turns photos upright from their EXIF orientation.
            val source = ImageDecoder.createSource(resolver, uri)
            val image = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                val scale = scaleFor(info.size.width, info.size.height)
                if (scale < 1f) {
                    decoder.setTargetSize(
                        (info.size.width * scale).roundToInt(), (info.size.height * scale).roundToInt()
                    )
                }
                // The text readers need pixels they can access.
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
            return softwareCopy(image)
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri).use { BitmapFactory.decodeStream(it, null, bounds) }
        var sampleSize = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sampleSize * 2) >= MAX_IMAGE_SIZE) sampleSize *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        val image = resolver.openInputStream(uri).use { BitmapFactory.decodeStream(it, null, options) }
            ?: error("Not an image")
        val orientation = resolver.openInputStream(uri).use { stream ->
            ExifInterface(stream!!).getAttributeInt(
                ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL
            )
        }
        val rotation = when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90
            ExifInterface.ORIENTATION_ROTATE_180 -> 180
            ExifInterface.ORIENTATION_ROTATE_270 -> 270
            else -> 0
        }
        return softwareCopy(shrunk(if (rotation == 0) image else rotated(image, rotation)))
    }

    private fun scaleFor(width: Int, height: Int): Float {
        val longest = max(width, height)
        return if (longest > MAX_IMAGE_SIZE) MAX_IMAGE_SIZE.toFloat() / longest else 1f
    }

    private fun shrunk(image: Bitmap): Bitmap {
        val scale = scaleFor(image.width, image.height)
        if (scale >= 1f) return image
        return Bitmap.createScaledBitmap(
            image, (image.width * scale).roundToInt(), (image.height * scale).roundToInt(), true
        )
    }

    private fun rotated(image: Bitmap, degrees: Int): Bitmap {
        val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
        return Bitmap.createBitmap(image, 0, 0, image.width, image.height, matrix, true)
    }

    // Tesseract reads only ARGB_8888; videos and HDR photos can decode to other formats.
    private fun softwareCopy(image: Bitmap): Bitmap =
        if (image.config == Bitmap.Config.ARGB_8888) image else image.copy(Bitmap.Config.ARGB_8888, false)
}
