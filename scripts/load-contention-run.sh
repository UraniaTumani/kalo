#!/usr/bin/env bash
# Runs ONE contention scenario against the isolated load stack, destroying
# nothing.
#
#   bash scripts/load-contention-run.sh
#   RACE_VUS=30 RACE_DRIVERS=1 bash scripts/load-contention-run.sh
#
# WHY THIS EXISTS. scripts/load-test.sh begins with `docker compose down -v`,
# which deletes the load database's volume. That is correct for the full suite,
# whose numbers mean nothing against a dirty world, and it is the wrong thing
# entirely when the point is to run one scenario and then INVESTIGATE what it
# left behind. The first real contention run was therefore driven by hand, a
# dozen commands at a time, with the destructive script sitting one typo away.
#
# So: one reviewed entry point, which contains no `down`, no `-v` and no
# truncate anywhere. scripts/test-load-contention-run.sh asserts that
# statically, because the guarantee is only worth as much as its proof.
#
# WHAT IT WILL NOT DO. It will not create, reset or seed anything on its own
# beyond bringing up containers that are not running. If the fleet is not
# already the shape the scenario needs, it says what is wrong and stops rather
# than fixing it, because fixing it means writing to a database somebody may be
# in the middle of reading.
set -u

cd "$(dirname "$0")/.." || exit 1

COMPOSE="docker compose -f docker-compose.load.yml --env-file .env.load.example"
PG="${PG_CONTAINER:-kalo-load-postgres-1}"
DB_USER="${DB_USERNAME:-kalo_load}"
DB_NAME="${DB_NAME:-kalo_load}"

RACE_VUS="${RACE_VUS:-30}"
RACE_DRIVERS="${RACE_DRIVERS:-1}"

# The scenario needs one company per raced driver, plus one for duplicates and
# one for accept-versus-cancel. Fewer and those two scenarios starve, which is
# how they recorded nothing at all on the first real run.
MIN_COMPANIES=$(( RACE_DRIVERS + 2 ))

STAMP=$(date +%Y%m%d-%H%M%S)
RESULTS="load/results/contention-$STAMP"

say() { echo "$1"; }
die() { echo "✗ $1" >&2; exit 1; }

q() {
  docker exec "$PG" psql -U "$DB_USER" -d "$DB_NAME" -tAc "$1" 2>/dev/null | tr -d '\r'
}

say "▸ contention run $STAMP"
say "  race: ${RACE_VUS} virtual users against ${RACE_DRIVERS} driver(s)"

# ------------------------------------------------------------------ the stack
#
# Brought up if it is down, never recreated. `up -d` on a running stack is a
# no-op for healthy containers and leaves volumes untouched.
if ! docker info --format '{{.ServerVersion}}' > /dev/null 2>&1; then
  die "the docker daemon is not answering"
fi

if [ -z "$($COMPOSE ps -q 2>/dev/null)" ]; then
  say "  stack is down — starting it (no volumes are touched)"
  $COMPOSE up -d --wait || die "the stack did not come up"
else
  say "  stack is already up — leaving it alone"
fi

STATES=$($COMPOSE ps --format '{{.Service}}:{{.Health}}' 2>/dev/null)
[ -n "$STATES" ] || die "no containers found for the load stack"
if echo "$STATES" | grep -qv ':healthy'; then
  echo "$STATES" | sed 's/^/    /'
  die "not every container is healthy"
fi
say "  all containers healthy"

# --------------------------------------------------------------- the fleet
#
# Checked, never corrected.
#
# BOOKABLE is the only count that matters, and it is defined here exactly as
# TaxiSearchRepository.findAvailableTaxis defines it, because a company the
# search cannot return is a company the race cannot contend for. Getting this
# wrong in either direction is costly: too lax and the run reports zero
# contested accepts without being able to say why, too strict and it refuses a
# fleet that would have worked.
#
# Every clause below is one of that query's, with the enum values
# TaxiAvailabilityFinder passes it:
#
#   assignment active, driver ACTIVE and ONLINE, vehicle ACTIVE
#   company APPROVED, ACTIVE and booking_enabled
#   GPS fix newer than MAX_LOCATION_AGE (two minutes)
#   driver and company licences valid today (F36)
#
# ONLINE also means the driver is not BUSY, since availability is one column —
# so a driver still holding a ride from an earlier run drops out of this count
# by itself. That is what lets this script run against a preserved database
# instead of requiring a fresh one.
BOOKABLE=$(q "
  SELECT count(DISTINCT c.id)
  FROM driver_vehicle_assignments a
  JOIN drivers d ON d.id = a.driver_id
  JOIN vehicles v ON v.id = a.vehicle_id
  JOIN taxi_companies c ON c.id = d.company_id
  JOIN driver_locations dl ON dl.driver_id = d.id
  WHERE a.active = true
    AND d.status = 'ACTIVE'
    AND d.availability_status = 'ONLINE'
    AND v.status = 'ACTIVE'
    AND c.verification_status = 'APPROVED'
    AND c.status = 'ACTIVE'
    AND c.booking_enabled = true
    AND dl.location_updated_at >= now() - interval '2 minutes'
    AND d.license_expiry_date >= CURRENT_DATE
    AND c.license_expiry_date IS NOT NULL
    AND c.license_expiry_date >= CURRENT_DATE
")

CUSTOMERS=$(q "SELECT count(*) FROM users WHERE role = 'CUSTOMER'")
BUSY=$(q "SELECT count(*) FROM drivers WHERE availability_status = 'BUSY'")

case "${BOOKABLE}${CUSTOMERS}" in
  *[!0-9]*|'') die "could not read the fleet from ${DB_NAME} on ${PG}" ;;
esac

say "  fleet: ${BOOKABLE} bookable companies, ${CUSTOMERS} customers (${BUSY} driver(s) BUSY from earlier runs)"

[ "$BOOKABLE" -ge "$MIN_COMPANIES" ] || die "${BOOKABLE} company(ies) are bookable but ${MIN_COMPANIES} are needed (${RACE_DRIVERS} raced + 1 for duplicates + 1 for cancel). Nothing here is reset; seed more alongside what exists with: COMPANIES=${MIN_COMPANIES} DRIVERS_PER_COMPANY=1 CUSTOMERS=${RACE_VUS} bash scripts/load-seed.sh"
[ "$CUSTOMERS" -ge "$RACE_VUS" ] || die "${CUSTOMERS} customers but ${RACE_VUS} are needed, one per virtual user — a customer can hold only one active ride, so sharing them would fail attempts for a reason unrelated to the race"

say "  the search query would return every one of them"

mkdir -p "$RESULTS"

# --------------------------------------------------------------- the heartbeat
#
# Positions age out of the search window in two minutes, and a fleet that has
# aged out produces a fast, clean, meaningless run. One pass first, so a broken
# heartbeat stops the run instead of being discovered in its results.
say ""
say "▸ heartbeat"
HEARTBEAT_ONCE=1 HEARTBEAT_BREACH_FILE="$RESULTS/heartbeat-breach.txt" \
  bash scripts/load-heartbeat.sh 2>&1 | sed 's/^/  /' || die "the heartbeat failed its first pass"

HEARTBEAT_BREACH_FILE="$RESULTS/heartbeat-breach.txt" \
  bash scripts/load-heartbeat.sh >> "$RESULTS/heartbeat.log" 2>&1 &
HEARTBEAT=$!
say "  running in the background (pid $HEARTBEAT)"

# Stopped however this script ends, so a stray heartbeat cannot outlive the run
# and keep writing to a database somebody is inspecting.
cleanup() {
  kill "$HEARTBEAT" 2>/dev/null
  wait "$HEARTBEAT" 2>/dev/null
}
trap cleanup EXIT

# ------------------------------------------------------------------- the run
#
# The instant the run begins, taken from the database's own clock so it is
# comparable with the rows the run will create. The gate counts assignments
# from here rather than across the whole table, which is what lets it judge a
# run against a database that still holds earlier ones.
RUN_SINCE=$(q "SELECT now()")
[ -n "$RUN_SINCE" ] || die "could not read the current time from the database"

say ""
RESULTS="$RESULTS" bash scripts/load-run-one.sh 06-contention 06-contention.js \
  "-e RACE_VUS=$RACE_VUS -e RACE_DRIVERS=$RACE_DRIVERS"
RUN_STATUS=$?

cleanup
trap - EXIT

# ------------------------------------------------------------------ the gate
#
# Always run, whatever the run status, because its refusals are the useful
# output when something went wrong. Its verdict is what decides the run, not
# this script's opinion of it.
say ""
EXPECTED_RACE_VUS="$RACE_VUS" EXPECTED_RACE_DRIVERS="$RACE_DRIVERS" \
RUN_SINCE="$RUN_SINCE" \
  bash scripts/load-contention-check.sh "$RESULTS/06-contention-summary.json" "$RESULTS" \
  | tee "$RESULTS/contention-validity.txt"
GATE_STATUS=${PIPESTATUS[0]}

say ""
say "▸ results in $RESULTS"
say "  the load database and its volume are untouched; nothing was reset"

if [ "$GATE_STATUS" != "0" ] || [ "$RUN_STATUS" != "0" ]; then
  say ""
  say "✗ this run is NOT reportable (run status $RUN_STATUS, gate status $GATE_STATUS)"
  exit 1
fi

say ""
say "✓ run complete and valid"
