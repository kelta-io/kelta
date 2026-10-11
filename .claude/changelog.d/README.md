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
grep -h '^- ' .claude/changelog.d/*.md | sort   # fragments only; skips this README
```

`cat .claude/changelog.d/[A-Za-z]*-*.md | sort` works too, as long as the glob excludes this
`README.md`.
