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
# $TMP/unlabelled: the unlabelled images, one `id|Created epoch|space-separated tags` per line.
#   A labelled image's `Created` is 30 days ago, as a cache-served build's is.
# $TMP/in-use: refs a container uses — `image rm` of one fails, as docker's does.
new_fake() {
  TMP="$(mktemp -d -t docker-reclaim-test.XXXXXX)"
  mkdir "$TMP/bin"
  cat > "$TMP/bin/docker" <<'FAKE'
#!/usr/bin/env bash
echo "$*" >> "$FAKE_CALLS"
if [[ -n "${FAKE_FAIL:-}" && "$*" == "$FAKE_FAIL"* ]]; then exit 1; fi
field() { awk -F'|' -v id="$1" -v f="$2" '$1 == id { print $f }' "$FAKE_IMAGES" "$FAKE_UNLABELLED"; }
created() {
  local at
  at="$(awk -F'|' -v id="$1" '$1 == id { print $2 }' "$FAKE_UNLABELLED")"
  grep -qxF -- "$1" <(cut -d'|' -f1 "$FAKE_IMAGES") && at=$(( $(date +%s) - 30 * 86400 ))
  if [[ "$at" =~ ^[0-9]+$ ]]; then date -u -d "@$at" +%Y-%m-%dT%H:%M:%S.123456789Z; else echo "$at"; fi
}
case "$*" in
  "image ls --filter label="*) cut -d'|' -f1 "$FAKE_IMAGES" ;;
  "image ls --filter dangling=false "*) cut -d'|' -f1 "$FAKE_IMAGES" "$FAKE_UNLABELLED" ;;
  "image inspect --format {{.Created}} "*) created "${*: -1}" ;;
  "image inspect --format {{ index .Config.Labels"*) field "${*: -1}" 2 ;;
  "image inspect --format {{ join .RepoTags"*) field "${*: -1}" 3 ;;
  "image rm "*) ! grep -qxF -- "$3" "$FAKE_IN_USE" ;;
esac
FAKE
  chmod +x "$TMP/bin/docker"
  : > "$TMP/calls"
  : > "$TMP/images"
  : > "$TMP/unlabelled"
  : > "$TMP/in-use"
}

run_reclaim() {
  set +e
  env PATH="$TMP/bin:$PATH" FAKE_CALLS="$TMP/calls" FAKE_IMAGES="$TMP/images" FAKE_UNLABELLED="$TMP/unlabelled" FAKE_IN_USE="$TMP/in-use" \
    "$@" bash "$RECLAIM" > "$TMP/out.log" 2>&1
  STATUS=$?
  set -e
  if grep -q -- "^image prune.*--all" "$TMP/calls"; then echo "FAIL: image prune --all ran" >&2; exit 1; fi
}

# Case 1: defaults — age-filtered prunes, then the kelta-ci builder trimmed to 20gb.
new_fake
run_reclaim
assert_eq "$STATUS" "0" "defaults → exit code"
assert_called "image prune --force --filter until=24h --filter label!=io.kelta.ci.loaded-at" \
  "defaults → dangling unlabelled images older than 24h"
assert_called "builder prune --all --force --filter until=24h" "defaults → default-builder cache older than 24h"
assert_called "buildx prune --builder kelta-ci --force --keep-storage 20gb" "defaults → kelta-ci capped at 20gb"
rm -rf "$TMP"

# Case 2: overrides are honoured.
new_fake
run_reclaim DOCKER_RECLAIM_AGE=6h DOCKER_RECLAIM_BUILDER=other DOCKER_RECLAIM_BUILDER_KEEP=5gb
assert_called "image prune --force --filter until=6h --filter label!=io.kelta.ci.loaded-at" "overrides → image age"
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

# Case 7 (KLT-638): a tagged unlabelled image is aged by `Created` and removed one tag at a time,
# except the images this checkout's compose files and workflows name — they are always older
# than 24h, and removing one between another job's pull and its `up` failed that job.
new_fake
NOW="$(date +%s)"
OLD=$((NOW - 25 * 3600))
{
  echo "sha-mailpit|$OLD|axllent/mailpit:latest"
  echo "sha-pgvector|$OLD|pgvector/pgvector:pg15"
  echo "sha-redis|$OLD|redis:7-alpine redis:6-alpine"
  echo "sha-curl|$OLD|curlimages/curl:8.11.0"
  echo "sha-old|$OLD|tpl-0-1-kelta-worker:latest someone/tool:1.0"
  echo "sha-digest|$OLD|"
  echo "sha-new|$((NOW - 60))|someone/new:1.0"
  echo "sha-busy|$OLD|someone/busy:1.0"
  echo "sha-badtime|not-a-time|someone/odd:1.0"
} > "$TMP/unlabelled"
echo "sha-loaded|$((NOW - 60))|kelta-cerbos-ci:1-1-e2e" > "$TMP/images"
echo "someone/busy:1.0" > "$TMP/in-use"
run_reclaim
assert_eq "$STATUS" "0" "unlabelled → exit code"
assert_not_called "image rm axllent/mailpit:latest" "unlabelled → old compose image axllent/mailpit:latest kept"
assert_not_called "image rm pgvector/pgvector:pg15" "unlabelled → old compose image pgvector/pgvector:pg15 kept"
assert_not_called "image rm redis:7-alpine" "unlabelled → old compose image redis:7-alpine kept"
assert_not_called "image rm curlimages/curl:8.11.0" "unlabelled → old workflow helper image kept"
grep -qF "kept axllent/mailpit:latest (referenced by compose, a workflow or a Testcontainers test)" "$TMP/out.log" || { echo "FAIL: keep not reported" >&2; cat "$TMP/out.log" >&2; exit 1; }
echo "PASS: unlabelled → kept compose image reported"
assert_called "image rm redis:6-alpine" "unlabelled → unreferenced tag of a kept image removed"
assert_called "image rm tpl-0-1-kelta-worker:latest" "unlabelled → old non-compose image's first tag removed"
assert_called "image rm someone/tool:1.0" "unlabelled → old non-compose image's second tag removed"
assert_called "image rm sha-digest" "unlabelled → old image without tags removed by id"
assert_not_called "image rm someone/new:1.0" "unlabelled → image created a minute ago kept"
assert_not_called "image rm someone/odd:1.0" "unlabelled → unreadable Created kept"
assert_not_called "image rm kelta-cerbos-ci:1-1-e2e" "unlabelled → labelled image left to the labelled pass"
grep -qF "kept someone/busy:1.0 (in use)" "$TMP/out.log" || { echo "FAIL: in-use image not reported kept" >&2; exit 1; }
echo "PASS: unlabelled → old image a container uses is kept"
rm -rf "$TMP"

# Case 8: no compose or workflow files to read → tagged unlabelled images all kept, with a
# warning; dangling images are still pruned.
new_fake
echo "sha-old|$(( $(date +%s) - 25 * 3600 ))|someone/tool:1.0" > "$TMP/unlabelled"
mkdir "$TMP/empty-root"
run_reclaim DOCKER_RECLAIM_ROOT="$TMP/empty-root"
assert_eq "$STATUS" "0" "no keep-list → exit code"
assert_not_called "image rm someone/tool:1.0" "no keep-list → tagged image kept"
grep -qF "::warning::docker-reclaim: no compose, workflow or Testcontainers images found" "$TMP/out.log" || { echo "FAIL: expected warning" >&2; cat "$TMP/out.log" >&2; exit 1; }
echo "PASS: no keep-list → warning emitted"
assert_called "image prune --force --filter until=24h --filter label!=io.kelta.ci.loaded-at" "no keep-list → dangling prune still runs"
rm -rf "$TMP"

# Case 9: DOCKER_RECLAIM_KEEP adds references; Docker Hub prefixes and a missing tag normalise.
new_fake
OLD=$(( $(date +%s) - 25 * 3600 ))
printf '%s\n' "sha-a|$OLD|someone/extra:latest" "sha-b|$OLD|nginx:1.27" > "$TMP/unlabelled"
run_reclaim DOCKER_RECLAIM_KEEP="someone/extra docker.io/library/nginx:1.27"
assert_not_called "image rm someone/extra:latest" "extra keep → untagged reference matches :latest"
assert_not_called "image rm nginx:1.27" "extra keep → docker.io/library/ prefix matches"
rm -rf "$TMP"

# Case 10: an inspect that fails mid-pass keeps that image and never fails the job.
new_fake
echo "sha-old|$(( $(date +%s) - 25 * 3600 ))|someone/tool:1.0" > "$TMP/unlabelled"
FAKE_FAIL="image inspect" run_reclaim
assert_eq "$STATUS" "0" "inspect fails → exit code"
assert_not_called "image rm someone/tool:1.0" "inspect fails → image kept"
assert_called "buildx prune --builder kelta-ci --force --keep-storage 20gb" "inspect fails → builder still trimmed"
rm -rf "$TMP"

# Case 11 (PLT-835): the images this checkout's Testcontainers tests start are kept too.
# Testcontainers skips its pull when the image is local, so removing one before it creates the
# container failed the job ("No such image: postgres:15-alpine" in `Test auth`).
new_fake
OLD=$(( $(date +%s) - 25 * 3600 ))
{
  echo "sha-pg15a|$OLD|postgres:15-alpine"
  echo "sha-pg15|$OLD|postgres:15"
  echo "sha-nats|$OLD|nats:2.10-alpine"
  echo "sha-cerbos|$OLD|ghcr.io/cerbos/cerbos:0.42.0"
  echo "sha-nowhere|$OLD|someone/nowhere:1.0"
} > "$TMP/unlabelled"
run_reclaim
assert_eq "$STATUS" "0" "testcontainers → exit code"
for ref in postgres:15-alpine postgres:15 redis:7-alpine nats:2.10-alpine ghcr.io/cerbos/cerbos:0.42.0; do
  grep -E "^docker-reclaim: keeping .* referenced images: " "$TMP/out.log" | tr ' ' '\n' | grep -qxF -- "$ref" \
    || { echo "FAIL: testcontainers → keep-list lacks $ref" >&2; cat "$TMP/out.log" >&2; exit 1; }
  echo "PASS: testcontainers → keep-list names $ref"
done
assert_not_called "image rm postgres:15-alpine" "testcontainers → old unused postgres:15-alpine kept"
assert_not_called "image rm postgres:15" "testcontainers → old unused postgres:15 kept"
assert_not_called "image rm nats:2.10-alpine" "testcontainers → old unused nats:2.10-alpine kept"
assert_not_called "image rm ghcr.io/cerbos/cerbos:0.42.0" "testcontainers → old unused cerbos:0.42.0 kept"
assert_called "image rm someone/nowhere:1.0" "testcontainers → old image named nowhere still removed"
rm -rf "$TMP"

# Case 12: each Java shape is read on its own (no compose or workflow files): a parse(...)
# literal, a constructor literal and an IMAGE constant; untagged literals and other strings are not.
new_fake
OLD=$(( $(date +%s) - 25 * 3600 ))
mkdir -p "$TMP/root/svc/src/test/java/x" "$TMP/root/node_modules/pkg"
cat > "$TMP/root/svc/src/test/java/x/ShapesTest.java" <<'JAVA'
class ShapesTest {
    private static final String CERBOS_IMAGE = "acme/cerbos:9.9.9";
    static final PostgreSQLContainer PG = new PostgreSQLContainer("acme/postgres:15");
    static final GenericContainer<?> R = new GenericContainer<>(DockerImageName.parse("acme/redis:7-alpine"));
    static final GenericContainer<?> W = serviceContainer("acme/untagged");
    void t() { redis.delete("pat:abc123"); }
}
JAVA
echo 'class Vendored { Object c = new GenericContainer<>("acme/vendored:1.0"); }' > "$TMP/root/node_modules/pkg/Vendored.java"
{
  echo "sha-a|$OLD|acme/cerbos:9.9.9"
  echo "sha-b|$OLD|acme/postgres:15"
  echo "sha-c|$OLD|acme/redis:7-alpine"
  echo "sha-d|$OLD|acme/vendored:1.0"
  echo "sha-e|$OLD|pat:abc123"
} > "$TMP/unlabelled"
run_reclaim DOCKER_RECLAIM_ROOT="$TMP/root"
assert_eq "$STATUS" "0" "java shapes → exit code"
assert_not_called "image rm acme/cerbos:9.9.9" "java shapes → IMAGE constant kept"
assert_not_called "image rm acme/postgres:15" "java shapes → Container constructor literal kept"
assert_not_called "image rm acme/redis:7-alpine" "java shapes → DockerImageName.parse literal kept"
assert_called "image rm acme/vendored:1.0" "java shapes → node_modules not scanned"
assert_called "image rm pat:abc123" "java shapes → non-image string literal not kept"
rm -rf "$TMP"

echo "All docker-reclaim tests passed."
