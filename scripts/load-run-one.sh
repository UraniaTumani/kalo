#!/usr/bin/env bash
# Runs ONE load scenario against an already-running isolated stack.
#
#   bash scripts/load-run-one.sh <name> <script.js> "<k6 -e args>"
#
# The full suite (scripts/load-test.sh) chains fifteen runs back to back, which
# is how it should work on a dedicated host. On a laptop sharing eight cores
# with the stack it is measuring, that chain took Docker Desktop down twice
# mid-run — and a run that ends because the daemon stopped answering tells you
# nothing about KALO. So this exists: one scenario, checked before and after, so
# a degraded run can be marked invalid instead of being reported as a result.
#
# Before the run it verifies the daemon answers and all three containers are
# healthy. After it, it checks the same again and says so in the log, because
# the failure mode that matters is the stack dying *during* the measurement.
set -u

cd "$(dirname "$0")/.." || exit 1

NAME="${1:?scenario name}"
SCRIPT="${2:?k6 script}"
ENV_ARGS="${3:-}"

COMPOSE="docker compose -f docker-compose.load.yml --env-file .env.load.example"
BASE_NET="http://frontend:80"
NETWORK="kalo-load_default"
DATA="load/.data"
RESULTS="${RESULTS:-load/results/single}"

mkdir -p "$RESULTS"

health_ok() {
  local states
  docker info --format '{{.ServerVersion}}' > /dev/null 2>&1 || return 1
  states=$($COMPOSE ps --format '{{.Service}}:{{.Health}}' 2>/dev/null)
  [ -n "$states" ] || return 1
  echo "$states" | grep -qv ':healthy' && return 1
  return 0
}

echo "════════════════════════════════════════════════════════════════"
echo " $NAME"
echo "════════════════════════════════════════════════════════════════"

if ! health_ok; then
  echo "✗ PRE-FLIGHT FAILED — daemon or stack not healthy. Not running."
  $COMPOSE ps 2>&1 | head -10
  exit 2
fi
echo "✓ pre-flight: daemon up, all containers healthy"

CSV="$RESULTS/$NAME-resources.csv"
ADMIN_TOKEN="${ADMIN_TOKEN:-}" bash scripts/load-monitor.sh "$CSV" &
MONITOR=$!

STARTED=$(date +%s)

# MSYS_NO_PATHCONV stops Git Bash rewriting /scripts into a Windows path.
# shellcheck disable=SC2086
MSYS_NO_PATHCONV=1 docker run --rm -i \
  --network "$NETWORK" \
  -v "$(pwd)/load:/scripts:ro" \
  -v "$(pwd)/$DATA:/data:ro" \
  -v "$(pwd)/$RESULTS:/results" \
  -e BASE_URL="$BASE_NET" \
  -e RUN_ID="$(date +%s | tail -c 4)" \
  $ENV_ARGS \
  grafana/k6:latest run \
    --summary-export="/results/$NAME-summary.json" \
    --quiet \
    "/scripts/$SCRIPT" 2>&1 | tee "$RESULTS/$NAME.log" | tail -40

K6_EXIT=${PIPESTATUS[0]}
ELAPSED=$(( $(date +%s) - STARTED ))

touch "$CSV.stop"
wait "$MONITOR" 2>/dev/null
kill "$MONITOR" 2>/dev/null

echo
if health_ok; then
  echo "✓ post-flight: daemon up, all containers still healthy"
  VERDICT=VALID
else
  echo "✗ post-flight: the stack degraded DURING this run."
  echo "  Treat these numbers as a measurement of the laptop, not of KALO."
  $COMPOSE ps 2>&1 | head -10
  VERDICT=INVALID
fi

echo "$NAME exit=$K6_EXIT elapsed=${ELAPSED}s verdict=$VERDICT" | tee -a "$RESULTS/runs.txt"
[ "$VERDICT" = VALID ] || exit 3
