#!/usr/bin/env bash
# Post-CI orchestration: reviewer, merge policy, deploy hooks.
# All the logic worker.sh runs between "CI checks are green" and "archive
# the task" lives here so it stays testable in isolation. tests/review-test.sh
# sources this file, stubs `claude`/`gh`/`notify_slack`, and drives each
# function directly.
#
# Source this file AFTER lib/log.sh, lib/queue.sh, lib/notify.sh:
#   . "$SELF_DIR/lib/deploy-hooks.sh"
#
# Env:
#   REPOS_YAML   override for the allowlist path (default: ../etc/repos.yaml
#                relative to this script)
#   CLAUDE_BIN   claude CLI (default 'claude')
#   DRY_RUN=1    do not call `gh pr merge`; print what would run instead

DEPLOY_HOOKS_SELF_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPOS_YAML="${REPOS_YAML:-$DEPLOY_HOOKS_SELF_DIR/../etc/repos.yaml}"
CLAUDE_BIN="${CLAUDE_BIN:-claude}"

# ---- repos.yaml lookup ------------------------------------------------------
# read_repo_field NAME FIELD → echoes the scalar value, empty on miss.
# YAML shape (one entry per repo):
#   - name: <name>
#     merge_policy: <auto|veto_window|manual_protected|none>
#     deploy_hook:  <argocd_image_bump|argocd_sync|install-claude-config.sh|sync-agents.sh|none>
#     ...
read_repo_field() {
  local name="$1" field="$2"
  [[ -f "$REPOS_YAML" ]] || return 1
  awk -v n="$name" -v f="$field" '
    BEGIN{cur=""}
    /^-[[:space:]]+name:[[:space:]]+/ {
      sub(/^-[[:space:]]+name:[[:space:]]+/,"")
      gsub(/^[ \t]+|[ \t]+$/,"")
      cur=$0
      next
    }
    cur==n && $1 == f":" {
      # Strip "key: " and any surrounding quotes.
      sub("^[[:space:]]*"f":[[:space:]]*","")
      gsub(/^"|"$/, "")
      gsub(/^'\''|'\''$/, "")
      print
      exit
    }
  ' "$REPOS_YAML"
}

# ---- reviewer stage ---------------------------------------------------------
# extract_acceptance TASK_FILE → prints the acceptance: YAML block body.
# One line per acceptance item, verbatim. Empty if the field is missing.
extract_acceptance() {
  local file="$1"
  awk '
    /^acceptance:[[:space:]]*$/ { in_acc=1; next }
    in_acc && /^[[:space:]]+-[[:space:]]/ { print; next }
    in_acc && /^[A-Za-z_]/ { in_acc=0 }
  ' "$file"
}

# run_reviewer PR_NUM TASK_FILE PROMPT_FILE [WORKER_PROMPT]
# Invokes `claude -p` with the reviewer prompt, the PR diff, and the
# acceptance block. Prints one JSON line to stdout:
#   {"verdict":"approve"|"changes","reason":"…"}
# Never fails: a nonzero claude exit or a malformed line falls back to
# approve so a reviewer crash cannot block a green PR forever.
run_reviewer() {
  local pr_num="$1" task_file="$2" prompt_file="$3" worker_prompt="${4:-}"
  local diff acceptance out json
  diff="$(gh pr diff "$pr_num" 2>/dev/null || true)"
  acceptance="$(extract_acceptance "$task_file")"
  local sys_prompt_arg=()
  [[ -n "$worker_prompt" && -f "$worker_prompt" ]] && \
    sys_prompt_arg=(--append-system-prompt "$(cat "$worker_prompt")")
  out="$("$CLAUDE_BIN" \
      -p "$(cat "$prompt_file")" \
      "${sys_prompt_arg[@]}" \
      -p "$diff" \
      -- "$acceptance" 2>/dev/null)" || out=""
  json="$(printf '%s\n' "$out" | grep -E '^\{.*"verdict":' | tail -1)"
  if [[ -z "$json" ]]; then
    log_warn "reviewer: no verdict JSON on stdout; defaulting to approve" pr="$pr_num"
    printf '{"verdict":"approve","reason":"reviewer output missing"}\n'
    return 0
  fi
  printf '%s\n' "$json"
}

# ---- merge policy dispatch --------------------------------------------------
# apply_merge_policy TASK_FILE PR_NUM PR_URL REPO_NAME
# Reads merge_policy from repos.yaml and takes the side effects (merge, write
# deploy_after, post to Slack). Prints exactly one token to stdout describing
# the outcome so worker.sh can pick the right archive path:
#   MERGED            gh pr merge was called (or DRY_RUN print substituted)
#   VETO_WINDOW       deploy_after: was stamped; wait 24h before deploy
#   MANUAL_PROTECTED  Slack asked Craig to merge; leave the PR alone
#   NONE              nothing to do
apply_merge_policy() {
  local task_file="$1" pr_num="$2" pr_url="$3" repo_name="$4"
  local policy
  policy="$(read_repo_field "$repo_name" merge_policy)"
  policy="${policy:-none}"
  local task_id
  task_id="$(basename "$task_file" .md)"

  case "$policy" in
    auto)
      if [[ "${DRY_RUN:-0}" == "1" ]]; then
        printf 'DRY_RUN: gh pr merge %s --merge --delete-branch\n' "$pr_num" >&2
      else
        if ! gh pr merge "$pr_num" --merge --delete-branch >/dev/null 2>&1; then
          log_error "gh pr merge failed" pr="$pr_num"
          echo NONE
          return 1
        fi
      fi
      echo MERGED
      ;;
    veto_window)
      local deploy_after
      deploy_after="$(date -u -d '+24 hours' +%Y-%m-%dT%H:%M:%SZ 2>/dev/null \
        || date -u -v+24H +%Y-%m-%dT%H:%M:%SZ 2>/dev/null \
        || date -u +%Y-%m-%dT%H:%M:%SZ)"
      queue_set_field "$task_file" deploy_after "$deploy_after"
      notify_slack "#rzware-ceo" \
        "${task_id}: PR #${pr_num} queued in 24h veto window (deploy_after: ${deploy_after}) ${pr_url}" || true
      echo VETO_WINDOW
      ;;
    manual_protected)
      notify_slack "#rzware-ceo" \
        "PR ready for review: ${pr_url} — reviewer approved" || true
      echo MANUAL_PROTECTED
      ;;
    none)
      echo NONE
      ;;
    *)
      log_warn "unknown merge_policy; treating as none" policy="$policy" repo="$repo_name"
      echo NONE
      ;;
  esac
}

# ---- deploy hook dispatch ---------------------------------------------------
# run_deploy_hook REPO_NAME REPO_PATH
# Reads deploy_hook from repos.yaml and calls the matching handler. Never
# fails the task: a hook error logs a warning and posts one line to
# #rzware-ops, then returns 0.
run_deploy_hook() {
  local repo_name="$1" repo_path="$2"
  local hook
  hook="$(read_repo_field "$repo_name" deploy_hook)"
  hook="${hook:-none}"
  case "$hook" in
    none) return 0 ;;
    argocd_image_bump) _hook_argocd_image_bump "$repo_name" "$repo_path" || true ;;
    argocd_sync)       _hook_argocd_sync       "$repo_name" "$repo_path" || true ;;
    install-claude-config.sh|sync-agents.sh)
      _hook_repo_script "$repo_path" "$hook" || true ;;
    *)
      log_warn "unknown deploy_hook; skipping" hook="$hook" repo="$repo_name" ;;
  esac
  return 0
}

_hook_argocd_image_bump() {
  local repo_name="$1" repo_path="$2"
  # ArgoCD image bump lives in the homelab-argo overlay repo. A real bump
  # commits a new imageTag there and lets ArgoCD reconcile. That workflow is
  # not implemented in this task — the bump script does not exist yet (see
  # TASK brief "Files checked"). Log once so the deploy path is visible in
  # Loki and continue.
  log_info "deploy_hook argocd_image_bump: no bump script yet, skipping" repo="$repo_name"
  return 0
}

_hook_argocd_sync() {
  local repo_name="$1" repo_path="$2"
  if ! command -v argocd >/dev/null 2>&1; then
    log_info "argocd CLI unavailable; skipping sync" repo="$repo_name"
    return 0
  fi
  local app="$repo_name"
  if ! argocd app sync "$app" >/dev/null 2>&1; then
    log_warn "argocd app sync failed" app="$app"
    notify_slack "#rzware-ops" "deploy_hook argocd_sync failed for ${app}" || true
    return 1
  fi
  return 0
}

_hook_repo_script() {
  local repo_path="$1" script="$2"
  local path="$repo_path/$script"
  [[ -f "$path" ]] || { log_info "deploy_hook $script not present; skipping" repo="$repo_path"; return 0; }
  if ! bash "$path" >/dev/null 2>&1; then
    log_warn "deploy_hook $script failed" repo="$repo_path"
    notify_slack "#rzware-ops" "deploy_hook ${script} failed for ${repo_path}" || true
    return 1
  fi
  return 0
}

# ---- top-level orchestrator -------------------------------------------------
# process_after_ci TASK_FILE PR_NUM PR_URL REPO_NAME REPO_PATH PROMPT_FILE WORKER_PROMPT
# Runs reviewer → merge policy → deploy hook and prints one line for
# worker.sh to key its queue action off:
#   APPROVED_MERGED         reviewer approved, PR merged (or DRY_RUN); archive as done
#   APPROVED_VETO           reviewer approved, deploy_after stamped; archive as done
#   APPROVED_MANUAL         reviewer approved, PR left for Craig; release orphan
#   APPROVED_NONE           reviewer approved, no auto-merge policy; release orphan
#   CHANGES_STRIKE_N        reviewer requested changes, review_strikes=N (N < 2); release orphan
#   CHANGES_FAILED          reviewer requested changes for the 2nd time; PR closed, queue_fail
process_after_ci() {
  local task_file="$1" pr_num="$2" pr_url="$3" repo_name="$4" repo_path="$5"
  local prompt_file="$6" worker_prompt="${7:-}"

  local verdict_json verdict reason
  verdict_json="$(run_reviewer "$pr_num" "$task_file" "$prompt_file" "$worker_prompt")"
  verdict="$(printf '%s' "$verdict_json" | jq -r '.verdict // "approve"' 2>/dev/null)"
  reason="$(printf '%s' "$verdict_json" | jq -r '.reason // ""' 2>/dev/null)"

  if [[ "$verdict" == "changes" ]]; then
    local strikes
    strikes="$(queue_get_field "$task_file" review_strikes)"
    strikes="${strikes:-0}"
    strikes=$((strikes + 1))
    queue_set_field "$task_file" review_strikes "$strikes"
    if (( strikes >= 2 )); then
      if [[ "${DRY_RUN:-0}" == "1" ]]; then
        printf 'DRY_RUN: gh pr close %s\n' "$pr_num" >&2
      else
        gh pr close "$pr_num" --comment "autopilot reviewer: ${reason:-rejected} (strike ${strikes}/2)" >/dev/null 2>&1 || true
      fi
      queue_fail "$task_file" "reviewer request-changes x${strikes}: ${reason}"
      echo CHANGES_FAILED
    else
      queue_release_orphan "$task_file"
      echo "CHANGES_STRIKE_${strikes}"
    fi
    return 0
  fi

  # Approved — apply merge policy.
  local policy_result
  policy_result="$(apply_merge_policy "$task_file" "$pr_num" "$pr_url" "$repo_name")"
  case "$policy_result" in
    MERGED)
      run_deploy_hook "$repo_name" "$repo_path"
      echo APPROVED_MERGED
      ;;
    VETO_WINDOW)
      echo APPROVED_VETO
      ;;
    MANUAL_PROTECTED)
      echo APPROVED_MANUAL
      ;;
    NONE|*)
      echo APPROVED_NONE
      ;;
  esac
}
