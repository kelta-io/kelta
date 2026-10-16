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

assert_not_called() {
  if grep -qxF -- "$1" "$TMP/calls"; then echo "FAIL: $2: '$1' was called" >&2; exit 1; fi
  echo "PASS: $2"
}

# FAKE_FAIL: a subcommand prefix the fake exits 1 for ("image prune", "buildx inspect", ...).
# $TMP/images: the labelled images, one `id|loaded-at label|space-separated tags` per line.
# $TMP/in-use: refs a container uses — `image rm` of one fails, as docker's does.
new_fake() {
  TMP="$(mktemp -d -t docker-reclaim-test.XXXXXX)"
  mkdir "$TMP/bin"
  cat > "$TMP/bin/docker" <<'FAKE'
#!/usr/bin/env bash
echo "$*" >> "$FAKE_CALLS"
if [[ -n "${FAKE_FAIL:-}" && "$*" == "$FAKE_FAIL"* ]]; then exit 1; fi
field() { awk -F'|' -v id="$1" -v f="$2" '$1 == id { print $f }' "$FAKE_IMAGES"; }
case "$*" in
  "image ls --filter label="*) cut -d'|' -f1 "$FAKE_IMAGES" ;;
  "image inspect --format {{ index .Config.Labels"*) field "${*: -1}" 2 ;;
  "image inspect --format {{ join .RepoTags"*) field "${*: -1}" 3 ;;
  "image rm "*) ! grep -qxF -- "$3" "$FAKE_IN_USE" ;;
esac
FAKE
  chmod +x "$TMP/bin/docker"
  : > "$TMP/calls"
  : > "$TMP/images"
  : > "$TMP/in-use"
}

run_reclaim() {
  set +e
  env PATH="$TMP/bin:$PATH" FAKE_CALLS="$TMP/calls" FAKE_IMAGES="$TMP/images" FAKE_IN_USE="$TMP/in-use" \
    "$@" bash "$RECLAIM" > "$TMP/out.log" 2>&1
  STATUS=$?
  set -e
}

# Case 1: defaults — age-filtered prunes, then the kelta-ci builder trimmed to 20gb.
new_fake
run_reclaim
assert_eq "$STATUS" "0" "defaults → exit code"
assert_called "image prune --all --force --filter until=24h --filter label!=io.kelta.ci.loaded-at" \
  "defaults → unused unlabelled images older than 24h"
assert_called "builder prune --all --force --filter until=24h" "defaults → default-builder cache older than 24h"
assert_called "buildx prune --builder kelta-ci --force --keep-storage 20gb" "defaults → kelta-ci capped at 20gb"
rm -rf "$TMP"

# Case 2: overrides are honoured.
new_fake
run_reclaim DOCKER_RECLAIM_AGE=6h DOCKER_RECLAIM_BUILDER=other DOCKER_RECLAIM_BUILDER_KEEP=5gb
assert_called "image prune --all --force --filter until=6h --filter label!=io.kelta.ci.loaded-at" "overrides → image age"
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

# Case 5 (PLT-618): labelled images are aged by when the job loaded them, not by `Created`
# (which a cache-served build inherits from weeks ago, so the prune above cannot see them).
new_fake
NOW="$(date +%s)"
{
  echo "sha-fresh|$((NOW - 60))|kelta-cerbos-ci:1-1-e2e"
  echo "sha-stale|$((NOW - 25 * 3600))|kelta-cerbos-ci:0-1-e2e ci-0-1-kelta-ui:latest"
  echo "sha-dangling|$((NOW - 25 * 3600))|"
  echo "sha-busy|$((NOW - 25 * 3600))|upg-0-1-kelta-worker:latest"
  echo "sha-unlabelled|not-a-time|odd:tag"
} > "$TMP/images"
echo "upg-0-1-kelta-worker:latest" > "$TMP/in-use"
run_reclaim
assert_eq "$STATUS" "0" "labelled → exit code"
assert_not_called "image rm kelta-cerbos-ci:1-1-e2e" "labelled → image loaded a minute ago kept"
assert_called "image rm kelta-cerbos-ci:0-1-e2e" "labelled → stale image's first tag removed"
assert_called "image rm ci-0-1-kelta-ui:latest" "labelled → stale image's second tag removed"
assert_called "image rm sha-dangling" "labelled → stale untagged image removed by id"
grep -qF "kept upg-0-1-kelta-worker:latest (in use)" "$TMP/out.log" || { echo "FAIL: in-use image not reported kept" >&2; cat "$TMP/out.log" >&2; exit 1; }
echo "PASS: labelled → stale image a container uses is kept"
assert_not_called "image rm odd:tag" "labelled → malformed label kept"
if grep -q -- "--force image rm\|image rm -f\|image rm --force" "$TMP/calls"; then echo "FAIL: labelled → forced rm" >&2; exit 1; fi
echo "PASS: labelled → never forced"
rm -rf "$TMP"

# Case 6: an age the labelled pass cannot convert keeps every labelled image, with a warning.
new_fake
echo "sha-stale|1|kelta-cerbos-ci:0-1-e2e" > "$TMP/images"
run_reclaim DOCKER_RECLAIM_AGE=2026-01-01T00:00:00
assert_eq "$STATUS" "0" "unparseable age → exit code"
assert_not_called "image rm kelta-cerbos-ci:0-1-e2e" "unparseable age → labelled image kept"
grep -qF "::warning::docker-reclaim: cannot age labelled images" "$TMP/out.log" || { echo "FAIL: expected warning" >&2; exit 1; }
echo "PASS: unparseable age → warning emitted"
rm -rf "$TMP"

echo "All docker-reclaim tests passed."
