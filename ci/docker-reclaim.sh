#!/usr/bin/env bash
# Reclaims disk on the node's Docker daemon, which the k8s runners share through the
# mounted host socket and which nothing else cleans. Every E2E/quickstart/templates/
# upgrade run builds its own <project>-<service> images and none were ever removed:
# by 2026-10-13 two nodes held ~1,300 images, ~35 GB of default-builder cache and an
# unbounded kelta-ci builder state volume (78 GB) each — 85% of a disk the node's
# Postgres replica lives on.
#
# Safe on a daemon other jobs are using at the same moment:
#   - images are only removed when no container uses them, and only ones older than
#     DOCKER_RECLAIM_AGE. Its `until` filter reads the image's `Created` time, and a build
#     served from the warm kelta-ci cache keeps the ORIGINAL build's `Created` — weeks old
#     for the CI cerbos image. So a job's freshly loaded image could be pruned before its
#     `up` (PLT-618: "No such image: kelta-cerbos-ci:local" at E2E `Start stack`). The CI
#     jobs therefore label what they build with DOCKER_RECLAIM_LABEL=<epoch loaded>; the
#     prune skips labelled images, and they are aged by that label instead;
#   - the same `Created` race hit third-party images: `axllent/mailpit:latest`, `redis:7-alpine`
#     and the rest are always older than DOCKER_RECLAIM_AGE, so once no container used one a
#     concurrent job's `image prune --all` deleted it between another job's pull and its `up`
#     (KLT-638: "No such image: axllent/mailpit:latest" at Template Install Check `Start stack`).
#     So there is no `--all` prune: the prune removes dangling images only, and tagged unlabelled
#     images are removed one tag at a time, skipping a keep-list of every `image:` named in the
#     checkout's docker-compose*.yml and every `docker pull`/`FROM` image in its workflows (plus
#     DOCKER_RECLAIM_KEEP). With no keep-list to read, tagged unlabelled images are all kept;
#   - Testcontainers images are kept the same way: the keep-list also takes every `name:tag`
#     literal the checkout's Java sources pass to `DockerImageName.parse(...)` or a
#     `...Container(...)` constructor, or assign to an `...IMAGE = "..."` constant. Testcontainers
#     finds the image locally, skips its pull and then creates the container, and a concurrent
#     job's reclaim on the shared daemon deleted the image in between (PLT-835: "No such image:
#     postgres:15-alpine" in `Test auth`);
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
ROOT="${DOCKER_RECLAIM_ROOT:-$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)}"
EXTRA_KEEP="${DOCKER_RECLAIM_KEEP:-}"

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

# A reference as `docker image ls` shows it: no docker.io prefix, `:latest` when untagged.
normalize_ref() {
  local ref="${1#docker.io/}"
  ref="${ref#library/}"
  [[ "${ref##*/}" == *[:@]* ]] || ref="${ref}:latest"
  echo "$ref"
}

# Every image the compose files, the workflows and the Testcontainers tests name, one per line.
# `${VAR:-default}` is read as its default, which is what CI runs with.
keep_list() {
  local ref
  {
    cat "$ROOT"/docker-compose*.yml 2>/dev/null | sed -nE 's/^[[:space:]]*image:[[:space:]]*["'\'']?([^"'\''[:space:]]+).*/\1/p'
    cat "$ROOT"/.github/workflows/*.yml 2>/dev/null | grep -v '^[[:space:]]*#' \
      | sed -nE -e 's/.*docker pull[[:space:]]+([^[:space:]]+).*/\1/p' -e 's/^[[:space:]]*FROM[[:space:]]+([^[:space:]]+).*/\1/p'
    grep -rhE --include='*.java' --exclude-dir={node_modules,target,.git} \
      'DockerImageName\.parse\("|Container(<>)?\("|IMAGE[[:space:]]*=[[:space:]]*"' "$ROOT" 2>/dev/null \
      | sed -nE -e 's/.*(DockerImageName\.parse|Container(<>)?)\("([^"]+:[^"]+)".*/\3/p' \
        -e 's/.*IMAGE[[:space:]]*=[[:space:]]*"([^"]+:[^"]+)".*/\1/p'
    for ref in $EXTRA_KEEP; do echo "$ref"; done
  } | sed -E 's/\$\{[A-Za-z_][A-Za-z0-9_]*:-([^}]*)\}/\1/g' \
    | grep -E '^[A-Za-z0-9][A-Za-z0-9._/:@-]*$' \
    | while read -r ref; do normalize_ref "$ref"; done | sort -u
}

# Removes each tag of an unlabelled image created more than AGE ago unless the keep-list names
# it (never forced, so an image a container still uses is kept). An image whose `Created`
# cannot be read is kept.
reclaim_unlabelled() {
  local max now keep labelled id created refs ref
  max="$(age_seconds "$AGE" 2>/dev/null)" || { echo "::warning::docker-reclaim: cannot age images by '${AGE}', keeping tagged images"; return; }
  keep="$(keep_list)"
  if [[ -z "$keep" ]]; then
    echo "::warning::docker-reclaim: no compose, workflow or Testcontainers images found under ${ROOT}, keeping every tagged unlabelled image"
    return
  fi
  echo "docker-reclaim: keeping $(wc -l <<< "$keep") referenced images: $(tr '\n' ' ' <<< "$keep")"
  now="$(date +%s)"
  labelled="$(docker image ls --filter "label=${LABEL}" --format '{{.ID}}')"
  for id in $(docker image ls --filter dangling=false --format '{{.ID}}' | sort -u); do
    if grep -qxF -- "$id" <<< "$labelled"; then continue; fi
    created="$(date -d "$(docker image inspect --format '{{.Created}}' "$id")" +%s 2>/dev/null)" || continue
    (( now - created >= max )) || continue
    read -r -a refs <<< "$(docker image inspect --format '{{ join .RepoTags " " }}' "$id")"
    [[ ${#refs[@]} -gt 0 ]] || refs=("$id")
    for ref in "${refs[@]}"; do
      if grep -qxF -- "$(normalize_ref "$ref")" <<< "$keep"; then
        echo "docker-reclaim: kept ${ref} (referenced by compose, a workflow or a Testcontainers test)"
        continue
      fi
      echo "+ docker image rm ${ref}"
      docker image rm "$ref" >/dev/null 2>&1 || echo "docker-reclaim: kept ${ref} (in use)"
    done
  done
}

run docker image prune --force --filter "until=${AGE}" --filter "label!=${LABEL}"
reclaim_unlabelled
reclaim_labelled
run docker builder prune --all --force --filter "until=${AGE}"
if docker buildx inspect "${BUILDER}" >/dev/null 2>&1; then
  run docker buildx prune --builder "${BUILDER}" --force --keep-storage "${KEEP}"
else
  echo "docker-reclaim: no '${BUILDER}' builder on this daemon, skipping its cache"
fi
run docker system df

exit 0
