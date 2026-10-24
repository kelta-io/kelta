#!/usr/bin/env bash
# Tests ci/mcp-replay.sh --in-container against a fake `kelta` (a stdio JSON-RPC server that
# answers like `kelta mcp serve`) and a fake `curl` (the record-count reads), both steered by
# FAKE_* variables. KELTA_TOKEN is set, so the tenant/PAT provisioning (which needs a live stack)
# is skipped. Also checks the checked-in applicant-tracking fixture: it is what generate.mjs
# writes, every {{var}} it uses is saved by an earlier call, it names no RZWare, SpotOpened or
# CouchPicks string, and a full replay of it against the fakes sends exactly baseline.txt calls.
# Usage: bash ci/mcp-replay.test.sh
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPLAY="$SCRIPT_DIR/mcp-replay.sh"
REAL_TEMPLATE="$(cd "$SCRIPT_DIR/../examples/templates/applicant-tracking" && pwd)"

TMP="$(mktemp -d -t mcp-replay-test.XXXXXX)"
trap 'rm -rf "$TMP"' EXIT
mkdir "$TMP/bin"

# FAKE_TOOLS: the tool names tools/list returns.
# FAKE_FLAKY=<tool>:<n>: the first n calls of <tool> return isError.
# A call whose arguments hold "fakeFail": true returns isError, unless FAKE_NEVER_FAIL=1.
# Results: bulk_apply → atomic:results echoing each operation (id "<type>-<index>"); any other
# tool → {"data": {"id": "<tool>-<call number>", "arguments": ...}}.
cat > "$TMP/bin/kelta" <<'FAKE'
#!/usr/bin/env bash
echo "$*" > "$FAKE_ARGS"
reply() { jq -cn --argjson id "$1" --argjson r "$2" '{jsonrpc: "2.0", id: $id, result: $r}'; }
text() { jq -cn --arg t "$1" --argjson e "${2:-false}" '{content: [{type: "text", text: $t}], isError: $e}'; }
calls=0
flaky_seen=0
while IFS= read -r line; do
  id=$(jq '.id // empty' <<<"$line")
  [ -n "$id" ] || continue
  method=$(jq -r '.method' <<<"$line")
  case "$method" in
    initialize) reply "$id" '{"protocolVersion": "2025-06-18", "serverInfo": {"name": "kelta-cli"}}' ;;
    tools/list) reply "$id" "$(jq -cn --arg t "$FAKE_TOOLS" '{tools: ($t | split(" ") | map({name: .}))}')" ;;
    tools/call)
      calls=$((calls + 1))
      jq -c '.params' <<<"$line" >> "$FAKE_CALLS"
      tool=$(jq -r '.params.name' <<<"$line")
      if [ -n "${FAKE_FLAKY:-}" ] && [ "$tool" = "${FAKE_FLAKY%%:*}" ] && [ "$flaky_seen" -lt "${FAKE_FLAKY##*:}" ]; then
        flaky_seen=$((flaky_seen + 1))
        reply "$id" "$(text 'Gateway returned HTTP 503' true)"
      elif [ "${FAKE_NEVER_FAIL:-}" != 1 ] && jq -e '.params.arguments.fakeFail == true' <<<"$line" >/dev/null; then
        reply "$id" "$(text 'Gateway returned HTTP 422 — {"errors":[{"code":"OPERATION_FAILED"}]}' true)"
      elif [ "$tool" = bulk_apply ]; then
        reply "$id" "$(text "$(jq -c '{"atomic:results": [.params.arguments.operations | to_entries[]
          | {data: {type: .value.type, id: "\(.value.type)-\(.key)", attributes: .value.attributes}}]}' <<<"$line")")"
      else
        reply "$id" "$(text "$(jq -c --arg n "$calls" '{data: {id: "\(.params.name)-\($n)", arguments: .params.arguments}}' <<<"$line")")"
      fi ;;
  esac
done
FAKE
chmod +x "$TMP/bin/kelta"

# FAKE_COUNTS: {"<collection>": <totalCount>}; a collection not in it answers 404 (exit 22).
cat > "$TMP/bin/curl" <<'FAKE'
#!/usr/bin/env bash
url="${*: -1}"
echo "$url" >> "$FAKE_CURL"
collection=$(sed -E 's|^.*/api/([^/?]+).*$|\1|' <<<"$url")
if jq -e --arg c "$collection" 'has($c)' <<<"$FAKE_COUNTS" >/dev/null; then
  jq -c --arg c "$collection" '{meta: {totalCount: .[$c]}}' <<<"$FAKE_COUNTS"
else
  echo "curl: (22) The requested URL returned error: 404" >&2
  exit 22
fi
FAKE
chmod +x "$TMP/bin/curl"

# A two-collection template: a is seeded with two records, b has no records section.
mkdir -p "$TMP/tpl/mcp-replay"
cat > "$TMP/tpl/metadata.json" <<'JSON'
{"items": [{"type": "COLLECTION", "data": {"name": "a"}}, {"type": "COLLECTION", "data": {"name": "b"}}],
 "records": [{"collection": "a", "key": ["key"], "rows": [{"key": "k1"}, {"key": "k2"}]}]}
JSON
cat > "$TMP/tpl/mcp-replay/calls.json" <<'JSON'
{"calls": [
  {"step": "s", "tool": "create_collection", "arguments": {"name": "a"}, "save": {"aId": ".data.id"}},
  {"step": "s", "tool": "create_record", "arguments": {"collection": "fields", "attributes": {"collectionId": "{{aId}}"}}},
  {"step": "s", "tool": "bulk_apply", "arguments": {"operations": [
    {"op": "add", "type": "a", "attributes": {"key": "k1"}}, {"op": "add", "type": "a", "attributes": {"key": "k2"}}]},
    "save": {"aIds": "[.\"atomic:results\"[].data | {key: .attributes.key, value: .id}] | from_entries"}},
  {"step": "s", "tool": "update_record", "arguments": {"collection": "a", "id": "{{aIds:k2}}", "attributes": {"n": 1}}},
  {"step": "s", "tool": "bulk_apply", "arguments": {"operations": [{"op": "add", "type": "a"}], "fakeFail": true},
    "expectError": "the build hit this too"}
]}
JSON
TOOLS="create_collection create_record bulk_apply update_record query_collection"
FIXTURE_TOOLS="$(jq -r '[.calls[].tool] | unique | join(" ")' "$REAL_TEMPLATE/mcp-replay/calls.json")"

run_replay() {
  local template="$1"
  shift
  : > "$TMP/calls"
  : > "$TMP/curl.log"
  : > "$TMP/args"
  rm -f "$TMP/count"
  set +e
  env PATH="$TMP/bin:$PATH" FAKE_CALLS="$TMP/calls" FAKE_ARGS="$TMP/args" FAKE_CURL="$TMP/curl.log" \
    FAKE_TOOLS="$TOOLS" FAKE_COUNTS='{"a": 2, "b": 0}' \
    KELTA_URL=http://gateway.test KELTA_TENANT=acme KELTA_TOKEN=klt_test \
    MCP_URL=http://kelta-mcp:8080 MCP_REPLAY_COUNT_FILE="$TMP/count" MCP_REPLAY_TIMEOUT=10 \
    MCP_REPLAY_RETRY_SECONDS=0 \
    "$@" timeout 120 bash "$REPLAY" --in-container "$template" > "$TMP/out.log" 2>&1
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
expect_count() {
  [ "$(cat "$TMP/count" 2>/dev/null)" = "$1" ] || fail_test "$2: count file holds '$(cat "$TMP/count" 2>/dev/null)', expected $1"
  pass "$2 → count $1"
}
calls_of() { jq -r --arg t "$1" 'select(.name == $t) | .arguments' "$TMP/calls"; }

# Case 1: the replay passes, the bridge runs as CI needs it, saved ids are substituted, the
# expected error is tolerated and every tools/call is counted.
run_replay "$TMP/tpl"
expect_status 0 "happy path"
[ "$(cat "$TMP/args")" = "mcp serve --source remote --mcp-url http://kelta-mcp:8080 --toolset all" ] \
  || fail_test "happy path: kelta was run as '$(cat "$TMP/args")'"
pass "happy path → kelta mcp serve --source remote --mcp-url http://kelta-mcp:8080 --toolset all"
expect_count 5 "happy path"
[ "$(calls_of create_record | jq -r '.attributes.collectionId')" = "create_collection-1" ] \
  || fail_test "happy path: {{aId}} was not replaced by the saved id"
pass "happy path → {{aId}} replaced by the id create_collection returned"
[ "$(calls_of update_record | jq -r '.id')" = "a-1" ] \
  || fail_test "happy path: {{aIds:k2}} was not replaced by the id of k2"
pass "happy path → {{aIds:k2}} replaced by the id bulk_apply returned for k2"
expect_output "expected error (the build hit this too)" "happy path → the expected error is logged"
expect_output "a: exists, 2 records (metadata.json records: 2)" "happy path → seed count checked"
expect_output "b: exists (0 records, no records section)" "happy path → unseeded collection checked for existence"
grep -qxF "http://gateway.test/acme/api/b?page%5Bsize%5D=1" "$TMP/curl.log" \
  || fail_test "happy path: collection b was not read through the tenant's gateway path"
pass "happy path → collections read at KELTA_URL/KELTA_TENANT/api/<collection>"

# Case 2: a call that fails once is retried, and the retry is counted.
run_replay "$TMP/tpl" FAKE_FLAKY=update_record:1
expect_status 0 "flaky call"
expect_count 6 "flaky call (5 calls + 1 retry)"
[ "$(calls_of update_record | grep -c '"id"')" -eq 2 ] || fail_test "flaky call: update_record not sent twice"
pass "flaky call → update_record sent twice"

# Case 3: a call that keeps failing fails the replay after MCP_REPLAY_RETRIES retries, and the
# count file holds every attempt so far.
run_replay "$TMP/tpl" FAKE_FLAKY=update_record:9 MCP_REPLAY_RETRIES=2
expect_status 1 "failing call"
expect_output "call 4/5 update_record (s) failed after 3 attempt(s)" "failing call → named with its attempts"
expect_count 6 "failing call (3 calls + 3 attempts)"
[ -z "$(calls_of bulk_apply | jq -r 'select(.fakeFail) | .fakeFail')" ] \
  || fail_test "failing call: the replay went on after it"
pass "failing call → the replay stops there"

# Case 4: an unexpected error is not retried away when retries are off.
run_replay "$TMP/tpl" FAKE_FLAKY=create_record:1 MCP_REPLAY_RETRIES=0
expect_status 1 "unexpected error, no retries"
expect_count 2 "unexpected error, no retries"

# Case 5: an expected error that no longer happens passes with a warning.
run_replay "$TMP/tpl" FAKE_NEVER_FAIL=1
expect_status 0 "expected error did not happen"
expect_output "::warning::mcp-replay: call 5/5 bulk_apply (s) succeeded, but the fixture expects an error" \
  "expected error did not happen → warning"

# Case 6: a tool the fixture calls is not listed → failure before any tools/call.
run_replay "$TMP/tpl" FAKE_TOOLS="create_collection create_record bulk_apply"
expect_status 1 "missing tool"
expect_output "tools/list lacks update_record, which the fixture calls" "missing tool → named"
[ ! -s "$TMP/calls" ] || fail_test "missing tool: tools/call was sent anyway"
pass "missing tool → no tools/call sent"

# Case 7: a seed count mismatch fails.
run_replay "$TMP/tpl" FAKE_COUNTS='{"a": 1, "b": 0}'
expect_status 1 "seed count mismatch"
expect_output "a holds 1 records, the metadata.json records sections have 2" "seed count mismatch → reported"

# Case 8: a template collection that does not exist fails.
run_replay "$TMP/tpl" FAKE_COUNTS='{"a": 2}'
expect_status 1 "missing collection"
expect_output "collection b does not exist in acme" "missing collection → reported"

# Case 9: a {{var}} nothing saved fails before the call is sent.
jq '.calls[1].arguments.attributes.collectionId = "{{nope}}"' "$TMP/tpl/mcp-replay/calls.json" > "$TMP/bad.json"
run_replay "$TMP/tpl" MCP_REPLAY_FIXTURE="$TMP/bad.json"
expect_status 1 "unresolved variable"
expect_output "{{nope}} was never saved by an earlier call" "unresolved variable → named"
[ -z "$(calls_of create_record)" ] || fail_test "unresolved variable: the call was sent anyway"
pass "unresolved variable → the call is not sent"

# Case 10: a save expression that finds nothing fails.
jq '.calls[0].save = {"aId": ".data.missing"}' "$TMP/tpl/mcp-replay/calls.json" > "$TMP/bad.json"
run_replay "$TMP/tpl" MCP_REPLAY_FIXTURE="$TMP/bad.json"
expect_status 1 "empty save"
expect_output "save aId: .data.missing is null" "empty save → named"

# Case 11: a call to a local-only cli_* tool fails before the bridge starts, naming the step.
jq '.calls[3].tool = "cli_metadata_export" | .calls[3].step = "Export"' "$TMP/tpl/mcp-replay/calls.json" > "$TMP/bad.json"
run_replay "$TMP/tpl" MCP_REPLAY_FIXTURE="$TMP/bad.json"
expect_status 1 "cli_ tool in fixture"
expect_output "::error::mcp-replay: call 4/5 cli_metadata_export (Export): cli_ tools are local CLI tools" \
  "cli_ tool in fixture → ::error:: names the call and its step"
if [ -s "$TMP/calls" ] || [ -s "$TMP/args" ]; then
  fail_test "cli_ tool in fixture: the bridge ran anyway"
fi
pass "cli_ tool in fixture → kelta mcp serve not started, no tools/call sent"

# --- the checked-in applicant-tracking fixture -------------------------------------------
FIXTURE="$REAL_TEMPLATE/mcp-replay/calls.json"
BASELINE="$(tr -d '[:space:]' < "$REAL_TEMPLATE/mcp-replay/baseline.txt")"

node "$REAL_TEMPLATE/mcp-replay/generate.mjs" --check > "$TMP/out.log" 2>&1 \
  || fail_test "calls.json is not what generate.mjs writes"
pass "applicant-tracking: calls.json is what generate.mjs writes"

if grep -iE 'rzware|spotopened|couchpicks' "$FIXTURE" > "$TMP/out.log"; then
  fail_test "applicant-tracking: calls.json names RZWare, SpotOpened or CouchPicks"
fi
pass "applicant-tracking: no RZWare, SpotOpened or CouchPicks string"

jq -r '.calls[] | select(has("arguments") | not) | .tool' "$FIXTURE" > "$TMP/out.log"
[ ! -s "$TMP/out.log" ] || fail_test "applicant-tracking: calls without arguments"
pass "applicant-tracking: every call carries explicit arguments"

# Each {{var}} or {{var:key}} must be saved by a call before the one that uses it.
jq -r '.calls | to_entries[] | .key as $i | .value
  | ((.save // {} | keys[] | "save \($i) \(.)"),
     ([.arguments | .. | strings | select(test("^\\{\\{.+\\}\\}$")) | .[2:-2] | split(":")[0]]
       | unique[] | "use \($i) \(.)"))' "$FIXTURE" > "$TMP/vars.txt"
grep '^use ' "$TMP/vars.txt" > "$TMP/uses.txt" || true
while read -r _ index var; do
  awk -v i="$index" -v v="$var" '$1 == "save" && $2 < i && $3 == v { found = 1 } END { exit !found }' \
    "$TMP/vars.txt" || fail_test "applicant-tracking: call $index uses {{$var}} before any call saves it"
done < "$TMP/uses.txt"
pass "applicant-tracking: every {{var}} is saved by an earlier call"

[ "$(jq '.calls | length' "$FIXTURE")" -eq "$BASELINE" ] \
  || fail_test "applicant-tracking: $(jq '.calls | length' "$FIXTURE") calls, baseline.txt says $BASELINE"
pass "applicant-tracking: baseline.txt ($BASELINE) is the number of calls in the fixture"

# The whole fixture against the fakes: every substitution resolves and the replay sends
# exactly the baseline (the fakes never fail a call, so the expected errors only warn).
SEED_COUNTS="$(jq -c '[.records[] | {(.collection): (.rows | length)}] | add' "$REAL_TEMPLATE/metadata.json")"
run_replay "$REAL_TEMPLATE" FAKE_TOOLS="$FIXTURE_TOOLS" FAKE_COUNTS="$SEED_COUNTS"
expect_status 0 "applicant-tracking full replay"
expect_count "$BASELINE" "applicant-tracking full replay"
[ "$(calls_of update_record | jq -r '.id' | sort -u | paste -sd ' ')" = "job-applications-1 job-applications-5 job-applications-8" ] \
  || fail_test "applicant-tracking: update_record ids are $(calls_of update_record | jq -r '.id' | paste -sd ' ')"
pass "applicant-tracking full replay → application ids resolved from the seed bulk_apply"
if grep -F '{{' "$TMP/calls" > /dev/null; then
  fail_test "applicant-tracking full replay: an unresolved {{var}} reached kelta"
fi
pass "applicant-tracking full replay → no {{var}} left in any argument"

echo "All mcp-replay.sh tests passed"
