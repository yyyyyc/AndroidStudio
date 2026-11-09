"""Price-transparency feed files: names, listings, decoding and streaming XML parsing.

The same rules as the Android app's Feeds.kt, so both read the chains' files alike.
"""
from __future__ import annotations

import gzip
import io
import json
import re
import zipfile
from dataclasses import dataclass, field
from typing import IO, Callable, Iterator
from xml.etree.ElementTree import iterparse

PRICE_FULL, PROMO_FULL, PRICE, PROMO, STORES = "pricefull", "promofull", "price", "promo", "stores"


@dataclass(frozen=True)
class FeedFile:
    kind: str
    chain_id: str
    store_id: str | None
    stamp: str  # yyyyMMddHHmmss


@dataclass(frozen=True)
class FeedLink:
    url: str
    file: FeedFile


@dataclass
class StoreRow:
    chain: str
    store_id: str
    name: str
    address: str
    city_code: int | None
    city_raw: str
    sub_chain: str


@dataclass
class PriceItem:
    code: str
    name: str
    manufacturer: str
    price: float
    unit_price: float | None
    unit_measure: str
    quantity: str
    unit_qty: str
    weighted: bool
    updated: str


@dataclass
class PromoRow:
    id: str
    description: str
    start: str
    end: str
    club: str
    min_qty: float | None
    discounted_price: float | None
    discount_rate: float | None
    codes: list[str] = field(default_factory=list)


def norm_store_id(store_id: str) -> str:
    """Store ids are written "002" in some files and "2" in others."""
    return store_id.strip().lstrip("0") or "0"


# Shufersal's store list is named like "Stores7290027600007-000-20261106-020" (3-digit time).
# Hazi Hinam names its store list "StoresFull…". Keshet leaves out the sub-chain:
# "PriceFull7290785400000-002-202611090010" is store 002.
_NAME = re.compile(
    r"^(PriceFull|PromoFull|StoresFull|Price|Promo|Stores)(\d{13})-(\d+)(?:-(\d+))?-(\d{8})-?(\d{2,6})", re.I
)


def parse_name(name: str) -> FeedFile | None:
    m = _NAME.search(name.rsplit("/", 1)[-1])
    if not m:
        return None
    kind = m.group(1).lower()
    if kind == "storesfull":
        kind = STORES
    raw_store = m.group(4) or m.group(3)  # with no sub-chain part, the first number is the store
    store = norm_store_id(raw_store) if kind != STORES else None
    return FeedFile(kind, m.group(2), store, m.group(5) + m.group(6).ljust(6, "0"))


_SHUFERSAL_LINK = re.compile(r'href="(https://pricesprod[^"]+)"')
_CARREFOUR_PATH = re.compile(r"const path = '([^']*)'")
_CARREFOUR_FILES = re.compile(r"const files = (\[.*?\]);", re.S)


def shufersal_links(html: str) -> list[FeedLink]:
    out = []
    for m in _SHUFERSAL_LINK.finditer(html):
        url = m.group(1).replace("&amp;", "&")
        name = url.split("?", 1)[0].rsplit("/", 1)[-1].split(".", 1)[0]
        f = parse_name(name)
        if f:
            out.append(FeedLink(url, f))
    return out


def carrefour_links(html: str, base: str) -> list[FeedLink]:
    path = _CARREFOUR_PATH.search(html)
    files = _CARREFOUR_FILES.search(html)
    if not path or not files:
        return []
    out = []
    for entry in json.loads(files.group(1)):
        f = parse_name(entry.get("name", ""))
        if f:
            out.append(FeedLink(f"{base}{path.group(1)}/{entry['name']}", f))
    return out


def latest(links: list[FeedLink], kind: str, store_id: str | None = None) -> FeedLink | None:
    want = norm_store_id(store_id) if store_id is not None else None
    best = None
    for link in links:
        if link.file.kind == kind and (want is None or link.file.store_id == want):
            if best is None or link.file.stamp > best.file.stamp:
                best = link
    return best


def latest_per_store(links: list[FeedLink], kind: str) -> dict[str, FeedLink]:
    out: dict[str, FeedLink] = {}
    for link in links:
        sid = link.file.store_id
        if link.file.kind == kind and sid is not None:
            if sid not in out or link.file.stamp > out[sid].file.stamp:
                out[sid] = link
    return out


def open_xml(raw: bytes) -> IO[bytes]:
    """Decompresses gzip or zip files; the XML parser handles UTF-8/UTF-16 itself."""
    if raw[:2] == b"\x1f\x8b":
        raw = gzip.decompress(raw)
    elif raw[:2] == b"PK":
        with zipfile.ZipFile(io.BytesIO(raw)) as z:
            raw = z.read(z.namelist()[0])
    # A UTF-16 file must announce itself; some declare encoding="UTF-8" anyway.
    if raw[:2] in (b"\xff\xfe", b"\xfe\xff"):
        text = raw.decode("utf-16")
        text = re.sub(r"^\s*<\?xml[^>]*\?>", "", text)
        raw = text.encode("utf-8")
    elif raw[:3] == b"\xef\xbb\xbf":
        raw = raw[3:]
    return io.BytesIO(raw)


def _local(tag: str) -> str:
    return tag.rsplit("}", 1)[-1].lower()


def _records(
    stream: IO[bytes], record: str, nested: str | None, outside: Callable[[str, str], None] | None = None
) -> Iterator[tuple[dict[str, str], list[dict[str, str]]]]:
    """Yields (leaf values, nested leaf maps) for each element named `record` (any case)."""
    depth_in_record = 0
    fields: dict[str, str] = {}
    nested_list: list[dict[str, str]] = []
    current: dict[str, str] | None = None
    children = [0]  # child counts per open element, to tell leaves from containers
    for event, el in iterparse(stream, events=("start", "end")):
        name = _local(el.tag)
        if event == "start":
            children[-1] += 1
            children.append(0)
            if depth_in_record == 0 and name == record:
                depth_in_record = 1
                fields, nested_list = {}, []
            elif depth_in_record:
                depth_in_record += 1
                if name == nested:
                    current = {}
            continue
        is_leaf = children.pop() == 0
        if is_leaf and name not in (record, nested):
            value = (el.text or "").strip()
            if not depth_in_record:
                if outside:
                    outside(name, value)
            elif current is not None:
                current[name] = value
            else:
                fields[name] = value
        if depth_in_record:
            if name == nested and current is not None:
                nested_list.append(current)
                current = None
            depth_in_record -= 1
            if depth_in_record == 0:
                yield fields, nested_list
                el.clear()


def _squash(s: str | None) -> str:
    return " ".join((s or "").split())


def _num(s: str | None) -> float | None:
    try:
        return float(s) if s not in (None, "") else None
    except ValueError:
        return None


def date_time(date: str | None, hour: str | None, end_of_day: bool) -> str:
    """Dates as sortable yyyy-MM-ddTHH:mm text."""
    d = (date or "").strip().replace(" ", "T")
    if len(d) < 10:
        return ""
    if len(d) >= 16 and d[10] == "T":
        return d[:16]
    h = (hour or "").strip()[:5]
    if not (len(h) == 5 and h[2] == ":"):
        h = "23:59" if end_of_day else "00:00"
    return f"{d[:10]}T{h}"


def parse_stores(stream: IO[bytes], chain: str) -> list[StoreRow]:
    sub = {"name": ""}

    def outside(k: str, v: str) -> None:
        if k == "subchainname":
            sub["name"] = v

    out = []
    for f, _ in _records(stream, "store", None, outside):
        sid = f.get("storeid", "")
        if not sid.strip():
            continue
        city = f.get("city", "")
        out.append(StoreRow(
            chain=chain,
            store_id=norm_store_id(sid),
            name=_squash(f.get("storename")),
            address=_squash(f.get("address")),
            city_code=int(city) if city.isdigit() else None,
            city_raw=city,
            sub_chain=sub["name"],
        ))
    return out


def parse_prices(stream: IO[bytes]) -> Iterator[PriceItem]:
    for f, _ in _records(stream, "item", None):
        code = f.get("itemcode", "")
        price = _num(f.get("itemprice"))
        if not code or price is None:
            continue
        yield PriceItem(
            code=code,
            name=_squash(f.get("itemname")),
            manufacturer=_squash(f.get("manufacturername") or f.get("manufacturename")),
            price=price,
            unit_price=_num(f.get("unitofmeasureprice")),
            unit_measure=_squash(f.get("unitofmeasure")),
            quantity=f.get("quantity", ""),
            unit_qty=_squash(f.get("unitqty")),
            weighted=f.get("bisweighted") == "1",
            updated=date_time(f.get("priceupdatedate") or f.get("priceupdatetime"), None, False),
        )


def parse_promos(stream: IO[bytes]) -> Iterator[PromoRow]:
    for f, items in _records(stream, "promotion", "promotionitem"):
        pid = f.get("promotionid", "")
        if not pid:
            continue
        first = items[0] if items else f
        codes = [i["itemcode"] for i in items if i.get("itemcode")]
        if not codes and f.get("itemcode"):
            codes = [f["itemcode"]]
        yield PromoRow(
            id=pid,
            description=_squash(f.get("promotiondescription")),
            start=date_time(f.get("promotionstartdatetime") or f.get("promotionstartdate"), f.get("promotionstarthour"), False),
            end=date_time(f.get("promotionenddatetime") or f.get("promotionenddate"), f.get("promotionendhour"), True),
            club=_squash(f.get("clubid")),
            min_qty=_num(first.get("minqty")),
            discounted_price=_num(first.get("discountedprice")),
            discount_rate=_num(first.get("discountrate")),
            codes=list(dict.fromkeys(codes)),
        )
