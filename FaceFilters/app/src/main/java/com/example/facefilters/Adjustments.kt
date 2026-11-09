package com.example.facefilters

import android.content.Context

/**
 * How the user has moved, resized and turned a sticker from where it is drawn at first.
 *
 * @property dx how far right across the face it is moved, in units (see [FacePose]).
 * @property dy how far down the face it is moved, in units; negative is up.
 * @property size how much bigger it is drawn.
 * @property rotationDeg how far it is turned clockwise.
 */
data class Adjustment(
    val dx: Float = 0f,
    val dy: Float = 0f,
    val size: Float = 1f,
    val rotationDeg: Float = 0f,
) {
    fun moved(byX: Float, byY: Float) = copy(
        dx = (dx + byX).coerceIn(-MAX_MOVE, MAX_MOVE),
        dy = (dy + byY).coerceIn(-MAX_MOVE, MAX_MOVE),
    )

    fun resized(by: Float) = copy(size = (size * by).coerceIn(MIN_SIZE, MAX_SIZE))

    fun rotated(byDeg: Float): Adjustment {
        var angle = (rotationDeg + byDeg) % 360f
        if (angle > 180f) angle -= 360f
        if (angle < -180f) angle += 360f
        return copy(rotationDeg = angle)
    }

    companion object {
        const val MAX_MOVE = 1.5f
        const val MIN_SIZE = 0.3f
        const val MAX_SIZE = 3f
    }
}

/**
 * Keeps each sticker's [Adjustment] and each shape's strength across launches, under the
 * sticker's or shape's key.
 */
class AdjustmentStore(context: Context) {
    private val prefs = context.getSharedPreferences("adjustments", Context.MODE_PRIVATE)

    fun adjustment(key: String) = Adjustment(
        dx = prefs.getFloat("$key.dx", 0f),
        dy = prefs.getFloat("$key.dy", 0f),
        size = prefs.getFloat("$key.size", 1f),
        rotationDeg = prefs.getFloat("$key.rotation", 0f),
    )

    fun save(key: String, adjustment: Adjustment) {
        prefs.edit()
            .putFloat("$key.dx", adjustment.dx)
            .putFloat("$key.dy", adjustment.dy)
            .putFloat("$key.size", adjustment.size)
            .putFloat("$key.rotation", adjustment.rotationDeg)
            .apply()
    }

    /** How strongly a shape reshapes the face: 1 is normal (see [FaceWarp]). */
    fun strength(key: String, default: Float = 1f) = prefs.getFloat("$key.strength", default)

    fun flag(key: String, default: Boolean) = prefs.getBoolean("$key.flag", default)

    fun saveFlag(key: String, value: Boolean) {
        prefs.edit().putBoolean("$key.flag", value).apply()
    }

    fun saveStrength(key: String, strength: Float) {
        prefs.edit().putFloat("$key.strength", strength).apply()
    }

    /** Forgets everything kept for a sticker or shape that was deleted. */
    fun forget(key: String) {
        prefs.edit().apply {
            for (name in listOf("dx", "dy", "size", "rotation", "strength")) remove("$key.$name")
        }.apply()
    }
}
