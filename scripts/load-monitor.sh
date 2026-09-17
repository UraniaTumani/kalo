#!/usr/bin/env bash
# Samples what the machine and the stack are doing while a load test runs.
#
#   bash scripts/load-monitor.sh <output.csv> &
#   MONITOR=$!
#   ... run the test ...
#   kill $MONITOR
#
# Container CPU and memory come from docker stats, PostgreSQL connections from
# pg_stat_activity, and JVM heap from the actuator's Prometheus endpoint, which
# is admin-only — so the caller passes a token if it wants that column filled.
set -u

OUT="${1:-load-resources.csv}"
INTERVAL="${MONITOR_INTERVAL:-5}"
ADMIN_TOKEN="${ADMIN_TOKEN:-}"
BASE="${BASE_URL:-http://localhost:8083}"
PG="kalo-load-postgres-1"
BE="kalo-load-backend-1"
FE="kalo-load-frontend-1"

echo "ts,backend_cpu_pct,backend_mem_mb,pg_cpu_pct,pg_mem_mb,nginx_cpu_pct,nginx_mem_mb,pg_connections,pg_active,jvm_heap_used_mb,backend_health" > "$OUT"

pct() { echo "$1" | tr -d '%' ; }
mb() {
  # docker stats gives "123.4MiB / 1.5GiB"; take the used half and normalise.
  local v="${1%% /*}"
  case "$v" in
    *GiB) awk -v x="${v%GiB}" 'BEGIN{printf "%.0f", x*1024}' ;;
    *MiB) awk -v x="${v%MiB}" 'BEGIN{printf "%.0f", x}' ;;
    *KiB) awk -v x="${v%KiB}" 'BEGIN{printf "%.1f", x/1024}' ;;
    *) echo 0 ;;
  esac
}

while true; do
  TS=$(date +%H:%M:%S)

  STATS=$(docker stats --no-stream --format '{{.Name}}|{{.CPUPerc}}|{{.MemUsage}}' "$BE" "$PG" "$FE" 2>/dev/null)

  BE_LINE=$(echo "$STATS" | grep "$BE" | head -1)
  PG_LINE=$(echo "$STATS" | grep "$PG" | head -1)
  FE_LINE=$(echo "$STATS" | grep "$FE" | head -1)

  BE_CPU=$(pct "$(echo "$BE_LINE" | cut -d'|' -f2)")
  BE_MEM=$(mb "$(echo "$BE_LINE" | cut -d'|' -f3)")
  PG_CPU=$(pct "$(echo "$PG_LINE" | cut -d'|' -f2)")
  PG_MEM=$(mb "$(echo "$PG_LINE" | cut -d'|' -f3)")
  FE_CPU=$(pct "$(echo "$FE_LINE" | cut -d'|' -f2)")
  FE_MEM=$(mb "$(echo "$FE_LINE" | cut -d'|' -f3)")

  CONNS=$(docker exec "$PG" psql -U "${DB_USERNAME:-kalo_load}" -d "${DB_NAME:-kalo_load}" -tAc \
    "SELECT count(*) FROM pg_stat_activity" 2>/dev/null | tr -d '\r')
  ACTIVE=$(docker exec "$PG" psql -U "${DB_USERNAME:-kalo_load}" -d "${DB_NAME:-kalo_load}" -tAc \
    "SELECT count(*) FROM pg_stat_activity WHERE state='active'" 2>/dev/null | tr -d '\r')

  HEAP=""
  if [ -n "$ADMIN_TOKEN" ]; then
    HEAP=$(docker exec "$BE" wget -qO- --header="Authorization: Bearer $ADMIN_TOKEN" \
      http://127.0.0.1:8080/actuator/prometheus 2>/dev/null \
      | awk '/^jvm_memory_used_bytes.*area="heap"/ {s+=$2} END {if (s>0) printf "%.0f", s/1048576}')
  fi

  HEALTH=$(curl -s -o /dev/null -w '%{http_code}' --max-time 3 "$BASE/actuator/health" 2>/dev/null)

  echo "$TS,${BE_CPU:-0},${BE_MEM:-0},${PG_CPU:-0},${PG_MEM:-0},${FE_CPU:-0},${FE_MEM:-0},${CONNS:-0},${ACTIVE:-0},${HEAP:-},${HEALTH:-0}" >> "$OUT"

  sleep "$INTERVAL"
done
