#!/usr/bin/env bash
# Samples the E2E backend while the browser suite runs against it.
#
#   bash scripts/e2e-sampler.sh <output.csv> &
#   ... run the suite ...
#   touch <output.csv>.stop
#
# Exists to answer one question: when a request to the API never comes back,
# is the application stalled, the database busy, or the machine out of room?
# Those look identical from a Playwright timeout and completely different here.
#
# Probe latency is measured from outside the container, so it includes whatever
# the host is doing. A probe that stays fast while a browser request hangs means
# the problem is not the backend being down.
set -u

OUT="${1:-e2e-samples.csv}"
INTERVAL="${SAMPLE_INTERVAL:-2}"
BASE="${BASE_URL:-http://localhost:18080}"
BE="kalo-e2e-backend-1"
PG="kalo-e2e-postgres-1"

STOP="$OUT.stop"
rm -f "$STOP"
trap 'rm -f "$STOP"' EXIT

echo "ts,probe_ms,probe_code,be_cpu_pct,be_mem_mb,pg_cpu_pct,pg_conns,pg_active,pg_idle_in_txn,pg_waiting" > "$OUT"

pct() { echo "${1:-0}" | tr -d '%'; }

mb() {
  local v="${1%% /*}"
  case "$v" in
    *GiB) awk -v x="${v%GiB}" 'BEGIN{printf "%.0f", x*1024}' ;;
    *MiB) awk -v x="${v%MiB}" 'BEGIN{printf "%.0f", x}' ;;
    *) echo 0 ;;
  esac
}

while [ ! -f "$STOP" ]; do
  TS=$(date +%H:%M:%S)

  # Timed from outside, so a stalled host shows up as a slow probe.
  PROBE=$(curl -s -o /dev/null -w '%{time_total} %{http_code}' --max-time 20 \
    "$BASE/actuator/health" 2>/dev/null || echo "0 000")
  PROBE_MS=$(echo "$PROBE" | awk '{printf "%.0f", $1*1000}')
  PROBE_CODE=$(echo "$PROBE" | awk '{print $2}')

  STATS=$(docker stats --no-stream --format '{{.Name}}|{{.CPUPerc}}|{{.MemUsage}}' "$BE" "$PG" 2>/dev/null)
  BE_LINE=$(echo "$STATS" | grep "$BE" | head -1)
  PG_LINE=$(echo "$STATS" | grep "$PG" | head -1)

  BE_CPU=$(pct "$(echo "$BE_LINE" | cut -d'|' -f2)")
  BE_MEM=$(mb "$(echo "$BE_LINE" | cut -d'|' -f3)")
  PG_CPU=$(pct "$(echo "$PG_LINE" | cut -d'|' -f2)")

  # idle-in-transaction and waiting are the two that explain a hang: a
  # connection held open by an unfinished transaction, or a lock nobody drops.
  READ=$(docker exec "$PG" psql -U postgres -d kalo_e2e -tAF',' -c \
    "SELECT count(*),
            count(*) FILTER (WHERE state='active'),
            count(*) FILTER (WHERE state='idle in transaction'),
            count(*) FILTER (WHERE wait_event_type='Lock')
     FROM pg_stat_activity" 2>/dev/null | tr -d '\r')

  echo "$TS,${PROBE_MS:-0},${PROBE_CODE:-000},${BE_CPU:-0},${BE_MEM:-0},${PG_CPU:-0},${READ:-0,0,0,0}" >> "$OUT"

  sleep "$INTERVAL"
done
