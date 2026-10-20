#!/usr/bin/env bash
# MCP smoke test: proves the CI stack's kelta-mcp serves the authoring tools through the CLI
# bridge an agent uses. Run by the "e2e" job in .github/workflows/ci.yml after the stack is up
# (COMPOSE_PROFILES=mcp starts kelta-mcp, see docker-compose.ci.yml) and the platform admin's
# forced first sign-in is done:
#
#   bash ci/mcp-smoke.sh                    (on the runner)
#
# Docker on the k8s-runner-integration runner is remote, so nothing can be bind-mounted: the
# built kelta CLI (kelta-web, built by the job beforehand) and this script are baked into a
# throwaway image that runs on the compose network, where this script re-invokes itself with
# --in-container. Inside, it:
#   1. signs in as the platform admin (POST /auth/direct-login), creates a fresh tenant (a
#      `tenants` record) and mints a PAT for its seeded admin (POST
#      /api/tenants/{id}/bootstrap-token) — skipped when KELTA_TOKEN is already set;
#   2. starts `kelta mcp serve --source remote --mcp-url $MCP_URL --toolset all` and speaks
#      JSON-RPC to it over stdio: initialize, tools/list, tools/call;
#   3. fails unless tools/list holds every REQUIRED_TOOLS entry, then calls create_collection
#      once and fails unless get_collection_schema returns that collection.
#
# By hand, against any stack whose kelta-mcp you can reach (KELTA_CLI defaults to `kelta`):
#
#   KELTA_URL=http://localhost:8080 KELTA_TENANT=<slug> KELTA_TOKEN=klt_... \
#     MCP_URL=http://localhost:<kelta-mcp port> bash ci/mcp-smoke.sh --in-container
#
# Every response has a deadline (MCP_SMOKE_TIMEOUT seconds), so a hung bridge fails the step
# instead of the job. Tested by ci/mcp-smoke.test.sh against a fake `kelta`.
set -euo pipefail

if [ "${1:-}" = "--in-container" ]; then
  AUTH_URL="${AUTH_URL:-http://kelta-auth:8081}"
  GATEWAY_URL="${GATEWAY_URL:-http://kelta-gateway:8080}"
  MCP_URL="${MCP_URL:-http://kelta-mcp:8080}"
  PLATFORM_SLUG="${PLATFORM_SLUG:-default}"
  ADMIN_USERNAME="${ADMIN_USERNAME:-admin@kelta.local}"
  TENANT_SLUG="${MCP_SMOKE_TENANT:-mcp-smoke}"
  COLLECTION="${MCP_SMOKE_COLLECTION:-mcp_smoke_items}"
  TIMEOUT="${MCP_SMOKE_TIMEOUT:-60}"
  WAIT_SECONDS="${WAIT_SECONDS:-180}"
  SCHEMA_ATTEMPTS="${MCP_SMOKE_SCHEMA_ATTEMPTS:-15}"
  RETRY_SECONDS="${MCP_SMOKE_RETRY_SECONDS:-2}"
  read -r -a KELTA <<<"${KELTA_CLI:-kelta}"
  REQUIRED_TOOLS=(create_collection apply_layout bulk_apply query_collection)

  TMP=$(mktemp -d)
  SERVE_PID=""
  trap '[ -z "$SERVE_PID" ] || kill "$SERVE_PID" 2>/dev/null || true; rm -rf "$TMP"' EXIT
  # A write to a bridge that has exited must fail the write (and so this script, with a
  # message), not kill the shell silently with SIGPIPE.
  trap '' PIPE

  log() { echo "mcp-smoke: $*" >&2; }
  fail() {
    echo "::error::mcp-smoke: $*" >&2
    if [ -s "$TMP/serve.err" ]; then
      echo "--- kelta mcp serve stderr (last 20 lines) ---" >&2
      tail -n 20 "$TMP/serve.err" >&2
    fi
    exit 1
  }

  # --- 1. a fresh tenant and its PAT ------------------------------------------------------
  if [ -z "${KELTA_TOKEN:-}" ]; then
    : "${ADMIN_PASSWORD:?ADMIN_PASSWORD (or KELTA_TOKEN) must be set}"
    STATUS=""
    # call <token> <method> <path> [json]: one gateway request; sets STATUS, body in $TMP/body.
    call() {
      local args=(-sS -m 30 -o "$TMP/body" -w '%{http_code}' -X "$2" "$GATEWAY_URL$3"
        -H "Authorization: Bearer $1" -H 'Content-Type: application/json')
      [ -n "${4:-}" ] && args+=(--data "$4")
      STATUS=$(curl "${args[@]}" 2>"$TMP/curl.err") || STATUS="000"
    }
    # api_retry: retried until WAIT_SECONDS — a new tenant's slug reaches the gateway
    # asynchronously. Prints the body of the first 2xx.
    api_retry() {
      local deadline=$((SECONDS + WAIT_SECONDS))
      while :; do
        call "$@"
        case "$STATUS" in 2??) cat "$TMP/body"; return 0 ;; esac
        [ "$SECONDS" -ge "$deadline" ] \
          && fail "$2 $3 still HTTP $STATUS after ${WAIT_SECONDS}s: $(head -c 500 "$TMP/body" 2>/dev/null; cat "$TMP/curl.err")"
        sleep 2
      done
    }

    PLATFORM_TOKEN=$(curl -fsS -m 30 -X POST "$AUTH_URL/auth/direct-login" \
      -H 'Content-Type: application/json' \
      -d "$(jq -cn --arg u "$ADMIN_USERNAME" --arg p "$ADMIN_PASSWORD" --arg t "$PLATFORM_SLUG" \
        '{username: $u, password: $p, tenantSlug: $t}')" | jq -r '.access_token // empty') \
      || fail "direct login to $AUTH_URL failed"
    [ -n "$PLATFORM_TOKEN" ] || fail "direct login did not return an access_token"

    call "$PLATFORM_TOKEN" POST "/$PLATFORM_SLUG/api/tenants" \
      "$(jq -cn --arg s "$TENANT_SLUG" '{data: {type: "tenants", attributes: {slug: $s, name: "MCP Smoke"}}}')"
    case "$STATUS" in
      2??) ;;
      *) fail "creating tenant $TENANT_SLUG returned HTTP $STATUS: $(head -c 500 "$TMP/body")" ;;
    esac
    TENANT_ID=$(jq -r '.data.id // empty' "$TMP/body")
    [ -n "$TENANT_ID" ] || fail "tenant create returned no id"
    log "created tenant $TENANT_SLUG ($TENANT_ID)"

    KELTA_TOKEN=$(api_retry "$PLATFORM_TOKEN" POST \
      "/$PLATFORM_SLUG/api/tenants/$TENANT_ID/bootstrap-token" '{"expiresIn":"1h"}' | jq -r '.token // empty')
    [ -n "$KELTA_TOKEN" ] || fail "bootstrap-token returned no token"
    api_retry "$KELTA_TOKEN" GET "/$TENANT_SLUG/api/me/identity" >/dev/null
    log "minted a PAT for $TENANT_SLUG's admin"
    export KELTA_URL="$GATEWAY_URL" KELTA_TENANT="$TENANT_SLUG" KELTA_TOKEN
  fi

  # --- 2. the stdio bridge ----------------------------------------------------------------
  mkfifo "$TMP/in" "$TMP/out"
  "${KELTA[@]}" mcp serve --source remote --mcp-url "$MCP_URL" --toolset all \
    <"$TMP/in" >"$TMP/out" 2>"$TMP/serve.err" &
  SERVE_PID=$!
  exec 3>"$TMP/in" 4<"$TMP/out"

  NEXT_ID=0
  RESPONSE=""
  # rpc <method> <params-json>: sends one request and sets RESPONSE to the frame answering it.
  # Not run in $(...): the id counter and fail() must act on this shell.
  rpc() {
    local line
    NEXT_ID=$((NEXT_ID + 1))
    jq -cn --argjson id "$NEXT_ID" --arg m "$1" --argjson p "$2" \
      '{jsonrpc: "2.0", id: $id, method: $m, params: $p}' >&3 2>/dev/null \
      || fail "$1: kelta mcp serve is no longer reading stdin"
    while IFS= read -r -t "$TIMEOUT" line <&4; do
      jq -e --argjson id "$NEXT_ID" '.id == $id' <<<"$line" >/dev/null 2>&1 || continue
      if jq -e 'has("error")' <<<"$line" >/dev/null; then
        fail "$1 returned a JSON-RPC error: $(jq -c '.error' <<<"$line")"
      fi
      RESPONSE="$line"
      return 0
    done
    fail "$1: no response within ${TIMEOUT}s (kelta mcp serve exited or hung)"
  }

  # tool_text: the text content of a tools/call RESPONSE.
  tool_text() { jq -r '[.result.content[]? | select(.type == "text") | .text] | join("\n")' <<<"$RESPONSE"; }
  tool_failed() { jq -e '.result.isError == true' <<<"$RESPONSE" >/dev/null; }

  rpc initialize '{"protocolVersion": "2025-06-18", "capabilities": {},
    "clientInfo": {"name": "kelta-ci-mcp-smoke", "version": "1"}}'
  printf '%s\n' '{"jsonrpc": "2.0", "method": "notifications/initialized"}' >&3 2>/dev/null \
    || fail "notifications/initialized: kelta mcp serve is no longer reading stdin"
  log "initialized: $(jq -c '.result.serverInfo // {}' <<<"$RESPONSE")"

  # --- 3. the authoring tools, one write and its read-back ---------------------------------
  rpc tools/list '{}'
  TOOLS=$(jq -r '.result.tools[]?.name' <<<"$RESPONSE")
  missing=()
  for tool in "${REQUIRED_TOOLS[@]}"; do
    grep -qxF "$tool" <<<"$TOOLS" || missing+=("$tool")
  done
  [ "${#missing[@]}" -eq 0 ] \
    || fail "tools/list lacks ${missing[*]} ($(grep -c . <<<"$TOOLS") tools listed: $(paste -sd ' ' <<<"$TOOLS"))"
  log "tools/list: $(grep -c . <<<"$TOOLS") tools, including ${REQUIRED_TOOLS[*]}"

  rpc tools/call "$(jq -cn --arg n "$COLLECTION" \
    '{name: "create_collection", arguments: {name: $n, displayName: "MCP Smoke Items"}}')"
  tool_failed && fail "create_collection $COLLECTION failed: $(tool_text | head -c 500)"
  log "create_collection $COLLECTION succeeded"

  # A failed read is retried (the collection may not be registered yet); a schema for another
  # collection is wrong at once.
  for ((attempt = 1; ; attempt++)); do
    rpc tools/call "$(jq -cn --arg n "$COLLECTION" \
      '{name: "get_collection_schema", arguments: {collection: $n, includeRules: false}}')"
    tool_failed || break
    [ "$attempt" -ge "$SCHEMA_ATTEMPTS" ] \
      && fail "get_collection_schema $COLLECTION still failing after $attempt attempts: $(tool_text | head -c 500)"
    sleep "$RETRY_SECONDS"
  done
  name=$(tool_text | jq -r '.name // empty' 2>/dev/null) || name=""
  [ "$name" = "$COLLECTION" ] \
    || fail "get_collection_schema returned '${name:-no name}', not $COLLECTION: $(tool_text | head -c 500)"
  log "get_collection_schema returned $COLLECTION with $(tool_text | jq '.fields | length') fields"
  echo "MCP smoke passed: kelta mcp serve bridged $MCP_URL, listed ${REQUIRED_TOOLS[*]}, created and read back $COLLECTION"
  exit 0
fi

COMPOSE_PROJECT_NAME="${COMPOSE_PROJECT_NAME:?COMPOSE_PROJECT_NAME must be set}"
: "${ADMIN_PASSWORD:?ADMIN_PASSWORD must be set}"
NETWORK="${COMPOSE_PROJECT_NAME}_kelta-network"
IMAGE="kelta-mcp-smoke:${COMPOSE_PROJECT_NAME}"
[ -f kelta-web/packages/cli/dist/index.js ] || { echo "kelta CLI not built (kelta-web/packages/cli/dist)" >&2; exit 1; }

# --load: the job selects the persistent docker-container builder (BUILDX_BUILDER=kelta-ci),
# which keeps a build result in its own cache unless told to load it into the daemon.
docker buildx build --load -q -t "$IMAGE" -f - . <<'DOCKERFILE'
FROM node:24-alpine
RUN apk add --no-cache bash coreutils curl jq
COPY kelta-web /kelta-web
RUN printf '#!/bin/sh\nexec node /kelta-web/packages/cli/dist/index.js "$@"\n' > /usr/local/bin/kelta \
 && chmod +x /usr/local/bin/kelta
COPY ci/mcp-smoke.sh /ci/
DOCKERFILE

status=0
docker run --rm --network "$NETWORK" --memory 1g --memory-swap 1g --pids-limit 512 \
  -e ADMIN_PASSWORD "$IMAGE" bash /ci/mcp-smoke.sh --in-container </dev/null || status=$?
docker image rm "$IMAGE" >/dev/null 2>&1 || true
exit "$status"
