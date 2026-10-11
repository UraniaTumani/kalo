#!/usr/bin/env bash
# Runs the KALO load suite against an isolated stack.
#
#   bash scripts/load-test.sh              # every scenario at every level
#   bash scripts/load-test.sh search       # one scenario
#
#   bash scripts/load-test.sh --baseline   # rate limiting off, labelled as such
#
# Contention needs a starved fleet rather than a wide one, so it is asked for
# separately and seeded differently:
#
#   COMPANIES=3 DRIVERS_PER_COMPANY=1 CUSTOMERS=30 \
#     bash scripts/load-test.sh contention
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

# Keeps the fleet visible: a position older than TWO MINUTES takes its driver
# out of search — MAX_LOCATION_AGE in TaxiAvailabilityFinder — so without this a
# long run would measure an emptying system, and would look faster for it.
#
# The breach file is how the heartbeat tells this script that its own cycle has
# grown past that window. A warning in a log nobody greps is the same as no
# warning, so the validity gate at the end reads this file and refuses to call
# the run valid.
echo "▸ starting the driver heartbeat"
BREACH="$RESULTS/heartbeat-breach.txt"
: > "$BREACH"
BASE_URL="$BASE_HOST" HEARTBEAT_BREACH_FILE="$BREACH" \
  bash scripts/load-heartbeat.sh > "$RESULTS/heartbeat.log" 2>&1 &
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

  # k6's own verdict, where the validity gate can read it.
  #
  # A failed threshold is k6 saying the run did not meet the preconditions its
  # scenario declared. The contention gate used to have no way to know, and
  # reported a run VALID that k6 had already failed.
  echo "${PIPESTATUS[0]}" > "$RESULTS/$name-k6-exit.txt"

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

# Contention is deliberately NOT part of `all`.
#
# It needs the opposite world from every other scenario — three companies with
# one driver each, rather than a hundred with two — because contention is what
# happens when there is no spare driver. Running it against the wide fleet the
# other scenarios need would produce thirty accepts that all succeed, which is
# the precise false positive the scenario exists to rule out.
#
#   COMPANIES=3 DRIVERS_PER_COMPANY=1 CUSTOMERS=30 bash scripts/load-test.sh contention
#
case "$ONLY" in
  contention)
    run_k6 "06-contention" "06-contention.js" \
      "-e RACE_VUS=${RACE_VUS:-30} -e RACE_DRIVERS=${RACE_DRIVERS:-1}"

    # Judged by its own gate, because a clean contention run and a contention
    # run that never happened produce identical invariants.
    #
    # The gate is told the same two numbers the scenario was given, so its
    # winner and participant checks are exact rather than approximate. Leaving
    # it to infer them is how "at least one accept won" came to pass a run in
    # which a driver could have taken thirty rides.
    EXPECTED_RACE_VUS="${RACE_VUS:-30}" EXPECTED_RACE_DRIVERS="${RACE_DRIVERS:-1}" \
      bash scripts/load-contention-check.sh \
      "$RESULTS/06-contention-summary.json" "$RESULTS" \
      | tee "$RESULTS/contention-validity.txt" || CONTENTION_INVALID=1
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

# ---------------------------------------------------------------------------
# Was the run valid at all?
#
# Every number above can look excellent for the wrong reason. A fleet that aged
# out of search returns nothing, fast; a run that attempted little has no
# errors; and "no driver held two active rides" is a perfect score for a test
# that never tried to give one two. So the invariants are reported first and
# then interrogated, and this section decides whether they are worth reading.
#
# Deliberately a gate rather than a note. The failure mode being guarded against
# is a confident report built on an empty system.
# ---------------------------------------------------------------------------

q() { docker exec kalo-load-postgres-1 psql -U kalo_load -d kalo_load -tAc "$1" 2>/dev/null | tr -d '\r'; }

echo
echo "▸ run validity"

VALID=1
note() { echo "  $1"; }
fail() { echo "  ✗ $1"; VALID=0; }

# 0. Did the contention gate accept the run?
#
#    It already printed its reasons above; this is what makes them count.
#    CONTENTION_INVALID was set by the contention case and then read by
#    nothing, so a run the gate had refused still reached the bottom of this
#    script and announced itself valid. The gate's whole job is to refuse, and
#    a refusal nobody acts on is the same as not checking.
if [ "${CONTENTION_INVALID:-0}" != "0" ]; then
  fail "the contention gate refused this run — see contention-validity.txt above"
fi

# 1. Did the heartbeat keep up? The heartbeat itself decides this and writes the
#    verdict; anything in the file means some driver aged out while the run was
#    being measured.
if [ -s "$BREACH" ]; then
  fail "the GPS heartbeat fell behind the 120s freshness window:"
  sed 's/^/      /' "$BREACH"
  note "  every latency figure after the first breach describes an emptying fleet"
else
  note "✓ GPS heartbeat stayed inside the freshness window"
fi

# 2. Is the fleet fresh NOW? A pass that finished long ago leaves stale rows
#    even if no cycle ever breached, so the end state is checked directly
#    against the production rule rather than inferred from timings.
STALE=$(q "SELECT count(*) FROM drivers d JOIN driver_locations dl ON dl.driver_id = d.id WHERE d.availability_status = 'ONLINE' AND dl.location_updated_at < now() - interval '120 seconds'")
ONLINE=$(q "SELECT count(*) FROM drivers WHERE availability_status = 'ONLINE'")

if [ "${STALE:-1}" = "0" ]; then
  note "✓ all ${ONLINE:-?} ONLINE drivers have a position inside 120s"
else
  fail "${STALE} of ${ONLINE} ONLINE drivers have a stale position — search was returning less than the full fleet"
fi

# 3. Did the run touch the fleet it claims to have tested? Distinct counts, not
#    totals: one company serving every ride would pass a totals check.
COMPANIES=$(q "SELECT count(*) FROM taxi_companies WHERE verification_status='APPROVED'")
COMPANIES_USED=$(q "SELECT count(DISTINCT company_id) FROM rides")
CUSTOMERS_USED=$(q "SELECT count(DISTINCT customer_id) FROM rides")
RIDES=$(q "SELECT count(*) FROM rides")

note "companies approved ${COMPANIES:-?}, companies that received a ride ${COMPANIES_USED:-?}"
note "distinct customers that booked ${CUSTOMERS_USED:-?}, ride attempts ${RIDES:-?}"

if [ "${RIDES:-0}" -lt "${MIN_RIDES:-1}" ]; then
  fail "only ${RIDES:-0} ride attempts — too little traffic for the invariants below to mean anything"
fi

if [ "${COMPANIES_USED:-0}" -lt "${MIN_COMPANIES_USED:-1}" ]; then
  fail "only ${COMPANIES_USED:-0} companies received a ride, against ${COMPANIES:-?} approved"
fi

# 4. Were the rides real, or were they refusals? A run that degraded into
#    measuring rejection paths gets faster as it gets less useful.
REJECTED=$(grep -ho 'kalo_business_rejections[^0-9]*[0-9]*' "$RESULTS"/*.log 2>/dev/null | grep -oE '[0-9]+$' | awk '{s+=$1} END {print s+0}')
LIMITED=$(grep -ho 'kalo_rate_limited[^0-9]*[0-9]*' "$RESULTS"/*.log 2>/dev/null | grep -oE '[0-9]+$' | awk '{s+=$1} END {print s+0}')

note "business rejections ${REJECTED:-0}, rate limited ${LIMITED:-0}"

if [ "${LIMITED:-0}" -gt "${MAX_RATE_LIMITED:-0}" ]; then
  fail "${LIMITED} requests were rate limited — the run measured the limiter, not capacity"
fi

# 5. Did the timeout sweep keep up? If it fell behind, rides stopped timing out
#    and the lifecycle under test was not the one production runs.
SWEPT=$(q "SELECT count(*) FROM rides WHERE status='NO_RESPONSE'")
note "rides timed out by the sweep ${SWEPT:-?}"

echo
if [ "$VALID" = "1" ]; then
  echo "  ✓ run is valid — the figures above describe a working system under load"
else
  echo "  ✗ RUN IS NOT VALID. Fix the causes above and run again; do not quote these numbers."
fi

echo
echo "▸ results in $RESULTS"
echo "▸ leaving the stack up for inspection; tear it down with:"
echo "    $COMPOSE down -v"
