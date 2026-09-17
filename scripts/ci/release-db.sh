#!/usr/bin/env bash
# Release the schema claimed by checkout-db.sh.
#
# Reads /tmp/ci-db-checkout.env, drops the schema (CASCADE) and the run's
# NOBYPASSRLS application role (app_<schema>, created by the test harness's
# KeltaStack), exits cleanly.
# Safe to run more than once; missing schema is treated as already released.
#
# Usage (in a CI step, typically with `if: always()` so a failed test still
# releases its schema):
#   scripts/ci/release-db.sh

set -uo pipefail

ENV_FILE="${CI_DB_ENV_FILE:-/tmp/ci-db-checkout.env}"
if [[ ! -f "$ENV_FILE" ]]; then
  echo "[release-db] no $ENV_FILE — checkout never ran or already cleaned up; nothing to do" >&2
  exit 0
fi

# shellcheck source=/dev/null
. "$ENV_FILE"

if [[ -z "${CI_DB_SCHEMA:-}" ]]; then
  echo "[release-db] $ENV_FILE missing CI_DB_SCHEMA; nothing to do" >&2
  rm -f "$ENV_FILE"
  exit 0
fi

# KeltaStack names its application role after the run's schema. The role owns the
# schema's tables, so it must go before (or with) the schema — DROP OWNED BY does
# both. The role is per-run, so this never touches a concurrent run's objects.
APP_ROLE="app_${CI_DB_SCHEMA}"

echo "[release-db] dropping schema $CI_DB_SCHEMA and role $APP_ROLE on instance ${CI_DB_INSTANCE:-?}" >&2
PGPASSWORD="$CI_DB_PASSWORD" psql \
  -h "$CI_DB_HOST" -p "$CI_DB_PORT" -U "$CI_DB_USER" -d "$CI_DB_DATABASE" \
  -v ON_ERROR_STOP=1 \
  -c "DROP SCHEMA IF EXISTS \"$CI_DB_SCHEMA\" CASCADE;" \
  -c "DO \$\$ BEGIN IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = '$APP_ROLE') THEN EXECUTE 'DROP OWNED BY \"$APP_ROLE\" CASCADE'; EXECUTE 'DROP ROLE \"$APP_ROLE\"'; END IF; END \$\$;" >&2 \
  || echo "[release-db] cleanup failed (instance may be down); leaving for next sweep" >&2

rm -f "$ENV_FILE"
