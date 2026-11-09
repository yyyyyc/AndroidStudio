package com.example.facefilters

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader

/** The filters the user can pick, in the order they are offered. */
enum class Filter(val label: String, val emoji: String) {
    None("None", "🚫"),
    Sunglasses("Shades", "🕶️"),
    Cat("Cat", "🐱"),
    PartyHat("Party", "🥳"),
    Crown("Crown", "👑"),
    Mustache("Mustache", "🥸"),
    Clown("Clown", "🤡"),
    HeartEyes("Hearts", "😍"),
    Alien("Alien", "👽"),
}

// How far in front of the eyes (positive) or behind them each part of a filter is, in units.
// Parts in front move with the nose when the head turns; parts behind, like hats, move the other way.
private const val GLASSES_DEPTH = 0.15f
private const val NOSE_DEPTH = 0.4f
private const val CHEEK_DEPTH = 0.05f
private const val HEAD_TOP_DEPTH = -0.7f
private const val EAR_DEPTH = -1.0f

/**
 * Draws filters on faces, on the live preview and on saved photos alike. Each filter is drawn in
 * a face's own terms (see [FacePose]): eyes at (-0.5, 0) and (0.5, 0), the top of the head near
 * y = -1.5, the chin near y = 2. Each part is drawn flat at its own depth and turned with the
 * head. Not thread-safe: use one renderer per thread.
 */
class FilterRenderer {

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val path = Path()
    private val rect = RectF()
    private val matrix = Matrix()
    private val picturePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private var yaw = 0f
    private var pitch = 0f

    fun draw(canvas: Canvas, poses: List<FacePose>, filter: Filter, adjustment: Adjustment = Adjustment()) =
        draw(canvas, poses, BuiltInSticker(filter), adjustment)

    fun draw(canvas: Canvas, poses: List<FacePose>, sticker: Sticker, adjustment: Adjustment = Adjustment()) {
        if (sticker.isNone) return
        for (pose in poses) {
            yaw = pose.yawDeg
            pitch = pose.pitchDeg
            canvas.save()
            canvas.translate(pose.cx, pose.cy)
            canvas.rotate(pose.angleDeg)
            canvas.scale(pose.eyeDistance, pose.eyeDistance)
            canvas.translate(adjustment.dx, adjustment.dy)
            val anchor = when (sticker) {
                is BuiltInSticker -> anchorY(sticker.filter, pose)
                is CustomStickerChoice -> sticker.info.top + sticker.info.height / 2
            }
            canvas.rotate(adjustment.rotationDeg, 0f, anchor)
            canvas.scale(adjustment.size, adjustment.size, 0f, anchor)
            when (sticker) {
                is BuiltInSticker -> builtIn(canvas, pose, sticker.filter)
                is CustomStickerChoice -> layer(canvas, GLASSES_DEPTH) {
                    val info = sticker.info
                    rect.set(info.left, info.top, info.left + info.width, info.top + info.height)
                    canvas.drawBitmap(sticker.picture, null, rect, picturePaint)
                }
            }
            canvas.restore()
            // Shaders are set in face units: don't let one leak into the next face.
            fill.shader = null
        }
    }

    private fun builtIn(canvas: Canvas, pose: FacePose, filter: Filter) {
        when (filter) {
            Filter.None -> Unit
            Filter.Sunglasses -> sunglasses(canvas)
            Filter.Cat -> cat(canvas, pose)
            Filter.PartyHat -> partyHat(canvas)
            Filter.Crown -> crown(canvas)
            Filter.Mustache -> mustache(canvas, pose)
            Filter.Clown -> clown(canvas, pose)
            Filter.HeartEyes -> heartEyes(canvas)
            Filter.Alien -> alien(canvas, pose)
        }
    }

    /** The height a filter grows and shrinks around when resized: its middle, roughly. */
    private fun anchorY(filter: Filter, pose: FacePose) = when (filter) {
        Filter.PartyHat, Filter.Crown -> -1.4f
        Filter.Cat -> -0.6f
        Filter.Mustache -> (pose.noseDrop + pose.mouthDrop) / 2
        Filter.Clown -> pose.noseDrop
        Filter.Alien -> 0.4f
        else -> 0f
    }

    /** Draws [block] flat at depth [z], turned with the head. */
    private inline fun layer(canvas: Canvas, z: Float, block: () -> Unit) {
        canvas.save()
        if (yaw != 0f || pitch != 0f) {
            matrix.setPolyToPoly(FaceProjection.SQUARE, 0, FaceProjection.corners(z, yaw, pitch), 0, 4)
            canvas.concat(matrix)
        }
        block()
        canvas.restore()
    }

    private fun sunglasses(canvas: Canvas) {
        layer(canvas, GLASSES_DEPTH) {
            for (side in listOf(-1f, 1f)) {
                rect.set(side * 0.5f - 0.38f, -0.24f, side * 0.5f + 0.38f, 0.3f)
                fill.shader = LinearGradient(
                    0f, -0.24f, 0f, 0.3f,
                    Color.argb(240, 40, 40, 60), Color.argb(240, 5, 5, 10), Shader.TileMode.CLAMP,
                )
                canvas.drawRoundRect(rect, 0.2f, 0.2f, fill)
                fill.shader = null
                // A glint across the lens.
                fill.color = Color.argb(70, 255, 255, 255)
                path.reset()
                path.moveTo(side * 0.5f - 0.25f, -0.2f)
                path.lineTo(side * 0.5f - 0.1f, -0.2f)
                path.lineTo(side * 0.5f - 0.3f, 0.2f)
                path.lineTo(side * 0.5f - 0.36f, 0.08f)
                path.close()
                canvas.drawPath(path, fill)
                stroke.color = Color.BLACK
                stroke.strokeWidth = 0.06f
                canvas.drawRoundRect(rect, 0.2f, 0.2f, stroke)
            }
            // The bridge.
            path.reset()
            path.moveTo(-0.12f, -0.1f)
            path.quadTo(0f, -0.2f, 0.12f, -0.1f)
            canvas.drawPath(path, stroke)
        }
        // The arms run back to the ears: drawn in 3D, and hidden behind the head when it turns
        // away from them.
        stroke.color = Color.BLACK
        stroke.strokeWidth = 0.06f
        for (side in listOf(-1f, 1f)) {
            if (side * yaw > 10f) continue
            val hinge = FaceProjection.project(side * 0.88f, -0.12f, GLASSES_DEPTH, yaw, pitch)
            val ear = FaceProjection.project(side * 1.12f, -0.05f, EAR_DEPTH, yaw, pitch)
            canvas.drawLine(hinge.x, hinge.y, ear.x, ear.y, stroke)
        }
    }

    private fun cat(canvas: Canvas, pose: FacePose) {
        layer(canvas, HEAD_TOP_DEPTH) {
            for (side in listOf(-1f, 1f)) {
                fill.color = Color.rgb(90, 70, 60)
                triangle(side * 1.1f, -1.25f, side * 0.3f, -1.6f, side * 1.0f, -2.45f)
                canvas.drawPath(path, fill)
                fill.color = Color.rgb(255, 170, 190)
                triangle(side * 0.95f, -1.4f, side * 0.5f, -1.6f, side * 0.95f, -2.15f)
                canvas.drawPath(path, fill)
            }
        }
        val noseY = pose.noseDrop
        layer(canvas, NOSE_DEPTH) {
            // The nose: a rounded triangle pointing down.
            fill.color = Color.rgb(255, 120, 160)
            path.reset()
            path.moveTo(-0.17f, noseY - 0.12f)
            path.quadTo(0f, noseY - 0.18f, 0.17f, noseY - 0.12f)
            path.quadTo(0.05f, noseY + 0.06f, 0f, noseY + 0.07f)
            path.quadTo(-0.05f, noseY + 0.06f, -0.17f, noseY - 0.12f)
            canvas.drawPath(path, fill)
        }
        layer(canvas, CHEEK_DEPTH) {
            stroke.color = Color.argb(230, 40, 30, 30)
            stroke.strokeWidth = 0.025f
            for (side in listOf(-1f, 1f)) {
                for (i in 0..2) {
                    val startY = noseY + 0.05f + i * 0.07f
                    canvas.drawLine(side * 0.3f, startY, side * 1.15f, startY - 0.15f + i * 0.17f, stroke)
                }
            }
        }
    }

    private fun partyHat(canvas: Canvas) = layer(canvas, HEAD_TOP_DEPTH) {
        val baseY = -1.4f
        triangle(-0.75f, baseY, 0.75f, baseY, 0.15f, -3.1f)
        val hat = Path(path)
        fill.color = Color.rgb(156, 39, 176)
        canvas.drawPath(hat, fill)
        // Stripes, kept inside the hat.
        canvas.save()
        canvas.clipPath(hat)
        fill.color = Color.rgb(255, 213, 79)
        var y = baseY - 0.15f
        while (y > -3.2f) {
            path.reset()
            path.moveTo(-1f, y)
            path.lineTo(1f, y - 0.25f)
            path.lineTo(1f, y - 0.4f)
            path.lineTo(-1f, y - 0.15f)
            path.close()
            canvas.drawPath(path, fill)
            y -= 0.45f
        }
        canvas.restore()
        // The brim and the pompom.
        fill.color = Color.WHITE
        rect.set(-0.85f, baseY - 0.1f, 0.85f, baseY + 0.12f)
        canvas.drawRoundRect(rect, 0.11f, 0.11f, fill)
        fill.color = Color.rgb(255, 82, 82)
        canvas.drawCircle(0.15f, -3.1f, 0.2f, fill)
    }

    private fun crown(canvas: Canvas) = layer(canvas, HEAD_TOP_DEPTH) {
        val baseY = -1.25f
        val topY = -2.15f
        path.reset()
        path.moveTo(-0.95f, baseY)
        path.lineTo(-1.0f, topY)
        path.lineTo(-0.55f, baseY - 0.45f)
        path.lineTo(-0.3f, topY - 0.1f)
        path.lineTo(0f, baseY - 0.5f)
        path.lineTo(0.3f, topY - 0.1f)
        path.lineTo(0.55f, baseY - 0.45f)
        path.lineTo(1.0f, topY)
        path.lineTo(0.95f, baseY)
        path.close()
        fill.shader = LinearGradient(
            0f, topY, 0f, baseY,
            Color.rgb(255, 236, 140), Color.rgb(212, 160, 23), Shader.TileMode.CLAMP,
        )
        canvas.drawPath(path, fill)
        fill.shader = null
        stroke.color = Color.rgb(150, 105, 10)
        stroke.strokeWidth = 0.035f
        canvas.drawPath(path, stroke)
        // Gems along the band, and one on each point.
        val gems = listOf(Color.rgb(229, 57, 53), Color.rgb(30, 136, 229), Color.rgb(67, 160, 71))
        for ((i, x) in listOf(-0.55f, 0f, 0.55f).withIndex()) {
            fill.color = gems[i]
            canvas.drawCircle(x, baseY - 0.18f, 0.1f, fill)
        }
        fill.color = Color.WHITE
        for ((x, y) in listOf(-1.0f to topY, -0.3f to topY - 0.1f, 0.3f to topY - 0.1f, 1.0f to topY)) {
            canvas.drawCircle(x, y, 0.06f, fill)
        }
    }

    private fun mustache(canvas: Canvas, pose: FacePose) = layer(canvas, NOSE_DEPTH) {
        // Between the nose and the mouth, nearer the mouth.
        val y = pose.noseDrop + (pose.mouthDrop - pose.noseDrop) * 0.45f
        fill.color = Color.rgb(60, 35, 20)
        path.reset()
        for (side in listOf(1f, -1f)) {
            path.moveTo(0f, y - 0.1f)
            path.cubicTo(side * 0.25f, y - 0.22f, side * 0.5f, y - 0.12f, side * 0.62f, y + 0.02f)
            // The curl at the end.
            path.cubicTo(side * 0.72f, y + 0.12f, side * 0.86f, y, side * 0.8f, y - 0.1f)
            path.cubicTo(side * 0.85f, y + 0.12f, side * 0.65f, y + 0.22f, side * 0.5f, y + 0.12f)
            path.cubicTo(side * 0.35f, y + 0.04f, side * 0.15f, y + 0.08f, 0f, y + 0.04f)
            path.close()
        }
        canvas.drawPath(path, fill)
    }

    private fun clown(canvas: Canvas, pose: FacePose) {
        val noseY = pose.noseDrop - 0.05f
        layer(canvas, CHEEK_DEPTH) {
            // Rosy cheeks, fading at the edges.
            for (side in listOf(-1f, 1f)) {
                fill.shader = RadialGradient(
                    side * 0.75f, noseY + 0.1f, 0.35f,
                    Color.argb(150, 255, 64, 129), Color.argb(0, 255, 64, 129), Shader.TileMode.CLAMP,
                )
                canvas.drawCircle(side * 0.75f, noseY + 0.1f, 0.35f, fill)
            }
            fill.shader = null
        }
        layer(canvas, NOSE_DEPTH) {
            fill.shader = RadialGradient(
                -0.08f, noseY - 0.1f, 0.32f,
                Color.rgb(255, 110, 110), Color.rgb(200, 0, 0), Shader.TileMode.CLAMP,
            )
            canvas.drawCircle(0f, noseY, 0.27f, fill)
            fill.shader = null
            fill.color = Color.argb(180, 255, 255, 255)
            canvas.drawCircle(-0.09f, noseY - 0.1f, 0.06f, fill)
        }
    }

    private fun heartEyes(canvas: Canvas) {
        layer(canvas, GLASSES_DEPTH) {
            for (side in listOf(-1f, 1f)) {
                heart(side * 0.5f, 0f, 0.62f)
                fill.color = Color.rgb(236, 30, 70)
                canvas.drawPath(path, fill)
                stroke.color = Color.WHITE
                stroke.strokeWidth = 0.04f
                canvas.drawPath(path, stroke)
            }
        }
        // A few small hearts floating above the head.
        layer(canvas, HEAD_TOP_DEPTH) {
            fill.color = Color.argb(220, 255, 82, 120)
            for ((x, y, size) in listOf(Triple(-0.9f, -1.5f, 0.3f), Triple(0.2f, -1.8f, 0.4f), Triple(1.0f, -1.4f, 0.25f))) {
                heart(x, y, size)
                canvas.drawPath(path, fill)
            }
        }
    }

    private fun alien(canvas: Canvas, pose: FacePose) {
        // Green skin over the face, fading at its edges.
        val faceY = pose.mouthDrop * 0.35f
        layer(canvas, 0f) {
            canvas.scale(1f, 1.35f, 0f, faceY)
            fill.shader = RadialGradient(
                0f, faceY, 1.25f,
                intArrayOf(Color.argb(150, 80, 220, 90), Color.argb(130, 80, 220, 90), Color.argb(0, 80, 220, 90)),
                floatArrayOf(0f, 0.75f, 1f), Shader.TileMode.CLAMP,
            )
            canvas.drawCircle(0f, faceY, 1.25f, fill)
            fill.shader = null
        }
        // Big black eyes, slanted.
        layer(canvas, GLASSES_DEPTH) {
            for (side in listOf(-1f, 1f)) {
                canvas.save()
                canvas.rotate(side * 25f, side * 0.52f, 0f)
                fill.color = Color.argb(235, 10, 10, 10)
                rect.set(side * 0.52f - 0.36f, -0.2f, side * 0.52f + 0.36f, 0.22f)
                canvas.drawOval(rect, fill)
                fill.color = Color.argb(150, 255, 255, 255)
                canvas.drawCircle(side * 0.52f - 0.12f, -0.06f, 0.06f, fill)
                canvas.restore()
            }
        }
        // Antennae.
        layer(canvas, HEAD_TOP_DEPTH) {
            stroke.color = Color.rgb(60, 180, 70)
            stroke.strokeWidth = 0.07f
            fill.color = Color.rgb(140, 255, 120)
            for (side in listOf(-1f, 1f)) {
                path.reset()
                path.moveTo(side * 0.35f, -1.4f)
                path.quadTo(side * 0.45f, -2.0f, side * 0.85f, -2.3f)
                canvas.drawPath(path, stroke)
                canvas.drawCircle(side * 0.85f, -2.3f, 0.14f, fill)
            }
        }
    }

    private fun triangle(x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float) {
        path.reset()
        path.moveTo(x1, y1)
        path.lineTo(x2, y2)
        path.lineTo(x3, y3)
        path.close()
    }

    /** Sets [path] to a heart [size] wide centered on ([x], [y]). */
    private fun heart(x: Float, y: Float, size: Float) {
        val s = size / 1.2f
        path.reset()
        path.moveTo(x, y + 0.45f * s)
        path.cubicTo(x - 0.95f * s, y - 0.1f * s, x - 0.5f * s, y - 0.75f * s, x, y - 0.3f * s)
        path.cubicTo(x + 0.5f * s, y - 0.75f * s, x + 0.95f * s, y - 0.1f * s, x, y + 0.45f * s)
        path.close()
    }
}
