#!/usr/bin/env bash
# Regenerate Playwright visual baselines in CI and push them to a branch.
#
# The single implementation behind both triggers in ci.yml's `e2e` job:
#   - a PR that adds or changes e2e-tests/.update-snapshots (one spec path per line,
#     relative to e2e-tests/, blank lines and `#` comments ignored), and
#   - workflow_dispatch with update_snapshots_ref + update_snapshots_specs.
#
# Runs after the compose stack is up and kelta-e2e-runner:local is built. It clones
# SNAPSHOTS_REF (the PR head, not the merge ref), validates the specs, runs
# `npx playwright test --update-snapshots=all <specs>` in the runner image on the compose
# network, `docker cp`s each spec's *-snapshots/ directory out (the image bakes the tests
# in; nothing is bind-mounted), commits only those PNGs plus the marker's deletion, pushes,
# and exits 1 so this run never reports green on tests it did not run. The push triggers
# the next CI run, which has no marker and is a normal run.
#
# Environment:
#   SNAPSHOTS_REF         branch to push to (required; never main)
#   SNAPSHOTS_SPECS       space-separated spec paths; empty = read the marker from the branch
#   EXPECTED_SHA          abort if the branch head is no longer this commit
#   PUSH_TOKEN            PAT used for the clone and the push (ci.yml: ARGOCD_REPO_TOKEN)
#   REPOSITORY            owner/name on github.com
#   COMPOSE_PROJECT_NAME  compose project; the network is <project>_kelta-network
#   E2E_ENV_FILE          docker --env-file shared with the `Run Playwright tests` step
#   E2E_IMAGE             runner image (default kelta-e2e-runner:local)
#   REPORT_DIR            where playwright-report/ and test-results/ are copied (default e2e-tests)
#   SNAPSHOTS_REMOTE      clone/push URL override (tests only); default github.com/$REPOSITORY
#   DOCKER                docker binary (tests only; default docker)
set -euo pipefail

MARKER=e2e-tests/.update-snapshots
DOCKER="${DOCKER:-docker}"
E2E_IMAGE="${E2E_IMAGE:-kelta-e2e-runner:local}"
REPORT_DIR="${REPORT_DIR:-e2e-tests}"
SUMMARY="${GITHUB_STEP_SUMMARY:-/dev/null}"

die() {
  echo "::error::update-snapshots: $*" >&2
  exit 1
}

# Spec lines from a marker file: `#` starts a comment, surrounding whitespace is dropped.
parse_marker() {
  local line
  while IFS= read -r line || [ -n "$line" ]; do
    line="${line%%#*}"
    line="${line#"${line%%[![:space:]]*}"}"
    line="${line%"${line##*[![:space:]]}"}"
    [ -n "$line" ] && printf '%s\n' "$line"
  done < "$1"
}

# Rejects anything that is not a spec file under <root>/e2e-tests/tests/.
validate_spec() {
  local root="$1" spec="$2" tests_dir resolved
  case "$spec" in
    /*) die "spec path '$spec' is absolute; give it relative to e2e-tests/ (e.g. tests/end-user/x.spec.ts)" ;;
    *..*) die "spec path '$spec' contains '..'" ;;
  esac
  [[ "$spec" =~ ^[A-Za-z0-9._/-]+$ ]] || die "spec path '$spec' contains characters outside [A-Za-z0-9._/-]"
  [[ "$spec" == tests/* ]] || die "spec path '$spec' is not under e2e-tests/tests/"
  [[ "$spec" == *.spec.ts ]] || die "spec path '$spec' is not a *.spec.ts file"
  [ -f "$root/e2e-tests/$spec" ] || die "spec path '$spec' does not exist on $SNAPSHOTS_REF (looked for e2e-tests/$spec)"
  tests_dir="$(realpath "$root/e2e-tests/tests")"
  resolved="$(realpath "$root/e2e-tests/$spec")"
  [[ "$resolved" == "$tests_dir"/* ]] || die "spec path '$spec' does not resolve under e2e-tests/tests/"
  [ ! -L "$root/e2e-tests/$spec-snapshots" ] || die "spec path '$spec' has a symlinked -snapshots directory"
}

[ -n "${SNAPSHOTS_REF:-}" ] || die "SNAPSHOTS_REF is empty"
[ "$SNAPSHOTS_REF" != main ] || die "refusing to push baselines to main"
git check-ref-format --branch "$SNAPSHOTS_REF" >/dev/null 2>&1 || die "'$SNAPSHOTS_REF' is not a valid branch name"
[ -n "${COMPOSE_PROJECT_NAME:-}" ] || die "COMPOSE_PROJECT_NAME is empty"
[ -f "${E2E_ENV_FILE:-}" ] || die "E2E_ENV_FILE '${E2E_ENV_FILE:-}' is not a file"

git_auth=()
if [ -n "${SNAPSHOTS_REMOTE:-}" ]; then
  remote="$SNAPSHOTS_REMOTE"
else
  [ -n "${PUSH_TOKEN:-}" ] || die "PUSH_TOKEN is empty (ci.yml passes secrets.ARGOCD_REPO_TOKEN; GITHUB_TOKEN pushes do not trigger CI)"
  [ -n "${REPOSITORY:-}" ] || die "REPOSITORY is empty"
  remote="https://github.com/${REPOSITORY}.git"
  basic="$(printf 'x-access-token:%s' "$PUSH_TOKEN" | base64 | tr -d '\n')"
  git_auth=(-c "http.https://github.com/.extraheader=AUTHORIZATION: basic ${basic}")
fi

head="$(mktemp -d)/head"
git "${git_auth[@]}" clone --quiet --depth 1 --branch "$SNAPSHOTS_REF" -- "$remote" "$head" \
  || die "could not clone branch '$SNAPSHOTS_REF'"
head_sha="$(git -C "$head" rev-parse HEAD)"
if [ -n "${EXPECTED_SHA:-}" ] && [ "$head_sha" != "$EXPECTED_SHA" ]; then
  die "$SNAPSHOTS_REF moved to $head_sha since this run started on $EXPECTED_SHA; the run for the newer commit handles it"
fi

specs=()
if [ -n "${SNAPSHOTS_SPECS:-}" ]; then
  read -ra specs <<< "$SNAPSHOTS_SPECS"
else
  [ -f "$head/$MARKER" ] || die "$MARKER does not exist on $SNAPSHOTS_REF and no specs were given"
  mapfile -t specs < <(parse_marker "$head/$MARKER")
fi
[ "${#specs[@]}" -gt 0 ] || die "no spec paths given (the marker lists one per line, relative to e2e-tests/)"
for spec in "${specs[@]}"; do
  validate_spec "$head" "$spec"
done
spec_list="${specs[*]}"

container="${COMPOSE_PROJECT_NAME}_kelta-e2e-snapshots"
"$DOCKER" rm -f "$container" >/dev/null 2>&1 || true
trap '"$DOCKER" rm -f "$container" >/dev/null 2>&1 || true' EXIT

echo "Regenerating baselines for: $spec_list"
status=0
"$DOCKER" run --name "$container" \
  --network "${COMPOSE_PROJECT_NAME}_kelta-network" \
  --env-file "$E2E_ENV_FILE" \
  "$E2E_IMAGE" \
  # `--update-snapshots` takes an optional mode in Playwright >= 1.50 (all|changed|missing|none);
  # a bare flag followed by a spec path parses the path as the mode and fails (PLT-476).
  npx playwright test --update-snapshots=all "${specs[@]}" || status=$?

mkdir -p "$REPORT_DIR/playwright-report" "$REPORT_DIR/test-results"
"$DOCKER" cp "$container":/work/playwright-report/. "$REPORT_DIR/playwright-report/" 2>/dev/null || true
"$DOCKER" cp "$container":/work/test-results/. "$REPORT_DIR/test-results/" 2>/dev/null || true
[ "$status" -eq 0 ] || die "playwright exited $status while updating baselines for $spec_list; nothing committed (see the e2e-report artifact)"

for spec in "${specs[@]}"; do
  mkdir -p "$head/e2e-tests/$spec-snapshots"
  "$DOCKER" cp "$container":"/work/$spec-snapshots/." "$head/e2e-tests/$spec-snapshots/" \
    || echo "::warning::no $spec-snapshots directory in the container (the spec takes no screenshots?)"
  if compgen -G "$head/e2e-tests/$spec-snapshots/*.png" >/dev/null; then
    git -C "$head" add -- ":(glob)e2e-tests/$spec-snapshots/*.png"
  fi
done
png_changes="$(git -C "$head" diff --cached --name-only | wc -l | tr -d ' ')"
git -C "$head" rm --quiet --cached --ignore-unmatch -- "$MARKER"
rm -f "$head/$MARKER"

if git -C "$head" diff --cached --quiet; then
  echo "no baseline changed for $spec_list and there is no marker to remove; nothing pushed" | tee -a "$SUMMARY"
  exit 1
fi

if [ "$png_changes" -eq 0 ]; then
  subject="test(e2e): remove the update-snapshots marker (no baseline changed)"
else
  subject="test(e2e): regenerate visual baselines for $spec_list"
fi
git -C "$head" \
  -c user.name='github-actions[bot]' \
  -c user.email='41898282+github-actions[bot]@users.noreply.github.com' \
  commit --quiet -m "$subject" -m "Regenerated by ci/update-snapshots.sh for: $spec_list"
sha="$(git -C "$head" rev-parse HEAD)"

if [ "$png_changes" -eq 0 ]; then
  message="no baseline changed for $spec_list; removed the marker in $sha; CI re-runs on that commit"
else
  message="baselines regenerated for $spec_list in $sha; CI re-runs on that commit"
fi

git -C "$head" "${git_auth[@]}" push --quiet origin "HEAD:refs/heads/$SNAPSHOTS_REF" \
  || die "push to $SNAPSHOTS_REF failed; $sha was not pushed"
# The push starts a new CI run, and ci.yml's concurrency cancels this one moments later;
# write the summary straight after the push so the cancel has as little window as possible.
echo "$message" >> "$SUMMARY"
echo "::error::$message"
exit 1
