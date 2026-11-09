package com.example.mathbowling

import kotlin.random.Random

/** One AI-basics quiz item in one language. */
data class AiItem(
    val question: String,
    val correct: String,
    val wrong1: String,
    val wrong2: String,
    val explanation: String
)

object AiQuiz {

    /** Each entry: level, Hebrew line, English line. Line format: question | correct | wrong | wrong | explanation */
    private val raw: List<Triple<Level, String, String>> = listOf(
        // ---------------- EASY: what AI is, everyday examples, safety ----------------
        Triple(Level.EASY,
            "מה זה בינה מלאכותית?|מחשב שלומד לחשוב|רובוט מפלסטיק|סוג של אינטרנט|בינה מלאכותית (AI) היא תוכנות מחשב שיודעות ללמוד ולפתור בעיות שבדרך כלל דורשות חשיבה של בני אדם.",
            "What is artificial intelligence?|Computers that learn|A plastic robot|A kind of internet|AI is computer programs that can learn and solve problems that usually need human thinking."),
        Triple(Level.EASY,
            "מה מהבאים משתמש בבינה מלאכותית?|עוזר קולי בטלפון|מחדד עפרונות|מטרייה|עוזרים קוליים מבינים דיבור בעזרת בינה מלאכותית.",
            "Which of these uses AI?|A voice assistant|A pencil sharpener|An umbrella|Voice assistants use AI to understand speech."),
        Triple(Level.EASY,
            "ממה בינה מלאכותית לומדת?|מדוגמאות ונתונים|מחלומות|מהשקע בקיר|AI לומדת ממספר עצום של דוגמאות: תמונות, טקסטים וקולות.",
            "What does AI learn from?|Examples and data|Dreams|The wall socket|AI learns from huge numbers of examples: pictures, texts and sounds."),
        Triple(Level.EASY,
            "האם בינה מלאכותית תמיד צודקת?|לא, היא יכולה לטעות|כן, תמיד|רק בימי שני|AI יכולה לטעות, ולכן חשוב לבדוק את מה שהיא אומרת.",
            "Is AI always right?|No, it can be wrong|Yes, always|Only on Mondays|AI can make mistakes, so it's important to check what it says."),
        Triple(Level.EASY,
            "איך קוראים למי שכותב תוכנות?|מתכנת|צייר|טייס|מתכנתים כותבים את הקוד שממנו בנויות תוכנות, כולל תוכנות AI.",
            "What do we call someone who writes programs?|A programmer|A painter|A pilot|Programmers write the code that programs, including AI, are made of."),
        Triple(Level.EASY,
            "מה עושה מסנן ספאם בדואר?|מזהה הודעות זבל|שולח מכתבים|מנקה את המסך|מסנן ספאם לומד לזהות הודעות לא רצויות לפי דוגמאות.",
            "What does a spam filter do?|Spots junk messages|Sends letters|Cleans the screen|A spam filter learns to recognize unwanted messages from examples."),
        Triple(Level.EASY,
            "מה זה רובוט?|מכונה שמבצעת משימות|סוג של מחשבון|בעל חיים|רובוט הוא מכונה שזזה ומבצעת משימות. לחלק מהרובוטים יש AI ולחלק אין.",
            "What is a robot?|A machine that does tasks|A kind of calculator|An animal|A robot is a machine that moves and does tasks. Some robots use AI and some don't."),
        Triple(Level.EASY,
            "איך מכונית אוטונומית \"רואה\" את הכביש?|במצלמות וחיישנים|במשקפיים|היא לא רואה|מכוניות אוטונומיות משתמשות במצלמות ובחיישנים, ו-AI מפענחת את מה שהם קולטים.",
            "How does a self-driving car \"see\"?|Cameras and sensors|With glasses|It can't see|Self-driving cars use cameras and sensors, and AI makes sense of what they capture."),
        Triple(Level.EASY,
            "מה עושה אפליקציית תרגום?|מתרגמת בין שפות|מצלמת תמונות|מנגנת שירים|אפליקציות תרגום משתמשות ב-AI שלמדה ממיליוני משפטים מתורגמים.",
            "What does a translation app do?|Translates languages|Takes photos|Plays songs|Translation apps use AI that learned from millions of translated sentences."),
        Triple(Level.EASY,
            "האם לבינה מלאכותית יש רגשות?|לא, היא רק מחקה|כן, כמו לנו|רק כשהיא עייפה|AI יכולה לכתוב כאילו יש לה רגשות, אבל היא לא מרגישה באמת.",
            "Does AI have feelings?|No, it only imitates|Yes, like us|Only when it's tired|AI can write as if it has feelings, but it doesn't really feel anything."),
        Triple(Level.EASY,
            "מה לא כדאי לשתף עם צ'אטבוט?|סיסמאות ופרטים אישיים|שאלה על החלל|מתכון לעוגה|לעולם אל תשתפו סיסמאות, כתובת או פרטים אישיים עם צ'אטבוט.",
            "What shouldn't you share with a chatbot?|Passwords, private info|A question about space|A cake recipe|Never share passwords, your address or personal details with a chatbot."),
        Triple(Level.EASY,
            "מה זה צ'אטבוט?|תוכנה שמשוחחת איתך|משחק כדורגל|סוג של מקלדת|צ'אטבוט הוא תוכנה שמנהלת שיחה בכתב או בקול.",
            "What is a chatbot?|A program you chat with|A football game|A kind of keyboard|A chatbot is a program that holds a conversation in text or by voice."),

        // ---------------- MEDIUM: machine learning, data, training ----------------
        Triple(Level.MEDIUM,
            "מה זה למידת מכונה?|מחשב שלומד מדוגמאות|תיקון מכונות|שיעורי נהיגה|בלמידת מכונה לא כותבים כל כלל: המחשב מוצא את החוקיות בעצמו מתוך דוגמאות.",
            "What is machine learning?|Learning from examples|Fixing machines|Driving lessons|In machine learning nobody writes every rule: the computer finds the patterns itself from examples."),
        Triple(Level.MEDIUM,
            "מה זה \"נתונים\" (דאטה)?|מידע שלומדים ממנו|סוג של כבל|מסך גדול|נתונים הם כל מידע (מספרים, תמונות, טקסטים) שממנו AI לומדת.",
            "What is data?|Information to learn from|A kind of cable|A big screen|Data is any information (numbers, pictures, texts) that AI learns from."),
        Triple(Level.MEDIUM,
            "איך מלמדים AI לזהות חתולים?|מראים הרבה תמונות|מספרים לה סיפור|נותנים לה חלב|מראים למודל אלפי תמונות עם התווית \"חתול\", והוא לומד מה משותף להן.",
            "How do you teach AI to spot cats?|Show it many pictures|Tell it a story|Give it milk|You show the model thousands of pictures labeled \"cat\", and it learns what they have in common."),
        Triple(Level.MEDIUM,
            "מה זה \"אימון\" של מודל?|שלב הלמידה מהנתונים|ריצה בפארק|כיבוי המחשב|באימון המודל מתקן את עצמו שוב ושוב, עד שהוא טועה פחות.",
            "What is \"training\" a model?|Learning from data|Jogging in the park|Turning it off|During training the model adjusts itself again and again, until it makes fewer mistakes."),
        Triple(Level.MEDIUM,
            "מה זה אלגוריתם?|סדרת הוראות לפתרון|שם של כוכב|סוג של מחשב נייד|אלגוריתם הוא רשימת צעדים מסודרת, כמו מתכון, לפתרון בעיה.",
            "What is an algorithm?|Step-by-step instructions|The name of a star|A kind of laptop|An algorithm is an ordered list of steps, like a recipe, for solving a problem."),
        Triple(Level.MEDIUM,
            "מה קורה כש-AI לומדת מנתונים שגויים?|היא לומדת טעויות|שום דבר|המחשב מתפוצץ|\"זבל נכנס, זבל יוצא\": אם לומדים מנתונים שגויים, גם התשובות יהיו שגויות.",
            "What happens when AI learns from wrong data?|It learns the mistakes|Nothing|The computer explodes|\"Garbage in, garbage out\": learning from wrong data gives wrong answers."),
        Triple(Level.MEDIUM,
            "מה זה זיהוי פנים?|AI שמזהה אנשים|איפור|ציור דיוקן|זיהוי פנים משמש למשל לפתיחת הטלפון, והוא מעורר גם שאלות של פרטיות.",
            "What is face recognition?|AI that recognizes people|Makeup|Portrait painting|Face recognition is used, for example, to unlock phones, and it also raises privacy questions."),
        Triple(Level.MEDIUM,
            "איך אפליקציית סרטים יודעת מה להמליץ?|לומדת ממה שצפיתם|מנחשת באקראי|שואלת את השכנים|מערכות המלצה לומדות מההיסטוריה שלכם ושל אנשים עם טעם דומה.",
            "How does a video app know what to suggest?|Learns from your viewing|Random guesses|It asks your neighbors|Recommendation systems learn from your history and from people with similar taste."),
        Triple(Level.MEDIUM,
            "מה זו \"תווית\" בנתונים?|התשובה הנכונה לדוגמה|מדבקה על בקבוק|שם של קובץ|תווית אומרת למודל מה יש בדוגמה, למשל \"כלב\" ליד תמונה של כלב.",
            "What is a \"label\" in data?|The answer for an example|A sticker on a bottle|A file name|A label tells the model what an example shows, like \"dog\" next to a dog photo."),
        Triple(Level.MEDIUM,
            "למה AI צריכה הרבה דוגמאות?|כדי ללמוד דפוסים|כי היא משועממת|כדי לחמם את המחשב|ככל שיש יותר דוגמאות מגוונות, המודל מזהה דפוסים טוב יותר.",
            "Why does AI need many examples?|To learn patterns|Because it's bored|To warm up the computer|The more varied examples a model sees, the better it recognizes patterns."),
        Triple(Level.MEDIUM,
            "מה זה \"מודל\" בבינה מלאכותית?|מה שה-AI למדה|דוגמנית|צעצוע הרכבה|מודל הוא התוצאה של האימון: ה\"מוח\" שמקבל קלט ומחזיר תשובה.",
            "What is a \"model\" in AI?|What the AI has learned|A fashion model|A building toy|A model is the result of training: the \"brain\" that takes an input and gives an answer."),
        Triple(Level.MEDIUM,
            "מה זו הטיה (Bias) ב-AI?|חוסר הוגנות שנלמד|שיפוע של כביש|מהירות המחשב|אם הנתונים לא מאוזנים, ה-AI עלולה להעדיף קבוצה אחת על פני אחרת.",
            "What is bias in AI?|Learned unfairness|The slope of a road|Computer speed|If the data is unbalanced, AI may unfairly favor one group over another."),

        // ---------------- HARD: how modern AI works ----------------
        Triple(Level.HARD,
            "מה זו רשת עצבית?|מודל בהשראת המוח|רשת דייגים|רשת חברתית|רשת עצבית בנויה משכבות של \"נוירונים\" מלאכותיים שמחוברים זה לזה.",
            "What is a neural network?|A brain-inspired model|A fishing net|A social network|A neural network is made of layers of connected artificial \"neurons\"."),
        Triple(Level.HARD,
            "מה זה מודל שפה גדול (LLM)?|AI שלמדה מהרבה טקסט|מילון ענק|מקלדת גדולה|מודל שפה גדול לומד ממיליארדי מילים, ומנבא איזו מילה צריכה לבוא אחר כך.",
            "What is a large language model (LLM)?|AI trained on lots of text|A huge dictionary|A big keyboard|An LLM learns from billions of words and predicts which word should come next."),
        Triple(Level.HARD,
            "מה זו \"הזיה\" של AI?|תשובה בטוחה אך שגויה|חלום בלילה|תקלה במסך|AI עלולה להמציא עובדות בביטחון מלא, ולכן חשוב לבדוק מקורות.",
            "What is an AI \"hallucination\"?|A confident wrong answer|A dream at night|A screen glitch|AI may make up facts with full confidence, so always check sources."),
        Triple(Level.HARD,
            "מה זה \"פרומפט\"?|ההוראה שנותנים ל-AI|סוג של וירוס|כפתור כיבוי|פרומפט הוא הטקסט שכותבים למודל. פרומפט ברור מקבל תשובה טובה יותר.",
            "What is a \"prompt\"?|The instruction you give AI|A kind of virus|An off button|A prompt is the text you write to the model. A clear prompt gets a better answer."),
        Triple(Level.HARD,
            "מה זו התאמת יתר (Overfitting)?|שינון במקום הבנה|בגדים צמודים|מחשב מהיר מדי|מודל עם התאמת יתר מצליח בדוגמאות האימון, אבל נכשל בדוגמאות חדשות.",
            "What is overfitting?|Memorizing, not learning|Tight clothes|A too-fast computer|An overfitted model does well on its training examples but fails on new ones."),
        Triple(Level.HARD,
            "מה זה \"טוקן\" במודל שפה?|חתיכת טקסט קטנה|מטבע למשחק|סיסמה|מודלי שפה מפרקים טקסט לטוקנים (מילים או חלקי מילים) ועובדים איתם.",
            "What is a \"token\" in a language model?|A small piece of text|A game coin|A password|Language models break text into tokens (words or parts of words) and work with those."),
        Triple(Level.HARD,
            "מה זו למידה מחיזוקים?|למידה מפרסים ועונשים|חיזוק קירות|שיעורי בית|בלמידה מחיזוקים ה-AI מנסה, מקבלת ניקוד, ולומדת מה מביא יותר נקודות, כמו במשחק.",
            "What is reinforcement learning?|Learning from rewards|Strengthening walls|Homework|In reinforcement learning the AI tries things, gets a score, and learns what earns more points, like in a game."),
        Triple(Level.HARD,
            "מה זו בינה מלאכותית יוצרת?|AI שיוצרת תוכן חדש|AI שמוחקת קבצים|מחשב ישן|AI יוצרת (גנרטיבית) מייצרת טקסט, תמונות, מוזיקה וקוד חדשים על בסיס מה שלמדה.",
            "What is generative AI?|AI that creates content|AI that deletes files|An old computer|Generative AI produces new text, images, music and code based on what it learned."),
        Triple(Level.HARD,
            "מה זה דיפפייק (Deepfake)?|סרטון מזויף שנוצר ב-AI|סרט מצויר|צלילה עמוקה|דיפפייק מחקה פנים וקול של אדם אמיתי. חשוב לבדוק אם סרטון אמין לפני שמשתפים.",
            "What is a deepfake?|A fake video made by AI|A cartoon|Deep-sea diving|A deepfake imitates a real person's face and voice. Check whether a video is trustworthy before sharing it."),
        Triple(Level.HARD,
            "למה בודקים מודל על נתונים שלא ראה?|לדעת אם באמת למד|כדי לחסוך מקום|בשביל היופי|בודקים את המודל על דוגמאות שלא ראה באימון, כדי לדעת אם הוא באמת למד ולא רק שינן.",
            "Why test a model on data it never saw?|To see if it really learned|To save space|For looks|Testing on examples the model never saw in training shows whether it really learned or just memorized."),
        Triple(Level.HARD,
            "מה בודק מבחן טיורינג?|אם מכונה נשמעת כאדם|ידע במתמטיקה|נהיגה בטוחה|אלן טיורינג הציע ב-1950: אם בשיחה אי אפשר להבחין בין מכונה לאדם, המכונה \"עוברת\".",
            "What does the Turing test check?|If a machine seems human|Math knowledge|Safe driving|Alan Turing proposed in 1950: if you can't tell a machine from a person in conversation, the machine \"passes\"."),
        Triple(Level.HARD,
            "למה משתמשים במעבד גרפי (GPU) לאימון?|חישובים מקבילים מהירים|כדי לנגן מוזיקה|לשמירת סיסמאות|מעבד גרפי מבצע אלפי חישובים במקביל, ולכן מאיץ מאוד אימון של רשתות עצביות.",
            "Why are GPUs used for AI training?|Fast parallel math|To play music|To store passwords|A graphics processor does thousands of calculations at once, which greatly speeds up training neural networks.")
    )

    private fun parse(line: String): AiItem {
        val f = line.split("|").map { it.trim() }
        require(f.size == 5) { "Bad AI quiz line: $line" }
        return AiItem(f[0], f[1], f[2], f[3], f[4])
    }

    /** Per level: (Hebrew item, English item). */
    val items: List<Triple<Level, AiItem, AiItem>> = raw.map { (lvl, he, en) -> Triple(lvl, parse(he), parse(en)) }
}

class AiQuestionGenerator(
    private val english: Boolean = false,
    private val rnd: Random = Random.Default
) {
    private val recent = ArrayDeque<Int>()

    fun next(level: Level): Question {
        val pool = AiQuiz.items.indices.filter { AiQuiz.items[it].first == level }
        var idx = pool.random(rnd)
        var tries = 0
        while (idx in recent && tries++ < 30) idx = pool.random(rnd)
        recent.addLast(idx)
        if (recent.size > 8) recent.removeFirst()

        val (_, he, en) = AiQuiz.items[idx]
        val item = if (english) en else he
        // option ids: 0 = correct, 1 and 2 = wrong
        val options = listOf(0, 1, 2).shuffled(rnd)
        val texts = listOf(item.correct, item.wrong1, item.wrong2)
        return Question(
            prompt = item.question,
            expression = "",
            answer = 0,
            options = options,
            labels = options.map { texts[it] },
            textExpression = true,
            explanation = item.explanation
        )
    }
}
