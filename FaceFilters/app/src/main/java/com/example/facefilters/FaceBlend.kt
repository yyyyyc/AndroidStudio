package com.example.facefilters

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Shader
import android.os.Build
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Whether faces can be drawn through a mesh on the live picture: that needs Android 10. */
val canBlendLive get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

/** The colors of [bitmap] at [points] (x, y pairs, in its pixels): 3 numbers each, NaN outside it. */
fun sampleColors(bitmap: Bitmap, points: FloatArray): FloatArray {
    val n = points.size / 2
    val out = FloatArray(n * 3) { Float.NaN }
    for (i in 0 until n) {
        val x = points[2 * i].toInt()
        val y = points[2 * i + 1].toInt()
        if (x < 0 || y < 0 || x >= bitmap.width || y >= bitmap.height) continue
        val c = bitmap.getPixel(x, y)
        out[3 * i] = Color.red(c).toFloat()
        out[3 * i + 1] = Color.green(c).toFloat()
        out[3 * i + 2] = Color.blue(c).toFloat()
    }
    return out
}

/**
 * Another person's face, from a photo, ready to blend onto faces: its mesh, cut into triangles,
 * and its colors, blurred, place by place, to match against the light on the face it covers.
 */
class BlendFace(
    val id: String,
    var name: String,
    val picture: Bitmap,
    val detected: FloatArray,
    counts: IntArray,
) {
    val key get() = "face.$id"
    val layout = MeshLayout(counts)
    /** Where each mesh point is in [picture]. */
    val texture = layout.expand(detected)
    val triangles: ShortArray = triangulate(texture).map { it.toShort() }.toShortArray()
    val shader = BitmapShader(picture, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
    /** The face's own light and color at each mesh point, blurred to leave out detail. */
    val colors: FloatArray = SkinSource.of(picture, null, texture, layout).let { sampleColors(it.blurred, it.blurredPoints) }
    private val alphasAll = layout.alphas(keepOwnFeatures = false)
    private val alphasKeep = layout.alphas(keepOwnFeatures = true)

    fun alphas(keepOwnFeatures: Boolean) = if (keepOwnFeatures) alphasKeep else alphasAll

    /** The face, cut out of its photo, for showing in the list. */
    val thumbnail: Bitmap = run {
        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        for (i in layout.range(MeshLayout.FACE)) {
            minX = min(minX, detected[2 * i]); maxX = max(maxX, detected[2 * i])
            minY = min(minY, detected[2 * i + 1]); maxY = max(maxY, detected[2 * i + 1])
        }
        val left = minX.toInt().coerceIn(0, picture.width - 1)
        val top = minY.toInt().coerceIn(0, picture.height - 1)
        val width = (maxX - minX).toInt().coerceIn(1, picture.width - left)
        val height = (maxY - minY).toInt().coerceIn(1, picture.height - top)
        val scale = 128f / max(width, height)
        Bitmap.createBitmap(picture, left, top, width, height, Matrix().apply { setScale(scale, scale) }, true)
    }
}

/** Draws a [BlendFace] stretched over a face. Not thread-safe: use one per thread. */
class FaceBlender {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val colorMatrix = ColorMatrix()
    private var colors = IntArray(0)

    /**
     * Draws [face] with its mesh points at [points] (expanded, see [MeshLayout.expand]), its colors
     * scaled at each point by [gains] (3 per point, see [MeshLayout.lightGains]) to take on the
     * light of the face under it, [amount] opaque.
     */
    fun draw(canvas: Canvas, face: BlendFace, points: FloatArray, gains: FloatArray, amount: Float, keepOwnFeatures: Boolean) {
        // Older phones can only draw triangles in software.
        if (canvas.isHardwareAccelerated && !canBlendLive) return
        val n = face.layout.size
        if (points.size != n * 2 || gains.size != n * 3 || face.triangles.isEmpty()) return
        // Each point's color carries its share of the light; the brightest share is made up by
        // the color filter, as point colors can only darken.
        var most = 1f
        for (g in gains) most = max(most, g)
        if (colors.size != n) colors = IntArray(n)
        val alphas = face.alphas(keepOwnFeatures)
        for (i in 0 until n) {
            colors[i] = Color.argb(
                (alphas[i] * 255).roundToInt(),
                (gains[3 * i] / most * 255).roundToInt().coerceIn(0, 255),
                (gains[3 * i + 1] / most * 255).roundToInt().coerceIn(0, 255),
                (gains[3 * i + 2] / most * 255).roundToInt().coerceIn(0, 255),
            )
        }
        paint.shader = face.shader
        colorMatrix.setScale(most, most, most, 1f)
        paint.colorFilter = ColorMatrixColorFilter(colorMatrix)
        paint.alpha = (amount.coerceIn(0f, 1f) * 255).roundToInt()
        canvas.drawVertices(
            Canvas.VertexMode.TRIANGLES, points.size, points, 0, face.texture, 0,
            colors, 0, face.triangles, 0, face.triangles.size, paint,
        )
    }
}

/**
 * The user's face mesh on the live picture, steadied from frame to frame, and the light on it.
 * Main thread only.
 */
class LiveMesh {
    private var detected: FloatArray? = null
    var layout: MeshLayout? = null
        private set
    private var seenAt = 0L
    private var lastUpdateAt = 0L
    private var speed = 0f
    private var gains: FloatArray? = null

    fun update(points: FloatArray?, counts: IntArray?, nowMs: Long) {
        if (points == null || counts == null) {
            // Missed in a frame: kept a moment.
            if (nowMs - seenAt > HOLD_MS) clear()
            return
        }
        seenAt = nowMs
        val current = detected
        val currentLayout = layout
        if (current == null || currentLayout == null || !currentLayout.counts.contentEquals(counts) || current.size != points.size) {
            detected = points.copyOf()
            layout = MeshLayout(counts)
            gains = null
            lastUpdateAt = nowMs
            speed = 0f
            return
        }
        // Steady when still, without lagging when moving (as OneEuroFilter, for the whole mesh at
        // once): small shifts are mostly the detector's jitter.
        val dt = ((nowMs - lastUpdateAt) / 1000f).coerceIn(0.001f, 0.5f)
        lastUpdateAt = nowMs
        var moved = 0f
        var minX = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        for (i in 0 until points.size / 2) {
            moved += hypot(points[2 * i] - current[2 * i], points[2 * i + 1] - current[2 * i + 1])
            minX = minOf(minX, points[2 * i])
            maxX = maxOf(maxX, points[2 * i])
        }
        moved /= points.size / 2
        // In face widths a second.
        val faceWidth = (maxX - minX).coerceAtLeast(1f)
        speed += OneEuroFilter.alpha(1f, dt) * (moved / dt / faceWidth - speed)
        val follow = OneEuroFilter.alpha(MIN_CUTOFF + BETA * speed, dt)
        for (i in points.indices) current[i] += (points[i] - current[i]) * follow
    }

    /** The mesh, expanded (see [MeshLayout.expand]), in preview pixels. */
    fun points(): FloatArray? {
        val current = detected ?: return null
        return layout?.expand(current)
    }

    /** The mesh, expanded, if it fits [face]'s. */
    fun points(face: BlendFace): FloatArray? = if (layout?.matches(face.layout) == true) points() else null

    /** Moves the light toward matching [face] onto the user's face, colored [userColors] place by place. */
    fun matchLight(face: BlendFace, userColors: FloatArray) {
        val target = face.layout.lightGains(userColors, face.colors)
        val current = gains
        gains = if (current == null || current.size != target.size) target
        else FloatArray(target.size) { current[it] + (target[it] - current[it]) * 0.25f }
    }

    /** How to scale the blended face's colors at each point, or plain if not yet known. */
    fun gains(face: BlendFace): FloatArray = gains?.takeIf { it.size == face.layout.size * 3 } ?: FloatArray(face.layout.size * 3) { 1f }

    fun clear() {
        detected = null
        layout = null
        gains = null
    }

    private companion object {
        const val HOLD_MS = 250L
        const val MIN_CUTOFF = 1.5f
        const val BETA = 12f
    }
}
