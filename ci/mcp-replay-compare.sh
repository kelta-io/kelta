#!/usr/bin/env bash
# Fails when the MCP replay's tools/call count grew more than 20% over its checked-in baseline:
#
#   bash ci/mcp-replay-compare.sh <count-file> <baseline-file>
#
# count > baseline * 1.2 fails, in integer arithmetic (count * 5 > baseline * 6), so a baseline
# of 100 allows 120 and fails 121. Prints one "count / baseline / threshold" line either way.
# A count below the baseline passes with a notice: lower the baseline in the same change that
# made the build cheaper (see examples/templates/applicant-tracking/BUILD-LOG.md).
# Tested by ci/mcp-replay-compare.test.sh.
set -euo pipefail

[ "$#" -eq 2 ] || { echo "usage: $0 <count-file> <baseline-file>" >&2; exit 2; }

read_int() {
  local value
  [ -f "$1" ] || { echo "::error::mcp-replay-compare: $2 file $1 does not exist" >&2; exit 1; }
  value=$(tr -d '[:space:]' <"$1")
  [[ "$value" =~ ^[0-9]+$ ]] || { echo "::error::mcp-replay-compare: $2 file $1 holds '$value', not a whole number" >&2; exit 1; }
  echo "$((10#$value))"
}

count=$(read_int "$1" count)
baseline=$(read_int "$2" baseline)
[ "$baseline" -gt 0 ] || { echo "::error::mcp-replay-compare: the baseline must be above 0" >&2; exit 1; }
threshold=$((baseline * 6 / 5))

echo "MCP replay: $count tools/call requests, baseline $baseline, threshold $threshold (+20%)"
if [ $((count * 5)) -gt $((baseline * 6)) ]; then
  echo "::error::mcp-replay-compare: $count tools/call requests is more than 20% over the baseline of $baseline (threshold $threshold)" >&2
  exit 1
fi
if [ "$count" -lt "$baseline" ]; then
  echo "::notice::mcp-replay-compare: $count is below the baseline of $baseline: lower the baseline to lock the gain in"
fi
