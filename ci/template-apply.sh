#!/usr/bin/env bash
# Installs a tenant template (examples/templates/<name>/) into the running quickstart
# stack and checks the result — the "quickstart" job in .github/workflows/ci.yml runs it
# after the timed smoke check, once per template.
#
#   bash ci/template-apply.sh examples/templates/crm          (on the runner)
#
# Docker on the k8s-runner-integration runner is remote, so nothing can be bind-mounted:
# the built kelta CLI (kelta-web, built by the job beforehand), the templates and this
# script are baked into a throwaway node image that runs on the compose network, where it
# re-invokes this script with --in-container. Inside, it signs in as the platform admin,
# checks that `kelta metadata diff` previews only creates on the fresh tenant, runs the
# template's install.sh (which exits non-zero on the first error) and asserts every
# collection holds exactly the number of records the template's seeds/*.json add.
set -euo pipefail

if [ "${1:-}" = "--in-container" ]; then
  TEMPLATE_DIR="${2:?template directory}"
  AUTH_URL="${AUTH_URL:-http://kelta-auth:8081}"
  TENANT_SLUG="${TENANT_SLUG:-default}"
  ADMIN_USERNAME="${ADMIN_USERNAME:-admin@kelta.local}"
  : "${ADMIN_PASSWORD:?ADMIN_PASSWORD must be set}"

  KELTA_TOKEN=$(curl -fsS -X POST "$AUTH_URL/auth/direct-login" \
    -H "Content-Type: application/json" \
    -d "$(jq -cn --arg u "$ADMIN_USERNAME" --arg p "$ADMIN_PASSWORD" --arg t "$TENANT_SLUG" \
      '{username: $u, password: $p, tenantSlug: $t}')" | jq -r '.access_token // empty')
  [ -n "$KELTA_TOKEN" ] || { echo "Direct login did not return an access_token" >&2; exit 1; }
  export KELTA_TOKEN KELTA_TENANT="$TENANT_SLUG" KELTA_URL="${GATEWAY_URL:-http://kelta-gateway:8080}"

  cd "$TEMPLATE_DIR"
  echo "--- kelta metadata diff package.json"
  kelta metadata diff package.json --output json > /tmp/diff.json
  jq -c '{creates: (.creates | length), updates: (.updates | length), conflicts: (.conflicts | length)}' /tmp/diff.json
  if ! jq -e '(.creates | length) > 0 and (.updates | length) == 0 and (.conflicts | length) == 0' /tmp/diff.json >/dev/null; then
    echo "::error::metadata diff on a fresh tenant must preview only creates" >&2
    jq '{updates, conflicts}' /tmp/diff.json >&2
    exit 1
  fi

  echo "--- install.sh"
  ./install.sh

  echo "--- seeded record counts"
  failed=0
  while read -r collection expected; do
    actual=$(kelta records list "$collection" --all --quiet | grep -c . || true)
    echo "$collection: expected $expected, found $actual"
    [ "$actual" = "$expected" ] || failed=1
  done < <(jq -r '."atomic:operations"[] | select(.op == "add") | .data.type' seeds/*.json | sort | uniq -c | awk '{print $2, $1}')
  if [ "$failed" -ne 0 ]; then
    echo "::error::seeded record counts do not match the template's seeds" >&2
    exit 1
  fi
  echo "Template $(basename "$TEMPLATE_DIR") installed cleanly"
  exit 0
fi

TEMPLATE="${1:?usage: ci/template-apply.sh examples/templates/<name>}"
COMPOSE_PROJECT_NAME="${COMPOSE_PROJECT_NAME:?COMPOSE_PROJECT_NAME must be set}"
NETWORK="${COMPOSE_PROJECT_NAME}_kelta-network"
ADMIN_PASSWORD_FILE="${ADMIN_PASSWORD_FILE:?ADMIN_PASSWORD_FILE must be set (written by ci/quickstart-run.sh)}"
IMAGE="kelta-template-runner:${COMPOSE_PROJECT_NAME}"
[ -f "$TEMPLATE/install.sh" ] || { echo "$TEMPLATE has no install.sh" >&2; exit 1; }
[ -f kelta-web/packages/cli/dist/index.js ] || { echo "kelta CLI not built (kelta-web/packages/cli/dist)" >&2; exit 1; }

docker build -q -t "$IMAGE" -f - . <<'DOCKERFILE'
FROM node:20-alpine
RUN apk add --no-cache bash curl jq
COPY kelta-web /kelta-web
RUN printf '#!/bin/sh\nexec node /kelta-web/packages/cli/dist/index.js "$@"\n' > /usr/local/bin/kelta \
 && chmod +x /usr/local/bin/kelta
COPY examples/templates /templates
COPY ci/template-apply.sh /usr/local/bin/template-apply.sh
DOCKERFILE

docker run --rm --network "$NETWORK" -e ADMIN_PASSWORD="$(cat "$ADMIN_PASSWORD_FILE")" \
  "$IMAGE" bash /usr/local/bin/template-apply.sh --in-container "/templates/${TEMPLATE#examples/templates/}"
