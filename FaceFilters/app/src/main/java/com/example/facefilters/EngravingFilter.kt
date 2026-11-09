package com.example.facefilters

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/** Turns ARGB pixels into a drawing of them. */
interface PictureFilter {
    /** Writes the drawing of the [width] by [height] picture [pixels] to [out]. */
    fun apply(pixels: IntArray, width: Int, height: Int, out: IntArray)
}

/**
 * Turns a picture into a gold engraving: drawn in dark ink lines on textured gold leaf, shaded with
 * diagonal hatching, and lit from the top left so it looks pressed into relief. Its lines are
 * sized to the picture, so a photo and the live picture look alike. The paper's grain stays in
 * place from frame to frame, so it doesn't flicker in videos. Not thread-safe: use one per thread.
 */
class EngravingFilter : PictureFilter {
    private var size = 0
    private var luma = IntArray(0)
    private var fine = IntArray(0)
    private var broad = IntArray(0)
    private var tmp = IntArray(0)

    override fun apply(pixels: IntArray, width: Int, height: Int, out: IntArray) {
        val n = width * height
        require(pixels.size >= n && out.size >= n)
        if (size != n) {
            size = n
            luma = IntArray(n); fine = IntArray(n); broad = IntArray(n); tmp = IntArray(n)
        }
        for (i in 0 until n) {
            val c = pixels[i]
            luma[i] = (77 * ((c shr 16) and 0xFF) + 150 * ((c shr 8) and 0xFF) + 29 * (c and 0xFF)) shr 8
        }
        // Everything in proportion to a 640-pixel picture.
        val scale = max(width, height) / 640f
        val fineRadius = max(1, scale.roundToInt())
        val broadRadius = max(2, (3.5f * scale).roundToInt())
        luma.copyInto(fine, 0, 0, n)
        boxBlur(fine, tmp, width, height, fineRadius)
        luma.copyInto(broad, 0, 0, n)
        boxBlur(broad, tmp, width, height, broadRadius)
        boxBlur(broad, tmp, width, height, broadRadius)
        val period = max(3f, 4.5f * scale)
        val reliefStep = max(1, scale.roundToInt())
        val grainCell = max(1, (1.5f * scale).roundToInt())

        for (y in 0 until height) {
            for (x in 0 until width) {
                val i = y * width + x
                val light = broad[i] / 255f
                // Ink lines: where the picture is darker than around it (a difference of blurs).
                val ink = ((broad[i] - fine[i] - INK_START) / INK_RANGE).coerceIn(0f, 1f)
                // Hatching in the shade: one way in half shade, crossed in deep shade.
                val dark = 1f - light
                val hatch = max(
                    stripe((x + y) / period, (dark - 0.35f) * 1.4f),
                    stripe((x - y) / period, (dark - 0.62f) * 1.8f),
                )
                // Raised where the light falls from the top left.
                val x0 = (x - reliefStep).coerceAtLeast(0); val y0 = (y - reliefStep).coerceAtLeast(0)
                val x1 = (x + reliefStep).coerceAtMost(width - 1); val y1 = (y + reliefStep).coerceAtMost(height - 1)
                val relief = (fine[y1 * width + x1] - fine[y0 * width + x0]) / 255f
                // Gold leaf's grain: a fine speckle and a coarser mottling.
                val grain = (noise(x, y) - 0.5f) * 0.08f + (noise(x / grainCell + 7919, y / grainCell) - 0.5f) * 0.1f

                var tone = 0.6f + 0.4f * light
                tone *= 1f - 0.85f * max(ink, 0.6f * hatch)
                tone += relief * RELIEF + grain
                out[i] = gold[(tone * 255f).roundToInt().coerceIn(0, 255)]
            }
        }
    }

    companion object {
        private const val INK_START = 3f
        private const val INK_RANGE = 12f
        private const val RELIEF = 1.4f

        /** Ink to gold leaf to highlight, from tone 0 to 255. */
        private val gold: IntArray = run {
            val stops = arrayOf(
                0f to intArrayOf(48, 28, 6),
                0.35f to intArrayOf(122, 78, 14),
                0.62f to intArrayOf(205, 148, 28),
                0.82f to intArrayOf(238, 190, 52),
                1f to intArrayOf(255, 232, 140),
            )
            IntArray(256) { v ->
                val t = v / 255f
                val upper = stops.indexOfFirst { it.first >= t }.coerceAtLeast(1)
                val (t0, c0) = stops[upper - 1]
                val (t1, c1) = stops[upper]
                val k = ((t - t0) / (t1 - t0)).coerceIn(0f, 1f)
                fun mix(ch: Int) = (c0[ch] + (c1[ch] - c0[ch]) * k).roundToInt()
                (0xFF shl 24) or (mix(0) shl 16) or (mix(1) shl 8) or mix(2)
            }
        }

        /** How much of a hatching line covers [u] (in line periods), for lines [width] of a period wide. */
        private fun stripe(u: Float, width: Float): Float {
            if (width <= 0f) return 0f
            val f = u - kotlin.math.floor(u)
            val fromMiddle = abs(f - 0.5f) * 2f
            // Soft edged, so the lines aren't jagged.
            return ((width.coerceAtMost(0.9f) - fromMiddle) / 0.25f + 0.5f).coerceIn(0f, 1f)
        }

        /** A steady random number in 0..1 for the point (x, y). */
        private fun noise(x: Int, y: Int): Float {
            var h = x * 374761393 + y * 668265263
            h = (h xor (h ushr 13)) * 1274126177
            h = h xor (h ushr 16)
            return (h and 0xFFFF) / 65535f
        }
    }
}

/** Averages each of [values] with its neighbors within [radius], across then down, using [tmp]. */
internal fun boxBlur(values: IntArray, tmp: IntArray, width: Int, height: Int, radius: Int) {
    val span = 2 * radius + 1
    for (y in 0 until height) {
        val row = y * width
        var sum = 0
        for (dx in -radius..radius) sum += values[row + dx.coerceIn(0, width - 1)]
        for (x in 0 until width) {
            tmp[row + x] = sum / span
            sum += values[row + (x + radius + 1).coerceAtMost(width - 1)] - values[row + (x - radius).coerceAtLeast(0)]
        }
    }
    for (x in 0 until width) {
        var sum = 0
        for (dy in -radius..radius) sum += tmp[dy.coerceIn(0, height - 1) * width + x]
        for (y in 0 until height) {
            values[y * width + x] = sum / span
            sum += tmp[(y + radius + 1).coerceAtMost(height - 1) * width + x] - tmp[(y - radius).coerceAtLeast(0) * width + x]
        }
    }
}
