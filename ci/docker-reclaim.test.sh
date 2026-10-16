#!/usr/bin/env bash
# Tests ci/docker-reclaim.sh against a fake `docker` on PATH that records its arguments.
# Usage: bash ci/docker-reclaim.test.sh
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
RECLAIM="$SCRIPT_DIR/docker-reclaim.sh"

assert_eq() {
  if [[ "$1" != "$2" ]]; then
    echo "FAIL: $3: expected '$2', got '$1'" >&2
    exit 1
  fi
  echo "PASS: $3 → $1"
}

assert_called() {
  grep -qxF -- "$1" "$TMP/calls" || { echo "FAIL: $2: '$1' not called" >&2; cat "$TMP/calls" >&2; exit 1; }
  echo "PASS: $2"
}

# FAKE_FAIL: a subcommand prefix the fake exits 1 for ("image prune", "buildx inspect", ...).
new_fake() {
  TMP="$(mktemp -d -t docker-reclaim-test.XXXXXX)"
  mkdir "$TMP/bin"
  cat > "$TMP/bin/docker" <<'FAKE'
#!/usr/bin/env bash
echo "$*" >> "$FAKE_CALLS"
if [[ -n "${FAKE_FAIL:-}" && "$*" == "$FAKE_FAIL"* ]]; then exit 1; fi
exit 0
FAKE
  chmod +x "$TMP/bin/docker"
  : > "$TMP/calls"
}

run_reclaim() {
  set +e
  env PATH="$TMP/bin:$PATH" FAKE_CALLS="$TMP/calls" "$@" bash "$RECLAIM" > "$TMP/out.log" 2>&1
  STATUS=$?
  set -e
}

# Case 1: defaults — age-filtered prunes, then the kelta-ci builder trimmed to 20gb.
new_fake
run_reclaim
assert_eq "$STATUS" "0" "defaults → exit code"
assert_called "image prune --all --force --filter until=24h" "defaults → unused images older than 24h"
assert_called "builder prune --all --force --filter until=24h" "defaults → default-builder cache older than 24h"
assert_called "buildx prune --builder kelta-ci --force --keep-storage 20gb" "defaults → kelta-ci capped at 20gb"
rm -rf "$TMP"

# Case 2: overrides are honoured.
new_fake
run_reclaim DOCKER_RECLAIM_AGE=6h DOCKER_RECLAIM_BUILDER=other DOCKER_RECLAIM_BUILDER_KEEP=5gb
assert_called "image prune --all --force --filter until=6h" "overrides → image age"
assert_called "buildx prune --builder other --force --keep-storage 5gb" "overrides → builder and cap"
rm -rf "$TMP"

# Case 3: no persistent builder on this daemon → its prune is skipped, not an error.
new_fake
FAKE_FAIL="buildx inspect" run_reclaim
assert_eq "$STATUS" "0" "no builder → exit code"
if grep -q "^buildx prune" "$TMP/calls"; then echo "FAIL: no builder → buildx prune still ran" >&2; exit 1; fi
echo "PASS: no builder → buildx prune skipped"
rm -rf "$TMP"

# Case 4: a failing prune warns and the rest still run; the job is never failed.
new_fake
FAKE_FAIL="image prune" run_reclaim
assert_eq "$STATUS" "0" "image prune fails → exit code"
grep -q "::warning::docker-reclaim: 'docker image prune" "$TMP/out.log" || { echo "FAIL: expected warning" >&2; cat "$TMP/out.log" >&2; exit 1; }
echo "PASS: image prune fails → warning emitted"
assert_called "buildx prune --builder kelta-ci --force --keep-storage 20gb" "image prune fails → builder still trimmed"
rm -rf "$TMP"

echo "All docker-reclaim tests passed."
