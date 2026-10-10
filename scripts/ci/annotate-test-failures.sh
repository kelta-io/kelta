#!/usr/bin/env bash
# Publish failing tests as check-run error annotations.
#
# A job log and its uploaded artifacts are only readable with repository credentials, but the
# check run's annotations are public through the API. Without this, an agent (or anyone without
# admin rights) sees a red harness job as "Process completed with exit code 1." and nothing else —
# KLT-378 burned three attempts guessing at a failure whose assertion it never saw.
#
# Each failing class's surefire/failsafe .txt summary (failure message + trimmed stack) becomes one
# `::error` annotation, capped so a mass failure stays within GitHub's per-step annotation limit.
#
# Usage: annotate-test-failures.sh <surefire-or-failsafe-report-dir>...
set -uo pipefail

max_annotations=10
max_chars=4000
emitted=0

escape() {
  local s=$1
  s=${s//'%'/'%25'}
  s=${s//$'\r'/'%0D'}
  s=${s//$'\n'/'%0A'}
  printf '%s' "$s"
}

for dir in "$@"; do
  for report in "$dir"/*.txt; do
    [ -f "$report" ] || continue
    grep -qE '<<< (FAILURE|ERROR)!' "$report" || continue
    if [ "$emitted" -ge "$max_annotations" ]; then
      echo "More failing reports than the ${max_annotations}-annotation cap; see the job log."
      exit 0
    fi
    title=$(basename "$report" .txt)
    body=$(head -c "$max_chars" "$report")
    echo "::error title=${title}::$(escape "$body")"
    emitted=$((emitted + 1))
  done
done

echo "Annotated ${emitted} failing test report(s)."
