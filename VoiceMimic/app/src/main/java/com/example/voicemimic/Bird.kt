package com.example.voicemimic

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import kotlin.math.cos
import kotlin.math.sin

/** Colours of a race bird; [GREEN] matches the app icon's parrot. */
class BirdPalette(
    val body: Color, val outline: Color, val belly: Color, val wing: Color,
    val crest: List<Color>, val beak: Color = Color(0xFFFFB300), val beakLine: Color = Color(0xFFD97A00),
) {
    companion object {
        val GREEN = BirdPalette(Color(0xFF35C46A), Color(0xFF1E8E4A), Color(0xFF9BE15D), Color(0xFF239B52),
            listOf(Color(0xFFFF3B4E), Color(0xFFFF8A00), Color(0xFFFFD60A)))
        val BLUE = BirdPalette(Color(0xFF3BA7F0), Color(0xFF1C6FB0), Color(0xFF9AD8FF), Color(0xFF2A85D0),
            listOf(Color(0xFFFF4FD8), Color(0xFFB266FF), Color(0xFFFFD60A)))
    }
}

/**
 * Draws a cartoon parrot walking to the right. [x] is the bird's centre, [ground] the y its feet stand on,
 * [height] its size in px. [walk] advances the leg cycle (radians), [singing] opens the beak.
 */
fun DrawScope.drawParrot(x: Float, ground: Float, height: Float, p: BirdPalette, walk: Float, singing: Boolean, time: Float) {
    val s = height / 100f
    withTransform({
        translate(x - 50f * s, ground - 100f * s)
        scale(s, s, pivot = Offset.Zero)
    }) {
        val bob = -kotlin.math.abs(sin(walk)) * 3f
        val legA = sin(walk) * 0.55f
        val ink = Color(0xFF1B1B2F)

        // legs (behind the body)
        fun leg(hipX: Float, angle: Float, shade: Float) {
            val c = Color(0xFFFF9800).copy(alpha = shade)
            val kx = hipX + sin(angle) * 16f; val ky = 80f + cos(angle) * 16f
            drawLine(c, Offset(hipX, 78f), Offset(kx, ky + 2f), 4f, StrokeCap.Round)
            drawLine(c, Offset(kx, ky + 2f), Offset(kx + 7f, ky + 3f), 4f, StrokeCap.Round)
            drawLine(c, Offset(kx, ky + 2f), Offset(kx - 3f, ky + 4f), 3f, StrokeCap.Round)
        }
        leg(47f, -legA, 0.75f)

        translate(0f, bob) {
            // tail
            val tail = Path().apply { moveTo(32f, 62f); lineTo(4f, 80f); lineTo(10f, 87f); lineTo(38f, 72f); close() }
            drawPath(tail, p.wing); drawPath(tail, p.outline, style = Stroke(2f))
            val tail2 = Path().apply { moveTo(32f, 66f); lineTo(8f, 90f); lineTo(16f, 93f); lineTo(40f, 74f); close() }
            drawPath(tail2, p.crest[0]); drawPath(tail2, p.outline, style = Stroke(1.5f))

            // body + belly
            drawOval(p.body, Offset(26f, 42f), Size(48f, 42f))
            drawOval(p.outline, Offset(26f, 42f), Size(48f, 42f), style = Stroke(2f))
            drawOval(p.belly, Offset(44f, 58f), Size(26f, 24f))

            // crest feathers
            val crest = listOf(
                Path().apply { moveTo(58f, 25f); cubicTo(50f, 14f, 46f, 8f, 50f, 2f); cubicTo(56f, 9f, 61f, 16f, 63f, 24f); close() },
                Path().apply { moveTo(63f, 23f); cubicTo(61f, 14f, 63f, 6f, 69f, 3f); cubicTo(70f, 10f, 69f, 17f, 67f, 23f); close() },
                Path().apply { moveTo(67f, 24f); cubicTo(70f, 17f, 75f, 12f, 81f, 11f); cubicTo(78f, 18f, 73f, 23f, 70f, 26f); close() },
            )
            crest.forEachIndexed { i, c -> drawPath(c, p.crest[i]) }

            // head
            drawCircle(p.body, 17f, Offset(66f, 38f))
            drawCircle(p.outline, 17f, Offset(66f, 38f), style = Stroke(2f))
            drawCircle(Color(0xFFFF7AA8), 3.2f, Offset(63f, 46f))

            // beak: upper hook + lower jaw that opens while singing
            val open = if (singing) 8f + sin(time * 18f) * 4f else 0f
            rotate(open, pivot = Offset(78f, 45f)) {
                val lower = Path().apply { moveTo(77f, 45f); cubicTo(82f, 46f, 86f, 47f, 88f, 49f); cubicTo(85f, 53f, 79f, 53f, 77f, 49f); close() }
                drawPath(lower, Color(0xFFFF9800)); drawPath(lower, p.beakLine, style = Stroke(1.2f))
            }
            if (singing) drawCircle(Color(0xFF7A1F2B), 2.5f, Offset(81f, 47f))
            val upper = Path().apply { moveTo(77f, 36f); cubicTo(86f, 33f, 93f, 39f, 92f, 47f); cubicTo(90f, 50f, 88f, 50f, 87f, 47f); cubicTo(84f, 45f, 80f, 45f, 77f, 46f); close() }
            drawPath(upper, p.beak); drawPath(upper, p.beakLine, style = Stroke(1.4f))

            // googly eyes (mismatched, like the icon)
            val look = sin(time * 1.7f) * 0.8f
            drawCircle(Color.White, 6f, Offset(60f, 35f)); drawCircle(ink, 6f, Offset(60f, 35f), style = Stroke(1.5f))
            drawCircle(ink, 2.8f, Offset(61.5f + look, 36f))
            drawCircle(Color.White, 7.5f, Offset(72f, 32f)); drawCircle(ink, 7.5f, Offset(72f, 32f), style = Stroke(1.5f))
            drawCircle(ink, 3.4f, Offset(74f + look, 32.5f))
            drawCircle(Color.White, 1.1f, Offset(75f + look, 31.3f))

            // wing, flapping while walking or singing
            val flap = (if (singing) 14f else 4f) * sin(time * if (singing) 14f else 3f) - 8f
            rotate(flap, pivot = Offset(46f, 56f)) {
                val wing = Path().apply { moveTo(44f, 54f); cubicTo(58f, 48f, 70f, 58f, 66f, 70f); cubicTo(56f, 74f, 44f, 68f, 44f, 54f); close() }
                drawPath(wing, p.wing); drawPath(wing, p.outline, style = Stroke(1.6f))
            }
        }
        leg(55f, legA, 1f)
    }
}

/** A chequered finish flag whose pole stands at [x] on [ground]. */
fun DrawScope.drawFinishFlag(x: Float, ground: Float, height: Float, time: Float) {
    drawLine(Color(0xFFE2DEF5), Offset(x, ground), Offset(x, ground - height), 4f, StrokeCap.Round)
    val w = height * 0.55f; val h = height * 0.36f
    val cols = 4; val rows = 3
    for (r in 0 until rows) for (c in 0 until cols) {
        val wave = sin(time * 5f + c * 0.9f) * h * 0.06f
        drawRect(if ((r + c) % 2 == 0) Color.White else Color(0xFF1B1B2F),
            Offset(x + c * w / cols, ground - height + r * h / rows + wave), Size(w / cols + 0.5f, h / rows + 0.5f))
    }
}

