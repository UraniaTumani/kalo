#!/usr/bin/env bash
# Tests for load-heartbeat.sh.
#
#   bash scripts/test-load-heartbeat.sh
#
# The heartbeat is the one piece of the load harness whose failure makes every
# other number look BETTER — a fleet that has aged out of search produces fast,
# clean, meaningless results. That is worth testing, and it can be tested
# without a stack: the script talks to the world through exactly two commands,
# `docker` (for psql) and `curl`, so stubbing both on PATH exercises the real
# loop against a fleet of any size.
#
# No database, no containers, no network. Nothing here touches kalo_load, the
# development database or the rehearsal stack.
set -u

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PASSES=0
FAILURES=0

ok() { PASSES=$(( PASSES + 1 )); echo "  ok   $1"; }
bad() { FAILURES=$(( FAILURES + 1 )); echo "  FAIL $1"; }

check() {
  local label="$1" expected="$2" actual="$3"
  if [ "$expected" = "$actual" ]; then
    ok "$label"
  else
    bad "$label — expected [$expected], got [$actual]"
  fi
}

# Builds a throwaway PATH holding fake `docker` and `curl`.
#
# The fakes record every call to $CALLS so a test can count logins against
# location updates, which is the whole question for token reuse.
make_stubs() {
  local dir="$1" rows_file="$2" login_delay="${3:-0}"

  mkdir -p "$dir"

  cat > "$dir/docker" <<STUB
#!/usr/bin/env bash
# Only ever asked for the ONLINE driver rows.
echo "docker \$*" >> "$CALLS"
cat "$rows_file"
STUB

  cat > "$dir/curl" <<STUB
#!/usr/bin/env bash
# Two shapes of call: a login, which returns a token, and a location PUT, which
# returns an HTTP code because the caller asks for one with -w.
for arg in "\$@"; do
  case "\$arg" in
    */auth/login)
      echo "login \$arg" >> "$CALLS"
      sleep $login_delay
      echo '{"accessToken":"stub-token","refreshToken":"r"}'
      exit 0
      ;;
    */drivers/*/location)
      echo "location \$arg" >> "$CALLS"
      echo "200"
      exit 0
      ;;
  esac
done
echo "other \$*" >> "$CALLS"
exit 0
STUB

  chmod +x "$dir/docker" "$dir/curl"
}

# One pass of the real script against the stubs.
run_pass() {
  local workdir="$1"; shift

  CALLS="$workdir/calls.log"
  : > "$CALLS"

  make_stubs "$workdir/bin" "$workdir/rows" "${LOGIN_DELAY:-0}"

  PATH="$workdir/bin:$PATH" \
  HEARTBEAT_ONCE=1 \
  HEARTBEAT_BREACH_FILE="$workdir/breach" \
  HEARTBEAT_INTERVAL="${INTERVAL_UNDER_TEST:-45}" \
    bash "$ROOT/scripts/load-heartbeat.sh" > "$workdir/out" 2>&1

  echo $?
}

# 100 companies, two drivers each, grouped as the ordered query would deliver.
fleet_rows() {
  local companies="$1" per_company="$2" out="$3"

  : > "$out"
  local c d id=0
  for c in $(seq 1 "$companies"); do
    for d in $(seq 1 "$per_company"); do
      id=$(( id + 1 ))
      printf '+35569%06d|%d\n' "$c" "$id" >> "$out"
    done
  done
}

echo "load-heartbeat.sh"

# ---------------------------------------------------------------- token reuse

WORK=$(mktemp -d)
fleet_rows 1 5 "$WORK/rows"
run_pass "$WORK" > /dev/null

check "one company with five drivers logs in once" \
  "1" "$(grep -c '^login ' "$WORK/calls.log")"

check "one company with five drivers updates five positions" \
  "5" "$(grep -c '^location ' "$WORK/calls.log")"

rm -rf "$WORK"

# ------------------------------------------------- the hundred-company fleet

WORK=$(mktemp -d)
fleet_rows 100 2 "$WORK/rows"
STATUS=$(run_pass "$WORK")

check "100 companies x 2 drivers: exit 0" "0" "$STATUS"

# The regression this file exists for. Unordered rows made the token cache miss
# and the script performed a bcrypt login per driver; grouped rows make it one
# per company. 100 rather than 200 is the entire fix.
check "100 companies x 2 drivers: 100 logins, not 200" \
  "100" "$(grep -c '^login ' "$WORK/calls.log")"

check "100 companies x 2 drivers: 200 position updates" \
  "200" "$(grep -c '^location ' "$WORK/calls.log")"

check "every driver is updated exactly once" \
  "200" "$(grep '^location ' "$WORK/calls.log" | sort -u | wc -l | tr -d ' ')"

check "the pass reports its own duration and the window" \
  "1" "$(grep -c 'cycle .*s / window 120s' "$WORK/out")"

check "no breach recorded for a fast pass" \
  "0" "$([ -f "$WORK/breach" ] && wc -l < "$WORK/breach" | tr -d ' ' || echo 0)"

rm -rf "$WORK"

# ----------------------------------------------- why the ordering is required

# The honest limit of the tests above, and the pair of checks that closes it.
#
# The `docker` stub returns a fixture file, so it would keep returning grouped
# rows even if the ORDER BY were deleted from the query. Those tests therefore
# prove that the loop reuses a token WHEN rows arrive grouped — not that the
# query asks for them that way. Two checks are needed, and neither alone is
# worth much:
#
#   this one      the loop is genuinely sensitive to ordering, shown by feeding
#                 it interleaved rows and watching the logins multiply
#   the next one  the query actually requests the grouping the loop relies on

WORK=$(mktemp -d)

# The same fleet, interleaved — which is what an unordered query returns.
: > "$WORK/rows"
for d in 1 2; do
  for c in $(seq 1 50); do
    printf '+35569%06d|%d%d\n' "$c" "$c" "$d" >> "$WORK/rows"
  done
done

run_pass "$WORK" > /dev/null

# 100 logins for 100 drivers: every row is a different company from the last,
# so the cache never hits. Grouped, the same fleet costs 50.
check "interleaved rows cost one login per driver, proving order matters" \
  "100" "$(grep -c '^login ' "$WORK/calls.log")"

rm -rf "$WORK"

check "the query asks for drivers grouped by partner phone" \
  "1" "$(grep -c 'ORDER BY u.phone' "$ROOT/scripts/load-heartbeat.sh")"

# -------------------------------------------------------- slow pass detection

# A pass slow enough that pass + interval reaches the 120s window. Faked by
# making each login sleep, which is what a contended bcrypt path does under
# load, and by setting an interval that leaves little room.
WORK=$(mktemp -d)
fleet_rows 4 1 "$WORK/rows"
LOGIN_DELAY=1 INTERVAL_UNDER_TEST=118 run_pass "$WORK" > /dev/null

check "a cycle reaching the window is reported as a failure" \
  "1" "$(grep -c '✗ heartbeat cycle' "$WORK/out")"

check "the breach is recorded where orchestration can find it" \
  "1" "$(grep -c 'window=120s' "$WORK/breach")"

rm -rf "$WORK"

# ------------------------------------------------------- narrow margin warning

WORK=$(mktemp -d)
fleet_rows 2 1 "$WORK/rows"
INTERVAL_UNDER_TEST=95 run_pass "$WORK" > /dev/null

check "a cycle inside 25% of the window warns without failing" \
  "1" "$(grep -c '! heartbeat cycle' "$WORK/out")"

check "a warning is not recorded as a breach" \
  "0" "$([ -f "$WORK/breach" ] && wc -l < "$WORK/breach" | tr -d ' ' || echo 0)"

rm -rf "$WORK"

# --------------------------------------------------------------- missing data

# The false-positive this guards against: an empty fleet produces a pass with
# nothing to do, which on its own looks like a clean, fast, successful pass.
WORK=$(mktemp -d)
: > "$WORK/rows"
STATUS=$(run_pass "$WORK")

check "no ONLINE drivers is an error, not an idle pass" "1" "$STATUS"

check "and says why" \
  "1" "$(grep -c 'no ONLINE drivers found' "$WORK/out")"

check "and records it for orchestration" \
  "1" "$(grep -c 'no-online-drivers' "$WORK/breach")"

check "with no position updates attempted" \
  "0" "$(grep -c '^location ' "$WORK/calls.log")"

rm -rf "$WORK"

# ------------------------------------------------------------ failed updates

WORK=$(mktemp -d)
fleet_rows 1 3 "$WORK/rows"
CALLS="$WORK/calls.log"
: > "$CALLS"
make_stubs "$WORK/bin" "$WORK/rows"

# A curl that refuses the location PUT, as a rate-limited or erroring backend
# would. Failures must be counted and surfaced rather than passed over.
cat > "$WORK/bin/curl" <<STUB
#!/usr/bin/env bash
for arg in "\$@"; do
  case "\$arg" in
    */auth/login) echo '{"accessToken":"stub-token"}' ; exit 0 ;;
    */drivers/*/location) echo "location \$arg" >> "$CALLS" ; echo "429" ; exit 0 ;;
  esac
done
exit 0
STUB
chmod +x "$WORK/bin/curl"

PATH="$WORK/bin:$PATH" HEARTBEAT_ONCE=1 HEARTBEAT_BREACH_FILE="$WORK/breach" \
  bash "$ROOT/scripts/load-heartbeat.sh" > "$WORK/out" 2>&1

check "refused updates are counted, not silently dropped" \
  "1" "$(grep -c '3 update(s) or login(s) failed' "$WORK/out")"

check "and the pass reports zero successful updates" \
  "1" "$(grep -c 'pass 1: 0 updated' "$WORK/out")"

rm -rf "$WORK"

# ------------------------------------------------------------------- summary

echo
echo "  $PASSES passed, $FAILURES failed"
[ "$FAILURES" -eq 0 ] || exit 1
