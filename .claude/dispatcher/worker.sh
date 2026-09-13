#!/usr/bin/env bash
# Per-task worker. Runs ONE task end-to-end:
#   1. Create worktree at $EMF_WT_ROOT/<task-id> on a fresh branch off origin/main
#   2. Invoke `claude -p --model "$KELTA_MODEL"` with the worker-prompt, passing the task brief
#   3. Run /verify (defensive — the worker also runs it inside the session)
#   4. Push branch, open PR with autopilot label
#   5. Poll `gh pr checks` until conclusion
#   6. On success: queue_done; on retryable failure: re-loop up to max_attempts
#   7. On exhausted retries: queue_fail; always: remove the worktree
#
# Designed to run inside a tmux session named emf-worker-<task-id> so the
# Mac side can attach for live debugging.
#
# Usage:
#   .claude/dispatcher/worker.sh <task-file>
#
# Env:
#   EMF_REPO        path to the EMF main repo (default ~/GitHub/emf)
#   EMF_QUEUE_REPO  path to emf-queue (default ~/GitHub/emf-queue)
#   EMF_WT_ROOT     worktree root (default /var/lib/emf-wt)
#   CLAUDE_BIN      claude CLI path (default 'claude' from PATH)
#   PR_TIMEOUT_MIN  how long to wait for CI (default 30)
#   RZWARE_REPO_<X> path to non-emf repo X, used when a task carries repo:<x>
#                   (e.g. RZWARE_REPO_SPOTOPENED_WEB=~/GitHub/spotopened-web)

set -uo pipefail

SELF_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
. "$SELF_DIR/lib/log.sh"
. "$SELF_DIR/lib/queue.sh"
. "$SELF_DIR/lib/notify.sh"
. "$SELF_DIR/lib/deploy-hooks.sh"

EMF_REPO="${EMF_REPO:-$HOME/GitHub/emf}"
EMF_QUEUE_REPO="${EMF_QUEUE_REPO:-$HOME/GitHub/emf-queue}"
EMF_WT_ROOT="${EMF_WT_ROOT:-/var/lib/emf-wt}"
CLAUDE_BIN="${CLAUDE_BIN:-claude}"
PR_TIMEOUT_MIN="${PR_TIMEOUT_MIN:-30}"

TASK_FILE="${1:-}"
[[ -n "$TASK_FILE" && -f "$TASK_FILE" ]] || { echo "Usage: $0 <task-file>" >&2; exit 2; }

# --- budget: model routing (BUDGET.md) -------------------------------------
# Sonnet by default; Opus only when the task asks for it; Haiku for triage.
KELTA_MODEL="$(awk -F': *' '/^model:/{print $2; exit}' "$TASK_FILE" 2>/dev/null)"
if [ -z "${KELTA_MODEL:-}" ]; then
  case "$(awk -F': *' '/^complexity:/{print $2; exit}' "$TASK_FILE" 2>/dev/null)" in
    high) KELTA_MODEL=opus ;; *) KELTA_MODEL=sonnet ;;
  esac
fi
export KELTA_MODEL
# ---------------------------------------------------------------------------

EMF_LOG_COMPONENT="worker"
log_init "worker"
ID="$(basename "$TASK_FILE" .md)"
EMF_LOG_TASK_ID="$ID"

BRANCH="$(queue_get_field "$TASK_FILE" branch)"
[[ -z "$BRANCH" ]] && BRANCH="autopilot/$ID"
WT="$EMF_WT_ROOT/$ID"
ATTEMPTS="$(queue_get_field "$TASK_FILE" attempts)"; ATTEMPTS="${ATTEMPTS:-1}"
MAX_ATTEMPTS="$(queue_get_field "$TASK_FILE" max_attempts)"; MAX_ATTEMPTS="${MAX_ATTEMPTS:-3}"

REPO_NAME="$(queue_get_field "$TASK_FILE" repo)"
REPO_NAME="${REPO_NAME:-emf}"
_repo_err="$(mktemp)"
if ! REPO_PATH="$(queue_resolve_repo "$REPO_NAME" 2>"$_repo_err")"; then
  err_msg="$(cat "$_repo_err")"; rm -f "$_repo_err"
  log_error "repo resolution failed" repo="$REPO_NAME" error="$err_msg"
  queue_fail "$TASK_FILE" "unknown repo '$REPO_NAME': ${err_msg:-no configured path}"
  exit 1
fi
rm -f "$_repo_err"
DEFAULT_BRANCH="$(queue_repo_default_branch "$REPO_PATH")"
log_info "repo resolved" repo="$REPO_NAME" path="$REPO_PATH" default_branch="$DEFAULT_BRANCH"

cleanup_worktree() {
  if [[ -d "$WT" ]]; then
    log_info "removing worktree" path="$WT"
    git -C "$REPO_PATH" worktree remove --force "$WT" 2>/dev/null || rm -rf "$WT"
  fi
}
trap cleanup_worktree EXIT

# ---- 1. Worktree ------------------------------------------------------------

WORKER_START_TS="$(date +%s)"
log_event worker_start task="$ID" attempts="$ATTEMPTS" max="$MAX_ATTEMPTS" branch="$BRANCH"

if ! git -C "$REPO_PATH" fetch --quiet origin "$DEFAULT_BRANCH"; then
  log_error "git fetch failed"
  queue_fail "$TASK_FILE" "git fetch origin $DEFAULT_BRANCH failed"
  exit 1
fi

mkdir -p "$EMF_WT_ROOT"
# Belt and suspenders cleanup before re-creating: any stale worktree dir,
# stale worktree registration in .git/worktrees, stale local branch, stale
# remote branch from a prior failed attempt.
if [[ -d "$WT" ]]; then
  log_warn "stale worktree dir exists, removing" path="$WT"
  git -C "$REPO_PATH" worktree remove --force "$WT" 2>/dev/null
  rm -rf "$WT"
fi
git -C "$REPO_PATH" worktree prune >/dev/null 2>&1
if git -C "$REPO_PATH" show-ref --verify --quiet "refs/heads/$BRANCH"; then
  log_warn "stale local branch exists, deleting" branch="$BRANCH"
  git -C "$REPO_PATH" branch -D "$BRANCH" >/dev/null 2>&1
fi
if git -C "$REPO_PATH" ls-remote --exit-code --heads origin "$BRANCH" >/dev/null 2>&1; then
  # If the previous attempt timed out and left the branch open, the PR may have
  # merged in the meantime. Detect that before deleting and avoid a wasted retry.
  _old_pr_num="$(queue_get_field "$TASK_FILE" pr)"
  if [[ -n "$_old_pr_num" ]] && [[ "$_old_pr_num" =~ ^[0-9]+$ ]]; then
    _old_recheck="$(gh pr view "$_old_pr_num" --json state,mergedAt 2>/dev/null)"
    _old_merged="$(printf '%s' "$_old_recheck" | jq -r '.mergedAt // ""')"
    if [[ -n "$_old_merged" ]]; then
      log_event task_done task="$ID" pr="$_old_pr_num" via="late_merge_detected"
      queue_done "$TASK_FILE" "$_old_pr_num"
      notify_slack "#rzware-ceo" "${ID} merged — PR #${_old_pr_num} (landed after timeout)" || true
      exit 0
    fi
  fi
  log_warn "stale remote branch exists, deleting" branch="$BRANCH"
  git -C "$REPO_PATH" push origin --delete "$BRANCH" >/dev/null 2>&1
fi

# -B (force-create) so a half-cleaned-up branch from a previous attempt
# doesn't block the new worktree.
if ! git -C "$REPO_PATH" worktree add --force "$WT" -B "$BRANCH" "origin/$DEFAULT_BRANCH" 2>&1; then
  log_error "worktree add failed"
  queue_fail "$TASK_FILE" "worktree add failed"
  exit 1
fi
log_info "worktree created" path="$WT"

# ---- 2. Claude session ------------------------------------------------------

WORKER_PROMPT="$WT/.claude/dispatcher/worker-prompt.md"
if [[ ! -f "$WORKER_PROMPT" ]]; then
  WORKER_PROMPT="$EMF_REPO/.claude/dispatcher/worker-prompt.md"
  log_warn "no worker-prompt in task repo; falling back to emf" fallback="$WORKER_PROMPT"
fi
[[ -f "$WORKER_PROMPT" ]] || { log_error "worker-prompt missing (tried task repo and emf fallback)"; queue_fail "$TASK_FILE" "worker-prompt.md missing in worktree"; exit 1; }

USER_PROMPT="$(cat <<EOF
Begin task ${ID}.

Task file (already read by you via \$EMF_TASK_FILE = ${TASK_FILE}):

\`\`\`
$(cat "$TASK_FILE")
\`\`\`

Work in the current directory ($WT). Stop only when /verify is green and you have followed all hard rules in the worker-prompt. Do not push or open a PR — the wrapper does that.
EOF
)"

cd "$WT" || { log_error "cd to worktree failed"; queue_fail "$TASK_FILE" "cd worktree failed"; exit 1; }

JSONL_LOG="${EMF_LOG_DIR:-/var/log/emf-dispatcher}/${ID}.jsonl"
mkdir -p "$(dirname "$JSONL_LOG")" 2>/dev/null || true

log_event claude_start task="$ID" log="$JSONL_LOG"
EMF_TASK_FILE="$TASK_FILE" \
EMF_TASK_REPO="$REPO_NAME" \
EMF_TASK_REPO_PATH="$REPO_PATH" \
EMF_TASK_DEFAULT_BRANCH="$DEFAULT_BRANCH" \
  "$CLAUDE_BIN" \
    --model "$KELTA_MODEL" \
    -p "$USER_PROMPT" \
    --append-system-prompt "$(cat "$WORKER_PROMPT")" \
    --output-format stream-json \
    --include-partial-messages \
    --verbose \
    --dangerously-skip-permissions \
  >> "$JSONL_LOG" 2>&1
CLAUDE_RC=$?
log_event claude_end task="$ID" rc="$CLAUDE_RC"

# --- budget: self-throttle on a usage-limit signal (BUDGET.md §13) ----------
# Writes PAUSE_FILE; dispatch.sh's throttled() stops claiming until it lapses.
if detect_usage_limit "$JSONL_LOG"; then
  log_warn "usage limit detected; pausing the fleet" until="$(cat "$PAUSE_FILE" 2>/dev/null)"
fi

# ---- 3. Self-blocked check --------------------------------------------------

if [[ -f "$WT/BLOCKED.md" ]]; then
  reason="$(cat "$WT/BLOCKED.md")"
  log_warn "worker self-blocked" reason="${reason:0:100}"
  queue_fail "$TASK_FILE" "BLOCKED: ${reason:0:200}"
  exit 0
fi

# ---- 4. (No defensive /verify) ----------------------------------------------
# The Stop hook .claude/hooks/pre-pr-gate.sh already runs verify.sh before
# the claude session can return. Running it again here was costing 5–10 min
# per task with no signal — if the Stop hook passed, verify is green.

# ---- 5. Push + PR -----------------------------------------------------------

# If nothing changed, the worker did nothing useful. Treat as failure.
if git -C "$WT" diff --quiet "origin/$DEFAULT_BRANCH" && [[ -z "$(git -C "$WT" status --porcelain)" ]]; then
  log_error "no changes after worker session"
  if (( ATTEMPTS < MAX_ATTEMPTS )); then
    queue_release_orphan "$TASK_FILE"
  else
    queue_fail "$TASK_FILE" "worker produced no diff after $MAX_ATTEMPTS attempts"
  fi
  exit 1
fi

# Stage anything left unstaged + create a final commit if needed.
# Use a conventional-commit message built from the task's title + type so
# that `gh pr create --fill` derives a useful PR title (the title is taken
# from the first commit on the branch).
git -C "$WT" add -A
if ! git -C "$WT" diff --cached --quiet; then
  TASK_TITLE="$(queue_get_field "$TASK_FILE" title)"
  TASK_TYPE="$(queue_get_field "$TASK_FILE" type)"
  case "$TASK_TYPE" in
    feature)        CC_PREFIX="feat"  ;;
    bug|security)   CC_PREFIX="fix"   ;;
    doc)            CC_PREFIX="docs"  ;;
    chore|*)        CC_PREFIX="chore" ;;
  esac
  if [[ -n "$TASK_TITLE" ]]; then
    FALLBACK_MSG="${CC_PREFIX}: ${TASK_TITLE}"
  else
    FALLBACK_MSG="${CC_PREFIX}: ${ID} — final commit by worker.sh"
  fi
  git -C "$WT" commit -m "$FALLBACK_MSG" >/dev/null
fi

# Rebase onto latest origin default branch BEFORE pushing. Other autopilot
# workers may have merged since this worktree was created (e.g. all four
# touch .claude/CHANGELOG.md — first one wins, the rest hit conflicts that
# auto-merge then refuses to merge).
git -C "$WT" fetch --quiet origin "$DEFAULT_BRANCH"
if ! git -C "$WT" rebase "origin/$DEFAULT_BRANCH" 2>&1; then
  # Auto-resolve append-only conflicts in .claude/CHANGELOG.md: keep both
  # sides. Every worker appends one line to CHANGELOG.md; conflicts there
  # are spurious. Other conflicts are real and a retry won't help.
  conflicting="$(git -C "$WT" diff --name-only --diff-filter=U)"
  if [[ "$conflicting" == ".claude/CHANGELOG.md" ]]; then
    log_warn "auto-resolving CHANGELOG.md append conflict"
    git -C "$WT" checkout --theirs -- .claude/CHANGELOG.md
    # 'theirs' during rebase = the rebased-onto branch. Now re-append our
    # line: pull it from the original commit's CHANGELOG.md.
    if our_line="$(git -C "$WT" show "ORIG_HEAD:.claude/CHANGELOG.md" 2>/dev/null | tail -1)"; then
      [[ -n "$our_line" ]] && printf '%s\n' "$our_line" >> "$WT/.claude/CHANGELOG.md"
    fi
    git -C "$WT" add .claude/CHANGELOG.md
    if ! git -C "$WT" -c core.editor=true rebase --continue 2>&1; then
      log_error "rebase --continue failed after CHANGELOG resolve"
      git -C "$WT" rebase --abort >/dev/null 2>&1
      queue_release_orphan "$TASK_FILE"
      exit 1
    fi
  else
    log_error "rebase failed with non-CHANGELOG conflicts" files="$conflicting"
    git -C "$WT" rebase --abort >/dev/null 2>&1
    queue_release_orphan "$TASK_FILE"
    exit 1
  fi
fi

if ! git -C "$WT" push -u origin "$BRANCH" 2>&1; then
  log_error "git push failed"
  queue_fail "$TASK_FILE" "git push origin $BRANCH failed"
  exit 1
fi
log_event branch_pushed task="$ID" branch="$BRANCH"

PR_URL="$(gh pr create --label autopilot --fill --head "$BRANCH" 2>&1 | tail -1)"
if [[ "$PR_URL" != https://github.com/* ]]; then
  log_error "gh pr create failed" output="$PR_URL"
  queue_fail "$TASK_FILE" "gh pr create failed: ${PR_URL:0:120}"
  exit 1
fi
PR_NUM="${PR_URL##*/}"
log_event pr_opened task="$ID" pr="$PR_NUM" url="$PR_URL"
queue_set_field "$TASK_FILE" pr "$PR_NUM"

# ---- 5b. Auto-merge, tier 0 only --------------------------------------------
# OPERATING-MODEL.md §4/§6: a tier 0 task inside an approved epic merges on its
# own once checks pass. On repos without required checks (rzware-ceo, the
# property sites) `--auto` merges immediately, so tier 1 is deliberately left
# for the reviewer / 24h veto stage (P-0 items 10–11) and for repos whose CI
# already arms auto-merge (emf) this is a no-op.
TIER="$(queue_get_field "$TASK_FILE" tier 2>/dev/null || true)"
if [[ "$TIER" == "0" ]]; then
  if gh pr merge "$PR_NUM" --auto --squash >/dev/null 2>&1; then
    log_event auto_merge_armed task="$ID" pr="$PR_NUM" tier="$TIER"
  else
    log_warn "gh pr merge --auto failed; PR left for a human" task="$ID" pr="$PR_NUM"
  fi
else
  log_info "tier ${TIER:-unset}: PR left open for review" task="$ID" pr="$PR_NUM"
fi

# ---- 6. Poll CI -------------------------------------------------------------

deadline=$(( $(date +%s) + PR_TIMEOUT_MIN * 60 ))
poll_interval=30
final_state=""

while (( $(date +%s) < deadline )); do
  raw="$(gh pr view "$PR_NUM" --json state,mergedAt,statusCheckRollup 2>/dev/null)"
  state="$(printf '%s' "$raw" | jq -r '.state // "UNKNOWN"')"
  merged_at="$(printf '%s' "$raw" | jq -r '.mergedAt // ""')"

  if [[ "$state" == "MERGED" || -n "$merged_at" ]]; then
    final_state="MERGED"; break
  fi
  if [[ "$state" == "CLOSED" ]]; then
    final_state="CLOSED"; break
  fi

  failures="$(printf '%s' "$raw" | jq -r '
    [.statusCheckRollup[]?
     | select((.conclusion // .state // "") | ascii_downcase
              | IN("failure","failed","cancelled","timed_out","action_required","startup_failure"))
     | (.name // .context // "?")
    ] | join(",")
  ')"
  if [[ -n "$failures" && "$failures" != "" ]]; then
    final_state="CHECK_FAIL"
    log_warn "ci checks failed" failures="$failures"
    break
  fi

  # Reviewer + merge policy (P-0 items 10-11): once every posted check is a
  # terminal success, break out for the reviewer stage. `gh pr view` puts a
  # `conclusion` on completed checks and only a `status` on in-flight ones,
  # so requiring every entry to have a non-empty conclusion in {success,
  # neutral,skipped} is the "all green" signal — an empty rollup means the
  # CI harness hasn't posted yet and we keep polling.
  checks_state="$(printf '%s' "$raw" | jq -r '
    if (.statusCheckRollup | length) == 0 then "pending"
    else
      ([.statusCheckRollup[] | (.conclusion // .state // "") | ascii_downcase]) as $conclusions
      | if any($conclusions[]; . == "" or . == "pending" or . == "in_progress" or . == "queued")
          then "pending"
        elif all($conclusions[]; IN("success","neutral","skipped"))
          then "green"
        else "pending"
        end
    end
  ')"
  if [[ "$checks_state" == "green" ]]; then
    final_state="CHECKS_GREEN"
    log_info "ci checks green; entering reviewer stage" pr="$PR_NUM"
    break
  fi

  sleep "$poll_interval"
done

if [[ -z "$final_state" ]]; then
  final_state="TIMEOUT"
  log_warn "ci poll timed out" minutes="$PR_TIMEOUT_MIN"
fi

# ---- 7. Cost telemetry ------------------------------------------------------
# Aggregate the claude session's usage events into a one-line JSON summary so
# Promtail can ship it to Loki for per-task / per-day cost panels.
WORKER_END_TS="$(date +%s)"
DURATION_SEC=$(( WORKER_END_TS - WORKER_START_TS ))
COST_LOG="${EMF_LOG_DIR:-/var/log/emf-dispatcher}/cost-${ID}.json"
if [[ -x "$SELF_DIR/lib/parse-usage.sh" ]]; then
  bash "$SELF_DIR/lib/parse-usage.sh" "$JSONL_LOG" "$ID" "$final_state" "$DURATION_SEC" \
    > "$COST_LOG" 2>/dev/null || log_warn "parse-usage failed"
fi

# ---- 8. Archive -------------------------------------------------------------

case "$final_state" in
  CHECKS_GREEN)
    # Step 6b + 7b — reviewer stage, merge policy dispatch, deploy hook.
    # process_after_ci lives in lib/deploy-hooks.sh so it's testable without
    # spinning up a real worktree; it also handles queue archival on the
    # request-changes paths (strike 1 = release_orphan, strike 2 = queue_fail).
    REVIEWER_PROMPT="$SELF_DIR/review-prompt.md"
    outcome="$(process_after_ci \
      "$TASK_FILE" "$PR_NUM" "$PR_URL" "$REPO_NAME" "$REPO_PATH" \
      "$REVIEWER_PROMPT" "$WORKER_PROMPT")"
    log_event reviewer_done task="$ID" pr="$PR_NUM" outcome="$outcome"
    case "$outcome" in
      APPROVED_MERGED)
        log_event task_done task="$ID" pr="$PR_NUM" duration_sec="$DURATION_SEC" via="reviewer_auto"
        queue_done "$TASK_FILE" "$PR_NUM"
        notify_slack "#rzware-ceo" "${ID} merged — PR #${PR_NUM} ${PR_URL}" || true
        ;;
      APPROVED_VETO)
        # deploy_after: stamped by apply_merge_policy; archive as done so the
        # queue reflects the reviewer's decision, then a separate cron flips
        # the actual merge when the window elapses.
        log_event task_done task="$ID" pr="$PR_NUM" via="veto_window"
        queue_done "$TASK_FILE" "$PR_NUM"
        ;;
      APPROVED_MANUAL)
        # Reviewer approved but merge_policy=manual_protected — Craig merges
        # by hand. Release the queue slot; the task stays open until then.
        log_info "manual_protected: released for craig to merge" task="$ID" pr="$PR_NUM"
        queue_release_orphan "$TASK_FILE"
        ;;
      APPROVED_NONE)
        log_info "no auto-merge policy; PR left open" task="$ID" pr="$PR_NUM"
        queue_release_orphan "$TASK_FILE"
        ;;
      CHANGES_STRIKE_1)
        log_warn "reviewer requested changes (strike 1)" task="$ID" pr="$PR_NUM"
        # process_after_ci already called queue_release_orphan.
        ;;
      CHANGES_FAILED)
        log_error "reviewer requested changes (strike 2); task failed" task="$ID" pr="$PR_NUM"
        # process_after_ci already called queue_fail + gh pr close.
        notify_slack "#rzware-ceo" \
          "FAILED: ${ID} — reviewer rejected twice. PR: ${PR_URL}. Log: ${JSONL_LOG}" || true
        ;;
      *)
        log_warn "unexpected reviewer outcome; leaving PR open" outcome="$outcome"
        queue_release_orphan "$TASK_FILE"
        ;;
    esac
    ;;
  MERGED)
    log_event task_done task="$ID" pr="$PR_NUM" duration_sec="$DURATION_SEC"
    queue_done "$TASK_FILE" "$PR_NUM"
    notify_slack "#rzware-ceo" "${ID} merged — PR #${PR_NUM} ${PR_URL}" || true
    ;;
  CHECK_FAIL|CLOSED)
    if (( ATTEMPTS < MAX_ATTEMPTS )); then
      log_info "releasing for retry" final_state="$final_state" attempts="$ATTEMPTS" max="$MAX_ATTEMPTS"
      # Close the PR so the next attempt opens a fresh one.
      gh pr close "$PR_NUM" --comment "autopilot retry: closing to relaunch on attempt $((ATTEMPTS + 1))" >/dev/null 2>&1 || true
      git -C "$REPO_PATH" push origin --delete "$BRANCH" >/dev/null 2>&1 || true
      queue_release_orphan "$TASK_FILE"
    else
      log_error "exhausted retries" final_state="$final_state" attempts="$ATTEMPTS"
      _fail_reason="$final_state after $MAX_ATTEMPTS attempts (pr #$PR_NUM)"
      queue_fail "$TASK_FILE" "$_fail_reason"
      notify_slack "#rzware-ceo" \
        "FAILED: ${ID} — ${_fail_reason}. Attempts: ${ATTEMPTS}/${MAX_ATTEMPTS}. PR: ${PR_URL}. Log: ${JSONL_LOG}" || true
    fi
    ;;
  TIMEOUT)
    # Re-check the live PR state: the poll loop exits on deadline but the PR
    # may have merged in the seconds since the last poll_interval check.
    _to_raw="$(gh pr view "$PR_NUM" --json state,mergedAt 2>/dev/null)"
    _to_merged_at="$(printf '%s' "$_to_raw" | jq -r '.mergedAt // ""')"
    _to_state="$(printf '%s' "$_to_raw" | jq -r '.state // "UNKNOWN"')"
    if [[ "$_to_state" == "MERGED" || -n "$_to_merged_at" ]]; then
      log_event task_done task="$ID" pr="$PR_NUM" duration_sec="$DURATION_SEC" via="timeout_recheck"
      queue_done "$TASK_FILE" "$PR_NUM"
      notify_slack "#rzware-ceo" "${ID} merged — PR #${PR_NUM} ${PR_URL} (caught at timeout recheck)" || true
    elif (( ATTEMPTS < MAX_ATTEMPTS )); then
      # CI is still pending. Leave the PR and branch intact so the auto-merge
      # workflow can land them; release for retry without closing or deleting.
      log_info "ci still pending at timeout; leaving PR and branch open for retry" \
        attempts="$ATTEMPTS" max="$MAX_ATTEMPTS" pr="$PR_NUM"
      queue_release_orphan "$TASK_FILE"
    else
      # All attempts exhausted. Fail, but leave the PR open — it may still merge.
      log_error "exhausted retries on timeout; PR left open" attempts="$ATTEMPTS" pr="$PR_NUM"
      _fail_reason="still pending after ${PR_TIMEOUT_MIN} minutes (pr #${PR_NUM}, attempt ${ATTEMPTS})"
      queue_fail "$TASK_FILE" "$_fail_reason"
      notify_slack "#rzware-ceo" \
        "FAILED: ${ID} — ${_fail_reason}. PR: ${PR_URL} (left open). Log: ${JSONL_LOG}" || true
    fi
    ;;
  *)
    queue_fail "$TASK_FILE" "unexpected final state: $final_state"
    ;;
esac
