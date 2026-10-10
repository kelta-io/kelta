# Testing Patterns

## Java Testing

### Frameworks
- **JUnit 5** (Jupiter) via Spring Boot
- **AssertJ** fluent assertions
- **Mockito** 5.21.0
- **jqwik** 1.8.2 — property-based testing
- **Testcontainers** 1.21.4 everywhere (`kelta-platform`, `kelta-{worker,auth,ai}`, `kelta-test-harness`) — Docker-based integration tests. **1.21.4 specifically** is required on hosts running Docker Engine 29+ (min API 1.44): earlier versions ping `/v1.32`, get a 400, and report "Could not find a valid Docker environment" — which `@Testcontainers(disabledWithoutDocker = true)` turns into a silent skip. The fix is Testcontainers' own API-version negotiation (testcontainers-java #11346, first released in 1.21.4), not a docker-java bump. Spring Boot 4.0.5's BOM would pull Testcontainers 2.0.x; each module pins 1.21.x on purpose, because 2.0 renames the modules (`postgresql` → `testcontainers-postgresql`) and moves packages — a migration of its own.
- **MockWebServer** — HTTP service mocks
- **Awaitility** — async assertions

### Integration harness (`kelta-test-harness`)

Boots a full mini-stack (Postgres, Redis, NATS, Cerbos, worker, auth, gateway) via Testcontainers and runs `*ScenarioTest.java` against it under the `integration-tests` profile.

**Standalone `*IT.java`** also run under that profile without the full stack. `CerbosGeneratedPolicyIT` starts only a Cerbos container (sqlite in-memory + admin API, pinned to the production image), pushes the worker's golden generated policies (`kelta-worker/src/test/resources/cerbos/golden/`, kept in sync with `CerbosPolicyGenerator` by `CerbosPolicyGeneratorTest.GoldenFixtureTests`) and asserts an allow/deny matrix — the pattern for "a real PDP must accept and evaluate what we generate", since the KeltaStack Cerbos is allow-all. Run one with `mvn verify -f kelta-test-harness/pom.xml -Pintegration-tests -Dit.test=CerbosGeneratedPolicyIT`.

**Service-module Testcontainers tests** (`*IntegrationTest` in kelta-worker, kelta-ai, kelta-auth and
runtime-core — e.g. `RowLevelSecurityIntegrationTest`) run under plain surefire, not the
harness profile, and are `@Testcontainers(disabledWithoutDocker = true)`: without a usable Docker
they are **skipped**, not failed. In CI that is caught by `scripts/ci/assert-integration-tests-ran.sh`,
which fails the job when an `*IntegrationTest` suite skipped every test; CI also sets
`TESTCONTAINERS_RYUK_DISABLED=true`, since Ryuk is unreachable on the k8s-runner's shared daemon and
Testcontainers otherwise reports Docker as unavailable. Locally on Docker Engine 29+, Testcontainers
1.20.4 fails its API handshake (it speaks API 1.32; the engine's minimum is 1.40) and skips too —
run with `-Dapi.version=1.44` (e.g. `JAVA_TOOL_OPTIONS=-Dapi.version=1.44`) until the dependency is
bumped. (kelta-gateway is different: its pom excludes `*IntegrationTest` from surefire and runs
them only via failsafe under `-Pintegration-tests`, which the CI `test-java` job does not activate —
so those suites do not run in CI at all; see `concerns.md` → Test Coverage Gaps. kelta-auth's
`BaselineAdminPasswordIntegrationTest` migrates its database from kelta-worker's real migration
directory via a `filesystem:` Flyway location, so it needs the whole repo checked out.)

**RLS-sensitive provisioning paths need a NOBYPASSRLS-backed test.** Any service that writes rows
belonging to a tenant *other than the one bound on the request* (sandbox provisioning, tenant
seeding, promotion, anything looping `callWithTenant`) can be silently wrong under RLS — a
mis-bound UPDATE matches 0 rows and raises nothing. Neither a mocked `JdbcTemplate` nor a harness
scenario catches it (the harness's service containers connect as the superuser — see
`concerns.md`). Cover it with a kelta-worker `*IntegrationTest` modelled on
`RowLevelSecurityIntegrationTest`: migrate as a `NOBYPASSRLS` role, wrap the pool in
`TenantAwareDataSource`, bind the *caller's* tenant, call the real service (stub only the
collaborators that don't touch the rows under test) and assert the stored row through the
superuser. `SandboxAdminHardeningRlsIntegrationTest` is the reference.

**How the harness signs in as the platform admin.** kelta-auth replaces the Flyway baseline's
`admin@kelta.local` password on first boot (`BaselineAdminPasswordInitializer`), so nothing signs in
with `password` any more. `KeltaStack` starts kelta-auth with `KELTA_BOOTSTRAP_ADMIN_PASSWORD` =
`AuthFixture.BOOTSTRAP_ADMIN_PASSWORD`, which carries a forced change; the first
`AuthFixture.loginAsAdmin()` for the `default` tenant calls `ensurePlatformAdminPassword()`, which
completes that change once per JVM through the real `/login` → `/change-password` form (CSRF token
scraped from the page, cookies carried by hand) and from then on signs in with
`AuthFixture.PLATFORM_ADMIN_PASSWORD`. There is **no** test-only switch that skips
`force_change_on_login`, and none should be added. Scenarios that seed their *own* users with a
BCrypt(`password`) hash (`DelegatedAdminScenarioTest`, `UserPreferenceScenarioTest`, …) are
unaffected. CI's compose stacks do the same with `ci/admin-first-sign-in.sh`: the `e2e` job sets
`KELTA_BOOTSTRAP_ADMIN_PASSWORD` and changes it to the password Playwright uses
(`E2E_TEST_PASSWORD`).

**When the stack won't start**: `KeltaStack.startService(...)` prints the tail of a service
container's log before rethrowing. The wait strategy is an HTTP probe on `/actuator/health`,
so a service that dies during boot is indistinguishable from a slow one — Testcontainers just
times out with a `ContainerLaunchException` that says nothing, and the container (with the
Flyway error in it) is discarded. `KeltaStackExtension` also **remembers** a failed start and
rethrows it rather than retrying: retrying meant every one of the ~40 scenario classes booted
a fresh container and waited out its multi-minute timeout, which exhausted the job's
`timeout-minutes` and got the run cancelled before a single test report was written.

**Database selection**: `KeltaStack` reads `CI_DB_JDBC_URL` at startup. When set (CI), it skips the Testcontainers PG and points worker + auth at the shared `kelta-ci-db` pool — the URL emitted by `scripts/ci/checkout-db.sh` already pins `currentSchema=ci_<run-tag>` for per-run isolation. When unset (local dev), it falls back to a Testcontainers PG on the docker network alias `postgres`. The Testcontainers dependency stays in `pom.xml` either way.

**Direct DB assertions**: `ScenarioBase.openDbConnection()` opens a JDBC connection from the test JVM (via `KeltaStack.dbJdbcUrl()`, which maps the local container port / passes the CI URL through) for asserting table state the API doesn't expose — e.g. `flow_pending_resume` in `FlowWaitResumeScenarioTest`. That user is the image's bootstrap superuser and **bypasses RLS even on FORCE'd tables**, which is what makes it useful for planting and inspecting fixtures — and what makes it useless for an RLS assertion. For those, use **`ScenarioBase.openAppDbConnection()`**: a `NOBYPASSRLS` role with the application's privilege set that `KeltaStack.provisionApplicationRole()` creates per run (`kelta_app` locally, `app_<run schema>` on the CI pool, dropped by `scripts/ci/release-db.sh`) once the worker's Flyway run has created the schema. Bind a tenant on it with `SET LOCAL app.current_tenant_id` inside a transaction — see `TenantIsolationScenarioTest.databaseDeniesCrossTenantReads`. It is granted SELECT, not write. `openDbConnection(user, password)` is still there for a purpose-built probe role, as `RecordShareWideningScenarioTest` uses. Note that the **service containers still connect as the bootstrap superuser**, so a scenario driving the API proves nothing about RLS on its own; `RowLevelSecurityIntegrationTest` (kelta-worker) is where the policies are gated, and `concerns.md` → "the harness's *service containers* still bypass RLS" tracks closing that gap. Also note V77's RLS loop targets only the `public` schema **by design**: `public` is the shared multi-tenant schema, while any non-public schema (including the CI pool's throwaway `ci_*` schemas) is tenant-/run-specific and needs no RLS gating. Practical consequence on CI: only migrations that `ALTER TABLE` directly (e.g. V150 `record_share`) carry RLS policies there, so scope harness RLS assertions to those tables, not V77-listed ones.

### Organization
- Unit tests: `src/test/java/io/kelta/...Test.java`
- Test resources: `src/test/resources/`
- No integration tests in gateway (skipITs = true)
- Naming: append `Test` to class name

### Structure
```java
@DisplayName("GatewayMetrics Tests")
class GatewayMetricsTest {
    private SimpleMeterRegistry registry;
    private GatewayMetrics metrics;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        metrics = new GatewayMetrics(registry);
    }

    @Nested
    @DisplayName("kelta.gateway.requests (Timer)")
    class RequestTimer {
        @Test
        @DisplayName("Should record request duration with all tags")
        void shouldRecordRequestDuration() {
            // arrange, act, assert
        }
    }
}
```

**Patterns**: `@DisplayName` for readable names, `@Nested` for grouping, `@BeforeEach` for setup, method names `shouldXxxWhenYyy()`, AssertJ `assertThat(actual).isEqualTo(expected)`

### Mocking
- MockWebServer for HTTP service mocks
- MockServerHttpRequest/Exchange for gateway filter tests
- Immutable test data: `List.of(...)` for constants
- Focus on behavior over state
- `Mockito.inOrder(mockA, mockB)` when the bug is about *ordering* of side-effects across collaborators (e.g. "reconcile schema must run before FK constraint statements"). Plain `verify()` checks only that calls happened, not the sequence — `InOrder` is what catches re-ordering regressions. See `PhysicalTableStorageAdapterSystemCollectionTest.initializeCollection_reconcilesSchema_beforeForeignKeyStatements` for an example.
- `doThrow(new DuplicateKeyException(...)).when(mockJdbc).execute(argThat(sql -> sql.contains("CREATE TABLE")))` to simulate PostgreSQL-only failure modes (e.g. the `pg_type_typname_nsp_index` race in concurrent `CREATE TABLE IF NOT EXISTS`) without spinning up Testcontainers. H2 won't reproduce these, so a mocked `JdbcTemplate` that throws the translated Spring exception on a matching statement is the cheapest regression guard. See `PhysicalTableStorageAdapterSystemCollectionTest.initializeCollection_swallowsDuplicateKey_fromConcurrentCreateRace`. **But a mock only covers the shapes you thought of**: that race also surfaces as `type "…" already exists` and FK `constraint "…" already exists` (both 42710), which the 23505-only guard missed in production. `PhysicalTableStorageAdapterConcurrentDdlIntegrationTest` races `initializeCollection` from six threads against real Postgres (Testcontainers) and asserts every "pod" succeeds — it fails on the pre-fix code in round 0. Classification lives in `PhysicalTableStorageAdapter.isConcurrentDdlDuplicate` (42P07, 42710, and 23505 only on the `pg_type`/`pg_class` name indexes — a 23505 from `CREATE UNIQUE INDEX` over duplicate rows is real and still fails).

### Real-DB guard for constraint-bearing writes

A mocked `JdbcTemplate` / `QueryEngine` proves what the code *sends*, never what Postgres
*accepts* or *returns*. Any path whose correctness depends on the real database — a `CHECK` or
FK constraint, JSON/`jsonb` serialization surviving a round-trip, or an RLS policy — needs at
least one `*ScenarioTest` in `kelta-test-harness` that writes through it and reads the row back.
Mock-level assertions stay for the logic around it; they are not the regression guard for the
database contract.

- **Why:** `CredentialResolverImpl` wrote an audit verb that `chk_audit_action` rejected on every
  insert; the unit test asserted the verb was passed to a mocked `JdbcTemplate`, and the write
  failure was swallowed by design, so no credential access was audited on any tenant until a log
  read found it (`concerns.md` → "Mockito audit tests passed against a table that rejected every
  row"; guard: `SetupAuditActionScenarioTest`).
- **When it applies:** new or changed writes into a constrained table, new DDL or constraints,
  `config`/`jsonb` fields whose shape must survive persistence, and authorization that depends on
  RLS or a DB-side lookup. A read/render-only change with no write, DDL or constraint does not
  need one — say so explicitly in the spec rather than silently skipping it.
- **Remember** the harness's direct DB connection bypasses RLS; use `openAppDbConnection()` for RLS
  assertions (see *Direct DB assertions* above).

## TypeScript Testing

### Frameworks
- **Vitest** — 1.3.1 (kelta-web), 4.0.18 (kelta-ui)
- **jsdom** — DOM environment
- **React Testing Library** — 14.2.1 (kelta-web), 16.3.2 (kelta-ui)
- **MSW** — 2.2.1 (kelta-web), 2.12.7 (kelta-ui)
- **fast-check** — 3.15.1 (kelta-web), 4.5.3 (kelta-ui)
- **Playwright** 1.50.0 — E2E testing (`e2e-tests/`)

### Organization
- Co-located: `FileName.test.ts` alongside source
- Property tests: `FileName.property.test.ts`
- Coverage thresholds: 80% (branches, functions, lines, statements) for kelta-web

### Structure
```typescript
import { describe, it, expect, vi, beforeEach } from 'vitest';

describe('ResourceClient', () => {
  let client: KeltaClient;

  beforeEach(() => {
    vi.clearAllMocks();
    client = new KeltaClient({ baseUrl: 'https://api.example.com' });
  });

  it('should list resources', async () => {
    // arrange, act, assert
  });
});
```

### Mocking
```typescript
// Module mocking
vi.mock('axios', async () => {
  const actual = await vi.importActual('axios');
  return { ...actual, default: { create: vi.fn(() => createMockAxiosInstance()) } };
});
```

### Component Testing
```typescript
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

it('should add filter when button clicked', async () => {
  const user = userEvent.setup();
  render(<FilterBuilder fields={mockFields} value={[]} onChange={vi.fn()} />);
  await user.click(screen.getByRole('button', { name: /add/i }));
  expect(onChange).toHaveBeenCalled();
});
```

## End-to-end (Playwright)

E2E specs live in `e2e-tests/` (see `e2e-tests/README.md` for setup, auth, and subsets).
Every new feature needs one. When a test drives a flow via `POST /api/flows/{id}/execute`
or the `execute_flow` MCP tool, remember the **double-wrap** rule (`{ "input": { ... } }`) —
see `integrations.md` → Flows. MCP tools are tested at the unit level with WireMock JSON-path
matchers asserting the on-the-wire JSON:API body (see `conventions.md` → MCP tools).

**The same suite runs post-deploy against production** (`build-and-publish-containers.yml` →
E2E, `app.kelta.io`, the `default` tenant). A spec must never persist a change to a tenant-wide
singleton setting (SMTP/email settings, password policy, portal auth, IP allowlist, …) — there is
no row to clean up, the fake value simply replaces the tenant's real configuration. Intercept the
write with `page.route(...)`, assert the request body, and `route.fulfill(...)` the server's
response (`tests/admin/setup/email-settings.spec.ts`). Records a test creates are fine as long as
it tears them down.

### Updating visual baselines

Visual baselines (`e2e-tests/tests/**/<spec>-snapshots/*-chromium-linux.png`) can only be
produced inside CI's Linux runner container against a running stack: a Mac renders fonts
differently, and fleet workers have no Docker or browser. So a PR that legitimately changes a
screenshot regenerates its own baselines in CI's `e2e` job.

**Marker.** Add `e2e-tests/.update-snapshots` to the PR, one spec path per line, relative to
`e2e-tests/`. Blank lines are ignored, and so is everything after a `#`:

```text
# object detail header moved (PLT-476)
tests/end-user/object-detail-visual.spec.ts
```

**Flow: regenerate → fail → re-run.**
1. The `changes` job sets `update_snapshots=true` only when the PR head has the marker **and**
   the PR's own diff (`base...head`) touches it. A marker that reached `main` by mistake does
   not trigger anything on other PRs.
2. After the stack is up and `kelta-e2e-runner:local` is built, the step `Regenerate visual
   baselines` runs `ci/update-snapshots.sh`. The script clones the PR **head branch** (not the
   merge ref), using `ARGOCD_REPO_TOKEN`. It aborts if the branch has moved past the commit
   this run was started for. It then runs `npx playwright test --update-snapshots <specs>` in
   the runner image, with the same network and `--env-file` as `Run Playwright tests`. Next it
   `docker cp`s each spec's `-snapshots/` directory out of the container (the image bakes the
   tests in; nothing is bind-mounted). It commits only those `*.png` files plus the marker's
   deletion, in one commit authored by `github-actions[bot]`, and pushes that commit to the head
   branch.
3. The step writes `baselines regenerated for <specs> in <sha>; CI re-runs on that commit` to
   the step summary and exits 1, so `Run Playwright tests` does not run and this run is red.
   If no PNG changed, it still removes the marker and says
   `no baseline changed for <specs>; removed the marker in <sha>`. If Playwright fails, it
   commits nothing.
4. The push is made with a PAT, so it triggers a new CI run (a `GITHUB_TOKEN` push would
   not). That run has no marker and is a normal run against the new baselines. Its start
   cancels the red run through `ci.yml`'s concurrency group, usually seconds after the push.
   The summary is written right after the push, so on a cancelled run, read the line from the
   step log (`::error::`) or the new commit's message.

Review the regenerated PNGs in the PR diff like any other change.

**Guards.**
- The step runs only for same-repo PRs
  (`github.event.pull_request.head.repo.full_name == github.repository`) and never for a head
  ref of `main`.
- The script also refuses `main` and rejects, naming the offending path, any spec that is:
  - absolute;
  - contains `..`;
  - uses characters outside `[A-Za-z0-9._/-]`;
  - is not a `*.spec.ts` file that exists on the branch and resolves (symlinks followed) under
    `e2e-tests/tests/`.
- A spec rejected by the script fails the step before Playwright runs, and nothing is pushed.

**Dispatch fallback.** Run `ci.yml` manually with `update_snapshots_ref=<branch>` and
`update_snapshots_specs="tests/a.spec.ts tests/b.spec.ts"` (space-separated, same rules), for
example `gh workflow run ci.yml -f update_snapshots_ref=<branch> -f update_snapshots_specs=<specs>`.
Only `changes` and `e2e` run. The `e2e` job checks out and builds that branch, then calls the same
script. The guards are the same: the step's `if:` refuses `main`, and the script validates the
specs. The branch must already contain `ci/update-snapshots.sh`, so rebase it if it predates
the script.

The script's own tests are `ci/update-snapshots.test.ts` (`node --test`, Node ≥ 24). They run
in the `lint-workflows` job, against a local bare repo and a fake `docker`.

## Upgrade test (CI)

`.github/workflows/upgrade-test.yml` checks that data written on the last release survives an
upgrade to HEAD (job detail: `ci-cd.md` → `upgrade-test.yml`). The pattern, for anything that
must be checked *across* a version change rather than on one build:

- **Two stacks, one `COMPOSE_PROJECT_NAME`.** The release is the base compose with
  `KELTA_VERSION` set; HEAD is the same project with the build overrides layered on. Same project
  ⇒ same named volumes. Between them `docker compose stop`, never `down -v`.
- **Seed through the API, record what came back.** `ci/upgrade-seed.sh` writes one tenant
  (`tenants` record on `default` + `POST /api/tenants/{id}/bootstrap-token` for its admin), a
  collection `upgrade_items` with STRING/INTEGER/DOUBLE/BOOLEAN/DATE fields, three records and a
  user, then GETs each back and prints the values *the release returned* as the baseline
  (`checks[]`: label, scope `platform|tenant`, path, attributes). Diffing against what was sent
  would flag release-side normalisation as an upgrade bug.
- **Verify reports every problem and names it.** `ci/upgrade-verify.sh` signs in again with the
  changed admin password (the credential must survive too), mints a fresh tenant PAT, and prints
  `MISSING <label>`, `UNREADABLE <label>` or `MISMATCH <label> field <f>: expected …, got …`
  (`<absent>` for a dropped attribute) for each, exiting 1. An empty or malformed seed exits 2 —
  a seed step that died must not read as "nothing differed".
- **Self-test without a stack.** `UPGRADE_VERIFY_STUB_DIR` makes the verify script read
  `<dir>/<path with / → _>.json` instead of calling the gateway (missing file = 404).
  `ci/upgrade-verify.test.sh` uses it to prove the clean case passes and a missing record, a
  changed value, a type change (`true` → `"true"`), a dropped field, a changed user field, a
  non-JSON body and an empty/malformed seed all fail with the resource and field named. Runs as the
  job's first step and locally with `bash ci/upgrade-verify.test.sh` (bash + jq).
- **Prove the job can go red.** Its acceptance included a temporary commit adding a migration
  that alters seeded data (e.g. `UPDATE platform_user SET last_name = 'Changed' WHERE email LIKE
  'upgrade-user@%'`, or a `DROP COLUMN` on the seeded table), a red run, and a revert before
  merge. Do the same when changing what the job seeds or compares.

## Quickstart smoke test (CI)

`.github/workflows/ci.yml`'s `quickstart` job proves the README [Quickstart](../../README.md#quickstart)
section actually works and stays fast, rather than trusting docs to stay in sync with the
compose file by hand. It builds no Kelta image: it pulls the release `.env.example` pins
(`ghcr.io/kelta-io/*:${KELTA_VERSION}`, base `docker-compose.yml` + `docker-compose.ci.yml` — the
port-safe overlay the `e2e` job also uses, for the shared k8s-runner daemon), then wraps
`ci/quickstart-run.sh` (`docker compose up -d --wait`; read the platform admin's first-boot
password from `docker compose logs kelta-auth` the way `quickstart.md` tells a new user to — no
`KELTA_BOOTSTRAP_ADMIN_PASSWORD` here, on purpose; complete the forced change with
`ci/admin-first-sign-in.sh`; then a login + first-collection-creation check, each piped over stdin
into a `curlimages/curl` sibling container on the compose network — via `kelta-auth`'s
`/auth/direct-login` and a `POST` to `/api/collections`, `ci/quickstart-check.sh`)
in `timeout 300`. The check script goes in over **stdin, not a bind mount** — Docker on
`k8s-runner-integration` is remote and can't see the runner's filesystem (same reason
`docker-compose.ci.yml` bakes cerbos config into an image instead of mounting it). Only the wall
clock from `docker compose up` to that API call succeeding counts against the budget — the image
pull happens first and isn't timed. Gated on the `quickstart` path filter (`.github/path-filters.yml`):
backend/frontend source, `docker-compose*.yml`, `Makefile`, `docker/bootstrap/**`. Uses the
default-profile services only — no `--profile ai`, matching what a first-time user actually runs.
