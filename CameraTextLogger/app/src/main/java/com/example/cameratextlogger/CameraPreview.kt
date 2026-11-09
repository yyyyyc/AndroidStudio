package com.example.cameratextlogger

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.RectF
import android.util.Log
import android.util.Size
import android.view.ScaleGestureDetector
import android.widget.Toast
import androidx.camera.core.Camera
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.core.ConcurrentCamera.SingleCameraConfig
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.annotation.OptIn
import androidx.camera.view.PreviewView
import androidx.camera.view.TransformExperimental
import androidx.camera.view.transform.CoordinateTransform
import androidx.camera.view.transform.OutputTransform
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size as CanvasSize
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.view.doOnLayout
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.util.Locale
import java.util.concurrent.Executors

// The one thread that reads text, for camera and screen sessions alike. Lives as long as the
// process: frames still being recognized when a session stops post their results here, and a
// shut-down executor would reject them.
internal val analysisExecutor = Executors.newSingleThreadExecutor()

private val MarkColor = Color(0x66FFEB3B)

/** Where [boxes], in an upright camera frame, appear on the preview. Main thread only. */
@OptIn(TransformExperimental::class)
private fun PreviewView.map(boxes: List<HighlightBox>, frame: OutputTransform): List<RectF> {
    val preview = outputTransform ?: return emptyList() // Not showing the camera yet.
    val transform = CoordinateTransform(frame, preview)
    return boxes.map { box ->
        RectF(box.left.toFloat(), box.top.toFloat(), box.right.toFloat(), box.bottom.toFloat())
            .also(transform::mapRect)
    }
}

/** One camera's preview, the camera once bound, and the found text marked on it. */
private class CameraPane(context: Context) {
    val previewView = PreviewView(context)
    var camera by mutableStateOf<Camera?>(null)
    var marks by mutableStateOf<List<RectF>>(emptyList())
}

/** A back and a front camera this phone can run at the same time, or null if it can't. */
private fun ProcessCameraProvider.frontAndBack(): Pair<CameraInfo, CameraInfo>? {
    val pair = availableConcurrentCameraInfos.firstOrNull { infos ->
        infos.any { it.lensFacing == CameraSelector.LENS_FACING_BACK } &&
            infos.any { it.lensFacing == CameraSelector.LENS_FACING_FRONT }
    } ?: return null
    return pair.first { it.lensFacing == CameraSelector.LENS_FACING_BACK } to
        pair.first { it.lensFacing == CameraSelector.LENS_FACING_FRONT }
}

/**
 * Shows the back camera, or with [bothCameras] the back and front cameras one above the other,
 * and passes the text in their frames to [onLines] on a background thread. The user can zoom the
 * back camera by pinching or with the slider. Text matching [highlight] is marked on the picture
 * after each scan. Phones that can't run two cameras at once show the back camera alone.
 */
@Composable
fun CameraPreview(
    language: RecognitionLanguage,
    readBy: ReadBy,
    intervalMs: Long,
    highlight: FindPattern?,
    bothCameras: Boolean,
    onLines: (List<String>, FrameCapture) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val back = remember { CameraPane(context) }
    val front = remember { CameraPane(context) }
    // Kept across rotation, which rebinds the camera at 1x.
    var zoomRatio by rememberSaveable { mutableFloatStateOf(1f) }

    // Whether to show both cameras, known once CameraX has started. The previews are laid out
    // only then: their size sets what the cameras crop to.
    var showBoth by remember { mutableStateOf<Boolean?>(null) }
    DisposableEffect(bothCameras) {
        val providerFuture = ProcessCameraProvider.getInstance(context)
        var disposed = false
        providerFuture.addListener({
            if (disposed) return@addListener
            val supported = bothCameras && providerFuture.get().frontAndBack() != null
            if (bothCameras && !supported) {
                Toast.makeText(
                    context,
                    "This phone can't use both cameras at once: using the back camera",
                    Toast.LENGTH_LONG,
                ).show()
            }
            showBoth = supported
        }, ContextCompat.getMainExecutor(context))
        onDispose { disposed = true }
    }

    fun setZoom(ratio: Float) {
        val state = back.camera?.cameraInfo?.zoomState?.value ?: return
        zoomRatio = ratio.coerceIn(state.minZoomRatio, state.maxZoomRatio)
        back.camera?.cameraControl?.setZoomRatio(zoomRatio)
    }

    val both = showBoth
    DisposableEffect(lifecycleOwner, language, readBy, intervalMs, highlight, both) {
        if (both == null) return@DisposableEffect onDispose {}
        val mainExecutor = ContextCompat.getMainExecutor(context)
        val panes = if (both) listOf(back, front) else listOf(back)
        val analyzers = panes.map { pane ->
            val onPositions = highlight?.let { pattern ->
                { lines: List<TextLine>, frame: OutputTransform ->
                    val boxes = highlightBoxes(lines, pattern, readBy)
                    mainExecutor.execute { pane.marks = pane.previewView.map(boxes, frame) }
                }
            }
            TextAnalyzer(
                context.applicationContext, analysisExecutor, language, readBy, intervalMs,
                onPositions, onLines,
            )
        }
        val providerFuture = ProcessCameraProvider.getInstance(context)
        var disposed = false

        fun useCases(pane: CameraPane, analyzer: TextAnalyzer): UseCaseGroup {
            // A preview is scaled past its edges to fill them. The faster SurfaceView can't be
            // clipped to them, so with two previews it would draw over the other and the log.
            pane.previewView.implementationMode = if (both) {
                PreviewView.ImplementationMode.COMPATIBLE
            } else {
                PreviewView.ImplementationMode.PERFORMANCE
            }
            val preview = Preview.Builder().build().also {
                it.surfaceProvider = pane.previewView.surfaceProvider
            }
            // The 640x480 default is too coarse to read small text, like a license plate a few
            // car lengths away. Tesseract is slow, so it gets fewer pixels than ML Kit.
            val size = if (language.tesseractLanguages == null) Size(1920, 1440) else Size(1280, 960)
            val resolution = ResolutionSelector.Builder()
                .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                .setResolutionStrategy(
                    ResolutionStrategy(size, ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)
                )
                .build()
            val analysis = ImageAnalysis.Builder()
                .setResolutionSelector(resolution)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also { it.setAnalyzer(analysisExecutor, analyzer) }
            // Both cropped to what the preview shows, so text positions in a frame can be
            // mapped onto the preview.
            val group = UseCaseGroup.Builder().addUseCase(preview).addUseCase(analysis)
            pane.previewView.viewPort?.let(group::setViewPort)
            return group.build()
        }

        fun bind() {
            if (disposed) return
            try {
                val provider = providerFuture.get()
                provider.unbindAll()
                val cameras = provider.frontAndBack()
                if (both && cameras != null) {
                    val bound = provider.bindToLifecycle(
                        listOf(
                            SingleCameraConfig(
                                cameras.first.cameraSelector, useCases(back, analyzers[0]), lifecycleOwner
                            ),
                            SingleCameraConfig(
                                cameras.second.cameraSelector, useCases(front, analyzers[1]), lifecycleOwner
                            ),
                        )
                    )
                    back.camera = bound.cameras[0]
                    front.camera = bound.cameras[1]
                } else {
                    back.camera = provider.bindToLifecycle(
                        lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, useCases(back, analyzers[0])
                    )
                }
                setZoom(zoomRatio)
            } catch (e: Exception) {
                Log.e(TAG, "Could not start camera", e)
            }
        }

        // The previews' viewports are known once they have a size.
        providerFuture.addListener({
            panes.last().previewView.doOnLayout { back.previewView.doOnLayout { bind() } }
        }, mainExecutor)

        onDispose {
            disposed = true
            panes.forEach {
                it.camera = null
                it.marks = emptyList()
            }
            if (providerFuture.isDone) providerFuture.get().unbindAll()
            analyzers.forEach { it.close() }
        }
    }

    Column(modifier.background(Color.Black)) {
        if (both != null) {
            CameraPaneView(
                back,
                label = if (both) "Back" else null,
                zoomRatio = zoomRatio,
                onZoom = ::setZoom,
                modifier = Modifier.fillMaxWidth().weight(1f),
            )
            if (both) {
                CameraPaneView(
                    front,
                    label = "Front",
                    zoomRatio = null,
                    onZoom = {},
                    modifier = Modifier.fillMaxWidth().weight(1f).padding(top = 2.dp),
                )
            }
        }
    }
}

/** A camera's preview with its marks, and for [zoomRatio] not null, pinch and slider zoom. */
@SuppressLint("ClickableViewAccessibility") // Pinch zoom; the slider is the accessible control.
@Composable
private fun CameraPaneView(
    pane: CameraPane,
    label: String?,
    zoomRatio: Float?,
    onZoom: (Float) -> Unit,
    modifier: Modifier,
) {
    val context = LocalContext.current
    val currentZoom by rememberUpdatedState(zoomRatio)
    val currentOnZoom by rememberUpdatedState(onZoom)
    val pinch = remember {
        ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                currentZoom?.let { currentOnZoom(it * detector.scaleFactor) }
                return true
            }
        })
    }
    // Clipped: a pane wider than the camera's picture shows its middle, scaled past the edges.
    Box(modifier.clipToBounds()) {
        AndroidView(
            factory = {
                pane.previewView.apply {
                    setOnTouchListener { _, event -> pinch.onTouchEvent(event) }
                }
            },
            modifier = Modifier.fillMaxSize(),
        )
        // Draws nothing that takes touches, so pinching still reaches the preview.
        Canvas(Modifier.fillMaxSize().clipToBounds()) {
            val padding = 4.dp.toPx()
            for (mark in pane.marks) {
                drawRoundRect(
                    color = MarkColor,
                    topLeft = Offset(mark.left - padding, mark.top - padding),
                    size = CanvasSize(mark.width() + 2 * padding, mark.height() + 2 * padding),
                    cornerRadius = CornerRadius(padding),
                )
            }
        }
        if (label != null) {
            Text(
                label,
                color = Color.White,
                fontSize = 12.sp,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(8.dp)
                    .background(Color(0x99000000))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
        val zoomState = pane.camera?.cameraInfo?.zoomState?.value
        if (zoomRatio != null && zoomState != null && zoomState.maxZoomRatio > zoomState.minZoomRatio) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(Color(0x66000000))
                    .padding(horizontal = 12.dp),
            ) {
                Text(
                    String.format(Locale.US, "%.1fx", zoomRatio),
                    color = Color.White,
                    modifier = Modifier.padding(end = 8.dp),
                )
                Slider(
                    value = zoomRatio,
                    onValueChange = onZoom,
                    valueRange = zoomState.minZoomRatio..zoomState.maxZoomRatio,
                )
            }
        }
    }
}
