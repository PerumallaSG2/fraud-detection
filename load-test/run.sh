#!/usr/bin/env bash
# One load-test run: k6, then wait for the consumers to catch up, then end-to-end times from Kafka timestamps and
# per-stage numbers from Grafana/Prometheus. Needs the app on :8080 and `docker compose --profile monitoring up`.
#
#   load-test/run.sh steps                      # or: smoke, sustain (with RATE=...), STEPS=100,200 for custom steps
#
# Everything goes to load-test/results/<run id>-* (git-ignored; delete it after copying the numbers).
set -euo pipefail
cd "$(dirname "$0")/.."

PROFILE="${1:-smoke}"
RUN_ID="${RUN_ID:-$(date +%s)}"
OUT="load-test/results/$RUN_ID"
mkdir -p load-test/results

# Container and process CPU every 5 s while the run lasts (k6 shares the laptop with the app).
(
  while true; do
    date +%s >> "$OUT-docker-stats.txt"
    docker stats --no-stream --format '{{.Name}} {{.CPUPerc}} {{.MemUsage}}' >> "$OUT-docker-stats.txt" 2>/dev/null || true
    ps -Ao pcpu=,rss=,comm= | awk '/java$|k6$/ {print}' >> "$OUT-docker-stats.txt" || true
    sleep 5
  done
) &
SAMPLER=$!
disown $SAMPLER
trap 'kill $SAMPLER 2>/dev/null || true' EXIT

k6 run -e PROFILE="$PROFILE" -e RUN_ID="$RUN_ID" load-test/fraud-load.js | tee "$OUT-k6.txt"

echo "waiting for the consumers to catch up..."
decisions() { curl -s localhost:8080/actuator/prometheus | awk '/^fraud_decisions_total/ {s += $2} END {print s}'; }
last=-1; stable=0
for _ in $(seq 1 600); do
  now=$(decisions)
  if [ "$now" = "$last" ]; then stable=$((stable + 1)); else stable=0; fi
  [ "$stable" -ge 5 ] && break
  last=$now; sleep 2
done
kill $SAMPLER 2>/dev/null || true

# Only this run's records (keys lt-<run id>-...). Both timestamps are set by the app's producer.
for topic in transactions decisions; do
  docker exec fraud-kafka /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server localhost:9092 \
    --topic "$topic" --from-beginning --timeout-ms 15000 \
    --property print.timestamp=true --property print.key=true 2>/dev/null \
    | grep -F "	lt-$RUN_ID-" > "$OUT-$topic.txt" || true
done

python3 load-test/e2e-latency.py "$OUT-summary.json" "$OUT-transactions.txt" "$OUT-decisions.txt" | tee "$OUT-e2e.txt"
python3 load-test/capture-metrics.py "$OUT-summary.json" > "$OUT-metrics.txt"
echo "metrics: $OUT-metrics.txt"
