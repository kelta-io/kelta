# Contributing to Kelta Platform

Thank you for your interest in contributing! This guide covers everything you need to
get started as an outside contributor.

Maintainers: automated pull requests are opened by `rzware-developer[bot]` and reviewed by
`rzware-reviewer[bot]`; that fleet is operated from the private `rzware-ceo` repository.
Everything merged here must be a generic platform feature (Critical Rule 0 in `CLAUDE.md`).

---

## Response times

Maintainers respond to new issues and pull requests from outside contributors within
**48 hours** — a first response (triage, question, or review), not necessarily a
resolution. Use the [bug report](.github/ISSUE_TEMPLATE/bug_report.md) or
[feature request](.github/ISSUE_TEMPLATE/feature_request.md) template so we have what
we need to respond quickly.

---

## How to run locally

Clone the repo and start the full stack with one command:

```bash
git clone https://github.com/kelta-io/kelta.git
cd kelta
make setup   # first time only: copies .env, generates keys
make up      # starts all services via docker-compose.yml
make seed    # prints login credentials once the stack is healthy
```

See [`README.md`](README.md) for port mappings, environment variables, and
`make up-jvm` (lower memory, faster builds).

---

## Making a change

### One-time setup after cloning

```bash
git config core.hooksPath .githooks
```

This activates the repo's pre-commit hook (`.githooks/pre-commit`), which runs
gitleaks to prevent accidental secret commits.

### Workflow

1. Create a feature branch from `main`:
   ```bash
   git checkout main && git pull origin main
   git checkout -b feat/my-short-description   # feat|fix|refactor|test|chore|docs
   ```
2. Make your changes. Add or update tests — see [Code style](#code-style).
3. Verify locally:
   ```bash
   # Java
   mvn verify -f kelta-gateway/pom.xml -B
   mvn verify -f kelta-worker/pom.xml -B

   # Frontend
   cd kelta-web && npm run lint && npm run typecheck && npm run test:coverage
   ```
4. Commit with the DCO sign-off (see below) and open a PR. CI runs automatically.

---

## DCO sign-off

Every commit must carry a `Signed-off-by` line certifying that you wrote the code
or have the right to contribute it under the project's license. Add it automatically:

```bash
git commit -s -m "feat(scope): describe the change"
```

Or append it by hand:

```
Signed-off-by: Your Full Name <you@example.com>
```

By adding this line you certify the Developer Certificate of Origin:

---

```
Developer Certificate of Origin
Version 1.1

Copyright (C) 2004, 2006 The Linux Foundation and its contributors.

Everyone is permitted to copy and distribute verbatim copies of this
license document, but changing it is not allowed.


Developer's Certificate of Origin 1.1

By making a contribution to this project, I certify that:

(a) The contribution was created in whole or in part by me and I
    have the right to submit it under the open source license
    indicated in the file; or

(b) The contribution is based upon previous work that, to the best
    of my knowledge, is covered under an appropriate open source
    license and I have the right under that license to submit that
    work with modifications, whether created in whole or in part
    by me, under the same open source license (unless I am
    permitted to submit under a different license), as indicated
    in the file; or

(c) The contribution was provided directly to me by some other
    person who certified (a), (b) or (c) and I have not modified
    it.

(d) I understand and agree that this project and the contribution
    are public and that a record of the contribution (including all
    personal information I submit with it, including my sign-off) is
    maintained indefinitely and may be redistributed consistent with
    this project or the open source license(s) involved.
```

---

## Code style

Follow the patterns described in [`.claude/docs/conventions.md`](.claude/docs/conventions.md).

Key points:
- Backend: Java records, `JdbcTemplate` with hand-written SQL — no Spring Data JPA.
- Frontend: React 19, TypeScript; reuse components from `@kelta/components`.
- Commit messages: [Conventional Commits](https://www.conventionalcommits.org/) —
  `type(scope): description`.

---

## Commercial license

Kelta Platform is licensed under AGPLv3. If you need to use Kelta under terms other
than AGPLv3, email licensing@rzware.com.

---

## What not to do

- **Do not push directly to `main`.** All changes go through a PR.
- **Do not bypass CI** (`--no-verify`, `git push --force` against `main`). If a check
  fails, fix the root cause.
