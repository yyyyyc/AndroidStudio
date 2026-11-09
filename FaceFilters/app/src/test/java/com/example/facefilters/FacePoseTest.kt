package com.example.facefilters

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FacePoseTest {

    private fun pose(a: Pt, b: Pt, nose: Pt? = null, mouth: Pt? = null) =
        requireNotNull(facePose(a, b, nose, mouth))

    @Test
    fun levelFaceIsUprightAndCenteredBetweenTheEyes() {
        val p = pose(Pt(100f, 200f), Pt(200f, 200f))
        assertEquals(150f, p.cx, 0.01f)
        assertEquals(200f, p.cy, 0.01f)
        assertEquals(100f, p.eyeDistance, 0.01f)
        assertEquals(0f, p.angleDeg, 0.01f)
    }

    @Test
    fun eyeOrderDoesNotMatter() {
        // Front-camera mirroring swaps which eye is on which side.
        val a = pose(Pt(100f, 200f), Pt(200f, 230f))
        val b = pose(Pt(200f, 230f), Pt(100f, 200f))
        assertEquals(a, b)
    }

    @Test
    fun tiltIsClockwiseInScreenCoordinates() {
        // The right eye lower than the left: tilted clockwise on a y-down screen.
        assertEquals(45f, pose(Pt(0f, 0f), Pt(100f, 100f)).angleDeg, 0.01f)
        assertEquals(-45f, pose(Pt(0f, 100f), Pt(100f, 0f)).angleDeg, 0.01f)
    }

    @Test
    fun angleStaysWithinNinetyDegrees() {
        val p = pose(Pt(100f, 0f), Pt(100.5f, 100f))
        assertTrue(p.angleDeg in -90f..90f)
    }

    @Test
    fun noseAndMouthAreMeasuredDownTheFace() {
        val p = pose(Pt(0f, 0f), Pt(100f, 0f), nose = Pt(50f, 70f), mouth = Pt(50f, 120f))
        assertEquals(0.7f, p.noseDrop, 0.001f)
        assertEquals(1.2f, p.mouthDrop, 0.001f)
    }

    @Test
    fun dropsFollowATiltedFace() {
        // Face turned 90° clockwise: "down the face" points to the screen's left.
        val p = pose(Pt(0f, 0f), Pt(0f, 100f), mouth = Pt(-120f, 50f))
        assertEquals(90f, p.angleDeg, 0.01f)
        assertEquals(1.2f, p.mouthDrop, 0.001f)
    }

    @Test
    fun implausibleDropsAreClampedAndMissingOnesDefault() {
        val p = pose(Pt(0f, 0f), Pt(100f, 0f), nose = Pt(50f, -500f))
        assertEquals(0.35f, p.noseDrop, 0.001f)
        assertEquals(FacePose.DEFAULT_MOUTH_DROP, p.mouthDrop, 0.001f)
    }

    @Test
    fun eyesOnTopOfEachOtherGiveNoPose() {
        assertNull(facePose(Pt(10f, 10f), Pt(10.2f, 10.2f)))
    }

    @Test
    fun turnDirectionComesFromTheNoseNotTheDetectorsSign() {
        val eyes = arrayOf(Pt(0f, 0f), Pt(100f, 0f))
        // Nose to the right of the eyes' middle: turned right, whatever sign the detector gave.
        val right = facePose(eyes[0], eyes[1], Pt(70f, 70f), null, turnDeg = -30f)!!
        assertEquals(30f, right.yawDeg, 0.01f)
        val left = facePose(eyes[0], eyes[1], Pt(30f, 70f), null, turnDeg = 30f)!!
        assertEquals(-30f, left.yawDeg, 0.01f)
    }

    @Test
    fun turnedFaceIsSizedAsIfLookingAtTheCamera() {
        val p = facePose(Pt(0f, 0f), Pt(50f, 0f), nose = Pt(40f, 40f), turnDeg = 60f)!!
        // Turns are capped, then the 50 px between the eyes is undone by cos(55°).
        assertEquals(FacePose.MAX_YAW, p.yawDeg, 0.01f)
        assertEquals(50f / kotlin.math.cos(Math.toRadians(55.0)).toFloat(), p.eyeDistance, 0.01f)
    }

    @Test
    fun noseNearTheMiddleFadesTheTurnIn() {
        // A nose 1% right of the middle: too little to be sure, so only a fifth of the turn.
        val p = facePose(Pt(0f, 0f), Pt(100f, 0f), nose = Pt(51f, 70f), turnDeg = 20f)!!
        assertEquals(4f, p.yawDeg, 0.01f)
    }
}
