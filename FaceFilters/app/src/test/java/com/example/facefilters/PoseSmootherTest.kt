package com.example.facefilters

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class PoseSmootherTest {

    private fun at(x: Float, angle: Float = 0f, y: Float = 100f) = FacePose(x, y, 100f, angle)

    @Test
    fun newFaceIsShownWhereDetected() {
        val smoother = PoseSmoother()
        assertEquals(listOf(at(50f)), smoother.update(listOf(at(50f)), 0))
    }

    @Test
    fun jitterIsSmoothedHard() {
        val smoother = PoseSmoother()
        smoother.update(listOf(at(0f)), 0)
        // Two pixels back and forth: detector noise on a still face.
        var worst = 0f
        for (frame in 1..30) {
            val shown = smoother.update(listOf(at(if (frame % 2 == 0) 2f else -2f)), frame * 33L).single()
            worst = maxOf(worst, abs(shown.cx))
        }
        assertTrue("shook by $worst", worst < 1f)
    }

    @Test
    fun aMovingFaceIsFollowedClosely() {
        val smoother = PoseSmoother()
        smoother.update(listOf(at(0f)), 0)
        // Half a face a frame: a quick turn of the head.
        var shown = at(0f)
        for (frame in 1..10) shown = smoother.update(listOf(at(frame * 50f)), frame * 33L).single()
        assertTrue("lagged ${500f - shown.cx} px behind", 500f - shown.cx < 60f)
    }

    @Test
    fun bigJumpsAreFollowedAtOnce() {
        val smoother = PoseSmoother()
        smoother.update(listOf(at(0f)), 0)
        assertEquals(200f, smoother.update(listOf(at(200f)), 33).single().cx, 0.001f)
        assertEquals(80f, smoother.update(listOf(at(200f, 80f)), 66).single().angleDeg, 0.001f)
    }

    @Test
    fun missedFaceIsHeldBrieflyThenDropped() {
        val smoother = PoseSmoother(holdMs = 250)
        smoother.update(listOf(at(0f)), 0)
        assertEquals(1, smoother.update(emptyList(), 200).size)
        assertTrue(smoother.update(emptyList(), 300).isEmpty())
    }

    @Test
    fun facesAreToldApartByWhereTheyAre() {
        val smoother = PoseSmoother()
        smoother.update(listOf(at(0f), at(1000f)), 0)
        // Listed the other way round this time: each still follows its own face.
        val poses = smoother.update(listOf(at(1010f), at(10f)), 33)
        assertEquals(2, poses.size)
        assertTrue(poses.any { it.cx in 0f..10f })
        assertTrue(poses.any { it.cx in 1000f..1010f })
    }

    @Test
    fun aFaceFarFromAnyIsNew() {
        val smoother = PoseSmoother(holdMs = 250)
        smoother.update(listOf(at(0f)), 0)
        val poses = smoother.update(listOf(at(0f), at(0f, y = 900f)), 33)
        assertEquals(2, poses.size)
    }

    @Test
    fun clearForgetsAllFaces() {
        val smoother = PoseSmoother()
        smoother.update(listOf(at(0f)), 0)
        smoother.clear()
        assertTrue(smoother.update(emptyList(), 1).isEmpty())
    }
}
