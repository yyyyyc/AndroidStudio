"""Run with: python -m unittest discover -s tests (from the server folder).

Fixtures are the real files the chains published on 2026-11-06, shared with the app's tests.
"""
from __future__ import annotations

import gzip
import json
import os
import tempfile
import threading
import unittest
import urllib.request
from pathlib import Path

from superprices import feeds
from superprices.api import Api, serve
from superprices.basket import compute_basket
from superprices.collector import Collector, Scope, city_name
from superprices.db import Db

FIXTURES = Path(__file__).resolve().parents[2] / "app" / "src" / "test" / "resources"


def fixture(name: str) -> bytes:
    return (FIXTURES / name).read_bytes()


class FeedTests(unittest.TestCase):
    def test_file_names(self):
        f = feeds.parse_name("PriceFull7290027600007-001-001-20261106-043100")
        self.assertEqual((f.kind, f.chain_id, f.store_id, f.stamp), ("pricefull", "7290027600007", "1", "20261106043100"))
        s = feeds.parse_name("Stores7290027600007-000-20261106-020")
        self.assertEqual((s.kind, s.store_id, s.stamp), ("stores", None, "20261106020000"))
        self.assertEqual(feeds.parse_name("Price7290055700007-001-009-20261106-080013.gz").store_id, "9")
        self.assertIsNone(feeds.parse_name("readme.txt"))
        # Keshet: no sub-chain part, so the first number is the store.
        k = feeds.parse_name("PriceFull7290785400000-002-202611090010.gz")
        self.assertEqual((k.kind, k.store_id, k.stamp), ("pricefull", "2", "20261109001000"))
        # Hazi Hinam's store list, and lower-case names from the shared portal.
        self.assertEqual(feeds.parse_name("StoresFull7290700100008-000-20261109-050000.gz").kind, "stores")
        self.assertEqual(feeds.parse_name("pricefull7290058140886-001-737-20261109-180015.gz").store_id, "737")

    def test_listings(self):
        html = ('<a href="https://pricesprodpublic.blob.core.windows.net/pricefull/PriceFull7290027600007-001-001-20261106-043100.gz?sv=1&amp;sig=a%3D">x</a>'
                '<a href="https://pricesprodpublic.blob.core.windows.net/pricefull/PriceFull7290027600007-001-002-20261105-043100.gz?sv=1">x</a>'
                '<a href="https://pricesprodpublic.blob.core.windows.net/pricefull/PriceFull7290027600007-001-002-20261106-043100.gz?sv=1">x</a>')
        per_store = feeds.latest_per_store(feeds.shufersal_links(html), feeds.PRICE_FULL)
        self.assertEqual(sorted(per_store), ["1", "2"])
        self.assertEqual(per_store["2"].file.stamp, "20261106043100")
        self.assertIn("&sig=a%3D", per_store["1"].url)

        carr = """const path = '20261106';
        const files = [{"name":"PriceFull7290055700007-001-002-20261106-051001.gz","size":1,"modified":"x"},
                       {"name":"Stores7290055700007-000-20261106-000100.xml","size":1,"modified":"x"}];"""
        links = feeds.carrefour_links(carr, "https://prices.carrefour.co.il/")
        self.assertEqual(feeds.latest(links, feeds.PRICE_FULL, "002").url,
                         "https://prices.carrefour.co.il/20261106/PriceFull7290055700007-001-002-20261106-051001.gz")
        self.assertIsNotNone(feeds.latest(links, feeds.STORES))

    def test_stores(self):
        carr = feeds.parse_stores(feeds.open_xml(fixture("carrefour_stores.xml")), "carrefour")
        self.assertEqual(len(carr), 147)
        self.assertEqual((carr[0].store_id, carr[0].name, carr[0].city_code), ("2", "קרפור מרקט אשקלון חאן", 7100))
        self.assertEqual(city_name(carr[0]), "אשקלון")
        shuf = feeds.parse_stores(feeds.open_xml(fixture("shufersal_stores.gz")), "shufersal")
        self.assertEqual(len(shuf), 417)
        s = next(r for r in shuf if r.store_id == "756")
        self.assertEqual((s.name, s.sub_chain, city_name(s)), ("שלי באר יעקב", "שופרסל שלי", "באר יעקב"))

    def test_prices(self):
        shuf = list(feeds.parse_prices(feeds.open_xml(fixture("shufersal_pricefull.gz"))))
        self.assertEqual(len(shuf), 6572)
        milk = next(i for i in shuf if i.code == "7290000042435")
        self.assertEqual((milk.name, milk.price), ("חלב מפוסטר 1% בקרטון 1ל", 6.92))
        carr = list(feeds.parse_prices(feeds.open_xml(fixture("carrefour_pricefull.gz"))))
        self.assertEqual(len(carr), 5780)
        coffee = next(i for i in carr if i.code == "7290119380466")
        self.assertEqual((coffee.price, coffee.unit_price, coffee.updated), (30.90, 15.45, "2026-07-20T12:52"))

    def test_promos(self):
        carr = list(feeds.parse_promos(feeds.open_xml(fixture("carrefour_promofull.gz"))))
        self.assertEqual(len(carr), 1648)
        w = next(p for p in carr if p.id == "0011459380")
        self.assertEqual(w.codes, ["5060462260539", "5060462260683"])
        self.assertEqual((w.start, w.end, w.min_qty, w.discounted_price), ("2026-11-01T00:00", "2026-12-11T23:59", 2.0, 25.0))
        shuf = list(feeds.parse_promos(feeds.open_xml(fixture("shufersal_promofull.gz"))))
        self.assertEqual(len(shuf), 1294)

    def test_plain_and_bom(self):
        xml = "<Root><Items><Item><ItemCode>1</ItemCode><ItemName>א &amp; ב</ItemName><ItemPrice>2.5</ItemPrice></Item></Items></Root>"
        for raw in (xml.encode(), b"\xef\xbb\xbf" + xml.encode(), gzip.compress(xml.encode("utf-16"))):
            (item,) = feeds.parse_prices(feeds.open_xml(raw))
            self.assertEqual((item.name, item.price), ("א & ב", 2.5))


class BasketTests(unittest.TestCase):
    def test_ranking_and_split(self):
        items = [("milk", 2.0), ("bread", 1.0), ("rare", 1.0)]
        prices = {"a/1": {"milk": 6.0, "bread": 10.0}, "b/2": {"milk": 5.0, "bread": 12.0, "rare": 30.0}, "c/3": {"milk": 1.0}}
        r = compute_basket(items, ["a/1", "b/2", "c/3"], prices)
        self.assertEqual([t["store"] for t in r["totals"]], ["b/2", "a/1", "c/3"])
        self.assertEqual(r["totals"][0]["total"], 52.0)
        self.assertEqual([s["store"] for s in r["split"]], ["c/3", "a/1", "b/2"])
        self.assertEqual(r["split_total"], 42.0)


class ApiTests(unittest.TestCase):
    """Loads the fixtures as if mirrored, then calls the real HTTP server."""

    @classmethod
    def setUpClass(cls):
        cls.tmp = tempfile.TemporaryDirectory(ignore_cleanup_errors=True)  # Windows keeps the db open
        db = Db(os.path.join(cls.tmp.name, "prices.db"))
        collector = Collector(db, cls.tmp.name, Scope("all"))
        db.replace_stores("carrefour", feeds.parse_stores(feeds.open_xml(fixture("carrefour_stores.xml")), "carrefour"), city_name)
        db.replace_stores("shufersal", feeds.parse_stores(feeds.open_xml(fixture("shufersal_stores.gz")), "shufersal"), city_name)
        for chain, sid, price, promo in (("carrefour", "2", "carrefour_pricefull.gz", "carrefour_promofull.gz"),
                                         ("shufersal", "1", "shufersal_pricefull.gz", "shufersal_promofull.gz")):
            with db.write() as c:
                db.replace_prices(c, chain, sid, feeds.parse_prices(feeds.open_xml(fixture(price))), "2026-11-06T00:00")
                db.replace_promos(c, chain, sid, feeds.parse_promos(feeds.open_xml(fixture(promo))))
                path = collector._save(chain, sid, "pricefull", fixture(price))
                db.put_file(c, chain, sid, "pricefull", "20261106051001", path, 1, "now")
        cls.server = serve(Api(db, collector, None), "127.0.0.1", 0)
        cls.base = f"http://127.0.0.1:{cls.server.server_address[1]}"
        threading.Thread(target=cls.server.serve_forever, daemon=True).start()

    @classmethod
    def tearDownClass(cls):
        cls.server.shutdown()
        cls.server.server_close()
        cls.tmp.cleanup()

    def get(self, path):
        with urllib.request.urlopen(self.base + path) as r:
            return json.loads(r.read())

    def test_status_and_chains(self):
        st = self.get("/api/status")
        self.assertEqual(st["stores"], 147 + 417)
        self.assertEqual(st["stores_with_prices"], 2)
        counts = {c["key"]: c["stores"] for c in self.get("/api/chains")}
        self.assertEqual((counts["shufersal"], counts["carrefour"]), (417, 147))
        self.assertIn("rami_levy", counts)  # listed even before its first sync

    def test_search_across_chains(self):
        hits = self.get("/api/search?q=" + urllib.request.quote("חלב"))
        self.assertTrue(any(h["stores"] == 2 for h in hits), "some milk product is sold by both stores")
        only = self.get("/api/search?q=7290000042435&stores=shufersal/1")
        self.assertEqual(only[0]["min_price"], 6.92)

    def test_product_and_basket(self):
        common = next(h for h in self.get("/api/search?q=" + urllib.request.quote("חלב")) if h["stores"] == 2)
        p = self.get(f"/api/products/{common['code']}")
        self.assertEqual(len(p["prices"]), 2)
        self.assertLessEqual(p["prices"][0]["price"], p["prices"][1]["price"])

        body = json.dumps({"items": [{"code": common["code"], "qty": 2}, {"code": "7290000042435", "qty": 1}],
                           "stores": ["carrefour/2", "shufersal/1"]}).encode()
        req = urllib.request.Request(self.base + "/api/basket", data=body, headers={"Content-Type": "application/json"})
        with urllib.request.urlopen(req) as r:
            b = json.loads(r.read())
        self.assertEqual(len(b["totals"]), 2)
        self.assertEqual(b["not_found"], [])

    def test_promos(self):
        promos = self.get("/api/promos?stores=carrefour/2&limit=500")
        self.assertTrue(promos)
        self.assertTrue(all(p["chain"] == "carrefour" for p in promos))

    def test_file_mirror(self):
        info = self.get("/api/files/carrefour/002")
        self.assertIn("pricefull", info)
        with urllib.request.urlopen(info["pricefull"]["url"]) as r:
            self.assertEqual(r.read(), fixture("carrefour_pricefull.gz"))

    def test_errors(self):
        for path, code in (("/api/products/0000", 404), ("/api/search", 400), ("/nope", 404)):
            with self.assertRaises(urllib.error.HTTPError) as e:
                urllib.request.urlopen(self.base + path)
            self.assertEqual(e.exception.code, code)

    def test_web_page(self):
        with urllib.request.urlopen(self.base + "/") as r:
            self.assertIn("השוואת מחירים", r.read().decode())


if __name__ == "__main__":
    unittest.main()
