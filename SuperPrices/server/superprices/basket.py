"""What a shopping list costs in each store, and the cheapest split across stores.

Same rules as the app's Basket.kt: stores are ranked by how many items they lack, then by
total, so a store that's cheap only because it lacks half the list doesn't come first.
"""
from __future__ import annotations


def compute_basket(items: list[tuple[str, float]], stores: list[str], prices: dict[str, dict[str, float]]) -> dict:
    totals = []
    for key in stores:
        p = prices.get(key, {})
        total, missing = 0.0, []
        for code, qty in items:
            if code in p:
                total += p[code] * qty
            else:
                missing.append(code)
        totals.append({"store": key, "total": round(total, 2), "found": len(items) - len(missing), "missing": missing})
    totals.sort(key=lambda t: (len(t["missing"]), t["total"]))

    split, not_found = [], []
    for code, qty in items:
        offers = [(prices[k][code], k) for k in stores if code in prices.get(k, {})]
        if not offers:
            not_found.append(code)
            continue
        price, key = min(offers)
        split.append({"code": code, "store": key, "price": price, "cost": round(price * qty, 2)})
    return {
        "totals": totals,
        "split": split,
        "split_total": round(sum(s["cost"] for s in split), 2),
        "not_found": not_found,
    }
