# kelta-billing — Stripe billing as an external Kelta module

Stripe-backed portal billing: plans, checkout, subscriptions, one-time passes, per-member
entitlements and quotas. It is an **external module**: a small Rust HTTP service that a tenant
admin installs by URL. The platform calls it with a signed assertion (module protocol v1,
[`docs/module-protocol/openapi.yaml`](../../docs/module-protocol/openapi.yaml)).

It is the reference implementation for external modules and is built on the
[`kelta-module` SDK](../sdk-rust/). Read it with
[`.claude/docs/playbooks.md`](../../.claude/docs/playbooks.md) → *Ship a module*.

License: **AGPL-3.0-only** (the same as the Java module it replaces).

The legacy Java JAR build of the same module is in [`../billing-jar/`](../billing-jar/). It has the
same module id, collections and behaviour, except as listed under
[Differences from the JAR module](#differences-from-the-jar-module). It is kept for rollback until
part 9g. Moving a tenant from the JAR to this service is [`CUTOVER.md`](CUTOVER.md).

## What the service declares and serves

The manifest is served at `GET /.well-known/kelta-module.json` (source: `src/manifest.rs`).

| Piece | Protocol endpoint | Notes |
|---|---|---|
| Collections `billing_plans`, `billing_customers`, `billing_subscriptions`, `billing_passes`, `billing_entitlement_rules` | — | Created (or adopted) at install from the manifest. The module has explicit read/create/edit grants on each. |
| Actions `create-checkout-session`, `create-portal-session`, `resolve-entitlements`, `me`, `list-plans`, `expire-passes` | `POST /kelta/v1/actions/{key}` | A flow Task references them as `kelta-billing:<key>`. |
| Routes `plans` (GET), `me` (GET), `checkout-sessions` (POST), `portal-sessions` (POST) | `/kelta/v1/routes/{path}` | Members call them at `/api/modules/kelta-billing/x/<path>`. Each acts on the **calling** member only. |
| Per-member quota hook | `POST /kelta/v1/hooks` | `beforeCreate` on every collection (`*`), order `-90`, 2 s timeout, `failurePolicy: ignore`. |
| Entitlements | `POST /kelta/v1/services/entitlements/resolve` | The platform's `ModuleEntitlementService` asks this (watch limits, alert channels). |
| Stripe webhook | `POST /kelta/v1/webhooks` | Stripe posts to `/api/modules/webhooks/{tenantId}/kelta-billing`; the worker forwards the raw body. |
| Pass expiry | schedule `expire-passes`, cron `0 0 * * * *` | Hourly, UTC. No flow needed. |
| Cache invalidation | `POST /kelta/v1/events` | `created`/`updated`/`deleted` on plans, subscriptions, passes and rules. |
| Admin UI bundle | `GET /ui/kelta-billing.js` | The platform pins its SHA-256 at install and serves its own copy. |
| Lifecycle | `POST /kelta/v1/lifecycle` | Clears the tenant's caches. |

It asks for three kinds of access, all shown on the install review:

- the `stripe` credential (`permissions.credentials`);
- read/create/edit on its own five collections;
- the `VIEW_ALL_DATA` system permission. The quota hook counts a member's records in whatever
  collection a rule names, so it must be able to read every collection.

## Configuration

All configuration is environment variables (`src/config.rs`). No secret is configured here: the
Stripe keys are the tenant's credential, read per request.

| Variable | Required | Default | Meaning |
|---|---|---|---|
| `KELTA_MODULE_ISSUER` | yes | — | The assertion issuer: `https://<api-host>/api/modules`. |
| `KELTA_MODULE_BASE_URLS` | yes | — | Comma-separated base URL(s) admins install the module by. Every assertion's `htu` must start with one. |
| `KELTA_PLATFORM_URL` | yes | — | The platform API base the module calls back (`{url}/{tenantSlug}/api/...`). In a cluster, the gateway Service. |
| `KELTA_MODULE_JWKS_URL` | no | `{issuer}/.well-known/jwks.json` | Fetch the platform keys from here instead, such as the in-cluster gateway. |
| `BIND_ADDRESS` | no | `0.0.0.0:8080` | Listen address. |
| `STRIPE_API_BASE` | no | `https://api.stripe.com/v1` | Point at a stub in tests. |
| `BILLING_ENTITLEMENTS_CACHE_SECONDS` | no | `0` (off) | Per-member entitlements cache. Off, as in the JAR: a stale entitlement is worse than a slow one, and a record event reaches only one replica. |
| `BILLING_RULES_CACHE_SECONDS` | no | `60` | Quota-rules cache, invalidated by rule events. `0` turns it off. |
| `RUST_LOG` | no | `info` | Log filter. `kelta_module=debug` adds one access line per request. |
| `LOG_FORMAT` | no | JSON | `text` for human-readable logs. |

## Build, test, run

From `kelta-modules/`:

```bash
cargo fmt --all --check
cargo clippy --workspace --all-targets --locked -- -D warnings
cargo test --workspace --locked
cargo run -p kelta-billing          # needs the three required variables
```

From the repository root, the image (one static binary on distroless, non-root, no shell):

```bash
docker build -f kelta-modules/Dockerfile -t kelta-billing .
docker build -f kelta-modules/Dockerfile --target check .   # what CI's test-rust-modules runs
```

The cross-service harness runs this image against a real stack:
`kelta-test-harness/.../scenarios/BillingModuleScenarioTest.java`. It covers install by URL,
consent, a flow action, the routes and checkout, the quota hook, event invalidation, entitlements
and a signed webhook. See [`VERIFICATION.md`](VERIFICATION.md).

Build and Deploy pushes `harbor.rzware.com/kelta-modules/kelta-billing:main-<sha>`. The homelab
deployment is the `kelta-billing` app in homelab-argo (`.claude/docs/ci-cd.md` → *Rust external
modules*).

## Tenant setup

1. Create a credential of type **`stripe`** named **`stripe`**:
   - secrets `secretKey` (`sk_…` or `rk_…`) and `webhookSecret` (`whsec_…`, the endpoint signing secret);
   - metadata `allowedReturnOrigins`: the origins a checkout or portal session may return a member to.
2. Install by URL: **Modules → Install Module → From URL**, enter the module's base URL, review
   the plan, approve. The API equivalent is `POST /api/modules/plan`, then `POST /api/modules/install`
   with the plan's `planHash`. The host must be public `https` or listed in the worker's
   `KELTA_MODULE_ALLOWED_HOSTS`.
3. Point a Stripe webhook endpoint at
   `https://<api-host>/api/modules/webhooks/<tenantId>/kelta-billing`. Subscribe it to
   `checkout.session.completed`, `checkout.session.async_payment_succeeded`,
   `checkout.session.async_payment_failed` and `customer.subscription.created|updated|deleted`.
   Without the two `async_payment` events, a pass bought with a delayed payment method (a bank
   debit, for example) is never granted: the session completes unpaid and the grant waits for
   `async_payment_succeeded`.
4. Create `billing_plans` rows. `kind` is `SUBSCRIPTION`, `ONE_TIME` or `DEFAULT`. A `DEFAULT`
   plan is the free baseline and cannot be bought; with more than one active, the first by
   `sortOrder`, then `code`, wins.
   `stripePriceId` must match the Stripe price.

## Per-member quotas

A `billing_entitlement_rules` row (`collectionName`, `limitKey`, `appliesTo`, optional
`countFilter`, optional `message`) caps creates in `collectionName`. The cap is the member's
resolved value for `limitKey`: base plan plus live passes. The value is read as one of three kinds:

| Value of `limitKey` | Effect on a create |
|---|---|
| Boolean (a feature gate) | `false` rejects with the rule's `message`; `true` allows. No count query. Passes OR-merge, so a pass granting `true` lifts a DEFAULT `false`. |
| Non-negative number (a count cap) | The owner's existing rows (`createdBy` = owner, plus `countFilter`) are counted. At or over the limit rejects. `0` rejects without counting. A numeric string is parsed as an integer. |
| Negative number, such as `-1` | Unlimited: allows, no count query. |

- The owner is the record's `createdBy`, or the acting user when the record has none.
- A member with no value for `limitKey` is uncapped. Put the key on the DEFAULT plan for a hard
  baseline.
- `appliesTo=ALL` holds for every actor. `appliesTo=PORTAL` holds only when the signed actor is a
  portal member.
- The hook fails open: a rule, entitlement or count it cannot read allows the write and is logged.
  The platform also ignores a hook timeout or failure (`failurePolicy: ignore`).

**Quotas are soft limits.** Do not sell a cap that must never be exceeded:

- The hook has 2 s (`timeout_ms: 2000`). A slow, restarting or unreachable module lets every
  create through for as long as it lasts.
- Counting and creating are separate steps with no lock between them. N creates for one member
  that arrive together all see the same count, so the member can end up to N − 1 records over
  the cap.

A hard limit needs the platform to enforce it in the create's own transaction, which a module
cannot do. See `.claude/docs/concerns.md` → *External modules — in-cluster modules*.

## Differences from the JAR module

| JAR module (`billing-jar`) | This service | Why |
|---|---|---|
| Action keys `billing:<key>` | `kelta-billing:<key>` | External actions are addressed `<moduleId>:<key>`. Flows that call billing actions must be updated at cutover. |
| `appliesTo=PORTAL` rules **skipped** | **Enforced** for portal members | The JAR could not read the caller's user type. The assertion's `act.user_type` carries it. |
| `expire-passes` scheduled by a tenant flow | Manifest schedule, hourly | External modules can declare schedules. Delete the tenant's old flow at cutover: once the JAR is gone, its `billing:expire-passes` step fails. |
| Reads every collection in-process | Asks for `VIEW_ALL_DATA` | Counting goes through the platform API as the module's service user. |
| Member from the in-process caller context | Member from the signed `act` claim only | Never from the input or a header. A flow run with no caller (scheduled, async) gets "No calling member". |
| Stripe `Idempotency-Key`: a random UUID | `kelta-billing:<operation>:<platform Idempotency-Key>` when the platform sends one | A retried flow step does not create a second checkout session. Routes have no platform key and still use a random UUID. |
| Credential resolved in-process | `GET /api/modules/self/credentials/stripe?purpose=…` | Per request, audited as the module. Never stored or logged. |

The same in both: entitlements are uncached by default; webhook idempotency converges (a pass is
granted once per checkout session; a subscription is upserted by its Stripe id); money is never
computed here.

Stricter than the JAR, in the webhook:

- **A pass needs a collected payment.** `checkout.session.completed` grants it only when
  `payment_status` is `paid` or `no_payment_required`. A session completed unpaid (a delayed
  payment method) is granted on `checkout.session.async_payment_succeeded`, and
  `checkout.session.async_payment_failed` grants nothing. The JAR granted on completion.
- **One pass per checkout, even under concurrent deliveries.** `stripeCheckoutSessionId` is a
  unique field; the platform refuses a second create (`400` `unique`, or `409` when two creates
  race past that check), and the module reads that as already granted. This
  holds only where the install **created** that field: a collection adopted from the JAR keeps
  its fields as they are, so `CUTOVER.md` sets the flag after install.
- **Out-of-order subscription events are ignored.** The row stores the Stripe event's `created`
  time in `lastStripeEventAt`, and an older event is not applied. A row in `canceled` or
  `incomplete_expired` is never moved out of it, so a retried old `updated` cannot re-activate a
  deleted subscription. A `deleted` that arrives before any row exists creates the row canceled,
  for the same reason. On an adopted collection without `lastStripeEventAt`, only the terminal
  rule applies until `CUTOVER.md` adds the field.
- **An event for another tenant is ignored.** Checkout stamps `metadata.tenantId` on the session
  and the subscription. A signed event whose `tenantId` differs from the assertion's tenant
  answers `200`, is logged at WARN and changes nothing. That happens when one Stripe account
  feeds several tenants' endpoints.

## Security notes

- **Identity comes from the verified assertion only.** The tenant, install and acting member are
  claims the SDK checked. Forwarded webhook headers are `UntrustedHeaders`; nothing reads
  identity from them.
- **The webhook route is unauthenticated and the platform verifies nothing.** This module is the
  trust anchor. It reads the tenant's `webhookSecret` and checks the `Stripe-Signature` HMAC over
  the **raw** body before parsing. It is constant-time, allows 5 minutes of clock drift, and
  accepts any of several `v1` signatures, so a secret can be rotated. A missing credential and a
  bad signature both answer `401`, so a caller cannot tell which tenants have billing. A
  credential the platform cannot hand over right now answers `503`, so Stripe retries.
- **Return URLs are matched on scheme, host and port**, never as a string prefix. They fail closed
  when no origins are configured; otherwise `https://good.example.com` would authorize
  `https://good.example.com.evil.test`.
- **Stripe error messages are logged, never returned** to a caller: they can name account
  internals.
- **The UI bundle runs same-origin** with the admin session. The platform pins its SHA-256 at
  install and serves only that approved copy; a changed file on the host has no effect until an
  upgrade is reviewed.
- **Network:** in a cluster, run the module in its own namespace with a NetworkPolicy that stops it
  reaching the worker pods. The worker trusts identity headers
  (`.claude/docs/concerns.md` → *External modules — in-cluster modules*).

## Known limitations

- **Webhook idempotency converges; it does not claim.** A module has no transaction of its own,
  so a partial failure mid-event can leave one write applied and another not. Stripe's retry then
  converges it.
- **Quotas are soft limits** (see *Per-member quotas*): a slow module or concurrent creates can
  exceed a cap.
- **Two subscription events with the same `created` second apply in arrival order.** Stripe's
  `created` has one-second resolution, so ties are not ordered.
- **Caches are per replica.** A record event reaches one replica. Run one replica, or accept that
  a rule change reaches the others within `BILLING_RULES_CACHE_SECONDS`.
- **A flow with no caller cannot act for a member.** Checkout, portal, `me` and
  `resolve-entitlements` need the signed actor.
