#!/usr/bin/env bash
# emit-deploy-event.sh — record "this SHA is live and healthy" for DORA.
#
# Runs at the end of the smoke-test job (in-cluster runner), after the rollout is
# healthy. Pushes one line to Loki ({job="dora", event="deploy_healthy"}) carrying
# merge→healthy seconds. The nightly collector (collect.mjs) recomputes everything
# from git + GitHub, so this is the real-time signal, not the source of truth — a
# failure here must never fail the deploy.
#
# Env:
#   LOKI_URL      default http://loki.observability.svc.cluster.local:3100
#   GITHUB_SHA    full SHA that was deployed (set by Actions)
#   GITHUB_RUN_ID / GITHUB_REPOSITORY (set by Actions)
#   MERGED_AT     ISO timestamp of the merge commit; derived from git when unset

set -uo pipefail

LOKI_URL="${LOKI_URL:-http://loki.observability.svc.cluster.local:3100}"
SHA="${GITHUB_SHA:-$(git rev-parse HEAD 2>/dev/null || echo unknown)}"
REPO="${GITHUB_REPOSITORY:-unknown}"
RUN_ID="${GITHUB_RUN_ID:-}"
NOW_S="$(date -u +%s)"
NOW_ISO="$(date -u +%Y-%m-%dT%H:%M:%SZ)"

MERGED_AT="${MERGED_AT:-}"
if [[ -z "$MERGED_AT" ]]; then
  MERGED_S="$(git log -1 --format=%ct "$SHA" 2>/dev/null || echo "")"
else
  MERGED_S="$(date -u -d "$MERGED_AT" +%s 2>/dev/null || echo "")"
fi
if [[ -n "$MERGED_S" ]]; then
  MERGE_TO_HEALTHY=$(( NOW_S - MERGED_S ))
  # GNU date first (runner), BSD date fallback (macOS dev box)
  MERGED_ISO="$(date -u -d "@$MERGED_S" +%Y-%m-%dT%H:%M:%SZ 2>/dev/null || date -u -r "$MERGED_S" +%Y-%m-%dT%H:%M:%SZ 2>/dev/null || echo null)"
else
  MERGE_TO_HEALTHY=null
  MERGED_ISO=null
fi

# Keys sorted: stable shape for LogQL `| json` consumers.
LINE=$(printf '{"healthyAt":"%s","mergeToHealthySec":%s,"mergedAt":%s,"runId":"%s","sha":"%s"}' \
  "$NOW_ISO" "$MERGE_TO_HEALTHY" \
  "$([[ "$MERGED_ISO" == null ]] && echo null || printf '"%s"' "$MERGED_ISO")" \
  "$RUN_ID" "${SHA:0:7}")

BODY=$(printf '{"streams":[{"stream":{"job":"dora","repo":"%s","event":"deploy_healthy"},"values":[["%s000000000",%s]]}]}' \
  "$REPO" "$NOW_S" "$(printf '%s' "$LINE" | sed 's/"/\\"/g' | sed 's/^/"/; s/$/"/')")

echo "dora: sha=${SHA:0:7} merge→healthy=${MERGE_TO_HEALTHY}s"
if curl -sf -m 10 -H 'content-type: application/json' -X POST "$LOKI_URL/loki/api/v1/push" --data "$BODY" >/dev/null; then
  echo "dora: deploy_healthy event pushed to Loki"
else
  echo "dora: WARN could not push deploy_healthy event to Loki (non-fatal)"
fi
exit 0
