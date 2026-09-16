#!/usr/bin/env bash
# Production rehearsal checks. Everything goes through nginx on :8082, the way a
# browser would — nothing talks to the backend container directly except where a
# check is explicitly about the backend's own posture.
#
# Status codes alone are not enough here: nginx falls unknown paths back to
# index.html with 200, so "200" can mean "the SPA answered" rather than "the
# endpoint exists". These checks look at content type and body.
BASE=http://localhost:8082
pass=0; fail=0
ok() { printf '  PASS  %s\n' "$1"; pass=$((pass+1)); }
no() { printf '  FAIL  %s -- %s\n' "$1" "$2"; fail=$((fail+1)); }

code() { curl -s -o /dev/null -w '%{http_code}' "$@"; }
ctype() { curl -s -o /dev/null -w '%{content_type}' "$@"; }
body() { curl -s "$@"; }
psql_q() {
  docker exec kalo-prod-postgres-1 psql -U kalo_rehearsal -d kalo_prod_rehearsal -tAc "$1" 2>/dev/null | tr -d '\r'
}

echo "== 1. Liquibase migrations from an empty database =="
APPLIED=$(psql_q "SELECT count(*) FROM databasechangelog")
TABLES=$(psql_q "SELECT count(*) FROM pg_tables WHERE schemaname='public'")
if [ "${APPLIED:-0}" -ge 22 ]; then ok "$APPLIED changesets applied, $TABLES tables"
else no "migrations" "only ${APPLIED:-0} changesets"; fi

echo "== 2. backend is up, and health really comes from it =="
HCT=$(ctype $BASE/actuator/health)
HBODY=$(body $BASE/actuator/health)
if echo "$HCT" | grep -qi json && echo "$HBODY" | grep -q '"status":"UP"'; then
  ok "health is backend JSON through nginx (UP)"
else
  no "health" "content-type=$HCT body=$(echo "$HBODY" | head -c 40)"
fi

echo "== 3. frontend loads =="
HTML=$(body $BASE/)
echo "$HTML" | grep -qi 'id="root"' && ok "index.html served with app root" || no "frontend" "no app root"
echo "$HTML" | grep -qE 'assets/index-[^"]+\.js' && ok "hashed asset bundle referenced" || no "assets" "none"

echo "== 4. register and login =="
PHONE="+35569$(shuf -i 1000000-9999999 -n 1)"
REG=$(code -X POST $BASE/api/v1/auth/register/customer -H 'Content-Type: application/json' \
  -d "{\"firstName\":\"Re\",\"lastName\":\"Hearsal\",\"phone\":\"$PHONE\",\"password\":\"Rehearsal123!\"}")
[ "$REG" = "201" ] && ok "customer registered ($REG)" || no "register" "got $REG"
LOGIN=$(body -X POST $BASE/api/v1/auth/login -H 'Content-Type: application/json' \
  -d "{\"phone\":\"$PHONE\",\"password\":\"Rehearsal123!\"}")
ACCESS=$(echo "$LOGIN" | grep -oE '"accessToken":"[^"]+' | cut -d'"' -f4)
REFRESH=$(echo "$LOGIN" | grep -oE '"refreshToken":"[^"]+' | cut -d'"' -f4)
[ -n "$ACCESS" ] && ok "login returned an access token" || no "login" "no token"

echo "== 5. refresh tokens =="
NEW=$(body -X POST $BASE/api/v1/auth/refresh -H 'Content-Type: application/json' \
  -d "{\"refreshToken\":\"$REFRESH\"}")
NEWACCESS=$(echo "$NEW" | grep -oE '"accessToken":"[^"]+' | cut -d'"' -f4)
NEWREFRESH=$(echo "$NEW" | grep -oE '"refreshToken":"[^"]+' | cut -d'"' -f4)
[ -n "$NEWACCESS" ] && ok "refresh issued a new access token" || no "refresh" "no token"
[ "$NEWREFRESH" != "$REFRESH" ] && ok "refresh token rotated" || no "rotation" "unchanged"
REUSE=$(code -X POST $BASE/api/v1/auth/refresh -H 'Content-Type: application/json' \
  -d "{\"refreshToken\":\"$REFRESH\"}")
[ "$REUSE" = "401" ] && ok "spent refresh token refused ($REUSE)" || no "reuse" "got $REUSE"

echo "== 12. Swagger is unavailable =="
for p in /v3/api-docs /swagger-ui.html /swagger-ui/index.html; do
  CT=$(ctype $BASE$p)
  if echo "$CT" | grep -qi json; then no "$p" "served JSON, springdoc is reachable"
  else ok "$p not reachable from outside (served $CT)"; fi
done
# And prove it is off in the application, not merely unrouted by nginx.
SW=$(docker exec kalo-prod-backend-1 wget -qO- -S http://127.0.0.1:8080/v3/api-docs 2>&1 | grep -oE 'HTTP/1.1 [0-9]+' | head -1 | grep -oE '[0-9]+$')
[ "$SW" = "404" ] && ok "backend itself does not serve /v3/api-docs ($SW)" || no "swagger on backend" "got $SW"

echo "== 13. sensitive actuator endpoints =="
for p in /actuator/env /actuator/heapdump /actuator/loggers /actuator/threaddump /actuator/beans; do
  CT=$(ctype $BASE$p)
  if echo "$CT" | grep -qiE 'json|octet-stream'; then no "$p" "reachable from outside ($CT)"
  else ok "$p not routed from outside ($CT)"; fi
done
for p in /actuator/env /actuator/heapdump /actuator/loggers; do
  C=$(docker exec kalo-prod-backend-1 wget -qO- -S http://127.0.0.1:8080$p 2>&1 | grep -oE 'HTTP/1.1 [0-9]+' | head -1 | grep -oE '[0-9]+$')
  case "$C" in 401|403|404) ok "backend does not serve $p ($C)";; *) no "$p on backend" "got $C";; esac
done
echo "$HBODY" | grep -q '"components"' && no "health detail" "anonymous caller sees components" \
  || ok "health shows no detail to an anonymous caller"
PROMC=$(docker exec kalo-prod-backend-1 wget -qO- -S http://127.0.0.1:8080/actuator/prometheus 2>&1 | grep -oE 'HTTP/1.1 [0-9]+' | head -1 | grep -oE '[0-9]+$')
[ "$PROMC" = "401" ] || [ "$PROMC" = "403" ] && ok "prometheus refused anonymously on the backend ($PROMC)" \
  || no "prometheus" "got $PROMC"
PROMCT=$(ctype $BASE/actuator/prometheus)
echo "$PROMCT" | grep -qiE 'text/plain|openmetrics' && no "prometheus" "routed from outside" \
  || ok "prometheus not routed from outside ($PROMCT)"

echo "== 14. no demo or dev users exist =="
USERS=$(psql_q "SELECT count(*) FROM users WHERE phone LIKE '+35569000%'")
[ "${USERS:-1}" = "0" ] && ok "no seeded demo accounts" || no "seed data" "$USERS demo users"
COMPANIES=$(psql_q "SELECT count(*) FROM taxi_companies")
[ "${COMPANIES:-1}" = "0" ] && ok "no seeded companies" || no "seed data" "$COMPANIES companies"
DRIVERS=$(psql_q "SELECT count(*) FROM drivers")
[ "${DRIVERS:-1}" = "0" ] && ok "no seeded drivers" || no "seed data" "$DRIVERS drivers"

echo "== 15. nginx /api routing =="
[ "$(code $BASE/api/v1/rides/current)" = "401" ] && ok "/api proxied to the backend (401 from the API)" \
  || no "/api routing" "got $(code $BASE/api/v1/rides/current)"
CT=$(curl -s -o /dev/null -w '%{content_type}' -X POST $BASE/api/v1/auth/login \
  -H 'Content-Type: application/json' -d '{}')
echo "$CT" | grep -qi json && ok "API responses are JSON, not the SPA fallback" || no "/api" "ct=$CT"
[ "$(code $BASE/definitely-not-a-route)" = "200" ] && ok "unknown app paths fall back to index.html" \
  || no "SPA fallback" "got $(code $BASE/definitely-not-a-route)"

echo "== rate limiting is on =="
LIMITED=no
for i in $(seq 1 25); do
  C=$(code -X POST $BASE/api/v1/auth/login -H 'Content-Type: application/json' \
    -d '{"phone":"+355690000000","password":"wrong"}')
  if [ "$C" = "429" ]; then LIMITED=yes; break; fi
done
[ "$LIMITED" = "yes" ] && ok "repeated failed logins hit 429" || no "rate limit" "never throttled"

echo "== CORS is same-origin =="
ACAO=$(curl -s -D - -o /dev/null -X OPTIONS $BASE/api/v1/auth/login \
  -H 'Origin: https://evil.example' -H 'Access-Control-Request-Method: POST' \
  | grep -i 'access-control-allow-origin' | head -1)
[ -z "$ACAO" ] && ok "no allow-origin header for a stranger's origin" || no "CORS" "$ACAO"

echo
echo "checks passed: $pass    failed: $fail"
[ "$fail" -eq 0 ] || exit 1
