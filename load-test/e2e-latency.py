#!/usr/bin/env python3
"""End-to-end time per transaction from Kafka record timestamps: no polling, no extra load on the app.

For each transaction of one run, end-to-end = CreateTime of its `decisions` record - CreateTime of its `transactions`
record. Both are stamped by the app's own producer (one JVM clock). The decision is published right after the scored
row is committed, which is the moment GET /api/transactions/{id} starts returning it. Not included: the few ms between
the POST arriving and the first publish (part of the POST latency k6 measures).

Usage: e2e-latency.py <k6 summary json> <transactions dump> <decisions dump>
Dumps are kafka-console-consumer output with print.timestamp and print.key: "CreateTime:<ms>\\t<key>\\t<json>".
"""
import json
import sys


def read_dump(path, prefix):
    with open(path, encoding="utf-8") as f:
        for line in f:
            parts = line.rstrip("\n").split("\t", 2)
            if len(parts) != 3 or not parts[0].startswith("CreateTime:") or not parts[1].startswith(prefix):
                continue
            yield int(parts[0][len("CreateTime:"):]), json.loads(parts[2])


def percentile(sorted_values, p):
    if not sorted_values:
        return None
    k = max(0, min(len(sorted_values) - 1, round(p / 100 * len(sorted_values) + 0.5) - 1))
    return sorted_values[k]


def phases(setup):
    t = setup["start"]
    for st in setup["stages"]:
        yield f"ramp-{st['rate']}", t, t + setup["rampS"] * 1000
        t += setup["rampS"] * 1000
        yield f"hold-{st['rate']}", t, t + st["hold"] * 1000
        t += st["hold"] * 1000


def main(summary_path, transactions_path, decisions_path):
    with open(summary_path, encoding="utf-8") as f:
        setup = json.load(f)["setup"]
    prefix = f"lt-{setup['runId']}-"
    submitted = {}
    for ts, value in read_dump(transactions_path, prefix):
        submitted.setdefault(value["id"], ts)
    decided = {}
    for ts, value in read_dump(decisions_path, prefix):
        decided.setdefault(value["transactionId"], ts)  # at-least-once: keep the first

    rows = [(sub, decided[i] - sub) for i, sub in submitted.items() if i in decided]
    missing = len(submitted) - len(rows)
    print(f"run {setup['runId']}: {len(submitted)} submitted, {len(rows)} decided, {missing} without a decision")
    print(f"{'phase':>12} {'count':>7} {'p50':>7} {'p95':>7} {'p99':>7} {'max':>7}  (ms, decisions - transactions)")

    def report(name, values):
        values.sort()
        cells = [percentile(values, p) for p in (50, 95, 99)] + [values[-1] if values else None]
        print(f"{name:>12} {len(values):>7} " + " ".join(f"{'-' if c is None else c:>7}" for c in cells))

    for name, start, end in phases(setup):
        if name.startswith("hold-"):
            report(name, [d for s, d in rows if start <= s < end])
    report("all", [d for _, d in rows])


if __name__ == "__main__":
    if len(sys.argv) != 4:
        sys.exit(__doc__)
    main(*sys.argv[1:])
