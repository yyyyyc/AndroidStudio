package com.example.mathbowling

import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.opengl.GLSurfaceView
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.Surface
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.view.animation.OvershootInterpolator
import android.widget.Button
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.util.Locale
import kotlin.math.abs
import kotlin.math.atan2

class MainActivity : Activity(), SensorEventListener, GameListener {

    companion object {
        private const val PREFS = "mathbowling"
        private const val KEY_LANG = "lang"
        private const val KEY_SPEAK = "speak"
        private const val KEY_MUSIC = "music"
        private const val KEY_MODE = "mode"
        private const val KEY_ENDLESS = "endless"
        private const val KEY_VOICE = "voice"
        private const val REQ_MIC = 7
        private const val REQ_OPEN_QUESTIONS = 8
        private const val REQ_SAVE_SAMPLE = 9
        private const val REQ_AI_FILE = 10
        private const val REQ_EXPORT = 11
        /** Separate prefs file for the OpenAI key, left out of backups (res/xml/backup_rules.xml). */
        private const val SECRETS = "secrets"
        private const val KEY_OPENAI = "openaiKey"
        private const val KEY_AI_MODEL = "aiModel"
        private const val KEY_CUSTOM_URI = "customUri"
        private const val KEY_CUSTOM_NAME = "customName"
        /** Last good copy of the player's questions file. */
        private const val CUSTOM_FILE = "custom_questions.txt"
        private const val KEY_TRANSLIT = "translit"
        private const val KEY_BLOWOUT = "blowout"
        private const val KEY_FAST_VOICE = "fastVoice"
        private const val KEY_BARGE_IN = "bargeIn"
        private const val KEY_SPEED = "ballSpeed"
        /** Old 5-step tilt setting (0.5..2); read once to carry the choice over to the wider slider. */
        private const val KEY_TILT_OLD = "tiltSensitivity"
        private const val KEY_TILT = "tiltLevel"
        private const val KEY_SHAKE = "shakeJump"
        /** A spike this far from 1 g (m/s²) counts as a shake. */
        private const val SHAKE_THRESHOLD = 7f
        private const val SHAKE_COOLDOWN_MS = 700L
        /** Slider steps (index 2 = normal). */
        val SPEED_STEPS = floatArrayOf(0.6f, 0.8f, 1f, 1.25f, 1.5f)
        /** From barely reacting to reacting to the slightest tilt; index 4 = normal. */
        val TILT_STEPS = floatArrayOf(0.4f, 0.55f, 0.7f, 0.85f, 1f, 1.4f, 2f, 3f, 4.5f)
        const val TILT_NORMAL = 4
        private const val STATE_OPTIONS = "optionsOpen"

        fun isEnglish(context: Context) =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_LANG, "he") == "en"
    }

    private lateinit var glView: GLSurfaceView
    private lateinit var renderer: GameRenderer
    private lateinit var sound: SoundEngine
    private lateinit var prefs: SharedPreferences

    private lateinit var hud: LinearLayout
    private lateinit var riddleCard: View
    private lateinit var tvScore: TextView
    private lateinit var tvProgress: TextView
    private lateinit var tvPrompt: TextView
    private lateinit var tvExpression: TextView
    private lateinit var tvCountdown: TextView
    private lateinit var tvFeedback: TextView
    private lateinit var flash: View
    private lateinit var menu: View
    private lateinit var tvBest: TextView
    private lateinit var gameOver: View
    private lateinit var tvOverScore: TextView
    private lateinit var tvOverMsg: TextView
    private lateinit var tvOverBest: TextView
    private lateinit var btnMusic: TextView
    private lateinit var options: View
    private lateinit var swSpeak: Switch
    private lateinit var swVoice: Switch
    private lateinit var tvHeard: TextView
    private lateinit var voice: VoiceAnswerer
    private var voiceOn = false
    /** The current question was already answered by voice. */
    private var voiceAnswered = false
    /** Start listening once the question has been read aloud. */
    private var voicePending = false
    /** The question is being read aloud while the mic listens: filter the reading out of what is heard. */
    private var echoGuard = false
    /** The phone is reading the current question aloud right now. */
    private var readingQuestion = false
    /** Optional: listen already while the question is read (off = listen right after the reading, as before). */
    private var bargeIn = false
    /** The text the phone is reading now (the question). */
    private var spokenNow = ""
    private lateinit var swMusic: Switch
    private lateinit var swTranslit: Switch
    private lateinit var swBlowout: Switch
    private lateinit var sbSpeed: android.widget.SeekBar
    private lateinit var sbTilt: android.widget.SeekBar
    private lateinit var tvSpeedValue: TextView
    private lateinit var tvTiltValue: TextView
    /** Multiplies how far the phone must be tilted (higher = less tilt needed). */
    private var tiltSensitivity = 1f
    private var shakeJumpOn = true
    private var lastShakeMs = 0L
    /** Until then, sensor samples are ignored for steering (the phone is being shaken). */
    private var shakeQuietUntilMs = 0L
    private lateinit var tvHint: TextView
    private lateinit var tvTitle: TextView
    private lateinit var tvSubtitle: TextView
    private lateinit var btnModeMath: Button
    private lateinit var btnModeArabic: Button
    private lateinit var btnModeRussian: Button
    private lateinit var btnModeAi: Button
    private lateinit var btnModeCustom: Button
    private lateinit var gameButtons: View
    private lateinit var pausePanel: View
    private lateinit var ivPauseTitle: android.widget.ImageView
    private lateinit var tvPauseTitle: TextView
    private var gamePaused = false
    private lateinit var tvCustomStatus: TextView
    private lateinit var aiPanel: View
    private lateinit var tvAiFile: TextView
    private lateinit var tvAiStatus: TextView
    private lateinit var btnAiCreate: Button
    private var aiFileUri: android.net.Uri? = null
    private var aiFileName = ""
    /** A quiz request is running; set to null to cancel it. */
    @Volatile private var aiRun: Any? = null
    @Volatile private var aiConn: java.net.HttpURLConnection? = null
    /** Questions waiting to be written to the file the player picks. */
    private var pendingExport: String? = null
    /** The player's own questions (from the cached copy of their file). */
    private var customItems: List<CustomItem> = emptyList()

    private var sensorManager: SensorManager? = null
    private var accel: Sensor? = null
    private var filteredX = 0f
    private var filteredPitch = Float.NaN
    private var currentLevel = Level.EASY
    private var english = false
    private var speakOn = false
    private var isRolling = false
    private var mode = GameMode.MATH
    private var endlessOn = false
    /** The running game uses the endless lane. */
    private var endlessGame = false
    /** The running game uses one long lane (endless lane or blowout mode). */
    private var continuousGame = false
    private var lives = 0
    private var questionNumber = 0
    private lateinit var swEndless: Switch

    // two-player network game
    private lateinit var discovery: NetDiscovery
    private lateinit var mpPanel: View
    private lateinit var tvMpStatus: TextView
    private lateinit var mpGames: LinearLayout
    private lateinit var mpManual: View
    private lateinit var mpLevels: View
    private lateinit var etIp: android.widget.EditText
    private lateinit var tvOpponent: TextView
    private lateinit var btnAgain: Button
    private var link: NetLink? = null
    private var mpHost = false
    /** Connected to the other phone. */
    private var mpConnected = false
    /** The running game is a two-player game. */
    private var mpInGame = false
    private var lastStart: NetMsg.Start? = null
    private var oppScore = 0
    private var oppQuestion = 0
    private var oppDone: NetMsg.Done? = null
    private var myDone: NetMsg.Done? = null
    private var gameStartMs = 0L
    private val foundGames = HashSet<String>()
    private val hideFeedback = Runnable { tvFeedback.animate().alpha(0f).setDuration(300).withEndAction { tvFeedback.visibility = View.GONE; tvFeedback.alpha = 1f }.start() }
    private var translitOn = true
    private var currentQuestion: Question? = null
    private val hudPad by lazy { (12 * resources.displayMetrics.density).toInt() }
    private val main = Handler(Looper.getMainLooper())

    private var tts: TextToSpeech? = null

    // touch: swipe up = jump; holding a side of the screen = steer (after a short delay, so swipes don't steer)
    private var pendingSteer = 0f
    private val steerRunnable = Runnable { renderer.touchSteer = pendingSteer }
    private lateinit var gestures: GestureDetector

    override fun attachBaseContext(newBase: Context) {
        val locale = if (isEnglish(newBase)) Locale.ENGLISH else Locale.forLanguageTag("he-IL")
        val cfg = Configuration(newBase.resources.configuration)
        cfg.setLocale(locale)
        cfg.setLayoutDirection(locale)
        super.attachBaseContext(newBase.createConfigurationContext(cfg))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_main)
        hideSystemBars()

        glView = findViewById(R.id.glView)
        hud = findViewById(R.id.hud)
        riddleCard = findViewById(R.id.riddleCard)
        tvScore = findViewById(R.id.tvScore)
        tvProgress = findViewById(R.id.tvProgress)
        tvPrompt = findViewById(R.id.tvPrompt)
        tvExpression = findViewById(R.id.tvExpression)
        tvCountdown = findViewById(R.id.tvCountdown)
        tvFeedback = findViewById(R.id.tvFeedback)
        flash = findViewById(R.id.flash)
        menu = findViewById(R.id.menu)
        tvBest = findViewById(R.id.tvBest)
        gameOver = findViewById(R.id.gameOver)
        tvOverScore = findViewById(R.id.tvOverScore)
        tvOverMsg = findViewById(R.id.tvOverMsg)
        tvOverBest = findViewById(R.id.tvOverBest)
        btnMusic = findViewById(R.id.btnMusic)
        options = findViewById(R.id.options)
        swSpeak = findViewById(R.id.swSpeak)
        swVoice = findViewById(R.id.swVoice)
        tvHeard = findViewById(R.id.tvHeard)
        swMusic = findViewById(R.id.swMusic)
        swTranslit = findViewById(R.id.swTranslit)
        tvHint = findViewById(R.id.tvHint)
        tvTitle = findViewById(R.id.tvTitle)
        tvSubtitle = findViewById(R.id.tvSubtitle)
        btnModeMath = findViewById(R.id.btnModeMath)
        btnModeArabic = findViewById(R.id.btnModeArabic)
        btnModeRussian = findViewById(R.id.btnModeRussian)
        btnModeAi = findViewById(R.id.btnModeAi)
        btnModeCustom = findViewById(R.id.btnModeCustom)
        gameButtons = findViewById(R.id.gameButtons)
        pausePanel = findViewById(R.id.pausePanel)
        ivPauseTitle = findViewById(R.id.ivPauseTitle)
        tvPauseTitle = findViewById(R.id.tvPauseTitle)
        findViewById<View>(R.id.btnPause).setOnClickListener { pauseGame(confirmExit = false) }
        findViewById<View>(R.id.btnExit).setOnClickListener { pauseGame(confirmExit = true) }
        findViewById<View>(R.id.btnResume).setOnClickListener { resumeGame() }
        findViewById<View>(R.id.btnPauseExit).setOnClickListener { resumeGame(toMenu = true) }
        tvCustomStatus = findViewById(R.id.tvCustomStatus)
        swEndless = findViewById(R.id.swEndless)
        mpPanel = findViewById(R.id.mpPanel)
        tvMpStatus = findViewById(R.id.tvMpStatus)
        mpGames = findViewById(R.id.mpGames)
        mpManual = findViewById(R.id.mpManual)
        mpLevels = findViewById(R.id.mpLevels)
        etIp = findViewById(R.id.etIp)
        tvOpponent = findViewById(R.id.tvOpponent)
        btnAgain = findViewById(R.id.btnAgain)
        prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        english = isEnglish(this)
        // android:layoutDirection="locale" follows the phone's language, not the in-app one
        findViewById<View>(R.id.root).layoutDirection = if (english) View.LAYOUT_DIRECTION_LTR else View.LAYOUT_DIRECTION_RTL
        sbSpeed = findViewById(R.id.sbSpeed)
        sbTilt = findViewById(R.id.sbTilt)
        tvSpeedValue = findViewById(R.id.tvSpeedValue)
        tvTiltValue = findViewById(R.id.tvTiltValue)
        speakOn = prefs.getBoolean(KEY_SPEAK, false)
        mode = GameMode.values().firstOrNull { it.name == prefs.getString(KEY_MODE, null) } ?: GameMode.MATH
        translitOn = prefs.getBoolean(KEY_TRANSLIT, true)

        sound = SoundEngine(this)
        sound.musicOn = prefs.getBoolean(KEY_MUSIC, true)
        sound.load()
        updateMusicButton()

        renderer = GameRenderer(this, english)
        renderer.speakMode = speakOn
        glView.setEGLContextClientVersion(2)
        glView.preserveEGLContextOnPause = true
        glView.setRenderer(renderer)
        glView.renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        setupTouch()

        sensorManager = getSystemService(SensorManager::class.java)
        accel = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

        findViewById<Button>(R.id.btnEasy).setOnClickListener { startGame(Level.EASY) }
        findViewById<Button>(R.id.btnMedium).setOnClickListener { startGame(Level.MEDIUM) }
        findViewById<Button>(R.id.btnHard).setOnClickListener { startGame(Level.HARD) }
        btnAgain.setOnClickListener {
            val last = lastStart
            when {
                mpConnected && mpHost && last != null -> hostStart(last.level)
                mpConnected -> {} // the guest waits for the host
                else -> startGame(currentLevel)
            }
        }
        setupMultiplayer()
        findViewById<Button>(R.id.btnMenu).setOnClickListener { showMenu() }
        findViewById<Button>(R.id.btnOptions).setOnClickListener { showOptions() }
        btnModeMath.setOnClickListener { setMode(GameMode.MATH) }
        btnModeArabic.setOnClickListener { setMode(GameMode.ARABIC) }
        btnModeRussian.setOnClickListener { setMode(GameMode.RUSSIAN) }
        btnModeAi.setOnClickListener { setMode(GameMode.AI) }
        btnModeCustom.setOnClickListener {
            setMode(GameMode.CUSTOM)
            if (customItems.isEmpty()) {
                Toast.makeText(this, R.string.custom_none, Toast.LENGTH_LONG).show()
                showOptions()
            } else {
                refreshCustomFromSource()
            }
        }
        findViewById<Button>(R.id.btnLoadQuestions).setOnClickListener { pickQuestionsFile() }
        findViewById<Button>(R.id.btnSampleQuestions).setOnClickListener { saveSampleFile() }
        setupAiPanel()
        customItems = readCustomCache()?.let { CustomQuestions.parse(it).items } ?: emptyList()
        updateCustomStatus()
        endlessOn = prefs.getBoolean(KEY_ENDLESS, false)
        swEndless.isChecked = endlessOn
        swEndless.setOnCheckedChangeListener { _, on ->
            endlessOn = on
            prefs.edit().putBoolean(KEY_ENDLESS, on).apply()
            updateBestLine()
        }
        // tap the foreign word to hear it
        tvExpression.setOnClickListener { currentQuestion?.let { q -> q.foreignSpeech?.let { sayForeign(it, q) } } }
        findViewById<Button>(R.id.btnOptionsBack).setOnClickListener { options.visibility = View.GONE; updateBestLine() }
        btnMusic.setOnClickListener { setMusic(!sound.musicOn) }
        setupOptions()

        findViewById<View>(R.id.root).setOnApplyWindowInsetsListener { _, insets ->
            val top = if (Build.VERSION.SDK_INT >= 30) {
                insets.getInsetsIgnoringVisibility(
                    WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()
                ).top
            } else {
                @Suppress("DEPRECATION")
                insets.systemWindowInsetTop
            }
            hud.setPadding(hudPad, hudPad + top, hudPad, hudPad)
            (btnMusic.layoutParams as ViewGroup.MarginLayoutParams).topMargin = hudPad + top
            btnMusic.requestLayout()
            insets
        }

        if (speakOn) initTts()
        showMenu()
        if (savedInstanceState?.getBoolean(STATE_OPTIONS) == true) showOptions()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_OPTIONS, options.visibility == View.VISIBLE)
    }

    // ------------------------------------------------------------------ options

    private fun setupOptions() {
        val rg = findViewById<RadioGroup>(R.id.rgLang)
        findViewById<RadioButton>(if (english) R.id.rbEnglish else R.id.rbHebrew).isChecked = true
        rg.setOnCheckedChangeListener { _, id ->
            val wantEnglish = id == R.id.rbEnglish
            if (wantEnglish != english) {
                prefs.edit().putString(KEY_LANG, if (wantEnglish) "en" else "he").commit()
                recreate()
            }
        }
        swSpeak.isChecked = speakOn
        swSpeak.setOnCheckedChangeListener { _, on ->
            speakOn = on
            prefs.edit().putBoolean(KEY_SPEAK, on).apply()
            renderer.speakMode = on
            if (on) initTts() else tts?.stop()
        }
        swMusic.isChecked = sound.musicOn
        swMusic.setOnCheckedChangeListener { _, on -> setMusic(on) }
        voice = VoiceAnswerer(this, ::onVoiceHeard) { listening ->
            if (listening && !voiceAnswered && hud.visibility == View.VISIBLE) {
                tvHeard.visibility = View.VISIBLE
                tvHeard.text = getString(R.string.voice_listening)
            }
        }
        voiceOn = prefs.getBoolean(KEY_VOICE, false) &&
            checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED
        swVoice.isChecked = voiceOn
        swVoice.setOnCheckedChangeListener { _, on -> setVoice(on) }
        val swBargeIn = findViewById<Switch>(R.id.swBargeIn)
        bargeIn = prefs.getBoolean(KEY_BARGE_IN, false)
        swBargeIn.isChecked = bargeIn
        swBargeIn.setOnCheckedChangeListener { _, on ->
            bargeIn = on
            prefs.edit().putBoolean(KEY_BARGE_IN, on).apply()
        }
        val swFastVoice = findViewById<Switch>(R.id.swFastVoice)
        voice.preferOffline = prefs.getBoolean(KEY_FAST_VOICE, true)
        swFastVoice.isChecked = voice.preferOffline
        swFastVoice.setOnCheckedChangeListener { _, on ->
            voice.preferOffline = on
            prefs.edit().putBoolean(KEY_FAST_VOICE, on).apply()
        }
        swBlowout = findViewById(R.id.swBlowout)
        swBlowout.isChecked = prefs.getBoolean(KEY_BLOWOUT, false)
        swBlowout.isEnabled = voiceOn
        swBlowout.setOnCheckedChangeListener { _, on ->
            prefs.edit().putBoolean(KEY_BLOWOUT, on).apply()
        }
        swTranslit.isChecked = translitOn
        swTranslit.setOnCheckedChangeListener { _, on ->
            translitOn = on
            prefs.edit().putBoolean(KEY_TRANSLIT, on).apply()
        }
        setupSlider(sbSpeed, KEY_SPEED, tvSpeedValue, R.array.speed_names) { i ->
            renderer.speedSetting = SPEED_STEPS[i]
        }
        if (!prefs.contains(KEY_TILT) && prefs.contains(KEY_TILT_OLD)) {
            // carry the old 5-step choice over to the nearest step of the wider slider
            val old = floatArrayOf(0.5f, 0.75f, 1f, 1.4f, 2f)[prefs.getInt(KEY_TILT_OLD, 2).coerceIn(0, 4)]
            val nearest = TILT_STEPS.indices.minByOrNull { abs(TILT_STEPS[it] - old) } ?: TILT_NORMAL
            prefs.edit().putInt(KEY_TILT, nearest).apply()
        }
        val swShake = findViewById<Switch>(R.id.swShake)
        shakeJumpOn = prefs.getBoolean(KEY_SHAKE, true)
        swShake.isChecked = shakeJumpOn
        swShake.setOnCheckedChangeListener { _, on ->
            shakeJumpOn = on
            prefs.edit().putBoolean(KEY_SHAKE, on).apply()
        }
        setupSlider(sbTilt, KEY_TILT, tvTiltValue, R.array.tilt_names, TILT_NORMAL) { i ->
            tiltSensitivity = TILT_STEPS[i]
            renderer.tiltSensitivity = TILT_STEPS[i]
        }
    }

    /** A 5-step slider saved in prefs; [apply] gets the step index (2 = normal). */
    private fun setupSlider(sb: android.widget.SeekBar, key: String, label: TextView, names: Int, default: Int = 2, apply: (Int) -> Unit) {
        val labels = resources.getStringArray(names)
        sb.max = labels.size - 1
        val start = prefs.getInt(key, default).coerceIn(0, sb.max)
        sb.progress = start
        label.text = labels[start]
        apply(start)
        sb.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: android.widget.SeekBar, p: Int, fromUser: Boolean) {
                label.text = labels[p]
                apply(p)
                prefs.edit().putInt(key, p).apply()
            }
            override fun onStartTrackingTouch(s: android.widget.SeekBar) {}
            override fun onStopTrackingTouch(s: android.widget.SeekBar) {}
        })
    }

    private fun showOptions() {
        swMusic.isChecked = sound.musicOn
        options.visibility = View.VISIBLE
    }

    private fun setMusic(on: Boolean) {
        if (sound.musicOn == on) return
        sound.musicOn = on
        prefs.edit().putBoolean(KEY_MUSIC, on).apply()
        updateMusicButton()
        if (swMusic.isChecked != on) swMusic.isChecked = on
    }

    // ------------------------------------------------------------------ speech

    private fun uiLocale(): Locale = if (english) Locale.US else Locale.forLanguageTag("he-IL")

    /** Speaks a foreign word; stress marks (U+0301) are removed so the voice reads it naturally. */
    private fun sayForeign(text: String, q: Question) {
        val tag = q.foreignLang ?: return
        say(text.filter { it.code != 0x0301 }, Locale.forLanguageTag(tag))
    }

    private var ttsInitDone = false
    private var ttsLocaleNow: Locale? = null
    private val missingVoiceWarned = HashSet<String>()
    private var pendingSpeech: Pair<String, Locale>? = null
    /** The last [say] really started speaking (a voice for the language exists). */
    private var lastSayOk = false

    private fun initTts() {
        if (tts != null) return
        tts = TextToSpeech(this) { status ->
            if (status != TextToSpeech.SUCCESS) {
                Toast.makeText(this, R.string.tts_missing, Toast.LENGTH_LONG).show()
                return@TextToSpeech
            }
            ttsInitDone = true
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) { main.post { sound.duck(true) } }
                override fun onDone(utteranceId: String?) {
                    main.post {
                        sound.duck(false)
                        readingQuestion = false
                        if (voicePending) startVoice()
                    }
                }
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    main.post {
                        sound.duck(false)
                        readingQuestion = false
                        if (voicePending) startVoice()
                    }
                }
            })
            pendingSpeech?.let { (text, loc) -> say(text, loc) }
            pendingSpeech = null
        }
    }

    /** Speaks [text] in [locale], switching the voice when needed. */
    private fun say(text: String, locale: Locale, flush: Boolean = true) {
        lastSayOk = false
        if (tts == null) initTts()
        val engine = tts ?: return
        if (!ttsInitDone) { pendingSpeech = Pair(text, locale); return }
        if (ttsLocaleNow != locale) {
            val r = engine.setLanguage(locale)
            if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
                if (missingVoiceWarned.add(locale.language)) {
                    Toast.makeText(this, R.string.tts_missing, Toast.LENGTH_LONG).show()
                }
                ttsLocaleNow = null
                return
            }
            ttsLocaleNow = locale
        }
        lastSayOk = engine.speak(text, if (flush) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, null, "q") == TextToSpeech.SUCCESS
    }

    private fun speak(q: Question) {
        if (!speakOn) return
        spokenNow = listOfNotNull(q.foreignSpeech, q.prompt, q.expression, spokenText(q)).joinToString(" ")
        when {
            q.foreignSpeech != null -> sayForeign(q.foreignSpeech, q)
            q.textExpression -> say(q.prompt + " " + q.expression, uiLocale())
            else -> say(spokenText(q), uiLocale())
        }
    }

    private fun stopSpeaking() {
        voicePending = false
        echoGuard = false
        readingQuestion = false
        if (::voice.isInitialized) voice.stop()
        tvHeard.visibility = View.GONE
        tts?.stop()
        pendingSpeech = null
        sound.duck(false)
    }

    /** Turns "7 + ? = 12" into words the speech engine reads naturally. */
    private fun spokenText(q: Question): String {
        val e = q.expression
        if (e.isEmpty()) return q.prompt
        fun words(s: String): String {
            val w = if (english)
                listOf(" plus ", " minus ", " times ", " divided by ", " equals ", " squared", " what")
            else
                listOf(" ועוד ", " פחות ", " כפול ", " חלקי ", " שווה ", " בריבוע", " כמה")
            return s.replace("(", "").replace(")", "")
                .replace("+", w[0]).replace("−", w[1]).replace("×", w[2]).replace("÷", w[3])
                .replace("²", w[5]).replace("=", w[4]).replace("?", w[6])
                .replace(Regex("\\s+"), " ").trim()
        }
        return when {
            e.endsWith("= ?") -> {
                val left = words(e.removeSuffix("= ?"))
                if (english) "What is $left?" else "כמה זה $left?"
            }
            e.endsWith(", ?") -> q.prompt + " " + e.removeSuffix(", ?") + (if (english) ", and then?" else ", ומה הלאה?")
            else -> q.prompt + ". " + words(e)
        }
    }

    // ------------------------------------------------------------------ two-player network game

    private fun setupMultiplayer() {
        discovery = NetDiscovery(this)
        discovery.onFound = { game -> addFoundGame(game) }
        findViewById<Button>(R.id.btnMultiplayer).setOnClickListener { showMultiplayer() }
        findViewById<Button>(R.id.btnMpBack).setOnClickListener { leaveMultiplayer(); mpPanel.visibility = View.GONE }
        findViewById<Button>(R.id.btnHostGame).setOnClickListener { hostGame() }
        findViewById<Button>(R.id.btnJoinGame).setOnClickListener { joinGame() }
        findViewById<Button>(R.id.btnMpConnect).setOnClickListener {
            val text = etIp.text.toString().trim()
            if (text.isEmpty()) return@setOnClickListener
            val host = text.substringBefore(':')
            val port = text.substringAfter(':', "").toIntOrNull() ?: NetLink.DEFAULT_PORT
            connectTo(host, port)
        }
        findViewById<Button>(R.id.btnMpEasy).setOnClickListener { hostStart(Level.EASY) }
        findViewById<Button>(R.id.btnMpMedium).setOnClickListener { hostStart(Level.MEDIUM) }
        findViewById<Button>(R.id.btnMpHard).setOnClickListener { hostStart(Level.HARD) }
    }

    private fun showMultiplayer() {
        leaveMultiplayer()
        tvMpStatus.setText(R.string.mp_choose)
        mpPanel.visibility = View.VISIBLE
    }

    private fun resetMpPanel() {
        mpGames.removeAllViews()
        foundGames.clear()
        mpManual.visibility = View.GONE
        mpLevels.visibility = View.GONE
    }

    private fun newLink(): NetLink {
        link?.close()
        val l = NetLink(object : NetLink.Listener {
            override fun onConnected(isHost: Boolean) = runOnUiThread { onMpConnected(isHost) }
            override fun onMessage(msg: NetMsg) = runOnUiThread { onMpMessage(msg) }
            override fun onClosed() = runOnUiThread { onMpClosed() }
        })
        link = l
        return l
    }

    private fun hostGame() {
        discovery.stop()
        resetMpPanel()
        val port = try { newLink().host() } catch (e: Exception) {
            tvMpStatus.setText(R.string.mp_failed); return
        }
        discovery.advertise(discovery.deviceName(), port)
        val ip = discovery.localAddress() ?: "?"
        val address = if (port == NetLink.DEFAULT_PORT) ip else "$ip:$port"
        tvMpStatus.text = getString(R.string.mp_waiting, address)
    }

    private fun joinGame() {
        link?.close(); link = null
        discovery.stop()
        resetMpPanel()
        mpManual.visibility = View.VISIBLE
        tvMpStatus.setText(R.string.mp_searching)
        discovery.search()
    }

    private fun addFoundGame(game: NetDiscovery.Game) {
        if (mpConnected || mpPanel.visibility != View.VISIBLE || !foundGames.add(game.name)) return
        val b = Button(this).apply {
            text = "🎳 " + game.name.removePrefix("MathBowling · ")
            setTextColor(android.graphics.Color.WHITE)
            textSize = 18f
            isAllCaps = false
            stateListAnimator = null
            setBackgroundResource(R.drawable.btn_purple)
            setOnClickListener { connectTo(game.host, game.port) }
        }
        val lp = LinearLayout.LayoutParams((260 * resources.displayMetrics.density).toInt(), (54 * resources.displayMetrics.density).toInt())
        lp.topMargin = (10 * resources.displayMetrics.density).toInt()
        mpGames.addView(b, lp)
    }

    private fun connectTo(host: String, port: Int) {
        discovery.stopSearching()
        tvMpStatus.setText(R.string.mp_connecting)
        newLink().connect(host, port)
    }

    private fun modeName(m: GameMode) = getString(when (m) {
        GameMode.MATH -> R.string.mode_math
        GameMode.ARABIC -> R.string.mode_arabic
        GameMode.RUSSIAN -> R.string.mode_russian
        GameMode.AI -> R.string.mode_ai
        GameMode.CUSTOM -> R.string.mode_custom
    }) + if (endlessOn) " · 🛣️" else ""

    private fun onMpConnected(isHost: Boolean) {
        mpConnected = true
        mpHost = isHost
        discovery.stop()
        resetMpPanel()
        vibrate(longArrayOf(0, 60))
        if (isHost) {
            tvMpStatus.text = getString(R.string.mp_connected_host, modeName(mode))
            mpLevels.visibility = View.VISIBLE
        } else {
            tvMpStatus.setText(R.string.mp_connected_guest)
        }
    }

    /** Host: start the same game on both phones. */
    private fun hostStart(level: Level) {
        val l = link ?: return
        if (!mpConnected) return
        val customText = if (mode == GameMode.CUSTOM) readCustomCache() else null
        if (mode == GameMode.CUSTOM && customItems.isEmpty()) {
            Toast.makeText(this, R.string.custom_none, Toast.LENGTH_LONG).show()
            return
        }
        val start = NetMsg.Start(kotlin.random.Random.nextLong(), mode, level, endlessOn, customText)
        l.send(start)
        beginMpGame(start)
    }

    private fun beginMpGame(start: NetMsg.Start) {
        lastStart = start
        mpInGame = true
        oppScore = 0; oppQuestion = 0
        oppDone = null; myDone = null
        gameStartMs = System.currentTimeMillis()
        updateOpponent()
        startGame(start.level, start.mode, start.endless, start.seed, start.customText)
    }

    private fun onMpMessage(msg: NetMsg) {
        when (msg) {
            is NetMsg.Start -> if (!mpHost) beginMpGame(msg)
            is NetMsg.Result -> { oppScore = msg.score; oppQuestion = msg.question; updateOpponent() }
            is NetMsg.Done -> {
                oppDone = msg
                oppScore = msg.score
                updateOpponent()
                if (gameOver.visibility == View.VISIBLE) showMpResult()
            }
            NetMsg.Bye -> {} // onClosed follows
        }
    }

    private fun onMpClosed() {
        val wasConnected = mpConnected || link != null
        mpConnected = false
        link = null
        if (!wasConnected) return
        if (mpPanel.visibility == View.VISIBLE && !mpInGame) {
            resetMpPanel()
            tvMpStatus.setText(R.string.mp_failed)
        } else {
            Toast.makeText(this, R.string.mp_disconnected, Toast.LENGTH_LONG).show()
        }
        if (mpInGame) {
            mpInGame = false
            tvOpponent.visibility = View.GONE
            btnAgain.isEnabled = true
            btnAgain.setText(R.string.play_again)
        }
    }

    private fun updateOpponent() {
        val done = oppDone
        tvOpponent.text = if (done != null) getString(R.string.mp_opponent_done, done.score)
        else getString(R.string.mp_opponent, oppScore, oppQuestion)
    }

    /** Game-over screen of a two-player game: wait for the other phone, then show who won. */
    private fun showMpResult() {
        val mine = myDone ?: return
        val theirs = oppDone
        if (theirs == null) {
            tvOverMsg.text = getString(R.string.mp_wait_result, oppScore)
            tvOverMsg.setTextColor(0xFFFFE36E.toInt())
        } else {
            val res = when {
                mine.score > theirs.score -> R.string.mp_win
                mine.score < theirs.score -> R.string.mp_lose
                mine.timeMs < theirs.timeMs -> R.string.mp_win_time
                mine.timeMs > theirs.timeMs -> R.string.mp_lose_time
                else -> R.string.mp_tie
            }
            tvOverMsg.setText(res)
            val won = res == R.string.mp_win || res == R.string.mp_win_time
            tvOverMsg.setTextColor(if (won) 0xFF7CFF6B.toInt() else 0xFFFFB3E6.toInt())
            tvOverBest.text = getString(R.string.mp_scores, mine.score, theirs.score)
            if (won) sound.blast()
        }
        if (mpConnected && !mpHost) {
            btnAgain.isEnabled = false
            btnAgain.setText(R.string.mp_wait_host)
        }
    }

    /** Ends any network game or search and tells the other phone. */
    private fun leaveMultiplayer() {
        if (!::discovery.isInitialized) return
        discovery.stop()
        link?.close()
        link = null
        mpConnected = false
        mpInGame = false
        tvOpponent.visibility = View.GONE
        resetMpPanel()
    }

    // ------------------------------------------------------------------ voice answers

    private fun setVoice(on: Boolean) {
        if (on && !voice.available) {
            Toast.makeText(this, R.string.voice_unavailable, Toast.LENGTH_LONG).show()
            swVoice.isChecked = false
            return
        }
        if (on && checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), REQ_MIC)
            return // the switch is settled in onRequestPermissionsResult
        }
        voiceOn = on
        swBlowout.isEnabled = on
        prefs.edit().putBoolean(KEY_VOICE, on).apply()
        if (!on) voice.stop()
    }

    @Deprecated("Deprecated in Java")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        @Suppress("DEPRECATION")
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_MIC) return
        val granted = grantResults.firstOrNull() == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!granted) Toast.makeText(this, R.string.voice_permission, Toast.LENGTH_LONG).show()
        voiceOn = granted
        swBlowout.isEnabled = granted
        prefs.edit().putBoolean(KEY_VOICE, granted).apply()
        if (swVoice.isChecked != granted) swVoice.isChecked = granted
    }

    /** Answers are said in the language written on the cones. */
    private fun voiceLocale(q: Question): Locale {
        val reverse = q.foreignLang != null && q.foreignSpeech == null
        return if (reverse) Locale.forLanguageTag(q.foreignLang!!) else uiLocale()
    }

    private fun startVoice() {
        voicePending = false
        val q = currentQuestion ?: return
        if (!voiceOn || voiceAnswered || hud.visibility != View.VISIBLE) return
        voice.start(voiceLocale(q), q.labels)
    }

    private fun onVoiceHeard(guesses: List<String>, final: Boolean) {
        val q = currentQuestion ?: return
        if (voiceAnswered) return
        // while the phone reads the question, the mic hears it too: keep only what the player added
        val heard = if (echoGuard) guesses.map { AnswerMatcher.withoutEcho(it, spokenNow) }.filter { it.isNotBlank() } else guesses
        if (heard.isEmpty()) return
        tvHeard.visibility = View.VISIBLE
        tvHeard.text = getString(R.string.voice_heard, heard.first())
        val idx = AnswerMatcher.match(heard, q.labels)
        if (idx == null) {
            // the recognizer ends a stretch of speech with a final result and starts afresh: once the reading is
            // over, the new stretch holds only the player's words, so the filter can go
            if (final && !readingQuestion) echoGuard = false
            return
        }
        voiceAnswered = true
        voice.stop()
        echoGuard = false
        if (readingQuestion) {
            // answered in the middle of the reading: no need to finish it
            readingQuestion = false
            tts?.stop()
            sound.duck(false)
        }
        tvHeard.text = getString(R.string.voice_heard, q.labels[idx])
        val number = questionNumber
        glView.queueEvent { renderer.voiceAnswer(number, idx) }
    }

    // ------------------------------------------------------------------ touch

    private fun setupTouch() {
        gestures = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
                if (-vy > 700f && abs(vy) > abs(vx) * 1.2f) {
                    main.removeCallbacks(steerRunnable)
                    renderer.touchSteer = 0f
                    renderer.jumpRequest = true
                    return true
                }
                return false
            }
        })
        glView.setOnTouchListener { v, e ->
            gestures.onTouchEvent(e)
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    pendingSteer = if (e.x < v.width / 2f) -1f else 1f
                    main.postDelayed(steerRunnable, 140)
                }
                MotionEvent.ACTION_MOVE -> {
                    pendingSteer = if (e.x < v.width / 2f) -1f else 1f
                    if (renderer.touchSteer != 0f) renderer.touchSteer = pendingSteer
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    main.removeCallbacks(steerRunnable)
                    renderer.touchSteer = 0f
                }
            }
            true
        }
    }

    // ------------------------------------------------------------------ flow

    private fun startGame(level: Level, m: GameMode = mode, e: Boolean = endlessOn, seed: Long? = null, customText: String? = null) {
        if (m == GameMode.CUSTOM) {
            // a two-player guest plays the host's questions
            val items = if (customText != null) CustomQuestions.parse(customText).items else customItems
            if (items.isEmpty()) {
                Toast.makeText(this, R.string.custom_none, Toast.LENGTH_LONG).show()
                showOptions()
                return
            }
            renderer.customItems = items
        }
        currentLevel = level
        mpPanel.visibility = View.GONE
        menu.visibility = View.GONE
        options.visibility = View.GONE
        gameOver.visibility = View.GONE
        tvFeedback.visibility = View.GONE
        tvCountdown.visibility = View.GONE
        hud.visibility = View.VISIBLE
        gameButtons.visibility = View.VISIBLE
        pausePanel.visibility = View.GONE
        gamePaused = false
        endlessGame = e
        val blowoutGame = voiceOn && swBlowout.isChecked
        continuousGame = e || blowoutGame
        lives = GameRenderer.ENDLESS_LIVES
        main.removeCallbacks(hideFeedback)
        btnAgain.isEnabled = true
        btnAgain.setText(R.string.play_again)
        tvOpponent.visibility = if (mpInGame) View.VISIBLE else View.GONE
        glView.queueEvent { renderer.startGame(level, m, e, seed, blowoutGame) }
    }

    private fun showMenu() {
        gamePaused = false
        renderer.paused = false
        pausePanel.visibility = View.GONE
        gameButtons.visibility = View.GONE
        leaveMultiplayer()
        isRolling = false
        sound.setRolling(false)
        stopSpeaking()
        hud.visibility = View.GONE
        gameOver.visibility = View.GONE
        tvFeedback.visibility = View.GONE
        tvCountdown.visibility = View.GONE
        menu.visibility = View.VISIBLE
        currentQuestion = null
        updateModeUi()
        glView.queueEvent { renderer.toMenu() }
    }

    private fun updateBestLine() {
        tvBest.text = getString(R.string.best_line, best(Level.EASY), best(Level.MEDIUM), best(Level.HARD))
    }

    private fun bestKey(l: Level, m: GameMode, endless: Boolean) = baseBestKey(l, m) + (if (endless) "_END" else "")

    private fun baseBestKey(l: Level, m: GameMode) = when (m) {
        GameMode.MATH -> "best_${l.name}"
        GameMode.ARABIC -> "best_AR_${l.name}"
        GameMode.RUSSIAN -> "best_RU_${l.name}"
        GameMode.AI -> "best_AI_${l.name}"
        GameMode.CUSTOM -> "best_CU_${l.name}"
    }
    private fun best(l: Level) = prefs.getInt(bestKey(l, mode, endlessOn), 0)

    private fun setMode(m: GameMode) {
        mode = m
        prefs.edit().putString(KEY_MODE, m.name).apply()
        updateModeUi()
    }

    private fun updateModeUi() {
        tvTitle.setText(when (mode) {
            GameMode.MATH -> R.string.menu_title
            GameMode.ARABIC -> R.string.menu_title_arabic
            GameMode.RUSSIAN -> R.string.menu_title_russian
            GameMode.AI -> R.string.menu_title_ai
            GameMode.CUSTOM -> R.string.menu_title_custom
        })
        tvSubtitle.setText(when (mode) {
            GameMode.MATH -> R.string.menu_subtitle
            GameMode.ARABIC -> R.string.menu_subtitle_arabic
            GameMode.RUSSIAN -> R.string.menu_subtitle_russian
            GameMode.AI -> R.string.menu_subtitle_ai
            GameMode.CUSTOM -> R.string.menu_subtitle_custom
        })
        btnModeMath.alpha = if (mode == GameMode.MATH) 1f else 0.4f
        btnModeArabic.alpha = if (mode == GameMode.ARABIC) 1f else 0.4f
        btnModeRussian.alpha = if (mode == GameMode.RUSSIAN) 1f else 0.4f
        btnModeAi.alpha = if (mode == GameMode.AI) 1f else 0.4f
        btnModeCustom.alpha = if (mode == GameMode.CUSTOM) 1f else 0.4f
        updateBestLine()
    }

    private fun updateMusicButton() {
        btnMusic.text = if (sound.musicOn) "🔊" else "🔇"
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            aiPanel.visibility == View.VISIBLE -> closeAiPanel()
            options.visibility == View.VISIBLE -> { options.visibility = View.GONE; updateBestLine() }
            mpPanel.visibility == View.VISIBLE -> { leaveMultiplayer(); mpPanel.visibility = View.GONE }
            menu.visibility == View.VISIBLE -> {
                @Suppress("DEPRECATION")
                super.onBackPressed()
            }
            pausePanel.visibility == View.VISIBLE -> resumeGame()
            gameRunning() -> pauseGame(confirmExit = true)
            else -> showMenu()
        }
    }

    // ------------------------------------------------------------------ GameListener (GL thread)

    override fun onQuestion(q: Question, index: Int, total: Int, score: Int) = runOnUiThread {
        questionNumber = index
        if (menu.visibility == View.VISIBLE) return@runOnUiThread
        if (!continuousGame) tvFeedback.visibility = View.GONE
        tvScore.text = getString(R.string.score, score)
        updateProgress(total)
        tvPrompt.text = q.prompt
        currentQuestion = q
        tvPrompt.textSize = if (q.expression.isEmpty()) 23f else 22f
        if (q.expression.isEmpty()) {
            tvExpression.visibility = View.GONE
        } else {
            tvExpression.visibility = View.VISIBLE
            tvExpression.text = q.expression
            tvExpression.textDirection = if (q.textExpression) View.TEXT_DIRECTION_FIRST_STRONG else View.TEXT_DIRECTION_LTR
            tvExpression.textSize = if (q.textExpression) 40f else 44f
        }
        if (translitOn && q.hint.isNotEmpty()) {
            tvHint.visibility = View.VISIBLE
            tvHint.text = q.hint
        } else {
            tvHint.visibility = View.GONE
        }
        riddleCard.scaleX = 0.85f
        riddleCard.scaleY = 0.85f
        riddleCard.alpha = 0f
        riddleCard.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(350)
            .setInterpolator(OvershootInterpolator()).start()
        speak(q)
        // voice answers: listen now, or right after the question is read aloud
        voiceAnswered = false
        tvHeard.visibility = View.GONE
        readingQuestion = speakOn && lastSayOk
        if (voiceOn) {
            if (readingQuestion && !bargeIn) {
                // listen right after the reading (the recognizer stays clean and fast)
                voicePending = true
                val n = index
                main.postDelayed({ if (voicePending && questionNumber == n) startVoice() }, 12000) // if the end is never reported
            } else {
                // listen right away; while the question is being read, its own words are filtered out
                echoGuard = readingQuestion
                startVoice()
            }
        }
    }

    override fun onCountdown(n: Int) = runOnUiThread {
        if (menu.visibility == View.VISIBLE) return@runOnUiThread
        tvCountdown.animate().cancel()
        tvCountdown.visibility = View.VISIBLE
        tvCountdown.alpha = 1f
        tvCountdown.scaleX = 1.8f
        tvCountdown.scaleY = 1.8f
        if (n > 0) {
            tvCountdown.text = n.toString()
            tvCountdown.textSize = 96f
            sound.tick()
            tvCountdown.animate().scaleX(1f).scaleY(1f).setDuration(300).start()
        } else {
            tvCountdown.text = getString(R.string.go)
            tvCountdown.textSize = 64f
            sound.go()
            isRolling = true
            sound.setRolling(true)
            tvCountdown.animate().scaleX(1f).scaleY(1f).alpha(0f).setDuration(700)
                .withEndAction { tvCountdown.visibility = View.GONE }.start()
        }
    }

    override fun onResult(correct: Boolean, answer: String, score: Int, jumpedOver: Boolean, keepsRolling: Boolean) = runOnUiThread {
        if (mpInGame) link?.send(NetMsg.Result(questionNumber, correct, score))
        voicePending = false
        echoGuard = false
        readingQuestion = false
        voice.stop()
        tvHeard.visibility = View.GONE
        if (keepsRolling) {
            // blowout during the countdown: the ball is off
            tvCountdown.animate().cancel()
            tvCountdown.visibility = View.GONE
            isRolling = true
            sound.setRolling(true)
        } else {
            isRolling = false
            sound.setRolling(false)
        }
        if (menu.visibility == View.VISIBLE) return@runOnUiThread
        tvScore.text = getString(R.string.score, score)
        if (correct) {
            sound.blast()
            val praise = resources.getStringArray(R.array.praise)
            tvFeedback.text = praise.random()
            tvFeedback.setTextColor(0xFF7CFF6B.toInt())
            flash.animate().cancel()
            flash.alpha = 0.75f
            flash.animate().alpha(0f).setDuration(450).start()
            vibrate(longArrayOf(0, 70, 50, 140))
        } else {
            sound.wrong()
            tvFeedback.text = getString(if (jumpedOver) R.string.jumped_over else R.string.wrong, answer)
            tvFeedback.setTextColor(0xFFFF7070.toInt())
            vibrate(longArrayOf(0, 220))
        }
        // language modes: say the correct word once more, after the hit sound
        val q = currentQuestion
        val foreignAnswer = q?.foreignAnswer
        if (speakOn && q != null && foreignAnswer != null) main.postDelayed({ sayForeign(foreignAnswer, q) }, 900)
        // AI mode: explain the answer under the feedback line, and read it aloud
        val explanation = q?.explanation.orEmpty()
        if (explanation.isNotEmpty()) {
            val head = tvFeedback.text.toString()
            val sp = android.text.SpannableString(head + 10.toChar() + explanation)
            sp.setSpan(android.text.style.RelativeSizeSpan(0.62f), head.length + 1, sp.length, 0)
            sp.setSpan(android.text.style.ForegroundColorSpan(0xFFEEF0FF.toInt()), head.length + 1, sp.length, 0)
            sp.setSpan(android.text.style.StyleSpan(android.graphics.Typeface.NORMAL), head.length + 1, sp.length, 0)
            tvFeedback.text = sp
            if (speakOn) main.postDelayed({ say(explanation, uiLocale()) }, 900)
        }
        tvFeedback.visibility = View.VISIBLE
        tvFeedback.scaleX = 0.5f
        tvFeedback.scaleY = 0.5f
        tvFeedback.alpha = 1f
        tvFeedback.animate().scaleX(1f).scaleY(1f).setDuration(350)
            .setInterpolator(OvershootInterpolator(2.5f)).start()
        // a continuous lane never stops, so the feedback clears itself
        main.removeCallbacks(hideFeedback)
        if (continuousGame) main.postDelayed(hideFeedback, if (explanation.isNotEmpty()) 6500L else 2600L)
    }

    private fun updateProgress(total: Int = if (endlessGame) 0 else GameRenderer.QUESTIONS) {
        tvProgress.text = if (endlessGame) {
            "❤".repeat(lives.coerceAtLeast(0)) + "🤍".repeat((GameRenderer.ENDLESS_LIVES - lives).coerceAtLeast(0)) +
                "  " + getString(R.string.progress_endless, questionNumber)
        } else {
            getString(R.string.progress, questionNumber, total)
        }
    }

    override fun onLives(lives: Int) = runOnUiThread {
        this.lives = lives
        updateProgress()
        if (lives <= 0) {
            isRolling = false
            sound.setRolling(false)
        }
    }

    override fun onJump() = runOnUiThread {
        sound.jump()
        sound.setRolling(false)
    }

    override fun onLand() = runOnUiThread {
        sound.land()
        vibrate(longArrayOf(0, 30))
        if (isRolling) sound.setRolling(true)
    }

    override fun onGameOver(score: Int, total: Int, level: Level, mode: GameMode, endless: Boolean) = runOnUiThread {
        main.removeCallbacks(hideFeedback)
        isRolling = false
        sound.setRolling(false)
        if (menu.visibility == View.VISIBLE) return@runOnUiThread
        stopSpeaking()
        hud.visibility = View.GONE
        tvFeedback.visibility = View.GONE
        val key = bestKey(level, mode, endless)
        val oldBest = prefs.getInt(key, 0)
        val newBest = score > oldBest
        if (newBest) prefs.edit().putInt(key, score).apply()
        tvOverScore.text = if (endless) getString(R.string.endless_score, score) else getString(R.string.final_score, score, total)
        tvOverMsg.text = getString(
            when {
                score == total -> R.string.msg_perfect
                score * 10 >= total * 7 -> R.string.msg_great
                score * 10 >= total * 4 -> R.string.msg_good
                else -> R.string.msg_try
            }
        )
        tvOverBest.text = if (newBest) getString(R.string.new_best) else getString(R.string.best, oldBest)
        gameOver.alpha = 0f
        gameOver.visibility = View.VISIBLE
        gameButtons.visibility = View.GONE
        gameOver.animate().alpha(1f).setDuration(400).start()
        sound.fanfare()
        if (mpInGame) {
            val done = NetMsg.Done(score, total, System.currentTimeMillis() - gameStartMs)
            myDone = done
            link?.send(done)
            showMpResult()
        }
    }

    // ------------------------------------------------------------------ AI quiz from a document

    private fun secrets() = getSharedPreferences(SECRETS, Context.MODE_PRIVATE)

    private fun setupAiPanel() {
        aiPanel = findViewById(R.id.aiPanel)
        tvAiFile = findViewById(R.id.tvAiFile)
        tvAiStatus = findViewById(R.id.tvAiStatus)
        btnAiCreate = findViewById(R.id.btnAiCreate)
        findViewById<Button>(R.id.btnAiQuiz).setOnClickListener { openAiPanel() }
        findViewById<Button>(R.id.btnSaveQuiz).setOnClickListener { askSaveQuiz() }
        findViewById<Button>(R.id.btnMyQuizzes).setOnClickListener { showLibrary() }
        findViewById<Button>(R.id.btnAiBack).setOnClickListener { closeAiPanel() }
        findViewById<Button>(R.id.btnAiFile).setOnClickListener {
            val intent = android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(android.content.Intent.CATEGORY_OPENABLE)
                .setType("*/*")
                .putExtra(android.content.Intent.EXTRA_MIME_TYPES, arrayOf(
                    "application/pdf", "text/*", "image/*",
                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document"))
            try {
                @Suppress("DEPRECATION")
                startActivityForResult(intent, REQ_AI_FILE)
            } catch (e: Exception) {
                Toast.makeText(this, R.string.custom_read_failed, Toast.LENGTH_LONG).show()
            }
        }
        findViewById<View>(R.id.btnAiClearFile).setOnClickListener {
            aiFileUri = null
            aiFileName = ""
            showAiFile()
        }
        btnAiCreate.setOnClickListener { if (aiRun != null) cancelAi() else createAiQuiz() }
    }

    private fun openAiPanel() {
        findViewById<android.widget.EditText>(R.id.etAiKey).setText(secrets().getString(KEY_OPENAI, ""))
        findViewById<android.widget.EditText>(R.id.etAiModel).setText(secrets().getString(KEY_AI_MODEL, QuizMaker.DEFAULT_MODEL))
        findViewById<RadioGroup>(R.id.rgAiLang).check(if (english) R.id.rbAiEn else R.id.rbAiHe)
        showAiFile()
        aiPanel.visibility = View.VISIBLE
    }

    private fun closeAiPanel() {
        cancelAi()
        aiPanel.visibility = View.GONE
    }

    private fun showAiFile() {
        tvAiFile.text = if (aiFileUri == null) getString(R.string.ai_no_file) else "\u2068$aiFileName\u2069"
        findViewById<View>(R.id.btnAiClearFile).visibility = if (aiFileUri == null) View.GONE else View.VISIBLE
    }

    private fun setAiBusy(status: Int?) {
        val busy = status != null
        findViewById<View>(R.id.aiProgress).visibility = if (busy) View.VISIBLE else View.GONE
        if (status != null) tvAiStatus.setText(status)
        btnAiCreate.setText(if (busy) R.string.ai_cancel else R.string.ai_create)
    }

    private fun cancelAi() {
        if (aiRun == null) return
        aiRun = null
        val c = aiConn
        aiConn = null
        Thread { try { c?.disconnect() } catch (e: Exception) {} }.start()
        setAiBusy(null)
    }

    private fun createAiQuiz() {
        val key = findViewById<android.widget.EditText>(R.id.etAiKey).text.toString().trim()
        val model = findViewById<android.widget.EditText>(R.id.etAiModel).text.toString().trim().ifEmpty { QuizMaker.DEFAULT_MODEL }
        val url = findViewById<android.widget.EditText>(R.id.etAiUrl).text.toString().trim()
        val pasted = findViewById<android.widget.EditText>(R.id.etAiText).text.toString().trim()
        val request = findViewById<android.widget.EditText>(R.id.etAiTopic).text.toString().trim()
        // a typed number wins over the quick choices
        val typed = findViewById<android.widget.EditText>(R.id.etAiCount).text.toString().trim().toIntOrNull()
        if (typed != null && typed !in 1..QuizMaker.MAX_QUESTIONS) {
            Toast.makeText(this, getString(R.string.ai_count_range, QuizMaker.MAX_QUESTIONS), Toast.LENGTH_LONG).show()
            return
        }
        val count = typed ?: when (findViewById<RadioGroup>(R.id.rgAiCount).checkedRadioButtonId) {
            R.id.rbAi10 -> 10
            R.id.rbAi20 -> 20
            R.id.rbAi30 -> 30
            else -> 15
        }
        val quizEnglish = findViewById<RadioGroup>(R.id.rgAiLang).checkedRadioButtonId == R.id.rbAiEn
        if (key.isEmpty()) { Toast.makeText(this, R.string.ai_need_key, Toast.LENGTH_LONG).show(); return }
        val fileUri = aiFileUri
        if (fileUri == null && url.isEmpty() && pasted.isEmpty() && request.isEmpty()) {
            Toast.makeText(this, R.string.ai_need_source, Toast.LENGTH_LONG).show()
            return
        }
        secrets().edit().putString(KEY_OPENAI, key).putString(KEY_AI_MODEL, model).apply()
        // hide the keyboard so the progress line is visible
        (getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
            .hideSoftInputFromWindow(aiPanel.windowToken, 0)

        val run = Any()
        aiRun = run
        setAiBusy(R.string.ai_reading)
        val fileName = aiFileName
        Thread {
            val result: Result<String> = runCatching {
                // a picked file wins, then a link, then pasted text
                val source = when {
                    fileUri != null -> {
                        val bytes = contentResolver.openInputStream(fileUri)?.use { it.readBytes() }
                            ?: throw QuizMakerException("read")
                        QuizMaker.sourceFromFile(bytes, contentResolver.getType(fileUri), fileName)
                    }
                    url.isNotEmpty() -> QuizMaker.sourceFromUrl(url)
                    pasted.isNotEmpty() -> QuizSource.Text(pasted, getString(R.string.ai_pasted_name))
                    else -> null // just a topic: ChatGPT writes from what it knows
                }
                // a short paste is fine when the request says what to ask about
                if (source is QuizSource.Text && source.text.length < 150 && request.isEmpty()) throw QuizMakerException("too_short")
                if (aiRun !== run) throw QuizMakerException("cancelled")
                QuizMaker.generate(key, model, source, count, quizEnglish, request,
                    onPart = { part, parts ->
                        runOnUiThread {
                            if (aiRun === run) tvAiStatus.text =
                                if (parts > 1) getString(R.string.ai_working_part, part, parts) else getString(R.string.ai_working)
                        }
                    },
                    isCancelled = { aiRun !== run },
                    onConnection = { aiConn = it })
            }
            runOnUiThread {
                if (aiRun !== run || isFinishing || isDestroyed) return@runOnUiThread
                aiRun = null
                aiConn = null
                setAiBusy(null)
                result.fold(::showAiQuiz) { e -> report(aiErrorText(e)) }
            }
        }.start()
    }

    private fun aiErrorText(e: Throwable): String {
        val m = (e as? QuizMakerException)?.message ?: return getString(R.string.ai_err_other, e.message ?: e.javaClass.simpleName)
        val detail = m.substringAfter(':', "")
        return when {
            m.startsWith("api_401") -> getString(R.string.ai_err_key)
            m.startsWith("api_429") -> getString(R.string.ai_err_quota)
            m.startsWith("api_") -> getString(R.string.ai_err_api, m.substringBefore(':').removePrefix("api_"), detail)
            m.startsWith("network") -> getString(R.string.ai_err_network)
            m.startsWith("http_") -> getString(R.string.ai_err_link, m.removePrefix("http_"))
            m == "too_big" -> getString(R.string.ai_err_big)
            m == "too_short" -> getString(R.string.ai_too_short)
            m == "bad_docx" -> getString(R.string.ai_err_docx)
            m.startsWith("refused") -> getString(R.string.ai_err_refused)
            m == "empty" || m.startsWith("incomplete") -> getString(R.string.ai_err_empty)
            else -> getString(R.string.ai_err_other, m)
        }
    }

    /** Shows a few of the new questions; the player plays them or also saves them as an editable file. */
    private fun showAiQuiz(file: String) {
        val parsed = CustomQuestions.parse(file)
        if (parsed.items.isEmpty()) { report(getString(R.string.ai_err_empty)); return }
        val title = QuizMaker.titleOf(file)
        val sample = parsed.items.shuffled().take(3).joinToString("\n\n") { "• ${it.question}\n   ✓ ${it.correct}" }
        android.app.AlertDialog.Builder(this)
            .setTitle(getString(R.string.ai_ready_title, title))
            .setMessage(getString(R.string.ai_ready_body, parsed.items.size, sample))
            .setPositiveButton(R.string.ai_play) { _, _ -> useAiQuiz(file, title) }
            .setNeutralButton(R.string.lib_save) { _, _ ->
                useAiQuiz(file, title)
                askSaveQuiz(file, title)
            }
            .setNegativeButton(R.string.ai_cancel, null)
            .show()
    }

    /** Makes the new quiz the player's questions and goes to the menu, ready to play. */
    private fun useAiQuiz(file: String, title: String) {
        java.io.File(filesDir, CUSTOM_FILE).writeText(file, Charsets.UTF_8)
        prefs.edit().remove(KEY_CUSTOM_URI).putString(KEY_CUSTOM_NAME, "🤖 $title").apply()
        customItems = CustomQuestions.parse(file).items
        updateCustomStatus()
        aiPanel.visibility = View.GONE
        options.visibility = View.GONE
        setMode(GameMode.CUSTOM)
        Toast.makeText(this, R.string.ai_loaded, Toast.LENGTH_LONG).show()
    }

    // ------------------------------------------------------------------ saved quizzes (by name)

    private fun quizDir() = java.io.File(filesDir, "quizzes").also { it.mkdirs() }

    /** Any name works: it is URL-encoded into the file name. */
    private fun quizFile(name: String) = java.io.File(quizDir(), java.net.URLEncoder.encode(name, "UTF-8") + ".txt")

    /** Saved quizzes, newest first. */
    private fun savedQuizzes(): List<Pair<String, java.io.File>> =
        (quizDir().listFiles() ?: emptyArray()).filter { it.name.endsWith(".txt") }
            .sortedByDescending { it.lastModified() }
            .map { java.net.URLDecoder.decode(it.name.removeSuffix(".txt"), "UTF-8") to it }

    private fun currentQuizName(): String =
        prefs.getString(KEY_CUSTOM_NAME, null).orEmpty().removePrefix("🤖 ").substringBeforeLast(".txt").trim()

    /** Saves [text] (by default the questions now in use) under a name the player types. */
    private fun askSaveQuiz(text: String? = readCustomCache(), suggested: String = currentQuizName()) {
        if (text == null || CustomQuestions.parse(text).items.isEmpty()) {
            Toast.makeText(this, R.string.custom_none, Toast.LENGTH_LONG).show()
            return
        }
        val pad = (20 * resources.displayMetrics.density).toInt()
        val et = android.widget.EditText(this).apply {
            setText(suggested)
            setSingleLine()
            filters = arrayOf(android.text.InputFilter.LengthFilter(60))
            hint = getString(R.string.lib_name_hint)
            textDirection = View.TEXT_DIRECTION_FIRST_STRONG
            selectAll()
        }
        val box = android.widget.FrameLayout(this).apply { setPadding(pad, pad / 2, pad, 0); addView(et) }
        val dialog = android.app.AlertDialog.Builder(this)
            .setTitle(R.string.lib_save_title)
            .setView(box)
            .setPositiveButton(R.string.lib_save_button, null)
            .setNegativeButton(R.string.ai_cancel, null)
            .create()
        dialog.setOnShowListener {
            et.requestFocus()
            dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = et.text.toString().trim()
                if (name.isEmpty()) {
                    et.error = getString(R.string.lib_name_hint)
                    return@setOnClickListener
                }
                val file = quizFile(name)
                fun write() {
                    file.writeText(text, Charsets.UTF_8)
                    prefs.edit().putString(KEY_CUSTOM_NAME, name).apply()
                    updateCustomStatus()
                    Toast.makeText(this, getString(R.string.lib_saved, name), Toast.LENGTH_SHORT).show()
                    dialog.dismiss()
                }
                if (file.exists()) {
                    android.app.AlertDialog.Builder(this)
                        .setMessage(getString(R.string.lib_overwrite, name))
                        .setPositiveButton(R.string.lib_replace) { _, _ -> write() }
                        .setNegativeButton(R.string.ai_cancel, null)
                        .show()
                } else {
                    write()
                }
            }
        }
        dialog.show()
    }

    private fun showLibrary() {
        val list = savedQuizzes()
        if (list.isEmpty()) {
            android.app.AlertDialog.Builder(this)
                .setTitle(R.string.lib_title)
                .setMessage(R.string.lib_empty)
                .setPositiveButton(android.R.string.ok, null)
                .show()
            return
        }
        val labels = list.map { (name, file) ->
            val n = CustomQuestions.parse(file.readText(Charsets.UTF_8)).items.size
            "\u2068$name\u2069  ·  " + getString(R.string.lib_count, n)
        }.toTypedArray()
        android.app.AlertDialog.Builder(this)
            .setTitle(R.string.lib_title)
            .setItems(labels) { _, i -> quizActions(list[i].first, list[i].second) }
            .setNegativeButton(R.string.ai_cancel, null)
            .show()
    }

    /** Load, export or delete one saved quiz. */
    private fun quizActions(name: String, file: java.io.File) {
        android.app.AlertDialog.Builder(this)
            .setTitle(name)
            .setPositiveButton(R.string.lib_load) { _, _ -> loadSavedQuiz(name, file) }
            .setNeutralButton(R.string.lib_export) { _, _ ->
                pendingExport = file.readText(Charsets.UTF_8)
                val safe = name.replace(Regex("[\\\\/:*?\"<>|]"), " ").trim().ifEmpty { "quiz" }
                val intent = android.content.Intent(android.content.Intent.ACTION_CREATE_DOCUMENT)
                    .addCategory(android.content.Intent.CATEGORY_OPENABLE)
                    .setType("text/plain")
                    .putExtra(android.content.Intent.EXTRA_TITLE, "$safe.txt")
                try {
                    @Suppress("DEPRECATION")
                    startActivityForResult(intent, REQ_EXPORT)
                } catch (e: Exception) {
                    pendingExport = null
                }
            }
            .setNegativeButton(R.string.lib_delete) { _, _ ->
                android.app.AlertDialog.Builder(this)
                    .setMessage(getString(R.string.lib_delete_confirm, name))
                    .setPositiveButton(R.string.lib_delete) { _, _ ->
                        file.delete()
                        Toast.makeText(this, getString(R.string.lib_deleted, name), Toast.LENGTH_SHORT).show()
                        showLibrary()
                    }
                    .setNegativeButton(R.string.ai_cancel, null)
                    .show()
            }
            .show()
    }

    private fun loadSavedQuiz(name: String, file: java.io.File) {
        val text = file.readText(Charsets.UTF_8)
        java.io.File(filesDir, CUSTOM_FILE).writeText(text, Charsets.UTF_8)
        prefs.edit().remove(KEY_CUSTOM_URI).putString(KEY_CUSTOM_NAME, name).apply()
        customItems = CustomQuestions.parse(text).items
        updateCustomStatus()
        options.visibility = View.GONE
        setMode(GameMode.CUSTOM)
        Toast.makeText(this, getString(R.string.lib_loaded, name), Toast.LENGTH_LONG).show()
    }

    // ------------------------------------------------------------------ pause / exit

    /** A game is on screen and not finished. */
    private fun gameRunning() =
        hud.visibility == View.VISIBLE && menu.visibility != View.VISIBLE && gameOver.visibility != View.VISIBLE

    /** Freezes the game; [confirmExit] shows the panel as "leave the game?". */
    private fun pauseGame(confirmExit: Boolean) {
        if (!gameRunning()) return
        gamePaused = true
        renderer.paused = true
        sound.pause()
        stopSpeaking()
        voicePending = false
        echoGuard = false
        readingQuestion = false
        voice.stop()
        ivPauseTitle.setImageResource(if (confirmExit) R.drawable.ic_exit else R.drawable.ic_pause)
        tvPauseTitle.setText(if (confirmExit) R.string.exit_confirm else R.string.paused)
        gameButtons.visibility = View.GONE
        pausePanel.alpha = 0f
        pausePanel.visibility = View.VISIBLE
        pausePanel.animate().alpha(1f).setDuration(180).start()
        ivPauseTitle.scaleX = 0.6f
        ivPauseTitle.scaleY = 0.6f
        ivPauseTitle.animate().scaleX(1f).scaleY(1f).setDuration(300).setInterpolator(OvershootInterpolator()).start()
    }

    private fun resumeGame(toMenu: Boolean = false) {
        gamePaused = false
        pausePanel.visibility = View.GONE
        if (toMenu) {
            showMenu()
            return
        }
        gameButtons.visibility = View.VISIBLE
        renderer.paused = false
        sound.resume()
        // keep listening for the answer if it hasn't been given yet
        if (voiceOn && !voiceAnswered && currentQuestion != null) startVoice()
    }

    // ------------------------------------------------------------------ the player's own questions

    private fun readCustomCache(): String? =
        java.io.File(filesDir, CUSTOM_FILE).takeIf { it.exists() }?.readText(Charsets.UTF_8)

    private fun updateCustomStatus() {
        val name = prefs.getString(KEY_CUSTOM_NAME, null)
        tvCustomStatus.text = if (customItems.isEmpty() || name == null) getString(R.string.custom_status_none)
        // isolate the (usually Latin) file name so it doesn't scramble the Hebrew sentence
        else getString(R.string.custom_status, customItems.size, "⁨$name⁩")
    }

    private fun pickQuestionsFile() {
        val intent = android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(android.content.Intent.CATEGORY_OPENABLE)
            .setType("*/*")
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(intent, REQ_OPEN_QUESTIONS)
        } catch (e: Exception) {
            Toast.makeText(this, R.string.custom_read_failed, Toast.LENGTH_LONG).show()
        }
    }

    private fun saveSampleFile() {
        val intent = android.content.Intent(android.content.Intent.ACTION_CREATE_DOCUMENT)
            .addCategory(android.content.Intent.CATEGORY_OPENABLE)
            .setType("text/plain")
            .putExtra(android.content.Intent.EXTRA_TITLE, "MathBowling-questions.txt")
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(intent, REQ_SAVE_SAMPLE)
        } catch (e: Exception) {
            Toast.makeText(this, R.string.custom_read_failed, Toast.LENGTH_LONG).show()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data ?: return
        if (resultCode != RESULT_OK) return
        when (requestCode) {
            REQ_OPEN_QUESTIONS -> loadQuestionsFrom(uri, showReport = true)
            REQ_AI_FILE -> {
                try {
                    contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                } catch (e: Exception) {}
                aiFileUri = uri
                aiFileName = displayName(uri)
                showAiFile()
            }
            REQ_EXPORT -> {
                val text = pendingExport ?: return
                pendingExport = null
                try {
                    contentResolver.openOutputStream(uri, "wt")?.use { it.write(text.toByteArray(Charsets.UTF_8)) }
                    loadQuestionsFrom(uri, showReport = false)
                    Toast.makeText(this, R.string.ai_saved, Toast.LENGTH_LONG).show()
                } catch (e: Exception) {
                    Toast.makeText(this, R.string.custom_read_failed, Toast.LENGTH_LONG).show()
                }
            }
            REQ_SAVE_SAMPLE -> {
                try {
                    contentResolver.openOutputStream(uri, "wt")?.use {
                        it.write(CustomQuestions.sample(english).toByteArray(Charsets.UTF_8))
                    }
                    loadQuestionsFrom(uri, showReport = false)
                    Toast.makeText(this, R.string.custom_sample_saved, Toast.LENGTH_LONG).show()
                } catch (e: Exception) {
                    Toast.makeText(this, R.string.custom_read_failed, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun displayName(uri: android.net.Uri): String = try {
        contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }
    } catch (e: Exception) {
        null
    } ?: uri.lastPathSegment ?: "?"

    /** Reads the file in the background (it may be on Google Drive), then keeps a copy and remembers where it is. */
    private fun loadQuestionsFrom(uri: android.net.Uri, showReport: Boolean) {
        try {
            contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (e: Exception) {
            // not every file source allows keeping access; the cached copy still works
        }
        Thread {
            val text = try {
                contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
            } catch (e: Exception) {
                null
            }
            val name = displayName(uri)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (text == null) {
                    if (showReport) Toast.makeText(this, R.string.custom_read_failed, Toast.LENGTH_LONG).show()
                    return@runOnUiThread
                }
                val result = CustomQuestions.parse(text)
                if (result.items.isEmpty()) {
                    if (showReport) report(getString(R.string.custom_empty))
                    return@runOnUiThread
                }
                java.io.File(filesDir, CUSTOM_FILE).writeText(text, Charsets.UTF_8)
                prefs.edit().putString(KEY_CUSTOM_URI, uri.toString()).putString(KEY_CUSTOM_NAME, name).apply()
                customItems = result.items
                updateCustomStatus()
                updateBestLine()
                if (showReport) {
                    var msg = getString(R.string.custom_loaded, result.items.size)
                    if (result.badLines.isNotEmpty()) {
                        val lines = result.badLines.take(15).joinToString(", ") + if (result.badLines.size > 15) "…" else ""
                        msg += "\n\n" + getString(R.string.custom_bad_lines, lines)
                    }
                    report(msg)
                }
            }
        }.start()
    }

    /** Re-reads the remembered file, so edits made since the last load are used. */
    private fun refreshCustomFromSource() {
        val uri = prefs.getString(KEY_CUSTOM_URI, null)?.let { android.net.Uri.parse(it) } ?: return
        loadQuestionsFrom(uri, showReport = false)
    }

    private fun report(msg: String) {
        android.app.AlertDialog.Builder(this)
            .setTitle(R.string.custom_title)
            .setMessage(msg)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    // ------------------------------------------------------------------ sensors

    override fun onSensorChanged(event: SensorEvent) {
        val v = event.values
        @Suppress("DEPRECATION")
        val rotation = if (Build.VERSION.SDK_INT >= 30) display?.rotation ?: Surface.ROTATION_0
        else windowManager.defaultDisplay.rotation
        // remap to screen axes: x = right, y = up along the screen
        val (x, y) = when (rotation) {
            Surface.ROTATION_90 -> Pair(-v[1], v[0])
            Surface.ROTATION_180 -> Pair(-v[0], -v[1])
            Surface.ROTATION_270 -> Pair(v[1], -v[0])
            else -> Pair(v[0], v[1])
        }
        val z = v[2]

        // shake = a sharp spike away from plain gravity: jump, and keep the spike out of the steering
        val now = android.os.SystemClock.uptimeMillis()
        val jolt = abs(kotlin.math.sqrt(x * x + y * y + z * z) - SensorManager.GRAVITY_EARTH)
        if (jolt > 3.5f) shakeQuietUntilMs = now + 250
        if (shakeJumpOn && jolt > SHAKE_THRESHOLD && now - lastShakeMs > SHAKE_COOLDOWN_MS) {
            lastShakeMs = now
            renderer.jumpRequest = true
        }
        if (now < shakeQuietUntilMs) return

        filteredX += (x - filteredX) * 0.3f
        var t = -filteredX * tiltSensitivity / 4.5f
        // a fixed dead zone in phone terms, so the highest sensitivities don't turn hand tremble into steering
        if (abs(t) < 0.05f || abs(filteredX) < 0.15f) t = 0f
        renderer.tilt = t.coerceIn(-1f, 1f)

        // pitch: 90° = phone upright, 0° = flat on its back; tilting forward lowers it
        val p = Math.toDegrees(atan2(y.toDouble(), z.toDouble())).toFloat()
        filteredPitch = if (filteredPitch.isNaN()) p else filteredPitch + (p - filteredPitch) * 0.2f
        renderer.pitch = filteredPitch
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    // ------------------------------------------------------------------ lifecycle

    override fun onResume() {
        super.onResume()
        // pick up edits made to the questions file while the app was in the background
        if (mode == GameMode.CUSTOM && menu.visibility == View.VISIBLE) refreshCustomFromSource()
        glView.onResume()
        accel?.let { sensorManager?.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        if (!gamePaused) sound.resume()
        hideSystemBars()
    }

    override fun onPause() {
        super.onPause()
        if (gameRunning() && !gamePaused) pauseGame(confirmExit = false)
        glView.onPause()
        sensorManager?.unregisterListener(this)
        stopSpeaking()
        sound.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        main.removeCallbacksAndMessages(null)
        link?.close()
        discovery.stop()
        tts?.shutdown()
        tts = null
        voice.release()
        sound.release()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    private fun hideSystemBars() {
        if (Build.VERSION.SDK_INT >= 30) {
            window.insetsController?.let {
                it.hide(WindowInsets.Type.systemBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    or View.SYSTEM_UI_FLAG_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION)
        }
    }

    private fun vibrate(pattern: LongArray) {
        val vib: Vibrator? = if (Build.VERSION.SDK_INT >= 31) {
            getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Vibrator::class.java)
        }
        if (vib?.hasVibrator() == true) vib.vibrate(VibrationEffect.createWaveform(pattern, -1))
    }
}
