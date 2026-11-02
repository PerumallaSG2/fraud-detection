#!/usr/bin/env python3
"""Replays prepare.py's output through POST /api/transactions at a fixed rate, keeping each card in order.

One dispatcher walks the file (already in timestamp order) and paces it at RATE per second. Every card belongs to one
worker thread (crc32(cardId) % WORKERS), which sends its cards' transactions one at a time. The API answers 202 only
after Kafka has acked the record, and the topic is keyed by cardId, so each card's transactions reach the scorer in
timestamp order: spike history, velocity and geo see the card's real past.

The label stays here: for every 202 the returned id and the label go to <labels.csv>. Nothing is retried (a 503 means
the outcome is unknown); every non-202 is counted and reported.

Usage: python3 -I accuracy/replay.py <payloads.ndjson> <labels.csv> <summary.json>
Env: RATE (700), LIMIT (all), WORKERS (16), BASE_URL (http://localhost:8080)
"""
import http.client
import json
import os
import queue
import sys
import threading
import time
import zlib
from collections import Counter
from urllib.parse import urlparse

RATE = float(os.environ.get("RATE", "700"))
LIMIT = int(os.environ["LIMIT"]) if os.environ.get("LIMIT") else None
WORKERS = int(os.environ.get("WORKERS", "16"))
BASE = urlparse(os.environ.get("BASE_URL", "http://localhost:8080"))
HEADERS = {"Content-Type": "application/json", "Accept": "application/json"}
STOP = object()


class Worker(threading.Thread):

    def __init__(self):
        super().__init__(daemon=True)
        self.inbox = queue.Queue(maxsize=2000)  # backpressure: the dispatcher waits if the API slows down
        self.accepted = []  # (id, label)
        self.statuses = Counter()
        self.latencies = []

    def run(self):
        conn = http.client.HTTPConnection(BASE.hostname, BASE.port or 80, timeout=10)
        while (item := self.inbox.get()) is not STOP:
            label, body = item
            started = time.perf_counter()
            try:
                conn.request("POST", "/api/transactions", body=body, headers=HEADERS)
                resp = conn.getresponse()
                data = resp.read()
                self.latencies.append(time.perf_counter() - started)
                self.statuses[resp.status] += 1
                if resp.status == 202:
                    self.accepted.append((json.loads(data)["id"], label))
            except (OSError, http.client.HTTPException) as e:
                self.statuses[type(e).__name__] += 1
                conn.close()  # reopened on the next request


def main(payloads_path, labels_path, summary_path):
    workers = [Worker() for _ in range(WORKERS)]
    for w in workers:
        w.start()

    start = time.monotonic()
    last_report, sent = start, 0
    with open(payloads_path, encoding="utf-8") as f:
        for line in f:
            if LIMIT is not None and sent >= LIMIT:
                break
            record = json.loads(line)
            card = record["payload"]["cardId"]
            body = json.dumps(record["payload"], separators=(",", ":")).encode("utf-8")
            workers[zlib.crc32(card.encode()) % WORKERS].inbox.put((record["label"], body))
            sent += 1
            ahead = start + sent / RATE - time.monotonic()
            if ahead > 0:
                time.sleep(ahead)
            now = time.monotonic()
            if now - last_report >= 10:
                done = sum(sum(w.statuses.values()) for w in workers)
                print(f"{now - start:7.0f}s queued {sent:>7} done {done:>7} ({done / (now - start):6.1f}/s) "
                      f"backlog {sum(w.inbox.qsize() for w in workers)}", flush=True)
                last_report = now
    for w in workers:
        w.inbox.put(STOP)
    for w in workers:
        w.join()
    elapsed = time.monotonic() - start

    statuses, latencies, accepted = Counter(), [], []
    for w in workers:
        statuses.update(w.statuses)
        latencies.extend(w.latencies)
        accepted.extend(w.accepted)
    latencies.sort()
    with open(labels_path, "w", encoding="utf-8") as f:
        f.write("id,label\n")
        f.writelines(f"{i},{label}\n" for i, label in accepted)

    def pct(p):
        return round(latencies[min(len(latencies) - 1, int(p / 100 * len(latencies)))] * 1000, 1)

    summary = {
        "sent": sent, "accepted": len(accepted), "statuses": {str(k): v for k, v in statuses.items()},
        "seconds": round(elapsed, 1), "ratePerSecond": round(sent / elapsed, 1), "targetRate": RATE,
        "postMs": {"p50": pct(50), "p95": pct(95), "p99": pct(99), "max": pct(100)} if latencies else None,
    }
    with open(summary_path, "w", encoding="utf-8") as f:
        json.dump(summary, f, indent=2)
    print(json.dumps(summary, indent=2))


if __name__ == "__main__":
    if len(sys.argv) != 4:
        sys.exit(__doc__)
    main(*sys.argv[1:])
