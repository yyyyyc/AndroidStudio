package com.example.facefilters

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FaceProjectionTest {

    @Test
    fun faceLookingAtTheCameraIsDrawnAsIsAtAnyDepth() {
        for (z in listOf(-1f, 0f, 0.4f)) {
            assertArrayEquals(FaceProjection.SQUARE, FaceProjection.corners(z, 0f), 0.0001f)
        }
    }

    @Test
    fun turningRightMovesPartsInFrontRightAndPartsBehindLeft() {
        val nose = FaceProjection.project(0f, 0.7f, 0.4f, 30f)
        val hat = FaceProjection.project(0f, -1.5f, -0.7f, 30f)
        assertTrue(nose.x > 0f)
        assertTrue(hat.x < 0f)
    }

    @Test
    fun turnedFaceIsNarrowerAndItsFarSideSmaller() {
        val near = FaceProjection.project(-0.5f, 0.5f, 0f, 40f)
        val far = FaceProjection.project(0.5f, 0.5f, 0f, 40f)
        // Turned right, the right side moves away from the camera.
        assertTrue(far.x - near.x < 1f)
        assertTrue(far.y < near.y)
    }

    @Test
    fun eyesStayAtTheirSpacingTimesCos() {
        val eye = FaceProjection.project(0.5f, 0f, 0f, 60f)
        // Perspective aside, which is small at selfie distance.
        assertEquals(0.25f, eye.x, 0.02f)
        assertEquals(0f, eye.y, 0.0001f)
    }
}
