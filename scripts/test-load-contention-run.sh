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
# RUNNER_SCRIPT points this suite at a different copy of the runner, which is
# how the runner gets mutation-tested: a mutant lives in its own file and the
# real one is never written to, so an interrupted mutation run cannot leave a
# disabled safety guard behind in the script that drives real runs. That is not
# hypothetical — an in-place mutation run did exactly that, and only a failing
# test caught the leftover.
SCRIPT="${RUNNER_SCRIPT:-$ROOT/scripts/load-contention-run.sh}"
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
refuses_pattern "never inserts rows" '\binsert[[:space:]]+into\b'
refuses_pattern "never updates rows" '\bupdate[[:space:]]+[a-z_]+[[:space:]]+set\b'
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
    *"string_agg(id::text"*) echo "4, 5, 6"; exit 0 ;;
    *"phone NOT IN"*) echo "3"; exit 0 ;;
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
# Writes the seed fixtures a run is scoped to.
#
# load-seed.sh truncates these on every seed, so they name only the companies
# and customers the latest seed created. That is the whole isolation mechanism
# between a preserved run and the next one, so these tests write them
# explicitly rather than letting a default stand in for them.
fixtures() {
  local work="$1" partners="$2" customers="$3"
  mkdir -p "$work/load/.data"

  {
    echo "["
    local i
    for i in $(seq 1 "$partners"); do
      [ "$i" -eq 1 ] || echo ","
      printf '  {"phone":"+35591009%03d"}' "$i"
    done
    echo "]"
  } > "$work/load/.data/partners.json"

  {
    echo "["
    local u
    for u in $(seq 1 "$customers"); do
      [ "$u" -eq 1 ] || echo ","
      printf '  {"phone":"+35569009%03d"}' "$u"
    done
    echo "]"
  } > "$work/load/.data/users.json"
}

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
  mkdir -p "$work/load/results" "$work/load/.data"
  cp "$SCRIPT" "$work/scripts/"

  # The fixtures that name this run's own companies and customers. Written
  # unless the case has already written its own, because they are the thing
  # that scopes a run to what it seeded and most cases want a valid pair.
  [ -f "$work/load/.data/partners.json" ] || fixtures "$work" 3 38
  cp "$ROOT/docker-compose.load.yml" "$work/" 2>/dev/null || touch "$work/docker-compose.load.yml"
  cp "$ROOT/.env.load.example" "$work/" 2>/dev/null || touch "$work/.env.load.example"

  PATH="$work/bin:$PATH" "$@" bash "$work/scripts/load-contention-run.sh" \
    > "$work/out" 2>&1
  echo $?
}

# Runs the script with one of its delegates made to fail.
#
# The ordinary stand-ins all exit 0, which left the runner's handling of a
# failing run or a refusing gate completely untested: every precondition test
# passed and nothing exercised what happens after the run starts. A k6
# threshold failure reaching the runner as exit 3 and being reported as success
# is exactly the shape of bug this whole branch is about.
run_it_with_failure() {
  local work="$1" failing="$2" code="$3"
  mkdir -p "$work/scripts" "$work/load/results" "$work/load/.data"

  for s in load-heartbeat.sh load-run-one.sh load-contention-check.sh; do
    if [ "$s" = "$failing" ]; then
      printf '#!/usr/bin/env bash\necho "CALLED %s" >> "%s/calls"\nexit %s\n' \
        "$s" "$work" "$code" > "$work/scripts/$s"
    else
      printf '#!/usr/bin/env bash\necho "CALLED %s" >> "%s/calls"\nexit 0\n' \
        "$s" "$work" > "$work/scripts/$s"
    fi
    chmod +x "$work/scripts/$s"
  done

  : > "$work/calls"
  cp "$SCRIPT" "$work/scripts/"
  [ -f "$work/load/.data/partners.json" ] || fixtures "$work" 3 38
  touch "$work/docker-compose.load.yml" "$work/.env.load.example"

  PATH="$work/bin:$PATH" bash "$work/scripts/load-contention-run.sh" \
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
if grep -q 'Companies from earlier runs do not count and cannot be targeted' "$WORK/out"; then
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
if [ "$STATUS" = "0" ] && grep -q 'untouched elsewhere in this database: .*1 BUSY driver' "$WORK/out"; then
  ok "a driver left BUSY by an earlier run is reported, not treated as a blocker"
else
  bad "a driver left BUSY by an earlier run is reported, not treated as a blocker (status $STATUS)"
  sed 's/^/        /' "$WORK/out"
fi
rm -rf "$WORK"

# =====================================================================
# ISOLATION: a run can only target what it seeded
# =====================================================================
#
# The load database is kept between runs, so these are the checks that keep a
# second run from leaning on a first one's fleet. The scenario reads
# partners.json and nothing else; every count the runner checks has to be
# scoped the same way, or the precondition passes on companies the scenario
# cannot see.

# Fewer customers than virtual users means users share a customer, and a
# customer can hold only one active ride, so attempts would fail for a reason
# that has nothing to do with the race.
#
# Counted from the FIXTURE, not from the users table: after an additive seed
# the table holds every earlier run's customers too, and those are not the
# ones the scenario will log in as.
WORK=$(mktemp -d); stub "$WORK/bin" 3 999; fixtures "$WORK" 3 10
expect_refusal "fewer customers in the fixture than virtual users is refused" "$WORK" "fixture names 10 customer(s)"
rm -rf "$WORK"

# Exactly RACE_VUS customers is NOT enough, which is the trap.
#
# Thirty looks right — one per virtual user in the race — and leaves nothing
# for the duplicate and cancel scenarios, whose customer bands then wrap round
# onto the race's. A customer may hold only one active ride, so the cancel
# scenario would draw the customer that had just won the race, be refused by
# the unique index, and record no race at all: one virtual user, one chance,
# and the whole run failed for it.
WORK=$(mktemp -d); stub "$WORK/bin" 3 999; fixtures "$WORK" 3 30
expect_refusal "exactly one customer per racing user is refused as too few" "$WORK" "38 are needed"
rm -rf "$WORK"

# And the refusal explains the bands rather than just quoting a number.
WORK=$(mktemp -d); stub "$WORK/bin" 3 999; fixtures "$WORK" 3 30
run_it "$WORK" > /dev/null
if grep -q "band no other scenario touches" "$WORK/out"; then
  ok "the customer-shortfall refusal explains why more are needed"
else
  bad "the customer-shortfall refusal explains why more are needed"
  sed 's/^/        /' "$WORK/out"
fi
rm -rf "$WORK"

# The mirror: the database is full of customers from earlier runs and the
# fixture names enough of its own. Must pass, and must pass on the fixture's
# count rather than the table's.
WORK=$(mktemp -d); stub "$WORK/bin" 3 999; fixtures "$WORK" 3 38
STATUS=$(run_it "$WORK")
if [ "$STATUS" = "0" ]; then
  ok "customers from earlier runs neither help nor hinder the count"
else
  bad "customers from earlier runs neither help nor hinder the count (status $STATUS)"
  sed 's/^/        /' "$WORK/out"
fi
rm -rf "$WORK"

# A fixture naming too few companies, while the database has plenty bookable.
#
# This is the gap that scoping closes. A seed that half failed leaves one new
# company and several old ones; a global count says "three are bookable" and
# the scenario, which can only read the file, races a fleet of one.
WORK=$(mktemp -d); stub "$WORK/bin" 3 30; fixtures "$WORK" 1 38
expect_refusal "a fixture naming one company is refused however many the database holds" "$WORK" "fixture names 1 company"
rm -rf "$WORK"

# And the refusal says plainly that earlier runs' companies are not candidates.
WORK=$(mktemp -d); stub "$WORK/bin" 3 30; fixtures "$WORK" 2 38
run_it "$WORK" > /dev/null
if grep -q 'Re-seed with' "$WORK/out"; then
  ok "the fixture-shortfall refusal names the remedy"
else
  bad "the fixture-shortfall refusal names the remedy"
  sed 's/^/        /' "$WORK/out"
fi
rm -rf "$WORK"

# No fixture at all is not an empty fleet; it is a run that has not been
# seeded, and it must say so rather than querying for zero and refusing on a
# count.
WORK=$(mktemp -d); stub "$WORK/bin" 3 30
mkdir -p "$WORK/load/.data"
echo '[]' > "$WORK/load/.data/partners.json"
echo '[]' > "$WORK/load/.data/users.json"
expect_refusal "an empty partner fixture is refused" "$WORK" "fixture names 0 company"
rm -rf "$WORK"

WORK=$(mktemp -d); stub "$WORK/bin" 3 30
mkdir -p "$WORK/load/.data"
printf '[\n  {"phone":"+355910091"},\n  {"phone":"+355910092"},\n  {"phone":"+355910093"}]\n' > "$WORK/load/.data/partners.json"
expect_refusal "a missing customer fixture is refused" "$WORK" "no customer fixture"
rm -rf "$WORK"

# The case the fixture COUNT cannot catch, and the one the scoping exists for.
#
# The fixture names three companies and the seed only created one of them --
# two registrations failed, which a seed run does not stop for. So
# FIXTURE_PARTNERS is 3 and passes, and the question is what "bookable" counts.
#
# Unscoped, it finds the one new company plus two left by an earlier run and
# reports three: the precondition passes and the scenario, which can only read
# the file, races a fleet of one and is refused later for a shortfall nobody
# was warned about. Scoped to the fixture's phones, it reports one and refuses
# here, with the reason.
#
# This stub answers both forms differently, so the test fails if the query
# stops being scoped.
stub_scoped() {
  local dir="$1"
  mkdir -p "$dir"
  cat > "$dir/docker" <<'STUB'
#!/usr/bin/env bash
case "$*" in
  info*) echo "27.0.0"; exit 0 ;;
  *"ps -q"*) echo "abc123"; exit 0 ;;
  *"ps --format"*) printf 'postgres:healthy\nbackend:healthy\nfrontend:healthy\n'; exit 0 ;;
esac

for arg in "$@"; do
  case "$arg" in
    # Scoped to the fixture: only the one company this seed actually created.
    *"driver_vehicle_assignments"*"c.phone IN"*) echo "1"; exit 0 ;;
    # Unscoped: that one plus two from earlier runs.
    *"driver_vehicle_assignments"*) echo "3"; exit 0 ;;
    *"string_agg(id::text"*) echo "4"; exit 0 ;;
    *"phone NOT IN"*) echo "3"; exit 0 ;;
    *"'CUSTOMER'"*) echo "999"; exit 0 ;;
    *"availability_status = 'BUSY'"*) echo "1"; exit 0 ;;
    *"SELECT now()"*) echo "2026-10-11 04:00:00+02"; exit 0 ;;
  esac
done
echo "0"
STUB
  chmod +x "$dir/docker"
}

WORK=$(mktemp -d); stub_scoped "$WORK/bin"; fixtures "$WORK" 3 38
expect_refusal "a part-seeded fleet is refused on its own companies, not the database's" "$WORK" "only 1 of this run's own companies are bookable"
rm -rf "$WORK"

# The scoping itself, asserted against the source: the bookable query has to
# restrict by the fixture's phones, and the runner has to report which company
# ids it is about to target. Isolation claimed is worth less than isolation
# recorded in the run's own log.
if echo "$CODE" | grep -q 'c.phone IN ($PHONE_LIST)'; then
  ok "the bookable query is restricted to the fixture's own companies"
else
  bad "the bookable query is restricted to the fixture's own companies"
fi

if echo "$CODE" | grep -q 'this run targets company id'; then
  ok "the run records which company ids it targeted"
else
  bad "the run records which company ids it targeted"
fi

if echo "$CODE" | grep -q 'phone NOT IN ($PHONE_LIST)'; then
  ok "the run records how much of the database it is leaving alone"
else
  bad "the run records how much of the database it is leaving alone"
fi

# RUN_SINCE has to reach the gate, or the winner count is read across every
# earlier run's rides.
if echo "$CODE" | grep -q 'RUN_SINCE="$RUN_SINCE"'; then
  ok "the gate is told the instant this run started"
else
  bad "the gate is told the instant this run started"
fi

if echo "$CODE" | grep -qE 'RUN_SINCE=\$\(q "SELECT now\(\)"\)'; then
  ok "that instant comes from the database's own clock, not the shell's"
else
  bad "that instant comes from the database's own clock, not the shell's"
fi

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

# =====================================================================
# A FAILING RUN MUST NOT REPORT SUCCESS
# =====================================================================
#
# load-run-one.sh exits 3 when k6's own thresholds failed or the stack
# degraded. The runner has to carry that out to its own exit status: a k6
# threshold failure is k6 saying the run did not meet the preconditions its
# scenario declared, and no amount of clean SQL afterwards makes it a pass.
#
# Untested until now. Every stand-in exited 0, so nothing exercised the code
# after the run begins.
WORK=$(mktemp -d); stub "$WORK/bin" 3 30
STATUS=$(run_it_with_failure "$WORK" load-run-one.sh 3)
if [ "$STATUS" = "0" ]; then
  bad "a failing k6 run is reported as success"
  sed 's/^/        /' "$WORK/out"
elif grep -q 'NOT reportable' "$WORK/out"; then
  ok "a failing k6 run makes the runner fail and say so"
else
  bad "a failing k6 run fails the runner but does not say why"
  sed 's/^/        /' "$WORK/out"
fi
rm -rf "$WORK"

# The gate refusing must do the same. Its reasons are the useful output when
# something went wrong, so it is always run -- and always obeyed.
WORK=$(mktemp -d); stub "$WORK/bin" 3 30
STATUS=$(run_it_with_failure "$WORK" load-contention-check.sh 1)
if [ "$STATUS" = "0" ]; then
  bad "a refusing validity gate is reported as success"
  sed 's/^/        /' "$WORK/out"
else
  ok "a refusing validity gate makes the runner fail"
fi
rm -rf "$WORK"

# And the gate is still consulted when the run itself failed, because that is
# when its reasons matter most.
WORK=$(mktemp -d); stub "$WORK/bin" 3 30
run_it_with_failure "$WORK" load-run-one.sh 3 > /dev/null
if grep -q 'CALLED load-contention-check.sh' "$WORK/calls"; then
  ok "the gate is consulted even after the run failed"
else
  bad "the gate is consulted even after the run failed"
fi
rm -rf "$WORK"

# A heartbeat that cannot complete its first pass stops the run before it
# starts: a fleet aged out of search produces a fast, clean, meaningless run.
WORK=$(mktemp -d); stub "$WORK/bin" 3 30
STATUS=$(run_it_with_failure "$WORK" load-heartbeat.sh 1)
if [ "$STATUS" = "0" ]; then
  bad "a failing heartbeat does not stop the run"
  sed 's/^/        /' "$WORK/out"
elif grep -q 'CALLED load-run-one.sh' "$WORK/calls"; then
  bad "a failing heartbeat is refused, but only after starting the run"
else
  ok "a failing heartbeat stops the run before it starts"
fi
rm -rf "$WORK"

# ------------------------------------------------------------------ summary

echo
echo "  $PASSES passed, $FAILURES failed"
[ "$FAILURES" -eq 0 ] || exit 1
