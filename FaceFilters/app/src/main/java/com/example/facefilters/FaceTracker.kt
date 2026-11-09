package com.example.facefilters

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.Build
import android.util.Log
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import java.io.Closeable
import kotlin.math.max

/**
 * A face found by [FaceTracker]: its mesh (see [Landmarks]), as x, y pairs in the pixels of the
 * picture it was found in, and how far the head is turned and tilted.
 */
class TrackedFace(val points: FloatArray, val yawDeg: Float, val pitchDeg: Float) {
    /** The same face, with its points moved by [matrix]: onto the screen, say. */
    fun mapped(matrix: Matrix?): TrackedFace {
        if (matrix == null) return this
        val moved = points.copyOf()
        matrix.mapPoints(moved)
        return TrackedFace(moved, yawDeg, pitchDeg)
    }

    /** The face's pose, where its points are. */
    fun pose(): FacePose? = Landmarks.pose(points, yawDeg, pitchDeg)

    /** The face's mesh (see [MeshLayout]), where its points are. */
    fun meshPoints(): FloatArray? = Landmarks.meshPoints(points)

    /** How big the face is: for picking the main face. */
    val size: Float get() = pose()?.eyeDistance ?: 0f
}

/**
 * Finds faces with MediaPipe's face landmarker: 478 points over each face, and its 3D pose. Works
 * on upright pictures. Uses the phone's graphics processor where it can, its processor otherwise.
 * Not thread-safe: use each from one thread at a time.
 *
 * @param video whether the pictures are frames of a video: faces are then followed from frame to
 *     frame, which is faster and steadier, and frames must come in time order.
 */
class FaceTracker private constructor(
    private val context: Context,
    private val video: Boolean,
    private val maxFaces: Int,
) : Closeable {
    private var landmarker: FaceLandmarker? = null
    private var onGpu = false
    private var lastTimestampMs = -1L

    /** The faces in [bitmap], which must be ARGB and upright. [timestampMs] orders video frames. */
    fun detect(bitmap: Bitmap, timestampMs: Long = 0L): List<TrackedFace> {
        val result = try {
            run(bitmap, timestampMs)
        } catch (e: Exception) {
            if (!onGpu) throw e
            // Some graphics drivers fail at it: the processor always works.
            Log.w(TAG, "Face tracking failed on the graphics processor; using the processor", e)
            close()
            start(gpu = false)
            run(bitmap, timestampMs)
        }
        val matrices = result.facialTransformationMatrixes().orElse(null)
        return result.faceLandmarks().mapIndexed { index, landmarks ->
            val points = FloatArray(landmarks.size * 2)
            for ((i, landmark) in landmarks.withIndex()) {
                points[2 * i] = landmark.x() * bitmap.width
                points[2 * i + 1] = landmark.y() * bitmap.height
            }
            val (yaw, pitch) = matrices?.getOrNull(index)?.let(Landmarks::headAngles) ?: (0f to 0f)
            TrackedFace(points, yaw, pitch)
        }
    }

    private fun run(bitmap: Bitmap, timestampMs: Long) = landmarker!!.let { mp ->
        val image = BitmapImageBuilder(bitmap).build()
        if (video) {
            // Frames must come strictly in order.
            val time = max(timestampMs, lastTimestampMs + 1)
            lastTimestampMs = time
            mp.detectForVideo(image, time)
        } else {
            mp.detect(image)
        }
    }

    private fun start(gpu: Boolean) {
        val options = FaceLandmarker.FaceLandmarkerOptions.builder()
            .setBaseOptions(
                BaseOptions.builder()
                    .setModelAssetPath(MODEL)
                    .setDelegate(if (gpu) Delegate.GPU else Delegate.CPU)
                    .build()
            )
            .setRunningMode(if (video) RunningMode.VIDEO else RunningMode.IMAGE)
            .setNumFaces(maxFaces)
            .setMinFaceDetectionConfidence(0.5f)
            .setMinFacePresenceConfidence(0.5f)
            .setMinTrackingConfidence(0.5f)
            .setOutputFacialTransformationMatrixes(true)
            .setOutputFaceBlendshapes(false)
            .build()
        landmarker = FaceLandmarker.createFromOptions(context, options)
        onGpu = gpu
        lastTimestampMs = -1L
    }

    override fun close() {
        landmarker?.close()
        landmarker = null
    }

    companion object {
        private const val TAG = "FaceFilters"
        private const val MODEL = "face_landmarker.task"

        /** Emulators' graphics can't run it reliably. */
        private val isEmulator: Boolean
            get() = Build.FINGERPRINT.contains("generic") || Build.HARDWARE.contains("ranchu") ||
                Build.HARDWARE.contains("goldfish") || Build.PRODUCT.contains("sdk")

        /**
         * A tracker for video frames ([video]) or single pictures, finding up to [maxFaces] faces.
         * Slow to make, a few tenths of a second: make it off the main thread, and keep it.
         */
        fun create(context: Context, video: Boolean, maxFaces: Int): FaceTracker {
            val tracker = FaceTracker(context.applicationContext, video, maxFaces)
            val gpu = video && !isEmulator
            try {
                tracker.start(gpu)
            } catch (e: Exception) {
                if (!gpu) throw e
                Log.w(TAG, "No graphics processor for face tracking; using the processor", e)
                tracker.start(gpu = false)
            }
            return tracker
        }

        /**
         * The faces in [photo], in its pixels, found with [tracker] (made for single pictures) in
         * a smaller copy, which is quicker and finds them just as well. Slow: off the main thread.
         */
        fun findInPhoto(tracker: FaceTracker, photo: Bitmap, detectionSize: Int = 1280): List<TrackedFace> {
            val longSide = max(photo.width, photo.height)
            val factor = if (longSide > detectionSize) detectionSize.toFloat() / longSide else 1f
            val small = if (factor < 1f) {
                Bitmap.createScaledBitmap(photo, (photo.width * factor).toInt(), (photo.height * factor).toInt(), true)
            } else if (photo.config != Bitmap.Config.ARGB_8888) {
                photo.copy(Bitmap.Config.ARGB_8888, false)
            } else {
                photo
            }
            val smallWidth = small.width
            val smallHeight = small.height
            val faces = try {
                tracker.detect(small)
            } finally {
                if (small !== photo) small.recycle()
            }
            if (factor >= 1f) return faces
            val back = Matrix().apply { setScale(photo.width / smallWidth.toFloat(), photo.height / smallHeight.toFloat()) }
            return faces.map { it.mapped(back) }
        }
    }
}
