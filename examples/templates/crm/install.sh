#!/usr/bin/env bash
# Installs the CRM template into the tenant named by the KELTA_URL, KELTA_TENANT
# and KELTA_TOKEN environment variables. Stops at the first error with a
# non-zero exit and prints "install failed at step: <name>".
#
#   ./install.sh             metadata, flow, list views, dashboard, seed records
#   ./install.sh --no-seed   everything except the seed records
#
# Safe to re-run: metadata is applied in skip mode, the flow, list views and
# dashboard are create-or-update, and seeding is skipped when accounts already
# has records. Needs the kelta CLI (override with KELTA=/path/to/kelta), jq and
# coreutils `timeout`.
#
# Nothing here can wait forever: stdin is /dev/null (no prompt can block),
# every kelta call runs under `timeout` (KELTA_CALL_TIMEOUT seconds, default
# 60; metadata apply KELTA_APPLY_TIMEOUT, default 150), and the tenant
# readiness wait is READY_ATTEMPTS polls READY_INTERVAL seconds apart.
#
# The steps marked "BUILD-LOG #n" cover platform gaps recorded in BUILD-LOG.md;
# drop them when the platform closes the gap.
set -euo pipefail
exec </dev/null

cd "$(dirname "$0")"

KELTA="${KELTA:-kelta}"
KELTA_CALL_TIMEOUT="${KELTA_CALL_TIMEOUT:-60}"
KELTA_APPLY_TIMEOUT="${KELTA_APPLY_TIMEOUT:-150}"
READY_ATTEMPTS="${READY_ATTEMPTS:-10}"
READY_INTERVAL="${READY_INTERVAL:-3}"
SEED=true
for arg in "$@"; do
  case "$arg" in
    --no-seed) SEED=false ;;
    -h|--help) sed -n '2,20p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) echo "unknown argument: $arg" >&2; exit 2 ;;
  esac
done

CURRENT_STEP="preflight"
step() { CURRENT_STEP="$*"; echo "==> $*" >&2; }
on_exit() {
  local rc=$?
  [ "$rc" -eq 0 ] || echo "install failed at step: $CURRENT_STEP (exit $rc)" >&2
}
trap on_exit EXIT
trap 'exit 143' TERM
trap 'exit 130' INT

command -v jq >/dev/null || { echo "install.sh needs jq on PATH" >&2; exit 2; }
command -v timeout >/dev/null || { echo "install.sh needs timeout (coreutils) on PATH" >&2; exit 2; }
for var in KELTA_URL KELTA_TENANT KELTA_TOKEN; do
  [ -n "${!var:-}" ] || { echo "install.sh authenticates from the environment: set $var" >&2; exit 2; }
done

# kelta_json [--timeout <s>] <args...>: run the CLI with JSON output under a time
# limit; on failure echo its stdout to stderr too (some wrappers report the
# error there) and stop.
kelta_json() {
  local limit="$KELTA_CALL_TIMEOUT" out rc=0
  if [ "$1" = --timeout ]; then limit="$2"; shift 2; fi
  out=$(timeout -k 10 "$limit" "$KELTA" "$@" --output json </dev/null) || rc=$?
  if [ "$rc" -ne 0 ]; then
    [ -z "$out" ] || echo "$out" >&2
    if [ "$rc" -eq 124 ] || [ "$rc" -eq 137 ]; then
      echo "install.sh: 'kelta $1 $2' timed out after ${limit}s" >&2
    else
      echo "install.sh: 'kelta $1 $2' failed (exit $rc)" >&2
    fi
    exit 1
  fi
  printf '%s\n' "$out"
}

step "wait for tenant API ($READY_ATTEMPTS attempts, ${READY_INTERVAL}s apart)"
ready_err=$(mktemp)
attempt=0
until timeout -k 5 15 "$KELTA" collections list --output json </dev/null >/dev/null 2>"$ready_err"; do
  rc=$?
  attempt=$((attempt + 1))
  # exit 3 = AUTH: a bad token does not get better by waiting
  if [ "$rc" -eq 3 ] || [ "$attempt" -ge "$READY_ATTEMPTS" ]; then
    cat "$ready_err" >&2
    echo "install.sh: tenant API not ready after $attempt attempt(s) (last exit $rc)" >&2
    exit 1
  fi
  sleep "$READY_INTERVAL"
done
rm -f "$ready_err"

step "metadata: collections, fields, picklists, page layouts"
result=$(kelta_json --timeout "$KELTA_APPLY_TIMEOUT" metadata apply package.json --yes)
# BUILD-LOG #3: the CLI exits 0 when individual items fail to import.
if [ "$(jq '.failed' <<<"$result")" != 0 ]; then
  jq '[.items[] | select(.action == "FAILED")]' <<<"$result" >&2
  echo "install.sh: metadata apply reported failed items" >&2
  exit 1
fi

# BUILD-LOG #4: the import copies fieldTypeConfig.globalPicklistId verbatim, so
# a picklist field points at the source tenant's picklist id. Relink each one
# to the picklist of the same name in this tenant.
links=$(jq -c '
  (.items | map(select(.type == "GLOBAL_PICKLIST") | {(.data.id): .data.name}) | add) as $names
  | .items[]
  | select(.type == "FIELD" and .data.field_type_config.globalPicklistId != null)
  | {collection: .data.collection_name, field: .data.name,
     picklist: $names[.data.field_type_config.globalPicklistId]}' package.json)
while read -r link; do
  [ -n "$link" ] || continue
  collection=$(jq -r .collection <<<"$link")
  field=$(jq -r .field <<<"$link")
  picklist=$(jq -r .picklist <<<"$link")
  step "picklist field: $collection.$field -> $picklist"
  picklist_id=$(kelta_json picklists get "$picklist" | jq -r .id)
  field_id=$(kelta_json fields list "$collection" | jq -r --arg f "$field" '.[] | select(.name == $f) | .id')
  [ -n "$picklist_id" ] && [ -n "$field_id" ] || { echo "install.sh: picklist or field not found" >&2; exit 1; }
  kelta_json fields update "$field_id" --yes --data "$(jq -cn --arg id "$picklist_id" \
    '{fieldTypeConfig: {globalPicklistId: $id, picklistSourceType: "GLOBAL"}}')" >/dev/null
done <<<"$links"

# BUILD-LOG #5: a FLOW item fails to import for a PAT caller, so flows are
# applied with the flows commands instead of riding in package.json.
for file in flows/*.json; do
  name=$(jq -r .name "$file")
  step "flow: $name"
  existing=$(kelta_json flows list | jq -r --arg n "$name" '[.[] | select(.name == $n)][0].id // empty')
  if [ -n "$existing" ]; then
    kelta_json flows update "$existing" --yes --active "$(jq -r .active "$file")" \
      --definition "$(jq -c .definition "$file")" \
      --data "$(jq -c '{description, triggerConfig}' "$file")" >/dev/null
  else
    args=(--name "$name" --type "$(jq -r .flowType "$file")"
      --description "$(jq -r .description "$file")"
      --trigger-config "$(jq -c .triggerConfig "$file")"
      --definition "$(jq -c .definition "$file")")
    [ "$(jq -r .active "$file")" = true ] && args+=(--active)
    kelta_json flows create "${args[@]}" --yes >/dev/null
  fi
done

for file in list-views/*/*.json; do
  collection=$(basename "$(dirname "$file")")
  name=$(jq -r .name "$file")
  step "list view: $collection / $name"
  kelta_json list-views apply "$collection" --yes \
    --name "$name" \
    --columns "$(jq -r '.columns | join(",")' "$file")" \
    --data "@$file" >/dev/null
done

for file in dashboards/*.json; do
  step "dashboard: $(jq -r .name "$file")"
  kelta_json dashboards apply --yes --file "$file" >/dev/null
done

if [ "$SEED" = true ]; then
  step "seed records: check for existing accounts"
  existing=$(kelta_json records list accounts --size 1 | jq length)
  if [ "$existing" != 0 ]; then
    step "seed records: skipped, accounts already has records"
  else
    # BUILD-LOG #6: a batch drops relationships (lid or id) on add, so each
    # collection is its own batch and a reference is written as {"lid": "..."}
    # in attributes, resolved here to the id an earlier batch returned.
    lids='{}'
    for file in seeds/*.json; do
      step "seed records: $file ($(jq '."atomic:operations" | length' "$file") records)"
      body=$(jq --argjson lids "$lids" '
        ."atomic:operations" |= map(.data.attributes |= with_entries(
          if (.value | type) == "object" and (.value | has("lid"))
          then .value = ($lids[.value.lid] // error("unknown lid \(.value.lid)"))
          else . end))' "$file")
      result=$(kelta_json records bulk --data "$body" --yes)
      lids=$(jq --argjson lids "$lids" \
        '$lids + ([."atomic:results"[].data | {(.lid): .id}] | add)' <<<"$result")
    done
  fi
fi

step "done"
echo "CRM template installed" >&2
