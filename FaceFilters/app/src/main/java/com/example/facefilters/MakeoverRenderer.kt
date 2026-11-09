package com.example.facefilters

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * The user's own face, to retouch: the picture it is in, and where each mesh point is in it
 * (see [MeshLayout.expand]), sharp and, for smoothing skin, blurred.
 */
class SkinSource(
    val sharp: Bitmap,
    val sharpPoints: FloatArray,
    val blurred: Bitmap,
    val blurredPoints: FloatArray,
) {
    companion object {
        /**
         * The face with mesh [points] (in canvas pixels) in [picture], which [toPicture] maps
         * canvas pixels into. The blur is about 1/40 of the face's width: enough to smooth skin,
         * not enough to lose its shape.
         */
        fun of(picture: Bitmap, toPicture: Matrix?, points: FloatArray, layout: MeshLayout): SkinSource {
            val sharpPoints = points.copyOf()
            toPicture?.mapPoints(sharpPoints)
            var minX = Float.MAX_VALUE
            var maxX = -Float.MAX_VALUE
            for (i in layout.range(MeshLayout.FACE)) {
                minX = min(minX, sharpPoints[2 * i])
                maxX = max(maxX, sharpPoints[2 * i])
            }
            val factor = ((maxX - minX) / 40f).coerceIn(2f, 24f)
            val blurred = Bitmap.createScaledBitmap(
                picture,
                (picture.width / factor).roundToInt().coerceAtLeast(1),
                (picture.height / factor).roundToInt().coerceAtLeast(1),
                true,
            )
            val sx = blurred.width / picture.width.toFloat()
            val sy = blurred.height / picture.height.toFloat()
            val blurredPoints = FloatArray(sharpPoints.size) { if (it % 2 == 0) sharpPoints[it] * sx else sharpPoints[it] * sy }
            return SkinSource(picture, sharpPoints, blurred, blurredPoints)
        }
    }
}

/**
 * Draws the retouching and painted detail of a [Makeover] on the main face: skin smoothed or
 * aged, wrinkles, makeup, stubble. Not thread-safe: use one per thread.
 */
class MakeoverRenderer {
    private val skinPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val path = Path()
    private val colorMatrix = ColorMatrix()
    private val shaderMatrix = Matrix()

    // The mesh's triangles, made once for each layout: the same every frame, so nothing shimmers.
    private var triangleLayout: MeshLayout? = null
    private var triangles = ShortArray(0)

    private val stubble: Bitmap by lazy { stubbleTile() }

    /**
     * Draws [makeover], at [strength], on the face whose mesh is at [points] (expanded, in canvas
     * pixels, already reshaped) and whose pose is [pose]. [warp] reshapes points the same way, for
     * details placed by the pose. [skin] is the face to retouch, if there is one.
     */
    fun draw(
        canvas: Canvas,
        makeover: Makeover,
        strength: Float,
        layout: MeshLayout,
        points: FloatArray,
        pose: FacePose,
        warp: (FloatArray) -> Unit,
        skin: SkinSource?,
    ) {
        // Anime, gold and sketch are drawn on the whole picture (see PictureStylizer), not retouched on.
        if (!makeover.retouches || strength <= 0f || points.size != layout.size * 2) return
        val k = strength.coerceAtMost(MakeoverWarp.MAX_STRENGTH)
        if (skin != null && (!canvas.isHardwareAccelerated || canBlendLive)) drawSkin(canvas, makeover, k, layout, points, skin)
        val face = Face(layout, points, pose, warp)
        when (makeover) {
            Makeover.None -> Unit
            Makeover.Older -> older(canvas, face, k)
            Makeover.Younger -> blush(canvas, face, (40 * k).roundToInt())
            Makeover.Feminine -> feminine(canvas, face, k)
            Makeover.Masculine -> masculine(canvas, face, k)
            Makeover.Anime, Makeover.Gold, Makeover.Sketch -> Unit
        }
    }

    // --- Skin

    private fun drawSkin(canvas: Canvas, makeover: Makeover, k: Float, layout: MeshLayout, points: FloatArray, skin: SkinSource) {
        if (triangleLayout?.matches(layout) != true) {
            triangles = triangulate(points).map { it.toShort() }.toShortArray()
            triangleLayout = layout
        }
        if (triangles.isEmpty()) return
        val smooth = makeover == Makeover.Younger || makeover == Makeover.Feminine
        val texture = if (smooth) skin.blurred else skin.sharp
        val texturePoints = if (smooth) skin.blurredPoints else skin.sharpPoints
        val strength = min(k, 1f)
        val alphas = layout.skinAlphas()
        val opacity = if (smooth) 0.85f * strength else 0.9f * strength
        val colors = IntArray(layout.size) { Color.argb((alphas[it] * opacity * 255).roundToInt(), 255, 255, 255) }

        fun lerp(to: Float) = 1f + (to - 1f) * strength
        colorMatrix.reset()
        when (makeover) {
            Makeover.Older -> {
                // Duller and a little yellow.
                colorMatrix.setSaturation(lerp(0.7f))
                colorMatrix.postConcat(ColorMatrix().apply { setScale(lerp(1.0f), lerp(0.96f), lerp(0.84f), 1f) })
            }
            Makeover.Younger -> {
                colorMatrix.setSaturation(lerp(1.08f))
                colorMatrix.postConcat(ColorMatrix().apply { setScale(lerp(1.09f), lerp(1.05f), lerp(1.04f), 1f) })
            }
            Makeover.Feminine -> colorMatrix.setScale(lerp(1.07f), lerp(1.03f), lerp(1.04f), 1f)
            Makeover.Masculine -> {
                // Ruddier and a little darker.
                colorMatrix.setSaturation(lerp(1.08f))
                colorMatrix.postConcat(ColorMatrix().apply { setScale(lerp(0.98f), lerp(0.9f), lerp(0.86f), 1f) })
            }
            Makeover.None, Makeover.Anime, Makeover.Gold, Makeover.Sketch -> return
        }
        skinPaint.shader = BitmapShader(texture, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        skinPaint.colorFilter = ColorMatrixColorFilter(colorMatrix)
        canvas.drawVertices(
            Canvas.VertexMode.TRIANGLES, points.size, points, 0, texturePoints, 0,
            colors, 0, triangles, 0, triangles.size, skinPaint,
        )
    }

    // --- Details

    /** The face to paint on: its mesh points, and its own terms (see [FacePose]) for placing. */
    private class Face(val layout: MeshLayout, val points: FloatArray, val pose: FacePose, val warp: (FloatArray) -> Unit) {
        val unit = pose.eyeDistance

        fun point(i: Int) = Pt(points[2 * i], points[2 * i + 1])

        /** The contour's points, in order across the face. */
        fun across(contour: Int, descending: Boolean = false): List<Pt> {
            val sorted = layout.range(contour).map(::point).sortedBy { pose.toLocal(it.x, it.y).x }
            return if (descending) sorted.reversed() else sorted
        }

        /** Where the face-terms points [uv] are on the screen, reshaped. */
        fun place(vararg uv: Float): FloatArray {
            val out = FloatArray(uv.size)
            for (i in 0 until uv.size / 2) {
                val p = pose.toScreen(uv[2 * i], uv[2 * i + 1])
                out[2 * i] = p.x
                out[2 * i + 1] = p.y
            }
            warp(out)
            return out
        }
    }

    private fun Path.through(points: List<Pt>, close: Boolean) {
        val first = points.firstOrNull() ?: return
        moveTo(first.x, first.y)
        for (p in points.drop(1)) lineTo(p.x, p.y)
        if (close) close()
    }

    /** A curve through three face-terms points, drawn as a crease: a dark line with light under it. */
    private fun crease(canvas: Canvas, face: Face, k: Float, u1: Float, v1: Float, u2: Float, v2: Float, u3: Float, v3: Float, weight: Float = 1f) {
        val a = (k * weight).coerceAtMost(1.2f)
        for (light in listOf(false, true)) {
            val dv = if (light) 0.016f else 0f
            val p = face.place(u1, v1 + dv, u2, v2 + dv, u3, v3 + dv)
            path.reset()
            path.moveTo(p[0], p[1])
            path.quadTo(p[2], p[3], p[4], p[5])
            stroke.color = if (light) Color.argb((40 * a).roundToInt(), 255, 236, 220) else Color.argb((85 * a).roundToInt(), 80, 50, 38)
            stroke.strokeWidth = face.unit * if (light) 0.012f else 0.02f
            canvas.drawPath(path, stroke)
        }
    }

    private fun older(canvas: Canvas, face: Face, k: Float) {
        val mouth = face.pose.mouthDrop
        val nose = face.pose.noseDrop
        // Forehead lines.
        for ((i, v) in listOf(-0.62f, -0.8f, -0.97f).withIndex()) {
            val w = 0.6f - i * 0.06f
            crease(canvas, face, k, -w, v + 0.02f, 0f, v - 0.04f, w, v + 0.02f, weight = 1f - i * 0.15f)
        }
        // Between the brows.
        crease(canvas, face, k, -0.08f, -0.42f, -0.06f, -0.3f, -0.07f, -0.2f, 0.7f)
        crease(canvas, face, k, 0.08f, -0.42f, 0.06f, -0.3f, 0.07f, -0.2f, 0.7f)
        for (s in listOf(-1f, 1f)) {
            // Crow's feet.
            crease(canvas, face, k, s * 0.8f, -0.06f, s * 0.92f, -0.12f, s * 1.02f, -0.15f, 0.8f)
            crease(canvas, face, k, s * 0.82f, 0.02f, s * 0.95f, 0.02f, s * 1.05f, 0.03f, 0.8f)
            crease(canvas, face, k, s * 0.8f, 0.1f, s * 0.92f, 0.16f, s * 1.0f, 0.22f, 0.8f)
            // Bags under the eyes, with their shadow.
            crease(canvas, face, k, s * 0.28f, 0.17f, s * 0.5f, 0.32f, s * 0.74f, 0.17f, 1.1f)
            crease(canvas, face, k, s * 0.32f, 0.27f, s * 0.52f, 0.42f, s * 0.72f, 0.28f, 0.6f)
            // From the nose to the mouth, and down from the mouth's corners.
            crease(canvas, face, k, s * 0.2f, nose - 0.1f, s * 0.42f, nose + 0.15f, s * 0.44f, mouth + 0.12f, 1.2f)
            crease(canvas, face, k, s * 0.34f, mouth + 0.06f, s * 0.37f, mouth + 0.25f, s * 0.42f, mouth + 0.45f, 0.9f)
        }
        // Age spots.
        fill.color = Color.argb((38 * min(k, 1.2f)).roundToInt(), 120, 80, 50)
        val spots = listOf(-0.55f to -0.75f, 0.42f to -0.9f, -0.75f to 0.55f, 0.7f to 0.5f, 0.2f to -1.05f, -0.3f to -0.6f)
        for ((i, uv) in spots.withIndex()) {
            val p = face.place(uv.first, uv.second)
            canvas.drawCircle(p[0], p[1], face.unit * (0.018f + 0.008f * (i % 3)), fill)
        }
        // Greying brows.
        fill.color = Color.argb((130 * min(k, 1.1f)).roundToInt(), 205, 205, 205)
        for ((top, bottom) in listOf(
            MeshLayout.LEFT_EYEBROW_TOP to MeshLayout.LEFT_EYEBROW_BOTTOM,
            MeshLayout.RIGHT_EYEBROW_TOP to MeshLayout.RIGHT_EYEBROW_BOTTOM,
        )) {
            path.reset()
            path.through(face.across(top) + face.across(bottom, descending = true), close = true)
            canvas.drawPath(path, fill)
        }
    }

    private fun lipsPath(face: Face): Path = Path().apply {
        fillType = Path.FillType.EVEN_ODD
        through(face.across(MeshLayout.UPPER_LIP_TOP) + face.across(MeshLayout.LOWER_LIP_BOTTOM, descending = true), close = true)
        through(face.across(MeshLayout.UPPER_LIP_BOTTOM) + face.across(MeshLayout.LOWER_LIP_TOP, descending = true), close = true)
    }

    private fun blush(canvas: Canvas, face: Face, alpha: Int) {
        for (cheek in listOf(MeshLayout.LEFT_CHEEK, MeshLayout.RIGHT_CHEEK)) {
            val p = face.point(face.layout.range(cheek).first)
            val r = face.unit * 0.38f
            fill.shader = RadialGradient(
                p.x, p.y, r, Color.argb(alpha.coerceIn(0, 255), 255, 90, 120), Color.argb(0, 255, 90, 120), Shader.TileMode.CLAMP,
            )
            canvas.drawCircle(p.x, p.y, r, fill)
        }
        fill.shader = null
    }

    private fun feminine(canvas: Canvas, face: Face, k: Float) {
        val a = min(k, 1.2f)
        blush(canvas, face, (75 * a).roundToInt())
        // Lipstick.
        fill.color = Color.argb((120 * a).roundToInt().coerceAtMost(180), 196, 30, 78)
        canvas.drawPath(lipsPath(face), fill)
        // Eyeliner with a little wing, and lashes.
        for (eye in listOf(MeshLayout.LEFT_EYE, MeshLayout.RIGHT_EYE)) {
            val pts = face.layout.range(eye).map(face::point)
            val local = pts.map { face.pose.toLocal(it.x, it.y) }
            val cu = local.map { it.x }.average().toFloat()
            val cv = local.map { it.y }.average().toFloat()
            val upper = local.filter { it.y <= cv }.sortedBy { it.x }
            if (upper.size < 2) continue
            val outward = if (cu < 0) -1f else 1f
            val outer = if (outward < 0) upper.first() else upper.last()
            val wing = Pt(outer.x + outward * 0.1f, outer.y - 0.06f)
            val line = (if (outward < 0) listOf(wing) + upper else upper + wing).map { face.pose.toScreen(it.x, it.y) }
            stroke.color = Color.argb((210 * min(a, 1f)).roundToInt(), 20, 14, 14)
            stroke.strokeWidth = face.unit * 0.03f
            path.reset()
            path.through(line, close = false)
            canvas.drawPath(path, stroke)
            stroke.strokeWidth = face.unit * 0.012f
            for (p in upper.drop(1).dropLast(1)) {
                val du = p.x - cu
                val dv = p.y - cv
                val length = hypot(du, dv).coerceAtLeast(1e-3f)
                val tip = face.pose.toScreen(p.x + du / length * 0.075f, p.y + dv / length * 0.075f - 0.02f)
                val base = face.pose.toScreen(p.x, p.y)
                canvas.drawLine(base.x, base.y, tip.x, tip.y, stroke)
            }
        }
    }

    private fun masculine(canvas: Canvas, face: Face, k: Float) {
        val a = min(k, 1.2f)
        // Stubble, from under the nose to the jaw, but not on the lips.
        val noseLine = face.pose.noseDrop + 0.1f
        val jaw = face.layout.range(MeshLayout.FACE).map(face::point)
            .map { it to face.pose.toLocal(it.x, it.y) }
            .filter { it.second.y > noseLine }
            .sortedBy { it.second.x }
        if (jaw.size >= 3) {
            val left = jaw.first().second
            val right = jaw.last().second
            val region = Path().apply {
                fillType = Path.FillType.EVEN_ODD
                through(
                    jaw.map { it.first } + listOf(face.pose.toScreen(right.x, noseLine), face.pose.toScreen(left.x, noseLine)),
                    close = true,
                )
                addPath(lipsPath(face))
            }
            fill.color = Color.argb((50 * a).roundToInt(), 35, 35, 42)
            canvas.drawPath(region, fill)
            shaderMatrix.reset()
            shaderMatrix.setScale(face.unit / 260f, face.unit / 260f)
            shaderMatrix.postRotate(face.pose.angleDeg)
            fill.shader = BitmapShader(stubble, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT).apply { setLocalMatrix(shaderMatrix) }
            fill.color = Color.argb((170 * min(a, 1f)).roundToInt(), 255, 255, 255)
            canvas.drawPath(region, fill)
            fill.shader = null
        }
        // Thicker, darker brows.
        val brow = Color.argb((95 * a).roundToInt().coerceAtMost(160), 45, 32, 24)
        fill.color = brow
        stroke.color = brow
        stroke.strokeWidth = face.unit * 0.04f
        for ((top, bottom) in listOf(
            MeshLayout.LEFT_EYEBROW_TOP to MeshLayout.LEFT_EYEBROW_BOTTOM,
            MeshLayout.RIGHT_EYEBROW_TOP to MeshLayout.RIGHT_EYEBROW_BOTTOM,
        )) {
            path.reset()
            path.through(face.across(top) + face.across(bottom, descending = true), close = true)
            canvas.drawPath(path, fill)
            canvas.drawPath(path, stroke)
        }
    }

    /** A tile of short dark hairs, for stubble. */
    private fun stubbleTile(): Bitmap {
        val size = 96
        val tile = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(tile)
        val random = Random(7)
        val hair = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
        }
        repeat(320) {
            val x = random.nextFloat() * size
            val y = random.nextFloat() * size
            val angle = Math.toRadians(70.0 + random.nextDouble() * 40)
            val length = 1.2f + random.nextFloat() * 2.2f
            hair.strokeWidth = 0.8f + random.nextFloat() * 0.7f
            hair.color = Color.argb(110 + random.nextInt(110), 25, 20, 18)
            canvas.drawLine(x, y, x + (length * kotlin.math.cos(angle)).toFloat(), y + (length * kotlin.math.sin(angle)).toFloat(), hair)
        }
        return tile
    }
}
