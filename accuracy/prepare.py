#!/usr/bin/env python3
"""Maps the Kaggle Sparkov CSV (kartik2112/fraud-detection) to our POST /api/transactions body.

Writes one line per row, sorted by the transaction's instant (ties: CSV order):
    {"label": 0|1, "payload": {...the API request...}}
The label never goes to the API; replay.py keeps it locally to join with the decision.

Mapping (see README "Accuracy"):
  cardId           "sp-" + first 16 hex of SHA-256(cc_num): no card numbers leave this script
  amount           amt, as is (already 2 decimals)
  merchant         merchant without Sparkov's "fraud_" prefix (every merchant has it; it is not the label)
  merchantCategory category, uppercased (none is in our risky list)
  latitude/long.   merch_lat/merch_long: where the purchase happens (lat/long is the cardholder's home)
  city             city (the cardholder's; the data has no merchant city)
  timestamp        trans_date_trans_time read as the cardholder's local wall clock, with the UTC offset of the
                   cardholder's state (DST-aware); unix_time is not used
Personal fields (first, last, gender, street, zip, dob, job, city_pop) and trans_num are never read into the output.

Usage: python3 -I accuracy/prepare.py <fraudTest.csv> <out.ndjson> [limit]
"""
import csv
import hashlib
import json
import math
import re
import statistics
import sys
from collections import Counter
from datetime import datetime
from zoneinfo import ZoneInfo

# One zone per state; states split across zones use the zone most of their population is in.
STATE_ZONES = {
    "AL": "America/Chicago", "AK": "America/Anchorage", "AZ": "America/Phoenix", "AR": "America/Chicago",
    "CA": "America/Los_Angeles", "CO": "America/Denver", "CT": "America/New_York", "DC": "America/New_York",
    "DE": "America/New_York", "FL": "America/New_York", "GA": "America/New_York", "HI": "Pacific/Honolulu",
    "ID": "America/Boise", "IL": "America/Chicago", "IN": "America/Indiana/Indianapolis", "IA": "America/Chicago",
    "KS": "America/Chicago", "KY": "America/New_York", "LA": "America/Chicago", "ME": "America/New_York",
    "MD": "America/New_York", "MA": "America/New_York", "MI": "America/Detroit", "MN": "America/Chicago",
    "MS": "America/Chicago", "MO": "America/Chicago", "MT": "America/Denver", "NE": "America/Chicago",
    "NV": "America/Los_Angeles", "NH": "America/New_York", "NJ": "America/New_York", "NM": "America/Denver",
    "NY": "America/New_York", "NC": "America/New_York", "ND": "America/Chicago", "OH": "America/New_York",
    "OK": "America/Chicago", "OR": "America/Los_Angeles", "PA": "America/New_York", "RI": "America/New_York",
    "SC": "America/New_York", "SD": "America/Chicago", "TN": "America/Chicago", "TX": "America/Chicago",
    "UT": "America/Denver", "VT": "America/New_York", "VA": "America/New_York", "WA": "America/Los_Angeles",
    "WV": "America/New_York", "WI": "America/Chicago", "WY": "America/Denver",
}
AMOUNT = re.compile(r"^\d+(\.\d{1,2})?$")
_zones = {}


def card_id(cc_num):
    return "sp-" + hashlib.sha256(cc_num.encode("ascii")).hexdigest()[:16]


def merchant_name(raw):
    return raw[len("fraud_"):] if raw.startswith("fraud_") else raw


def local_timestamp(naive_text, state):
    """'2020-06-21 12:14:25' + state -> aware datetime in that state's zone (same wall clock)."""
    zone = _zones.setdefault(state, ZoneInfo(STATE_ZONES[state]))
    return datetime.strptime(naive_text, "%Y-%m-%d %H:%M:%S").replace(tzinfo=zone)


def to_payload(row):
    """Returns (payload, instant) or raises ValueError for a row the API would reject."""
    if not AMOUNT.match(row["amt"]) or float(row["amt"]) <= 0:
        raise ValueError("amount")
    ts = local_timestamp(row["trans_date_trans_time"], row["state"])
    payload = {
        "cardId": card_id(row["cc_num"]),
        "amount": float(row["amt"]),  # shortest repr round-trips, so 2 decimals stay 2 decimals
        "merchant": merchant_name(row["merchant"]),
        "merchantCategory": row["category"].strip().upper(),
        "city": row["city"],
        "latitude": float(row["merch_lat"]),
        "longitude": float(row["merch_long"]),
        "timestamp": ts.isoformat(),
    }
    return payload, ts.timestamp()


def haversine_km(lat1, lon1, lat2, lon2):
    p1, p2 = math.radians(lat1), math.radians(lat2)
    h = math.sin((p2 - p1) / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(math.radians(lon2 - lon1) / 2) ** 2
    return 2 * 6371.0088 * math.asin(math.sqrt(min(1.0, h)))


def main(src, out, limit=None):
    rows, rejected, distances = [], Counter(), []
    with open(src, newline="", encoding="utf-8") as f:
        for i, row in enumerate(csv.DictReader(f)):
            if limit is not None and i >= limit:
                break
            try:
                payload, instant = to_payload(row)
            except (ValueError, KeyError) as e:
                rejected[str(e)] += 1
                continue
            label = int(row["is_fraud"])
            distances.append(haversine_km(float(row["lat"]), float(row["long"]), payload["latitude"],
                                          payload["longitude"]))
            rows.append((instant, i, label, payload))
    rows.sort(key=lambda r: (r[0], r[1]))

    with open(out, "w", encoding="utf-8") as f:
        for _, _, label, payload in rows:
            f.write(json.dumps({"label": label, "payload": payload}, separators=(",", ":")) + "\n")

    # Per-card gaps between consecutive transactions (by instant), for the velocity discussion.
    last, gaps = {}, []
    for instant, _, _, p in rows:
        if p["cardId"] in last:
            gaps.append(instant - last[p["cardId"]])
        last[p["cardId"]] = instant
    gaps.sort()
    q = statistics.quantiles(distances, n=100)
    frauds = sum(r[2] for r in rows)
    print(json.dumps({
        "rows": len(rows), "rejected": dict(rejected), "fraud": frauds, "normal": len(rows) - frauds,
        "fraudRate": round(frauds / len(rows), 5) if rows else None,
        "cards": len(last),
        "first": rows[0][3]["timestamp"] if rows else None, "last": rows[-1][3]["timestamp"] if rows else None,
        "categories": sorted({r[3]["merchantCategory"] for r in rows}),
        "homeToMerchantKm": {"p50": round(q[49], 1), "p95": round(q[94], 1), "max": round(max(distances), 1)},
        "sameCardGapSeconds": {"p1": gaps[len(gaps) // 100], "p50": gaps[len(gaps) // 2]} if gaps else None,
        "sameCardGapsUnder60s": sum(1 for g in gaps if g < 60),
    }, indent=2))


if __name__ == "__main__":
    if len(sys.argv) not in (3, 4):
        sys.exit(__doc__)
    main(sys.argv[1], sys.argv[2], int(sys.argv[3]) if len(sys.argv) == 4 else None)
