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
  `any_java`, `e2e`, `workflows`). Downstream jobs run only for changed paths; a
  `quality-gate` job passes if every *triggered* job passed (skipped jobs are allowed).

## `ci.yml` — Pull-request CI

Trigger: `pull_request` → `main`, plus `workflow_dispatch`.

| Job | What it does |
|-----|--------------|
| `changes` | Path-filter detection (above). |
| `test-java` | Matrix `[gateway, worker, auth, ai, mcp]`. Builds runtime libs **`-DskipTests`**, then `mvn verify -f kelta-<svc>/pom.xml -B` — i.e. it tests only the *services*, never the runtime libs it just built. That is what `test-runtime` exists for. Uploads `kelta-test-results-<svc>` (surefire XML) + `java-coverage-<svc>` (JaCoCo). |
| `test-runtime` | `mvn test` over the seven `kelta-platform/runtime/*` modules (~1,900 tests). Runs when the `runtime` path filter (`kelta-platform/**`) or `workflows` changed. **Why it's a separate job:** `test-java` builds these with `-DskipTests`, so before this existed their tests were compiled and never executed — the query engine, storage adapters and their DDL/SQL generation, flow engine, validation and `TenantContext` had zero CI coverage, and a FORMULA regression (#1281) sat on a green `main` for eleven days with its own test failing. Its own job rather than dropping `-DskipTests` above, so the suite runs once instead of once per service. Uploads `java-test-results-runtime`. |
| `test-frontend` | In `kelta-web`: `npm ci`, `npm run lint`, `npm run typecheck`, `npm run format:check`, `npm run test:coverage` (Vitest, v8 coverage, **80% threshold** in `vitest.config.ts`). Then **`kelta-ui/app`**: builds the `kelta-web` packages (formula → sdk → plugin-sdk → components — its `@kelta/*` types resolve through their built `dist/`), `npm ci`, `npm run typecheck`, **`npm run test:run`** (223 files, ~2,700 tests). The test step is newer than the rest: the job typechecked `kelta-ui/app` but never ran its tests, so the whole admin/builder + end-user UI suite gated nothing. **`NODE_VERSION` must stay ≥ 20.19** (pinned by `kelta-ui/app`'s `engines`) — on 18, jsdom's `html-encoding-sniffer` `require()`s an ES module and all 223 files fail to start with `ERR_REQUIRE_ESM` before a test runs. 20 also matches `kelta-ui/Dockerfile` (`node:20-alpine`). |
| `integration-tests` | Builds service JARs, pre-pulls images (`redis:7`, `nats:2.10`, `cerbos:0.40.0`, `eclipse-temurin:25-jre`), pre-builds service images, then `mvn verify -f kelta-test-harness/pom.xml -Pintegration-tests` (failsafe). `TESTCONTAINERS_RYUK_DISABLED=true`. |
| `e2e` | Builds JVM service images (`Dockerfile.jvm`), spins up the full stack via `docker-compose.yml -f docker-compose.ci.yml`, runs Playwright (`mcr.microsoft.com/playwright:v1.58.2-noble`) inside the compose network. Timeout 45 min. Uploads HTML report + traces. **Readiness gating:** `up -d --wait` is the only barrier before the tests run, so it has to be trustworthy. The gateway's healthcheck targets `/actuator/health/readiness`, which stays 503 until its route table is loaded — `RouteInitializer` is an `ApplicationRunner` and runs *after* the web server starts, so plain `/actuator/health` reports UP while every `/api/**` still 404s. That window used to surface as the first few minutes of specs failing and everything afterwards passing, which reads like a broken page but is pure startup ordering. See `architecture.md` → Gateway startup & readiness. |
| `quality-gate` | Green iff all triggered jobs passed. |

## `build-and-publish-containers.yml` — Post-merge deploy

Trigger: `push` → `main` (path-filtered), plus `workflow_dispatch`.

1. `changes`, `test-java`, `test-runtime`, `test-frontend` — same as CI. `test-frontend` here
   previously ran **kelta-web only**, so `kelta-ui/app` was validated nowhere on `main`; it now
   mirrors CI (build packages → install → typecheck → `test:run`).
   `build-and-push` gates on all three test jobs (`result != 'failure'`), so a runtime-module
   regression stops images from building rather than shipping.
2. **`build-and-push`** — matrix `[gateway, worker, worker-migrate, auth, ui, ai, mcp]`.
   Docker buildx → pushes to `harbor.rzware.com/emf/emf-<svc>:latest` and
   `:main-<short-sha>`. Per-service GHA cache scope.
3. **`deploy`** — checks out `homelab-argo`, runs kustomize to bump image tags (verifies
   each image exists on Harbor first), commits to `homelab-argo`. ArgoCD then syncs.
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
   E2E token + Authentik secrets. Timeout 25 min. Files failing-E2E bug tasks to `emf-queue`.
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

Auto-merges PRs labeled `autopilot` from authorized actors (`cklinker`,
`github-actions[bot]`). Squash strategy. Uses `ARGOCD_REPO_TOKEN` (a PAT, so the merge
push triggers the downstream publish workflow). **`type: security` tasks are never
auto-merged** (see `SECURITY.md`).

## Other workflows

- `build-runner-image.yml` — builds the self-hosted CI runner image.
- `.github/workflows/README.md` and `scripts/ci/README.md` — runner + shared CI DB
  (`kelta-ci-db`, schema-isolated per run) notes.

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
