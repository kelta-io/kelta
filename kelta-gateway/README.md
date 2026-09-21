# Kelta API Gateway

Spring Cloud Gateway service that serves as the main ingress point for the Kelta platform. Provides centralized authentication, multi-tenant routing, JSON:API processing, and rate limiting.

## Architecture

```
Client Request
  │
  ├─ CustomDomainFilter (-310)            Map custom domain → tenant
  ├─ TenantSlugExtractionFilter (-300)    Strip tenant slug from URL
  ├─ TenantResolutionFilter (-200)        Resolve slug → tenant ID
  ├─ IpRateLimitFilter (-150)             Per-IP rate limiting
  ├─ JwtAuthenticationFilter (-100)       Validate JWT, extract principal
  ├─ PatAuthenticationFilter (-99)        Validate PAT (klt_) as JWT alternative
  ├─ RateLimitFilter / UserIdentityResolutionFilter (-50)   Per-tenant rate limit; user identity
  ├─ RouteAuthorizationFilter (0)         Cerbos object-level permission check
  ├─ Request forwarded to worker via DynamicRouteLocator (RouteRegistry)
  ├─ HeaderTransformationFilter (50)      Inject X-Tenant-* headers for worker
  ├─ SecurityHeadersFilter (100)          Add response security headers
  └─ SecurityAuditFilter (200)            Record final response status
```

Order values are the live `getOrder()` returns from each filter (lower runs first).
Cross-cutting filters not on the main path: `ObservabilityContextFilter (-90)`,
`HttpBodyCaptureFilter (-80)`, `SystemCollectionResponseCacheFilter (-10)`,
`RequestLoggingFilter (Integer.MAX_VALUE)`. JSON:API `?include=` resolution is done by
`IncludeResolver` (`jsonapi` package) during response processing — it is **not** a numbered
filter. Response field-level security (stripping HIDDEN fields) is enforced in the
**worker** (`CerbosFieldSecurityAdvice`), not the gateway.

## Key Packages

| Package | Description |
|---------|-------------|
| `auth` | JWT validation, `GatewayPrincipal` extraction, public path matching |
| `authz` | Route/field authorization filters, permission resolution from worker |
| `config` | Spring configuration, bootstrap, NATS/Redis/Security beans |
| `filter` | Tenant resolution, security headers, request logging |
| `route` | `DynamicRouteLocator`, `RouteRegistry` (in-memory, thread-safe) |
| `cache` | `GatewayCacheManager` -- Caffeine-backed tenant slug and governor limit caches |
| `ratelimit` | `RedisRateLimiter` (sliding window) |
| `jsonapi` | `IncludeResolver` -- fetches related resources from Redis cache with backend fallback |
| `listener` | NATS consumers for collection and worker assignment changes |
| `health` | Health indicators for Redis, NATS, and worker service |

## Configuration

Key properties from `application.yml`:

```yaml
nats.url: ${NATS_URL:nats://localhost:4222}
spring.data.redis:
  host: ${REDIS_HOST:localhost}
  port: ${REDIS_PORT:6379}
spring.security.oauth2.resourceserver.jwt:
  issuer-uri: ${OIDC_ISSUER_URI:http://localhost:9000/realms/kelta}

kelta.gateway:
  worker-service-url: ${WORKER_SERVICE_URL:http://emf-worker:80}
  tenant-slug:
    enabled: ${TENANT_SLUG_ENABLED:true}
    cache-refresh-ms: 60000
  security:
    permissions-enabled: ${PERMISSIONS_ENABLED:true}
    permissions-cache-ttl-minutes: ${PERMISSIONS_CACHE_TTL:5}
    public-paths: /api/ui-pages,/api/ui-menus,/api/oidc-providers,/api/tenants
  nats.subjects:
    collection-changed: kelta.config.collection.changed
    worker-assignment-changed: kelta.worker.assignment.changed
    record-changed: kelta.record.changed
```

## How It Works

**Startup:**
1. `RouteInitializer` primes the `GatewayCacheManager` tenant slug cache by calling the worker
2. Fetches initial routes and governor limits via `POST {worker}/internal/bootstrap`
3. Populates `RouteRegistry` and rebuilds the gateway route cache via `RouteRefresher` (the one serialized publisher of `RefreshRoutesEvent`)

**Runtime:**
- Routes are **not** hardcoded -- they are discovered from the worker at startup and updated dynamically via NATS events without restart
- JSON:API resources are cached in Redis with a 10-minute TTL
- Rate limits are enforced per-tenant using Redis sliding windows backed by governor limit configuration

**NATS subjects consumed:**
- `kelta.config.collection.changed` -- updates routes on collection create/update/delete
- `kelta.worker.assignment.changed` -- updates routes on worker assignment changes
- `kelta.record.changed` -- record change events

## Building

```bash
# Build runtime dependencies first
mvn clean install -DskipTests -f kelta-platform/pom.xml \
  -pl runtime/runtime-core,runtime/runtime-events,runtime/runtime-jsonapi,runtime/runtime-module-core,runtime/runtime-module-integration,runtime/runtime-module-schema \
  -am -B

# Build and test gateway
mvn verify -f kelta-gateway/pom.xml -B
```

## Running Locally

Requires Redis, NATS, Keycloak, and the worker service. Start infrastructure via Docker Compose from the repo root:

```bash
docker-compose up -d
mvn spring-boot:run -f kelta-gateway/pom.xml
```

The gateway starts on port **8080**.

## Testing

- **Unit tests** -- JUnit 5 + Mockito, test classes in isolation
- **Integration tests** -- Testcontainers for NATS/Redis, MockWebServer for HTTP
- **Property-based tests** -- JUnit QuickCheck for universal property validation

```bash
mvn verify -f kelta-gateway/pom.xml -B
```

## Health Checks

| Endpoint | Description |
|----------|-------------|
| `GET /actuator/health` | Overall health |
| `GET /actuator/health/redis` | Redis connectivity |
| `GET /actuator/health/nats` | NATS connectivity |
| `GET /actuator/metrics` | Micrometer metrics |

## Docker

**Production** (`Dockerfile`): multi-stage **GraalVM CE 25 native-image** build
(`ghcr.io/graalvm/native-image-community:25-ol9` builder, `mvn -Pnative native:compile`)
→ `debian:12-slim` runtime — **no JRE**, self-contained binary, ~50 ms startup. Runs as
non-root `kelta` on port 8080; OTLP is exported directly by Spring Boot's native
OpenTelemetry starter (no Java agent).

**CI / e2e** (`Dockerfile.jvm`): faster JVM build (`maven:3.9-eclipse-temurin-25` →
`eclipse-temurin:25-jre-alpine`, `-XX:MaxRAMPercentage=75`). Used only for CI speed, never
in production.

## Dependencies

- Java 25, Spring Boot 4.0.5, Spring Cloud 2025.1.1
- `runtime-events`, `runtime-jsonapi` (Kelta platform modules)
- Spring Cloud Gateway, Spring WebFlux (reactive)
- Spring Security OAuth2 Resource Server
- Spring Data Redis (reactive), NATS (jnats)
