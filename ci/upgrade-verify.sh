#!/usr/bin/env bash
# Checks that the upgraded stack still holds everything ci/upgrade-seed.sh wrote on the release
# it was upgraded from. Run by the "Upgrade Test" workflow (.github/workflows/upgrade-test.yml)
# on the compose network of the HEAD stack, which was started on the release stack's volumes:
#
#   bash ci/upgrade-verify.sh seed.json       (or the seed JSON on stdin)
#
# For every entry in the seed JSON's `checks` it GETs `path` and compares each key of
# `attributes` with the response's data.attributes (jq equality, so 12.5 and 12.50 match). It
# reports every problem, not just the first, then exits 1:
#
#   MISSING  <label>: GET <path> returned HTTP 404
#   UNREADABLE <label>: GET <path> returned HTTP <status>
#   MISMATCH <label> field <name>: expected <json>, got <json>
#
# Live mode signs in as the platform admin (ADMIN_PASSWORD, the password the seed ran with —
# it must have survived the upgrade too) for `platform` checks, and mints a fresh bootstrap PAT
# in the seeded tenant for `tenant` checks.
#
# Stub mode (UPGRADE_VERIFY_STUB_DIR set) makes no requests: the response for <path> is the file
# <dir>/<path with every / replaced by _>.json, and a missing file is a 404. It is how
# ci/upgrade-verify.test.sh proves the failure paths without a stack.
set -euo pipefail
shopt -s inherit_errexit

AUTH_URL="${AUTH_URL:-http://kelta-auth:8081}"
GATEWAY_URL="${GATEWAY_URL:-http://kelta-gateway:8080}"
PLATFORM_SLUG="${PLATFORM_SLUG:-default}"
ADMIN_USERNAME="${ADMIN_USERNAME:-admin@kelta.local}"
WAIT_SECONDS="${WAIT_SECONDS:-180}"
STUB_DIR="${UPGRADE_VERIFY_STUB_DIR:-}"

SEED="$(cat "${1:-/dev/stdin}")"
CHECK_COUNT=$(jq -r 'if (.tenant.id | type) == "string" then (.checks | length) else 0 end' <<<"$SEED" 2>/dev/null || true)
if ! [ "${CHECK_COUNT:-0}" -ge 1 ] 2>/dev/null; then
  echo "::error::upgrade-verify: the seed JSON is empty, unreadable or has no tenant.id/checks — nothing to verify" >&2
  exit 2
fi

TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT
BODY="$TMP/body"
STATUS=""

fail() {
  echo "::error::upgrade-verify: $*" >&2
  exit 1
}

# fetch <token> <method> <path> [json]: sets STATUS, leaves the body in $BODY.
fetch() {
  if [ -n "$STUB_DIR" ]; then
    local file="$STUB_DIR/${3//\//_}.json"
    if [ -f "$file" ]; then STATUS=200; cp "$file" "$BODY"; else STATUS=404; : >"$BODY"; fi
    return 0
  fi
  local args=(-sS -m 30 -o "$BODY" -w '%{http_code}' -X "$2" "$GATEWAY_URL$3"
    -H "Authorization: Bearer $1" -H 'Content-Type: application/json')
  [ -n "${4:-}" ] && args+=(--data "$4")
  STATUS=$(curl "${args[@]}" 2>/dev/null) || STATUS="000"
}

# fetch_ok: fetch, retried until WAIT_SECONDS — the gateway may still be loading routes.
fetch_ok() {
  local deadline=$((SECONDS + WAIT_SECONDS))
  while :; do
    fetch "$@"
    case "$STATUS" in 2??) return 0 ;; esac
    [ "$SECONDS" -ge "$deadline" ] \
      && fail "$2 $3 still HTTP $STATUS after ${WAIT_SECONDS}s: $(head -c 500 "$BODY")"
    sleep 2
  done
}

PLATFORM_TOKEN=stub
TENANT_TOKEN=stub
if [ -z "$STUB_DIR" ]; then
  : "${ADMIN_PASSWORD:?ADMIN_PASSWORD must be set}"
  PLATFORM_TOKEN=$(curl -fsS -m 30 -X POST "$AUTH_URL/auth/direct-login" \
    -H 'Content-Type: application/json' \
    -d "$(jq -cn --arg u "$ADMIN_USERNAME" --arg p "$ADMIN_PASSWORD" --arg t "$PLATFORM_SLUG" \
      '{username: $u, password: $p, tenantSlug: $t}')" | jq -r '.access_token // empty') \
    || fail "platform admin direct login failed after the upgrade"
  [ -n "$PLATFORM_TOKEN" ] || fail "platform admin direct login returned no access_token after the upgrade"

  TENANT_ID=$(jq -r '.tenant.id' <<<"$SEED")
  TENANT_SLUG=$(jq -r '.tenant.slug' <<<"$SEED")
  fetch_ok "$PLATFORM_TOKEN" POST "/$PLATFORM_SLUG/api/tenants/$TENANT_ID/bootstrap-token" '{"expiresIn":"1h"}'
  TENANT_TOKEN=$(jq -er '.token' "$BODY")
  fetch_ok "$TENANT_TOKEN" GET "/$TENANT_SLUG/api/me/identity"
fi

problems=0
total=0
while IFS= read -r entry; do
  total=$((total + 1))
  label=$(jq -r '.label' <<<"$entry")
  path=$(jq -r '.path' <<<"$entry")
  if [ "$(jq -r '.scope' <<<"$entry")" = platform ]; then token="$PLATFORM_TOKEN"; else token="$TENANT_TOKEN"; fi

  fetch "$token" GET "$path"
  if [ "$STATUS" = 404 ]; then
    echo "MISSING $label: GET $path returned HTTP 404"
    problems=$((problems + 1))
    continue
  elif [ "$STATUS" != 200 ]; then
    echo "UNREADABLE $label: GET $path returned HTTP $STATUS: $(head -c 300 "$BODY")"
    problems=$((problems + 1))
    continue
  fi

  diffs=$(jq -r --argjson expected "$(jq -c '.attributes' <<<"$entry")" '
    (.data.attributes // {}) as $actual
    | $expected | to_entries[] | .key as $k
    | select($actual[$k] != .value)
    | "\($k)\t\(.value | tojson)\t\(if $actual | has($k) then $actual[$k] | tojson else "<absent>" end)"
  ' "$BODY" 2>/dev/null) || { echo "UNREADABLE $label: GET $path returned a body that is not JSON"; problems=$((problems + 1)); continue; }

  if [ -z "$diffs" ]; then
    echo "ok $label"
    continue
  fi
  while IFS=$'\t' read -r field expected actual; do
    echo "MISMATCH $label field $field: expected $expected, got $actual"
    problems=$((problems + 1))
  done <<<"$diffs"
done < <(jq -c '.checks[]' <<<"$SEED")

if [ "$problems" -gt 0 ]; then
  echo "::error::upgrade-verify: $problems problem(s) across $total seeded resource(s) after the upgrade" >&2
  exit 1
fi
echo "upgrade-verify: all $total seeded resource(s) read back with identical values"
