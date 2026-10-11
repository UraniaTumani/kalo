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
#   k6 agreed     k6's own thresholds passed, so the run met its preconditions
#   wins > 0      somebody was assigned, so the fleet was not already busy and
#                 setup did not silently fail
#   losses > 0    somebody was refused, so requests genuinely competed
#   everyone      every virtual user that entered is accounted for
#   overlap       measured, from the instants the accepts opened and closed
#   one driver    they named the same driver, not merely the same company
#   each scenario every scheduled scenario produced evidence of its own
#   invariants    and after all that, the database still holds
#
# Dropping any one of them lets a quiet run look like a successful one.
#
# WHAT THE FIRST REAL RUN TAUGHT THIS FILE. It passed a run that k6 had
# failed, on 29 of 30 participants, with an overlap verdict of "every accept
# was in flight at the same time" derived from a metric that reported the same
# impossible value for every sample, and with two of three scheduled scenarios
# having recorded nothing at all. Four separate ways of concluding something
# from an absence. The rule that came out of it: a check must be able to tell
# its own evidence from the lack of it, and when it cannot, it fails.
set -u

SUMMARY="${1:-}"
RESULTS="${2:-.}"

PG="${PG_CONTAINER:-kalo-load-postgres-1}"
DB_USER="${DB_USERNAME:-kalo_load}"
DB_NAME="${DB_NAME:-kalo_load}"

ROOT="$(cd "$(dirname "$0")/.." && pwd)"

# How many virtual users the race was configured with.
#
# Derived from the run rather than guessed: the scenario's RACE_VUS and this
# must be the same number, and every participant has to be accounted for
# against it. The old floor of "at least 2 contested accepts" would have passed
# a 30-user race in which 28 users never got off the ground.
EXPECTED_VUS="${EXPECTED_RACE_VUS:-30}"

# How many drivers the race was spread across; one winner is expected per
# driver. Stated by whoever ran the race (matching the scenario's RACE_DRIVERS)
# rather than inferred, so the check is exact without being hardcoded.
EXPECTED_WINNERS="${EXPECTED_RACE_DRIVERS:-1}"

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

echo "▸ contention validity"

if [ -z "$SUMMARY" ] || [ ! -f "$SUMMARY" ]; then
  fail "no k6 summary at '${SUMMARY:-<none>}' — nothing to judge"
  echo
  echo "  ✗ CONTENTION RUN IS NOT VALID."
  exit 1
fi

# --------------------------------------------------------------- k6's verdict
#
# Checked first and on its own, because it is k6 saying the run did not meet
# the preconditions the scenario declared. The first real run crossed
# `kalo_contested_accepts: count>=30` at 29 and exited non-zero; this gate had
# no idea and reported VALID. Clean invariants after a failed threshold are a
# clean measurement of a run that should not have counted.
#
# The file is written by load-run-one.sh next to the summary. Its absence is
# not treated as success: an unknown k6 status leaves the central question open.
K6_EXIT_FILE="${K6_EXIT_FILE:-}"

if [ -z "$K6_EXIT_FILE" ]; then
  # Same directory and stem as the summary: <results>/<name>-k6-exit.txt
  K6_EXIT_FILE="${SUMMARY%-summary.json}-k6-exit.txt"
fi

if [ -f "$K6_EXIT_FILE" ]; then
  K6_EXIT=$(tr -dc '0-9-' < "$K6_EXIT_FILE" | head -c 8)
  K6_EXIT="${K6_EXIT:-missing}"
else
  K6_EXIT="missing"
fi

if [ "$K6_EXIT" = "missing" ]; then
  fail "k6's exit status was not recorded (looked in '${K6_EXIT_FILE}') — a failed threshold would be invisible, so the run cannot be called valid"
elif [ "$K6_EXIT" != "0" ]; then
  fail "k6 exited ${K6_EXIT} — one of its own thresholds failed, so the scenario did not meet its declared preconditions"
else
  note "k6 exit 0 — its own thresholds passed"
fi

# ----------------------------------------------------------------- the facts
#
# One node call, because the judgements are arithmetic over JSON and they live
# in load/lib/contention.js where unit tests can reach them. Inlining them here
# as `node -e` strings is how the unsound overlap rule came to be untested.
FACTS="$RESULTS/contention-facts.txt"

if ! node "$ROOT/scripts/contention-verdict.mjs" "$SUMMARY" > "$FACTS" 2>&1; then
  fail "could not read the k6 summary: $(head -1 "$FACTS")"
  echo
  echo "  ✗ CONTENTION RUN IS NOT VALID."
  exit 1
fi

# A fact, or empty when the metric was absent. Absent and zero are different
# answers and are never collapsed: one means nothing measured it.
fact() { sed -n "s/^$1=//p" "$FACTS" | head -1; }

ENTERED=$(fact entered)
CONTESTED=$(fact contested)
WON=$(fact won)
LOST=$(fact lost)
UNEXPECTED=$(fact unexpected)
SERVER_ERRORS=$(fact server_errors)
TIMING_SAMPLES=$(fact timing_samples)
OFFSET_MIN=$(fact offset_min)
DUP_ATTEMPTS=$(fact duplicate_attempts)
DUP_REFUSED=$(fact duplicate_refused)
DUP_ACCEPTED=$(fact duplicate_accepted)
CANCEL_ATTEMPTS=$(fact cancel_attempts)
CANCEL_RACES=$(fact cancel_races)
TIMEOUT_RACES=$(fact timeout_races)
OVERLAP_OK=$(fact overlap_ok)
OVERLAP_SPREAD=$(fact overlap_spread_ms)
OVERLAP_REASON=$(fact overlap_reason)
RECONCILE_OK=$(fact reconcile_ok)
RECONCILE_PROBLEMS=$(fact reconcile_problems)
RECONCILE_SKIPPED=$(fact reconcile_skipped)

note "entered ${ENTERED:-<absent>}, contested ${CONTESTED:-<absent>}, won ${WON:-<absent>}, lost ${LOST:-<absent>}, unexpected ${UNEXPECTED:-<absent>}"
note "duplicate attempts ${DUP_ATTEMPTS:-<absent>} (refused ${DUP_REFUSED:-<absent>}, wrongly accepted ${DUP_ACCEPTED:-<absent>}), cancel attempts ${CANCEL_ATTEMPTS:-<absent>}"

# ------------------------------------------------- every participant counted
#
# The denominator has to exist before any ratio means anything. A summary with
# no kalo_race_entered predates participant accounting and cannot answer
# "did everyone take part?", which is not a detail: the first real run lost one
# of thirty users to a failed booking and reported the other 29 as the race.
if [ -z "$ENTERED" ]; then
  fail "the summary carries no kalo_race_entered — without it a virtual user that never reached an accept is invisible"
elif [ "$ENTERED" -ne "$EXPECTED_VUS" ]; then
  fail "${ENTERED} virtual user(s) entered the race but ${EXPECTED_VUS} were expected — either the run was not the size it claims, or EXPECTED_RACE_VUS here does not match RACE_VUS in the scenario"
fi

if [ -z "$CONTESTED" ]; then
  fail "the summary carries no kalo_contested_accepts — nothing says an accept was ever attempted"
elif [ "$CONTESTED" -ne "$EXPECTED_VUS" ]; then
  fail "${CONTESTED} accept(s) were attempted but ${EXPECTED_VUS} virtual users were configured — $(( EXPECTED_VUS - CONTESTED )) never reached an accept, so part of the race did not happen"
fi

# And the arithmetic closes: entered == contested + the named reasons, and
# contested == won + lost + unexpected. Both sums come from the shared library.
if [ "${RECONCILE_SKIPPED:-0}" = "1" ]; then
  : # nothing to reconcile against; the missing-denominator failure above says so
elif [ "${RECONCILE_OK:-0}" != "1" ]; then
  i=0
  while [ "$i" -lt "${RECONCILE_PROBLEMS:-0}" ]; do
    fail "$(fact "reconcile_problem_${i}")"
    i=$(( i + 1 ))
  done
  [ "${RECONCILE_PROBLEMS:-0}" -gt 0 ] || fail "the attempt tallies do not reconcile"
fi

# ------------------------------------------------------- winners and losers
#
# Exactly as many winners as there were drivers in the race. "At least one won"
# was the first version and was too loose to be worth having: thirty accepts
# over three drivers correctly produce THREE winners, and a gate happy with one
# or more passes equally for one, three, or thirty — where thirty would mean
# the per-driver invariant had been violated on every driver.
if [ "${WON:-0}" -ne "$EXPECTED_WINNERS" ]; then
  if [ "${WON:-0}" -lt 1 ]; then
    fail "no accept succeeded — nothing was assigned, so nothing was contended"
  elif [ "${WON:-0}" -gt "$EXPECTED_WINNERS" ]; then
    fail "${WON} accepts won against ${EXPECTED_WINNERS} driver(s) in the race — more winners than drivers means a driver took two rides"
  else
    fail "${WON} accepts won but ${EXPECTED_WINNERS} driver(s) were raced — a driver was never assigned, so part of the race did not happen"
  fi
fi

EXPECTED_LOSERS=$(( ${CONTESTED:-0} - EXPECTED_WINNERS ))

if [ "${LOST:-0}" -lt 1 ]; then
  fail "no accept was refused — every request found a free driver, so no race occurred"
elif [ "${LOST:-0}" -ne "$EXPECTED_LOSERS" ]; then
  fail "${LOST} accepts lost but ${EXPECTED_LOSERS} were expected from ${CONTESTED:-0} contested minus ${EXPECTED_WINNERS} winner(s) — attempts are unaccounted for"
fi

# Nothing unexplained. A unique-index violation reaching the client as a 500 is
# the worst outcome available here and must not be averaged away.
if [ "${UNEXPECTED:-0}" -gt 0 ]; then
  fail "${UNEXPECTED} accept(s) failed for an unclassified reason — read them before trusting the rest"
fi

if [ "${SERVER_ERRORS:-0}" -gt 0 ]; then
  fail "${SERVER_ERRORS} server error(s) — a refusal became a crash"
fi

# --------------------------------------------------------------- the overlap
#
# The central claim, and the one the first real run got wrong. The rule used to
# be `spread < duration`, computed from two unrelated metrics; a spread of zero
# satisfied it perfectly, so a metric reporting one constant value for every
# sample read as flawless synchronisation. The replacement compares measured
# instants — max(start) < min(end) — which a degenerate input cannot satisfy,
# because satisfying it requires two different numbers in a given order.
#
# load/lib/contention.js:assessIntervalOverlap holds the rule and its reasons.
#
# The absent case is separated from the mismatched one so that each has a
# message of its own. It first shared the word "unverifiable" with the
# library's refusal below, which made the two indistinguishable in the test
# output — and a mutation that deleted this branch passed the suite, because
# the assertion could not tell which check had spoken. Distinct wording is
# what makes the branch testable, and an empty value here would also make the
# numeric comparison that follows a bash error rather than a verdict.
if [ -z "$TIMING_SAMPLES" ]; then
  fail "the summary carries no kalo_accept_timing_samples — nothing counted how many accepts were timed, so their min and max describe an unknown number of them"
elif [ "$TIMING_SAMPLES" -ne "${CONTESTED:-0}" ]; then
  fail "${TIMING_SAMPLES} accept(s) were timed but ${CONTESTED:-0} were attempted — the timings describe part of the run, so the overlap they show is not the run's"
fi

# There is one passing verdict, not two. The library offers no "at least two
# accepts overlapped" conclusion, because four aggregate numbers cannot support
# one — see assessIntervalOverlap for why the obvious test is vacuous.
if [ "${OVERLAP_OK:-0}" != "1" ]; then
  fail "overlap not demonstrated: ${OVERLAP_REASON:-no reason given}"
else
  note "accept window: starts ${OVERLAP_SPREAD}ms apart, durations $(fact accept_dur_min)-$(fact accept_dur_max)ms"
  note "✓ ${OVERLAP_REASON}"
fi

# The diagnostic offset, which must not be negative. waitForBarrier returns
# only once the barrier has passed, so a negative offset is impossible — and
# the barrier shares a clock with the timings above, so an impossible reading
# here discredits those too.
if [ -n "$OFFSET_MIN" ]; then
  case "$OFFSET_MIN" in
    -*)
      fail "the barrier offset reports ${OFFSET_MIN}ms, which cannot happen — waitForBarrier returns only after the barrier, so the clock or the instrumentation is broken and the timings share it"
      ;;
  esac
fi

# ----------------------------------------------- every scheduled scenario
#
# Positive evidence from each, because both of these recorded NOTHING in the
# first real run — they shared the raced fleet, whose only driver was busy by
# the time they started — and the gate printed a note and passed. A scheduled
# scenario that attempted nothing tested nothing.
if [ -z "$DUP_ATTEMPTS" ] || [ "$DUP_ATTEMPTS" -lt 1 ]; then
  fail "the duplicate scenario attempted nothing — it was scheduled, so a run in which it never got started is not a run that tested duplicates"
elif [ $(( ${DUP_REFUSED:-0} + ${DUP_ACCEPTED:-0} )) -lt 1 ]; then
  fail "${DUP_ATTEMPTS} duplicate attempt(s) but none reached a decision — every pair failed before the second submission, so nothing was tested"
fi

if [ "${DUP_ACCEPTED:-0}" -gt 0 ]; then
  fail "${DUP_ACCEPTED} duplicate submission(s) were accepted rather than refused"
fi

if [ -z "$CANCEL_ATTEMPTS" ] || [ "$CANCEL_ATTEMPTS" -lt 1 ]; then
  fail "the accept-versus-cancel scenario attempted nothing — it was scheduled, so its invariants below are untested"
elif [ -z "$CANCEL_RACES" ] || [ "$CANCEL_RACES" -lt 1 ]; then
  fail "${CANCEL_ATTEMPTS} cancel attempt(s) but no race was recorded — nothing reached the simultaneous accept and cancel"
fi

# accept-versus-timeout is exported but deliberately not scheduled. Said out
# loud every time, because an unrun scenario reported as nothing is an unrun
# scenario a reader will assume passed.
if [ -z "$TIMEOUT_RACES" ] || [ "$TIMEOUT_RACES" -lt 1 ]; then
  note "— accept-versus-timeout: NOT VERIFIED (defined, never executed; do not report it as covered)"
else
  note "accept-versus-timeout raced ${TIMEOUT_RACES} time(s)"
fi

# ------------------------------------------------------------- the database
#
# The only authority on what persisted.
DOUBLE_DRIVER=$(count "drivers with two active rides" "SELECT count(*) FROM (SELECT driver_id FROM rides WHERE driver_id IS NOT NULL AND status IN ('DRIVER_ASSIGNED','DRIVER_ARRIVING','DRIVER_ARRIVED','IN_PROGRESS') GROUP BY driver_id HAVING count(*) > 1) x")
DOUBLE_CUSTOMER=$(count "customers with two active rides" "SELECT count(*) FROM (SELECT customer_id FROM rides WHERE status IN ('REQUESTED','DRIVER_ASSIGNED','DRIVER_ARRIVING','DRIVER_ARRIVED','IN_PROGRESS') GROUP BY customer_id HAVING count(*) > 1) x")
BUSY_WITHOUT_RIDE=$(count "drivers BUSY with no ride" "SELECT count(*) FROM drivers d WHERE d.availability_status = 'BUSY' AND NOT EXISTS (SELECT 1 FROM rides r WHERE r.driver_id = d.id AND r.status IN ('DRIVER_ASSIGNED','DRIVER_ARRIVING','DRIVER_ARRIVED','IN_PROGRESS'))")
ASSIGNED_SEARCHING=$(count "assigned rides whose request is SEARCHING" "SELECT count(*) FROM rides r JOIN ride_requests rq ON rq.id = r.ride_request_id WHERE r.status IN ('DRIVER_ASSIGNED','DRIVER_ARRIVING','DRIVER_ARRIVED','IN_PROGRESS') AND rq.status = 'SEARCHING'")

# Per driver, not just globally: the number of drivers actually holding an
# active ride must equal the number raced. Fewer means a driver was never
# assigned; more is impossible without a violation.
#
# SCOPED TO THIS RUN when RUN_SINCE is given, and this is not a nicety. The
# count is a per-run figure that was being read globally, which is only correct
# against a freshly created database. The load database is now deliberately
# preserved between runs, so a second run would find its own winner plus the
# first run's — two drivers holding a ride where one was raced — and the gate
# would report a violation that had not happened.
#
# The invariant queries above stay global on purpose. "No driver holds two
# active rides" is true of the whole database or it is not true, and a
# violation left by an earlier run is worth failing on wherever it came from.
if [ -n "${RUN_SINCE:-}" ]; then
  SINCE_CLAUSE="AND created_at >= '${RUN_SINCE}'"
  note "counting assignments from this run only (since ${RUN_SINCE})"
else
  SINCE_CLAUSE=""
fi

ASSIGNED_DRIVERS=$(count "drivers holding an active ride" "SELECT count(DISTINCT driver_id) FROM rides WHERE driver_id IS NOT NULL AND status IN ('DRIVER_ASSIGNED','DRIVER_ARRIVING','DRIVER_ARRIVED','IN_PROGRESS') ${SINCE_CLAUSE}")

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
