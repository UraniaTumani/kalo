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

# A count out of the isolated load database.
#
# Errors are NOT swallowed. A query that fails returns nothing, and nothing
# compared against "0" used to read as a violation — the gate failed closed,
# which is right, but it failed with the wrong message and sent the reader
# hunting a corruption that had not happened. Failure is now its own answer.
q() {
  local sql="$1" out status

  out=$(docker exec "$PG" psql -U "$DB_USER" -d "$DB_NAME" -tAc "$sql" 2>&1)
  status=$?

  if [ "$status" -ne 0 ] || [ -z "$out" ]; then
    echo "QUERY_FAILED"
    return
  fi

  echo "$out" | tr -d '\r'
}

# Reads a count, failing the run when the database could not answer.
count() {
  local label="$1" sql="$2" value
  value=$(q "$sql")

  if [ "$value" = "QUERY_FAILED" ]; then
    fail "could not read '${label}' from ${DB_NAME} on ${PG} — the invariants below are unverified"
    echo "0"
    return
  fi

  echo "$value"
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

# 1. Exactly as many winners as there were drivers in the race.
#
#    "At least one won" was the first version of this check and it was too
#    loose to be worth having. Thirty accepts over three drivers correctly
#    produce THREE winners, and a gate happy with one or more passes equally
#    for one, three, or thirty — where thirty would mean the per-driver
#    invariant had been violated on every driver and the gate had not noticed.
#
#    The expected number is stated by whoever ran the race (EXPECTED_RACE_DRIVERS,
#    matching the scenario's RACE_DRIVERS) rather than inferred, so the check is
#    exact without being hardcoded to one particular shape of run.
EXPECTED_WINNERS="${EXPECTED_RACE_DRIVERS:-1}"

if [ "${WON:-0}" -ne "$EXPECTED_WINNERS" ]; then
  if [ "${WON:-0}" -lt 1 ]; then
    fail "no accept succeeded — nothing was assigned, so nothing was contended"
  elif [ "${WON:-0}" -gt "$EXPECTED_WINNERS" ]; then
    fail "${WON} accepts won against ${EXPECTED_WINNERS} driver(s) in the race — more winners than drivers means a driver took two rides"
  else
    fail "${WON} accepts won but ${EXPECTED_WINNERS} driver(s) were raced — a driver was never assigned, so part of the race did not happen"
  fi
fi

# 2. Everyone else lost. Checked as an exact complement rather than "at least
#    one", so an accept that neither won nor lost nor was classified unexpected
#    cannot go missing between the three buckets.
EXPECTED_LOSERS=$(( ${CONTESTED:-0} - EXPECTED_WINNERS ))

if [ "${LOST:-0}" -lt 1 ]; then
  fail "no accept was refused — every request found a free driver, so no race occurred"
elif [ "${LOST:-0}" -ne "$EXPECTED_LOSERS" ]; then
  fail "${LOST} accepts lost but ${EXPECTED_LOSERS} were expected from ${CONTESTED:-0} contested minus ${EXPECTED_WINNERS} winner(s) — attempts are unaccounted for"
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

#    And how long an accept stayed open, because the spread alone decides
#    nothing. Thirty accepts issued across two seconds did not contend if each
#    took forty milliseconds — they queued. Overlap is spread < duration.
DUR_MAX=$(node -e '
  const fs = require("fs");
  try {
    const s = JSON.parse(fs.readFileSync(process.argv[1], "utf8"));
    const t = (s.metrics || {}).kalo_accept_duration_ms;
    const v = t && (t.values || t);
    console.log(v && v.max !== undefined ? String(Math.round(v.max)) : "unknown");
  } catch (e) { console.log("unknown") }
' "$SUMMARY")

DUR_MIN=$(node -e '
  const fs = require("fs");
  try {
    const s = JSON.parse(fs.readFileSync(process.argv[1], "utf8"));
    const t = (s.metrics || {}).kalo_accept_duration_ms;
    const v = t && (t.values || t);
    console.log(v && v.min !== undefined ? String(Math.round(v.min)) : "unknown");
  } catch (e) { console.log("unknown") }
' "$SUMMARY")

if [ "$SPREAD" = "unknown" ] || [ "$DUR_MAX" = "unknown" ]; then
  # Not a note to be skimmed past: without the timings the central claim of the
  # scenario is unverified, and an unverified claim is not a passing one.
  fail "accept timings missing from the summary — overlap could not be verified, so contention is unproven"
else
  note "accept spread ${SPREAD}ms, accept duration ${DUR_MIN}-${DUR_MAX}ms"

  if [ "$SPREAD" -ge "$DUR_MAX" ]; then
    fail "accepts were spread over ${SPREAD}ms while the longest took ${DUR_MAX}ms — they queued rather than raced, so no request overlapped another"
  elif [ "$DUR_MIN" != "unknown" ] && [ "$SPREAD" -lt "$DUR_MIN" ]; then
    note "✓ every accept was in flight at the same time"
  else
    note "✓ at least two accepts were in flight at the same time"
  fi

  # A sanity bound as well, so a pathologically slow backend cannot make any
  # spread look like overlap.
  if [ "$SPREAD" -gt "$MAX_SPREAD_MS" ]; then
    fail "accepts were spread over ${SPREAD}ms, beyond the ${MAX_SPREAD_MS}ms this scenario calls simultaneous"
  fi
fi

# 7. And now the database, which is the only authority on what persisted.
DOUBLE_DRIVER=$(count "drivers with two active rides" "SELECT count(*) FROM (SELECT driver_id FROM rides WHERE driver_id IS NOT NULL AND status IN ('DRIVER_ASSIGNED','DRIVER_ARRIVING','DRIVER_ARRIVED','IN_PROGRESS') GROUP BY driver_id HAVING count(*) > 1) x")
DOUBLE_CUSTOMER=$(count "customers with two active rides" "SELECT count(*) FROM (SELECT customer_id FROM rides WHERE status IN ('REQUESTED','DRIVER_ASSIGNED','DRIVER_ARRIVING','DRIVER_ARRIVED','IN_PROGRESS') GROUP BY customer_id HAVING count(*) > 1) x")
BUSY_WITHOUT_RIDE=$(count "drivers BUSY with no ride" "SELECT count(*) FROM drivers d WHERE d.availability_status = 'BUSY' AND NOT EXISTS (SELECT 1 FROM rides r WHERE r.driver_id = d.id AND r.status IN ('DRIVER_ASSIGNED','DRIVER_ARRIVING','DRIVER_ARRIVED','IN_PROGRESS'))")
ASSIGNED_SEARCHING=$(count "assigned rides whose request is SEARCHING" "SELECT count(*) FROM rides r JOIN ride_requests rq ON rq.id = r.ride_request_id WHERE r.status IN ('DRIVER_ASSIGNED','DRIVER_ARRIVING','DRIVER_ARRIVED','IN_PROGRESS') AND rq.status = 'SEARCHING'")

# Per driver, not just globally: the number of drivers actually holding an
# active ride must equal the number raced. Fewer means a driver was never
# assigned; more is impossible without a violation.
ASSIGNED_DRIVERS=$(count "drivers holding an active ride" "SELECT count(DISTINCT driver_id) FROM rides WHERE driver_id IS NOT NULL AND status IN ('DRIVER_ASSIGNED','DRIVER_ARRIVING','DRIVER_ARRIVED','IN_PROGRESS')")

note "drivers holding an active ride ${ASSIGNED_DRIVERS:-?} (raced ${EXPECTED_WINNERS})"
note "drivers with two active rides ${DOUBLE_DRIVER:-?}, customers with two ${DOUBLE_CUSTOMER:-?}"
note "drivers BUSY with no active ride ${BUSY_WITHOUT_RIDE:-?}, assigned rides whose request is SEARCHING ${ASSIGNED_SEARCHING:-?}"

[ "${DOUBLE_DRIVER:-1}" = "0" ] || fail "a driver holds more than one active ride"
[ "${DOUBLE_CUSTOMER:-1}" = "0" ] || fail "a customer holds more than one active ride"

# The per-driver half of the winner check, taken from persisted state rather
# than from k6's counters. Both have to agree: the counters say how many accepts
# the API blessed, this says how many drivers actually hold a ride.
if [ "${ASSIGNED_DRIVERS:-0}" -ne "$EXPECTED_WINNERS" ]; then
  fail "${ASSIGNED_DRIVERS:-0} driver(s) hold an active ride but ${EXPECTED_WINNERS} were raced — the winners k6 counted and the drivers the database assigned do not agree"
fi

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
