#!/usr/bin/env bash
# Queue manipulation primitives for the dispatcher and workers.
# All functions assume EMF_QUEUE_REPO points at a git working tree with the
# six lifecycle dirs (inbox/ready/approved/in-progress/done/failed).
#
# Source this file from another script:
#   . .claude/dispatcher/lib/queue.sh
#
# Functions:
#   queue_pull                    — git pull --rebase --autostash
#   queue_push_with_retry MSG     — commit (msg) and push, retry on race
#   queue_get_field FILE FIELD    — read scalar frontmatter field
#   queue_set_field FILE FIELD VAL— upsert scalar frontmatter field
#   queue_eligible_tasks          — list approved/*.md eligible to claim
#   queue_claim FILE              — atomic mv approved → in-progress
#   queue_done FILE PR_NUM        — atomic mv in-progress → done
#   queue_fail FILE REASON        — atomic mv in-progress → failed
#   queue_release_orphan FILE     — mv in-progress → approved (worker died)

EMF_QUEUE_REPO="${EMF_QUEUE_REPO:-$HOME/GitHub/emf-queue}"

# ---- Repo registry ----------------------------------------------------------
# Tasks may carry an optional `repo:` frontmatter field. Absent/empty/'emf'
# resolves to the emf mono-repo ($EMF_REPO). Any other name is looked up in a
# small registry: an env var RZWARE_REPO_<NAME_UPPER> takes precedence, then
# $HOME/GitHub/<name> is used as a convention fallback if that directory
# exists. An unknown repo returns nonzero — the caller must fail the task
# rather than silently defaulting to emf: a task landing in the wrong repo is
# worse than a task that fails.

queue_repo_env_var() {
  local name="$1"
  local upper
  upper="$(printf '%s' "$name" | tr '[:lower:]' '[:upper:]' | tr -c 'A-Z0-9' '_')"
  # Strip a trailing underscore from tr -c padding.
  upper="${upper%_}"
  printf 'RZWARE_REPO_%s\n' "$upper"
}

queue_resolve_repo() {
  local name="${1:-}"
  if [[ -z "$name" || "$name" == "emf" ]]; then
    printf '%s\n' "${EMF_REPO:-$HOME/GitHub/emf}"
    return 0
  fi
  local env_var from_env conv
  env_var="$(queue_repo_env_var "$name")"
  from_env="${!env_var:-}"
  if [[ -n "$from_env" ]]; then
    if [[ -d "$from_env" ]]; then
      printf '%s\n' "$from_env"
      return 0
    fi
    printf 'repo %s resolved via %s to %s but that path does not exist\n' \
      "$name" "$env_var" "$from_env" >&2
    return 1
  fi
  conv="$HOME/GitHub/$name"
  if [[ -d "$conv/.git" ]]; then
    printf '%s\n' "$conv"
    return 0
  fi
  printf 'repo %s has no configured path (set %s or create %s)\n' \
    "$name" "$env_var" "$conv" >&2
  return 1
}

# Check whether a repo name appears in the etc/repos.yaml allowlist.
# Usage: queue_repo_allowed <name>   → exit 0 if allowed, 1 otherwise.
# Reads the file relative to this script: $SELF_DIR/../etc/repos.yaml.
# Returns 1 (denied) if the file is absent or the name is not found.
queue_repo_allowed() {
  local name="${1:-}"
  local SELF_DIR
  SELF_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
  local yaml="$SELF_DIR/../etc/repos.yaml"
  if [[ ! -f "$yaml" ]]; then
    printf 'queue_repo_allowed: %s not found — denying %s\n' "$yaml" "$name" >&2
    return 1
  fi
  grep -q "^- name: ${name}$" "$yaml"
}

# Resolve the default branch of a repo (main, master, ...) from origin/HEAD.
# Falls back to 'main' if origin/HEAD is unset and cannot be discovered.
queue_repo_default_branch() {
  local repo_path="$1"
  local head_ref
  head_ref="$(git -C "$repo_path" symbolic-ref refs/remotes/origin/HEAD 2>/dev/null)"
  if [[ -z "$head_ref" ]]; then
    git -C "$repo_path" remote set-head origin --auto >/dev/null 2>&1 || true
    head_ref="$(git -C "$repo_path" symbolic-ref refs/remotes/origin/HEAD 2>/dev/null)"
  fi
  if [[ -n "$head_ref" ]]; then
    printf '%s\n' "${head_ref##refs/remotes/origin/}"
    return 0
  fi
  printf 'main\n'
}

# ---- Internal helpers --------------------------------------------------------

_q() { git -C "$EMF_QUEUE_REPO" "$@"; }

_q_in_progress_count_by_owner() {
  local owner="$1"
  local n=0 f
  for f in "$EMF_QUEUE_REPO"/in-progress/*.md; do
    [[ -e "$f" ]] || continue
    [[ "$(queue_get_field "$f" owner)" == "$owner" ]] && n=$((n+1))
  done
  echo "$n"
}

# ---- Public API --------------------------------------------------------------

queue_pull() {
  _q pull --rebase --autostash 2>&1 || return 1
}

queue_push_with_retry() {
  local msg="$1"
  local tries=0
  while (( tries < 3 )); do
    _q add -A
    if _q diff --cached --quiet; then
      return 0   # nothing to commit
    fi
    _q commit -m "$msg" >/dev/null 2>&1 || return 2
    if _q push 2>/dev/null; then
      return 0
    fi
    tries=$((tries+1))
    _q pull --rebase --autostash >/dev/null 2>&1 || return 3
  done
  return 4
}

# Read a scalar frontmatter field: 'foo: bar' → echoes 'bar'.
queue_get_field() {
  local file="$1" field="$2"
  awk -v f="$field" '
    BEGIN{in_fm=0}
    /^---[[:space:]]*$/{ in_fm = (in_fm ? 0 : 1); next }
    in_fm && $0 ~ "^"f":[[:space:]]" {
      sub("^"f":[[:space:]]*", "")
      sub(/^"/, ""); sub(/"$/, "")
      sub(/^'\''/, ""); sub(/'\''$/, "")
      print; exit
    }
  ' "$file"
}

# Upsert a scalar frontmatter field. Inserts before the closing '---' if
# the field doesn't exist.
queue_set_field() {
  local file="$1" field="$2" val="$3"
  if grep -q "^${field}:" "$file" 2>/dev/null; then
    sed -i.bak -E "s|^${field}:.*|${field}: ${val}|" "$file" && rm -f "${file}.bak"
  else
    awk -v line="${field}: ${val}" '
      BEGIN{seen=0; inserted=0}
      /^---[[:space:]]*$/{
        if (seen==0){ print; seen=1; next }
        if (seen==1 && !inserted){ print line; inserted=1 }
      }
      {print}
    ' "$file" > "$file.tmp" && mv "$file.tmp" "$file"
  fi
}

# List approved tasks in claim order. Filters:
#   - status: approved
#   - depends_on: every dep id must be in done/
#   - parallel_safe: false → only if no other in-progress task exists
#   - needs_migration: true → only if _active-migration marker absent
# Sort: priority asc, then created_at asc.
queue_eligible_tasks() {
  local active_in_progress=0
  active_in_progress=$(ls "$EMF_QUEUE_REPO"/in-progress/*.md 2>/dev/null | wc -l | tr -d ' ')
  local migration_locked="false"
  [[ -f "$EMF_QUEUE_REPO/_active-migration" ]] && migration_locked="true"

  local tmp
  tmp="$(mktemp -t qeligible.XXXX)"
  local f
  for f in "$EMF_QUEUE_REPO"/approved/*.md; do
    [[ -e "$f" ]] || continue
    local id pri created par_safe needs_mig deps eligible=1
    id="$(queue_get_field "$f" id)"
    pri="$(queue_get_field "$f" priority)"; pri="${pri:-5}"
    created="$(queue_get_field "$f" created_at)"
    par_safe="$(queue_get_field "$f" parallel_safe)"
    needs_mig="$(queue_get_field "$f" needs_migration)"
    deps="$(queue_get_field "$f" depends_on)"

    # Migration lock
    if [[ "$needs_mig" == "true" && "$migration_locked" == "true" ]]; then
      eligible=0
    fi
    # Parallel-safe check: if any other in-progress task exists and this one
    # isn't parallel_safe, skip.
    if [[ "$par_safe" == "false" && "$active_in_progress" -gt 0 ]]; then
      eligible=0
    fi
    # Dependency check: every id in depends_on must be in done/.
    if [[ -n "$deps" && "$deps" != "[]" ]]; then
      local d_ids
      d_ids="$(printf '%s\n' "$deps" | tr -d '[]" ' | tr ',' '\n')"
      local d
      for d in $d_ids; do
        [[ -z "$d" ]] && continue
        if [[ ! -f "$EMF_QUEUE_REPO/done/$d.md" ]]; then
          eligible=0; break
        fi
      done
    fi
    if (( eligible == 1 )); then
      printf '%s\t%s\t%s\n' "$pri" "$created" "$f" >> "$tmp"
    fi
  done

  sort -k1,1n -k2,2 "$tmp" | cut -f3
  rm -f "$tmp"
}

# Atomic claim. Echoes the new in-progress path, or empty + nonzero exit on race.
queue_claim() {
  local src="$1"
  local owner="${2:-${HOSTNAME:-$(hostname)}}"
  local base id dest branch attempts
  base="$(basename "$src")"
  id="${base%.md}"
  dest="$EMF_QUEUE_REPO/in-progress/$base"
  branch="autopilot/$id"
  attempts="$(queue_get_field "$src" attempts)"; attempts="${attempts:-0}"

  queue_pull >/dev/null 2>&1 || return 1
  if [[ ! -f "$src" ]]; then
    return 2   # someone else claimed it during pull
  fi

  _q mv "approved/$base" "in-progress/$base"
  queue_set_field "$dest" status "in_progress"
  queue_set_field "$dest" owner "$owner"
  queue_set_field "$dest" branch "$branch"
  queue_set_field "$dest" attempts "$((attempts + 1))"

  if queue_push_with_retry "claim $id by $owner (attempt $((attempts + 1)))"; then
    echo "$dest"
    return 0
  fi
  return 3
}

queue_done() {
  local src="$1" pr="$2"
  local base id dest
  base="$(basename "$src")"
  id="${base%.md}"
  dest="$EMF_QUEUE_REPO/done/$base"

  _q mv "in-progress/$base" "done/$base"
  queue_set_field "$dest" status "done"
  [[ -n "$pr" ]] && queue_set_field "$dest" pr "$pr"

  # If this task held the migration lock, release it.
  local needs_mig
  needs_mig="$(queue_get_field "$dest" needs_migration)"
  if [[ "$needs_mig" == "true" && -f "$EMF_QUEUE_REPO/_active-migration" ]]; then
    _q rm -f "_active-migration" >/dev/null 2>&1
  fi

  queue_push_with_retry "done $id (pr #$pr)"
}

queue_fail() {
  local src="$1" reason="$2"
  local base id dest
  base="$(basename "$src")"
  id="${base%.md}"
  dest="$EMF_QUEUE_REPO/failed/$base"

  _q mv "in-progress/$base" "failed/$base"
  queue_set_field "$dest" status "failed"
  queue_set_field "$dest" fail_reason "${reason//$'\n'/ }"

  # Release migration lock if held.
  local needs_mig
  needs_mig="$(queue_get_field "$dest" needs_migration)"
  if [[ "$needs_mig" == "true" && -f "$EMF_QUEUE_REPO/_active-migration" ]]; then
    _q rm -f "_active-migration" >/dev/null 2>&1
  fi

  queue_push_with_retry "fail $id: ${reason:0:80}"
}

queue_release_orphan() {
  local src="$1"
  local base id
  base="$(basename "$src")"
  id="${base%.md}"
  _q mv "in-progress/$base" "approved/$base" 2>/dev/null
  queue_set_field "$EMF_QUEUE_REPO/approved/$base" status "approved"
  queue_set_field "$EMF_QUEUE_REPO/approved/$base" owner "null"
  queue_push_with_retry "release orphan $id (worker died)"
}

# --- budget: subscription self-throttle (BUDGET.md) -------------------------
# worker.sh writes state/PAUSED_UNTIL when the stream reports a usage limit.
# dispatch.sh calls this at the top of every tick and claims nothing while set.
PAUSE_FILE="${PAUSE_FILE:-/srv/rzware-ceo/state/PAUSED_UNTIL}"
throttled() {
  [ -f "$PAUSE_FILE" ] || return 1
  local until; until=$(cat "$PAUSE_FILE" 2>/dev/null)
  if [ -n "$until" ] && [ "$(date +%s)" -lt "$until" ]; then return 0; fi
  rm -f "$PAUSE_FILE"; return 1
}
# --- when the fleet may claim work ------------------------------------------
# RUN_WINDOW=nightly  22:00-06:00 America/Denver (the original BUDGET.md §4 rule)
# RUN_WINDOW=always   any hour (the setting in use)
#
# The window was only ever a proxy for "do not spend the five-hour session
# quota Craig is using". Two better guards exist now — MAX_PARALLEL=1 and
# detect_usage_limit() below, which throttles on a real limit signal rather
# than on the clock — so the clock can stop standing in for them.
#
# One serial worker is the whole budget control. Nothing watches for Craig's
# own sessions: at MAX_PARALLEL=1 the fleet's draw is bounded, and if it does
# collide with him the throttle backs the fleet off on a real signal.
in_run_window() {
  [ "${FORCE_RUN:-0}" = "1" ] && return 0
  [ "${RUN_WINDOW:-nightly}" = "always" ] && return 0
  local h; h=$(TZ=America/Denver date +%-H)
  [ "$h" -ge 22 ] || [ "$h" -lt 6 ]
}

# --- budget: daily spend ceiling (D-010) -------------------------------------
# Sums estimated_cost_usd from cost-*.json files in EMF_LOG_DIR whose ts falls
# on today in America/Denver. Returns 0 (exceeded) when sum >= ceiling; 1 when
# not exceeded or DAILY_COST_CEILING_USD is unset/empty.
#
# Running workers are never killed — this gate only stops new claims.
# dispatch.sh checks it every tick; the gate state ("ceiling") lets the caller
# log only on transition (same pattern as throttled / in_run_window).
cost_ceiling_exceeded() {
  [[ -z "${DAILY_COST_CEILING_USD:-}" ]] && return 1
  command -v jq >/dev/null 2>&1 || return 1

  local today log_dir
  today="$(TZ=America/Denver date +%Y-%m-%d)"
  log_dir="${EMF_LOG_DIR:-/var/log/emf-dispatcher}"

  local total=0 f ts cost file_day
  shopt -s nullglob
  for f in "$log_dir"/cost-*.json; do
    ts="$(jq -r '.ts // empty' "$f" 2>/dev/null)"
    [[ -n "$ts" ]] || continue
    file_day="$(TZ=America/Denver date -d "$ts" +%Y-%m-%d 2>/dev/null)" || continue
    [[ "$file_day" == "$today" ]] || continue
    cost="$(jq -r '.estimated_cost_usd // 0' "$f" 2>/dev/null)"
    total="$(awk -v a="$total" -v b="${cost:-0}" 'BEGIN { printf "%.6f", a+b }')"
  done
  shopt -u nullglob

  awk -v t="$total" -v c="$DAILY_COST_CEILING_USD" 'BEGIN { exit (t+0 >= c+0 ? 0 : 1) }'
}

# ---------------------------------------------------------------------------

# --- budget: detect a usage-limit signal and self-throttle (BUDGET.md §13) --
# worker.sh calls this right after the claude run. On a subscription-limit
# signal it writes PAUSE_FILE with the epoch to resume at; throttled() in
# dispatch.sh then stops the fleet claiming, with no human in the path.
#
# This reads the run's *outcome* fields, never the whole log as text.
#
# It used to grep the log for literal strings, narrowed to avoid the
# RateLimiter.java false positive. That was not narrow enough. Two of those
# strings live in this very function, and TASK-2026-08-31-0003 was a task to
# parameterize the dispatcher — so the worker read lib/queue.sh, the
# stream-json log captured the file verbatim, and the detector matched its own
# source. It paused the whole fleet on 31 Aug for an hour against a
# subscription that was nowhere near its limit, and blocked every shell
# command behind guard-bash.sh rule 7 while it did.
#
# Any text search over the log has that failure mode permanently, because the
# detector lives in the repo the fleet edits. So: parse the JSONL and look only
# at fields the CLI itself writes as the result of the run — the terminal
# result line, an error object, and synthetic assistant messages. File content
# arrives inside tool_use/tool_result blocks, which are never consulted.
detect_usage_limit() {
  local log="${1:-}" until=""
  [ -n "$log" ] && [ -f "$log" ] || return 1
  command -v python3 >/dev/null 2>&1 || return 1

  until="$(python3 - "$log" <<'PY'
import json, re, sys, time

INDICATORS = ("usage limit reached", "usage_limit_reached",
              "rate_limit_error", "rate limit exceeded")

def outcome_fields(o):
    """Only what the CLI reports about the run itself. Never nested content."""
    t = o.get("type")
    if t == "result" and o.get("is_error"):
        yield str(o.get("result") or "")
        yield str(o.get("subtype") or "")
    elif t == "error":
        yield str(o.get("message") or "")
    elif t == "assistant":
        m = o.get("message") or {}
        if m.get("model") == "<synthetic>":      # CLI-generated, not model output
            for c in m.get("content") or []:
                if isinstance(c, dict) and c.get("type") == "text":
                    yield str(c.get("text") or "")
    e = o.get("error")
    if isinstance(e, dict):
        yield str(e.get("type") or "")
        yield str(e.get("message") or "")

def reset_epoch(o, text):
    m = re.search(r"(\d{10,})", text)            # "…reached|1788212345"
    if m:
        return int(m.group(1))
    src = o.get("error") if isinstance(o.get("error"), dict) else o
    for k in ("resets_at", "resetsAt", "reset_at"):
        v = src.get(k)
        if isinstance(v, (int, float)):
            return int(v)
        if isinstance(v, str):
            m = re.search(r"\d{10,}", v)
            if m:
                return int(m.group())
    return None

hit, reset = False, None
with open(sys.argv[1], errors="replace") as fh:
    for line in fh:
        line = line.lstrip()
        if not line.startswith("{"):
            continue
        try:
            o = json.loads(line)
        except ValueError:
            continue
        if not isinstance(o, dict):
            continue
        for text in outcome_fields(o):
            if any(i in text.lower() for i in INDICATORS):
                hit = True
                reset = reset or reset_epoch(o, text)

if not hit:
    sys.exit(1)
now = int(time.time())
# No parseable reset: wait an hour and let the next tick re-evaluate.
print(reset if reset and reset > now else now + 3600)
PY
)" || return 1
  [ -n "$until" ] || return 1

  mkdir -p "$(dirname "$PAUSE_FILE")" 2>/dev/null || true
  printf '%s\n' "$until" > "$PAUSE_FILE" 2>/dev/null || return 1
  return 0
}
