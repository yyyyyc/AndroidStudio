package com.example.facefilters

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AnimeFilterTest {

    private fun rgb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    private fun red(c: Int) = (c shr 16) and 0xFF
    private fun green(c: Int) = (c shr 8) and 0xFF
    private fun blue(c: Int) = c and 0xFF
    private fun luma(c: Int) = (77 * red(c) + 150 * green(c) + 29 * blue(c)) shr 8

    private fun cartoon(pixels: IntArray, w: Int, h: Int) = IntArray(w * h).also { AnimeFilter().apply(pixels, w, h, it) }

    @Test
    fun flatAreasHaveNoInkAndKeepTheirHue() {
        val w = 20
        val h = 20
        val out = cartoon(IntArray(w * h) { rgb(200, 150, 120) }, w, h)
        assertTrue(out.all { it == out[0] })
        // Still a warm skin tone: red over green over blue.
        assertTrue(red(out[0]) > green(out[0]) && green(out[0]) > blue(out[0]))
        assertEquals(0xFF, out[0] ushr 24)
    }

    @Test
    fun strongEdgesAreInked() {
        val w = 30
        val h = 10
        // Dark on the left, light on the right.
        val out = cartoon(IntArray(w * h) { if (it % w < w / 2) rgb(30, 30, 30) else rgb(240, 240, 240) }, w, h)
        val row = 5 * w
        val edge = (w / 2 - 2..w / 2 + 2).minOf { luma(out[row + it]) }
        assertTrue("ink at the edge", edge < luma(out[row + w - 3]) - 100)
    }

    @Test
    fun lightComesInFewBands() {
        val w = 256
        val h = 6
        // A smooth ramp of grey becomes a few flat steps.
        val out = cartoon(IntArray(w * h) { rgb(it % w, it % w, it % w) }, w, h)
        val levels = (10 until w - 10).map { luma(out[3 * w + it]) / 8 }.toSet()
        assertTrue("got ${levels.size} levels", levels.size < 40)
    }

    @Test
    fun colorsGetBrighter() {
        val w = 10
        val h = 10
        val out = cartoon(IntArray(w * h) { rgb(150, 120, 100) }, w, h)
        assertTrue(red(out[0]) - blue(out[0]) > 50)
    }
}
