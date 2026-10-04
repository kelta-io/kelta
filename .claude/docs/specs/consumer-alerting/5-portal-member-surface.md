# Slice 5 — Portal Member Surface

> Child of `specs/consumer-alerting/README.md`. Depends on slices 3 (watches) + 1
> (entitlements). **Security-typed → NO auto-merge** (public signup + portal authz boundary).

> **Landed.** `WatchController`, `WatchGuardHook`, `PortalSignupController`,
> `InternalPortalSignupController`, the `selfSignupEnabled` setting, and 27 unit tests. Four
> notes against the design below:
> - **Signup creates the account via the worker, not in kelta-auth.** §5 reads as though
>   kelta-auth would insert the `platform_user` itself, and it could — it shares the database.
>   But `PortalUserService.invitePortalUser` already enforces the **`maxPortalUsers` seat
>   governor**, which the spec never mentions. Public self-signup without a seat cap is
>   unbounded account creation, and kelta-auth has no `TenantQuotaResolver` to duplicate the
>   check with (two copies would drift). So signup calls a new internal-token endpoint,
>   `POST /api/internal/portal/signup`, reusing the existing enforcement. The existing
>   `/api/admin/users/portal-invite` could not be reused: it requires `MANAGE_USERS` and a
>   resolved profile, which an unauthenticated signup has neither of.
> - **A CSRF exemption is required, not just `permitAll`.** §5 lists only the permitAll entry;
>   without also adding the path to `AuthorizationServerConfig`'s `ignoringRequestMatchers`,
>   every signup POST returns 403.
> - **The request field is `tenant`, not `tenantSlug`.** The sibling
>   `PortalAuthApiController.LoginLinkRequest` already calls it `tenant`, and the same frontend
>   calls both endpoints — two names for one concept is a needless trap.
> - **The INTERNAL-collision explanatory email is not sent.** No such template key exists, so
>   it would mean inventing and seeding one; an unseeded template just records a FAILED
>   delivery. The collision is audited server-side and the response is still 202, which is the
>   security property. Worth adding as a UX follow-up.
>
> Also: `PortalAuthSettingsController`'s PUT **replaces the whole `{portalAuth}` object**, so
> `selfSignupEnabled` is written back unconditionally — omitting it would let an unrelated
> allowlist edit silently switch signup off.

## 1. Goal & scope

Delivers: opt-in portal self-signup (magic-link verified), an owner-scoped watch CRUD API,
deny-by-default portal access to watch/billing rows on generic routes, and a configurable
public-path IP budget (fully generalized in slice 7).

Does not deliver: the bot challenge (slice 7), the consumer frontend itself (outside this
repo), password login for portal users (passwordless by design), MFA for portal users.

## 2. UI samples

N/A — backend. Frontend flow: signup form → 202 → "check your email" → magic link →
`/portal/api/login/verify` → Bearer JWT → watch list.

## 3. Data & API contracts

**Signup** — `POST /portal/api/signup` (kelta-auth, beside `PortalAuthApiController`):
`{email, tenantSlug, redirectUri}` → **202 always** (enumeration-safe, mirroring
login/request). Behavior: existing PORTAL user → treated as a login request; new email →
create `platform_user` (`user_type=PORTAL`, Portal User profile) + send a magic link
(PORTAL_INVITE semantics); an address belonging to an INTERNAL user → 202 + an email
explaining a staff account exists. `redirectUri` exact-match against
`tenant.settings.portalAuth.redirectUris` (existing allowlist). Per-email throttling rides
`PortalLoginService.MAX_LINKS_PER_WINDOW`; per-IP budget comes from the rate-limit config.
Gated per tenant by `tenant.settings.portalAuth.selfSignupEnabled` (**default FALSE** —
invite-only tenants are unchanged, and production enablement waits on slice 7).

**Watch CRUD** — `WatchController` (worker) at `/api/watches` (new gateway static route):

| Endpoint | Behavior |
|---|---|
| `GET /api/watches` | the caller's watches only (`WHERE member_id = caller`), with an embedded target summary |
| `POST /api/watches` `{targetId, criteria, channels}` | owner stamped from the caller; channels ∩ entitled channels; entitlement quota enforced (slice-1 hook + an explicit pre-check for a friendly 422 `{error, limit, current, upgradeHint}`) |
| `PATCH /api/watches/{id}` | owner-checked; criteria/channels/status (pause/resume) |
| `DELETE /api/watches/{id}` | owner-checked |
| `GET /api/watches/{id}/alerts` | alert history for a caller-owned watch (joins `alert` + deliveries) |

Actor from `X-User-Id`/`X-User-Type`. INTERNAL users holding `MANAGE_DATA` may pass
`?memberId=` for support (in-controller gate). The tenant-wide `list`/`get` (no `memberId`)
also admits the read-only `VIEW_ALL_DATA`; naming a member and every mutation stay
`MANAGE_DATA`-only.

**Generic-route lockdown**: `WatchGuardHook` (BeforeSaveHook, `UserPreferenceGuardHook` idiom)
owner-guards writes to `watches` arriving via dynamic routes; portal reads of `watches` and
the billing collections via generic routes are denied (Cerbos policy or gateway check — pick
in-slice after verifying the seeded Portal User profile's reach; the parent Security section
requires deny-by-default either way).

## 4. DB migrations

None expected (the watch table ships in slice 3; `selfSignupEnabled` lives in
`tenant.settings` JSONB). If a seeded default is wanted, take head+1.

## 5. File-by-file code changes

- kelta-auth: `PortalSignupController` (or an extension of `PortalAuthApiController`) +
  the `PortalLoginService` signup path; `AuthorizationServerConfig` permitAll list +=
  `/portal/api/signup`.
- kelta-worker: `controller/WatchController.java`, `service/availability/WatchService.java`,
  `listener/WatchGuardHook.java` (+ `FlowConfig` registration); `PortalAuthSettingsController`
  gains `selfSignupEnabled`.
- kelta-gateway: static route `{"watches", "/api/watches/**", "watches"}` in
  `RouteConfigService.registerStaticRoutes()`; `IpRateLimitFilter` path set becomes
  configurable (`kelta.gateway.rate-limit.paths`) covering `/portal/api/signup` and
  `/portal/api/login/request` (interim — slice 7 replaces the limiter implementation).

## 6. Test plan

Unit: signup branches (new / existing-portal / internal-collision all → 202; disabled tenant →
no-op; bad redirectUri rejected); WatchController owner scoping (a foreign id returns 404, not
403 — no existence oracle); channel intersection; the guard hook. Harness:
`PortalSignupScenarioTest` (real DB: signup → user row + hashed token; verify → working JWT);
`WatchOwnershipScenarioTest` (two portal members cannot read or write each other's watches,
via the controller **and** via the generic route). Post-deploy Playwright (with the frontend):
signup → magic link (Mailpit) → create watch → quota 422.

## 7. Docs to update

`architecture.md` (`/api/watches` authz row, signup endpoint) · `status.md` (portal
self-signup row; the existing "invite-only" note updated) · `integrations.md` portal-auth
section gains signup · `concerns.md` only if something lands as an accepted partial (the
generic-read lockdown should not).

## 8. Risks & open questions

Self-signup stays off in production until slice 7 lands (the per-IP/per-email budgets here are
interim). Portal JWT TTL is 8h — acceptable for v1. Whether the portal read-deny is
implemented as a Cerbos policy or a gateway check is decided in-slice and documented in
architecture.md either way.
