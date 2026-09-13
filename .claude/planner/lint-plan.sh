#!/usr/bin/env bash
# Validate task-file frontmatter against the JSON Schema in
# emf-queue/schemas/task-frontmatter.schema.json.
#
# Usage:
#   .claude/planner/lint-plan.sh <task-file.md> [<task-file.md> ...]
#   .claude/planner/lint-plan.sh --all      # lint every .md in emf-queue/{ready,approved,in-progress,done,failed}
#
# Exit:
#   0 — every file valid
#   1 — at least one file invalid (errors printed to stderr)
#   2 — usage error / missing tool
#
# Dependencies (all available on Mac + worker-01):
#   - yq     (mikefarah)
#   - npx    (ships with Node)  — downloads ajv-cli@5 on first use
#   - python3 (for frontmatter extraction; stdlib only)

set -uo pipefail

EMF_QUEUE_REPO="${EMF_QUEUE_REPO:-$HOME/GitHub/emf-queue}"
SCHEMA="${LINT_SCHEMA:-$EMF_QUEUE_REPO/schemas/task-frontmatter.schema.json}"

[[ -f "$SCHEMA" ]] || { echo "Schema not found: $SCHEMA" >&2; exit 2; }
command -v yq  >/dev/null 2>&1 || { echo "yq not found"  >&2; exit 2; }
command -v npx >/dev/null 2>&1 || { echo "npx not found" >&2; exit 2; }

# Extract YAML frontmatter (text between the first two '---' delimiter lines).
extract_fm() {
  python3 - "$1" <<'PY' 2>/dev/null
import sys
path = sys.argv[1]
with open(path) as f:
    lines = f.readlines()
if not lines or lines[0].rstrip() != "---":
    sys.exit(3)
buf = []
for line in lines[1:]:
    if line.rstrip() == "---":
        sys.stdout.writelines(buf)
        sys.exit(0)
    buf.append(line)
sys.exit(3)
PY
}

# Walk arg list (or --all) into FILES[].
declare -a FILES=()
if [[ "${1:-}" == "--all" ]]; then
  for d in ready approved in-progress done failed; do
    while IFS= read -r f; do
      FILES+=("$f")
    done < <(find "$EMF_QUEUE_REPO/$d" -maxdepth 1 -type f -name '*.md' 2>/dev/null)
  done
elif [[ $# -ge 1 ]]; then
  FILES=("$@")
else
  echo "Usage: $0 <file.md>... | --all" >&2
  exit 2
fi

(( ${#FILES[@]} == 0 )) && { echo "No task files to lint." >&2; exit 0; }

# Lint each file. Build one bundle of errors so we report all at once.
bad=0
tmpdir="$(mktemp -d -t lint-plan.XXXXXX)"
trap 'rm -rf "$tmpdir"' EXIT

for f in "${FILES[@]}"; do
  base="$(basename "$f")"
  fm="$tmpdir/$base.yaml"
  json="$tmpdir/$base.json"
  out="$tmpdir/$base.out"

  if ! extract_fm "$f" > "$fm"; then
    echo "INVALID $base: missing or malformed frontmatter (no '---' fences)" >&2
    bad=$((bad + 1))
    continue
  fi

  if ! yq -o=json '.' "$fm" > "$json" 2>"$out"; then
    echo "INVALID $base: YAML parse error" >&2
    sed 's/^/    /' "$out" >&2
    bad=$((bad + 1))
    continue
  fi

  if ! npx --yes ajv-cli@5 validate \
        --spec=draft2020 --strict=false --all-errors \
        -s "$SCHEMA" -d "$json" \
        > "$out" 2>&1; then
    echo "INVALID $base:" >&2
    sed 's/^/    /' "$out" >&2
    bad=$((bad + 1))
    continue
  fi

  # Extra field-level checks (beyond JSON Schema)
  file_bad=0

  epic_val="$(yq '.epic' "$fm")"
  if [[ "$epic_val" != "null" && -n "$epic_val" ]]; then
    if ! [[ "$epic_val" =~ ^[A-Z]-[0-9]+$ ]]; then
      echo "INVALID $base: field 'epic' value '$epic_val' does not match ^[A-Z]-[0-9]+\$" >&2
      file_bad=1
    fi
  fi

  tier_val="$(yq '.tier' "$fm")"
  if [[ "$tier_val" != "null" && -n "$tier_val" ]]; then
    if [[ "$tier_val" != "0" && "$tier_val" != "1" && "$tier_val" != "2" ]]; then
      echo "INVALID $base: field 'tier' must be 0, 1, or 2 (got '$tier_val')" >&2
      file_bad=1
    fi
  fi

  acceptance_raw="$(yq '.acceptance' "$fm")"
  if [[ "$acceptance_raw" != "null" && -n "$acceptance_raw" ]]; then
    empty_count="$(yq '[.acceptance[] | select(. == "")] | length' "$fm")"
    if [[ "$empty_count" -gt 0 ]]; then
      echo "INVALID $base: field 'acceptance' contains $empty_count empty string(s)" >&2
      file_bad=1
    fi
  fi

  if (( file_bad )); then
    bad=$((bad + 1))
    continue
  fi

  echo "OK $base"
done

if (( bad > 0 )); then
  echo
  echo "$bad file(s) failed validation." >&2
  exit 1
fi
echo
echo "All ${#FILES[@]} file(s) valid."
