package com.example.facefilters

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SketchFilterTest {
    private fun rgb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    private fun luma(c: Int) = (((c shr 16) and 0xFF) + ((c shr 8) and 0xFF) + (c and 0xFF)) / 3f
    private fun sketch(pixels: IntArray, w: Int, h: Int) = IntArray(w * h).also { SketchFilter().apply(pixels, w, h, it) }

    @Test
    fun `a flat light picture is blank paper`() {
        val w = 40; val h = 30
        val out = sketch(IntArray(w * h) { rgb(220, 180, 160) }, w, h)
        for (c in out) {
            assertEquals(0xFF, c ushr 24)
            assertTrue("paper: ${luma(c)}", luma(c) > 225)
        }
    }

    @Test
    fun `an edge is drawn as a pencil line`() {
        val w = 60; val h = 40
        val out = sketch(IntArray(w * h) { if (it % w < 30) rgb(90, 90, 90) else rgb(235, 235, 235) }, w, h)
        val atEdge = (5 until 35).minOf { y -> (27..31).minOf { x -> luma(out[y * w + x]) } }
        val farLight = luma(out[20 * w + 55])
        assertTrue("edge $atEdge vs paper $farLight", atEdge < farLight - 60)
    }

    @Test
    fun `shadows are shaded darker than light areas`() {
        val w = 64; val h = 64
        fun mean(color: Int) = sketch(IntArray(w * h) { color }, w, h).map(::luma).average()
        assertTrue(mean(rgb(30, 30, 30)) < mean(rgb(240, 240, 240)) - 60)
    }

    @Test
    fun `the same picture comes out the same, so videos don't flicker`() {
        val w = 32; val h = 24
        val pixels = IntArray(w * h) { rgb((it * 7) % 256, (it * 3) % 256, 128) }
        assertArrayEquals(sketch(pixels, w, h), sketch(pixels, w, h))
    }
}
