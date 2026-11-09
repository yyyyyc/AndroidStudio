package com.example.facefilters

import org.junit.Assert.assertEquals
import org.junit.Test

class AdjustmentTest {

    @Test
    fun movesResizesAndTurnsAddUp() {
        val a = Adjustment().moved(0.2f, -0.3f).moved(0.1f, 0.1f).resized(2f).resized(0.75f).rotated(30f).rotated(15f)
        assertEquals(0.3f, a.dx, 0.0001f)
        assertEquals(-0.2f, a.dy, 0.0001f)
        assertEquals(1.5f, a.size, 0.0001f)
        assertEquals(45f, a.rotationDeg, 0.0001f)
    }

    @Test
    fun staysWithinLimits() {
        val a = Adjustment().moved(10f, 10f).resized(10f)
        assertEquals(Adjustment.MAX_MOVE, a.dx, 0f)
        assertEquals(Adjustment.MAX_MOVE, a.dy, 0f)
        assertEquals(Adjustment.MAX_SIZE, a.size, 0f)
        val b = Adjustment().moved(-10f, -10f).resized(0.01f)
        assertEquals(-Adjustment.MAX_MOVE, b.dy, 0f)
        assertEquals(Adjustment.MIN_SIZE, b.size, 0f)
    }

    @Test
    fun turningWrapsAround() {
        assertEquals(-170f, Adjustment(rotationDeg = 170f).rotated(20f).rotationDeg, 0.0001f)
        assertEquals(170f, Adjustment(rotationDeg = -170f).rotated(-20f).rotationDeg, 0.0001f)
    }
}
