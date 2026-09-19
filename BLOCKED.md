# PLT-278 — blocked: review findings for PR #1564 are placeholder text

## What came back

The strike-1 review appended to the task record (and passed to this worker) is:

```
Summary: Test summary line one two three.
- [blocker] task.md:2 — test note
```

That is not an actionable finding, on three independent counts:

1. **No defect is described.** "test note" states no behaviour, no expectation, no
   acceptance line it fails. There is nothing to fix and nothing to verify a fix against.
2. **The cited file is not in the diff, or in the repo at all.** `find /work/emf -name
   task.md` returns nothing. The only `task.md` on this pod is `/work/task.md`, the
   tracker-rendered task file, which is *outside* the clone and is not part of PR #1564.
3. **The cited line is tracker-owned metadata.** Line 2 of `/work/task.md` is the
   `acceptance:` frontmatter key — generated from the tracker record. A PR diff in this
   repo cannot change it, and a worker should not be editing its own acceptance criteria.

The `Summary` line ("line one two three") reads as fixture data from a reviewer
smoke-test rather than output from a reviewer that read the diff.

## What I did not do

I did not invent a plausible-looking finding and "fix" it. PR #1564 is green and was
reviewed on the strength of a real CI pass; editing it against a guess at what the
reviewer meant would put unreviewed changes into an approved PR and burn the second
strike (which routes to a human anyway) on speculation.

The working tree is therefore untouched and identical to the reviewed commit:

- `HEAD` = `cd21f18` *fix(marketing): kelta.io/www.kelta.io were never deployed — manifest describes a cluster that does not exist*
- `git status` clean, `git diff HEAD` empty
- 7 files / +158 −52: `kelta-marketing/k8s/deployment.yaml`,
  `.github/workflows/build-and-publish-containers.yml`,
  `kelta-marketing/test/marketing-site.test.ts`, `.claude/docs/{ci-cd,concerns,status}.md`,
  `.claude/CHANGELOG.md`

Nothing was committed on this attempt, so the PR is unchanged and still reviewable as-is.

## What is needed to unblock

One of:

- **Re-run the reviewer against PR #1564** and append its real findings. If the reviewer
  harness emitted fixtures, that is a fleet-side bug worth fixing before the re-run —
  otherwise the next attempt gets the same placeholder.
- **Or restate the blocker against a file in the diff**, naming the path, the line and the
  acceptance criterion it violates. Any of the seven files above is a valid target.
- **Or approve PR #1564** if the strike was issued in error.

## Standing caveat on this task, independent of the review

Acceptance line 2 requires a real post-merge `Build and Deploy` run on `main` to pass the
`Wait for public DNS to serve the marketing site (post-deploy readiness)` step. As recorded
in the commit message, the diagnosis is that `HTTP 000` is **DNS**, not TLS or routing: the
apex `kelta.io` A record is stale (`174.16.114.174`, no longer answering on :443). The
manifest fixes in this PR (namespace `kelta` → `emf`, name `kelta-marketing` →
`emf-marketing`, dropping the ingress-nginx class and the `letsencrypt-prod`/`kelta-io-tls`
block that would have clobbered the existing `*.kelta.io` wildcard) are necessary but
**not sufficient on their own** — the public A/CNAME records for `kelta.io` and
`www.kelta.io` are owned outside this repo and must be corrected for that step to go green.
Whoever picks this up should confirm that DNS change is scheduled, or acceptance line 2
cannot pass no matter what lands in `emf`.
