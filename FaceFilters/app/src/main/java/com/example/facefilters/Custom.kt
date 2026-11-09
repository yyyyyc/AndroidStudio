package com.example.facefilters

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import kotlin.math.max

/**
 * A sticker the user added: a picture, drawn on a face over the rectangle from ([left], [top]),
 * [width] by [height], in the face's units (see [FacePose]).
 */
data class CustomSticker(
    val id: String,
    val name: String,
    val left: Float,
    val top: Float,
    val width: Float,
    val height: Float,
) {
    val key get() = "sticker.$id"

    companion object {
        /** A picture from the gallery covers the face at first; the user moves it from there. */
        fun forPicture(id: String, name: String, picture: Bitmap): CustomSticker {
            val width = 2.6f
            val height = width * picture.height / picture.width
            return CustomSticker(id, name, -width / 2, 0.5f - height / 2, width, height)
        }
    }
}

/** A sticker to draw: built in, or the user's own with its picture. */
sealed interface Sticker {
    /** What its adjustment is kept under. */
    val key: String
    val isNone: Boolean
}

data class BuiltInSticker(val filter: Filter) : Sticker {
    // Just the name, as before custom stickers, so adjustments made then are kept.
    override val key get() = filter.name
    override val isNone get() = filter == Filter.None
}

data class CustomStickerChoice(val info: CustomSticker, val picture: Bitmap) : Sticker {
    override val key get() = info.key
    override val isNone get() = false
}

/**
 * Keeps the user's stickers and shapes, in the app's own storage: a list in index.json, and each
 * sticker's picture as a PNG beside it. Slow: call off the main thread.
 */
class CustomStore(context: Context) {
    private val dir = File(context.filesDir, "custom").apply { mkdirs() }
    private val index = File(dir, "index.json")

    /**
     * Another person's face, from a photo: its mesh, as the detector found it in the photo.
     * [detector] is which detector that was: meshes only line up point for point with the same.
     */
    class SavedFace(
        val id: String,
        val name: String,
        val points: FloatArray,
        val counts: IntArray,
        val detector: Int = CURRENT_DETECTOR,
    )

    class Contents(val stickers: List<CustomSticker>, val shapes: List<CustomShape>, val faces: List<SavedFace>)

    fun load(): Contents {
        if (!index.isFile) return Contents(emptyList(), emptyList(), emptyList())
        return try {
            val json = JSONObject(index.readText())
            val stickers = json.optJSONArray("stickers").objects().map {
                CustomSticker(
                    id = it.getString("id"),
                    name = it.getString("name"),
                    left = it.getDouble("left").toFloat(),
                    top = it.getDouble("top").toFloat(),
                    width = it.getDouble("width").toFloat(),
                    height = it.getDouble("height").toFloat(),
                )
            }
            val shapes = json.optJSONArray("shapes").objects().map { shape ->
                CustomShape(
                    id = shape.getString("id"),
                    name = shape.getString("name"),
                    pushes = shape.optJSONArray("pushes").objects().map {
                        Push(
                            u = it.getDouble("u").toFloat(),
                            v = it.getDouble("v").toFloat(),
                            du = it.getDouble("du").toFloat(),
                            dv = it.getDouble("dv").toFloat(),
                            radius = it.getDouble("radius").toFloat(),
                        )
                    },
                )
            }
            val faces = json.optJSONArray("faces").objects().map { face ->
                val counts = face.getJSONArray("counts")
                val points = face.getJSONArray("points")
                SavedFace(
                    id = face.getString("id"),
                    name = face.getString("name"),
                    points = FloatArray(points.length()) { points.getDouble(it).toFloat() },
                    counts = IntArray(counts.length()) { counts.getInt(it) },
                    detector = face.optInt("detector", ML_KIT_DETECTOR),
                )
            }
            Contents(stickers, shapes, faces)
        } catch (e: Exception) {
            Log.e("FaceFilters", "Could not read the custom stickers and shapes", e)
            Contents(emptyList(), emptyList(), emptyList())
        }
    }

    fun save(stickers: List<CustomSticker>, shapes: List<CustomShape>, faces: List<SavedFace>) {
        val json = JSONObject()
        json.put("stickers", JSONArray().apply {
            for (s in stickers) {
                put(JSONObject()
                    .put("id", s.id).put("name", s.name)
                    .put("left", s.left.toDouble()).put("top", s.top.toDouble())
                    .put("width", s.width.toDouble()).put("height", s.height.toDouble()))
            }
        })
        json.put("shapes", JSONArray().apply {
            for (shape in shapes) {
                put(JSONObject().put("id", shape.id).put("name", shape.name).put("pushes", JSONArray().apply {
                    for (p in shape.pushes) {
                        put(JSONObject()
                            .put("u", p.u.toDouble()).put("v", p.v.toDouble())
                            .put("du", p.du.toDouble()).put("dv", p.dv.toDouble())
                            .put("radius", p.radius.toDouble()))
                    }
                }))
            }
        })
        json.put("faces", JSONArray().apply {
            for (face in faces) {
                put(JSONObject().put("id", face.id).put("name", face.name).put("detector", face.detector)
                    .put("counts", JSONArray().apply { face.counts.forEach { put(it) } })
                    .put("points", JSONArray().apply { face.points.forEach { put(it.toDouble()) } }))
            }
        })
        // Written whole, then swapped in, so a crash can't leave half a list.
        val temp = File(dir, "index.json.tmp")
        temp.writeText(json.toString())
        if (!temp.renameTo(index)) {
            index.delete()
            temp.renameTo(index)
        }
    }

    fun savePicture(id: String, picture: Bitmap) {
        File(dir, "$id.png").outputStream().use { picture.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** Saves a photo, which keeps much smaller as a JPEG than a PNG. */
    fun savePhoto(id: String, photo: Bitmap) {
        File(dir, "$id.jpg").outputStream().use { photo.compress(Bitmap.CompressFormat.JPEG, 92, it) }
    }

    fun loadPicture(id: String): Bitmap? =
        listOf("png", "jpg").map { File(dir, "$id.$it") }.firstOrNull { it.isFile }?.let { BitmapFactory.decodeFile(it.path) }

    fun deletePicture(id: String) {
        File(dir, "$id.png").delete()
        File(dir, "$id.jpg").delete()
    }

    companion object {
        /** Faces found by ML Kit, before MediaPipe. */
        const val ML_KIT_DETECTOR = 1
        /** Faces found by MediaPipe (see [FaceTracker]). */
        const val CURRENT_DETECTOR = 2

        fun newId(): String = UUID.randomUUID().toString()
    }
}

private fun JSONArray?.objects(): List<JSONObject> =
    if (this == null) emptyList() else (0 until length()).map { getJSONObject(it) }

/**
 * The picture at [uri], upright, and no bigger than [maxSide] on its long side. Null if it
 * can't be read. Slow: call off the main thread.
 */
fun loadPicture(context: Context, uri: Uri, maxSide: Int = 1024): Bitmap? {
    val resolver = context.contentResolver
    return try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        val decoded = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
            ?: return null
        // Phones store photos sideways and say which way is up.
        val orientation = resolver.openInputStream(uri)?.use {
            ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        } ?: ExifInterface.ORIENTATION_NORMAL
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
        }
        val scale = maxSide.toFloat() / max(decoded.width, decoded.height)
        if (scale < 1f) matrix.postScale(scale, scale)
        if (matrix.isIdentity) {
            decoded
        } else {
            Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
                .also { if (it !== decoded) decoded.recycle() }
        }
    } catch (e: Exception) {
        Log.w("FaceFilters", "Could not read the picture", e)
        null
    }
}
