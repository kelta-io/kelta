#!/usr/bin/env bash
# Orchestrates the timed portion of the "quickstart" CI job (see
# .github/workflows/ci.yml): start the stack, then run ci/quickstart-check.sh
# against it from a sibling container on the compose network.
#
# Docker on the k8s-runner-integration runner is remote — containers can't see
# the runner's filesystem, so the check script is piped in over stdin rather
# than bind-mounted. (Same constraint docker-compose.ci.yml works around for
# cerbos: "Bind mounts don't work with the remote k8s-runner Docker daemon —
# bake config + policies into a CI-only image instead.")
set -euo pipefail

COMPOSE_PROJECT_NAME="${COMPOSE_PROJECT_NAME:?COMPOSE_PROJECT_NAME must be set}"
NETWORK="${COMPOSE_PROJECT_NAME}_kelta-network"

docker compose -f docker-compose.yml -f docker-compose.ci.yml up -d --wait --wait-timeout 300

docker run --rm -i --network "$NETWORK" curlimages/curl:8.11.0 sh < ci/quickstart-check.sh
