"""The chains' public price portals."""
from __future__ import annotations

import http.cookiejar
import json
import logging
import re
import threading
import time
import urllib.parse
import urllib.request
from datetime import datetime, timedelta
from zoneinfo import ZoneInfo

from . import feeds
from .feeds import FeedLink

log = logging.getLogger(__name__)
try:
    ISRAEL = ZoneInfo("Asia/Jerusalem")
except Exception:  # noqa: BLE001 - no time-zone database (e.g. a slim container)
    from datetime import timezone
    ISRAEL = timezone(timedelta(hours=2), "IST")  # off by an hour in summer; only affects scheduling
UA = "Mozilla/5.0 (compatible; SuperPricesServer/1.0)"


def fetch(url: str, attempts: int = 3, timeout: float = 180, opener=None, data: bytes | None = None) -> bytes:
    """GET (or POST with `data`) with retries. Shufersal's portal often takes ~50 s and sometimes says 502."""
    last: Exception | None = None
    for attempt in range(attempts):
        try:
            req = urllib.request.Request(url, data=data, headers={"User-Agent": UA})
            with (opener.open(req, timeout=timeout) if opener else urllib.request.urlopen(req, timeout=timeout)) as r:
                return r.read()
        except Exception as e:  # noqa: BLE001 - network errors come in many types
            last = e
            log.info("fetch failed (%s/%s) %s: %s", attempt + 1, attempts, url.split("?")[0], e)
            if attempt + 1 < attempts:
                time.sleep(5 * (attempt + 1))
    raise IOError(f"download failed: {url.split('?')[0]}: {last}")


class Source:
    key = ""
    title = ""
    chain_id = ""

    def stores_link(self) -> FeedLink | None:
        raise NotImplementedError

    def all_latest(self, kind: str) -> dict[str, FeedLink]:
        """The newest file of `kind` for every store in the chain."""
        raise NotImplementedError

    def latest(self, store_id: str, kind: str) -> FeedLink | None:
        raise NotImplementedError

    def download(self, link: FeedLink) -> bytes:
        return fetch(link.url)


class ListedSource(Source):
    """A portal that lists all its recent files at once; the list is cached for a few minutes."""

    _ttl = 600

    def __init__(self):
        self._cache: tuple[float, list[FeedLink]] | None = None
        self._lock = threading.Lock()

    def _list(self) -> list[FeedLink]:
        raise NotImplementedError

    def files(self) -> list[FeedLink]:
        with self._lock:
            if self._cache is None or time.time() - self._cache[0] > self._ttl:
                # Some folders hold stray files of other chains (Keshet's has a Carrefour file).
                mine = [l for l in self._list() if not self.chain_id or l.file.chain_id == self.chain_id]
                self._cache = (time.time(), mine)
            return self._cache[1]

    def stores_link(self) -> FeedLink | None:
        return feeds.latest(self.files(), feeds.STORES)

    def all_latest(self, kind: str) -> dict[str, FeedLink]:
        return feeds.latest_per_store(self.files(), kind)

    def latest(self, store_id: str, kind: str) -> FeedLink | None:
        return feeds.latest(self.files(), kind, store_id)


def _links(html: str, pattern: str, prefix: str = "") -> list[FeedLink]:
    out = []
    for m in re.finditer(pattern, html):
        href = m.group(1).replace("&amp;", "&")
        f = feeds.parse_name(href.split("?", 1)[0].rsplit("/", 1)[-1].split(".", 1)[0])
        if f:
            out.append(FeedLink(prefix + href, f))
    return out


class PublishedPrices(ListedSource):
    """The shared portal most chains use (Cerberus). Each chain logs in with the public username
    the law requires it to publish, usually with an empty password."""

    base = "https://url.publishedprices.co.il"  # the certificate covers *.publishedprices.co.il only
    _list_params = ("sEcho=1&iColumns=5&sColumns=%2C%2C%2C%2C&iDisplayStart=0&iDisplayLength=100000"
                    "&mDataProp_0=fname&mDataProp_1=typeLabel&mDataProp_2=size&mDataProp_3=ftime&mDataProp_4="
                    "&sSearch=&bRegex=false&iSortingCols=0&cd=%2F")

    def __init__(self, key: str, title: str, chain_id: str, user: str, password: str = ""):
        super().__init__()
        self.key, self.title, self.chain_id = key, title, chain_id
        self.user, self.password = user, password
        self._opener = None

    @staticmethod
    def _token(html: bytes) -> str:
        m = re.search(rb'name="csrftoken" content="([^"]*)"', html)
        return m.group(1).decode() if m else ""

    def _login(self):
        opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
        token = self._token(fetch(self.base + "/login", opener=opener))
        form = urllib.parse.urlencode({"username": self.user, "password": self.password, "csrftoken": token}).encode()
        req = urllib.request.Request(self.base + "/login/user", data=form, headers={"User-Agent": UA})
        with opener.open(req, timeout=60) as r:
            if "/file" not in r.geturl():
                raise IOError(f"{self.key}: login failed for user {self.user}")
            page = r.read()
        self._opener = opener
        return opener, self._token(page)

    def _list(self) -> list[FeedLink]:
        opener, token = self._login()
        body = (self._list_params + "&csrftoken=" + urllib.parse.quote(token)).encode()
        rows = json.loads(fetch(self.base + "/file/json/dir", opener=opener, data=body))["aaData"]
        out = []
        for row in rows:
            name = row.get("fname", "")
            f = feeds.parse_name(name) if row.get("type") == "file" else None
            if f:
                out.append(FeedLink(f"{self.base}/file/d/{urllib.parse.quote(name)}", f))
        return out

    def download(self, link: FeedLink) -> bytes:
        if self._opener is None:
            self.files()
        raw = fetch(link.url, opener=self._opener)
        if raw[:1] == b"<" and b"csrftoken" in raw[:5000]:  # session expired: the login page came back
            self._login()
            raw = fetch(link.url, opener=self._opener)
        return raw


class BinaProjects(ListedSource):
    """Chains hosted on binaprojects.com: a JSON list of the newest files, and a download page
    that returns the file's real address."""

    def __init__(self, key: str, title: str, chain_id: str, subdomain: str):
        super().__init__()
        self.key, self.title, self.chain_id = key, title, chain_id
        self.base = f"https://{subdomain}.binaprojects.com"

    def _list(self) -> list[FeedLink]:
        rows = json.loads(fetch(self.base + "/MainIO_Hok.aspx").decode("utf-8-sig"))
        out = []
        for row in rows:
            name = row.get("FileNm", "")
            f = feeds.parse_name(name)
            if f:
                out.append(FeedLink(f"{self.base}/Download.aspx?FileNm={urllib.parse.quote(name)}", f))
        return out

    def download(self, link: FeedLink) -> bytes:
        real = json.loads(fetch(link.url).decode("utf-8-sig"))[0]["SPath"]
        return fetch(real)


class Wolt(ListedSource):
    """Wolt's stores: one page per day of files."""
    key, title, chain_id = "wolt", "וולט מרקט", "7290058249350"
    base = "https://wm-gateway.wolt.com/isr-prices/public/v1/"

    def _list(self) -> list[FeedLink]:
        today = datetime.now(ISRAEL).date()
        out: list[FeedLink] = []
        for d in (today - timedelta(days=1), today):
            try:
                out += _links(fetch(f"{self.base}{d:%Y-%m-%d}.html").decode("utf-8", "replace"), r'href="(download/[^"]+)"', self.base)
            except IOError:
                if d == today:
                    raise
        return out


class HaziHinam(ListedSource):
    """Hazi Hinam: pages of 50 files per type (t=1 prices, t=2 promotions, t=3 stores), newest first."""
    key, title, chain_id = "hazi_hinam", "חצי חינם", "7290700100008"
    base = "https://shop.hazi-hinam.co.il/Prices"

    def _list(self) -> list[FeedLink]:
        oldest = (datetime.now(ISRAEL) - timedelta(days=2)).strftime("%Y%m%d")
        out: list[FeedLink] = []
        for t in (1, 2, 3):
            for page in range(1, 60):
                got = _links(fetch(f"{self.base}?t={t}&p={page}").decode("utf-8", "replace"),
                             r'href="(https://hazihinamprod01[^"]+)"')
                if not got:
                    break
                out += got
                if all(l.file.stamp[:8] < oldest for l in got):
                    break
        return out


class SuperPharm(ListedSource):
    """Super-Pharm: a grid filtered by file type and date, 20 rows a page."""
    key, title, chain_id = "super_pharm", "סופר-פארם", "7290172900007"
    base = "https://prices.super-pharm.co.il"

    def _list(self) -> list[FeedLink]:
        out: list[FeedLink] = []
        today = datetime.now(ISRAEL).date()
        for category in ("pricefull", "promofull", "stores"):
            for d in (today, today - timedelta(days=1)):
                got = self._category(category, f"{d:%d/%m/%Y}")
                out += got
                if got:
                    break
        return out

    def _category(self, category: str, day: str) -> list[FeedLink]:
        out: list[FeedLink] = []
        query = f"?category-equals={category}&date-equals={urllib.parse.quote(day)}"
        first = fetch(self.base + "/" + query).decode("utf-8", "replace")
        total = int(m.group(1)) if (m := re.search(r'data-total-rows="(\d+)"', first)) else 0
        out += _links(first, r'href="(/Download/[^"]+)"', self.base)
        for page in range(2, min(100, -(-total // 20)) + 1):
            out += _links(fetch(f"{self.base}/{query}&page={page}").decode("utf-8", "replace"),
                          r'href="(/Download/[^"]+)"', self.base)
        return out


class Shufersal(Source):
    key, title, chain_id = "shufersal", "שופרסל", "7290027600007"
    base = "https://prices.shufersal.co.il/FileObject/UpdateCategory"
    categories = {feeds.PRICE: 1, feeds.PRICE_FULL: 2, feeds.PROMO: 3, feeds.PROMO_FULL: 4, feeds.STORES: 5}

    def _page(self, kind: str, store: str, page: int) -> list[FeedLink]:
        url = f"{self.base}?catID={self.categories[kind]}&storeId={store}&page={page}"
        return feeds.shufersal_links(fetch(url).decode("utf-8", "replace"))

    def stores_link(self) -> FeedLink | None:
        return feeds.latest(self._page(feeds.STORES, "0", 1), feeds.STORES)

    def all_latest(self, kind: str) -> dict[str, FeedLink]:
        # Every store's file of this kind, page by page, until a page adds nothing new.
        links: list[FeedLink] = []
        seen: set[str] = set()
        for page in range(1, 200):
            got = self._page(kind, "0", page)
            new = [l for l in got if l.url.split("?")[0] not in seen]
            if not new:
                break
            seen.update(l.url.split("?")[0] for l in new)
            links += new
        return feeds.latest_per_store(links, kind)

    def latest(self, store_id: str, kind: str) -> FeedLink | None:
        return feeds.latest(self._page(kind, feeds.norm_store_id(store_id), 1), kind, store_id)


class Carrefour(Source):
    """Carrefour (formerly Yeinot Bitan): one page per day listing all of that day's files."""
    key, title, chain_id = "carrefour", "קרפור", "7290055700007"
    base = "https://prices.carrefour.co.il/"

    def _days(self) -> list[list[FeedLink]]:
        today = datetime.now(ISRAEL).date()
        out = []
        for d in (today, today - timedelta(days=1)):
            url = self.base if d == today else f"{self.base}?date={d:%Y%m%d}"
            out.append(feeds.carrefour_links(fetch(url).decode("utf-8", "replace"), self.base))
        return out

    def stores_link(self) -> FeedLink | None:
        for links in self._days():
            if (l := feeds.latest(links, feeds.STORES)) is not None:
                return l
        return None

    def all_latest(self, kind: str) -> dict[str, FeedLink]:
        yesterday, today = reversed(self._days())
        merged = feeds.latest_per_store(yesterday, kind)
        merged.update(feeds.latest_per_store(today, kind))
        return merged

    def latest(self, store_id: str, kind: str) -> FeedLink | None:
        for links in self._days():
            if (l := feeds.latest(links, kind, store_id)) is not None:
                return l
        return None


def _published(key: str, title: str, chain_id: str, user: str) -> PublishedPrices:
    return PublishedPrices(key, title, chain_id, user)


SOURCES: dict[str, Source] = {s.key: s for s in (
    Shufersal(),
    Carrefour(),
    _published("rami_levy", "רמי לוי", "7290058140886", "RamiLevi"),
    _published("yohananof", "יוחננוף", "7290803800003", "yohananof"),
    _published("osher_ad", "אושר עד", "7290103152017", "osherad"),
    _published("tiv_taam", "טיב טעם", "7290873255550", "TivTaam"),
    _published("dor_alon", "דור אלון", "7290492000005", "doralon"),
    _published("keshet", "קשת טעמים", "7290785400000", "Keshet"),
    _published("fresh_market", "פרש מרקט", "7290876100000", "freshmarket"),
    _published("stop_market", "סטופ מרקט", "7290639000004", "Stop_Market"),
    _published("politzer", "פוליצר", "7291059100008", "politzer"),
    _published("salach_dabach", "סאלח דבאח", "7290526500006", "SalachD"),
    BinaProjects("king_store", "קינג סטור", "7290058108879", "kingstore"),
    BinaProjects("maayan_2000", "מעיין אלפיים", "7290058159628", "maayan2000"),
    BinaProjects("zol_vebegadol", "זול ובגדול", "7290058173198", "zolvebegadol"),
    BinaProjects("shuk_hair", "שוק העיר", "7290058148776", "shuk-hayir"),
    HaziHinam(),
    SuperPharm(),
    Wolt(),
)}
