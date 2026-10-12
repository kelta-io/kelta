#!/usr/bin/env bash
# SessionStart hook. Prints a short session summary (host, cwd, branch) and,
# when an external task runner sets EMF_TASK_FILE, the start of that task brief.
#
# Output goes to stderr (Claude shows it as session context).

set -uo pipefail

emit() { printf '%s\n' "$1" >&2; }


emit "─── Kelta session ───"
emit "host:    $(hostname)  user: $(whoami)"
emit "cwd:     $(pwd)"
emit "branch:  $(git rev-parse --abbrev-ref HEAD 2>/dev/null || echo '(no git)')"


if [[ -n "${EMF_TASK_FILE:-}" && -f "$EMF_TASK_FILE" ]]; then
  emit ""
  emit "─── current task ───"
  emit "file: $EMF_TASK_FILE"
  emit ""
  # Print the first 30 lines of the task file (frontmatter + start of brief).
  head -30 "$EMF_TASK_FILE" | sed 's/^/  /'
fi

exit 0
