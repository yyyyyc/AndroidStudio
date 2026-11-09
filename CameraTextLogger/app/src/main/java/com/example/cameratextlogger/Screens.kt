package com.example.cameratextlogger

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

private val HighlightColor = Color(0xFFFFEB3B)
private val FoundRowColor = Color(0x33FFEB3B)
private val FileMarkColor = Color(0x66FFEB3B)

@Composable
private fun Modifier.screenPadding() =
    fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(16.dp)

@Composable
private fun ScreenTitle(title: String, action: @Composable () -> Unit = {}) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
        action()
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
    )
}

@Composable
private fun RadioRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Text(label)
    }
}

@Composable
private fun SwitchRow(
    label: String,
    checked: Boolean,
    enabled: Boolean = true,
    onChange: (Boolean) -> Unit,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = checked, enabled = enabled, onCheckedChange = onChange)
    }
}

@Composable
private fun NumberField(
    label: String,
    value: String,
    maxDigits: Int = 3,
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = { new -> onChange(new.filter { it.isDigit() }.take(maxDigits)) },
        label = { Text(label) },
        singleLine = true,
        isError = parseMinimum(value) == null,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth(),
    )
}

private fun logEntriesLabel(count: Int) = if (count == 1) "1 log entry" else "$count log entries"

private fun imagesLabel(count: Int) = if (count == 1) "1 image" else "$count images"

/** [search] with [alternative] added after a `|`, unless it is already there. */
private fun withAlternative(search: String, alternative: String): String {
    val existing = search.split("|").map { it.trim() }
    return when {
        existing.any { it.equals(alternative, ignoreCase = true) } -> search
        search.isBlank() -> alternative
        else -> search.trimEnd().trimEnd('|') + "|" + alternative
    }
}

/** A positive number the user typed, or null if it isn't a usable one. */
private fun parseMinimum(value: String) = value.toIntOrNull()?.takeIf { it >= 1 }

private fun Session.describe() = buildList {
    if (source != SessionSource.CAMERA) add(source.label)
    if (bothCameras) add("Front + back")
    add(if (findText.isEmpty()) "No search text" else "Find: $findText")
    if (notifyWhenFound) add("Notify")
    if (beepWhenFound) add("Beep")
    if (captureImageWhenFound) add("Capture images")
    if (highlightFound) add("Highlight")
    add(language.label)
    if (readBy == ReadBy.PARAGRAPHS) add("By paragraph")
    add(scanRate.label)
    add(
        when (saveMode) {
            SaveMode.ALL -> "Save all text"
            SaveMode.MIN_CHARACTERS -> "Save $saveMinCharacters+ characters"
            SaveMode.CONTAINS -> "Save text with \"$saveContains\""
        }
    )
    maxLogEntries?.let { add("Max $it entries") }
}.joinToString(" · ")

@Composable
fun HomeScreen(viewModel: AppViewModel) {
    Column(Modifier.screenPadding()) {
        ScreenTitle("Sessions") {
            TextButton(onClick = viewModel::openSettings) { Text("Settings") }
        }
        Button(onClick = viewModel::openNewSession, modifier = Modifier.fillMaxWidth()) {
            Text("New session")
        }
        Spacer(Modifier.height(8.dp))
        if (viewModel.sessions.isEmpty()) {
            Text(
                "No sessions yet.",
                color = Color.Gray,
                modifier = Modifier.padding(top = 16.dp),
            )
        }
        LazyColumn {
            items(viewModel.sessions, key = { it.session.id }) { summary ->
                SessionRow(summary) { viewModel.openLog(summary.session) }
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun SessionRow(summary: SessionSummary, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 12.dp)) {
        Text(summary.session.name, fontWeight = FontWeight.Bold)
        Text(formatTimestamp(summary.session.startedAtMs), fontSize = 13.sp, color = Color.LightGray)
        Text(summary.session.describe(), fontSize = 13.sp, color = Color.LightGray)
        val found = if (summary.session.findText.isEmpty()) "" else " · ${summary.foundCount} found"
        val images = if (summary.imageCount == 0) "" else " · ${imagesLabel(summary.imageCount)}"
        Text(
            logEntriesLabel(summary.entryCount) + found + images,
            fontSize = 13.sp,
            color = Color.LightGray,
        )
    }
}

@Composable
fun SettingsScreen(viewModel: AppViewModel) {
    var minCharacters by rememberSaveable {
        mutableStateOf(viewModel.settings.minCharacters.toString())
    }
    var ignoreSymbolsOnly by rememberSaveable { mutableStateOf(viewModel.settings.ignoreSymbolsOnly) }
    val minimum = parseMinimum(minCharacters)

    Column(Modifier.screenPadding().verticalScroll(rememberScrollState())) {
        ScreenTitle("General settings")
        Text(
            "Text that fails these rules is ignored in every session, unless it contains the " +
                "session's search text.",
            color = Color.LightGray,
        )
        Spacer(Modifier.height(16.dp))
        NumberField("Minimum characters", minCharacters) { minCharacters = it }
        Spacer(Modifier.height(8.dp))
        SwitchRow("Ignore text with only symbols", ignoreSymbolsOnly) { ignoreSymbolsOnly = it }
        Spacer(Modifier.height(16.dp))
        Button(
            enabled = minimum != null,
            onClick = { viewModel.saveSettings(GeneralSettings(minimum ?: 1, ignoreSymbolsOnly)) },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Save")
        }
    }
}

private fun Context.isGranted(permission: String) =
    ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

private val notificationPermission =
    if (Build.VERSION.SDK_INT >= 33) Manifest.permission.POST_NOTIFICATIONS else null

/**
 * Returns a function that asks for what the session needs, then starts scanning. Camera sessions
 * need the camera; screen sessions need permission to capture the screen, asked every time, and
 * to display over other apps if they highlight. Photo or video sessions ask for the file to read
 * every time. Notifications are asked for when the session sends them, and for screen sessions,
 * whose notification has the Stop button. [onCameraDenied] runs if the camera is refused.
 */
@Composable
private fun rememberStartScanning(
    viewModel: AppViewModel,
    onCameraDenied: () -> Unit,
): (Session) -> Unit {
    val context = LocalContext.current
    var pending by remember { mutableStateOf<Session?>(null) }

    val screenCaptureLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val session = pending ?: return@rememberLauncherForActivityResult
        pending = null
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            viewModel.startScanning(session, result)
        } else {
            Toast.makeText(context, "Screen capture was not allowed", Toast.LENGTH_LONG).show()
        }
    }

    fun captureScreen(session: Session) {
        pending = session
        val manager = context.getSystemService(MediaProjectionManager::class.java)
        screenCaptureLauncher.launch(manager.createScreenCaptureIntent())
    }

    // "Display over other apps" has no permission dialog: it is switched on in Settings.
    val overlayLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        val session = pending ?: return@rememberLauncherForActivityResult
        if (!HighlightOverlay.canDraw(context)) {
            Toast.makeText(
                context,
                "Highlighting is off: \"Display over other apps\" is not allowed",
                Toast.LENGTH_LONG,
            ).show()
        }
        captureScreen(session)
    }

    // The system photo picker needs no permission.
    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        val session = pending ?: return@rememberLauncherForActivityResult
        pending = null
        if (uri != null) {
            viewModel.startScanning(session, file = uri)
        } else {
            Toast.makeText(context, "No photo or video was chosen", Toast.LENGTH_LONG).show()
        }
    }

    fun proceed(session: Session) {
        when {
            session.source == SessionSource.CAMERA -> viewModel.startScanning(session)
            session.source == SessionSource.FILE -> {
                pending = session
                filePicker.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)
                )
            }
            session.highlightFound && !HighlightOverlay.canDraw(context) -> {
                pending = session
                Toast.makeText(
                    context,
                    "Allow \"Display over other apps\" to highlight found text",
                    Toast.LENGTH_LONG,
                ).show()
                overlayLauncher.launch(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:${context.packageName}"),
                    )
                )
            }
            else -> captureScreen(session)
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        val session = pending ?: return@rememberLauncherForActivityResult
        pending = null
        if (session.source == SessionSource.CAMERA && !context.isGranted(Manifest.permission.CAMERA)) {
            onCameraDenied()
            return@rememberLauncherForActivityResult
        }
        if (session.notifyWhenFound && notificationPermission != null &&
            !context.isGranted(notificationPermission)
        ) {
            Toast.makeText(context, "Notifications are not allowed", Toast.LENGTH_LONG).show()
        }
        proceed(session)
    }

    return { session ->
        val needsNotifications = session.notifyWhenFound || session.source == SessionSource.SCREEN
        val missing = listOfNotNull(
            Manifest.permission.CAMERA.takeIf { session.source == SessionSource.CAMERA },
            notificationPermission?.takeIf { needsNotifications },
        ).filterNot(context::isGranted)
        if (missing.isEmpty()) {
            proceed(session)
        } else {
            pending = session
            permissionLauncher.launch(missing.toTypedArray())
        }
    }
}

/** Creates a session, or edits [existing]. */
@Composable
fun SessionFormScreen(viewModel: AppViewModel, existing: Session?) {
    val key = existing?.id
    var name by rememberSaveable(key) { mutableStateOf(existing?.name ?: "") }
    val defaultName = existing?.name ?: "Session ${viewModel.sessions.size + 1}"
    var findText by rememberSaveable(key) { mutableStateOf(existing?.findText ?: "") }
    var notify by rememberSaveable(key) { mutableStateOf(existing?.notifyWhenFound ?: false) }
    // New sessions beep, save images and read Hebrew + English unless turned off.
    var beep by rememberSaveable(key) { mutableStateOf(existing?.beepWhenFound ?: true) }
    var captureImage by rememberSaveable(key) {
        mutableStateOf(existing?.captureImageWhenFound ?: true)
    }
    var language by rememberSaveable(key) {
        mutableStateOf(existing?.language ?: RecognitionLanguage.HEBREW_AND_ENGLISH)
    }
    var readBy by rememberSaveable(key) { mutableStateOf(existing?.readBy ?: ReadBy.LINES) }
    var source by rememberSaveable(key) {
        mutableStateOf(existing?.source ?: SessionSource.CAMERA)
    }
    var highlight by rememberSaveable(key) { mutableStateOf(existing?.highlightFound ?: true) }
    var bothCameras by rememberSaveable(key) { mutableStateOf(existing?.bothCameras ?: false) }
    var scanRate by rememberSaveable(key) {
        mutableStateOf(existing?.scanRate ?: ScanRate.EVERY_SECOND)
    }
    var saveMode by rememberSaveable(key) { mutableStateOf(existing?.saveMode ?: SaveMode.ALL) }
    var saveMinCharacters by rememberSaveable(key) {
        mutableStateOf((existing?.saveMinCharacters ?: 5).toString())
    }
    var saveContains by rememberSaveable(key) { mutableStateOf(existing?.saveContains ?: "") }
    var limitLog by rememberSaveable(key) { mutableStateOf(existing?.maxLogEntries != null) }
    var maxLogEntries by rememberSaveable(key) {
        mutableStateOf((existing?.maxLogEntries ?: 1000).toString())
    }
    var cameraDenied by rememberSaveable { mutableStateOf(false) }
    val startScanning = rememberStartScanning(viewModel) { cameraDenied = true }

    val normalizedFind = normalizeLine(findText)
    val hasFindText = !FindPattern(normalizedFind).isEmpty
    val minimum = parseMinimum(saveMinCharacters)
    val normalizedContains = normalizeLine(saveContains)
    val notifyWhenFound = notify && hasFindText
    val logLimit = parseMinimum(maxLogEntries)
    val valid = (!limitLog || logLimit != null) && when (saveMode) {
        SaveMode.ALL -> true
        SaveMode.MIN_CHARACTERS -> minimum != null
        SaveMode.CONTAINS -> normalizedContains.isNotEmpty()
    }

    fun buildSession(): Session {
        val now = System.currentTimeMillis()
        return Session(
            id = existing?.id ?: now.toString(),
            name = normalizeLine(name).ifEmpty { defaultName },
            startedAtMs = existing?.startedAtMs ?: now,
            findText = normalizedFind,
            notifyWhenFound = notifyWhenFound,
            scanRate = scanRate,
            saveMode = saveMode,
            saveMinCharacters = minimum ?: 1,
            saveContains = normalizedContains,
            maxLogEntries = logLimit.takeIf { limitLog },
            language = language,
            beepWhenFound = beep && hasFindText,
            captureImageWhenFound = captureImage && hasFindText,
            readBy = readBy,
            source = source,
            highlightFound = highlight && hasFindText,
            bothCameras = bothCameras && source == SessionSource.CAMERA,
        )
    }

    Column(Modifier.screenPadding().verticalScroll(rememberScrollState())) {
        ScreenTitle(if (existing == null) "New session" else "Edit session")

        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text("Session name") },
            placeholder = { Text(defaultName) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        SectionLabel("Source")
        SessionSource.entries.forEach { option ->
            RadioRow(option.label, source == option) { source = option }
        }
        if (source == SessionSource.CAMERA) {
            SwitchRow("Use the front and back cameras together", bothCameras) { bothCameras = it }
        }
        val sourceHint = when (source) {
            SessionSource.CAMERA -> if (bothCameras) {
                "The screen is split between the back and front cameras, and both are read. " +
                    "Only some phones can run two cameras at once; others use the back camera."
            } else {
                null
            }
            SessionSource.SCREEN ->
                "Reads the phone's screen while you use other apps. Android asks for permission " +
                    "each time scanning starts. Scanning pauses while this app is open."
            SessionSource.FILE ->
                "You choose a photo or video each time scanning starts. A video is read one " +
                    "frame per scan interval of video time, or two per second when continuous."
        }
        if (sourceHint != null) {
            Text(
                sourceHint,
                fontSize = 13.sp,
                color = Color.LightGray,
                modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
            )
        }
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = findText,
            onValueChange = { findText = it },
            label = { Text("Text to find (optional)") },
            supportingText = {
                Text(
                    "AAA|BBB finds either · ab% starts with ab · %ab ends with ab · _ any character · " +
                        "# any digit · @phone and the others below find kinds of text"
                )
            },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        // Adds a ready-made detector to the search, as another alternative.
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Detector.entries.forEach { detector ->
                AssistChip(
                    onClick = { findText = withAlternative(findText, detector.token) },
                    label = { Text(detector.label) },
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        SwitchRow(
            "Send a notification when found",
            checked = notifyWhenFound,
            enabled = hasFindText,
        ) { notify = it }
        SwitchRow(
            "Beep when found",
            checked = beep && hasFindText,
            enabled = hasFindText,
        ) { beep = it }
        SwitchRow(
            "Save image when found",
            checked = captureImage && hasFindText,
            enabled = hasFindText,
        ) { captureImage = it }
        SwitchRow(
            "Highlight found text",
            checked = highlight && hasFindText,
            enabled = hasFindText,
        ) { highlight = it }
        if (highlight && hasFindText) {
            Text(
                when (source) {
                    SessionSource.CAMERA ->
                        "Marks found text in yellow on the camera picture. The marks update " +
                            "after each scan, so they lag behind when the camera moves."
                    SessionSource.FILE ->
                        "Marks found text in yellow on the photo or video frame being read."
                    SessionSource.SCREEN ->
                        "Marks found text in yellow over the app you're using. Needs \"Display " +
                            "over other apps\", allowed once in Settings. The marks update after " +
                            "each scan, so they lag behind scrolling."
                },
                fontSize = 13.sp,
                color = Color.LightGray,
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }

        SectionLabel("Language")
        RecognitionLanguage.entries.forEach { option ->
            RadioRow(option.label, language == option) { language = option }
        }

        SectionLabel("Read by")
        ReadBy.entries.forEach { option ->
            RadioRow(option.label, readBy == option) { readBy = option }
        }
        if (readBy == ReadBy.PARAGRAPHS) {
            Text(
                "Each paragraph is one log entry, and text to find can span its lines. " +
                    "A misread word makes the paragraph count as new, so it may be logged again.",
                fontSize = 13.sp,
                color = Color.LightGray,
                modifier = Modifier.padding(top = 4.dp),
            )
        }

        SectionLabel("Scan rate")
        ScanRate.entries.forEach { rate ->
            RadioRow(rate.label, scanRate == rate) { scanRate = rate }
        }

        SectionLabel("What to save")
        RadioRow("All scanned text", saveMode == SaveMode.ALL) { saveMode = SaveMode.ALL }
        RadioRow("Text with a minimum length", saveMode == SaveMode.MIN_CHARACTERS) {
            saveMode = SaveMode.MIN_CHARACTERS
        }
        if (saveMode == SaveMode.MIN_CHARACTERS) {
            NumberField("Minimum characters", saveMinCharacters) { saveMinCharacters = it }
        }
        RadioRow("Text containing a string", saveMode == SaveMode.CONTAINS) {
            saveMode = SaveMode.CONTAINS
        }
        if (saveMode == SaveMode.CONTAINS) {
            OutlinedTextField(
                value = saveContains,
                onValueChange = { saveContains = it },
                label = { Text("String") },
                singleLine = true,
                isError = normalizedContains.isEmpty(),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (normalizedFind.isNotEmpty() && saveMode != SaveMode.ALL) {
            Text(
                "Text matching \"$normalizedFind\" is always saved.",
                fontSize = 13.sp,
                color = Color.LightGray,
                modifier = Modifier.padding(top = 4.dp),
            )
        }

        SectionLabel("Log size")
        SwitchRow("Limit log size", limitLog) { limitLog = it }
        if (limitLog) {
            NumberField("Maximum log entries", maxLogEntries, maxDigits = 6) { maxLogEntries = it }
            Text(
                "When the log is full, scanning continues but nothing more is saved.",
                fontSize = 13.sp,
                color = Color.LightGray,
                modifier = Modifier.padding(top = 4.dp),
            )
        }

        if (cameraDenied) {
            Text(
                "This app needs the camera to scan text.",
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 16.dp),
            )
        }
        Spacer(Modifier.height(16.dp))
        Button(
            enabled = valid,
            onClick = {
                if (existing == null) startScanning(buildSession())
                else viewModel.updateSession(buildSession())
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (existing == null) "Start session" else "Save changes")
        }
    }
}

@Composable
fun ScanningScreen(viewModel: AppViewModel, session: Session) {
    Column(Modifier.fillMaxSize()) {
        when (session.source) {
            SessionSource.SCREEN -> ScreenScanInfo(Modifier.fillMaxWidth().weight(1f))
            SessionSource.FILE -> FileScanView(viewModel, Modifier.fillMaxWidth().weight(2f))
            SessionSource.CAMERA -> CameraPreview(
                language = session.language,
                readBy = session.readBy,
                intervalMs = session.scanRate.intervalMs,
                highlight = session.findPattern.takeIf { session.highlightFound && !it.isEmpty },
                bothCameras = session.bothCameras,
                onLines = viewModel::onLinesRecognized,
                modifier = Modifier.fillMaxWidth().weight(2f),
            )
        }
        val insets = WindowInsets.safeDrawing
            .only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom)
        Column(Modifier.fillMaxWidth().weight(1f).windowInsetsPadding(insets).padding(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(session.name, fontWeight = FontWeight.Bold)
                    Text(session.describe(), fontSize = 12.sp, color = Color.LightGray)
                    if (session.findText.isNotEmpty()) {
                        val images = if (session.captureImageWhenFound) {
                            " · Images saved: ${viewModel.liveImageCount}"
                        } else {
                            ""
                        }
                        Text("Times found: ${viewModel.liveFoundCount}$images", fontSize = 13.sp)
                    }
                    if (viewModel.liveLogFull) {
                        Text(
                            "Log full: no longer saving",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
                val done = session.source == SessionSource.FILE && viewModel.fileFinished
                OutlinedButton(onClick = viewModel::stopSession) { Text(if (done) "Done" else "Stop") }
            }
            if (viewModel.liveEntries.isEmpty()) {
                val hint = when {
                    session.source != SessionSource.FILE -> "Point the camera at some text…"
                    viewModel.fileFinished -> "No new text to log."
                    else -> "Reading…"
                }
                Text(hint, color = Color.Gray)
            }
            val listState = rememberLazyListState()
            val newest = viewModel.liveEntries.firstOrNull()
            LaunchedEffect(newest) { listState.scrollToItem(0) }
            LazyColumn(state = listState) {
                items(viewModel.liveEntries) { LogEntryRow(it, session.findPattern) }
            }
        }
    }
}

private fun formatVideoTime(ms: Long): String {
    val seconds = ms / 1000
    return "%d:%02d".format(seconds / 60, seconds % 60)
}

/** Shown instead of the camera in photo or video sessions: the frame last read, with its marks. */
@Composable
private fun FileScanView(viewModel: AppViewModel, modifier: Modifier) {
    val frame = viewModel.fileFrame
    Box(modifier.background(Color.Black)) {
        if (frame != null) {
            val image = remember(frame) { frame.image.asImageBitmap() }
            Image(
                bitmap = image,
                contentDescription = "The image being read",
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
            val marks = viewModel.fileMarks
            Canvas(Modifier.fillMaxSize()) {
                // The image is fitted inside the view, centered.
                val scale = minOf(size.width / image.width, size.height / image.height)
                val left = (size.width - image.width * scale) / 2
                val top = (size.height - image.height * scale) / 2
                val padding = 4.dp.toPx()
                for (mark in marks) {
                    drawRoundRect(
                        color = FileMarkColor,
                        topLeft = Offset(
                            left + mark.left * scale - padding,
                            top + mark.top * scale - padding,
                        ),
                        size = Size(
                            (mark.right - mark.left) * scale + 2 * padding,
                            (mark.bottom - mark.top) * scale + 2 * padding,
                        ),
                        cornerRadius = CornerRadius(padding),
                    )
                }
            }
        }
        val position = frame?.positionMs
        val status = when {
            viewModel.fileFinished -> "Finished"
            frame == null -> "Reading…"
            position == null -> "Photo"
            else -> "Video ${formatVideoTime(position)} / ${formatVideoTime(frame.durationMs ?: 0)}"
        }
        Text(
            status,
            color = Color.White,
            modifier = Modifier
                .align(Alignment.TopStart)
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .background(Color(0x99000000))
                .padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

/** Shown instead of the camera in screen sessions. Sends the app to the background once. */
@Composable
private fun ScreenScanInfo(modifier: Modifier) {
    val context = LocalContext.current
    var movedToBack by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (!movedToBack) {
            movedToBack = true
            (context as? Activity)?.moveTaskToBack(true)
        }
    }
    Column(
        modifier = modifier.windowInsetsPadding(WindowInsets.safeDrawing).padding(16.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text("Scanning the screen", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Switch to the app you want to read. Scanning is paused while this app is open. " +
                "Stop it here or from its notification.",
            color = Color.LightGray,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

private enum class PendingAction { CLEAR_LOG, DELETE }

@Composable
fun SessionLogScreen(viewModel: AppViewModel, session: Session) {
    var cameraDenied by rememberSaveable { mutableStateOf(false) }
    var confirm by rememberSaveable { mutableStateOf<PendingAction?>(null) }
    val startScanning = rememberStartScanning(viewModel) { cameraDenied = true }

    when (confirm) {
        PendingAction.CLEAR_LOG -> ConfirmDialog(
            title = "Clear log?",
            text = "All entries and images in \"${session.name}\" will be erased. " +
                "The session's options are kept.",
            confirmLabel = "Clear",
            onConfirm = { confirm = null; viewModel.clearLog(session) },
            onDismiss = { confirm = null },
        )
        PendingAction.DELETE -> ConfirmDialog(
            title = "Delete session?",
            text = "\"${session.name}\", its log and its images will be deleted.",
            confirmLabel = "Delete",
            onConfirm = { confirm = null; viewModel.deleteSession(session) },
            onDismiss = { confirm = null },
        )
        null -> {}
    }

    Column(Modifier.screenPadding()) {
        ScreenTitle(session.name) {
            TextButton(onClick = viewModel::back) { Text("Close") }
        }
        Text(formatTimestamp(session.startedAtMs), fontSize = 13.sp, color = Color.LightGray)
        Text(session.describe(), fontSize = 13.sp, color = Color.LightGray)

        Button(
            onClick = { startScanning(session) },
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        ) {
            Text("Start scanning")
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = { viewModel.openEditSession(session) }) { Text("Edit") }
            TextButton(onClick = { confirm = PendingAction.CLEAR_LOG }) { Text("Clear log") }
            TextButton(onClick = { confirm = PendingAction.DELETE }) {
                Text("Delete", color = MaterialTheme.colorScheme.error)
            }
        }
        if (cameraDenied) {
            Text(
                "This app needs the camera to scan text.",
                color = MaterialTheme.colorScheme.error,
            )
        }
        HorizontalDivider(Modifier.padding(vertical = 8.dp))

        val entries = viewModel.viewedLog
        if (entries != null) {
            val found = entries.count { session.findPattern.matches(it.text) }
            val foundText = if (session.findText.isEmpty()) "" else " · $found found"
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    logEntriesLabel(entries.size) + foundText,
                    fontSize = 13.sp,
                    color = Color.LightGray,
                    modifier = Modifier.weight(1f),
                )
                val images = viewModel.viewedImages
                if (images.isNotEmpty() || session.captureImageWhenFound) {
                    TextButton(
                        enabled = images.isNotEmpty(),
                        onClick = { viewModel.openImages(session) },
                    ) {
                        Text("Images (${images.size})")
                    }
                }
            }
            if (entries.isEmpty()) {
                Text("The log is empty.", color = Color.Gray)
            }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                items(entries.asReversed()) { LogEntryRow(it, session.findPattern) }
            }
        }
    }
}

@Composable
private fun ConfirmDialog(
    title: String,
    text: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(confirmLabel, color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** The images a session saved when it found its text, newest first. Tap one to see it full screen. */
@Composable
fun SessionImagesScreen(viewModel: AppViewModel, session: Session) {
    var opened by remember { mutableStateOf<ImageEntry?>(null) }

    Column(Modifier.screenPadding()) {
        ScreenTitle("Images: ${session.name}") {
            TextButton(onClick = viewModel::back) { Text("Close") }
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(viewModel.viewedImages.asReversed(), key = { it.fileName }) { entry ->
                Column(Modifier.fillMaxWidth().clickable { opened = entry }) {
                    SavedImage(viewModel.imageFile(session, entry), entry.text, sampleSize = 4)
                    LogEntryRow(LogEntry(entry.timestamp, entry.text), session.findPattern)
                }
            }
        }
    }

    opened?.let { entry ->
        Dialog(
            onDismissRequest = { opened = null },
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black)
                    .clickable { opened = null },
                verticalArrangement = Arrangement.Center,
            ) {
                SavedImage(viewModel.imageFile(session, entry), entry.text, sampleSize = 1)
                LogEntryRow(LogEntry(entry.timestamp, entry.text), session.findPattern)
            }
        }
    }
}

/** Loads [file] off the main thread, shrunk by [sampleSize] in each direction. */
@Composable
private fun SavedImage(file: File, description: String, sampleSize: Int) {
    val image by produceState<ImageBitmap?>(null, file, sampleSize) {
        value = withContext(Dispatchers.IO) {
            val options = BitmapFactory.Options().apply { inSampleSize = sampleSize }
            BitmapFactory.decodeFile(file.path, options)?.asImageBitmap()
        }
    }
    val loaded = image
    if (loaded == null) {
        Spacer(Modifier.fillMaxWidth().height(200.dp))
    } else {
        Image(
            bitmap = loaded,
            contentDescription = description,
            modifier = Modifier.fillMaxWidth().aspectRatio(loaded.width.toFloat() / loaded.height),
        )
    }
}

@Composable
private fun LogEntryRow(entry: LogEntry, pattern: FindPattern) {
    val ranges = pattern.ranges(entry.text)
    val text = buildAnnotatedString {
        append(entry.timestamp)
        append("  ")
        var position = 0
        for (range in ranges) {
            append(entry.text.substring(position, range.first))
            withStyle(
                SpanStyle(
                    background = HighlightColor,
                    color = Color.Black,
                    fontWeight = FontWeight.Bold,
                )
            ) {
                append(entry.text.substring(range))
            }
            position = range.last + 1
        }
        append(entry.text.substring(position))
    }
    Text(
        text = text,
        fontFamily = FontFamily.Monospace,
        fontSize = 13.sp,
        modifier = Modifier
            .fillMaxWidth()
            .background(if (ranges.isEmpty()) Color.Transparent else FoundRowColor),
    )
}
