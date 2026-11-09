package com.example.facefilters

import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Turns a picture into a pencil sketch on paper: outlines where the light changes (the picture
 * "dodged" by a blur of itself, which leaves only edges), soft graphite shading in the shadows,
 * drawn in short diagonal strokes, and paper grain. Its strokes are sized to the picture and stay
 * in place from frame to frame, so videos don't flicker. Not thread-safe: use one per thread.
 */
class SketchFilter : PictureFilter {
    private var size = 0
    private var luma = IntArray(0)
    private var soft = IntArray(0)
    private var broad = IntArray(0)
    private var tmp = IntArray(0)

    override fun apply(pixels: IntArray, width: Int, height: Int, out: IntArray) {
        val n = width * height
        require(pixels.size >= n && out.size >= n)
        if (size != n) {
            size = n
            luma = IntArray(n); soft = IntArray(n); broad = IntArray(n); tmp = IntArray(n)
        }
        for (i in 0 until n) {
            val c = pixels[i]
            luma[i] = (77 * ((c shr 16) and 0xFF) + 150 * ((c shr 8) and 0xFF) + 29 * (c and 0xFF)) shr 8
        }
        // Everything in proportion to a 640-pixel picture.
        val scale = max(width, height) / 640f
        val lineRadius = max(2, (3f * scale).roundToInt())
        val shadeRadius = max(3, (8f * scale).roundToInt())
        luma.copyInto(soft, 0, 0, n)
        // Lightly smoothed first, so camera noise doesn't come out as specks.
        boxBlur(luma, tmp, width, height, max(1, (0.6f * scale).roundToInt()))
        repeat(2) { boxBlur(soft, tmp, width, height, lineRadius) }
        luma.copyInto(broad, 0, 0, n)
        repeat(2) { boxBlur(broad, tmp, width, height, shadeRadius) }
        val strokeLength = max(2, (5f * scale).roundToInt())
        val strokeWidth = max(1, (1.2f * scale).roundToInt())

        for (y in 0 until height) {
            for (x in 0 until width) {
                val i = y * width + x
                // Lines: dodging the picture by its blur leaves white, except at edges.
                val dodged = (luma[i] * 255f / (soft[i] + 1f)).coerceAtMost(255f) / 255f
                val line = dodged.pow(LINE_DARKNESS)
                // Shading: graphite in the shadows, in diagonal strokes of uneven pressure.
                val shade = ((1f - broad[i] / 255f) - 0.25f).coerceAtLeast(0f) * 1.1f
                val along = (x + y) / strokeLength
                val across = (x - y) / strokeWidth
                val pressure = 0.55f + 0.9f * noise(along, across)
                val graphite = (shade * pressure).coerceIn(0f, 0.75f)
                // Paper grain catches a little graphite.
                val grain = (noise(x + 7919, y) - 0.5f) * 0.05f
                val tone = (line * (1f - graphite) + grain).coerceIn(0f, 1f)
                out[i] = pencil[(tone * 255f).roundToInt()]
            }
        }
    }

    companion object {
        private const val LINE_DARKNESS = 2.4f

        /** Graphite to paper, from tone 0 to 255: grey with a hint of blue, on warm white. */
        private val pencil = IntArray(256) { v ->
            val t = v / 255f
            fun mix(dark: Int, light: Int) = (dark + (light - dark) * t).roundToInt()
            (0xFF shl 24) or (mix(46, 250) shl 16) or (mix(48, 247) shl 8) or mix(56, 240)
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
