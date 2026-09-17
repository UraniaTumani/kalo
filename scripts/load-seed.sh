#!/usr/bin/env bash
# Builds the world the load tests run against.
#
# Everything goes through the real API at the real rules — companies are
# registered, submit documents, and are approved by an admin; drivers are
# created, paired with vehicles, put online and given a position. Nothing is
# written straight to the database except the one thing that has no endpoint:
# promoting the first user to ADMIN.
#
# Writes /data/users.json and /data/partners.json for k6 to read.
set -u

BASE="${BASE_URL:-http://localhost:8083}"
OUT="${OUT_DIR:-./load/.data}"
COMPANIES="${COMPANIES:-5}"
DRIVERS_PER_COMPANY="${DRIVERS_PER_COMPANY:-4}"
CUSTOMERS="${CUSTOMERS:-60}"
PG="kalo-load-postgres-1"
DB_USER="${DB_USERNAME:-kalo_load}"
DB_NAME="${DB_NAME:-kalo_load}"
PASSWORD="LoadTest123!"

mkdir -p "$OUT"

q() { docker exec "$PG" psql -U "$DB_USER" -d "$DB_NAME" -tAc "$1" 2>/dev/null | tr -d '\r'; }
jget() { echo "$1" | grep -oE "\"$2\":[^,}]*" | head -1 | cut -d: -f2- | tr -d '"'; }

# A distinct forwarded address per call, so seeding does not exhaust one
# client's rate-limit bucket and stall halfway through.
ip=0
post() {
  ip=$((ip + 1))
  curl -s -X POST "$BASE$1" \
    -H 'Content-Type: application/json' \
    -H "X-Forwarded-For: 172.$(( (ip / 250) % 250 )).$(( ip % 250 )).9" \
    ${2:+-H "Authorization: Bearer $2"} \
    ${3:+-d "$3"}
}
put() {
  ip=$((ip + 1))
  curl -s -X PUT "$BASE$1" \
    -H 'Content-Type: application/json' \
    -H "X-Forwarded-For: 172.$(( (ip / 250) % 250 )).$(( ip % 250 )).9" \
    -H "Authorization: Bearer $2" \
    ${3:+-d "$3"}
}
patch_() {
  ip=$((ip + 1))
  curl -s -X PATCH "$BASE$1" \
    -H 'Content-Type: application/json' \
    -H "X-Forwarded-For: 172.$(( (ip / 250) % 250 )).$(( ip % 250 )).9" \
    -H "Authorization: Bearer $2" \
    ${3:+-d "$3"}
}
login_token() {
  jget "$(post /api/v1/auth/login '' "{\"phone\":\"$1\",\"password\":\"$PASSWORD\"}")" accessToken
}

R=$(date +%s | tail -c 5)

echo "▸ admin"
ADMIN_PHONE="+3559000${R}"
post /api/v1/auth/register/customer '' \
  "{\"firstName\":\"Load\",\"lastName\":\"Admin\",\"phone\":\"$ADMIN_PHONE\",\"password\":\"$PASSWORD\"}" > /dev/null
q "UPDATE users SET role='ADMIN' WHERE phone='$ADMIN_PHONE'" > /dev/null
ADMIN=$(login_token "$ADMIN_PHONE")
[ -n "$ADMIN" ] || { echo "could not sign in as admin"; exit 1; }

echo "▸ $COMPANIES companies, each with $DRIVERS_PER_COMPANY drivers"
echo "[" > "$OUT/partners.json"
FIRST=1

for c in $(seq 1 "$COMPANIES"); do
  P_PHONE="+3559100${R}$(printf '%02d' "$c")"

  post /api/v1/auth/register/partner '' \
    "{\"firstName\":\"Owner\",\"lastName\":\"$c\",\"phone\":\"$P_PHONE\",\"email\":\"p$c-$R@load.test\",\"password\":\"$PASSWORD\",\"legalName\":\"Load Taxi $c-$R SHPK\",\"displayName\":\"Load Taxi $c-$R\",\"nipt\":\"L$R$c\",\"address\":\"Rruga e Durresit, Tirane\"}" > /dev/null

  PT=$(login_token "$P_PHONE")
  [ -n "$PT" ] || { echo "  company $c: could not sign in"; continue; }

  put /api/v1/partner/me "$PT" \
    "{\"legalName\":\"Load Taxi $c-$R SHPK\",\"displayName\":\"Load Taxi $c-$R\",\"phone\":\"$P_PHONE\",\"address\":\"Rruga e Durresit, Tirane\",\"licenseNumber\":\"TAXI-$R-$c\",\"licenseExpiryDate\":\"2030-01-01\"}" > /dev/null

  for T in BUSINESS_REGISTRATION TAXI_LICENSE; do
    post /api/v1/partner/documents "$PT" \
      "{\"documentType\":\"$T\",\"fileUrl\":\"https://files.load.test/$T-$R-$c.pdf\"}" > /dev/null
  done

  post /api/v1/partner/submit-verification "$PT" '' > /dev/null

  CID=$(q "SELECT id FROM taxi_companies WHERE nipt='L$R$c'")
  post "/api/v1/admin/partners/$CID/approve" "$ADMIN" '' > /dev/null

  put /api/v1/partner/operational-settings "$PT" \
    '{"bookingEnabled":true,"paymentMethods":["CASH","CARD_IN_CAR"]}' > /dev/null
  put /api/v1/partner/availability-settings/service-area "$PT" \
    '{"latitude":41.3275,"longitude":19.8187,"radiusKm":30,"timezone":"Europe/Tirane"}' > /dev/null

  HOURS='{"hours":['
  for D in MONDAY TUESDAY WEDNESDAY THURSDAY FRIDAY SATURDAY SUNDAY; do
    HOURS="$HOURS{\"dayOfWeek\":\"$D\",\"closed\":false,\"openTime\":\"00:00:00\",\"closeTime\":\"00:00:00\"},"
  done
  put /api/v1/partner/availability-settings/operating-hours "$PT" "${HOURS%,}]}" > /dev/null

  for d in $(seq 1 "$DRIVERS_PER_COMPANY"); do
    DRV=$(post /api/v1/partner/drivers "$PT" \
      "{\"firstName\":\"Driver\",\"lastName\":\"$c$d\",\"phone\":\"+3556$R$c$d\",\"licenseNumber\":\"DL-$R-$c-$d\",\"licenseExpiryDate\":\"2030-01-01\"}")
    DID=$(jget "$DRV" id)

    VEH=$(post /api/v1/partner/vehicles "$PT" \
      "{\"plateNumber\":\"L$R$c$d\",\"brand\":\"Skoda\",\"model\":\"Octavia\",\"manufactureYear\":2021,\"seats\":4,\"vehicleType\":\"STANDARD\"}")
    VID=$(jget "$VEH" id)

    [ -n "$DID" ] && [ -n "$VID" ] || continue

    post /api/v1/partner/driver-vehicle-assignments "$PT" \
      "{\"driverId\":$DID,\"vehicleId\":$VID}" > /dev/null

    # Online first, then the position: the order the application accepts.
    patch_ "/api/v1/partner/drivers/$DID/availability" "$PT" \
      '{"availabilityStatus":"ONLINE"}' > /dev/null

    # Spread the fleet around Tirana so search has real distances to rank.
    LAT=$(awk -v s="$c$d" 'BEGIN{srand(s); printf "%.4f", 41.30 + rand()*0.06}')
    LNG=$(awk -v s="$c$d" 'BEGIN{srand(s+7); printf "%.4f", 19.78 + rand()*0.08}')
    put "/api/v1/partner/drivers/$DID/location" "$PT" \
      "{\"latitude\":$LAT,\"longitude\":$LNG}" > /dev/null
  done

  [ $FIRST -eq 1 ] && FIRST=0 || echo "," >> "$OUT/partners.json"
  printf '  {"phone":"%s"}' "$P_PHONE" >> "$OUT/partners.json"
  echo "  company $c ready"
done

echo "]" >> "$OUT/partners.json"

echo "▸ $CUSTOMERS customers"
echo "[" > "$OUT/users.json"
FIRST=1
for u in $(seq 1 "$CUSTOMERS"); do
  C_PHONE="+3556900${R}$(printf '%03d' "$u")"
  CODE=$(curl -s -o /dev/null -w '%{http_code}' -X POST "$BASE/api/v1/auth/register/customer" \
    -H 'Content-Type: application/json' \
    -H "X-Forwarded-For: 10.9.$(( u / 250 )).$(( u % 250 ))" \
    -d "{\"firstName\":\"Cust\",\"lastName\":\"$u\",\"phone\":\"$C_PHONE\",\"password\":\"$PASSWORD\"}")
  if [ "$CODE" = "201" ]; then
    [ $FIRST -eq 1 ] && FIRST=0 || echo "," >> "$OUT/users.json"
    printf '  {"phone":"%s"}' "$C_PHONE" >> "$OUT/users.json"
  fi
done
echo "]" >> "$OUT/users.json"

echo
echo "seeded:"
echo "  companies       $(q "SELECT count(*) FROM taxi_companies WHERE verification_status='APPROVED'")"
echo "  online drivers  $(q "SELECT count(*) FROM drivers WHERE availability_status='ONLINE'")"
echo "  with positions  $(q "SELECT count(*) FROM driver_locations")"
echo "  customers       $(grep -c phone "$OUT/users.json")"
echo "  partners        $(grep -c phone "$OUT/partners.json")"
