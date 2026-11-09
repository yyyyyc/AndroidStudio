"""JSON API, the file mirror the Android app syncs from, and a small web page."""
from __future__ import annotations

import gzip
import json
import logging
import os
import re
from datetime import datetime
from http import HTTPStatus
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from importlib import resources
from urllib.parse import parse_qs, urlparse

from . import feeds
from .basket import compute_basket
from .collector import Collector
from .db import Db
from .sources import ISRAEL, SOURCES

log = logging.getLogger(__name__)

STORE_COLS = "s.chain, s.store_id, s.name, s.address, s.city, s.sub_chain"
PROMO_COLS = ("p.chain, p.store_id, p.promo_id, p.description, p.start, p.end_at, p.club, p.min_qty, "
              "p.discounted_price, p.discount_rate, p.item_count")


def now_minute() -> str:
    return datetime.now(ISRAEL).strftime("%Y-%m-%dT%H:%M")


class ApiError(Exception):
    def __init__(self, status: int, message: str):
        super().__init__(message)
        self.status = status


def parse_store_keys(value: str | None) -> list[tuple[str, str]]:
    """"carrefour/2,shufersal/1" → [("carrefour", "2"), ("shufersal", "1")]."""
    out = []
    for part in (value or "").split(","):
        chain, _, sid = part.strip().partition("/")
        if chain and sid:
            out.append((chain, feeds.norm_store_id(sid)))
    return out


def store_filter(keys: list[tuple[str, str]], alias: str = "") -> tuple[str, list]:
    if not keys:
        return "1=1", []
    p = f"{alias}." if alias else ""
    return "(" + " OR ".join(f"({p}chain=? AND {p}store_id=?)" for _ in keys) + ")", [v for k in keys for v in k]


class Api:
    def __init__(self, db: Db, collector: Collector, admin_token: str | None):
        self.db = db
        self.collector = collector
        self.admin_token = admin_token

    # ---- handlers; each returns a JSON-able object ----

    def status(self, q):
        counts = self.db.query(
            "SELECT (SELECT COUNT(*) FROM stores) stores, (SELECT COUNT(*) FROM items) items, "
            "(SELECT COUNT(*) FROM promos) promos, (SELECT COUNT(DISTINCT chain || '/' || store_id) FROM items) stores_with_prices"
        )[0]
        return {**counts, **{k: v for k, v in self.collector.status.items()}}

    def chains(self, q):
        rows = {r["chain"]: r["n"] for r in self.db.query("SELECT chain, COUNT(*) n FROM stores GROUP BY chain")}
        return [{"key": s.key, "title": s.title, "chain_id": s.chain_id, "stores": rows.get(s.key, 0)} for s in SOURCES.values()]

    def stores(self, q):
        where, args = ["1=1"], []
        if q.get("chain"):
            where.append("s.chain=?"); args.append(q["chain"])
        if q.get("city"):
            where.append("s.city LIKE ?"); args.append(f"%{q['city']}%")
        for w in (q.get("q") or "").split():
            where.append("(s.name LIKE ? OR s.city LIKE ? OR s.address LIKE ?)"); args += [f"%{w}%"] * 3
        return self.db.query(
            f"""SELECT {STORE_COLS},
                (SELECT stamp FROM files f WHERE f.chain=s.chain AND f.store_id=s.store_id AND f.kind='pricefull') price_stamp
                FROM stores s WHERE {' AND '.join(where)} ORDER BY s.city, s.chain, s.name LIMIT 1000""",
            args,
        )

    def search(self, q):
        text = (q.get("q") or "").strip()
        if not text:
            raise ApiError(400, "q is required")
        limit = min(int(q.get("limit") or 50), 200)
        sf, sargs = store_filter(parse_store_keys(q.get("stores")))
        if text.isdigit():
            cond, args = "(code=? OR ltrim(code,'0')=ltrim(?,'0'))", [text, text]
        else:
            words = text.split()
            cond = " AND ".join("(name LIKE ? OR manufacturer LIKE ?)" for _ in words)
            args = [a for w in words for a in (f"%{w}%", f"%{w}%")]
        return self.db.query(
            f"""SELECT code, MAX(name) name, MAX(manufacturer) manufacturer, MIN(price) min_price,
                MAX(price) max_price, COUNT(*) stores FROM items WHERE {cond} AND {sf}
                GROUP BY code ORDER BY COUNT(*) DESC, MIN(price) LIMIT {limit}""",
            args + sargs,
        )

    def product(self, q, code):
        sf, sargs = store_filter(parse_store_keys(q.get("stores")), "i")
        prices = self.db.query(
            f"""SELECT {STORE_COLS}, i.name item_name, i.manufacturer, i.price, i.unit_price, i.unit_measure, i.updated
                FROM items i JOIN stores s ON s.chain=i.chain AND s.store_id=i.store_id
                WHERE i.code=? AND {sf} ORDER BY i.price LIMIT 1000""",
            [code] + sargs,
        )
        if not prices:
            raise ApiError(404, "product not found")
        now = now_minute()
        pf, pargs = store_filter(parse_store_keys(q.get("stores")), "pi")
        promos = self.db.query(
            f"""SELECT {PROMO_COLS} FROM promo_items pi
                JOIN promos p ON p.chain=pi.chain AND p.store_id=pi.store_id AND p.promo_id=pi.promo_id
                WHERE pi.code=? AND p.end_at>=? AND p.start<=? AND {pf} ORDER BY p.end_at LIMIT 500""",
            [code, now, now] + pargs,
        )
        hf, hargs = store_filter(parse_store_keys(q.get("stores")))
        history = self.db.query(
            f"SELECT chain, store_id, at, price FROM history WHERE code=? AND {hf} ORDER BY at", [code] + hargs
        )
        return {
            "code": code,
            "name": prices[0]["item_name"],
            "manufacturer": prices[0]["manufacturer"],
            "prices": prices,
            "promos": promos,
            "history": history,
        }

    def basket(self, body):
        items = body.get("items") or []
        if not items:
            raise ApiError(400, "items is required")
        keys = parse_store_keys(",".join(body.get("stores") or []))
        codes = [str(i["code"]) for i in items]
        sf, sargs = store_filter(keys)
        rows = self.db.query(
            f"SELECT chain || '/' || store_id key, code, price FROM items WHERE code IN ({','.join('?' * len(codes))}) AND {sf}",
            codes + sargs,
        )
        prices: dict[str, dict[str, float]] = {}
        for r in rows:
            prices.setdefault(r["key"], {})[r["code"]] = r["price"]
        stores = [f"{c}/{s}" for c, s in keys] or sorted(prices)
        result = compute_basket([(str(i["code"]), float(i.get("qty", 1))) for i in items], stores, prices)
        result["totals"] = result["totals"][: int(body.get("limit") or 20)]
        return result

    def promos(self, q):
        where, args = ["p.end_at>=?"], [now_minute()]
        for w in (q.get("q") or "").split():
            where.append("p.description LIKE ?"); args.append(f"%{w}%")
        sf, sargs = store_filter(parse_store_keys(q.get("stores")), "p")
        limit = min(int(q.get("limit") or 100), 500)
        return self.db.query(
            f"""SELECT {PROMO_COLS}, s.name store_name FROM promos p
                JOIN stores s ON s.chain=p.chain AND s.store_id=p.store_id
                WHERE {' AND '.join(where)} AND {sf} ORDER BY p.start DESC LIMIT {limit}""",
            args + sargs,
        )

    def promo(self, q, chain, sid, pid):
        p = self.db.query(f"SELECT {PROMO_COLS} FROM promos p WHERE chain=? AND store_id=? AND promo_id=?", [chain, sid, pid])
        if not p:
            raise ApiError(404, "promotion not found")
        items = self.db.query(
            """SELECT pi.code, i.name, i.price FROM promo_items pi
               LEFT JOIN items i ON i.chain=pi.chain AND i.store_id=pi.store_id AND i.code=pi.code
               WHERE pi.chain=? AND pi.store_id=? AND pi.promo_id=? LIMIT 1000""",
            [chain, sid, pid],
        )
        return {**p[0], "items": items}

    def file_info(self, q, chain, sid, base_url):
        """What the app needs to sync one store (or `_chain` for the store list) from this mirror."""
        sid = sid if sid == "_chain" else feeds.norm_store_id(sid)
        rows = self.db.query("SELECT kind, stamp, size, fetched_at FROM files WHERE chain=? AND store_id=?", [chain, sid])
        if not rows:
            raise ApiError(404, "no files for this store yet")
        return {r["kind"]: {**r, "url": f"{base_url}/files/{chain}/{sid}/{r['kind']}"} for r in rows}

    def file_path(self, chain, sid, kind) -> str:
        sid = sid if sid == "_chain" else feeds.norm_store_id(sid)
        r = self.db.query("SELECT path FROM files WHERE chain=? AND store_id=? AND kind=?", [chain, sid, kind])
        if not r or not os.path.exists(r[0]["path"]):
            raise ApiError(404, "file not found")
        return r[0]["path"]

    def sync(self, headers, client_ip):
        token = (headers.get("Authorization") or "").removeprefix("Bearer ").strip()
        if self.admin_token:
            if token != self.admin_token:
                raise ApiError(401, "bad token")
        elif client_ip not in ("127.0.0.1", "::1"):
            raise ApiError(403, "set SP_ADMIN_TOKEN to allow remote sync requests")
        return {"started": self.collector.run_async()}


ROUTES = [
    ("GET", re.compile(r"^/api/status$"), "status"),
    ("GET", re.compile(r"^/api/chains$"), "chains"),
    ("GET", re.compile(r"^/api/stores$"), "stores"),
    ("GET", re.compile(r"^/api/search$"), "search"),
    ("GET", re.compile(r"^/api/products/([0-9A-Za-z_-]+)$"), "product"),
    ("GET", re.compile(r"^/api/promos$"), "promos"),
    ("GET", re.compile(r"^/api/promos/(\w+)/(\w+)/([\w-]+)$"), "promo"),
]


def make_handler(api: Api):
    page = resources.files(__package__).joinpath("web/index.html").read_bytes()

    class Handler(BaseHTTPRequestHandler):
        server_version = "SuperPrices/1.0"
        protocol_version = "HTTP/1.1"

        def log_message(self, fmt, *args):
            log.debug("%s %s", self.address_string(), fmt % args)

        def _send(self, status: int, body: bytes, ctype: str, extra: dict | None = None):
            gz = "gzip" in (self.headers.get("Accept-Encoding") or "") and ctype.startswith(("application/json", "text/"))
            if gz and len(body) > 1024:
                body = gzip.compress(body, 5)
            else:
                gz = False
            self.send_response(status)
            self.send_header("Content-Type", ctype)
            self.send_header("Content-Length", str(len(body)))
            self.send_header("Access-Control-Allow-Origin", "*")
            if gz:
                self.send_header("Content-Encoding", "gzip")
            for k, v in (extra or {}).items():
                self.send_header(k, v)
            self.end_headers()
            if self.command != "HEAD":
                self.wfile.write(body)

        def _json(self, status: int, obj):
            self._send(status, json.dumps(obj, ensure_ascii=False).encode("utf-8"), "application/json; charset=utf-8")

        def _base_url(self) -> str:
            proto = self.headers.get("X-Forwarded-Proto") or "http"
            return f"{proto}://{self.headers.get('Host') or 'localhost'}"

        def _handle(self, method: str):
            url = urlparse(self.path)
            q = {k: v[0] for k, v in parse_qs(url.query).items()}
            try:
                if method == "GET" and url.path in ("/", "/index.html"):
                    return self._send(200, page, "text/html; charset=utf-8")
                if method == "GET" and (m := re.match(r"^/files/(\w+)/(\w+)/(\w+)$", url.path)):
                    with open(api.file_path(*m.groups()), "rb") as f:
                        data = f.read()
                    return self._send(200, data, "application/octet-stream", {"Cache-Control": "public, max-age=600"})
                if method == "GET" and (m := re.match(r"^/api/files/(\w+)/(\w+)$", url.path)):
                    return self._json(200, api.file_info(q, *m.groups(), self._base_url()))
                if method == "POST" and url.path == "/api/basket":
                    length = int(self.headers.get("Content-Length") or 0)
                    if length > 1_000_000:
                        raise ApiError(413, "body too large")
                    body = json.loads(self.rfile.read(length) or b"{}")
                    return self._json(200, api.basket(body))
                if method == "POST" and url.path == "/api/sync":
                    return self._json(202, api.sync(self.headers, self.client_address[0]))
                for verb, pattern, name in ROUTES:
                    if verb == method and (m := pattern.match(url.path)):
                        return self._json(200, getattr(api, name)(q, *m.groups()))
                raise ApiError(404, "not found")
            except ApiError as e:
                self._json(e.status, {"error": str(e)})
            except (ValueError, KeyError, json.JSONDecodeError) as e:
                self._json(400, {"error": f"bad request: {e}"})
            except Exception:  # noqa: BLE001
                log.exception("request failed: %s", self.path)
                self._json(500, {"error": "internal error"})

        def do_GET(self):
            self._handle("GET")

        def do_HEAD(self):
            self._handle("GET")

        def do_POST(self):
            self._handle("POST")

        def do_OPTIONS(self):
            self.send_response(HTTPStatus.NO_CONTENT)
            self.send_header("Access-Control-Allow-Origin", "*")
            self.send_header("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
            self.send_header("Access-Control-Allow-Headers", "Content-Type, Authorization")
            self.send_header("Content-Length", "0")
            self.end_headers()

    return Handler


def serve(api: Api, host: str, port: int) -> ThreadingHTTPServer:
    server = ThreadingHTTPServer((host, port), make_handler(api))
    server.daemon_threads = True
    return server
