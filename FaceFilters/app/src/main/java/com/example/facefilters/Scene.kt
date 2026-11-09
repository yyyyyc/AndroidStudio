package com.example.facefilters

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import kotlin.math.max
import kotlin.math.min

/** Everything drawn on faces: the sticker, shape, blended face and look, as the user set them. */
data class Look(
    val sticker: Sticker,
    val adjustment: Adjustment,
    val reshape: Reshape,
    val strength: Float,
    val blend: BlendFace?,
    val blendAmount: Float,
    val keepOwnFeatures: Boolean,
    val makeover: Makeover,
    val makeoverStrength: Float,
) {
    /** Whether the face itself is moved about: by a shape or a look. */
    val reshapes get() = !reshape.isNone || makeover.reshapes

    /** Whether the face itself is changed, not just drawn on. */
    val changesFaces get() = reshapes || blend != null || makeover.stylizes

    /** Whether the main face's whole outline is needed. */
    val needsMesh get() = blend != null || makeover.retouches

    companion object {
        val NONE = Look(
            BuiltInSticker(Filter.None), Adjustment(), BuiltInReshape(Shape.None), 1f, null, 0.9f, true, Makeover.None, 1f,
        )
    }
}

/**
 * Draws a [Look] on faces: the picture reshaped, the look's retouching, the blended face, and the
 * sticker. Used for the camera, its videos, and screen mode. Not thread-safe: use one per thread.
 */
class SceneRenderer(warpCells: Int = 96) {
    private val warper = MeshWarper(cellsOnLongSide = warpCells)
    private val blender = FaceBlender()
    private val makeoverRenderer = MakeoverRenderer()
    private val stickers = FilterRenderer()
    private val picturePaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val stylizers = HashMap<Makeover, PictureStylizer>()
    private val clip = Path()
    private val oval = RectF()
    private val turn = Matrix()

    /** How [look] reshapes points on the faces at [poses]. */
    fun warp(look: Look, poses: List<FacePose>): (FloatArray) -> Unit = { points ->
        look.reshape.warp(points, poses, look.strength)
        MakeoverWarp.warp(points, poses, look.makeover, look.makeoverStrength)
    }

    /**
     * Draws the faces' changes on [canvas]: [picture], placed by [bitmapToView], reshaped; then the
     * look's retouching and the blended face on the main face, whose mesh is [mesh].
     *
     * @param alwaysPicture draw the picture even when the look doesn't reshape it.
     * @param pictureUnderBlend draw the picture under a blended face, so they match exactly.
     * @param clipToFaces draw only the picture around the faces; elsewhere the screen shows.
     */
    fun drawFaces(
        canvas: Canvas,
        picture: Bitmap?,
        bitmapToView: Matrix?,
        look: Look,
        poses: List<FacePose>,
        mesh: LiveMesh,
        skin: SkinSource?,
        alwaysPicture: Boolean,
        pictureUnderBlend: Boolean,
        clipToFaces: Boolean,
    ) {
        if (picture == null || bitmapToView == null) return
        val warp = warp(look, poses)
        // Anime, gold, sketch: the picture itself, drawn anew.
        val stylizer = look.makeover.pictureFilter()?.let { filter -> stylizers.getOrPut(look.makeover) { PictureStylizer(filter) } }
        val (shown, shownToView) = stylizer?.stylize(picture, bitmapToView) ?: (picture to bitmapToView)
        if (alwaysPicture || look.reshapes || stylizer != null || (pictureUnderBlend && look.blend != null)) {
            canvas.save()
            if (clipToFaces) {
                clip.reset()
                for (pose in poses) clip.addPath(faceOval(pose))
                canvas.clipPath(clip)
            }
            if (look.reshapes) warper.draw(canvas, shown, shownToView, warp) else canvas.drawBitmap(shown, shownToView, picturePaint)
            canvas.restore()
        }
        val layout = mesh.layout ?: return
        val points = mesh.points() ?: return
        // Moved with the face they cover.
        if (look.reshapes) warp(points)
        if (look.makeover.retouches) {
            poses.maxByOrNull { it.eyeDistance }?.let { pose ->
                makeoverRenderer.draw(canvas, look.makeover, look.makeoverStrength, layout, points, pose, warp, skin)
            }
        }
        look.blend?.takeIf { layout.matches(it.layout) }?.let { donor ->
            blender.draw(canvas, donor, points, mesh.gains(donor), look.blendAmount, look.keepOwnFeatures)
        }
    }

    fun drawStickers(canvas: Canvas, poses: List<FacePose>, look: Look) =
        stickers.draw(canvas, poses, look.sticker, look.adjustment)

    /**
     * An oval around a face, big enough for anything a shape or look moves: the whole head, and
     * the neck below the chin.
     */
    private fun faceOval(pose: FacePose): Path {
        val middle = pose.toScreen(0f, FACE_MIDDLE)
        val unit = pose.eyeDistance
        oval.set(middle.x - FACE_HALF_WIDTH * unit, middle.y - FACE_HALF_HEIGHT * unit,
            middle.x + FACE_HALF_WIDTH * unit, middle.y + FACE_HALF_HEIGHT * unit)
        return Path().apply {
            addOval(oval, Path.Direction.CW)
            turn.setRotate(pose.angleDeg, middle.x, middle.y)
            transform(turn)
        }
    }

    companion object {
        private const val FACE_MIDDLE = 0.3f
        private const val FACE_HALF_WIDTH = 2.1f
        private const val FACE_HALF_HEIGHT = 3.0f

        /** The screen area [faceOval] covers for the faces at [poses], or null for none. */
        fun facesArea(poses: List<FacePose>): RectF? {
            if (poses.isEmpty()) return null
            var left = Float.MAX_VALUE
            var top = Float.MAX_VALUE
            var right = -Float.MAX_VALUE
            var bottom = -Float.MAX_VALUE
            for (pose in poses) {
                val middle = pose.toScreen(0f, FACE_MIDDLE)
                // Turned any way, an oval stays within the circle of its longer radius.
                val r = FACE_HALF_HEIGHT * pose.eyeDistance
                left = min(left, middle.x - r)
                top = min(top, middle.y - r)
                right = max(right, middle.x + r)
                bottom = max(bottom, middle.y + r)
            }
            return RectF(left, top, right, bottom)
        }
    }
}
