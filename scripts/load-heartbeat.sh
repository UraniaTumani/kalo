#!/usr/bin/env bash
# Keeps the seeded fleet visible to search for the length of a run.
#
#   bash scripts/load-heartbeat.sh &
#
# KALO only offers a driver whose last position is under TWO MINUTES old, which
# is correct: a fix older than that says nothing about where the car is now. The
# window is MAX_LOCATION_AGE in TaxiAvailabilityFinder and is enforced in SQL by
# TaxiSearchRepository, so nothing here can soften it and nothing here should
# try. In production the driver's phone pushes a position every few seconds and
# the question never arises.
#
# A load test has no phones. Without this, every driver ages out two minutes in
# and search starts returning nothing — the run would then be measuring an
# emptying system rather than KALO, and the falling latency would look like good
# news.
#
# WHAT THIS HAS TO BEAT, and why the default interval is not simply "something
# smaller than two minutes": the cycle is one PASS plus one SLEEP, not just the
# sleep. A pass that takes forty seconds on a forty-five second interval is an
# eighty-five second cycle, which still fits. A pass that takes ninety seconds
# does not fit at any interval, and the only honest response is to say so
# loudly rather than to quietly return an emptying fleet.
#
# Pass cost is dominated by logins, because login is deliberately bcrypt-bound.
# The query below is therefore ordered by partner phone so every driver of one
# company arrives together and one token serves all of them. Unordered, the
# token cache missed almost every time and the script performed one bcrypt login
# per DRIVER — at a hundred companies that was the difference between a pass
# that fits in the window and one that does not.
set -u

BASE="${BASE_URL:-http://localhost:8083}"
PG="kalo-load-postgres-1"
DB_USER="${DB_USERNAME:-kalo_load}"
DB_NAME="${DB_NAME:-kalo_load}"
PASSWORD="LoadTest123!"

# Mirrors MAX_LOCATION_AGE in TaxiAvailabilityFinder. Read, never written: this
# script's job is to stay inside the production rule, not to change it.
FRESHNESS_WINDOW_SECONDS=120

# Room for a pass that is slower than expected. 45 + a 30s pass is 75s, well
# inside the window; 45 + a 75s pass is not, and that is what the check below
# is for.
INTERVAL="${HEARTBEAT_INTERVAL:-45}"

# Set by the test harness to run exactly one pass and exit, so the loop can be
# exercised without a timer.
ONCE="${HEARTBEAT_ONCE:-0}"

# Where a breach is recorded for the orchestrator to find. A warning in a log
# nobody greps is the same as no warning at all.
BREACH_FILE="${HEARTBEAT_BREACH_FILE:-}"

ip=200
auth_header() {
  ip=$((ip + 1))
  echo "X-Forwarded-For: 172.30.$(( ip / 250 % 250 )).$(( ip % 250 ))"
}

# Four decimal places from $RANDOM, which is two fewer processes per driver than
# calling awk twice. At two hundred drivers that is four hundred fewer spawns a
# pass. The jitter is cosmetic — a car that moves a little — so the arithmetic
# does not need to be better than this.
jitter() {
  local base_whole="$1" base_frac="$2" span="$3"
  local offset=$(( RANDOM % span ))
  printf "%d.%04d" "$base_whole" $(( base_frac + offset ))
}

pass_count=0

while true; do
  started=$(date +%s)

  # Partner phone and driver id together, so each refresh is sent by the company
  # that owns the driver — the endpoint refuses anything else.
  #
  # ORDER BY is load-bearing, not tidiness: it groups each company's drivers so
  # the token below is reused instead of re-earned.
  ROWS=$(docker exec "$PG" psql -U "$DB_USER" -d "$DB_NAME" -tAc \
    "SELECT u.phone || '|' || d.id
       FROM drivers d
       JOIN taxi_companies c ON c.id = d.company_id
       JOIN users u ON u.id = c.owner_user_id
      WHERE d.availability_status = 'ONLINE'
      ORDER BY u.phone, d.id" 2>/dev/null | tr -d '\r')

  if [ -z "$ROWS" ]; then
    echo "✗ heartbeat: no ONLINE drivers found in $DB_NAME."
    echo "  Either the stack is not up, or seeding did not finish, or every"
    echo "  driver has been taken offline. A run from here would measure an"
    echo "  empty system, so this is an error rather than an idle pass."
    [ -n "$BREACH_FILE" ] && echo "no-online-drivers" >> "$BREACH_FILE"
    [ "$ONCE" = "1" ] && exit 1
    sleep "$INTERVAL"
    continue
  fi

  LAST_PHONE=""
  TOKEN=""
  logins=0
  updates=0
  failures=0

  while IFS='|' read -r PHONE DRIVER_ID; do
    [ -n "$PHONE" ] || continue

    if [ "$PHONE" != "$LAST_PHONE" ]; then
      TOKEN=$(curl -s -X POST "$BASE/api/v1/auth/login" \
        -H 'Content-Type: application/json' -H "$(auth_header)" \
        -d "{\"phone\":\"$PHONE\",\"password\":\"$PASSWORD\"}" \
        | grep -oE '"accessToken":"[^"]+' | cut -d'"' -f4)
      LAST_PHONE="$PHONE"
      logins=$(( logins + 1 ))
    fi

    if [ -z "$TOKEN" ]; then
      failures=$(( failures + 1 ))
      continue
    fi

    LAT=$(jitter 41 3000 600)
    LNG=$(jitter 19 7800 800)

    CODE=$(curl -s -o /dev/null -w '%{http_code}' \
      -X PUT "$BASE/api/v1/partner/drivers/$DRIVER_ID/location" \
      -H 'Content-Type: application/json' \
      -H "Authorization: Bearer $TOKEN" \
      -H "$(auth_header)" \
      -d "{\"latitude\":$LAT,\"longitude\":$LNG}")

    if [ "$CODE" = "200" ] || [ "$CODE" = "204" ]; then
      updates=$(( updates + 1 ))
    else
      failures=$(( failures + 1 ))
    fi
  done <<< "$ROWS"

  elapsed=$(( $(date +%s) - started ))
  cycle=$(( elapsed + INTERVAL ))
  pass_count=$(( pass_count + 1 ))

  echo "heartbeat pass $pass_count: ${updates} updated, ${logins} logins, ${failures} failed, ${elapsed}s (cycle ${cycle}s / window ${FRESHNESS_WINDOW_SECONDS}s)"

  # The check that turns a silent degradation into a visible failure.
  #
  # If one pass plus one sleep exceeds the freshness window then some driver is
  # certainly stale by the time their turn comes round again, search will stop
  # offering them, and every latency number after that point describes an
  # emptying system. Said loudly, and recorded where the orchestration can see
  # it, because the symptom on its own looks like good news.
  if [ "$cycle" -ge "$FRESHNESS_WINDOW_SECONDS" ]; then
    echo "✗ heartbeat cycle ${cycle}s has reached the ${FRESHNESS_WINDOW_SECONDS}s GPS freshness window."
    echo "  Drivers will age out of search and the run will measure an emptying"
    echo "  fleet. Lower HEARTBEAT_INTERVAL, reduce the fleet, or give the"
    echo "  machine more room — do not reinterpret the results."
    [ -n "$BREACH_FILE" ] && echo "cycle=${cycle}s window=${FRESHNESS_WINDOW_SECONDS}s pass=${elapsed}s" >> "$BREACH_FILE"
  elif [ $(( cycle * 100 / FRESHNESS_WINDOW_SECONDS )) -ge 75 ]; then
    echo "! heartbeat cycle ${cycle}s is within 25% of the ${FRESHNESS_WINDOW_SECONDS}s window — little margin left."
  fi

  if [ "$failures" -gt 0 ]; then
    echo "! heartbeat: ${failures} update(s) or login(s) failed this pass."
  fi

  [ "$ONCE" = "1" ] && break

  sleep "$INTERVAL"
done
