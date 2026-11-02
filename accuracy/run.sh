#!/usr/bin/env bash
# Sparkov replay (accuracy). Needs `docker compose up -d --wait` and the app via java -jar with
# OLLAMA_URL=http://localhost:1 (explanations fall back to rule reasons at once instead of queueing on an LLM).
#
#   accuracy/run.sh run      # pre-clean, prepare, replay, wait, export, evaluate. Env: RATE (700), LIMIT, OUT
#   accuracy/run.sh order    # per-card order check on the stored rows (0 = every card scored in timestamp order)
#   accuracy/run.sh clean    # delete the replay's rows (card_id LIKE 'sp-%') and Redis velocity keys
#   accuracy/run.sh purge    # clean + delete accuracy/data and accuracy/work (the dataset and per-row files)
#
# If a run is interrupted (e.g. Docker quit), its unconsumed records stay in the stopped Kafka container and are
# scored when the app restarts. Recreate Kafka before re-running: docker compose rm -sf kafka && docker compose up -d --wait
#
# The CSV goes in accuracy/data/fraudTest.csv (git-ignored). Only accuracy/results.json (aggregates) is kept.
set -euo pipefail
cd "$(dirname "$0")/.."

DATA=accuracy/data/fraudTest.csv
WORK=accuracy/work
OUT="${OUT:-accuracy/results.json}"

sql() { docker exec -i fraud-postgres psql -U fraud -d fraud -v ON_ERROR_STOP=1 -qAt "$@"; }

wait_settled() {
  # Scored rows match the accepted count, and every flagged one has its alert and explanation (those consumers
  # write rows that reference the transaction, so deleting before they finish would send records to the DLT).
  local want=$1 last="" stable=0
  for _ in $(seq 1 720); do
    now=$(sql -c "select (select count(*) from transactions where card_id like 'sp-%'),
                         (select count(*) from transactions where card_id like 'sp-%' and decision <> 'APPROVE'),
                         (select count(*) from alerts where card_id like 'sp-%'),
                         (select count(*) from alert_explanations e join transactions t on t.id = e.transaction_id
                           where t.card_id like 'sp-%')")
    IFS='|' read -r scored flagged alerts explained <<< "$now"
    echo "  scored $scored/$want, flagged $flagged, alerts $alerts, explanations $explained"
    if [ "$scored" = "$want" ] && [ "$alerts" = "$flagged" ] && [ "$explained" = "$flagged" ]; then return 0; fi
    if [ "$scored" -gt "$want" ]; then echo "  more sp-% rows than this run accepted: not a clean run"; return 1; fi
    if [ "$now" = "$last" ]; then stable=$((stable + 1)); else stable=0; fi
    if [ "$stable" -ge 12 ]; then echo "  no progress for 60 s; continuing with what is stored"; return 0; fi
    last=$now; sleep 5
  done
}

clean() {
  sql <<'SQL'
delete from alert_explanations where transaction_id in (select id from transactions where card_id like 'sp-%');
delete from alerts where card_id like 'sp-%';
delete from transaction_rule_hits where transaction_id in (select id from transactions where card_id like 'sp-%');
delete from transactions where card_id like 'sp-%';
SQL
  docker exec fraud-redis sh -c "redis-cli --scan --pattern 'velocity:sp-*' | xargs -r redis-cli del > /dev/null"
  echo "deleted sp-% rows and velocity keys"
}

case "${1:-run}" in
  run)
    curl -sf localhost:8080/actuator/health > /dev/null || { echo "app is not up on :8080"; exit 1; }
    [ -f "$DATA" ] || { echo "missing $DATA"; exit 1; }
    mkdir -p "$WORK"
    clean
    # Leftover records of an interrupted run still in Kafka would be scored now and pollute card histories.
    sleep 10
    left=$(sql -c "select count(*) from transactions where card_id like 'sp-%'")
    [ "$left" = 0 ] || { echo "$left sp-% rows reappeared after clean: an old run is still in Kafka; recreate it"; exit 1; }
    python3 -I accuracy/prepare.py "$DATA" "$WORK/payloads.ndjson" ${LIMIT:-} | tee "$WORK/prepare.json"
    python3 -I accuracy/replay.py "$WORK/payloads.ndjson" "$WORK/labels.csv" "$WORK/replay.json"
    echo "waiting for scoring, alerts and explanations to finish..."
    wait_settled "$(python3 -I -c 'import json,sys; print(json.load(open(sys.argv[1]))["accepted"])' "$WORK/replay.json")"
    sql -c "\copy (select t.id, t.decision, t.risk_score,
                          coalesce(string_agg(h.rule_name, '|' order by h.rule_name), '')
                     from transactions t left join transaction_rule_hits h on h.transaction_id = t.id
                    where t.card_id like 'sp-%' group by t.id) to stdout with csv" > "$WORK/scored.csv"
    python3 -I accuracy/evaluate.py "$WORK/labels.csv" "$WORK/scored.csv" "$WORK/prepare.json" "$WORK/replay.json" "$OUT"
    ;;
  order)
    sql -c "select count(*) from (select transaction_time,
                   lag(transaction_time) over (partition by card_id order by created_at, id) as prev
              from transactions where card_id like 'sp-%') x where prev > transaction_time"
    ;;
  clean) clean ;;
  purge) clean; rm -rf accuracy/data accuracy/work; echo "deleted accuracy/data and accuracy/work" ;;
  *) echo "usage: $0 run|order|clean|purge"; exit 2 ;;
esac
