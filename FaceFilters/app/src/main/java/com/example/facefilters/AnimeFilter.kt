package com.example.facefilters

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Turns a picture into a cartoon, like a frame of anime: skin and texture smoothed away, colors
 * brighter and in a few flat bands of light and shade, and dark ink lines along strong edges.
 * Works on ARGB pixels, so it runs anywhere: the live picture, photos, videos and screen mode.
 * Not thread-safe: it reuses its buffers. Use one per thread.
 */
class AnimeFilter : PictureFilter {
    private var size = 0
    private var r = IntArray(0)
    private var g = IntArray(0)
    private var b = IntArray(0)
    private var tmp = IntArray(0)
    private var luma = IntArray(0)

    /** Writes the cartoon of the [width] by [height] picture [pixels] to [out]. */
    override fun apply(pixels: IntArray, width: Int, height: Int, out: IntArray) {
        val n = width * height
        require(pixels.size >= n && out.size >= n)
        if (size != n) {
            size = n
            r = IntArray(n); g = IntArray(n); b = IntArray(n); tmp = IntArray(n); luma = IntArray(n)
        }
        for (i in 0 until n) {
            val c = pixels[i]
            r[i] = (c shr 16) and 0xFF
            g[i] = (c shr 8) and 0xFF
            b[i] = c and 0xFF
        }
        // Smoothed twice: flat areas for the color bands, and edges only where they're strong.
        for (channel in listOf(r, g, b)) repeat(2) { boxBlur(channel, tmp, width, height, RADIUS) }
        for (i in 0 until n) luma[i] = (77 * r[i] + 150 * g[i] + 29 * b[i]) shr 8

        for (y in 0 until height) {
            for (x in 0 until width) {
                val i = y * width + x
                // Brighter colors.
                val mean = (r[i] + g[i] + b[i]) / 3f
                var rr = mean + (r[i] - mean) * SATURATION
                var gg = mean + (g[i] - mean) * SATURATION
                var bb = mean + (b[i] - mean) * SATURATION
                // Light in a few flat bands, with soft steps between them.
                val l = luma[i].toFloat()
                val banded = (l / 255f * (BANDS - 1)).roundToInt() / (BANDS - 1f) * 255f
                val target = (banded * 0.8f + l * 0.2f) * 0.92f + 22f
                val k = target / l.coerceAtLeast(1f)
                rr *= k; gg *= k; bb *= k
                // Ink where the light changes sharply.
                val ink = edge(x, y, width, height)
                rr += (INK_R - rr) * ink
                gg += (INK_G - gg) * ink
                bb += (INK_B - bb) * ink
                out[i] = (0xFF shl 24) or (clamp(rr) shl 16) or (clamp(gg) shl 8) or clamp(bb)
            }
        }
    }

    /** How strongly the light changes at (x, y), from 0 (smooth) to 1 (a line to ink). */
    private fun edge(x: Int, y: Int, width: Int, height: Int): Float {
        if (x == 0 || y == 0 || x == width - 1 || y == height - 1) return 0f
        val i = y * width + x
        val tl = luma[i - width - 1]; val t = luma[i - width]; val tr = luma[i - width + 1]
        val l = luma[i - 1]; val rt = luma[i + 1]
        val bl = luma[i + width - 1]; val bt = luma[i + width]; val br = luma[i + width + 1]
        val gx = (tr + 2 * rt + br) - (tl + 2 * l + bl)
        val gy = (bl + 2 * bt + br) - (tl + 2 * t + tr)
        val magnitude = abs(gx) + abs(gy)
        return ((magnitude - EDGE_START) / EDGE_RANGE).coerceIn(0f, 1f)
    }

    private fun clamp(v: Float) = v.roundToInt().coerceIn(0, 255)

    companion object {
        private const val RADIUS = 2
        private const val SATURATION = 1.35f
        private const val BANDS = 5
        private const val EDGE_START = 70f
        private const val EDGE_RANGE = 90f
        private const val INK_R = 38f
        private const val INK_G = 28f
        private const val INK_B = 42f
    }
}
