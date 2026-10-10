#!/usr/bin/env bash
# Installs the CRM template into the tenant of the active kelta profile
# (`kelta profile use <name>` or KELTA_PROFILE / KELTA_URL / KELTA_TENANT / KELTA_TOKEN).
#
#   ./install.sh
#
# 1. kelta metadata apply metadata.json   collections, fields, picklists, layouts, validation rule, flow
# 2. kelta list-views apply               every view in list-views/<collection>.json
# 3. kelta dashboards apply               every dashboard in dashboards/*.json
# 4. kelta records bulk                   every seeds/<collection>.json, parents before children
#
# Seed files hold plain attribute objects. A reference field holds the parent's natural key
# (see REFS below) and is resolved to the parent's record id at load time.
# Needs: kelta, jq. Stops at the first failed command.
set -euo pipefail

if [[ $# -ne 0 ]]; then
  echo "usage: $0 (takes no arguments; installs into the active kelta profile)" >&2
  exit 2
fi

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$HERE"

for tool in kelta jq; do
  command -v "$tool" >/dev/null || { echo "install.sh: '$tool' is not on PATH" >&2; exit 1; }
done

# Load order: a collection is seeded after every collection its reference fields point at.
SEED_ORDER=(accounts contacts deals activities)

# <collection>.<reference field>=<parent collection>.<parent field holding the seed's key>
REFS=(
  contacts.account=accounts.name
  deals.account=accounts.name
  deals.primaryContact=contacts.email
  activities.deal=deals.name
  activities.contact=contacts.email
)

BULK_MAX=100
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

# --- 1. metadata package ---------------------------------------------------------------
if ! kelta metadata apply metadata.json --yes --output json >"$WORK/apply.json" \
  || ! jq -e '.success == true and .failed == 0' "$WORK/apply.json" >/dev/null; then
  echo "install.sh: metadata apply reported errors:" >&2
  cat "$WORK/apply.json" >&2
  exit 1
fi
echo "applied metadata.json: $(jq -c '{created, updated, skipped, failed}' "$WORK/apply.json")"

# --- 2. list views ---------------------------------------------------------------------
for file in list-views/*.json; do
  collection="$(basename "$file" .json)"
  count="$(jq length "$file")"
  for ((i = 0; i < count; i++)); do
    view="$(jq -c ".[$i]" "$file")"
    args=(list-views apply "$collection"
      --name "$(jq -r .name <<<"$view")"
      --columns "$(jq -r '.columns | join(",")' <<<"$view")"
      --visibility "$(jq -r '.visibility // "PUBLIC"' <<<"$view")"
      --view-type "$(jq -r '.viewType // "TABLE"' <<<"$view")"
      --default "$(jq -r '.default // false' <<<"$view")")
    while IFS= read -r filter; do
      args+=(--filter "$filter")
    done < <(jq -r '.filters // [] | .[]' <<<"$view")
    if jq -e 'has("sort")' <<<"$view" >/dev/null; then args+=(--sort "$(jq -r .sort <<<"$view")"); fi
    if jq -e 'has("rowLimit")' <<<"$view" >/dev/null; then args+=(--row-limit "$(jq -r .rowLimit <<<"$view")"); fi
    if jq -e 'has("laneField")' <<<"$view" >/dev/null; then args+=(--lane-field "$(jq -r .laneField <<<"$view")"); fi
    if jq -e 'has("cardFields")' <<<"$view" >/dev/null; then
      args+=(--card-fields "$(jq -r '.cardFields | join(",")' <<<"$view")")
    fi
    result="$(kelta "${args[@]}" --output json)"
    echo "list view $collection/$(jq -r .name <<<"$view"): $(jq -r '.action // "applied"' <<<"$result")"
  done
done

# --- 3. dashboards ---------------------------------------------------------------------
for file in dashboards/*.json; do
  result="$(kelta dashboards apply --file "$file" --output json)"
  echo "dashboard $(jq -r .name <<<"$result"): $(jq -c '{created, updated, deleted, unchanged}' <<<"$result")"
done

# --- 4. seed records -------------------------------------------------------------------
echo '{}' >"$WORK/keys.json"

# Adds "<parent>.<field>" -> {key: id} for every parent key a reference in REFS needs.
index_keys() {
  local collection="$1" ref parent field
  for ref in "${REFS[@]}"; do
    parent="${ref#*=}"
    [[ "${parent%%.*}" == "$collection" ]] || continue
    field="${parent#*.}"
    kelta records list "$collection" --all --fields "$field" --output json \
      | jq --arg k "$parent" --arg f "$field" --slurpfile keys "$WORK/keys.json" \
          '$keys[0] + {($k): (map({key: (.[$f] | tostring), value: .id}) | from_entries)}' \
          >"$WORK/keys.next.json"
    mv "$WORK/keys.next.json" "$WORK/keys.json"
  done
}

# Prints the {"field": "<parent>.<key field>"} map of the reference fields of a collection.
refs_of() {
  local collection="$1" ref
  for ref in "${REFS[@]}"; do
    [[ "${ref%%.*}" == "$collection" ]] || continue
    jq -n --arg f "$(cut -d= -f1 <<<"${ref#*.}")" --arg p "${ref#*=}" '{($f): $p}'
  done | jq -s 'add // {}'
}

for collection in "${SEED_ORDER[@]}"; do
  file="seeds/$collection.json"
  [[ -f "$file" ]] || { echo "install.sh: missing $file" >&2; exit 1; }
  expected="$(jq length "$file")"

  # Replace each reference value with the parent's id; an unknown key is an error, not a null.
  jq --argjson refs "$(refs_of "$collection")" --slurpfile keys "$WORK/keys.json" --arg c "$collection" '
    map(with_entries(
      if $refs[.key] and .value != null then
        .value as $v | $refs[.key] as $p
        | .value = ($keys[0][$p][$v | tostring]
            // error("\($c).\(.key): no \($p) record with value \"\($v)\""))
      else . end))' "$file" >"$WORK/resolved.json"

  created=0
  for ((start = 0; start < expected; start += BULK_MAX)); do
    jq --arg type "$collection" --argjson s "$start" --argjson n "$BULK_MAX" \
      '{"atomic:operations": (.[$s:$s + $n] | map({op: "add", data: {type: $type, attributes: .}}))}' \
      "$WORK/resolved.json" >"$WORK/ops.json"
    result="$(kelta records bulk --data "@$WORK/ops.json" --yes --output json)"
    created=$((created + $(jq '."atomic:results" | length' <<<"$result")))
  done

  if [[ "$created" -ne "$expected" ]]; then
    echo "install.sh: $collection: created $created records, expected $expected" >&2
    exit 1
  fi
  echo "seeded $collection: $created"
  index_keys "$collection"
done
