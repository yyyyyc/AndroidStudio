package com.example.facefilters

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OneEuroFilterTest {

    @Test
    fun firstValueIsKeptAsIs() {
        assertEquals(7f, OneEuroFilter(1f, 0f).filter(7f, 0.033f), 0f)
    }

    @Test
    fun aSteadyValueStaysPut() {
        val filter = OneEuroFilter(1f, 1f)
        repeat(20) { assertEquals(5f, filter.filter(5f, 0.033f), 1e-4f) }
    }

    @Test
    fun movingFastIsSmoothedLessThanJitter() {
        // The same step, once as noise on a still value and once as part of a fast move.
        val still = OneEuroFilter(1f, 2f)
        still.filter(0f, 0.033f)
        val afterJitter = still.filter(1f, 0.033f)
        val moving = OneEuroFilter(1f, 2f)
        var x = 0f
        moving.filter(x, 0.033f)
        repeat(10) { x += 20f; moving.filter(x, 0.033f) }
        val before = moving.filter(x, 0.033f)
        val afterMove = moving.filter(x + 1f, 0.033f) - before
        assertTrue("jitter $afterJitter vs move $afterMove", afterMove > afterJitter)
    }

    @Test
    fun resetStartsAgain() {
        val filter = OneEuroFilter(1f, 0f)
        filter.filter(0f, 0.033f)
        filter.reset()
        assertEquals(100f, filter.filter(100f, 0.033f), 0f)
    }

    @Test
    fun tiltingUpRaisesWhatIsInFrontAndPushesTheTopBack() {
        // In front of the face: rises on the screen.
        assertTrue(FaceProjection.project(0f, 0f, 1f, 0f, pitchDeg = 20f).y < 0f)
        // Above the eyes, flat: comes down a little, foreshortened.
        val top = FaceProjection.project(0f, -1f, 0f, 0f, pitchDeg = 20f)
        assertTrue(top.y > -1f)
        // Not tilted, the same as before.
        val flat = FaceProjection.project(0.5f, -1f, 0.3f, 20f)
        val alsoFlat = FaceProjection.project(0.5f, -1f, 0.3f, 20f, pitchDeg = 0f)
        assertEquals(flat, alsoFlat)
    }
}
