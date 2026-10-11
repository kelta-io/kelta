# Slice 1 — Portal Billing (Stripe)

> Child of `specs/consumer-alerting/README.md`. Two PRs: **1A core + webhook**, **1B portal
> surface + enforcement**. Both **security-typed → NO auto-merge**.

> **1B landed.** Delivered: `StripeApiClient` (+ `StripeFormBody`, `StripeApiException`),
> `ReturnUrlValidator`, `BillingCheckoutService`, `BillingController`,
> `MemberEntitlementQuotaHook` + `BillingEntitlementRuleCache` +
> `BillingEntitlementRuleRefreshHook`, `BillingPassExpirySweep`, and
> `BillingWebhookScenarioTest` in the harness.
>
> Two corrections to the design below, both found while implementing:
> - **Quota rejections are HTTP 400, not 422.** A `BeforeSaveResult.error(...)` becomes a
>   `ValidationException`, which `GlobalExceptionHandler` maps to 400 with JSON:API code
>   `beforeSaveHook` and pointer `/data/attributes/_record`. There is no 422 mapping in the
>   platform; inventing one would have meant a bespoke exception path for this hook alone.
> - **Wildcard hook ordering is not what `getOrder()` suggests.** `BeforeSaveHookRegistry`
>   runs *every* collection-specific hook before *any* wildcard hook, so the quota hook's
>   `-90` orders it only within the wildcard group.
>
> Also added, not in the original plan: `ReturnUrlValidator`. Checkout and portal return
> URLs are handed to the processor, which redirects a paying member to them — unvalidated
> that is an open redirect originating inside a real payment flow. Validation is
> origin-equality (not prefix, which `https://app.example.com.evil.test` defeats), HTTPS-only,
> userinfo rejected, and an empty allowlist denies everything.
>
> **1A landed (V178).** Two items listed below under PR-1A moved to **1B**, because 1A had no
> caller for either and shipping unused code is worse than shipping it late:
> - `StripeApiClient` — every call it makes (create customer, checkout session, portal
>   session) belongs to a 1B endpoint. The webhook path never calls out to Stripe. The
>   credential test (`GET /v1/account`) lives in `StripeCredentialType` and uses the JDK
>   `HttpClient`, matching the runtime-core convention — runtime-core deliberately does not
>   depend on a concrete HTTP client.
> - `BillingEntitlementRuleRefreshHook` — it exists to invalidate the rule cache that
>   `MemberEntitlementQuotaHook` owns, and that hook is 1B. 1A ships
>   `BillingEntitlementRuleRepository` (uncached) so 1B only adds the cache and its hook.
>
> Also landed in 1A but not listed below: the gateway's `IpRateLimitFilter` needed real work
> before the webhook entry could function — its path set was **exact-match**, so
> `/api/billing/webhooks` would never have matched `/api/billing/webhooks/stripe/{tenantId}`.
> It is now longest-prefix with per-path budgets and per-path buckets, plus a shared
> IP/CIDR **exemption** list (`kelta.gateway.rate-limit.exempt-cidrs`) honoured by both
> limiters. That anticipates part of slice 7 — see that spec's updated scope note.

## 1. Goal & scope

Delivers: a reusable tenant→portal-user commerce capability — plans with opaque entitlements,
Stripe Checkout (subscription + one-time pass), Billing Portal self-serve, Stripe Tax,
webhook-mirrored subscription/pass state, per-member entitlement resolution + generic quota
enforcement, lapse-to-free.

Does not deliver: refund/dispute handling (the dispatch table stays extensible; passes only
move to REFUNDED once `charge.refunded` handling is added), multiple concurrent subscriptions
per member, in-house invoicing or dunning (Stripe owns both), any frontend.

Conforms to parent: Reuse Map (credential vault, LiveKit webhook pattern, system-collection
recipe), Security (signature trust anchor, portal deny-by-default), NATS subject table.

## 2. UI samples

N/A — backend-only. Sample `GET /api/billing/me` response:

```json
{
  "plan": {"code": "standard-monthly", "name": "Standard", "kind": "SUBSCRIPTION"},
  "subscription": {"status": "active", "currentPeriodEnd": "2026-09-02T00:00:00Z", "cancelAtPeriodEnd": false},
  "passes": [{"planCode": "pass-30d", "expiresAt": "2026-08-20T00:00:00Z"}],
  "entitlements": {"maxActiveWatches": 11, "channels": ["push", "email"], "apiAccess": false}
}
```

## 3. Data & API contracts

Endpoints (all under new gateway static route `/api/billing/**`):

| Endpoint | Auth | Notes |
|---|---|---|
| `POST /api/billing/webhooks/stripe/{tenantId}` | unauthenticated (gateway `unauthenticated-paths`) | signature = trust anchor; 401 bad sig; 200 duplicate |
| `POST /api/billing/checkout-sessions` `{planCode, successUrl, cancelUrl}` → `{url}` | member self-service | URLs must be https + host ∈ credential `allowedReturnOrigins` |
| `POST /api/billing/portal-sessions` `{returnUrl}` → `{url}` | member self-service | requires existing `billing_customer` |
| `GET /api/billing/me` | member | own plan/subscription/passes/effective entitlements |
| `GET /api/billing/plans` | any authenticated | active plans, safe fields only (pricing page) |

Actor resolution: `X-User-Id`/`X-User-Type` headers (`VideoSessionController.actor()` idiom).
Admin plan/rule authoring rides the dynamic system-collection JSON:API; future admin ops gate
on a new `MANAGE_BILLING` permission.

`EntitlementService` (worker `service/billing/`):

```java
MemberEntitlements resolve(String tenantId, String userId);   // cached (Caffeine 5-min TTL)
int intLimit(String tenantId, String userId, String key, int deflt);
boolean boolLimit(...); List<String> listLimit(...);
void invalidate(String tenantId, String userId); void invalidateTenant(String tenantId);
```

Resolution: subscription plan iff status ∈ {active, trialing, past_due} (grace while the
processor retries), else the tenant's DEFAULT plan; merge ACTIVE non-expired passes (numerics
SUM, booleans OR, arrays union). Expired passes are ignored at read regardless of row status.
`canceled|unpaid|incomplete_expired` → DEFAULT (lapse-to-free).

Stripe REST (no stripe-java — native-image reflection hazard): `StripeApiClient` on
`RestClient`, form-encoded, `JsonNode` responses, pinned `Stripe-Version`, `Idempotency-Key`
on POSTs. Calls: create customer; create checkout session (`mode=subscription|payment`,
`automatic_tax[enabled]=true`, `client_reference_id=userId`, metadata tenantId/userId on both
the session and `subscription_data`); create billing-portal session; `GET /v1/account` (test).

Webhook dispatch:

| Stripe event | Action |
|---|---|
| `checkout.session.completed` (payment) | upsert customer; insert pass (ACTIVE, expires per plan; idempotent on session id) |
| `checkout.session.completed` (subscription) | upsert customer only |
| `customer.subscription.created/updated` | upsert `billing_subscription` (plan via price id; member via metadata else customer lookup) |
| `customer.subscription.deleted` | status → canceled |
| `invoice.paid` / `invoice.payment_failed` | flow-trigger bridge only |
| unknown | dedupe row + ignore |

Claim (`INSERT … ON CONFLICT (event_id) DO NOTHING`) and mutation share one `@Transactional`
method — a processing failure rolls back the claim so the processor retries (deliberate
improvement over the LiveKit pattern).

NATS: stream `KELTA_BILLING` (`kelta.billing.>`); subject
`kelta.billing.entitlement.changed.<tenantId>.<userId>` with payload record
`BillingEntitlementChangedPayload(userId, planCode, status, reason)` (runtime-events;
reflect-config in worker AND gateway); broadcast worker consumer evicts the entitlement
cache. Bridge to `kelta.trigger.<tenantId>.billing.subscription` for tenant flows.

## 4. DB migrations

`V178__portal_billing.sql` (verify head — the directory head was V177 at spec time). V170
inline-RLS style; all tables `tenant_isolation` + `admin_bypass` except the dedupe table.

- `billing_plan`: `code` UNIQUE per tenant; `kind` CHECK `SUBSCRIPTION|ONE_TIME|DEFAULT`
  (one active DEFAULT per tenant — partial unique index); `stripe_product_id`;
  `stripe_price_id` (partial UNIQUE per tenant when non-null); `entitlements` JSONB;
  `pass_duration_days`; `active`, `sort_order`, audit columns.
- `billing_customer`: `UNIQUE (tenant_id, user_id)`, `UNIQUE (tenant_id, stripe_customer_id)`.
- `billing_subscription`: `stripe_subscription_id` UNIQUE; processor status verbatim;
  `current_period_end`, `cancel_at_period_end`, `canceled_at`; `UNIQUE (tenant_id, user_id)`;
  index `(tenant_id, stripe_customer_id)`.
- `billing_pass`: `stripe_checkout_session_id` UNIQUE; `status` CHECK
  `ACTIVE|EXPIRED|REFUNDED`; `starts_at`/`expires_at`; indexes
  `(tenant_id, user_id, status, expires_at)` and `(status, expires_at)`.
- `billing_entitlement_rule`: `(tenant_id, collection_name, limit_key)` UNIQUE;
  `count_filter` JSONB; `applies_to` CHECK `PORTAL|ALL`; `active`.
- `billing_webhook_event`: PK `event_id`, `tenant_id`, `event_type`, `processed_at` — no RLS
  (`livekit_webhook_event` shape).
- Seed `MANAGE_BILLING` (copy `V162__seed_view_analytics_permission.sql` shape).

## 5. File-by-file code changes

**PR-1A:** migration above · `SystemCollectionDefinitions.java` (5 factories:
`billingPlans()`, `billingCustomers()` read-only, `billingSubscriptions()` read-only,
`billingPasses()` read-only, `billingEntitlementRules()`; + `all()`) ·
`runtime-core/.../credential/types/StripeCredentialType.java` (secrets `secretKey`,
`webhookSecret`; metadata `publishableKey`, `allowedReturnOrigins`; test = GET /v1/account) ·
`credential-templates/stripe.json` type → `stripe` ·
`runtime-events/.../BillingEntitlementChangedPayload.java` ·
`JetStreamInitializer.java` (`KELTA_BILLING`) · worker
`service/billing/{StripeApiClient, StripeSignatureVerifier, BillingWebhookService,
EntitlementService, EntitlementServiceImpl}` · worker `repository/{BillingPlanRepository,
BillingCustomerRepository, BillingSubscriptionRepository, BillingPassRepository,
BillingEntitlementRuleRepository}` (records + JdbcTemplate) ·
`controller/BillingWebhookController.java` · `listener/{BillingPlanRefreshHook,
BillingEntitlementRuleRefreshHook, BillingEntitlementCacheListener}` · `FlowConfig.java`
(hook registrations) · `NatsSubscriptionConfig.java` (broadcast `worker-billing-entitlement`)
· worker + gateway `reflect-config.json` · gateway `RouteConfigService.registerStaticRoutes()`
(`{"billing", "/api/billing/**", "billing"}`) · gateway `application.yml`
(`unauthenticated-paths += /api/billing/webhooks`) · gateway `IpRateLimitFilter`
(add the webhook path — interim until slice 7 generalizes the limiter).

**PR-1B:** `controller/BillingController.java` · `service/billing/BillingCheckoutService.java`
· `listener/MemberEntitlementQuotaHook.java` (wildcard BeforeSaveHook, order −90: rule-cache
lookup, no rule → ok; actor = `record.get("createdBy")`; skip INTERNAL when
`applies_to=PORTAL`; indexed COUNT vs `intLimit`; `count_filter` field names validated
against the field registry, values bound) · `service/billing/BillingPassExpirySweep.java`
(@Scheduled 60s, `UPDATE … RETURNING` → publish + bridge) · harness scenarios.

## 6. Test plan

Unit: `StripeSignatureVerifierTest` (HMAC vectors, tolerance, multi-`v1`, malformed);
`BillingWebhookServiceTest` (dispatch, duplicate skip, unknown price ignore);
`EntitlementServiceImplTest` (status precedence, pass merge SUM/OR/union, read-time expiry);
`MemberEntitlementQuotaHookTest` (fast path, INTERNAL skip, at-limit reject);
`StripeApiClientTest` via `MockRestServiceServer`. The existing `EventPayloadReflectConfigTest`
guards the worker reflect entry.

Harness (real DB — DB-constraint bugs slip past Mockito): `BillingWebhookScenarioTest`
(signed fabricated `checkout.session.completed` twice → exactly one pass; subscription upsert
conflict; RLS isolation); `BillingEntitlementScenarioTest` (PR-1B: member hits limit → 422;
expire pass + sweep → limit drops).

Not testable pre-deploy: real processor events, Checkout round-trip, retry/Tax behavior.
Local: `stripe listen --forward-to localhost:<gw>/api/billing/webhooks/stripe/<tenantId>`;
post-deploy: Stripe test clocks (renewal, past_due, cancel timelines).

## 7. Docs to update

Root `CLAUDE.md` messaging table (+`KELTA_BILLING` stream list) · `status.md` new capability
row · `integrations.md` Stripe section (credential setup, webhook registration + API-version
pin, dashboard prerequisites: Products/Prices, Tax, retry settings, Billing Portal config) ·
`architecture.md` `/api/billing/**` authz row.

## 8. Risks & open questions

Processor API version drift (pin + defensive JsonNode parsing) · refunds deferred · Cerbos
visibility of billing collections to portal profiles (verify in 1A; ownership metadata if
needed — `specs/member-data-ownership/`) · the gateway reflect-config entry is convention-only (no CI guard on the gateway
side — review checklist item) · one-subscription-per-member simplification (upgrades = Billing
Portal price change).
