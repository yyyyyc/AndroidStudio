package com.example.facefilters

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

class FaceMeshTest {

    private val layout = MeshLayout(MeshLayout.STANDARD_COUNTS)

    /** A made-up face: each contour a small circle at a place on a face 200 px wide. */
    private fun fakeFace(): FloatArray {
        val centers = listOf(
            0f to 0f to 100f, // Outline.
            -40f to -45f to 12f, -40f to -38f to 12f, 40f to -45f to 12f, 40f to -38f to 12f, // Brows.
            -40f to -20f to 14f, 40f to -20f to 14f, // Eyes.
            0f to 45f to 28f, 0f to 47f to 18f, 0f to 49f to 18f, 0f to 51f to 28f, // Lips.
            0f to 0f to 8f, 0f to 20f to 10f, // Nose.
            -55f to 15f to 1f, 55f to 15f to 1f, // Cheeks.
        )
        val out = ArrayList<Float>()
        for ((c, contour) in centers.withIndex()) {
            val (xy, r) = contour
            val n = MeshLayout.STANDARD_COUNTS[c]
            for (i in 0 until n) {
                val t = 2 * Math.PI * i / n + c * 0.1
                out += xy.first + (r * cos(t)).toFloat()
                out += xy.second + (r * 1.2f * sin(t)).toFloat()
            }
        }
        return out.toFloatArray()
    }

    @Test
    fun expandAddsTheFadingRings() {
        val detected = fakeFace()
        val expanded = layout.expand(detected)
        assertEquals(layout.size * 2, expanded.size)
        // The detected points are kept as they are.
        assertArrayEquals(detected, expanded.copyOf(detected.size), 0f)
        // The inner ring is inside the outline: 86% of the way out from the middle.
        val firstOutline = layout.range(MeshLayout.FACE).first
        val ring = layout.detectedSize
        assertEquals(detected[2 * firstOutline] * MeshLayout.FACE_INNER, expanded[2 * ring], 0.01f)
    }

    @Test
    fun outlineIsClearAndFeaturesOnlyWhenKept() {
        val all = layout.alphas(keepOwnFeatures = false)
        val keep = layout.alphas(keepOwnFeatures = true)
        val eye = layout.range(MeshLayout.LEFT_EYE).first
        val nose = layout.range(MeshLayout.NOSE_BOTTOM).first
        for (i in layout.range(MeshLayout.FACE)) {
            assertEquals(0f, all[i], 0f)
            assertEquals(0f, keep[i], 0f)
        }
        assertEquals(1f, all[eye], 0f)
        assertEquals(0f, keep[eye], 0f)
        assertEquals(1f, keep[nose], 0f)
        // The rings around the features stay opaque, so the fade is short.
        assertEquals(1f, keep[layout.size - 1], 0f)
    }

    @Test
    fun skinPointsLeaveOutEdgesEyesAndLips() {
        val skin = layout.skinPoints().toSet()
        assertTrue(layout.range(MeshLayout.FACE).none { it in skin })
        assertTrue(layout.range(MeshLayout.LEFT_EYE).none { it in skin })
        assertTrue(layout.range(MeshLayout.UPPER_LIP_TOP).none { it in skin })
        assertTrue(layout.range(MeshLayout.NOSE_BOTTOM).all { it in skin })
    }

    @Test
    fun squareSplitsInTwo() {
        val triangles = triangulate(floatArrayOf(0f, 0f, 10f, 0f, 10f, 10f, 0f, 10f))
        assertEquals(6, triangles.size)
    }

    @Test
    fun gridTrianglesCoverItExactly() {
        // A 5 by 4 grid of points, 1 apart: 4 by 3 squares, two triangles each, area 12.
        val points = ArrayList<Float>()
        for (y in 0 until 4) for (x in 0 until 5) {
            points += x.toFloat() + y * 0.001f // Not quite square, so the split is certain.
            points += y.toFloat()
        }
        val xy = points.toFloatArray()
        val triangles = triangulate(xy)
        assertEquals(24 * 3, triangles.size)
        var area = 0.0
        for (t in triangles.indices step 3) {
            val (a, b, c) = Triple(triangles[t], triangles[t + 1], triangles[t + 2])
            area += abs(
                (xy[2 * b] - xy[2 * a]) * (xy[2 * c + 1] - xy[2 * a + 1]) -
                    (xy[2 * c] - xy[2 * a]) * (xy[2 * b + 1] - xy[2 * a + 1])
            ) / 2.0
        }
        assertEquals(12.0, area, 0.05)
    }

    @Test
    fun repeatedPointsAreLeftOut() {
        val triangles = triangulate(floatArrayOf(0f, 0f, 10f, 0f, 0f, 10f, 10.1f, 0.1f))
        assertEquals(3, triangles.size)
        assertTrue(3 !in triangles.toList())
    }

    @Test
    fun aWholeFaceMeshTriangulates() {
        val expanded = layout.expand(fakeFace())
        val triangles = triangulate(expanded)
        assertTrue(triangles.size / 3 > layout.size)
        assertTrue(triangles.all { it in 0 until layout.size })
    }

    @Test
    fun lightIsMatchedPlaceByPlace() {
        val n = layout.size
        // The user's face is lit from the left: bright on the left half, dark on the right.
        val expanded = layout.expand(fakeFace())
        val user = FloatArray(n * 3) { val x = expanded[2 * (it / 3)]; if (x < 0) 200f else 80f }
        val donor = FloatArray(n * 3) { 120f }
        val gains = layout.lightGains(user, donor)
        val nose = layout.range(MeshLayout.NOSE_BOTTOM).first // The middle point is at x = 0: left of it lit.
        val leftCheek = layout.range(MeshLayout.LEFT_CHEEK).first
        val rightCheek = layout.range(MeshLayout.RIGHT_CHEEK).first
        assertTrue(gains[3 * leftCheek] > 1.5f)
        assertTrue(gains[3 * rightCheek] < 0.7f)
        // Outline points borrow from just inside.
        val outline = layout.range(MeshLayout.FACE).first
        assertEquals(gains[3 * layout.innerOf(outline)], gains[3 * outline], 0f)
        // Eyes keep the overall scale, not their own.
        val eye = layout.range(MeshLayout.LEFT_EYE).first
        assertEquals(gains[3 * layout.range(MeshLayout.RIGHT_EYE).first], gains[3 * eye], 0f)
        assertTrue(nose >= 0)
    }

    @Test
    fun lightGainsStayInRangeAndFillUnknowns() {
        val n = layout.size
        val user = FloatArray(n * 3) { if (it % 7 == 0) Float.NaN else 255f }
        val donor = FloatArray(n * 3) { 10f }
        val gains = layout.lightGains(user, donor)
        assertTrue(gains.all { !it.isNaN() && it in MeshLayout.MIN_GAIN..MeshLayout.MAX_GAIN })
    }

    @Test
    fun retouchingSkipsEyesBrowsLipsAndOutline() {
        val a = layout.skinAlphas()
        for (c in listOf(MeshLayout.FACE, MeshLayout.LEFT_EYE, MeshLayout.LEFT_EYEBROW_TOP, MeshLayout.UPPER_LIP_TOP)) {
            assertTrue(layout.range(c).all { a[it] == 0f })
        }
        assertTrue(layout.range(MeshLayout.NOSE_BOTTOM).all { a[it] == 1f })
    }
}
