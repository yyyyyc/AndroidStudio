package com.example.facefilters

import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke as StrokeStyle
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/** A camera frame held still for editing on, with the face in it, in the screen's pixels. */
class FrozenFrame(val bitmap: Bitmap, val bitmapToView: Matrix, val pose: FacePose)

/** One line drawn by finger, in screen pixels. */
class DrawnStroke(val color: Int, val widthPx: Float) {
    val points = mutableListOf<Offset>()
}

/**
 * Turns [strokes] drawn on the face at [pose] into a sticker picture and where it goes on a
 * face, or null if nothing was drawn. The picture is laid out along the face, so it sits the
 * same way on any face, however tilted.
 */
fun drawingToSticker(strokes: List<DrawnStroke>, pose: FacePose, id: String, name: String): Pair<Bitmap, CustomSticker>? {
    val drawn = strokes.filter { it.points.isNotEmpty() }
    if (drawn.isEmpty()) return null
    val unit = pose.eyeDistance
    var left = Float.MAX_VALUE
    var top = Float.MAX_VALUE
    var right = -Float.MAX_VALUE
    var bottom = -Float.MAX_VALUE
    val local = drawn.map { stroke ->
        val half = stroke.widthPx / unit / 2
        stroke.points.map { p ->
            pose.toLocal(p.x, p.y).also {
                left = min(left, it.x - half)
                top = min(top, it.y - half)
                right = max(right, it.x + half)
                bottom = max(bottom, it.y + half)
            }
        }
    }
    val width = right - left
    val height = bottom - top
    // As sharp as it was on the screen, within a size that keeps the file small.
    val pixelsPerUnit = min(max(unit, 200f), 1024f / max(width, height))
    val bitmap = Bitmap.createBitmap(
        ceil(width * pixelsPerUnit).toInt().coerceAtLeast(1),
        ceil(height * pixelsPerUnit).toInt().coerceAtLeast(1),
        Bitmap.Config.ARGB_8888,
    )
    val canvas = android.graphics.Canvas(bitmap)
    canvas.scale(pixelsPerUnit, pixelsPerUnit)
    canvas.translate(-left, -top)
    val paint = strokePaint()
    val path = Path()
    for ((stroke, points) in drawn.zip(local)) {
        paint.color = stroke.color
        paint.strokeWidth = stroke.widthPx / unit
        path.toStroke(points.map { Offset(it.x, it.y) })
        canvas.drawPath(path, paint)
    }
    return bitmap to CustomSticker(id, name, left, top, width, height)
}

private fun strokePaint() = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    style = Paint.Style.STROKE
    strokeCap = Paint.Cap.ROUND
    strokeJoin = Paint.Join.ROUND
}

/** Sets this path to a line through [points]; a single point makes a dot. */
private fun Path.toStroke(points: List<Offset>) {
    reset()
    val first = points.firstOrNull() ?: return
    moveTo(first.x, first.y)
    if (points.size == 1) lineTo(first.x + 0.01f, first.y)
    for (i in 1 until points.size) {
        // Smoothed: through the midpoints, curving at the points themselves.
        val a = points[i - 1]
        val b = points[i]
        quadTo(a.x, a.y, (a.x + b.x) / 2, (a.y + b.y) / 2)
    }
    points.last().let { lineTo(it.x, it.y) }
}

private val Palette = listOf(
    0xFFFFFFFF, 0xFF000000, 0xFFE53935, 0xFFFF9800, 0xFFFFEB3B,
    0xFF43A047, 0xFF1E88E5, 0xFF8E24AA, 0xFFFF80AB, 0xFF795548,
).map { it.toInt() }

/**
 * Lets the user draw on a still of their face, then gives the drawing to [onSave] to become a
 * sticker. Drawn on the face, it sits on any face the same way.
 */
@Composable
fun DrawEditor(frozen: FrozenFrame, onCancel: () -> Unit, onSave: (List<DrawnStroke>) -> Unit) {
    BackHandler(onBack = onCancel)
    val strokes = remember { mutableStateListOf<DrawnStroke>() }
    var color by remember { mutableIntStateOf(Palette[2]) }
    var width by remember { mutableFloatStateOf(14f) }
    // Bumped as a line is drawn, to redraw it.
    var drawing by remember { mutableIntStateOf(0) }
    val paint = remember { strokePaint() }
    val path = remember { Path() }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        Canvas(
            Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { start ->
                            strokes += DrawnStroke(color, width * density).apply { points += start }
                            drawing++
                        },
                        onDrag = { change, _ ->
                            strokes.lastOrNull()?.points?.add(change.position)
                            drawing++
                        },
                    )
                },
        ) {
            drawing // Read, so each new point redraws.
            drawIntoCanvas { canvas ->
                canvas.nativeCanvas.drawBitmap(frozen.bitmap, frozen.bitmapToView, null)
                for (stroke in strokes) {
                    paint.color = stroke.color
                    paint.strokeWidth = stroke.widthPx
                    path.toStroke(stroke.points)
                    canvas.nativeCanvas.drawPath(path, paint)
                }
            }
        }
        EditorHint("Draw on your face: it becomes a sticker that follows any face")
        EditorBar {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(bottom = 10.dp),
            ) {
                for (c in Palette) {
                    Box(
                        Modifier
                            .size(if (c == color) 30.dp else 24.dp)
                            .clip(CircleShape)
                            .background(Color(c))
                            .border(if (c == color) 3.dp else 1.dp, Color(0xFFFFD54F).takeIf { c == color } ?: Color.Gray, CircleShape)
                            .clickable { color = c },
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 24.dp)) {
                Text("Brush", color = Color.White, fontSize = 12.sp)
                Slider(
                    value = width,
                    onValueChange = { width = it },
                    valueRange = 3f..40f,
                    modifier = Modifier.padding(horizontal = 12.dp).weight(1f),
                )
                Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                    Canvas(Modifier.size((width).coerceAtMost(40f).dp)) { drawCircle(Color(color)) }
                }
            }
            EditorButtons(
                onCancel = onCancel,
                onUndo = { strokes.removeLastOrNull(); Unit }.takeIf { strokes.isNotEmpty() },
                onClear = { strokes.clear() }.takeIf { strokes.isNotEmpty() },
                onSave = { onSave(strokes.toList()) }.takeIf { strokes.isNotEmpty() },
            )
        }
    }
}

/**
 * Lets the user push and pull a still of their face around, seeing it change as they drag, then
 * gives the pushes to [onSave] to become a shape.
 */
@Composable
fun ReshapeEditor(frozen: FrozenFrame, warper: MeshWarper, onCancel: () -> Unit, onSave: (List<Push>) -> Unit) {
    BackHandler(onBack = onCancel)
    val pushes = remember { mutableStateListOf<Push>() }
    // The push being dragged, and where the finger is, in screen pixels.
    var pending by remember { mutableStateOf<Push?>(null) }
    var finger by remember { mutableStateOf<Offset?>(null) }
    var radius by remember { mutableFloatStateOf(0.6f) }
    val pose = frozen.pose
    val poses = remember(pose) { listOf(pose) }

    fun pushTo(start: Pt, at: Offset): Push {
        val end = pose.toLocal(at.x, at.y)
        var du = end.x - start.x
        var dv = end.y - start.y
        // As far as it can go without folding the picture.
        val length = kotlin.math.hypot(du, dv)
        val most = Push.MAX_MOVE * radius
        if (length > most) {
            du *= most / length
            dv *= most / length
        }
        return Push(start.x, start.y, du, dv, radius)
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        Canvas(
            Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    var start = Pt(0f, 0f)
                    detectDragGestures(
                        onDragStart = {
                            start = pose.toLocal(it.x, it.y)
                            finger = it
                        },
                        onDrag = { change, _ ->
                            finger = change.position
                            pending = pushTo(start, change.position)
                        },
                        onDragEnd = {
                            pending?.let { pushes += it }
                            pending = null
                            finger = null
                        },
                        onDragCancel = {
                            pending = null
                            finger = null
                        },
                    )
                },
        ) {
            val all = pushes.toList() + listOfNotNull(pending)
            drawIntoCanvas { canvas ->
                warper.draw(canvas.nativeCanvas, frozen.bitmap, frozen.bitmapToView) { points ->
                    FaceWarp.warpPushes(points, poses, all)
                }
            }
            // How much of the face a push moves.
            finger?.let {
                drawCircle(Color(0xAAFFD54F), radius * pose.eyeDistance, it, style = StrokeStyle(width = 3.dp.toPx()))
            }
        }
        EditorHint("Drag on your face to push it around")
        EditorBar {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 24.dp)) {
                Text("Push size", color = Color.White, fontSize = 12.sp)
                Slider(
                    value = radius,
                    onValueChange = { radius = it },
                    valueRange = 0.25f..1.4f,
                    modifier = Modifier.padding(start = 12.dp).weight(1f),
                )
            }
            EditorButtons(
                onCancel = onCancel,
                onUndo = { pushes.removeLastOrNull(); Unit }.takeIf { pushes.isNotEmpty() },
                onClear = { pushes.clear() }.takeIf { pushes.isNotEmpty() },
                onSave = { onSave(pushes.toList()) }.takeIf { pushes.isNotEmpty() },
            )
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.BoxScope.EditorHint(text: String) {
    Text(
        text,
        color = Color.White,
        fontSize = 14.sp,
        modifier = Modifier
            .align(Alignment.TopCenter)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(16.dp)
            .background(Color(0x99000000), RoundedCornerShape(16.dp))
            .padding(horizontal = 14.dp, vertical = 6.dp),
    )
}

@Composable
private fun androidx.compose.foundation.layout.BoxScope.EditorBar(content: @Composable () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .fillMaxWidth()
            .background(Color(0xB3000000))
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(vertical = 12.dp),
    ) { content() }
}

/** Cancel, Undo, Clear and Save; null actions show greyed out. */
@Composable
private fun EditorButtons(onCancel: () -> Unit, onUndo: (() -> Unit)?, onClear: (() -> Unit)?, onSave: (() -> Unit)?) {
    Row(
        horizontalArrangement = Arrangement.SpaceEvenly,
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
    ) {
        EditorButton("Cancel", onCancel)
        EditorButton("Undo", onUndo)
        EditorButton("Clear", onClear)
        EditorButton("Save", onSave, highlight = true)
    }
}

@Composable
private fun EditorButton(label: String, onClick: (() -> Unit)?, highlight: Boolean = false) {
    val enabled = onClick != null
    Text(
        label,
        color = when {
            !enabled -> Color(0x66FFFFFF)
            highlight -> Color.Black
            else -> Color.White
        },
        fontWeight = FontWeight.Bold,
        fontSize = 14.sp,
        modifier = Modifier
            .clip(RoundedCornerShape(18.dp))
            .background(if (highlight && enabled) Color(0xFFFFD54F) else Color(0x33FFFFFF))
            .clickable(enabled = enabled) { onClick?.invoke() }
            .padding(horizontal = 16.dp, vertical = 8.dp),
    )
}
