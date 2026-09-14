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
#   REPOS_YAML          override for the allowlist path (default: ../etc/repos.yaml
#                       relative to this script)
#   CLAUDE_BIN          claude CLI (default 'claude')
#   HOMELAB_ARGO_REPO   argocd overlay repo path (default $HOME/GitHub/homelab-argo)
#   DEPLOY_HEALTH_DIR   post-bump SHA marker dir (default /srv/rzware-ceo/state/deploy-health);
#                       sibling of $GATE_STATE_DIR from agents/gate.sh line 33
#   DRY_RUN=1           skip gh/git/argocd side effects; still write the
#                       deploy-health marker and print DRY_RUN diagnostics

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
    argocd_image_bump)
      # OPERATING-MODEL.md §10 item 12 + §9: the bump commits a new image tag
      # to homelab-argo; run_health_check then waits, probes health_url, and
      # reverts on failure. The health-check wait happens inline so the worker
      # process stays alive to post the outcome to Slack.
      _hook_argocd_image_bump "$repo_name" "$repo_path" || true
      run_health_check "$repo_name" || true
      ;;
    argocd_sync)       _hook_argocd_sync       "$repo_name" "$repo_path" || true ;;
    install-claude-config.sh|sync-agents.sh)
      _hook_repo_script "$repo_path" "$hook" || true ;;
    *)
      log_warn "unknown deploy_hook; skipping" hook="$hook" repo="$repo_name" ;;
  esac
  return 0
}

# Overlay-shape convention in homelab-argo, verified against the tree at time
# of writing:
#   - emf/           → kustomization.yaml with an images:/newTag: block
#                      (8 harbor.rzware.com/emf/* images bumped in lockstep;
#                      `kustomize edit set image` is the intended tool but a
#                      literal sed matches the tag pattern in every case).
#   - spotopened-web/  → deployment.yaml with a hardcoded
#                        image: harbor.rzware.com/spotopened/spotopened-web:main-<sha>
#   - couchpicks-web/  → deployment.yaml with a hardcoded
#                        image: harbor.rzware.com/couchpicks/couchpicks-web:main-<sha>
#                        (repos.yaml calls this repo `couchpicks`; the overlay
#                        dir adds a "-web" suffix — probe both).
_hook_argocd_overlay_dir() {
  local repo_name="$1"
  local hla="${HOMELAB_ARGO_REPO:-$HOME/GitHub/homelab-argo}"
  local cand
  for cand in "$hla/$repo_name" "$hla/${repo_name}-web"; do
    if [[ -d "$cand" ]]; then
      printf '%s\n' "$cand"
      return 0
    fi
  done
  return 1
}

# Collect the tag-carrying files in an overlay: the kustomization.yaml if it
# has a newTag: block, plus any *deployment.yaml with a `main-<sha>` image tag.
# Writes one path per line to stdout.
_hook_argocd_tag_files() {
  local overlay_dir="$1"
  local kust="$overlay_dir/kustomization.yaml"
  if grep -qE '^[[:space:]]*newTag:' "$kust" 2>/dev/null; then
    printf '%s\n' "$kust"
  fi
  local f
  while IFS= read -r f; do
    grep -qE 'image:.*:main-[0-9a-f]{6,10}' "$f" 2>/dev/null && printf '%s\n' "$f"
  done < <(find "$overlay_dir" -maxdepth 1 -name '*deployment.yaml' 2>/dev/null)
}

_hook_argocd_image_bump() {
  local repo_name="$1" repo_path="$2"
  local sha short_sha
  sha="$(git -C "$repo_path" rev-parse HEAD 2>/dev/null || echo unknown)"
  short_sha="${sha:0:7}"

  local overlay_dir
  if ! overlay_dir="$(_hook_argocd_overlay_dir "$repo_name")"; then
    log_warn "argocd_image_bump: no homelab-argo overlay found; skipping" repo="$repo_name"
    return 0
  fi
  local hla
  hla="$(dirname "$overlay_dir")"

  local -a files=()
  mapfile -t files < <(_hook_argocd_tag_files "$overlay_dir")
  if (( ${#files[@]} == 0 )); then
    log_warn "argocd_image_bump: no tag fields found in overlay; skipping" \
      overlay="$overlay_dir" repo="$repo_name"
    return 0
  fi

  # Write the health-state marker unconditionally so run_health_check can find
  # the bumped SHA (and so DRY_RUN observably touches the sibling path noted in
  # agents/gate.sh line 33).
  local health_dir="${DEPLOY_HEALTH_DIR:-/srv/rzware-ceo/state/deploy-health}"
  mkdir -p "$health_dir" 2>/dev/null || true
  {
    printf 'sha=%s\n' "$sha"
    printf 'ts=%s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
    printf 'repo=%s\n' "$repo_name"
  } > "$health_dir/$repo_name" 2>/dev/null || true

  if [[ "${DRY_RUN:-0}" == "1" ]]; then
    printf 'DRY_RUN: would bump homelab-argo %s to %s\n' "$repo_name" "$sha" >&2
    return 0
  fi

  local f
  for f in "${files[@]}"; do
    sed -i.bak -E "s|main-[0-9a-f]{6,10}|main-${short_sha}|g" "$f" && rm -f "${f}.bak"
  done

  git -C "$hla" add -- "${files[@]}" 2>/dev/null || true
  if git -C "$hla" diff --cached --quiet 2>/dev/null; then
    log_info "argocd_image_bump: no diff after tag rewrite; already at $short_sha" repo="$repo_name"
    return 0
  fi
  if ! git -C "$hla" -c user.email="autopilot@rzware.com" -c user.name="autopilot" \
       commit -m "chore: bump $repo_name to $short_sha [autopilot]" >/dev/null 2>&1; then
    log_warn "argocd_image_bump: commit failed" repo="$repo_name"
    return 1
  fi
  if ! git -C "$hla" push >/dev/null 2>&1; then
    log_warn "argocd_image_bump: push failed; pull --rebase + retry" repo="$repo_name"
    if ! git -C "$hla" pull --rebase >/dev/null 2>&1 || \
       ! git -C "$hla" push >/dev/null 2>&1; then
      notify_slack "#rzware-ops" "argocd_image_bump push failed for ${repo_name}" || true
      return 1
    fi
  fi

  if command -v argocd >/dev/null 2>&1; then
    argocd app sync "$repo_name" >/dev/null 2>&1 \
      || log_warn "argocd app sync failed" app="$repo_name"
  else
    log_info "argocd CLI not present; ArgoCD auto-sync will pick up the commit" repo="$repo_name"
  fi
  return 0
}

# Revert the last homelab-argo tag change for $repo_name by rewriting each
# tag-carrying file to its previous git-committed contents. Used by
# run_health_check when a post-deploy probe fails.
_hook_argocd_revert() {
  local repo_name="$1" bumped_sha="$2"
  local overlay_dir
  if ! overlay_dir="$(_hook_argocd_overlay_dir "$repo_name")"; then
    log_warn "argocd revert: no overlay found" repo="$repo_name"
    return 0
  fi
  local hla
  hla="$(dirname "$overlay_dir")"

  local -a files=()
  mapfile -t files < <(_hook_argocd_tag_files "$overlay_dir")
  (( ${#files[@]} == 0 )) && return 0

  local f rel prev
  for f in "${files[@]}"; do
    rel="$(git -C "$hla" ls-files --full-name -- "$f" 2>/dev/null)"
    [[ -n "$rel" ]] || continue
    prev="$(git -C "$hla" log --format=%H -n 2 -- "$rel" 2>/dev/null | tail -1)"
    [[ -n "$prev" ]] || continue
    git -C "$hla" show "$prev:$rel" > "$f" 2>/dev/null || true
  done

  if [[ "${DRY_RUN:-0}" == "1" ]]; then
    printf 'DRY_RUN: would revert homelab-argo %s from %s\n' "$repo_name" "$bumped_sha" >&2
    return 0
  fi

  git -C "$hla" add -- "${files[@]}" 2>/dev/null || true
  if git -C "$hla" diff --cached --quiet 2>/dev/null; then
    log_info "argocd revert: no diff (file already at previous state)" repo="$repo_name"
    return 0
  fi
  git -C "$hla" -c user.email="autopilot@rzware.com" -c user.name="autopilot" \
    commit -m "chore: revert $repo_name from $bumped_sha [autopilot health check]" \
    >/dev/null 2>&1 || return 0
  git -C "$hla" push >/dev/null 2>&1 \
    || log_warn "argocd revert push failed; local commit remains" repo="$repo_name"
  return 0
}

# File a needs_clarification task in emf-queue/failed/ when a health check
# reverts a deploy — human loop, not a retry.
_hook_file_health_failure_task() {
  local repo_name="$1" bumped_sha="$2" code="$3" pr_num="${4:-}"
  local qroot="${EMF_QUEUE_REPO:-$HOME/GitHub/emf-queue}"
  local failed_dir="$qroot/failed"
  [[ -d "$failed_dir" ]] || return 0
  local now id path
  now="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  id="NEEDS-CLARIFY-$(date -u +%Y-%m-%d-%H%M%S)-${repo_name}"
  path="$failed_dir/${id}.md"
  cat > "$path" <<EOF
---
id: ${id}
title: "health check failed for ${repo_name} after deploy ${bumped_sha}"
type: needs_clarification
status: failed
repo: ${repo_name}
pr: ${pr_num}
fail_reason: "post-deploy health check returned HTTP ${code}; reverted homelab-argo tag from ${bumped_sha}"
created_at: "${now}"
---

Post-deploy health check failed for repo \`${repo_name}\` after autopilot bumped
the homelab-argo image tag to \`${bumped_sha}\`. The homelab-argo tag has been
reverted to the previous SHA. Health probe HTTP status: ${code}.
EOF
  return 0
}

# run_health_check REPO_NAME
# Called by run_deploy_hook immediately after a successful _hook_argocd_image_bump.
# Sleeps 300s (DRY_RUN=1: 1s), then curls the repos.yaml health_url. Non-2xx
# reverts the tag, files a needs_clarification, and posts one Slack line to
# #rzware-ops. Always returns 0 — a health failure must not kill the worker.
run_health_check() {
  local repo_name="$1"
  local health_dir="${DEPLOY_HEALTH_DIR:-/srv/rzware-ceo/state/deploy-health}"
  local state_file="$health_dir/$repo_name"

  if [[ ! -f "$state_file" ]]; then
    log_warn "run_health_check: no deploy-health state; skipping" repo="$repo_name"
    return 0
  fi

  local wait_sec=300
  [[ "${DRY_RUN:-0}" == "1" ]] && wait_sec=1
  sleep "$wait_sec"

  local health_url
  health_url="$(read_repo_field "$repo_name" health_url)"
  if [[ -z "$health_url" || "$health_url" == "none" ]]; then
    log_info "run_health_check: no health_url in repos.yaml; skipping" repo="$repo_name"
    return 0
  fi

  local code
  code="$(curl -s -o /dev/null -w '%{http_code}' --max-time 10 "$health_url" 2>/dev/null || echo 000)"
  if [[ "$code" =~ ^2[0-9][0-9]$ ]]; then
    log_info "run_health_check: healthy" repo="$repo_name" code="$code" url="$health_url"
    return 0
  fi

  local bumped_sha
  bumped_sha="$(awk -F= '$1=="sha"{print $2; exit}' "$state_file" 2>/dev/null)"
  log_warn "run_health_check: probe failed; reverting" \
    repo="$repo_name" code="$code" sha="$bumped_sha" url="$health_url"
  notify_slack "#rzware-ops" \
    "health check failed for ${repo_name} after deploy ${bumped_sha} — reverting (HTTP ${code})" || true
  _hook_argocd_revert "$repo_name" "$bumped_sha" || true
  _hook_file_health_failure_task "$repo_name" "$bumped_sha" "$code" || true
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
