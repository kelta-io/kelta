# kelta-gateway

Spring Cloud Gateway — API entry point for all Kelta platform requests. Handles JWT authentication, tenant resolution, Cerbos authorization, rate limiting, and dynamic routing to backend services.

## Package Layout

```
io.kelta.gateway/
  auth/            ← JWT validation, PAT auth, principal extraction, public path matching
  authz/           ← Route authorization filter + Cerbos integration
    cerbos/        ← CerbosAuthorizationService, CerbosConfig, CerbosPrincipalBuilder
  cache/           ← GatewayCacheManager
  config/          ← Spring Security, Redis, NATS subscriptions, route init, WebSocket, service accounts
  error/           ← Exception classes + GlobalErrorHandler
  filter/          ← Gateway filters (tenant, security headers, rate limit, geo enrichment, logging, caching)
  geo/             ← IP geolocation: GeoLite2 mmdb download/hot-swap, lookup service, ClientIpResolver
  health/          ← Health indicators (NATS, Redis, Worker)
  listener/        ← NATS event listeners (config events, Cerbos cache invalidation, realtime bridge)
  metrics/         ← GatewayMetrics (Micrometer)
  ratelimit/       ← Redis-backed rate limiter
  route/           ← DynamicRouteLocator, RouteRegistry, RouteDefinition
  service/         ← RouteConfigService
  websocket/       ← Real-time subscriptions (WebSocket handler, SubscriptionManager)
```

## Key Patterns

### Reactive Filters
All filters use reactive `Mono` / `Flux` — never block in the filter chain:
```java
public class MyFilter implements GlobalFilter, Ordered {
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        // Extract from exchange, transform, chain.filter(exchange)
    }
}
```
**Reference**: `filter/TenantResolutionFilter.java`, `filter/SecurityHeadersFilter.java`

### Per-Request State
Use `ServerWebExchange` attributes — never instance fields:
```java
exchange.getAttributes().put("tenantId", tenantId);
```

### Dynamic Routing
Routes are loaded from worker at startup and updated via NATS events:
- `RouteRegistry` — in-memory route store
- `DynamicRouteLocator` — bridges RouteRegistry with Spring Cloud Gateway
- `RouteRefresher` — the only place that publishes `RefreshRoutesEvent`; `synchronized` so concurrent refreshes cannot lose the newer registry snapshot (`concerns.md` → Resolved). Mutate `RouteRegistry` first, then `refresh()`
- `listener/ConfigEventListener` — subscribes to `kelta.config.collection.changed.*` and updates routes
- `listener/CerbosCacheInvalidationListener` — invalidates the principal authz cache on policy changes

### Error Handling
| Exception | When |
|-----------|------|
| `GatewayAuthenticationException` | JWT validation fails |
| `GatewayAuthorizationException` | Cerbos denies access |
| `RateLimitExceededException` | Rate limit hit |
| `RouteNotFoundException` | No matching route |

All handled by `error/GlobalErrorHandler`.

### Authorization Flow
1. `auth/` filters validate the JWT / PAT and populate a `GatewayPrincipal` on the exchange
2. `authz/` filter consults Cerbos via `authz/cerbos/CerbosAuthorizationService` for the `(principal, resource, action)` tuple
3. Decisions are cached; the cache is invalidated by `listener/CerbosCacheInvalidationListener` when policies change

## When Adding a New Filter

1. Implement `GlobalFilter` + `Ordered` in `filter/`
2. Return `Mono<Void>` — never block
3. Use `ServerWebExchange` attributes for per-request state
4. Register the filter as a `@Component` — Spring Cloud Gateway picks it up automatically
5. Add a test in `src/test/java/io/kelta/gateway/filter/`

**Reference**: `filter/TenantResolutionFilter.java`

## Reference Implementations

| Pattern | File |
|---------|------|
| Reactive filter | `filter/TenantResolutionFilter.java` |
| Request-URI rewrite (must preserve raw encoding) | `filter/QueryBracketEncodingFilter.java` |
| Principal extraction | `auth/GatewayPrincipal.java` |
| Cerbos integration | `authz/cerbos/CerbosAuthorizationService.java` |
| Route data model | `route/RouteDefinition.java` |
| Dynamic routing | `route/DynamicRouteLocator.java` + `route/RouteRegistry.java` |
| NATS event listener | `listener/ConfigEventListener.java` |
| Error handler | `error/GlobalErrorHandler.java` |
| Client IP resolution (trust-aware, shared) | `geo/ClientIpResolver.java` |
| Self-refreshing local dataset (download + hot-swap) | `geo/GeoIpDatabaseManager.java` |

## Running Tests

```bash
mvn test -f kelta-gateway/pom.xml                                                    # All tests
mvn test -f kelta-gateway/pom.xml -Dtest=DynamicRouteLocatorTest                     # Single class
mvn test -f kelta-gateway/pom.xml -Dtest=DynamicRouteLocatorTest#buildsRouteFromDefinition  # Single method
mvn test -f kelta-gateway/pom.xml -Dtest="*Filter*"                                  # Pattern match
```

## Test Fixtures

Use `TestFixtures.java` in `src/test/java/io/kelta/gateway/` for pre-built `RouteDefinition`, `RateLimitConfig`, and `GatewayPrincipal` instances. Prefer these over hand-constructing objects so tests stay terse and survive constructor changes.
