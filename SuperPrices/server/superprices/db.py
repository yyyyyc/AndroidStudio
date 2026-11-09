"""SQLite storage: stores, current prices and promotions, price history, mirrored files."""
from __future__ import annotations

import sqlite3
import threading
from contextlib import contextmanager
from typing import Iterable, Iterator

from .feeds import PriceItem, PromoRow, StoreRow

SCHEMA = """
CREATE TABLE IF NOT EXISTS stores(chain TEXT, store_id TEXT, name TEXT, address TEXT, city TEXT,
    sub_chain TEXT, PRIMARY KEY(chain, store_id));
CREATE TABLE IF NOT EXISTS files(chain TEXT, store_id TEXT, kind TEXT, stamp TEXT, path TEXT,
    size INTEGER, fetched_at TEXT, PRIMARY KEY(chain, store_id, kind));
CREATE TABLE IF NOT EXISTS items(chain TEXT, store_id TEXT, code TEXT, name TEXT, manufacturer TEXT,
    price REAL, unit_price REAL, unit_measure TEXT, quantity TEXT, unit_qty TEXT, weighted INTEGER,
    updated TEXT, PRIMARY KEY(chain, store_id, code)) WITHOUT ROWID;
CREATE INDEX IF NOT EXISTS items_code ON items(code);
CREATE TABLE IF NOT EXISTS history(chain TEXT, store_id TEXT, code TEXT, at TEXT, price REAL);
CREATE INDEX IF NOT EXISTS history_code ON history(code);
CREATE TABLE IF NOT EXISTS promos(chain TEXT, store_id TEXT, promo_id TEXT, description TEXT,
    start TEXT, end_at TEXT, club TEXT, min_qty REAL, discounted_price REAL, discount_rate REAL,
    item_count INTEGER, PRIMARY KEY(chain, store_id, promo_id));
CREATE TABLE IF NOT EXISTS promo_items(chain TEXT, store_id TEXT, promo_id TEXT, code TEXT);
CREATE INDEX IF NOT EXISTS promo_items_code ON promo_items(code);
CREATE INDEX IF NOT EXISTS promo_items_promo ON promo_items(chain, store_id, promo_id);
"""


class Db:
    """One connection per thread; writes are serialised with a lock (SQLite has one writer)."""

    def __init__(self, path: str):
        self.path = path
        self._local = threading.local()
        self.write_lock = threading.Lock()
        with self.conn() as c:
            c.executescript(SCHEMA)

    def conn(self) -> sqlite3.Connection:
        c = getattr(self._local, "conn", None)
        if c is None:
            c = sqlite3.connect(self.path, timeout=60, isolation_level=None, check_same_thread=False)
            c.row_factory = sqlite3.Row
            c.execute("PRAGMA journal_mode=WAL")
            c.execute("PRAGMA synchronous=NORMAL")
            self._local.conn = c
        return c

    @contextmanager
    def write(self) -> Iterator[sqlite3.Connection]:
        with self.write_lock:
            c = self.conn()
            try:
                c.execute("BEGIN")
                yield c
                c.execute("COMMIT")
            except BaseException:
                c.execute("ROLLBACK")
                raise

    def query(self, sql: str, args: Iterable = ()) -> list[dict]:
        return [dict(r) for r in self.conn().execute(sql, tuple(args))]

    # ---- writes ----

    def replace_stores(self, chain: str, rows: list[StoreRow], city_name) -> None:
        with self.write() as c:
            c.execute("DELETE FROM stores WHERE chain=?", (chain,))
            c.executemany(
                "INSERT OR REPLACE INTO stores VALUES(?,?,?,?,?,?)",
                [(r.chain, r.store_id, r.name, r.address, city_name(r), r.sub_chain) for r in rows],
            )

    def file_stamp(self, chain: str, store_id: str, kind: str) -> str | None:
        r = self.conn().execute(
            "SELECT stamp FROM files WHERE chain=? AND store_id=? AND kind=?", (chain, store_id, kind)
        ).fetchone()
        return r[0] if r else None

    def put_file(self, c: sqlite3.Connection, chain: str, store_id: str, kind: str, stamp: str, path: str, size: int, now: str) -> None:
        c.execute("INSERT OR REPLACE INTO files VALUES(?,?,?,?,?,?,?)", (chain, store_id, kind, stamp, path, size, now))

    def replace_prices(self, c: sqlite3.Connection, chain: str, store_id: str, items: Iterable[PriceItem], day: str) -> int:
        """Replaces a store's prices; changed prices go to history (with the old price first)."""
        old = {r[0]: (r[1], r[2]) for r in c.execute(
            "SELECT code, price, updated FROM items WHERE chain=? AND store_id=?", (chain, store_id))}
        has_history = {r[0] for r in c.execute(
            "SELECT DISTINCT code FROM history WHERE chain=? AND store_id=?", (chain, store_id))}
        c.execute("DELETE FROM items WHERE chain=? AND store_id=?", (chain, store_id))
        rows, hist = [], []
        for i in items:
            rows.append((chain, store_id, i.code, i.name, i.manufacturer, i.price, i.unit_price,
                         i.unit_measure, i.quantity, i.unit_qty, int(i.weighted), i.updated))
            before = old.get(i.code)
            if before and abs(before[0] - i.price) > 0.001:
                if i.code not in has_history:
                    hist.append((chain, store_id, i.code, before[1] or day, before[0]))
                hist.append((chain, store_id, i.code, i.updated or day, i.price))
        c.executemany("INSERT OR REPLACE INTO items VALUES(?,?,?,?,?,?,?,?,?,?,?,?)", rows)
        c.executemany("INSERT INTO history VALUES(?,?,?,?,?)", hist)
        return len(rows)

    def replace_promos(self, c: sqlite3.Connection, chain: str, store_id: str, promos: Iterable[PromoRow]) -> int:
        c.execute("DELETE FROM promos WHERE chain=? AND store_id=?", (chain, store_id))
        c.execute("DELETE FROM promo_items WHERE chain=? AND store_id=?", (chain, store_id))
        n = 0
        for p in promos:
            n += 1
            c.execute("INSERT OR REPLACE INTO promos VALUES(?,?,?,?,?,?,?,?,?,?,?)",
                      (chain, store_id, p.id, p.description, p.start, p.end, p.club, p.min_qty,
                       p.discounted_price, p.discount_rate, len(p.codes)))
            c.executemany("INSERT INTO promo_items VALUES(?,?,?,?)", [(chain, store_id, p.id, code) for code in p.codes])
        return n
