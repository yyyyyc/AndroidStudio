package com.example.superprices

import android.content.Context
import android.util.Xml
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

data class PriceAlert(val code: String, val name: String, val price: Double, val storeTitle: String, val previous: Double?)

data class RefreshReport(val updated: Int, val failed: List<String>, val alerts: List<PriceAlert>)

data class NearStore(val store: Store, val km: Double?)

class Repository private constructor(private val context: Context) {
    val db = Db(context)

    init { Chains.load(context) }

    companion object {
        @Volatile private var instance: Repository? = null
        fun get(context: Context) = instance ?: synchronized(this) {
            instance ?: Repository(context.applicationContext).also { instance = it }
        }

        /** Most stores to keep prices for; each one is ~15k items. */
        const val MAX_STORES = 12

        fun now(): String = LocalDateTime.now(ZoneId.of("Asia/Jerusalem"))
            .format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm"))
    }

    /** Downloads one chain's store list; false if it failed. */
    fun refreshChainStores(chain: ChainSource): Boolean = runCatching {
        val rows = chain.stores()
        if (rows.isNotEmpty()) db.replaceStores(chain.key, rows) { Cities.name(context, it.cityCode, it.cityRaw) }
        if (rows.isEmpty()) android.util.Log.w("SuperPrices", "Store list empty: ${chain.key}")
        rows.isNotEmpty()
    }.onFailure { android.util.Log.w("SuperPrices", "Store list failed: ${chain.key}", it) }.getOrDefault(false)

    /**
     * Downloads the newest full price and promotion files of every chosen store, skipping
     * files already loaded, then checks the watch list for price drops.
     */
    fun refreshMyStores(progress: (String, Float) -> Unit): RefreshReport {
        Chains.clearCaches()
        val mine = db.myStores()
        val failed = java.util.Collections.synchronizedList(ArrayList<String>())
        val updated = java.util.concurrent.atomic.AtomicInteger()
        val done = java.util.concurrent.atomic.AtomicInteger()
        val total = mine.size.coerceAtLeast(1)
        progress("מוריד ${mine.size} חנויות…", 0f)
        // A few stores at a time: Shufersal's listing pages are slow to answer, not to download.
        val pool = java.util.concurrent.Executors.newFixedThreadPool(3)
        try {
            mine.map { m ->
                pool.submit {
                    val s = m.store
                    val chain = Chains.get(s.chain)
                    try {
                        val price = chain.latest(s.storeId, FileKind.PRICE_FULL)
                        if (price != null && price.file.stamp != m.priceStamp) {
                            chain.download(price) { input ->
                                db.replacePrices(s.chain, s.storeId, price.file.stamp) { put ->
                                    XmlFeeds.parsePrices(Xml.newPullParser(), XmlFeeds.reader(input), put)
                                }
                            }
                            updated.incrementAndGet()
                        }
                        val promo = chain.latest(s.storeId, FileKind.PROMO_FULL)
                        if (promo != null && promo.file.stamp != m.promoStamp) {
                            chain.download(promo) { input ->
                                db.replacePromos(s.chain, s.storeId, promo.file.stamp) { put ->
                                    XmlFeeds.parsePromos(Xml.newPullParser(), XmlFeeds.reader(input), put)
                                }
                            }
                        }
                        if (price == null) failed += s.title
                    } catch (e: Exception) {
                        android.util.Log.w("SuperPrices", "Refresh failed: ${s.key}", e)
                        failed += s.title
                    }
                    val n = done.incrementAndGet()
                    progress("עודכנו $n מתוך ${mine.size} (${s.title})", n.toFloat() / total)
                }
            }.forEach { it.get() }
        } finally {
            pool.shutdown()
        }
        progress("בודק ירידות מחיר", 1f)
        return RefreshReport(updated.get(), failed.toList(), checkWatches())
    }

    /** A watched item alerts when it reaches its target price, or (with no target) gets cheaper. */
    fun checkWatches(): List<PriceAlert> {
        val alerts = ArrayList<PriceAlert>()
        for (w in db.watches()) {
            val (min, storeKey) = db.minPrice(w.code) ?: continue
            val prev = w.lastMin
            val hit = if (w.target != null) min <= w.target && (prev == null || prev > w.target)
            else prev != null && min < prev - 0.005
            if (hit) {
                val store = db.store(storeKey.substringBefore('/'), storeKey.substringAfter('/'))
                alerts += PriceAlert(w.code, w.name, min, store?.title ?: storeKey, prev)
            }
            if (prev == null || kotlin.math.abs(prev - min) > 0.001) db.updateWatchMin(w.code, min)
        }
        return alerts
    }

    /**
     * Stores sorted by distance from [here]. Cities are located first (one lookup per city,
     * cached), then the stores in the nearest cities are located by street address.
     */
    fun nearby(here: Pair<Double, Double>, progress: (String) -> Unit): List<NearStore> {
        val stores = db.stores()
        val cities = stores.map { it.city }.filter { it.isNotBlank() }.distinct()
        val cityPos = HashMap<String, Pair<Double, Double>>()
        cities.forEachIndexed { i, city ->
            val pos = cached("city:$city") { Geo.lookup(context, "$city, ישראל") }
            if (pos != null) cityPos[city] = pos
            if (i % 10 == 0) progress("מאתר ערים ${i + 1}/${cities.size}")
        }
        val byCity = stores.groupBy { it.city }
        val nearCities = cityPos.entries.sortedBy { Geo.distanceKm(here, it.value) }
        val chosen = ArrayList<Store>()
        for ((city, _) in nearCities) {
            if (chosen.size >= 30) break
            chosen += byCity[city].orEmpty()
        }
        val out = chosen.mapIndexed { i, s ->
            progress("מאתר סניפים ${i + 1}/${chosen.size}")
            val pos = cached("store:${s.key}") { Geo.lookup(context, "${s.address}, ${s.city}") } ?: cityPos[s.city]
            NearStore(s, pos?.let { Geo.distanceKm(here, it) })
        }
        return out.sortedBy { it.km ?: Double.MAX_VALUE }
    }

    private fun cached(key: String, look: () -> Pair<Double, Double>?): Pair<Double, Double>? {
        val hit = db.geo(key)
        if (hit != null) return hit.takeUnless { it.first.isNaN() }
        val found = runCatching(look).getOrElse { return null } // failed lookups are retried next time
        db.putGeo(key, found)
        return found
    }
}
