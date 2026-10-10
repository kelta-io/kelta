#!/usr/bin/env bash
# Orchestrates the timed portion of the "quickstart" CI job (see
# .github/workflows/ci.yml): start the stack (the released images pinned by
# KELTA_VERSION, pulled beforehand — docker-compose.ci.yml builds no Kelta service),
# read the platform admin's first-boot password from the kelta-auth log the way
# quickstart.md tells a new user to, complete the forced password change, then run
# ci/quickstart-check.sh against the stack from a sibling container on the compose
# network.
#
# Docker on the k8s-runner-integration runner is remote — containers can't see
# the runner's filesystem, so the scripts are piped in over stdin rather
# than bind-mounted. (Same constraint docker-compose.ci.yml works around for
# cerbos: "Bind mounts don't work with the remote k8s-runner Docker daemon —
# bake config + policies into a CI-only image instead.")
set -euo pipefail

COMPOSE_PROJECT_NAME="${COMPOSE_PROJECT_NAME:?COMPOSE_PROJECT_NAME must be set}"
NETWORK="${COMPOSE_PROJECT_NAME}_kelta-network"
COMPOSE=(docker compose -f docker-compose.yml -f docker-compose.ci.yml)

"${COMPOSE[@]}" up -d --wait --wait-timeout 300

# No KELTA_BOOTSTRAP_ADMIN_PASSWORD here on purpose: this is the documented path where
# kelta-auth generates the password and prints it once in its first-boot banner.
INITIAL_PASSWORD=""
for _ in $(seq 1 30); do
  INITIAL_PASSWORD=$("${COMPOSE[@]}" logs --no-color kelta-auth \
    | grep -o 'Password: *[A-Za-z0-9]\{20,\}' | head -n 1 | sed 's/Password: *//' || true)
  [ -n "$INITIAL_PASSWORD" ] && break
  sleep 1
done
if [ -z "$INITIAL_PASSWORD" ]; then
  echo "kelta-auth printed no first-boot admin password banner" >&2
  exit 1
fi

ADMIN_PASSWORD="qs-$(head -c 24 /dev/urandom | base64 | tr -dc 'A-Za-z0-9')"

docker run --rm -i --network "$NETWORK" \
  -e INITIAL_PASSWORD="$INITIAL_PASSWORD" -e NEW_PASSWORD="$ADMIN_PASSWORD" \
  curlimages/curl:8.11.0 sh < ci/admin-first-sign-in.sh

docker run --rm -i --network "$NETWORK" -e ADMIN_PASSWORD="$ADMIN_PASSWORD" \
  curlimages/curl:8.11.0 sh < ci/quickstart-check.sh
