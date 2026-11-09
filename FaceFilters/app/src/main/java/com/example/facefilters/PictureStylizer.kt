package com.example.facefilters

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Draws pictures with a [PictureFilter] (a cartoon, an engraving), at a size where its lines read well and it runs
 * fast enough for live video. Keeps the last one, so the same frame drawn twice (on the screen
 * and in a video) is only worked on once. Not thread-safe: use one per thread.
 */
class PictureStylizer(private val filter: PictureFilter, private val maxSide: Int = 640) {
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private var small: Bitmap? = null
    // Two, used in turn: the screen may still be showing the last one while the next is made.
    private val outs = arrayOfNulls<Bitmap>(2)
    private var next = 0
    private var out: Bitmap? = null
    private var pixels = IntArray(0)
    private var result = IntArray(0)
    private var lastSource: Bitmap? = null
    private var lastGeneration = -1

    /** The drawing of [source], and how to place it where [source] would go by [sourceToView]. */
    fun stylize(source: Bitmap, sourceToView: Matrix): Pair<Bitmap, Matrix> {
        val cartoon = cartoonOf(source)
        val placed = Matrix().apply {
            setScale(source.width / cartoon.width.toFloat(), source.height / cartoon.height.toFloat())
            postConcat(sourceToView)
        }
        return cartoon to placed
    }

    private fun cartoonOf(source: Bitmap): Bitmap {
        val cached = out
        if (cached != null && source === lastSource && source.generationId == lastGeneration) return cached
        val scale = min(1f, maxSide / max(source.width, source.height).toFloat())
        val w = (source.width * scale).roundToInt().coerceAtLeast(1)
        val h = (source.height * scale).roundToInt().coerceAtLeast(1)
        val shrunk = small?.takeIf { it.width == w && it.height == h }
            ?: Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { small = it }
        Canvas(shrunk).drawBitmap(source, Matrix().apply { setScale(scale, scale) }, paint)
        if (pixels.size != w * h) {
            pixels = IntArray(w * h)
            result = IntArray(w * h)
        }
        shrunk.getPixels(pixels, 0, w, 0, 0, w, h)
        filter.apply(pixels, w, h, result)
        val target = outs[next]?.takeIf { it.width == w && it.height == h }
            ?: Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { outs[next] = it }
        next = 1 - next
        target.setPixels(result, 0, w, 0, 0, w, h)
        out = target
        lastSource = source
        lastGeneration = source.generationId
        return target
    }

    companion object {
        /** A full-size drawing of [photo] by [filter], made at a size where its lines are in proportion. */
        fun stylizePhoto(filter: PictureFilter, photo: Bitmap): Bitmap {
            val (cartoon, _) = PictureStylizer(filter, maxSide = 1400).stylize(photo, Matrix())
            return Bitmap.createScaledBitmap(cartoon, photo.width, photo.height, true)
        }
    }
}
