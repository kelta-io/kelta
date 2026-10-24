#!/usr/bin/env bash
# MCP replay: replays a template's MCP-only build (examples/templates/applicant-tracking/
# mcp-replay/calls.json) against a fresh tenant and counts the tools/call requests it takes. Run
# by the "mcp-replay" job in .github/workflows/ci.yml after the stack is up (COMPOSE_PROFILES=mcp
# starts kelta-mcp) and the platform admin's forced first sign-in is done:
#
#   bash ci/mcp-replay.sh                   (on the runner; writes mcp-replay-count.txt)
#
# No model is involved: the "prompt" is the checked-in list of tool calls. Docker on the
# k8s-runner-integration runner is remote, so nothing can be bind-mounted: the built kelta CLI
# (kelta-web, built by the job beforehand), the template and this script are baked into a
# throwaway image that runs on the compose network, where this script re-invokes itself with
# --in-container. Inside, it:
#   1. signs in as the platform admin (POST /auth/direct-login), creates a fresh tenant (a
#      `tenants` record) and mints a PAT for its seeded admin (POST
#      /api/tenants/{id}/bootstrap-token) — skipped when KELTA_TOKEN is already set;
#   (before that, the fixture is refused if any call names a local-only cli_* tool);
#   2. starts `kelta mcp serve --source remote --mcp-url $MCP_URL --toolset all` and speaks
#      JSON-RPC to it over stdio: initialize, one tools/list (fails unless it lists every tool
#      the fixture calls), then each fixture call as a tools/call;
#   3. counts every tools/call it sends, retries included, and rewrites the count to
#      MCP_REPLAY_COUNT_FILE after each one. A call that errors is retried up to
#      MCP_REPLAY_RETRIES times unless the fixture marks it expectError (the build hit that error
#      too); one that still errors fails the replay;
#   4. checks the tenant over plain HTTP (not MCP, so not counted): every collection of the
#      template's metadata.json exists and holds as many records as the rows of its `records`
#      sections for that collection.
# It makes no `kelta api` call and drives no CLI authoring command; the only kelta command it
# runs is the bridge. Fixture format and the {{var}} substitution: see the generator,
# examples/templates/applicant-tracking/mcp-replay/generate.mjs.
#
# By hand, against any stack whose kelta-mcp you can reach (KELTA_CLI defaults to `kelta`):
#
#   KELTA_URL=http://localhost:8080 KELTA_TENANT=<fresh slug> KELTA_TOKEN=klt_... \
#     MCP_URL=http://localhost:<kelta-mcp port> MCP_REPLAY_COUNT_FILE=count.txt \
#     bash ci/mcp-replay.sh --in-container examples/templates/applicant-tracking
#
# The count is compared with mcp-replay/baseline.txt by ci/mcp-replay-compare.sh. Tested by
# ci/mcp-replay.test.sh against a fake `kelta` and a fake `curl`.
set -euo pipefail

if [ "${1:-}" = "--in-container" ]; then
  TEMPLATE_DIR="${2:?template directory}"
  FIXTURE="${MCP_REPLAY_FIXTURE:-$TEMPLATE_DIR/mcp-replay/calls.json}"
  AUTH_URL="${AUTH_URL:-http://kelta-auth:8081}"
  GATEWAY_URL="${GATEWAY_URL:-http://kelta-gateway:8080}"
  MCP_URL="${MCP_URL:-http://kelta-mcp:8080}"
  PLATFORM_SLUG="${PLATFORM_SLUG:-default}"
  ADMIN_USERNAME="${ADMIN_USERNAME:-admin@kelta.local}"
  TENANT_SLUG="${MCP_REPLAY_TENANT:-mcp-replay}"
  COUNT_FILE="${MCP_REPLAY_COUNT_FILE:-/out/mcp-replay-count.txt}"
  TIMEOUT="${MCP_REPLAY_TIMEOUT:-120}"
  RETRIES="${MCP_REPLAY_RETRIES:-2}"
  RETRY_SECONDS="${MCP_REPLAY_RETRY_SECONDS:-3}"
  WAIT_SECONDS="${WAIT_SECONDS:-180}"
  read -r -a KELTA <<<"${KELTA_CLI:-kelta}"

  TMP=$(mktemp -d)
  SERVE_PID=""
  trap '[ -z "$SERVE_PID" ] || kill "$SERVE_PID" 2>/dev/null || true; rm -rf "$TMP"' EXIT
  # A write to a bridge that has exited must fail the write (and so this script, with a
  # message), not kill the shell silently with SIGPIPE.
  trap '' PIPE

  log() { echo "mcp-replay: $*" >&2; }
  fail() {
    echo "::error::mcp-replay: $*" >&2
    if [ -s "$TMP/serve.err" ]; then
      echo "--- kelta mcp serve stderr (last 20 lines) ---" >&2
      tail -n 20 "$TMP/serve.err" >&2
    fi
    exit 1
  }

  [ -f "$FIXTURE" ] || fail "no fixture at $FIXTURE"
  jq -e '.calls | type == "array" and length > 0' "$FIXTURE" >/dev/null || fail "$FIXTURE has no calls"
  # cli_* tools are local to the kelta CLI: `kelta mcp serve --source remote` never serves them, so
  # a fixture naming one can only fail at tools/list. Name each offending call and its step.
  local_calls=$(jq -r '.calls | length as $n | to_entries[] | select((.value.tool // "") | startswith("cli_"))
    | "call \(.key + 1)/\($n) \(.value.tool) (\(.value.step // "-"))"' "$FIXTURE" | paste -sd ';' | sed 's/;/; /g')
  [ -z "$local_calls" ] \
    || fail "$local_calls: cli_ tools are local CLI tools that --source remote does not serve; call the hosted kelta-mcp tool instead"
  mkdir -p "$(dirname "$COUNT_FILE")"
  COUNT=0
  echo "$COUNT" >"$COUNT_FILE"

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
      "$(jq -cn --arg s "$TENANT_SLUG" '{data: {type: "tenants", attributes: {slug: $s, name: "MCP Replay"}}}')"
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
  : "${KELTA_URL:?KELTA_URL must be set with KELTA_TOKEN}" "${KELTA_TENANT:?KELTA_TENANT must be set with KELTA_TOKEN}"

  # --- 2. the stdio bridge ----------------------------------------------------------------
  mkfifo "$TMP/in" "$TMP/out"
  "${KELTA[@]}" mcp serve --source remote --mcp-url "$MCP_URL" --toolset all \
    <"$TMP/in" >"$TMP/out" 2>"$TMP/serve.err" &
  SERVE_PID=$!
  exec 3>"$TMP/in" 4<"$TMP/out"

  NEXT_ID=0
  RESPONSE=""
  # rpc <method> <params-json>: sends one request and sets RESPONSE to the frame answering it.
  # A JSON-RPC error frame is returned like a result; the caller decides. Not run in $(...):
  # the id counter and fail() must act on this shell.
  rpc() {
    local line
    NEXT_ID=$((NEXT_ID + 1))
    jq -cn --argjson id "$NEXT_ID" --arg m "$1" --argjson p "$2" \
      '{jsonrpc: "2.0", id: $id, method: $m, params: $p}' >&3 2>/dev/null \
      || fail "$1: kelta mcp serve is no longer reading stdin"
    while IFS= read -r -t "$TIMEOUT" line <&4; do
      jq -e --argjson id "$NEXT_ID" '.id == $id' <<<"$line" >/dev/null 2>&1 || continue
      RESPONSE="$line"
      return 0
    done
    fail "$1: no response within ${TIMEOUT}s (kelta mcp serve exited or hung)"
  }
  rpc_error() { jq -e 'has("error")' <<<"$RESPONSE" >/dev/null; }

  # tool_text: the text content of a tools/call RESPONSE (or its JSON-RPC error).
  tool_text() {
    jq -r 'if has("error") then (.error | tojson)
      else [.result.content[]? | select(.type == "text") | .text] | join("\n") end' <<<"$RESPONSE"
  }
  tool_failed() { jq -e 'has("error") or .result.isError == true' <<<"$RESPONSE" >/dev/null; }

  rpc initialize '{"protocolVersion": "2025-06-18", "capabilities": {},
    "clientInfo": {"name": "kelta-ci-mcp-replay", "version": "1"}}'
  rpc_error && fail "initialize returned a JSON-RPC error: $(jq -c '.error' <<<"$RESPONSE")"
  printf '%s\n' '{"jsonrpc": "2.0", "method": "notifications/initialized"}' >&3 2>/dev/null \
    || fail "notifications/initialized: kelta mcp serve is no longer reading stdin"
  log "initialized: $(jq -c '.result.serverInfo // {}' <<<"$RESPONSE")"

  rpc tools/list '{}'
  rpc_error && fail "tools/list returned a JSON-RPC error: $(jq -c '.error' <<<"$RESPONSE")"
  jq -r '.result.tools[]?.name' <<<"$RESPONSE" | sort -u >"$TMP/tools"
  jq -r '.calls[].tool' "$FIXTURE" | sort -u >"$TMP/needed"
  missing=$(comm -23 "$TMP/needed" "$TMP/tools" | paste -sd ' ')
  [ -z "$missing" ] || fail "tools/list lacks $missing, which the fixture calls"
  log "tools/list: $(grep -c . "$TMP/tools") tools, including all $(grep -c . "$TMP/needed") the fixture calls"

  # --- 3. the replay ----------------------------------------------------------------------
  # Saved values ({{var}} / {{var:key}} in arguments) live in vars.json.
  echo '{}' >"$TMP/vars.json"
  # shellcheck disable=SC2016 # a jq program, not a shell expansion
  RESOLVE='walk(if type == "string" and test("^\\{\\{.+\\}\\}$") then
      (.[2:-2] | capture("^(?<v>[^:]+)(?::(?<k>.*))?$")) as $r
      | ($vars[$r.v] // error("{{\($r.v)}} was never saved by an earlier call")) as $val
      | if $r.k == null then $val
        else ($val[$r.k] // error("{{\($r.v):\($r.k)}}: no key \"\($r.k)\" in the saved value")) end
    else . end)'

  TOTAL=$(jq '.calls | length' "$FIXTURE")
  EXPECTED_ERRORS=0
  RETRIED=0
  for ((i = 0; i < TOTAL; i++)); do
    jq -c --argjson i "$i" '.calls[$i]' "$FIXTURE" >"$TMP/call.json"
    tool=$(jq -r '.tool' "$TMP/call.json")
    expect_error=$(jq -r '.expectError // empty' "$TMP/call.json")
    label="call $((i + 1))/$TOTAL $tool ($(jq -r '.step // "-"' "$TMP/call.json"))"
    params=$(jq -c --slurpfile vars "$TMP/vars.json" \
      "\$vars[0] as \$vars | {name: .tool, arguments: (.arguments // {} | $RESOLVE)}" "$TMP/call.json" 2>"$TMP/jq.err") \
      || fail "$label: $(sed 's/^jq: error[^:]*: //' "$TMP/jq.err")"

    for ((attempt = 1; ; attempt++)); do
      COUNT=$((COUNT + 1))
      echo "$COUNT" >"$COUNT_FILE"
      rpc tools/call "$params"
      tool_failed || break
      if [ -n "$expect_error" ]; then
        EXPECTED_ERRORS=$((EXPECTED_ERRORS + 1))
        log "$label: expected error ($expect_error): $(tool_text | head -c 300)"
        break
      fi
      [ "$attempt" -le "$RETRIES" ] \
        || fail "$label failed after $attempt attempt(s): $(tool_text | head -c 1000)"
      RETRIED=$((RETRIED + 1))
      log "$label failed (attempt $attempt, retrying in ${RETRY_SECONDS}s): $(tool_text | head -c 300)"
      sleep "$RETRY_SECONDS"
    done

    if ! tool_failed; then
      [ -z "$expect_error" ] \
        || echo "::warning::mcp-replay: $label succeeded, but the fixture expects an error ($expect_error). The build may now need fewer calls: regenerate the fixture and baseline (see BUILD-LOG.md)." >&2
      while IFS=$'\t' read -r var expr; do
        [ -n "$var" ] || continue
        value=$(tool_text | jq -c "$expr" 2>"$TMP/jq.err") \
          || fail "$label: save $var: $expr failed on the result: $(head -c 300 "$TMP/jq.err") — result: $(tool_text | head -c 500)"
        [ "$value" != "null" ] || fail "$label: save $var: $expr is null on the result: $(tool_text | head -c 500)"
        jq --arg k "$var" --argjson v "$value" '. + {($k): $v}' "$TMP/vars.json" >"$TMP/vars.next"
        mv "$TMP/vars.next" "$TMP/vars.json"
      done < <(jq -r '.save // {} | to_entries[] | [.key, .value] | @tsv' "$TMP/call.json")
    fi
  done
  log "replayed $TOTAL fixture calls in $COUNT tools/call requests ($RETRIED retries, $EXPECTED_ERRORS expected errors)"

  # --- 4. the tenant holds the template ---------------------------------------------------
  # record_count <collection>: meta.totalCount of a one-record page; fails if the collection
  # does not exist (404) or the read fails.
  record_count() {
    curl -fsS -m 30 -H "Authorization: Bearer $KELTA_TOKEN" \
      "$KELTA_URL/$KELTA_TENANT/api/$1?page%5Bsize%5D=1" | jq -e '.meta.totalCount'
  }
  collections=$(jq -r '.items[] | select(.type == "COLLECTION") | .data.name' "$TEMPLATE_DIR/metadata.json")
  [ -n "$collections" ] || fail "$TEMPLATE_DIR/metadata.json declares no collections"
  for collection in $collections; do
    actual=$(record_count "$collection") || fail "collection $collection does not exist in $KELTA_TENANT (or its records could not be read)"
    expected=$(jq --arg c "$collection" \
      '[.records // [] | .[] | select(.collection == $c) | .rows | length] | if length > 0 then add else empty end' \
      "$TEMPLATE_DIR/metadata.json")
    if [ -n "$expected" ]; then
      [ "$actual" -eq "$expected" ] \
        || fail "$collection holds $actual records, the metadata.json records sections have $expected"
      log "$collection: exists, $actual records (metadata.json records: $expected)"
    else
      log "$collection: exists ($actual records, no records section)"
    fi
  done
  echo "MCP replay passed: $COUNT tools/call requests through kelta mcp serve --source remote --mcp-url $MCP_URL"
  exit 0
fi

COMPOSE_PROJECT_NAME="${COMPOSE_PROJECT_NAME:?COMPOSE_PROJECT_NAME must be set}"
: "${ADMIN_PASSWORD:?ADMIN_PASSWORD must be set}"
TEMPLATE="${MCP_REPLAY_TEMPLATE:-applicant-tracking}"
COUNT_OUT="${MCP_REPLAY_COUNT_FILE:-mcp-replay-count.txt}"
NETWORK="${COMPOSE_PROJECT_NAME}_kelta-network"
IMAGE="kelta-mcp-replay:${COMPOSE_PROJECT_NAME}"
CONTAINER="${COMPOSE_PROJECT_NAME}_mcp-replay"
[ -f kelta-web/packages/cli/dist/index.js ] || { echo "kelta CLI not built (kelta-web/packages/cli/dist)" >&2; exit 1; }
[ -f "examples/templates/$TEMPLATE/mcp-replay/calls.json" ] \
  || { echo "no fixture at examples/templates/$TEMPLATE/mcp-replay/calls.json" >&2; exit 1; }

# --load: the job selects the persistent docker-container builder (BUILDX_BUILDER=kelta-ci),
# which keeps a build result in its own cache unless told to load it into the daemon.
docker buildx build --load -q -t "$IMAGE" --build-arg "TEMPLATE=$TEMPLATE" -f - . <<'DOCKERFILE'
FROM node:24-alpine
ARG TEMPLATE
RUN apk add --no-cache bash coreutils curl jq
COPY kelta-web /kelta-web
RUN printf '#!/bin/sh\nexec node /kelta-web/packages/cli/dist/index.js "$@"\n' > /usr/local/bin/kelta \
 && chmod +x /usr/local/bin/kelta
COPY examples/templates/${TEMPLATE} /template
COPY ci/mcp-replay.sh /ci/
DOCKERFILE

# Not --rm: the count file is copied out of the container after it exits, pass or fail.
docker rm -f "$CONTAINER" >/dev/null 2>&1 || true
status=0
docker run --name "$CONTAINER" --network "$NETWORK" --memory 1g --memory-swap 1g --pids-limit 512 \
  -e ADMIN_PASSWORD "$IMAGE" bash /ci/mcp-replay.sh --in-container /template </dev/null || status=$?
docker cp "$CONTAINER:/out/mcp-replay-count.txt" "$COUNT_OUT" >/dev/null 2>&1 \
  || echo "mcp-replay: no count file in the container" >&2
docker rm -f "$CONTAINER" >/dev/null 2>&1 || true
docker image rm "$IMAGE" >/dev/null 2>&1 || true
exit "$status"
