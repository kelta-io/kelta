# Verifying the Rust billing module

What proves `kelta-billing` (the Rust external module) works, and how to repeat each check.
Moving a live tenant from the JAR build to this one is [`CUTOVER.md`](CUTOVER.md).

The JAR build's live rollout record (2026-08-31, including the one-off email→UUID rewrite of
`billing_*.userId` rows) stays in [`../billing-jar/VERIFICATION.md`](../billing-jar/VERIFICATION.md).

**Steps marked 🔑 handle secrets. Do not delegate them to an agent.**

## 1. Unit and protocol tests (CI: `test-rust-modules`)

```bash
cd kelta-modules
cargo test --workspace --locked
```

| Suite | Proves |
|---|---|
| `sdk-rust/tests/assertion.rs` | Each assertion refusal: bad signature, unknown `kid`, wrong `iss`/`aud`, expired, future `iat`, over-long lifetime (the default cap is the protocol's 60 s), `htm`/`htu` mismatch, `body_sha256` mismatch, replayed `jti`. JWKS refetch on an unknown `kid`, rate-limited. |
| `sdk-rust/tests/server.rs` | The router refuses before any handler runs; the manifest and assets are the only open paths. |
| `billing/src/*` unit tests | Entitlement merge, quota kinds (boolean/count/unlimited), PORTAL rules, Stripe signature (tolerance, rotation, constant-time), webhook outcomes, return-URL origin matching, configuration. Webhook: a pass only once paid (`async_payment_succeeded` grants, `async_payment_failed` does not); a concurrent duplicate grant refused by the unique session id; an old `updated` after `deleted` does not re-activate, and an older event never overwrites a newer one; an event naming another tenant is acknowledged and ignored. |
| `billing/tests/module.rs` | The whole service through signed requests against an in-memory platform and a stub Stripe. |

## 2. Cross-service harness (CI: `integration-tests`)

`kelta-test-harness/.../scenarios/BillingModuleScenarioTest.java` runs the **real image** against a
real gateway, worker, auth, Postgres and NATS, with WireMock standing in for Stripe. CI builds the
image first (step "Build the kelta-billing module image"). Locally:

```bash
docker build -f kelta-modules/Dockerfile -t kelta-billing-harness .
mvn verify -f kelta-test-harness/pom.xml -Pintegration-tests -Dit.test=BillingModuleScenarioTest
```

Without the image the scenario skips, unless `KELTA_HARNESS_PREBUILT_IMAGES=true`, where it fails.

| Step | Asserted |
|---|---|
| Consent | `POST /api/modules/plan` lists the five collections, the `stripe` credential and `VIEW_ALL_DATA`; no blockers; the UI bundle's fetched hash equals the declared one. |
| Install | With the reviewed `planHash` → `ACTIVE`; the module receives the `installed` lifecycle call. |
| Action | A flow Task `kelta-billing:list-plans` returns the plans. |
| Routes + checkout | `me` shows the DEFAULT plan. `checkout-sessions` returns the Stripe URL; Stripe saw the secret key, an `Idempotency-Key` and the caller's user id. A return URL outside `allowedReturnOrigins` is refused before Stripe is called. |
| Hook | A rule caps a new collection at 1 record; the second create is refused with the plan-limit message. |
| Event | Changing the rule's message reaches the module as an event; the next refusal uses the new message. |
| Entitlements | 120 × `POST /api/watches` at a 0 limit → `422` (the worker asks the module each time). |
| Webhook | An unsigned body → `401`. A signed `customer.subscription.created` → `200`, the subscription row is `active` with `lastStripeEventAt` set (the install provisioned the field), `me` shows the paid plan, and the watch limit lifts. |
| One pass per session | A second pass with the same `stripeCheckoutSessionId` → `400` `unique`: the install made the field unique. |
| Uninstall | With `purgeData=true` and the plan's hash; the hook no longer runs. |

### Gate 0 measurements

Measured 2026-10-31 on the CI-class build host (32 cores) and in the harness.

| | Rust module | Java JAR module |
|---|---|---|
| Release build, clean | 29.8 s (native); 108.6 s cold Docker incl. toolchain | n/a (runs inside the worker) |
| Release build, incremental | 15.3 s | — |
| Image | 8.4 MB (static musl, distroless) | n/a (no image; loaded into the ~JVM worker) |
| Idle RSS | 2.3 MB | not separable from the worker JVM |
| `entitlements.resolve`, module side (uncached, 3 platform reads) | p50 30.4 ms, p99 46.9 ms, n=120 | in-process; not separately metered |
| `POST /api/watches` round trip through gateway → worker → module | p50 46.4 ms, p99 76.3 ms, n=120 | — |

The harness prints the latencies as `GATE0 …` lines. They come from the module's own access log
(`kelta_module::access`, `elapsed_us`), so they exclude the worker's call overhead.

## 3. After deploy (homelab)

Build and Deploy pushes `harbor.rzware.com/kelta-modules/kelta-billing:main-<sha>`, bumps the
`kelta-billing` app in homelab-argo and smoke-tests the manifest from a worker pod. Then:

```bash
kubectl -n kelta-billing get deploy kelta-billing -o jsonpath='{.spec.template.spec.containers[0].image}{"\n"}'
kubectl -n kelta-billing rollout status deploy/kelta-billing
```

**The NetworkPolicy must hold in both directions.** Check it live:

```bash
# Worker → module: allowed.
kubectl -n emf exec deploy/emf-worker -- \
  curl -sS -o /dev/null -w '%{http_code}\n' http://kelta-billing.kelta-billing.svc.cluster.local:8080/.well-known/kelta-module.json
# expect 200

# Module → worker: denied. The image has no shell, so probe from a debug container
# that shares the module pod's network namespace.
POD=$(kubectl -n kelta-billing get pod -l app=kelta-billing -o jsonpath='{.items[0].metadata.name}')
kubectl -n kelta-billing debug "$POD" --image=curlimages/curl:8.11.1 --target=kelta-billing -it --rm -- \
  curl -sS -m 5 -o /dev/null -w '%{http_code}\n' http://emf-worker.emf.svc.cluster.local/actuator/health
# expect a timeout (exit 28), never an HTTP status

# Module → gateway: allowed (Kelta-Access-Token calls and the JWKS).
kubectl -n kelta-billing debug "$POD" --image=curlimages/curl:8.11.1 --target=kelta-billing -it --rm -- \
  curl -sS -m 5 -o /dev/null -w '%{http_code}\n' http://emf-gateway.emf.svc.cluster.local/api/modules/.well-known/jwks.json
# expect 200

# Any other pod → module: denied.
kubectl -n default run np-probe --image=curlimages/curl:8.11.1 --rm -it --restart=Never -- \
  curl -sS -m 5 http://kelta-billing.kelta-billing.svc.cluster.local:8080/.well-known/kelta-module.json
# expect a timeout
```

## 4. Live end to end — a non-production tenant, Stripe TEST mode

Run this on a sandbox tenant (never a tenant with paying members) before any production cutover.
Use only Stripe **test-mode** keys (`sk_test_…`). If the sandbox has the JAR build installed, this
is also the cutover rehearsal: follow [`CUTOVER.md`](CUTOVER.md) steps 1–6 on it.

1. 🔑 Create the credential `stripe` (type `stripe`) on the sandbox with the test-mode
   `secretKey`, a test endpoint's `webhookSecret`, and `allowedReturnOrigins`.
2. Install by URL with `http://kelta-billing.kelta-billing.svc.cluster.local:8080`:

   ```bash
   kelta api POST /api/modules/plan --profile <sandbox-profile> \
     --data '{"baseUrl":"http://kelta-billing.kelta-billing.svc.cluster.local:8080"}'
   # review: five collections (CREATE or ADOPT), credential stripe, system VIEW_ALL_DATA, no blockers
   kelta api POST /api/modules/install --profile <sandbox-profile> --yes \
     --data '{"baseUrl":"http://kelta-billing.kelta-billing.svc.cluster.local:8080","planHash":"<planHash>"}'
   # expect status ACTIVE
   ```

3. Create a DEFAULT plan and a SUBSCRIPTION plan whose `stripePriceId` is a test-mode price.
4. Point a Stripe **test-mode** webhook endpoint at
   `https://<api-host>/api/modules/webhooks/<sandbox-tenant-id>/kelta-billing`, subscribed to
   `checkout.session.completed`, `checkout.session.async_payment_succeeded`,
   `checkout.session.async_payment_failed` and `customer.subscription.created|updated|deleted`.
5. As a portal member of the sandbox: `GET /api/modules/kelta-billing/x/me` shows the DEFAULT plan.
   `POST /api/modules/kelta-billing/x/checkout-sessions` returns a Stripe URL. Pay with the test card
   `4242 4242 4242 4242`.
6. The Stripe dashboard shows the delivery answered `200`. `me` now shows the paid plan and an
   `active` subscription. `portal-sessions` returns a portal URL.
7. Add a rule capping a collection at the paid plan's limit; the create past it is refused.
8. Buy a one-time pass (if a `ONE_TIME` plan exists) and check it expires on the hourly schedule
   after `expiresAt`. Resend that `checkout.session.completed` from the Stripe dashboard: still one
   pass.
9. Cancel the subscription in the Stripe dashboard, then resend an earlier
   `customer.subscription.updated` (status `active`): the row stays `canceled`.
10. In the module log (`kubectl -n kelta-billing logs deploy/kelta-billing`), confirm no secret,
    token or query string appears.

Record the result here, with the date, the image tag and any deviation.

| Date | Image | Tenant | Result |
|---|---|---|---|
| 2026-10-31 | `main-03f18ce` | sandbox (JAR → Rust rehearsal, CUTOVER.md steps 0–6) | **Pass, with the deviations below.** Gap 11 s. Exports unchanged apart from the new `lastStripeEventAt`; `me` and `plans` equal the JAR's. Checkout returns a `cs_test_` URL, portal returns a URL. Two real `customer.subscription.updated` deliveries (cancel at period end, then renew, both done in the test-mode customer portal) answered and updated the row, including `lastStripeEventAt`. Forged and unsigned webhooks: `401`, no change. Quota: a create past a zero cap → `400` `VALIDATION_FAILED` with the rule's message. Module log: no `ERROR`, no secret, token or query string. |
| 2026-10-31 | `main-03f18ce` | production tenant A (no billing rows, 6 plans, no rules) | **Pass.** Gap 1.5 s. Exports, `me` and `plans` unchanged. Checkout returns a live URL (not paid; no customer created). |
| 2026-10-31 | `main-03f18ce` | production tenant B (1 active subscription, 3 quota rules) | **Pass.** Gap 9.5 s. Exports unchanged apart from the new `lastStripeEventAt`; `me`, `plans` and a quota refusal equal the JAR's. Checkout returns a live URL (not paid; no customer created). |

Deviations and gaps from the sandbox run:

- **Flow-invoked member actions fail.** A flow step calling `resolve-entitlements`,
  `create-checkout-session` or `create-portal-session` answers `No calling member`, because the
  platform sends no actor on a flow step. The JAR read the flow's user. This is held for an owner
  decision in kelta-io/kelta#2116. Neither production tenant has an active flow that calls a
  billing action.
- **Not exercised live:**
  - A new checkout paid end to end: the sandbox member already holds the subscription.
  - A one-time pass and its hourly expiry: the sandbox has no `ONE_TIME` plan.
  - The resend of an older subscription event: this needs Stripe dashboard access.
  - All three are covered by unit tests.
- **Still to do in the Stripe dashboard:** subscribe each production webhook endpoint to
  `checkout.session.async_payment_succeeded` and `checkout.session.async_payment_failed`
  (CUTOVER.md step 0). Card payments do not need them.
