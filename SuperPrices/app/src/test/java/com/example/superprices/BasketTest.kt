package com.example.superprices

import org.junit.Assert.assertEquals
import org.junit.Test

class BasketTest {
    private val list = listOf(ListEntry("milk", "חלב", 2.0), ListEntry("bread", "לחם", 1.0), ListEntry("rare", "נדיר", 1.0))

    @Test fun ranksStoresByMissingThenTotal() {
        val prices = mapOf(
            "a/1" to mapOf("milk" to 6.0, "bread" to 10.0),
            "b/2" to mapOf("milk" to 5.0, "bread" to 12.0, "rare" to 30.0),
            "c/3" to mapOf("milk" to 1.0),
        )
        val r = Basket.compute(list, listOf("a/1", "b/2", "c/3"), prices)
        assertEquals(listOf("b/2", "a/1", "c/3"), r.totals.map { it.storeKey })
        assertEquals(52.0, r.totals[0].total, 0.001)
        assertEquals(22.0, r.totals[1].total, 0.001)
        assertEquals(listOf("rare"), r.totals[1].missing)

        // Milk from c, bread from a, the rare item from b.
        assertEquals(listOf("c/3", "a/1", "b/2"), r.split.map { it.storeKey })
        assertEquals(2.0 + 10.0 + 30.0, r.splitTotal, 0.001)
        assertEquals(emptyList<String>(), r.notFound)
    }

    @Test fun itemsNowhereAreReported() {
        val r = Basket.compute(list, listOf("a/1"), mapOf("a/1" to mapOf("milk" to 6.0)))
        assertEquals(listOf("bread", "rare"), r.notFound)
        assertEquals(12.0, r.splitTotal, 0.001)
    }
}
