#!/usr/bin/env bash
# Runs the KALO load suite against an isolated stack.
#
#   bash scripts/load-test.sh              # every scenario at every level
#   bash scripts/load-test.sh search       # one scenario
#   bash scripts/load-test.sh --baseline   # rate limiting off, labelled as such
#
# Brings the stack up on its own database and ports, seeds a realistic world
# through the real API, runs k6 in a container on the same docker network, and
# samples CPU, memory, PostgreSQL connections and JVM heap throughout.
#
# Results land in load/results/<timestamp>/ as k6 JSON summaries plus a CSV of
# resource samples per run.
#
# The machine matters. k6 runs beside the stack it is measuring, so both compete
# for the same cores — the numbers are a floor for a dedicated host, not a
# ceiling. Every scenario ramps rather than stepping, so a run can be stopped
# before it takes the machine down with it.
set -u

cd "$(dirname "$0")/.." || exit 1

COMPOSE="docker compose -f docker-compose.load.yml --env-file .env.load.example"
BASE_HOST="http://localhost:8083"
BASE_NET="http://frontend:80"
NETWORK="kalo-load_default"
STAMP=$(date +%Y%m%d-%H%M%S)
RESULTS="load/results/$STAMP"
DATA="load/.data"

ONLY="${1:-all}"
BASELINE=no
if [ "$ONLY" = "--baseline" ]; then
  BASELINE=yes
  ONLY=all
  export RATE_LIMIT_ENABLED=false
fi

mkdir -p "$RESULTS" "$DATA"

echo "════════════════════════════════════════════════════════════════"
echo " KALO load test — $STAMP"
[ "$BASELINE" = yes ] && echo " BASELINE RUN: rate limiting DISABLED (not a realistic configuration)"
echo "════════════════════════════════════════════════════════════════"

echo
echo "▸ bringing up the isolated stack (db kalo_load, port 8083)"
$COMPOSE down -v > /dev/null 2>&1
if ! $COMPOSE up --build -d --wait > "$RESULTS/stack-up.log" 2>&1; then
  echo "✗ the stack did not come up. See $RESULTS/stack-up.log"
  exit 1
fi

echo "▸ seeding a realistic world through the API"
BASE_URL="$BASE_HOST" OUT_DIR="$DATA" bash scripts/load-seed.sh | tee "$RESULTS/seed.log"

if ! grep -q '"phone"' "$DATA/users.json" 2>/dev/null; then
  echo "✗ seeding produced no users; stopping rather than measuring an empty system"
  exit 1
fi

# Keeps the fleet visible: a position older than five minutes takes its driver
# out of search, so without this a long run would measure an emptying system.
echo "▸ starting the driver heartbeat"
BASE_URL="$BASE_HOST" bash scripts/load-heartbeat.sh > "$RESULTS/heartbeat.log" 2>&1 &
HEARTBEAT=$!
trap 'kill $HEARTBEAT 2>/dev/null' EXIT

# An admin token so the monitor can read JVM heap from the actuator.
ADMIN_PHONE=$(grep -oE '\+3559000[0-9]+' "$RESULTS/seed.log" | head -1)
ADMIN_TOKEN=$(curl -s -X POST "$BASE_HOST/api/v1/auth/login" \
  -H 'Content-Type: application/json' -H 'X-Forwarded-For: 172.31.0.1' \
  -d "{\"phone\":\"$ADMIN_PHONE\",\"password\":\"LoadTest123!\"}" \
  | grep -oE '"accessToken":"[^"]+' | cut -d'"' -f4)

run_k6() {
  local name="$1" script="$2" env_args="$3"

  echo
  echo "────────────────────────────────────────────────────────────────"
  echo " $name"
  echo "────────────────────────────────────────────────────────────────"

  ADMIN_TOKEN="$ADMIN_TOKEN" BASE_URL="$BASE_HOST" \
    bash scripts/load-monitor.sh "$RESULTS/$name-resources.csv" &
  local monitor=$!

  # MSYS_NO_PATHCONV stops Git Bash rewriting /scripts into a Windows path.
  # shellcheck disable=SC2086
  MSYS_NO_PATHCONV=1 docker run --rm -i \
    --network "$NETWORK" \
    -v "$(pwd)/load:/scripts:ro" \
    -v "$(pwd)/$DATA:/data:ro" \
    -v "$(pwd)/$RESULTS:/results" \
    -e BASE_URL="$BASE_NET" \
    -e RUN_ID="$(date +%S%N | tail -c 4)" \
    $env_args \
    grafana/k6:latest run \
      --summary-export="/results/$name-summary.json" \
      --quiet \
      "/scripts/$script" 2>&1 | tee "$RESULTS/$name.log" | tail -30

  kill "$monitor" 2>/dev/null
  wait "$monitor" 2>/dev/null

  # Let the JVM and the connection pool settle before the next level.
  sleep 15
}

case "$ONLY" in
  all|registration)
    run_k6 "01-registration-100"  "01-registration.js" "-e TARGET=100 -e VUS=10 -e HOLD=45s"
    run_k6 "01-registration-500"  "01-registration.js" "-e TARGET=500 -e VUS=25 -e HOLD=90s"
    run_k6 "01-registration-1000" "01-registration.js" "-e TARGET=1000 -e VUS=40 -e HOLD=150s"
    ;;
esac

case "$ONLY" in
  all|login)
    run_k6 "02-login-50"  "02-login.js" "-e VUS=50  -e HOLD=60s"
    run_k6 "02-login-100" "02-login.js" "-e VUS=100 -e HOLD=60s"
    run_k6 "02-login-250" "02-login.js" "-e VUS=250 -e HOLD=60s"
    ;;
esac

case "$ONLY" in
  all|search)
    run_k6 "03-search-50"  "03-search.js" "-e VUS=50  -e HOLD=90s"
    run_k6 "03-search-100" "03-search.js" "-e VUS=100 -e HOLD=90s"
    run_k6 "03-search-250" "03-search.js" "-e VUS=250 -e HOLD=90s"
    ;;
esac

case "$ONLY" in
  all|rides)
    run_k6 "04-rides-25"  "04-ride-creation.js" "-e VUS=25  -e HOLD=60s"
    run_k6 "04-rides-50"  "04-ride-creation.js" "-e VUS=50  -e HOLD=60s"
    run_k6 "04-rides-100" "04-ride-creation.js" "-e VUS=100 -e HOLD=60s"
    ;;
esac

case "$ONLY" in
  all|mixed)
    run_k6 "05-mixed" "05-mixed.js" "-e CUSTOMER_VUS=40 -e PARTNER_VUS=6 -e DURATION=10m"
    ;;
esac

echo
echo "▸ database invariants after the run"
{
  echo "active rides per customer above 1: $(docker exec kalo-load-postgres-1 psql -U kalo_load -d kalo_load -tAc "SELECT count(*) FROM (SELECT customer_id FROM rides WHERE status IN ('REQUESTED','DRIVER_ASSIGNED','DRIVER_ARRIVING','DRIVER_ARRIVED','IN_PROGRESS') GROUP BY customer_id HAVING count(*) > 1) x" 2>/dev/null | tr -d '\r')"
  echo "active rides per driver above 1:   $(docker exec kalo-load-postgres-1 psql -U kalo_load -d kalo_load -tAc "SELECT count(*) FROM (SELECT driver_id FROM rides WHERE driver_id IS NOT NULL AND status IN ('DRIVER_ASSIGNED','DRIVER_ARRIVING','DRIVER_ARRIVED','IN_PROGRESS') GROUP BY driver_id HAVING count(*) > 1) x" 2>/dev/null | tr -d '\r')"
  echo "drivers with two active vehicles:  $(docker exec kalo-load-postgres-1 psql -U kalo_load -d kalo_load -tAc "SELECT count(*) FROM (SELECT driver_id FROM driver_vehicle_assignments WHERE active GROUP BY driver_id HAVING count(*) > 1) x" 2>/dev/null | tr -d '\r')"
  echo "total rides created:               $(docker exec kalo-load-postgres-1 psql -U kalo_load -d kalo_load -tAc "SELECT count(*) FROM rides" 2>/dev/null | tr -d '\r')"
  echo "total users:                       $(docker exec kalo-load-postgres-1 psql -U kalo_load -d kalo_load -tAc "SELECT count(*) FROM users" 2>/dev/null | tr -d '\r')"
  echo "max PostgreSQL connections seen:   $(cat "$RESULTS"/*-resources.csv 2>/dev/null | awk -F, 'NR>1 && $8+0>m {m=$8} END {print m+0}')"
} | tee "$RESULTS/invariants.txt"

echo
echo "▸ results in $RESULTS"
echo "▸ leaving the stack up for inspection; tear it down with:"
echo "    $COMPOSE down -v"
