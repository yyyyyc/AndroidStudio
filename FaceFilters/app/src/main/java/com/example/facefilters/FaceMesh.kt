package com.example.facefilters

import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * The points of a face mesh, in the order of the face detector's contours (see [CONTOURS]), plus
 * rings derived from them that let the blended face fade out smoothly:
 * - inside the face's outline, so it fades into the user's face toward the jaw and hairline;
 * - around each eye and around the mouth, so the user's own eyes and mouth can show through.
 *
 * [counts] is how many points each contour has. The detector always gives the same numbers, but
 * they're kept rather than assumed, and meshes only fit together if their counts match.
 */
class MeshLayout(val counts: IntArray) {
    init {
        require(counts.size == CONTOURS) { "Expected $CONTOURS contours, got ${counts.size}" }
    }

    private val starts = IntArray(CONTOURS).also {
        var at = 0
        for (c in 0 until CONTOURS) {
            it[c] = at
            at += counts[c]
        }
    }

    /** How many points come from the detector. */
    val detectedSize = counts.sum()

    private val innerStart = detectedSize
    private val leftEyeRingStart = innerStart + counts[FACE]
    private val rightEyeRingStart = leftEyeRingStart + counts[LEFT_EYE]
    private val lipRingStart = rightEyeRingStart + counts[RIGHT_EYE]

    /** How many points [expand] gives. */
    val size = lipRingStart + counts[UPPER_LIP_TOP] + counts[LOWER_LIP_BOTTOM]

    fun range(contour: Int) = starts[contour] until starts[contour] + counts[contour]

    fun matches(other: MeshLayout) = counts.contentEquals(other.counts)

    /** The detected points [detected], as x, y pairs, with the fading rings added after them. */
    fun expand(detected: FloatArray): FloatArray {
        require(detected.size == detectedSize * 2)
        val out = detected.copyOf(size * 2)
        ring(detected, out, listOf(FACE), innerStart, FACE_INNER)
        ring(detected, out, listOf(LEFT_EYE), leftEyeRingStart, EYE_RING)
        ring(detected, out, listOf(RIGHT_EYE), rightEyeRingStart, EYE_RING)
        ring(detected, out, listOf(UPPER_LIP_TOP, LOWER_LIP_BOTTOM), lipRingStart, LIP_RING)
        return out
    }

    /** Copies the points of [contours] to [out] at [at], scaled by [scale] around their middle. */
    private fun ring(detected: FloatArray, out: FloatArray, contours: List<Int>, at: Int, scale: Float) {
        var cx = 0f
        var cy = 0f
        var n = 0
        for (c in contours) for (i in range(c)) {
            cx += detected[2 * i]
            cy += detected[2 * i + 1]
            n++
        }
        if (n == 0) return
        cx /= n
        cy /= n
        var o = at
        for (c in contours) for (i in range(c)) {
            out[2 * o] = cx + (detected[2 * i] - cx) * scale
            out[2 * o + 1] = cy + (detected[2 * i + 1] - cy) * scale
            o++
        }
    }

    /**
     * How opaque the blended face is at each point: 0 on the face's outline, so it fades in from
     * the edge, and with [keepOwnFeatures], 0 on the eyes and lips too, so the user's own show.
     */
    fun alphas(keepOwnFeatures: Boolean): FloatArray {
        val a = FloatArray(size) { 1f }
        for (i in range(FACE)) a[i] = 0f
        if (keepOwnFeatures) {
            for (c in listOf(LEFT_EYE, RIGHT_EYE, UPPER_LIP_TOP, UPPER_LIP_BOTTOM, LOWER_LIP_TOP, LOWER_LIP_BOTTOM)) {
                for (i in range(c)) a[i] = 0f
            }
        }
        return a
    }

    /** The points well inside the face, for measuring its color. */
    fun skinPoints(): IntArray {
        val skip = HashSet<Int>()
        for (c in listOf(FACE, LEFT_EYE, RIGHT_EYE, UPPER_LIP_TOP, UPPER_LIP_BOTTOM, LOWER_LIP_TOP, LOWER_LIP_BOTTOM,
            LEFT_EYEBROW_TOP, LEFT_EYEBROW_BOTTOM, RIGHT_EYEBROW_TOP, RIGHT_EYEBROW_BOTTOM)) {
            skip += range(c)
        }
        return (0 until size).filter { it !in skip }.toIntArray()
    }

    /** The eyes, brows and lips: kept as they are when the skin is retouched. */
    private val features = listOf(LEFT_EYE, RIGHT_EYE, UPPER_LIP_TOP, UPPER_LIP_BOTTOM, LOWER_LIP_TOP, LOWER_LIP_BOTTOM,
        LEFT_EYEBROW_TOP, LEFT_EYEBROW_BOTTOM, RIGHT_EYEBROW_TOP, RIGHT_EYEBROW_BOTTOM)

    /** How much of the skin at each point is retouched: all but the outline, eyes, brows and lips. */
    fun skinAlphas(): FloatArray {
        val a = FloatArray(size) { 1f }
        for (c in features + FACE) for (i in range(c)) a[i] = 0f
        return a
    }

    /** The point on the inner ring just inside outline point [i]. */
    fun innerOf(i: Int) = innerStart + (i - range(FACE).first)

    /**
     * How much to scale red, green and blue at each point, so a face whose colors are [donor]
     * takes on the light and skin tone of the face colored [user] under it, place by place: one
     * side lit and the other in shadow, say. Both give 3 numbers per point, NaN where unknown.
     *
     * The eyes, brows and lips keep the face's own colors, scaled like its skin overall; the
     * outline, which may be against the background, borrows from just inside it.
     */
    fun lightGains(user: FloatArray, donor: FloatArray): FloatArray {
        val gains = FloatArray(size * 3) { Float.NaN }
        val skin = skinPoints()
        val mean = FloatArray(3)
        val counts = IntArray(3)
        for (i in skin) for (c in 0 until 3) {
            val u = user[3 * i + c]
            val d = donor[3 * i + c]
            if (u.isNaN() || d.isNaN()) continue
            val g = (u / d.coerceAtLeast(8f)).coerceIn(MIN_GAIN, MAX_GAIN)
            gains[3 * i + c] = g
            mean[c] += g
            counts[c]++
        }
        for (c in 0 until 3) mean[c] = if (counts[c] == 0) 1f else (mean[c] / counts[c]).coerceIn(MIN_GAIN, MAX_GAIN)
        for (c in features) for (i in range(c)) for (k in 0 until 3) gains[3 * i + k] = mean[k]
        for (i in range(FACE)) for (k in 0 until 3) gains[3 * i + k] = gains[3 * innerOf(i) + k]
        for (i in 0 until size * 3) if (gains[i].isNaN()) gains[i] = mean[i % 3]
        return gains
    }

    companion object {
        const val MIN_GAIN = 0.35f
        const val MAX_GAIN = 2.8f

        // The detector's contours, in the order points are kept.
        const val FACE = 0
        const val LEFT_EYEBROW_TOP = 1
        const val LEFT_EYEBROW_BOTTOM = 2
        const val RIGHT_EYEBROW_TOP = 3
        const val RIGHT_EYEBROW_BOTTOM = 4
        const val LEFT_EYE = 5
        const val RIGHT_EYE = 6
        const val UPPER_LIP_TOP = 7
        const val UPPER_LIP_BOTTOM = 8
        const val LOWER_LIP_TOP = 9
        const val LOWER_LIP_BOTTOM = 10
        const val NOSE_BRIDGE = 11
        const val NOSE_BOTTOM = 12
        const val LEFT_CHEEK = 13
        const val RIGHT_CHEEK = 14
        const val CONTOURS = 15

        /** How many points ML Kit gives for each contour. */
        val STANDARD_COUNTS = intArrayOf(36, 5, 5, 5, 5, 16, 16, 11, 9, 9, 9, 2, 3, 1, 1)

        /** The fade runs from the outline to this much of the way in. */
        const val FACE_INNER = 0.84f
        const val EYE_RING = 1.4f
        const val LIP_RING = 1.25f
    }
}

/**
 * Splits the points [xy], as x, y pairs, into triangles (Delaunay: as close to equal-sided as can
 * be), as triples of point indices. A point within [minDistance] of an earlier one is left out:
 * contours share their end points.
 */
fun triangulate(xy: FloatArray, minDistance: Float = 0.5f): IntArray {
    val n = xy.size / 2
    val used = ArrayList<Int>()
    for (i in 0 until n) {
        val x = xy[2 * i]
        val y = xy[2 * i + 1]
        if (used.none { hypot(xy[2 * it] - x, xy[2 * it + 1] - y) < minDistance }) used += i
    }
    if (used.size < 3) return IntArray(0)

    val px = DoubleArray(n + 3)
    val py = DoubleArray(n + 3)
    var minX = Double.MAX_VALUE
    var minY = Double.MAX_VALUE
    var maxX = -Double.MAX_VALUE
    var maxY = -Double.MAX_VALUE
    for (i in 0 until n) {
        px[i] = xy[2 * i].toDouble()
        py[i] = xy[2 * i + 1].toDouble()
        minX = min(minX, px[i]); maxX = max(maxX, px[i])
        minY = min(minY, py[i]); maxY = max(maxY, py[i])
    }
    // A triangle around everything to start from.
    val size = max(maxX - minX, maxY - minY).coerceAtLeast(1.0) * 20
    val midX = (minX + maxX) / 2
    val midY = (minY + maxY) / 2
    px[n] = midX - size; py[n] = midY - size
    px[n + 1] = midX + size; py[n + 1] = midY - size
    px[n + 2] = midX; py[n + 2] = midY + size

    class Tri(val a: Int, val b: Int, val c: Int) {
        val cx: Double
        val cy: Double
        val r2: Double

        init {
            val ax = px[a]; val ay = py[a]
            val bx = px[b]; val by = py[b]
            val qx = px[c]; val qy = py[c]
            val d = 2 * (ax * (by - qy) + bx * (qy - ay) + qx * (ay - by))
            if (kotlin.math.abs(d) < 1e-12) {
                // In a line: take it as an endless circle, so the next point replaces it.
                cx = (ax + bx + qx) / 3; cy = (ay + by + qy) / 3; r2 = Double.MAX_VALUE
            } else {
                val a2 = ax * ax + ay * ay
                val b2 = bx * bx + by * by
                val c2 = qx * qx + qy * qy
                cx = (a2 * (by - qy) + b2 * (qy - ay) + c2 * (ay - by)) / d
                cy = (a2 * (qx - bx) + b2 * (ax - qx) + c2 * (bx - ax)) / d
                r2 = (ax - cx) * (ax - cx) + (ay - cy) * (ay - cy)
            }
        }

        fun circleHas(p: Int): Boolean {
            val dx = px[p] - cx
            val dy = py[p] - cy
            return dx * dx + dy * dy < r2
        }
    }

    var triangles = mutableListOf(Tri(n, n + 1, n + 2))
    for (p in used) {
        val bad = triangles.filter { it.circleHas(p) }
        // The hole's edge: edges of exactly one bad triangle.
        val edges = HashMap<Long, IntArray>()
        val seen = HashMap<Long, Int>()
        for (t in bad) {
            for ((u, v) in listOf(t.a to t.b, t.b to t.c, t.c to t.a)) {
                val key = min(u, v).toLong() shl 32 or max(u, v).toLong()
                seen[key] = (seen[key] ?: 0) + 1
                edges[key] = intArrayOf(u, v)
            }
        }
        triangles = triangles.filterNot { it in bad }.toMutableList()
        for ((key, edge) in edges) {
            if (seen[key] == 1) triangles += Tri(edge[0], edge[1], p)
        }
    }
    val out = ArrayList<Int>()
    for (t in triangles) {
        if (t.a < n && t.b < n && t.c < n) {
            out += t.a; out += t.b; out += t.c
        }
    }
    return out.toIntArray()
}
