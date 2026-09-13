#!/usr/bin/env bash
# Unit tests for queue_repo_allowed() in lib/queue.sh.
#
# Invoked directly (`bash repos-yaml-test.sh`). Not part of /verify — the
# dispatcher lives outside the maven/npm build. The Playwright wrapper at
# e2e-tests/tests/dispatcher/repos-yaml.spec.ts calls this file so it also
# shows up in git diffs against origin/main.

set -u
SELF_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
LIB="$SELF_DIR/../lib/queue.sh"
[[ -f "$LIB" ]] || { echo "missing $LIB" >&2; exit 2; }

# shellcheck source=/dev/null
. "$LIB"

pass=0; fail=0
_ok() { pass=$((pass+1)); printf 'ok   %s\n' "$1"; }
_no() { fail=$((fail+1)); printf 'FAIL %s\n     %s\n' "$1" "$2"; }
assert_eq() {
  local msg="$1" want="$2" got="$3"
  [[ "$want" == "$got" ]] && _ok "$msg" || _no "$msg" "want=[$want] got=[$got]"
}
assert_zero() {
  local msg="$1" rc="$2"
  (( rc == 0 )) && _ok "$msg" || _no "$msg" "expected exit 0 (allowed), got $rc"
}
assert_nonzero() {
  local msg="$1" rc="$2"
  (( rc != 0 )) && _ok "$msg" || _no "$msg" "expected nonzero exit (denied), got $rc"
}

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

# Point queue_repo_allowed() at a fake etc/ directory by temporarily replacing
# the real lib symlink. We do this by creating a parallel etc/ under $TMP and
# symlinking the lib in the same subtree so BASH_SOURCE resolves correctly.
#
# Approach: override via a wrapper that substitutes the yaml path by setting
# a test-scoped shadow function.  Simpler: replicate the repo's directory
# structure and source queue.sh from the copy, then swap the yaml.

setup_fake_lib() {
  local scenario="$1"
  local fake_root="$TMP/$scenario"
  mkdir -p "$fake_root/lib" "$fake_root/etc"
  cp "$LIB" "$fake_root/lib/queue.sh"
  echo "$fake_root"
}

write_yaml() {
  local fake_root="$1"
  cat > "$fake_root/etc/repos.yaml" <<'YAML'
- name: emf
  clone_path: /home/craig/GitHub/emf
  default_branch: main
  merge_policy: auto
  deploy_hook: argocd_image_bump
  tier_ceiling: 1

- name: spotopened-web
  clone_path: /home/craig/GitHub/spotopened-web
  default_branch: main
  merge_policy: auto
  deploy_hook: argocd_image_bump
  tier_ceiling: 1
YAML
}

write_malformed_yaml() {
  local fake_root="$1"
  # An entry that has no `name:` key — should not match any lookup.
  cat > "$fake_root/etc/repos.yaml" <<'YAML'
- clone_path: /home/craig/GitHub/emf
  default_branch: main
  merge_policy: auto
YAML
}

run_allowed() {
  local fake_root="$1" repo_name="$2"
  # Source the copy of queue.sh from the fake root so BASH_SOURCE[0] resolves
  # to a path whose ../etc/ points at our fake etc/.
  bash -c ". '$fake_root/lib/queue.sh'; queue_repo_allowed '$repo_name'" 2>/dev/null
}

run_allowed_stderr() {
  local fake_root="$1" repo_name="$2"
  bash -c ". '$fake_root/lib/queue.sh'; queue_repo_allowed '$repo_name'" 2>&1
}

# --- Case 1: listed repo name → allowed (exit 0) ----------------------------

ROOT1="$(setup_fake_lib case1)"
write_yaml "$ROOT1"

run_allowed "$ROOT1" "emf"; rc=$?
assert_zero "listed name 'emf' → allowed" "$rc"

run_allowed "$ROOT1" "spotopened-web"; rc=$?
assert_zero "listed name 'spotopened-web' → allowed" "$rc"

# --- Case 2: unlisted repo name → denied (exit 1) ----------------------------

ROOT2="$(setup_fake_lib case2)"
write_yaml "$ROOT2"

run_allowed "$ROOT2" "unknown-xyz"; rc=$?
assert_nonzero "unlisted name 'unknown-xyz' → denied" "$rc"

run_allowed "$ROOT2" ""; rc=$?
assert_nonzero "empty name → denied" "$rc"

# --- Case 3: repos.yaml absent → denied (exit 1) without crashing -----------

ROOT3="$(setup_fake_lib case3)"
# No yaml written — etc/ is empty.

output="$(run_allowed_stderr "$ROOT3" "emf")"; rc=$?
assert_nonzero "absent repos.yaml → denied (exit 1)" "$rc"
# Must not be a crash (bash error). We expect a warning message on stderr.
[[ "$output" == *"not found"* ]] && _ok "absent repos.yaml → warning on stderr" \
  || _no "absent repos.yaml → warning on stderr" "got: $output"

# --- Case 4: malformed yaml (entry missing name:) → does not crash, denied --

ROOT4="$(setup_fake_lib case4)"
write_malformed_yaml "$ROOT4"

# The malformed entry has no `name:` line — must not match 'emf'.
run_allowed "$ROOT4" "emf"; rc=$?
assert_nonzero "malformed entry (no name: key) → denied without crash" "$rc"

printf '\n%d passed, %d failed\n' "$pass" "$fail"
(( fail == 0 ))
