#!/usr/bin/env bash
# PreToolUse hook for Edit/Write/MultiEdit. If the touched file is a Flyway
# migration, verify the sequence number matches a claim made via
# scripts/migration-claim.sh. Prevents two parallel workers from grabbing
# the same V<N>.
#
# Tool input shape (varies by tool):
#   Edit:      { "tool_input": { "file_path": "..." } }
#   Write:     { "tool_input": { "file_path": "..." } }
#   MultiEdit: { "tool_input": { "file_path": "..." } }

set -uo pipefail

input="$(cat)"
file="$(printf '%s' "$input" | jq -r '.tool_input.file_path // ""' 2>/dev/null)"
[[ -z "$file" ]] && exit 0

# Match Flyway migrations only.
case "$file" in
  *kelta-worker/src/main/resources/db/migration/V*__*.sql) ;;
  *) exit 0 ;;
esac

# Extract V<N> from filename.
v="$(basename "$file" | grep -oE '^V[0-9]+' || true)"
[[ -z "$v" ]] && exit 0

block() {
  echo "BLOCKED by .claude/hooks/check-migration.sh: $1" >&2
  exit 2
}

# Find the task file for this worker (EMF_TASK_FILE is set by whatever automation runs the
# session; the RZWare fleet renders it from the tracker record).
task_file="${EMF_TASK_FILE:-}"
if [[ -z "$task_file" || ! -f "$task_file" ]]; then
  echo "WARN: EMF_TASK_FILE not set or missing; skipping migration claim check (interactive session?)" >&2
  exit 0
fi

claimed="$(grep -oE '^claimed_migration:[[:space:]]*V[0-9]+' "$task_file" | grep -oE 'V[0-9]+' || true)"
if [[ -z "$claimed" ]]; then
  block "no claimed_migration in $task_file — claim a Flyway sequence number through your automation's migration-claim step first"
fi

if [[ "$claimed" != "$v" ]]; then
  block "filename uses $v but task claims $claimed — rename the file or re-claim"
fi

exit 0
