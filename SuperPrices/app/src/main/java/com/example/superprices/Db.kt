package com.example.superprices

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

data class Store(
    val chain: String,
    val storeId: String,
    val name: String,
    val address: String,
    val city: String,
    val subChain: String,
    val selected: Boolean,
) {
    val key get() = "$chain/$storeId"
    val title get() = "${Chains.title(chain)} · $name"
}

data class MyStore(val store: Store, val priceStamp: String?, val promoStamp: String?, val itemCount: Int)

data class SearchHit(val code: String, val name: String, val manufacturer: String, val minPrice: Double, val stores: Int)

data class StorePrice(
    val store: Store,
    val price: Double,
    val unitPrice: Double?,
    val unitMeasure: String,
    val updated: String,
)

data class Promo(
    val store: Store,
    val id: String,
    val description: String,
    val start: String,
    val end: String,
    val club: String,
    val minQty: Double?,
    val discountedPrice: Double?,
    val itemCount: Int,
)

data class HistoryPoint(val storeKey: String, val at: String, val price: Double)

data class ListEntry(val code: String, val name: String, val qty: Double)

data class Watch(val code: String, val name: String, val target: Double?, val lastMin: Double?)

class Db(context: Context) : SQLiteOpenHelper(context, "prices.db", null, 1) {

    init { setWriteAheadLoggingEnabled(true) }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE stores(chain TEXT, store_id TEXT, name TEXT, address TEXT, city TEXT,
            sub_chain TEXT, PRIMARY KEY(chain, store_id))""")
        db.execSQL("""CREATE TABLE my_stores(chain TEXT, store_id TEXT, price_stamp TEXT, promo_stamp TEXT,
            PRIMARY KEY(chain, store_id))""")
        db.execSQL("""CREATE TABLE items(chain TEXT, store_id TEXT, code TEXT, name TEXT, manufacturer TEXT,
            price REAL, unit_price REAL, unit_measure TEXT, quantity TEXT, unit_qty TEXT, weighted INTEGER,
            updated TEXT, PRIMARY KEY(chain, store_id, code))""")
        db.execSQL("CREATE INDEX items_code ON items(code)")
        db.execSQL("CREATE TABLE history(chain TEXT, store_id TEXT, code TEXT, at TEXT, price REAL)")
        db.execSQL("CREATE INDEX history_code ON history(code)")
        db.execSQL("""CREATE TABLE promos(chain TEXT, store_id TEXT, promo_id TEXT, description TEXT,
            start TEXT, end_at TEXT, club TEXT, min_qty REAL, discounted_price REAL, discount_rate REAL,
            item_count INTEGER, PRIMARY KEY(chain, store_id, promo_id))""")
        db.execSQL("CREATE TABLE promo_items(chain TEXT, store_id TEXT, promo_id TEXT, code TEXT)")
        db.execSQL("CREATE INDEX promo_items_code ON promo_items(code)")
        db.execSQL("CREATE INDEX promo_items_promo ON promo_items(chain, store_id, promo_id)")
        db.execSQL("CREATE TABLE shopping_list(code TEXT PRIMARY KEY, name TEXT, qty REAL, added INTEGER)")
        db.execSQL("CREATE TABLE watch(code TEXT PRIMARY KEY, name TEXT, target REAL, last_min REAL)")
        db.execSQL("CREATE TABLE geo(key TEXT PRIMARY KEY, lat REAL, lon REAL, found INTEGER)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}

    // ---- Store directory ----

    fun replaceStores(chain: String, rows: List<StoreRow>, cityName: (StoreRow) -> String) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("stores", "chain=?", arrayOf(chain))
            val st = db.compileStatement("INSERT OR REPLACE INTO stores VALUES(?,?,?,?,?,?)")
            for (r in rows) {
                st.clearBindings()
                st.bindString(1, r.chain); st.bindString(2, r.storeId); st.bindString(3, r.name)
                st.bindString(4, r.address); st.bindString(5, cityName(r)); st.bindString(6, r.subChain)
                st.executeInsert()
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun storeCount(): Int = readableDatabase.rawQuery("SELECT COUNT(*) FROM stores", null).use {
        it.moveToFirst(); it.getInt(0)
    }

    private val storeCols = "s.chain, s.store_id, s.name, s.address, s.city, s.sub_chain, " +
        "(SELECT COUNT(*) FROM my_stores m WHERE m.chain=s.chain AND m.store_id=s.store_id)"

    private fun Cursor.store(at: Int = 0) = Store(
        getString(at), getString(at + 1), getString(at + 2), getString(at + 3), getString(at + 4),
        getString(at + 5), getInt(at + 6) > 0,
    )

    fun stores(): List<Store> = readableDatabase.rawQuery(
        "SELECT $storeCols FROM stores s ORDER BY s.city, s.chain, s.name", null,
    ).use { c -> buildList { while (c.moveToNext()) add(c.store()) } }

    fun store(chain: String, id: String): Store? = readableDatabase.rawQuery(
        "SELECT $storeCols FROM stores s WHERE s.chain=? AND s.store_id=?", arrayOf(chain, id),
    ).use { c -> if (c.moveToFirst()) c.store() else null }

    fun myStores(): List<MyStore> = readableDatabase.rawQuery(
        """SELECT $storeCols, m.price_stamp, m.promo_stamp,
           (SELECT COUNT(*) FROM items i WHERE i.chain=m.chain AND i.store_id=m.store_id)
           FROM my_stores m JOIN stores s ON s.chain=m.chain AND s.store_id=m.store_id ORDER BY s.chain, s.name""",
        null,
    ).use { c ->
        buildList { while (c.moveToNext()) add(MyStore(c.store(), c.getString(7), c.getString(8), c.getInt(9))) }
    }

    fun setSelected(chain: String, id: String, selected: Boolean) {
        val db = writableDatabase
        if (selected) {
            db.execSQL("INSERT OR IGNORE INTO my_stores(chain, store_id) VALUES(?,?)", arrayOf(chain, id))
            return
        }
        db.beginTransaction()
        try {
            val args = arrayOf(chain, id)
            for (t in listOf("my_stores", "items", "promos", "promo_items", "history")) {
                db.delete(t, "chain=? AND store_id=?", args)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    // ---- Prices ----

    /**
     * Replaces one store's prices with a new full file, read by [fill]. A price change is
     * kept in the history table, with the old price added first the first time an item changes.
     */
    fun replacePrices(chain: String, id: String, stamp: String, fill: ((PriceItem) -> Unit) -> Unit): Int {
        val db = writableDatabase
        val old = HashMap<String, Pair<Double, String>>()
        db.rawQuery("SELECT code, price, updated FROM items WHERE chain=? AND store_id=?", arrayOf(chain, id)).use { c ->
            while (c.moveToNext()) old[c.getString(0)] = c.getDouble(1) to c.getString(2)
        }
        val hasHistory = HashSet<String>()
        db.rawQuery("SELECT DISTINCT code FROM history WHERE chain=? AND store_id=?", arrayOf(chain, id)).use { c ->
            while (c.moveToNext()) hasHistory += c.getString(0)
        }
        val today = stamp.take(8).let { "${it.take(4)}-${it.substring(4, 6)}-${it.substring(6, 8)}T00:00" }
        var n = 0
        db.beginTransaction()
        try {
            db.delete("items", "chain=? AND store_id=?", arrayOf(chain, id))
            val ins = db.compileStatement("INSERT OR REPLACE INTO items VALUES(?,?,?,?,?,?,?,?,?,?,?,?)")
            val hist = db.compileStatement("INSERT INTO history VALUES(?,?,?,?,?)")
            fill { item ->
                n++
                ins.clearBindings()
                ins.bindString(1, chain); ins.bindString(2, id); ins.bindString(3, item.code)
                ins.bindString(4, item.name); ins.bindString(5, item.manufacturer); ins.bindDouble(6, item.price)
                if (item.unitPrice != null) ins.bindDouble(7, item.unitPrice) else ins.bindNull(7)
                ins.bindString(8, item.unitMeasure); ins.bindString(9, item.quantity); ins.bindString(10, item.unitQty)
                ins.bindLong(11, if (item.weighted) 1 else 0); ins.bindString(12, item.updated)
                ins.executeInsert()
                val before = old[item.code]
                if (before != null && kotlin.math.abs(before.first - item.price) > 0.001) {
                    fun addHistory(at: String, price: Double) {
                        hist.clearBindings()
                        hist.bindString(1, chain); hist.bindString(2, id); hist.bindString(3, item.code)
                        hist.bindString(4, at); hist.bindDouble(5, price)
                        hist.executeInsert()
                    }
                    if (item.code !in hasHistory) addHistory(before.second.ifEmpty { today }, before.first)
                    addHistory(item.updated.ifEmpty { today }, item.price)
                }
            }
            db.execSQL("UPDATE my_stores SET price_stamp=? WHERE chain=? AND store_id=?", arrayOf(stamp, chain, id))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return n
    }

    fun replacePromos(chain: String, id: String, stamp: String, fill: ((PromoRow) -> Unit) -> Unit): Int {
        val db = writableDatabase
        var n = 0
        db.beginTransaction()
        try {
            val args = arrayOf(chain, id)
            db.delete("promos", "chain=? AND store_id=?", args)
            db.delete("promo_items", "chain=? AND store_id=?", args)
            val ins = db.compileStatement("INSERT OR REPLACE INTO promos VALUES(?,?,?,?,?,?,?,?,?,?,?)")
            val item = db.compileStatement("INSERT INTO promo_items VALUES(?,?,?,?)")
            fill { p ->
                n++
                ins.clearBindings()
                ins.bindString(1, chain); ins.bindString(2, id); ins.bindString(3, p.id)
                ins.bindString(4, p.description); ins.bindString(5, p.start); ins.bindString(6, p.end)
                ins.bindString(7, p.club)
                p.minQty?.let { ins.bindDouble(8, it) } ?: ins.bindNull(8)
                p.discountedPrice?.let { ins.bindDouble(9, it) } ?: ins.bindNull(9)
                p.discountRate?.let { ins.bindDouble(10, it) } ?: ins.bindNull(10)
                ins.bindLong(11, p.codes.size.toLong())
                ins.executeInsert()
                for (code in p.codes) {
                    item.clearBindings()
                    item.bindString(1, chain); item.bindString(2, id); item.bindString(3, p.id); item.bindString(4, code)
                    item.executeInsert()
                }
            }
            db.execSQL("UPDATE my_stores SET promo_stamp=? WHERE chain=? AND store_id=?", arrayOf(stamp, chain, id))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return n
    }

    // ---- Search and products ----

    fun search(query: String, limit: Int = 150): List<SearchHit> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        val sql = StringBuilder(
            "SELECT code, MAX(name), MAX(manufacturer), MIN(price), COUNT(*) FROM items WHERE ",
        )
        val args = ArrayList<String>()
        if (q.all { it.isDigit() }) {
            sql.append("(code=? OR ltrim(code,'0')=ltrim(?,'0'))")
            args += q; args += q
        } else {
            val words = q.split(Regex("\\s+"))
            sql.append(words.joinToString(" AND ") { "(name LIKE ? OR manufacturer LIKE ?)" })
            for (w in words) { args += "%$w%"; args += "%$w%" }
        }
        sql.append(" GROUP BY code ORDER BY COUNT(*) DESC, MIN(price) LIMIT $limit")
        return readableDatabase.rawQuery(sql.toString(), args.toTypedArray()).use { c ->
            buildList { while (c.moveToNext()) add(SearchHit(c.getString(0), c.getString(1), c.getString(2), c.getDouble(3), c.getInt(4))) }
        }
    }

    fun productName(code: String): String? = readableDatabase.rawQuery(
        "SELECT name FROM items WHERE code=? LIMIT 1", arrayOf(code),
    ).use { if (it.moveToFirst()) it.getString(0) else null }

    fun prices(code: String): List<StorePrice> = readableDatabase.rawQuery(
        """SELECT $storeCols, i.price, i.unit_price, i.unit_measure, i.updated
           FROM items i JOIN stores s ON s.chain=i.chain AND s.store_id=i.store_id
           WHERE i.code=? ORDER BY i.price""",
        arrayOf(code),
    ).use { c ->
        buildList {
            while (c.moveToNext()) add(
                StorePrice(c.store(), c.getDouble(7), if (c.isNull(8)) null else c.getDouble(8), c.getString(9), c.getString(10)),
            )
        }
    }

    fun history(code: String): List<HistoryPoint> = readableDatabase.rawQuery(
        "SELECT chain || '/' || store_id, at, price FROM history WHERE code=? ORDER BY at", arrayOf(code),
    ).use { c -> buildList { while (c.moveToNext()) add(HistoryPoint(c.getString(0), c.getString(1), c.getDouble(2))) } }

    /** Prices of the given barcodes in every chosen store: store key → (code → price). */
    fun pricesFor(codes: Collection<String>): Map<String, Map<String, Double>> {
        if (codes.isEmpty()) return emptyMap()
        val out = HashMap<String, HashMap<String, Double>>()
        codes.chunked(500).forEach { chunk ->
            readableDatabase.rawQuery(
                "SELECT chain || '/' || store_id, code, price FROM items WHERE code IN (${chunk.joinToString(",") { "?" }})",
                chunk.toTypedArray(),
            ).use { c -> while (c.moveToNext()) out.getOrPut(c.getString(0)) { HashMap() }[c.getString(1)] = c.getDouble(2) }
        }
        return out
    }

    // ---- Promotions ----

    private val promoCols = "p.promo_id, p.description, p.start, p.end_at, p.club, p.min_qty, p.discounted_price, p.item_count"

    private fun Cursor.promo() = Promo(
        store(), getString(7), getString(8), getString(9), getString(10), getString(11),
        if (isNull(12)) null else getDouble(12), if (isNull(13)) null else getDouble(13), getInt(14),
    )

    fun promos(query: String, now: String, storeKey: String?, limit: Int = 300): List<Promo> {
        val args = arrayListOf(now)
        val where = StringBuilder("p.end_at >= ?")
        query.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.forEach {
            where.append(" AND p.description LIKE ?"); args += "%$it%"
        }
        if (storeKey != null) {
            where.append(" AND p.chain=? AND p.store_id=?")
            args += storeKey.substringBefore('/'); args += storeKey.substringAfter('/')
        }
        return readableDatabase.rawQuery(
            """SELECT $storeCols, $promoCols FROM promos p JOIN stores s ON s.chain=p.chain AND s.store_id=p.store_id
               WHERE $where ORDER BY p.start DESC LIMIT $limit""",
            args.toTypedArray(),
        ).use { c -> buildList { while (c.moveToNext()) add(c.promo()) } }
    }

    fun promosForItem(code: String, now: String): List<Promo> = readableDatabase.rawQuery(
        """SELECT $storeCols, $promoCols FROM promo_items pi
           JOIN promos p ON p.chain=pi.chain AND p.store_id=pi.store_id AND p.promo_id=pi.promo_id
           JOIN stores s ON s.chain=p.chain AND s.store_id=p.store_id
           WHERE pi.code=? AND p.end_at >= ? AND p.start <= ? ORDER BY p.end_at""",
        arrayOf(code, now, now),
    ).use { c -> buildList { while (c.moveToNext()) add(c.promo()) } }

    /** Barcodes from [codes] with an active promotion, per store key. */
    fun promoCodes(codes: Collection<String>, now: String): Map<String, Set<String>> {
        if (codes.isEmpty()) return emptyMap()
        val out = HashMap<String, HashSet<String>>()
        codes.chunked(500).forEach { chunk ->
            readableDatabase.rawQuery(
                """SELECT DISTINCT pi.chain || '/' || pi.store_id, pi.code FROM promo_items pi
                   JOIN promos p ON p.chain=pi.chain AND p.store_id=pi.store_id AND p.promo_id=pi.promo_id
                   WHERE p.end_at >= ? AND p.start <= ? AND pi.code IN (${chunk.joinToString(",") { "?" }})""",
                arrayOf(now, now, *chunk.toTypedArray()),
            ).use { c -> while (c.moveToNext()) out.getOrPut(c.getString(0)) { HashSet() } += c.getString(1) }
        }
        return out
    }

    /** The items of one promotion with their price in that store, when known. */
    fun promoItems(p: Promo, limit: Int = 300): List<Triple<String, String?, Double?>> = readableDatabase.rawQuery(
        """SELECT pi.code, i.name, i.price FROM promo_items pi
           LEFT JOIN items i ON i.chain=pi.chain AND i.store_id=pi.store_id AND i.code=pi.code
           WHERE pi.chain=? AND pi.store_id=? AND pi.promo_id=? LIMIT $limit""",
        arrayOf(p.store.chain, p.store.storeId, p.id),
    ).use { c ->
        buildList {
            while (c.moveToNext()) add(Triple(c.getString(0), c.getString(1), if (c.isNull(2)) null else c.getDouble(2)))
        }
    }

    // ---- Shopping list ----

    fun list(): List<ListEntry> = readableDatabase.rawQuery(
        "SELECT code, name, qty FROM shopping_list ORDER BY added", null,
    ).use { c -> buildList { while (c.moveToNext()) add(ListEntry(c.getString(0), c.getString(1), c.getDouble(2))) } }

    fun addToList(code: String, name: String) {
        // No UPSERT: Android 8-10 ship SQLite versions older than 3.24.
        val db = writableDatabase
        db.execSQL("UPDATE shopping_list SET qty=qty+1 WHERE code=?", arrayOf(code))
        db.execSQL(
            "INSERT OR IGNORE INTO shopping_list VALUES(?,?,1,?)",
            arrayOf<Any>(code, name, System.currentTimeMillis()),
        )
    }

    fun setQty(code: String, qty: Double) {
        if (qty <= 0) writableDatabase.delete("shopping_list", "code=?", arrayOf(code))
        else writableDatabase.execSQL("UPDATE shopping_list SET qty=? WHERE code=?", arrayOf<Any>(qty, code))
    }

    fun clearList() { writableDatabase.delete("shopping_list", null, null) }

    // ---- Price watch ----

    fun watches(): List<Watch> = readableDatabase.rawQuery(
        "SELECT code, name, target, last_min FROM watch ORDER BY name", null,
    ).use { c ->
        buildList {
            while (c.moveToNext()) add(
                Watch(c.getString(0), c.getString(1), if (c.isNull(2)) null else c.getDouble(2), if (c.isNull(3)) null else c.getDouble(3)),
            )
        }
    }

    fun setWatch(code: String, name: String, target: Double?, currentMin: Double?) {
        val v = ContentValues().apply {
            put("code", code); put("name", name)
            if (target != null) put("target", target) else putNull("target")
            if (currentMin != null) put("last_min", currentMin) else putNull("last_min")
        }
        writableDatabase.insertWithOnConflict("watch", null, v, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun updateWatchMin(code: String, min: Double) {
        writableDatabase.execSQL("UPDATE watch SET last_min=? WHERE code=?", arrayOf<Any>(min, code))
    }

    fun removeWatch(code: String) { writableDatabase.delete("watch", "code=?", arrayOf(code)) }

    fun minPrice(code: String): Pair<Double, String>? = readableDatabase.rawQuery(
        "SELECT price, chain || '/' || store_id FROM items WHERE code=? ORDER BY price LIMIT 1", arrayOf(code),
    ).use { if (it.moveToFirst()) it.getDouble(0) to it.getString(1) else null }

    // ---- Geocoding cache ----

    /** null = never looked up; Pair(NaN, NaN) = looked up and not found. */
    fun geo(key: String): Pair<Double, Double>? = readableDatabase.rawQuery(
        "SELECT lat, lon, found FROM geo WHERE key=?", arrayOf(key),
    ).use { if (!it.moveToFirst()) null else if (it.getInt(2) == 0) Double.NaN to Double.NaN else it.getDouble(0) to it.getDouble(1) }

    fun putGeo(key: String, latLon: Pair<Double, Double>?) {
        val v = ContentValues().apply {
            put("key", key); put("lat", latLon?.first ?: 0.0); put("lon", latLon?.second ?: 0.0)
            put("found", if (latLon != null) 1 else 0)
        }
        writableDatabase.insertWithOnConflict("geo", null, v, SQLiteDatabase.CONFLICT_REPLACE)
    }
}
