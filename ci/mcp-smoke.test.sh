#!/usr/bin/env bash
# Tests ci/mcp-smoke.sh --in-container against a fake `kelta` on PATH: a stdio JSON-RPC
# server that answers like `kelta mcp serve`, steered by FAKE_* variables. KELTA_TOKEN is set,
# so the tenant/PAT provisioning (which needs a live stack) is skipped.
# Usage: bash ci/mcp-smoke.test.sh
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SMOKE="$SCRIPT_DIR/mcp-smoke.sh"

TMP="$(mktemp -d -t mcp-smoke-test.XXXXXX)"
trap 'rm -rf "$TMP"' EXIT
mkdir "$TMP/bin"

# FAKE_TOOLS: the tool names tools/list returns.        FAKE_LIST_ERROR=1: tools/list is a JSON-RPC error.
# FAKE_CREATE_ERROR=1: create_collection has isError.   FAKE_SCHEMA_NAME: the name the schema reports.
# FAKE_SCHEMA_ERRORS=<n>: the first n schema reads fail. FAKE_EXIT=1: exits before answering anything.
cat > "$TMP/bin/kelta" <<'FAKE'
#!/usr/bin/env bash
echo "$*" > "$FAKE_ARGS"
[ "${FAKE_EXIT:-}" = 1 ] && { echo "hosted MCP unreachable" >&2; exit 3; }
schema_reads=0
reply() { jq -cn --argjson id "$1" --argjson r "$2" '{jsonrpc: "2.0", id: $id, result: $r}'; }
text() { jq -cn --arg t "$1" --argjson e "${2:-false}" '{content: [{type: "text", text: $t}], isError: $e}'; }
while IFS= read -r line; do
  id=$(jq '.id // empty' <<<"$line")
  [ -n "$id" ] || continue
  method=$(jq -r '.method' <<<"$line")
  tool=$(jq -r '.params.name // empty' <<<"$line")
  echo "$method $tool" >> "$FAKE_CALLS"
  case "$method:$tool" in
    initialize:) reply "$id" '{"protocolVersion": "2025-06-18", "serverInfo": {"name": "kelta-cli"}}' ;;
    tools/list:)
      if [ "${FAKE_LIST_ERROR:-}" = 1 ]; then
        jq -cn --argjson id "$id" '{jsonrpc: "2.0", id: $id, error: {code: -32603, message: "Hosted MCP (admin): 401"}}'
      else
        reply "$id" "$(jq -cn --arg t "$FAKE_TOOLS" '{tools: ($t | split(" ") | map({name: .}))}')"
      fi ;;
    tools/call:create_collection)
      if [ "${FAKE_CREATE_ERROR:-}" = 1 ]; then reply "$id" "$(text '{"errors":[{"status":"409"}]}' true)"
      else reply "$id" "$(text '{"data":{"id":"c1","type":"collections"}}')"; fi ;;
    tools/call:get_collection_schema)
      schema_reads=$((schema_reads + 1))
      if [ "$schema_reads" -le "${FAKE_SCHEMA_ERRORS:-0}" ]; then reply "$id" "$(text 'HTTP 404 COLLECTION_NOT_FOUND' true)"
      else
        name="${FAKE_SCHEMA_NAME:-$(jq -r '.params.arguments.collection' <<<"$line")}"
        reply "$id" "$(text "$(jq -cn --arg n "$name" '{name: $n, fields: [{name: "id"}]}')")"
      fi ;;
  esac
done
FAKE
chmod +x "$TMP/bin/kelta"

ALL_TOOLS="create_collection apply_layout bulk_apply query_collection get_collection_schema cli_records_list"

run_smoke() {
  : > "$TMP/calls"
  : > "$TMP/args"
  set +e
  env PATH="$TMP/bin:$PATH" FAKE_CALLS="$TMP/calls" FAKE_ARGS="$TMP/args" FAKE_TOOLS="$ALL_TOOLS" \
    KELTA_URL=http://gateway.test KELTA_TENANT=acme KELTA_TOKEN=klt_test \
    MCP_URL=http://kelta-mcp:8080 MCP_SMOKE_TIMEOUT=10 MCP_SMOKE_SCHEMA_ATTEMPTS=3 MCP_SMOKE_RETRY_SECONDS=0 \
    "$@" timeout 60 bash "$SMOKE" --in-container > "$TMP/out.log" 2>&1
  STATUS=$?
  set -e
}

pass() { echo "PASS: $1"; }
fail_test() { echo "FAIL: $1" >&2; cat "$TMP/out.log" >&2; exit 1; }
expect_status() {
  if [ "$1" = 0 ]; then [ "$STATUS" -eq 0 ] || fail_test "$2: exited $STATUS, expected 0"
  else
    [ "$STATUS" -ne 0 ] || fail_test "$2: exited 0, expected a failure"
    [ "$STATUS" -ne 124 ] || fail_test "$2: hung until timeout instead of failing"
  fi
  pass "$2 → exit $STATUS"
}
expect_output() { grep -qF -- "$1" "$TMP/out.log" || fail_test "$2: output lacks '$1'"; pass "$2"; }
expect_calls() { [ "$(grep -cxF -- "$1" "$TMP/calls")" -eq "$2" ] || fail_test "$3: '$1' not called $2 time(s)"; pass "$3"; }

# Case 1: everything answers → exit 0, the bridge started as CI needs it, one create, one read.
run_smoke
expect_status 0 "happy path"
[ "$(cat "$TMP/args")" = "mcp serve --source remote --mcp-url http://kelta-mcp:8080 --toolset all" ] \
  || fail_test "happy path: kelta was run as '$(cat "$TMP/args")'"
pass "happy path → kelta mcp serve --source remote --mcp-url http://kelta-mcp:8080 --toolset all"
expect_calls "tools/call create_collection" 1 "happy path → create_collection called once"
expect_calls "tools/call get_collection_schema" 1 "happy path → schema read once"
expect_output "MCP smoke passed" "happy path → success line"

# Case 2: each required authoring tool, missing in turn → failure naming it, nothing created.
for missing in create_collection apply_layout bulk_apply query_collection; do
  run_smoke FAKE_TOOLS="$(tr ' ' '\n' <<<"$ALL_TOOLS" | grep -vxF "$missing" | paste -sd ' ')"
  expect_status 1 "missing $missing"
  expect_output "tools/list lacks $missing" "missing $missing → named in the error"
  expect_calls "tools/call create_collection" 0 "missing $missing → no create_collection call"
done

# Case 3: tools/list itself errors (hosted kelta-mcp unreachable with --source remote).
run_smoke FAKE_LIST_ERROR=1
expect_status 1 "tools/list JSON-RPC error"
expect_output "tools/list returned a JSON-RPC error" "tools/list JSON-RPC error → reported"

# Case 4: create_collection fails → failure, no read-back.
run_smoke FAKE_CREATE_ERROR=1
expect_status 1 "failed create"
expect_output "create_collection mcp_smoke_items failed" "failed create → reported"
expect_calls "tools/call get_collection_schema" 0 "failed create → no schema read"

# Case 5: the schema is another collection's → failure on the first read, no retries.
run_smoke FAKE_SCHEMA_NAME=other_items
expect_status 1 "schema mismatch"
expect_output "get_collection_schema returned 'other_items', not mcp_smoke_items" "schema mismatch → reported"
expect_calls "tools/call get_collection_schema" 1 "schema mismatch → not retried"

# Case 6: the schema read fails once, then succeeds → retried, exit 0.
run_smoke FAKE_SCHEMA_ERRORS=1
expect_status 0 "schema read retried"
expect_calls "tools/call get_collection_schema" 2 "schema read retried → two reads"

# Case 7: the schema read never succeeds → failure after MCP_SMOKE_SCHEMA_ATTEMPTS reads.
run_smoke FAKE_SCHEMA_ERRORS=99
expect_status 1 "schema read keeps failing"
expect_output "still failing after 3 attempts" "schema read keeps failing → reported"
expect_calls "tools/call get_collection_schema" 3 "schema read keeps failing → three reads"

# Case 8: the bridge exits at once → failure (with its stderr), not a hang.
run_smoke FAKE_EXIT=1
expect_status 1 "bridge exits"
expect_output "hosted MCP unreachable" "bridge exits → its stderr is shown"

echo "All mcp-smoke.sh tests passed"
