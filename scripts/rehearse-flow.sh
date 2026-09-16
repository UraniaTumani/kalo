#!/usr/bin/env bash
# The business flow, checks 6-11, driven entirely through nginx against the
# prod-profile stack with an empty database and no seed data.
#
# Everything a real first day would involve: a company registers, uploads its
# papers, an admin approves it, it builds a fleet, a passenger books, the ride
# runs to completion, the passenger rates it, and the company is told.
BASE=http://localhost:8082
pass=0; fail=0
ok() { printf '  PASS  %s\n' "$1"; pass=$((pass+1)); }
no() { printf '  FAIL  %s -- %s\n' "$1" "$2"; fail=$((fail+1)); }
jget() { echo "$1" | grep -oE "\"$2\":[^,}]*" | head -1 | cut -d: -f2- | tr -d '"'; }
psql_q() { docker exec kalo-prod-postgres-1 psql -U kalo_rehearsal -d kalo_prod_rehearsal -tAc "$1" 2>/dev/null | tr -d '\r'; }
auth() { echo "Authorization: Bearer $1"; }

R=$RANDOM

echo "== bootstrap: the first admin =="
# There is no API that creates an administrator. In production the first one
# must be made by hand: register through the public endpoint so the password is
# hashed by the application, then promote the row.
ADMIN_PHONE="+35569${R}0001"
curl -s -o /dev/null -X POST $BASE/api/v1/auth/register/customer -H 'Content-Type: application/json' \
  -d "{\"firstName\":\"Ops\",\"lastName\":\"Admin\",\"phone\":\"$ADMIN_PHONE\",\"password\":\"Rehearsal123!\"}"
psql_q "UPDATE users SET role='ADMIN' WHERE phone='$ADMIN_PHONE'" > /dev/null
ADMIN_TOKEN=$(jget "$(curl -s -X POST $BASE/api/v1/auth/login -H 'Content-Type: application/json' \
  -d "{\"phone\":\"$ADMIN_PHONE\",\"password\":\"Rehearsal123!\"}")" accessToken)
[ -n "$ADMIN_TOKEN" ] && ok "admin promoted by SQL and signed in" || { no "admin" "no token"; exit 1; }

echo "== partner onboarding =="
P_PHONE="+35569${R}0002"
PREG=$(curl -s -X POST $BASE/api/v1/auth/register/partner -H 'Content-Type: application/json' \
  -d "{\"firstName\":\"Arben\",\"lastName\":\"Krasniqi\",\"phone\":\"$P_PHONE\",\"email\":\"p$R@kalo.test\",\"password\":\"Rehearsal123!\",\"legalName\":\"Rehearsal Taxi $R SHPK\",\"displayName\":\"Rehearsal Taxi $R\",\"nipt\":\"K${R}A\",\"address\":\"Rruga e Durresit, Tirane\"}")
P_TOKEN=$(jget "$(curl -s -X POST $BASE/api/v1/auth/login -H 'Content-Type: application/json' \
  -d "{\"phone\":\"$P_PHONE\",\"password\":\"Rehearsal123!\"}")" accessToken)
[ -n "$P_TOKEN" ] && ok "partner registered and signed in" || { no "partner" "no token"; exit 1; }

curl -s -o /dev/null -X PUT $BASE/api/v1/partner/me -H "$(auth $P_TOKEN)" -H 'Content-Type: application/json' \
  -d "{\"legalName\":\"Rehearsal Taxi $R SHPK\",\"displayName\":\"Rehearsal Taxi $R\",\"phone\":\"$P_PHONE\",\"address\":\"Rruga e Durresit, Tirane\",\"licenseNumber\":\"TAXI-$R\",\"licenseExpiryDate\":\"2030-01-01\"}"
for T in BUSINESS_REGISTRATION TAXI_LICENSE; do
  curl -s -o /dev/null -X POST $BASE/api/v1/partner/documents -H "$(auth $P_TOKEN)" -H 'Content-Type: application/json' \
    -d "{\"documentType\":\"$T\",\"fileUrl\":\"https://files.kalo.test/$T-$R.pdf\"}"
done
SUB=$(curl -s -o /dev/null -w '%{http_code}' -X POST $BASE/api/v1/partner/submit-verification -H "$(auth $P_TOKEN)")
[ "$SUB" = "200" ] && ok "company submitted for verification ($SUB)" || no "submit" "got $SUB"

CID=$(psql_q "SELECT id FROM taxi_companies WHERE nipt='K${R}A'")
APPR=$(curl -s -o /dev/null -w '%{http_code}' -X POST $BASE/api/v1/admin/partners/$CID/approve -H "$(auth $ADMIN_TOKEN)")
[ "$APPR" = "200" ] && ok "admin approved the company ($APPR)" || no "approve" "got $APPR"

echo "== fleet and availability =="
curl -s -o /dev/null -X PUT $BASE/api/v1/partner/operational-settings -H "$(auth $P_TOKEN)" -H 'Content-Type: application/json' \
  -d '{"bookingEnabled":true,"paymentMethods":["CASH"]}'
curl -s -o /dev/null -X PUT $BASE/api/v1/partner/availability-settings/service-area -H "$(auth $P_TOKEN)" -H 'Content-Type: application/json' \
  -d '{"latitude":41.3275,"longitude":19.8187,"radiusKm":25,"timezone":"Europe/Tirane"}'
HOURS='{"hours":['
for D in MONDAY TUESDAY WEDNESDAY THURSDAY FRIDAY SATURDAY SUNDAY; do
  HOURS="$HOURS{\"dayOfWeek\":\"$D\",\"closed\":false,\"openTime\":\"00:00:00\",\"closeTime\":\"00:00:00\"},"
done
HOURS="${HOURS%,}]}"
HC=$(curl -s -o /dev/null -w '%{http_code}' -X PUT $BASE/api/v1/partner/availability-settings/operating-hours \
  -H "$(auth $P_TOKEN)" -H 'Content-Type: application/json' -d "$HOURS")
[ "$HC" = "200" ] && ok "operating hours saved, open around the clock ($HC)" || no "hours" "got $HC"

DRV=$(curl -s -X POST $BASE/api/v1/partner/drivers -H "$(auth $P_TOKEN)" -H 'Content-Type: application/json' \
  -d "{\"firstName\":\"Ilir\",\"lastName\":\"Hoxha\",\"phone\":\"+35569${R}0003\",\"licenseNumber\":\"DL-$R\",\"licenseExpiryDate\":\"2030-01-01\"}")
DID=$(jget "$DRV" id)
VEH=$(curl -s -X POST $BASE/api/v1/partner/vehicles -H "$(auth $P_TOKEN)" -H 'Content-Type: application/json' \
  -d "{\"plateNumber\":\"AA${R}BB\",\"brand\":\"Skoda\",\"model\":\"Octavia\",\"manufactureYear\":2021,\"seats\":4,\"vehicleType\":\"STANDARD\"}")
VID=$(jget "$VEH" id)
ASG=$(curl -s -o /dev/null -w '%{http_code}' -X POST $BASE/api/v1/partner/driver-vehicle-assignments \
  -H "$(auth $P_TOKEN)" -H 'Content-Type: application/json' -d "{\"driverId\":$DID,\"vehicleId\":$VID}")
[ "$ASG" = "201" ] && ok "driver $DID paired with vehicle $VID ($ASG)" || no "assignment" "got $ASG"

# ONLINE first, then the position.
#
# The other order is refused with "Offline driver cannot update location" —
# which is exactly what the partner UI does, and why a driver can never be put
# on the road from it. Reversed here so the rest of the rehearsal can proceed;
# the bug itself is reported separately.
ONL=$(curl -s -o /dev/null -w '%{http_code}' -X PATCH $BASE/api/v1/partner/drivers/$DID/availability \
  -H "$(auth $P_TOKEN)" -H 'Content-Type: application/json' -d '{"availabilityStatus":"ONLINE"}')
[ "$ONL" = "200" ] && ok "driver set ONLINE ($ONL)" || no "online" "got $ONL"
LOC=$(curl -s -o /dev/null -w '%{http_code}' -X PUT $BASE/api/v1/partner/drivers/$DID/location \
  -H "$(auth $P_TOKEN)" -H 'Content-Type: application/json' -d '{"latitude":41.3275,"longitude":19.8187}')
[ "$LOC" = "200" ] && ok "driver position recorded ($LOC)" || no "driver location" "got $LOC"
LOCROWS=$(psql_q "SELECT count(*) FROM driver_locations WHERE driver_id=$DID")
[ "${LOCROWS:-0}" = "1" ] && ok "position persisted in driver_locations" || no "location row" "$LOCROWS"

echo "== 6. customer search =="
C_PHONE="+35569${R}0004"
curl -s -o /dev/null -X POST $BASE/api/v1/auth/register/customer -H 'Content-Type: application/json' \
  -d "{\"firstName\":\"Elira\",\"lastName\":\"Meta\",\"phone\":\"$C_PHONE\",\"password\":\"Rehearsal123!\"}"
C_TOKEN=$(jget "$(curl -s -X POST $BASE/api/v1/auth/login -H 'Content-Type: application/json' \
  -d "{\"phone\":\"$C_PHONE\",\"password\":\"Rehearsal123!\"}")" accessToken)
SEARCH=$(curl -s -X POST $BASE/api/v1/rides/search -H "$(auth $C_TOKEN)" -H 'Content-Type: application/json' \
  -d '{"pickupLatitude":41.3275,"pickupLongitude":19.8187,"destinationLatitude":41.32,"destinationLongitude":19.83}')
RRID=$(jget "$SEARCH" rideRequestId)
OFFER=$(echo "$SEARCH" | grep -oE '"offerId":[0-9]+' | head -1 | cut -d: -f2)
[ -n "$OFFER" ] && ok "search returned an offer from the approved company" || { no "search" "no offers: $(echo $SEARCH | head -c 120)"; }

echo "== 7. partner ride flow =="
SEL=$(curl -s -X POST $BASE/api/v1/rides/requests/$RRID/select -H "$(auth $C_TOKEN)" -H 'Content-Type: application/json' \
  -d "{\"offerId\":$OFFER}")
RIDE=$(jget "$SEL" rideId)
[ -n "$RIDE" ] && ok "customer selected the company, ride $RIDE created" || no "select" "no rideId"
ACC=$(curl -s -o /dev/null -w '%{http_code}' -X POST $BASE/api/v1/partner/rides/$RIDE/accept \
  -H "$(auth $P_TOKEN)" -H 'Content-Type: application/json' -d "{\"driverId\":$DID}")
[ "$ACC" = "200" ] && ok "partner accepted and assigned a driver ($ACC)" || no "accept" "got $ACC"
STEPS_OK=yes
for S in driver-arriving driver-arrived start; do
  C=$(curl -s -o /dev/null -w '%{http_code}' -X POST $BASE/api/v1/partner/rides/$RIDE/$S -H "$(auth $P_TOKEN)")
  [ "$C" = "200" ] || STEPS_OK="$S=$C"
done
[ "$STEPS_OK" = "yes" ] && ok "ride moved through arriving, arrived and start" || no "transitions" "$STEPS_OK"
BUSY=$(psql_q "SELECT availability_status FROM drivers WHERE id=$DID")
[ "$BUSY" = "BUSY" ] && ok "driver is BUSY during the ride" || no "driver state" "got $BUSY"
CMP=$(curl -s -o /dev/null -w '%{http_code}' -X POST $BASE/api/v1/partner/rides/$RIDE/complete \
  -H "$(auth $P_TOKEN)" -H 'Content-Type: application/json' -d '{"finalAmount":1250.00}')
[ "$CMP" = "200" ] && ok "ride completed with a taximeter amount ($CMP)" || no "complete" "got $CMP"
FREE=$(psql_q "SELECT availability_status FROM drivers WHERE id=$DID")
[ "$FREE" = "ONLINE" ] && ok "driver released back to ONLINE" || no "driver release" "got $FREE"

echo "== 8. rating =="
RATE=$(curl -s -o /dev/null -w '%{http_code}' -X POST $BASE/api/v1/rides/$RIDE/rating -H "$(auth $C_TOKEN)" \
  -H 'Content-Type: application/json' -d '{"driverRating":5,"companyRating":4,"comment":"Rehearsal"}')
[ "$RATE" = "201" ] && ok "completed ride rated ($RATE)" || no "rating" "got $RATE"
DUP=$(curl -s -o /dev/null -w '%{http_code}' -X POST $BASE/api/v1/rides/$RIDE/rating -H "$(auth $C_TOKEN)" \
  -H 'Content-Type: application/json' -d '{"driverRating":1,"companyRating":1}')
[ "$DUP" = "409" ] && ok "second rating refused ($DUP)" || no "duplicate rating" "got $DUP"
HIST=$(curl -s "$BASE/api/v1/rides/history" -H "$(auth $C_TOKEN)")
echo "$HIST" | grep -q '"rated":true' && ok "history reports the ride as rated" || no "history" "not marked rated"

echo "== 9. company notification =="
NOTIF=$(curl -s "$BASE/api/v1/partner/notifications" -H "$(auth $P_TOKEN)")
echo "$NOTIF" | grep -q '"type":"RIDE_RATED"' && ok "company received a RIDE_RATED notification" || no "notification" "none"
NROWS=$(psql_q "SELECT count(*) FROM company_notifications WHERE company_id=$CID AND type='RIDE_RATED'")
[ "${NROWS:-0}" -ge 1 ] && ok "notification persisted in PostgreSQL ($NROWS row)" || no "notification row" "$NROWS"

echo "== 10. support requests =="
SUP=$(curl -s -o /dev/null -w '%{http_code}' -X POST $BASE/api/v1/support/requests -H "$(auth $C_TOKEN)" \
  -H 'Content-Type: application/json' -d '{"category":"RIDE_ISSUE","subject":"Rehearsal ticket","message":"Checking the support flow."}')
[ "$SUP" = "201" ] && ok "customer filed a support request ($SUP)" || no "support" "got $SUP"
MINE=$(curl -s "$BASE/api/v1/support/requests" -H "$(auth $C_TOKEN)")
echo "$MINE" | grep -q "Rehearsal ticket" && ok "customer sees their own request" || no "support read" "missing"
OTHER=$(curl -s "$BASE/api/v1/support/requests" -H "$(auth $P_TOKEN)")
echo "$OTHER" | grep -q "Rehearsal ticket" && no "support isolation" "partner saw another user's ticket" \
  || ok "partner does not see the customer's request"
ASUP=$(curl -s "$BASE/api/v1/admin/support/requests" -H "$(auth $ADMIN_TOKEN)")
echo "$ASUP" | grep -q "Rehearsal ticket" && ok "admin sees every support request" || no "admin support" "missing"

echo "== 11. admin pages =="
for EP in "users" "partners" "rides" "support/requests"; do
  C=$(curl -s -o /dev/null -w '%{http_code}' "$BASE/api/v1/admin/$EP" -H "$(auth $ADMIN_TOKEN)")
  [ "$C" = "200" ] && ok "admin /$EP reachable ($C)" || no "admin /$EP" "got $C"
done
CUSTC=$(curl -s -o /dev/null -w '%{http_code}' "$BASE/api/v1/admin/users" -H "$(auth $C_TOKEN)")
[ "$CUSTC" = "403" ] && ok "a customer is refused the admin API ($CUSTC)" || no "admin guard" "got $CUSTC"

echo
echo "flow checks passed: $pass    failed: $fail"
[ "$fail" -eq 0 ] || exit 1
