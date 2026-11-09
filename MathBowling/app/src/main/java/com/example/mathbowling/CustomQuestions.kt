package com.example.mathbowling

import kotlin.random.Random

/** One question from the player's own file. [level] = null means "every level". */
data class CustomItem(
    val question: String,
    val correct: String,
    val wrong: List<String>,
    val explanation: String = "",
    val level: Level? = null
)

/** [badLines] are 1-based line numbers that could not be understood. */
data class CustomParseResult(val items: List<CustomItem>, val badLines: List<Int>)

/**
 * The player's own questions, in a plain text file that is easy to edit in any editor:
 *
 *     question | right answer | wrong answer | wrong answer [| wrong answer]
 *     > optional explanation, shown after the ball hits
 *
 * Lines starting with # are comments. A line [EASY] / [MEDIUM] / [HARD] (or [קל] / [בינוני] / [קשה])
 * puts the questions below it in that level; [ALL] / [הכל] goes back to "every level".
 */
object CustomQuestions {

    fun parse(text: String): CustomParseResult {
        val items = ArrayList<CustomItem>()
        val bad = ArrayList<Int>()
        var level: Level? = null
        text.removePrefix("﻿").lines().forEachIndexed { i, raw ->
            val line = raw.trim()
            when {
                line.isEmpty() || line.startsWith("#") -> {}
                line.startsWith("[") && line.endsWith("]") -> {
                    val name = line.substring(1, line.length - 1).trim().lowercase()
                    level = when (name) {
                        "easy", "קל" -> Level.EASY
                        "medium", "בינוני" -> Level.MEDIUM
                        "hard", "קשה" -> Level.HARD
                        "all", "הכל", "הכול" -> null
                        else -> { bad.add(i + 1); level }
                    }
                }
                line.startsWith(">") -> {
                    val last = items.lastOrNull()
                    val more = line.removePrefix(">").trim()
                    if (last == null || more.isEmpty()) bad.add(i + 1)
                    else items[items.size - 1] = last.copy(
                        explanation = if (last.explanation.isEmpty()) more else last.explanation + " " + more
                    )
                }
                else -> {
                    val f = line.split("|").map { it.trim() }.filter { it.isNotEmpty() }
                    // a question, the right answer and at least two different wrong answers
                    val wrong = f.drop(2).distinct().filter { f.size >= 2 && it != f[1] }
                    if (f.size < 4 || wrong.size < 2) bad.add(i + 1)
                    else items.add(CustomItem(f[0], f[1], wrong, level = level))
                }
            }
        }
        return CustomParseResult(items, bad)
    }

    /** A starter file, with instructions, in the app's language. */
    fun sample(english: Boolean): String = if (english) SAMPLE_EN else SAMPLE_HE

    private val SAMPLE_HE = """
# קובץ השאלות שלי – MathBowling
# כל שאלה בשורה אחת:
#   שאלה | תשובה נכונה | תשובה שגויה | תשובה שגויה
# אפשר להוסיף תשובה שגויה שלישית (ואז יהיו 4 קונוסים).
# שורה שמתחילה ב־> מתחת לשאלה היא הסבר שיופיע אחרי הפגיעה (לא חובה).
# שורות שמתחילות ב־# הן הערות והמשחק מתעלם מהן.
# אפשר לחלק לרמות עם השורות [קל] [בינוני] [קשה]. שאלות בלי רמה מופיעות בכל הרמות.
# שמרו את הקובץ בקידוד UTF-8, ואז במשחק: הגדרות ← השאלות שלי ← טעינת קובץ.

[קל]
מה בירת ישראל? | ירושלים | תל אביב | חיפה
> ירושלים היא בירת ישראל.
כמה רגליים יש לעכביש? | 8 | 6 | 4
איזה צבע מקבלים מכחול וצהוב? | ירוק | סגול | כתום

[בינוני]
איזה כוכב לכת הכי קרוב לשמש? | כוכב חמה | מאדים | נוגה | צדק
> כוכב חמה (מרקורי) הוא הקרוב ביותר לשמש.
כמה ימים יש בשנה מעוברת? | 366 | 365 | 364

[קשה]
מה הנוסחה הכימית של מים? | H2O | CO2 | O2 | NaCl
מי כתב את "התקווה"? | נפתלי הרץ אימבר | חיים נחמן ביאליק | שאול טשרניחובסקי
""".trimStart()

    private val SAMPLE_EN = """
# My questions – MathBowling
# One question per line:
#   question | right answer | wrong answer | wrong answer
# You can add a third wrong answer (then there will be 4 cones).
# A line starting with > under a question is an explanation shown after the hit (optional).
# Lines starting with # are comments and are ignored.
# Split questions into levels with the lines [EASY] [MEDIUM] [HARD]. Questions without a level appear in every level.
# Save the file as UTF-8, then in the game: Settings → My questions → Load file.

[EASY]
What is the capital of France? | Paris | London | Rome
> Paris has been the capital of France for centuries.
How many legs does a spider have? | 8 | 6 | 4
What color do you get from blue and yellow? | Green | Purple | Orange

[MEDIUM]
Which planet is closest to the Sun? | Mercury | Mars | Venus | Jupiter
How many days are in a leap year? | 366 | 365 | 364

[HARD]
What is the chemical formula of water? | H2O | CO2 | O2 | NaCl
Who wrote "Romeo and Juliet"? | William Shakespeare | Charles Dickens | Jane Austen
""".trimStart()
}

/** Serves the player's questions in a shuffled order, without repeats until every question was asked. */
class CustomQuestionGenerator(
    private val items: List<CustomItem>,
    private val rnd: Random = Random.Default
) {
    private val queues = HashMap<Level, ArrayDeque<CustomItem>>()

    /** Questions for [level]; when the file has none marked for it, every question is used. */
    private fun pool(level: Level): List<CustomItem> {
        val own = items.filter { it.level == null || it.level == level }
        return own.ifEmpty { items }
    }

    fun next(level: Level): Question {
        val queue = queues.getOrPut(level) { ArrayDeque() }
        if (queue.isEmpty()) queue.addAll(pool(level).shuffled(rnd))
        val item = queue.removeFirst()
        // up to 3 wrong answers (4 cones)
        val wrong = if (item.wrong.size > 3) item.wrong.shuffled(rnd).take(3) else item.wrong
        val texts = listOf(item.correct) + wrong
        // option ids: 0 = correct
        val options = texts.indices.toList().shuffled(rnd)
        return Question(
            prompt = item.question,
            expression = "",
            answer = 0,
            options = options,
            labels = options.map { texts[it] },
            // long answers get the wide two-line signs
            textExpression = texts.any { it.length > 4 },
            explanation = item.explanation
        )
    }
}
