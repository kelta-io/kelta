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
#     DOCKER_RECLAIM_AGE. Its `until` filter reads the image's `Created` time, and a build
#     served from the warm kelta-ci cache keeps the ORIGINAL build's `Created` — weeks old
#     for the CI cerbos image. So a job's freshly loaded image could be pruned before its
#     `up` (PLT-618: "No such image: kelta-cerbos-ci:local" at E2E `Start stack`). The CI
#     jobs therefore label what they build with DOCKER_RECLAIM_LABEL=<epoch loaded>; the
#     prune skips labelled images, and they are aged by that label instead;
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
LABEL="${DOCKER_RECLAIM_LABEL:-io.kelta.ci.loaded-at}"

run() {
  echo "+ $*"
  "$@" || echo "::warning::docker-reclaim: '$*' failed (exit $?)"
}

# AGE as seconds; only the s/m/h forms docker's `until` takes as a duration are accepted.
age_seconds() {
  case "$1" in
    *[0-9]s) echo "${1%s}" ;;
    *[0-9]m) echo $(( ${1%m} * 60 )) ;;
    *[0-9]h) echo $(( ${1%h} * 3600 )) ;;
    *) return 1 ;;
  esac
}

# Removes each labelled image loaded more than AGE ago, one tag at a time (never forced),
# so an image a container still uses is kept. A missing or malformed label is kept too.
reclaim_labelled() {
  local max now id loaded refs ref
  max="$(age_seconds "$AGE" 2>/dev/null)" || { echo "::warning::docker-reclaim: cannot age labelled images by '${AGE}', keeping them"; return; }
  now="$(date +%s)"
  for id in $(docker image ls --filter "label=${LABEL}" --format '{{.ID}}' | sort -u); do
    loaded="$(docker image inspect --format "{{ index .Config.Labels \"${LABEL}\" }}" "$id")"
    if ! [[ "$loaded" =~ ^[0-9]+$ ]] || (( now - loaded < max )); then continue; fi
    read -r -a refs <<< "$(docker image inspect --format '{{ join .RepoTags " " }}' "$id")"
    [[ ${#refs[@]} -gt 0 ]] || refs=("$id")
    for ref in "${refs[@]}"; do
      echo "+ docker image rm ${ref}"
      docker image rm "$ref" >/dev/null 2>&1 || echo "docker-reclaim: kept ${ref} (in use)"
    done
  done
}

run docker image prune --all --force --filter "until=${AGE}" --filter "label!=${LABEL}"
reclaim_labelled
run docker builder prune --all --force --filter "until=${AGE}"
if docker buildx inspect "${BUILDER}" >/dev/null 2>&1; then
  run docker buildx prune --builder "${BUILDER}" --force --keep-storage "${KEEP}"
else
  echo "docker-reclaim: no '${BUILDER}' builder on this daemon, skipping its cache"
fi
run docker system df

exit 0
