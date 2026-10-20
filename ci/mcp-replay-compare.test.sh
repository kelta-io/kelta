#!/usr/bin/env bash
# Tests ci/mcp-replay-compare.sh: the >20% threshold (baseline 100: 120 passes, 121 fails), and
# the checked-in baseline of the applicant-tracking replay.
# Usage: bash ci/mcp-replay-compare.test.sh
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
COMPARE="$SCRIPT_DIR/mcp-replay-compare.sh"
BASELINE_FILE="$SCRIPT_DIR/../examples/templates/applicant-tracking/mcp-replay/baseline.txt"

TMP="$(mktemp -d -t mcp-replay-compare-test.XXXXXX)"
trap 'rm -rf "$TMP"' EXIT

fail_test() { echo "FAIL: $1" >&2; cat "$TMP/out.log" >&2; exit 1; }

# compare <count> <baseline>: runs the script on files holding these values; sets STATUS.
compare() {
  printf '%s\n' "$1" >"$TMP/count"
  printf '%s\n' "$2" >"$TMP/baseline"
  set +e
  bash "$COMPARE" "$TMP/count" "$TMP/baseline" >"$TMP/out.log" 2>&1
  STATUS=$?
  set -e
}
expect() {
  compare "$2" "$3"
  if [ "$1" = pass ]; then [ "$STATUS" -eq 0 ] || fail_test "count $2, baseline $3: exited $STATUS, expected 0"
  else [ "$STATUS" -ne 0 ] || fail_test "count $2, baseline $3: exited 0, expected a failure"; fi
  echo "PASS: count $2, baseline $3 → $1"
}

expect pass 120 100
expect fail 121 100
expect pass 100 100
expect pass 80 100
expect pass 112 94
expect fail 113 94
expect fail 1 0
expect fail 12x 100
expect fail '' 100
expect fail 100 abc

compare 121 100
grep -qF "121 tools/call requests is more than 20% over the baseline of 100 (threshold 120)" "$TMP/out.log" \
  || fail_test "a failure names the count, baseline and threshold"
echo "PASS: a failure names the count, baseline and threshold"

compare 80 100
grep -qF "below the baseline" "$TMP/out.log" || fail_test "a lower count suggests lowering the baseline"
echo "PASS: a lower count suggests lowering the baseline"

set +e
bash "$COMPARE" "$TMP/missing" "$TMP/baseline" >"$TMP/out.log" 2>&1
status=$?
set -e
[ "$status" -ne 0 ] || fail_test "a missing count file fails"
grep -qF "does not exist" "$TMP/out.log" || fail_test "a missing count file is named"
echo "PASS: a missing count file fails"

baseline=$(tr -d '[:space:]' <"$BASELINE_FILE")
[[ "$baseline" =~ ^[1-9][0-9]*$ ]] || { echo "FAIL: $BASELINE_FILE holds '$baseline'" >&2; exit 1; }
echo "PASS: the applicant-tracking baseline is a whole number ($baseline)"

echo "All mcp-replay-compare.sh tests passed"
