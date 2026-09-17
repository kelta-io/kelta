# CI/CD Pipeline

Source of truth: `.github/workflows/`. Verified against the workflow YAML — if a command
here disagrees with the YAML, the YAML wins.

## Shared environment (all workflows)

```
JAVA_VERSION=25          NODE_VERSION=20          MAVEN_VERSION=3.9.9
MAVEN_OPTS=-Dmaven.wagon.http.retryHandler.count=10 ...
```

- Maven cache key: `maven-${runner.os}-${hashFiles('**/pom.xml')}` over `~/.m2/repository`.
- npm cache key: `npm-${runner.os}-${hashFiles('kelta-web/package-lock.json')}` over `~/.npm`.
- Change detection: `.github/path-filters.yml` drives a `changes` job that outputs which
  services changed (`runtime`, `gateway`, `worker`, `auth`, `ai`, `mcp`, `web`, `ui`,
  `any_java`, `e2e`, `runtime_modules`, `quickstart`, `workflows`). Downstream jobs run only
  for changed paths; a `quality-gate` job passes if every *triggered* job passed (skipped
  jobs are allowed).

## `ci.yml` — Pull-request CI

Trigger: `pull_request` → `main`, plus `workflow_dispatch`.

| Job | What it does |
|-----|--------------|
| `changes` | Path-filter detection (above). |
| `test-java` | Matrix `[gateway, worker, auth, ai, mcp]`. Builds runtime libs **`-DskipTests`**, then `mvn verify -f kelta-<svc>/pom.xml -B` — i.e. it tests only the *services*, never the runtime libs it just built. That is what `test-runtime` exists for. Uploads `kelta-test-results-<svc>` (surefire XML) + `java-coverage-<svc>` (JaCoCo). |
| `test-runtime` | `mvn test` over the seven `kelta-platform/runtime/*` modules (~1,900 tests). Runs when the `runtime` path filter (`kelta-platform/**`) or `workflows` changed. **Why it's a separate job:** `test-java` builds these with `-DskipTests`, so before this existed their tests were compiled and never executed — the query engine, storage adapters and their DDL/SQL generation, flow engine, validation and `TenantContext` had zero CI coverage, and a FORMULA regression (#1281) sat on a green `main` for eleven days with its own test failing. Its own job rather than dropping `-DskipTests` above, so the suite runs once instead of once per service. Uploads `java-test-results-runtime`. |
| `test-frontend` | In `kelta-web`: `npm ci`, `npm run lint`, `npm run typecheck`, `npm run format:check`, `npm run test:coverage` (Vitest, v8 coverage, **80% threshold** in `vitest.config.ts`). Then **`kelta-ui/app`**: builds the `kelta-web` packages (formula → sdk → plugin-sdk → components — its `@kelta/*` types resolve through their built `dist/`), `npm ci`, `npm run typecheck`, **`npm run test:run`** (223 files, ~2,700 tests). The test step is newer than the rest: the job typechecked `kelta-ui/app` but never ran its tests, so the whole admin/builder + end-user UI suite gated nothing. **`NODE_VERSION` must stay ≥ 20.19** (pinned by `kelta-ui/app`'s `engines`) — on 18, jsdom's `html-encoding-sniffer` `require()`s an ES module and all 223 files fail to start with `ERR_REQUIRE_ESM` before a test runs. 20 also matches `kelta-ui/Dockerfile` (`node:20-alpine`). A **"Stability check" step (PLT-250)** re-runs `ReportViewPage.test.tsx` and `FieldEditor.test.tsx` 5x consecutively each, right after `Test kelta-ui` — these two files raced under CI-load contention and were fixed at the root cause (a shared `exporting` boolean; per-keystroke `userEvent.type`), not by raising `asyncUtilTimeout`, so this job's own log is the reusable evidence that the fix holds rather than a one-off green. Copy this pattern (name the file(s), loop 5x, `::group::` per iteration) for any future fix to a test that failed only under CI load. |
| `integration-tests` | Builds service JARs, pre-pulls images (`redis:7`, `nats:2.10`, `cerbos:0.40.0`, `eclipse-temurin:25-jre`), pre-builds service images, then `mvn verify -f kelta-test-harness/pom.xml -Pintegration-tests` (failsafe). `TESTCONTAINERS_RYUK_DISABLED=true`. |
| `e2e` | Builds JVM service images (`Dockerfile.jvm`), spins up the full stack via `docker-compose.yml -f docker-compose.ci.yml`, runs Playwright (`mcr.microsoft.com/playwright:v1.58.2-noble`) inside the compose network. Timeout 45 min. Uploads HTML report + traces. **Readiness gating:** `up -d --wait` is the only barrier before the tests run, so it has to be trustworthy. The gateway's healthcheck targets `/actuator/health/readiness`, which stays 503 until its route table is loaded — `RouteInitializer` is an `ApplicationRunner` and runs *after* the web server starts, so plain `/actuator/health` reports UP while every `/api/**` still 404s. That window used to surface as the first few minutes of specs failing and everything afterwards passing, which reads like a broken page but is pure startup ordering. See `architecture.md` → Gateway startup & readiness. |
| `quickstart` | K-3's CI enforcement of the README [Quickstart](../../README.md#quickstart): same JVM-image build (`docker-compose.ci.yml`) as `e2e`, then `timeout 300` around `ci/quickstart-run.sh` — `docker compose up -d --wait` followed by a login-and-create-collection check (`ci/quickstart-check.sh`, piped over stdin — not bind-mounted, the remote runner daemon can't see the filesystem — into a `curlimages/curl` container on the compose network) — direct-login as the seeded admin, then `POST /default/api/collections`. Only that sequence is timed; image build happens first and isn't part of the 300s budget. Default profile only (no `--profile ai`), matching what a first-time `docker compose up` actually starts. Timeout 20 min. |
| `dependency-audit` | `node ci/dependency-audit.mjs` — `npm audit --omit=dev` over `kelta-web` + `kelta-ui/app`, diffed against `ci/npm-audit-baseline.json`. Fails on any **new** high/critical advisory in a production dependency. Deliberately a delta gate, not `--audit-level=high`: production deps already carry **33** high/critical advisories, so a threshold gate would fail on day one, and a red `main` is a deploy outage rather than a signal. The baseline is debt to burn down — refresh it with `node ci/dependency-audit.mjs --update`, and only ever add an entry with a written reason. Needs no `node_modules` (audit resolves from the lockfile), so it skips the installs. |
| `lint-workflows` | `actionlint` over `.github/workflows/`, pinned to 1.7.12, runner labels declared in `.github/actionlint.yaml`. Exists because a `secrets` context in an `if:` condition broke the whole of `ci.yml` — GitHub exposes `secrets` only to `with:`, `env:` and `run:`, never to `if:`. That failure does not look like a red build: the file does not parse, so **no jobs run at all** (not even `Detect Changes`), the run is named after the file path rather than "CI", and the PR reads as though CI simply has not started. A YAML parse calls the broken file valid; actionlint reports `context "secrets" is not allowed here`. **shellcheck is deliberately off** (`-shellcheck=`) — the workflows carry 22 shell findings (19× SC2086, 2× SC2129, 1× SC2034), all info/style/warning and none errors; cleaning them is its own change, and gating on them here would only have blocked this one. Drop the flag once they are fixed. The `constant expression "false"` ignore is narrow: two CI-DB steps are disabled on purpose and actionlint's advice to delete their `if:` is wrong for them. |
| `quality-gate` | Green iff all triggered jobs passed. |

> **Java dependencies are not scanned yet.** OWASP dependency-check is configured in `kelta-platform/pom.xml` but only inside `<pluginManagement>`, so it never runs. Wiring it up is blocked on the `NVD_API_KEY` secret: it is set, but the NVD API rejects it (`NvdApiException: Invalid API Key`), which dependency-check 10.0.4 masked as an opaque `NullPointerException` in `NvdCveClient`. Tracked as a follow-up — see `concerns.md`.

## `build-and-publish-containers.yml` — Post-merge deploy

Trigger: `push` → `main` (path-filtered), plus `workflow_dispatch`.

1. `changes`, `test-java`, `test-runtime`, `test-frontend` — same as CI. `test-frontend` here
   previously ran **kelta-web only**, so `kelta-ui/app` was validated nowhere on `main`; it now
   mirrors CI (build packages → install → typecheck → `test:run`).
   `build-and-push` gates on all three test jobs (`result != 'failure'`), so a runtime-module
   regression stops images from building rather than shipping.
2. **`build-and-push`** — matrix `[gateway, worker, worker-migrate, auth, ui, ai, mcp,
   cli-downloads]`. Docker buildx → pushes to `harbor.rzware.com/emf/emf-<svc>:latest` and
   `:main-<short-sha>`. Per-service GHA cache scope. Immediately after each leg's "Build and
   push ${{ matrix.service }}" step, a **"Verify image pushed to Harbor"** step re-queries the
   manifest it just pushed (up to 6 attempts, 5s apart, ~30s budget) and fails *that leg* if the
   tag never becomes queryable — catching a lagging or silently-failed push at the leg that has
   the context (it just pushed), rather than three jobs later in `deploy` with no timing
   information. `deploy`'s existing `needs.build-and-push.result != 'failure'` gate already
   turns a failed leg into "don't bump this image", so no new gating logic was needed there.
   Added by PLT-236 after investigating run 35039447057 (`gh-run-35039447057`): `emf-worker`
   404'd on Harbor for the full 14-attempt/~140s budget in `deploy`, but the actual root cause
   was **not** registry read-after-write lag — `test-frontend`'s `Test kelta-ui` step failed at
   `2026-09-16T00:22:07Z`, which skipped the *entire* `build-and-push` job (all matrix legs,
   0 steps ran on any of them, confirmed via the Actions API) through its
   `needs.test-frontend.result != 'failure'` gate. No push ever happened for
   `emf-worker:main-66a7114` — there was nothing for Harbor to lag behind. `deploy` started at
   `00:22:12Z` and its first `verify_image_exists()` attempt at `00:22:16Z` (per the source run)
   was checking a tag that could never appear; it correctly refused to bump after exhausting
   retries, but the two prior fixes (PLT-230, PLT-232) had both assumed lag and widened that
   retry window, which could never fix a tag that was never pushed. The post-push check above
   does not change this specific failure mode (a fully-skipped leg still skips its own
   verify step) — it guards the *other* failure mode, a leg that did run its push but Harbor
   hasn't caught up yet or the push silently no-op'd.
3. **`deploy`** — checks out `homelab-argo`, runs kustomize to bump image tags (verifies
   each image exists on Harbor first — `verify_image_exists()` retries the manifest check up
   to 14 times, 10s apart (~130s / 2m10s total budget), since Harbor can briefly lag behind a
   successful push before the tag is queryable, especially for a service checked later in the
   `bump()` sequence; each failed attempt logs the HTTP status code it got back, and the step
   still fails with `::error::Refusing to bump ...` if every retry comes back non-200), commits
   to `homelab-argo`. ArgoCD then syncs. Left unchanged by PLT-236 — it stays a defensive
   fallback for a leg that reports success without the new build-and-push-side check running
   (e.g. `workflow_dispatch` with `push_images=false` legitimately skips both the push and the
   post-push verify).
4. **`smoke-test`** — waits for k8s rollouts; `curl .../actuator/health/liveness` on gateway
   + worker; then (only when cli-downloads was rebuilt) downloads and executes the CLI binary.
   **The CLI check keys on two different values and they can disagree:** `CLI_VERSION` is baked
   in as `1.0.<run_number>`, but ArgoCD only rolls when the image *tag* `main-<short-sha>`
   changes. A rebuild at an already-deployed commit (`workflow_dispatch` with
   `force_build_all=true`, a re-run) pushes an identical tag, so nothing rolls, the pod keeps
   serving the earlier run's version, and the version poll times out on a perfectly good
   deploy. The step now compares the running tag to the pushed tag first and asserts against
   the *served* version when they match. Don't reintroduce a bare `1.0.<run_number>` assertion.
5. **`rollback-on-smoke-failure`** — `git revert HEAD` in `homelab-argo` if smoke fails.
   **It reverts the whole image bump, not just the failing service** — a false failure in one
   smoke step rolls back every service in that commit. Run 31510066633 reverted a gateway +
   worker bump whose own health steps had already passed, because the cli-downloads version
   check timed out on an unchanged tag.
6. **`e2e-test`** — Playwright against production (`app.kelta.io`, `api.kelta.io`) using
   E2E token + Authentik secrets. Timeout 25 min. Uploads failing pod logs as the `bug-context` artifact; the RZWare fleet's `gh-failures` job files the bug task from the run.
   Also builds `@kelta/cli` (`kelta-web`: formula+sdk, then cli) so the CLI smoke spec
   (`e2e-tests/tests/admin/cli-smoke.spec.ts`) can spawn the built binary entry against the
   deployed stack via the pre-issued `E2E_API_TOKEN` (env-override auth path); the spec
   self-skips when the dist is absent.
   Because ArgoCD rolls the worker out **asynchronously** after `deploy` commits the tag,
   this job first blocks until the `emf-worker` Deployment targets **this commit's**
   `main-<short-sha>` image and its rollout is fully complete (all pods on the new
   revision) — *then* polls `/api/collections` for data readiness. Without the rollout gate,
   the gateway load-balances to not-yet-updated pods that 404 brand-new endpoints, and the
   data poll can't detect it (it hits an endpoint that exists on the old image too).

## `auto-merge.yml`

Two paths, squash strategy, both using `ARGOCD_REPO_TOKEN` (a PAT, so the merge push
triggers the downstream publish workflow):

- **human** — PRs labeled `autopilot` from `cklinker` or `github-actions[bot]`: auto-merge
  is enabled on the label (`pull_request` events), no review gate.
- **fleet** — PRs from `rzware-developer[bot]`: auto-merge is enabled only when
  `rzware-reviewer[bot]` submits an `approved` review (`pull_request_review`), and
  disabled again by a later `changes_requested`. The worker Job's review round (request
  changes → fix → push → re-review) therefore completes before anything merges. Before
  2026-09-15 this path was label-triggered too and #1478 merged on CI green eleven minutes
  after "changes requested", stranding the fix commit (#1479).

**`type: security` tasks are never auto-merged** (see `SECURITY.md`).

## `dora-metrics.yml` — nightly DORA rollup

Cron `17 4 * * *` on `k8s-runner` (in-cluster, so Loki/Mimir/Alloy are reachable by
Service DNS). Runs `scripts/dora/collect.mjs`, which recomputes the four DORA numbers
from durable sources every night — nothing is incrementally tracked, so a bad run just
gets overwritten by the next one:

| Metric | Source | Definition |
|--------|--------|------------|
| Deployment frequency | `homelab-argo` git log (`chore: update Kelta images to main-<sha>` bumps by `github-actions[bot]`) | bumps per day in the trailing window |
| Lead time for changes | GitHub GraphQL (merged PRs + commit dates) joined to the bump that shipped them via the first-parent history of `main` | p50 of first-commit → bump for every PR in the window; also merge→prod |
| Change failure rate | `revert: roll back image bump (...)` (auto) and manual `revert…` commits in `homelab-argo` | rolled-back bumps ÷ bumps |
| Time to restore | Loki `{from="state-history"}` (Grafana alert state history) + Mimir `ALERTS{alertstate="firing"}` (Prometheus rules) | p50 of Alerting→Normal per alert fingerprint |

Bands follow the Google DORA report thresholds. Segments `all` / `bot` / `human` split
lead time by PR author (`rzware-developer[bot]` vs people) — deploys are shared, so only
the change-level numbers differ.

Sinks (all idempotent): Loki (`{job="dora"}` — one line per deploy/rollback/incident
with the event's own timestamp, plus a `snapshot` per window; Loki drops byte-identical
re-pushes), Mimir via Alloy OTLP (`dora_*` gauges, `job="ci/dora"`) → Grafana **EMF DORA**
(`homelab-argo/grafana/dashboard-dora.yaml`), and — when `KELTA_DORA_TOKEN` +
`KELTA_DORA_URL`/`KELTA_DORA_TENANT` are set — the tenant's `dora-deployments` /
`dora-changes` / `dora-incidents` / `dora-snapshots` collections and a **DORA** native
dashboard, created by `scripts/dora/bootstrap-kelta.mjs`. Kelta is the long-term store:
Loki and Mimir retain 30 days, DORA wants 90+.

`build-and-publish-containers.yml` additionally stamps `{job="dora", event="deploy_healthy"}`
(merge→healthy seconds) at the end of the smoke-test job via
`scripts/dora/emit-deploy-event.sh` — best-effort, never fails the deploy. Details, local
usage and the counting caveats (cancelled builds bundling PRs, false-positive
auto-rollbacks, re-apply bumps) are in `scripts/dora/README.md`.

## Other workflows

- `build-runner-image.yml` — builds the self-hosted CI runner image.
- `dora-metrics.yml` — see above.
- `.github/workflows/README.md` and `scripts/ci/README.md` — runner + shared CI DB
  (`kelta-ci-db`, schema-isolated per run) notes. `checkout-db.sh` claims the schema;
  `release-db.sh` drops it **and** the run's `app_<schema>` role — `KeltaStack` provisions a
  per-run `NOBYPASSRLS` application role (`KeltaStack.provisionApplicationRole`) that
  scenarios connect as to observe row-level security, and it holds grants that block
  `DROP ROLE` until `DROP OWNED BY` clears them.

## Container registry & deploy

- Registry: `harbor.rzware.com/emf/emf-<service>`.
- Manifests: `homelab-argo` (kustomize), synced by **ArgoCD** to the local K8s cluster,
  namespace **`kelta`**. In-cluster service DNS is `emf-<service>` (e.g. `emf-gateway`).
- **Schema gate.** `emf/worker-migrate-job.yaml` is an ArgoCD `PreSync` hook with
  `backoffLimit: 0`, so the sync halts before any Deployment is touched if it fails. It runs, in
  order: Flyway migrations → `SystemCollectionSeeder` (`@Order(5)`) → `SchemaBootstrapRunner`
  (`@Order(10)`, applies every active collection's DDL) → `MigrateShutdownRunner`
  (`@Order(MAX_VALUE)`, `System.exit(0)`). A schema failure throws before that exit, so the Job
  exits non-zero and workers never roll out against an incomplete schema. Worker pods themselves
  run no Flyway and no collection DDL.
- Local dev never touches CI: `make up` / `docker-compose.yml`. CI overrides live in
  `docker-compose.ci.yml` (no fixed host ports, JVM Dockerfiles, CI-only cerbos image).

## CLI downloads image (kelta-cli-downloads)

The `cli-downloads` matrix entry builds `kelta-cli-downloads/Dockerfile`: a node stage
builds `@kelta/sdk`, a bun stage (pinned `oven/bun:1.2.19`) cross-compiles the kelta CLI
for all five targets with `--define` build identity (version `1.0.<run_number>`, git sha,
target), and nginx serves `/cli/manifest.json`, `/cli/latest.txt`,
`/cli/releases/<version>/…` (+ `SHA256SUMS`), and the install scripts. The deploy job bumps
`emf-cli-downloads` like any service; the smoke job (runner is in-cluster) curls the
Service, asserts the manifest version equals this run's, downloads the linux binary, and
executes `kelta version` — warning-skipping only while the homelab-argo manifests don't
exist yet. Ingress/Service/Deployment live in homelab-argo (`downloads.kelta.io`).

**Smoke waits for ArgoCD (do not revert to a one-shot check).** ArgoCD applies the image
bump asynchronously, so `kubectl rollout status` returns immediately against the still-old
ReplicaSet and a single version check races the sync — a stale-version false failure that
auto-rollback then turns into a needless revert of a good deploy (happened repeatedly:
runs 31110044147, 31116131086, 31276755616). The step now **polls `/cli/manifest.json`
until it reports `1.0.<run_number>` (≤5 min)** before asserting, and runs **only when
`cli_downloads == 'true'`** (the image is only rebuilt then; the old `|| workflows == 'true'`
ran the strict `EXPECT == run_number` check on runs where the image was not rebuilt, so it
could never match).

**Two keys, and they can disagree.** The served version is `1.0.<run_number>` (baked in at
build time) but the rollout is triggered by the image tag `main-<short-sha>`. A rebuild at an
already-deployed commit — `workflow_dispatch` with `force_build_all=true`, or a re-run —
pushes an identical tag, so ArgoCD applies nothing, the pod keeps serving the version from the
run that first built that tag, and `EXPECT` can never arrive. The step compares the running tag
to the pushed tag before polling and asserts against the **served** version when they match
(run 31510066633 is the case that proved it). Do not reintroduce a bare
`1.0.<run_number>` assertion.
