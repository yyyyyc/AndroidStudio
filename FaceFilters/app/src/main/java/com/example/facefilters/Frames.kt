package com.example.facefilters

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * One analyzed camera frame: the faces in it, in preview pixels, and if asked for, the picture
 * itself with how to draw it over the preview, and the main face's mesh (see [MeshLayout]) with
 * how many points each of its contours has.
 *
 * @property timestampNs when the camera took it: the preview frame with the same time is the one
 *     to draw the filters on.
 * @property sensorToView from the camera sensor's pixels to the preview's.
 */
class FaceFrame(
    val faces: List<FacePose>,
    val bitmap: Bitmap?,
    val bitmapToView: Matrix,
    val mesh: FloatArray? = null,
    val meshCounts: IntArray? = null,
    val timestampNs: Long = 0L,
    val sensorToView: Matrix? = null,
)

/**
 * Reuses frame bitmaps, which are big, rather than making one per frame. Any thread.
 */
class FrameBuffers {
    private val free = ArrayDeque<Bitmap>()

    @Synchronized
    fun obtain(width: Int, height: Int): Bitmap {
        while (free.isNotEmpty()) {
            val bitmap = free.removeFirst()
            if (bitmap.width == width && bitmap.height == height) return bitmap
        }
        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    }

    @Synchronized
    fun release(bitmap: Bitmap) {
        if (free.size < 3) free.addLast(bitmap)
    }
}

/**
 * Finds faces in camera frames (see [FaceTracker]) and gives their positions in the preview's
 * pixels, mirrored like the preview for the front camera. While [wantPictures] is set it also
 * hands over each frame's picture, for drawing reshaped faces, and while [wantMesh] is set, the
 * main face's mesh, for blending faces and looks. Frames must come as RGBA. Makes its tracker
 * with [makeTracker] on the first frame, and calls [onFrame], on the analysis thread.
 */
class FaceAnalyzer(
    private val makeTracker: () -> FaceTracker,
    private val buffers: FrameBuffers,
    private val wantPictures: AtomicBoolean,
    private val wantMesh: AtomicBoolean,
    private val onFrame: (FaceFrame) -> Unit,
) : ImageAnalysis.Analyzer, Closeable {

    // From the camera sensor to the preview, given by the camera controller on the main thread.
    @Volatile
    private var sensorToView: Matrix? = null
    private var tracker: FaceTracker? = null
    private var failed = false
    // The small upright copy faces are looked for in.
    private var upright: Bitmap? = null
    private val shrinkPaint = Paint(Paint.FILTER_BITMAP_FLAG)

    override fun getTargetCoordinateSystem() = ImageAnalysis.COORDINATE_SYSTEM_VIEW_REFERENCED

    override fun updateTransform(matrix: Matrix?) {
        sensorToView = matrix?.let(::Matrix)
    }

    override fun analyze(image: ImageProxy) {
        image.use { analyzeFrame(it) }
    }

    /** On the analysis thread, after the last frame. */
    override fun close() {
        tracker?.close()
        tracker = null
    }

    private fun analyzeFrame(image: ImageProxy) {
        val toView = sensorToView ?: run {
            // The preview isn't placed yet. Its frame is let through all the same, without faces:
            // preview frames wait for theirs (see OverlayEffect).
            onFrame(FaceFrame(emptyList(), null, Matrix(), timestampNs = image.imageInfo.timestamp))
            return
        }
        val bitmap = image.toPooledBitmap()

        // From the frame's pixels to the preview's.
        val sensorToBuffer = Matrix(image.imageInfo.sensorToBufferTransformMatrix)
        val bufferToSensor = Matrix().also { sensorToBuffer.invert(it) }
        val bufferToView = Matrix().apply { setConcat(toView, bufferToSensor) }

        // Faces are found upright, in a smaller copy: the tracker works at a low size anyway.
        val rotation = image.imageInfo.rotationDegrees
        val scale = min(1f, DETECTION_SIZE / max(bitmap.width, bitmap.height).toFloat())
        val bufferToUpright = Matrix().apply {
            postScale(scale, scale)
            postRotate(rotation.toFloat())
            val bounds = RectF(0f, 0f, bitmap.width.toFloat(), bitmap.height.toFloat())
            mapRect(bounds)
            postTranslate(-bounds.left, -bounds.top)
        }
        val size = RectF(0f, 0f, bitmap.width.toFloat(), bitmap.height.toFloat()).also(bufferToUpright::mapRect)
        val w = size.width().roundToInt().coerceAtLeast(1)
        val h = size.height().roundToInt().coerceAtLeast(1)
        val small = upright?.takeIf { it.width == w && it.height == h }
            ?: Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { upright = it }
        Canvas(small).drawBitmap(bitmap, bufferToUpright, shrinkPaint)
        val uprightToView = Matrix().apply {
            val uprightToBuffer = Matrix().also { bufferToUpright.invert(it) }
            setConcat(bufferToView, uprightToBuffer)
        }

        val found = try {
            if (failed) emptyList() else (tracker ?: makeTracker().also { tracker = it })
                .detect(small, image.imageInfo.timestamp / 1_000_000)
        } catch (e: Throwable) {
            // Missing native code is an Error, not an Exception: still not worth crashing for.
            Log.e("FaceFilters", "Face tracking failed", e)
            // Not tried again every frame: the camera still shows.
            failed = tracker == null
            emptyList()
        }
        val faces = found.map { it.mapped(uprightToView) }
        val poses = faces.mapNotNull { it.pose() }
        val mesh = if (wantMesh.get()) faces.maxByOrNull { it.size }?.meshPoints() else null

        val keepPicture = wantPictures.get()
        if (!keepPicture) buffers.release(bitmap)
        onFrame(
            FaceFrame(
                poses, if (keepPicture) bitmap else null, bufferToView, mesh, mesh?.let { Landmarks.COUNTS },
                image.imageInfo.timestamp, Matrix(toView),
            )
        )
    }

    /** The frame's pixels, in a reused bitmap when the frame's rows are packed tightly. */
    private fun ImageProxy.toPooledBitmap(): Bitmap {
        val plane = planes[0]
        val buffer = plane.buffer.apply { rewind() }
        // Rows may be padded at the end: the padding falls outside the preview.
        val width = plane.rowStride / plane.pixelStride
        if (plane.pixelStride == 4 && buffer.remaining() >= plane.rowStride * height) {
            return buffers.obtain(width, height).apply { copyPixelsFromBuffer(buffer) }
        }
        // Unusual layouts: let CameraX convert it.
        val converted = toBitmap()
        return buffers.obtain(converted.width, converted.height).also {
            Canvas(it).drawBitmap(converted, 0f, 0f, null)
            converted.recycle()
        }
    }

    private companion object {
        /** The long side of the picture faces are looked for in. */
        const val DETECTION_SIZE = 640f
    }
}

/**
 * Draws pictures with faces reshaped (see [FaceWarp]), by bending a grid laid over the picture.
 * Not thread-safe: use one per thread.
 */
class MeshWarper(private val cellsOnLongSide: Int) {
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private var verts = FloatArray(0)

    /**
     * Draws [bitmap] on [canvas], placed by [bitmapToCanvas], with its points moved by [warp],
     * which gets them in canvas pixels as x, y pairs.
     */
    fun draw(canvas: Canvas, bitmap: Bitmap, bitmapToCanvas: Matrix?, warp: (FloatArray) -> Unit) {
        val landscape = bitmap.width >= bitmap.height
        val longSide = if (landscape) bitmap.width else bitmap.height
        val shortSide = if (landscape) bitmap.height else bitmap.width
        val cellsShort = (cellsOnLongSide * shortSide / longSide).coerceAtLeast(1)
        val cols = if (landscape) cellsOnLongSide else cellsShort
        val rows = if (landscape) cellsShort else cellsOnLongSide
        val count = (cols + 1) * (rows + 1) * 2
        if (verts.size != count) verts = FloatArray(count)
        var i = 0
        for (row in 0..rows) {
            val y = bitmap.height * row.toFloat() / rows
            for (col in 0..cols) {
                verts[i++] = bitmap.width * col.toFloat() / cols
                verts[i++] = y
            }
        }
        bitmapToCanvas?.mapPoints(verts)
        warp(verts)
        canvas.drawBitmapMesh(bitmap, cols, rows, verts, 0, null, 0, paint)
    }

    /** A copy of [photo] with its points moved by [warp], as in [draw]. */
    fun warped(photo: Bitmap, warp: (FloatArray) -> Unit): Bitmap {
        val out = Bitmap.createBitmap(photo.width, photo.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        // Under the edges, in case the grid pulls them in.
        canvas.drawBitmap(photo, 0f, 0f, null)
        draw(canvas, photo, null, warp)
        return out
    }
}
