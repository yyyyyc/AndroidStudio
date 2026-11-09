package com.example.superprices

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class Tab { SEARCH, LIST, PROMOS, WATCH, STORES }
enum class ScanFor { SEARCH, LIST }

data class ProductDetail(
    val code: String,
    val name: String,
    val prices: List<StorePrice>,
    val promos: List<Promo>,
    val history: List<HistoryPoint>,
    val storeTitles: Map<String, String>,
    val qtyInList: Double,
    val watch: Watch?,
)

data class WatchRow(val watch: Watch, val min: Double?, val storeTitle: String?)

data class BasketView(val entries: List<ListEntry>, val result: BasketResult, val titles: Map<String, String>, val promos: Map<String, Set<String>>)

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = Repository.get(app)
    private val db = repo.db

    var tab by mutableStateOf(Tab.SEARCH)
    var snack by mutableStateOf<String?>(null)

    // Search
    var query by mutableStateOf("")
        private set
    var hits by mutableStateOf<List<SearchHit>>(emptyList())
    var product by mutableStateOf<ProductDetail?>(null)
    var scanning by mutableStateOf<ScanFor?>(null)
    private var searchJob: Job? = null

    // Shopping list
    var basket by mutableStateOf<BasketView?>(null)

    // Promotions
    var promoQuery by mutableStateOf("")
        private set
    var promoStore by mutableStateOf<String?>(null)
        private set
    var promos by mutableStateOf<List<Promo>>(emptyList())
    var openPromo by mutableStateOf<Pair<Promo, List<Triple<String, String?, Double?>>>?>(null)
    private var promoJob: Job? = null

    // Price watch
    var watches by mutableStateOf<List<WatchRow>>(emptyList())

    // Stores
    var stores by mutableStateOf<List<Store>>(emptyList())
    var myStores by mutableStateOf<List<MyStore>>(emptyList())
    var near by mutableStateOf<List<NearStore>?>(null)
    var storeBusy by mutableStateOf<String?>(null)

    // Refresh
    var refreshing by mutableStateOf(false)
    var refreshMsg by mutableStateOf<String?>(null)
    var refreshProgress by mutableStateOf(0f)
    /** Queued but not started (e.g. Android is waiting for a network it trusts). */
    var refreshWaiting by mutableStateOf(false)

    init {
        RefreshWorker.scheduleDaily(app)
        viewModelScope.launch {
            reloadAll()
            if (withContext(Dispatchers.IO) { db.storeCount() } == 0) loadStoreDirectory()
        }
        viewModelScope.launch {
            WorkManager.getInstance(app).getWorkInfosForUniqueWorkFlow(RefreshWorker.NOW).collect { infos ->
                val wanted = pendingWork
                val info = (if (wanted != null) infos.firstOrNull { it.id == wanted } else infos.lastOrNull())
                    ?: return@collect // the previous run, reported before ours was queued
                val running = info.state == WorkInfo.State.RUNNING || info.state == WorkInfo.State.ENQUEUED
                refreshWaiting = info.state == WorkInfo.State.ENQUEUED
                if (running) {
                    refreshMsg = info.progress.getString(RefreshWorker.KEY_MSG) ?: "ממתין לרשת…"
                    refreshProgress = info.progress.getFloat(RefreshWorker.KEY_PROGRESS, 0f)
                } else if (refreshing) {
                    snack = info.outputData.getString(RefreshWorker.KEY_MSG)
                        ?: if (info.state == WorkInfo.State.FAILED) "העדכון נכשל" else "העדכון הסתיים"
                    reloadAll()
                }
                refreshing = running
                if (!running) {
                    pendingWork = null
                    if (refreshAgain) { refreshAgain = false; refreshPrices() }
                }
            }
        }
    }

    private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }

    fun reloadAll() {
        viewModelScope.launch {
            loadStores()
            runSearch(query)
            loadBasket()
            loadPromos()
            loadWatches()
            product?.let { openProduct(it.code) }
        }
    }

    fun refreshPrices() {
        if (myStores.isEmpty()) {
            snack = "בחר קודם חנויות בלשונית החנויות"
            tab = Tab.STORES
            return
        }
        // A running refresh runs again when it ends; one still waiting to start is replaced.
        if (refreshing && !refreshWaiting) { refreshAgain = true; return }
        refreshing = true
        refreshMsg = "מתחיל…"
        refreshProgress = 0f
        pendingWork = RefreshWorker.runNow(getApplication())
    }

    private var pendingWork: java.util.UUID? = null
    private var refreshAgain = false

    // ---- Search and product ----

    fun onQuery(q: String) {
        query = q
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            delay(250)
            runSearch(q)
        }
    }

    private suspend fun runSearch(q: String) {
        hits = io { db.search(q) }
    }

    fun openProduct(code: String) {
        viewModelScope.launch {
            product = io {
                val prices = db.prices(code)
                val name = prices.firstOrNull()?.let { db.productName(code) } ?: db.productName(code)
                    ?: db.list().firstOrNull { it.code == code }?.name
                    ?: db.watches().firstOrNull { it.code == code }?.name ?: code
                val titles = db.myStores().associate { it.store.key to it.store.title }
                ProductDetail(
                    code = code,
                    name = name,
                    prices = prices,
                    promos = db.promosForItem(code, Repository.now()),
                    history = db.history(code),
                    storeTitles = titles,
                    qtyInList = db.list().firstOrNull { it.code == code }?.qty ?: 0.0,
                    watch = db.watches().firstOrNull { it.code == code },
                )
            }
        }
    }

    fun closeProduct() { product = null }

    fun onScanned(code: String) {
        val target = scanning
        scanning = null
        viewModelScope.launch {
            val name = io { db.productName(code) }
            if (target == ScanFor.LIST) {
                if (name == null) {
                    snack = "המוצר $code לא נמצא בחנויות שלך"
                } else {
                    io { db.addToList(code, name) }
                    snack = "נוסף לרשימה: $name"
                    loadBasket()
                }
            } else {
                query = code
                runSearch(code)
                if (name == null) snack = "המוצר $code לא נמצא בחנויות שלך" else openProduct(code)
            }
        }
    }

    // ---- Shopping list ----

    fun addToList(code: String, name: String) {
        viewModelScope.launch {
            io { db.addToList(code, name) }
            snack = "נוסף לרשימה: $name"
            loadBasket()
            product?.takeIf { it.code == code }?.let { openProduct(code) }
        }
    }

    fun setQty(code: String, qty: Double) {
        viewModelScope.launch { io { db.setQty(code, qty) }; loadBasket() }
    }

    fun clearList() {
        viewModelScope.launch { io { db.clearList() }; loadBasket() }
    }

    private suspend fun loadBasket() {
        basket = io {
            val entries = db.list()
            val mine = db.myStores()
            val codes = entries.map { it.code }
            BasketView(
                entries,
                Basket.compute(entries, mine.map { it.store.key }, db.pricesFor(codes)),
                mine.associate { it.store.key to it.store.title },
                db.promoCodes(codes, Repository.now()),
            )
        }
    }

    // ---- Promotions ----

    fun onPromoQuery(q: String) {
        promoQuery = q
        promoJob?.cancel()
        promoJob = viewModelScope.launch { delay(250); loadPromos() }
    }

    fun onPromoStore(key: String?) {
        promoStore = key
        viewModelScope.launch { loadPromos() }
    }

    private suspend fun loadPromos() {
        promos = io { db.promos(promoQuery, Repository.now(), promoStore) }
    }

    fun openPromo(p: Promo) {
        viewModelScope.launch { openPromo = p to io { db.promoItems(p) } }
    }

    // ---- Price watch ----

    fun setWatch(code: String, name: String, target: Double?) {
        viewModelScope.launch {
            io { db.setWatch(code, name, target, db.minPrice(code)?.first) }
            snack = if (target != null) "אודיע כשהמחיר יגיע ל-${money(target)}" else "אודיע כשהמחיר יירד"
            loadWatches()
            product?.takeIf { it.code == code }?.let { openProduct(code) }
        }
    }

    fun removeWatch(code: String) {
        viewModelScope.launch {
            io { db.removeWatch(code) }
            loadWatches()
            product?.takeIf { it.code == code }?.let { openProduct(code) }
        }
    }

    private suspend fun loadWatches() {
        watches = io {
            val titles = db.myStores().associate { it.store.key to it.store.title }
            db.watches().map { w ->
                val min = db.minPrice(w.code)
                WatchRow(w, min?.first, min?.second?.let { titles[it] })
            }
        }
    }

    // ---- Stores ----

    private suspend fun loadStores() {
        val (all, mine) = io { db.stores() to db.myStores() }
        stores = all
        myStores = mine
        near = near?.map { n -> n.copy(store = all.firstOrNull { it.key == n.store.key } ?: n.store) }
    }

    fun loadStoreDirectory() {
        // Each chain separately: Shufersal's portal can take a minute or two, Carrefour's seconds.
        val pending = Chains.all.map { it.title }.toMutableList()
        storeBusy = "מוריד את רשימת הסניפים של ${pending.joinToString(" ו")}…"
        for (chain in Chains.all) viewModelScope.launch {
            val ok = io { repo.refreshChainStores(chain) }
            pending -= chain.title
            storeBusy = if (pending.isEmpty()) null else "מוריד את רשימת הסניפים של ${pending.joinToString(" ו")}…"
            if (!ok) snack = "לא הצלחתי להוריד את הסניפים של ${chain.title}. נסה \"רענן סניפים\"."
            loadStores()
        }
    }

    fun toggleStore(s: Store) {
        viewModelScope.launch {
            if (!s.selected && myStores.size >= Repository.MAX_STORES) {
                snack = "אפשר לבחור עד ${Repository.MAX_STORES} חנויות"
                return@launch
            }
            io { db.setSelected(s.chain, s.storeId, !s.selected) }
            loadStores()
            if (!s.selected) {
                snack = "נבחרה ${s.title}. מוריד מחירים…"
                refreshPrices()
            } else {
                reloadAll()
            }
        }
    }

    fun findNearby() {
        if (!Geo.available) { snack = "אין בטלפון שירות איתור כתובות"; return }
        viewModelScope.launch {
            storeBusy = "מאתר את המיקום שלך…"
            val loc = Geo.currentLocation(getApplication())
            if (loc == null) {
                storeBusy = null
                snack = "לא הצלחתי לאתר את המיקום"
                return@launch
            }
            near = io { repo.nearby(loc.latitude to loc.longitude) { msg -> storeBusy = msg } }
            storeBusy = null
        }
    }

    fun clearNearby() { near = null }

    // ---- Optional SuperPrices server ----

    var serverUrl by mutableStateOf(Chains.serverUrl.orEmpty())
        private set

    /** Saves the server address (empty = read the chains directly) and checks that it answers. */
    fun saveServer(url: String) {
        val clean = url.trim().trimEnd('/')
        if (clean.isNotEmpty() && !clean.startsWith("http://") && !clean.startsWith("https://")) {
            snack = "הכתובת צריכה להתחיל ב-http:// או https://"
            return
        }
        Chains.save(getApplication(), clean)
        serverUrl = clean
        if (clean.isEmpty()) {
            snack = "המחירים יורדו ישירות מאתרי הרשתות"
            return
        }
        viewModelScope.launch {
            snack = try {
                val status = org.json.JSONObject(io { Http.text("$clean/api/status") })
                "השרת מחובר: ${status.optInt("stores_with_prices")} חנויות עם מחירים"
            } catch (e: Exception) {
                "השרת לא עונה (${e.message}). המחירים יורדו מאתרי הרשתות עד שיענה."
            }
        }
    }
}
