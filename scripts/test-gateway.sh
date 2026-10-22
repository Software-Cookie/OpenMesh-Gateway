#!/usr/bin/env bash
# End-to-end smoke test against a running gateway started with the "dev" profile.
#   ./scripts/test-gateway.sh [http://localhost:8080]
# Requires: curl, jq
set -euo pipefail

G="${1:-http://localhost:8080}"
pass=0; fail=0
check() { # name expected actual
  if [[ "$2" == "$3" ]]; then echo "  PASS  $1"; pass=$((pass+1)); else echo "  FAIL  $1 (expected $2, got $3)"; fail=$((fail+1)); fi
}
status() { curl -s -o /dev/null -w "%{http_code}" "$@"; }
token() { curl -s -X POST "$G/auth/token" -H 'Content-Type: application/json' -d "$1" | jq -r .token; }

echo "OpenMesh Gateway smoke test: $G"

echo "[1] Health"
check "gateway is UP" "UP" "$(curl -s "$G/actuator/health" | jq -r .status)"

echo "[2] Tokens"
ADMIN=$(token '{"tenantId":"tenant-acme","userId":"alice","roles":["ROLE_ADMIN"],"scopes":["read","write"],"tier":"PRO"}')
FREE=$(token "{\"tenantId\":\"free-$RANDOM\",\"userId\":\"bob\",\"scopes\":[\"read\"],\"tier\":\"FREE\"}")
check "admin token minted" "true" "$([[ -n "$ADMIN" && "$ADMIN" != null ]] && echo true || echo false)"

echo "[3] Security"
check "no token -> 401" "401" "$(status "$G/service-alpha/orders")"
check "garbage token -> 401" "401" "$(status -H 'Authorization: Bearer x.y.z' "$G/service-alpha/orders")"
check "admin API without token -> 401" "401" "$(status "$G/admin/v1/routes")"
check "admin API as non-admin -> 403" "403" "$(status -H "Authorization: Bearer $FREE" "$G/admin/v1/routes")"

echo "[4] Zero-trust identity"
BODY=$(curl -s -H "Authorization: Bearer $ADMIN" -H 'X-User-Id: attacker' -H 'X-Tenant-Id: victim' "$G/service-alpha/orders")
check "spoofed X-User-Id replaced" "alice" "$(echo "$BODY" | jq -r .identity.userId)"
check "spoofed X-Tenant-Id replaced" "tenant-acme" "$(echo "$BODY" | jq -r .identity.tenantId)"
check "downstream verified HMAC signature" "true" "$(echo "$BODY" | jq -r .signature.valid)"

echo "[5] Rate limiting (FREE tier: burst 10)"
LIMITED=0
for _ in $(seq 1 25); do [[ "$(status -H "Authorization: Bearer $FREE" "$G/service-beta/items")" == 429 ]] && LIMITED=$((LIMITED+1)); done
check "requests rejected with 429" "true" "$([[ $LIMITED -ge 5 ]] && echo true || echo false)"

echo "[6] Resilience"
check "upstream 500 -> 502 fallback" "502" "$(status -H "Authorization: Bearer $ADMIN" "$G/service-beta/chaos/error?status=500")"
check "slow upstream -> 504 timeout" "504" "$(status -H "Authorization: Bearer $ADMIN" "$G/service-beta/chaos/delay?ms=2500")"

echo "[7] Dynamic routes"
check "invalid route rejected" "400" "$(status -X POST "$G/admin/v1/routes" -H "Authorization: Bearer $ADMIN" -H 'Content-Type: application/json' -d '{"id":"bad","uri":"http://169.254.169.254","predicates":["Path=/x/**"]}')"
check "route created" "201" "$(status -X POST "$G/admin/v1/routes" -H "Authorization: Bearer $ADMIN" -H 'Content-Type: application/json' -d '{"id":"smoke-route","uri":"http://downstream-service-alpha:8081","predicates":["Path=/smoke/**"],"filters":["StripPrefix=1"]}')"
sleep 1
check "route deleted" "204" "$(status -X DELETE "$G/admin/v1/routes/smoke-route" -H "Authorization: Bearer $ADMIN")"

echo "[8] API keys"
KEY=$(curl -s -X POST "$G/admin/v1/api-keys" -H "Authorization: Bearer $ADMIN" -H 'Content-Type: application/json' -d '{"name":"smoke","tenantId":"tenant-smoke","tier":"PRO","scopes":["read"]}')
check "API key authenticates" "200" "$(status -H "X-API-Key: $(echo "$KEY" | jq -r .apiKey)" "$G/service-alpha/orders")"
curl -s -o /dev/null -X DELETE "$G/admin/v1/api-keys/$(echo "$KEY" | jq -r .record.id)" -H "Authorization: Bearer $ADMIN"
check "revoked key -> 401" "401" "$(status -H "X-API-Key: $(echo "$KEY" | jq -r .apiKey)" "$G/service-alpha/orders")"

echo "[9] Metrics"
check "Prometheus exposes gateway metrics" "true" "$(curl -s "$G/actuator/prometheus" | grep -q openmesh_gateway_requests_total && echo true || echo false)"

echo; echo "Passed: $pass  Failed: $fail"
[[ $fail -eq 0 ]]
