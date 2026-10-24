#!/usr/bin/env bash
# Export → apply → re-export round trip of every tenant template (KLT-666). Run by the
# "templates" job in .github/workflows/ci.yml right after ci/templates-install-check.sh, against
# the tenant that script installed every examples/templates/<name>/ into:
#
#   bash ci/templates-roundtrip-check.sh          (on the runner)
#
# It reuses the runner image templates-install-check.sh built (same tag; the kelta CLI, the
# templates and these ci/ files are baked in), first runs this script's own tests in it
# (ci/templates-roundtrip-check.test.ts), then runs on the compose network, where it re-invokes
# this script with --in-container. Inside, for each template directory in name order, it:
#   1. exports the template's metadata from the tenant it was installed into — `kelta metadata
#      export --collections <its metadata.json's COLLECTION names>` (the server adds the rules,
#      layouts, flows, picklists, list views and dashboards that hang off them);
#   2. creates an empty sandbox of that tenant (cloneMetadata=false, as templates-install-check.sh
#      does), signs in as its one-time admin and applies the export there with `kelta metadata
#      apply`; a failed item is reported, and the diff still runs;
#   3. exports the same collections from the sandbox;
#   4. normalises both exports with ci/templates-roundtrip-normalise.jq (ids and timestamps only),
#      drops the package types listed in ci/templates-roundtrip-exclusions.txt from both — and
#      prints each excluded type with its task and item counts — and diffs them; any difference
#      fails the template and is printed.
# Every template is checked even after one fails, so one run shows every type that does not
# round-trip; the run exits non-zero if any template failed.
#
#   bash ci/templates-roundtrip-check.sh --compare <export> <re-export> [label]
#
# runs step 4 alone on two export files (used by the tests).
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
NORMALISE="${NORMALISE:-$HERE/templates-roundtrip-normalise.jq}"
EXCLUSIONS="${EXCLUSIONS:-$HERE/templates-roundtrip-exclusions.txt}"

fail() {
  echo "::error::$*" >&2
  exit 1
}

# Reads EXCLUSIONS into EXCLUDED_TYPES / EXCLUDED_NOTES; a line must be `<TYPE> <TASK> [why]`.
EXCLUDED_TYPES=()
EXCLUDED_NOTES=()
read_exclusions() {
  local line type task reason line_no=0
  [ -f "$EXCLUSIONS" ] || fail "exclusions file $EXCLUSIONS not found"
  while IFS= read -r line || [ -n "$line" ]; do
    line_no=$((line_no + 1))
    [[ "$line" =~ ^[[:space:]]*(#|$) ]] && continue
    read -r type task reason <<<"$line"
    [[ "$type" =~ ^[A-Z][A-Z_]*$ ]] \
      || fail "$EXCLUSIONS:$line_no: '$type' is not a package type (e.g. REPORT)"
    [[ "${task:-}" =~ ^[A-Z][A-Z0-9]*-[0-9]+$ ]] \
      || fail "$EXCLUSIONS:$line_no: $type names no task that removes it (expected e.g. '$type KLT-123 <why>')"
    EXCLUDED_TYPES+=("$type")
    EXCLUDED_NOTES+=("$task${reason:+: $reason}")
  done <"$EXCLUSIONS"
}

# compare <export> <re-export> <label> — prints the excluded types and the diff; returns 1 on
# any difference.
compare() {
  local source="$1" target="$2" label="$3" excluded work i type side
  excluded="$(jq -cn '$ARGS.positional' --args ${EXCLUDED_TYPES[@]+"${EXCLUDED_TYPES[@]}"})"
  if [ "${#EXCLUDED_TYPES[@]}" -eq 0 ]; then
    echo "$label: no package types excluded from the diff ($(basename "$EXCLUSIONS") lists none)"
  fi
  for i in "${!EXCLUDED_TYPES[@]}"; do
    type="${EXCLUDED_TYPES[$i]}"
    echo "$label: $type excluded from the diff (${EXCLUDED_NOTES[$i]}) —" \
      "$(jq --arg t "$type" '[.items[] | select(.type == $t)] | length' "$source") item(s) in the export," \
      "$(jq --arg t "$type" '[.items[] | select(.type == $t)] | length' "$target") in the re-export"
  done

  work="$(mktemp -d)"
  for side in source target; do
    jq -f "$NORMALISE" "${!side}" \
      | jq -S --argjson ex "$excluded" '.items |= map(select(.type as $t | $ex | index($t) | not))' \
        >"$work/$side.json" \
      || { echo "::error::$label: could not normalise ${!side}" >&2; rm -rf "$work"; return 1; }
  done
  if diff -u -L "$label: export from the installed tenant" \
    -L "$label: re-export from the empty sandbox" \
    "$work/source.json" "$work/target.json" >"$work/diff"; then
    echo "$label: the re-export matches the export ($(jq '.items | length' "$work/source.json") item(s) compared)"
    rm -rf "$work"
    return 0
  fi
  echo "::error::$label: the re-export differs from the export (normalised; - export, + re-export):" >&2
  cat "$work/diff" >&2
  rm -rf "$work"
  return 1
}

if [ "${1:-}" = "--compare" ]; then
  read_exclusions
  compare "${2:?export file}" "${3:?re-export file}" "${4:-compare}"
  exit
fi

if [ "${1:-}" = "--in-container" ]; then
  TEMPLATES_DIR="${2:?templates directory}"
  AUTH_URL="${AUTH_URL:-http://kelta-auth:8081}"
  GATEWAY_URL="${GATEWAY_URL:-http://kelta-gateway:8080}"
  TENANT_SLUG="${TENANT_SLUG:-default}"
  ADMIN_USERNAME="${ADMIN_USERNAME:-admin@kelta.local}"
  SANDBOX_PREFIX="${SANDBOX_PREFIX:-roundtrip-}"
  READY_ATTEMPTS="${READY_ATTEMPTS:-60}"
  PACKAGE_VERSION="1.0.0"
  : "${ADMIN_PASSWORD:?ADMIN_PASSWORD must be set}"
  read_exclusions

  error() {
    echo "::error::$*" >&2
    return 1
  }

  # sign_in [username password tenant-slug] — defaults to the platform admin of TENANT_SLUG.
  sign_in() {
    local token
    token=$(curl -fsS -m 30 -X POST "$AUTH_URL/auth/direct-login" \
      -H "Content-Type: application/json" \
      -d "$(jq -cn --arg u "${1:-$ADMIN_USERNAME}" --arg p "${2:-$ADMIN_PASSWORD}" \
        --arg t "${3:-$TENANT_SLUG}" '{username: $u, password: $p, tenantSlug: $t}')" \
      | jq -r '.access_token // empty') || token=""
    [ -n "$token" ] || error "direct login to $AUTH_URL as ${1:-$ADMIN_USERNAME} returned no access_token"
    printf '%s' "$token"
  }

  # export_to <file> <collections> <name> — `kelta metadata export` from KELTA_TENANT.
  export_to() {
    kelta metadata export --collections "$2" --name "$3" --version "$PACKAGE_VERSION" \
      --out "$1" --output json </dev/null >/dev/null && [ -s "$1" ]
  }

  # roundtrip <template dir> — steps 1-4 for one template; returns 1 on any failure.
  roundtrip() {
    local template="$1" name collections work parent_token created_status created attrs \
      sandbox_slug ready report status i ok=0
    name="$(basename "$template")"
    [ -f "$template/metadata.json" ] || { error "$name: no metadata.json"; return 1; }
    collections="$(jq -r '[.items[] | select(.type == "COLLECTION") | .data.name] | join(",")' \
      "$template/metadata.json")" || { error "$name: metadata.json is not a package"; return 1; }
    [ -n "$collections" ] || { error "$name: metadata.json has no COLLECTION items"; return 1; }
    work="$(mktemp -d)"

    parent_token="$(sign_in)" || return 1
    export KELTA_URL="$GATEWAY_URL" KELTA_TENANT="$TENANT_SLUG" KELTA_TOKEN="$parent_token"
    export_to "$work/export.json" "$collections" "$name" \
      || { error "$name: kelta metadata export from $TENANT_SLUG failed"; return 1; }
    echo "$name: exported $(jq '.items | length' "$work/export.json") item(s) of $collections from $TENANT_SLUG"

    created_status=$(curl -sS -m 120 -o "$work/created.json" -w '%{http_code}' \
      -X POST "$GATEWAY_URL/$TENANT_SLUG/api/environments" \
      -H "Authorization: Bearer $parent_token" -H "Content-Type: application/json" \
      -d "$(jq -cn --arg n "$SANDBOX_PREFIX$name" \
        '{data: {type: "environments", attributes: {name: $n, type: "SANDBOX", cloneMetadata: false}}}')") \
      || created_status=000
    created="$(cat "$work/created.json" 2>/dev/null || true)"
    [ "$created_status" = "202" ] \
      || { error "$name: creating the empty sandbox $SANDBOX_PREFIX$name answered HTTP $created_status: $created"; return 1; }
    attrs="$(jq -c '.data.attributes' <<<"$created")"
    [ "$(jq -r '.clone_mode' <<<"$attrs")" = "EMPTY" ] \
      || { error "$name: sandbox clone_mode is $(jq -r '.clone_mode' <<<"$attrs"), expected EMPTY"; return 1; }
    sandbox_slug="$(jq -r '.sandboxSlug' <<<"$attrs")"
    KELTA_TOKEN="$(sign_in "$(jq -r '.adminUsername' <<<"$attrs")" \
      "$(jq -r '.adminInitialPassword' <<<"$attrs")" "$sandbox_slug")" || return 1
    KELTA_TENANT="$sandbox_slug"
    for ((i = 0; i < READY_ATTEMPTS; i++)); do
      ready="$(curl -sS -m 30 -o /dev/null -w '%{http_code}' -H "Authorization: Bearer $KELTA_TOKEN" \
        "$GATEWAY_URL/$sandbox_slug/api/collections" || true)"
      [ "$ready" = "200" ] && break
      sleep 1
    done
    [ "$ready" = "200" ] \
      || { error "$name: sandbox $sandbox_slug: GET /api/collections answered $ready, not 200"; return 1; }
    echo "$name: created empty sandbox $sandbox_slug"

    set +e
    report="$(kelta metadata apply "$work/export.json" --yes --output json </dev/null)"
    status=$?
    set -e
    if [ "$status" -eq 0 ] && jq -e '.failed == 0' <<<"$report" >/dev/null 2>&1; then
      echo "$name: applied the export to $sandbox_slug: $(jq -c '{created, updated, skipped, failed}' <<<"$report")"
    else
      ok=1
      echo "::error::$name: applying the export to $sandbox_slug failed (exit $status): $(jq -c '{created, updated, skipped, failed}' <<<"$report" 2>/dev/null || echo "unparseable report")" >&2
      jq -r '.items // [] | .[] | select(.action == "FAILED") | "  \(.type) \(.naturalKey): \(.error // "FAILED")"' \
        <<<"$report" >&2 2>/dev/null || printf '%s\n' "$report" >&2
    fi

    export_to "$work/re-export.json" "$collections" "$name" \
      || { error "$name: kelta metadata export from $sandbox_slug failed"; return 1; }
    compare "$work/export.json" "$work/re-export.json" "$name" || ok=1
    rm -rf "$work"
    return "$ok"
  }

  shopt -s nullglob
  templates=("$TEMPLATES_DIR"/*/)
  [ "${#templates[@]}" -gt 0 ] || fail "no templates under $TEMPLATES_DIR"

  failed=()
  for template in "${templates[@]}"; do
    template="${template%/}"
    echo "::group::round trip $(basename "$template")"
    roundtrip "$template" || failed+=("$(basename "$template")")
    echo "::endgroup::"
  done
  [ "${#failed[@]}" -eq 0 ] \
    || fail "export → apply → re-export did not round-trip for: ${failed[*]} (diffs above)"
  echo "All ${#templates[@]} template(s) round-trip: export → apply to an empty sandbox → re-export matches"
  exit 0
fi

COMPOSE_PROJECT_NAME="${COMPOSE_PROJECT_NAME:?COMPOSE_PROJECT_NAME must be set}"
: "${ADMIN_PASSWORD:?ADMIN_PASSWORD must be set}"
NETWORK="${COMPOSE_PROJECT_NAME}_kelta-network"
IMAGE="kelta-templates-runner:${COMPOSE_PROJECT_NAME}"
CONTAINER="${COMPOSE_PROJECT_NAME}_roundtrip"
docker image inspect "$IMAGE" >/dev/null 2>&1 \
  || { echo "image $IMAGE not found: ci/templates-install-check.sh builds it and must run first" >&2; exit 1; }

docker run --rm --memory 1g --pids-limit 512 "$IMAGE" node --test /ci/templates-roundtrip-check.test.ts </dev/null

# Same caps as the install check's container (see there).
docker rm -f "$CONTAINER" >/dev/null 2>&1 || true
docker run --rm --name "$CONTAINER" --network "$NETWORK" \
  --memory 1g --memory-swap 1g --pids-limit 512 \
  -e ADMIN_PASSWORD \
  "$IMAGE" bash /ci/templates-roundtrip-check.sh --in-container /templates </dev/null
