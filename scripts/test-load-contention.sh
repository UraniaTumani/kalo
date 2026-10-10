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
# No stack. The gate reaches the world through `docker` and a k6 summary file,
# so a stub and a fixture are enough. Nothing here touches kalo_load, the
# development database or the rehearsal stack.
set -u

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PASSES=0
FAILURES=0

ok() { PASSES=$(( PASSES + 1 )); echo "  ok   $1"; }
bad() { FAILURES=$(( FAILURES + 1 )); echo "  FAIL $1"; }

# A k6 summary with the counters a run would have produced.
summary() {
  local file="$1" contested="$2" won="$3" lost="$4" unexpected="$5"
  local dup_refused="${6:-3}" dup_accepted="${7:-0}" errors="${8:-0}" spread="${9:-40}"

  cat > "$file" <<JSON
{
  "metrics": {
    "kalo_contested_accepts": { "count": $contested },
    "kalo_accept_won":         { "count": $won },
    "kalo_accept_lost":        { "count": $lost },
    "kalo_accept_unexpected":  { "count": $unexpected },
    "kalo_duplicate_refused":  { "count": $dup_refused },
    "kalo_duplicate_accepted": { "count": $dup_accepted },
    "kalo_server_errors":      { "count": $errors },
    "kalo_accept_offset_ms":   { "min": 0, "max": $spread, "avg": 10 }
  },
  "metrics_trend_note": "kalo_accept_offset_ms carries the barrier offsets"
}
JSON
}

# A `docker` whose psql answers the four invariant queries with given values.
stub_db() {
  local dir="$1" double_driver="$2" double_customer="$3" busy="$4" searching="$5"

  mkdir -p "$dir"
  cat > "$dir/docker" <<STUB
#!/usr/bin/env bash
# The gate asks four questions, in this order, each by a distinctive fragment.
for arg in "\$@"; do
  case "\$arg" in
    *"GROUP BY driver_id HAVING"*) echo "$double_driver"; exit 0 ;;
    *"GROUP BY customer_id HAVING"*) echo "$double_customer"; exit 0 ;;
    *"availability_status = 'BUSY'"*) echo "$busy"; exit 0 ;;
    *"rq.status = 'SEARCHING'"*) echo "$searching"; exit 0 ;;
  esac
done
echo "0"
STUB
  chmod +x "$dir/docker"
}

# Runs the gate and returns its exit status; output lands in <work>/out.
#
# The caller reads that file directly rather than a variable set in here: this
# runs in a command substitution, so anything assigned would not survive.
run_gate() {
  local work="$1"
  PATH="$work/bin:$PATH" bash "$ROOT/scripts/load-contention-check.sh" "$work/summary.json" "$work" > "$work/out" 2>&1
  echo $?
}

expect_invalid() {
  local label="$1" work="$2" needle="$3"
  local status
  status=$(run_gate "$work")

  if [ "$status" = "0" ]; then
    bad "$label — gate accepted a run it should have refused"
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

echo "load-contention-check.sh"

# --------------------------------------------------- the positive control

# 30 accepts on one driver: one wins, 29 lose, database holds. The only shape
# that should pass, and it has to pass or every refusal below means nothing.
WORK=$(mktemp -d); stub_db "$WORK/bin" 0 0 0 0
summary "$WORK/summary.json" 30 1 29 0
expect_valid "a real race passes: 1 won, 29 lost, invariants clean" "$WORK"
rm -rf "$WORK"

# ------------------------------------------- no contention actually happened

# The headline false positive. Every accept succeeded, which means every one
# found a free driver — a fleet too large for the traffic, reported as flawless
# concurrency handling.
WORK=$(mktemp -d); stub_db "$WORK/bin" 0 0 0 0
summary "$WORK/summary.json" 30 30 0 0
expect_invalid "all accepts won — no race occurred" "$WORK" "no accept was refused"
rm -rf "$WORK"

# The mirror image: nothing was assigned at all, usually because the drivers
# were already busy or booking failed. Looks like perfect refusal discipline.
WORK=$(mktemp -d); stub_db "$WORK/bin" 0 0 0 0
summary "$WORK/summary.json" 30 0 30 0
expect_invalid "all accepts lost — nothing was ever assigned" "$WORK" "no accept succeeded"
rm -rf "$WORK"

# Barely any traffic. Two accepts with one winner is technically a race and
# tells you almost nothing.
WORK=$(mktemp -d); stub_db "$WORK/bin" 0 0 0 0
summary "$WORK/summary.json" 1 1 0 0
expect_invalid "a single accept is not contention" "$WORK" "too few to demonstrate"
rm -rf "$WORK"

# ------------------------------------------------------ overlap not achieved

# Accepts spread over a minute: each found the driver free in turn. Sequential
# requests presented as simultaneous ones.
WORK=$(mktemp -d); stub_db "$WORK/bin" 0 0 0 0
summary "$WORK/summary.json" 30 1 29 0 3 0 0 60000
expect_invalid "accepts spread too far apart to contend" "$WORK" "too far apart"
rm -rf "$WORK"

# ------------------------------------------------- genuine defects detected

WORK=$(mktemp -d); stub_db "$WORK/bin" 1 0 0 0
summary "$WORK/summary.json" 30 1 29 0
expect_invalid "two active rides on one driver is refused" "$WORK" "more than one active ride"
rm -rf "$WORK"

WORK=$(mktemp -d); stub_db "$WORK/bin" 0 1 0 0
summary "$WORK/summary.json" 30 1 29 0
expect_invalid "two active rides on one customer is refused" "$WORK" "customer holds more than one"
rm -rf "$WORK"

# The accept-versus-cancel corruptions, which no counter would reveal.
WORK=$(mktemp -d); stub_db "$WORK/bin" 0 0 1 0
summary "$WORK/summary.json" 30 1 29 0
expect_invalid "a driver left BUSY with no ride is refused" "$WORK" "stranded by a lost race"
rm -rf "$WORK"

WORK=$(mktemp -d); stub_db "$WORK/bin" 0 0 0 1
summary "$WORK/summary.json" 30 1 29 0
expect_invalid "an assigned ride whose request is SEARCHING is refused" "$WORK" "back to SEARCHING"
rm -rf "$WORK"

# ----------------------------------------------------- unexplained outcomes

WORK=$(mktemp -d); stub_db "$WORK/bin" 0 0 0 0
summary "$WORK/summary.json" 30 1 26 3
expect_invalid "unclassified accept failures are not averaged away" "$WORK" "unclassified reason"
rm -rf "$WORK"

WORK=$(mktemp -d); stub_db "$WORK/bin" 0 0 0 0
summary "$WORK/summary.json" 30 1 29 0 3 0 2
expect_invalid "a refusal that became a crash is refused" "$WORK" "became a crash"
rm -rf "$WORK"

WORK=$(mktemp -d); stub_db "$WORK/bin" 0 0 0 0
summary "$WORK/summary.json" 30 1 29 0 0 4
expect_invalid "an accepted duplicate submission is a defect" "$WORK" "duplicate submission"
rm -rf "$WORK"

# ------------------------------------------------- unverifiable overlap

# The gate used to read a field k6 never produces, so it printed a polite note
# and passed. An unverified central claim is not a passing one.
WORK=$(mktemp -d); stub_db "$WORK/bin" 0 0 0 0
cat > "$WORK/summary.json" <<JSON
{ "metrics": {
  "kalo_contested_accepts": { "count": 30 },
  "kalo_accept_won": { "count": 1 },
  "kalo_accept_lost": { "count": 29 },
  "kalo_accept_unexpected": { "count": 0 },
  "kalo_duplicate_refused": { "count": 3 },
  "kalo_duplicate_accepted": { "count": 0 },
  "kalo_server_errors": { "count": 0 }
} }
JSON
expect_invalid "missing accept timings is refused, not noted" "$WORK" "overlap could not be verified"
rm -rf "$WORK"

# ------------------------------------------------------------ missing input

WORK=$(mktemp -d); stub_db "$WORK/bin" 0 0 0 0
expect_invalid "a missing k6 summary is refused, not assumed clean" "$WORK" "nothing to judge"
rm -rf "$WORK"

# A summary whose counters are all absent reads as zeros, which must land on
# the same refusals rather than on a pass.
WORK=$(mktemp -d); stub_db "$WORK/bin" 0 0 0 0
echo '{"metrics":{}}' > "$WORK/summary.json"
expect_invalid "an empty summary is refused" "$WORK" "no accept succeeded"
rm -rf "$WORK"

# ------------------------------------------------------------------ summary

echo
echo "  $PASSES passed, $FAILURES failed"
[ "$FAILURES" -eq 0 ] || exit 1
