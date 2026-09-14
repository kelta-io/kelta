#!/usr/bin/env bash
# Unit tests for _hook_argocd_image_bump + run_health_check in
# lib/deploy-hooks.sh (OPERATING-MODEL.md §9-§10 item 12).
#
# Invoked directly (`bash health-test.sh`). Not part of /verify — the
# dispatcher lives outside the maven/npm build.
#
# All external commands (git, argocd, curl, notify_slack) are stubbed onto a
# private PATH. HOMELAB_ARGO_REPO points at a mktemp'd fake overlay; the
# deploy-health marker dir and emf-queue root are also mktemp'd. DRY_RUN=1
# is used everywhere so no real git/argocd/curl call escapes.

set -u
SELF_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
LOG_LIB="$SELF_DIR/../lib/log.sh"
QUEUE_LIB="$SELF_DIR/../lib/queue.sh"
NOTIFY_LIB="$SELF_DIR/../lib/notify.sh"
DEPLOY_LIB="$SELF_DIR/../lib/deploy-hooks.sh"

for f in "$LOG_LIB" "$QUEUE_LIB" "$NOTIFY_LIB" "$DEPLOY_LIB"; do
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
log_init "health-test"

# ---- Fixtures --------------------------------------------------------------
# repos.yaml with the three argocd_image_bump repos + one none entry.

mkdir -p "$TMP/etc"
cat > "$TMP/etc/repos.yaml" <<'YAML'
- name: emf
  clone_path: /tmp/emf
  default_branch: main
  merge_policy: auto
  deploy_hook: argocd_image_bump
  health_url: https://api.kelta.io/actuator/health
  tier_ceiling: 1

- name: spotopened-web
  clone_path: /tmp/spotopened-web
  default_branch: main
  merge_policy: auto
  deploy_hook: argocd_image_bump
  health_url: https://app.spotopened.com/
  tier_ceiling: 1

- name: couchpicks
  clone_path: /tmp/couchpicks
  default_branch: main
  merge_policy: auto
  deploy_hook: argocd_image_bump
  health_url: https://www.couchpicks.tv/
  tier_ceiling: 1

- name: other
  clone_path: /tmp/other
  default_branch: main
  merge_policy: none
  deploy_hook: none
  health_url: none
  tier_ceiling: 0
YAML
export REPOS_YAML="$TMP/etc/repos.yaml"

# Fake homelab-argo overlay: a git repo with an emf/ overlay whose
# kustomization.yaml carries a real newTag: line (matches the shape used by
# the emf overlay in the real homelab-argo tree). Two commits so revert has
# a previous SHA to check out.
mkdir -p "$TMP/homelab-argo/emf"
cat > "$TMP/homelab-argo/emf/kustomization.yaml" <<'YAML'
apiVersion: kustomize.config.k8s.io/v1beta1
kind: Kustomization
images:
- name: harbor.rzware.com/emf/emf-gateway
  newTag: main-aaaaaaa
resources:
- namespace.yaml
YAML
git -C "$TMP/homelab-argo" init -q
git -C "$TMP/homelab-argo" -c user.email=t@t -c user.name=t add emf/kustomization.yaml >/dev/null
git -C "$TMP/homelab-argo" -c user.email=t@t -c user.name=t commit -q -m "initial tag"
# A second commit so `git log -n 2` returns a distinct previous SHA.
cat > "$TMP/homelab-argo/emf/kustomization.yaml" <<'YAML'
apiVersion: kustomize.config.k8s.io/v1beta1
kind: Kustomization
images:
- name: harbor.rzware.com/emf/emf-gateway
  newTag: main-bbbbbbb
resources:
- namespace.yaml
YAML
git -C "$TMP/homelab-argo" -c user.email=t@t -c user.name=t add emf/kustomization.yaml >/dev/null
git -C "$TMP/homelab-argo" -c user.email=t@t -c user.name=t commit -q -m "second tag"
export HOMELAB_ARGO_REPO="$TMP/homelab-argo"

# Fake emf source repo (for git rev-parse HEAD): another git repo with one
# commit. The bump function reads its HEAD SHA.
mkdir -p "$TMP/emf-src"
git -C "$TMP/emf-src" init -q
echo hi > "$TMP/emf-src/README.md"
git -C "$TMP/emf-src" -c user.email=t@t -c user.name=t add README.md >/dev/null
git -C "$TMP/emf-src" -c user.email=t@t -c user.name=t commit -q -m "seed"

# Overridable dirs.
export DEPLOY_HEALTH_DIR="$TMP/deploy-health"
export EMF_QUEUE_REPO="$TMP/emf-queue"
mkdir -p "$EMF_QUEUE_REPO/failed"

# ---- Stubs -----------------------------------------------------------------
# Curl is the only external call in run_health_check. The stub reads the
# desired status from $TMP/curl-status, defaulting to 200. All calls are
# logged to $TMP/calls.log for assertions.
mkdir -p "$TMP/stubs"
cat > "$TMP/stubs/curl" <<EOF
#!/usr/bin/env bash
echo "curl \$*" >> "$TMP/calls.log"
if [[ -f "$TMP/curl-status" ]]; then
  cat "$TMP/curl-status"
else
  echo 200
fi
exit 0
EOF
chmod +x "$TMP/stubs/curl"

OLD_PATH="$PATH"
PATH="$TMP/stubs:$OLD_PATH"

# In-process Slack override so tests don't touch sops.
notify_slack() {
  echo "notify_slack $*" >> "$TMP/calls.log"
  return 0
}

reset_calls() { : > "$TMP/calls.log"; }

# ---- 1. bash -n on the library --------------------------------------------

if bash -n "$DEPLOY_LIB" 2>/dev/null; then
  _ok "bash -n lib/deploy-hooks.sh"
else
  _no "bash -n lib/deploy-hooks.sh" "syntax error"
fi

# ---- 2. DRY_RUN bump prints the expected line and writes health state -----
# Acceptance:
#   DRY_RUN=1 _hook_argocd_image_bump emf $repo_path
#     → 'DRY_RUN: would bump homelab-argo emf to <sha>' on stderr
#     → writes $DEPLOY_HEALTH_DIR/emf
#     → no git/argocd calls made (fake homelab-argo is untouched)

reset_calls
rm -rf "$DEPLOY_HEALTH_DIR"
before_log_sha="$(git -C "$HOMELAB_ARGO_REPO" rev-parse HEAD)"
stderr_out="$(DRY_RUN=1 _hook_argocd_image_bump emf "$TMP/emf-src" 2>&1 >/dev/null)"
rc=$?
assert_zero "DRY_RUN bump: exit 0" "$rc"
expected_sha="$(git -C "$TMP/emf-src" rev-parse HEAD)"
if [[ "$stderr_out" == "DRY_RUN: would bump homelab-argo emf to ${expected_sha}" ]]; then
  _ok "DRY_RUN bump: stderr line matches acceptance"
else
  _no "DRY_RUN bump: stderr line" "want=[DRY_RUN: would bump homelab-argo emf to ${expected_sha}] got=[$stderr_out]"
fi
[[ -f "$DEPLOY_HEALTH_DIR/emf" ]] \
  && _ok "DRY_RUN bump: health-state file written to \$DEPLOY_HEALTH_DIR/emf" \
  || _no "DRY_RUN bump: health-state file written" "missing $DEPLOY_HEALTH_DIR/emf"
after_log_sha="$(git -C "$HOMELAB_ARGO_REPO" rev-parse HEAD)"
[[ "$before_log_sha" == "$after_log_sha" ]] \
  && _ok "DRY_RUN bump: no git commit in homelab-argo" \
  || _no "DRY_RUN bump: no git commit" "HEAD moved $before_log_sha → $after_log_sha"

# ---- 3. Healthy curl stub (200) → no revert, no Slack ---------------------

reset_calls
# Pre-populate deploy-health so run_health_check has state to read.
mkdir -p "$DEPLOY_HEALTH_DIR"
printf 'sha=abcdef1\nts=2026-09-14T00:00:00Z\nrepo=emf\n' > "$DEPLOY_HEALTH_DIR/emf"
echo 200 > "$TMP/curl-status"
before_head="$(git -C "$HOMELAB_ARGO_REPO" rev-parse HEAD)"
DRY_RUN=1 run_health_check emf
rc=$?
assert_zero "healthy check: exit 0" "$rc"
grep -q '^curl ' "$TMP/calls.log" \
  && _ok "healthy check: curl invoked" \
  || _no "healthy check: curl invoked" "no curl call in calls.log"
grep -q '^notify_slack ' "$TMP/calls.log" \
  && _no "healthy check: no Slack post" "unexpected notify_slack call" \
  || _ok "healthy check: no Slack post"
after_head="$(git -C "$HOMELAB_ARGO_REPO" rev-parse HEAD)"
[[ "$before_head" == "$after_head" ]] \
  && _ok "healthy check: no revert commit" \
  || _no "healthy check: no revert" "HEAD moved"

# ---- 4. Unhealthy curl stub (503) → revert commit + Slack post ------------

reset_calls
mkdir -p "$DEPLOY_HEALTH_DIR"
printf 'sha=abcdef1\nts=2026-09-14T00:00:00Z\nrepo=emf\n' > "$DEPLOY_HEALTH_DIR/emf"
echo 503 > "$TMP/curl-status"
# DRY_RUN=1 keeps the sleep short (1s) but still exercises the full unhealthy
# path: notify_slack fires, _hook_argocd_revert prints its DRY_RUN diagnostic,
# _hook_file_health_failure_task writes into $EMF_QUEUE_REPO/failed/.
stderr_out="$(DRY_RUN=1 run_health_check emf 2>&1 >/dev/null)"
rc=$?
assert_zero "unhealthy check (DRY_RUN): exit 0" "$rc"
grep -q '^notify_slack #rzware-ops ' "$TMP/calls.log" \
  && _ok "unhealthy check: Slack post to #rzware-ops" \
  || _no "unhealthy check: Slack post" "not found in calls.log"
grep -q 'DRY_RUN: would revert homelab-argo emf' <<<"$stderr_out" \
  && _ok "unhealthy check: DRY_RUN revert diagnostic printed" \
  || _no "unhealthy check: DRY_RUN revert diagnostic" "got=[$stderr_out]"
# needs_clarification task filed in failed/
ls "$EMF_QUEUE_REPO/failed"/NEEDS-CLARIFY-*-emf.md >/dev/null 2>&1 \
  && _ok "unhealthy check: needs_clarification filed in failed/" \
  || _no "unhealthy check: needs_clarification filed" "no NEEDS-CLARIFY-*-emf.md in $EMF_QUEUE_REPO/failed/"

# Now run the *non-DRY_RUN* revert path against the fake homelab-argo — a
# real revert commit should land, HEAD should move by exactly one commit,
# the resulting kustomization.yaml should show newTag: main-aaaaaaa again.
reset_calls
before_head="$(git -C "$HOMELAB_ARGO_REPO" rev-parse HEAD)"
_hook_argocd_revert emf abcdef1
after_head="$(git -C "$HOMELAB_ARGO_REPO" rev-parse HEAD)"
[[ "$before_head" != "$after_head" ]] \
  && _ok "revert: real revert commit landed" \
  || _no "revert: commit landed" "HEAD unchanged"
if grep -q 'newTag: main-aaaaaaa' "$HOMELAB_ARGO_REPO/emf/kustomization.yaml"; then
  _ok "revert: kustomization.yaml rolled back to previous tag"
else
  _no "revert: kustomization.yaml rolled back" "current content: $(cat "$HOMELAB_ARGO_REPO/emf/kustomization.yaml")"
fi
# Reset the homelab-argo repo to its post-fixture state so later tests can
# assume the same starting HEAD. Push the revert away with a reset.
git -C "$HOMELAB_ARGO_REPO" reset --hard "$before_head" >/dev/null 2>&1

# ---- 5. Missing health-state file → warn + return 0 -----------------------

reset_calls
rm -rf "$DEPLOY_HEALTH_DIR"
DRY_RUN=1 run_health_check emf
rc=$?
assert_zero "missing health-state: exit 0" "$rc"
grep -q '^curl ' "$TMP/calls.log" \
  && _no "missing health-state: no curl call" "curl was invoked" \
  || _ok "missing health-state: no curl call (short-circuited)"

# ---- 6. health_url: none in repos.yaml → skip cleanly ---------------------

reset_calls
mkdir -p "$DEPLOY_HEALTH_DIR"
printf 'sha=abcdef1\nts=2026-09-14T00:00:00Z\nrepo=other\n' > "$DEPLOY_HEALTH_DIR/other"
DRY_RUN=1 run_health_check other
rc=$?
assert_zero "health_url none: exit 0" "$rc"
grep -q '^curl ' "$TMP/calls.log" \
  && _no "health_url none: no curl" "curl was invoked" \
  || _ok "health_url none: no curl (skipped)"

PATH="$OLD_PATH"

printf '\n%d passed, %d failed\n' "$pass" "$fail"
(( fail == 0 ))
