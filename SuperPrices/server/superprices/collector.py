"""Downloads the chains' files on a schedule, keeps a mirror on disk and loads the database."""
from __future__ import annotations

import logging
import os
import threading
import time
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timedelta
from importlib import resources

from . import feeds
from .db import Db
from .sources import ISRAEL, SOURCES, Source

log = logging.getLogger(__name__)


def _load_cities() -> dict[int, str]:
    text = resources.files(__package__).joinpath("cities.tsv").read_text(encoding="utf-8")
    out = {}
    for line in text.splitlines():
        code, _, name = line.partition("\t")
        if code.isdigit():
            out[int(code)] = name
    return out


CITIES = _load_cities()


def city_name(row: feeds.StoreRow) -> str:
    # Some Shufersal stores carry internal codes (e.g. 10018) that aren't settlements.
    if row.city_code is None:
        return row.city_raw
    return CITIES.get(row.city_code, "")


def now_text() -> str:
    return datetime.now(ISRAEL).strftime("%Y-%m-%dT%H:%M:%S")


class Scope:
    """Which stores to mirror: "all", "shufersal:all", or "carrefour/2,shufersal/1" (comma separated)."""

    def __init__(self, spec: str):
        self.everything = False
        self.chains: set[str] = set()
        self.stores: set[tuple[str, str]] = set()
        for part in (p.strip() for p in spec.split(",") if p.strip()):
            if part == "all":
                self.everything = True
            elif part.endswith(":all"):
                self.chains.add(part[:-4])
            elif "/" in part:
                chain, sid = part.split("/", 1)
                self.stores.add((chain, feeds.norm_store_id(sid)))

    def wants_chain(self, chain: str) -> bool:
        return self.everything or chain in self.chains or any(c == chain for c, _ in self.stores)

    def wants(self, chain: str, sid: str) -> bool:
        return self.everything or chain in self.chains or (chain, sid) in self.stores


class Collector:
    def __init__(self, db: Db, data_dir: str, scope: Scope, workers: int = 4):
        self.db = db
        self.files_dir = os.path.join(data_dir, "files")
        self.scope = scope
        self.workers = workers
        self.lock = threading.Lock()
        self.status: dict = {"running": False, "last_run": None, "last_error": None, "progress": ""}

    # ---- store lists ----

    def sync_stores(self, source: Source) -> int:
        link = source.stores_link()
        if link is None:
            raise IOError(f"{source.key}: store list not found")
        raw = source.download(link)
        self._save(source.key, "_chain", feeds.STORES, raw)
        rows = feeds.parse_stores(feeds.open_xml(raw), source.key)
        if rows:
            self.db.replace_stores(source.key, rows, city_name)
            with self.db.write() as c:
                self.db.put_file(c, source.key, "_chain", feeds.STORES, link.file.stamp,
                                 self._path(source.key, "_chain", feeds.STORES), len(raw), now_text())
        log.info("%s: %d stores", source.key, len(rows))
        return len(rows)

    # ---- prices and promotions ----

    def _path(self, chain: str, sid: str, kind: str) -> str:
        return os.path.join(self.files_dir, chain, sid, kind + ".bin")

    def _save(self, chain: str, sid: str, kind: str, raw: bytes) -> str:
        path = self._path(chain, sid, kind)
        os.makedirs(os.path.dirname(path), exist_ok=True)
        tmp = path + ".tmp"
        with open(tmp, "wb") as f:
            f.write(raw)
        os.replace(tmp, path)
        return path

    def _load_one(self, chain: str, sid: str, kind: str, link: feeds.FeedLink) -> bool:
        if self.db.file_stamp(chain, sid, kind) == link.file.stamp:
            return False
        raw = SOURCES[chain].download(link)
        stream = feeds.open_xml(raw)
        day = f"{link.file.stamp[:4]}-{link.file.stamp[4:6]}-{link.file.stamp[6:8]}T00:00"
        with self.db.write() as c:
            if kind == feeds.PRICE_FULL:
                n = self.db.replace_prices(c, chain, sid, feeds.parse_prices(stream), day)
            else:
                n = self.db.replace_promos(c, chain, sid, feeds.parse_promos(stream))
            path = self._save(chain, sid, kind, raw)
            self.db.put_file(c, chain, sid, kind, link.file.stamp, path, len(raw), now_text())
        log.info("%s/%s %s: %d rows", chain, sid, kind, n)
        return True

    def _links(self, source: Source, kind: str) -> dict[str, feeds.FeedLink]:
        """Newest file per wanted store: one chain-wide listing, or per store when only a few are wanted."""
        if self.scope.everything or source.key in self.scope.chains:
            return source.all_latest(kind)
        out = {}
        for chain, sid in sorted(self.scope.stores):
            if chain == source.key:
                try:
                    if (link := source.latest(sid, kind)) is not None:
                        out[sid] = link
                except Exception as e:  # noqa: BLE001
                    log.warning("%s/%s %s listing failed: %s", chain, sid, kind, e)
        return out

    def sync_chain(self, source: Source) -> tuple[int, list[str]]:
        updated, failed = 0, []
        for kind in (feeds.PRICE_FULL, feeds.PROMO_FULL):
            self.status["progress"] = f"{source.key}: listing {kind} files"
            links = self._links(source, kind)
            done = 0

            def work(item):
                sid, link = item
                try:
                    return self._load_one(source.key, sid, kind, link), None
                except Exception as e:  # noqa: BLE001
                    log.warning("%s/%s %s failed: %s", source.key, sid, kind, e)
                    return False, f"{source.key}/{sid}"

            with ThreadPoolExecutor(self.workers) as pool:
                for ok, err in pool.map(work, links.items()):
                    done += 1
                    updated += int(ok)
                    if err:
                        failed.append(err)
                    self.status["progress"] = f"{source.key}: {kind} {done}/{len(links)}"
        return updated, failed

    def run(self) -> dict:
        """One full sync. Only one runs at a time; returns a summary."""
        if not self.lock.acquire(blocking=False):
            return {"started": False, "reason": "already running"}
        started = time.time()
        summary: dict = {"started": True, "chains": {}}
        self.status.update(running=True, last_error=None)
        try:
            for source in SOURCES.values():
                if not self.scope.wants_chain(source.key):
                    continue
                chain_summary: dict = {}
                try:
                    chain_summary["stores"] = self.sync_stores(source)
                except Exception as e:  # noqa: BLE001
                    chain_summary["stores_error"] = str(e)
                    log.warning("%s store list failed: %s", source.key, e)
                try:
                    chain_summary["updated"], chain_summary["failed"] = self.sync_chain(source)
                except Exception as e:  # noqa: BLE001
                    chain_summary["error"] = str(e)
                    self.status["last_error"] = f"{source.key}: {e}"
                    log.warning("%s sync failed: %s", source.key, e)
                summary["chains"][source.key] = chain_summary
        finally:
            summary["seconds"] = round(time.time() - started)
            self.status.update(running=False, last_run=now_text(), progress="", last_summary=summary)
            self.lock.release()
        return summary

    def run_async(self) -> bool:
        if self.lock.locked():
            return False
        threading.Thread(target=self.run, name="sync", daemon=True).start()
        return True

    def schedule_daily(self, hour: int) -> None:
        """Syncs now, then every day at `hour` Israel time (chains publish full files early morning)."""

        def loop():
            self.run()
            while True:
                now = datetime.now(ISRAEL)
                nxt = now.replace(hour=hour, minute=0, second=0, microsecond=0)
                if nxt <= now:
                    nxt += timedelta(days=1)
                time.sleep((nxt - now).total_seconds())
                self.run()

        threading.Thread(target=loop, name="scheduler", daemon=True).start()
