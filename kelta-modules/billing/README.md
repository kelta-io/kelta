# Kelta Billing Module

Stripe-backed portal billing, packaged as a **runtime-installable Kelta module**: one signed JAR
plus its manifest, uploaded into a tenant with no platform recompile or redeploy.

It is the reference implementation for the module SPI — the thing that proves a customer could
ship functionality Kelta never anticipated. Read it alongside
[`.claude/docs/playbooks.md`](../../.claude/docs/playbooks.md) → *Ship a runtime-installable module*.

## What is in the one JAR

| Piece | How it reaches the platform |
|-------|-----------------------------|
| `billing_plans`, `billing_customers`, `billing_subscriptions`, `billing_passes`, `billing_entitlement_rules` | Declared in `kelta-module.json` → created at install through the standard collection API |
| `billing:create-checkout-session` | `ActionHandler`, invoked as a flow action |
| `billing:create-portal-session` | `ActionHandler` — opens Stripe's billing portal for the calling member |
| `billing:resolve-entitlements` | `ActionHandler` — replaces `GET /api/billing/me`; a module cannot contribute a controller |
| `billing:expire-passes` | `ActionHandler` — schedule with a scheduled flow; **a module has no scheduler** |
| `billing:stripe-webhook` | `ActionHandler`, dispatched by `POST /api/modules/webhooks/{tenantId}/kelta-billing` |
| Per-member quotas | `MemberEntitlementQuotaHook`, a wildcard `BeforeSaveHook` driven by `billing_entitlement_rules` |
| Billing Plans widget | `static/ui-bundle.js`, served by `GET /api/modules/kelta-billing/ui-bundle.js` |

## Deliberate differences from the former compiled-in implementation

The worker carried a compiled-in copy of billing until 2026-10-11; this module is now the **only**
billing path. These are not oversights. Each is a platform constraint a module cannot escape, and
each changed behaviour in a way an operator should know about.

| Compiled-in | This module | Why |
|---|---|---|
| `EntitlementServiceImpl` caches per member (Caffeine) and invalidates fleet-wide over NATS | `EntitlementResolver` **does not cache** | A module cannot subscribe to NATS, so a cache it held could not be invalidated when a webhook on another pod changed a subscription. A stale entitlement is worse than a slower one. |
| `BillingPassExpirySweep` is a Spring `@Scheduled` bean | `billing:expire-passes` action, scheduled by a flow | A module is not a Spring bean and has no scheduler. Expiry is read-time anyway, so a sweep that never runs cannot over-entitle anyone. |
| Sweep claims rows `FOR UPDATE SKIP LOCKED` | No claim | A module writes through `QueryEngine` and cannot express one. Two pods sweeping concurrently flip the same row to the same terminal value — harmless. |
| Quota hook reads `X-User-Type` to exempt internal staff | **Skips `appliesTo=PORTAL` rules** (logged once) | Neither `jakarta.servlet` nor `org.springframework` is on a module's classpath, so a module cannot read the actor tier. Use `appliesTo=ALL` for rules that must hold for every actor. |
| Webhook claims each event id in a row sharing the mutation's transaction | Converges instead | See *Known limitations* below. |

## Build

```bash
mvn -f kelta-modules/billing/pom.xml package
```

Produces `target/kelta-module-billing-1.0.0.jar`. Install it through the admin UI's **Modules**
page, or `POST /api/modules/install-jar` with the JAR, `kelta-module.json`, and a detached
signature when the tenant has a signing key configured.

**This project is deliberately outside the `kelta-platform` Maven reactor and has no parent pom.**
Wiring it in would ship it with the platform and quietly undo the point of the exercise. Its
`runtime-core` / `runtime-module-integration` dependencies are `provided` because both load from
the platform classloader at runtime; bundling them would put a second copy of the runtime model in
the JAR and every cast to a platform type would fail.

## Tenant setup

1. Create a credential of type **`stripe`** named `stripe`, holding:
   - `secretKey` — `sk_…` or `rk_…`
   - `webhookSecret` — `whsec_…` (the endpoint signing secret)
   - `allowedReturnOrigins` — the origins checkout may return a member to
2. Point a Stripe webhook endpoint at
   `https://<tenant-host>/api/modules/webhooks/<tenantId>/kelta-billing`, subscribed to
   `checkout.session.completed`, `customer.subscription.created|updated|deleted`.
3. Create `billing_plans` rows. `kind` is `SUBSCRIPTION`, `ONE_TIME`, or `DEFAULT` (the free
   baseline — not purchasable). `stripePriceId` must match the Stripe price.

## Security notes

- **The webhook route is unauthenticated and the platform verifies nothing.** This module is the
  trust anchor: `ProcessStripeWebhookActionHandler` resolves the tenant's own credential and checks
  the HMAC over the **raw** body before parsing it. Re-serializing the body would change the bytes
  the signature covers.
- **Return URLs are matched on scheme + host + port**, never as a string prefix, and fail closed
  when no origins are configured — otherwise `https://good.example.com` would authorize
  `https://good.example.com.evil.test`.
- **The UI bundle runs same-origin** with the admin session's full DOM and cookie access. The JAR
  signature verified at install and re-verified on read is the entire trust model; there is no
  browser-side isolation.
- Stripe's own error messages are logged, never returned to a caller — they can name account
  internals. Money is never computed here: amounts, proration, tax, dunning, and retries all stay
  with Stripe, and these collections hold ids and coarse state only.

## Known limitations (versus the compiled-in implementation this replaced)

These are real gaps, not oversights — record them before relying on this in production:

- **Webhook idempotency is weaker.** The compiled-in version claims each event id in a row that
  shares one transaction with the mutation, so a failure rolls the claim back and Stripe's retry
  genuinely re-runs. A module writes through `QueryEngine` and has no transaction of its own, so
  this relies on convergence instead: a pass is granted once per checkout session, and a
  subscription is upserted by its Stripe id. Replaying converges; it does not duplicate. A partial
  failure mid-event can still leave one write applied and another not.
- **Verify each install.** The prod rollout is recorded in [`VERIFICATION.md`](VERIFICATION.md);
  run its checks on any new tenant. "Installed and ACTIVE" is not evidence on its own — read the
  health endpoint and confirm the `EntitlementProvider` service registered on every pod.
- The behavioural differences in the table above are permanent given the module boundary — decide
  they are acceptable for your tenant before cutting over, particularly the **`appliesTo=PORTAL`
  quota rules being skipped**, which silently enforces nothing where the compiled-in version
  enforced a member cap.

## Entitlements are published to the platform

The module implements the platform port `io.kelta.runtime.module.service.EntitlementProvider` and
publishes it from `getServices()`. It is the platform's **only** source of entitlements: platform
code that gates on them (availability alert fanout, watch limits) asks the worker's
`ModuleEntitlementService`, which asks this module. On a tenant without the module every member
resolves `MemberEntitlements.EMPTY` — watches uncapped, push/email ungated, SMS never sent.

The platform binds `TenantContext` (id **and** slug) around the call, because `EntitlementResolver`
reads through the query engine and the tenant's schema comes from the slug; a module has no way to
look one up. If the slug cannot be resolved, the platform refuses the module path and resolves no
entitlements rather than reading the public schema.

Answers are **not cached** platform-side: a module cannot consume a NATS invalidation, so nothing
could evict a cached answer when a webhook on another pod changed a subscription, and a stale
entitlement is worse than a slower lookup. A provider that throws or returns null resolves no
entitlements (`EMPTY`) — entitlements gate alert delivery, so a broken module must neither take that
path down nor grant more than a tenant with no billing at all.
