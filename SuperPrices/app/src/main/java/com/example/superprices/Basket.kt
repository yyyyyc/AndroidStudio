package com.example.superprices

/** What the shopping list costs in one store. */
data class StoreTotal(val storeKey: String, val total: Double, val found: Int, val missing: List<String>)

/** The cheapest store for each item, when buying in several stores. */
data class SplitLine(val code: String, val storeKey: String, val cost: Double)

data class BasketResult(
    val totals: List<StoreTotal>,
    val split: List<SplitLine>,
    val splitTotal: Double,
    val notFound: List<String>,
)

object Basket {
    /**
     * [prices] is store key → (barcode → price). Stores are ranked by how many items they
     * have, then by total, so a store that's cheap only because it lacks half the list
     * doesn't come first.
     */
    fun compute(items: List<ListEntry>, stores: List<String>, prices: Map<String, Map<String, Double>>): BasketResult {
        val totals = stores.map { key ->
            val p = prices[key].orEmpty()
            var total = 0.0
            val missing = ArrayList<String>()
            for (e in items) {
                val price = p[e.code]
                if (price == null) missing += e.code else total += price * e.qty
            }
            StoreTotal(key, total, items.size - missing.size, missing)
        }.sortedWith(compareBy<StoreTotal>({ it.missing.size }, { it.total }))

        val split = ArrayList<SplitLine>()
        val notFound = ArrayList<String>()
        for (e in items) {
            val best = stores.mapNotNull { k -> prices[k]?.get(e.code)?.let { k to it } }.minByOrNull { it.second }
            if (best == null) notFound += e.code else split += SplitLine(e.code, best.first, best.second * e.qty)
        }
        return BasketResult(totals, split, split.sumOf { it.cost }, notFound)
    }
}
