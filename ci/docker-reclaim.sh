#!/usr/bin/env bash
# Reclaims disk on the node's Docker daemon, which the k8s runners share through the
# mounted host socket and which nothing else cleans. Every E2E/quickstart/templates/
# upgrade run builds its own <project>-<service> images and none were ever removed:
# by 2026-10-13 two nodes held ~1,300 images, ~35 GB of default-builder cache and an
# unbounded kelta-ci builder state volume (78 GB) each — 85% of a disk the node's
# Postgres replica lives on.
#
# Safe on a daemon other jobs are using at the same moment:
#   - image prune only removes images no container uses, and only ones older than
#     DOCKER_RECLAIM_AGE, so a concurrent job's freshly built/loaded images survive;
#   - builder prune keeps cache younger than DOCKER_RECLAIM_AGE, so builds stay warm;
#   - buildx prune on the persistent builder trims it to DOCKER_RECLAIM_BUILDER_KEEP,
#     least recently used first, and buildkit never deletes records an active build holds.
# Never fails the job: a step that errors is reported as a warning.
#
# Usage: bash ci/docker-reclaim.sh
set -uo pipefail

AGE="${DOCKER_RECLAIM_AGE:-24h}"
BUILDER="${DOCKER_RECLAIM_BUILDER:-kelta-ci}"
KEEP="${DOCKER_RECLAIM_BUILDER_KEEP:-20gb}"

run() {
  echo "+ $*"
  "$@" || echo "::warning::docker-reclaim: '$*' failed (exit $?)"
}

run docker image prune --all --force --filter "until=${AGE}"
run docker builder prune --all --force --filter "until=${AGE}"
if docker buildx inspect "${BUILDER}" >/dev/null 2>&1; then
  run docker buildx prune --builder "${BUILDER}" --force --keep-storage "${KEEP}"
else
  echo "docker-reclaim: no '${BUILDER}' builder on this daemon, skipping its cache"
fi
run docker system df

exit 0
