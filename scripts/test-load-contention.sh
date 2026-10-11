#!/usr/bin/env bash
# Tests for load-contention-check.sh.
#
#   bash scripts/test-load-contention.sh
#
# These are negative controls. The contention run is expected to come back
# clean, so a clean result proves nothing on its own — what has to be proved is
# that the gate REFUSES a run which never contended. Every case below is a way
# of looking successful while having tested nothing, and each one must be
# rejected.
#
# The second half of this file exists because the gate failed exactly that duty
# on the first real run. It reported VALID for a run in which k6's own
# threshold had failed, one of thirty participants never reached an accept, the
# overlap metric reported the same impossible value for every sample, and two
# of three scheduled scenarios recorded nothing at all. Each of those four is
# now a test, because a gate that has been wrong in a particular way once is
# the only kind of gate worth testing in that way.
#
# No stack. The gate reaches the world through `docker`, a k6 summary file and
# an exit-status file, so stubs and fixtures are enough. Nothing here touches
# kalo_load, the development database or the rehearsal stack.
set -u

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PASSES=0
FAILURES=0

ok() { PASSES=$(( PASSES + 1 )); echo "  ok   $1"; }
bad() { FAILURES=$(( FAILURES + 1 )); echo "  FAIL $1"; }

# Builds a k6 summary from named overrides.
#
# The defaults describe a VALID thirty-user race against one driver, so each
# case below overrides only the thing it is testing and the reader can see what
# that is. Positional parameters were the first version and became eleven deep,
# at which point `summary f 30 1 29 0 3 0 0 200 30 80` told nobody anything.
#
# An override of `-` removes the metric entirely, which is how a summary that
# predates a counter is simulated. That distinction matters: absent and zero
# are different facts and the gate is required to treat them differently.
BUILDER=$(mktemp)
cat > "$BUILDER" <<'NODE'
const fs = require('fs')
const [file, ...pairs] = process.argv.slice(2)

const v = {
  entered: 30,
  contested: 30,
  won: 1,
  lost: 29,
  unexpected: 0,
  server_errors: 0,
  no_fleet: 0,
  no_customer_token: 0,
  no_booking: 0,
  no_partner_token: 0,
  timing_samples: 30,
  start_min: 0,
  start_max: 40,
  end_min: 120,
  end_max: 200,
  dur_min: 30,
  dur_max: 80,
  offset_samples: 30,
  offset_min: 0,
  offset_max: 40,
  duplicate_attempts: 6,
  duplicate_refused: 3,
  duplicate_accepted: 0,
  cancel_attempts: 6,
  cancel_races: 6,
  timeout_races: '-',
}

for (const pair of pairs) {
  const i = pair.indexOf('=')
  const key = pair.slice(0, i)
  if (!(key in v)) {
    console.error('unknown override: ' + key)
    process.exit(2)
  }
  v[key] = pair.slice(i + 1)
}

const metrics = {}
const counter = (name, value) => {
  if (value !== '-') metrics[name] = { count: Number(value) }
}
const trend = (name, min, max) => {
  if (min !== '-' && max !== '-') {
    metrics[name] = { min: Number(min), max: Number(max), avg: (Number(min) + Number(max)) / 2 }
  }
}

counter('kalo_race_entered', v.entered)
counter('kalo_contested_accepts', v.contested)
counter('kalo_accept_won', v.won)
counter('kalo_accept_lost', v.lost)
counter('kalo_accept_unexpected', v.unexpected)
counter('kalo_server_errors', v.server_errors)
counter('kalo_race_no_fleet', v.no_fleet)
counter('kalo_race_no_customer_token', v.no_customer_token)
counter('kalo_race_no_booking', v.no_booking)
counter('kalo_race_no_partner_token', v.no_partner_token)
counter('kalo_accept_timing_samples', v.timing_samples)
counter('kalo_accept_offset_samples', v.offset_samples)
counter('kalo_duplicate_attempts', v.duplicate_attempts)
counter('kalo_duplicate_refused', v.duplicate_refused)
counter('kalo_duplicate_accepted', v.duplicate_accepted)
counter('kalo_cancel_attempts', v.cancel_attempts)
counter('kalo_cancel_races', v.cancel_races)
counter('kalo_timeout_races', v.timeout_races)

trend('kalo_accept_start_ms', v.start_min, v.start_max)
trend('kalo_accept_end_ms', v.end_min, v.end_max)
trend('kalo_accept_duration_ms', v.dur_min, v.dur_max)
trend('kalo_accept_offset_ms', v.offset_min, v.offset_max)

fs.writeFileSync(file, JSON.stringify({ metrics }, null, 2))
NODE

# The summary is named as the runner names it, so the gate's derivation of the
# exit-status path from the summary path is exercised rather than bypassed.
summary() {
  local work="$1"; shift
  node "$BUILDER" "$work/06-contention-summary.json" "$@" || exit 2
}

# k6's own verdict, as load-run-one.sh records it. Written by default, because
# almost every case wants a run k6 was happy with.
k6_exit() {
  local work="$1" status="${2:-0}"
  if [ "$status" = "-" ]; then
    rm -f "$work/06-contention-k6-exit.txt"
  else
    echo "$status" > "$work/06-contention-k6-exit.txt"
  fi
}

# A `docker` whose psql answers the invariant queries with given values.
stub_db() {
  local dir="$1" double_driver="$2" double_customer="$3" busy="$4" searching="$5"
  local assigned_drivers="${6:-1}"

  mkdir -p "$dir"
  cat > "$dir/docker" <<STUB
#!/usr/bin/env bash
# The gate asks five questions, each by a distinctive fragment.
for arg in "\$@"; do
  case "\$arg" in
    *"GROUP BY driver_id HAVING"*) echo "$double_driver"; exit 0 ;;
    *"GROUP BY customer_id HAVING"*) echo "$double_customer"; exit 0 ;;
    *"availability_status = 'BUSY'"*) echo "$busy"; exit 0 ;;
    *"rq.status = 'SEARCHING'"*) echo "$searching"; exit 0 ;;
    *"count(DISTINCT driver_id)"*) echo "$assigned_drivers"; exit 0 ;;
  esac
done
echo "0"
STUB
  chmod +x "$dir/docker"
}

# A work directory with a clean fleet, a happy k6 and a valid summary.
# Overrides are passed straight through to the summary builder.
setup_work() {
  local work
  work=$(mktemp -d)
  stub_db "$work/bin" 0 0 0 0 1
  k6_exit "$work" 0
  summary "$work" "$@"
  echo "$work"
}

# Runs the gate and returns its exit status; output lands in <work>/out.
#
# The caller reads that file directly rather than a variable set in here: this
# runs in a command substitution, so anything assigned would not survive.
#
# GATE_SCRIPT points this suite at a different copy of the gate, which is how
# the gate gets mutation-tested: a mutant lives in its own file and the real
# one is never written to, so an interrupted mutation run cannot leave a
# disabled check behind in the script that decides real runs. It has to stay
# inside scripts/ because the gate locates its verdict reader relative to
# itself.
run_gate() {
  local work="$1"
  local gate="${GATE_SCRIPT:-$ROOT/scripts/load-contention-check.sh}"
  PATH="$work/bin:$PATH" bash "$gate" \
    "$work/06-contention-summary.json" "$work" > "$work/out" 2>&1
  echo $?
}

expect_invalid() {
  local label="$1" work="$2" needle="$3"
  local status
  status=$(run_gate "$work")

  if [ "$status" = "0" ]; then
    bad "$label — gate accepted a run it should have refused"
    sed 's/^/        /' "$work/out"
  elif grep -q "$needle" "$work/out"; then
    ok "$label"
  else
    bad "$label — refused, but not for the stated reason (wanted '$needle')"
    sed 's/^/        /' "$work/out"
  fi
}

expect_valid() {
  local label="$1" work="$2"
  local status
  status=$(run_gate "$work")

  if [ "$status" = "0" ]; then
    ok "$label"
  else
    bad "$label — gate refused a run that should have passed"
    sed 's/^/        /' "$work/out"
  fi
}

# Passes, and says a particular thing while passing.
expect_valid_saying() {
  local label="$1" work="$2" needle="$3"
  local status
  status=$(run_gate "$work")

  if [ "$status" != "0" ]; then
    bad "$label — gate refused a run that should have passed"
    sed 's/^/        /' "$work/out"
  elif grep -q "$needle" "$work/out"; then
    ok "$label"
  else
    bad "$label — passed without saying '$needle'"
    sed 's/^/        /' "$work/out"
  fi
}

echo "load-contention-check.sh"

# --------------------------------------------------- the positive control

# 30 accepts on one driver: one wins, 29 lose, timings overlap, every scenario
# reported evidence, database holds. The only shape that should pass, and it
# has to pass or every refusal below means nothing.
WORK=$(setup_work)
expect_valid "a real race passes: 1 won, 29 lost, invariants clean" "$WORK"
rm -rf "$WORK"

# ------------------------------------------- no contention actually happened

# The headline false positive. Every accept succeeded, which means every one
# found a free driver — a fleet too large for the traffic, reported as flawless
# concurrency handling.
WORK=$(setup_work won=30 lost=0)
expect_invalid "all accepts won — no race occurred" "$WORK" "no accept was refused"
rm -rf "$WORK"

# The mirror image: nothing was assigned at all, usually because the drivers
# were already busy or booking failed. Looks like perfect refusal discipline.
WORK=$(setup_work won=0 lost=30)
expect_invalid "all accepts lost — nothing was ever assigned" "$WORK" "no accept succeeded"
rm -rf "$WORK"

# ------------------------------------------------- genuine defects detected

WORK=$(mktemp -d); stub_db "$WORK/bin" 1 0 0 0 1; k6_exit "$WORK" 0; summary "$WORK"
expect_invalid "two active rides on one driver is refused" "$WORK" "more than one active ride"
rm -rf "$WORK"

WORK=$(mktemp -d); stub_db "$WORK/bin" 0 1 0 0 1; k6_exit "$WORK" 0; summary "$WORK"
expect_invalid "two active rides on one customer is refused" "$WORK" "customer holds more than one"
rm -rf "$WORK"

# The accept-versus-cancel corruptions, which no counter would reveal.
WORK=$(mktemp -d); stub_db "$WORK/bin" 0 0 1 0 1; k6_exit "$WORK" 0; summary "$WORK"
expect_invalid "a driver left BUSY with no ride is refused" "$WORK" "stranded by a lost race"
rm -rf "$WORK"

WORK=$(mktemp -d); stub_db "$WORK/bin" 0 0 0 1 1; k6_exit "$WORK" 0; summary "$WORK"
expect_invalid "an assigned ride whose request is SEARCHING is refused" "$WORK" "back to SEARCHING"
rm -rf "$WORK"

# ----------------------------------------------------- unexplained outcomes

WORK=$(setup_work lost=26 unexpected=3)
expect_invalid "unclassified accept failures are not averaged away" "$WORK" "unclassified reason"
rm -rf "$WORK"

WORK=$(setup_work server_errors=2)
expect_invalid "a refusal that became a crash is refused" "$WORK" "became a crash"
rm -rf "$WORK"

WORK=$(setup_work duplicate_accepted=4 duplicate_refused=0)
expect_invalid "an accepted duplicate submission is a defect" "$WORK" "duplicate submission"
rm -rf "$WORK"

# ------------------------------------------------------------ missing input

WORK=$(mktemp -d); stub_db "$WORK/bin" 0 0 0 0 1
expect_invalid "a missing k6 summary is refused, not assumed clean" "$WORK" "nothing to judge"
rm -rf "$WORK"

# A summary whose counters are all absent must land on refusals rather than a
# pass. Every number the gate needs is missing, so every claim is unproven.
WORK=$(mktemp -d); stub_db "$WORK/bin" 0 0 0 0 1; k6_exit "$WORK" 0
echo '{"metrics":{}}' > "$WORK/06-contention-summary.json"
expect_invalid "an empty summary is refused" "$WORK" "no kalo_race_entered"
rm -rf "$WORK"

# -------------------------------------------------- winners per driver raced

# More winners than drivers in the race. Every driver would have had to take
# two rides, which the old "at least one won" gate passed without comment.
WORK=$(mktemp -d); stub_db "$WORK/bin" 0 0 0 0 3; k6_exit "$WORK" 0
summary "$WORK" won=3 lost=27
expect_invalid "three winners against one raced driver is refused" "$WORK" "more winners than drivers"
rm -rf "$WORK"

# Fewer winners than drivers raced: part of the race never happened, usually
# because a driver was already busy or its booking failed.
WORK=$(setup_work)
EXPECTED_RACE_DRIVERS=2 expect_invalid "one winner against two raced drivers is refused" "$WORK" "part of the race did not happen"
rm -rf "$WORK"

# Three drivers raced, three winners, invariants clean: the wider race, valid.
WORK=$(mktemp -d); stub_db "$WORK/bin" 0 0 0 0 3; k6_exit "$WORK" 0
summary "$WORK" won=3 lost=27
EXPECTED_RACE_DRIVERS=3 expect_valid "three winners against three raced drivers passes" "$WORK"
rm -rf "$WORK"

# ------------------------------------ judging a run against a kept database

# The load database is preserved between runs now, so the "drivers holding an
# active ride" count has to be scoped to the run being judged. Unscoped, a
# second run finds its own winner plus every earlier one and the gate reports a
# violation that never happened.
#
# The stub answers 2 for that count, as it would with one winner from this run
# and one left by the previous one. Without RUN_SINCE the gate must refuse;
# with it, the query carries a created_at bound and the stub answers 1.
stub_db_scoped() {
  local dir="$1"
  mkdir -p "$dir"
  cat > "$dir/docker" <<'STUB'
#!/usr/bin/env bash
for arg in "$@"; do
  case "$arg" in
    *"GROUP BY driver_id HAVING"*) echo "0"; exit 0 ;;
    *"GROUP BY customer_id HAVING"*) echo "0"; exit 0 ;;
    *"availability_status = 'BUSY'"*) echo "0"; exit 0 ;;
    *"rq.status = 'SEARCHING'"*) echo "0"; exit 0 ;;
    # Scoped by a created_at bound: this run's winner only.
    *"count(DISTINCT driver_id)"*"created_at >="*) echo "1"; exit 0 ;;
    # Unscoped: this run's winner plus one left behind by an earlier run.
    *"count(DISTINCT driver_id)"*) echo "2"; exit 0 ;;
  esac
done
echo "0"
STUB
  chmod +x "$dir/docker"
}

WORK=$(mktemp -d); stub_db_scoped "$WORK/bin"; k6_exit "$WORK" 0; summary "$WORK"
expect_invalid "an earlier run's winner is refused when the count is unscoped" "$WORK" "do not agree"
rm -rf "$WORK"

WORK=$(mktemp -d); stub_db_scoped "$WORK/bin"; k6_exit "$WORK" 0; summary "$WORK"
RUN_SINCE='2026-10-11 04:00:00' expect_valid "scoping the count to this run ignores what earlier runs left" "$WORK"
rm -rf "$WORK"

# And the scoping is stated, so nobody reads a scoped figure as a global one.
WORK=$(mktemp -d); stub_db_scoped "$WORK/bin"; k6_exit "$WORK" 0; summary "$WORK"
RUN_SINCE='2026-10-11 04:00:00' expect_valid_saying "the gate says when it has scoped the count" "$WORK" "from this run only"
rm -rf "$WORK"

# ------------------------------------- k6 and the database have to agree

# k6 counted a winner; the database holds no assigned driver. One of them is
# wrong and the run cannot be trusted either way.
WORK=$(mktemp -d); stub_db "$WORK/bin" 0 0 0 0 0; k6_exit "$WORK" 0; summary "$WORK"
expect_invalid "a counted winner with no assigned driver is refused" "$WORK" "do not agree"
rm -rf "$WORK"

# --------------------------------------------------------- database failure

# The check that used to fail for the wrong reason. A query that errors
# returned nothing, nothing read as a violation, and the reader went hunting a
# corruption that had not happened.
WORK=$(mktemp -d); mkdir -p "$WORK/bin"
cat > "$WORK/bin/docker" <<'STUB'
#!/usr/bin/env bash
echo "could not connect to server" >&2
exit 2
STUB
chmod +x "$WORK/bin/docker"
k6_exit "$WORK" 0; summary "$WORK"
expect_invalid "an unreachable database is reported as unverified" "$WORK" "could not read"
rm -rf "$WORK"

# ======================================================================
# The four false positives of the first real contention run.
#
# Each of these is a shape the gate passed on 2026-10-10 and must now refuse.
# ======================================================================

# ------------------------------------------------- 1. k6 said no, gate said yes

# k6 crossed its own `kalo_contested_accepts` threshold and exited non-zero.
# The gate never looked at the exit status and declared the run VALID.
WORK=$(setup_work); k6_exit "$WORK" 99
expect_invalid "a non-zero k6 exit is refused however clean the rest is" "$WORK" "k6 exited 99"
rm -rf "$WORK"

# And an exit status nobody recorded is not an exit status of zero. Without the
# file a failed threshold is invisible, which is the condition the gate was in.
WORK=$(setup_work); k6_exit "$WORK" -
expect_invalid "an unrecorded k6 exit is refused, not assumed zero" "$WORK" "was not recorded"
rm -rf "$WORK"

# ------------------------------------------- 2. the constant overlap metric

# The exact shape of the broken metric: every sample identical. A spread of
# zero satisfied the old `spread < duration` rule perfectly, so the strongest
# possible evidence and no evidence at all were indistinguishable.
WORK=$(setup_work start_min=500 start_max=500 end_min=600 end_max=600)
expect_invalid "identical timings on every accept are a constant, not synchronisation" "$WORK" "a constant, not a measurement"
rm -rf "$WORK"

# The impossible value itself. waitForBarrier returns only once the barrier has
# passed, so a negative offset cannot be measured — and the barrier shares a
# clock with the timings that decide overlap.
WORK=$(setup_work offset_min=-1150 offset_max=-1150)
expect_invalid "a negative barrier offset is refused as broken instrumentation" "$WORK" "which cannot happen"
rm -rf "$WORK"

# Timestamps measured from setup cannot be negative either.
WORK=$(setup_work start_min=-20 start_max=40)
expect_invalid "negative accept timestamps are refused" "$WORK" "instrumentation is broken"
rm -rf "$WORK"

# An end before the start: the clock disagreeing with itself.
WORK=$(setup_work start_min=900 start_max=950 end_min=100 end_max=200)
expect_invalid "an accept closing before any opened is refused" "$WORK" "disagrees with itself"
rm -rf "$WORK"

# Timings absent altogether, which is how the first run's summary actually
# reads once the broken metric is no longer trusted. The needle names the
# library's refusal specifically.
WORK=$(setup_work start_min=- start_max=- end_min=- end_max=- timing_samples=-)
expect_invalid "absent timings are refused, not noted" "$WORK" "overlap is unverifiable"
rm -rf "$WORK"

# And the sample COUNTER absent while the timings themselves are present.
#
# Needs its own case and its own message. Without a count, the min and max of
# the timing Trends describe an unknown number of accepts — which is exactly
# the ambiguity that let one impossible sample pass for a measurement of
# twenty-nine, since k6's summary export reports no count for a Trend.
#
# This case exists because deleting the check survived the suite: the previous
# assertion shared the word "unverifiable" with the library's refusal, so it
# could not tell which of the two had spoken.
WORK=$(setup_work timing_samples=-)
expect_invalid "an absent timing-sample counter is refused on its own terms" "$WORK" "carries no kalo_accept_timing_samples"
rm -rf "$WORK"

# One timed accept has nothing to overlap with, however good its numbers look.
WORK=$(setup_work contested=1 entered=1 won=1 lost=0 timing_samples=1 start_max=0 end_max=120)
EXPECTED_RACE_VUS=1 expect_invalid "a single timed accept is not overlap" "$WORK" "nothing to overlap with"
rm -rf "$WORK"

# Accepts that queued: the last one was issued long after the first had closed.
# Sequential requests, each finding the driver free in turn, presented as a
# race — sixty seconds of them.
#
# This case is why the library offers no partial-overlap verdict. It first
# passed here as "at least two accepts were in flight at once", because
# max(end) was the last accept's own end and the comparison that produced that
# sentence was true of any run with a non-zero duration.
WORK=$(setup_work start_min=0 start_max=60000 end_min=120 end_max=60080)
expect_invalid "accepts spread over a minute are a queue, not a race" "$WORK" "queued rather than raced"
rm -rf "$WORK"

# The same hole in miniature: starts spread by more than an accept lasts. No
# accept was still open when the last was issued, so nothing contended, and the
# numbers are small enough to look innocuous.
WORK=$(setup_work start_min=0 start_max=150 end_min=100 end_max=250)
expect_invalid "starts spread wider than an accept lasts is refused" "$WORK" "queued rather than raced"
rm -rf "$WORK"

# Fewer timings than accepts: the overlap shown belongs to part of the run.
# This is the ambiguity a Trend alone cannot resolve, which is why the sample
# count is a Counter.
WORK=$(setup_work timing_samples=4)
expect_invalid "fewer timings than accepts is refused" "$WORK" "describe part of the run"
rm -rf "$WORK"

# ------------------------------------------------- 3. the missing participant

# 29 of 30 virtual users reached an accept. One booking failed, the scenario
# returned quietly, and the run was reported on 29 as though that had been the
# plan. The old floor — at least two contested accepts — passed it easily.
WORK=$(setup_work contested=29 lost=28 timing_samples=29 no_booking=1)
expect_invalid "29 accepts from 30 configured users is refused" "$WORK" "never reached an accept"
rm -rf "$WORK"

# The denominator itself missing. A summary that cannot say how many users
# entered cannot say whether they all took part.
WORK=$(setup_work entered=-)
expect_invalid "a summary with no participant count is refused" "$WORK" "no kalo_race_entered"
rm -rf "$WORK"

# More users than were configured, with the arithmetic closing perfectly: 31
# entered, 30 accepted, 1 recorded a failed booking. Every other check is
# satisfied, so only the comparison against RACE_VUS can catch it — which is
# how this case came to be written, as a mutation that survived the suite.
#
# It matters because the run would be reported as the 30-user race it was
# configured to be while having been something else.
WORK=$(setup_work entered=31 no_booking=1)
expect_invalid "more participants than configured is refused" "$WORK" "not the size it claims"
rm -rf "$WORK"

# An attempt that vanished without a reason: entered exceeds contested plus
# every recorded excuse, so something was dropped silently.
WORK=$(setup_work contested=29 lost=28 timing_samples=29 no_booking=0)
expect_invalid "an unexplained missing attempt is refused" "$WORK" "vanished without explanation"
rm -rf "$WORK"

# Outcomes that do not sum to the attempts. Something was lost between the
# won, lost and unexpected buckets.
WORK=$(setup_work lost=20)
expect_invalid "outcomes that do not sum to the attempts are refused" "$WORK" "unaccounted for"
rm -rf "$WORK"

# ------------------------------------------------ 4. the skipped scenarios

# Both of these recorded nothing in the first real run: they shared the raced
# fleet, whose only driver was BUSY by the time they started, so every virtual
# user searched, found no offer and returned. The gate printed a polite note
# and passed. A scheduled scenario that attempted nothing tested nothing.
WORK=$(setup_work duplicate_attempts=0 duplicate_refused=0)
expect_invalid "a duplicate scenario that never ran fails the run" "$WORK" "duplicate scenario attempted nothing"
rm -rf "$WORK"

WORK=$(setup_work duplicate_attempts=- duplicate_refused=-)
expect_invalid "an absent duplicate counter fails the run" "$WORK" "duplicate scenario attempted nothing"
rm -rf "$WORK"

# Attempted, but nothing got as far as a second submission: no duplicate was
# ever decided, so the scenario produced no evidence either way.
WORK=$(setup_work duplicate_attempts=6 duplicate_refused=0 duplicate_accepted=0)
expect_invalid "duplicates attempted but never decided fails the run" "$WORK" "none reached a decision"
rm -rf "$WORK"

WORK=$(setup_work cancel_attempts=0 cancel_races=0)
expect_invalid "a cancel scenario that never ran fails the run" "$WORK" "cancel scenario attempted nothing"
rm -rf "$WORK"

WORK=$(setup_work cancel_attempts=6 cancel_races=0)
expect_invalid "cancel attempted but never raced fails the run" "$WORK" "no race was recorded"
rm -rf "$WORK"

# ---------------------------------------- accept-versus-timeout stays unproven

# Exported but never scheduled. Its absence must be stated on every run, since
# a scenario reported as nothing is a scenario a reader will assume passed.
WORK=$(setup_work)
expect_valid_saying "an unrun accept-versus-timeout is reported NOT VERIFIED" "$WORK" "NOT VERIFIED"
rm -rf "$WORK"

# ------------------------------------------- the only passing overlap verdict

# Full overlap, and the gate says exactly that: the last accept was issued
# while every other was still open.
WORK=$(setup_work)
expect_valid_saying "full overlap is reported as every accept" "$WORK" "every accept was in flight"
rm -rf "$WORK"

# The boundary. The last accept opens one millisecond before the first closes,
# which is still every accept in flight at once, and one millisecond later is
# not overlap at all.
WORK=$(setup_work start_min=0 start_max=119 end_min=120 end_max=200)
expect_valid_saying "a one-millisecond overlap still counts" "$WORK" "every accept was in flight"
rm -rf "$WORK"

WORK=$(setup_work start_min=0 start_max=120 end_min=120 end_max=240)
expect_invalid "the last accept opening as the first closes is not overlap" "$WORK" "queued rather than raced"
rm -rf "$WORK"

# ------------------------------------------------------------------ summary

rm -f "$BUILDER"

echo
echo "  $PASSES passed, $FAILURES failed"
[ "$FAILURES" -eq 0 ] || exit 1
