package com.example.facefilters

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.DisplayMetrics
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.core.content.IntentCompat
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private const val TAG = "FaceFilters"

/**
 * Screen mode: draws the chosen filters on the faces on the phone's screen, in any app. It reads
 * the screen through a screen capture the user allows, finds the faces, and draws over other apps.
 *
 * Sharing one app (Android 14 and newer), the capture doesn't see what is drawn, so every filter
 * works: faces are redrawn from the capture, reshaped, retouched or blended, in a solid window
 * that follows them. Sharing the entire screen, the capture sees the drawing, and redrawn faces
 * would be redrawn again and again, so only stickers are shown. Which one the user chose is found
 * by showing a small mark at the start and looking for it in the capture.
 */
class ScreenFilterService : Service() {

    /** The filters to show, by the keys the app keeps them under. */
    data class Keys(val sticker: String, val shape: String, val face: String?, val makeover: String)

    private val main = Handler(Looper.getMainLooper())
    private val scope = MainScope()
    private var thread: HandlerThread? = null
    private var worker: Handler? = null

    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    // Made and used on the worker thread.
    private var tracker: FaceTracker? = null
    private val buffers = FrameBuffers()

    private var stickerWindow: OverlayWindow? = null
    private var faceWindow: OverlayWindow? = null
    private val scene = SceneRenderer()

    // Set on the main thread, read by the analysis thread.
    @Volatile private var scale = 1f
    @Volatile private var contentWidth = 0
    @Volatile private var contentHeight = 0
    @Volatile private var probing = true
    @Volatile private var keepPictures = false
    @Volatile private var findMesh = false

    // Main thread only.
    private var look = Look.NONE
    private var keys: Keys? = null
    /** Whether the capture leaves out what is drawn: null until known. */
    private var captureIsClean: Boolean? = null
    private var probeStartedAt = 0L
    private var warnedAboutSharing = false
    private var smoother = PoseSmoother(holdMs = 1500)
    private val liveMesh = LiveMesh()
    private var poses: List<FacePose> = emptyList()
    private var skin: SkinSource? = null
    private var shown: ScreenFrame? = null
    private var previous: ScreenFrame? = null
    private var lastFaceAt = 0L
    private var hiddenAt: Long? = null
    private var pulseEndedAt = 0L
    private val endPulse = Runnable { finishPulse() }
    private val endProbe = Runnable { finishProbe(clean = true) }

    // Analysis thread only: loaded stickers and faces, kept while they are used.
    private val pictures = HashMap<String, CustomStickerChoice>()
    private val faces = HashMap<String, BlendFace>()

    /** One analyzed screen frame, in screen pixels. */
    private class ScreenFrame(
        val faces: List<FacePose>,
        val mesh: FloatArray?,
        val meshCounts: IntArray?,
        val bitmap: Bitmap?,
        val toScreen: Matrix,
        val markSeen: Boolean,
        val capturedAt: Long,
    )

    override fun onBind(intent: Intent?) = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> start(intent)
            ACTION_UPDATE -> intent.keys()?.let(::load)
            ACTION_STOP -> stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun Intent.keys(): Keys? {
        val sticker = getStringExtra(EXTRA_STICKER) ?: return null
        return Keys(
            sticker = sticker,
            shape = getStringExtra(EXTRA_SHAPE) ?: BuiltInReshape(Shape.None).key,
            face = getStringExtra(EXTRA_FACE),
            makeover = getStringExtra(EXTRA_MAKEOVER) ?: Makeover.None.name,
        )
    }

    private fun start(intent: Intent) {
        val startKeys = intent.keys()
        if (projection != null) {
            startKeys?.let(::load)
            return
        }
        // Before the capture starts, as Android requires.
        startInForeground()
        val data = IntentCompat.getParcelableExtra(intent, EXTRA_DATA, Intent::class.java)
        val code = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        val manager = getSystemService(MediaProjectionManager::class.java)
        val capture = data?.let {
            try {
                manager.getMediaProjection(code, it)
            } catch (e: Exception) {
                Log.e(TAG, "Could not start the screen capture", e)
                null
            }
        }
        if (capture == null || !Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }
        projection = capture
        capture.registerCallback(object : MediaProjection.Callback() {
            // The user can end it from the system's own controls.
            override fun onStop() {
                stopSelf()
            }

            // Sharing one app: the size of what is shared.
            override fun onCapturedContentResize(width: Int, height: Int) {
                contentWidth = width
                contentHeight = height
            }
        }, main)

        val workThread = HandlerThread("ScreenFilters").apply { start() }
        thread = workThread
        worker = Handler(workThread.looper)
        // Before the capture starts, on the same thread, so it's ready for the first frame.
        worker?.post {
            tracker = try {
                FaceTracker.create(this, video = true, maxFaces = MAX_FACES)
            } catch (e: Throwable) {
                Log.e(TAG, "Could not start face tracking", e)
                null
            }
        }
        // The face window first, so stickers are drawn over it.
        faceWindow = OverlayWindow(this, fullScreen = false) { canvas -> drawFaces(canvas) }.also { it.add() }
        stickerWindow = OverlayWindow(this, fullScreen = true) { canvas -> drawStickers(canvas) }.also { it.add() }
        startProbe()
        worker?.post { createCapture() }
        startKeys?.let(::load)
        _running.value = true

        // Not over the app itself, which has its own camera.
        scope.launch { appVisible.collect { updateWindows() } }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // Turned sideways: the screen is a different shape.
        worker?.post { resizeCapture() }
    }

    override fun onDestroy() {
        _running.value = false
        scope.cancel()
        main.removeCallbacks(endPulse)
        main.removeCallbacks(endProbe)
        stickerWindow?.remove()
        faceWindow?.remove()
        stickerWindow = null
        faceWindow = null
        val capture = projection
        projection = null
        worker?.post {
            display?.release()
            display = null
            reader?.close()
            reader = null
            capture?.stop()
            tracker?.close()
            tracker = null
        }
        thread?.quitSafely()
        super.onDestroy()
    }

    // --- Which kind of sharing: a mark is shown, and looked for in the capture.

    private fun startProbe() {
        if (Build.VERSION.SDK_INT < 34) {
            // Only the entire screen can be shared.
            finishProbe(clean = false)
            return
        }
        probing = true
        probeStartedAt = SystemClock.uptimeMillis()
        stickerWindow?.invalidate()
        // A shared app that doesn't change sends no frames: none showing the mark means clean.
        main.postDelayed(endProbe, PROBE_MS * 2)
    }

    private fun finishProbe(clean: Boolean) {
        main.removeCallbacks(endProbe)
        probing = false
        captureIsClean = clean
        smoother = if (clean) PoseSmoother() else PoseSmoother(holdMs = 1500)
        applyLook()
        stickerWindow?.invalidate()
    }

    // --- Capture, on the analysis thread.

    private fun screenSize(): Triple<Int, Int, Int> {
        val wm = getSystemService(WindowManager::class.java)
        val dpi = resources.displayMetrics.densityDpi
        return if (Build.VERSION.SDK_INT >= 30) {
            val bounds = wm.maximumWindowMetrics.bounds
            Triple(bounds.width(), bounds.height(), dpi)
        } else {
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(metrics)
            Triple(metrics.widthPixels, metrics.heightPixels, dpi)
        }
    }

    @SuppressLint("DiscouragedApi", "InternalInsetResource") // There's no other way to ask outside an activity.
    private fun statusBarHeight(): Int {
        val id = resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) resources.getDimensionPixelSize(id) else 0
    }

    /** A reader for the screen, scaled down: faces are found as well, and much faster. */
    private fun newReader(): ImageReader {
        val (width, height, _) = screenSize()
        scale = min(1f, CAPTURE_SIZE / max(width, height).toFloat())
        val w = (width * scale).toInt() / 2 * 2
        val h = (height * scale).toInt() / 2 * 2
        return ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 3).apply {
            setOnImageAvailableListener({ onFrame(it) }, worker)
        }
    }

    private fun createCapture() {
        val capture = projection ?: return
        val newReader = newReader()
        reader = newReader
        display = try {
            capture.createVirtualDisplay(
                "FaceFilters", newReader.width, newReader.height, screenSize().third,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, newReader.surface, null, worker,
            )
        } catch (e: Exception) {
            Log.e(TAG, "Could not capture the screen", e)
            main.post { stopSelf() }
            null
        }
    }

    private fun resizeCapture() {
        val current = display ?: return
        val old = reader
        val newReader = newReader()
        if (old != null && old.width == newReader.width && old.height == newReader.height) {
            newReader.close()
            return
        }
        reader = newReader
        current.resize(newReader.width, newReader.height, screenSize().third)
        current.surface = newReader.surface
        old?.close()
    }

    /**
     * From the capture's pixels to the screen's. The entire screen fills the capture. A shared
     * app is fitted inside it, and is on the screen where its window is: the full width, under
     * the status bar if it doesn't reach the top.
     */
    private fun captureToScreen(captureWidth: Int, captureHeight: Int): Matrix {
        val (screenWidth, screenHeight, _) = screenSize()
        val sharedWidth = (if (contentWidth > 0) contentWidth else screenWidth).toFloat()
        val sharedHeight = (if (contentHeight > 0) contentHeight else screenHeight).toFloat()
        val fit = min(captureWidth / sharedWidth, captureHeight / sharedHeight)
        val left = (captureWidth - sharedWidth * fit) / 2
        val top = (captureHeight - sharedHeight * fit) / 2
        val toScreen = screenWidth / sharedWidth
        val windowHeight = sharedHeight * toScreen
        val windowTop = if (windowHeight >= screenHeight - 2) 0f else min(statusBarHeight().toFloat(), screenHeight - windowHeight)
        return Matrix().apply {
            setTranslate(-left, -top)
            postScale(toScreen / fit, toScreen / fit)
            postTranslate(0f, windowTop)
        }
    }

    private fun onFrame(source: ImageReader) {
        val image = try {
            source.acquireLatestImage()
        } catch (e: IllegalStateException) {
            null
        } ?: return
        val capturedAt = SystemClock.uptimeMillis()
        if (appVisible.value || source !== reader) {
            image.close()
            return
        }
        val bitmap = try {
            val plane = image.planes[0]
            val buffer = plane.buffer.apply { rewind() }
            // Rows may be padded at the end: the padding is past the screen's right edge.
            val width = plane.rowStride / plane.pixelStride
            if (buffer.remaining() < plane.rowStride * image.height) return
            buffers.obtain(width, image.height).apply { copyPixelsFromBuffer(buffer) }
        } finally {
            image.close()
        }
        val toScreen = captureToScreen(source.width, source.height)
        val markSeen = probing && markIn(bitmap, toScreen)
        val withMesh = findMesh && !probing
        val found = try {
            val use = tracker ?: run {
                buffers.release(bitmap)
                return
            }
            use.detect(bitmap, capturedAt)
        } catch (e: Exception) {
            Log.w(TAG, "Face detection failed", e)
            buffers.release(bitmap)
            return
        }
        val faces = found.map { it.mapped(toScreen) }
        val poses = faces.mapNotNull { it.pose() }
        val mesh = if (withMesh) faces.maxByOrNull { it.size }?.meshPoints() else null
        val keep = keepPictures && !probing
        if (!keep) buffers.release(bitmap)
        val frame = ScreenFrame(poses, mesh, mesh?.let { Landmarks.COUNTS }, if (keep) bitmap else null, toScreen, markSeen, capturedAt)
        main.post { onFrame(frame) }
    }

    /** Whether the mark (see [OverlayWindow]) is in the capture [bitmap]. */
    private fun markIn(bitmap: Bitmap, toScreen: Matrix): Boolean {
        val toCapture = Matrix().also { toScreen.invert(it) }
        val p = floatArrayOf(MARK_SIZE * 0.5f, MARK_SIZE * 0.5f, MARK_SIZE * 1.5f, MARK_SIZE * 0.5f)
        toCapture.mapPoints(p)
        fun at(i: Int): Int? {
            val x = p[2 * i].roundToInt()
            val y = p[2 * i + 1].roundToInt()
            return if (x in 0 until bitmap.width && y in 0 until bitmap.height) bitmap.getPixel(x, y) else null
        }
        val magenta = at(0) ?: return false
        val green = at(1) ?: return false
        return Color.red(magenta) > 140 && Color.green(magenta) < 110 && Color.blue(magenta) > 140 &&
            Color.green(green) > 140 && Color.red(green) < 110 && Color.blue(green) < 110
    }

    // --- Showing, on the main thread.

    private fun onFrame(frame: ScreenFrame) {
        if (probing) {
            frame.bitmap?.let(buffers::release)
            if (frame.markSeen) {
                finishProbe(clean = false)
            } else if (SystemClock.uptimeMillis() - probeStartedAt > PROBE_MS) {
                finishProbe(clean = true)
            }
            return
        }
        if (captureIsClean == true) showClean(frame) else showStickersOnly(frame)
    }

    /** The capture doesn't see the drawing: everything is shown as found. */
    private fun showClean(frame: ScreenFrame) {
        val now = SystemClock.uptimeMillis()
        poses = smoother.update(frame.faces, now)
        liveMesh.update(frame.mesh, frame.meshCounts, now)
        val bitmap = frame.bitmap
        if (bitmap != null) {
            val layout = liveMesh.layout
            val mesh = liveMesh.points()
            skin = if (look.needsMesh && layout != null && mesh != null) {
                val toBitmap = Matrix().also { frame.toScreen.invert(it) }
                SkinSource.of(bitmap, toBitmap, mesh, layout).also { source ->
                    look.blend?.takeIf { layout.matches(it.layout) }?.let { donor ->
                        liveMesh.matchLight(donor, sampleColors(source.blurred, source.blurredPoints))
                    }
                }
            } else {
                null
            }
            // The one before is kept a frame longer: the screen may still be drawing it.
            previous?.bitmap?.let(buffers::release)
            previous = shown
            shown = frame
        }
        updateWindows()
    }

    /** The capture sees the drawing: stickers only, looked at without them now and then. */
    private fun showStickersOnly(frame: ScreenFrame) {
        val now = SystemClock.uptimeMillis()
        frame.bitmap?.let(buffers::release)
        // Taken without the stickers in the way.
        val clean = hiddenAt?.let { frame.capturedAt >= it + CLEAN_AFTER_MS } ?: false
        when {
            frame.faces.isNotEmpty() -> {
                poses = smoother.update(frame.faces, now)
                lastFaceAt = now
                if (hiddenAt != null) finishPulse()
            }
            clean -> {
                // Gone for real.
                smoother.clear()
                poses = emptyList()
                finishPulse()
            }
            else -> {
                poses = smoother.update(emptyList(), now)
                // Perhaps hidden by a sticker: look without them.
                if (poses.isNotEmpty() && hiddenAt == null &&
                    now - lastFaceAt > LOOK_AGAIN_AFTER_MS && now - pulseEndedAt > LOOK_AGAIN_AFTER_MS
                ) {
                    hiddenAt = now
                    main.postDelayed(endPulse, PULSE_MAX_MS)
                }
            }
        }
        updateWindows()
    }

    private fun finishPulse() {
        main.removeCallbacks(endPulse)
        hiddenAt = null
        pulseEndedAt = SystemClock.uptimeMillis()
        updateWindows()
    }

    /** Shows, hides, places and redraws the windows. */
    private fun updateWindows() {
        val hidden = appVisible.value
        stickerWindow?.let {
            it.hidden = hidden || hiddenAt != null
            it.invalidate()
        }
        faceWindow?.let { window ->
            val area = if (captureIsClean == true && look.changesFaces && !hidden) SceneRenderer.facesArea(poses) else null
            window.hidden = area == null
            if (area != null) window.place(area)
            window.invalidate()
        }
    }

    private fun drawStickers(canvas: Canvas) {
        if (probing) {
            canvas.drawRect(0f, 0f, MARK_SIZE, MARK_SIZE, markPaint.apply { color = Color.MAGENTA })
            canvas.drawRect(MARK_SIZE, 0f, MARK_SIZE * 2, MARK_SIZE, markPaint.apply { color = Color.GREEN })
            return
        }
        scene.drawStickers(canvas, poses, look)
    }

    private val markPaint = Paint()

    private fun drawFaces(canvas: Canvas) {
        val frame = shown ?: return
        scene.drawFaces(
            canvas, frame.bitmap, frame.toScreen, look, poses, liveMesh, skin,
            alwaysPicture = false, pictureUnderBlend = false, clipToFaces = true,
        )
    }

    /** Uses the look, and tells the analysis what it needs. */
    private fun applyLook() {
        val clean = captureIsClean == true
        keepPictures = clean && look.changesFaces
        findMesh = clean && look.needsMesh
        if (!findMesh) liveMesh.clear()
        if (captureIsClean == false && look.changesFaces && !warnedAboutSharing) {
            warnedAboutSharing = true
            Toast.makeText(
                this,
                if (Build.VERSION.SDK_INT >= 34) {
                    "Shapes, looks and face blend need \"Share one app\" when screen sharing starts. Showing stickers only"
                } else {
                    "Shapes, looks and face blend on the screen need Android 14. Showing stickers only"
                },
                Toast.LENGTH_LONG,
            ).show()
        }
        updateWindows()
    }

    /** Shows the filters kept under [keys], as set in the app, from now on. */
    private fun load(keys: Keys) {
        this.keys = keys
        worker?.post {
            val store = AdjustmentStore(this)
            val customStore = CustomStore(this)
            val contents by lazy { customStore.load() }

            val sticker: Sticker = Filter.entries.firstOrNull { it.name == keys.sticker }?.let(::BuiltInSticker)
                ?: pictures[keys.sticker]
                ?: contents.stickers.firstOrNull { it.key == keys.sticker }?.let { info ->
                    customStore.loadPicture(info.id)?.let { CustomStickerChoice(info, it) }
                }?.also { pictures[keys.sticker] = it }
                ?: BuiltInSticker(Filter.None)
            val reshape: Reshape = Shape.entries.map(::BuiltInReshape).firstOrNull { it.key == keys.shape }
                ?: contents.shapes.firstOrNull { CustomReshape(it).key == keys.shape }?.let(::CustomReshape)
                ?: BuiltInReshape(Shape.None)
            val blend = keys.face?.takeIf { canBlendLive }?.let { id ->
                faces[id] ?: contents.faces.firstOrNull { it.id == id }?.let { saved ->
                    customStore.loadPicture(id)?.let { BlendFace(id, saved.name, it, saved.points, saved.counts) }
                }?.also { faces[id] = it }
            }
            val makeover = Makeover.entries.firstOrNull { it.name == keys.makeover } ?: Makeover.None
            val loaded = Look(
                sticker = sticker,
                adjustment = store.adjustment(sticker.key),
                reshape = reshape,
                strength = store.strength(reshape.key),
                blend = blend,
                blendAmount = store.strength("blend", default = 0.9f),
                keepOwnFeatures = store.flag("blend.keep", default = true),
                makeover = makeover,
                makeoverStrength = store.strength(makeover.key),
            )
            main.post {
                if (this.keys != keys) return@post
                look = loaded
                applyLook()
            }
        }
    }

    private fun startInForeground() {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, "Screen mode", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val stop = PendingIntent.getService(
            this, 1, Intent(this, ScreenFilterService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val open = PendingIntent.getActivity(
            this, 2, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL)
        else @Suppress("DEPRECATION") Notification.Builder(this)
        val notification = builder
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle("Face filters are on your screen")
            .setContentText("Tap Stop to end screen mode")
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        // Several people on a call, say.
        private const val MAX_FACES = 4
        private const val ACTION_START = "start"
        private const val ACTION_UPDATE = "update"
        private const val ACTION_STOP = "stop"
        private const val EXTRA_RESULT_CODE = "resultCode"
        private const val EXTRA_DATA = "data"
        private const val EXTRA_STICKER = "sticker"
        private const val EXTRA_SHAPE = "shape"
        private const val EXTRA_FACE = "face"
        private const val EXTRA_MAKEOVER = "makeover"
        private const val CHANNEL = "screen"
        private const val NOTIFICATION_ID = 1

        /** The long side of the captured screen, in pixels. */
        private const val CAPTURE_SIZE = 1280f
        /** The mark looked for in the capture: two squares this many pixels across, top left. */
        private const val MARK_SIZE = 28f
        /** How long the mark is looked for, once frames come. */
        private const val PROBE_MS = 900L
        /** How long a frame comes after the stickers are hidden to be sure they aren't in it. */
        private const val CLEAN_AFTER_MS = 60L
        /** How long a face may be lost before looking without the stickers, and between looks. */
        private const val LOOK_AGAIN_AFTER_MS = 300L
        /** The longest the stickers stay hidden while looking. */
        private const val PULSE_MAX_MS = 400L

        private val _running = MutableStateFlow(false)
        val running: StateFlow<Boolean> = _running.asStateFlow()

        /** Whether the app itself is on the screen, where screen mode keeps out of its way. */
        val appVisible = MutableStateFlow(false)

        private fun Intent.putKeys(keys: Keys) = putExtra(EXTRA_STICKER, keys.sticker)
            .putExtra(EXTRA_SHAPE, keys.shape)
            .putExtra(EXTRA_FACE, keys.face)
            .putExtra(EXTRA_MAKEOVER, keys.makeover)

        /** Starts screen mode, with the screen capture the user allowed, showing [keys]. */
        fun start(context: Context, resultCode: Int, data: Intent, keys: Keys) {
            val intent = Intent(context, ScreenFilterService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_RESULT_CODE, resultCode)
                .putExtra(EXTRA_DATA, data)
                .putKeys(keys)
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent) else context.startService(intent)
        }

        /** Shows [keys], with their current settings, if screen mode is on. */
        fun update(context: Context, keys: Keys) {
            if (!running.value) return
            context.startService(Intent(context, ScreenFilterService::class.java).setAction(ACTION_UPDATE).putKeys(keys))
        }

        fun stop(context: Context) {
            if (!running.value) return
            context.startService(Intent(context, ScreenFilterService::class.java).setAction(ACTION_STOP))
        }
    }
}

/**
 * A window drawn over other apps by [paintContent], in screen pixels. Touches pass through it.
 *
 * Full screen, it is a little see-through: Android passes touches through windows from other apps
 * only if they are. Otherwise it is solid, and only as big as [place] makes it: there, touches
 * don't reach the app underneath. Main thread only.
 */
private class OverlayWindow(context: Context, private val fullScreen: Boolean, private val paintContent: (Canvas) -> Unit) {
    private val windowManager = context.getSystemService(WindowManager::class.java)
    private val view = DrawView(context)
    private var added = false
    private val params = WindowManager.LayoutParams(
        if (fullScreen) WindowManager.LayoutParams.MATCH_PARENT else 1,
        if (fullScreen) WindowManager.LayoutParams.MATCH_PARENT else 1,
        if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        // Android 12 blocks touches through overlays more opaque than this.
        alpha = if (fullScreen) 0.8f else 1f
        if (Build.VERSION.SDK_INT >= 28) {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        if (Build.VERSION.SDK_INT >= 30) fitInsetsTypes = 0
    }

    var hidden = false
        set(value) {
            field = value
            view.visibility = if (value) View.INVISIBLE else View.VISIBLE
        }

    fun add() {
        try {
            windowManager.addView(view, params)
            added = true
        } catch (e: RuntimeException) {
            // The permission can be withdrawn at any time.
            Log.w(TAG, "Could not draw over other apps", e)
        }
    }

    fun remove() {
        if (added) windowManager.removeView(view)
        added = false
    }

    fun invalidate() = view.invalidate()

    /** Covers [area] of the screen, moving only for changes big enough to see. */
    fun place(area: RectF) {
        if (fullScreen || !added) return
        val x = area.left.roundToInt()
        val y = area.top.roundToInt()
        val w = area.width().roundToInt().coerceAtLeast(1)
        val h = area.height().roundToInt().coerceAtLeast(1)
        // Grown a little past what is needed, so small moves stay inside.
        if (x >= params.x && y >= params.y && x + w <= params.x + params.width && y + h <= params.y + params.height &&
            abs(params.width - w) < w / 3 && abs(params.height - h) < h / 3
        ) {
            return
        }
        val margin = (max(w, h) * 0.12f).roundToInt()
        params.x = x - margin
        params.y = y - margin
        params.width = w + 2 * margin
        params.height = h + 2 * margin
        try {
            windowManager.updateViewLayout(view, params)
        } catch (e: RuntimeException) {
            Log.w(TAG, "Could not move the overlay", e)
        }
    }

    private inner class DrawView(context: Context) : View(context) {
        private val location = IntArray(2)

        override fun onDraw(canvas: Canvas) {
            // Drawn in screen pixels; the window may be anywhere.
            getLocationOnScreen(location)
            canvas.translate(-location[0].toFloat(), -location[1].toFloat())
            // Not View.draw, which would call onDraw again.
            this@OverlayWindow.paintContent(canvas)
        }
    }
}
