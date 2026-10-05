#!/bin/sh
# Runs inside a curlimages/curl container on the compose network (see the
# "quickstart" job in .github/workflows/ci.yml). Verifies the documented
# quickstart actually works: log in as the platform admin (after
# ci/quickstart-run.sh has completed its forced first-sign-in password change to
# ADMIN_PASSWORD), then create a collection through the same API a first-time
# user's browser session hits.
set -eu

AUTH_URL="${AUTH_URL:-http://kelta-auth:8081}"
GATEWAY_URL="${GATEWAY_URL:-http://kelta-gateway:8080}"
TENANT_SLUG="${TENANT_SLUG:-default}"
ADMIN_USERNAME="${ADMIN_USERNAME:-admin@kelta.local}"
: "${ADMIN_PASSWORD:?ADMIN_PASSWORD must be set}"

TOKEN=$(curl -fsS -X POST "$AUTH_URL/auth/direct-login" \
  -H "Content-Type: application/json" \
  -d "{\"username\":\"$ADMIN_USERNAME\",\"password\":\"$ADMIN_PASSWORD\",\"tenantSlug\":\"$TENANT_SLUG\"}" \
  | sed -n 's/.*"access_token":"\([^"]*\)".*/\1/p')

if [ -z "$TOKEN" ]; then
  echo "Direct login did not return an access_token" >&2
  exit 1
fi

curl -fsS -X POST "$GATEWAY_URL/$TENANT_SLUG/api/collections" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"data":{"type":"collections","attributes":{"name":"quickstart_smoke","displayName":"Quickstart Smoke","tenantScoped":true}}}'
