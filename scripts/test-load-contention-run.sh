#!/usr/bin/env bash
# Tests for load-contention-run.sh.
#
#   bash scripts/test-load-contention-run.sh
#
# Two kinds of check, and the first kind is the point of the file.
#
# STATIC. The runner's one promise is that it destroys nothing, and a promise
# made in a comment is worth nothing. These greps assert it against the source:
# no `down`, no `-v`, no truncate, no drop. They would be trivial to satisfy
# dishonestly if the script called out to something else that did the deleting,
# so the set of commands it may invoke is pinned too.
#
# BEHAVIOURAL. The runner refuses rather than repairs: a fleet of the wrong
# shape, a busy driver, an expired licence. Each refusal is exercised against a
# stubbed `docker`, because each one is a case where carrying on would produce
# a run that looked clean and proved nothing.
#
# No stack, no containers, no database.
set -u

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SCRIPT="$ROOT/scripts/load-contention-run.sh"
PASSES=0
FAILURES=0

ok() { PASSES=$(( PASSES + 1 )); echo "  ok   $1"; }
bad() { FAILURES=$(( FAILURES + 1 )); echo "  FAIL $1"; }

echo "load-contention-run.sh"

# =====================================================================
# STATIC: it cannot destroy anything
# =====================================================================

# Comments explain the hazard and must not be mistaken for it, so the source is
# stripped of comment lines before being searched.
CODE=$(grep -v '^[[:space:]]*#' "$SCRIPT")

# Case-insensitive throughout, and matching the compose VARIABLE as well as
# the literal command.
#
# The first version of these patterns was case-sensitive and looked only for a
# lowercase "docker compose ... down". The script invokes compose through
# $COMPOSE, so planting `$COMPOSE down -v` in it satisfied every guard here —
# the whole set was decorative. It was caught by planting exactly that, which
# is now what keeps them honest: scripts/test-load-contention-run.sh is only
# worth having if the hazards it forbids actually fail it.
refuses_pattern() {
  local label="$1" pattern="$2"
  if echo "$CODE" | grep -Eiq "$pattern"; then
    bad "$label — found: $(echo "$CODE" | grep -Ein "$pattern" | head -2 | tr '\n' ' ')"
  else
    ok "$label"
  fi
}

COMPOSE_CALL='(\$COMPOSE|docker[[:space:]]+compose)'

refuses_pattern "never runs 'compose down'" "${COMPOSE_CALL}[^#]*[[:space:]]down([[:space:]]|\$)"
refuses_pattern "never passes -v or --volumes to compose" "${COMPOSE_CALL}[^#]*(-v([[:space:]]|\$)|--volumes)"
refuses_pattern "never truncates a table" '\btruncate\b'
refuses_pattern "never drops anything" '\bdrop\b'
refuses_pattern "never deletes rows" '\bdelete[[:space:]]+from\b'
refuses_pattern "never removes a volume" '\bvolume[[:space:]]+(rm|prune)\b'
refuses_pattern "never prunes anything" '\b(system|container|image)[[:space:]]+prune\b'
refuses_pattern "never calls the destructive suite runner" 'load-test\.sh'
refuses_pattern "never seeds on its own" 'load-seed\.sh[^"]*$'

# And the positive half: it does bring the stack up, which is the one compose
# verb it is allowed. Without this the greps above would pass for a script that
# had stopped working altogether.
if echo "$CODE" | grep -Eq 'COMPOSE up -d'; then
  ok "does bring the stack up, which is the only compose verb it needs"
else
  bad "does bring the stack up"
fi

if echo "$CODE" | grep -q 'load-contention-check.sh'; then
  ok "hands the verdict to the validity gate rather than deciding itself"
else
  bad "hands the verdict to the validity gate"
fi

# The gate is only exact if it is told the same numbers the scenario used.
if echo "$CODE" | grep -q 'EXPECTED_RACE_VUS="\$RACE_VUS"'; then
  ok "tells the gate the virtual-user count the scenario actually ran"
else
  bad "tells the gate the virtual-user count the scenario actually ran"
fi

if echo "$CODE" | grep -q 'EXPECTED_RACE_DRIVERS="\$RACE_DRIVERS"'; then
  ok "tells the gate the driver count the scenario actually ran"
else
  bad "tells the gate the driver count the scenario actually ran"
fi

# =====================================================================
# STATIC: its SQL names tables that exist
# =====================================================================

# The gap the stubs below cannot close, and it was a real bug.
#
# A stubbed `docker` answers whatever the test tells it to, so the runner's
# queries were never executed against a schema. The first version of them
# selected `FROM companies`, a table this project does not have — it is
# `taxi_companies` — and every behavioural test passed while the real script
# would have refused every run with "could not read the fleet".
#
# So each table the SQL names is checked against the Liquibase changelogs,
# which are the schema's definition. This catches a typo in a table name
# without a database, and it keeps catching one if the schema is renamed.
CHANGELOG="$ROOT/src/main/resources/db/changelog"

if [ -d "$CHANGELOG" ]; then
  # Case-SENSITIVE on purpose: the SQL writes its keywords in capitals and the
  # script's prose does not, so this picks up `FROM taxi_companies` and leaves
  # "BUSY from earlier runs" alone. Matching either case pulled in English
  # words and failed on them.
  SQL_TABLES=$(echo "$CODE" \
    | grep -oE '\b(FROM|JOIN)[[:space:]]+[a-z_][a-z0-9_]*' \
    | awk '{print $2}' \
    | sort -u)

  # The extraction is itself worth checking. If it quietly stopped finding
  # anything — a reformatted query, a lowercased keyword — the loop below would
  # pass having verified nothing, which is the failure mode this whole file is
  # about. These three must always be in the list.
  for required in taxi_companies drivers driver_locations; do
    if ! echo "$SQL_TABLES" | grep -qx "$required"; then
      bad "the table extraction did not find '$required' — it is no longer reading the SQL properly"
    fi
  done

  if [ -z "$SQL_TABLES" ]; then
    bad "could not find any table names in the runner's SQL — the check below would pass vacuously"
  else
    MISSING=""
    for t in $SQL_TABLES; do
      if ! grep -rqE "tableName=\"$t\"|tableName: $t" "$CHANGELOG" 2>/dev/null; then
        MISSING="$MISSING $t"
      fi
    done

    if [ -n "$MISSING" ]; then
      bad "the runner's SQL names table(s) absent from the schema:$MISSING"
    else
      ok "every table the SQL names exists in the Liquibase changelogs ($(echo "$SQL_TABLES" | tr '\n' ' '))"
    fi
  fi
else
  bad "changelog directory not found at $CHANGELOG — cannot verify the SQL's tables"
fi

# The fleet check is only meaningful if it applies the same rules as the real
# search. Each of these clauses is one of TaxiSearchRepository's; dropping any
# one of them counts a company the search would not return.
for clause in \
  "a.active = true" \
  "d.status = 'ACTIVE'" \
  "d.availability_status = 'ONLINE'" \
  "v.status = 'ACTIVE'" \
  "c.verification_status = 'APPROVED'" \
  "c.status = 'ACTIVE'" \
  "c.booking_enabled = true" \
  "location_updated_at" \
  "d.license_expiry_date" \
  "c.license_expiry_date"
do
  if echo "$CODE" | grep -qF "$clause"; then
    ok "the bookable query applies: $clause"
  else
    bad "the bookable query is missing the search's own clause: $clause"
  fi
done

# =====================================================================
# BEHAVIOURAL: it refuses a fleet it cannot race
# =====================================================================

# A `docker` that reports a healthy stack and a fleet of given shape.
#
# The runner asks compose for container states and psql for three counts and
# the current time, each identifiable by a fragment of its SQL. BOOKABLE is the
# interesting one: a single query that applies every clause the real search
# applies, so there is one precondition rather than four overlapping ones.
stub() {
  local dir="$1" bookable="$2" customers="$3" busy="${4:-0}"

  mkdir -p "$dir"
  cat > "$dir/docker" <<STUB
#!/usr/bin/env bash
case "\$*" in
  info*) echo "27.0.0"; exit 0 ;;
esac

# compose ps -q: a non-empty list means the stack is already up.
case "\$*" in
  *"ps -q"*) echo "abc123"; exit 0 ;;
  *"ps --format"*)
    printf 'postgres:healthy\nbackend:healthy\nfrontend:healthy\n'
    exit 0
    ;;
esac

for arg in "\$@"; do
  case "\$arg" in
    *"driver_vehicle_assignments"*) echo "$bookable"; exit 0 ;;
    *"'CUSTOMER'"*) echo "$customers"; exit 0 ;;
    *"availability_status = 'BUSY'"*) echo "$busy"; exit 0 ;;
    *"SELECT now()"*) echo "2026-10-11 04:00:00+02"; exit 0 ;;
  esac
done
echo "0"
STUB
  chmod +x "$dir/docker"

  # The run must never get as far as these; if it does, the test says so
  # instead of the script reaching a real stack.
  for blocked in k6; do
    cat > "$dir/$blocked" <<'STUB'
#!/usr/bin/env bash
echo "REACHED_$0" >&2
exit 97
STUB
    chmod +x "$dir/$blocked"
  done
}

# Runs the script far enough to hit its preconditions. The heartbeat and the
# run itself are stubbed out by giving it a PATH where `bash` still works but
# the scripts it calls are replaced.
run_it() {
  local work="$1"; shift
  mkdir -p "$work/scripts"

  # Stand-ins for everything the runner delegates to, so a precondition test
  # never starts a real run. Each records that it was reached.
  for s in load-heartbeat.sh load-run-one.sh load-contention-check.sh; do
    printf '#!/usr/bin/env bash\necho "CALLED %s" >> "%s/calls"\nexit 0\n' "$s" "$work" \
      > "$work/scripts/$s"
    chmod +x "$work/scripts/$s"
  done

  : > "$work/calls"

  # A copy of the runner beside the stand-ins, so its own `dirname` resolution
  # finds them instead of the real ones.
  mkdir -p "$work/load/results"
  cp "$SCRIPT" "$work/scripts/"
  cp "$ROOT/docker-compose.load.yml" "$work/" 2>/dev/null || touch "$work/docker-compose.load.yml"
  cp "$ROOT/.env.load.example" "$work/" 2>/dev/null || touch "$work/.env.load.example"

  PATH="$work/bin:$PATH" "$@" bash "$work/scripts/load-contention-run.sh" \
    > "$work/out" 2>&1
  echo $?
}

expect_refusal() {
  local label="$1" work="$2" needle="$3"
  local status
  status=$(run_it "$work")

  if [ "$status" = "0" ]; then
    bad "$label — ran anyway"
    sed 's/^/        /' "$work/out"
  elif grep -q "$needle" "$work/out"; then
    if grep -q 'CALLED load-run-one.sh' "$work/calls"; then
      bad "$label — refused, but only after starting the run"
    else
      ok "$label"
    fi
  else
    bad "$label — refused for another reason (wanted '$needle')"
    sed 's/^/        /' "$work/out"
  fi
}

# Three bookable companies and thirty customers: the shape the scenario needs
# for one raced driver. Must get past the preconditions.
WORK=$(mktemp -d); stub "$WORK/bin" 3 30
STATUS=$(run_it "$WORK")
if [ "$STATUS" = "0" ] && grep -q 'CALLED load-run-one.sh' "$WORK/calls"; then
  ok "a fleet of the right shape is accepted and the run starts"
else
  bad "a fleet of the right shape is accepted (status $STATUS)"
  sed 's/^/        /' "$WORK/out"
fi
rm -rf "$WORK"

# Too few bookable companies: duplicates and accept-versus-cancel would starve,
# which is exactly what happened on the first real run.
#
# One refusal now covers every cause — an offline or BUSY driver, an inactive
# vehicle, an unapproved company, booking disabled, a stale GPS fix, an expired
# driver or company licence — because the query counts what the real search
# would return rather than checking each rule separately and hoping the set is
# complete.
WORK=$(mktemp -d); stub "$WORK/bin" 1 30
expect_refusal "one bookable company is refused — the other scenarios would starve" "$WORK" "are bookable but 3 are needed"
rm -rf "$WORK"

WORK=$(mktemp -d); stub "$WORK/bin" 2 30
expect_refusal "two bookable companies is still one short" "$WORK" "are bookable but 3 are needed"
rm -rf "$WORK"

WORK=$(mktemp -d); stub "$WORK/bin" 0 30
expect_refusal "no bookable company at all is refused" "$WORK" "are bookable but 3 are needed"
rm -rf "$WORK"

# And the refusal names the remedy, including that it adds to what is there
# rather than replacing it.
WORK=$(mktemp -d); stub "$WORK/bin" 1 30
run_it "$WORK" > /dev/null
if grep -q 'Nothing here is reset; seed more alongside what exists' "$WORK/out"; then
  ok "the refusal says how to fix it without resetting anything"
else
  bad "the refusal says how to fix it without resetting anything"
  sed 's/^/        /' "$WORK/out"
fi
rm -rf "$WORK"

# A driver left BUSY by an earlier run is reported but is not an obstacle: it
# is not ONLINE, so it is not in the bookable count, and the run proceeds
# against the companies that are. This is what lets the script run against a
# preserved database instead of demanding a fresh one.
WORK=$(mktemp -d); stub "$WORK/bin" 3 30 1
STATUS=$(run_it "$WORK")
if [ "$STATUS" = "0" ] && grep -q '1 driver(s) BUSY from earlier runs' "$WORK/out"; then
  ok "a driver left BUSY by an earlier run is reported, not treated as a blocker"
else
  bad "a driver left BUSY by an earlier run is reported, not treated as a blocker (status $STATUS)"
  sed 's/^/        /' "$WORK/out"
fi
rm -rf "$WORK"

# Fewer customers than virtual users means users share a customer, and a
# customer can hold only one active ride, so attempts would fail for a reason
# that has nothing to do with the race.
WORK=$(mktemp -d); stub "$WORK/bin" 3 10
expect_refusal "fewer customers than virtual users is refused" "$WORK" "customers but 30 are needed"
rm -rf "$WORK"

# A database that cannot be read is not an empty fleet.
WORK=$(mktemp -d); mkdir -p "$WORK/bin"
cat > "$WORK/bin/docker" <<'STUB'
#!/usr/bin/env bash
case "$*" in
  info*) echo "27.0.0"; exit 0 ;;
  *"ps -q"*) echo "abc123"; exit 0 ;;
  *"ps --format"*) printf 'postgres:healthy\nbackend:healthy\nfrontend:healthy\n'; exit 0 ;;
esac
echo "psql: could not connect" >&2
exit 2
STUB
chmod +x "$WORK/bin/docker"
expect_refusal "an unreadable fleet is refused, not read as zero" "$WORK" "could not read the fleet"
rm -rf "$WORK"

# An unhealthy container means the measurement would be of the laptop.
WORK=$(mktemp -d); mkdir -p "$WORK/bin"
cat > "$WORK/bin/docker" <<'STUB'
#!/usr/bin/env bash
case "$*" in
  info*) echo "27.0.0"; exit 0 ;;
  *"ps -q"*) echo "abc123"; exit 0 ;;
  *"ps --format"*) printf 'postgres:healthy\nbackend:starting\nfrontend:healthy\n'; exit 0 ;;
esac
echo "0"
STUB
chmod +x "$WORK/bin/docker"
expect_refusal "an unhealthy container is refused" "$WORK" "not every container is healthy"
rm -rf "$WORK"

# A daemon that is not answering at all.
WORK=$(mktemp -d); mkdir -p "$WORK/bin"
printf '#!/usr/bin/env bash\nexit 1\n' > "$WORK/bin/docker"
chmod +x "$WORK/bin/docker"
expect_refusal "a dead docker daemon is refused" "$WORK" "daemon is not answering"
rm -rf "$WORK"

# ------------------------------------------------------------------ summary

echo
echo "  $PASSES passed, $FAILURES failed"
[ "$FAILURES" -eq 0 ] || exit 1
