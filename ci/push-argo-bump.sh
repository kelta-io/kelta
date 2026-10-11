#!/usr/bin/env bash
# Commits the staged image-tag bump in a homelab-argo clone and pushes it, rebasing onto
# whatever landed on the remote branch in the meantime. Run by the "Update ArgoCD Manifests"
# job (.github/workflows/build-and-publish-containers.yml) from inside the clone:
#
#   bash ../emf-src/ci/push-argo-bump.sh "chore: update Kelta images to main-<sha>"
#
# The deploy job checks homelab-argo out minutes before it pushes (the Harbor image checks run
# in between), so a human or another workflow committing to homelab-argo main in that window
# made a bare `git push` fail with "rejected (fetch first)" — run 38169547207. On a rejected
# push this runs `git pull --rebase` and pushes again, up to PUSH_ATTEMPTS times. A rebase
# conflict is aborted (the clone is left with no rebase in progress) and fails the step. It
# never force-pushes.
#
# Env: PUSH_ATTEMPTS (default 5, at most 5), PUSH_BACKOFF_SECONDS (default 5; the wait before
# attempt n is n times this).
set -euo pipefail

MESSAGE="${1:-}"
if [[ -z "$MESSAGE" ]]; then
  echo "usage: push-argo-bump.sh <commit message>" >&2
  exit 2
fi

ATTEMPTS="${PUSH_ATTEMPTS:-5}"
BACKOFF="${PUSH_BACKOFF_SECONDS:-5}"
if ((ATTEMPTS < 1 || ATTEMPTS > 5)); then
  echo "::error::PUSH_ATTEMPTS must be between 1 and 5, got ${ATTEMPTS}" >&2
  exit 2
fi

git add -A
if git diff --cached --quiet; then
  echo "No image tag changes — nothing to commit (all builds were skipped)."
  exit 0
fi

BRANCH="$(git symbolic-ref --short HEAD)"
git commit -q -m "$MESSAGE"

for ((attempt = 1; attempt <= ATTEMPTS; attempt++)); do
  if git push origin "HEAD:${BRANCH}"; then
    echo "Pushed $(git rev-parse --short HEAD) to ${BRANCH} on attempt ${attempt}."
    exit 0
  fi
  if ((attempt == ATTEMPTS)); then
    break
  fi
  echo "Push rejected (attempt ${attempt}/${ATTEMPTS}); rebasing onto origin/${BRANCH}."
  sleep "$((attempt * BACKOFF))"
  if ! git pull -q --rebase origin "$BRANCH"; then
    git rebase --abort 2>/dev/null || true
    echo "::error::Rebasing the image bump onto origin/${BRANCH} conflicted; a concurrent homelab-argo commit changed the same lines. Nothing was pushed."
    exit 1
  fi
done

echo "::error::Push to ${BRANCH} was still rejected after ${ATTEMPTS} attempts. Nothing was pushed."
exit 1
