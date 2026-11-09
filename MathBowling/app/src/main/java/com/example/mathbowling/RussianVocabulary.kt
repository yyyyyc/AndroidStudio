package com.example.mathbowling

/**
 * Russian words with stress marks (U+0301) on the stressed vowel, as in learner dictionaries.
 * The Hebrew transliteration follows pronunciation (e.g. unstressed о sounds like א), the Latin one follows spelling.
 * Format per line: Russian | Hebrew transliteration | Latin transliteration | Hebrew meaning | English meaning
 */
object RussianVocabulary {
    val data: List<Triple<Level, String, String>> = listOf(
        Triple(Level.EASY, "animals", """
            кот|קוט|kot|חתול|cat
            соба́ка|סאבאקה|sobaka|כלב|dog
            ло́шадь|לושאט|loshad'|סוס|horse
            слон|סלון|slon|פיל|elephant
            ры́ба|ריבה|ryba|דג|fish
            пти́ца|פטיצה|ptitsa|ציפור|bird
            коро́ва|קארובה|korova|פרה|cow
            мышь|מיש|mysh'|עכבר|mouse
            волк|וולק|volk|זאב|wolf
            лиса́|ליסה|lisa|שועל|fox
            за́яц|זאיץ|zayats|ארנב|hare
            у́тка|אוטקה|utka|ברווז|duck
        """),
        Triple(Level.EASY, "colors", """
            кра́сный|קראסני|krasny|אדום|red
            си́ний|סיני|siniy|כחול|blue
            зелёный|זיליוני|zelyony|ירוק|green
            жёлтый|ז'ולטי|zhyolty|צהוב|yellow
            бе́лый|בילי|bely|לבן|white
            чёрный|צ'ורני|chyorny|שחור|black
        """),
        Triple(Level.EASY, "numbers", """
            оди́н|אדין|odin|אחד|one
            два|דווה|dva|שניים|two
            три|טרי|tri|שלושה|three
            четы́ре|צ'יטירה|chetyre|ארבעה|four
            пять|פיאט|pyat'|חמישה|five
            шесть|שסט|shest'|שישה|six
            семь|סיים|sem'|שבעה|seven
            во́семь|ווסים|vosem'|שמונה|eight
            де́вять|דיוויט|devyat'|תשעה|nine
            де́сять|דיסיט|desyat'|עשרה|ten
        """),
        Triple(Level.EASY, "family", """
            ма́ма|מאמה|mama|אמא|mother
            па́па|פאפה|papa|אבא|father
            брат|בראט|brat|אח|brother
            сестра́|סיסטרה|sestra|אחות|sister
            де́душка|דידושקה|dedushka|סבא|grandfather
            ба́бушка|באבושקה|babushka|סבתא|grandmother
            сын|סין|syn|בן|son
            дочь|דוץ'|doch'|בת|daughter
        """),
        Triple(Level.EASY, "body", """
            голова́|גלאווה|golova|ראש|head
            глаз|גלאס|glaz|עין|eye
            рука́|רוקה|ruka|יד|hand
            нога́|נאגה|noga|רגל|leg
            нос|נוס|nos|אף|nose
            рот|רוט|rot|פה|mouth
            у́хо|אוחה|ukho|אוזן|ear
            се́рдце|סירצה|serdtse|לב|heart
        """),
        Triple(Level.MEDIUM, "food", """
            хлеб|חליעפ|khleb|לחם|bread
            вода́|וואדה|voda|מים|water
            молоко́|מלאקו|moloko|חלב|milk
            я́блоко|יאבלאקה|yabloko|תפוח|apple
            апельси́н|אפילסין|apel'sin|תפוז|orange
            бана́н|באנאן|banan|בננה|banana
            сыр|סיר|syr|גבינה|cheese
            яйцо́|יייצו|yaytso|ביצה|egg
            мя́со|מיאסה|myaso|בשר|meat
            рис|ריס|ris|אורז|rice
            са́хар|סאחר|sakhar|סוכר|sugar
            соль|סול|sol'|מלח|salt
            ко́фе|קופה|kofe|קפה|coffee
            чай|צ'אי|chay|תה|tea
        """),
        Triple(Level.MEDIUM, "home", """
            дом|דום|dom|בית|house
            дверь|דווער|dver'|דלת|door
            окно́|אקנו|okno|חלון|window
            стул|סטוּל|stul|כיסא|chair
            стол|סטוֹל|stol|שולחן|table
            крова́ть|קראוואט|krovat'|מיטה|bed
            ключ|קליוץ'|klyuch|מפתח|key
            кни́га|קניגה|kniga|ספר|book
            ру́чка|רוצ'קה|ruchka|עט|pen
            шко́ла|שקולה|shkola|בית ספר|school
            маши́на|מאשינה|mashina|מכונית|car
        """),
        Triple(Level.MEDIUM, "nature", """
            со́лнце|סונצה|solntse|שמש|sun
            луна́|לונה|luna|ירח|moon
            звезда́|זוויזדה|zvezda|כוכב|star
            мо́ре|מורה|more|ים|sea
            де́рево|דיריווה|derevo|עץ|tree
            цвето́к|צוויטוק|tsvetok|פרח|flower
            дождь|דושץ'|dozhd'|גשם|rain
            гора́|גארה|gora|הר|mountain
            не́бо|ניבה|nebo|שמיים|sky
        """),
        Triple(Level.MEDIUM, "adjectives", """
            большо́й|באלשוי|bol'shoy|גדול|big
            ма́ленький|מאלינקי|malen'kiy|קטן|small
            краси́вый|קראסיווי|krasivy|יפה|beautiful
            но́вый|נובי|novy|חדש|new
            ста́рый|סטארי|stary|עתיק|old
            горя́чий|גאריאצ'י|goryachiy|חם|hot
            холо́дный|חאלודני|kholodny|קר|cold
            дли́нный|דליני|dlinny|ארוך|long
            счастли́вый|שצ'סליווי|schastlivy|שמח|happy
        """),
        Triple(Level.MEDIUM, "time", """
            день|דיין|den'|יום|day
            ночь|נוץ'|noch'|לילה|night
            сего́дня|סיבודניה|segodnya|היום|today
            за́втра|זאפטרה|zavtra|מחר|tomorrow
            вчера́|פצ'ירה|vchera|אתמול|yesterday
            час|צ'אס|chas|שעה|hour
        """),
        Triple(Level.HARD, "phrases", """
            приве́т|פריוויעט|privet|שלום|hello
            спаси́бо|ספאסיבה|spasibo|תודה|thank you
            пожа́луйста|פאז'אלוסטה|pozhaluysta|בבקשה|please
            до́брое у́тро|דוברויה אוטרה|dobroye utro|בוקר טוב|good morning
            до́брый ве́чер|דוברי וויצ'ר|dobry vecher|ערב טוב|good evening
            споко́йной но́чи|ספאקוינוי נוצ'י|spokoynoy nochi|לילה טוב|good night
            как дела́?|קאק דילה|kak dela?|מה שלומך?|how are you?
            да|דא|da|כן|yes
            нет|ניעט|net|לא|no
            до свида́ния|דא סווידאניה|do svidaniya|להתראות|goodbye
            добро́ пожа́ловать|דאברו פאז'אלאוואט|dobro pozhalovat'|ברוכים הבאים|welcome
            не́ за что|ניע זה שטו|ne za chto|על לא דבר|you're welcome
            извини́те|איזוויניטה|izvinite|סליחה|sorry
            как тебя́ зову́т?|קאק טיביה זאווּט|kak tebya zovut?|מה שמך?|what is your name?
        """),
        Triple(Level.HARD, "pronouns", """
            я|יא|ya|אני|I
            ты|טי|ty|אתה|you
            он|און|on|הוא|he
            она́|אנה|ona|היא|she
            мы|מי|my|אנחנו|we
        """),
        Triple(Level.HARD, "verbs", """
            писа́л|פיסאל|pisal|כתב|wrote
            ел|יעל|yel|אכל|ate
            пил|פיל|pil|שתה|drank
            шёл|שול|shyol|הלך|went
            спал|ספאל|spal|ישן|slept
            чита́л|צ'יטאל|chital|קרא|read
            откры́л|אטקריל|otkryl|פתח|opened
            сиде́л|סידיעל|sidel|ישב|sat
            игра́л|איגראל|igral|שיחק|played
        """)
    )
}
