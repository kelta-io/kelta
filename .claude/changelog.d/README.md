# Changelog fragments

Each change adds **one new file** here instead of editing `.claude/CHANGELOG.md`, so two open
PRs never conflict on the changelog's last lines.

- **Name** the file after the task key, or a short kebab-case slug when there is no key:
  `KLT-123.md`, `fix-login-redirect.md`.
- **Content** is a single line in the existing changelog format:

  ```
  - YYYY-MM-DD <type>(<scope>): <summary> (<key>)
  ```

- **No fold step.** Fragments are never merged back into `.claude/CHANGELOG.md`; nothing
  reads them at build or release time. Release notes come from conventional commits
  (`.github/release/lib.ts`).

## Reading the full log

`.claude/CHANGELOG.md` holds the history up to the switch to fragments; the files here hold
everything since. To read both, oldest first:

```bash
cat .claude/CHANGELOG.md
cat .claude/changelog.d/[A-Za-z]*-*.md | sort   # fragments only; the glob skips this README
```

`grep -h '^- [0-9]' .claude/changelog.d/*.md | sort` does the same without relying on the
file names (it matches only dated entry lines, not this README's bullets).
