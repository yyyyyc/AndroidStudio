package com.example.mathbowling

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.opengl.GLES20.*
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import android.opengl.Matrix
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan
import kotlin.random.Random

interface GameListener {
    /** total = 0 in endless mode. */
    fun onQuestion(q: Question, index: Int, total: Int, score: Int)
    /** 3,2,1 = countdown, 0 = ball released. */
    fun onCountdown(n: Int)
    /** jumpedOver = the ball flew over all the cones. */
    fun onResult(correct: Boolean, answer: String, score: Int, jumpedOver: Boolean, blewOut: Boolean)
    /** Endless mode only: remaining lives. */
    fun onLives(lives: Int)
    fun onGameOver(score: Int, total: Int, level: Level, mode: GameMode, endless: Boolean)
    fun onJump()
    fun onLand()
}

class GameRenderer(private val listener: GameListener, private val english: Boolean) : GLSurfaceView.Renderer {

    companion object {
        const val QUESTIONS = 10
        const val CONE_Z = -20f
        /** Distance between cone sets on the endless lane (the lane decoration repeats with this period). */
        const val SEGMENT = 20f
        /** Blowout mode: how much faster the ball rolls through blown-up cones to the next question. */
        const val BOOST = 2.6f
        const val PIT_Z = -21.6f
        const val LANE_FRONT = 14f
        /** Where the ball waits at the start of a round: further back gives more time to aim. */
        const val START_Z = 5f
        const val BALL_R = 0.32f
        const val LANE_HALF = 2f
        const val CONE_SCALE = 0.8f
        const val RESULT_TIME = 2.8f
        /** Longer pause when an explanation is shown after the hit. */
        const val RESULT_TIME_EXPLAINED = 7f
        const val ENDLESS_LIVES = 3

        private val CONE_COLORS = arrayOf(
            floatArrayOf(1f, 0.45f, 0.08f),
            floatArrayOf(0.95f, 0.22f, 0.55f),
            floatArrayOf(0.1f, 0.72f, 0.85f),
            floatArrayOf(0.45f, 0.85f, 0.15f)
        )
        private val LABEL_COLORS = intArrayOf(
            Color.rgb(230, 100, 10), Color.rgb(210, 40, 120), Color.rgb(15, 140, 175), Color.rgb(80, 160, 20)
        )
        private val WHITE = floatArrayOf(1f, 1f, 1f)
        private val WOOD1 = floatArrayOf(0.80f, 0.56f, 0.32f)
        private val WOOD2 = floatArrayOf(0.60f, 0.38f, 0.20f)
        private val GUTTER = floatArrayOf(0.17f, 0.17f, 0.21f)
        private val WALL = floatArrayOf(0.30f, 0.12f, 0.46f)
        private val LAMP = floatArrayOf(1f, 0.85f, 0.45f)
        private val DARK = floatArrayOf(0.06f, 0.06f, 0.09f)
        private val BACK = floatArrayOf(0.10f, 0.08f, 0.20f)
        private val NEON1 = floatArrayOf(1f, 0.2f, 0.8f)
        private val NEON2 = floatArrayOf(0.2f, 0.9f, 1f)
        private val ARROW = floatArrayOf(0.75f, 0.12f, 0.10f)
        private val CARPET = floatArrayOf(0.10f, 0.08f, 0.16f)
        private val BALL1 = floatArrayOf(0.08f, 0.18f, 0.85f)
        private val BALL2 = floatArrayOf(0.55f, 0.20f, 0.95f)
        private val BLACK = floatArrayOf(0f, 0f, 0f)
        private val GREEN = floatArrayOf(0.25f, 1f, 0.4f)
        private val ORANGE = floatArrayOf(1f, 0.55f, 0.15f)
        private val FOG = floatArrayOf(0.05f, 0.055f, 0.11f)
        private val LIGHT = floatArrayOf(0.35f, 1.0f, 0.45f)
    }

    enum class State { MENU, READY, ROLLING, RESULT, OVER }

    /** One row of cones with its question and answer signs. */
    private class ConeSet {
        var q: Question? = null
        var z = CONE_Z
        var count = 3
        val x = FloatArray(4)
        val exploded = BooleanArray(4)
        val fallT = FloatArray(4) { -1f }
        val fallAxis = Array(4) { FloatArray(3) }
        var labels: Array<String> = arrayOf("?", "?", "?")
        val tex = IntArray(4)
        var dirty = true
        var wide = false
        var resolved = false
        var hitIndex = -1
        var correct = false
        var jumpedOver = false
        /** Blown up by a spoken answer (blowout mode). */
        var blewOut = false
        /** Seconds since the ball reached this set. */
        var hitTime = 0f

        fun setup(question: Question?, z: Float) {
            q = question
            this.z = z
            count = question?.options?.size ?: 3
            val xs = if (count == 3) floatArrayOf(-1.3f, 0f, 1.3f) else floatArrayOf(-1.5f, -0.5f, 0.5f, 1.5f)
            for (i in 0 until count) x[i] = xs[i]
            labels = question?.labels?.toTypedArray() ?: Array(count) { "?" }
            wide = question?.textExpression ?: false
            dirty = true
            for (i in 0 until 4) { exploded[i] = false; fallT[i] = -1f }
            resolved = false; hitIndex = -1; correct = false; jumpedOver = false; blewOut = false; hitTime = 0f
        }
    }

    /** -1 (left) .. 1 (right), written from the UI thread. */
    @Volatile var tilt = 0f
    @Volatile var touchSteer = 0f
    /** Phone pitch in degrees (90 = upright, 0 = flat); NaN when there is no sensor. */
    @Volatile var pitch = Float.NaN
    @Volatile var jumpRequest = false
    /** The game is frozen (the picture keeps drawing). */
    @Volatile var paused = false
    /** Questions are read aloud: give more time before the ball is released. */
    @Volatile var speakMode = false
    /** Ball speed from the settings (1 = normal). */
    @Volatile var speedSetting = 1f
    /** Tilt sensitivity from the settings; also scales the forward/back speed tilt (1 = normal). */
    @Volatile var tiltSensitivity = 1f

    private var neutralPitch = Float.NaN
    private var speedMul = 1f

    private var gen = QuestionGenerator(english)
    private var vocabGens = ForeignLanguage.values().associateWith { VocabQuestionGenerator(it, english) }
    private var aiGen = AiQuestionGenerator(english)
    /** The player's own questions (set before a CUSTOM game starts). */
    @Volatile var customItems: List<CustomItem> = emptyList()
    private var customGen: CustomQuestionGenerator? = null
    private var mode = GameMode.MATH
    private var endless = false
    /** Blowout mode: a spoken answer blows up the cones at once instead of steering the ball. */
    private var blowout = false
    /** One long lane: after a set of cones the ball rolls on to the next (endless lane and blowout mode). */
    private var continuous = false
    /** Extra speed while rolling through blown-up cones to the next question. */
    private var boostMul = 1f
    private var lives = ENDLESS_LIVES
    private val rnd = Random.Default
    private var state = State.MENU
    private var level = Level.EASY
    private var qIndex = 0
    private var score = 0
    private var stateTime = 0f
    private var readyDur = 3f
    private var lastCount = -1
    private var time = 0f
    private var lastNanos = 0L
    /** Cone the player named by voice for the announced question, or -1. */
    private var voiceTarget = -1
    /** Endless lane: the question on the next set has already been shown. */
    private var nextAnnounced = false

    // ball
    private var ballX = 0f
    private var ballY = BALL_R
    private var ballZ = 0f
    private var ballVx = 0f
    private var ballVy = 0f
    private var ballVz = 0f
    private var ballVisible = true
    private val ballRot = FloatArray(16).also { Matrix.setIdentityM(it, 0) }

    // cones: the set the ball is heading to, and (endless lane) the one after it
    private var cur = ConeSet()
    private var nxt = ConeSet()
    private var shake = 0f
    private var blastGlow = 0f
    private var blastX = 0f
    private var blastZ = CONE_Z

    private val particles = Particles(800)

    // camera
    private val eye = floatArrayOf(0f, 2.1f, 5f)
    private val look = floatArrayOf(0f, 0.4f, -6f)
    private var viewH = 1

    // GL
    private val lit = LitShader()
    private val points = PointShader()
    private val sphere = Shapes.sphere()
    private val cone = Shapes.cone()
    private val cube = Shapes.cube()
    private val quadXY = Shapes.quadXY()
    private val quadXZ = Shapes.quadXZ()
    private val arrow = Shapes.arrow()
    private val proj = FloatArray(16)
    private val view = FloatArray(16)
    private val vp = FloatArray(16)
    private val mvp = FloatArray(16)
    private val mm = FloatArray(16)
    private val tmp = FloatArray(16)
    private val tmp2 = FloatArray(16)
    private val texturesToDelete = ArrayList<Int>()

    init {
        cur.setup(null, CONE_Z)
    }

    // ------------------------------------------------------------------ game control (GL thread)

    /**
     * Starts a game. With [seed] the questions are reproducible, so two phones given the same seed
     * get exactly the same questions (network game).
     */
    fun startGame(l: Level, gameMode: GameMode, endlessLane: Boolean = false, seed: Long? = null, blowoutGame: Boolean = false) {
        if (seed != null) {
            gen = QuestionGenerator(english, Random(seed))
            vocabGens = ForeignLanguage.values().associateWith { VocabQuestionGenerator(it, english, Random(seed + it.ordinal + 1)) }
            aiGen = AiQuestionGenerator(english, Random(seed + 7))
        }
        if (gameMode == GameMode.CUSTOM) {
            customGen = CustomQuestionGenerator(customItems, if (seed != null) Random(seed + 11) else Random.Default)
        }
        level = l
        mode = gameMode
        endless = endlessLane
        blowout = blowoutGame
        continuous = endlessLane || blowoutGame
        boostMul = 1f
        lives = ENDLESS_LIVES
        qIndex = 0
        score = 0
        particles.clear()
        recycle(nxt)
        nxt.setup(null, CONE_Z - SEGMENT)
        nextAnnounced = false
        if (endless) listener.onLives(lives)
        newRound()
    }

    fun toMenu() {
        state = State.MENU
        endless = false
        continuous = false
        blowout = false
        recycle(cur); recycle(nxt)
        cur.setup(null, CONE_Z)
        nxt.setup(null, CONE_Z - SEGMENT)
        resetBall()
    }

    /** Voice answer: steer the ball to cone [index] of question number [questionIndex]. */
    fun voiceAnswer(questionIndex: Int, index: Int) {
        if (questionIndex != qIndex + 1) return
        if (state != State.READY && state != State.ROLLING) return
        // on a continuous lane the announced question may still be on the next set of cones
        val set = if (continuous && cur.resolved) nxt else cur
        if (set.q == null || set.resolved || index !in 0 until set.count) return
        if (blowout) {
            blowOut(set, index)
        } else {
            voiceTarget = index
        }
    }

    /** Blowout mode: every cone of [set] blows up, the answer counts, and the ball speeds on to the next question. */
    private fun blowOut(set: ConeSet, idx: Int) {
        val q = set.q ?: return
        set.resolved = true
        set.blewOut = true
        set.hitTime = 0f
        set.hitIndex = idx
        set.correct = idx == q.correctIndex
        voiceTarget = -1
        if (set.correct) score++
        for (i in 0 until set.count) {
            set.exploded[i] = true
            if (i == idx) explode(set.x[i], 0.45f, set.z, CONE_COLORS[i]) else puff(set.x[i], 0.45f, set.z, CONE_COLORS[i])
        }
        blastGlow = if (set.correct) 1f else 0.4f
        blastX = set.x[idx]
        blastZ = set.z
        shake = 0.8f
        if (state == State.READY) {
            // answered during the countdown: off it goes
            state = State.ROLLING
            stateTime = 0f
            ballVz = -level.speed * speedSetting * rampMul()
        }
        finishHit(q, set.correct, false)
    }

    /** A smaller burst for the cones next to the named one. */
    private fun puff(x: Float, y: Float, z: Float, coneColor: FloatArray) {
        repeat(70) {
            val th = rf(0f, 2f * PI.toFloat())
            val sp = rf(1.5f, 5f)
            val hot = rnd.nextFloat()
            particles.emit(
                x, y, z, cos(th) * sp, rf(1.5f, 5f), sin(th) * sp,
                rf(0.4f, 1f), rf(0.1f, 0.22f), 1f, 0.5f + 0.4f * hot, 0.15f, -5f, 1.8f, true
            )
        }
        repeat(40) {
            val th = rf(0f, 2f * PI.toFloat())
            val sp = rf(2f, 5f)
            particles.emit(
                x, y, z, cos(th) * sp, rf(2f, 5f), sin(th) * sp,
                rf(0.8f, 1.4f), rf(0.08f, 0.14f), coneColor[0], coneColor[1], coneColor[2], -12f, 0.6f, false
            )
        }
    }

    private fun nextQuestion(): Question {
        val lang = mode.language
        return when {
            mode == GameMode.AI -> aiGen.next(level)
            mode == GameMode.CUSTOM -> customGen?.next(level) ?: gen.next(level)
            lang != null -> vocabGens.getValue(lang).next(level)
            else -> gen.next(level)
        }
    }

    private fun readyTimeFor(q: Question): Float {
        val wordy = q.expression.isEmpty()
        return when {
            // the player's own questions can be any length: give time by the amount of text
            mode == GameMode.CUSTOM ->
                (3f + (q.prompt.length + q.labels.sumOf { it.length }) * 0.05f).coerceIn(4f, 10f) + (if (speakMode) 2.5f else 0f)
            q.explanation.isNotEmpty() -> if (speakMode) 9f else 6.5f
            q.textExpression && q.expression.isNotEmpty() -> if (speakMode) 4.5f else 3.5f
            speakMode -> if (wordy) 8f else 4.5f
            else -> if (wordy) 5.5f else 3.2f
        }
    }

    /** Fresh round with the ball back at the start (normal mode, and the first question of endless mode). */
    private fun newRound() {
        val q = nextQuestion()
        recycle(cur)
        cur.setup(q, CONE_Z)
        resetBall(START_Z)
        voiceTarget = -1
        state = State.READY
        stateTime = 0f
        lastCount = -1
        readyDur = readyTimeFor(q)
        listener.onQuestion(q, qIndex + 1, if (endless) 0 else QUESTIONS, score)
    }

    /** Endless lane: put the next question on the cones further down the lane. */
    private fun prepareNextSet() {
        if (nxt.q != null) return
        val q = nextQuestion()
        recycle(nxt)
        nxt.setup(q, cur.z - SEGMENT)
    }

    /** Endless lane: once the old cones are behind the camera, shift the world back by one segment. */
    private fun advanceSegment() {
        showNextQuestion()
        ballZ += SEGMENT
        eye[2] += SEGMENT
        look[2] += SEGMENT
        blastZ += SEGMENT
        particles.shiftZ(SEGMENT)
        val old = cur
        cur = nxt
        cur.z = CONE_Z
        nxt = old
        recycle(nxt)
        nxt.setup(null, CONE_Z - SEGMENT)
        nextAnnounced = false
    }

    private fun recycle(set: ConeSet) {
        for (i in set.tex.indices) {
            if (set.tex[i] != 0) texturesToDelete.add(set.tex[i])
            set.tex[i] = 0
        }
    }

    private fun resetBall(z: Float = 0f) {
        ballX = 0f; ballY = BALL_R; ballZ = z
        ballVx = 0f; ballVy = 0f; ballVz = 0f
        ballVisible = true
        speedMul = 1f
        jumpRequest = false
        Matrix.setIdentityM(ballRot, 0)
    }

    private val grounded get() = ballY <= BALL_R + 1e-3f && ballVy <= 0f

    /** Gravity, landing on the lane, and (normal lane) falling into the pit behind the cones. */
    private fun vertical(dt: Float) {
        val overPit = !continuous && ballZ < PIT_Z
        if (!overPit && grounded) return
        ballVy -= 14f * dt
        ballY += ballVy * dt
        if (!overPit && ballY <= BALL_R) {
            val impact = -ballVy
            ballY = BALL_R
            ballVy = if (impact > 3f) impact * 0.25f else 0f
            if (impact > 2f) listener.onLand()
        }
        if (ballY < -1.5f) ballVisible = false
    }

    private fun handleJump() {
        if (!jumpRequest) return
        jumpRequest = false
        if ((state == State.READY || state == State.ROLLING) && grounded) {
            ballVy = 5.8f
            listener.onJump()
        }
    }

    /** Forward tilt = faster (up to 1.9x), backward tilt = slower (down to 0.4x, never stops). */
    private fun targetSpeedMul(): Float {
        val p = pitch
        if (p.isNaN() || neutralPitch.isNaN()) return 1f
        // forward/back speed follows the setting too, but within limits, or the extremes make speed jumpy
        val d = ((neutralPitch - p) * tiltSensitivity.coerceIn(0.6f, 2.5f) / 22f).coerceIn(-1f, 1f)
        return if (d >= 0f) 1f + 0.9f * d else 1f + 0.6f * d
    }

    /** Endless lane gets a little faster with every question. */
    private fun rampMul() = if (endless) min(1.35f, 0.8f + 0.035f * qIndex) else 1f

    // ------------------------------------------------------------------ simulation

    private fun steer(dt: Float, input: Float) {
        val target = input * 3.4f
        ballVx += (target - ballVx) * min(1f, dt * 6f)
        val dx = ballVx * dt
        ballX += dx
        val lim = LANE_HALF - BALL_R - 0.05f
        if (ballX > lim) { ballX = lim; ballVx = 0f }
        if (ballX < -lim) { ballX = -lim; ballVx = 0f }
    }

    private fun roll(dx: Float, dz: Float) {
        val dist = sqrt(dx * dx + dz * dz)
        if (dist < 1e-6f) return
        val angle = (dist / BALL_R) * (180f / PI.toFloat())
        Matrix.setRotateM(tmp, 0, angle, dz / dist, 0f, -dx / dist)
        Matrix.multiplyMM(tmp2, 0, tmp, 0, ballRot, 0)
        System.arraycopy(tmp2, 0, ballRot, 0, 16)
    }


    private fun playerInput(): Float {
        val v = voiceTarget
        if (v >= 0 && !cur.resolved) return ((cur.x[v] - ballX) * 2.5f).coerceIn(-1f, 1f)
        val t = touchSteer
        return if (t != 0f) t else tilt
    }

    private fun update(dt: Float) {
        time += dt
        handleJump()
        // The way the player holds the phone before the ball is released is the "normal speed" position.
        if (state == State.MENU || state == State.READY || state == State.OVER) {
            val p = pitch
            if (!p.isNaN()) {
                neutralPitch = if (neutralPitch.isNaN()) p else neutralPitch + (p - neutralPitch) * min(1f, dt * 3f)
            }
        }
        when (state) {
            State.MENU -> {
                val x0 = ballX
                steer(dt, sin(time * 0.8f) * 0.5f)
                roll(ballX - x0, 0f)
            }
            State.READY -> {
                stateTime += dt
                val x0 = ballX
                steer(dt, playerInput())
                roll(ballX - x0, 0f)
                vertical(dt)
                // an answer given by voice releases the ball right away
                if (voiceTarget >= 0) stateTime = max(stateTime, readyDur)
                val remaining = readyDur - stateTime
                val n = kotlin.math.ceil(remaining).toInt()
                if (n in 1..3 && n != lastCount) {
                    lastCount = n
                    listener.onCountdown(n)
                }
                if (remaining <= 0f) {
                    state = State.ROLLING
                    stateTime = 0f
                    ballVz = -level.speed * speedSetting * rampMul()
                    listener.onCountdown(0)
                }
            }
            State.ROLLING -> {
                stateTime += dt
                val x0 = ballX
                steer(dt, playerInput())
                speedMul += (targetSpeedMul() - speedMul) * min(1f, dt * 4f)
                val boostTarget = if ((cur.resolved && cur.blewOut) || nxt.blewOut) BOOST else 1f
                boostMul += (boostTarget - boostMul) * min(1f, dt * 3f)
                ballVz = -level.speed * speedSetting * speedMul * rampMul() * boostMul
                val dz = ballVz * dt
                ballZ += dz
                roll(ballX - x0, dz)
                vertical(dt)
                if (!cur.resolved && ballZ <= cur.z + 0.6f) resolveHit()
                if (continuous && cur.resolved) {
                    if (cur.hitTime > 1.2f) showNextQuestion()
                    if (ballZ < cur.z - 5f) advanceSegment()
                }
            }
            State.RESULT -> {
                stateTime += dt
                val dz = ballVz * dt
                ballZ += dz
                roll(0f, dz)
                val last = if (nxt.resolved) nxt else cur
                if (last.blewOut) ballVz *= exp(-2f * dt)
                else if (!last.correct && !last.jumpedOver) ballVz *= exp(-3f * dt)
                vertical(dt)
                val resultTime = if (last.q?.explanation.isNullOrEmpty()) RESULT_TIME else RESULT_TIME_EXPLAINED
                if (endless) {
                    // out of lives: let the last hit play out, then end the run
                    if (stateTime > RESULT_TIME) {
                        state = State.OVER
                        stateTime = 0f
                        listener.onGameOver(score, qIndex + 1, level, mode, true)
                    }
                } else {
                    if (stateTime > resultTime) {
                        qIndex++
                        if (qIndex >= QUESTIONS) {
                            state = State.OVER
                            stateTime = 0f
                            listener.onGameOver(score, QUESTIONS, level, mode, false)
                        } else {
                            newRound()
                        }
                    }
                }
            }
            State.OVER -> stateTime += dt
        }
        for (set in arrayOf(cur, nxt)) {
            if (set.resolved) set.hitTime += dt
            for (i in 0 until set.count) if (set.fallT[i] >= 0f) set.fallT[i] += dt
        }
        particles.update(dt)
        shake = max(0f, shake - dt * 1.3f)
        blastGlow = max(0f, blastGlow - dt * 1.2f)
        updateCamera(dt)
    }

    /** Endless lane: announce the question waiting on the next set of cones. */
    private fun showNextQuestion() {
        if (nextAnnounced) return
        nextAnnounced = true
        prepareNextSet()
        qIndex++
        voiceTarget = -1
        listener.onQuestion(nxt.q!!, qIndex + 1, if (endless) 0 else QUESTIONS, score)
    }

    private fun resolveHit() {
        val set = cur
        val q = set.q ?: return
        set.resolved = true
        set.hitTime = 0f
        if (voiceTarget >= 0) voiceTarget = -1
        // Ball higher than the cone tops: it sails over everything.
        if (ballY - BALL_R > 0.62f) {
            set.jumpedOver = true
            set.correct = false
            finishHit(q, false, true)
            return
        }
        var idx = 0
        var best = Float.MAX_VALUE
        for (i in 0 until set.count) {
            val d = abs(set.x[i] - ballX)
            if (d < best) { best = d; idx = i }
        }
        set.hitIndex = idx
        set.correct = idx == q.correctIndex
        val cx = set.x[idx]
        if (set.correct) {
            score++
            set.exploded[idx] = true
            explode(cx, 0.45f, set.z, CONE_COLORS[idx])
            blastGlow = 1f
            blastX = cx
            blastZ = set.z
            shake = 0.8f
        } else {
            set.fallT[idx] = 0f
            var dx = cx - ballX
            var dz = -1f
            val len = sqrt(dx * dx + dz * dz)
            dx /= len; dz /= len
            set.fallAxis[idx][0] = dz; set.fallAxis[idx][1] = 0f; set.fallAxis[idx][2] = -dx
            shake = 0.3f
            sparks(ballX, 0.3f, set.z + 0.4f)
            if (continuous) speedMul *= 0.6f else ballVz = 2.2f
        }
        finishHit(q, set.correct, false)
    }

    private fun finishHit(q: Question, correct: Boolean, jumpedOver: Boolean) {
        if (endless && !correct) lives--
        val lastQuestion = !endless && qIndex + 1 >= QUESTIONS
        // on a continuous lane the ball keeps rolling towards the next set
        val stop = !continuous || lastQuestion || (endless && lives <= 0)
        if (stop) {
            state = State.RESULT
            stateTime = 0f
        }
        listener.onResult(correct, q.answerLabel, score, jumpedOver, !stop)
        if (endless && !correct) listener.onLives(lives)
    }

    private fun rf(a: Float, b: Float) = a + rnd.nextFloat() * (b - a)

    private fun explode(x: Float, y: Float, z: Float, coneColor: FloatArray) {
        repeat(260) {
            val th = rf(0f, 2f * PI.toFloat())
            val u = rf(-0.3f, 1f)
            val s = sqrt(1f - u * u)
            val sp = rf(2f, 8f)
            val hot = rnd.nextFloat()
            particles.emit(
                x, y, z, cos(th) * s * sp, u * sp + 1.5f, sin(th) * s * sp,
                rf(0.5f, 1.4f), rf(0.12f, 0.32f),
                1f, 0.55f + 0.4f * hot, 0.1f + 0.3f * hot * hot, -5f, 1.8f, true
            )
        }
        repeat(140) {
            val th = rf(0f, 2f * PI.toFloat())
            val sp = rf(1.5f, 5f)
            val hue = rnd.nextInt(6)
            val c = when (hue) {
                0 -> floatArrayOf(1f, 0.2f, 0.3f)
                1 -> floatArrayOf(1f, 0.9f, 0.2f)
                2 -> floatArrayOf(0.2f, 1f, 0.4f)
                3 -> floatArrayOf(0.3f, 0.6f, 1f)
                4 -> floatArrayOf(0.9f, 0.3f, 1f)
                else -> floatArrayOf(1f, 1f, 1f)
            }
            particles.emit(
                x, y + 0.3f, z, cos(th) * sp, rf(3f, 7f), sin(th) * sp,
                rf(1.4f, 2.4f), rf(0.07f, 0.12f), c[0], c[1], c[2], -4f, 1.2f, false
            )
        }
        repeat(60) {
            val th = rf(0f, 2f * PI.toFloat())
            val sp = rf(2f, 6f)
            particles.emit(
                x, y, z, cos(th) * sp, rf(2f, 6f), sin(th) * sp,
                rf(0.8f, 1.6f), rf(0.08f, 0.16f), coneColor[0], coneColor[1], coneColor[2], -12f, 0.6f, false
            )
        }
    }

    private fun sparks(x: Float, y: Float, z: Float) {
        repeat(45) {
            val th = rf(0f, 2f * PI.toFloat())
            val sp = rf(1f, 3.5f)
            particles.emit(
                x, y, z, cos(th) * sp, rf(1f, 3f), sin(th) * sp * 0.5f + 1f,
                rf(0.3f, 0.7f), rf(0.05f, 0.1f), 1f, 0.9f, 0.7f, -9f, 1f, false
            )
        }
    }


    private fun updateCamera(dt: Float) {
        val tx: Float; val ty: Float; val tz: Float
        val lx: Float; val ly: Float; val lz: Float
        if (state == State.MENU) {
            tx = sin(time * 0.25f) * 0.8f; ty = 2.1f; tz = 5f
            lx = 0f; ly = 0.4f; lz = -6f
        } else {
            tz = if (continuous) ballZ + 4.5f else max(ballZ + 4.5f, CONE_Z + 7.5f)
            tx = if (state == State.OVER) 0f else ballX * 0.35f
            ty = 1.9f
            lx = tx * 0.4f; ly = 0.45f; lz = tz - 9f
        }
        val k = 1f - exp(-dt * 4f)
        eye[0] += (tx - eye[0]) * k; eye[1] += (ty - eye[1]) * k; eye[2] += (tz - eye[2]) * k
        look[0] += (lx - look[0]) * k; look[1] += (ly - look[1]) * k; look[2] += (lz - look[2]) * k
    }

    // ------------------------------------------------------------------ GL

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        lit.init()
        points.init()
        for (m in arrayOf(sphere, cone, cube, quadXY, quadXZ, arrow)) m.upload()
        // the old GL context (and its textures) is gone
        texturesToDelete.clear()
        for (set in arrayOf(cur, nxt)) { for (i in set.tex.indices) set.tex[i] = 0; set.dirty = true }
        lastNanos = System.nanoTime()
        glClearColor(FOG[0], FOG[1], FOG[2], 1f)
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        glViewport(0, 0, width, height)
        viewH = height
        val aspect = width.toFloat() / height
        val wantHalfH = Math.toRadians(19.0)
        var vHalf = atan(tan(wantHalfH) / aspect)
        vHalf = vHalf.coerceIn(Math.toRadians(25.0), Math.toRadians(40.0))
        val near = 0.2f
        val top = (near * tan(vHalf)).toFloat()
        val right = top * aspect
        val shift = top * 0.22f
        Matrix.frustumM(proj, 0, -right, right, -top + shift, top + shift, near, 80f)
    }


    override fun onDrawFrame(gl: GL10?) {
        val now = System.nanoTime()
        val dt = ((now - lastNanos) / 1e9f).coerceIn(0f, 0.05f)
        lastNanos = now
        if (paused) jumpRequest = false else update(dt)
        if (texturesToDelete.isNotEmpty()) {
            glDeleteTextures(texturesToDelete.size, texturesToDelete.toIntArray(), 0)
            texturesToDelete.clear()
        }
        for (set in arrayOf(cur, nxt)) if (set.dirty) rebuildLabels(set)
        render()
    }

    private fun rebuildLabels(set: ConeSet) {
        set.dirty = false
        recycle(set)
        if (set.q == null && set !== cur) return // the next set gets its signs once its question is known
        for (i in set.labels.indices) set.tex[i] = makeLabelTexture(set.labels[i], LABEL_COLORS[i], set.wide)
    }

    private fun makeLabelTexture(text: String, color: Int, wide: Boolean): Int {
        val w = if (wide) 384 else 256
        val h = 128
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val rect = RectF(6f, 6f, w - 6f, h - 6f)
        p.color = color
        c.drawRoundRect(rect, 34f, 34f, p)
        p.style = Paint.Style.STROKE
        p.strokeWidth = 9f
        p.color = Color.WHITE
        c.drawRoundRect(rect, 34f, 34f, p)
        val tp = Paint(Paint.ANTI_ALIAS_FLAG)
        tp.color = Color.WHITE
        tp.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        tp.textAlign = Paint.Align.CENTER
        tp.setShadowLayer(5f, 0f, 3f, 0x99000000.toInt())
        // Right-to-left marks keep punctuation such as "?" on the correct side of Hebrew/Arabic text.
        val rlm = 0x200F.toChar().toString()
        fun shape(s: String) = if (s.any { it.code in 0x0590..0x06FF }) rlm + s + rlm else s
        val maxW = w - 36f
        tp.textSize = 96f
        while (tp.measureText(text) > maxW && tp.textSize > 56f) tp.textSize -= 4f
        if (tp.measureText(text) <= maxW || !text.contains(' ')) {
            while (tp.measureText(text) > maxW && tp.textSize > 24f) tp.textSize -= 2f
            val baseline = h / 2f - (tp.ascent() + tp.descent()) / 2f
            c.drawText(shape(text), w / 2f, baseline, tp)
        } else {
            // long answer: two lines, split at the space closest to the middle
            val mid = text.length / 2
            val cut = text.indices.filter { text[it] == ' ' }.minByOrNull { kotlin.math.abs(it - mid) }!!
            val l1 = text.substring(0, cut).trim()
            val l2 = text.substring(cut + 1).trim()
            tp.textSize = 52f
            while ((tp.measureText(l1) > maxW || tp.measureText(l2) > maxW) && tp.textSize > 22f) tp.textSize -= 2f
            val lineH = tp.descent() - tp.ascent()
            val top = h / 2f - lineH
            c.drawText(shape(l1), w / 2f, top - tp.ascent(), tp)
            c.drawText(shape(l2), w / 2f, top + lineH - tp.ascent(), tp)
        }

        val ids = IntArray(1)
        glGenTextures(1, ids, 0)
        glBindTexture(GL_TEXTURE_2D, ids[0])
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR_MIPMAP_LINEAR)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE)
        GLUtils.texImage2D(GL_TEXTURE_2D, 0, bmp, 0)
        glGenerateMipmap(GL_TEXTURE_2D)
        bmp.recycle()
        return ids[0]
    }


    private fun box(tx: Float, ty: Float, tz: Float, sx: Float, sy: Float, sz: Float): FloatArray {
        Matrix.setIdentityM(mm, 0)
        Matrix.translateM(mm, 0, tx, ty, tz)
        Matrix.scaleM(mm, 0, sx, sy, sz)
        return mm
    }

    private fun draw(
        mesh: Mesh, m: FloatArray, color: FloatArray, color2: FloatArray = color,
        mode: Float = 0f, alpha: Float = 1f, shine: Float = 24f, spec: Float = 0.25f,
        emissive: Float = 0f, tex: Int = 0
    ) {
        Matrix.multiplyMM(mvp, 0, vp, 0, m, 0)
        glUniformMatrix4fv(lit.uMVP, 1, false, mvp, 0)
        glUniformMatrix4fv(lit.uModel, 1, false, m, 0)
        glUniform3fv(lit.uColor, 1, color, 0)
        glUniform3fv(lit.uColor2, 1, color2, 0)
        glUniform1f(lit.uMode, mode)
        glUniform1f(lit.uAlpha, alpha)
        glUniform1f(lit.uShine, shine)
        glUniform1f(lit.uSpec, spec)
        glUniform1f(lit.uEmissive, emissive)
        if (tex != 0) {
            glActiveTexture(GL_TEXTURE0)
            glBindTexture(GL_TEXTURE_2D, tex)
            glUniform1i(lit.uTex, 0)
        }
        mesh.draw()
    }

    private fun fallAngle(t: Float): Float {
        val dur = 0.45f
        if (t < dur) {
            val p = t / dur
            return 84f * p * p
        }
        val s = t - dur
        return 84f - 9f * exp(-s * 7f) * abs(sin(s * 18f))
    }


    private fun render() {
        glClear(GL_COLOR_BUFFER_BIT or GL_DEPTH_BUFFER_BIT)

        val sx = if (shake > 0f) rf(-1f, 1f) * shake * 0.12f else 0f
        val sy = if (shake > 0f) rf(-1f, 1f) * shake * 0.12f else 0f
        Matrix.setLookAtM(
            view, 0, eye[0] + sx, eye[1] + sy, eye[2],
            look[0] + sx, look[1] + sy, look[2], 0f, 1f, 0f
        )
        Matrix.multiplyMM(vp, 0, proj, 0, view, 0)

        glEnable(GL_DEPTH_TEST)
        glDepthMask(true)
        glDisable(GL_BLEND)
        glDisable(GL_CULL_FACE)

        glUseProgram(lit.program)
        glUniform3fv(lit.uLightDir, 1, LIGHT, 0)
        glUniform3f(lit.uEye, eye[0], eye[1], eye[2])
        glUniform3fv(lit.uFog, 1, FOG, 0)
        glUniform2f(lit.uFogRange, 14f, 38f)

        // ---- alley (the endless lane has no pit: it just keeps going into the fog)
        val laneEnd = if (continuous) CONE_Z - 3f * SEGMENT else PIT_Z
        val laneLen = LANE_FRONT - laneEnd
        val laneC = (LANE_FRONT + laneEnd) / 2f
        draw(cube, box(0f, -0.15f, laneC, LANE_HALF * 2f, 0.3f, laneLen), WOOD1, WOOD2, mode = 2f, shine = 60f, spec = 0.55f)
        for (s in floatArrayOf(-1f, 1f)) {
            draw(cube, box(s * 2.25f, -0.2f, laneC, 0.5f, 0.25f, laneLen), GUTTER, shine = 40f, spec = 0.5f)
            draw(cube, box(s * 2.65f, 0.1f, laneC, 0.3f, 0.9f, laneLen), WALL)
            var z = 6f
            while (z > laneEnd + 1f) {
                draw(cube, box(s * 2.65f, 0.58f, z, 0.12f, 0.06f, 0.12f), LAMP, emissive = 1f)
                z -= 2f
            }
        }
        draw(cube, box(0f, -0.4f, -8f, 40f, 0.2f, 140f), CARPET)
        if (!continuous) {
            draw(cube, box(0f, -0.9f, -23.6f, 6f, 0.2f, 4f), DARK)
            draw(cube, box(0f, 1.2f, -25.6f, 7f, 4.5f, 0.4f), BACK)
            draw(cube, box(0f, 2.45f, -25.35f, 4.4f, 0.08f, 0.08f), NEON1, emissive = 1f)
            draw(cube, box(0f, 2.2f, -25.35f, 3.6f, 0.08f, 0.08f), NEON2, emissive = 1f)
        }

        // ---- cones
        val sets = if (continuous && nxt.q != null) arrayOf(cur, nxt) else arrayOf(cur)
        for (set in sets) {
            for (i in 0 until set.count) {
                if (set.exploded[i]) continue
                Matrix.setIdentityM(mm, 0)
                Matrix.translateM(mm, 0, set.x[i], 0f, set.z)
                if (set.fallT[i] >= 0f) {
                    val a = set.fallAxis[i]
                    Matrix.rotateM(mm, 0, fallAngle(set.fallT[i]), a[0], a[1], a[2])
                }
                Matrix.scaleM(mm, 0, CONE_SCALE, CONE_SCALE, CONE_SCALE)
                draw(cone, mm, CONE_COLORS[i], WHITE, mode = 4f, shine = 40f, spec = 0.4f)
            }
        }

        // ---- ball
        if (ballVisible) {
            Matrix.setIdentityM(mm, 0)
            Matrix.translateM(mm, 0, ballX, ballY, ballZ)
            Matrix.multiplyMM(tmp, 0, mm, 0, ballRot, 0)
            Matrix.scaleM(tmp, 0, BALL_R, BALL_R, BALL_R)
            draw(sphere, tmp, BALL1, BALL2, mode = 3f, shine = 90f, spec = 0.9f)
        }

        // ---- floor decals (opaque, offset); arrows repeat once per segment
        glEnable(GL_POLYGON_OFFSET_FILL)
        glPolygonOffset(-1f, -2f)
        val arrowRows = if (continuous) 3 else 1
        for (r in 0 until arrowRows) {
            for (k in -2..2) {
                draw(arrow, box(k * 0.6f, 0.005f, -6f - r * SEGMENT + abs(k) * 0.5f, 0.8f, 1f, 0.8f), ARROW)
            }
        }
        if (!continuous) draw(quadXZ, box(0f, 0.005f, 1.5f, LANE_HALF * 2f, 1f, 0.06f), BLACK)

        // ---- transparent: shadows and glows
        glEnable(GL_BLEND)
        glBlendFunc(GL_ONE, GL_ONE_MINUS_SRC_ALPHA)
        glDepthMask(false)
        if (ballVisible && ballY > 0f && (continuous || ballZ > PIT_Z)) {
            val h = ballY - BALL_R
            val ss = 0.9f / (1f + h * 0.6f)
            draw(quadXZ, box(ballX, 0.01f, ballZ, ss, 1f, ss), BLACK, mode = 5f, alpha = 0.55f / (1f + h))
        }
        for (set in sets) {
            for (i in 0 until set.count) {
                if (set.exploded[i] || set.fallT[i] >= 0f) continue
                draw(quadXZ, box(set.x[i], 0.01f, set.z, 1.0f, 1f, 1.0f), BLACK, mode = 5f, alpha = 0.4f)
            }
            val sq = set.q
            if (set.resolved && !set.correct && sq != null && set.hitTime < RESULT_TIME_EXPLAINED) {
                val a = 0.6f + 0.3f * sin(set.hitTime * 10f)
                draw(quadXZ, box(set.x[sq.correctIndex], 0.015f, set.z, 1.6f, 1f, 1.6f), GREEN, mode = 5f, alpha = a)
            }
        }
        if (blastGlow > 0f) {
            val s = 1f + (1f - blastGlow) * 5f
            draw(quadXZ, box(blastX, 0.02f, blastZ, s, 1f, s), ORANGE, mode = 5f, alpha = blastGlow)
        }
        glDisable(GL_POLYGON_OFFSET_FILL)

        // ---- answer signs (bigger while far away; every other sign raised so neighbours never overlap)
        for (set in sets) drawSigns(set)

        // ---- particles
        val n = particles.fill()
        if (n > 0) {
            glBlendFunc(GL_ONE, GL_ONE)
            glUseProgram(points.program)
            glUniformMatrix4fv(points.uVP, 1, false, vp, 0)
            glUniform1f(points.uScale, viewH * proj[5] * 0.5f)
            val b = particles.buffer
            b.position(0)
            glEnableVertexAttribArray(0)
            glVertexAttribPointer(0, 3, GL_FLOAT, false, 32, b)
            b.position(3)
            glEnableVertexAttribArray(1)
            glVertexAttribPointer(1, 4, GL_FLOAT, false, 32, b)
            b.position(7)
            glEnableVertexAttribArray(2)
            glVertexAttribPointer(2, 1, GL_FLOAT, false, 32, b)
            b.position(0)
            glDrawArrays(GL_POINTS, 0, n)
        }

        glDepthMask(true)
        glDisable(GL_BLEND)
    }

    private fun drawSigns(set: ConeSet) {
        val distScale = ((eye[2] - set.z) / 9f).coerceIn(1f, 1.6f)
        val signH = 0.47f * distScale
        for (i in 0 until set.count) {
            if (set.tex[i] == 0) continue
            val raise = if (i % 2 == 1) signH + 0.08f else 0f
            val baseY = 0.95f + signH / 2f + raise
            if (!set.exploded[i] && set.fallT[i] < 0f) {
                glDisable(GL_BLEND); glDepthMask(true)
                val poleH = baseY - signH / 2f - 0.78f
                draw(cube, box(set.x[i], 0.78f + poleH / 2f, set.z, 0.035f, poleH, 0.035f), CONE_COLORS[i])
                glEnable(GL_BLEND); glDepthMask(false)
            }
        }
        val q = set.q
        val t = set.hitTime
        for (i in 0 until set.count) {
            val tex = set.tex[i]
            if (tex == 0) continue
            var y = 0.95f + signH / 2f + (if (i % 2 == 1) signH + 0.08f else 0f)
            var alpha = 1f
            var s = distScale
            if (set.resolved && q != null) {
                if (i == q.correctIndex) {
                    if (set.correct) {
                        y += t * 1.6f
                        alpha = max(0f, 1f - t / 1.4f)
                        s = distScale * (1f + t * 0.5f)
                    } else {
                        s = distScale * (1f + 0.12f * sin(t * 12f))
                    }
                } else if (i == set.hitIndex) {
                    alpha = 0.45f
                }
            }
            if (alpha <= 0f) continue
            val bob = sin(time * 2f + i) * 0.04f
            draw(quadXY, box(set.x[i], y + bob, set.z + 0.1f, (if (set.wide) 1.41f else 0.94f) * s, 0.47f * s, 1f), WHITE, mode = 1f, alpha = alpha, tex = tex)
        }
    }
}
