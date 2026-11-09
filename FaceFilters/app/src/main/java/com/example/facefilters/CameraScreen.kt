package com.example.facefilters

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.PorterDuff
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.util.Size
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraEffect
import androidx.camera.core.CameraSelector
import androidx.camera.core.MirrorMode
import androidx.camera.effects.Frame
import androidx.camera.effects.OverlayEffect
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recording
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.video.AudioConfig
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ZoomState
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.view.CameraController
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

private const val TAG = "FaceFilters"

/** The most faces looked for: in the live picture, and in photos. */
private const val LIVE_FACES = 3
private const val PHOTO_FACES = 6

/** How many preview frames wait for their faces to be found: a fifth of a second at most. */
private const val OVERLAY_QUEUE = 6

/** What the camera screen works with while it is shown. */
private class Session(
    val analysisExecutor: ExecutorService,
    val photoExecutor: ExecutorService,
    private val makePhotoTracker: () -> FaceTracker,
) {
    private var photoTrackerOrNull: FaceTracker? = null

    /** Finds faces in photos. Made when first needed. Photo thread only. */
    val photoTracker: FaceTracker
        get() = photoTrackerOrNull ?: makePhotoTracker().also { photoTrackerOrNull = it }

    /** Photo thread only. */
    fun closePhotoTracker() {
        photoTrackerOrNull?.close()
        photoTrackerOrNull = null
    }
}

/** The editors that work on a still of the user's face. */
private enum class EditorKind { Draw, Reshape }

/** The live camera with the chosen filter drawn on every face, and buttons to take a photo. */
@Composable
fun CameraScreen() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val previewView = remember {
        PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER }
    }
    val controller = remember { LifecycleCameraController(context) }
    val smoother = remember { PoseSmoother() }
    val scene = remember { SceneRenderer() }
    val store = remember { AdjustmentStore(context.applicationContext) }
    val customStore = remember { CustomStore(context.applicationContext) }

    val buffers = remember { FrameBuffers() }
    val warper = remember { MeshWarper(cellsOnLongSide = 96) }
    // Whether the analyzer should hand over pictures, for drawing reshaped faces.
    val wantPictures = remember { AtomicBoolean(false) }
    // Whether it should find the main face's outline, for blending faces.
    val wantMesh = remember { AtomicBoolean(false) }
    val liveMesh = remember { LiveMesh() }
    // The user's face in the frame being shown, for retouching and matching light.
    val liveSkin = remember { object { var value: SkinSource? = null } }

    var session by remember { mutableStateOf<Session?>(null) }
    var poses by remember { mutableStateOf<List<FacePose>>(emptyList()) }
    // The camera picture drawn instead of the preview while a face is reshaped, and the one
    // drawn before it, whose bitmap may still be in use by the screen.
    var shownFrame by remember { mutableStateOf<FaceFrame?>(null) }
    var previousFrame by remember { mutableStateOf<FaceFrame?>(null) }

    // The user's own stickers and shapes, loaded once.
    var customStickers by remember { mutableStateOf<List<CustomStickerChoice>>(emptyList()) }
    var customShapes by remember { mutableStateOf<List<CustomShape>>(emptyList()) }
    var blendFaces by remember { mutableStateOf<List<BlendFace>>(emptyList()) }

    var tab by rememberSaveable { mutableStateOf(Tab.Stickers) }
    var stickerKey by rememberSaveable { mutableStateOf(Filter.Sunglasses.name) }
    var shapeKey by rememberSaveable { mutableStateOf(BuiltInReshape(Shape.None).key) }
    val stickers = remember(customStickers) { Filter.entries.map(::BuiltInSticker) + customStickers }
    val reshapes = remember(customShapes) { Shape.entries.map(::BuiltInReshape) + customShapes.map(::CustomReshape) }
    val sticker = stickers.firstOrNull { it.key == stickerKey } ?: BuiltInSticker(Filter.None)
    val reshape = reshapes.firstOrNull { it.key == shapeKey } ?: BuiltInReshape(Shape.None)
    var blendKey by rememberSaveable { mutableStateOf("") }
    val blend = blendFaces.firstOrNull { it.key == blendKey }?.takeIf { canBlendLive }
    var blendAmount by remember { mutableFloatStateOf(store.strength("blend", default = 0.9f)) }
    var keepOwnFeatures by remember { mutableStateOf(store.flag("blend.keep", default = true)) }
    var addingFace by remember { mutableStateOf(false) }
    // Bumped when a face is renamed: faces are kept as they are, so the list alone wouldn't change.
    var facesRenamed by remember { mutableIntStateOf(0) }
    var makeover by rememberSaveable { mutableStateOf(Makeover.None) }

    val strengths = remember { mutableStateMapOf<String, Float>() }
    val strength = strengths[reshape.key] ?: store.strength(reshape.key)
    val makeoverStrength = strengths[makeover.key] ?: store.strength(makeover.key)
    val adjustments = remember { mutableStateMapOf<String, Adjustment>() }
    val adjustment = adjustments[sticker.key] ?: store.adjustment(sticker.key)
    var adjustedCount by remember { mutableIntStateOf(0) }
    val currentLook by rememberUpdatedState(
        Look(sticker, adjustment, reshape, strength, blend, blendAmount, keepOwnFeatures, makeover, makeoverStrength)
    )
    var showAdjustment by remember { mutableStateOf(false) }

    var useFront by rememberSaveable { mutableStateOf(true) }
    var hasBothCameras by remember { mutableStateOf(false) }
    // The camera's zoom, and how far it can zoom: front cameras often can't, or only a little.
    var zoom by remember { mutableStateOf<ZoomState?>(null) }
    var saving by remember { mutableStateOf(false) }
    val flash = remember { Animatable(0f) }

    var captureMode by rememberSaveable { mutableStateOf(CaptureMode.Photo) }
    // The video being recorded, and when it started.
    var recorder by remember { mutableStateOf<VideoRecorder?>(null) }
    // Or, when filters are drawn on the camera's own frames, the camera's recording of them.
    var cameraRecording by remember { mutableStateOf<Recording?>(null) }
    val recording = recorder != null || cameraRecording != null
    var recordingSince by remember { mutableLongStateOf(0L) }
    var recordingSeconds by remember { mutableLongStateOf(0L) }
    var finishingVideo by remember { mutableStateOf(false) }

    // Waiting for a frame with a face to edit on, then editing on it.
    var waitingToFreeze by remember { mutableStateOf<EditorKind?>(null) }
    var editing by remember { mutableStateOf<Pair<EditorKind, FrozenFrame>?>(null) }
    // A custom sticker, shape or face the user is naming, and one they asked to delete, until
    // they confirm.
    var naming by remember { mutableStateOf<Any?>(null) }
    var toDelete by remember { mutableStateOf<Any?>(null) }
    // Hidden, the whole screen shows the camera.
    var controlsShown by rememberSaveable { mutableStateOf(true) }
    val screenModeOn by ScreenFilterService.running.collectAsState()
    // Whether filters can't be drawn on the camera's frames, and are drawn over the preview instead.
    var overlayBroken by remember { mutableStateOf(false) }
    // From the camera sensor to the preview, as of the last frame: for drawing on the frames.
    val overlaySensorToView = remember { object { var value: Matrix? = null } }

    LaunchedEffect(Unit) {
        val loaded = withContext(Dispatchers.IO) {
            val contents = customStore.load()
            val withPictures = contents.stickers.mapNotNull { info ->
                customStore.loadPicture(info.id)?.let { CustomStickerChoice(info, it) }
            }
            val pictures = contents.faces.mapNotNull { saved -> customStore.loadPicture(saved.id)?.let { saved to it } }
            // Faces found by the old detector are found again, so their points match the live ones.
            val old = pictures.any { it.first.detector != CustomStore.CURRENT_DETECTOR }
            val tracker = if (!old) null else runCatching { FaceTracker.create(context, video = false, maxFaces = 1) }
                .onFailure { Log.w(TAG, "Could not start face tracking", it) }
                .getOrNull()
            val faces = pictures.map { (saved, picture) ->
                val again = if (saved.detector == CustomStore.CURRENT_DETECTOR || tracker == null) null
                else runCatching { FaceTracker.findInPhoto(tracker, picture).maxByOrNull { it.size }?.meshPoints() }.getOrNull()
                if (again != null) BlendFace(saved.id, saved.name, picture, again, Landmarks.COUNTS)
                else BlendFace(saved.id, saved.name, picture, saved.points, saved.counts)
            }
            tracker?.close()
            Triple(withPictures, contents.shapes, faces) to old
        }
        customStickers = loaded.first.first
        customShapes = loaded.first.second
        blendFaces = loaded.first.third
        if (loaded.second) {
            val faces = blendFaces.map { CustomStore.SavedFace(it.id, it.name, it.detected, it.layout.counts) }
            withContext(Dispatchers.IO) { customStore.save(loaded.first.first.map { it.info }, loaded.first.second, faces) }
        }
    }

    fun saveCustom() {
        val stickerInfos = customStickers.map { it.info }
        val shapes = customShapes
        val faces = blendFaces.map { CustomStore.SavedFace(it.id, it.name, it.detected, it.layout.counts) }
        scope.launch(Dispatchers.IO) { customStore.save(stickerInfos, shapes, faces) }
    }

    /**
     * Draws faces as [look] has them on [canvas], in preview pixels: the camera's [picture],
     * reshaped and blended, then the stickers. Without [alwaysPicture], the picture is only drawn
     * when changed, and the preview under it shows otherwise.
     */
    fun drawScene(canvas: android.graphics.Canvas, picture: Bitmap?, bitmapToView: Matrix?, look: Look, alwaysPicture: Boolean) {
        scene.drawFaces(
            canvas, picture, bitmapToView, look, poses, liveMesh, liveSkin.value,
            alwaysPicture = alwaysPicture, pictureUnderBlend = true, clipToFaces = false,
        )
        scene.drawStickers(canvas, poses, look)
    }

    /** Draws the filters on a camera [frame], in its own pixels, for the preview and videos. */
    fun drawOverlay(frame: Frame) {
        val canvas = frame.overlayCanvas
        canvas.drawColor(android.graphics.Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        val toView = overlaySensorToView.value ?: return
        // Drawn in preview pixels, like everything else, then moved into the frame's.
        val viewToFrame = Matrix()
        if (!toView.invert(viewToFrame)) return
        viewToFrame.postConcat(frame.sensorToBufferTransform)
        canvas.save()
        canvas.concat(viewToFrame)
        val shown = shownFrame
        drawScene(canvas, shown?.bitmap, shown?.bitmapToView, currentLook, alwaysPicture = false)
        canvas.restore()
    }

    DisposableEffect(lifecycleOwner) {
        val appContext = context.applicationContext
        val current = Session(
            analysisExecutor = Executors.newSingleThreadExecutor(),
            photoExecutor = Executors.newSingleThreadExecutor(),
            makePhotoTracker = { FaceTracker.create(appContext, video = false, maxFaces = PHOTO_FACES) },
        )
        session = current
        val mainExecutor = ContextCompat.getMainExecutor(context)
        controller.imageAnalysisBackpressureStrategy = ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST
        // RGBA, to draw reshaped faces from. Sharp enough to fill the screen, small enough for
        // the phone to keep up.
        controller.imageAnalysisOutputImageFormat = ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888
        controller.imageAnalysisResolutionSelector = ResolutionSelector.Builder()
            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
            .setResolutionStrategy(
                ResolutionStrategy(Size(1280, 960), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER)
            )
            .build()
        controller.imageCaptureMode = ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY
        // Videos in full HD, the right way round for the front camera, like the preview.
        controller.videoCaptureQualitySelector =
            QualitySelector.from(Quality.FHD, FallbackStrategy.lowerQualityOrHigherThan(Quality.FHD))
        controller.videoCaptureMirrorMode = MirrorMode.MIRROR_MODE_ON_FRONT_ONLY

        // Filters drawn onto the very frames their faces were found in: each preview frame waits
        // for its faces, so filters never trail behind a moving face. Videos get the same frames.
        val mainHandler = Handler(Looper.getMainLooper())
        val overlay = OverlayEffect(CameraEffect.PREVIEW or CameraEffect.VIDEO_CAPTURE, OVERLAY_QUEUE, mainHandler) { error ->
            Log.e(TAG, "Couldn't draw on the camera's frames: drawing over the preview instead", error)
            mainHandler.post {
                if (session === current && !overlayBroken) {
                    overlayBroken = true
                    controller.clearEffects()
                }
            }
        }
        overlay.setOnDrawListener { frame ->
            drawOverlay(frame)
            true
        }
        if (!overlayBroken) controller.setEffects(setOf(overlay))

        fun show(frame: FaceFrame) {
            if (session !== current) {
                frame.bitmap?.let(buffers::release)
                return
            }
            // Positions come in preview pixels, mirrored like the preview for the front camera.
            val now = SystemClock.uptimeMillis()
            poses = smoother.update(frame.faces, now)
            liveMesh.update(frame.mesh, frame.meshCounts, now)
            frame.sensorToView?.let { overlaySensorToView.value = it }
            // The user's face, for retouching, and for the blended face to take on its light.
            val look = currentLook
            val framePicture = frame.bitmap
            val meshLayout = liveMesh.layout
            val mesh = liveMesh.points()
            if (look.needsMesh && framePicture != null && meshLayout != null && mesh != null) {
                val toBitmap = Matrix().also { frame.bitmapToView.invert(it) }
                val skin = SkinSource.of(framePicture, toBitmap, mesh, meshLayout)
                liveSkin.value = skin
                look.blend?.takeIf { meshLayout.matches(it.layout) }?.let { donor ->
                    liveMesh.matchLight(donor, sampleColors(skin.blurred, skin.blurredPoints))
                }
            } else if (framePicture != null) {
                liveSkin.value = null
            }
            val kind = waitingToFreeze
            val face = frame.faces.maxByOrNull { it.eyeDistance }
            if (kind != null && frame.bitmap != null && face != null) {
                // A copy: the frame's bitmap goes back to be reused.
                val still = frame.bitmap.copy(Bitmap.Config.ARGB_8888, false)
                editing = kind to FrozenFrame(still, Matrix(frame.bitmapToView), face)
                waitingToFreeze = null
            }
            if (frame.bitmap != null || shownFrame != null) {
                previousFrame?.bitmap?.let(buffers::release)
                previousFrame = shownFrame
                // Null goes back to the preview.
                shownFrame = frame.takeIf { it.bitmap != null }
            }
            // The video gets what the screen shows: the picture, reshaped, and the stickers.
            val video = recorder
            val picture = frame.bitmap
            if (video != null && picture != null && previewView.width > 0 && previewView.height > 0) {
                val look = currentLook
                video.drawFrame { canvas ->
                    canvas.scale(video.width / previewView.width.toFloat(), video.height / previewView.height.toFloat())
                    drawScene(canvas, picture, frame.bitmapToView, look, alwaysPicture = true)
                }
            }
            // The preview frame taken with this one can now be shown, with its filters.
            if (!overlayBroken) overlay.drawFrameAsync(frame.timestampNs)
        }
        val analyzer = FaceAnalyzer(
            makeTracker = { FaceTracker.create(appContext, video = true, maxFaces = LIVE_FACES) },
            buffers = buffers,
            wantPictures = wantPictures,
            wantMesh = wantMesh,
        ) { frame -> mainExecutor.execute { show(frame) } }
        controller.setImageAnalysisAnalyzer(current.analysisExecutor, analyzer)
        controller.setEnabledUseCases(CameraController.IMAGE_ANALYSIS or CameraController.IMAGE_CAPTURE)
        controller.initializationFuture.addListener({
            val front = controller.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA)
            val back = controller.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)
            hasBothCameras = front && back
            if (!front && back) useFront = false
        }, mainExecutor)
        controller.bindToLifecycle(lifecycleOwner)
        // Updated for each camera, which starts at 1x when switched to.
        controller.zoomState.observe(lifecycleOwner) { zoom = it }
        previewView.controller = controller

        onDispose {
            session = null
            shownFrame = null
            previousFrame = null
            controller.zoomState.removeObservers(lifecycleOwner)
            zoom = null
            previewView.controller = null
            controller.unbind()
            controller.clearImageAnalysisAnalyzer()
            controller.clearEffects()
            overlay.close()
            overlaySensorToView.value = null
            // A photo being processed finishes first: the executor runs it before shutting down.
            current.photoExecutor.execute { current.closePhotoTracker() }
            current.photoExecutor.shutdown()
            // After the last frame, on the thread that used it.
            current.analysisExecutor.execute { analyzer.close() }
            current.analysisExecutor.shutdown()
        }
    }

    LaunchedEffect(reshape.isNone, waitingToFreeze, recorder, blend, makeover) {
        val needMesh = currentLook.needsMesh
        wantPictures.set(currentLook.changesFaces || waitingToFreeze != null || recorder != null)
        wantMesh.set(needMesh)
        if (!needMesh) {
            liveMesh.clear()
            liveSkin.value = null
        }
    }

    // Videos are recorded by the camera, with the filters on its frames, when that works.
    LaunchedEffect(captureMode, overlayBroken) {
        controller.setEnabledUseCases(
            if (captureMode == CaptureMode.Video && !overlayBroken) CameraController.IMAGE_ANALYSIS or CameraController.VIDEO_CAPTURE
            else CameraController.IMAGE_ANALYSIS or CameraController.IMAGE_CAPTURE
        )
    }

    // Gives up looking for a face to edit on after a while.
    LaunchedEffect(waitingToFreeze) {
        if (waitingToFreeze == null) return@LaunchedEffect
        delay(4000)
        waitingToFreeze = null
        Toast.makeText(context, "No face found: point the camera at a face", Toast.LENGTH_SHORT).show()
    }

    LaunchedEffect(useFront) {
        controller.cameraSelector =
            if (useFront) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
        // Faces seen by the other camera are no longer there.
        smoother.clear()
        poses = emptyList()
    }

    // Shows how a sticker is adjusted while the user adjusts it, and a moment after.
    LaunchedEffect(adjustedCount) {
        if (adjustedCount == 0) return@LaunchedEffect
        showAdjustment = true
        delay(1500)
        showAdjustment = false
    }

    fun adjust(change: (Adjustment) -> Adjustment) {
        val key = sticker.key
        val changed = change(adjustments[key] ?: store.adjustment(key))
        adjustments[key] = changed
        store.save(key, changed)
        adjustedCount++
    }

    fun addSticker(choice: CustomStickerChoice) {
        customStickers = customStickers + choice
        saveCustom()
        stickerKey = choice.key
        tab = Tab.Stickers
        naming = choice
    }

    fun saveDrawing(strokes: List<DrawnStroke>, pose: FacePose) {
        val id = CustomStore.newId()
        val name = "Drawing ${customStickers.size + 1}"
        scope.launch {
            val made = withContext(Dispatchers.Default) { drawingToSticker(strokes, pose, id, name) }
            if (made != null) {
                withContext(Dispatchers.IO) { customStore.savePicture(id, made.first) }
                addSticker(CustomStickerChoice(made.second, made.first))
            }
            editing = null
        }
    }

    fun saveShape(pushes: List<Push>) {
        val shape = CustomShape(CustomStore.newId(), "Shape ${customShapes.size + 1}", pushes)
        customShapes = customShapes + shape
        saveCustom()
        shapeKey = CustomReshape(shape).key
        tab = Tab.Shapes
        editing = null
        naming = shape
    }

    val pickPicture = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            val id = CustomStore.newId()
            val picture = withContext(Dispatchers.IO) {
                loadPicture(context.applicationContext, uri)?.also { customStore.savePicture(id, it) }
            }
            if (picture == null) {
                Toast.makeText(context, "Couldn't open that picture", Toast.LENGTH_SHORT).show()
                return@launch
            }
            addSticker(CustomStickerChoice(CustomSticker.forPicture(id, "Picture ${customStickers.size + 1}", picture), picture))
        }
    }

    // A photo of someone, whose face becomes one to blend.
    val pickFace = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        addingFace = true
        scope.launch {
            val id = CustomStore.newId()
            val name = "Face ${blendFaces.size + 1}"
            val made: Any = withContext(Dispatchers.IO) {
                val picture = loadPicture(context.applicationContext, uri, maxSide = 1280)
                    ?: return@withContext "Couldn't open that picture"
                val mesh = try {
                    val tracker = FaceTracker.create(context, video = false, maxFaces = 1)
                    try {
                        FaceTracker.findInPhoto(tracker, picture).maxByOrNull { it.size }?.meshPoints()
                    } finally {
                        tracker.close()
                    }
                } catch (e: Throwable) {
                    Log.w(TAG, "Face detection failed on the picture", e)
                    null
                }
                if (mesh == null) return@withContext "No face found in that picture: try one facing the camera"
                customStore.savePhoto(id, picture)
                BlendFace(id, name, picture, mesh, Landmarks.COUNTS)
            }
            addingFace = false
            if (made is BlendFace) {
                blendFaces = blendFaces + made
                saveCustom()
                blendKey = made.key
                tab = Tab.Faces
                naming = made
            } else {
                Toast.makeText(context, made.toString(), Toast.LENGTH_LONG).show()
            }
        }
    }

    fun nameOf(item: Any) = when (item) {
        is CustomStickerChoice -> item.info.name
        is CustomShape -> item.name
        is BlendFace -> item.name
        else -> ""
    }

    fun rename(item: Any, name: String) {
        when (item) {
            is CustomStickerChoice -> customStickers = customStickers.map {
                if (it.key == item.key) it.copy(info = it.info.copy(name = name)) else it
            }
            is CustomShape -> customShapes = customShapes.map { if (it.id == item.id) it.copy(name = name) else it }
            is BlendFace -> {
                item.name = name
                facesRenamed++
            }
        }
        saveCustom()
    }

    fun delete(item: Any) {
        when (item) {
            is BlendFace -> {
                blendFaces = blendFaces - item
                scope.launch(Dispatchers.IO) { customStore.deletePicture(item.id) }
                if (blendKey == item.key) blendKey = ""
            }
            is CustomStickerChoice -> {
                customStickers = customStickers - item
                store.forget(item.key)
                adjustments.remove(item.key)
                scope.launch(Dispatchers.IO) { customStore.deletePicture(item.info.id) }
                if (stickerKey == item.key) stickerKey = Filter.None.name
            }
            is CustomShape -> {
                val key = CustomReshape(item).key
                customShapes = customShapes - item
                store.forget(key)
                strengths.remove(key)
                if (shapeKey == key) shapeKey = BuiltInReshape(Shape.None).key
            }
        }
        saveCustom()
    }

    // A picture from the gallery, drawn with the look and the other tabs' filters, saved as a new photo.
    val restylePicture = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        val current = session ?: return@rememberLauncherForActivityResult
        if (saving) return@rememberLauncherForActivityResult
        saving = true
        val look = currentLook
        scope.launch {
            val saved = try {
                val picture = withContext(Dispatchers.IO) {
                    loadPicture(context, uri, maxSide = 2400)?.let { if (it.isMutable) it else it.copy(Bitmap.Config.ARGB_8888, true) }
                }
                if (picture == null) {
                    null
                } else {
                    val finished = withContext(current.photoExecutor.asCoroutineDispatcher()) {
                        finishPhoto(
                            current, picture, look.sticker, look.adjustment, look.reshape, look.strength,
                            look.blend, look.blendAmount, look.keepOwnFeatures, look.makeover, look.makeoverStrength,
                            previewPoses = emptyList(), previewWidth = 0,
                        )
                    }
                    withContext(Dispatchers.IO) { PhotoSaver.save(context.applicationContext, finished) }
                        .also { finished.recycle() }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Could not redraw a gallery picture", e)
                null
            }
            saving = false
            Toast.makeText(
                context,
                if (saved != null) "Saved to Pictures/${PhotoSaver.ALBUM}" else "Couldn't open that picture",
                Toast.LENGTH_SHORT,
            ).show()
        }
    }

    fun takePhoto() {
        val current = session ?: return
        if (saving) return
        saving = true
        // Kept from now: the user may switch filter or move before the photo is ready.
        val photoSticker = sticker
        val photoAdjustment = adjustment
        val photoReshape = reshape
        val photoStrength = strength
        val photoBlend = blend
        val photoBlendAmount = blendAmount
        val photoKeep = keepOwnFeatures
        val photoMakeover = makeover
        val photoMakeoverStrength = makeoverStrength
        val previewPoses = poses
        val previewWidth = previewView.width
        val mirror = useFront
        scope.launch {
            launch {
                flash.snapTo(0.8f)
                flash.animateTo(0f, tween(300))
            }
            val saved = try {
                val photo = controller.takePhoto(current.photoExecutor, mirror)
                // On the photo thread, so the detector isn't closed while in use.
                val finished = withContext(current.photoExecutor.asCoroutineDispatcher()) {
                    finishPhoto(
                        current, photo, photoSticker, photoAdjustment, photoReshape, photoStrength,
                        photoBlend, photoBlendAmount, photoKeep, photoMakeover, photoMakeoverStrength,
                        previewPoses, previewWidth,
                    )
                }
                withContext(Dispatchers.IO) { PhotoSaver.save(context.applicationContext, finished) }
                    .also { finished.recycle() }
            } catch (e: Exception) {
                Log.e(TAG, "Could not take a photo", e)
                null
            }
            saving = false
            Toast.makeText(
                context,
                if (saved != null) "Saved to Pictures/${PhotoSaver.ALBUM}" else "Couldn't take the photo",
                Toast.LENGTH_SHORT,
            ).show()
        }
    }

    /** Saves the camera's recording to [file], once it is [finished], and says so. */
    fun onCameraVideoFinished(file: File, finished: VideoRecordEvent.Finalize) {
        val appContext = context.applicationContext
        val mainExecutor = ContextCompat.getMainExecutor(appContext)
        // Stopped by leaving the app, say: what was recorded is still worth keeping.
        val usable = finished.error != VideoRecordEvent.Finalize.ERROR_NO_VALID_DATA && file.length() > 0
        if (finished.hasError()) Log.w(TAG, "Recording ended with error ${finished.error}", finished.cause)
        // Not tied to the screen: it may be gone by now.
        thread(name = "SaveVideo") {
            val uri = if (usable) PhotoSaver.saveVideo(appContext, file) else null
            mainExecutor.execute {
                finishingVideo = false
                Toast.makeText(
                    appContext,
                    if (uri != null) "Video saved to Movies/${PhotoSaver.ALBUM}" else "Couldn't save the video",
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }
    }

    fun startRecording(withSound: Boolean) {
        if (recording || finishingVideo) return
        val file = File(context.cacheDir, "recording_${System.currentTimeMillis()}.mp4")
        if (!overlayBroken) {
            // The camera records its frames, with the filters drawn on them, in full HD.
            cameraRecording = try {
                val audio = if (withSound) AudioConfig.create(true) else AudioConfig.AUDIO_DISABLED
                controller.startRecording(FileOutputOptions.Builder(file).build(), audio, ContextCompat.getMainExecutor(context)) { event ->
                    if (event is VideoRecordEvent.Finalize) onCameraVideoFinished(file, event)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Could not start recording", e)
                null
            }
            if (cameraRecording == null) {
                Toast.makeText(context, "This phone couldn't start recording", Toast.LENGTH_LONG).show()
                return
            }
        } else {
            val started = VideoRecorder.start(file, previewView.width, previewView.height, withSound)
            if (started == null) {
                Toast.makeText(context, "This phone couldn't start recording", Toast.LENGTH_LONG).show()
                return
            }
            recorder = started
        }
        recordingSince = SystemClock.elapsedRealtime()
        recordingSeconds = 0
    }

    fun stopRecording() {
        cameraRecording?.let { camera ->
            cameraRecording = null
            finishingVideo = true
            // Saved when the camera says it's finished.
            camera.stop()
            return
        }
        val video = recorder ?: return
        recorder = null
        finishingVideo = true
        scope.launch {
            val uri = withContext(Dispatchers.IO) {
                if (video.stop()) PhotoSaver.saveVideo(context.applicationContext, video.file) else null
            }
            finishingVideo = false
            Toast.makeText(
                context,
                if (uri != null) "Video saved to Movies/${PhotoSaver.ALBUM}" else "Couldn't save the video",
                Toast.LENGTH_SHORT,
            ).show()
        }
    }

    // Leaving the app ends the video: the camera stops.
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { stopRecording() }
    // Turning the phone rebuilds the screen: finish the video on the way out.
    DisposableEffect(Unit) {
        onDispose {
            cameraRecording?.stop()
            cameraRecording = null
            val video = recorder ?: return@onDispose
            recorder = null
            val appContext = context.applicationContext
            thread(name = "FinishVideo") { if (video.stop()) PhotoSaver.saveVideo(appContext, video.file) }
        }
    }

    // How long the video has been recording.
    LaunchedEffect(recording) {
        while (recording) {
            recordingSeconds = (SystemClock.elapsedRealtime() - recordingSince) / 1000
            delay(250)
        }
    }

    // With sound if the user allows the microphone; without it otherwise.
    val microphonePermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> startRecording(withSound = granted) }

    // Asked for again, after the storage permission's answer, which only Android 9 and older ask for.
    var onShutterAgain by remember { mutableStateOf<(() -> Unit)?>(null) }
    val storagePermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            onShutterAgain?.invoke()
        } else {
            Toast.makeText(context, "Saving needs storage access", Toast.LENGTH_LONG).show()
        }
    }

    fun onShutter() {
        if (captureMode == CaptureMode.Video && recording) {
            stopRecording()
            return
        }
        val permission = Manifest.permission.WRITE_EXTERNAL_STORAGE
        if (PhotoSaver.needsStoragePermission &&
            ContextCompat.checkSelfPermission(context, permission) != PackageManager.PERMISSION_GRANTED
        ) {
            onShutterAgain = ::onShutter
            storagePermission.launch(permission)
            return
        }
        when (captureMode) {
            CaptureMode.Photo -> takePhoto()
            CaptureMode.Video -> {
                val microphone = Manifest.permission.RECORD_AUDIO
                if (ContextCompat.checkSelfPermission(context, microphone) == PackageManager.PERMISSION_GRANTED) {
                    startRecording(withSound = true)
                } else {
                    microphonePermission.launch(microphone)
                }
            }
        }
    }

    /** What screen mode is to show: the filters picked here. */
    fun screenKeys() = ScreenFilterService.Keys(
        sticker = stickerKey,
        shape = shapeKey,
        face = blend?.id,
        makeover = makeover.name,
    )

    // Screen mode: allowed to draw over other apps, then to capture the screen.
    val screenCapture = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data
        if (result.resultCode == Activity.RESULT_OK && data != null) {
            ScreenFilterService.start(context, result.resultCode, data, screenKeys())
            Toast.makeText(context, "Screen mode is on: open any app with faces", Toast.LENGTH_LONG).show()
        }
    }

    fun askToCaptureScreen() {
        val manager = context.getSystemService(MediaProjectionManager::class.java)
        screenCapture.launch(manager.createScreenCaptureIntent())
    }

    // Without it, the notification with Stop on it isn't shown. Screen mode works either way.
    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { askToCaptureScreen() }

    fun toggleScreenMode() {
        if (screenModeOn) {
            ScreenFilterService.stop(context)
            return
        }
        if (sticker.isNone && !currentLook.changesFaces) {
            Toast.makeText(context, "Pick a filter first: screen mode puts it on faces on your screen", Toast.LENGTH_LONG).show()
            return
        }
        if (!Settings.canDrawOverlays(context)) {
            Toast.makeText(context, "Allow Face Filters to display over other apps, then tap 📱 again", Toast.LENGTH_LONG).show()
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))
            )
            return
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            askToCaptureScreen()
        }
    }

    // Screen mode shows the filters picked here, as adjusted here.
    LaunchedEffect(screenModeOn, currentLook) {
        if (screenModeOn) ScreenFilterService.update(context, screenKeys())
    }

    // The face drags are measured against, so a drag moves the sticker as far as the finger.
    val firstFace by rememberUpdatedState(poses.firstOrNull())

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
        // The same drawing as on saved photos, over the preview and in its pixels. Dragging on it
        // moves the sticker, pinching resizes it, twisting turns it, and double-tapping puts it back.
        Canvas(
            Modifier
                .fillMaxSize()
                .pointerInput(sticker.key) {
                    detectTransformGestures { _, pan, zoomBy, rotation ->
                        if (sticker.isNone) return@detectTransformGestures
                        val face = firstFace
                        val unit = face?.eyeDistance ?: (size.width / 5f)
                        // Along the face, which may be tilted.
                        val t = Math.toRadians((face?.angleDeg ?: 0f).toDouble())
                        val c = cos(t).toFloat()
                        val s = sin(t).toFloat()
                        val across = (pan.x * c + pan.y * s) / unit
                        val down = (-pan.x * s + pan.y * c) / unit
                        adjust { it.moved(across, down).resized(zoomBy).rotated(rotation) }
                    }
                }
                .pointerInput(sticker.key) {
                    detectTapGestures(onDoubleTap = {
                        if (!sticker.isNone) adjust { Adjustment() }
                    })
                },
        ) {
            // Normally drawn on the camera's frames themselves (see drawOverlay).
            if (overlayBroken) drawIntoCanvas { canvas ->
                // Reshaped and blended faces are drawn from the camera's frames, over the preview.
                val frame = shownFrame
                drawScene(canvas.nativeCanvas, frame?.bitmap, frame?.bitmapToView, currentLook, alwaysPicture = false)
            }
        }

        val message = when {
            recording -> "🔴 " + String.format(Locale.US, "%d:%02d", recordingSeconds / 60, recordingSeconds % 60)
            !controlsShown -> null
            waitingToFreeze != null -> "Hold still: looking for your face…"
            screenModeOn -> "Screen mode is on: open any app with faces. Tap 📱 to stop"
            showAdjustment && !sticker.isNone -> adjustment.describe()
            addingFace -> "Finding the face in the picture…"
            poses.isEmpty() && (!sticker.isNone || !reshape.isNone || blend != null || makeover.retouches || makeover.reshapes) ->
                "Point the camera at a face"
            else -> null
        }
        if (message != null) {
            Text(
                message,
                color = Color.White,
                fontSize = 14.sp,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(top = 16.dp)
                    .background(Color(0x99000000), RoundedCornerShape(16.dp))
                    .padding(horizontal = 14.dp, vertical = 6.dp),
            )
        }
        Box(Modifier.fillMaxSize().alpha(flash.value).background(Color.White))

        // Always shown, so the controls can be brought back.
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(12.dp),
        ) {
            RoundButton(if (controlsShown) "🙈" else "👁️", enabled = true, size = 44) {
                controlsShown = !controlsShown
            }
        }

        if (controlsShown) Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Color(0x80000000))
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(vertical = 12.dp),
        ) {
            zoom?.takeIf { it.maxZoomRatio > it.minZoomRatio + 0.01f }?.let { state ->
                ZoomSlider(state) { controller.setZoomRatio(it) }
            }
            TabSwitch(selected = tab, onSelect = { tab = it })
            when (tab) {
                Tab.Stickers -> {
                    val items = stickers.map { choice ->
                        when (choice) {
                            is BuiltInSticker -> PickerItem(choice.key, choice.filter.label, emoji = choice.filter.emoji) {
                                stickerKey = choice.key
                            }
                            is CustomStickerChoice -> PickerItem(
                                choice.key, choice.info.name, picture = choice.picture.asImageBitmap(),
                                onLongClick = { naming = choice },
                            ) { stickerKey = choice.key }
                        }
                    } + listOf(
                        PickerItem("add.picture", "Picture", emoji = "🖼️") {
                            pickPicture.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                        },
                        PickerItem("add.drawing", "Draw", emoji = "✏️") { waitingToFreeze = EditorKind.Draw },
                    )
                    Picker(items, stickerKey)
                    Text(
                        if (customStickers.isEmpty()) "Drag to move · pinch to resize · twist to turn · double-tap to reset"
                        else "Drag · pinch · twist to place · double-tap to reset · hold your own to rename",
                        color = Color(0xB3FFFFFF),
                        fontSize = 11.sp,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
                Tab.Shapes -> {
                    val items = reshapes.map { choice ->
                        when (choice) {
                            is BuiltInReshape -> PickerItem(choice.key, choice.shape.label, emoji = choice.shape.emoji) {
                                shapeKey = choice.key
                            }
                            is CustomReshape -> PickerItem(
                                choice.key, choice.shape.name, emoji = "✨",
                                onLongClick = { naming = choice.shape },
                            ) { shapeKey = choice.key }
                        }
                    } + PickerItem("add.shape", "Make", emoji = "🛠️") { waitingToFreeze = EditorKind.Reshape }
                    Picker(items, shapeKey)
                    if (!reshape.isNone) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
                        ) {
                            Text("Strength", color = Color.White, fontSize = 12.sp)
                            Slider(
                                value = strength,
                                onValueChange = { value ->
                                    strengths[reshape.key] = value
                                    store.saveStrength(reshape.key, value)
                                },
                                valueRange = 0.2f..FaceWarp.MAX_STRENGTH,
                                modifier = Modifier.padding(start = 12.dp).weight(1f),
                            )
                        }
                    } else {
                        Text(
                            if (customShapes.isEmpty()) "Make your own: push and pull a still of your face"
                            else "Hold your own to rename or delete",
                            color = Color(0xB3FFFFFF),
                            fontSize = 11.sp,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                }
                Tab.Looks -> Unit
                Tab.Faces -> if (!canBlendLive) {
                    Text(
                        "Blending faces needs Android 10 or newer",
                        color = Color.White,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(vertical = 16.dp),
                    )
                } else {
                    @Suppress("UNUSED_EXPRESSION") facesRenamed // Read, so a rename shows.
                    val items = listOf(PickerItem("face.none", "None", emoji = "🚫") { blendKey = "" }) +
                        blendFaces.map { face ->
                            PickerItem(
                                face.key, face.name, picture = face.thumbnail.asImageBitmap(),
                                onLongClick = { naming = face },
                            ) { blendKey = face.key }
                        } +
                        PickerItem("add.face", if (addingFace) "Adding…" else "Add face", emoji = "🧑") {
                            if (!addingFace) {
                                pickFace.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                            }
                        }
                    Picker(items, blend?.key ?: "face.none")
                    if (blend != null) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
                        ) {
                            Text("Blend", color = Color.White, fontSize = 12.sp)
                            Slider(
                                value = blendAmount,
                                onValueChange = { value ->
                                    blendAmount = value
                                    store.saveStrength("blend", value)
                                },
                                valueRange = 0.15f..1f,
                                modifier = Modifier.padding(start = 12.dp).weight(1f),
                            )
                        }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
                        ) {
                            Text("Keep my eyes & mouth", color = Color.White, fontSize = 12.sp, modifier = Modifier.weight(1f))
                            Switch(
                                checked = keepOwnFeatures,
                                onCheckedChange = {
                                    keepOwnFeatures = it
                                    store.saveFlag("blend.keep", it)
                                },
                            )
                        }
                    } else {
                        Text(
                            if (blendFaces.isEmpty()) "Add a photo of someone facing the camera, in even light"
                            else "Hold a face to rename or delete it",
                            color = Color(0xB3FFFFFF),
                            fontSize = 11.sp,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                }
            }
            if (tab == Tab.Looks) {
                Picker(
                    Makeover.entries.map { option -> PickerItem(option.key, option.label, emoji = option.emoji) { makeover = option } },
                    makeover.key,
                )
                if (makeover.stylizes) {
                    TextButton(
                        enabled = !saving,
                        onClick = { restylePicture.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                    ) {
                        Text(
                            if (saving) "Drawing…" else "🖼️ Turn a gallery picture into ${if (makeover == Makeover.Sketch) "a sketch" else makeover.label.lowercase()}",
                            color = Color.White,
                            fontSize = 13.sp,
                        )
                    }
                }
                if (makeover.reshapes) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
                    ) {
                        Text("Strength", color = Color.White, fontSize = 12.sp)
                        Slider(
                            value = makeoverStrength,
                            onValueChange = { value ->
                                strengths[makeover.key] = value
                                store.saveStrength(makeover.key, value)
                            },
                            valueRange = 0.2f..MakeoverWarp.MAX_STRENGTH,
                            modifier = Modifier.padding(start = 12.dp).weight(1f),
                        )
                    }
                } else if (makeover == Makeover.None) {
                    Text(
                        "Look older or younger, more feminine or masculine, or turn into anime, a gold engraving or a pencil sketch. Works with the other tabs",
                        color = Color(0xB3FFFFFF),
                        fontSize = 11.sp,
                        modifier = Modifier.padding(top = 6.dp, start = 16.dp, end = 16.dp),
                    )
                }
            }
            ModeSwitch(
                selected = captureMode,
                enabled = !recording && !finishingVideo,
                onSelect = { captureMode = it },
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceEvenly,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            ) {
                RoundButton("🔄", enabled = hasBothCameras && !saving, size = 52) { useFront = !useFront }
                Shutter(
                    mode = captureMode,
                    recording = recording,
                    busy = saving || finishingVideo,
                    onClick = ::onShutter,
                )
                RoundButton("📱", enabled = true, size = 52, highlighted = screenModeOn, onClick = ::toggleScreenMode)
            }
        }

        editing?.let { (kind, frozen) ->
            when (kind) {
                EditorKind.Draw -> DrawEditor(
                    frozen,
                    onCancel = { editing = null },
                    onSave = { strokes -> saveDrawing(strokes, frozen.pose) },
                )
                EditorKind.Reshape -> ReshapeEditor(
                    frozen, warper,
                    onCancel = { editing = null },
                    onSave = ::saveShape,
                )
            }
        }
    }

    naming?.let { item ->
        NameDialog(
            name = nameOf(item),
            onSave = { name ->
                rename(item, name)
                naming = null
            },
            onDelete = {
                naming = null
                toDelete = item
            },
            onDismiss = { naming = null },
        )
    }

    toDelete?.let { item ->
        val name = nameOf(item)
        AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text("Delete \"$name\"?") },
            text = { Text("It can't be brought back.") },
            confirmButton = {
                TextButton(onClick = {
                    delete(item)
                    toDelete = null
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { toDelete = null }) { Text("Keep") } },
        )
    }
}

/**
 * Reshapes the faces in [photo] and draws the sticker on them, returning the finished photo,
 * which may be a new bitmap. The faces are found again in the photo, where they are sharper and
 * exactly placed. If none are found there, the preview's are used, scaled up: the photo shows
 * what the preview showed.
 */
private fun finishPhoto(
    session: Session,
    photo: Bitmap,
    sticker: Sticker,
    adjustment: Adjustment,
    reshape: Reshape,
    strength: Float,
    blend: BlendFace?,
    blendAmount: Float,
    keepOwnFeatures: Boolean,
    makeover: Makeover,
    makeoverStrength: Float,
    previewPoses: List<FacePose>,
    previewWidth: Int,
): Bitmap {
    if (sticker.isNone && reshape.isNone && blend == null && makeover == Makeover.None) return photo
    // Found before the photo is reshaped, which replaces it.
    val needMesh = blend != null || makeover.retouches
    val faces = runCatching { FaceTracker.findInPhoto(session.photoTracker, photo) }
        .onFailure { Log.w(TAG, "Face detection failed on the photo", it) }
        .getOrDefault(emptyList())
    val mesh = if (!needMesh) null else faces.maxByOrNull { it.size }?.meshPoints()?.let { it to Landmarks.COUNTS }
    val found = faces.mapNotNull { it.pose() }
    val poses = found.ifEmpty {
        if (previewWidth <= 0) emptyList()
        else previewPoses.map { it.scaled(photo.width.toFloat() / previewWidth) }
    }
    val warp: (FloatArray) -> Unit = { points ->
        reshape.warp(points, poses, strength)
        MakeoverWarp.warp(points, poses, makeover, makeoverStrength)
    }
    val reshaped = !reshape.isNone || makeover.reshapes
    // Anime, gold, sketch: the whole photo drawn anew.
    val drawn = makeover.pictureFilter()?.let { PictureStylizer.stylizePhoto(it, photo).also { photo.recycle() } } ?: photo
    // Fine enough for a face that fills a full-resolution photo.
    val finished = if (reshaped && poses.isNotEmpty()) {
        MeshWarper(cellsOnLongSide = 160).warped(drawn, warp).also { drawn.recycle() }
    } else {
        drawn
    }
    val canvas = android.graphics.Canvas(finished)
    if (mesh != null) {
        val layout = MeshLayout(mesh.second)
        val points = layout.expand(mesh.first)
        if (reshaped) warp(points)
        // The photo is already reshaped, so its face is where the reshaped mesh is.
        val skin = SkinSource.of(finished, null, points, layout)
        val pose = poses.maxByOrNull { it.eyeDistance }
        if (makeover.retouches && pose != null) {
            MakeoverRenderer().draw(canvas, makeover, makeoverStrength, layout, points, pose, warp, skin)
        }
        if (blend != null && layout.matches(blend.layout)) {
            // Measured after the makeover, so the blended face takes on its color too.
            val light = layout.lightGains(sampleColors(skin.blurred, skin.blurredPoints), blend.colors)
            FaceBlender().draw(canvas, blend, points, light, blendAmount, keepOwnFeatures)
        }
    }
    FilterRenderer().draw(canvas, poses, sticker, adjustment)
    return finished
}

private fun Adjustment.describe(): String {
    val parts = mutableListOf<String>()
    if (abs(dx) >= 0.05f || abs(dy) >= 0.05f) parts += "Moved"
    parts += "size ${(size * 100).roundToInt()}%"
    if (abs(rotationDeg) >= 1f) parts += "turned ${rotationDeg.roundToInt()}°"
    return parts.joinToString(" · ").replaceFirstChar { it.uppercase() }
}

/** Zooms the camera, from as wide as it goes to as close. Shows the zoom, like "2.5x". */
@Composable
private fun ZoomSlider(state: ZoomState, onZoom: (Float) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = 4.dp),
    ) {
        Text(
            String.format(Locale.US, "%.1fx", state.zoomRatio),
            color = Color.White,
            fontSize = 12.sp,
            modifier = Modifier.width(40.dp),
        )
        Slider(
            value = state.zoomRatio.coerceIn(state.minZoomRatio, state.maxZoomRatio),
            onValueChange = onZoom,
            valueRange = state.minZoomRatio..state.maxZoomRatio,
            modifier = Modifier.weight(1f),
        )
        // Back to no zoom.
        Text(
            "1x",
            color = Color.White,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier
                .padding(start = 8.dp)
                .clip(CircleShape)
                .background(Color(0x55000000))
                .clickable { onZoom(1f.coerceIn(state.minZoomRatio, state.maxZoomRatio)) }
                .padding(horizontal = 10.dp, vertical = 6.dp),
        )
    }
}

private enum class Tab(val label: String) { Stickers("Stickers"), Shapes("Shapes"), Faces("Faces"), Looks("Looks") }

/** Names a custom sticker, shape or face, or deletes it. */
@Composable
private fun NameDialog(name: String, onSave: (String) -> Unit, onDelete: () -> Unit, onDismiss: () -> Unit) {
    var text by remember(name) { mutableStateOf(name) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Name") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it.take(24) },
                singleLine = true,
                label = { Text("Name") },
            )
        },
        confirmButton = {
            TextButton(enabled = text.isNotBlank(), onClick = { onSave(text.trim()) }) { Text("Save") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onDelete) { Text("Delete", color = Color(0xFFEF5350)) }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}

@Composable
private fun TabSwitch(selected: Tab, onSelect: (Tab) -> Unit) {
    Row(
        modifier = Modifier
            .padding(bottom = 10.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(Color(0x55000000)),
    ) {
        for (tab in Tab.entries) {
            val isSelected = tab == selected
            Text(
                tab.label,
                color = if (isSelected) Color.Black else Color.White,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .clip(RoundedCornerShape(18.dp))
                    .background(if (isSelected) Color(0xFFFFD54F) else Color.Transparent)
                    .clickable { onSelect(tab) }
                    .padding(horizontal = 13.dp, vertical = 7.dp),
            )
        }
    }
}

/** One round choice in a [Picker]: an emoji or a picture, with its name under it. */
private class PickerItem(
    val key: String,
    val label: String,
    val emoji: String? = null,
    val picture: ImageBitmap? = null,
    val onLongClick: (() -> Unit)? = null,
    val onClick: () -> Unit,
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Picker(items: List<PickerItem>, selectedKey: String) {
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(horizontal = 16.dp),
    ) {
        items(items, key = { it.key }) { item ->
            val isSelected = item.key == selectedKey
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .combinedClickable(onLongClick = item.onLongClick, onClick = item.onClick)
                    .padding(4.dp),
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(56.dp)
                        .clip(CircleShape)
                        .background(if (isSelected) Color(0x55FFFFFF) else Color(0x33000000))
                        .border(
                            width = if (isSelected) 3.dp else 1.dp,
                            color = if (isSelected) Color(0xFFFFD54F) else Color(0x66FFFFFF),
                            shape = CircleShape,
                        ),
                ) {
                    when {
                        item.picture != null -> Image(
                            item.picture,
                            contentDescription = item.label,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.size(44.dp),
                        )
                        item.emoji != null -> Text(item.emoji, fontSize = 26.sp)
                    }
                }
                Text(
                    item.label,
                    color = if (isSelected) Color(0xFFFFD54F) else Color.White,
                    fontSize = 12.sp,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                    maxLines = 1,
                    modifier = Modifier.padding(top = 4.dp).width(64.dp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
        }
    }
}

private enum class CaptureMode(val label: String) { Photo("Photo"), Video("Video") }

@Composable
private fun ModeSwitch(selected: CaptureMode, enabled: Boolean, onSelect: (CaptureMode) -> Unit) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.padding(top = 10.dp).alpha(if (enabled) 1f else 0.5f),
    ) {
        for (mode in CaptureMode.entries) {
            val isSelected = mode == selected
            Text(
                mode.label.uppercase(),
                color = if (isSelected) Color(0xFFFFD54F) else Color.White,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .clickable(enabled = enabled) { onSelect(mode) }
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            )
        }
    }
}

/** Takes a photo; or starts a video, and while recording, a red square that stops it. */
@Composable
private fun Shutter(mode: CaptureMode, recording: Boolean, busy: Boolean, onClick: () -> Unit) {
    val red = Color(0xFFE53935)
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(76.dp)
            .border(4.dp, Color.White, CircleShape)
            .padding(8.dp)
            .clip(CircleShape)
            .clickable(enabled = !busy, onClick = onClick),
    ) {
        when {
            busy -> Box(Modifier.fillMaxSize().background(Color.Gray), contentAlignment = Alignment.Center) {
                // Full-resolution photos, and finishing a video, take a moment.
                CircularProgressIndicator(color = Color.White, strokeWidth = 3.dp, modifier = Modifier.size(36.dp))
            }
            recording -> Box(Modifier.size(28.dp).clip(RoundedCornerShape(6.dp)).background(red))
            mode == CaptureMode.Video -> Box(Modifier.fillMaxSize().background(red))
            else -> Box(Modifier.fillMaxSize().background(Color.White))
        }
    }
}

@Composable
private fun RoundButton(
    text: String,
    enabled: Boolean,
    size: Int,
    highlighted: Boolean = false,
    onClick: () -> Unit,
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(size.dp)
            .alpha(if (enabled) 1f else 0.35f)
            .clip(CircleShape)
            .background(if (highlighted) Color(0xCCFFD54F) else Color(0x66000000))
            .clickable(enabled = enabled, onClick = onClick),
    ) {
        Text(text, fontSize = (size * 0.45f).sp)
    }
}
