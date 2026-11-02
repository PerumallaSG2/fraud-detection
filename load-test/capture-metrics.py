#!/usr/bin/env python3
"""Per-stage numbers from the monitoring stack for one k6 run.

Dashboard panels: the queries are read from the provisioned dashboard JSON and run through Grafana's /api/ds/query,
so these are the numbers the dashboard shows. Extra queries (bottleneck evidence) go straight to Prometheus.
For each hold phase it prints the average and maximum of every series, and for lag also the value at the start and
end of the hold (growing = consumers falling behind).

Usage: capture-metrics.py <k6 summary json>   (GRAFANA_URL, PROMETHEUS_URL override the localhost defaults)
"""
import json
import os
import sys
import urllib.parse
import urllib.request

GRAFANA = os.environ.get("GRAFANA_URL", "http://localhost:3001")
PROMETHEUS = os.environ.get("PROMETHEUS_URL", "http://localhost:9091")
DASHBOARD = os.path.join(os.path.dirname(__file__), "..", "monitoring", "grafana", "provisioning", "dashboards",
                         "fraud-overview.json")
STEP_S = 5

# Rates over 20 s (4 scrapes). Listener and template timers are Spring Kafka's own (on by default).
EXTRA = {
    "listener busy (s/s per thread)":
        'sum by (name) (rate(spring_kafka_listener_seconds_sum[20s]))',
    "listener ms per record":
        '1000 * sum by (name) (rate(spring_kafka_listener_seconds_sum[20s]))'
        ' / sum by (name) (rate(spring_kafka_listener_seconds_count[20s]))',
    "template send ms":
        '1000 * sum by (name) (rate(spring_kafka_template_seconds_sum[20s]))'
        ' / sum by (name) (rate(spring_kafka_template_seconds_count[20s]))',
    "POST server ms (avg)":
        '1000 * sum(rate(http_server_requests_seconds_sum{method="POST",uri="/api/transactions"}[20s]))'
        ' / sum(rate(http_server_requests_seconds_count{method="POST",uri="/api/transactions"}[20s]))',
    "hikari active": 'max(hikaricp_connections_active)',
    "hikari pending": 'max(hikaricp_connections_pending)',
    "app CPU (share of all cores)": 'max(process_cpu_usage)',
    "system CPU (share of all cores)": 'max(system_cpu_usage)',
    "GC pause s/s": 'sum(rate(jvm_gc_pause_seconds_sum[20s]))',
    "heap used MB": 'sum(jvm_memory_used_bytes{area="heap"}) / 1e6',
    "tomcat busy threads": 'max(tomcat_threads_busy_threads)',
}


def post_json(url, body):
    req = urllib.request.Request(url, data=json.dumps(body).encode(), headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=30) as r:
        return json.load(r)


def grafana_series(expr, start_ms, end_ms):
    body = {"from": str(start_ms), "to": str(end_ms), "queries": [{
        "refId": "A", "datasource": {"uid": "prometheus", "type": "prometheus"}, "expr": expr,
        "range": True, "instant": False, "intervalMs": STEP_S * 1000, "maxDataPoints": 10000}]}
    result = post_json(f"{GRAFANA}/api/ds/query", body)["results"]["A"]
    if "error" in result:
        raise RuntimeError(f"{expr}: {result['error']}")
    series = {}
    for frame in result.get("frames", []):
        fields = frame["schema"]["fields"]
        times, values = frame["data"]["values"][0], frame["data"]["values"][1]
        labels = fields[1].get("labels") or {}
        name = ",".join(f"{k}={v}" for k, v in sorted(labels.items()) if k not in ("application", "instance", "job"))
        series[name or "-"] = [(t, v) for t, v in zip(times, values) if v is not None]
    return series


def prometheus_series(expr, start_ms, end_ms):
    q = urllib.parse.urlencode({"query": expr, "start": start_ms / 1000, "end": end_ms / 1000, "step": STEP_S})
    with urllib.request.urlopen(f"{PROMETHEUS}/api/v1/query_range?{q}", timeout=30) as r:
        data = json.load(r)["data"]["result"]
    series = {}
    for s in data:
        name = ",".join(f"{k}={v}" for k, v in sorted(s["metric"].items()))
        series[name or "-"] = [(float(t) * 1000, float(v)) for t, v in s["values"] if v not in ("NaN", "+Inf")]
    return series


def holds(setup):
    t = setup["start"]
    for st in setup["stages"]:
        t += setup["rampS"] * 1000
        yield st["rate"], t, t + st["hold"] * 1000
        t += st["hold"] * 1000


def short(name):
    # "name=org.springframework.kafka.KafkaListenerEndpointContainer#1-0" -> "#1-0"; client_id=consumer-fraud-x-1 kept
    return name.split("KafkaListenerEndpointContainer")[-1] if "KafkaListenerEndpointContainer" in name else name


def main(summary_path):
    with open(summary_path, encoding="utf-8") as f:
        setup = json.load(f)["setup"]
    with open(DASHBOARD, encoding="utf-8") as f:
        panels = json.load(f)["panels"]
    queries = [(f"[dashboard] {p['title']}" + (f" ({t.get('legendFormat')})" if len(p["targets"]) > 1 else ""),
                grafana_series, t["expr"]) for p in panels for t in p["targets"]]
    queries += [(f"[extra] {title}", prometheus_series, expr) for title, expr in EXTRA.items()]

    run_start = setup["start"]
    run_end = max(end for _, _, end in holds(setup))
    print(f"run {setup['runId']} profile {setup['profile']}")
    for title, fetch, expr in queries:
        try:
            series = fetch(expr, run_start, run_end + 30_000)
        except Exception as e:  # report and keep going; a missing metric is noted, not fatal
            print(f"\n{title}: ERROR {e}")
            continue
        print(f"\n{title}\n  {expr}")
        if not series:
            print("  (no data)")
            continue
        is_lag = "lag" in title.lower()
        for rate, start, end in holds(setup):
            cells = []
            for name, points in sorted(series.items()):
                vals = [v for t, v in points if start <= t <= end]
                if not vals:
                    continue
                cell = f"{short(name)} avg {sum(vals) / len(vals):.3g} max {max(vals):.3g}"
                if is_lag:
                    cell += f" start {vals[0]:.3g} end {vals[-1]:.3g}"
                cells.append(cell)
            print(f"  {rate:>5}/s: " + ("; ".join(cells) if cells else "(no data)"))


if __name__ == "__main__":
    if len(sys.argv) != 2:
        sys.exit(__doc__)
    main(sys.argv[1])
