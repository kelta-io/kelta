#!/usr/bin/env bash
# SessionStart hook. Prints a one-screen summary of the queue + the current
# task brief if this is a worker session.
#
# Output goes to stderr (Claude shows it as session context) and is also
# echoed to stdout so the launching tmux pane shows it.

set -uo pipefail

emit() { printf '%s\n' "$1" >&2; }


emit "─── EMF autopilot session ───"
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
