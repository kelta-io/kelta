#!/usr/bin/env bash
# Smoke test for rollback.sh. Sets up throwaway git repos in $TMPDIR.
# Usage: bash scripts/rollback.test.sh

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROLLBACK="$SCRIPT_DIR/rollback.sh"
[[ -x "$ROLLBACK" ]] || chmod +x "$ROLLBACK"

assert_eq() {
  if [[ "$1" != "$2" ]]; then
    echo "FAIL: expected '$2', got '$1'" >&2; exit 1
  fi
  echo "PASS: $3 → $1"
}

new_repo() {
  # Sets up $TMP/origin (bare) and $TMP/argo (working clone with an initial commit).
  cd "$SCRIPT_DIR"
  TMP="$(mktemp -d -t rollback-test.XXXXXX)"
  git init -q -b main --bare "$TMP/origin"
  git clone -q "$TMP/origin" "$TMP/argo"
  cd "$TMP/argo"
  git checkout -q -b main 2>/dev/null || git checkout -q main
  git config user.email t@t
  git config user.name t
  echo "images: v1" > deploy.yaml
  git add . && git commit -q -m "init"
  git push -q origin HEAD:main
}

# Case 1: HEAD is the bump commit -> revert commit created and pushed.
new_repo
git config user.name "github-actions[bot]"
git config user.email "github-actions[bot]@users.noreply.github.com"
echo "images: v2" > deploy.yaml
git add . && git commit -q -m "chore: update Kelta images to v2"
BEFORE_COUNT="$(git rev-list --count HEAD)"

HOMELAB_ARGO_DIR="$TMP/argo" REASON="smoke-test failed in run 1" "$ROLLBACK"
STATUS=$?
assert_eq "$STATUS" "0" "bump commit → exit code"

cd "$TMP/argo"
AFTER_COUNT="$(git rev-list --count HEAD)"
assert_eq "$((AFTER_COUNT))" "$((BEFORE_COUNT + 1))" "bump commit → a revert commit was created"

NEW_SUBJECT="$(git log -1 --pretty=%s)"
[[ "$NEW_SUBJECT" == "revert: roll back image bump"* ]] \
  || { echo "FAIL: expected revert subject, got '$NEW_SUBJECT'"; exit 1; }
echo "PASS: bump commit → revert subject rewritten ($NEW_SUBJECT)"

REMOTE_HEAD="$(git ls-remote "$TMP/origin" refs/heads/main | cut -f1)"
LOCAL_HEAD="$(git rev-parse HEAD)"
assert_eq "$REMOTE_HEAD" "$LOCAL_HEAD" "bump commit → pushed to origin"
rm -rf "$TMP"

# Case 2: HEAD is already a rollback revert -> exit 0, no-op, no new commit, no push.
new_repo
git config user.name "github-actions[bot]"
git config user.email "github-actions[bot]@users.noreply.github.com"
git commit -q --allow-empty -m "revert: roll back image bump (smoke-test failed in run 1)"
BEFORE_SHA="$(git rev-parse HEAD)"
BEFORE_REMOTE_SHA="$(git ls-remote "$TMP/origin" refs/heads/main | cut -f1)"

set +e
HOMELAB_ARGO_DIR="$TMP/argo" REASON="smoke-test failed in run 2" "$ROLLBACK"
STATUS=$?
set -e
assert_eq "$STATUS" "0" "already-reverted commit → exit code"

cd "$TMP/argo"
AFTER_SHA="$(git rev-parse HEAD)"
assert_eq "$AFTER_SHA" "$BEFORE_SHA" "already-reverted commit → no new commit created"

AFTER_REMOTE_SHA="$(git ls-remote "$TMP/origin" refs/heads/main | cut -f1)"
assert_eq "$AFTER_REMOTE_SHA" "$BEFORE_REMOTE_SHA" "already-reverted commit → origin untouched"
rm -rf "$TMP"

# Case 3: HEAD is an unrelated human commit -> exit 4, no new commit, no push.
new_repo
git commit -q --allow-empty -m "fix: unrelated typo"
BEFORE_SHA="$(git rev-parse HEAD)"
BEFORE_REMOTE_SHA="$(git ls-remote "$TMP/origin" refs/heads/main | cut -f1)"

set +e
HOMELAB_ARGO_DIR="$TMP/argo" REASON="smoke-test failed in run 3" "$ROLLBACK"
STATUS=$?
set -e
assert_eq "$STATUS" "4" "unrelated commit → exit code"

cd "$TMP/argo"
AFTER_SHA="$(git rev-parse HEAD)"
assert_eq "$AFTER_SHA" "$BEFORE_SHA" "unrelated commit → no new commit created"

AFTER_REMOTE_SHA="$(git ls-remote "$TMP/origin" refs/heads/main | cut -f1)"
assert_eq "$AFTER_REMOTE_SHA" "$BEFORE_REMOTE_SHA" "unrelated commit → origin untouched"
rm -rf "$TMP"

echo "ALL TESTS PASSED"
