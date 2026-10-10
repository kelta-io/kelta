# kelta-worker

Primary service for the Kelta platform. Owns database migrations (Flyway), workflow execution, collection lifecycle, and all business logic.

## Package Layout

```
io.kelta.worker/
  config/          ← Spring @Configuration (NATS subscriptions, Cerbos, S3, scheduler, tenant datasource)
  controller/      ← REST controllers (thin — delegate to services)
  event/           ← Record event publisher
  filter/          ← Servlet filters (tenant context, caller context, MDC, metrics, login tracking)
  flow/            ← JdbcFlowStore (workflow persistence)
  handler/         ← Action handlers (e.g., SubmitForApprovalActionHandler)
  interceptor/     ← Cerbos authorization advice
  listener/        ← NATS event listeners + BeforeSaveHooks (config event publishing)
  module/          ← Runtime module management
  repository/      ← JDBC repositories (raw SQL, NOT JPA)
  scim/            ← SCIM 2.0 protocol (controller, model, service, filter)
  service/         ← Business logic (45+ services)
    email/         ← Email providers (SMTP, templates)
    push/          ← Push notification providers (FCM, APNs)
    sms/           ← SMS providers
```

## Key Patterns

### Models
Models are Java records with static factory methods — NOT JPA entities:
```java
public record Conversation(UUID id, String tenantId, ...) {
    public static Conversation create(String tenantId, ...) { ... }
}
```

### Repositories
Custom JDBC repositories using `JdbcTemplate` with raw SQL — NOT Spring Data JPA:
```java
@Repository
public class ApprovalRepository {
    private final JdbcTemplate jdbcTemplate;
    // Raw SQL queries via jdbcTemplate.query(), queryForObject(), update()
}
```

### BeforeSaveHook → NATS Event Publishing
When a system collection config changes, broadcast via NATS JetStream so all pods update their in-memory registries.

1. Create a class in `listener/` implementing `BeforeSaveHook` and override `getCollectionName()`
2. In `afterCreate` / `afterUpdate` / `afterDelete`, publish via `PlatformEventPublisher` to a subject under `kelta.config.*` (e.g. `kelta.config.collection.changed.<tenantId>`)
3. All pods consume the event via their `NatsSubscriptionConfig` and refresh local state

**Reference**: `listener/CollectionConfigEventPublisher.java` (subject prefix: `kelta.config.collection.changed.`)
**Reference**: `listener/FieldConfigEventPublisher.java`

> Never call `lifecycleManager.refreshX()` directly from a hook **as a substitute for** the NATS broadcast — that only updates the local pod and leaves every other pod stale. Publishing the event is what keeps all pods consistent.
>
> **Read-after-write exception (issue #910):** a hook MAY *additionally* trigger a synchronous local refresh so the originating pod is consistent for an immediate read-after-write (e.g. `addField` → `getById` on the same pod), **provided it still publishes the NATS config event** for all other pods. Use `CollectionLifecycleManager.refreshOrInitializeLocally(collectionId)` for this — see `FieldConfigEventPublisher`. Local-refresh-*only* (skipping the broadcast) remains forbidden.

### NATS Subscriptions
Listeners are plain classes registered in `config/NatsSubscriptionConfig` at `@PostConstruct` time via `NatsSubscriptionManager`. There are no annotation-based listener bindings. A listener method typically takes the raw JSON message and extracts the `PlatformEvent` envelope.

**Reference**: `listener/CollectionSchemaListener.java`, `listener/FlowEventListener.java`, `config/NatsSubscriptionConfig.java`

### Flyway Migrations
Location: `src/main/resources/db/migration/`
Use the next sequential `V<n>__description.sql` — check the highest existing version first:
```bash
ls kelta-worker/src/main/resources/db/migration | sort -V | tail -3
```

### Deploy-time schema (migrate profile)
Flyway **and** collection DDL run in the `migrate` profile only — the ArgoCD `PreSync` Job. Pods
run neither. `runner/SchemaBootstrapRunner` (`@Profile("migrate")`, `@Order(10)`) applies every
active collection's `CREATE TABLE`/reconcile once and throws on failure, which fails the Job and
stops the rollout. Two rules follow:
- **Don't put work in an `ApplicationRunner` ordered at/after `MigrateShutdownRunner`**
  (`@Order(Integer.MAX_VALUE)`) — its `System.exit(0)` means anything after it never runs, and
  `ApplicationReadyEvent` listeners never fire in the Job at all.
- **Keep `applySchema=true` on runtime paths.** `CollectionLifecycleManager.initializeCollection`
  defaults to applying DDL; only `WorkerBootstrapService` passes `false`. A collection created
  after deploy has no Job to lean on and needs its table from the NATS `collection.changed` path.

### Error Handling
- `ScimException` → SCIM endpoints (400, 404, 409 with scimType)
- `EmailDeliveryException` → Email send failures
- `SmsDeliveryException` → SMS send failures
- `PushDeliveryException` → Push notification failures
- Runtime errors caught by Spring `@ExceptionHandler` in `ScimExceptionHandler`

## When Creating a New Endpoint

1. Add a `@RestController` in `controller/` — keep thin, delegate to service
2. Create or update a service class in `service/`
3. If it needs persistence, add methods to existing repository or create new `@Repository` with `JdbcTemplate`
4. Add a test: `src/test/java/io/kelta/worker/controller/MyControllerTest.java`
5. **Tenant context** is pre-bound per request by `filter/TenantContextFilter` from the gateway's `X-Tenant-ID` / `X-Tenant-Slug` headers — **read** `TenantContext` (or `@RequestHeader("X-Tenant-ID")`); do **not** call `runWithTenant` on a request path. RLS scopes queries automatically. The caller is pre-resolved too: `filter/CallerContextFilter` binds `CallerContext` (runtime-core — caller UUID, INTERNAL/PORTAL, `viewAll`/`modifyAll`); read `CallerContext.current()` instead of re-resolving `X-User-Id` (empty = internal tier). The same tenant filter binds `GeoContext.CURRENT_GEO` (runtime-core) from the gateway's `X-Geo-*` headers — read `GeoContext.current()`/`currentCountry()` for request-origin geolocation; it is unbound on flow/scheduled paths.
6. **Authorization**: `/api/admin/**` (and other `static-*` routes) are **not** covered by per-resource Cerbos — they get only the blanket `API_ACCESS` check, and the worker advices exclude `/api/admin/` (and the record advice excludes `/api/telehealth/` — participant scoping lives in those controllers). Enforce a specific permission **in the controller/service**. A new top-level `/api/<x>/**` segment needs a gateway static route (`RouteConfigService.registerStaticRoutes()`) or it 404s. See `.claude/docs/architecture.md` → Authorizing a new endpoint.

**Reference**: `ModuleController.java` + `ScimUserControllerTest.java`. Full recipe: `.claude/docs/playbooks.md` → "Add a worker REST endpoint".

## When Adding a BeforeSaveHook

1. Create a class in `listener/` implementing `BeforeSaveHook`
2. Return the target system collection name from `getCollectionName()`
3. In `afterCreate` / `afterUpdate` / `afterDelete`, publish via `PlatformEventPublisher` to a `kelta.config.*` subject
4. If other pods need to react, register a handler in `NatsSubscriptionConfig`

**Reference**: `CollectionConfigEventPublisher.java`, `ValidationRuleRefreshHook.java`

## When Adding a System Collection

1. Declare it in `runtime-core/.../model/system/SystemCollectionDefinitions.java` — add a `<name>()` factory via the `systemBuilder(name, displayName, tableName)` helper, then add it to `all()`.
2. The worker does **NOT** create system tables (Flyway owns them) — ship `src/main/resources/db/migration/V<n>__create_<table>.sql` **and** add the table to the RLS-enable migration (V77-style, table names hardcoded).
3. Register a `<Name>RefreshHook` `@Bean` in `config/FlowConfig.java` via `hookRegistry.register(hook)` that publishes `kelta.config.collection.changed.<id>` (reuse `CollectionConfigEventPublisher.SUBJECT_PREFIX`).

**Reference**: `ValidationRuleRefreshHook.java`. Full recipe: `.claude/docs/playbooks.md` → "Add a system collection".

## Reference Implementations

| Pattern | File |
|---------|------|
| REST controller | `controller/ModuleController.java` |
| SCIM controller | `scim/controller/ScimUserController.java` |
| BeforeSaveHook publishing NATS events | `listener/CollectionConfigEventPublisher.java` |
| NATS event listener | `listener/CollectionSchemaListener.java` |
| NATS subscription wiring | `config/NatsSubscriptionConfig.java` |
| JDBC repository | `repository/ApprovalRepository.java` |
| Owner-scoped writes (don't write a per-collection owner-guard hook — declare `ownerField`/`ownerScope`/`ownerScopeReads` on the collection: `SystemCollectionDefinitions.watches()` for a system collection, collection metadata via the API for a tenant one; the wildcard guard enforces it) | `listener/OwnerScopeGuardHook.java` |
| Support staff acting on a member's owner-scoped rows (re-bind `CallerContext` to the member for the write, add the `viewAll` bypass for a tenant-wide read) | `controller/SupportPermissions.java` (`writeAs`/`readAll`) |
| Service with event publishing | `service/CollectionLifecycleManager.java` |
| Unit test | `scim/service/ScimUserServiceTest.java` |
| Controller test | `scim/controller/ScimUserControllerTest.java` |
| Action handler | `handler/SubmitForApprovalActionHandler.java` |

## Running Tests

```bash
mvn test -f kelta-worker/pom.xml                                              # All tests
mvn test -f kelta-worker/pom.xml -Dtest=ScimUserServiceTest                   # Single class
mvn test -f kelta-worker/pom.xml -Dtest=ScimUserServiceTest#getUserNotFound   # Single method
mvn test -f kelta-worker/pom.xml -Dtest="Scim*"                               # Pattern match
mvn verify -f kelta-worker/pom.xml -Pintegration-tests                        # Include integration tests
```

## Test Fixtures

Use `TestFixtures.java` in `src/test/java/io/kelta/worker/` for pre-built domain objects. For collection/field definitions, leverage `CollectionDefinitionBuilder` and `FieldDefinitionBuilder` from runtime-core.
