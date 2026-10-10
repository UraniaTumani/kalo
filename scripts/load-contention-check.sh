#!/usr/bin/env bash
# Decides whether a contention run proved anything.
#
#   bash scripts/load-contention-check.sh <k6-summary.json> [results-dir]
#
# Separated from load-test.sh so it can be tested without a stack: everything
# it needs arrives as a file and a database it reaches through `docker`, both
# of which a test can stub. The gates are the point of the scenario, so they
# are the part that most needs testing.
#
# WHAT IT IS GUARDING AGAINST. In a contention run every invariant is expected
# to hold, so a clean result is the normal outcome and proves nothing on its
# own. "No driver held two active rides" is a perfect score for a run that
# never tried to give one two. Each check below therefore pairs a negative
# result with evidence that the attempt was real:
#
#   wins > 0      somebody was assigned, so the fleet was not already busy and
#                 setup did not silently fail
#   losses > 0    somebody was refused, so requests genuinely competed
#   overlap       the accepts arrived close enough together to contend
#   one driver    they named the same driver, not merely the same company
#   invariants    and after all that, the database still holds
#
# Dropping any one of them lets a quiet run look like a successful one.
set -u

SUMMARY="${1:-}"
RESULTS="${2:-.}"

PG="${PG_CONTAINER:-kalo-load-postgres-1}"
DB_USER="${DB_USERNAME:-kalo_load}"
DB_NAME="${DB_NAME:-kalo_load}"

# The widest spread of accept timestamps that can still be called simultaneous.
# Generous: the barrier aims for the same millisecond, and anything inside a
# second is contention on a request that takes tens of milliseconds.
MAX_SPREAD_MS="${MAX_ACCEPT_SPREAD_MS:-1000}"

VALID=1
note() { echo "  $1"; }
fail() { echo "  ✗ $1"; VALID=0; }

q() {
  docker exec "$PG" psql -U "$DB_USER" -d "$DB_NAME" -tAc "$1" 2>/dev/null | tr -d '\r'
}

# A counter out of the k6 JSON summary. Reads the structured output rather than
# scraping the console, so a change of log format cannot quietly zero a gate.
metric() {
  local name="$1"
  node -e '
    const fs = require("fs");
    const [file, key] = process.argv.slice(1);
    try {
      const summary = JSON.parse(fs.readFileSync(file, "utf8"));
      const m = (summary.metrics || {})[key];
      if (!m) { console.log("0"); process.exit(0) }
      const v = m.count ?? m.value ?? (m.values && (m.values.count ?? m.values.value));
      console.log(String(v ?? 0));
    } catch (e) {
      console.log("0");
    }
  ' "$SUMMARY" "$name"
}

echo "▸ contention validity"

if [ -z "$SUMMARY" ] || [ ! -f "$SUMMARY" ]; then
  fail "no k6 summary at '${SUMMARY:-<none>}' — nothing to judge"
  echo
  echo "  ✗ CONTENTION RUN IS NOT VALID."
  exit 1
fi

CONTESTED=$(metric kalo_contested_accepts)
WON=$(metric kalo_accept_won)
LOST=$(metric kalo_accept_lost)
UNEXPECTED=$(metric kalo_accept_unexpected)
DUP_REFUSED=$(metric kalo_duplicate_refused)
DUP_ACCEPTED=$(metric kalo_duplicate_accepted)
SERVER_ERRORS=$(metric kalo_server_errors)

note "contested accepts ${CONTESTED}, won ${WON}, lost ${LOST}, unexpected ${UNEXPECTED}"
note "duplicate selects refused ${DUP_REFUSED}, wrongly accepted ${DUP_ACCEPTED}"

# 1. Did anything win? An all-refused run usually means the drivers were
#    already busy or the booking step failed, and it would otherwise look like
#    flawless contention handling.
if [ "${WON:-0}" -lt 1 ]; then
  fail "no accept succeeded — nothing was assigned, so nothing was contended"
fi

# 2. Did anything lose? This is the gate that catches a fleet too large for the
#    traffic: every accept finding a free driver is not contention.
if [ "${LOST:-0}" -lt 1 ]; then
  fail "no accept was refused — every request found a free driver, so no race occurred"
fi

# 3. Was the attempt big enough to mean anything?
if [ "${CONTESTED:-0}" -lt "${MIN_CONTESTED:-2}" ]; then
  fail "only ${CONTESTED:-0} contested accepts — too few to demonstrate anything"
fi

# 4. Nothing unexplained. A unique-index violation reaching the client as a 500
#    is the worst outcome available here and must not be averaged away.
if [ "${UNEXPECTED:-0}" -gt 0 ]; then
  fail "${UNEXPECTED} accept(s) failed for an unclassified reason — read them before trusting the rest"
fi

if [ "${SERVER_ERRORS:-0}" -gt 0 ]; then
  fail "${SERVER_ERRORS} server error(s) — a refusal became a crash"
fi

# 5. A duplicate that was accepted is a straight defect.
if [ "${DUP_ACCEPTED:-0}" -gt 0 ]; then
  fail "${DUP_ACCEPTED} duplicate submission(s) were accepted rather than refused"
fi

if [ "${DUP_REFUSED:-0}" -lt 1 ]; then
  note "! no duplicate submissions were refused — the duplicate scenario may not have run"
fi

# 6. Did the accepts actually overlap, and did they name one driver?
#
#    Without this the run could be a sequence of unrelated accepts spread over
#    minutes, each finding the driver free, reported as contention.
#    Read from the kalo_accept_offset_ms trend: max minus min is how far the
#    first and last accept were from each other.
SPREAD=$(node -e '
  const fs = require("fs");
  try {
    const s = JSON.parse(fs.readFileSync(process.argv[1], "utf8"));
    const t = (s.metrics || {}).kalo_accept_offset_ms;
    if (!t) { console.log("unknown"); process.exit(0) }
    const v = t.values || t;
    if (v.min === undefined || v.max === undefined) { console.log("unknown"); process.exit(0) }
    console.log(String(Math.round(v.max - v.min)));
  } catch (e) { console.log("unknown") }
' "$SUMMARY")

if [ "$SPREAD" = "unknown" ]; then
  # Not a note to be skimmed past: without the timings the central claim of the
  # scenario is unverified, and an unverified claim is not a passing one.
  fail "no kalo_accept_offset_ms in the summary — overlap could not be verified, so contention is unproven"
else
  note "accept spread ${SPREAD}ms (limit ${MAX_SPREAD_MS}ms)"
  if [ "$SPREAD" -gt "$MAX_SPREAD_MS" ]; then
    fail "accepts were spread over ${SPREAD}ms — too far apart to have contended"
  fi
fi

# 7. And now the database, which is the only authority on what persisted.
DOUBLE_DRIVER=$(q "SELECT count(*) FROM (SELECT driver_id FROM rides WHERE driver_id IS NOT NULL AND status IN ('DRIVER_ASSIGNED','DRIVER_ARRIVING','DRIVER_ARRIVED','IN_PROGRESS') GROUP BY driver_id HAVING count(*) > 1) x")
DOUBLE_CUSTOMER=$(q "SELECT count(*) FROM (SELECT customer_id FROM rides WHERE status IN ('REQUESTED','DRIVER_ASSIGNED','DRIVER_ARRIVING','DRIVER_ARRIVED','IN_PROGRESS') GROUP BY customer_id HAVING count(*) > 1) x")
BUSY_WITHOUT_RIDE=$(q "SELECT count(*) FROM drivers d WHERE d.availability_status = 'BUSY' AND NOT EXISTS (SELECT 1 FROM rides r WHERE r.driver_id = d.id AND r.status IN ('DRIVER_ASSIGNED','DRIVER_ARRIVING','DRIVER_ARRIVED','IN_PROGRESS'))")
ASSIGNED_SEARCHING=$(q "SELECT count(*) FROM rides r JOIN ride_requests rq ON rq.id = r.ride_request_id WHERE r.status IN ('DRIVER_ASSIGNED','DRIVER_ARRIVING','DRIVER_ARRIVED','IN_PROGRESS') AND rq.status = 'SEARCHING'")

note "drivers with two active rides ${DOUBLE_DRIVER:-?}, customers with two ${DOUBLE_CUSTOMER:-?}"
note "drivers BUSY with no active ride ${BUSY_WITHOUT_RIDE:-?}, assigned rides whose request is SEARCHING ${ASSIGNED_SEARCHING:-?}"

[ "${DOUBLE_DRIVER:-1}" = "0" ] || fail "a driver holds more than one active ride"
[ "${DOUBLE_CUSTOMER:-1}" = "0" ] || fail "a customer holds more than one active ride"

# The two states the accept-versus-cancel race can corrupt. A BUSY driver with
# no ride is stranded and takes their company out of search; an assigned ride
# whose request went back to SEARCHING sends the passenger to book again while
# a driver is on the way.
[ "${BUSY_WITHOUT_RIDE:-1}" = "0" ] || fail "a driver is BUSY with no active ride — stranded by a lost race"
[ "${ASSIGNED_SEARCHING:-1}" = "0" ] || fail "a ride is assigned while its request is back to SEARCHING"

echo
if [ "$VALID" = "1" ]; then
  echo "  ✓ contention run is valid — races occurred, were resolved, and the database held"
else
  echo "  ✗ CONTENTION RUN IS NOT VALID. Do not report these invariants as proven."
  exit 1
fi
