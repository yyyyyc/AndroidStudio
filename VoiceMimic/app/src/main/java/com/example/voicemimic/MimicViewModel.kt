package com.example.voicemimic

import android.app.Application
import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

sealed interface Screen {
    data object Home : Screen
    class Trim(val audio: FloatArray, val name: String, val selection: ClosedFloatingPointRange<Float>) : Screen
    class Play(val id: String) : Screen
    data object Race : Screen
}

class MimicViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = app.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val store = VoiceStore(app)
    private val embedder = Embedder(app.assets)
    private val recorder = Recorder()
    private val player = Player(viewModelScope)
    private val main = Handler(Looper.getMainLooper())

    var lang by mutableStateOf(
        prefs.getString("lang", null)?.let { Lang.valueOf(it) }
            ?: if (java.util.Locale.getDefault().language in setOf("iw", "he")) Lang.HE else Lang.EN
    )
        private set
    val s get() = Strings.of(lang)

    var voices by mutableStateOf(store.load()); private set
    var screen by mutableStateOf<Screen>(Screen.Home); private set
    var busy by mutableStateOf<String?>(null); private set
    var message by mutableStateOf<String?>(null)

    // recording (target or attempt)
    var recording by mutableStateOf(false); private set
    var recSec by mutableStateOf(0f); private set
    var recLevel by mutableStateOf(0f); private set
    val livePitch = mutableStateListOf<Pair<Float, Float>>()

    // play screen
    var target by mutableStateOf<VoiceProfile?>(null); private set
    var mode by mutableStateOf(Mode.PHRASE)
    var result by mutableStateOf<ScoreResult?>(null); private set
    var attempt by mutableStateOf<VoiceProfile?>(null); private set
    var playPos by mutableStateOf<Float?>(null); private set
    var playingMine by mutableStateOf(false); private set
    var bestNew by mutableStateOf(false); private set

    // live mode
    var liveScore by mutableStateOf<Int?>(null); private set
    var liveResult by mutableStateOf<ScoreResult?>(null); private set
    var liveWaiting by mutableStateOf(false); private set
    var liveOffset by mutableStateOf<Float?>(null); private set   // semitones vs the target's pitch
    var liveSessionBest by mutableStateOf(-1); private set
    val liveHistory = mutableStateListOf<Pair<Float, Int>>()
    private var liveJob: Job? = null
    private val recentPitch = ArrayDeque<Float>()

    // say mode (voice cloning text-to-speech)
    private val cloner = Cloner(app)
    var clonerReady by mutableStateOf(cloner.isDownloaded); private set
    var downloadProgress by mutableStateOf<Float?>(null); private set
    var sayText by mutableStateOf("")
    var saySpeed by mutableStateOf(1f)
    var sayProgress by mutableStateOf<Float?>(null); private set   // seconds elapsed, while generating
    var said by mutableStateOf<FloatArray?>(null); private set
    @Volatile private var sayCancel = false
    @Volatile private var downloadCancel = false

    // bird race game
    val race = RaceController(app, viewModelScope, embedder, store, { s }, { message = it })

    init {
        viewModelScope.launch { background { runCatching { embedder.warmUp() } } }
    }

    fun toggleLang() {
        lang = if (lang == Lang.EN) Lang.HE else Lang.EN
        prefs.edit().putString("lang", lang.name).apply()
    }

    fun back() {
        if (screen == Screen.Race && race.phase != RacePhase.LOBBY) { race.leave(); return }
        race.stop()
        stopAll()
        if (screen is Screen.Play) { target = null; result = null; attempt = null }
        screen = Screen.Home
    }

    private fun stopAll() {
        recorder.stop(); player.stop(); playPos = null
        liveJob?.cancel(); liveJob = null
        sayCancel = true
    }

    // ------------------------------------------------------------ creating a target

    fun recordTarget() {
        if (recording) { recorder.stop(); return }
        startRecording(30f) { x ->
            if (x.size < Dsp.SR) { message = s.tooShort; return@startRecording }
            openTrim(x, "${s.defaultName} ${voices.size + 1}")
        }
    }

    fun importUri(uri: Uri) {
        stopAll()
        busy = s.decoding
        viewModelScope.launch {
            val x = runCatching { background { Decoder.decode(getApplication(), uri) } }
                .onFailure { android.util.Log.w("VoiceMimic", "decode failed", it) }.getOrNull()
            if (x == null || x.size < Dsp.SR) { busy = null; message = s.importFailed; return@launch }
            val sel = background { suggestSelection(x) }
            busy = null
            if (sel == null) { message = s.noSpeechFound; return@launch }
            val name = runCatching {
                getApplication<Application>().contentResolver.query(uri, null, null, null, null)?.use { c ->
                    val i = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (i >= 0 && c.moveToFirst()) c.getString(i)?.substringBeforeLast('.') else null
                }
            }.getOrNull() ?: "${s.defaultName} ${voices.size + 1}"
            screen = Screen.Trim(x, name.take(40), sel)
        }
    }

    private fun openTrim(x: FloatArray, name: String) {
        busy = s.analyzing
        viewModelScope.launch {
            val sel = background { suggestSelection(x) }
            busy = null
            if (sel == null) message = s.noSpeechFound else screen = Screen.Trim(x, name, sel)
        }
    }

    /** Initial selection for the trim screen: first speech, up to 12 s; null if no speech. */
    private fun suggestSelection(x: FloatArray): ClosedFloatingPointRange<Float>? {
        val b = Dsp.speechBounds(x) ?: return null
        val st = b.first / Dsp.SR.toFloat()
        val en = minOf(b.last / Dsp.SR.toFloat(), st + 12f)
        return st..en
    }

    fun playClip(x: FloatArray, from: Float = 0f) {
        playingMine = false
        player.play(x) { p -> main.post { playPos = p?.plus(from) } }
    }

    fun saveTarget(x: FloatArray, sel: ClosedFloatingPointRange<Float>, name: String) {
        player.stop()
        val a = (sel.start * Dsp.SR).toInt().coerceIn(0, x.size)
        val b = (sel.endInclusive * Dsp.SR).toInt().coerceIn(a, x.size)
        val clip = x.copyOfRange(a, b)
        val bounds = Dsp.speechBounds(clip)
        if (bounds == null || bounds.last - bounds.first < Dsp.SR * 1.5) { message = s.tooShort; return }
        val trimmed = normalize(clip.copyOfRange(bounds.first, bounds.last + 1))
        voices = store.add(name.ifBlank { "${s.defaultName} ${voices.size + 1}" }, trimmed, voices)
        openVoice(voices.first().id)
    }

    fun rename(id: String, name: String) {
        voices = voices.map { if (it.id == id) it.copy(name = name) else it }
        store.save(voices)
    }

    fun delete(id: String) { voices = store.delete(id, voices) }

    // ------------------------------------------------------------ imitating

    fun openVoice(id: String) {
        stopAll()
        result = null; attempt = null; target = null; bestNew = false
        livePitch.clear(); resetLive()
        said = null; sayProgress = null
        screen = Screen.Play(id)
        busy = s.preparing
        viewModelScope.launch {
            val p = runCatching {
                background {
                    val x = Wav.read(store.wav(id))
                    VoiceProfile.build(x) { embedder.embed(it) }
                }
            }.getOrNull()
            busy = null
            if (p == null) { message = s.importFailed; screen = Screen.Home; return@launch }
            if ((screen as? Screen.Play)?.id == id) target = p
            if (p.embedding == null) message = s.noTimbre
        }
    }

    fun current(): VoiceEntry? = (screen as? Screen.Play)?.let { sc -> voices.firstOrNull { it.id == sc.id } }

    fun recordAttempt() {
        if (recording) { recorder.stop(); return }
        val t = target ?: return
        result = null; attempt = null; bestNew = false
        livePitch.clear()
        val maxSec = (t.audio.size / Dsp.SR.toFloat() * 2 + 4).coerceIn(8f, 30f)
        startRecording(maxSec) { x -> analyze(t, x) }
    }

    private fun resetLive() {
        liveScore = null; liveResult = null; liveWaiting = false; liveOffset = null
        liveSessionBest = -1; liveHistory.clear()
    }

    /** Live test: keeps listening and re-scores the last few seconds about every 0.7 s. */
    fun toggleLive() {
        if (recording) { recorder.stop(); return }
        val t = target ?: return
        resetLive()
        livePitch.clear()
        startRecording(180f) { _ -> finishLive() }
        liveJob = viewModelScope.launch {
            var smooth = -1.0
            while (isActive && recording) {
                delay(700)
                if (!recording) break
                val w = recorder.tail(Scorer.LIVE_WINDOW_SEC * Dsp.SR)
                val r = background { Scorer.live(t, normalize(w)) { embedder.embed(it) } }
                if (!recording) break
                if (r == null) { liveWaiting = true; continue }
                liveWaiting = false
                smooth = if (smooth < 0) r.total.toDouble() else smooth * 0.5 + r.total * 0.5
                val v = smooth.roundToInt()
                liveScore = v; liveResult = r
                liveHistory.add(recSec to v)
                if (v > liveSessionBest) liveSessionBest = v
            }
        }
    }

    private fun finishLive() {
        liveJob?.cancel(); liveJob = null
        liveOffset = null; liveWaiting = false
        val cur = current() ?: return
        if (liveSessionBest > cur.bestLive) {
            voices = voices.map { if (it.id == cur.id) it.copy(bestLive = liveSessionBest) else it }
            store.save(voices)
        }
    }

    private fun updateTuner(pitch: Float) {
        recentPitch.addLast(pitch)
        while (recentPitch.size > 8) recentPitch.removeFirst()   // last ~0.4 s
        val v = recentPitch.filter { it > 0f }.sorted()
        val ref = target?.medianPitch ?: 0f
        liveOffset = if (v.size >= 3 && ref > 0f) Dsp.semitones(v[v.size / 2], ref) else null
    }

    private fun analyze(t: VoiceProfile, x: FloatArray) {
        val b = Dsp.speechBounds(x)
        if (b == null || b.last - b.first < Dsp.SR / 2) { message = s.noSpeechAttempt; return }
        val m = mode
        busy = s.analyzing
        viewModelScope.launch {
            val (prof, res) = background {
                val clip = normalize(x.copyOfRange(b.first, b.last + 1))
                val p = VoiceProfile.build(clip) { embedder.embed(it) }
                p to Scorer.score(t, p, m)
            }
            busy = null
            if (res.error != null) { message = s.noSpeechAttempt; return@launch }
            attempt = prof; result = res
            val cur = current() ?: return@launch
            val prev = if (m == Mode.PHRASE) cur.bestPhrase else cur.bestFree
            if (res.total > prev) {
                bestNew = prev >= 0
                voices = voices.map {
                    if (it.id != cur.id) it else if (m == Mode.PHRASE) it.copy(bestPhrase = res.total) else it.copy(bestFree = res.total)
                }
                store.save(voices)
            }
        }
    }

    fun playTarget() { target?.let { playingMine = false; player.play(it.audio) { p -> main.post { playPos = p } } } }

    fun playMine() {
        attempt?.let { playingMine = true; player.play(it.audio) { p -> main.post { playPos = p } } }
    }

    private fun startRecording(maxSec: Float, onDone: (FloatArray) -> Unit) {
        player.stop(); playPos = null
        recording = true; recSec = 0f; recLevel = 0f
        recentPitch.clear()
        recorder.start(maxSec,
            onChunk = { sec, level, pitch ->
                main.post {
                    recSec = sec; recLevel = level
                    livePitch.add(sec to pitch)
                    updateTuner(pitch)
                }
            },
            onDone = { x -> main.post { recording = false; recLevel = 0f; onDone(x) } })
    }

    // ------------------------------------------------------------ bird race

    fun openRace(preselect: String? = null) {
        stopAll()
        val id = preselect ?: race.voiceId?.takeIf { v -> voices.any { it.id == v } } ?: voices.firstOrNull()?.id
        race.openLobby(id)
        target = null; result = null; attempt = null
        screen = Screen.Race
    }

    // ------------------------------------------------------------ say it in this voice

    fun downloadCloner() {
        if (downloadProgress != null) { downloadCancel = true; return }
        downloadCancel = false
        downloadProgress = 0f
        viewModelScope.launch {
            val ok = runCatching {
                background { cloner.download({ p -> main.post { if (downloadProgress != null) downloadProgress = p } }, { downloadCancel }) }
            }.onFailure { android.util.Log.w("VoiceMimic", "model download", it) }.isSuccess
            downloadProgress = null
            clonerReady = cloner.isDownloaded
            if (!ok && !downloadCancel) message = s.downloadFailed
        }
    }

    fun say() {
        if (sayProgress != null) { sayCancel = true; return }
        val t = target ?: return
        val text = sayText.trim()
        if (text.isEmpty()) return
        if (!Cloner.isEnglish(text)) { message = s.englishOnly; return }
        player.stop(); playPos = null
        sayCancel = false
        sayProgress = 0f
        val speed = saySpeed
        val t0 = System.currentTimeMillis()
        val ticker = viewModelScope.launch {
            while (isActive) { delay(250); if (sayProgress != null) sayProgress = (System.currentTimeMillis() - t0) / 1000f }
        }
        viewModelScope.launch {
            val y = runCatching {
                background { cloner.say(text, t.audio, speed, {}, { !sayCancel }) }
            }.onFailure { android.util.Log.w("VoiceMimic", "tts", it) }.getOrNull()
            ticker.cancel()
            sayProgress = null
            if (sayCancel) return@launch
            if (y == null || y.isEmpty()) { message = s.sayFailed; return@launch }
            said = normalize(y)
            playSaid()
        }
    }

    fun playSaid() { said?.let { playingMine = false; player.play(it) { p -> main.post { playPos = p } } } }

    /** Saves the generated speech as a new voice, so you can try to imitate it. */
    fun imitateSaid() {
        val y = said ?: return
        val b = Dsp.speechBounds(y) ?: return
        val base = current()?.name ?: s.defaultName
        val words = sayText.trim().split(Regex("\\s+")).take(3).joinToString(" ")
        voices = store.add("$base: $words".take(40), y.copyOfRange(b.first, b.last + 1), voices)
        val id = voices.first().id
        openVoice(id)
        mode = Mode.PHRASE
    }

    override fun onCleared() {
        recorder.stop(); player.stop(); liveJob?.cancel()
        sayCancel = true; downloadCancel = true
        race.stop()
        Thread { cloner.release() }.start()
    }
}
