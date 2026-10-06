# kelta-auth

Internal OIDC provider for the Kelta platform. OAuth2 Authorization Server with identity brokering, MFA, and session management.

## Package Layout

```
io.kelta.auth/
  config/          ← AuthorizationServerConfig, AuthProperties, CORS, redirect URI validation
  controller/      ← Thymeleaf controllers (login, consent, MFA, password) + API controllers
  federation/      ← External IdP brokering (dynamic client registration, user mapping)
  model/           ← KeltaUserDetails, KeltaSession, KeltaUserDetailsMixin
  service/         ← Business logic (token customizer, user details, TOTP, session, worker client, etc.)
```

## Key Patterns

### Models
- `KeltaUserDetails` — implements Spring `UserDetails`; 11-param constructor (adds `userType`
  INTERNAL|PORTAL) with a 10-param overload defaulting to INTERNAL. Serialized principals
  round-trip through `KeltaUserDetailsMixin` — grow both together.
- `KeltaSession` — serializable session data stored in Redis
- Both are plain classes (not records, not JPA entities)

### OAuth2 Flow
1. Client redirects to `/oauth2/authorize`
2. `LoginController` renders Thymeleaf login page
3. `KeltaUserDetailsService` loads user from worker via `WorkerClient`
4. `KeltaTokenCustomizer` adds custom claims (tenantId, profileId, groups)
5. Token issued and returned to client
6. Silent refresh: the SPA (`kelta-platform`, public client, PKCE) refreshes with
   `grant_type=refresh_token` + `client_id` only. Spring AS has no built-in
   client-auth path for that request, so
   `PublicClientRefreshTokenAuthenticationConverter`/`-Provider` (in `config/`)
   authenticate it as method NONE; the refresh token is the credential and is
   rotated on every use (`reuseRefreshTokens(false)`). Confidential clients that
   omit their secret are rejected, never silently authenticated. Spring AS's
   default `OAuth2RefreshTokenGenerator` returns `null` for a public client on
   `authorization_code`, so `AuthorizationServerConfig.tokenGenerator` uses
   `PublicClientRefreshTokenGenerator`: any client registered with the
   `refresh_token` grant gets one (kelta-cli is not, so it still gets none).
   Platform client lifetimes live in `ConnectedAppRegistrar.platformTokenSettings()`
   and are reconciled onto the existing DB row at startup.
   A login form that outlived its session (CSRF failure on POST `/login`) is
   resumed by `ExpiredLoginFormHandler` from the form's hidden `authorize_url`.
7. CLI login: `kelta-cli` (public client, PKCE, **no refresh grant**) uses an
   RFC 8252 loopback redirect — `http://127.0.0.1:<any port>/<tenant-slug>/auth/callback`
   (or `[::1]`) — accepted port-agnostically by `PlatformRedirectUriValidator`
   for this client ONLY (loopback IP literals, never `localhost`; path must match
   `/{slug}/auth/callback`; no userinfo/query/fragment). **The tenant slug in the
   path is load-bearing, not cosmetic**: `TenantContextFilter` derives the login's
   tenant from the `redirect_uri` path for every client, so a slug-less
   `/callback` passes OAuth validation and then fails authentication with
   "no tenant context in session" (`KeltaUserDetailsService`). The 15-min access
   token exists solely for the CLI to mint a PAT (`POST /api/me/tokens`) and is
   then discarded. Registered by `ConnectedAppRegistrar.registerCliClient()`.
   Spec: `specs/kelta-cli/2-browser-login.md`.

### First-boot platform admin password
`config/BaselineAdminPasswordInitializer` (`ApplicationRunner`) replaces the Flyway baseline's
`admin@kelta.local` BCrypt("password") with `KELTA_BOOTSTRAP_ADMIN_PASSWORD` or a generated
password (printed once in a WARN banner) and forces a change at first sign-in. Exact-hash compare,
one conditional `UPDATE`, only the replica that changed the row logs. Tests:
`BaselineAdminPasswordInitializerTest` (unit) and `BaselineAdminPasswordIntegrationTest`
(Testcontainers; migrates from `../kelta-worker`'s migrations). `*IntegrationTest` classes run under
plain surefire here and skip without Docker.

### Identity Brokering (SSO)
- `DynamicClientRegistrationRepository` — loads OIDC provider configs from worker at runtime
- `FederatedLoginSuccessHandler` — handles successful external IdP login
- `FederatedUserMapper` — maps external identity to Kelta user

### MFA
- `TotpService` — TOTP generation and verification
- `MfaController` — enrollment and challenge pages
- Session attributes: `SESSION_MFA_USER_ID`, `SESSION_MFA_PENDING`, `SESSION_MFA_SETUP_REQUIRED`

### Error Handling
Standard Spring Security exceptions (`AuthenticationException` hierarchy). No custom exception classes in this service.

## When Adding a New Auth Flow

1. Add a `@Controller` in `controller/` for the page/form handling
2. Create a Thymeleaf template in `src/main/resources/templates/`
3. Add business logic to an existing or new service in `service/`
4. Wire into `AuthorizationServerConfig` or `SecurityConfig` if it changes the security filter chain
5. Add a test in `src/test/java/io/kelta/auth/`

**Reference**: `MfaController.java` + `TotpService.java`

## Reference Implementations

| Pattern | File |
|---------|------|
| Thymeleaf controller | `controller/LoginController.java` |
| API controller | `controller/SessionController.java` |
| OAuth2 customization | `service/KeltaTokenCustomizer.java` |
| User loading | `service/KeltaUserDetailsService.java` |
| External IdP | `federation/DynamicClientRegistrationRepository.java` |
| MFA | `service/TotpService.java` + `controller/MfaController.java` |
| Passwordless magic-link (portal users) | `controller/PortalLoginController.java` + `service/PortalLoginService.java` |
| Worker HTTP client | `service/WorkerClient.java` |

## Running Tests

```bash
mvn test -f kelta-auth/pom.xml                                      # All tests
mvn test -f kelta-auth/pom.xml -Dtest=LoginControllerTest           # Single class
mvn test -f kelta-auth/pom.xml -Dtest=LoginControllerTest#shouldRenderLoginPage  # Single method
mvn test -f kelta-auth/pom.xml -Dtest="*Mfa*"                       # Pattern match
```

## Test Fixtures

Use `TestFixtures.java` in `src/test/java/io/kelta/auth/` for pre-built `KeltaUserDetails` and `KeltaSession` instances. Prefer these over hand-constructing models so tests stay terse and survive constructor changes.
