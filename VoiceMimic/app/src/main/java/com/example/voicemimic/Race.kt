package com.example.voicemimic

import android.app.Application
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

enum class RaceLevel(val sec: Int) { EASY(60), NORMAL(40), HARD(25) }

enum class RacePhase {
    LOBBY,      // pick voice, time limit, solo / host / join
    HOSTING,    // waiting for a friend to connect (host)
    JOINING,    // looking for games (guest)
    WAITING,    // connected: host waits for "ready" / guest waits for "go"
    COUNTDOWN, LISTEN, RACE, DONE,
}

enum class RaceOutcome { WIN, LOSE, TIE }

/** How imitation quality turns into walking speed. */
object RaceRules {
    /** Seconds to cross the screen at the best imitation. */
    const val FULL_SPEED_SEC = 8f

    /** Track fraction per second for a live score (null = not talking: the bird stops). */
    fun speed(score: Int?): Float {
        if (score == null) return 0f
        val f = ((score - 20) / 70f).coerceIn(0f, 1f)
        return f.pow(1.3f) / FULL_SPEED_SEC
    }

    /** Network race: who won once the round is over. */
    fun outcome(myProgress: Float, myFinishMs: Long?, oppProgress: Float, oppFinishMs: Long?): RaceOutcome = when {
        myFinishMs != null && oppFinishMs != null ->
            if (myFinishMs < oppFinishMs) RaceOutcome.WIN else if (myFinishMs > oppFinishMs) RaceOutcome.LOSE else RaceOutcome.TIE
        myFinishMs != null -> RaceOutcome.WIN
        oppFinishMs != null -> RaceOutcome.LOSE
        // time is up: the bird that got further wins
        myProgress > oppProgress + 0.005f -> RaceOutcome.WIN
        oppProgress > myProgress + 0.005f -> RaceOutcome.LOSE
        else -> RaceOutcome.TIE
    }
}

/**
 * The bird race game: listen to a voice, then imitate it — the better the imitation, the faster
 * your bird walks to the right edge. Solo against the clock, or two phones racing over Wi-Fi.
 */
class RaceController(
    private val app: Application,
    private val scope: CoroutineScope,
    private val embedder: Embedder,
    private val store: VoiceStore,
    private val strings: () -> Strings,
    private val toast: (String) -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())
    private val recorder = Recorder()
    private val player = Player(scope)
    private val discovery = NetDiscovery(app)
    private var link: NetLink? = null

    var phase by mutableStateOf(RacePhase.LOBBY); private set
    var voiceId by mutableStateOf<String?>(null)
    var level by mutableStateOf(RaceLevel.NORMAL)
    var network by mutableStateOf(false); private set
    var isHost by mutableStateOf(false); private set
    var voiceName by mutableStateOf(""); private set
    private var target: VoiceProfile? = null
    var busy by mutableStateOf(false); private set

    // round state
    var countdown by mutableStateOf(3); private set
    var timeLeft by mutableStateOf(0f); private set
    var outcome by mutableStateOf<RaceOutcome?>(null); private set
    var myProgress by mutableStateOf(0f); private set
    var myScore by mutableStateOf<Int?>(null); private set
    var myBest by mutableStateOf(-1); private set
    var myWalk by mutableStateOf(0f); private set
    var talking by mutableStateOf(false); private set
    var pitchOffset by mutableStateOf<Float?>(null); private set
    var myFinishMs by mutableStateOf<Long?>(null); private set

    // the other phone
    var oppName by mutableStateOf(""); private set
    var oppReady by mutableStateOf(false); private set
    var oppConnected by mutableStateOf(false); private set
    private var oppProgressNet = 0f
    var oppProgress by mutableStateOf(0f); private set     // smoothed for drawing
    var oppScore by mutableStateOf<Int?>(null); private set
    var oppWalk by mutableStateOf(0f); private set
    var oppFinishMs by mutableStateOf<Long?>(null); private set

    // lobby / connection
    val games = mutableStateListOf<NetDiscovery.Game>()
    var localAddress by mutableStateOf<String?>(null); private set
    var port by mutableStateOf(NetLink.DEFAULT_PORT); private set

    private var roundJob: Job? = null
    private var scoreJob: Job? = null
    private val recentPitch = ArrayDeque<Float>()
    /** No current score (not talking, or not enough speech yet): the bird stands still. */
    var paused by mutableStateOf(true); private set

    val inRound get() = phase in setOf(RacePhase.COUNTDOWN, RacePhase.LISTEN, RacePhase.RACE)

    init {
        discovery.onFound = { g -> if (games.none { it.name == g.name }) games.add(g) }
        discovery.onLost = { n -> games.removeAll { it.name == n } }
    }

    fun openLobby(preselect: String?) {
        stop()
        if (preselect != null) voiceId = preselect
        phase = RacePhase.LOBBY
    }

    // ------------------------------------------------------------ solo

    fun startSolo() {
        val id = voiceId ?: return
        network = false; isHost = false
        loadVoice(id) { round() }
    }

    private fun loadVoice(id: String, then: () -> Unit) {
        busy = true
        scope.launch {
            val p = runCatching { background { VoiceProfile.build(Wav.read(store.wav(id))) { embedder.embed(it) } } }.getOrNull()
            busy = false
            if (p == null) { toast(strings().importFailed); return@launch }
            target = p
            voiceName = store.load().firstOrNull { it.id == id }?.name ?: ""
            then()
        }
    }

    /** Solo: replay. Host: start the next round for both phones. */
    fun again() {
        if (!network) { round(); return }
        if (isHost && oppConnected && oppReady) { link?.send(RaceMsg.Go); round() }
    }

    // ------------------------------------------------------------ network

    fun host() {
        val id = voiceId ?: return
        closeLink()
        network = true; isHost = true; oppReady = false; oppName = ""
        loadVoice(id) {
            val l = NetLink(listener)
            link = l
            port = runCatching { l.host() }.getOrElse { toast(strings().netFailed); phase = RacePhase.LOBBY; return@loadVoice }
            localAddress = discovery.localAddress()
            discovery.advertise("${strings().raceTitle} · ${discovery.deviceName()} · $voiceName", port)
            phase = RacePhase.HOSTING
        }
    }

    fun join() {
        closeLink()
        network = true; isHost = false; oppReady = false; oppName = ""
        games.clear()
        localAddress = discovery.localAddress()
        discovery.search()
        phase = RacePhase.JOINING
    }

    fun connectTo(host: String, port: Int = NetLink.DEFAULT_PORT) {
        if (host.isBlank()) return
        discovery.stopSearching()
        val l = NetLink(listener)
        link = l
        busy = true
        l.connect(host.trim(), port)
    }

    /** Back from a network/round screen to the lobby. */
    fun leave() {
        stopRound()
        closeLink()
        phase = RacePhase.LOBBY
    }

    private val listener = object : NetLink.Listener {
        override fun onConnected(isHost: Boolean) = main.post {
            busy = false
            oppConnected = true
            discovery.stop()
            link?.send(RaceMsg.Hello(RaceMsg.VERSION, discovery.deviceName()))
            if (isHost) {
                val bytes = voiceBytes()
                if (bytes == null) { toast(strings().importFailed); leave(); return@post }
                link?.send(RaceMsg.Voice(voiceName, level.sec, bytes))
            } else busy = true   // until the voice arrives and is analysed
            phase = RacePhase.WAITING
        }.let {}

        override fun onMessage(msg: RaceMsg) = main.post { handle(msg) }.let {}

        override fun onClosed() = main.post {
            val wasConnected = oppConnected
            oppConnected = false
            busy = false
            if (link == null) return@post
            link = null
            if (inRound) {
                toast(strings().friendLeft)    // keep racing alone; the result treats them as not finishing
            } else {
                toast(if (wasConnected) strings().friendLeft else strings().netFailed)
                if (phase != RacePhase.DONE) { stopRound(); phase = RacePhase.LOBBY }
            }
        }.let {}
    }

    private fun handle(msg: RaceMsg) {
        when (msg) {
            is RaceMsg.Hello -> oppName = msg.name
            is RaceMsg.Voice -> if (!isHost) {
                level = RaceLevel.entries.minByOrNull { kotlin.math.abs(it.sec - msg.limitSec) } ?: RaceLevel.NORMAL
                voiceName = msg.name
                scope.launch {
                    val p = runCatching { background { VoiceProfile.build(Wav.parse(msg.wav)) { embedder.embed(it) } } }.getOrNull()
                    busy = false
                    if (p == null) { toast(strings().importFailed); leave(); return@launch }
                    target = p
                    link?.send(RaceMsg.Ready)
                }
            }
            RaceMsg.Ready -> if (isHost) oppReady = true
            RaceMsg.Go -> if (!isHost && target != null) round()
            is RaceMsg.Pos -> { oppProgressNet = msg.progress.coerceIn(0f, 1f); oppScore = msg.score.takeIf { it >= 0 } }
            is RaceMsg.Finish -> {
                oppFinishMs = msg.ms; oppProgressNet = 1f
                if (phase == RacePhase.DONE) outcome = RaceRules.outcome(myProgress, myFinishMs, 1f, msg.ms)
            }
            RaceMsg.Bye -> {}
        }
    }

    /** The target voice as WAV bytes for the other phone (at most 15 s). */
    private fun voiceBytes(): ByteArray? = runCatching {
        val x = target!!.audio
        val f = File(app.cacheDir, "race-voice.wav")
        Wav.write(f, if (x.size > 15 * Dsp.SR) x.copyOf(15 * Dsp.SR) else x)
        f.readBytes().also { f.delete() }
    }.getOrNull()

    // ------------------------------------------------------------ a round

    private fun round() {
        val t = target ?: return
        stopRound()
        myProgress = 0f; myScore = null; myBest = -1; myWalk = 0f; myFinishMs = null; talking = false; pitchOffset = null
        oppProgress = 0f; oppProgressNet = 0f; oppScore = null; oppWalk = 0f; oppFinishMs = null
        outcome = null
        timeLeft = level.sec.toFloat()
        roundJob = scope.launch {
            phase = RacePhase.COUNTDOWN
            for (n in 3 downTo 1) { countdown = n; delay(800) }
            phase = RacePhase.LISTEN
            player.play(t.audio) {}
            delay((t.audio.size * 1000L / Dsp.SR) + 400)
            player.stop()
            phase = RacePhase.RACE
            race(t)
        }
    }

    private suspend fun race(t: VoiceProfile) {
        val limit = level.sec
        paused = true
        recentPitch.clear()
        recorder.start(limit + 5f,
            onChunk = { _, lvl, pitch -> main.post { talking = lvl > 0.12f; updateTuner(t, pitch) } },
            onDone = {})
        scoreJob = scope.launch {
            var smooth = -1.0
            while (isActive) {
                delay(600)
                val w = recorder.tail(Scorer.LIVE_WINDOW_SEC * Dsp.SR)
                val r = background { Scorer.live(t, normalize(w)) { embedder.embed(it) } }
                if (r == null) { paused = true; continue }
                paused = false
                smooth = if (smooth < 0) r.total.toDouble() else smooth * 0.5 + r.total * 0.5
                val v = smooth.roundToInt()
                myScore = v
                if (v > myBest) myBest = v
            }
        }

        val start = System.nanoTime()
        var last = start
        var speed = 0f
        var lastSend = 0L
        while (true) {
            delay(16)
            val now = System.nanoTime()
            val dt = ((now - last) / 1e9f).coerceAtMost(0.1f)
            last = now
            val elapsed = (now - start) / 1e9f
            val want = if (paused) 0f else RaceRules.speed(myScore)
            speed += (want - speed) * min(1f, dt / 0.4f)
            val step = speed * dt
            myProgress = (myProgress + step).coerceAtMost(1f)
            myWalk += step * 40f
            timeLeft = (limit - elapsed).coerceAtLeast(0f)

            if (network) {
                val d = (oppProgressNet - oppProgress) * min(1f, dt / 0.25f)
                oppProgress += d
                oppWalk += kotlin.math.abs(d) * 40f
                val ms = (now - start) / 1_000_000
                if (ms - lastSend >= 200) { lastSend = ms; link?.send(RaceMsg.Pos(myProgress, myScore ?: -1)) }
            }

            if (myProgress >= 1f) {
                val ms = (now - start) / 1_000_000
                myFinishMs = ms
                link?.send(RaceMsg.Pos(1f, myScore ?: -1))
                link?.send(RaceMsg.Finish(ms))
                break
            }
            if (network && oppFinishMs != null) break          // the friend got there first
            if (timeLeft <= 0f) {
                link?.send(RaceMsg.Pos(myProgress, myScore ?: -1))
                break
            }
        }
        recorder.stop()
        scoreJob?.cancel(); scoreJob = null
        talking = false; pitchOffset = null
        if (network && myFinishMs == null && oppFinishMs == null) delay(700)   // let the friend's last position arrive
        if (network) oppProgress = oppProgressNet
        outcome = if (!network) (if (myFinishMs != null) RaceOutcome.WIN else RaceOutcome.LOSE)
        else RaceRules.outcome(myProgress, myFinishMs, oppProgressNet, oppFinishMs)
        phase = RacePhase.DONE
    }

    private fun updateTuner(t: VoiceProfile, pitch: Float) {
        recentPitch.addLast(pitch)
        while (recentPitch.size > 8) recentPitch.removeFirst()
        val v = recentPitch.filter { it > 0f }.sorted()
        pitchOffset = if (v.size >= 3 && t.medianPitch > 0f) Dsp.semitones(v[v.size / 2], t.medianPitch) else null
    }

    private fun stopRound() {
        roundJob?.cancel(); roundJob = null
        scoreJob?.cancel(); scoreJob = null
        recorder.stop(); player.stop()
        talking = false; pitchOffset = null
    }

    private fun closeLink() {
        val l = link
        link = null
        l?.close()
        discovery.stop()
        oppConnected = false; oppReady = false
        busy = false
    }

    /** Leaving the race screen. */
    fun stop() {
        stopRound()
        closeLink()
        phase = RacePhase.LOBBY
    }
}
