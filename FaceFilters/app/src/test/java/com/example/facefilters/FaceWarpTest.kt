package com.example.facefilters

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FaceWarpTest {

    // A level face, eyes 100 px apart, centered at (500, 500).
    private val face = FacePose(500f, 500f, 100f, 0f)
    private val faces = listOf(face)
    // Halfway down the face, where the reshaping oval is centered.
    private val middleY = 500f + 100f * FacePose.DEFAULT_MOUTH_DROP * 0.45f

    private fun warp(x: Float, y: Float, shape: Shape, strength: Float = 1f) =
        FaceWarp.warp(x, y, faces, shape, strength)

    @Test
    fun noShapeOrNoFaceLeavesThePictureAlone() {
        assertEquals(Pt(560f, 540f), warp(560f, 540f, Shape.None))
        assertEquals(Pt(560f, 540f), FaceWarp.warp(560f, 540f, emptyList(), Shape.Thin))
        assertEquals(Pt(560f, 540f), warp(560f, 540f, Shape.Thin, strength = 0f))
    }

    @Test
    fun farFromTheFaceNothingMoves() {
        for (shape in Shape.entries) {
            assertEquals(shape.name, Pt(1200f, 1500f), warp(1200f, 1500f, shape))
        }
    }

    @Test
    fun thinPullsTheSidesInAndChubbyPushesThemOut() {
        val cheek = 560f
        assertTrue(warp(cheek, middleY, Shape.Thin).x in 500f..cheek)
        assertTrue(warp(cheek, middleY, Shape.Chubby).x > cheek)
        // Side to side only.
        assertEquals(middleY, warp(cheek, middleY, Shape.Thin).y, 0.001f)
    }

    @Test
    fun squishAndStretchMoveTheChinAlongTheFace() {
        val chin = middleY + 100f
        assertTrue(warp(500f, chin, Shape.Squish).y in middleY..chin)
        assertTrue(warp(500f, chin, Shape.Stretch).y > chin)
    }

    @Test
    fun bigEyesGrowAroundEachEye() {
        val nearEye = warp(470f, 510f, Shape.BigEyes) // Just below and right of the left eye (450, 500).
        assertTrue(nearEye.x > 470f)
        assertTrue(nearEye.y > 510f)
        // The eye's own middle stays put.
        val eye = warp(450f, 500f, Shape.BigEyes)
        assertEquals(450f, eye.x, 0.001f)
        assertEquals(500f, eye.y, 0.001f)
    }

    @Test
    fun followsATiltedFace() {
        // Turned 90° clockwise, "across the face" is the screen's up and down.
        val tilted = listOf(face.copy(angleDeg = 90f))
        val side = 500f - 100f * FacePose.DEFAULT_MOUTH_DROP * 0.45f // The oval's middle, on the screen's left.
        val p = FaceWarp.warp(side, 560f, tilted, Shape.Thin)
        assertTrue(p.y < 560f)
        assertEquals(side, p.x, 0.01f)
    }

    @Test
    fun neverFoldsThePictureEvenAtFullStrength() {
        // Points in order along lines through the face stay in order after reshaping.
        for (shape in Shape.entries) {
            var lastX = Float.NEGATIVE_INFINITY
            var lastY = Float.NEGATIVE_INFINITY
            for (i in 0..400) {
                val t = 200f + i * 1.5f
                val across = FaceWarp.warp(t, middleY, faces, shape, FaceWarp.MAX_STRENGTH)
                val down = FaceWarp.warp(470f, t, faces, shape, FaceWarp.MAX_STRENGTH)
                assertTrue("$shape folds across at $t", across.x > lastX)
                assertTrue("$shape folds down at $t", down.y > lastY)
                lastX = across.x
                lastY = down.y
            }
        }
    }
}
