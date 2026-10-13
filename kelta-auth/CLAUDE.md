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
  session/         ← HTTP sessions in Redis: HttpSessionConfig, value serializer, cookie, index, OIDC SessionRegistry,
                     UserSessionRevoker (sign-out elsewhere after a password change)
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
- `KeltaSession` — the `kelta_session` SSO cookie's data, stored as JSON in Redis by
  `SessionService` (separate from the HTTP session — see **HTTP sessions** below)
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
   (`session/HttpSessionConfig.sessionRegistry()`, a `TenantScopedSessionRegistry` over
   the Redis session store) **by principal** — the one read back from the JDBC
   authorization store, a different instance from the session's. Sessions are indexed
   and looked up by tenant + user id (`SessionPrincipalKeys`, the same identity as
   `KeltaUserDetails.equals/hashCode`), never by email, which repeats across tenants.
   Spring Session updates a session's last access time on every request, so a session
   that authorizes again gets its own `sid`, not a newer device's. Ending the session
   (sign-out) deletes it from Redis, and with it from the index.
   Tests: `OidcLogoutEndpointTest` (redirect rules), `OidcSignOutFlowTest` (form
   login → authorize → token → logout over the JDBC store and the session filter),
   `SharedHttpSessionIntegrationTest` (the same across pods and a restart, real Redis).

### HTTP sessions
Stored in Redis by Spring Session (Boot's `spring-boot-starter-session-data-redis`; the Boot 4
auto-configuration lives in that module), so every replica sees every session and a restart or
rollout signs nobody out. Settings: `spring.session.*` in `application.yml` (indexed repository —
a literal, it gates auto-configuration at native build time — namespace `kelta:auth:session`,
30-minute idle timeout before sign-in) and `kelta.auth.session.*` (8-hour idle timeout once signed
in, cookie name `kelta_auth_session`, `SameSite`, `cookie-secure`). `session/`:
- `SessionAttributeSerializer` — Java serialization of each session value, read through a
  JDK/Spring/platform class allowlist. A value that cannot be written is left out, one that cannot
  be read (classes changed in a release) reads as absent — never a failed request.
- `SessionCookieSerializer` — `HttpOnly`, path `/`, browser-session lifetime, no `Domain`;
  `Secure` when the browser used https, else not, with `SameSite=None` written as `Lax`
  (browsers reject `None` without `Secure`). `CookieDomainResolver.secureForRequest` decides it for
  this cookie and the `kelta_session` SSO cookie: `kelta.auth.session.cookie-secure`
  (`KELTA_AUTH_SESSION_COOKIE_SECURE`) `auto` = first `X-Forwarded-Proto` (or `Forwarded: proto=`)
  value, else `request.isSecure()` — production behind the TLS-terminating ingress is `Secure`,
  `http://*.localhost` and a plain-http LAN install are not; `always` / `never` override (`always`
  behind a proxy that terminates TLS without sending the header). The host name plays no part.
  `SameSite=None` is what lets a SAML IdP's cross-site POST carry the session that started the
  sign-in, so SP-initiated SAML needs https.
- `SessionPrincipalIndexResolver` / `TenantScopedSessionRegistry` — the per-user index and the
  OIDC registry over it (see Sign-out above).
- `SignedInSessionTimeoutFilter` — the long timeout only once the session holds a signed-in user.
- `UserSessionRevoker` — `signOutElsewhere(userId, keepSessionId)` after a successful password
  change (`PasswordController` change and email-link reset, `ForcePasswordChangeController`) ends
  every other session of the user (index key tenant + user id; the session the change was made in
  is kept) and deletes the user's `oauth2_authorization` rows, so earlier refresh tokens answer
  `invalid_grant`. `signOutEverywhere(tenantId, userId, loginNames)` does the same with nothing
  kept, for kelta-worker's internal call (below); it ignores a user id of another tenant and takes
  the login names from the caller only when the user row is gone. `oauth2_authorization` rows carry
  only the login name as `principal_name` (looked up via `lower(principal_name)`, indexed by worker
  V210), so it reads candidates back through the authorization service and deletes only rows whose
  stored `KeltaUserDetails` has the same tenant + id. Never fails the request (the change is
  already made); failures log at ERROR. A new password-changing path must call it. Tests:
  `PasswordChangeSignOutFlowTest`, `SharedHttpSessionIntegrationTest` (Redis), `UserSessionRevokerTest`.
- **Native image**: every class written for a session value needs a serialization hint
  (`AuthRuntimeHints.registerHttpSessionSerialization`, on top of Spring Session's own). A new
  session attribute type gets a sample in the test `SessionAttributeSamples`;
  `SessionAttributeHintsTest` fails until it is hinted.

### `oauth2_authorization` retention
Spring Authorization Server never deletes a row. `service/AuthorizationRetentionSweep`
(`@Scheduled`, `config/SchedulingConfig` turns scheduling on) deletes rows that hold at least one
token and whose every token (code, access, OIDC ID, refresh, device, user code) expired before
`now - grace`; a token without an expiry keeps the row, and a row with no token (pending consent)
is kept. Settings `kelta.auth.authorization-retention.*` (`enabled`, `interval` 1h, `grace` 1d,
`batch-size` 500, `max-batches` 100; first run 5 min after start). Every replica runs it — there is
no cluster lock in this service (nor ShedLock anywhere in the repo): each statement is
`DELETE … WHERE id IN (SELECT … LIMIT ? FOR UPDATE SKIP LOCKED)`, so concurrent sweeps take
disjoint rows. The select leads with `GREATEST(<six expiry columns>) < cutoff`, which matches the
expression index `idx_oauth2_authorization_latest_expiry` (worker V210) — keep the two identical.
The table has no RLS. Tests: `AuthorizationRetentionSweepTest`,
`AuthorizationRetentionIntegrationTest` (real migrated schema, concurrent sweeps, index use).

### Password paths are scoped to one user
An email address can belong to users in several tenants, so every password write names one user
by tenant + user id:
- Forced change at sign-in: `KeltaUserDetailsService` leaves the user it loaded in the request
  attribute `LOADED_USER_ATTRIBUTE`; when form login fails with `CredentialsExpiredException`
  (raised after the password check), `config/LoginFailureHandler` calls
  `ForcePasswordChangeController.begin`, which stores that user's id + tenant id (and the email,
  for display) in the session and changes the session id. `/change-password` re-reads the account
  by id + tenant, requires `force_change_on_login` and the current password, and updates only that
  credential (`WHERE user_id = ? AND force_change_on_login = true AND <user is in the tenant>`).
  A session without the id + tenant goes back to `/login`.
- Email-link reset (`service/PasswordResetService`): a request issues one token per active account
  with the address, stored on that user only and mailed with that tenant's name; a reset accepts a
  token only when it matches exactly one credential and clears it in the same
  `UPDATE … WHERE user_id = ? AND reset_token = ?` (single use).
- `/auth/password/change` resolves the signed-in principal by tenant + id.
Test on Postgres: `BaselineAdminPasswordIntegrationTest.sameEmailInAnotherTenantIsUntouched`.

Every way to set a password — `/auth/password/change`, the email-link reset and the forced
`/change-password` — checks the new one against the tenant's policy (`PasswordPolicyService`:
length, complexity, dictionary, personal data) and history, and records it in the history.

### Forgot password (signed out)
`service/PasswordResetService` does the work for two front doors, both `permitAll`:
- Pages (`controller/ForgotPasswordController`, templates `forgot-password.html`,
  `reset-password.html`; the login page links to it): `GET/POST /forgot-password`,
  `GET /reset-password?token=…` (the emailed link) and `POST /reset-password`. Form posts with the
  session's CSRF token like the login form. Every handler takes the `HttpSession` so it exists
  before Thymeleaf writes the CSRF field (the link is usually opened with no session, and a session
  cannot be created once the response is committed). After a reset a browser with a saved
  authorization request goes to `/login?passwordReset` (sign-in resumes the app); otherwise the page
  links to the tenant's app (`ui-base-url/<slug>/app`).
- JSON for an external frontend (`PasswordController`): `POST /auth/password/reset-request`
  `{email}` and `POST /auth/password/reset` `{token, newPassword}` — **CSRF-exempt**; the request
  answers the same for any address and the reset needs the emailed token.
Rules:
- Enumeration-safe: the request answers the same whether or not the address has an account; the
  email goes out on a separate (virtual) thread and a custom-domain lookup runs either way, so
  timing does not tell either.
- The emailed link opens on the request's host only when that host serves sign-in for the
  account: `localhost`, `*.localhost`, `kelta.io`, `*.kelta.io`, the host of `issuer-uri` or
  `ui-base-url`, or a verified custom domain of the account's own tenant (`AuthDomainResolver`).
  Otherwise the configured issuer, else no email. Never build the link from the `Host` header
  unchecked — a forged `Host` would mail the user a working link to the requester's site.
- The pages set `<meta name="referrer" content="same-origin">` so the token in the URL never
  leaves as a referrer. Never `no-referrer` on a page with a form: the browser then posts
  `Origin: null`, which the CORS filter refuses ("Invalid CORS request", 403). MockMvc sends no
  `Origin` unless a test adds one (`PasswordResetFlowTest.formPostsCarryTheBrowsersOrigin`).
- A password the policy refuses leaves the token usable; a reset clears the force-change flag and
  any lockout and ends the user's other sessions (`UserSessionRevoker`).
- Rate limited per IP (`kelta.auth.rate-limit.password-reset-paths`, below).
- Worker invites (`UserInviteService`) write the same `reset_token` column (7 days,
  `force_change_on_login = true`), so an invite token also works on `/reset-password`.
Tests: `PasswordResetFlowTest` (real chains, session filter, rate limit, real policy and
templates), `PasswordResetServiceTest`, `BaselineAdminPasswordIntegrationTest` (Postgres, history),
e2e `tests/auth/forgot-password.spec.ts` (Mailpit).

### Internal endpoint (kelta-worker → kelta-auth)
`POST /internal/tenants/{tenantId}/users/{userId}/revoke-sessions`
(`controller/InternalSessionRevocationController`), optional body `{"loginNames": [...]}`: calls
`UserSessionRevoker.signOutEverywhere`. kelta-worker's `AuthSessionRevocationClient` calls it after
an admin password reset, deactivation or removal. Authenticated only by `X-Internal-Token` =
`kelta.internal.token` (`KELTA_INTERNAL_TOKEN`, same value as the worker), compared in constant
time; no token configured ⇒ every call 403. The ingress sends every path of the auth host here,
so that check is the only gate: `/internal/**` is `permitAll` and CSRF-exempt in
`AuthorizationServerConfig`, path variables only (no request record to hint for the native image).
Tests: `InternalSessionRevocationControllerTest`, `InternalSessionRevocationFlowTest` (real chain).

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
  is never counted. The signed-out password reset has its own list,
  `kelta.auth.rate-limit.password-reset-paths` (`/auth/password/reset-request=5`,
  `/auth/password/reset=10`, `/forgot-password=10`, `/reset-password=20`).
  Paths match on the decoded servlet path, so encoded variants share a bucket.
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

Error responses (`controller/`):
- `HtmlErrorPageResolver` (a `HandlerExceptionResolver`, ahead of MVC's) — an exception from a page
  handler (`@Controller` method that is not `@ResponseBody`/`ResponseEntity`) renders
  `templates/error.html` with the exception's status. JSON endpoints fall through to runtime-core's
  `GlobalExceptionHandler` (JSON:API errors), unchanged. Page controllers must not rely on local
  `@ExceptionHandler` methods — this resolver answers first.
- `AuthErrorController` (`/error`, replaces Boot's `BasicErrorController`;
  `spring.web.error.whitelabel.enabled: false`) — errors outside a handler. HTML when the failed
  response was already `text/html` or `Accept` puts `text/html` ahead of JSON; otherwise Boot's
  JSON shape (`timestamp`, `status`, `error`, `path`) plus `requestId`, always as
  `application/json`.
- `ErrorPages` — the rule and the page model: strings only (`status`, `title`, `message`,
  `requestId`), never the exception message or a stack trace; the request id is in the log line.
  Tests: `AuthErrorHandlingTest`.

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

Unit and flow tests run on H2, which binds a `java.time.Instant` JDBC parameter that the PostgreSQL
driver rejects ("Can't infer the SQL type") — bind `java.sql.Timestamp`, and cover new SQL with a
Testcontainers test on the migrated schema (`BaselineAdminPasswordIntegrationTest`,
`AuthorizationRetentionIntegrationTest`).

## Test Fixtures

Use `TestFixtures.java` in `src/test/java/io/kelta/auth/` for pre-built `KeltaUserDetails` and `KeltaSession` instances. Prefer these over hand-constructing models so tests stay terse and survive constructor changes.
