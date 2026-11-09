package com.example.mathbowling

import kotlin.math.abs
import kotlin.random.Random

/** What the game asks about. */
enum class GameMode(val language: ForeignLanguage?) {
    MATH(null),
    ARABIC(ForeignLanguage.ARABIC),
    RUSSIAN(ForeignLanguage.RUSSIAN),
    AI(null),
    /** The player's own questions, loaded from a text file. */
    CUSTOM(null)
}

enum class Level(val options: Int, val speed: Float) {
    EASY(3, 2.8f),
    MEDIUM(4, 3.3f),
    HARD(4, 3.8f)
}

/**
 * prompt: instruction text. expression: the big line (math written left-to-right, or a word when [textExpression]).
 * options are distinct ids (numbers for math, word indices for Arabic); labels are what the cones show.
 */
data class Question(
    val prompt: String,
    val expression: String,
    val answer: Int,
    val options: List<Int>,
    val labels: List<String> = options.map { it.toString() },
    /** Small line under the expression (transliteration). */
    val hint: String = "",
    /** Expression is natural-language text (any direction), not a math formula. */
    val textExpression: Boolean = false,
    /** Foreign word spoken (in [foreignLang]) when the question appears. */
    val foreignSpeech: String? = null,
    /** The correct foreign word, spoken after the ball hits. */
    val foreignAnswer: String? = null,
    /** Speech-engine language tag for the foreign words ("ar", "ru"). */
    val foreignLang: String? = null,
    /** Shown (and read aloud) after the ball hits, to teach why the answer is right. */
    val explanation: String = ""
) {
    val correctIndex: Int get() = options.indexOf(answer)
    val answerLabel: String get() = labels[correctIndex]
}

class QuestionGenerator(
    private val english: Boolean = false,
    private val rnd: Random = Random.Default
) {

    private fun t(he: String, en: String) = if (english) en else he
    private fun how() = t("כמה זה?", "How much is it?")
    private fun missing() = t("מצאו את המספר החסר", "Find the missing number")
    private fun series() = t("איזה מספר ממשיך את הסדרה?", "Which number comes next?")

    private class Raw(val prompt: String, val expr: String, val answer: Int, val near: List<Int> = emptyList())

    private val recent = ArrayDeque<String>()

    fun next(level: Level): Question {
        repeat(25) {
            val q = make(level)
            val key = q.prompt + q.expression
            if (key !in recent) {
                recent.addLast(key)
                if (recent.size > 10) recent.removeFirst()
                return q
            }
        }
        return make(level)
    }

    private fun r(a: Int, b: Int) = rnd.nextInt(a, b + 1)

    private fun make(level: Level): Question {
        val raw = when (level) {
            Level.EASY -> easy()
            Level.MEDIUM -> medium()
            Level.HARD -> hard()
        }
        return Question(raw.prompt, raw.expr, raw.answer, options(raw.answer, raw.near, level.options))
    }

    private fun options(answer: Int, near: List<Int>, n: Int): List<Int> {
        val set = LinkedHashSet<Int>()
        val generic = listOf(answer + 1, answer - 1, answer + 2, answer - 2, answer + 10, answer - 10)
        for (c in near.shuffled(rnd) + generic.shuffled(rnd)) {
            if (set.size >= n - 1) break
            if (c >= 0 && c != answer) set.add(c)
        }
        var spread = 3
        while (set.size < n - 1) {
            val c = answer + rnd.nextInt(-spread, spread + 1)
            if (c >= 0 && c != answer) set.add(c)
            spread++
        }
        return (set.toList() + answer).shuffled(rnd)
    }

    private fun series(start: Int, next: (Int) -> Int, terms: Int = 4): Pair<String, Int> {
        val list = ArrayList<Int>()
        var v = start
        repeat(terms) { list.add(v); v = next(v) }
        return Pair(list.joinToString(", ") + ", ?", v)
    }

    // ---------------- EASY ----------------

    private fun easy(): Raw = when (r(0, 9)) {
        0, 1 -> {
            val a = r(1, 10); val b = r(1, 10)
            Raw(how(), "$a + $b = ?", a + b, listOf(a + b + 1, a + b - 1, a * b))
        }
        2 -> {
            val a = r(5, 20); val b = r(1, a)
            Raw(how(), "$a − $b = ?", a - b, listOf(a + b, a - b + 1, a - b - 1))
        }
        3 -> {
            val a = r(1, 10); val c = a + r(1, 10)
            Raw(missing(), "$a + ? = $c", c - a, listOf(c, c + a, c - a + 1))
        }
        4 -> {
            val d = r(1, 3); val (expr, ans) = series(r(0, 10), { it + d })
            Raw(series(), expr, ans, listOf(ans + 1, ans - 1, ans + d))
        }
        5, 6 -> easyWord()
        else -> easyMore()
    }

    private fun easyWord(): Raw = when (r(0, 5)) {
        0 -> {
            val a = r(2, 9); val b = r(2, 9)
            Raw(t("לנועה יש $a בלונים. היא קיבלה עוד $b בלונים. כמה בלונים יש לה עכשיו?",
                "Noa has $a balloons. She got $b more balloons. How many balloons does she have now?"), "", a + b,
                listOf(a + b + 1, a + b - 1, abs(a - b)))
        }
        1 -> {
            val a = r(5, 15); val b = r(2, a - 1)
            Raw(t("על העץ ישבו $a ציפורים. $b ציפורים עפו. כמה ציפורים נשארו על העץ?",
                "$a birds sat on a tree. $b birds flew away. How many birds are left on the tree?"), "", a - b,
                listOf(a + b, a - b + 1, a - b - 1))
        }
        2 -> {
            val a = r(2, 9); val b = r(2, 9)
            Raw(t("בכיתה יש $a בנים ו־$b בנות. כמה ילדים יש בכיתה?",
                "There are $a boys and $b girls in the class. How many children are in the class?"), "", a + b,
                listOf(a + b + 1, a + b - 1, abs(a - b)))
        }
        3 -> {
            val a = r(6, 15); val b = r(2, 5)
            Raw(t("היו לדני $a סוכריות. הוא נתן לחבר $b סוכריות. כמה סוכריות נשארו לדני?",
                "Danny had $a candies. He gave $b candies to a friend. How many candies does Danny have left?"), "", a - b,
                listOf(a + b, a - b + 1, a - b - 1))
        }
        4 -> {
            val a = r(2, 9); val b = r(2, 9)
            Raw(t("בסל היו $a תפוחים ו־$b אגסים. כמה פירות היו בסל?",
                "There were $a apples and $b pears in the basket. How many fruits were in the basket?"), "", a + b,
                listOf(a + b + 1, a + b - 1, abs(a - b)))
        }
        else -> {
            val a = r(2, 8)
            Raw(t("ליוסי יש $a מכוניות צעצוע, ולאחותו יש פי 2. כמה מכוניות יש לאחותו?",
                "Yossi has $a toy cars, and his sister has twice as many. How many cars does his sister have?"), "", a * 2,
                listOf(a + 2, a * 2 + 1, a * 2 - 1))
        }
    }

    // ---------------- MEDIUM ----------------

    private fun medium(): Raw = when (r(0, 11)) {
        0 -> {
            val a = r(2, 10); val b = r(2, 10)
            Raw(how(), "$a × $b = ?", a * b, listOf(a * b + a, a * b - b, a + b, a * b + 1))
        }
        1 -> {
            val b = r(2, 10); val q = r(2, 10)
            Raw(how(), "${b * q} ÷ $b = ?", q, listOf(q + 1, q - 1, q + 2, b))
        }
        2 -> {
            val b = r(2, 10); val x = r(2, 10)
            Raw(missing(), "? × $b = ${x * b}", x, listOf(x + 1, x - 1, x * b - b, b))
        }
        3 -> {
            val a = r(15, 60); val b = r(10, 39)
            Raw(how(), "$a + $b = ?", a + b, listOf(a + b + 10, a + b - 10, a + b + 1, a + b - 1))
        }
        4 -> {
            val a = r(30, 99); val b = r(10, a - 5)
            Raw(how(), "$a − $b = ?", a - b, listOf(a - b + 10, a - b - 10, a - b + 1, a - b - 1))
        }
        5 -> {
            val a = r(5, 20); val b = r(5, 20); val c = r(1, a + b - 1)
            Raw(how(), "$a + $b − $c = ?", a + b - c, listOf(a + b + c, a + b - c + 1, a + b - c - 2))
        }
        6 -> {
            val d = r(2, 9); val (expr, ans) = series(r(1, 20), { it + d })
            Raw(series(), expr, ans, listOf(ans + 1, ans - 1, ans + d, ans - 2))
        }
        7, 8 -> mediumWord()
        else -> mediumMore()
    }

    private fun mediumWord(): Raw = when (r(0, 5)) {
        0 -> {
            val a = r(3, 9); val b = r(2, 6)
            Raw(t("בכל קופסה יש $a עפרונות. כמה עפרונות יש ב־$b קופסאות?",
                "Each box has $a pencils. How many pencils are in $b boxes?"), "", a * b,
                listOf(a + b, a * b + a, a * b - b))
        }
        1 -> {
            val k = r(2, 6); val q = r(2, 8)
            Raw(t("${k * q} עוגיות חולקו שווה בשווה בין $k ילדים. כמה עוגיות קיבל כל ילד?",
                "${k * q} cookies were shared equally among $k children. How many cookies did each child get?"), "", q,
                listOf(q + 1, q - 1, k, q + 2))
        }
        2 -> {
            val n = r(2, 9)
            Raw(t("לכל כלב יש 4 רגליים. כמה רגליים יש ל־$n כלבים?",
                "A dog has 4 legs. How many legs do $n dogs have?"), "", 4 * n,
                listOf(4 + n, 4 * n + 4, 4 * n - 4, 4 * n + 1))
        }
        3 -> {
            val n = r(2, 6)
            Raw(t("בשבוע יש 7 ימים. כמה ימים יש ב־$n שבועות?",
                "A week has 7 days. How many days are in $n weeks?"), "", 7 * n,
                listOf(7 + n, 7 * n + 7, 7 * n - 1, 7 * n + 1))
        }
        4 -> {
            val p = r(5, 15); val n = r(2, 5)
            Raw(t("כרטיס לסרט עולה $p שקלים. כמה עולים $n כרטיסים?",
                "A movie ticket costs $p shekels. How much do $n tickets cost?"), "", p * n,
                listOf(p + n, p * n + p, p * n - 1, p * n + 10))
        }
        else -> {
            val a = r(30, 100); val b = r(10, a - 5)
            Raw(t("לרון היו $a שקלים. הוא קנה ספר ב־$b שקלים. כמה כסף נשאר לו?",
                "Ron had $a shekels. He bought a book for $b shekels. How much money does he have left?"), "", a - b,
                listOf(a + b, a - b + 10, a - b - 1, a - b + 1))
        }
    }

    // ---------------- HARD ----------------

    private fun hard(): Raw = when (r(0, 13)) {
        0 -> {
            val a = r(2, 20); val b = r(2, 9); val c = r(2, 9)
            Raw(how(), "$a + $b × $c = ?", a + b * c, listOf((a + b) * c, a + b * c + 1, a + b * c - c))
        }
        1 -> {
            val a = r(2, 9); val b = r(2, 9); val c = r(2, 6)
            Raw(how(), "($a + $b) × $c = ?", (a + b) * c, listOf(a + b * c, (a + b) * c + c, (a + b) * c - 1))
        }
        2 -> {
            val a = r(3, 12); val b = r(3, 9); val c = r(1, a * b - 1)
            Raw(how(), "$a × $b − $c = ?", a * b - c, listOf(a * b + c, a * b - c + 1, a * b - c - 10))
        }
        3 -> {
            val a = r(12, 49); val b = r(3, 9)
            Raw(how(), "$a × $b = ?", a * b, listOf(a * b + b, a * b - b, a * b + 10, a * b - 1))
        }
        4 -> {
            val a = r(2, 15)
            Raw(how(), "$a² = ?", a * a, listOf(a * 2, a * a + a, a * a - 1, a * a + 1))
        }
        5 -> {
            val a = r(3, 12); val x = r(3, 12)
            Raw(missing(), "$a × ? = ${a * x}", x, listOf(x + 1, x - 1, a * x - a, x + 2))
        }
        6 -> {
            val b = r(3, 12); val q = r(4, 15)
            Raw(how(), "${b * q} ÷ $b = ?", q, listOf(q + 1, q - 1, q + 2, b))
        }
        7 -> {
            val k = r(2, 3); val (expr, ans) = series(r(1, 5), { it * k })
            val last = ans / k; val prev = last / k
            Raw(series(), expr, ans, listOf(last + (last - prev), ans + 1, ans - k, ans + k))
        }
        8, 9, 10 -> hardWord()
        else -> hardMore()
    }

    private fun hardWord(): Raw = when (r(0, 5)) {
        0 -> {
            val x = r(2, 12); val m = r(2, 6); val p = r(1, 15); val res = x * m + p
            Raw(t("חשבתי על מספר. הכפלתי אותו ב־$m והוספתי $p, וקיבלתי $res. על איזה מספר חשבתי?",
                "I thought of a number. I multiplied it by $m and added $p, and got $res. What number did I think of?"), "", x,
                listOf(x + 1, x - 1, res - p, x + 2))
        }
        1 -> {
            val v = r(4, 12) * 10; val h = r(2, 5)
            Raw(t("רכבת נוסעת במהירות של $v קילומטרים בשעה. כמה קילומטרים היא תעבור ב־$h שעות?",
                "A train travels at $v kilometers per hour. How many kilometers will it travel in $h hours?"), "", v * h,
                listOf(v + h, v * h + v, v * h - 10, v * (h + 1)))
        }
        2 -> {
            val l = r(4, 15); val w = r(3, 9)
            Raw(t("לגינה מלבנית יש אורך של $l מטרים ורוחב של $w מטרים. מה שטח הגינה במטרים רבועים?",
                "A rectangular garden is $l meters long and $w meters wide. What is its area in square meters?"), "", l * w,
                listOf(2 * (l + w), l + w, l * w + l, l * w - w))
        }
        3 -> {
            val n = r(3, 12)
            Raw(t("לכל עכביש יש 8 רגליים. כמה רגליים יש ל־$n עכבישים?",
                "A spider has 8 legs. How many legs do $n spiders have?"), "", 8 * n,
                listOf(8 + n, 8 * n + 8, 8 * n - 8, 8 * n + 1))
        }
        4 -> {
            val boxes = r(3, 8); val per = r(6, 12); val sold = r(5, boxes * per - 5)
            Raw(t("בחנות יש $boxes ארגזים, ובכל ארגז $per בקבוקים. נמכרו $sold בקבוקים. כמה בקבוקים נשארו?",
                "A store has $boxes crates with $per bottles in each. $sold bottles were sold. How many bottles are left?"), "",
                boxes * per - sold, listOf(boxes * per + sold, boxes * per - sold + 10, boxes + per - sold, boxes * per - sold - 1))
        }
        else -> {
            val n = r(5, 50) * 2; val k = r(1, 20)
            Raw(t("מה זה חצי מ־$n ועוד $k?",
                "What is half of $n plus $k?"), "", n / 2 + k, listOf(n + k, n / 2 + k + 1, n / 2 - k, n / 2 + k + 10))
        }
    }


    // ---------------- more question types ----------------

    private class Kid(val he: String, val en: String, val boy: Boolean)

    private val kids = listOf(
        Kid("מאיה", "Maya", false), Kid("איתי", "Itai", true), Kid("שירה", "Shira", false),
        Kid("עומר", "Omer", true), Kid("יעל", "Yael", false), Kid("נועם", "Noam", true),
        Kid("תמר", "Tamar", false), Kid("אורי", "Ori", true), Kid("ליה", "Lia", false), Kid("אדם", "Adam", true)
    )

    private fun kid() = kids[rnd.nextInt(kids.size)]

    /** Hebrew verb/adjective form for the kid's gender. */
    private fun g(k: Kid, boy: String, girl: String) = if (k.boy) boy else girl
    private fun he(k: Kid) = if (k.boy) "he" else "she"
    private fun his(k: Kid) = if (k.boy) "his" else "her"

    /** [count] distinct numbers in [lo]..[hi] that satisfy [ok]. */
    private fun distinct(count: Int, lo: Int, hi: Int, ok: (Int) -> Boolean): List<Int> {
        val set = LinkedHashSet<Int>()
        while (set.size < count) { val v = r(lo, hi); if (ok(v)) set.add(v) }
        return set.toList()
    }

    private fun easyMore(): Raw = when (r(0, 6)) {
        0 -> {
            val nums = distinct(4, 1, 99) { true }
            if (rnd.nextBoolean()) {
                val ans = nums.max()
                Raw(t("איזה מספר הכי גדול?", "Which number is the biggest?"), "", ans, nums - ans)
            } else {
                val ans = nums.min()
                Raw(t("איזה מספר הכי קטן?", "Which number is the smallest?"), "", ans, nums - ans)
            }
        }
        1 -> {
            val shapes = listOf(
                Triple("משולש", "a triangle", 3), Triple("ריבוע", "a square", 4), Triple("מלבן", "a rectangle", 4),
                Triple("מחומש", "a pentagon", 5), Triple("משושה", "a hexagon", 6), Triple("מתומן", "an octagon", 8)
            )
            val (h, e, n) = shapes[rnd.nextInt(shapes.size)]
            Raw(t("כמה צלעות יש ל$h?", "How many sides does $e have?"), "", n, listOf(n + 1, n - 1, n + 2, 2 * n))
        }
        2 -> {
            val n = r(11, 99)
            if (rnd.nextBoolean()) {
                Raw(t("כמה עשרות יש במספר $n?", "How many tens are in the number $n?"), "", n / 10,
                    listOf(n % 10, n / 10 + 1, n / 10 - 1, n))
            } else {
                Raw(t("כמה אחדות יש במספר $n?", "How many ones are in the number $n?"), "", n % 10,
                    listOf(n / 10, n % 10 + 1, n % 10 - 1, n))
            }
        }
        3 -> {
            val k = kid(); val a = r(1, 4); val b = r(1, 5)
            Raw(t("ל${k.he} יש $a מטבעות של 5 שקלים ו־$b מטבעות של שקל אחד. כמה שקלים יש ל${g(k, "ו", "ה")}?",
                "${k.en} has $a five-shekel coins and $b one-shekel coins. How many shekels does ${he(k)} have?"), "",
                5 * a + b, listOf(a + b, 5 * a, 5 * a + b + 1, 5 * (a + b)))
        }
        4 -> {
            val c = r(6, 20); val x = r(1, c - 1)
            Raw(missing(), "$c − ? = ${c - x}", x, listOf(c + (c - x), x + 1, c - x, x - 1))
        }
        5 -> {
            val n = r(2, 10)
            Raw(t("מה זה חצי מ־${2 * n}?", "What is half of ${2 * n}?"), "", n, listOf(2 * n, n + 1, n - 1, 4 * n))
        }
        else -> {
            val h = r(1, 8); val k = r(1, 3)
            Raw(t("השעה עכשיו $h:00. מה תהיה השעה בעוד $k שעות?",
                "It is $h:00 now. What hour will it be in $k hours?"), "", h + k,
                listOf(h - k, h + k + 1, k, h + k - 1))
        }
    }

    private fun mediumMore(): Raw = when (r(0, 7)) {
        0 -> {
            var n = r(11, 98)
            while (n % 10 == 5 || n % 10 == 0) n = r(11, 98)
            val ans = (n + 5) / 10 * 10
            Raw(t("עגלו לעשרת הקרובה", "Round to the nearest ten"), "$n", ans,
                listOf(if (ans > n) ans - 10 else ans + 10, n, ans + 10, ans - 20))
        }
        1 -> {
            val h = r(2, 5)
            if (rnd.nextBoolean()) {
                Raw(t("כמה דקות יש ב־$h שעות?", "How many minutes are in $h hours?"), "", 60 * h,
                    listOf(60 + h, 60 * (h + 1), 100 * h, 60 * h - 10))
            } else {
                Raw(t("כמה שניות יש ב־$h דקות?", "How many seconds are in $h minutes?"), "", 60 * h,
                    listOf(60 + h, 60 * (h - 1), 100 * h, 60 * h + 10))
            }
        }
        2 -> {
            val (d, fh, fe) = listOf(Triple(3, "שליש", "a third"), Triple(4, "רבע", "a quarter"), Triple(5, "חמישית", "a fifth"))[r(0, 2)]
            val q = r(2, 10); val n = d * q
            Raw(t("מה זה $fh מ־$n?", "What is $fe of $n?"), "", q, listOf(n - q, q + 1, q - 1, d))
        }
        3 -> {
            if (rnd.nextBoolean()) {
                val s = r(2, 12)
                Raw(t("לריבוע יש צלע באורך $s סנטימטרים. מה ההיקף שלו?",
                    "A square has sides of $s centimeters. What is its perimeter?"), "", 4 * s,
                    listOf(s * s, 2 * s, 4 * s + 4, 3 * s))
            } else {
                val l = r(4, 15); val w = r(2, l - 1)
                Raw(t("למלבן יש אורך $l ורוחב $w. מה ההיקף שלו?",
                    "A rectangle is $l long and $w wide. What is its perimeter?"), "", 2 * (l + w),
                    listOf(l * w, l + w, 2 * (l + w) + 2, 2 * l + w))
            }
        }
        4 -> {
            if (rnd.nextBoolean()) {
                val ans = distinct(1, 10, 98) { it % 2 == 0 }[0]
                Raw(t("איזה מספר זוגי?", "Which number is even?"), "", ans, distinct(3, 11, 99) { it % 2 == 1 })
            } else {
                val ans = distinct(1, 11, 99) { it % 2 == 1 }[0]
                Raw(t("איזה מספר אי־זוגי?", "Which number is odd?"), "", ans, distinct(3, 10, 98) { it % 2 == 0 })
            }
        }
        5 -> {
            val a = r(30, 99); val x = r(10, a - 5)
            Raw(missing(), "$a − ? = ${a - x}", x, listOf(a + (a - x), x + 10, x - 1, a - x))
        }
        6 -> {
            val k = kid(); val p = r(12, 45)
            Raw(t("${k.he} ${g(k, "קנה", "קנתה")} משחק ב־$p שקלים ו${g(k, "שילם", "שילמה")} בשטר של 50. כמה עודף ${g(k, "קיבל", "קיבלה")}?",
                "${k.en} bought a game for $p shekels and paid with a 50 note. How much change did ${he(k)} get?"), "",
                50 - p, listOf(p, 50 - p + 10, 50 - p - 1, 50 - p + 1))
        }
        else -> {
            val k = kid(); val a = r(5, 12); val b = r(2, 6); val y = r(2, 5)
            Raw(t("${k.he} ${g(k, "בן", "בת")} $a. האח ${g(k, "שלו", "שלה")} גדול ב־$b שנים. בן כמה יהיה האח בעוד $y שנים?",
                "${k.en} is $a. ${his(k).replaceFirstChar { it.uppercase() }} brother is $b years older. How old will the brother be in $y years?"), "",
                a + b + y, listOf(a + b, a + y, a + b + y + 1, a + b + y - 1))
        }
    }

    private fun hardMore(): Raw = when (r(0, 7)) {
        0 -> {
            val (p, step) = listOf(10 to 10, 20 to 5, 25 to 4, 50 to 2, 75 to 4)[r(0, 4)]
            val n = step * r(5, 40)
            val ans = n * p / 100
            Raw(how(), "$p% × $n = ?", ans, listOf(n - ans, ans + 10, ans * 2, p))
        }
        1 -> {
            val a = r(2, 15)
            Raw(how(), "√${a * a} = ?", a, listOf(a * a / 2, a + 1, a - 1, 2 * a))
        }
        2 -> {
            val m = r(2, 9); val x = r(2, 12); val p = r(1, 20); val res = m * x + p
            Raw(missing(), "$m × ? + $p = $res", x, listOf(x + 1, x - 1, res - p, x + 2))
        }
        3 -> {
            val d = r(3, 9); val q = r(2, 9); val rem = r(1, d - 1); val n = d * q + rem
            Raw(t("מה השארית כשמחלקים $n ב־$d?", "What is the remainder when $n is divided by $d?"), "", rem,
                listOf(q, rem + 1, d - rem, 0))
        }
        4 -> {
            val m = r(8, 40); val d1 = r(1, 6); val d2 = r(1, 6)
            val nums = listOf(m - d1, m - d2, m + d1 + d2).shuffled(rnd)
            Raw(t("מה הממוצע של המספרים ${nums[0]}, ${nums[1]} ו־${nums[2]}?",
                "What is the average of ${nums[0]}, ${nums[1]} and ${nums[2]}?"), "", m,
                listOf(3 * m, m + 1, m - 1, m + d1 + d2))
        }
        5 -> {
            val price = r(4, 30) * 10
            val disc = listOf(10, 20, 50).let { it[r(0, it.size - 1)] }
            val pay = price - price * disc / 100
            Raw(t("מעיל עולה $price שקלים, ויש עליו הנחה של $disc%. כמה הוא עולה אחרי ההנחה?",
                "A coat costs $price shekels and has a $disc% discount. How much does it cost after the discount?"), "", pay,
                listOf(price * disc / 100, price - disc, pay + 10, price))
        }
        6 -> {
            val a = r(200, 999); val b = r(100, a - 50)
            Raw(how(), "$a − $b = ?", a - b, listOf(a - b + 100, a - b - 10, a - b + 1, a - b - 100))
        }
        else -> {
            val a = r(12, 99); val k = if (rnd.nextBoolean()) 10 else 100
            Raw(how(), "$a × $k = ?", a * k, listOf(a * k / 10, a * k * 10, a + k, a * k + k))
        }
    }

}
