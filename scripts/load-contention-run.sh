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

# Enough for every scenario's customer band, not just the race's.
#
# The scenarios draw from disjoint slices of the fixture -- RACE_VUS for the
# race, then six for duplicates and one for accept_vs_cancel -- because a
# customer may hold only one active ride and overlapping slices let one
# scenario's leftovers refuse another's bookings. Too short a fixture makes the
# bands wrap onto each other, and for accept_vs_cancel, which gets one virtual
# user and one chance, that is the difference between a recorded race and a
# failed run. The layout lives in load/06-contention.js; this is the number it
# implies.
CUSTOMERS_NEEDED=$(( RACE_VUS + 6 + 1 + 1 ))

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

# ------------------------------------------------------------- this run's own
#
# THE FIXTURES ARE THE RUN'S IDENTITY, and every count below is scoped to them.
#
# load-seed.sh truncates load/.data/partners.json and users.json on each seed
# (`>` then `>>`), so after an additive seed they name only the newly created
# companies and customers. The scenario reads nothing else: setup() logs in as
# each partner in partners.json and asks the API for that partner's own company
# id and ONLINE driver. Companies from an earlier run are not in the file, so
# they are not in the fleet, and that is what isolates one run from the next.
#
# Counting globally instead would defeat it. The load database is kept between
# runs, so a global count mixes this run's companies with every earlier run's:
# a seed that half failed could leave one new company and two old ones and
# still satisfy "three are bookable", while the scenario -- which can only see
# the file -- would race a fleet of one and be refused later for a shortfall
# nobody had been warned about.
DATA="${DATA:-load/.data}"
PARTNERS_FILE="$DATA/partners.json"
USERS_FILE="$DATA/users.json"

[ -f "$PARTNERS_FILE" ] || die "no partner fixture at ${PARTNERS_FILE} — seed first; the scenario can only target companies named there"
[ -f "$USERS_FILE" ] || die "no customer fixture at ${USERS_FILE} — seed first"

# Phones, which is all the fixtures carry. A company's row holds the same phone
# as its owning partner user, so they identify the companies this run may use.
PARTNER_PHONES=$(grep -oE '\+[0-9]+' "$PARTNERS_FILE" | sort -u)
CUSTOMER_PHONES=$(grep -oE '\+[0-9]+' "$USERS_FILE" | sort -u)

FIXTURE_PARTNERS=$(echo "$PARTNER_PHONES" | grep -c . || true)
FIXTURE_CUSTOMERS=$(echo "$CUSTOMER_PHONES" | grep -c . || true)

[ "$FIXTURE_PARTNERS" -ge "$MIN_COMPANIES" ] || die "the partner fixture names ${FIXTURE_PARTNERS} company(ies) but ${MIN_COMPANIES} are needed (${RACE_DRIVERS} raced + 1 for duplicates + 1 for cancel). Re-seed with: COMPANIES=${MIN_COMPANIES} DRIVERS_PER_COMPANY=1 CUSTOMERS=${CUSTOMERS_NEEDED} bash scripts/load-seed.sh"
[ "$FIXTURE_CUSTOMERS" -ge "$CUSTOMERS_NEEDED" ] || die "the customer fixture names ${FIXTURE_CUSTOMERS} customer(s) but ${CUSTOMERS_NEEDED} are needed: ${RACE_VUS} for the race, six for duplicates, one for accept-versus-cancel and one spare, each from a band no other scenario touches. Re-seed with CUSTOMERS=${CUSTOMERS_NEEDED}"

# A quoted SQL list of exactly those phones.
PHONE_LIST=$(echo "$PARTNER_PHONES" | sed "s/^/'/; s/\$/',/" | tr -d '\n' | sed "s/,\$//")
[ -n "$PHONE_LIST" ] || die "could not read any partner phone from ${PARTNERS_FILE}"

# BOOKABLE, defined exactly as TaxiSearchRepository.findAvailableTaxis defines
# it, because a company the search cannot return is a company the race cannot
# contend for. Getting this wrong in either direction is costly: too lax and
# the run reports zero contested accepts without being able to say why, too
# strict and it refuses a fleet that would have worked.
#
# Every clause is one of that query's, with the enum values
# TaxiAvailabilityFinder passes it:
#
#   assignment active, driver ACTIVE and ONLINE, vehicle ACTIVE
#   company APPROVED, ACTIVE and booking_enabled
#   GPS fix newer than MAX_LOCATION_AGE (two minutes)
#   driver and company licences valid today (F36)
#
# ONLINE also means the driver is not BUSY, since availability is one column,
# so a driver still holding a ride from an earlier run drops out by itself.
BOOKABLE=$(q "
  SELECT count(DISTINCT c.id)
  FROM driver_vehicle_assignments a
  JOIN drivers d ON d.id = a.driver_id
  JOIN vehicles v ON v.id = a.vehicle_id
  JOIN taxi_companies c ON c.id = d.company_id
  JOIN driver_locations dl ON dl.driver_id = d.id
  WHERE c.phone IN ($PHONE_LIST)
    AND a.active = true
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

# Which company ids those are, printed so the run's own log says what it
# targeted. Isolation claimed is worth less than isolation recorded.
TARGET_IDS=$(q "SELECT string_agg(id::text, ', ' ORDER BY id) FROM taxi_companies WHERE phone IN ($PHONE_LIST)")

# Everything else in the database, reported as context and never as a
# precondition: these are the companies the run must NOT touch.
OTHERS=$(q "SELECT count(*) FROM taxi_companies WHERE phone NOT IN ($PHONE_LIST)")
BUSY=$(q "SELECT count(*) FROM drivers WHERE availability_status = 'BUSY'")

case "${BOOKABLE}" in
  *[!0-9]*|'') die "could not read the fleet from ${DB_NAME} on ${PG}" ;;
esac

say "  this run targets company id(s): ${TARGET_IDS:-none}"
say "  of those, ${BOOKABLE} are bookable; fixture names ${FIXTURE_PARTNERS} partner(s) and ${FIXTURE_CUSTOMERS} customer(s)"
say "  untouched elsewhere in this database: ${OTHERS} company(ies), ${BUSY} BUSY driver(s)"

[ "$BOOKABLE" -ge "$MIN_COMPANIES" ] || die "only ${BOOKABLE} of this run's own companies are bookable but ${MIN_COMPANIES} are needed. Companies from earlier runs do not count and cannot be targeted: the scenario reads ${PARTNERS_FILE} and nothing else. Re-seed with: COMPANIES=${MIN_COMPANIES} DRIVERS_PER_COMPANY=1 CUSTOMERS=${CUSTOMERS_NEEDED} bash scripts/load-seed.sh"

say "  the search query would return every one of them"

mkdir -p "$RESULTS"

# --------------------------------------------------------------- the heartbeat
#
# Positions age out of the search window in two minutes, and a fleet that has
# aged out produces a fast, clean, meaningless run. One pass first, so a broken
# heartbeat stops the run instead of being discovered in its results.
say ""
say "▸ heartbeat"
# The first pass runs to a file rather than through a pipe.
#
# It was written as `... | sed 's/^/  /' || die`, which never fired: a
# pipeline's exit status is its LAST command's, so `sed` succeeding masked the
# heartbeat failing, and a run whose fleet had aged out of search would have
# gone ahead and produced fast, clean, meaningless numbers. Caught by a test
# that makes the heartbeat exit non-zero -- which existed only because every
# stand-in used to exit 0 and nothing exercised this path at all.
FIRST_PASS="$RESULTS/heartbeat-first-pass.log"

if HEARTBEAT_ONCE=1 HEARTBEAT_BREACH_FILE="$RESULTS/heartbeat-breach.txt" \
     bash scripts/load-heartbeat.sh > "$FIRST_PASS" 2>&1; then
  sed 's/^/  /' "$FIRST_PASS"
else
  sed 's/^/  /' "$FIRST_PASS"
  die "the heartbeat failed its first pass — the fleet would age out of search mid-run and every latency figure would describe an emptying fleet"
fi

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
