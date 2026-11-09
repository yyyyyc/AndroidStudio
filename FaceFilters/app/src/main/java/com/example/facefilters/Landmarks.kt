package com.example.facefilters

import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * Reads MediaPipe's face mesh: 478 points over the whole face (468, plus 10 on the irises). Turns
 * them into what the rest of the app works with: a [FacePose], and the 15 contours [MeshLayout]
 * expects, with the same number of points each as before, so meshes and saved faces fit together.
 *
 * Point numbers are MediaPipe's. "Left" and "right" are the person's own, as in MeshLayout.
 */
object Landmarks {
    /** The fewest points a face can have to be read. */
    const val MIN_POINTS = 468

    private const val NOSE_BASE = 2
    private const val MOUTH_LEFT = 291
    private const val MOUTH_RIGHT = 61

    /** For each of MeshLayout's contours, in its order, the mesh points it is made of. */
    val CONTOURS: Array<IntArray> = arrayOf(
        // The face's outline, from the top of the forehead, all the way around.
        intArrayOf(
            10, 338, 297, 332, 284, 251, 389, 356, 454, 323, 361, 288, 397, 365, 379, 378, 400, 377,
            152, 148, 176, 149, 150, 136, 172, 58, 132, 93, 234, 127, 162, 21, 54, 103, 67, 109,
        ),
        intArrayOf(300, 293, 334, 296, 336), // Left eyebrow, top.
        intArrayOf(276, 283, 282, 295, 285), // Left eyebrow, bottom.
        intArrayOf(70, 63, 105, 66, 107), // Right eyebrow, top.
        intArrayOf(46, 53, 52, 65, 55), // Right eyebrow, bottom.
        // The eyes, round from the outer corner: along the bottom lid, then back along the top.
        intArrayOf(263, 249, 390, 373, 374, 380, 381, 382, 362, 398, 384, 385, 386, 387, 388, 466),
        intArrayOf(33, 7, 163, 144, 145, 153, 154, 155, 133, 173, 157, 158, 159, 160, 161, 246),
        intArrayOf(61, 185, 40, 39, 37, 0, 267, 269, 270, 409, 291), // Upper lip, top edge, corner to corner.
        intArrayOf(191, 80, 81, 82, 13, 312, 311, 310, 415), // Upper lip, bottom edge.
        intArrayOf(95, 88, 178, 87, 14, 317, 402, 318, 324), // Lower lip, top edge.
        intArrayOf(146, 91, 181, 84, 17, 314, 405, 321, 375), // Lower lip, bottom edge.
        intArrayOf(168, 4), // Nose bridge: between the eyes, and the tip.
        intArrayOf(98, NOSE_BASE, 327), // Bottom of the nose.
        intArrayOf(425), // Left cheek.
        intArrayOf(205), // Right cheek.
    )

    /** How many points each contour has: the same as MeshLayout's standard ones. */
    val COUNTS = IntArray(CONTOURS.size) { CONTOURS[it].size }

    /** The middle of the points [indices] of [points] (x, y pairs). */
    private fun middle(points: FloatArray, indices: IntArray): Pt {
        var x = 0f
        var y = 0f
        for (i in indices) {
            x += points[2 * i]
            y += points[2 * i + 1]
        }
        return Pt(x / indices.size, y / indices.size)
    }

    private fun point(points: FloatArray, i: Int) = Pt(points[2 * i], points[2 * i + 1])

    /**
     * The pose of the face whose mesh is [points] (x, y pairs, on the screen), turned [yawDeg]
     * (only its size is used: see [facePose]) and tilted [pitchDeg] up. Null if it can't be told.
     */
    fun pose(points: FloatArray, yawDeg: Float, pitchDeg: Float): FacePose? {
        if (points.size < MIN_POINTS * 2) return null
        val leftEye = middle(points, CONTOURS[MeshLayout.LEFT_EYE])
        val rightEye = middle(points, CONTOURS[MeshLayout.RIGHT_EYE])
        val mouthLeft = point(points, MOUTH_LEFT)
        val mouthRight = point(points, MOUTH_RIGHT)
        val mouth = Pt((mouthLeft.x + mouthRight.x) / 2, (mouthLeft.y + mouthRight.y) / 2)
        return facePose(leftEye, rightEye, point(points, NOSE_BASE), mouth, yawDeg)
            ?.copy(pitchDeg = pitchDeg.coerceIn(-FacePose.MAX_PITCH, FacePose.MAX_PITCH))
    }

    /** The mesh of the face whose points are [points], in MeshLayout's order (see [COUNTS]). */
    fun meshPoints(points: FloatArray): FloatArray? {
        if (points.size < MIN_POINTS * 2) return null
        val out = FloatArray(COUNTS.sum() * 2)
        var o = 0
        for (contour in CONTOURS) for (i in contour) {
            out[o++] = points[2 * i]
            out[o++] = points[2 * i + 1]
        }
        return out
    }

    /**
     * How far the head is turned and tilted, from MediaPipe's transform of a face looking at the
     * camera to this one, [matrix] (4 by 4, either way round: the move is told apart from the
     * turn). Measured against the line from the camera to the face, so a face off to one side
     * that looks at the camera counts as looking straight at it, as it looks on the screen.
     *
     * @return yaw (turned toward the picture's right, unmirrored) and pitch (tilted up), in degrees.
     */
    fun headAngles(matrix: FloatArray): Pair<Float, Float> {
        if (matrix.size < 16) return 0f to 0f
        // Translation sits in the last column, which is the end of the array column by column.
        val columnMajor = abs(matrix[14]) >= abs(matrix[11])
        fun at(row: Int, col: Int) = if (columnMajor) matrix[col * 4 + row] else matrix[row * 4 + col]
        // Where the face looks: its z axis. Space is y up, with the camera looking down -z.
        val fx = at(0, 2)
        val fy = at(1, 2)
        val fz = at(2, 2)
        // Toward the camera, from the face.
        val tx = -at(0, 3)
        val ty = -at(1, 3)
        val tz = -at(2, 3)
        val yaw = Math.toDegrees(atan2(fx, fz).toDouble()) - Math.toDegrees(atan2(tx, tz).toDouble())
        val pitch = Math.toDegrees(asin(fy.coerceIn(-1f, 1f).toDouble())) -
            Math.toDegrees(atan2(ty, hypot(tx, tz)).toDouble())
        return wrap(yaw.toFloat()) to wrap(pitch.toFloat())
    }

    private fun wrap(deg: Float): Float = when {
        deg > 180f -> deg - 360f
        deg < -180f -> deg + 360f
        else -> deg
    }
}
