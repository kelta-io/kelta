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
  round-trip through `KeltaUserDetailsMixin` (its `@JsonCreator` is the static
  `KeltaUserDetails.restore`) — grow both together. It is a `CredentialsContainer`:
  `ProviderManager` erases the password hash after authentication, so `getPassword()` is
  `null` (empty for federated/passwordless principals) on every principal in the session or the
  authorization store — never read it after login. Whether the user signed in with a local
  password is `hasLocalCredential()` (fixed at construction; drives the `auth_method` claim). The mixin never writes `passwordHash` and
  reads stored rows that still carry it without keeping it.
- `KeltaSession` — serializable session data `SessionService` stores in Redis (the servlet
  `HttpSession` is NOT in Redis — `spring.session.*` is inert, see `concerns.md`)
- Both are plain classes (not records, not JPA entities)

### OAuth2 Flow
1. Client redirects to `/oauth2/authorize`
2. `LoginController` renders Thymeleaf login page
3. `KeltaUserDetailsService` loads the user by JDBC, scoped to the session's tenant
   (`LoginTenantResolver`; no tenant → refused as "no tenant context in session").
   Lockout: `AuthenticationEventListener` counts a failed sign-in (`PasswordPolicyService.
   incrementFailedAttempts`) only for a wrong password against a loaded user, resolved by
   that same tenant + email/username — refusals that load no user (unknown, inactive, no
   tenant) are not counted. A lockout that has expired restarts the count at the next miss
   (likewise `TotpService` for `mfa_failed_attempts`).
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
8. Sign-out: the SPA routes through `/logout/saml2/initiate` (SAML SLO, else a
   passthrough) to the OIDC end-session endpoint `/connect/logout` with
   `id_token_hint` and `post_logout_redirect_uri={ui-origin}/{tenant-slug}?logged_out=true`.
   No post-logout URIs are registered on `kelta-platform`; for that client ONLY,
   `PlatformPostLogoutRedirectUriValidator` accepts any path/query on the origin of
   a registered redirect URI or an `https` verified custom domain (absolute
   http(s), no userinfo/fragment). Other clients keep Spring's exact match. A
   rejected URI is a 400 with no redirect — and the session is NOT ended, so an
   over-strict rule here means sign-out silently keeps the user signed in.
   When the browser's session is signed in, Spring AS also requires the ID token's
   `sid` to be the hash of that session's id. It writes `sid` at the code exchange
   by looking the user's sessions up in the `SessionRegistry` bean
   (`AuthorizationServerConfig.sessionRegistry()`) **by principal** — the one read
   back from the JDBC authorization store, a different instance from the session's.
   So `KeltaUserDetails.equals/hashCode` (tenant + user id) is load-bearing: without
   it tokens carry no `sid` and sign-out is a 400 `invalid_token` (`sid`). The
   authorization chain's `ConcurrentSessionFilter` keeps `lastRequest` current, so a
   session that authorizes again gets its own `sid`, not a newer device's; the
   `HttpSessionEventPublisher` bean drops ended sessions from the registry.
   Tests: `OidcLogoutEndpointTest` (redirect rules), `OidcSignOutFlowTest` (form
   login → authorize → token → logout over the JDBC store).

### First-boot platform admin password
`config/BaselineAdminPasswordInitializer` (`ApplicationRunner`) replaces the Flyway baseline's
`admin@kelta.local` BCrypt("password") with `KELTA_BOOTSTRAP_ADMIN_PASSWORD` or a generated
password (printed once in a WARN banner) and forces a change at first sign-in. Exact-hash compare,
one conditional `UPDATE`, only the replica that changed the row logs. Tests:
`BaselineAdminPasswordInitializerTest` (unit) and `BaselineAdminPasswordIntegrationTest`
(Testcontainers; migrates from `../kelta-worker`'s migrations). `*IntegrationTest` classes run under
plain surefire here and skip without Docker.

### Identity Brokering (SSO)
- `config/FederationConfig` — owns every federation bean (OIDC client registrations, SAML
  relying parties, the SAML chain, the SLO resolver). **All registered unconditionally**;
  availability is decided at run time, never with `@ConditionalOnBean`/`@ConditionalOnProperty`
  (`EncryptionService` is auto-configured, so a `@ConditionalOnBean` on it is always false, and
  this service is a native image). `FederationWiringTest` reproduces the real auto-config order.
- OIDC needs `EncryptionService` (`KELTA_ENCRYPTION_KEY`) to decrypt client secrets. Without it
  `clientRegistrationRepository` refuses every lookup, the login page shows no OIDC buttons and
  `oauth2Login` is not enabled.
- SAML stores no secrets and does not need the key. It needs OpenSAML to initialize
  (`federation/OpenSamlStatus`, probed once at startup). If that fails, SAML is disabled with an
  ERROR log and the service still starts. Native hints: `aot/OpenSamlRuntimeHints`.
- `/logout/saml2/initiate` (`SamlLogoutController`) always exists and is `permitAll`: the UI sends
  every sign-out there. Without a SAML session it redirects to the same-origin
  `post_logout_redirect_uri` (else `/login?logout`).
- `DynamicClientRegistrationRepository` — loads OIDC provider configs from worker at runtime
  (`DynamicRelyingPartyRegistrationRepository` is the SAML mirror). Both cache hits for 5 min and
  remember an id the worker reported as unknown for 30 s in a bounded per-pod
  `UnknownRegistrationCache` (10k ids, ids over 256 chars never kept). Only an *answered* miss is
  remembered: they call `WorkerClient.lookupActive{Oidc,Saml}Providers`, whose `Optional.empty()`
  means the worker could not answer, so an outage is never cached as "no such provider". Listing
  a tenant's providers (login page) forgets misses for the ids it found; `evictAll()` clears both.
- Rate limiting: `filter/PortalPublicRateLimitFilter` (order -150, before Spring Security) also
  budgets the unauthenticated federation endpoints — `/oauth2/authorization/`,
  `/login/oauth2/code/`, `/saml2/authenticate/`, `/saml2/metadata`,
  `/saml2/service-provider-metadata/`, `/login/saml2/sso/`, `/logout/saml2/slo/` at 60 per IP per 60 s, each prefix its own Redis bucket
  (`kelta.auth.rate-limit.federation-paths`, separate from the portal `ip-paths` so overriding one
  keeps the other). `GET /login` with a non-blank `tenant` or `idp_hint` has its own bucket
  (`kelta.auth.rate-limit.login-page-limit`, 60; 0 disables); the password form POST to `/login`
  is never counted. Paths match on the decoded servlet path, so encoded variants share a bucket.
  Client IP and fail-open semantics are the portal limiter's (`kelta.security.trusted-proxies`).
  The defaults live in the filter and in `application.yml`; `PortalPublicRateLimitFilterTest`
  checks they agree.
- Calls to the IdP during login (token exchange, user info, JWKS) go over `federation/OidcLoginHttp`:
  every request is checked with the `OutboundUrlGuard` right before it is sent, redirects are never
  followed, connect/response waits are bounded. `FederationConfig` registers the token response
  client and user services (set on `oauth2Login` in `AuthorizationServerConfig`) and the
  `JwtDecoderFactory<ClientRegistration>` bean Spring Security picks up **by type** — with more
  than one it silently falls back to its own default client, so keep exactly one
  (`FederationWiringTest` checks). `DynamicClientRegistrationRepository` also checks those three
  endpoints when it builds a registration; a malformed or non-public one means no registration.
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
| Fetching a tenant-supplied URL (runtime-core `OutboundHttpClient` + the `OutboundUrlGuard` bean from `config/OutboundConfig`) | `service/OidcDiscoveryService.java` |
| Spring Security calling a tenant's IdP (token, user info, JWKS) over a guarded, redirect-free, timeout-bounded request factory | `federation/OidcLoginHttp.java` (beans in `config/FederationConfig`) |
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
