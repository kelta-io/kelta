# Telehealth Slice 8 — Headless Portal Auth

**Status:** SHIPPED (this slice) · **Parent:** `specs/_archive/telehealth/README.md` · **Depends on:** slice 1 (portal identity)

Slice 1's portal login is Thymeleaf-only: the magic-link email points at
`GET /portal/login/verify`, which establishes a kelta-auth session and 302s to
`{ui-base-url}/{tenantSlug}/app` (kelta-ui). A third-party frontend (e.g. a
tenant's own Next.js portal) cannot complete a login — the link always lands in
kelta-ui, and no endpoint returns a token.

This slice adds a JSON API for the same magic-link machinery plus a per-tenant
redirect allowlist, so an external site can run the whole flow: request link →
user clicks email → lands on the external site → site exchanges the token for a
bearer access token and calls the platform as the portal user.

## Endpoints (kelta-auth, permitAll + CSRF-exempt like `/auth/direct-login`)

### `POST /portal/api/login/request`
```json
{ "email": "pat@example.com", "tenant": "telehealth", "redirectUri": "https://portal.example.com/auth/callback" }
```
- `tenant` = slug or UUID; falls back to custom-domain Host resolution when omitted.
- Always **202 `{"status":"ok"}`** whether or not the email matches a portal
  user (enumeration-safe, same as the form flow). Rate limiting (3 outstanding
  links / 15 min / user) unchanged — it lives in `PortalLoginService`.
- `redirectUri` (optional): where the emailed link lands. Must **exactly match**
  an entry in the tenant's allowlist, else **400
  `{"error":"redirect_uri_not_allowed"}`** — validated before any user lookup,
  so the error carries no account signal. Unknown tenant + redirectUri → same
  400 (indistinguishable). Without `redirectUri` the link targets the classic
  Thymeleaf verify page on the current host (behavior unchanged).
- The link is `redirectUri` + `?token=<raw>` (`&token=` when the URI already
  has a query). The landing page must POST that token to `/portal/api/login/verify`.

### `POST /portal/api/login/verify`
```json
{ "token": "<raw token from the link>" }
```
- Consumes the single-use token (same atomic UPDATE as the page flow; PORTAL_LOGIN
  and PORTAL_INVITE tokens both work, so invites can land headlessly too).
- **401 `{"error":"invalid_or_expired_token"}`** on unknown/expired/used tokens.
- **200**:
```json
{ "accessToken": "<RS256 JWT>", "tokenType": "Bearer", "expiresIn": 28800,
  "expiresAt": "2026-07-12T08:00:00Z", "tenantSlug": "telehealth", "userId": "<uuid>" }
```
- The JWT is minted with the authorization server's `JwtEncoder` (same JWKS the
  gateway validates) and mirrors the `KeltaTokenCustomizer` access-token claims:
  `iss`, `sub` = userId, `aud kelta-platform`, `scope "openid profile email"`,
  `email`, `preferred_username`, `tenant_id`, `profile_id`, `profile_name`,
  **`user_type` (PORTAL — the gateway stamps `X-User-Type` from it)**, and
  `auth_method "magic_link"`. TTL 8 h (matches `/auth/direct-login`). No refresh
  token in v1 — the caller re-requests a link when it expires.

## Per-tenant allowlist

Stored under `tenant.settings.portalAuth`:
```json
{ "redirectUris": ["https://portal.example.com/auth/callback"],
  "inviteRedirectUri": "https://portal.example.com/auth/callback" }
```
- Read per-request by kelta-auth (SQL, no cache → no NATS invalidation needed).
- `inviteRedirectUri`: when set, `portal.invite` emails (worker
  `PortalUserService`) link there instead of the kelta-auth verify page — the
  external site completes the invite via the same `/portal/api/login/verify`.
  Visit links (`GET /api/telehealth/visits/{token}`, slice 4) 302 to the same
  callback with `?token=<raw>&appointmentId=<id>` so headless portals land
  authenticated on their own visit page instead of the built-in `/app`.
- Managed via worker **`GET|PUT /api/admin/tenant/portal-auth-settings`**
  (MANAGE_USERS, in-controller gate like the other `/api/admin` endpoints).
  PUT validates: ≤ 10 entries, absolute `https://` URLs (`http://localhost…`
  allowed for dev), no fragments, `inviteRedirectUri` ∈ `redirectUris`.

## Security posture

- Token-in-URL to an attacker-controlled site is the main risk → exact-match
  allowlist, no wildcards, no open redirect. The token is single-use and
  15-minute (login) / 7-day (invite) bound, hashed at rest.
- JSON endpoints reuse the existing enumeration-safe + rate-limited service;
  audit events unchanged (`PORTAL_LINK_REQUESTED`, `PORTAL_LOGIN`).
- Thymeleaf pages and the kelta-ui flow are untouched.
- Security-review PR path: **no auto-merge** (SECURITY.md).

## Out of scope (v2 candidates)

Refresh tokens; per-client rate limits beyond the existing per-user cap;
revocation list for issued portal JWTs (TTL-bounded instead); moving the
allowlist into a cached registry (would then need the NATS broadcast per
Critical Rule 1).
