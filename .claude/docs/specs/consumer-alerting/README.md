# Consumer Alerting Substrate — Portal Commerce + Availability Alerting (Parent Spec)

> **Status:** parent planning spec. Authoritative shared contract for a set of platform
> capabilities that let a tenant run a **consumer-facing product on top of Kelta**: charge its
> portal users (subscriptions + one-time passes), let those members register **watches** on
> things they want, ingest **availability signals** from external sources, and deliver
> **alerts** in seconds over push/email. Every capability here is tenant-agnostic platform
> substrate — the driving use case is one tenant app, but nothing in the design is specific to
> it. Each slice in the [Slice plan](#slice-plan) is expanded into its own child spec in this
> directory and **extends, never contradicts** the [Reuse Map](#reuse-map),
> [shared contracts](#shared-contracts), and [Security](#security) sections below.
>
> Source-verified against the codebase on 2026-08-02 (Flyway directory head **V177**
> — next new migration is **V178**; deployed flyway_schema_history keeps pre-flatten
> numbering, so always check the directory + deployed history before numbering). If code and
> this doc disagree, trust the code and fix this doc.

## How to use this document

This parent defines the cross-cutting architecture once. Child specs each cover one PR-sized
slice with acceptance criteria, exact contracts, DB migrations (or "none"), file-by-file
changes, and a test plan — per the child-spec template in `specs/app-surfacing/README.md`
(sections 1–8; sections that don't apply state "N/A — reason"). Read this parent first;
every child references it.

## Slice plan

| Slice | Child spec | Axis |
|-------|-----------|------|
| 0 — This spec + doc wiring | (this file) | foundation (docs) |
| 1 — Portal billing (Stripe) | `1-portal-billing.md` | **backend, security** (two PRs: core+webhook, portal surface+enforcement) |
| 2 — Flow-log retention | `2-flow-log-retention.md` | backend (ops hardening) |
| 3 — Watch/target model | `3-watch-model.md` | backend (collections, tables) |
| 4 — Availability matcher + alert fanout | `4-availability-matcher.md` | backend (NATS, hot path) |
| 5 — Portal member surface | `5-portal-member-surface.md` | **backend + auth, security** (self-signup, watch API) |
| 6 — Web Push (VAPID) | `6-web-push.md` | backend (notification channel) |
| 7 — Public-traffic hardening | `7-public-traffic-hardening.md` | **gateway + edge, security** |
| 8 — Analytics capture | `8-analytics-capture.md` | backend (capture + retention) |
| 9 — Win tracking + live ticker | `9-win-tracking.md` | backend (collection, member API) |
| 11 — SEO page generation | `11-seo-page-generation.md` | backend (collection, nightly sweep) |
| 10 — SMS alert channel (Twilio) | `10-sms-channel.md` | backend (notification channel) |

External tracks (contracts owned here, code outside this repo): an **availability poller**
(separate service; see [Poller contract](#poller-contract)) and a **consumer frontend**
(standalone SPA/PWA over headless portal auth — the existing third-party-portal precedent —
consuming the slice 1/5/6 APIs).

**Dependency order (hard edges): 1A → 1B; 3 → 4 → {poller, 5, 6}; 5 → 7 → public launch.**
Slice 2 is independent — land it while slice 1 reviews; it MUST land before high-frequency
polling flows turn on. Slice 5 also depends on slice 1 (entitlement checks at watch create).

## Context

The 2026-08-02 recon established what already exists and what this effort adds.

Already built (reuse, do not rebuild):

1. **RLS `SET LOCAL` hardening is done** (transaction-scoped, PgBouncer-safe).
2. **The external-trigger path is built** — `kelta.trigger.<tenantId>.<topic>` NATS-triggered
   flows work end-to-end (`NatsTriggerFlowListener`); an external poller can drive tenant
   flows today with no platform change (useful as a proof-of-concept before slice 4).
3. **Portal identity + headless auth ship** (telehealth slices 1/8): PORTAL user type,
   magic-link login JSON API, redirect-URI allowlists, invite-driven creation.
4. **Push delivery is real** (`FcmPushProvider`/`ApnsPushProvider`, `push_device`) for native
   platforms.
5. **Governor limits** exist at the tenant level (tiers + `tenant.limits` overrides + quota
   BeforeSaveHooks).

Added by this effort:

6. **Member-level commerce and entitlements** — tenant→portal-user billing does not exist
   today (governor limits are tenant-scoped); slice 1 adds plans, Stripe-mirrored
   subscriptions/passes, and a per-member entitlement resolver with generic quota enforcement.
7. **Alert-in-seconds matching** — record-triggered flow matching is a per-event linear scan
   with no subscription index; slice 4 adds an indexed matcher and a priority fanout path,
   keeping the flow engine available for extension but off the hot path.
8. **Browser push** — slice 6 adds the Web Push (VAPID) channel beside the native providers.
9. **Retention for flow logs** — slice 2 adds age-based pruning (the `record_version` growth
   concern applies equally to `flow_execution`/`flow_step_log`/`job_execution_log`, and
   high-frequency polling flows would otherwise accumulate them).
10. **Consumer-grade public-traffic controls** — slice 5 adds opt-in portal self-signup and
    slice 7 adds Redis-backed per-IP budgets, entitlement-driven per-user budgets, and a
    pluggable bot challenge. Slice 7 is the gate for enabling public signup in production.

**Intended outcome:** a tenant can stand up a consumer product where a stranger pays via
Stripe Checkout, registers a watch, and receives a push/email alert with no manual steps —
with every piece reusable by any other tenant.

## Key architecture decisions

- **Billing = platform capability for tenant→portal-user commerce.** `billing-plans` carry
  opaque `entitlements` JSONB; a generic `EntitlementService` resolves per-member limits
  (subscription plan, else the tenant's DEFAULT free plan, plus time-boxed passes); a generic
  wildcard `MemberEntitlementQuotaHook` enforces limits declared as
  `billing-entitlement-rules` rows. A tenant's "N watches on plan X" is **config, not code**.
  Stripe is integrated via **plain REST (`RestClient` + form-encoded + `JsonNode`)** — the
  official stripe-java SDK carries a large unregistered reflective model surface that is a
  GraalVM native-image hazard. Dunning is Stripe Smart Retries; Products/Prices are authored
  in the tenant's Stripe dashboard and referenced by id. Checkout / Billing Portal / Tax are
  Stripe-hosted (buy-not-build).
- **Watch/target = system collections; hot-path state = plain tables.** `watch-targets` and
  `watches` are system collections (the matcher needs fixed shapes for indexed SQL — the
  approvals/telehealth precedent — and they get JSON:API, RLS, flows, and audit for free).
  `availability_state`, `alert`, `alert_delivery` are Flyway-owned plain tables internal to
  the matcher. **Raw availability observations are not persisted** — processed in memory off
  NATS; any reporting aggregates are a later, separate effort.
- **The matcher rides a dedicated subject, not `kelta.trigger.>`.** Pollers publish normalized
  events to `kelta.availability.event.<tenantId>.<source>` (new `KELTA_AVAILABILITY` stream,
  short maxAge ~1h). A queue-group `AvailabilityEventListener` detects closed→open
  transitions (minting an `episode_id`), runs the indexed watch query, dedupes per
  `(watch, slot, episode)`, and fans out via `AlertDispatchService` (push + email now, SMS
  later). After fanout it republishes a compact summary to
  `kelta.trigger.<tenantId>.availability` so tenant flows (digests, tickers, custom
  automations) stay extensible **off** the hot path. Latency budget: seconds.
- **Poller lives outside this repo.** A long-running service publishing straight to in-cluster
  NATS, with per-source token-bucket politeness, jitter, and backoff. Posture is **alert-only
  and polite**: never automate booking against a source, never resell access to a source's
  inventory, honor rate limits and caching, and treat any unofficial source as revocable.
- **Standalone consumer frontend** over headless portal auth + the slice 5 APIs (the existing
  external-portal precedent). `kelta-ui/app` is the staff/admin surface, not the member portal.
- **Web Push = new `WebPushProvider` inside the existing `PushProvider` SPI**, routed per
  device platform (`web` → WebPushProvider; ios/android → the property-selected provider).
  VAPID + RFC 8291; `device_token = sha256(endpoint)` preserves the existing
  `UNIQUE (tenant_id, device_token)` and 500-char column; full subscription JSON in a new
  TEXT column.
- **No public bulk-data API in this effort.** Any content site built on this data renders
  server-side/statically from build-time authenticated reads; structured bulk data gets no
  open JSON endpoint. This keeps the public surface as narrow as it is today and defers a
  "public read surface" design until something actually needs it.

## Reuse Map

| Need | Reuse (do NOT rebuild) |
|------|------------------------|
| Store Stripe keys | Credential vault: `CredentialResolverImpl`, `CredentialEncryptionHook`, new `StripeCredentialType` alongside existing `credential/types/` |
| Inbound signed webhook | LiveKit pattern: `VideoSessionController` raw-body + verify + `ON CONFLICT (event_id) DO NOTHING` dedupe (`LiveKitWebhookService`), gateway `unauthenticated-paths` |
| System collection + config broadcast | `SystemCollectionDefinitions` factory + Flyway + RLS + `<Name>RefreshHook` in `FlowConfig` publishing collection-changed (Critical Rule 1) |
| Member login / signup verify | `PortalLoginService` magic-link machinery + `PortalAuthApiController` JSON API |
| Owner-scoped writes on generic routes | `UserPreferenceGuardHook` idiom |
| Push delivery | `DefaultPushService` + `PushProvider` SPI (`FcmPushProvider` reference) |
| Email delivery | `DefaultEmailService`/`SmtpEmailProvider` (+ templates, per-tenant SMTP) |
| Retention sweep shape | `RetentionPurgeSweep`/`AutoArchiveSweep` (@Scheduled, SKIP LOCKED, dry-run gate) |
| Tenant flow extensibility | `kelta.trigger.<t>.<topic>` NATS_TRIGGERED flows (`$.input` = message body) |
| Redis fixed window | `RedisRateLimiter` post-lockout-fix Lua pattern (TTL set only on new window) |
| Quota hook shape | `CollectionQuotaEnforcementHook` (order −100, `FlowConfig` registration) |

## Shared contracts

### AvailabilityEvent (poller → platform, NATS `kelta.availability.event.<tenantId>.<source>`)

```json
{
  "source": "source-key",              // registered source identifier
  "targetExternalId": "12345",         // source-native id; resolved to watch_target
  "slotKey": "2026-08-14",             // source-defined slot identity (date, unit+date, appointment slot)
  "status": "OPEN",                    // OPEN | CLOSED
  "window": {"start": "2026-08-14", "end": "2026-08-16"},   // optional
  "quantity": 1,                        // optional
  "meta": {},                           // source-specific extras (never required by the matcher)
  "polledAt": "2026-08-02T17:31:04Z"
}
```

JSON object body (JetStream); one event per (target, slot) state observation. The matcher is
transition-driven: repeated OPEN observations of the same slot do not re-alert (episode model,
child spec 4).

### Entitlements (billing → consumers)

`billing_plan.entitlements` JSONB, opaque tenant-defined keys. Reference keys used by the
watch/alert slices: `maxActiveWatches` (int), `channels` (array: push|email|sms), `apiAccess`
(bool), `apiCallsPerDay` (int — slice 7 per-user limiter input). Resolution: subscription plan
when status ∈ {active, trialing, past_due}, else DEFAULT plan; ACTIVE non-expired passes merge
on top (numerics SUM, booleans OR, arrays union).

### NATS subjects added by this effort

| Subject | Stream | Publisher → consumer |
|---------|--------|----------------------|
| `kelta.billing.entitlement.changed.<tenantId>.<userId>` | KELTA_BILLING (new) | webhook svc / expiry sweep → worker broadcast cache-evict |
| `kelta.availability.event.<tenantId>.<source>` | KELTA_AVAILABILITY (new, ~1h maxAge) | poller → worker queue-group matcher |
| `kelta.trigger.<tenantId>.billing.subscription` | KELTA_TRIGGERS (existing) | webhook svc → tenant flows (welcome / payment-issue / win-back) |
| `kelta.trigger.<tenantId>.availability` | KELTA_TRIGGERS (existing) | matcher post-fanout → tenant flows (digest / ticker) |
| `kelta.trigger.<tenantId>.poller-health` | KELTA_TRIGGERS (existing) | poller heartbeat → tenant staleness flow |

Every new stream gets its own `ensureStream(...)` in `JetStreamInitializer`; every new
`PlatformEvent` payload record gets reflect-config entries in **both** worker and gateway
(native rule; `EventPayloadReflectConfigTest` covers the worker side only).

## Security

- **Billing slices and the member-surface / hardening slices are security-typed → NO auto-merge.**
- **Webhook trust anchor is the signature, not the path.** The `{tenantId}` path segment only
  selects which per-tenant `webhookSecret` to verify against; an HMAC pass proves authenticity
  for that tenant. Constant-time compare, 300s timestamp tolerance, event-id dedupe bounds
  replay.
- **Portal users are deny-by-default on member data.** Watch reads go only through the
  owner-scoped `WatchController` (`WHERE member_id = caller`); generic-route reads of the
  watch/billing collections are denied to `user_type=PORTAL`; generic-route writes are
  owner-guarded by hook. Slice 1 and 3 reviews must confirm the seeded Portal User profile
  cannot reach another member's rows through the dynamic API; add owner-guard hooks if it can.
- **Public-surface controls ship before public signup is enabled.** Self-signup is per-tenant
  opt-in (default off) and slice 7 — Redis-backed per-IP budgets on public paths,
  entitlement-driven per-user budgets on authenticated traffic, and a pluggable bot challenge
  (ALTCHA proof-of-work; open source, self-hosted — proprietary CAPTCHA services rejected per
  the open-source rule) — is the gate for turning it on in production.
- **Injection guards:** `billing_entitlement_rule.count_filter` field names are validated
  against the collection field registry and values always bound as params. Never log payment
  payloads beyond ids.
- **Cross-tenant access is per-tenant `callWithTenant`, never a sentinel.** The webhook and
  matcher paths use explicit-tenantId repositories or `callWithTenant`. `runAsPlatform` was a
  silent-data-loss trap (no matching RLS policy) and was removed 2026-09-09.

## Poller contract

Owned here, implemented outside this repo (separate service + deployment manifests).
Requirements: publish [AvailabilityEvent](#shared-contracts) JSON to
`kelta.availability.event.<tenantId>.<source>` on in-cluster NATS; per-source token-bucket +
jitter + backoff on 403/429 (politeness is a product requirement, not an option); heartbeat
every cycle to `kelta.trigger.<tenantId>.poller-health`
`{source, lastPollAt, ok, targetsPolled}`, watched by a tenant scheduled flow (no platform
code); target list from config v1, later read from `watch-targets` via the API.

## Proof-of-concept (before/alongside slice 1)

No repo changes needed: create the tenant, author a NATS_TRIGGERED flow (topic `availability`)
with an email action via the MCP admin tools, and publish poller JSON to
`kelta.trigger.<tenantId>.availability`. Proves source data shapes (feeding the slice 3/4
specs), the live trigger path, end-to-end latency, and the per-source politeness envelope.

## Docs to update (per CLAUDE.md Rule 6, in each slice's PR)

- Root `CLAUDE.md` — Messaging table + JetStream stream list (slices 1, 4); specs index (this PR).
- `.claude/docs/status.md` — capability rows as slices ship (portal billing, availability
  alerting, flow-log retention, Web Push, portal signup, public-traffic rate limiting).
- `.claude/docs/integrations.md` — Stripe section (slice 1); availability/poller contract (4).
- `.claude/docs/architecture.md` — `/api/billing/**`, `/api/watches`, signup + rate-limit
  filter changes (slices 1, 5, 7).
- `.claude/docs/concerns.md` — flow-log retention row closed (slice 2); any accepted
  trade-offs added in-slice.
