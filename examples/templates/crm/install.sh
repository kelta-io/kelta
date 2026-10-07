#!/usr/bin/env bash
# Installs the CRM template into the tenant of the active kelta profile (or of
# the KELTA_URL / KELTA_TENANT / KELTA_TOKEN environment). Stops at the first
# error with a non-zero exit.
#
#   ./install.sh             metadata, flow, list views, dashboard, seed records
#   ./install.sh --no-seed   everything except the seed records
#
# Safe to re-run: metadata is applied in skip mode, the flow, list views and
# dashboard are create-or-update, and seeding is skipped when accounts already
# has records. Needs the kelta CLI (override with KELTA=/path/to/kelta) and jq.
#
# The steps marked "BUILD-LOG #n" cover platform gaps recorded in BUILD-LOG.md;
# drop them when the platform closes the gap.
set -euo pipefail

cd "$(dirname "$0")"

KELTA="${KELTA:-kelta}"
SEED=true
for arg in "$@"; do
  case "$arg" in
    --no-seed) SEED=false ;;
    -h|--help) sed -n '2,15p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) echo "unknown argument: $arg" >&2; exit 2 ;;
  esac
done

command -v jq >/dev/null || { echo "install.sh needs jq on PATH" >&2; exit 2; }

step() { echo "==> $*" >&2; }

# kelta <args...>: run the CLI with JSON output; on failure echo its stdout to
# stderr too (some wrappers report the error there) and stop. `command` skips
# shell functions: with the default KELTA=kelta, a bare "$KELTA" resolves to
# this function and recurses until the host runs out of memory.
kelta() {
  local out
  if ! out=$(command "$KELTA" "$@" --output json); then
    echo "$out" >&2
    echo "install.sh: 'kelta $1 $2' failed" >&2
    exit 1
  fi
  printf '%s\n' "$out"
}

step "metadata: collections, fields, picklists, page layouts"
result=$(kelta metadata apply package.json --yes)
# BUILD-LOG #3: the CLI exits 0 when individual items fail to import.
if [ "$(jq '.failed' <<<"$result")" != 0 ]; then
  jq '[.items[] | select(.action == "FAILED")]' <<<"$result" >&2
  echo "install.sh: metadata apply reported failed items" >&2
  exit 1
fi

# BUILD-LOG #4: the import copies fieldTypeConfig.globalPicklistId verbatim, so
# a picklist field points at the source tenant's picklist id. Relink each one
# to the picklist of the same name in this tenant.
jq -c '
  (.items | map(select(.type == "GLOBAL_PICKLIST") | {(.data.id): .data.name}) | add) as $names
  | .items[]
  | select(.type == "FIELD" and .data.field_type_config.globalPicklistId != null)
  | {collection: .data.collection_name, field: .data.name,
     picklist: $names[.data.field_type_config.globalPicklistId]}' package.json |
while read -r link; do
  collection=$(jq -r .collection <<<"$link")
  field=$(jq -r .field <<<"$link")
  picklist=$(jq -r .picklist <<<"$link")
  step "picklist field: $collection.$field -> $picklist"
  picklist_id=$(kelta picklists get "$picklist" | jq -r .id)
  field_id=$(kelta fields list "$collection" | jq -r --arg f "$field" '.[] | select(.name == $f) | .id')
  kelta fields update "$field_id" --yes --data "$(jq -cn --arg id "$picklist_id" \
    '{fieldTypeConfig: {globalPicklistId: $id, picklistSourceType: "GLOBAL"}}')" >/dev/null
done

# BUILD-LOG #5: a FLOW item fails to import for a PAT caller, so flows are
# applied with the flows commands instead of riding in package.json.
for file in flows/*.json; do
  name=$(jq -r .name "$file")
  step "flow: $name"
  existing=$(kelta flows list | jq -r --arg n "$name" '[.[] | select(.name == $n)][0].id // empty')
  if [ -n "$existing" ]; then
    kelta flows update "$existing" --yes --active "$(jq -r .active "$file")" \
      --definition "$(jq -c .definition "$file")" \
      --data "$(jq -c '{description, triggerConfig}' "$file")" >/dev/null
  else
    args=(--name "$name" --type "$(jq -r .flowType "$file")"
      --description "$(jq -r .description "$file")"
      --trigger-config "$(jq -c .triggerConfig "$file")"
      --definition "$(jq -c .definition "$file")")
    [ "$(jq -r .active "$file")" = true ] && args+=(--active)
    kelta flows create "${args[@]}" >/dev/null
  fi
done

for file in list-views/*/*.json; do
  collection=$(basename "$(dirname "$file")")
  name=$(jq -r .name "$file")
  step "list view: $collection / $name"
  kelta list-views apply "$collection" \
    --name "$name" \
    --columns "$(jq -r '.columns | join(",")' "$file")" \
    --data "@$file" >/dev/null
done

for file in dashboards/*.json; do
  step "dashboard: $(jq -r .name "$file")"
  kelta dashboards apply --file "$file" >/dev/null
done

if [ "$SEED" = true ]; then
  existing=$(kelta records list accounts --size 1 | jq length)
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
      result=$(kelta records bulk --data "$body" --yes)
      lids=$(jq --argjson lids "$lids" \
        '$lids + ([."atomic:results"[].data | {(.lid): .id}] | add)' <<<"$result")
    done
  fi
fi

step "CRM template installed"
