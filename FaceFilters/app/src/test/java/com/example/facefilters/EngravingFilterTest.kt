package com.example.facefilters

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EngravingFilterTest {
    private fun rgb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    private fun r(c: Int) = (c shr 16) and 0xFF
    private fun g(c: Int) = (c shr 8) and 0xFF
    private fun b(c: Int) = c and 0xFF
    private fun luma(c: Int) = (r(c) + g(c) + b(c)) / 3f
    private fun engrave(pixels: IntArray, w: Int, h: Int) = IntArray(w * h).also { EngravingFilter().apply(pixels, w, h, it) }

    @Test
    fun `everything comes out gold and opaque`() {
        val w = 40; val h = 30
        val out = engrave(IntArray(w * h) { rgb(it % 256, 80, 200) }, w, h)
        for (c in out) {
            assertEquals(0xFF, c ushr 24)
            assertTrue("red over green over blue: ${Integer.toHexString(c)}", r(c) >= g(c) && g(c) >= b(c))
        }
    }

    @Test
    fun `a thin dark line is inked darker than the paper around it`() {
        val w = 60; val h = 60
        val out = engrave(IntArray(w * h) { if (it % w == 30) rgb(20, 20, 20) else rgb(230, 230, 230) }, w, h)
        val onLine = (10 until 50).map { luma(out[it * w + 30]) }.average()
        val away = (10 until 50).map { luma(out[it * w + 8]) }.average()
        assertTrue("line $onLine vs paper $away", onLine < away - 40)
    }

    @Test
    fun `shade is hatched, light is plain`() {
        val w = 64; val h = 64
        fun spread(color: Int): Float {
            val out = engrave(IntArray(w * h) { color }, w, h)
            val inner = (16 until 48).flatMap { y -> (16 until 48).map { x -> luma(out[y * w + x]) } }
            return inner.max() - inner.min()
        }
        assertTrue(spread(rgb(40, 40, 40)) > 2 * spread(rgb(240, 240, 240)))
    }

    @Test
    fun `the same picture comes out the same, so videos don't flicker`() {
        val w = 32; val h = 24
        val pixels = IntArray(w * h) { rgb((it * 7) % 256, (it * 3) % 256, 128) }
        assertArrayEquals(engrave(pixels, w, h), engrave(pixels, w, h))
    }
}
