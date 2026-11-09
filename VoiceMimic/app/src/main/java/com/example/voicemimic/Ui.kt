package com.example.voicemimic

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

internal val Purple = Color(0xFF8B6CFF)
internal val Pink = Color(0xFFFF5C93)
internal val Bg = Color(0xFF14121F)
internal val CardBg = Color(0xFF221F33)

internal fun scoreColor(v: Int) = when {
    v >= 80 -> Color(0xFF4CD787)
    v >= 60 -> Color(0xFFB8E05A)
    v >= 40 -> Color(0xFFFFC145)
    else -> Color(0xFFFF7A59)
}

@Composable
fun App(vm: MimicViewModel) {
    val s = vm.s
    MaterialTheme(colorScheme = darkColorScheme(primary = Purple, secondary = Pink, background = Bg, surface = Bg)) {
        CompositionLocalProvider(LocalLayoutDirection provides if (vm.lang == Lang.HE) LayoutDirection.Rtl else LayoutDirection.Ltr) {
            Surface(Modifier.fillMaxSize(), color = Bg) {
                Box(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
                    when (val sc = vm.screen) {
                        Screen.Home -> HomeScreen(vm)
                        is Screen.Trim -> TrimScreen(vm, sc)
                        is Screen.Play -> PlayScreen(vm)
                        Screen.Race -> RaceScreen(vm)
                    }
                    vm.busy?.let { BusyOverlay(it) }
                    vm.message?.let { msg ->
                        LaunchedEffect(msg) { kotlinx.coroutines.delay(3500); vm.message = null }
                        Snackbar(Modifier.align(Alignment.BottomCenter).padding(16.dp),
                            action = { TextButton({ vm.message = null }) { Text("OK") } }) { Text(msg) }
                    }
                }
            }
        }
        BackHandler(enabled = vm.screen != Screen.Home) { vm.back() }
    }
}

@Composable
internal fun BusyOverlay(text: String) {
    Box(Modifier.fillMaxSize().background(Color(0xAA000000)).clickable(enabled = true) {}, contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Spacer(Modifier.height(12.dp))
            Text(text, color = Color.White)
        }
    }
}

/** Runs [action] once microphone permission is granted. */
@Composable
internal fun rememberMicGate(vm: MimicViewModel): (() -> Unit) -> Unit {
    val ctx = LocalContext.current
    var pending by remember { mutableStateOf<(() -> Unit)?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) pending?.invoke() else vm.message = vm.s.micDenied
        pending = null
    }
    return { action ->
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) action()
        else { pending = action; launcher.launch(Manifest.permission.RECORD_AUDIO) }
    }
}

@Composable
internal fun TopBar(title: String, vm: MimicViewModel, onBack: (() -> Unit)? = null, onRace: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        if (onBack != null) IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null) }
        else Spacer(Modifier.width(12.dp))
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f), maxLines = 1)
        if (onRace != null) IconButton(onRace) { Icon(Icons.Default.Flag, vm.s.raceButton, tint = Pink) }
        TextButton(onClick = vm::toggleLang) { Text(vm.s.langToggle, fontWeight = FontWeight.Bold) }
    }
}

// ================================================================ Home

@Composable
private fun HomeScreen(vm: MimicViewModel) {
    val s = vm.s
    val mic = rememberMicGate(vm)
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(vm::importUri) }
    var renaming by remember { mutableStateOf<VoiceEntry?>(null) }
    var deleting by remember { mutableStateOf<VoiceEntry?>(null) }

    Column(Modifier.fillMaxSize()) {
        TopBar(s.appName, vm)
        if (vm.recording) {
            Column(Modifier.weight(1f).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center) {
                Text(s.recordingTarget, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(24.dp))
                RecordButton(true, vm.recLevel) { vm.recordTarget() }
                Spacer(Modifier.height(16.dp))
                Text(s.sec(vm.recSec), style = MaterialTheme.typography.headlineSmall)
            }
            return@Column
        }
        Text(s.myVoices, Modifier.padding(horizontal = 20.dp, vertical = 8.dp), color = Color(0xFFB9B4D0))
        if (vm.voices.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                Text(s.noVoices, textAlign = TextAlign.Center, color = Color(0xFFB9B4D0))
            }
        } else {
            LazyColumn(Modifier.weight(1f), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp)) {
                items(vm.voices, key = { it.id }) { v ->
                    VoiceCard(vm, v, onRename = { renaming = v }, onDelete = { deleting = v })
                }
            }
        }
        FilledTonalButton({ vm.openRace() }, Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp).height(52.dp)) {
            Icon(Icons.Default.Flag, null, tint = Pink); Spacer(Modifier.width(8.dp)); Text(s.raceButton)
        }
        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button({ mic { vm.recordTarget() } }, Modifier.weight(1f).height(56.dp)) {
                Icon(Icons.Default.Mic, null); Spacer(Modifier.width(8.dp)); Text(s.recordVoice)
            }
            FilledTonalButton({ picker.launch(arrayOf("audio/*", "video/*")) }, Modifier.weight(1f).height(56.dp)) {
                Icon(Icons.Default.FileOpen, null); Spacer(Modifier.width(8.dp)); Text(s.importFile)
            }
        }
    }

    renaming?.let { v ->
        var name by remember(v.id) { mutableStateOf(v.name) }
        AlertDialog(onDismissRequest = { renaming = null },
            title = { Text(s.rename) },
            text = { OutlinedTextField(name, { name = it }, singleLine = true) },
            confirmButton = { TextButton({ vm.rename(v.id, name.trim().ifBlank { v.name }); renaming = null }) { Text(s.save) } },
            dismissButton = { TextButton({ renaming = null }) { Text(s.cancel) } })
    }
    deleting?.let { v ->
        AlertDialog(onDismissRequest = { deleting = null },
            title = { Text(s.deleteConfirm) }, text = { Text(v.name) },
            confirmButton = { TextButton({ vm.delete(v.id); deleting = null }) { Text(s.delete) } },
            dismissButton = { TextButton({ deleting = null }) { Text(s.cancel) } })
    }
}

@Composable
private fun VoiceCard(vm: MimicViewModel, v: VoiceEntry, onRename: () -> Unit, onDelete: () -> Unit) {
    val s = vm.s
    var menu by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth().padding(vertical = 6.dp).clickable { vm.openVoice(v.id) },
        colors = CardDefaults.cardColors(containerColor = CardBg)) {
        Row(Modifier.padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp).clip(CircleShape).background(Brush.linearGradient(listOf(Purple, Pink))),
                contentAlignment = Alignment.Center) { Icon(Icons.Default.RecordVoiceOver, null, tint = Color.White) }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(v.name, fontWeight = FontWeight.SemiBold, maxLines = 1)
                Text(s.sec(v.durationSec), color = Color(0xFF9C97B5), fontSize = 13.sp)
            }
            BestBadge(s.phraseShort, v.bestPhrase)
            Spacer(Modifier.width(6.dp))
            BestBadge(s.freeShort, v.bestFree)
            Box {
                IconButton({ menu = true }) { Icon(Icons.Default.MoreVert, null) }
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem({ Text(s.rename) }, { menu = false; onRename() })
                    DropdownMenuItem({ Text(s.delete) }, { menu = false; onDelete() })
                }
            }
        }
    }
}

@Composable
private fun BestBadge(label: String, v: Int) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(52.dp)) {
        Text(if (v < 0) "–" else "$v", color = if (v < 0) Color(0xFF6E6988) else scoreColor(v),
            fontWeight = FontWeight.Bold, fontSize = 18.sp)
        Text(label, fontSize = 10.sp, color = Color(0xFF9C97B5), maxLines = 1)
    }
}

// ================================================================ Trim

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TrimScreen(vm: MimicViewModel, sc: Screen.Trim) {
    val s = vm.s
    val x = sc.audio
    val dur = x.size / Dsp.SR.toFloat()
    var sel by remember(sc) { mutableStateOf(sc.selection) }
    var name by remember(sc) { mutableStateOf(sc.name) }
    val env = remember(sc) { envelope(x, 400) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        TopBar(s.trimTitle, vm, onBack = vm::back)
        Text(s.trimHint, Modifier.padding(horizontal = 20.dp), color = Color(0xFFB9B4D0))
        Spacer(Modifier.height(16.dp))
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            Column(Modifier.padding(horizontal = 16.dp)) {
                Canvas(Modifier.fillMaxWidth().height(140.dp).clip(RoundedCornerShape(12.dp)).background(CardBg)) {
                    val w = size.width; val h = size.height
                    val a = sel.start / dur * w; val b = sel.endInclusive / dur * w
                    drawRect(Purple.copy(alpha = 0.25f), Offset(a, 0f), Size(b - a, h))
                    val bw = w / env.size
                    for (i in env.indices) {
                        val cx = i * bw + bw / 2
                        val inSel = cx in a..b
                        val amp = env[i] * h * 0.45f
                        drawLine(if (inSel) Purple else Color(0xFF5A5575), Offset(cx, h / 2 - amp), Offset(cx, h / 2 + amp),
                            strokeWidth = max(1f, bw * 0.7f))
                    }
                    vm.playPos?.let { p -> val px = p / dur * w; drawLine(Pink, Offset(px, 0f), Offset(px, h), 3f) }
                }
                RangeSlider(value = sel, onValueChange = { r ->
                    // keep at most 30 s selected
                    sel = if (r.endInclusive - r.start > 30f) {
                        if (r.start != sel.start) r.start..(r.start + 30f) else (r.endInclusive - 30f)..r.endInclusive
                    } else r
                }, valueRange = 0f..dur)
                Row(Modifier.fillMaxWidth()) {
                    Text(s.sec(sel.start), color = Color(0xFF9C97B5))
                    Spacer(Modifier.weight(1f))
                    Text(s.sec(sel.endInclusive - sel.start), fontWeight = FontWeight.Bold)
                    Spacer(Modifier.weight(1f))
                    Text(s.sec(sel.endInclusive), color = Color(0xFF9C97B5))
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            label = { Text(s.name) }, singleLine = true)
        Spacer(Modifier.height(20.dp))
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            FilledTonalButton({
                val a = (sel.start * Dsp.SR).toInt(); val b = (sel.endInclusive * Dsp.SR).toInt().coerceAtMost(x.size)
                vm.playClip(x.copyOfRange(a, b), sel.start)
            }, Modifier.weight(1f)) { Icon(Icons.Default.PlayArrow, null); Text(s.playSelection) }
            Button({ vm.saveTarget(x, sel, name.trim()) }, Modifier.weight(1f)) { Text(s.save) }
        }
    }
}

/** Peak amplitude per bucket, scaled so the loudest bucket is 1. */
private fun envelope(x: FloatArray, buckets: Int): FloatArray {
    val n = min(buckets, max(1, x.size / 64))
    val out = FloatArray(n)
    val step = x.size / n.toFloat()
    for (i in 0 until n) {
        var p = 0f
        for (k in (i * step).toInt() until min(x.size, ((i + 1) * step).toInt())) p = max(p, kotlin.math.abs(x[k]))
        out[i] = p
    }
    val m = out.maxOrNull()?.takeIf { it > 0f } ?: 1f
    for (i in out.indices) out[i] /= m
    return out
}

// ================================================================ Play

@Composable
private fun PlayScreen(vm: MimicViewModel) {
    val s = vm.s
    val entry = vm.current()
    val t = vm.target
    val mic = rememberMicGate(vm)

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        TopBar(entry?.name ?: "", vm, onBack = vm::back, onRace = { vm.openRace(entry?.id) })
        if (t == null) return@Column

        val live = vm.mode == Mode.LIVE
        val say = vm.mode == Mode.SAY
        val compact = live && vm.recording   // keep the stop button on screen during a live test

        // ---- pitch chart: original vs you (in live mode only the play button, to leave room)
        if (!compact) Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp), colors = CardDefaults.cardColors(containerColor = CardBg)) {
            Column(Modifier.padding(12.dp)) {
                if (!live && !say) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Legend(Purple, s.target); Spacer(Modifier.width(16.dp)); Legend(Pink, s.you)
                    }
                    Spacer(Modifier.height(8.dp))
                    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                        PitchChart(vm, t)
                    }
                    Spacer(Modifier.height(8.dp))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(vm::playTarget) { Icon(Icons.Default.PlayArrow, null); Text(s.playTarget) }
                    if (!live && !say) FilledTonalButton(vm::playMine, enabled = vm.attempt != null && !vm.recording) {
                        Icon(Icons.Default.PlayArrow, null); Text(s.playMine)
                    }
                }
            }
        }

        // ---- mode
        Spacer(Modifier.height(if (compact) 4.dp else 14.dp))
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            listOf(Mode.PHRASE to s.phraseShort, Mode.FREE to s.freeShort, Mode.LIVE to s.liveShort, Mode.SAY to s.sayShort)
                .forEachIndexed { i, (m, label) ->
                    SegmentedButton(selected = vm.mode == m, onClick = { if (!vm.recording) vm.mode = m },
                        shape = SegmentedButtonDefaults.itemShape(i, 4), icon = {}) { Text(label, maxLines = 1, fontSize = 13.sp) }
                }
        }
        if (!compact) Text(when (vm.mode) { Mode.PHRASE -> s.modePhraseHint; Mode.FREE -> s.modeFreeHint; Mode.LIVE -> s.modeLiveHint; Mode.SAY -> s.modeSayHint },
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
            textAlign = TextAlign.Center, color = Color(0xFFB9B4D0), fontSize = 13.sp)

        if (live) {
            Spacer(Modifier.height(4.dp))
            LivePanel(vm, entry)
        }
        if (say) {
            Spacer(Modifier.height(4.dp))
            SayPanel(vm)
            Spacer(Modifier.height(24.dp))
            return@Column
        }

        // ---- record
        Spacer(Modifier.height(8.dp))
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            RecordButton(vm.recording, vm.recLevel) { mic { if (live) vm.toggleLive() else vm.recordAttempt() } }
            Spacer(Modifier.height(8.dp))
            Text(when {
                vm.recording && live -> s.liveListening + "  " + s.sec(vm.recSec)
                vm.recording -> s.listening + "  " + s.sec(vm.recSec)
                live -> s.liveStart
                else -> s.tapToImitate
            }, color = Color(0xFFB9B4D0))
        }

        // ---- result
        if (!live) vm.result?.let { r ->
            Spacer(Modifier.height(16.dp))
            ResultCard(vm, r, entry)
        }
        Spacer(Modifier.height(24.dp))
    }
}

/** Say mode: type text and hear it in the target voice (voice-cloning TTS). */
@Composable
private fun SayPanel(vm: MimicViewModel) {
    val s = vm.s
    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp), colors = CardDefaults.cardColors(containerColor = CardBg)) {
        Column(Modifier.padding(14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            if (!vm.clonerReady) {
                Text(s.sayDownloadInfo.format(Cloner.TOTAL_MB), color = Color(0xFFE2DEF5))
                Spacer(Modifier.height(12.dp))
                val p = vm.downloadProgress
                if (p != null) {
                    LinearProgressIndicator(progress = { p }, Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)),
                        trackColor = Color(0xFF34304A), drawStopIndicator = {})
                    Spacer(Modifier.height(6.dp))
                    Text("${s.downloading} ${(p * 100).toInt()}%  ·  ${(p * Cloner.TOTAL_MB).toInt()} / ${Cloner.TOTAL_MB} MB",
                        color = Color(0xFFB9B4D0), fontSize = 13.sp)
                    TextButton(vm::downloadCloner) { Text(s.cancel) }
                } else {
                    Button(vm::downloadCloner) { Icon(Icons.Default.Download, null); Spacer(Modifier.width(6.dp)); Text(s.sayDownload) }
                }
            } else {
                val busy = vm.sayProgress != null
                OutlinedTextField(vm.sayText, { vm.sayText = it.take(Cloner.MAX_CHARS) }, Modifier.fillMaxWidth(),
                    placeholder = { Text(s.sayPlaceholder) }, minLines = 3, maxLines = 6, enabled = !busy,
                    textStyle = androidx.compose.material3.LocalTextStyle.current.copy(textDirection = androidx.compose.ui.text.style.TextDirection.Content))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(s.saySpeed, fontSize = 14.sp)
                    Spacer(Modifier.width(10.dp))
                    Slider(vm.saySpeed, { vm.saySpeed = it }, Modifier.weight(1f), valueRange = 0.7f..1.3f, enabled = !busy)
                    Text("×" + String.format(java.util.Locale.US, "%.1f", vm.saySpeed), Modifier.width(44.dp), textAlign = TextAlign.End)
                }
                Button(vm::say, Modifier.fillMaxWidth().height(52.dp), enabled = busy || vm.sayText.isNotBlank(),
                    colors = ButtonDefaults.buttonColors(containerColor = if (busy) Pink else Purple)) {
                    if (busy) {
                        CircularProgressIndicator(Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
                        Spacer(Modifier.width(10.dp))
                        Text("${s.sayGenerating} ${s.sec(vm.sayProgress ?: 0f)}  ·  ${s.sayStop}")
                    } else {
                        Icon(Icons.Default.VolumeUp, null); Spacer(Modifier.width(8.dp)); Text(s.sayButton)
                    }
                }
                if (vm.said != null && !busy) {
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalButton(vm::playSaid, Modifier.weight(1f)) { Icon(Icons.Default.Replay, null); Spacer(Modifier.width(4.dp)); Text(s.sayAgain, maxLines = 1) }
                        FilledTonalButton(vm::imitateSaid, Modifier.weight(1f)) { Icon(Icons.Default.Mic, null); Spacer(Modifier.width(4.dp)); Text(s.sayImitate, maxLines = 1) }
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            Text(s.sayConsent, fontSize = 12.sp, color = Color(0xFF8E89A8), textAlign = TextAlign.Center)
        }
    }
}

/** Live test: score, its trend while talking, a pitch tuner and the score parts. */
@Composable
private fun LivePanel(vm: MimicViewModel, entry: VoiceEntry?) {
    val s = vm.s
    val score = vm.liveScore
    val shown by animateFloatAsState((score ?: 0).toFloat(), tween(500), label = "live")
    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp), colors = CardDefaults.cardColors(containerColor = CardBg)) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (score == null) "–" else "${shown.toInt()}", fontSize = 56.sp, fontWeight = FontWeight.Black,
                    color = if (score == null) Color(0xFF6E6988) else scoreColor(shown.toInt()),
                    modifier = Modifier.width(110.dp), textAlign = TextAlign.Center)
                Column(Modifier.weight(1f)) {
                    Text(when {
                        vm.recording && (score == null || vm.liveWaiting) -> s.keepTalking
                        score != null -> s.verdicts[(score / 20).coerceIn(0, 5)]
                        else -> ""
                    }, style = MaterialTheme.typography.titleMedium)
                    if (vm.liveSessionBest >= 0) Text("${s.sessionBest}: ${vm.liveSessionBest}", fontSize = 13.sp, color = Color(0xFFB9B4D0))
                    entry?.bestLive?.takeIf { it >= 0 }?.let {
                        Text("${s.best}: $it", fontSize = 13.sp, color = Color(0xFF9C97B5))
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                ScoreTrend(vm.liveHistory.toList(), vm.recSec)
                Spacer(Modifier.height(12.dp))
                PitchTuner(vm.liveOffset)
            }
            val off = vm.liveOffset
            Text(when {
                off == null -> s.tunerSilent
                off < -1f -> s.tunerHigher
                off > 1f -> s.tunerLower
                else -> s.tunerOk
            }, Modifier.fillMaxWidth().padding(top = 4.dp), textAlign = TextAlign.Center,
                color = if (off != null && kotlin.math.abs(off) <= 1f) Color(0xFF4CD787) else Color(0xFFE2DEF5))
            vm.liveResult?.let { r ->
                Spacer(Modifier.height(8.dp))
                PartBars(vm, r)
                r.tips.firstOrNull { it !is Tip.PitchHigher && it !is Tip.PitchLower }?.let {
                    Text("• " + s.tip(it), Modifier.fillMaxWidth().padding(top = 6.dp), color = Color(0xFFE2DEF5))
                }
            }
        }
    }
}

/** Score over the last minute of the live test. */
@Composable
private fun ScoreTrend(points: List<Pair<Float, Int>>, now: Float) {
    Canvas(Modifier.fillMaxWidth().height(72.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xFF1A1828))) {
        val w = size.width; val h = size.height
        for (g in listOf(40, 60, 80)) {
            val y = h - g / 100f * h
            drawLine(Color(0x22FFFFFF), Offset(0f, y), Offset(w, y), 1f)
        }
        val span = 60f
        val end = max(now, span)
        val start = end - span
        val path = Path(); var first = true
        var last: Pair<Float, Int>? = null
        for (p in points) {
            if (p.first < start) continue
            val x = (p.first - start) / span * w; val y = h - p.second / 100f * h
            if (first) path.moveTo(x, y) else path.lineTo(x, y)
            first = false; last = p
        }
        drawPath(path, Pink, style = Stroke(4f, cap = StrokeCap.Round))
        last?.let { drawCircle(scoreColor(it.second), 7f, Offset((it.first - start) / span * w, h - it.second / 100f * h)) }
    }
}

/** Where the current pitch is relative to the original's, −8..+8 semitones. */
@Composable
internal fun PitchTuner(offset: Float?) {
    val anim by animateFloatAsState(offset ?: 0f, tween(120), label = "tuner")
    Canvas(Modifier.fillMaxWidth().height(28.dp)) {
        val w = size.width; val h = size.height
        val range = 8f
        fun x(st: Float) = (st.coerceIn(-range, range) + range) / (2 * range) * w
        drawRoundRect(Color(0xFF34304A), size = Size(w, h), cornerRadius = androidx.compose.ui.geometry.CornerRadius(h / 2))
        drawRect(Color(0x664CD787), Offset(x(-1f), 0f), Size(x(1f) - x(-1f), h))
        drawLine(Color(0xAAFFFFFF), Offset(w / 2, 0f), Offset(w / 2, h), 2f)
        if (offset != null) {
            val c = if (kotlin.math.abs(offset) <= 1f) Color(0xFF4CD787) else Pink
            drawCircle(c, h * 0.42f, Offset(x(anim), h / 2))
        }
    }
}

@Composable
private fun PartBars(vm: MimicViewModel, r: ScoreResult) {
    for ((part, v) in r.parts) {
        Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(vm.s.part(part), Modifier.width(96.dp), fontSize = 14.sp)
            LinearProgressIndicator(progress = { v / 100f }, Modifier.weight(1f).height(8.dp).clip(RoundedCornerShape(4.dp)),
                color = scoreColor(v), trackColor = Color(0xFF34304A), drawStopIndicator = {})
            Text("$v", Modifier.width(36.dp), textAlign = TextAlign.End, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun Legend(c: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(width = 16.dp, height = 4.dp).background(c, RoundedCornerShape(2.dp)))
        Spacer(Modifier.width(6.dp))
        Text(label, fontSize = 12.sp, color = Color(0xFFB9B4D0))
    }
}

@Composable
private fun PitchChart(vm: MimicViewModel, t: VoiceProfile) {
    val tSec = t.pitch.size * Dsp.HOP / Dsp.SR.toFloat()
    // what to draw for "you": live while recording, aligned (phrase) or raw (free) after
    val mine: List<Pair<Float, Float>> = when {
        vm.recording -> vm.livePitch.toList()
        vm.result?.alignedPitch != null && !vm.playingMine ->
            vm.result!!.alignedPitch!!.mapIndexed { i, f -> Dsp.frameToSec(i) to f }
        vm.attempt != null -> vm.attempt!!.pitch.mapIndexed { i, f -> Dsp.frameToSec(i) to f }
        else -> emptyList()
    }
    val all = t.voiced + mine.map { it.second }.filter { it > 0f }
    val lo = (all.minOrNull() ?: 80f).coerceAtLeast(60f) / 1.15f
    val hi = (all.maxOrNull() ?: 400f).coerceAtMost(900f) * 1.15f
    val xMax = max(tSec, mine.lastOrNull()?.first ?: 0f).coerceAtLeast(1f)

    Canvas(Modifier.fillMaxWidth().height(170.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xFF1A1828))) {
        val w = size.width; val h = size.height
        fun y(hz: Float) = h - ((ln(hz / lo) / ln(hi / lo)).toFloat() * h)
        // semitone-ish grid: octave lines
        var g = 55f
        while (g < hi) { if (g > lo) drawLine(Color(0x22FFFFFF), Offset(0f, y(g)), Offset(w, y(g)), 1f); g *= 2f }

        fun series(pts: List<Pair<Float, Float>>, c: Color, width: Float) {
            val path = Path(); var open = false; var lastT = -1f
            for ((sec, hz) in pts) {
                if (hz <= 0f || sec - lastT > 0.08f) open = false
                if (hz > 0f) {
                    val px = sec / xMax * w; val py = y(hz)
                    if (open) path.lineTo(px, py) else path.moveTo(px, py)
                    open = true
                }
                lastT = sec
            }
            drawPath(path, c, style = Stroke(width, cap = StrokeCap.Round))
        }
        series(t.pitch.mapIndexed { i, f -> Dsp.frameToSec(i) to f }, Purple, 5f)
        series(mine, Pink, 4f)
        vm.playPos?.let { p -> val px = p / xMax * w; drawLine(Color.White.copy(alpha = 0.7f), Offset(px, 0f), Offset(px, h), 2f) }
    }
}

@Composable
private fun RecordButton(recording: Boolean, level: Float, onClick: () -> Unit) {
    val ring by animateFloatAsState(if (recording) 1f + level * 0.35f else 1f, tween(80), label = "ring")
    Box(Modifier.size(120.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val r = size.minDimension / 2 * 0.72f * ring
            drawCircle(Pink.copy(alpha = if (recording) 0.35f else 0.15f), radius = r.coerceAtMost(size.minDimension / 2))
        }
        Box(Modifier.size(84.dp).clip(CircleShape)
            .background(Brush.linearGradient(if (recording) listOf(Pink, Color(0xFFFF8A5C)) else listOf(Purple, Pink)))
            .clickable(onClick = onClick), contentAlignment = Alignment.Center) {
            Icon(if (recording) Icons.Default.Stop else Icons.Default.Mic, null, tint = Color.White, modifier = Modifier.size(40.dp))
        }
    }
}

@Composable
private fun ResultCard(vm: MimicViewModel, r: ScoreResult, entry: VoiceEntry?) {
    val s = vm.s
    val shown = remember(r) { Animatable(0f) }
    LaunchedEffect(r) { shown.animateTo(r.total.toFloat(), tween(900)) }
    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp), colors = CardDefaults.cardColors(containerColor = CardBg)) {
        Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("${shown.value.toInt()}", fontSize = 64.sp, fontWeight = FontWeight.Black, color = scoreColor(r.total))
            Text(s.verdicts[(r.total / 20).coerceIn(0, 5)], style = MaterialTheme.typography.titleMedium)
            val best = if (vm.mode == Mode.PHRASE) entry?.bestPhrase else entry?.bestFree
            if (best != null && best >= 0) {
                Text((if (vm.bestNew) "★ " else "") + "${s.best}: $best", color = if (vm.bestNew) Color(0xFFFFC145) else Color(0xFF9C97B5),
                    fontSize = 13.sp, modifier = Modifier.padding(top = 2.dp))
            }
            Spacer(Modifier.height(12.dp))
            PartBars(vm, r)
            if (r.tips.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                for (tip in r.tips) {
                    Text("• " + s.tip(tip), Modifier.fillMaxWidth().padding(vertical = 2.dp), color = Color(0xFFE2DEF5))
                }
            }
            Spacer(Modifier.height(8.dp))
            Button({ vm.recordAttempt() }, colors = ButtonDefaults.buttonColors(containerColor = Pink)) {
                Icon(Icons.Default.Mic, null); Spacer(Modifier.width(6.dp)); Text(s.tapToImitate)
            }
        }
    }
}
