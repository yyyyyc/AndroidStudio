package com.example.cameratextlogger

import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.DisplayMetrics
import android.view.WindowInsets
import android.view.WindowManager
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.IntentCompat
import java.util.concurrent.atomic.AtomicBoolean

/** Connects the screen-scanning service to the session running in the app. */
object ScreenScanBridge {

    interface Listener {
        /** Called on the analysis thread for every screenshot read. */
        fun onLines(lines: List<String>, frame: FrameCapture)

        /** Called on the main thread when screen capture ends for any reason. */
        fun onCaptureStopped()
    }

    @Volatile
    var listener: Listener? = null

    /** The app's own screen is not scanned: it would read its own log back. */
    @Volatile
    var appVisible = false
}

private const val CHANNEL_ID = "screen_scanning"
private const val NOTIFICATION_ID = 1
private const val ACTION_STOP = "com.example.cameratextlogger.STOP_SCREEN_SCAN"
private const val EXTRA_RESULT_CODE = "resultCode"
private const val EXTRA_RESULT_DATA = "resultData"
private const val EXTRA_SESSION_NAME = "sessionName"
private const val EXTRA_LANGUAGE = "language"
private const val EXTRA_READ_BY = "readBy"
private const val EXTRA_INTERVAL_MS = "intervalMs"
private const val EXTRA_FIND_TEXT = "findText"
private const val EXTRA_HIGHLIGHT = "highlight"

/**
 * Captures the screen while a screen session runs and passes the text in it to
 * [ScreenScanBridge]. Android requires this to be a foreground service with a notification.
 */
class ScreenScanService : Service() {

    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var textReader: BitmapTextReader? = null
    private var throttle = ScanThrottle(0)
    private val busy = AtomicBoolean(false)
    private val thread = HandlerThread("ScreenCapture").apply { start() }
    private val handler = Handler(thread.looper)
    private val mainHandler = Handler(Looper.getMainLooper())

    // The newest screenshot not read yet. Used on the capture thread only.
    private var waiting: Image? = null
    private val readWaitingTask = Runnable { readWaiting() }
    private var readBy = ReadBy.LINES
    private var findPattern = FindPattern("")

    // Used on the main thread only. Null when the session doesn't highlight.
    private var overlay: HighlightOverlay? = null
    private var width = 0
    private var height = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP || projection != null) {
            if (intent?.action == ACTION_STOP) stopSelf()
            return START_NOT_STICKY
        }
        val data = intent?.let { IntentCompat.getParcelableExtra(it, EXTRA_RESULT_DATA, Intent::class.java) }
        if (data == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        // Android 14 requires the foreground service to be running before the projection starts.
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, buildNotification(intent.getStringExtra(EXTRA_SESSION_NAME) ?: ""),
            if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION else 0,
        )
        readBy = ReadBy.valueOf(intent.getStringExtra(EXTRA_READ_BY)!!)
        textReader = BitmapTextReader(
            applicationContext,
            analysisExecutor,
            RecognitionLanguage.valueOf(intent.getStringExtra(EXTRA_LANGUAGE)!!),
            readBy,
        )
        throttle = ScanThrottle(intent.getLongExtra(EXTRA_INTERVAL_MS, 1_000))
        findPattern = FindPattern(intent.getStringExtra(EXTRA_FIND_TEXT) ?: "")
        if (intent.getBooleanExtra(EXTRA_HIGHLIGHT, false) && !findPattern.isEmpty) {
            overlay = HighlightOverlay(this)
        }

        val manager = getSystemService(MediaProjectionManager::class.java)
        val newProjection = manager.getMediaProjection(intent.getIntExtra(EXTRA_RESULT_CODE, 0), data)
        if (newProjection == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        projection = newProjection
        // Android ends the capture itself in some cases, such as when another app starts one.
        newProjection.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                stopSelf()
            }
        }, handler)

        val metrics = screenMetrics()
        width = metrics.widthPixels
        height = metrics.heightPixels
        reader = newReader()
        display = newProjection.createVirtualDisplay(
            "CameraTextLogger", width, height, metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader!!.surface, null, handler,
        )
        return START_NOT_STICKY
    }

    // On rotation the capture is resized, or the screenshots would be squeezed.
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val currentDisplay = display ?: return
        val metrics = screenMetrics()
        if (metrics.widthPixels == width && metrics.heightPixels == height) return
        overlay?.clear() // The text has moved.
        width = metrics.widthPixels
        height = metrics.heightPixels
        val oldReader = reader
        reader = newReader()
        currentDisplay.resize(width, height, metrics.densityDpi)
        currentDisplay.surface = reader!!.surface
        // On the capture thread, so a screenshot from the old reader isn't in use as it closes.
        handler.post {
            waiting?.close()
            waiting = null
            oldReader?.close()
        }
    }

    override fun onDestroy() {
        overlay?.remove()
        overlay = null // Highlights still being worked out are then dropped.
        display?.release()
        val lastReader = reader
        handler.removeCallbacks(readWaitingTask)
        handler.post {
            waiting?.close()
            waiting = null
            lastReader?.close()
        }
        projection?.stop()
        textReader?.close()
        thread.quitSafely() // After the tasks already posted.
        ScreenScanBridge.listener?.onCaptureStopped()
        super.onDestroy()
    }

    // One screenshot can be waiting while the next arrives.
    private fun newReader() = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3).apply {
        setOnImageAvailableListener({ onImageAvailable(it) }, handler)
    }

    private fun onImageAvailable(imageReader: ImageReader) {
        // Every image must be taken and closed, or the capture stalls.
        waiting?.close()
        waiting = imageReader.acquireLatestImage()
        readWaiting()
    }

    /**
     * Reads the newest screenshot once the last one is read and the scan rate allows. The screen
     * sends a screenshot only when it changes, so one that came too soon is kept, not dropped:
     * it may be the last before the screen settles.
     */
    private fun readWaiting() {
        val image = waiting ?: return
        if (ScreenScanBridge.appVisible) {
            image.close()
            waiting = null
            // Highlights belong to the app they were found in, not this one.
            mainHandler.post { overlay?.clear() }
            return
        }
        if (busy.get()) return // Read when the current one is done.
        val waitMs = throttle.waitMs(SystemClock.elapsedRealtime())
        if (waitMs > 0) {
            handler.removeCallbacks(readWaitingTask)
            handler.postDelayed(readWaitingTask, waitMs)
            return
        }
        throttle.shouldScan(SystemClock.elapsedRealtime())
        waiting = null
        val bars = systemBars()
        val screenshot = image.use { withoutSystemBars(it.toBitmap(), bars) }

        busy.set(true)
        analysisExecutor.execute {
            val currentReader = textReader
            if (currentReader == null) {
                busy.set(false)
                return@execute
            }
            currentReader.read(screenshot) { text ->
                try {
                    showHighlights(text.lines, bars)
                    ScreenScanBridge.listener?.onLines(text.entries) { screenshot }
                } finally {
                    busy.set(false)
                    handler.post(readWaitingTask)
                }
            }
        }
    }

    // The screenshot was cropped by the system bars, so its boxes are moved back to the screen.
    private fun showHighlights(lines: List<TextLine>, bars: Rect) {
        val boxes = highlightBoxes(lines, findPattern, readBy).map {
            HighlightBox(
                it.left + bars.left, it.top + bars.top, it.right + bars.left, it.bottom + bars.top,
            )
        }
        mainHandler.post { overlay?.show(boxes) }
    }

    private fun Image.toBitmap(): Bitmap {
        // Rows can be padded beyond the image width, so the copy is cropped afterwards.
        val plane = planes[0]
        val rowPixels = plane.rowStride / plane.pixelStride
        val padded = Bitmap.createBitmap(rowPixels, height, Bitmap.Config.ARGB_8888)
        padded.copyPixelsFromBuffer(plane.buffer)
        return if (rowPixels == width) padded else Bitmap.createBitmap(padded, 0, 0, width, height)
    }

    // The status and navigation bars are cut off: their icons read as junk text, like "8 0 = @".
    private fun withoutSystemBars(screenshot: Bitmap, bars: Rect): Bitmap {
        val cropWidth = screenshot.width - bars.left - bars.right
        val cropHeight = screenshot.height - bars.top - bars.bottom
        if (cropWidth <= 0 || cropHeight <= 0) return screenshot
        return Bitmap.createBitmap(screenshot, bars.left, bars.top, cropWidth, cropHeight)
    }

    private fun systemBars(): Rect {
        val windowManager = getSystemService(WindowManager::class.java)
        if (Build.VERSION.SDK_INT >= 30) {
            val insets = windowManager.currentWindowMetrics.windowInsets
                .getInsetsIgnoringVisibility(WindowInsets.Type.systemBars())
            return Rect(insets.left, insets.top, insets.right, insets.bottom)
        }
        fun dimension(name: String): Int {
            @Suppress("DiscouragedApi") // The bar heights are not public before Android 11.
            val id = resources.getIdentifier(name, "dimen", "android")
            return if (id > 0) resources.getDimensionPixelSize(id) else 0
        }
        return Rect(0, dimension("status_bar_height"), 0, dimension("navigation_bar_height"))
    }

    private fun screenMetrics(): DisplayMetrics {
        val metrics = DisplayMetrics()
        val windowManager = getSystemService(WindowManager::class.java)
        if (Build.VERSION.SDK_INT >= 30) {
            val bounds = windowManager.currentWindowMetrics.bounds
            metrics.widthPixels = bounds.width()
            metrics.heightPixels = bounds.height()
            metrics.densityDpi = resources.configuration.densityDpi
        } else {
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealMetrics(metrics)
        }
        return metrics
    }

    private fun buildNotification(sessionName: String): android.app.Notification {
        val manager = NotificationManagerCompat.from(this)
        manager.createNotificationChannel(
            NotificationChannelCompat.Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_LOW)
                .setName("Screen scanning")
                .build()
        )
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 0,
            Intent(this, ScreenScanService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("Scanning the screen")
            .setContentText(sessionName)
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(0, "Stop", stop)
            .build()
    }

    companion object {

        fun start(context: Context, session: Session, resultCode: Int, resultData: Intent) {
            val intent = Intent(context, ScreenScanService::class.java)
                .putExtra(EXTRA_RESULT_CODE, resultCode)
                .putExtra(EXTRA_RESULT_DATA, resultData)
                .putExtra(EXTRA_SESSION_NAME, session.name)
                .putExtra(EXTRA_LANGUAGE, session.language.name)
                .putExtra(EXTRA_READ_BY, session.readBy.name)
                .putExtra(EXTRA_INTERVAL_MS, session.scanRate.intervalMs)
                .putExtra(EXTRA_FIND_TEXT, session.findText)
                .putExtra(EXTRA_HIGHLIGHT, session.highlightFound)
            androidx.core.content.ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ScreenScanService::class.java))
        }
    }
}
