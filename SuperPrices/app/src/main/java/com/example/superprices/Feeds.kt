package com.example.superprices

import org.xmlpull.v1.XmlPullParser
import java.io.BufferedInputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.io.Reader
import java.util.zip.GZIPInputStream
import java.util.zip.ZipInputStream

/** The file types every chain publishes under the 2014 Food Act. */
enum class FileKind { PRICE_FULL, PROMO_FULL, PRICE, PROMO, STORES }

/** A parsed file name such as `PriceFull7290027600007-001-001-20261106-043100`. */
data class FeedFile(val kind: FileKind, val chainId: String, val storeId: String?, val stamp: String)

/** A file in a chain's listing: where to download it and what it is. */
data class FeedLink(val url: String, val file: FeedFile)

data class StoreRow(
    val chain: String,
    val storeId: String,
    val name: String,
    val address: String,
    val cityCode: Int?,
    val cityRaw: String,
    val subChain: String,
)

data class PriceItem(
    val code: String,
    val name: String,
    val manufacturer: String,
    val price: Double,
    val unitPrice: Double?,
    val unitMeasure: String,
    val quantity: String,
    val unitQty: String,
    val weighted: Boolean,
    val updated: String,
)

data class PromoRow(
    val id: String,
    val description: String,
    val start: String,
    val end: String,
    val club: String,
    val minQty: Double?,
    val discountedPrice: Double?,
    val discountRate: Double?,
    val codes: List<String>,
)

/** Store ids are written "002" in some files and "2" in others. */
fun normStoreId(id: String): String = id.trim().trimStart('0').ifEmpty { "0" }

object FeedFiles {
    private val RE = Regex(
        // Shufersal's store list is named like "Stores7290027600007-000-20261106-020" (3-digit time),
        // Hazi Hinam's "StoresFull…", and Keshet leaves out the sub-chain:
        // "PriceFull7290785400000-002-202611090010" is store 002.
        "^(PriceFull|PromoFull|StoresFull|Price|Promo|Stores)(\\d{13})-(\\d+)(?:-(\\d+))?-(\\d{8})-?(\\d{2,6})",
        RegexOption.IGNORE_CASE,
    )

    fun parse(name: String): FeedFile? {
        val m = RE.find(name.substringAfterLast('/')) ?: return null
        val kind = when (m.groupValues[1].lowercase()) {
            "pricefull" -> FileKind.PRICE_FULL
            "promofull" -> FileKind.PROMO_FULL
            "price" -> FileKind.PRICE
            "promo" -> FileKind.PROMO
            else -> FileKind.STORES
        }
        // With no sub-chain part, the first number is the store.
        val store = m.groupValues[4].ifEmpty { m.groupValues[3] }.let(::normStoreId)
        return FeedFile(kind, m.groupValues[2], if (kind == FileKind.STORES) null else store,
            m.groupValues[5] + m.groupValues[6].padEnd(6, '0'))
    }
}

object Listings {
    private val SHUFERSAL_LINK = Regex("href=\"(https://pricesprod[^\"]+)\"")
    private val CARREFOUR_PATH = Regex("const path = '([^']*)'")
    private val CARREFOUR_NAME = Regex("\"name\":\"([^\"]+)\"")

    /** Shufersal's file table: each row links to a signed blob URL that names the file. */
    fun shufersal(html: String): List<FeedLink> =
        SHUFERSAL_LINK.findAll(html).mapNotNull { m ->
            val url = m.groupValues[1].replace("&amp;", "&")
            val name = url.substringBefore('?').substringAfterLast('/').substringBefore('.')
            FeedFiles.parse(name)?.let { FeedLink(url, it) }
        }.toList()

    /** Carrefour's page embeds the day's folder and a JSON list of file names. */
    fun carrefour(html: String, base: String): List<FeedLink> {
        val path = CARREFOUR_PATH.find(html)?.groupValues?.get(1) ?: return emptyList()
        val files = html.substringAfter("const files = ", "")
        return CARREFOUR_NAME.findAll(files).mapNotNull { m ->
            val name = m.groupValues[1]
            FeedFiles.parse(name)?.let { FeedLink("$base$path/$name", it) }
        }.toList()
    }

    private val PUBLISHED_NAME = Regex("\"fname\":\"([^\"]+)\"")
    private val BINA_NAME = Regex("\"FileNm\":\"([^\"]+)\"")

    /** The shared portal's JSON file list (url.publishedprices.co.il). */
    fun publishedPrices(json: String, base: String): List<FeedLink> =
        PUBLISHED_NAME.findAll(json).mapNotNull { m ->
            val name = m.groupValues[1]
            FeedFiles.parse(name)?.let { FeedLink("$base/file/d/${java.net.URLEncoder.encode(name, "UTF-8")}", it) }
        }.distinctBy { it.url }.toList()

    /** binaprojects.com's JSON list; each link is a page that returns the file's real address. */
    fun bina(json: String, base: String): List<FeedLink> =
        BINA_NAME.findAll(json).mapNotNull { m ->
            val name = m.groupValues[1]
            FeedFiles.parse(name)?.let { FeedLink("$base/Download.aspx?FileNm=${java.net.URLEncoder.encode(name, "UTF-8")}", it) }
        }.toList()

    /** Links in a page whose address (group 1 of [pattern]) ends with the file's name. */
    fun links(html: String, pattern: Regex, prefix: String = ""): List<FeedLink> =
        pattern.findAll(html).mapNotNull { m ->
            val href = m.groupValues[1].replace("&amp;", "&")
            FeedFiles.parse(href.substringBefore('?').substringAfterLast('/').substringBefore('.'))
                ?.let { FeedLink(prefix + href, it) }
        }.toList()

    /** The newest file of [kind], for one store or (for store lists) the whole chain. */
    fun latest(links: List<FeedLink>, kind: FileKind, storeId: String? = null): FeedLink? =
        links.filter { it.file.kind == kind && (storeId == null || it.file.storeId == normStoreId(storeId)) }
            .maxByOrNull { it.file.stamp }
}

object XmlFeeds {
    /**
     * Turns a downloaded file into text. Files may be gzip or zip compressed, and are
     * UTF-8 or UTF-16 (Carrefour's store list is UTF-16 with a byte-order mark).
     */
    fun reader(raw: InputStream): Reader {
        var input: InputStream = BufferedInputStream(raw, 64 * 1024)
        input.mark(4)
        val b0 = input.read(); val b1 = input.read()
        input.reset()
        if (b0 == 0x1f && b1 == 0x8b) {
            input = BufferedInputStream(GZIPInputStream(input, 64 * 1024), 64 * 1024)
        } else if (b0 == 'P'.code && b1 == 'K'.code) {
            val zip = ZipInputStream(input)
            zip.nextEntry ?: error("Empty zip file")
            input = BufferedInputStream(zip, 64 * 1024)
        }
        input.mark(4)
        val c0 = input.read(); val c1 = input.read(); val c2 = input.read()
        input.reset()
        val charset = when {
            c0 == 0xFF && c1 == 0xFE -> { input.skip(2); Charsets.UTF_16LE }
            c0 == 0xFE && c1 == 0xFF -> { input.skip(2); Charsets.UTF_16BE }
            c0 == 0xEF && c1 == 0xBB && c2 == 0xBF -> { input.skip(3); Charsets.UTF_8 }
            c0 > 0 && c1 == 0 -> Charsets.UTF_16LE
            c0 == 0 && c1 > 0 -> Charsets.UTF_16BE
            else -> Charsets.UTF_8
        }
        return InputStreamReader(input, charset)
    }

    fun parseStores(p: XmlPullParser, reader: Reader, chain: String): List<StoreRow> {
        val out = ArrayList<StoreRow>()
        var subChain = ""
        walk(p, reader, "store", null, outside = { k, v -> if (k == "subchainname") subChain = v }) { f, _ ->
            val id = f["storeid"].orEmpty()
            if (id.isNotBlank()) {
                val city = f["city"].orEmpty()
                out += StoreRow(
                    chain = chain,
                    storeId = normStoreId(id),
                    name = f["storename"].orEmpty().squash(),
                    address = f["address"].orEmpty().squash(),
                    cityCode = city.toIntOrNull(),
                    cityRaw = city,
                    subChain = subChain,
                )
            }
        }
        return out
    }

    fun parsePrices(p: XmlPullParser, reader: Reader, onItem: (PriceItem) -> Unit): Int {
        var n = 0
        walk(p, reader, "item", null) { f, _ ->
            val code = f["itemcode"].orEmpty()
            val price = f["itemprice"]?.toDoubleOrNull()
            if (code.isNotBlank() && price != null) {
                n++
                onItem(
                    PriceItem(
                        code = code,
                        name = f["itemname"].orEmpty().squash(),
                        manufacturer = (f["manufacturername"] ?: f["manufacturename"]).orEmpty().squash(),
                        price = price,
                        unitPrice = f["unitofmeasureprice"]?.toDoubleOrNull(),
                        unitMeasure = f["unitofmeasure"].orEmpty().squash(),
                        quantity = f["quantity"].orEmpty(),
                        unitQty = f["unitqty"].orEmpty().squash(),
                        weighted = f["bisweighted"] == "1",
                        updated = dateTime(f["priceupdatedate"] ?: f["priceupdatetime"], null, false),
                    ),
                )
            }
        }
        return n
    }

    fun parsePromos(p: XmlPullParser, reader: Reader, onPromo: (PromoRow) -> Unit): Int {
        var n = 0
        walk(p, reader, "promotion", "promotionitem") { f, items ->
            val id = f["promotionid"].orEmpty()
            if (id.isNotBlank()) {
                n++
                val first = items.firstOrNull() ?: f
                val codes = items.mapNotNull { it["itemcode"]?.takeIf(String::isNotBlank) }
                    .ifEmpty { listOfNotNull(f["itemcode"]?.takeIf(String::isNotBlank)) }
                onPromo(
                    PromoRow(
                        id = id,
                        description = f["promotiondescription"].orEmpty().squash(),
                        start = dateTime(f["promotionstartdatetime"] ?: f["promotionstartdate"], f["promotionstarthour"], false),
                        end = dateTime(f["promotionenddatetime"] ?: f["promotionenddate"], f["promotionendhour"], true),
                        club = f["clubid"].orEmpty().squash(),
                        minQty = first["minqty"]?.toDoubleOrNull(),
                        discountedPrice = first["discountedprice"]?.toDoubleOrNull(),
                        discountRate = first["discountrate"]?.toDoubleOrNull(),
                        codes = codes.distinct(),
                    ),
                )
            }
        }
        return n
    }

    /** Dates as sortable `yyyy-MM-ddTHH:mm` text. */
    fun dateTime(date: String?, hour: String?, endOfDay: Boolean): String {
        val d = date?.trim()?.replace(' ', 'T').orEmpty()
        if (d.length < 10) return ""
        if (d.length >= 16 && d[10] == 'T') return d.substring(0, 16)
        val h = hour?.trim()?.take(5)?.takeIf { it.length == 5 && it[2] == ':' }
        return d.substring(0, 10) + "T" + (h ?: if (endOfDay) "23:59" else "00:00")
    }

    private fun String.squash() = trim().replace(Regex("\\s+"), " ")

    /**
     * Streams through the document and calls [onRecord] for each element named [record]
     * (compared without case), with its leaf values. Leaves inside elements named [nested]
     * are collected separately, one map per nested element. Leaves outside any record go
     * to [outside].
     */
    private fun walk(
        p: XmlPullParser,
        reader: Reader,
        record: String,
        nested: String?,
        outside: (String, String) -> Unit = { _, _ -> },
        onRecord: (Map<String, String>, List<Map<String, String>>) -> Unit,
    ) {
        p.setInput(reader)
        val text = StringBuilder()
        var inRecord = false
        var fields = HashMap<String, String>()
        var nestedList = ArrayList<Map<String, String>>()
        var current: HashMap<String, String>? = null
        var leaf: String? = null
        var ev = p.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            when (ev) {
                XmlPullParser.START_TAG -> {
                    val n = p.name.lowercase()
                    if (!inRecord && n == record) {
                        inRecord = true; fields = HashMap(); nestedList = ArrayList()
                    } else if (inRecord && n == nested) {
                        current = HashMap()
                    }
                    leaf = n
                    text.setLength(0)
                }
                XmlPullParser.TEXT -> if (leaf != null) text.append(p.text)
                XmlPullParser.END_TAG -> {
                    val n = p.name.lowercase()
                    if (leaf == n && n != record && n != nested) {
                        val v = text.toString().trim()
                        when {
                            !inRecord -> outside(n, v)
                            current != null -> current[n] = v
                            else -> fields[n] = v
                        }
                    }
                    leaf = null
                    if (inRecord && n == nested && current != null) {
                        nestedList.add(current); current = null
                    } else if (inRecord && n == record) {
                        onRecord(fields, nestedList); inRecord = false
                    }
                }
            }
            ev = p.next()
        }
    }
}
