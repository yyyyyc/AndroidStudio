package com.example.cameratextlogger

import android.app.Application
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import android.widget.Toast
import androidx.activity.result.ActivityResult
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private const val MAX_LIVE_ENTRIES = 200

sealed interface Screen {
    data object Home : Screen
    data object Settings : Screen
    data object NewSession : Screen
    data class EditSession(val session: Session) : Screen
    data class Scanning(val session: Session) : Screen
    data class SessionLog(val session: Session) : Screen
    data class SessionImages(val session: Session) : Screen
}

class AppViewModel(application: Application) : AndroidViewModel(application) {

    private class ActiveSession(
        val session: Session,
        val processor: LineProcessor,
        val notifier: FoundNotifier?,
        val beeper: FoundBeeper?,
        var savedEntries: Int,
    )

    private val store = SessionStore(
        File(application.getExternalFilesDir(null) ?: application.filesDir, "sessions")
    )
    private val settingsStore = SettingsStore(application)

    // Written on the main thread, read on the analysis thread.
    @Volatile
    private var active: ActiveSession? = null

    var screen by mutableStateOf<Screen>(Screen.Home)
        private set
    var settings by mutableStateOf(settingsStore.load())
        private set
    var sessions by mutableStateOf<List<SessionSummary>>(emptyList())
        private set

    /** The session being viewed, null while it loads. */
    var viewedLog by mutableStateOf<List<LogEntry>?>(null)
        private set
    var viewedImages by mutableStateOf<List<ImageEntry>>(emptyList())
        private set

    val liveEntries = mutableStateListOf<LogEntry>()
    var liveFoundCount by mutableIntStateOf(0)
        private set
    var liveLogFull by mutableStateOf(false)
        private set
    var liveImageCount by mutableIntStateOf(0)
        private set

    /** Photo or video sessions: the frame last read, where its found text is, and progress. */
    var fileFrame by mutableStateOf<FileFrame?>(null)
        private set
    var fileMarks by mutableStateOf<List<HighlightBox>>(emptyList())
        private set
    var fileFinished by mutableStateOf(false)
        private set
    private var fileScanner: FileScanner? = null

    init {
        refreshSessions()
    }

    fun openSettings() {
        screen = Screen.Settings
    }

    fun openNewSession() {
        screen = Screen.NewSession
    }

    fun openEditSession(session: Session) {
        screen = Screen.EditSession(session)
    }

    fun saveSettings(newSettings: GeneralSettings) {
        settingsStore.save(newSettings)
        settings = newSettings
        goHome()
    }

    fun openLog(session: Session) {
        viewedLog = null
        viewedImages = emptyList()
        screen = Screen.SessionLog(session)
        viewModelScope.launch {
            val (log, images) = withContext(Dispatchers.IO) {
                store.readLog(session) to store.readImages(session)
            }
            viewedLog = log
            viewedImages = images
        }
    }

    fun openImages(session: Session) {
        screen = Screen.SessionImages(session)
    }

    fun imageFile(session: Session, entry: ImageEntry) = store.imageFile(session, entry)

    /**
     * Starts a new session, or continues an existing one, adding to its log. A screen session
     * needs the user's permission to capture the screen: [screenCapture] is that result. A photo
     * or video session reads [file].
     */
    fun startScanning(session: Session, screenCapture: ActivityResult? = null, file: Uri? = null) {
        val application = getApplication<Application>()
        liveEntries.clear()
        liveFoundCount = 0
        liveImageCount = 0
        fileFrame = null
        fileMarks = emptyList()
        fileFinished = false
        viewModelScope.launch {
            val saved = withContext(Dispatchers.IO) {
                store.save(session)
                store.readLog(session).size
            }
            liveLogFull = session.maxLogEntries?.let { saved >= it } ?: false
            active = ActiveSession(
                session,
                LineProcessor(session, settings),
                if (session.notifyWhenFound) FoundNotifier(application, session) else null,
                if (session.beepWhenFound) FoundBeeper() else null,
                saved,
            )
            val captureData = screenCapture?.data
            if (session.source == SessionSource.SCREEN && captureData != null) {
                ScreenScanBridge.listener = screenListener
                ScreenScanService.start(application, session, screenCapture.resultCode, captureData)
            }
            if (session.source == SessionSource.FILE && file != null) {
                fileScanner = FileScanner(application, file, session, fileListener(session)).also {
                    it.start()
                }
            }
            screen = Screen.Scanning(session)
        }
    }

    private fun fileListener(session: Session) = object : FileScanner.Listener {
        // Video frames are read faster or slower than they play, so the time that decides when
        // text has left view and may be logged again is the video's own.
        private val startMs = SystemClock.elapsedRealtime()

        override fun onFrame(frame: FileFrame, text: ReadText) {
            val marks = if (session.highlightFound) {
                highlightBoxes(text.lines, session.findPattern, session.readBy)
            } else {
                emptyList()
            }
            onLinesRecognized(text.entries, { frame.image }, startMs + (frame.positionMs ?: 0))
            viewModelScope.launch {
                fileFrame = frame
                fileMarks = marks
            }
        }

        override fun onFinished(error: String?) {
            viewModelScope.launch {
                fileFinished = true
                if (error != null) Toast.makeText(getApplication(), error, Toast.LENGTH_LONG).show()
            }
        }
    }

    private val screenListener = object : ScreenScanBridge.Listener {
        override fun onLines(lines: List<String>, frame: FrameCapture) = onLinesRecognized(lines, frame)

        override fun onCaptureStopped() {
            // Stopped from its notification, or by Android, rather than by the Stop button.
            if (active?.session?.source != SessionSource.SCREEN) return
            Toast.makeText(getApplication(), "Screen capture ended", Toast.LENGTH_LONG).show()
            stopSession()
        }
    }

    fun stopSession() {
        val current = active ?: return
        active = null
        current.beeper?.release()
        fileScanner?.cancel()
        fileScanner = null
        fileFrame = null
        if (current.session.source == SessionSource.SCREEN) {
            ScreenScanBridge.listener = null
            ScreenScanService.stop(getApplication())
        }
        openLog(current.session)
    }

    // The app is closing, for example swiped away from recent apps: screen scanning stops with it.
    override fun onCleared() {
        active?.beeper?.release()
        fileScanner?.cancel()
        if (active?.session?.source == SessionSource.SCREEN) {
            ScreenScanBridge.listener = null
            ScreenScanService.stop(getApplication())
        }
    }

    fun updateSession(session: Session) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { store.save(session) }
            openLog(session)
        }
    }

    fun clearLog(session: Session) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { store.clearLog(session) }
            openLog(session)
        }
    }

    fun deleteSession(session: Session) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { store.delete(session) }
            goHome()
        }
    }

    fun back() {
        when (val current = screen) {
            is Screen.Scanning -> stopSession()
            is Screen.EditSession -> openLog(current.session)
            is Screen.SessionImages -> openLog(current.session)
            else -> goHome()
        }
    }

    // Runs on the analysis thread, so the file writes stay off the main thread.
    fun onLinesRecognized(
        lines: List<String>,
        frame: FrameCapture,
        clockMs: Long = SystemClock.elapsedRealtime(),
    ) {
        val current = active ?: return
        val processed = current.processor.process(lines, clockMs)
        if (processed.isEmpty()) return

        val now = System.currentTimeMillis()
        val fitting = processed.take(current.session.linesThatFit(processed.size, current.savedEntries))
        val entries = fitting.map { current.store(it.text, now) }
        current.savedEntries += entries.size
        val logFull = entries.size < processed.size ||
            current.savedEntries == current.session.maxLogEntries

        // One image per scan, and none once the log is full: images are part of the log.
        val imageText = fitting.firstOrNull { it.found }?.text
            ?.takeIf { current.session.captureImageWhenFound }
        if (imageText != null) {
            try {
                store.saveImage(current.session, frame.capture(), imageText, now)
            } catch (e: Exception) {
                Log.e(TAG, "Could not save image", e)
            }
        }

        // Found text still counts, beeps and notifies once the log is full.
        val found = processed.filter { it.found }
        found.firstOrNull()?.let {
            current.beeper?.beep()
            current.notifier?.notifyFound(it.text, SystemClock.elapsedRealtime())
        }
        viewModelScope.launch {
            liveEntries.addAll(0, entries)
            if (liveEntries.size > MAX_LIVE_ENTRIES) {
                liveEntries.removeRange(MAX_LIVE_ENTRIES, liveEntries.size)
            }
            liveFoundCount += found.size
            if (imageText != null) liveImageCount++
            if (logFull) liveLogFull = true
        }
    }

    private fun ActiveSession.store(text: String, nowMs: Long) =
        store.append(session, text, nowMs)

    private fun goHome() {
        screen = Screen.Home
        refreshSessions()
    }

    private fun refreshSessions() {
        viewModelScope.launch {
            sessions = withContext(Dispatchers.IO) { store.list() }
        }
    }
}
