#!/usr/bin/env bash
# Seeds the fixed dataset the "Upgrade Test" workflow (.github/workflows/upgrade-test.yml)
# carries across an upgrade: one tenant, one collection with five field types, three records
# and one user — all through the public API, as a self-hoster's data would be written.
#
# Runs on the compose network of the RELEASE stack (inside the job's runner image, which has
# bash, curl and jq), after ci/admin-first-sign-in.sh has set the platform admin's password to
# ADMIN_PASSWORD:
#   1. signs in as the platform admin (POST /auth/direct-login on the platform tenant);
#   2. creates the tenant (a `tenants` record) and mints a bootstrap PAT for its seeded admin
#      (POST /api/tenants/{id}/bootstrap-token);
#   3. creates the collection, its fields, the records and the user with that PAT;
#   4. reads every seeded resource back and prints the values the release returned as JSON on
#      stdout. ci/upgrade-verify.sh compares the upgraded stack against exactly that JSON.
#
# Progress goes to stderr; stdout is only the JSON. Every request has a 30s cap and every wait
# loop a deadline (WAIT_SECONDS), so a hung stack fails the step instead of the job.
set -euo pipefail
shopt -s inherit_errexit

AUTH_URL="${AUTH_URL:-http://kelta-auth:8081}"
GATEWAY_URL="${GATEWAY_URL:-http://kelta-gateway:8080}"
PLATFORM_SLUG="${PLATFORM_SLUG:-default}"
ADMIN_USERNAME="${ADMIN_USERNAME:-admin@kelta.local}"
TENANT_SLUG="${UPGRADE_TENANT_SLUG:-upgrade-check}"
WAIT_SECONDS="${WAIT_SECONDS:-180}"
: "${ADMIN_PASSWORD:?ADMIN_PASSWORD must be set}"

COLLECTION=upgrade_items
FIELDS='[
  {"name": "title", "type": "STRING"},
  {"name": "quantity", "type": "INTEGER"},
  {"name": "price", "type": "DOUBLE"},
  {"name": "active", "type": "BOOLEAN"},
  {"name": "due_date", "type": "DATE"}
]'
RECORDS='[
  {"title": "Alpha", "quantity": 3, "price": 12.5, "active": true, "due_date": "2026-01-15"},
  {"title": "Bravo", "quantity": 0, "price": 0.99, "active": false, "due_date": "2026-06-30"},
  {"title": "Charlie", "quantity": 1200, "price": 4500.25, "active": true, "due_date": "2027-12-31"}
]'
USER_ATTRIBUTES="$(jq -cn --arg email "upgrade-user@${TENANT_SLUG}.test" \
  '{email: $email, firstName: "Ursula", lastName: "Upgrade", timezone: "Europe/Lisbon"}')"

TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT

log() { echo "upgrade-seed: $*" >&2; }
fail() {
  echo "::error::upgrade-seed: $*" >&2
  exit 1
}

STATUS=""
BODY="$TMP/body"

# call <token> <method> <url> [json]: one request; sets STATUS, leaves the body in $BODY.
call() {
  local token="$1" method="$2" url="$3" data="${4:-}"
  local args=(-sS -m 30 -o "$BODY" -w '%{http_code}' -X "$method" "$url"
    -H "Authorization: Bearer $token" -H 'Content-Type: application/json')
  [ -n "$data" ] && args+=(--data "$data")
  STATUS=$(curl "${args[@]}" 2>"$TMP/curl.err") || STATUS="000"
}

# api <token> <method> <path> [json]: a gateway request that must succeed; prints the body.
api() {
  call "$1" "$2" "$GATEWAY_URL$3" "${4:-}"
  case "$STATUS" in
    2??) cat "$BODY" ;;
    *) fail "$2 $3 returned HTTP $STATUS: $(head -c 500 "$BODY" 2>/dev/null; cat "$TMP/curl.err")" ;;
  esac
}

# api_retry: as api, retried until WAIT_SECONDS for the windows where the stack has accepted a
# write but not yet propagated it (a new tenant's slug, a new collection's route or field).
api_retry() {
  local deadline=$((SECONDS + WAIT_SECONDS))
  while :; do
    call "$1" "$2" "$GATEWAY_URL$3" "${4:-}"
    case "$STATUS" in 2??) cat "$BODY"; return 0 ;; esac
    [ "$SECONDS" -ge "$deadline" ] \
      && fail "$2 $3 still HTTP $STATUS after ${WAIT_SECONDS}s: $(head -c 500 "$BODY" 2>/dev/null; cat "$TMP/curl.err")"
    sleep 2
  done
}

sign_in() {
  local token
  token=$(curl -fsS -m 30 -X POST "$AUTH_URL/auth/direct-login" \
    -H 'Content-Type: application/json' \
    -d "$(jq -cn --arg u "$ADMIN_USERNAME" --arg p "$ADMIN_PASSWORD" --arg t "$PLATFORM_SLUG" \
      '{username: $u, password: $p, tenantSlug: $t}')" | jq -r '.access_token // empty') \
    || fail "direct login to $AUTH_URL failed"
  [ -n "$token" ] || fail "direct login did not return an access_token"
  printf '%s' "$token"
}

# read_back <token> <path> <label> <keys-json>: the resource's attributes, limited to <keys>;
# fails if any of them came back null, i.e. the release never stored what was sent.
read_back() {
  local attrs
  attrs=$(api "$1" GET "$2" | jq -c --argjson keys "$4" \
    '.data.attributes as $a | reduce $keys[] as $k ({}; .[$k] = $a[$k])')
  jq -e 'all(.[]; . != null)' <<<"$attrs" >/dev/null \
    || fail "$3 read back with a null seeded field: $attrs"
  printf '%s' "$attrs"
}

PLATFORM_TOKEN="$(sign_in)"
log "signed in as $ADMIN_USERNAME on $PLATFORM_SLUG"

TENANT_ID=$(api "$PLATFORM_TOKEN" POST "/$PLATFORM_SLUG/api/tenants" \
  "$(jq -cn --arg s "$TENANT_SLUG" '{data: {type: "tenants", attributes: {slug: $s, name: "Upgrade Check"}}}')" \
  | jq -r '.data.id')
if [ -z "$TENANT_ID" ] || [ "$TENANT_ID" = null ]; then fail "tenant create returned no id"; fi
log "created tenant $TENANT_SLUG ($TENANT_ID)"

TENANT_TOKEN=$(api_retry "$PLATFORM_TOKEN" POST "/$PLATFORM_SLUG/api/tenants/$TENANT_ID/bootstrap-token" \
  '{"expiresIn":"1h"}' | jq -er '.token')
# The gateway learns a new tenant's slug asynchronously.
api_retry "$TENANT_TOKEN" GET "/$TENANT_SLUG/api/me/identity" >/dev/null
log "bootstrap token for $TENANT_SLUG works"

BASE="/$TENANT_SLUG/api"
COLLECTION_ID=$(api "$TENANT_TOKEN" POST "$BASE/collections" \
  "$(jq -cn --arg n "$COLLECTION" \
    '{data: {type: "collections", attributes: {name: $n, displayName: "Upgrade Items", tenantScoped: true}}}')" \
  | jq -er '.data.id')
api_retry "$TENANT_TOKEN" GET "$BASE/$COLLECTION" >/dev/null
while IFS= read -r field; do
  api "$TENANT_TOKEN" POST "$BASE/fields" \
    "$(jq -cn --arg c "$COLLECTION_ID" --argjson f "$field" \
      '{data: {type: "fields", attributes: ({collectionId: $c} + $f)}}')" >/dev/null
done < <(jq -c '.[]' <<<"$FIELDS")
log "created collection $COLLECTION ($COLLECTION_ID) with $(jq length <<<"$FIELDS") fields"

FIELD_NAMES=$(jq -c 'map(.name)' <<<"$FIELDS")
CHECKS="$TMP/checks.ndjson"
: >"$CHECKS"

# check <label> <scope> <path> <attributes-json>: one resource ci/upgrade-verify.sh re-reads.
check() {
  jq -cn --arg l "$1" --arg s "$2" --arg p "$3" --argjson a "$4" \
    '{"label": $l, "scope": $s, "path": $p, "attributes": $a}' >>"$CHECKS"
}

TENANT_PATH="/$PLATFORM_SLUG/api/tenants/$TENANT_ID"
attrs=$(read_back "$PLATFORM_TOKEN" "$TENANT_PATH" "tenant $TENANT_SLUG" '["slug","name"]')
check "tenant $TENANT_SLUG" platform "$TENANT_PATH" "$attrs"

while IFS= read -r record; do
  title=$(jq -r '.title' <<<"$record")
  # A field is live on the record route a moment after its create returns.
  id=$(api_retry "$TENANT_TOKEN" POST "$BASE/$COLLECTION" \
    "$(jq -cn --arg t "$COLLECTION" --argjson a "$record" '{data: {type: $t, attributes: $a}}')" \
    | jq -er '.data.id')
  label="record $COLLECTION/$id ($title)"
  attrs=$(read_back "$TENANT_TOKEN" "$BASE/$COLLECTION/$id" "$label" "$FIELD_NAMES")
  check "$label" tenant "$BASE/$COLLECTION/$id" "$attrs"
done < <(jq -c '.[]' <<<"$RECORDS")
log "created $(jq length <<<"$RECORDS") $COLLECTION records"

USER_ID=$(api "$TENANT_TOKEN" POST "$BASE/users" \
  "$(jq -cn --argjson a "$USER_ATTRIBUTES" '{data: {type: "users", attributes: $a}}')" | jq -er '.data.id')
label="user $(jq -r '.email' <<<"$USER_ATTRIBUTES") ($USER_ID)"
attrs=$(read_back "$TENANT_TOKEN" "$BASE/users/$USER_ID" "$label" "$(jq -c 'keys' <<<"$USER_ATTRIBUTES")")
check "$label" tenant "$BASE/users/$USER_ID" "$attrs"
log "created $label"

jq -s --arg id "$TENANT_ID" --arg slug "$TENANT_SLUG" \
  '{tenant: {id: $id, slug: $slug}, checks: .}' "$CHECKS"
