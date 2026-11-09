package com.example.mathbowling

import kotlin.random.Random

/** One vocabulary entry. translitHe = Hebrew-letter transliteration, translitEn = Latin transliteration. */
data class VocabWord(
    val word: String,
    val translitHe: String,
    val translitEn: String,
    val he: String,
    val en: String,
    val category: String,
    val level: Level
)

/** Languages that have a learning mode. ttsTag is the speech-engine language. */
enum class ForeignLanguage(val ttsTag: String, val inHe: String, val inEn: String) {
    ARABIC("ar", "בערבית", "in Arabic"),
    RUSSIAN("ru", "ברוסית", "in Russian");

    val words: List<VocabWord> by lazy {
        parseVocabulary(if (this == ARABIC) ArabicVocabulary.data else RussianVocabulary.data)
    }
}

/** Each block line: word | Hebrew transliteration | Latin transliteration | Hebrew meaning | English meaning */
fun parseVocabulary(data: List<Triple<Level, String, String>>): List<VocabWord> =
    data.flatMap { (level, category, block) ->
        block.trim().lines().map { it.trim() }.filter { it.isNotEmpty() }.map { line ->
            val f = line.split("|").map { it.trim() }
            require(f.size == 5) { "Bad vocabulary line: $line" }
            VocabWord(f[0], f[1], f[2], f[3], f[4], category, level)
        }
    }

object ArabicVocabulary {
    val data: List<Triple<Level, String, String>> = listOf(
        Triple(Level.EASY, "animals", """
            قِطّ|קט|qiṭṭ|חתול|cat
            كَلْب|כלב|kalb|כלב|dog
            حِصَان|חצאן|ḥiṣān|סוס|horse
            أَسَد|אסד|asad|אריה|lion
            فِيل|פיל|fīl|פיל|elephant
            سَمَكَة|סמכה|samaka|דג|fish
            عُصْفُور|עצפור|ʿuṣfūr|ציפור|bird
            بَقَرَة|בקרה|baqara|פרה|cow
            حِمَار|חמאר|ḥimār|חמור|donkey
            جَمَل|ג'מל|jamal|גמל|camel
            أَرْنَب|ארנב|arnab|ארנב|rabbit
            دَجَاجَة|דג'אג'ה|dajāja|תרנגולת|chicken
        """),
        Triple(Level.EASY, "colors", """
            أَحْمَر|אחמר|aḥmar|אדום|red
            أَزْرَق|אזרק|azraq|כחול|blue
            أَخْضَر|אח'צ'ר|akhḍar|ירוק|green
            أَصْفَر|אצפר|aṣfar|צהוב|yellow
            أَبْيَض|אביצ'|abyaḍ|לבן|white
            أَسْوَد|אסוד|aswad|שחור|black
        """),
        Triple(Level.EASY, "numbers", """
            وَاحِد|ואחד|wāḥid|אחד|one
            اِثْنَان|את'נאן|ithnān|שניים|two
            ثَلَاثَة|ת'לאת'ה|thalātha|שלושה|three
            أَرْبَعَة|ארבעה|arbaʿa|ארבעה|four
            خَمْسَة|ח'מסה|khamsa|חמישה|five
            سِتَّة|סתה|sitta|שישה|six
            سَبْعَة|סבעה|sabʿa|שבעה|seven
            ثَمَانِيَة|ת'מאניה|thamāniya|שמונה|eight
            تِسْعَة|תסעה|tisʿa|תשעה|nine
            عَشَرَة|עשרה|ʿashara|עשרה|ten
        """),
        Triple(Level.EASY, "family", """
            أُمّ|אם|umm|אמא|mother
            أَب|אב|ab|אבא|father
            أَخ|אח'|akh|אח|brother
            أُخْت|אח'ת|ukht|אחות|sister
            جَدّ|ג'ד|jadd|סבא|grandfather
            جَدَّة|ג'דה|jadda|סבתא|grandmother
            اِبْن|אבן|ibn|בן|son
            بِنْت|בנת|bint|בת|daughter
        """),
        Triple(Level.EASY, "body", """
            رَأْس|ראס|raʾs|ראש|head
            عَيْن|עין|ʿayn|עין|eye
            يَد|יד|yad|יד|hand
            رِجْل|רג'ל|rijl|רגל|leg
            أَنْف|אנף|anf|אף|nose
            فَم|פם|fam|פה|mouth
            أُذُن|אד'ן|udhun|אוזן|ear
            قَلْب|קלב|qalb|לב|heart
        """),
        Triple(Level.MEDIUM, "food", """
            خُبْز|ח'בז|khubz|לחם|bread
            مَاء|מאא|māʾ|מים|water
            حَلِيب|חליב|ḥalīb|חלב|milk
            تُفَّاحَة|תפאחה|tuffāḥa|תפוח|apple
            بُرْتُقَالَة|ברתקאלה|burtuqāla|תפוז|orange
            مَوْزَة|מוזה|mawza|בננה|banana
            جُبْنَة|ג'בנה|jubna|גבינה|cheese
            بَيْضَة|ביצ'ה|bayḍa|ביצה|egg
            لَحْم|לחם|laḥm|בשר|meat
            أَرُزّ|ארז|aruzz|אורז|rice
            سُكَّر|סכר|sukkar|סוכר|sugar
            مِلْح|מלח|milḥ|מלח|salt
            قَهْوَة|קהוה|qahwa|קפה|coffee
            شَاي|שאי|shāy|תה|tea
        """),
        Triple(Level.MEDIUM, "home", """
            بَيْت|בית|bayt|בית|house
            بَاب|באב|bāb|דלת|door
            نَافِذَة|נאפד'ה|nāfidha|חלון|window
            كُرْسِيّ|כרסי|kursī|כיסא|chair
            طَاوِلَة|טאולה|ṭāwila|שולחן|table
            سَرِير|סריר|sarīr|מיטה|bed
            مِفْتَاح|מפתאח|miftāḥ|מפתח|key
            كِتَاب|כתאב|kitāb|ספר|book
            قَلَم|קלם|qalam|עט|pen
            مَدْرَسَة|מדרסה|madrasa|בית ספר|school
            سَيَّارَة|סיארה|sayyāra|מכונית|car
        """),
        Triple(Level.MEDIUM, "nature", """
            شَمْس|שמס|shams|שמש|sun
            قَمَر|קמר|qamar|ירח|moon
            نَجْمَة|נג'מה|najma|כוכב|star
            بَحْر|בחר|baḥr|ים|sea
            شَجَرَة|שג'רה|shajara|עץ|tree
            وَرْدَة|ורדה|warda|פרח|flower
            مَطَر|מטר|maṭar|גשם|rain
            جَبَل|ג'בל|jabal|הר|mountain
            سَمَاء|סמאא|samāʾ|שמיים|sky
        """),
        Triple(Level.MEDIUM, "adjectives", """
            كَبِير|כביר|kabīr|גדול|big
            صَغِير|צע'יר|ṣaghīr|קטן|small
            جَمِيل|ג'מיל|jamīl|יפה|beautiful
            جَدِيد|ג'דיד|jadīd|חדש|new
            قَدِيم|קדים|qadīm|עתיק|old
            حَارّ|חאר|ḥārr|חם|hot
            بَارِد|בארד|bārid|קר|cold
            طَوِيل|טויל|ṭawīl|ארוך|long
            سَعِيد|סעיד|saʿīd|שמח|happy
        """),
        Triple(Level.MEDIUM, "time", """
            يَوْم|יום|yawm|יום|day
            لَيْل|ליל|layl|לילה|night
            اَلْيَوْم|אליום|al-yawm|היום|today
            غَدًا|ע'דן|ghadan|מחר|tomorrow
            أَمْس|אמס|ams|אתמול|yesterday
            سَاعَة|סאעה|sāʿa|שעה|hour
        """),
        Triple(Level.HARD, "phrases", """
            مَرْحَبًا|מרחבן|marḥaban|שלום|hello
            شُكْرًا|שכרן|shukran|תודה|thank you
            مِنْ فَضْلِكَ|מן פצ'לכ|min faḍlik|בבקשה|please
            صَبَاحُ الْخَيْرِ|צבאח אלח'יר|ṣabāḥ al-khayr|בוקר טוב|good morning
            مَسَاءُ الْخَيْرِ|מסאא אלח'יר|masāʾ al-khayr|ערב טוב|good evening
            تُصْبِحُ عَلَى خَيْرٍ|תצבח עלא ח'יר|tuṣbiḥ ʿalā khayr|לילה טוב|good night
            كَيْفَ حَالُكَ؟|כיף חאלכ|kayfa ḥāluk|מה שלומך?|how are you?
            نَعَم|נעם|naʿam|כן|yes
            لَا|לא|lā|לא|no
            مَعَ السَّلَامَة|מע א-סלאמה|maʿa as-salāma|להתראות|goodbye
            أَهْلًا وَسَهْلًا|אהלן וסהלן|ahlan wa-sahlan|ברוכים הבאים|welcome
            عَفْوًا|עפון|ʿafwan|על לא דבר|you're welcome
            آسِف|אאסף|āsif|סליחה|sorry
            مَا اسْمُكَ؟|מא אסמכ|mā ismuk|מה שמך?|what is your name?
        """),
        Triple(Level.HARD, "pronouns", """
            أَنَا|אנא|ana|אני|I
            أَنْتَ|אנת|anta|אתה|you
            هُوَ|הו|huwa|הוא|he
            هِيَ|הי|hiya|היא|she
            نَحْنُ|נחן|naḥnu|אנחנו|we
        """),
        Triple(Level.HARD, "verbs", """
            كَتَبَ|כתב|kataba|כתב|wrote
            أَكَلَ|אכל|akala|אכל|ate
            شَرِبَ|שרב|shariba|שתה|drank
            ذَهَبَ|ד'הב|dhahaba|הלך|went
            نَامَ|נאם|nāma|ישן|slept
            قَرَأَ|קרא|qaraʾa|קרא|read
            فَتَحَ|פתח|fataḥa|פתח|opened
            جَلَسَ|ג'לס|jalasa|ישב|sat
            لَعِبَ|לעב|laʿiba|שיחק|played
        """)
    )
}

/** Builds vocabulary questions: foreign word -> meaning, and (harder) meaning -> foreign word. */
class VocabQuestionGenerator(
    private val language: ForeignLanguage,
    private val english: Boolean = false,
    private val rnd: Random = Random.Default
) {
    private val words = language.words
    private val recent = ArrayDeque<Int>()

    private fun meaning(w: VocabWord) = if (english) w.en else w.he

    fun next(level: Level): Question {
        val reverseChance = when (level) { Level.EASY -> 0.0; Level.MEDIUM -> 0.3; Level.HARD -> 0.5 }
        val reverse = rnd.nextDouble() < reverseChance
        // Forward questions use the level's own words; reverse questions review words from easier levels too.
        val pool = words.indices.filter {
            val l = words[it].level
            if (reverse) l.ordinal <= level.ordinal && (level == Level.MEDIUM || l != Level.EASY) else l == level
        }
        var idx = pool.random(rnd)
        var tries = 0
        while (idx in recent && tries++ < 20) idx = pool.random(rnd)
        recent.addLast(idx)
        if (recent.size > 15) recent.removeFirst()

        val w = words[idx]
        val options = pickOptions(idx, reverse)
        val labels = options.map { if (reverse) words[it].word else meaning(words[it]) }
        val tag = language.ttsTag
        return if (!reverse) {
            Question(
                prompt = if (english) "What does it mean?" else "מה הפירוש בעברית?",
                expression = w.word,
                answer = idx,
                options = options,
                labels = labels,
                hint = if (english) w.translitEn else w.translitHe,
                textExpression = true,
                foreignSpeech = w.word,
                foreignAnswer = w.word,
                foreignLang = tag
            )
        } else {
            Question(
                prompt = if (english) "How do you say it ${language.inEn}?" else "איך אומרים ${language.inHe}?",
                expression = meaning(w),
                answer = idx,
                options = options,
                labels = labels,
                textExpression = true,
                foreignAnswer = w.word,
                foreignLang = tag
            )
        }
    }

    /** Correct word plus two distractors, preferably from the same category, with distinct labels. */
    private fun pickOptions(idx: Int, reverse: Boolean): List<Int> {
        val w = words[idx]
        fun label(i: Int) = if (reverse) words[i].word else meaning(words[i])
        val used = hashSetOf(label(idx))
        val chosen = mutableListOf(idx)
        val sameCat = words.indices.filter { it != idx && words[it].category == w.category }.shuffled(rnd)
        val sameLevel = words.indices.filter { it != idx && words[it].level == w.level && words[it].category != w.category }.shuffled(rnd)
        val rest = words.indices.filter { it != idx }.shuffled(rnd)
        for (c in sameCat + sameLevel + rest) {
            if (chosen.size >= 3) break
            if (used.add(label(c))) chosen.add(c)
        }
        return chosen.shuffled(rnd)
    }
}
