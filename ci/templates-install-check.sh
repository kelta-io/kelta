#!/usr/bin/env bash
# Installs every tenant template (examples/templates/<name>/) into one fresh tenant of the
# running CI stack and checks the result. Run by the "templates" job in
# .github/workflows/ci.yml after the stack is up and the platform admin's forced first
# sign-in is done:
#
#   bash ci/templates-install-check.sh          (on the runner)
#
# Docker on the k8s-runner-integration runner is remote, so nothing can be bind-mounted: the
# built kelta CLI (kelta-web, built by the job beforehand), the templates and this script are
# baked into a throwaway image. The image first runs this script's own tests
# (ci/templates-install-check.test.ts, against fake templates and a stand-in auth/gateway), then
# runs on the compose network, where it re-invokes this script with --in-container. Inside, for
# each template directory in name order, it:
#   1. signs in as the platform admin (POST /auth/direct-login) and exports the token as
#      KELTA_TOKEN with KELTA_URL/KELTA_TENANT, so the CLI never opens a browser login;
#   2. runs `timeout 300 bash install.sh` with stdin from /dev/null, so no prompt can wait;
#   3. reads each collection's record count (meta.totalCount of a page[size]=1 list) and
#      compares it with the length of seeds/<collection>.json.
# It exits non-zero at the first failed install, timeout or count mismatch.
set -euo pipefail

if [ "${1:-}" = "--in-container" ]; then
  TEMPLATES_DIR="${2:?templates directory}"
  AUTH_URL="${AUTH_URL:-http://kelta-auth:8081}"
  GATEWAY_URL="${GATEWAY_URL:-http://kelta-gateway:8080}"
  TENANT_SLUG="${TENANT_SLUG:-default}"
  ADMIN_USERNAME="${ADMIN_USERNAME:-admin@kelta.local}"
  INSTALL_TIMEOUT="${INSTALL_TIMEOUT:-300}"
  : "${ADMIN_PASSWORD:?ADMIN_PASSWORD must be set}"

  fail() {
    echo "::error::$*" >&2
    exit 1
  }

  sign_in() {
    local token
    token=$(curl -fsS -m 30 -X POST "$AUTH_URL/auth/direct-login" \
      -H "Content-Type: application/json" \
      -d "$(jq -cn --arg u "$ADMIN_USERNAME" --arg p "$ADMIN_PASSWORD" --arg t "$TENANT_SLUG" \
        '{username: $u, password: $p, tenantSlug: $t}')" | jq -r '.access_token // empty') \
      || fail "direct login to $AUTH_URL failed"
    [ -n "$token" ] || fail "direct login did not return an access_token"
    printf '%s' "$token"
  }

  record_count() {
    curl -fsS -m 30 -H "Authorization: Bearer $KELTA_TOKEN" \
      "$GATEWAY_URL/$TENANT_SLUG/api/$1?page%5Bsize%5D=1" | jq -e '.meta.totalCount'
  }

  shopt -s nullglob
  templates=("$TEMPLATES_DIR"/*/)
  [ "${#templates[@]}" -gt 0 ] || fail "no templates under $TEMPLATES_DIR"

  for template in "${templates[@]}"; do
    template="${template%/}"
    name="$(basename "$template")"
    [ -f "$template/install.sh" ] || fail "$name: no install.sh"
    echo "::group::template $name"

    KELTA_TOKEN="$(sign_in)"
    export KELTA_TOKEN KELTA_URL="$GATEWAY_URL" KELTA_TENANT="$TENANT_SLUG"

    start=$(date +%s)
    set +e
    timeout -k 10 "$INSTALL_TIMEOUT" bash "$template/install.sh" </dev/null
    status=$?
    set -e
    elapsed=$(($(date +%s) - start))
    if [ "$status" -eq 124 ] || [ "$status" -eq 137 ]; then
      fail "$name: install.sh did not finish within ${INSTALL_TIMEOUT}s"
    elif [ "$status" -ne 0 ]; then
      fail "$name: install.sh exited $status after ${elapsed}s"
    fi
    echo "$name: install.sh exited 0 in ${elapsed}s"

    for seed in "$template"/seeds/*.json; do
      collection="$(basename "$seed" .json)"
      expected="$(jq length "$seed")"
      actual="$(record_count "$collection")" || fail "$name: could not read the record count of $collection"
      echo "$name/$collection: expected $expected, found $actual"
      [ "$actual" -eq "$expected" ] \
        || fail "$name: $collection holds $actual records, seeds/$collection.json has $expected"
    done
    echo "::endgroup::"
  done
  echo "All ${#templates[@]} template(s) installed and their seed counts match"
  exit 0
fi

COMPOSE_PROJECT_NAME="${COMPOSE_PROJECT_NAME:?COMPOSE_PROJECT_NAME must be set}"
: "${ADMIN_PASSWORD:?ADMIN_PASSWORD must be set}"
NETWORK="${COMPOSE_PROJECT_NAME}_kelta-network"
IMAGE="kelta-templates-runner:${COMPOSE_PROJECT_NAME}"
CONTAINER="${COMPOSE_PROJECT_NAME}_templates"
[ -f kelta-web/packages/cli/dist/index.js ] || { echo "kelta CLI not built (kelta-web/packages/cli/dist)" >&2; exit 1; }

# --load: the job selects the persistent docker-container builder (BUILDX_BUILDER=kelta-ci),
# which keeps a build result in its own cache unless told to load it into the daemon; a
# per-run tag that was never loaded makes `docker run` fail with exit 125.
docker buildx build --load -q -t "$IMAGE" -f - . <<'DOCKERFILE'
FROM node:24-alpine
RUN apk add --no-cache bash coreutils curl jq
COPY kelta-web /kelta-web
RUN printf '#!/bin/sh\nexec node /kelta-web/packages/cli/dist/index.js "$@"\n' > /usr/local/bin/kelta \
 && chmod +x /usr/local/bin/kelta
COPY examples/templates /templates
COPY ci/templates-install-check.sh ci/templates-install-check.test.ts /ci/
DOCKERFILE

docker run --rm --memory 1g --pids-limit 512 "$IMAGE" node --test /ci/templates-install-check.test.ts </dev/null

# The runner's Docker daemon is the node's own, outside any Kubernetes limit: cap memory and
# process count so a runaway install script fails this job instead of the node (a recursive
# shell function in an earlier template run took a node down this way).
docker rm -f "$CONTAINER" >/dev/null 2>&1 || true
docker run --rm --name "$CONTAINER" --network "$NETWORK" \
  --memory 1g --memory-swap 1g --pids-limit 512 \
  -e ADMIN_PASSWORD \
  "$IMAGE" bash /ci/templates-install-check.sh --in-container /templates </dev/null
