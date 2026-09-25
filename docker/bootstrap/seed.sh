#!/usr/bin/env bash
# Kelta Bootstrap — waits for all services to be healthy, then prints access info.
#
# Flyway migrations (run by kelta-worker on startup) already seed:
#   - Default tenant:  slug=default, id=00000000-0000-0000-0000-000000000001
#   - Default admin:   admin@kelta.local / password  (force_change_on_login=true)
#
# This script just confirms the stack is up and ready.
set -euo pipefail

GATEWAY_URL="${GATEWAY_URL:-http://kelta-gateway:8080}"
AUTH_URL="${AUTH_URL:-http://kelta-auth:8081}"
WORKER_URL="${WORKER_URL:-http://kelta-worker:8080}"
MAX_WAIT="${MAX_WAIT:-300}"
INTERVAL=5

wait_for() {
    local name="$1" url="$2/actuator/health"
    local deadline=$(( $(date +%s) + MAX_WAIT ))
    printf "⏳  Waiting for %-20s %s\n" "$name" "$url"
    until curl -fsS "$url" >/dev/null 2>&1; do
        if [ "$(date +%s)" -ge "$deadline" ]; then
            echo "❌  Timed out waiting for $name after ${MAX_WAIT}s"
            exit 1
        fi
        sleep "$INTERVAL"
    done
    echo "✅  $name is healthy"
}

echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo "  Kelta Bootstrap"
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"

wait_for "kelta-worker"  "$WORKER_URL"
wait_for "kelta-auth"    "$AUTH_URL"
wait_for "kelta-gateway" "$GATEWAY_URL"

echo ""
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo "  Stack is healthy — Kelta is ready!"
echo ""
echo "  UI:          http://localhost:5173/default/"
echo "  Gateway API: http://localhost:8080"
echo "  Auth:        http://localhost:8081"
echo "  Worker:      http://localhost:8083"
echo ""
echo "  Default tenant slug: default"
echo "  Login:  admin@kelta.local"
echo "  Pass:   password  (change on first login)"
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
