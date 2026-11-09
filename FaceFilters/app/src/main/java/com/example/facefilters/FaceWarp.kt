package com.example.facefilters

import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/** The ways a face can be reshaped, in the order they are offered. */
enum class Shape(val label: String, val emoji: String) {
    None("None", "🚫"),
    Thin("Thin", "🥒"),
    Chubby("Chubby", "🐹"),
    Squish("Squish", "🥞"),
    Stretch("Stretch", "🦒"),
    BigEyes("Big eyes", "👀"),
    BigHead("Big head", "🎈"),
}

/**
 * Reshapes faces by moving points of a picture: a point of the picture at (x, y) is shown where
 * [warp] moves it to. The change fades out smoothly toward the edge of the face, and never folds
 * the picture over itself, up to a strength of 2.
 */
object FaceWarp {

    const val MAX_STRENGTH = 2f

    /**
     * Moves the points in [points], as x, y pairs, to where they show after the faces at [poses]
     * are reshaped. [strength] scales the change: 1 is normal, 0 none.
     */
    fun warp(points: FloatArray, poses: List<FacePose>, shape: Shape, strength: Float = 1f) {
        if (shape == Shape.None || strength <= 0f || poses.isEmpty()) return
        val k = strength.coerceAtMost(MAX_STRENGTH)
        val out = FloatArray(2)
        for (i in 0 until points.size - 1 step 2) {
            val x = points[i]
            val y = points[i + 1]
            var moveX = 0f
            var moveY = 0f
            // Each face moves the picture on its own; faces rarely overlap.
            for (pose in poses) {
                move(x, y, pose, shape, k, out)
                moveX += out[0]
                moveY += out[1]
            }
            points[i] = x + moveX
            points[i + 1] = y + moveY
        }
    }

    /** Where the point ([x], [y]) shows after reshaping: for tests, and single points. */
    fun warp(x: Float, y: Float, poses: List<FacePose>, shape: Shape, strength: Float = 1f): Pt {
        val p = floatArrayOf(x, y)
        warp(p, poses, shape, strength)
        return Pt(p[0], p[1])
    }

    /**
     * Moves [points] as the user's [pushes] do on each face at [poses], one push after another,
     * each on the picture as the ones before it left it. [strength] scales each push, up to as
     * far as it can go without folding the picture.
     */
    fun warpPushes(points: FloatArray, poses: List<FacePose>, pushes: List<Push>, strength: Float = 1f) {
        if (pushes.isEmpty() || strength <= 0f || poses.isEmpty()) return
        for (i in 0 until points.size - 1 step 2) {
            val x = points[i]
            val y = points[i + 1]
            var moveX = 0f
            var moveY = 0f
            for (pose in poses) {
                val start = pose.toLocal(x, y)
                var u = start.x
                var v = start.y
                for (push in pushes) {
                    val du = u - push.u
                    val dv = v - push.v
                    val r2 = (du * du + dv * dv) / (push.radius * push.radius)
                    if (r2 >= 1f) continue
                    val length = kotlin.math.hypot(push.du, push.dv)
                    if (length == 0f) continue
                    val scale = kotlin.math.min(strength, Push.MAX_MOVE * push.radius / length)
                    val w = (1 - r2) * (1 - r2) * scale
                    u += push.du * w
                    v += push.dv * w
                }
                val end = pose.toScreen(u, v)
                moveX += end.x - x
                moveY += end.y - y
            }
            points[i] = x + moveX
            points[i + 1] = y + moveY
        }
    }

    /** Sets [out] to how far one face moves the point ([x], [y]), in screen pixels. */
    private fun move(x: Float, y: Float, pose: FacePose, shape: Shape, k: Float, out: FloatArray) {
        out[0] = 0f
        out[1] = 0f
        val unit = pose.eyeDistance
        if (unit <= 0f) return
        val t = Math.toRadians(pose.angleDeg.toDouble())
        val c = cos(t).toFloat()
        val s = sin(t).toFloat()
        val dx = x - pose.cx
        val dy = y - pose.cy
        // In the face's own terms (see FacePose): u across the face, v down it.
        val u = (dx * c + dy * s) / unit
        val v = (-dx * s + dy * c) / unit
        // A face turned away looks narrower.
        val narrow = max(cos(Math.toRadians(pose.yawDeg.toDouble())).toFloat(), 0.6f)

        var newU = u
        var newV = v
        when (shape) {
            Shape.None -> return
            Shape.Thin, Shape.Chubby, Shape.Squish, Shape.Stretch -> {
                // The middle of the face, and an oval a little bigger than it.
                val middle = pose.mouthDrop * 0.45f
                val down = v - middle
                val w = falloff(u / (1.25f * narrow * 1.5f), down / (1.75f * 1.5f))
                if (w == 0f) return
                when (shape) {
                    Shape.Thin -> newU = u * (1 - 0.22f * k * w)
                    Shape.Chubby -> newU = u * (1 + 0.3f * k * w)
                    Shape.Squish -> newV = middle + down * (1 - 0.22f * k * w)
                    else -> newV = middle + down * (1 + 0.3f * k * w)
                }
            }
            Shape.BigEyes -> {
                for (side in floatArrayOf(-1f, 1f)) {
                    val eyeU = side * 0.5f * narrow
                    val w = falloff((newU - eyeU) / 0.45f, newV / 0.45f)
                    if (w == 0f) continue
                    val grow = 1 + 0.6f * k * w
                    newU = eyeU + (newU - eyeU) * grow
                    newV *= grow
                }
            }
            Shape.BigHead -> {
                // Centered on the forehead, so the head grows more than the chin.
                val middle = -0.2f
                val w = falloff(u / (1.7f * narrow), (v - middle) / 2.3f)
                if (w == 0f) return
                val grow = 1 + 0.3f * k * w
                newU = u * grow
                newV = middle + (v - middle) * grow
            }
        }
        val du = (newU - u) * unit
        val dv = (newV - v) * unit
        out[0] = du * c - dv * s
        out[1] = du * s + dv * c
    }

    /**
     * 1 at the middle of an oval, fading to 0 at its edge, where ([a], [b]) is a point's place in
     * it: (0, 0) the middle, a² + b² = 1 the edge. Flat at the edge, so the change has no seam.
     */
    private fun falloff(a: Float, b: Float): Float {
        val r2 = a * a + b * b
        if (r2 >= 1f) return 0f
        val f = 1 - r2
        return f * f
    }
}

/**
 * One push of a custom shape: the picture at ([u], [v]) is moved by ([du], [dv]), and the picture
 * around it within [radius] moves less the farther it is. All in a face's units (see [FacePose]).
 */
data class Push(val u: Float, val v: Float, val du: Float, val dv: Float, val radius: Float) {
    companion object {
        /** The most a push moves the picture, for its size: more would fold the picture over. */
        const val MAX_MOVE = 0.6f
    }
}

/** A shape the user made, by pushing a face around. */
data class CustomShape(val id: String, val name: String, val pushes: List<Push>)

/** A way to reshape faces: built in, or made by the user. */
sealed interface Reshape {
    /** What its strength and other settings are kept under. */
    val key: String
    val isNone: Boolean

    /** Moves [points], as x, y pairs, to where they show after the faces at [poses] are reshaped. */
    fun warp(points: FloatArray, poses: List<FacePose>, strength: Float)
}

data class BuiltInReshape(val shape: Shape) : Reshape {
    override val key get() = "shape.${shape.name}"
    override val isNone get() = shape == Shape.None
    override fun warp(points: FloatArray, poses: List<FacePose>, strength: Float) =
        FaceWarp.warp(points, poses, shape, strength)
}

data class CustomReshape(val shape: CustomShape) : Reshape {
    override val key get() = "shapec.${shape.id}"
    override val isNone get() = shape.pushes.isEmpty()
    override fun warp(points: FloatArray, poses: List<FacePose>, strength: Float) =
        FaceWarp.warpPushes(points, poses, shape.pushes, strength)
}
