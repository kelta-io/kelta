#!/usr/bin/env bash
# Tests for ci/upgrade-verify.sh in its stub mode (no stack): a clean read-back passes, and a
# missing record, a changed value, a dropped field and a changed user field each fail with a
# non-zero exit that names the resource and the field. The "Upgrade Test" workflow runs this
# before it boots anything; locally: `bash ci/upgrade-verify.test.sh` (needs bash and jq).
set -euo pipefail

VERIFY="$(cd "$(dirname "$0")" && pwd)/upgrade-verify.sh"
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT

cat >"$TMP/seed.json" <<'JSON'
{
  "tenant": {"id": "t-1", "slug": "upgrade-check"},
  "checks": [
    {"label": "tenant upgrade-check", "scope": "platform", "path": "/default/api/tenants/t-1",
     "attributes": {"slug": "upgrade-check", "name": "Upgrade Check"}},
    {"label": "record upgrade_items/r-1 (Alpha)", "scope": "tenant", "path": "/upgrade-check/api/upgrade_items/r-1",
     "attributes": {"title": "Alpha", "quantity": 3, "price": 12.5, "active": true, "due_date": "2026-01-15"}},
    {"label": "record upgrade_items/r-2 (Bravo)", "scope": "tenant", "path": "/upgrade-check/api/upgrade_items/r-2",
     "attributes": {"title": "Bravo", "quantity": 0, "price": 0.99, "active": false, "due_date": "2026-06-30"}},
    {"label": "user upgrade-user@upgrade-check.test (u-1)", "scope": "tenant", "path": "/upgrade-check/api/users/u-1",
     "attributes": {"email": "upgrade-user@upgrade-check.test", "firstName": "Ursula", "lastName": "Upgrade"}}
  ]
}
JSON

# stub <case> <path> <attributes-json>: the response upgrade-verify.sh reads for <path>.
stub() {
  mkdir -p "$TMP/$1"
  jq -n --argjson a "$3" '{data: {type: "x", id: "x", attributes: ($a + {createdAt: "2026-10-10T00:00:00Z"})}}' \
    >"$TMP/$1/${2//\//_}.json"
}

# baseline <case>: every seeded resource, exactly as seeded (price spelled 12.50 on purpose).
baseline() {
  stub "$1" /default/api/tenants/t-1 '{"slug": "upgrade-check", "name": "Upgrade Check", "edition": "FREE"}'
  stub "$1" /upgrade-check/api/upgrade_items/r-1 \
    '{"title": "Alpha", "quantity": 3, "price": 12.50, "active": true, "due_date": "2026-01-15"}'
  stub "$1" /upgrade-check/api/upgrade_items/r-2 \
    '{"title": "Bravo", "quantity": 0, "price": 0.99, "active": false, "due_date": "2026-06-30"}'
  stub "$1" /upgrade-check/api/users/u-1 \
    '{"email": "upgrade-user@upgrade-check.test", "firstName": "Ursula", "lastName": "Upgrade", "status": "ACTIVE"}'
}

failures=0
STATUS=0
OUT=""

run() {
  set +e
  OUT=$(UPGRADE_VERIFY_STUB_DIR="$TMP/$1" bash "$VERIFY" "$TMP/seed.json" 2>&1)
  STATUS=$?
  set -e
}

# expect_fail <case> <line...>: verify exits non-zero and prints every <line>.
expect_fail() {
  local name="$1"
  shift
  run "$name"
  if [ "$STATUS" -eq 0 ]; then
    echo "FAIL $name: expected a non-zero exit, got 0. Output:"$'\n'"$OUT"
    failures=$((failures + 1))
    return
  fi
  local line
  for line in "$@"; do
    if ! grep -qF -- "$line" <<<"$OUT"; then
      echo "FAIL $name: output lacks '$line'. Output:"$'\n'"$OUT"
      failures=$((failures + 1))
      return
    fi
  done
  echo "ok $name (exit $STATUS)"
}

baseline clean
run clean
if [ "$STATUS" -eq 0 ] && grep -qF 'all 4 seeded resource(s) read back with identical values' <<<"$OUT"; then
  echo "ok clean"
else
  echo "FAIL clean: expected exit 0, got $STATUS. Output:"$'\n'"$OUT"
  failures=$((failures + 1))
fi

baseline missing-record
rm "$TMP/missing-record/_upgrade-check_api_upgrade_items_r-2.json"
expect_fail missing-record \
  'MISSING record upgrade_items/r-2 (Bravo): GET /upgrade-check/api/upgrade_items/r-2 returned HTTP 404'

baseline changed-value
stub changed-value /upgrade-check/api/upgrade_items/r-1 \
  '{"title": "Alpha", "quantity": 4, "price": 12.5, "active": "true", "due_date": "2026-01-15"}'
expect_fail changed-value \
  'MISMATCH record upgrade_items/r-1 (Alpha) field quantity: expected 3, got 4' \
  'MISMATCH record upgrade_items/r-1 (Alpha) field active: expected true, got "true"'

baseline dropped-field
stub dropped-field /upgrade-check/api/upgrade_items/r-2 \
  '{"title": "Bravo", "price": 0.99, "active": false, "due_date": "2026-06-30"}'
expect_fail dropped-field \
  'MISMATCH record upgrade_items/r-2 (Bravo) field quantity: expected 0, got <absent>'

baseline changed-user
stub changed-user /upgrade-check/api/users/u-1 \
  '{"email": "upgrade-user@upgrade-check.test", "firstName": "Ursula", "lastName": null}'
expect_fail changed-user \
  'MISMATCH user upgrade-user@upgrade-check.test (u-1) field lastName: expected "Upgrade", got null'

baseline not-json
printf 'upstream connect error' >"$TMP/not-json/_upgrade-check_api_users_u-1.json"
expect_fail not-json 'UNREADABLE user upgrade-user@upgrade-check.test (u-1)'

# An empty seed file (the seed step died before printing) must not pass as "nothing differed".
for seed in empty not-json no-checks; do
  case "$seed" in
    empty) : >"$TMP/seed-$seed.json" ;;
    not-json) printf 'jq: error' >"$TMP/seed-$seed.json" ;;
    no-checks) printf '{"tenant": {"id": "t-1"}, "checks": []}' >"$TMP/seed-$seed.json" ;;
  esac
  set +e
  OUT=$(UPGRADE_VERIFY_STUB_DIR="$TMP/clean" bash "$VERIFY" "$TMP/seed-$seed.json" 2>&1)
  STATUS=$?
  set -e
  if [ "$STATUS" -ne 0 ] && grep -qF 'nothing to verify' <<<"$OUT"; then
    echo "ok seed-$seed (exit $STATUS)"
  else
    echo "FAIL seed-$seed: expected a non-zero exit naming the bad seed, got $STATUS. Output:"$'\n'"$OUT"
    failures=$((failures + 1))
  fi
done

if [ "$failures" -gt 0 ]; then
  echo "upgrade-verify.test: $failures case(s) failed" >&2
  exit 1
fi
echo "upgrade-verify.test: all cases passed"
