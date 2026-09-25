#!/usr/bin/env bash
# Fail when a Testcontainers integration test was skipped wholesale.
#
# `@Testcontainers(disabledWithoutDocker = true)` turns "Docker is unusable" into SKIPPED, and a
# skipped suite leaves the job green. That is how RowLevelSecurityIntegrationTest — the only
# test proving tenant isolation holds for a role WITHOUT the RLS bypass — reported 13/13 skipped
# on every CI run: Ryuk (Testcontainers' reaper) cannot be reached on the k8s-runner's shared
# Docker daemon, so Testcontainers declared Docker unavailable. On a developer laptop without
# Docker the skip is fine; in CI it must be a failure.
#
# A suite whose every test was skipped is that signature; an individually @Disabled test is not.
#
# Usage: assert-integration-tests-ran.sh <surefire-or-failsafe-report-dir>...
set -euo pipefail

found=0
failed=0
for dir in "$@"; do
  for report in "$dir"/TEST-*IntegrationTest.xml; do
    [ -f "$report" ] || continue
    found=$((found + 1))
    header=$(grep -m1 -o '<testsuite [^>]*>' "$report" || true)
    if [ -z "$header" ]; then
      # A truncated/empty report (e.g. a crashed fork) — say so instead of aborting silently.
      echo "::error file=$report::$(basename "$report") has no <testsuite> element — truncated report?"
      failed=$((failed + 1))
      continue
    fi
    tests=$(sed -n 's/.* tests="\([0-9]*\)".*/\1/p' <<<"$header")
    skipped=$(sed -n 's/.* skipped="\([0-9]*\)".*/\1/p' <<<"$header")
    if [ "${tests:-0}" -gt 0 ] && [ "${skipped:-0}" -eq "${tests:-0}" ]; then
      echo "::error file=$report::$(basename "$report" .xml | sed 's/^TEST-//') skipped all ${tests} tests — is Docker/Testcontainers usable on this runner?"
      failed=$((failed + 1))
    else
      echo "ok: $(basename "$report" .xml | sed 's/^TEST-//') (tests=${tests:-?}, skipped=${skipped:-?})"
    fi
  done
done

echo "Checked ${found} integration test report(s); ${failed} fully skipped or unreadable."
[ "$failed" -eq 0 ]
