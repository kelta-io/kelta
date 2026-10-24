#!/usr/bin/env bash
# Runs the blocks of the "your first app" tutorial that are fenced ```bash runnable
# (kelta-marketing/src/content/docs/getting-started/first-app.md, Part 2) against a tenant
# created fresh for the run. Run by the "templates" job in .github/workflows/ci.yml after the
# stack is up and the platform admin's forced first sign-in is done:
#
#   bash ci/first-app-runnable.sh                        (on the runner)
#   bash ci/first-app-runnable.sh --extract <doc.md>     (print the runnable blocks)
#
# Only blocks whose fence info string carries the word `runnable` run; every other block of
# the page (```bash without the marker, ```json, …) is illustrative and stays out.
#
# Docker on the k8s-runner-integration runner is remote, so nothing can be bind-mounted: the
# built kelta CLI (kelta-web, built by the job beforehand), the page and this script are baked
# into a throwaway image that runs on the compose network, where this script re-invokes itself
# with --in-container. Inside, it:
#   1. signs in as the platform admin (POST /auth/direct-login) and creates an empty sandbox of
#      TENANT_SLUG (cloneMetadata=false) — a new tenant holding none of the collections the
#      templates job installed, so `customers`/`invoices` cannot collide;
#   2. signs in as the sandbox's one-time admin and exports KELTA_URL, KELTA_TENANT and
#      KELTA_TOKEN, so the CLI never opens a browser login;
#   3. writes the runnable blocks, in document order, into one script and runs it with
#      `bash -euo pipefail` in a new temp directory (the blocks write their JSON files there),
#      echoing each block's first line before the block runs. The first failing command
#      fails the run;
#   4. runs every `kelta … apply` command of those blocks a second time with --output json and
#      fails unless each reports nothing created, updated or deleted — the page promises a
#      second run reports `unchanged`.
set -euo pipefail

# extract_blocks <doc.md> <out-dir> — writes each runnable block to <out-dir>/<n>.sh
# (n = 1, 2, … in document order) and prints the number of blocks written.
extract_blocks() {
  awk -v out="$2" '
    !open && /^[ \t]*(```|~~~)/ {
      line = $0
      sub(/^[ \t]+/, "", line)
      match(line, /^(`+|~+)/)
      fence = substr(line, 1, RLENGTH)
      info = " " substr(line, RLENGTH + 1) " "
      gsub(/[ \t]+/, " ", info)
      open = 1
      keep = index(info, " runnable ") > 0
      if (keep) file = out "/" (++n) ".sh"
      next
    }
    open {
      line = $0
      gsub(/^[ \t]+|[ \t]+$/, "", line)
      if (length(line) >= length(fence) && line ~ ("^" substr(fence, 1, 1) "+$")) {
        open = 0
        if (keep) close(file)
        next
      }
      if (keep) print > file
    }
    END { print n + 0 }
  ' "$1"
}

# apply_commands <block-file>... — prints every `kelta <group> apply …` command of the blocks,
# with backslash-continued lines joined into one.
apply_commands() {
  awk '
    { if (sub(/\\$/, "")) { buf = buf $0; next } line = buf $0; buf = "" }
    line ~ /^[ \t]*kelta [a-z-]+ apply( |$)/ { print line }
  ' "$@"
}

fail() {
  echo "::error::$*" >&2
  exit 1
}

if [ "${1:-}" = "--extract" ]; then
  doc="${2:?markdown file}"
  [ -f "$doc" ] || fail "no such file: $doc"
  dir="$(mktemp -d)"
  trap 'rm -rf "$dir"' EXIT
  count="$(extract_blocks "$doc" "$dir")"
  for ((i = 1; i <= count; i++)); do
    echo "### runnable block $i"
    cat "$dir/$i.sh"
  done
  exit 0
fi

# --run <doc.md> — steps 3 and 4 above, with KELTA_URL/KELTA_TENANT/KELTA_TOKEN already set.
if [ "${1:-}" = "--run" ]; then
  doc="$(cd "$(dirname "${2:?markdown file}")" && pwd)/$(basename "$2")"
  [ -f "$doc" ] || fail "no such file: $doc"
  : "${KELTA_URL:?KELTA_URL must be set}" "${KELTA_TENANT:?KELTA_TENANT must be set}"
  : "${KELTA_TOKEN:?KELTA_TOKEN must be set}"
  blocks="$(mktemp -d)"
  workdir="$(mktemp -d)"
  trap 'rm -rf "$blocks" "$workdir"' EXIT
  count="$(extract_blocks "$doc" "$blocks")"
  [ "$count" -gt 0 ] || fail "no \`\`\`bash runnable block in $doc"

  {
    for ((i = 1; i <= count; i++)); do
      printf 'printf "%%s\\n" %q\n' "==> block $i/$count: $(head -n 1 "$blocks/$i.sh")"
      cat "$blocks/$i.sh"
    done
  } >"$blocks/all.sh"

  echo "Running $count runnable block(s) of $doc against tenant $KELTA_TENANT in $workdir"
  set +e
  (cd "$workdir" && bash -euo pipefail "$blocks/all.sh" </dev/null)
  status=$?
  set -e
  [ "$status" -eq 0 ] || fail "a runnable block of $doc failed (exit $status); the last ==> line above names the block"
  echo "All $count runnable block(s) ran"

  applies=()
  while IFS= read -r command; do applies+=("$command"); done < <(
    files=()
    for ((i = 1; i <= count; i++)); do files+=("$blocks/$i.sh"); done
    apply_commands "${files[@]}"
  )
  for command in "${applies[@]}"; do
    echo "==> again: $command"
    set +e
    report="$(cd "$workdir" && bash -euo pipefail -c "$command --output json" </dev/null)"
    status=$?
    set -e
    [ "$status" -eq 0 ] || fail "second run of \`$command\` exited $status"
    jq -e '(.action // "unchanged") == "unchanged"
      and ((.created // 0) + (.updated // 0) + (.deleted // 0)) == 0' <<<"$report" >/dev/null 2>&1 \
      || fail "second run of \`$command\` was not unchanged: $(jq -c . <<<"$report" 2>/dev/null || printf '%s' "$report")"
    echo "unchanged: $(jq -c . <<<"$report")"
  done
  echo "Every apply command (${#applies[@]}) reported unchanged on its second run"
  exit 0
fi

if [ "${1:-}" = "--in-container" ]; then
  doc="${2:?markdown file}"
  AUTH_URL="${AUTH_URL:-http://kelta-auth:8081}"
  GATEWAY_URL="${GATEWAY_URL:-http://kelta-gateway:8080}"
  TENANT_SLUG="${TENANT_SLUG:-default}"
  ADMIN_USERNAME="${ADMIN_USERNAME:-admin@kelta.local}"
  SANDBOX_NAME="${SANDBOX_NAME:-first-app}"
  READY_ATTEMPTS="${READY_ATTEMPTS:-60}"
  : "${ADMIN_PASSWORD:?ADMIN_PASSWORD must be set}"

  # sign_in <username> <password> <tenant-slug>
  sign_in() {
    local token
    token=$(curl -fsS -m 30 -X POST "$AUTH_URL/auth/direct-login" \
      -H "Content-Type: application/json" \
      -d "$(jq -cn --arg u "$1" --arg p "$2" --arg t "$3" \
        '{username: $u, password: $p, tenantSlug: $t}')" \
      | jq -r '.access_token // empty') \
      || fail "direct login of $1 to $3 failed"
    [ -n "$token" ] || fail "direct login of $1 to $3 did not return an access_token"
    printf '%s' "$token"
  }

  admin_token="$(sign_in "$ADMIN_USERNAME" "$ADMIN_PASSWORD" "$TENANT_SLUG")"
  create_body="$(mktemp)"
  created_status=$(curl -sS -m 120 -o "$create_body" -w '%{http_code}' \
    -X POST "$GATEWAY_URL/$TENANT_SLUG/api/environments" \
    -H "Authorization: Bearer $admin_token" -H "Content-Type: application/json" \
    -d "$(jq -cn --arg n "$SANDBOX_NAME" \
      '{data: {type: "environments", attributes: {name: $n, type: "SANDBOX", cloneMetadata: false}}}')") \
    || created_status=000
  created="$(cat "$create_body")"
  rm -f "$create_body"
  [ "$created_status" = "202" ] \
    || fail "creating the sandbox $SANDBOX_NAME answered HTTP $created_status: $created"
  attrs="$(jq -c '.data.attributes' <<<"$created")"
  [ "$(jq -r '.status' <<<"$attrs")" = "ACTIVE" ] \
    || fail "sandbox $SANDBOX_NAME: status is $(jq -r '.status' <<<"$attrs"), expected ACTIVE"
  sandbox_slug="$(jq -r '.sandboxSlug' <<<"$attrs")"
  echo "created the empty sandbox $sandbox_slug for the run"

  KELTA_TOKEN="$(sign_in "$(jq -r '.adminUsername' <<<"$attrs")" \
    "$(jq -r '.adminInitialPassword' <<<"$attrs")" "$sandbox_slug")"
  export KELTA_URL="$GATEWAY_URL" KELTA_TENANT="$sandbox_slug" KELTA_TOKEN
  ready=""
  for ((i = 0; i < READY_ATTEMPTS; i++)); do
    ready="$(curl -sS -m 30 -o /dev/null -w '%{http_code}' \
      -H "Authorization: Bearer $KELTA_TOKEN" "$GATEWAY_URL/$sandbox_slug/api/collections" || true)"
    [ "$ready" = "200" ] && break
    sleep 1
  done
  [ "$ready" = "200" ] \
    || fail "sandbox $sandbox_slug: GET /api/collections answered $ready, not 200, with its admin's token"

  exec bash "$0" --run "$doc"
fi

COMPOSE_PROJECT_NAME="${COMPOSE_PROJECT_NAME:?COMPOSE_PROJECT_NAME must be set}"
: "${ADMIN_PASSWORD:?ADMIN_PASSWORD must be set}"
DOC="${FIRST_APP_DOC:-kelta-marketing/src/content/docs/getting-started/first-app.md}"
NETWORK="${COMPOSE_PROJECT_NAME}_kelta-network"
IMAGE="kelta-first-app-runner:${COMPOSE_PROJECT_NAME}"
CONTAINER="${COMPOSE_PROJECT_NAME}_first_app"
[ -f kelta-web/packages/cli/dist/index.js ] || fail "kelta CLI not built (kelta-web/packages/cli/dist)"
[ -f "$DOC" ] || fail "no such file: $DOC"

# The first four instructions match ci/templates-install-check.sh's image, so the job's
# persistent builder serves them from cache. --load: see that script.
docker buildx build --load -q -t "$IMAGE" --build-arg "DOC=$DOC" -f - . <<'DOCKERFILE'
FROM node:24-alpine
RUN apk add --no-cache bash coreutils curl jq
COPY kelta-web /kelta-web
RUN printf '#!/bin/sh\nexec node /kelta-web/packages/cli/dist/index.js "$@"\n' > /usr/local/bin/kelta \
 && chmod +x /usr/local/bin/kelta
ARG DOC
COPY ${DOC} /doc/first-app.md
COPY ci/first-app-runnable.sh /ci/
DOCKERFILE

docker rm -f "$CONTAINER" >/dev/null 2>&1 || true
docker run --rm --name "$CONTAINER" --network "$NETWORK" \
  --memory 1g --memory-swap 1g --pids-limit 512 \
  -e ADMIN_PASSWORD -e SANDBOX_NAME -e TENANT_SLUG \
  "$IMAGE" bash /ci/first-app-runnable.sh --in-container /doc/first-app.md </dev/null
