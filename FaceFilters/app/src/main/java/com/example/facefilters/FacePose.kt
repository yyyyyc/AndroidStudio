package com.example.facefilters

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/** A point on the screen, in preview pixels. */
data class Pt(val x: Float, val y: Float)

/**
 * Where a face is on the screen, in the terms filters are drawn in. The origin is midway between
 * the eyes, the x axis runs from the eye on the screen's left to the other, and the unit is the
 * distance between the eyes as if the face looked straight at the camera. The y axis points down
 * the face, toward the mouth, and z points out of the face, toward the camera.
 *
 * @property eyeDistance the unit, in screen pixels.
 * @property angleDeg how far the face is tilted clockwise, between -90 and 90 degrees.
 * @property yawDeg how far the face is turned toward the screen's right; negative is left.
 * @property noseDrop how far below the eyes the bottom of the nose is, in units.
 * @property mouthDrop how far below the eyes the middle of the mouth is, in units.
 * @property pitchDeg how far the face is tilted up (looking up); negative is down.
 */
data class FacePose(
    val cx: Float,
    val cy: Float,
    val eyeDistance: Float,
    val angleDeg: Float,
    val yawDeg: Float = 0f,
    val noseDrop: Float = DEFAULT_NOSE_DROP,
    val mouthDrop: Float = DEFAULT_MOUTH_DROP,
    val pitchDeg: Float = 0f,
) {
    /** The same pose in an image [factor] times as big. */
    fun scaled(factor: Float) = copy(cx = cx * factor, cy = cy * factor, eyeDistance = eyeDistance * factor)

    companion object {
        const val DEFAULT_NOSE_DROP = 0.75f
        const val DEFAULT_MOUTH_DROP = 1.15f
        const val MAX_YAW = 55f
        const val MAX_PITCH = 40f
    }
}

/** Where the screen point ([x], [y]) is in this face's own terms: across it and down it, in units. */
fun FacePose.toLocal(x: Float, y: Float): Pt {
    val t = Math.toRadians(angleDeg.toDouble())
    val c = cos(t).toFloat()
    val s = sin(t).toFloat()
    val dx = x - cx
    val dy = y - cy
    return Pt((dx * c + dy * s) / eyeDistance, (-dx * s + dy * c) / eyeDistance)
}

/** Where the point ([u], [v]), in this face's own terms, is on the screen. */
fun FacePose.toScreen(u: Float, v: Float): Pt {
    val t = Math.toRadians(angleDeg.toDouble())
    val c = cos(t).toFloat()
    val s = sin(t).toFloat()
    val px = u * eyeDistance
    val py = v * eyeDistance
    return Pt(cx + px * c - py * s, cy + px * s + py * c)
}

/**
 * The pose of a face with eyes at [eyeA] and [eyeB], in either order, and the nose and mouth at
 * [nose] and [mouth] if known. Null if the eyes are too close together to tell the tilt.
 *
 * [turnDeg] is how far the head is turned, as the detector reports it. Only its size is used:
 * which way it is turned is read from the nose, which moves toward the side the face turns to.
 * That holds on mirrored front-camera previews too, where the detector's sign is backwards.
 */
fun facePose(eyeA: Pt, eyeB: Pt, nose: Pt? = null, mouth: Pt? = null, turnDeg: Float = 0f): FacePose? {
    var dx = eyeB.x - eyeA.x
    var dy = eyeB.y - eyeA.y
    val distance = hypot(dx, dy)
    if (distance < 1f) return null
    // From the eye on the screen's left, so the face is drawn upright.
    if (dx < 0) {
        dx = -dx
        dy = -dy
    }
    val cx = (eyeA.x + eyeB.x) / 2
    val cy = (eyeA.y + eyeB.y) / 2

    // How far the nose is to the right of the eyes' midpoint, in on-screen eye distances. Near
    // zero the direction is noise, so the turn fades in.
    val noseShift = nose?.let { ((it.x - cx) * dx + (it.y - cy) * dy) / distance / distance } ?: 0f
    val direction = (noseShift / 0.05f).coerceIn(-1f, 1f)
    val yaw = (abs(turnDeg) * direction).coerceIn(-FacePose.MAX_YAW, FacePose.MAX_YAW)
    // Turned away, the eyes look closer together than they are.
    val unit = distance / cos(Math.toRadians(yaw.toDouble())).toFloat()

    // How far below the eyes p is, along the face's down direction (the x axis turned 90°).
    fun drop(p: Pt?, default: Float, range: ClosedFloatingPointRange<Float>): Float {
        p ?: return default
        val down = ((p.x - cx) * -dy + (p.y - cy) * dx) / distance
        return (down / unit).coerceIn(range)
    }

    return FacePose(
        cx = cx,
        cy = cy,
        eyeDistance = unit,
        angleDeg = Math.toDegrees(atan2(dy, dx).toDouble()).toFloat(),
        yawDeg = yaw,
        noseDrop = drop(nose, FacePose.DEFAULT_NOSE_DROP, 0.35f..1.3f),
        mouthDrop = drop(mouth, FacePose.DEFAULT_MOUTH_DROP, 0.7f..1.9f),
    )
}

/** How the parts of a filter move when the head turns: each part is flat, at its own depth. */
object FaceProjection {
    /** How far the camera is from the face, in units. About 40 cm for a selfie. */
    const val CAMERA_DISTANCE = 6f

    /** The corners of the square that [corners] maps, as x, y pairs. */
    val SQUARE = floatArrayOf(-1f, -1f, 1f, -1f, 1f, 1f, -1f, 1f)

    /**
     * Where the point ([x], [y], [z]) of a face looking at the camera ends up once the face tilts
     * [pitchDeg] up and then turns [yawDeg]. Scaled so that a face looking at the camera is drawn
     * as is, whatever the depth.
     */
    fun project(x: Float, y: Float, z: Float, yawDeg: Float, pitchDeg: Float = 0f): Pt {
        // Tilted up: what faces the camera rises (y is down), and the top comes back.
        val p = Math.toRadians(pitchDeg.toDouble())
        val cp = cos(p).toFloat()
        val sp = sin(p).toFloat()
        val tiltedY = y * cp - z * sp
        val tiltedZ = y * sp + z * cp
        val t = Math.toRadians(yawDeg.toDouble())
        val c = cos(t).toFloat()
        val s = sin(t).toFloat()
        val turnedX = x * c + tiltedZ * s
        val turnedZ = -x * s + tiltedZ * c
        val perspective = (CAMERA_DISTANCE - z) / (CAMERA_DISTANCE - turnedZ)
        return Pt(turnedX * perspective, tiltedY * perspective)
    }

    /** Where the corners of [SQUARE], at depth [z], end up once the face turns (see [project]). */
    fun corners(z: Float, yawDeg: Float, pitchDeg: Float = 0f): FloatArray {
        val out = FloatArray(8)
        for (i in 0 until 4) {
            val p = project(SQUARE[2 * i], SQUARE[2 * i + 1], z, yawDeg, pitchDeg)
            out[2 * i] = p.x
            out[2 * i + 1] = p.y
        }
        return out
    }
}

/**
 * Smooths a value that is measured again and again, such as a face's position: the One Euro
 * filter (Casiez, Roussel and Vogel, 2012). When the value holds still, it is smoothed hard, so
 * the detector's jitter doesn't show; the faster it moves, the less it is smoothed, so it doesn't
 * lag behind.
 *
 * @param minCutoff how much a still value is smoothed, in Hz: lower is smoother.
 * @param beta how quickly smoothing lets go as the value speeds up.
 */
class OneEuroFilter(
    private val minCutoff: Float,
    private val beta: Float,
    private val speedCutoff: Float = 1f,
) {
    private var value = 0f
    private var speed = 0f
    private var started = false

    /**
     * The smoothed value, once [x] is measured [dtSeconds] after the last one. Speed is counted in
     * [scale]s a second: a face's size, say, so faces near and far are smoothed alike.
     */
    fun filter(x: Float, dtSeconds: Float, scale: Float = 1f): Float {
        if (!started || dtSeconds <= 0f) {
            started = true
            value = x
            speed = 0f
            return x
        }
        val measured = (x - value) / dtSeconds
        speed += alpha(speedCutoff, dtSeconds) * (measured - speed)
        val cutoff = minCutoff + beta * abs(speed) / scale.coerceAtLeast(1e-6f)
        value += alpha(cutoff, dtSeconds) * (x - value)
        return value
    }

    fun reset() {
        started = false
    }

    companion object {
        /** How far toward a new measurement to move, for smoothing at [cutoff] Hz. */
        fun alpha(cutoff: Float, dtSeconds: Float): Float {
            val tau = 1f / (2f * Math.PI.toFloat() * cutoff)
            return 1f / (1f + tau / dtSeconds)
        }
    }
}

/**
 * Steadies poses from frame to frame so filters don't shake, without lagging behind a moving
 * face (see [OneEuroFilter]), and keeps a face on the screen for a moment after the detector
 * misses it in a frame. Faces are told apart by where they are: each is matched to the nearest
 * face in the last frame.
 */
class PoseSmoother(
    /** How long a face that is no longer detected stays shown. */
    private val holdMs: Long = 250,
    private val minCutoff: Float = 1.2f,
    private val beta: Float = 4f,
) {
    private inner class Track(var pose: FacePose, var seenAtMs: Long) {
        val filters = Array(8) { OneEuroFilter(minCutoff, beta) }

        init {
            follow(pose, seenAtMs)
        }

        fun follow(target: FacePose, nowMs: Long) {
            val dt = (nowMs - seenAtMs) / 1000f
            seenAtMs = nowMs
            // A big jump is a different face or a fast move: lagging behind it would look wrong.
            val jump = hypot(target.cx - pose.cx, target.cy - pose.cy)
            if (jump > target.eyeDistance * 1.5f || abs(target.angleDeg - pose.angleDeg) > 45f) {
                filters.forEach { it.reset() }
            }
            val size = target.eyeDistance
            // Positions and size move in face sizes, angles in tens of degrees, the rest in units.
            pose = FacePose(
                cx = filters[0].filter(target.cx, dt, size),
                cy = filters[1].filter(target.cy, dt, size),
                eyeDistance = filters[2].filter(target.eyeDistance, dt, size),
                angleDeg = filters[3].filter(target.angleDeg, dt, ANGLE_SCALE),
                yawDeg = filters[4].filter(target.yawDeg, dt, ANGLE_SCALE),
                noseDrop = filters[5].filter(target.noseDrop, dt),
                mouthDrop = filters[6].filter(target.mouthDrop, dt),
                pitchDeg = filters[7].filter(target.pitchDeg, dt, ANGLE_SCALE),
            )
        }
    }

    private val tracks = ArrayList<Track>()

    /** The poses to show after a frame in which [detected] faces were found. */
    fun update(detected: List<FacePose>, nowMs: Long): List<FacePose> {
        val unmatched = tracks.toMutableList()
        // Nearest pairs first, so two faces close together don't swap.
        val pairs = ArrayList<Triple<Int, Track, Float>>()
        for ((index, face) in detected.withIndex()) {
            for (track in unmatched) {
                val distance = hypot(face.cx - track.pose.cx, face.cy - track.pose.cy)
                if (distance < face.eyeDistance * MATCH_DISTANCE) pairs += Triple(index, track, distance)
            }
        }
        pairs.sortBy { it.third }
        val placed = BooleanArray(detected.size)
        for ((index, track, _) in pairs) {
            if (placed[index] || track !in unmatched) continue
            track.follow(detected[index], nowMs)
            placed[index] = true
            unmatched -= track
        }
        for ((index, face) in detected.withIndex()) if (!placed[index]) tracks += Track(face, nowMs)
        tracks.removeAll { nowMs - it.seenAtMs > holdMs }
        return tracks.map { it.pose }
    }

    fun clear() = tracks.clear()

    private companion object {
        const val ANGLE_SCALE = 10f
        /** How far a face can move between frames, in its own sizes, and still be the same face. */
        const val MATCH_DISTANCE = 3f
    }
}
