package com.example.superprices

import android.util.Xml
import org.json.JSONObject
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class HttpStatusException(val code: Int) : IOException("HTTP $code")

object Http {
    private const val UA = "Mozilla/5.0 (Linux; Android) SuperPrices/1.0"

    fun <T> get(url: String, post: String? = null, block: (InputStream) -> T): T {
        var last: IOException? = null
        // Shufersal's portal often answers 502 when busy; a retry a few seconds later usually works.
        repeat(3) { attempt ->
            val c = URL(url).openConnection() as HttpURLConnection
            try {
                c.connectTimeout = 30_000
                // Shufersal's listing pages often take 50-60 seconds to start answering.
                c.readTimeout = 150_000
                c.setRequestProperty("User-Agent", UA)
                if (post != null) {
                    c.requestMethod = "POST"
                    c.doOutput = true
                    c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                    c.outputStream.use { it.write(post.toByteArray()) }
                }
                val code = c.responseCode
                if (code in 400..499) throw HttpStatusException(code) // won't change on retry
                if (code !in 200..299) throw IOException("HTTP $code")
                return c.inputStream.use(block)
            } catch (e: HttpStatusException) {
                throw e
            } catch (e: IOException) {
                last = e
                if (attempt < 2) Thread.sleep(5000L * (attempt + 1))
            } finally {
                c.disconnect()
            }
        }
        throw last ?: IOException("Download failed")
    }

    fun text(url: String, post: String? = null): String = get(url, post) { it.readBytes().toString(Charsets.UTF_8) }

    const val USER_AGENT = UA
}

/**
 * Cookies for one portal login. HttpURLConnection's global cookie handler would mix the
 * sessions of different chains, so each login keeps its own and follows no redirects itself.
 */
class CookieSession {
    private val cookies = java.util.concurrent.ConcurrentHashMap<String, String>()

    class Response(val code: Int, val location: String?, val body: ByteArray) {
        val text get() = body.toString(Charsets.UTF_8)
    }

    fun open(url: String, post: String? = null): Response {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.instanceFollowRedirects = false
            c.connectTimeout = 30_000
            c.readTimeout = 120_000
            c.setRequestProperty("User-Agent", Http.USER_AGENT)
            if (cookies.isNotEmpty()) c.setRequestProperty("Cookie", cookies.entries.joinToString("; ") { "${it.key}=${it.value}" })
            if (post != null) {
                c.requestMethod = "POST"
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                c.outputStream.use { it.write(post.toByteArray()) }
            }
            val code = c.responseCode
            c.headerFields.filterKeys { it.equals("Set-Cookie", ignoreCase = true) }.values.flatten().forEach { v ->
                val pair = v.substringBefore(';')
                cookies[pair.substringBefore('=').trim()] = pair.substringAfter('=', "").trim()
            }
            val body = (if (code >= 400) c.errorStream else c.inputStream)?.use { it.readBytes() } ?: ByteArray(0)
            return Response(code, c.getHeaderField("Location"), body)
        } finally {
            c.disconnect()
        }
    }
}

/** One supermarket chain's public price portal. All calls block; run them off the main thread. */
interface ChainSource {
    val key: String
    val title: String
    fun stores(): List<StoreRow>
    fun latest(storeId: String, kind: FileKind): FeedLink?

    /** Opens a file this source listed. Most are plain downloads; some portals need a session. */
    fun <T> download(link: FeedLink, block: (InputStream) -> T): T = Http.get(link.url, block = block)
}

fun ChainSource.downloadStores(link: FeedLink): List<StoreRow> =
    download(link) { XmlFeeds.parseStores(Xml.newPullParser(), XmlFeeds.reader(it), key) }

private fun enc(s: String) = java.net.URLEncoder.encode(s, "UTF-8")

/** A portal that lists all its recent files at once. The list is kept for a few minutes. */
abstract class ListedSource(private val chainId: String) : ChainSource {
    private var cache: Pair<Long, List<FeedLink>>? = null

    protected abstract fun list(): List<FeedLink>

    @Synchronized
    fun files(): List<FeedLink> {
        cache?.let { (at, links) -> if (System.currentTimeMillis() - at < 10 * 60_000) return links }
        // Some folders hold stray files of other chains (Keshet's has a Carrefour file).
        return list().filter { it.file.chainId == chainId }.also { cache = System.currentTimeMillis() to it }
    }

    @Synchronized
    fun clearCache() { cache = null }

    override fun stores(): List<StoreRow> =
        Listings.latest(files(), FileKind.STORES)?.let { downloadStores(it) } ?: throw IOException("$key: store list not found")

    override fun latest(storeId: String, kind: FileKind): FeedLink? = Listings.latest(files(), kind, storeId)
}

/**
 * The shared portal most chains use. Each chain logs in with the public username the law
 * requires it to publish, with an empty password. The certificate covers *.publishedprices.co.il,
 * so the url. host is used rather than url.retail.
 */
class PublishedPricesSource(
    override val key: String,
    override val title: String,
    chainId: String,
    private val user: String,
) : ListedSource(chainId) {
    private val base = "https://url.publishedprices.co.il"
    @Volatile private var session: CookieSession? = null

    private fun token(html: String) = Regex("name=\"csrftoken\" content=\"([^\"]*)\"").find(html)?.groupValues?.get(1).orEmpty()

    @Synchronized
    private fun login(): Pair<CookieSession, String> {
        val s = CookieSession()
        val t = token(s.open("$base/login").text)
        val r = s.open("$base/login/user", "username=${enc(user)}&password=&csrftoken=${enc(t)}")
        val loc = r.location
        if (r.code !in 300..399 || loc == null || !loc.contains("/file")) throw IOException("$key: login failed")
        val page = s.open(if (loc.startsWith("http")) loc else base + loc).text
        session = s
        return s to token(page)
    }

    override fun list(): List<FeedLink> {
        val (s, t) = login()
        val params = "sEcho=1&iColumns=5&sColumns=%2C%2C%2C%2C&iDisplayStart=0&iDisplayLength=100000" +
            "&mDataProp_0=fname&mDataProp_1=typeLabel&mDataProp_2=size&mDataProp_3=ftime&mDataProp_4=" +
            "&sSearch=&bRegex=false&iSortingCols=0&cd=%2F&csrftoken=${enc(t)}"
        val r = s.open("$base/file/json/dir", params)
        if (r.code != 200) throw IOException("$key: file list HTTP ${r.code}")
        return Listings.publishedPrices(r.text, base)
    }

    override fun <T> download(link: FeedLink, block: (InputStream) -> T): T {
        var last: IOException? = null
        repeat(2) {
            try {
                val s = session ?: login().first
                var r = s.open(link.url)
                if (r.code != 200 || (r.body.isNotEmpty() && r.body[0] == '<'.code.toByte())) {
                    r = login().first.open(link.url) // the session expired and the login page came back
                }
                if (r.code != 200) throw IOException("$key: download HTTP ${r.code}")
                return r.body.inputStream().use(block)
            } catch (e: IOException) {
                last = e
            }
        }
        throw last ?: IOException("$key: download failed")
    }
}

/** Chains on binaprojects.com: a JSON list of the newest files, and a page with each file's address. */
class BinaSource(override val key: String, override val title: String, chainId: String, subdomain: String) : ListedSource(chainId) {
    private val base = "https://$subdomain.binaprojects.com"

    override fun list() = Listings.bina(Http.text("$base/MainIO_Hok.aspx"), base)

    override fun <T> download(link: FeedLink, block: (InputStream) -> T): T {
        val real = Regex("\"SPath\":\"([^\"]+)\"").find(Http.text(link.url))?.groupValues?.get(1)
            ?.replace("\\/", "/") ?: throw IOException("$key: no file address")
        return Http.get(real, block = block)
    }
}

/** Wolt's stores: a page of files per day. */
class WoltSource : ListedSource("7290058249350") {
    override val key = "wolt"
    override val title = "וולט מרקט"
    private val base = "https://wm-gateway.wolt.com/isr-prices/public/v1/"

    override fun list(): List<FeedLink> {
        val today = LocalDate.now(ZoneId.of("Asia/Jerusalem"))
        val yesterday = runCatching { page(today.minusDays(1)) }.getOrDefault(emptyList())
        return yesterday + page(today)
    }

    private fun page(d: LocalDate) =
        Listings.links(Http.text("$base${d.format(DateTimeFormatter.ISO_LOCAL_DATE)}.html"), Regex("href=\"(download/[^\"]+)\""), base)
}

/** Hazi Hinam: pages of 50 files per type (t=1 prices, t=2 promotions, t=3 stores), newest first. */
class HaziHinamSource : ListedSource("7290700100008") {
    override val key = "hazi_hinam"
    override val title = "חצי חינם"
    private val base = "https://shop.hazi-hinam.co.il/Prices"
    private val link = Regex("href=\"(https://hazihinamprod01[^\"]+)\"")

    override fun list(): List<FeedLink> {
        val oldest = LocalDate.now(ZoneId.of("Asia/Jerusalem")).minusDays(2).format(DateTimeFormatter.BASIC_ISO_DATE)
        val out = ArrayList<FeedLink>()
        for (t in 1..3) {
            for (p in 1..59) {
                val got = Listings.links(Http.text("$base?t=$t&p=$p"), link)
                if (got.isEmpty()) break
                out += got
                if (got.all { it.file.stamp.take(8) < oldest }) break
            }
        }
        return out
    }
}

/** Super-Pharm: a grid filtered by file type and date, 20 rows a page. */
class SuperPharmSource : ListedSource("7290172900007") {
    override val key = "super_pharm"
    override val title = "סופר-פארם"
    private val base = "https://prices.super-pharm.co.il"
    private val link = Regex("href=\"(/Download/[^\"]+)\"")

    override fun list(): List<FeedLink> {
        val today = LocalDate.now(ZoneId.of("Asia/Jerusalem"))
        val fmt = DateTimeFormatter.ofPattern("dd/MM/yyyy")
        return listOf("pricefull", "promofull", "stores").flatMap { category ->
            category(category, today.format(fmt)).ifEmpty { category(category, today.minusDays(1).format(fmt)) }
        }
    }

    private fun category(category: String, day: String): List<FeedLink> {
        val query = "?category-equals=$category&date-equals=${enc(day)}"
        val first = Http.text("$base/$query")
        val total = Regex("data-total-rows=\"(\\d+)\"").find(first)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val out = ArrayList(Listings.links(first, link, base))
        for (page in 2..minOf(100, (total + 19) / 20)) out += Listings.links(Http.text("$base/$query&page=$page"), link, base)
        return out
    }
}

class ShufersalSource : ChainSource {
    override val key = "shufersal"
    override val title = "שופרסל"
    private val base = "https://prices.shufersal.co.il/FileObject/UpdateCategory"

    private fun category(kind: FileKind) = when (kind) {
        FileKind.PRICE -> 1
        FileKind.PRICE_FULL -> 2
        FileKind.PROMO -> 3
        FileKind.PROMO_FULL -> 4
        FileKind.STORES -> 5
    }

    private fun links(kind: FileKind, storeId: String) =
        Listings.shufersal(Http.text("$base?catID=${category(kind)}&storeId=$storeId&page=1"))

    override fun stores(): List<StoreRow> {
        val link = Listings.latest(links(FileKind.STORES, "0"), FileKind.STORES)
            ?: throw IOException("Shufersal store list not found")
        return downloadStores(link)
    }

    override fun latest(storeId: String, kind: FileKind): FeedLink? =
        Listings.latest(links(kind, normStoreId(storeId)), kind, storeId)
}

/** Carrefour (formerly Yeinot Bitan): one page per day, listing every file of that day. */
class CarrefourSource : ChainSource {
    override val key = "carrefour"
    override val title = "קרפור"
    private val base = "https://prices.carrefour.co.il/"
    private val pages = HashMap<String, List<FeedLink>>()

    /** Today's files, then yesterday's (early in the morning today's may not be out yet). */
    private fun days(): List<List<FeedLink>> {
        val today = LocalDate.now(ZoneId.of("Asia/Jerusalem"))
        return listOf(today, today.minusDays(1)).map { d ->
            val key = d.format(DateTimeFormatter.BASIC_ISO_DATE)
            synchronized(pages) {
                pages.getOrPut(key) {
                    val url = if (d == today) base else "$base?date=$key"
                    Listings.carrefour(Http.text(url), base)
                }
            }
        }
    }

    override fun stores(): List<StoreRow> {
        val link = days().firstNotNullOfOrNull { Listings.latest(it, FileKind.STORES) }
            ?: throw IOException("Carrefour store list not found")
        return downloadStores(link)
    }

    override fun latest(storeId: String, kind: FileKind): FeedLink? =
        days().firstNotNullOfOrNull { Listings.latest(it, kind, storeId) }

    /** Lists are cached per refresh; drop them so the next refresh sees new files. */
    fun clearCache() = synchronized(pages) { pages.clear() }
}

/**
 * Reads a chain's files from a SuperPrices server's mirror (see the server folder), which is
 * much faster than Shufersal's portal. Stores the server doesn't have come from the chain itself.
 */
class MirrorSource(private val direct: ChainSource, base: String) : ChainSource {
    override val key = direct.key
    override val title = direct.title
    private val base = base.trim().trimEnd('/')

    private fun link(store: String, kind: FileKind, storeId: String?): FeedLink? {
        if (System.currentTimeMillis() < downUntil) return null
        val info = try {
            JSONObject(Http.text("$base/api/files/$key/$store"))
        } catch (e: HttpStatusException) {
            return null // the server doesn't mirror this store
        } catch (e: IOException) {
            downUntil = System.currentTimeMillis() + 5 * 60_000 // unreachable: use the chains directly for a while
            return null
        }
        val name = when (kind) {
            FileKind.PRICE_FULL -> "pricefull"
            FileKind.PROMO_FULL -> "promofull"
            FileKind.PRICE -> "price"
            FileKind.PROMO -> "promo"
            FileKind.STORES -> "stores"
        }
        val f = info.optJSONObject(name) ?: return null
        return FeedLink(f.getString("url"), FeedFile(kind, "", storeId?.let(::normStoreId), f.getString("stamp")))
    }

    override fun stores(): List<StoreRow> =
        link("_chain", FileKind.STORES, null)?.let { downloadStores(it) } ?: direct.stores()

    override fun latest(storeId: String, kind: FileKind): FeedLink? =
        link(normStoreId(storeId), kind, storeId) ?: direct.latest(storeId, kind)

    override fun <T> download(link: FeedLink, block: (InputStream) -> T): T =
        if (link.url.startsWith(base)) Http.get(link.url, block = block) else direct.download(link, block)

    companion object {
        @Volatile private var downUntil = 0L
    }
}

object Chains {
    private val direct: List<ChainSource> = listOf(
        ShufersalSource(),
        CarrefourSource(),
        PublishedPricesSource("rami_levy", "רמי לוי", "7290058140886", "RamiLevi"),
        PublishedPricesSource("yohananof", "יוחננוף", "7290803800003", "yohananof"),
        PublishedPricesSource("osher_ad", "אושר עד", "7290103152017", "osherad"),
        PublishedPricesSource("tiv_taam", "טיב טעם", "7290873255550", "TivTaam"),
        PublishedPricesSource("dor_alon", "דור אלון", "7290492000005", "doralon"),
        PublishedPricesSource("keshet", "קשת טעמים", "7290785400000", "Keshet"),
        PublishedPricesSource("fresh_market", "פרש מרקט", "7290876100000", "freshmarket"),
        PublishedPricesSource("stop_market", "סטופ מרקט", "7290639000004", "Stop_Market"),
        PublishedPricesSource("politzer", "פוליצר", "7291059100008", "politzer"),
        PublishedPricesSource("salach_dabach", "סאלח דבאח", "7290526500006", "SalachD"),
        BinaSource("king_store", "קינג סטור", "7290058108879", "kingstore"),
        BinaSource("maayan_2000", "מעיין אלפיים", "7290058159628", "maayan2000"),
        BinaSource("zol_vebegadol", "זול ובגדול", "7290058173198", "zolvebegadol"),
        BinaSource("shuk_hair", "שוק העיר", "7290058148776", "shuk-hayir"),
        HaziHinamSource(),
        SuperPharmSource(),
        WoltSource(),
    )

    /** A SuperPrices server to sync through, or null to read the chains' portals directly. */
    @Volatile var serverUrl: String? = null

    val all: List<ChainSource>
        get() = serverUrl?.takeIf { it.isNotBlank() }?.let { url -> direct.map { MirrorSource(it, url) } } ?: direct

    fun get(key: String) = all.first { it.key == key }
    fun title(key: String) = direct.firstOrNull { it.key == key }?.title ?: key

    /** File lists are cached per refresh; drop them so the next refresh sees new files. */
    fun clearCaches() {
        direct.filterIsInstance<CarrefourSource>().forEach { it.clearCache() }
        direct.filterIsInstance<ListedSource>().forEach { it.clearCache() }
    }

    fun load(context: android.content.Context) {
        serverUrl = context.getSharedPreferences("settings", 0).getString("server", null)
    }

    fun save(context: android.content.Context, url: String?) {
        serverUrl = url?.trim()?.takeIf { it.isNotEmpty() }
        context.getSharedPreferences("settings", 0).edit().putString("server", serverUrl).apply()
    }
}
