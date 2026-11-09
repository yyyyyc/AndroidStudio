package com.example.facefilters

/**
 * Looks that make a face seem older, younger, more feminine or more masculine, or the whole
 * picture drawn: as anime, a gold engraving or a pencil sketch.
 */
enum class Makeover(val label: String, val emoji: String) {
    None("None", "🚫"),
    Older("Older", "👴"),
    Younger("Younger", "👶"),
    Feminine("Feminine", "💄"),
    Masculine("Masculine", "🧔"),
    Anime("Anime", "🌸"),
    Gold("Gold", "🏅"),
    Sketch("Sketch", "✏️");

    val key get() = "makeover.$name"

    /** Whether the whole picture is drawn anew (see [pictureFilter]). */
    val stylizes get() = this == Anime || this == Gold || this == Sketch

    /** Whether the face is retouched on, which needs its whole outline. */
    val retouches get() = this != None && !stylizes

    /** Whether the face is reshaped (see [MakeoverWarp]). */
    val reshapes get() = this != None && this != Gold && this != Sketch

    /** A new filter that draws the picture for this look, or null if it doesn't. */
    fun pictureFilter(): PictureFilter? = when (this) {
        Anime -> AnimeFilter()
        Gold -> EngravingFilter()
        Sketch -> SketchFilter()
        else -> null
    }
}

/**
 * The reshaping part of a [Makeover]: pushes on the face (see [Push]) for the proportions that
 * read as age or gender. Older faces sag at the jaw and mouth; younger ones are rounder, with a
 * smaller chin and nose and bigger eyes; feminine ones have a slimmer jaw, fuller lips and higher
 * brows; masculine ones a wider jaw, bigger chin and lower brows.
 */
object MakeoverWarp {

    const val MAX_STRENGTH = 1.5f

    /** Moves [points], as x, y pairs, as [makeover] reshapes the faces at [poses]. */
    fun warp(points: FloatArray, poses: List<FacePose>, makeover: Makeover, strength: Float) {
        if (makeover == Makeover.None || strength <= 0f) return
        val k = strength.coerceAtMost(MAX_STRENGTH)
        for (pose in poses) {
            val single = listOf(pose)
            FaceWarp.warpPushes(points, single, pushes(makeover, pose), k)
            val eyes = when (makeover) {
                Makeover.Younger -> 0.45f
                Makeover.Feminine -> 0.25f
                Makeover.Anime -> 0.95f
                else -> 0f
            }
            if (eyes > 0f) FaceWarp.warp(points, single, Shape.BigEyes, eyes * k)
        }
    }

    /** The pushes for [makeover] on the face at [pose], whose mouth and nose they follow. */
    fun pushes(makeover: Makeover, pose: FacePose): List<Push> {
        val mouth = pose.mouthDrop
        val nose = pose.noseDrop
        val chin = mouth + 0.8f
        val jaw = mouth + 0.35f
        fun both(u: Float, v: Float, du: Float, dv: Float, r: Float) =
            listOf(Push(-u, v, -du, dv, r), Push(u, v, du, dv, r))
        return when (makeover) {
            Makeover.None, Makeover.Gold, Makeover.Sketch -> emptyList()
            Makeover.Older ->
                // Jowls and cheeks sag, the mouth's corners turn down, the nose grows longer.
                both(0.85f, jaw, 0.02f, 0.11f, 0.5f) +
                    both(0.65f, nose + 0.1f, 0f, 0.06f, 0.45f) +
                    both(0.33f, mouth, 0f, 0.05f, 0.2f) +
                    Push(0f, nose, 0f, 0.05f, 0.3f)
            Makeover.Younger ->
                // Rounder cheeks, a smaller chin and nose, a higher forehead.
                both(0.8f, jaw - 0.15f, 0.08f, -0.02f, 0.55f) +
                    Push(0f, chin, 0f, -0.14f, 0.6f) +
                    both(0.16f, nose - 0.05f, -0.04f, -0.02f, 0.22f) +
                    Push(0f, -0.9f, 0f, -0.08f, 0.7f)
            Makeover.Feminine ->
                // A slimmer jaw and smaller chin, fuller lips, a narrower nose, higher brows.
                both(0.9f, jaw, -0.11f, -0.02f, 0.6f) +
                    Push(0f, chin, 0f, -0.07f, 0.45f) +
                    Push(0f, mouth - 0.07f, 0f, -0.035f, 0.18f) +
                    Push(0f, mouth + 0.07f, 0f, 0.035f, 0.18f) +
                    both(0.16f, nose - 0.05f, -0.04f, 0f, 0.2f) +
                    both(0.5f, -0.3f, 0f, -0.05f, 0.28f)
            Makeover.Masculine ->
                // A wider jaw, a bigger chin, a wider nose, lower brows.
                both(0.9f, jaw, 0.12f, 0.02f, 0.6f) +
                    Push(0f, chin, 0f, 0.08f, 0.5f) +
                    both(0.16f, nose - 0.05f, 0.04f, 0f, 0.2f) +
                    both(0.5f, -0.3f, 0f, 0.05f, 0.28f)
            Makeover.Anime ->
                // A V-shaped jaw and small pointed chin, a tiny nose and mouth, a big forehead.
                both(0.9f, jaw, -0.15f, -0.04f, 0.6f) +
                    Push(0f, chin, 0f, -0.1f, 0.45f) +
                    both(0.16f, nose - 0.05f, -0.05f, -0.02f, 0.2f) +
                    Push(0f, nose, 0f, -0.06f, 0.25f) +
                    both(0.33f, mouth, -0.06f, 0f, 0.18f) +
                    Push(0f, -0.9f, 0f, -0.1f, 0.75f)
        }
    }
}
