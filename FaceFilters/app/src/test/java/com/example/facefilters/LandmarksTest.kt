package com.example.facefilters

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class LandmarksTest {

    @Test
    fun contoursHaveTheStandardCounts() {
        // Saved faces and meshes are matched by these.
        assertArrayEquals(MeshLayout.STANDARD_COUNTS, Landmarks.COUNTS)
    }

    @Test
    fun contourPointsAreRealAndNotRepeated() {
        val all = Landmarks.CONTOURS.flatMap { it.toList() }
        assertTrue(all.all { it in 0 until Landmarks.MIN_POINTS })
        assertEquals(all.size, all.toSet().size)
    }

    /** A mesh with every point at its own place, so where each lands can be checked. */
    private fun numbered() = FloatArray(Landmarks.MIN_POINTS * 2) { if (it % 2 == 0) (it / 2).toFloat() else 0f }

    @Test
    fun meshPointsFollowTheContours() {
        val mesh = Landmarks.meshPoints(numbered())!!
        val expected = Landmarks.CONTOURS.flatMap { it.toList() }
        assertEquals(expected.size * 2, mesh.size)
        for ((i, index) in expected.withIndex()) assertEquals(index.toFloat(), mesh[2 * i], 0f)
    }

    @Test
    fun tooFewPointsGiveNothing() {
        assertNull(Landmarks.meshPoints(FloatArray(10)))
        assertNull(Landmarks.pose(FloatArray(10), 0f, 0f))
    }

    @Test
    fun poseComesFromTheEyesNoseAndMouth() {
        val points = FloatArray(Landmarks.MIN_POINTS * 2)
        fun put(indices: IntArray, x: Float, y: Float) = indices.forEach { points[2 * it] = x; points[2 * it + 1] = y }
        put(Landmarks.CONTOURS[MeshLayout.LEFT_EYE], 300f, 200f)
        put(Landmarks.CONTOURS[MeshLayout.RIGHT_EYE], 200f, 200f)
        put(intArrayOf(2), 250f, 280f) // Bottom of the nose.
        put(intArrayOf(61, 291), 250f, 320f) // Mouth corners.
        val pose = Landmarks.pose(points, 0f, pitchDeg = 15f)
        assertNotNull(pose)
        pose!!
        assertEquals(250f, pose.cx, 0.01f)
        assertEquals(200f, pose.cy, 0.01f)
        assertEquals(100f, pose.eyeDistance, 0.01f)
        assertEquals(0f, pose.angleDeg, 0.01f)
        assertEquals(0.8f, pose.noseDrop, 0.01f)
        assertEquals(1.2f, pose.mouthDrop, 0.01f)
        assertEquals(15f, pose.pitchDeg, 0.01f)
    }

    /** MediaPipe's transform, column by column: turned [yaw] about y after [pitch] about x. */
    private fun transform(yawDeg: Double, pitchDeg: Double, tx: Float = 0f, ty: Float = 0f, tz: Float = -40f): FloatArray {
        val y = Math.toRadians(yawDeg)
        val p = Math.toRadians(pitchDeg)
        // R = Ry(yaw) * Rx(-pitch): x right, y up; a face looking up has its z axis tipped up.
        val cy = cos(y).toFloat(); val sy = sin(y).toFloat()
        val cp = cos(p).toFloat(); val sp = sin(p).toFloat()
        val r = arrayOf(
            floatArrayOf(cy, -sy * sp, sy * cp),
            floatArrayOf(0f, cp, sp),
            floatArrayOf(-sy, -cy * sp, cy * cp),
        )
        val m = FloatArray(16)
        for (row in 0 until 3) for (col in 0 until 3) m[col * 4 + row] = r[row][col]
        m[12] = tx; m[13] = ty; m[14] = tz; m[15] = 1f
        return m
    }

    @Test
    fun headAnglesOfAFaceLookingAtTheCamera() {
        val (yaw, pitch) = Landmarks.headAngles(transform(0.0, 0.0))
        assertEquals(0f, yaw, 0.01f)
        assertEquals(0f, pitch, 0.01f)
    }

    @Test
    fun headAnglesOfATurnedAndTiltedFace() {
        val (yaw, pitch) = Landmarks.headAngles(transform(30.0, 20.0))
        assertEquals(30f, yaw, 0.5f)
        assertEquals(20f, pitch, 0.5f)
        val (yaw2, pitch2) = Landmarks.headAngles(transform(-25.0, -15.0))
        assertEquals(-25f, yaw2, 0.5f)
        assertEquals(-15f, pitch2, 0.5f)
    }

    @Test
    fun headAnglesReadRowByRowToo() {
        val columns = transform(30.0, 20.0)
        val rows = FloatArray(16) { columns[(it % 4) * 4 + it / 4] }
        val (yaw, pitch) = Landmarks.headAngles(rows)
        assertEquals(30f, yaw, 0.5f)
        assertEquals(20f, pitch, 0.5f)
    }

    @Test
    fun aFaceOffToTheSideLookingAtTheCameraLooksStraight() {
        // 10 cm right of the camera's axis, 40 cm away, turned to face the camera.
        val angle = Math.toDegrees(kotlin.math.atan2(-10.0, 40.0))
        val (yaw, _) = Landmarks.headAngles(transform(angle, 0.0, tx = 10f))
        assertEquals(0f, yaw, 0.5f)
    }
}
