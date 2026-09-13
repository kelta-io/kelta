#!/usr/bin/env bash
# Unit tests for lib/deploy-hooks.sh — the reviewer stage + merge policy
# dispatch + deploy hook dispatch that worker.sh runs between "CI checks are
# green" and "archive the task".
#
# Invoked directly (`bash review-test.sh`). Not part of /verify — the
# dispatcher lives outside the maven/npm build.
#
# All external commands (`claude`, `gh`, `notify_slack`) are stubbed. No
# network, no real repo needed. DRY_RUN=1 short-circuits gh pr merge/close
# so the merge-policy paths log-and-return without touching GitHub.

set -u
SELF_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
LOG_LIB="$SELF_DIR/../lib/log.sh"
QUEUE_LIB="$SELF_DIR/../lib/queue.sh"
NOTIFY_LIB="$SELF_DIR/../lib/notify.sh"
DEPLOY_LIB="$SELF_DIR/../lib/deploy-hooks.sh"
PROMPT_FILE="$SELF_DIR/../review-prompt.md"
WORKER_PROMPT="$SELF_DIR/../worker-prompt.md"

for f in "$LOG_LIB" "$QUEUE_LIB" "$NOTIFY_LIB" "$DEPLOY_LIB" "$PROMPT_FILE"; do
  [[ -f "$f" ]] || { echo "missing $f" >&2; exit 2; }
done

# shellcheck source=/dev/null
. "$LOG_LIB"
# shellcheck source=/dev/null
. "$QUEUE_LIB"
# shellcheck source=/dev/null
. "$NOTIFY_LIB"
# shellcheck source=/dev/null
. "$DEPLOY_LIB"

pass=0; fail=0
_ok() { pass=$((pass+1)); printf 'ok   %s\n' "$1"; }
_no() { fail=$((fail+1)); printf 'FAIL %s\n     %s\n' "$1" "$2"; }
assert_eq()   { local m="$1" w="$2" g="$3"; [[ "$w" == "$g" ]] && _ok "$m" || _no "$m" "want=[$w] got=[$g]"; }
assert_zero() { local m="$1" rc="$2"; (( rc == 0 )) && _ok "$m" || _no "$m" "expected 0, got $rc"; }

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

EMF_LOG_DIR="$TMP/logs"
mkdir -p "$EMF_LOG_DIR"
log_init "review-test"

# ---- Fixtures: repos.yaml + queue dirs --------------------------------------

mkdir -p "$TMP/queue/in-progress" "$TMP/queue/approved" "$TMP/queue/done" "$TMP/queue/failed"
git init -q "$TMP/queue"
git -C "$TMP/queue" -c user.email=t@t -c user.name=t commit -q --allow-empty -m init
EMF_QUEUE_REPO="$TMP/queue"

mkdir -p "$TMP/etc"
cat > "$TMP/etc/repos.yaml" <<'YAML'
- name: emf
  clone_path: /tmp/emf
  default_branch: main
  merge_policy: auto
  deploy_hook: none
  tier_ceiling: 1

- name: homelab-argo
  clone_path: /tmp/homelab-argo
  default_branch: main
  merge_policy: veto_window
  deploy_hook: argocd_sync
  tier_ceiling: 1

- name: rzware-ceo
  clone_path: /tmp/rzware-ceo
  default_branch: master
  merge_policy: manual_protected
  deploy_hook: none
  tier_ceiling: 1
YAML
REPOS_YAML="$TMP/etc/repos.yaml"
export REPOS_YAML

# ---- Stubs: claude, gh, notify_slack, argocd -------------------------------
# Each stub records its call args into $TMP/calls.log so tests can assert on
# what was invoked. `claude` reads the fixture verdict from $TMP/verdict.txt.

mkdir -p "$TMP/stubs"

cat > "$TMP/stubs/claude" <<EOF
#!/usr/bin/env bash
echo "claude \$@" >> "$TMP/calls.log"
if [[ -f "$TMP/verdict.txt" ]]; then
  cat "$TMP/verdict.txt"
else
  echo '{"verdict":"approve","reason":"acceptance met"}'
fi
EOF
chmod +x "$TMP/stubs/claude"

cat > "$TMP/stubs/gh" <<EOF
#!/usr/bin/env bash
echo "gh \$@" >> "$TMP/calls.log"
# gh pr diff: emit a canned unified diff so run_reviewer has something to feed.
if [[ "\$1" == "pr" && "\$2" == "diff" ]]; then
  cat <<'DIFF'
diff --git a/foo.txt b/foo.txt
index 0000000..1111111 100644
--- a/foo.txt
+++ b/foo.txt
@@ -0,0 +1 @@
+hello
DIFF
fi
exit 0
EOF
chmod +x "$TMP/stubs/gh"

cat > "$TMP/stubs/argocd" <<EOF
#!/usr/bin/env bash
echo "argocd \$@" >> "$TMP/calls.log"
exit 0
EOF
chmod +x "$TMP/stubs/argocd"

OLD_PATH="$PATH"
PATH="$TMP/stubs:$OLD_PATH"
export CLAUDE_BIN="$TMP/stubs/claude"

# Silence Slack: override notify_slack in-process so tests don't need sops.
notify_slack() {
  echo "notify_slack $*" >> "$TMP/calls.log"
  return 0
}

# ---- Helper: fresh task file per scenario ----------------------------------

new_task() {
  local id="$1" repo="$2" strikes="${3:-0}"
  local f="$TMP/queue/in-progress/${id}.md"
  cat > "$f" <<EOF
---
id: ${id}
title: "test"
type: feature
status: in_progress
repo: ${repo}
pr: 42
review_strikes: ${strikes}
acceptance:
  - "line one is met"
  - "line two is met"
---
body
EOF
  git -C "$EMF_QUEUE_REPO" add "in-progress/${id}.md" >/dev/null 2>&1
  git -C "$EMF_QUEUE_REPO" -c user.email=t@t -c user.name=t commit -q -m "add ${id}" >/dev/null 2>&1
  echo "$f"
}

reset_calls() { : > "$TMP/calls.log"; }

# ---- 1. review-prompt.md exists and is under 40 lines ----------------------

lines="$(wc -l < "$PROMPT_FILE")"
[[ "$lines" -lt 40 ]] && _ok "review-prompt.md < 40 lines ($lines)" \
  || _no "review-prompt.md < 40 lines" "got $lines"

# ---- 2. run_reviewer parses a stubbed verdict JSON -------------------------

reset_calls
echo '{"verdict":"approve","reason":"ok"}' > "$TMP/verdict.txt"
tf="$(new_task TASK-t1 emf)"
out="$(run_reviewer 42 "$tf" "$PROMPT_FILE" "$WORKER_PROMPT" 2>/dev/null)"
verdict="$(printf '%s' "$out" | jq -r '.verdict')"
assert_eq "run_reviewer: verdict approve" "approve" "$verdict"
grep -q '^claude ' "$TMP/calls.log" && _ok "run_reviewer: claude invoked" \
  || _no "run_reviewer: claude invoked" "no claude call recorded"
grep -q '^gh pr diff 42' "$TMP/calls.log" && _ok "run_reviewer: gh pr diff invoked" \
  || _no "run_reviewer: gh pr diff invoked" "no gh pr diff call recorded"

# ---- 3. run_reviewer falls back to approve on missing JSON -----------------

reset_calls
echo 'garbage without json' > "$TMP/verdict.txt"
out="$(run_reviewer 42 "$tf" "$PROMPT_FILE" "$WORKER_PROMPT" 2>/dev/null)"
verdict="$(printf '%s' "$out" | jq -r '.verdict')"
assert_eq "run_reviewer: fallback approve on missing JSON" "approve" "$verdict"

# ---- 4. Scenario A — CI-green + reviewer approves + auto merge_policy ------
#        Expect: gh pr merge is called (DRY_RUN prints), task moves to done/.

reset_calls
echo '{"verdict":"approve","reason":"acceptance met"}' > "$TMP/verdict.txt"
tf="$(new_task TASK-approve-auto emf)"
DRY_RUN=1
outcome="$(DRY_RUN=1 process_after_ci "$tf" 42 "https://example/pr/42" emf /tmp/emf "$PROMPT_FILE" "$WORKER_PROMPT" 2>>"$TMP/calls.log")"
assert_eq "scenario A: outcome APPROVED_MERGED" "APPROVED_MERGED" "$outcome"
grep -q 'DRY_RUN: gh pr merge 42 --merge --delete-branch' "$TMP/calls.log" \
  && _ok "scenario A: DRY_RUN merge line printed" \
  || _no "scenario A: DRY_RUN merge line printed" "not found in calls.log"
# process_after_ci does not call queue_done itself on APPROVED_MERGED — that's
# worker.sh's job. What it MUST do is not have called queue_fail or
# release_orphan (task stays in-progress, worker.sh archives it).
[[ -f "$TMP/queue/in-progress/TASK-approve-auto.md" ]] \
  && _ok "scenario A: task stayed in in-progress for worker.sh to archive" \
  || _no "scenario A: task stayed in in-progress" "task file moved"

# ---- 5. Scenario B — reviewer requests changes (first strike) --------------

reset_calls
echo '{"verdict":"changes","reason":"missed acceptance line X"}' > "$TMP/verdict.txt"
tf="$(new_task TASK-strike-1 emf 0)"
outcome="$(DRY_RUN=1 process_after_ci "$tf" 42 "https://example/pr/42" emf /tmp/emf "$PROMPT_FILE" "$WORKER_PROMPT" 2>>"$TMP/calls.log")"
assert_eq "scenario B: outcome CHANGES_STRIKE_1" "CHANGES_STRIKE_1" "$outcome"
# release_orphan moved the task from in-progress → approved.
[[ -f "$TMP/queue/approved/TASK-strike-1.md" ]] \
  && _ok "scenario B: task moved to approved/ (release_orphan)" \
  || _no "scenario B: task moved to approved/" "not in approved/"
strikes="$(queue_get_field "$TMP/queue/approved/TASK-strike-1.md" review_strikes)"
assert_eq "scenario B: review_strikes = 1" "1" "$strikes"

# ---- 6. Scenario C — reviewer requests changes (second strike) → failed/ ---

reset_calls
echo '{"verdict":"changes","reason":"still wrong"}' > "$TMP/verdict.txt"
tf="$(new_task TASK-strike-2 emf 1)"
outcome="$(DRY_RUN=1 process_after_ci "$tf" 42 "https://example/pr/42" emf /tmp/emf "$PROMPT_FILE" "$WORKER_PROMPT" 2>>"$TMP/calls.log")"
assert_eq "scenario C: outcome CHANGES_FAILED" "CHANGES_FAILED" "$outcome"
[[ -f "$TMP/queue/failed/TASK-strike-2.md" ]] \
  && _ok "scenario C: task moved to failed/" \
  || _no "scenario C: task moved to failed/" "not in failed/"
grep -q 'DRY_RUN: gh pr close 42' "$TMP/calls.log" \
  && _ok "scenario C: PR close called (DRY_RUN)" \
  || _no "scenario C: PR close called" "no close line in calls.log"

# ---- 7. Scenario D — merge_policy: veto_window -----------------------------
#        Expect: no gh pr merge; deploy_after: written; notify_slack called.

reset_calls
echo '{"verdict":"approve","reason":"acceptance met"}' > "$TMP/verdict.txt"
tf="$(new_task TASK-veto homelab-argo)"
outcome="$(DRY_RUN=1 process_after_ci "$tf" 42 "https://example/pr/42" homelab-argo /tmp/homelab-argo "$PROMPT_FILE" "$WORKER_PROMPT" 2>>"$TMP/calls.log")"
assert_eq "scenario D: outcome APPROVED_VETO" "APPROVED_VETO" "$outcome"
grep -q 'gh pr merge' "$TMP/calls.log" \
  && _no "scenario D: no gh pr merge" "unexpected merge call found" \
  || _ok "scenario D: no gh pr merge called"
deploy_after="$(queue_get_field "$tf" deploy_after)"
[[ -n "$deploy_after" ]] \
  && _ok "scenario D: deploy_after stamped ($deploy_after)" \
  || _no "scenario D: deploy_after stamped" "empty"
grep -q '^notify_slack ' "$TMP/calls.log" \
  && _ok "scenario D: notify_slack line posted" \
  || _no "scenario D: notify_slack line posted" "not in calls.log"

# ---- 8. Scenario E — merge_policy: manual_protected ------------------------
#        Expect: no gh pr merge; one Slack line; task moved to approved/
#        (release_orphan, so it stays open until Craig acts).

reset_calls
echo '{"verdict":"approve","reason":"acceptance met"}' > "$TMP/verdict.txt"
tf="$(new_task TASK-manual rzware-ceo)"
outcome="$(DRY_RUN=1 process_after_ci "$tf" 42 "https://example/pr/42" rzware-ceo /tmp/rzware-ceo "$PROMPT_FILE" "$WORKER_PROMPT" 2>>"$TMP/calls.log")"
assert_eq "scenario E: outcome APPROVED_MANUAL" "APPROVED_MANUAL" "$outcome"
grep -q 'gh pr merge' "$TMP/calls.log" \
  && _no "scenario E: no gh pr merge" "unexpected merge call found" \
  || _ok "scenario E: no gh pr merge called"
grep -q '^notify_slack ' "$TMP/calls.log" \
  && _ok "scenario E: notify_slack line posted" \
  || _no "scenario E: notify_slack line posted" "not in calls.log"

# ---- 9. review-prompt.md emits valid JSON when fed a canned diff -----------
#        The stubbed claude echoes the fixture verdict — validate the JSON.

reset_calls
echo '{"verdict":"approve","reason":"acceptance met"}' > "$TMP/verdict.txt"
tf="$(new_task TASK-jsonvalid emf)"
out="$(run_reviewer 42 "$tf" "$PROMPT_FILE" "$WORKER_PROMPT" 2>/dev/null)"
printf '%s' "$out" | jq -e '.verdict and .reason' >/dev/null 2>&1 \
  && _ok "review-prompt: stubbed claude emits valid JSON with verdict+reason" \
  || _no "review-prompt: valid JSON" "got: $out"

# ---- 10. bash -n worker.sh -------------------------------------------------

if bash -n "$SELF_DIR/../worker.sh" 2>/dev/null; then
  _ok "worker.sh: bash -n syntax check"
else
  _no "worker.sh: bash -n syntax check" "syntax error"
fi

PATH="$OLD_PATH"

printf '\n%d passed, %d failed\n' "$pass" "$fail"
(( fail == 0 ))
