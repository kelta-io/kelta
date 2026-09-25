# Testing Patterns

## Java Testing

### Frameworks
- **JUnit 5** (Jupiter) via Spring Boot
- **AssertJ** fluent assertions
- **Mockito** 5.21.0
- **jqwik** 1.8.2 — property-based testing
- **Testcontainers** 1.21.3 in `kelta-test-harness` (1.20.4 elsewhere via `kelta-platform`) — Docker-based integration tests. 1.21.x is required on hosts running Docker Engine 29+ (min API 1.44; older docker-java pings `/v1.32` and gets a 400 → "Could not find a valid Docker environment").
- **MockWebServer** — HTTP service mocks
- **Awaitility** — async assertions

### Integration harness (`kelta-test-harness`)

Boots a full mini-stack (Postgres, Redis, NATS, Cerbos, worker, auth, gateway) via Testcontainers and runs `*ScenarioTest.java` against it under the `integration-tests` profile.

**Standalone `*IT.java`** also run under that profile without the full stack. `CerbosGeneratedPolicyIT` starts only a Cerbos container (sqlite in-memory + admin API, pinned to the production image), pushes the worker's golden generated policies (`kelta-worker/src/test/resources/cerbos/golden/`, kept in sync with `CerbosPolicyGenerator` by `CerbosPolicyGeneratorTest.GoldenFixtureTests`) and asserts an allow/deny matrix — the pattern for "a real PDP must accept and evaluate what we generate", since the KeltaStack Cerbos is allow-all. Run one with `mvn verify -f kelta-test-harness/pom.xml -Pintegration-tests -Dit.test=CerbosGeneratedPolicyIT`.

**Service-module Testcontainers tests** (`*IntegrationTest` in kelta-worker, kelta-ai and
runtime-core — e.g. `RowLevelSecurityIntegrationTest`) run under plain surefire, not the
harness profile, and are `@Testcontainers(disabledWithoutDocker = true)`: without a usable Docker
they are **skipped**, not failed. In CI that is caught by `scripts/ci/assert-integration-tests-ran.sh`,
which fails the job when an `*IntegrationTest` suite skipped every test; CI also sets
`TESTCONTAINERS_RYUK_DISABLED=true`, since Ryuk is unreachable on the k8s-runner's shared daemon and
Testcontainers otherwise reports Docker as unavailable. Locally on Docker Engine 29+, Testcontainers
1.20.4 fails its API handshake (it speaks API 1.32; the engine's minimum is 1.40) and skips too —
run with `-Dapi.version=1.44` (e.g. `JAVA_TOOL_OPTIONS=-Dapi.version=1.44`) until the dependency is
bumped. (kelta-auth and kelta-gateway are different: their poms exclude `*IntegrationTest` from
surefire and run them only via failsafe under `-Pintegration-tests`, which the CI `test-java` job
does not activate — so those suites, including auth's `TenantBindingIntegrationTest`, do not run
in CI at all; see `concerns.md` → Test Coverage Gaps.)

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
- `doThrow(new DuplicateKeyException(...)).when(mockJdbc).execute(argThat(sql -> sql.contains("CREATE TABLE")))` to simulate PostgreSQL-only failure modes (e.g. the `pg_type_typname_nsp_index` race in concurrent `CREATE TABLE IF NOT EXISTS`) without spinning up Testcontainers. H2 won't reproduce these, so a mocked `JdbcTemplate` that throws the translated Spring exception on a matching statement is the cheapest regression guard. See `PhysicalTableStorageAdapterSystemCollectionTest.initializeCollection_swallowsDuplicateKey_fromConcurrentCreateRace`.

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

## Quickstart smoke test (CI)

`.github/workflows/ci.yml`'s `quickstart` job proves the README [Quickstart](../../README.md#quickstart)
section actually works and stays fast, rather than trusting docs to stay in sync with the
compose file by hand. It builds the JVM-mode images (`docker-compose.yml` + `docker-compose.ci.yml`
— same port-safe overlay the `e2e` job uses, for the shared k8s-runner daemon), then wraps
`ci/quickstart-run.sh` (`docker compose up -d --wait`, then a login + first-collection-creation
check piped over stdin into a `curlimages/curl` sibling container on the compose network — via
`kelta-auth`'s `/auth/direct-login` and a `POST` to `/api/collections`, `ci/quickstart-check.sh`)
in `timeout 300`. The check script goes in over **stdin, not a bind mount** — Docker on
`k8s-runner-integration` is remote and can't see the runner's filesystem (same reason
`docker-compose.ci.yml` bakes cerbos config into an image instead of mounting it). Only the wall
clock from `docker compose up` to that API call succeeding counts against the budget — image build
happens first and isn't timed. Gated on the `quickstart` path filter (`.github/path-filters.yml`):
backend/frontend source, `docker-compose*.yml`, `Makefile`, `docker/bootstrap/**`. Uses `make up`'s
default-profile services only — no `--profile ai`, matching what a first-time user actually runs.
