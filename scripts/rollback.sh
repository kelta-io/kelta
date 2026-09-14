#!/usr/bin/env bash
# rollback.sh — revert the most recent ArgoCD image-tag commit in homelab-argo,
# restoring whatever images were live before the current deploy.
#
# Intended to run from inside a GH Actions step on the deploy/smoke-test
# failure path. Expects:
#   - homelab-argo cloned at $HOMELAB_ARGO_DIR (default ./homelab-argo)
#   - git push permissions in that clone
#   - The most recent commit on main of homelab-argo is the image bump from this deploy
#
# Usage:
#   scripts/rollback.sh [--reason "smoke-test failed in run 12345"]
#
# Exit:
#   0 on successful revert + push (or no-op if last commit isn't an image bump)
#   non-zero on failure (will surface to the workflow)

set -euo pipefail

REASON="${REASON:-rollback triggered by smoke-test or e2e failure}"
HOMELAB_ARGO_DIR="${HOMELAB_ARGO_DIR:-homelab-argo}"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --reason) REASON="$2"; shift 2 ;;
    -h|--help) sed -n '2,18p' "$0"; exit 0 ;;
    *) echo "Unknown arg: $1" >&2; exit 2 ;;
  esac
done

[[ -d "$HOMELAB_ARGO_DIR" ]] || { echo "Not found: $HOMELAB_ARGO_DIR" >&2; exit 3; }

cd "$HOMELAB_ARGO_DIR"

LAST_SUBJECT="$(git log -1 --pretty=%s)"
LAST_AUTHOR="$(git log -1 --pretty=%an)"

# Already rolled back: a prior run of this script already reverted the bump and
# HEAD is that revert commit. Nothing to do — this is a benign no-op, not a failure
# (e.g. the rollback workflow re-runs, or two failure paths fire for the same deploy).
if [[ "$LAST_AUTHOR" == "github-actions[bot]" ]] && \
   [[ "$LAST_SUBJECT" == "revert: roll back image bump"* ]]; then
  echo "Head commit is already a rollback revert (subject=$LAST_SUBJECT) — nothing to do."
  exit 0
fi

# Only revert if the head looks like the bot-authored image bump from this deploy.
# Conservative: refuse to touch unrelated commits.
if [[ "$LAST_AUTHOR" != "github-actions[bot]" ]] || \
   [[ "$LAST_SUBJECT" != chore:*"update Kelta images"* ]]; then
  echo "Head commit doesn't look like the deploy bump (author=$LAST_AUTHOR subject=$LAST_SUBJECT)"
  echo "Refusing to revert — manual intervention required."
  exit 4
fi

git config user.name "github-actions[bot]"
git config user.email "github-actions[bot]@users.noreply.github.com"

REVERT_MSG="revert: roll back image bump (${REASON})"
git revert --no-edit HEAD

# Safety net: if the resulting tree is the well-known empty tree, the parent
# commit object was unreachable (shallow clone) and `git revert` silently
# produced an empty revert. Pushing this would prune every workload via
# ArgoCD. Abort instead.
EMPTY_TREE_SHA="4b825dc642cb6eb9a060e54bf8d69288fbee4904"
REVERT_TREE_SHA="$(git log -1 --pretty=%T HEAD)"
if [[ "$REVERT_TREE_SHA" == "$EMPTY_TREE_SHA" ]]; then
  echo "ERROR: revert produced the empty tree — likely a shallow-clone issue." >&2
  echo "Refusing to push. Check the workflow's actions/checkout fetch-depth." >&2
  git reset --hard HEAD~ >/dev/null 2>&1 || true
  exit 5
fi

# git revert produces a default message; rewrite the subject for searchability.
git commit --amend -m "$REVERT_MSG" -m "Reverts $(git log -1 --pretty=%H HEAD~ 2>/dev/null || echo unknown)"

git push origin HEAD
echo "Reverted. ArgoCD will reconcile within ~30s."
