# [Slice 1] — Portal Identity & Record Access

> Child spec of [Telehealth parent](./README.md) — **SHIPPED 2026-07-10** (V167); sections
> below updated to as-built. Conforms to parent §Key architecture decisions, §Security.
> **Security-typed PR — never auto-merged.** Backend + auth; small admin-UI surface.

## 1. Goal & scope

Delivers the **external user** foundation every other slice depends on:

- `user_type ∈ {INTERNAL, PORTAL}` on `platform_user`, surfaced as a JWT claim and forwarded
  identity attribute.
- A seeded **Portal User** profile (API_ACCESS only; no collection CRUD, no setup
  permissions) provisioned for new tenants and backfilled for existing ones.
- **Passwordless magic-link login** for portal users (email one-time link/OTP), issued by
  kelta-auth; portal invites through the existing invite pipeline.
- A reusable **participant-share hook utility**: system collections can declare "creating a
  participant row grants the named user a `record_share` on the parent record" — the
  mechanism later slices use to scope patients to their own conversations/appointments.
- `maxPortalUsers` governor key, enforced at portal-user create/invite (staff `gov_users`
  stays tracked-only; changing that is out of scope).

Does NOT deliver: any chat/scheduling/video surface; self-service open registration
(portal users exist by invite, booking flow, or tenant IdP federation); SMS OTP
(email only — `SmsProvider` SPI hookup is deferred per parent).

## 2. UI samples

Admin Users page: a *Type* column (Internal/Portal badge) + "Invite portal user" action
(email + optional name; profile forced to Portal User). Portal login page (served on the
tenant slug/custom domain): email field → "Check your email" confirmation → link opens an
authenticated session; uniform response whether or not the email exists.

## 3. Data & API contracts

As built — the login flow landed as kelta-auth Thymeleaf pages (the `MfaController` idiom),
not JSON endpoints; the tenant is resolved like `/login` (session → `?tenant=` → custom
domain):

```
GET  /portal/login                    email form (permitAll; tenant-aware)
POST /portal/login/request  email=…   → always the same "check your email" view (enumeration-safe)
GET  /portal/login/verify?token=…     → consumes token, authenticates the session, redirects to
                                        {ui-base-url}/{tenantSlug}/app (OIDC then completes silently)
POST /api/admin/users/portal-invite  {email, firstName?, lastName?}
                                      → 201 {userId, status:"INVITED"} (user created ACTIVE, passwordless)
                                      → 200 {userId, status:"REINVITED"} (existing portal email → fresh link)
                                      → 409 email belongs to an INTERNAL user · 429 governor cap
```

- JWT gains `user_type` claim (`KeltaTokenCustomizer`, id + access tokens); gateway re-stamps
  it as `X-User-Type` (`HeaderTransformationFilter`) and strips client-supplied values
  chain-head (`IdentityHeaderStripFilter`). Pre-claim tokens read as INTERNAL.
- Magic-link tokens: 256-bit `SecureRandom`, stored **only as SHA-256 hashes** in
  `portal_login_token` (V167, RLS) with `purpose ∈ PORTAL_LOGIN (15 min) | PORTAL_INVITE
  (7 days — the invite email's first-access link)`; single-use via atomic
  `UPDATE … SET consumed_at … RETURNING`. Request issuance rate-limited to 3 outstanding
  PORTAL_LOGIN tokens per user per window (per-IP limiting stays a gateway concern).
- Portal users get **no `user_credential` row** — `KeltaUserDetailsService` inner-joins it,
  so the password form structurally cannot authenticate them.
- Participant-share utility (worker): `ParticipantShareSupport.grant(collectionName,
  recordId, userId, accessLevel)` / `revoke(…)` — idempotent conditional-insert
  `record_share` USER rows tagged `reason='participant'` (revoke only removes those).
- `TenantTierQuotas.KEY_MAX_PORTAL_USERS`: FREE 25 · PROFESSIONAL 1 000 · ENTERPRISE
  10 000 · UNLIMITED ∞; enforced in `PortalUserService` before create (429).

## 4. DB migrations

One migration (**verify directory head first**; V167 at time of writing):
`ALTER TABLE platform_user ADD COLUMN user_type` (text, NOT NULL default `INTERNAL`, check
constraint), backfill, index on `(tenant_id, user_type)`; seed Portal User profile per
existing tenant (mirror the V162 `VIEW_ANALYTICS` per-profile seeding pattern); magic-link
token table (hashed token, purpose, expires_at, consumed_at, RLS).

## 5. File-by-file code changes (sketch — finalize with implementation)

| Area | Files (as built) |
|------|------|
| kelta-auth | `controller/PortalLoginController` + `service/PortalLoginService` (new); `KeltaUserDetails` (+`userType`, 10-param overload kept) + `KeltaUserDetailsMixin` (grow together — serialized principals); `KeltaUserDetailsService` (selects `user_type`); `KeltaTokenCustomizer` (`user_type` claim); `AuthorizationServerConfig` (permitAll `/portal/login/**`); templates `portal-login[,-sent,-error].html` |
| kelta-worker | `service/PortalUserService` + `controller/PortalUserAdminController` (new — dedicated endpoint; `UserInviteService` untouched: its token rides `user_credential.reset_token`, wrong for passwordless); `service/ParticipantShareSupport` (new); `TenantTierQuotas` + `TenantQuotaResolver` enforcement; `TenantProvisioningHook` Portal User `ProfileDef`; `SystemCollectionDefinitions.users()` +`userType` (immutable); `SecurityAuditLogger.EventType.PORTAL_USER_INVITED` |
| kelta-gateway | `HeaderTransformationFilter` (+`X-User-Type` from claim), `IdentityHeaderStripFilter` (+strip). No new routes |
| kelta-web SDK | `admin.users.invitePortal` + `PortalInviteRequest/Response` types + `PlatformUser.userType` |
| kelta-ui | `UsersPage`: Type badge column + portal-invite dialog; i18n `users.type*`/`users.*Portal*` keys. Portal login pages live on kelta-auth (not React) |
| Migration | `V167__portal_identity.sql` — column+check+index, Portal User profile seed (single API_ACCESS grant row, absent rows read as denied), `portal_login_token` (+RLS policies), `portal.invite` + `portal.login-link` system templates |

Email template keys: `portal.invite`, `portal.login-link` (system-seeded, tenant-overridable).
Audit: `PORTAL_LINK_REQUESTED`, `PORTAL_LOGIN` (auth, `security.audit` logger),
`PORTAL_USER_INVITED` (worker).

## 6. Test plan

- kelta-auth unit: token single-use/expiry/purpose-binding; uniform not-found response;
  rate limit.
- Worker unit: portal invite forces profile + type; governor cap rejects at limit;
  `ParticipantShareSupport` idempotency.
- Harness (real Postgres/RLS): portal user + share grant reads parent record, non-participant
  denied; cross-tenant isolation on the token table.
- Playwright: invite → mailpit link → portal session lands on the tenant app; portal JWT
  gets 403 on `/api/admin/**` and on an unshared collection read.

## 7. Docs to update (same PR)

Done in the slice PR: `status.md` (Telehealth slice 1 → 🟡); `architecture.md` (portal
identity bullet + `X-User-Type` strip/stamp); `integrations.md` (Portal magic-link login
section); `kelta-auth/CLAUDE.md` (constructor note + reference impl). `conventions.md`
intentionally untouched — no new convention was introduced (existing `requirePermission` +
strip/stamp idioms reused). CLAUDE.md Messaging table unchanged (no new subjects).

## 8. Risks & open questions

- Magic-link deliverability depends on tenant SMTP health — surface send failures in
  `email-logs`; consider a resend cooldown UX.
- `user_type` on the JWT means token re-issue on type change (acceptable: type is immutable
  after create in v1).
- Decision taken (opinionated): portal users are **invited, not self-registered** in v1 —
  self-serve signup would open an enumeration/abuse surface this slice doesn't budget for;
  the booking flow (slice 4) can auto-invite.
- Seat semantics: portal seats counted separately from staff (`maxPortalUsers` vs
  `gov_users`) — pricing implications are an operator decision, keys are independent.
- Decision taken (as built): **MFA/TOTP is not interposed on magic-link login** — link
  possession is the factor and portal users cannot reach enrollment surfaces. Revisit if a
  tenant policy demands step-up for portal users.
- As-built caveat: invite-link URLs use the platform `kelta.auth.issuer-uri` — custom-domain
  tenants get a working link on the platform host; per-domain invite links are a polish item.
- Coupling to watch: `KeltaUserDetails` and `KeltaUserDetailsMixin` constructors must grow
  together (JDBC-stored OAuth2 authorizations round-trip the principal through Jackson).
