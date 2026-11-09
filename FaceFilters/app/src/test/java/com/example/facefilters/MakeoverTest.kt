package com.example.facefilters

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MakeoverTest {

    // A level face, eyes 100 px apart, centered at (500, 500).
    private val face = FacePose(500f, 500f, 100f, 0f)

    private fun warp(u: Float, v: Float, makeover: Makeover, strength: Float = 1f): Pt {
        val start = face.toScreen(u, v)
        val p = floatArrayOf(start.x, start.y)
        MakeoverWarp.warp(p, listOf(face), makeover, strength)
        return face.toLocal(p[0], p[1])
    }

    private val jawSide = FacePose.DEFAULT_MOUTH_DROP + 0.35f
    private val chin = FacePose.DEFAULT_MOUTH_DROP + 0.8f

    @Test
    fun noneLeavesTheFaceAlone() {
        assertEquals(Pt(0.9f, jawSide), warp(0.9f, jawSide, Makeover.None))
    }

    @Test
    fun olderSagsTheJaw() {
        assertTrue(warp(0.85f, jawSide, Makeover.Older).y > jawSide + 0.05f)
    }

    @Test
    fun youngerRaisesTheChin() {
        assertTrue(warp(0f, chin, Makeover.Younger).y < chin - 0.05f)
    }

    @Test
    fun feminineNarrowsAndMasculineWidensTheJaw() {
        assertTrue(warp(0.9f, jawSide, Makeover.Feminine).x < 0.85f)
        assertTrue(warp(0.9f, jawSide, Makeover.Masculine).x > 0.95f)
        // On both sides alike.
        assertEquals(-warp(0.9f, jawSide, Makeover.Feminine).x, warp(-0.9f, jawSide, Makeover.Feminine).x, 0.001f)
    }

    @Test
    fun farFromTheFaceNothingMoves() {
        for (m in Makeover.entries) assertEquals(m.name, Pt(6f, 6f).x, warp(6f, 6f, m).x, 0.001f)
    }

    @Test
    fun neverFoldsEvenAtFullStrength() {
        for (m in Makeover.entries) {
            var lastX = Float.NEGATIVE_INFINITY
            var lastY = Float.NEGATIVE_INFINITY
            for (i in 0..300) {
                val t = -3f + i * 0.02f
                val across = warp(t, jawSide, m, MakeoverWarp.MAX_STRENGTH)
                val down = warp(0.3f, t, m, MakeoverWarp.MAX_STRENGTH)
                assertTrue("$m folds across at $t", across.x > lastX)
                assertTrue("$m folds down at $t", down.y > lastY)
                lastX = across.x
                lastY = down.y
            }
        }
    }
}
