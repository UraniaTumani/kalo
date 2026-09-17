#!/usr/bin/env bash
# Keeps the seeded fleet visible to search for the length of a run.
#
#   bash scripts/load-heartbeat.sh &
#
# KALO only offers a driver whose last position is under five minutes old, which
# is correct: a fix older than that says nothing about where the car is now. In
# production the driver's phone pushes a position every few seconds and the
# question never arises.
#
# A load test has no phones. Without this, every driver ages out five minutes in
# and search starts returning nothing — the run would then be measuring an
# emptying system rather than KALO, and the falling latency would look like good
# news. This does what the phones would: one refresh per driver, every ninety
# seconds.
set -u

BASE="${BASE_URL:-http://localhost:8083}"
INTERVAL="${HEARTBEAT_INTERVAL:-90}"
PG="kalo-load-postgres-1"
DB_USER="${DB_USERNAME:-kalo_load}"
DB_NAME="${DB_NAME:-kalo_load}"
PASSWORD="LoadTest123!"

ip=200
auth_header() {
  ip=$((ip + 1))
  echo "X-Forwarded-For: 172.30.$(( ip / 250 % 250 )).$(( ip % 250 ))"
}

while true; do
  # Partner phone and driver id together, so each refresh is sent by the company
  # that owns the driver — the endpoint refuses anything else.
  ROWS=$(docker exec "$PG" psql -U "$DB_USER" -d "$DB_NAME" -tAc \
    "SELECT u.phone || '|' || d.id
       FROM drivers d
       JOIN taxi_companies c ON c.id = d.company_id
       JOIN users u ON u.id = c.owner_user_id
      WHERE d.availability_status = 'ONLINE'" 2>/dev/null | tr -d '\r')

  LAST_PHONE=""
  TOKEN=""

  while IFS='|' read -r PHONE DRIVER_ID; do
    [ -n "$PHONE" ] || continue

    if [ "$PHONE" != "$LAST_PHONE" ]; then
      TOKEN=$(curl -s -X POST "$BASE/api/v1/auth/login" \
        -H 'Content-Type: application/json' -H "$(auth_header)" \
        -d "{\"phone\":\"$PHONE\",\"password\":\"$PASSWORD\"}" \
        | grep -oE '"accessToken":"[^"]+' | cut -d'"' -f4)
      LAST_PHONE="$PHONE"
    fi

    [ -n "$TOKEN" ] || continue

    # Jitter the position slightly, as a moving car would.
    LAT=$(awk -v s="$DRIVER_ID$RANDOM" 'BEGIN{srand(s); printf "%.4f", 41.30 + rand()*0.06}')
    LNG=$(awk -v s="$DRIVER_ID$RANDOM" 'BEGIN{srand(s+3); printf "%.4f", 19.78 + rand()*0.08}')

    curl -s -o /dev/null -X PUT "$BASE/api/v1/partner/drivers/$DRIVER_ID/location" \
      -H 'Content-Type: application/json' \
      -H "Authorization: Bearer $TOKEN" \
      -H "$(auth_header)" \
      -d "{\"latitude\":$LAT,\"longitude\":$LNG}"
  done <<< "$ROWS"

  sleep "$INTERVAL"
done
