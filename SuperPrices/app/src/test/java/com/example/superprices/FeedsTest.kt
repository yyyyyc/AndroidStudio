package com.example.superprices

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.kxml2.io.KXmlParser
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream

/** Fixtures in src/test/resources are real files the chains published on 2026-11-06. */
class FeedsTest {
    private fun res(name: String) = javaClass.classLoader!!.getResourceAsStream(name)!!

    @Test fun parsesFileNames() {
        val p = FeedFiles.parse("PriceFull7290027600007-001-001-20261106-043100")!!
        assertEquals(FileKind.PRICE_FULL, p.kind)
        assertEquals("7290027600007", p.chainId)
        assertEquals("1", p.storeId)
        assertEquals("20261106043100", p.stamp)

        val s = FeedFiles.parse("Stores7290027600007-000-20261106-020101")!!
        assertEquals(FileKind.STORES, s.kind)
        assertNull(s.storeId)
        assertEquals("20261106020101", s.stamp)

        // The name Shufersal's portal actually uses for its store list.
        val shufStores = FeedFiles.parse("Stores7290027600007-000-20261106-020")!!
        assertEquals(FileKind.STORES, shufStores.kind)
        assertEquals("20261106020000", shufStores.stamp)

        val c = FeedFiles.parse("Price7290055700007-001-009-20261106-080013.gz")!!
        assertEquals(FileKind.PRICE, c.kind)
        assertEquals("9", c.storeId)
        assertEquals(FileKind.PROMO_FULL, FeedFiles.parse("PromoFull7290055700007-001-002-20261106-051114.gz")!!.kind)
        assertNull(FeedFiles.parse("readme.txt"))
    }

    @Test fun parsesShufersalListing() {
        val html = """
            <tr class="webgrid-row-style">
            <td><a href="https://pricesprodpublic.blob.core.windows.net/pricefull/PriceFull7290027600007-001-001-20261106-043100.gz?sv=2014-02-14&amp;sr=b&amp;sig=abc%3D&amp;se=2026-11-06T09%3A17%3A50Z&amp;sp=r" target="_blank">לחץ להורדה</a></td>
            <td><a href="https://pricesprodpublic.blob.core.windows.net/pricefull/PriceFull7290027600007-001-001-20261105-043100.gz?sv=1&amp;sp=r">x</a></td>
            <td><a href="https://pricesprodpublic.blob.core.windows.net/pricefull/PriceFull7290027600007-001-002-20261107-043100.gz?sv=1&amp;sp=r">x</a></td>
        """
        val links = Listings.shufersal(html)
        assertEquals(3, links.size)
        val best = Listings.latest(links, FileKind.PRICE_FULL, "001")!!
        assertEquals("20261106043100", best.file.stamp)
        assertTrue(best.url.contains("&sr=b&sig=abc%3D"))
        assertNull(Listings.latest(links, FileKind.PROMO_FULL, "1"))
    }

    @Test fun parsesCarrefourListing() {
        val html = """
            const path = '20261106';
            const files = [{"name":"PriceFull7290055700007-001-002-20261106-051001.gz","size":435003,"modified":"05:10"},
              {"name":"PromoFull7290055700007-001-002-20261106-051114.gz","size":1,"modified":"x"},
              {"name":"Stores7290055700007-000-20261106-000100.xml","size":1,"modified":"x"}];
        """
        val links = Listings.carrefour(html, "https://prices.carrefour.co.il/")
        assertEquals(3, links.size)
        assertEquals(
            "https://prices.carrefour.co.il/20261106/PriceFull7290055700007-001-002-20261106-051001.gz",
            Listings.latest(links, FileKind.PRICE_FULL, "2")!!.url,
        )
        assertNotNull(Listings.latest(links, FileKind.STORES))
    }

    @Test fun parsesCarrefourStoresInUtf16() {
        val stores = XmlFeeds.parseStores(KXmlParser(), XmlFeeds.reader(res("carrefour_stores.xml")), "carrefour")
        assertEquals(147, stores.size)
        val first = stores.first()
        assertEquals("2", first.storeId)
        assertEquals("קרפור מרקט אשקלון חאן", first.name)
        assertEquals("הרצל 33", first.address)
        assertEquals(7100, first.cityCode)
    }

    @Test fun parsesShufersalStores() {
        val stores = XmlFeeds.parseStores(KXmlParser(), XmlFeeds.reader(res("shufersal_stores.gz")), "shufersal")
        assertEquals(417, stores.size)
        val s = stores.first { it.storeId == "756" }
        assertEquals("שלי באר יעקב", s.name)
        assertEquals(2530, s.cityCode)
        assertEquals("שופרסל שלי", s.subChain)
    }

    @Test fun parsesPriceFiles() {
        val shuf = ArrayList<PriceItem>()
        assertEquals(6572, XmlFeeds.parsePrices(KXmlParser(), XmlFeeds.reader(res("shufersal_pricefull.gz")), shuf::add))
        val milk = shuf.first { it.code == "7290000042435" }
        assertEquals("חלב מפוסטר 1% בקרטון 1ל", milk.name)
        assertEquals(6.92, milk.price, 0.001)
        assertEquals("תנובה בע\"מ", milk.manufacturer)

        val carr = ArrayList<PriceItem>()
        assertEquals(5780, XmlFeeds.parsePrices(KXmlParser(), XmlFeeds.reader(res("carrefour_pricefull.gz")), carr::add))
        val coffee = carr.first { it.code == "7290119380466" }
        assertEquals(30.90, coffee.price, 0.001)
        assertEquals(15.45, coffee.unitPrice!!, 0.001)
        assertEquals("100 גרם", coffee.unitMeasure)
        assertEquals("2026-07-20T12:52", coffee.updated)
    }

    @Test fun parsesPromoFiles() {
        val carr = ArrayList<PromoRow>()
        assertEquals(1648, XmlFeeds.parsePromos(KXmlParser(), XmlFeeds.reader(res("carrefour_promofull.gz")), carr::add))
        val waffles = carr.first { it.id == "0011459380" }
        assertEquals(listOf("5060462260539", "5060462260683"), waffles.codes)
        assertEquals("2026-11-01T00:00", waffles.start)
        assertEquals("2026-12-11T23:59", waffles.end)
        assertEquals(2.0, waffles.minQty!!, 0.001)
        assertEquals(25.0, waffles.discountedPrice!!, 0.001)

        val shuf = ArrayList<PromoRow>()
        assertEquals(1294, XmlFeeds.parsePromos(KXmlParser(), XmlFeeds.reader(res("shufersal_promofull.gz")), shuf::add))
        val honey = shuf.first { it.id == "667573" }
        assertEquals(listOf("7296073173045"), honey.codes)
        assertEquals("0 - כלל הלקוחות", honey.club)
        assertTrue(shuf.all { it.end.length == 16 })
    }

    @Test fun readerHandlesGzipBomAndPlainUtf8() {
        val xml = "<Root><Items><Item><ItemCode>1</ItemCode><ItemName>א &amp; ב</ItemName><ItemPrice>2.5</ItemPrice></Item></Items></Root>"
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + xml.toByteArray()
        val gz = ByteArrayOutputStream().also { GZIPOutputStream(it).use { z -> z.write(bom) } }.toByteArray()
        for (bytes in listOf(xml.toByteArray(), bom, gz)) {
            val out = ArrayList<PriceItem>()
            XmlFeeds.parsePrices(KXmlParser(), XmlFeeds.reader(ByteArrayInputStream(bytes)), out::add)
            assertEquals("א & ב", out.single().name)
            assertEquals(2.5, out.single().price, 0.0)
        }
    }

    @Test fun dateTimes() {
        assertEquals("2026-11-01T00:00", XmlFeeds.dateTime("2026-11-01", null, false))
        assertEquals("2026-11-01T23:59", XmlFeeds.dateTime("2026-11-01", "", true))
        assertEquals("2026-11-01T08:30", XmlFeeds.dateTime("2026-11-01", "08:30:00", false))
        assertEquals("2026-11-01T08:30", XmlFeeds.dateTime("2026-11-01 08:30:00", null, false))
        assertEquals("", XmlFeeds.dateTime(null, null, false))
    }
}

/** Listing formats of the portals added for Rami Levy and the other chains (samples from 2026-11-09). */
class MoreChainsTest {
    @Test fun parsesUnusualFileNames() {
        // Keshet leaves out the sub-chain, so the first number is the store.
        val k = FeedFiles.parse("PriceFull7290785400000-002-202611090010.gz")!!
        assertEquals(FileKind.PRICE_FULL, k.kind)
        assertEquals("2", k.storeId)
        assertEquals("20261109001000", k.stamp)
        // Hazi Hinam's store list, and lower-case names on the shared portal.
        assertEquals(FileKind.STORES, FeedFiles.parse("StoresFull7290700100008-000-20261109-050000.gz")!!.kind)
        assertEquals("737", FeedFiles.parse("pricefull7290058140886-001-737-20261109-180015.gz")!!.storeId)
    }

    @Test fun parsesPublishedPricesList() {
        val json = """{"aaData":[{"DT_RowId":"PriceFull7290058140886-001-737-20261109-180015.gz","fname":"PriceFull7290058140886-001-737-20261109-180015.gz","type":"file"},
            {"fname":"Stores7290058140886-000-20261109-050500.xml","type":"file"},{"fname":"notes.txt","type":"file"}],"iTotalRecords":"3"}"""
        val links = Listings.publishedPrices(json, "https://url.publishedprices.co.il")
        assertEquals(2, links.size)
        assertEquals("https://url.publishedprices.co.il/file/d/PriceFull7290058140886-001-737-20261109-180015.gz",
            Listings.latest(links, FileKind.PRICE_FULL, "737")!!.url)
        assertNotNull(Listings.latest(links, FileKind.STORES))
    }

    @Test fun parsesBinaList() {
        val json = """[{"FileNm":"PriceFull7290058108879-000-340-20261109-051132.GZ","Company":"1"},{"FileNm":"Stores7290058108879-000-20261109-050000.xml"}]"""
        val links = Listings.bina(json, "https://kingstore.binaprojects.com")
        assertEquals("https://kingstore.binaprojects.com/Download.aspx?FileNm=PriceFull7290058108879-000-340-20261109-051132.GZ",
            Listings.latest(links, FileKind.PRICE_FULL, "340")!!.url)
        assertNotNull(Listings.latest(links, FileKind.STORES))
    }

    @Test fun parsesLinkPages() {
        val wolt = """<a href="download/2026-11-09/PriceFull7290058249350-000-046-20261109-000040.gz">x</a>"""
        val w = Listings.links(wolt, Regex("href=\"(download/[^\"]+)\""), "https://wm-gateway.wolt.com/isr-prices/public/v1/")
        assertEquals("https://wm-gateway.wolt.com/isr-prices/public/v1/download/2026-11-09/PriceFull7290058249350-000-046-20261109-000040.gz", w.single().url)
        assertEquals("46", w.single().file.storeId)

        val sp = """<a href="/Download/PriceFull7290172900007-000-667-20261109-071651.gz?bucketName=sp_transparency_output_prod_v2&amp;x=1">"""
        val s = Listings.links(sp, Regex("href=\"(/Download/[^\"]+)\""), "https://prices.super-pharm.co.il")
        assertEquals("https://prices.super-pharm.co.il/Download/PriceFull7290172900007-000-667-20261109-071651.gz?bucketName=sp_transparency_output_prod_v2&x=1", s.single().url)

        val hazi = """<a href="https://hazihinamprod01.blob.core.windows.net/regulatories/StoresFull7290700100008-000-20261109-050000.gz">"""
        assertEquals(FileKind.STORES, Listings.links(hazi, Regex("href=\"(https://hazihinamprod01[^\"]+)\"")).single().file.kind)
    }
}
