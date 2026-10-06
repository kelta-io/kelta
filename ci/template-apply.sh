#!/usr/bin/env bash
# Installs a tenant template (examples/templates/<name>/) into the running quickstart
# stack and checks the result — the "quickstart" job in .github/workflows/ci.yml runs it
# after the timed smoke check, once per template.
#
#   bash ci/template-apply.sh examples/templates/crm          (on the runner)
#
# Docker on the k8s-runner-integration runner is remote, so nothing can be bind-mounted:
# the kelta CLI (bundled into one file from kelta-web, built by the job beforehand), the
# templates and this script are baked into a small throwaway node image that runs on the
# compose network, where it re-invokes this script with --in-container. Inside, it signs in
# as the platform admin, checks that `kelta metadata diff` previews only creates on the fresh
# tenant, runs the template's install.sh and asserts every collection holds exactly the
# number of records the template's seeds/*.json add.
#
# Every docker, kelta and curl call is time-bounded, and a failure prints
# "template check failed at step: <name>", so a hang fails here in minutes and names the
# command instead of running into the job timeout.
set -euo pipefail
exec </dev/null

CURRENT_STEP="preflight"
step() { CURRENT_STEP="$*"; echo "--- $*"; }
on_exit() {
  local rc=$?
  [ "$rc" -eq 0 ] || echo "::error::template check failed at step: $CURRENT_STEP (exit $rc)" >&2
}
trap on_exit EXIT

if [ "${1:-}" = "--in-container" ]; then
  TEMPLATE_DIR="${2:?template directory}"
  AUTH_URL="${AUTH_URL:-http://kelta-auth:8081}"
  TENANT_SLUG="${TENANT_SLUG:-default}"
  ADMIN_USERNAME="${ADMIN_USERNAME:-admin@kelta.local}"
  : "${ADMIN_PASSWORD:?ADMIN_PASSWORD must be set}"

  step "direct login as $ADMIN_USERNAME"
  KELTA_TOKEN=$(curl -fsS -m 30 --connect-timeout 10 -X POST "$AUTH_URL/auth/direct-login" \
    -H "Content-Type: application/json" \
    -d "$(jq -cn --arg u "$ADMIN_USERNAME" --arg p "$ADMIN_PASSWORD" --arg t "$TENANT_SLUG" \
      '{username: $u, password: $p, tenantSlug: $t}')" | jq -r '.access_token // empty')
  [ -n "$KELTA_TOKEN" ] || { echo "Direct login did not return an access_token" >&2; exit 1; }
  export KELTA_TOKEN KELTA_TENANT="$TENANT_SLUG" KELTA_URL="${GATEWAY_URL:-http://kelta-gateway:8080}"

  cd "$TEMPLATE_DIR"
  step "kelta metadata diff package.json"
  timeout -k 10 90 kelta metadata diff package.json --output json > /tmp/diff.json
  jq -c '{creates: (.creates | length), updates: (.updates | length), conflicts: (.conflicts | length)}' /tmp/diff.json
  if ! jq -e '(.creates | length) > 0 and (.updates | length) == 0 and (.conflicts | length) == 0' /tmp/diff.json >/dev/null; then
    echo "::error::metadata diff on a fresh tenant must preview only creates" >&2
    jq '{updates, conflicts}' /tmp/diff.json >&2
    exit 1
  fi

  step "install.sh"
  START=$(date +%s)
  timeout -k 30 300 ./install.sh
  echo "install.sh wall-clock: $(( $(date +%s) - START ))s"

  step "seeded record counts"
  failed=0
  while read -r collection expected; do
    actual=$(timeout -k 10 30 kelta records list "$collection" --all --quiet | grep -c . || true)
    echo "$collection: expected $expected, found $actual"
    [ "$actual" = "$expected" ] || failed=1
  done < <(jq -r '."atomic:operations"[] | select(.op == "add") | .data.type' seeds/*.json | sort | uniq -c | awk '{print $2, $1}')
  if [ "$failed" -ne 0 ]; then
    echo "::error::seeded record counts do not match the template's seeds" >&2
    exit 1
  fi
  step "done"
  echo "Template $(basename "$TEMPLATE_DIR") installed cleanly"
  exit 0
fi

TEMPLATE="${1:?usage: ci/template-apply.sh examples/templates/<name>}"
COMPOSE_PROJECT_NAME="${COMPOSE_PROJECT_NAME:?COMPOSE_PROJECT_NAME must be set}"
NETWORK="${COMPOSE_PROJECT_NAME}_kelta-network"
ADMIN_PASSWORD_FILE="${ADMIN_PASSWORD_FILE:?ADMIN_PASSWORD_FILE must be set (written by ci/quickstart-run.sh)}"
IMAGE="kelta-template-runner:${COMPOSE_PROJECT_NAME}"
CONTAINER="kelta-template-$(basename "$TEMPLATE")-${COMPOSE_PROJECT_NAME}"
[ -f "$TEMPLATE/install.sh" ] || { echo "$TEMPLATE has no install.sh" >&2; exit 1; }
[ -f kelta-web/packages/cli/dist/index.js ] || { echo "kelta CLI not built (kelta-web/packages/cli/dist)" >&2; exit 1; }

CONTEXT=$(mktemp -d)
cleanup() {
  on_exit
  timeout 30 docker rm -f "$CONTAINER" >/dev/null 2>&1 || true
  rm -rf "$CONTEXT"
}
trap cleanup EXIT

# One-file CLI: the build context stays a few MB instead of kelta-web with its
# node_modules (~400 MB), which the image used to copy in on every run.
step "bundle the kelta CLI"
timeout 60 kelta-web/node_modules/.bin/esbuild kelta-web/packages/cli/dist/index.js \
  --bundle --platform=node --target=node20 --format=esm --log-level=warning \
  --banner:js="import { createRequire as __kcr } from 'node:module'; const require = __kcr(import.meta.url);" \
  --outfile="$CONTEXT/kelta.mjs"
cp -R examples/templates "$CONTEXT/templates"
cp ci/template-apply.sh "$CONTEXT/template-apply.sh"
cat > "$CONTEXT/Dockerfile" <<'DOCKERFILE'
FROM node:20-alpine
RUN apk add --no-cache bash coreutils curl jq
COPY kelta.mjs /opt/kelta/kelta.mjs
RUN printf '#!/bin/sh\nexec node /opt/kelta/kelta.mjs "$@"\n' > /usr/local/bin/kelta \
 && chmod +x /usr/local/bin/kelta
COPY templates /templates
COPY template-apply.sh /usr/local/bin/template-apply.sh
DOCKERFILE

# --load: the job selects the persistent docker-container builder (BUILDX_BUILDER=kelta-ci),
# which keeps build results in its own cache unless told to load them into the daemon.
step "build the template-runner image"
timeout -k 10 120 docker buildx build --load --progress=plain -t "$IMAGE" "$CONTEXT"

# Killing the docker client does not stop the container, so it is named and removed by
# cleanup(); the in-container script's own timeouts fire well inside this limit.
step "run $TEMPLATE in the template-runner container"
timeout -k 10 450 docker run --rm --name "$CONTAINER" --network "$NETWORK" \
  -e ADMIN_PASSWORD="$(cat "$ADMIN_PASSWORD_FILE")" -e KELTA_UPDATE_CHECK=0 \
  "$IMAGE" bash /usr/local/bin/template-apply.sh --in-container "/templates/${TEMPLATE#examples/templates/}"
step "done"
