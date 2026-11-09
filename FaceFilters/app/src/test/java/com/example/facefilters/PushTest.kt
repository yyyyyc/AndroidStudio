package com.example.facefilters

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PushTest {

    private val face = FacePose(500f, 500f, 100f, 0f)

    private fun warp(x: Float, y: Float, pushes: List<Push>, strength: Float = 1f, poses: List<FacePose> = listOf(face)): Pt {
        val p = floatArrayOf(x, y)
        FaceWarp.warpPushes(p, poses, pushes, strength)
        return Pt(p[0], p[1])
    }

    @Test
    fun pushMovesItsMiddleAllTheWay() {
        // Chin (0, 2) pulled down 0.3 units.
        val p = warp(500f, 700f, listOf(Push(0f, 2f, 0f, 0.3f, 0.6f)))
        assertEquals(500f, p.x, 0.01f)
        assertEquals(730f, p.y, 0.01f)
    }

    @Test
    fun outsideThePushNothingMoves() {
        assertEquals(Pt(500f, 500f), warp(500f, 500f, listOf(Push(0f, 2f, 0f, 0.3f, 0.6f))))
    }

    @Test
    fun pushesTooFarForTheirSizeAreHeldBack() {
        // 1 unit with a 0.5 radius would fold the picture: held to 0.6 × 0.5.
        val p = warp(500f, 700f, listOf(Push(0f, 2f, 0f, 1f, 0.5f)))
        assertEquals(730f, p.y, 0.01f)
    }

    @Test
    fun followsATiltedFace() {
        // Turned 90° clockwise: "down the face" is the screen's left.
        val tilted = face.copy(angleDeg = 90f)
        val chin = tilted.toScreen(0f, 2f)
        val p = warp(chin.x, chin.y, listOf(Push(0f, 2f, 0f, 0.3f, 0.6f)), poses = listOf(tilted))
        assertEquals(chin.x - 30f, p.x, 0.01f)
        assertEquals(chin.y, p.y, 0.01f)
    }

    @Test
    fun neverFolds() {
        val pushes = listOf(Push(0f, 1f, 0.5f, 0.5f, 0.8f), Push(0.3f, 1.2f, -0.4f, 0.2f, 0.7f))
        var last = Float.NEGATIVE_INFINITY
        for (i in 0..300) {
            val y = 400f + i
            val p = warp(510f, y, pushes, strength = FaceWarp.MAX_STRENGTH)
            assertTrue("folds at $y", p.y > last)
            last = p.y
        }
    }

    @Test
    fun localAndScreenAreOpposites() {
        val tilted = FacePose(320f, 410f, 80f, 33f)
        val local = tilted.toLocal(123f, 456f)
        val back = tilted.toScreen(local.x, local.y)
        assertEquals(123f, back.x, 0.01f)
        assertEquals(456f, back.y, 0.01f)
    }
}
