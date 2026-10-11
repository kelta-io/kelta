#!/usr/bin/env bash
# Tests ci/push-argo-bump.sh against throwaway git repos in $TMPDIR: a bare origin plus two
# clones, "argo" (the deploy job's checkout) and "other" (a human or another workflow pushing
# to homelab-argo while the deploy job is still running).
# Usage: bash ci/push-argo-bump.test.sh
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PUSH="$SCRIPT_DIR/push-argo-bump.sh"
export PUSH_BACKOFF_SECONDS=0
MESSAGE="chore: update Kelta images to main-abc1234"

assert_eq() {
  if [[ "$1" != "$2" ]]; then
    echo "FAIL: $3: expected '$2', got '$1'" >&2
    exit 1
  fi
  echo "PASS: $3 → $1"
}

clone() {
  git clone -q "$TMP/origin" "$TMP/$1"
  git -C "$TMP/$1" config user.email t@t
  git -C "$TMP/$1" config user.name t
}

new_repos() {
  TMP="$(mktemp -d -t push-argo-bump-test.XXXXXX)"
  git init -q -b main --bare "$TMP/origin"
  git init -q -b main "$TMP/seed"
  printf 'image: v1\n' > "$TMP/seed/deploy.yaml"
  printf 'notes\n' > "$TMP/seed/README.md"
  git -C "$TMP/seed" add .
  git -C "$TMP/seed" -c user.email=t@t -c user.name=t commit -q -m init
  git -C "$TMP/seed" push -q "$TMP/origin" HEAD:main
  clone argo
  clone other
}

run_push() {
  set +e
  (cd "$TMP/argo" && bash "$PUSH" "$MESSAGE") > "$TMP/out.log" 2>&1
  STATUS=$?
  set -e
}

origin_head() { git -C "$TMP/origin" rev-parse refs/heads/main; }

# Case 1: nobody else pushed -> committed and pushed on the first attempt.
new_repos
printf 'image: v2\n' > "$TMP/argo/deploy.yaml"
run_push
assert_eq "$STATUS" "0" "first-time push → exit code"
assert_eq "$(origin_head)" "$(git -C "$TMP/argo" rev-parse HEAD)" "first-time push → origin at the bump"
assert_eq "$(git -C "$TMP/origin" log -1 --format=%s main)" "$MESSAGE" "first-time push → bump subject"
grep -q "on attempt 1\." "$TMP/out.log" || { echo "FAIL: expected attempt 1 in output" >&2; cat "$TMP/out.log" >&2; exit 1; }
echo "PASS: first-time push → pushed on attempt 1"
rm -rf "$TMP"

# Case 2: another clone pushed an unrelated commit first -> rejected, rebased, pushed.
new_repos
printf 'notes, edited\n' > "$TMP/other/README.md"
git -C "$TMP/other" commit -q -am "docs: unrelated change"
git -C "$TMP/other" push -q origin HEAD:main
OTHER_SHA="$(git -C "$TMP/other" rev-parse HEAD)"
printf 'image: v2\n' > "$TMP/argo/deploy.yaml"
run_push
assert_eq "$STATUS" "0" "rejected then rebased → exit code"
grep -q "Push rejected (attempt 1/5)" "$TMP/out.log" || { echo "FAIL: expected a rejected first attempt" >&2; cat "$TMP/out.log" >&2; exit 1; }
echo "PASS: rejected then rebased → first push was rejected"
assert_eq "$(origin_head)" "$(git -C "$TMP/argo" rev-parse HEAD)" "rejected then rebased → origin at the clone's HEAD"
assert_eq "$(git -C "$TMP/origin" log -1 --format=%s main)" "$MESSAGE" "rejected then rebased → bump commit at HEAD"
assert_eq "$(git -C "$TMP/origin" rev-parse main~1)" "$OTHER_SHA" "rejected then rebased → other commit is the bump's parent"
assert_eq "$(git -C "$TMP/origin" show main:README.md)" "notes, edited" "rejected then rebased → other change kept"
assert_eq "$(git -C "$TMP/origin" show main:deploy.yaml)" "image: v2" "rejected then rebased → bump applied"
rm -rf "$TMP"

# Case 3: another clone changed the same lines -> rebase conflict, aborted, non-zero.
new_repos
printf 'image: hotfix\n' > "$TMP/other/deploy.yaml"
git -C "$TMP/other" commit -q -am "fix: pin hotfix image"
git -C "$TMP/other" push -q origin HEAD:main
OTHER_SHA="$(git -C "$TMP/other" rev-parse HEAD)"
printf 'image: v2\n' > "$TMP/argo/deploy.yaml"
run_push
assert_eq "$STATUS" "1" "conflicting change → exit code"
grep -q "^::error::Rebasing the image bump" "$TMP/out.log" || { echo "FAIL: expected a ::error:: line" >&2; cat "$TMP/out.log" >&2; exit 1; }
echo "PASS: conflicting change → ::error:: line printed"
GIT_DIR="$(git -C "$TMP/argo" rev-parse --absolute-git-dir)"
if [[ -e "$GIT_DIR/rebase-merge" || -e "$GIT_DIR/rebase-apply" ]]; then
  echo "FAIL: conflicting change → a rebase is still in progress" >&2
  exit 1
fi
echo "PASS: conflicting change → no rebase in progress"
assert_eq "$(git -C "$TMP/argo" status --porcelain)" "" "conflicting change → clean working tree"
assert_eq "$(git -C "$TMP/argo" log -1 --format=%s)" "$MESSAGE" "conflicting change → clone back on its bump commit"
assert_eq "$(origin_head)" "$OTHER_SHA" "conflicting change → origin untouched"
rm -rf "$TMP"

# Case 4: nothing staged -> exit 0 without committing.
new_repos
BEFORE="$(git -C "$TMP/argo" rev-parse HEAD)"
run_push
assert_eq "$STATUS" "0" "nothing staged → exit code"
grep -q "No image tag changes — nothing to commit" "$TMP/out.log" || { echo "FAIL: expected the nothing-to-commit message" >&2; cat "$TMP/out.log" >&2; exit 1; }
echo "PASS: nothing staged → nothing-to-commit message"
assert_eq "$(git -C "$TMP/argo" rev-parse HEAD)" "$BEFORE" "nothing staged → no commit"
assert_eq "$(origin_head)" "$BEFORE" "nothing staged → origin untouched"
rm -rf "$TMP"

# Case 5: origin keeps rejecting -> gives up after PUSH_ATTEMPTS with ::error::.
new_repos
printf '#!/bin/sh\necho "rejected by test hook" >&2\nexit 1\n' > "$TMP/origin/hooks/pre-receive"
chmod +x "$TMP/origin/hooks/pre-receive"
BEFORE="$(origin_head)"
printf 'image: v2\n' > "$TMP/argo/deploy.yaml"
PUSH_ATTEMPTS=3 run_push
assert_eq "$STATUS" "1" "attempts exhausted → exit code"
assert_eq "$(grep -c "rejected by test hook" "$TMP/out.log")" "3" "attempts exhausted → pushes tried"
grep -q "^::error::Push to main was still rejected after 3 attempts" "$TMP/out.log" || { echo "FAIL: expected a ::error:: line" >&2; cat "$TMP/out.log" >&2; exit 1; }
echo "PASS: attempts exhausted → ::error:: line printed"
assert_eq "$(origin_head)" "$BEFORE" "attempts exhausted → origin untouched"
rm -rf "$TMP"

# Case 6: more than 5 attempts is refused.
new_repos
PUSH_ATTEMPTS=6 run_push
assert_eq "$STATUS" "2" "PUSH_ATTEMPTS=6 → refused"
rm -rf "$TMP"

echo "All push-argo-bump tests passed."
