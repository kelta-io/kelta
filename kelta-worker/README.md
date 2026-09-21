# Kelta Worker

Generic collection hosting worker for the Kelta platform. Owns database migrations, serves REST endpoints for all collections via `DynamicCollectionRouter`, executes workflows, and publishes record change events.

## Architecture

```
                    ┌──────────────────────────┐
                    │       kelta-worker         │
                    ├──────────────────────────┤
  NATS ──────────► │ CollectionSchemaListener │ ◄── schema change events
                    │ WorkflowEventListener    │ ◄── record change events
                    ├──────────────────────────┤
  HTTP  ──────────► │ DynamicCollectionRouter  │ ◄── JSON:API CRUD for all collections
                    │ InternalBootstrapCtrl    │ ◄── gateway bootstrap, permissions
                    │ GovernorLimitsCtrl       │ ◄── rate limit config + usage
                    ├──────────────────────────┤
                    │ CollectionLifecycleMgr   │ ◄── init, refresh, teardown
                    │ WorkflowEngine           │ ◄── rule evaluation + execution
                    │ ScheduledWorkflowExec    │ ◄── polls for due scheduled rules
                    ├──────────────────────────┤
                    │ PostgreSQL (Flyway)       │ ◄── 142 versioned migrations
                    │ Redis (rate limit counts) │
                    │ NATS (event publishing)  │
                    │ S3 (optional attachments) │
                    └──────────────────────────┘
```

## Key Packages

| Package | Description |
|---------|-------------|
| `controller` | `InternalBootstrapController` (gateway bootstrap, tenants, OIDC, permissions), `GovernorLimitsController` |
| `service` | `WorkerBootstrapService` (startup init), `CollectionLifecycleManager` (collection lifecycle), `S3StorageService` (attachment URLs) |
| `listener` | `CollectionSchemaListener` (NATS schema events), `WorkflowEventListener` (record change → workflow) |
| `event` | `NatsRecordEventPublisher` -- publishes record CRUD events to `kelta.record.changed` |
| `workflow` | `ScheduledWorkflowExecutor` -- polls for due rules every 60s with optimistic locking |
| `filter` | `RequestMetricsFilter` -- Prometheus metrics per collection (request count, duration, errors) |
| `advice` | `AttachmentUrlEnricher` -- enriches JSON:API responses with presigned S3 download URLs |
| `config` | NATS, storage, metrics, workflow, and S3 configuration beans |

## Internal API Endpoints

These endpoints are called by the gateway (not exposed externally):

| Endpoint | Description |
|----------|-------------|
| `GET /internal/bootstrap` | Returns all active collections + tenant governor limits |
| `GET /internal/tenants/slug-map` | Slug-to-tenant-ID mapping |
| `GET /internal/oidc/by-issuer` | OIDC provider lookup by issuer URI |
| `GET /internal/permissions` | Resolves effective permissions for a user (profiles + permission sets + groups) |

## Configuration

Key properties from `application.yml`:

```yaml
spring.datasource:
  url: jdbc:postgresql://${DB_HOST:localhost}:${DB_PORT:5432}/${DB_NAME:kelta_control_plane}
  username: ${DB_USERNAME:kelta}
  password: ${DB_PASSWORD:kelta}

spring.flyway:
  enabled: ${FLYWAY_ENABLED:true}
  baseline-on-migrate: true

nats.url: ${NATS_URL:nats://localhost:4222}
spring.data.redis:
  host: ${REDIS_HOST:localhost}
  port: ${REDIS_PORT:6379}

kelta:
  storage.mode: PHYSICAL_TABLES
  worker.id: ${kelta.worker.id:kelta-worker-default}
  workflow:
    enabled: true
    scheduled.poll-interval-ms: 60000
  storage.s3:
    enabled: false
    bucket: kelta-attachments
    presigned-url-expiry-minutes: 15
```

## Database Migrations

Flyway migrations are in `src/main/resources/db/migration/`. The baseline was flattened into `V1__baseline` (#1189); check the directory for the current head before adding one (deployed history keeps the pre-flatten numbering, so the ranges below describe *what* landed, not files that still exist). Key migrations:

| Range | Content |
|-------|---------|
| V1-V3 | Core schema (collections, fields, tenants), OIDC seeding, default menus |
| V10-V12 | Users, permissions, sharing |
| V15 | Picklist tables |
| V26 | Workflow rules |
| V39 | Worker-specific tables |
| V42-V43 | Notes, attachments, system collections |
| V50 | Demo data (ecommerce clothing store) |
| V55 | Enhanced permission model |
| V59-V62 | Workflow engine foundation |
| V66-V68 | Base entity / audit columns for junction, layout, and system tables |
| V77-V86 | Row-level security and per-tenant table isolation |
| V101-V113 | kelta-auth tables, OIDC federation, MFA (TOTP/SMS/push), personal access tokens |
| V126-V142 | Credential vault, API spec library, layout rules, email templates, custom domains, auto-number sequences |

## Governor Limits

Per-tenant limits are tracked via `GovernorLimitsController` (`GET /api/governor-limits`, `PUT /api/governor-limits/tier`, `PUT /api/governor-limits`). Defaults come from the tenant's edition (`FREE`, `PROFESSIONAL`, `ENTERPRISE`, `UNLIMITED`) in `TenantTierQuotas`; keys: `apiCallsPerDay`, `storageGb`, `maxUsers`, `maxPortalUsers`, `maxCollections`, `maxFieldsPerCollection`, `maxWorkflows`, `maxReports`, `aiEnabled`, `aiTokensPerMonth`, `campaignEmailsPerDay`, `telehealthEnabled`, `videoMinutesPerMonth`, `archiveAfterDays`, `retentionYears`, `purgeLiveAfterDays`. See `TenantTierQuotas.java` for the per-edition numbers.

## Metrics

Prometheus metrics exposed at `/actuator/prometheus`:

- `kelta_worker_request_total` -- counter by collection, method, status
- `kelta_worker_request_duration_seconds` -- histogram by collection, method
- `kelta_worker_error_total` -- counter by collection, error type
- `kelta.worker.collections.active` -- gauge of active collections
- `kelta.worker.collection.count` -- gauge for HPA scaling

## Building

```bash
# Build runtime dependencies first
mvn clean install -DskipTests -f kelta-platform/pom.xml \
  -pl runtime/runtime-core,runtime/runtime-events,runtime/runtime-jsonapi,runtime/runtime-module-core,runtime/runtime-module-integration,runtime/runtime-module-schema \
  -am -B

# Build and test worker
mvn verify -f kelta-worker/pom.xml -B
```

## Running Locally

Requires PostgreSQL, Redis, and NATS. Start infrastructure via Docker Compose from the repo root:

```bash
docker-compose up -d
mvn spring-boot:run -f kelta-worker/pom.xml
```

The worker starts on port **8080** (mapped to **8083** in Docker Compose).

## Testing

JUnit 5 + Mockito. Tests cover bootstrap, governor limits, schema listeners, workflow execution, metrics, S3 storage, and event publishing.

```bash
mvn verify -f kelta-worker/pom.xml -B
```

## Docker

**Production** (`Dockerfile`): multi-stage **GraalVM CE 25 native-image** build
(`ghcr.io/graalvm/native-image-community:25-ol9` builder) → `debian:12-slim` runtime —
**no JRE**, self-contained binary, ~50 ms startup. The native build bakes
`kelta.storage.s3.enabled=true` at Spring AOT time so `S3StorageService` (and the
attachment upload controller) are compiled into the binary; runtime `KELTA_S3_*` env vars
still configure endpoint, bucket, and credentials. Runs as non-root `kelta` on port 8080.

**CI / e2e** (`Dockerfile.jvm`): faster JVM build (`maven:3.9-eclipse-temurin-25` →
`eclipse-temurin:25-jre-alpine`, `-XX:MaxRAMPercentage=75`). Used only for CI speed, never
in production.

## Dependencies

- Java 25, Spring Boot 4.0.5
- All `runtime-*` modules (core, events, jsonapi, module-core, module-integration, module-schema)
- PostgreSQL + Flyway, NATS (jnats), Spring Data Redis
- Persistence via Java records + `JdbcTemplate` raw SQL (no Spring Data JPA)
- AWS SDK S3 (optional, for attachment presigned URLs)
