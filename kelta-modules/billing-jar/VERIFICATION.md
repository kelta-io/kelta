# Live verification runbook — billing module

Verifies the billing module end-to-end against **real Stripe test-mode keys** on the live
`<tenant-slug>` tenant. *(This runbook gated removing the compiled-in `kelta-worker` billing; that
copy is now gone (2026-10-11), so this module is the only billing path and a tenant without it has
no entitlements.)*

**Steps marked 🔑 require you to handle secrets and must not be delegated to an agent.**

## Use the sandbox, not production

`<tenant-slug>` had an existing subscriber row on the compiled-in billing (6 plans, 1 subscription,
1 customer). *(It emerged later that this was a **test-mode** subscription orphaned by a processor
account switch, and it has since been deleted — see the prod-rollout section. The reasoning below
still holds: at the time it was indistinguishable from a real one, and treating it as real cost
nothing.)* Pointing a real Stripe webhook at the module there would write events into the module's
parallel, empty collections while the app keeps reading the compiled-in ones — diverging a paying
member's state. Do the Stripe half in a sandbox.

A sandbox is already provisioned:

| | |
|---|---|
| Environment | `billing-stripe-verify` (id `<environment-id>`), status ACTIVE |
| Sandbox tenant | `<sandbox-tenant-id>` |
| Slug | `<sandbox-slug>` |
| CLI profile | `billing-sandbox` (already written to `~/.kelta/config.json`) |
| Admin user | `<sandbox-admin>` |
| Webhook URL | `https://<api-host>/api/modules/webhooks/<sandbox-tenant-id>/kelta-billing` |

Verified reachable: that webhook URL answers **404** pre-install (dispatcher responding, nothing
installed) and the sandbox slug resolves 200.

### 🔑 One human step before the rest can be automated

The auth server supports only `authorization_code`, `client_credentials`, `refresh_token` and
token-exchange — **no password grant** — so a sandbox token cannot be minted non-interactively,
and a parent-tenant PAT is correctly refused across tenants (403 `User identity not resolved`).

Log in once with the sandbox admin credentials printed by `kelta sandbox create`:

```bash
kelta auth login --profile billing-sandbox
```

After that every step below works with `--profile billing-sandbox`.

### Signing keys are per tenant

The sandbox needs its **own** registered key or the install falls back to inert stubs. Register the
same public half already trusted on <tenant-slug>:

```bash
kelta api POST /api/modules/signing-keys --profile billing-sandbox --yes --data "$(python3 - <<'EOF'
import json
pem=open('~/.<tenant>/module-signing/module-signing-public.pem').read()
print(json.dumps({"label":"2026-h2","algorithm":"Ed25519","publicKeyPem":pem,"active":True}))
EOF
)"
```

Then run steps 1–8 with `--profile billing-sandbox`, using the sandbox tenant id in the webhook URL.

---

Environment established by inspection, not assumption:

| | |
|---|---|
| Tenant | `<tenant-slug>` = `<prod-tenant-id>` |
| API | `https://<api-host>` (CLI profile `<tenant-slug>`) |
| Signing | **required** (`KELTA_MODULES_SIGNING_REQUIRED=true`), no platform key |
| Tenant key | Ed25519, label `2026-h2`, fp `7d796bd4…`, active, 1 dependent module |
| Precedent | `<tenant-slug>-ridb` v0.1.0 is installed and ACTIVE — this path is proven |

## ⚠️ The failure mode to watch for

A signature or checksum failure **does not fail the install**. The module falls back to inert stub
handlers and still reports `status: ACTIVE`. Every action then returns `"mode": "stub"` and quietly
does nothing.

**Never treat ACTIVE as working.** Step 4 is the real gate.

There are **three** independent causes of that silent stub fallback, and they look identical:

1. The signature does not verify (wrong key, or base64 with newlines — see step 2).
2. The JAR checksum does not match what was stored.
3. **The worker is running the GraalVM native image.** A native image has no bytecode
   interpreter, so it cannot execute an uploaded JAR at all — every module installs as stubs.

Cause 3 is the one that is invisible from the API. Check it directly:

```bash
kubectl exec -n emf deploy/emf-worker -- sh -c 'ls /app'
# app.jar  => JVM image, modules can run
# no app.jar (a single native binary) => modules CANNOT run, stop here
```

✅ Confirmed 2026-08-30: the deployed worker is the JVM image (`/app/app.jar`, Temurin 25.0.4),
which is also why `<tenant-slug>-ridb` works today. If the worker is ever switched to the native
image, every installed module silently becomes inert.

---

## 0. Prerequisite — land the tenant-binding fix first

The webhook route binds no tenant of its own until PR #1381 ships. Without it the module's writes
run under the `admin_bypass` RLS policy, unscoped across tenants — verification would "pass" while
writing in the wrong scope.

```bash
kelta api GET /api/modules | grep -q uiBundlePath && echo "worker has #1378+ ✓"
```

Confirm the deployed worker tag is at or past `main-01cf091` (the merge of #1381):

```bash
kubectl get pods -n emf -o custom-columns='N:.metadata.name,I:.spec.containers[0].image' --no-headers | grep worker
```

✅ Satisfied as of 2026-08-30: worker pods run `main-01cf091`, rolled 01:25Z.

## 1. Build the module

```bash
mvn package -f kelta-modules/billing-jar/pom.xml
shasum -a 256 kelta-modules/billing-jar/target/kelta-module-billing-1.0.0.jar
```

Last known-good build, from `01cf0911` (the merge of #1381):
`2ea8a8a8f81b513c83833977252fa583d94b30cbbc41c6ac6983d3b42c8a8c8c`
Re-derive it rather than trusting this line — the JAR changes whenever the module does.

Sanity-check the JAR before signing something incomplete:

```bash
unzip -l "$JAR" | grep -E 'kelta-module.json|ui-bundle.js|BillingModule.class'
unzip -p "$JAR" kelta-module.json | python3 -c "import sys,json;m=json.load(sys.stdin);print(len(m['actionHandlers']),'handlers',len(m['collections']),'collections')"
# expect: all three present, and "5 handlers 5 collections"
```

## 2. 🔑 Sign the JAR

Use the **same Ed25519 private key** whose public half is registered as `2026-h2` — the one that
signed `<tenant-slug>-ridb`. A different key installs as stubs (see the warning above).

On this machine that key is `~/.<tenant-slug>/module-signing/module-signing-key.pem`; its public half
matches the registered `2026-h2` anchor exactly. Confirm before signing rather than after:

```bash
diff <(grep -v 'BEGIN\|END' ~/.<tenant-slug>/module-signing/module-signing-public.pem | tr -d '\n') \
     <(kelta api GET /api/modules/signing-keys | python3 -c "import sys,json;print(json.load(sys.stdin)['data'][0]['attributes']['publicKeyPem'])" | grep -v 'BEGIN\|END' | tr -d '\n') \
  && echo "key matches the registered anchor"
```

```bash
JAR=kelta-modules/billing-jar/target/kelta-module-billing-1.0.0.jar
openssl pkeyutl -sign -rawin -inkey ~/.<tenant-slug>/module-signing/module-signing-key.pem -in "$JAR" \
  | base64 | tr -d '\n' > /tmp/billing.sig
```

`-rawin` is required: Ed25519 signs the message itself, and signing a digest instead produces a
signature the verifier rejects. `scripts/sign-ridb-module.sh` in `<tenant-slug>-web` is the proven
equivalent for the other module — worth reading if this ever misbehaves.

`tr -d '\n'` is not optional. The verifier uses Java's **basic** Base64 decoder, which rejects
embedded newlines, and `base64` wraps at 76 columns on Linux (macOS does not). Without it the
install fails on some machines and not others.

Keep the private key out of the repo and out of any agent session.

## 3. Install

The CLI has no `modules` command and `kelta api` cannot send multipart, so this one call is raw
curl and needs a PAT of its own. 🔑 Mint one and keep it in your shell only:

```bash
kelta token create --name billing-install   # full token is shown exactly once
export KELTA_PAT='klt_...'                  # paste it here, not into a file
```

```bash
curl -sS -X POST https://<api-host>/api/modules/install-jar \
  -H "Authorization: Bearer $KELTA_PAT" \
  -H "X-Tenant-Slug: <tenant-slug>" \
  -F "manifest=@kelta-modules/billing-jar/src/main/resources/kelta-module.json" \
  -F "jar=@kelta-modules/billing-jar/target/kelta-module-billing-1.0.0.jar" \
  -F "signature=$(cat /tmp/billing.sig)"
```

Revoke it when you are done: `kelta token list` then `kelta token revoke <id>`.

A `403` means the signature did not verify — fix it here rather than continuing, or you get stubs.

Then enable it:

```bash
kelta api POST /api/modules/kelta-billing/enable
```

## 4. 🚨 Gate: prove it is NOT running as stubs

An action handler is not callable on its own — it is a Task node's executable unit, so it runs via
a flow. Create a one-step probe flow once and reuse it:

```bash
kelta flows create --name billing-probe --definition '{
  "StartAt": "Resolve",
  "States": { "Resolve": { "Type": "Task", "Resource": "billing:resolve-entitlements", "End": true } }
}'
kelta flows execute <flowId>
kelta flows runs <flowId>          # then: kelta flows run <executionId> --steps
```

Read the step output:

- `"mode": "stub"` ⇒ **the JAR did not verify.** Stop and re-check step 2. The module will still
  say ACTIVE — that is the trap this step exists to catch.
- A real result (`planCode`/`entitlements`, or `No calling member` when run without an actor) ⇒
  real module code is loaded.

Also confirm the five collections were provisioned:

```bash
kelta api GET /api/collections | grep -o 'billing_[a-z_]*' | sort -u
# expect: billing_customers billing_entitlement_rules billing_passes billing_plans billing_subscriptions
```

## 5. 🔑 Create the Stripe credential

In the admin UI (**Setup → Credentials → New → type `stripe`**, name **exactly** `stripe`):

| Field | Value |
|---|---|
| `secretKey` | your Stripe **test-mode** `sk_test_…` |
| `webhookSecret` | the endpoint signing secret from step 6 (`whsec_…`) |
| `allowedReturnOrigins` | e.g. `https://<portal-host>` |

Enter these yourself in the UI — never paste them into a terminal, a file, or an agent session.
Use **test mode** for verification; a real charge is not required to prove the path.

`allowedReturnOrigins` is enforced on scheme + host + port. Omit it and every checkout is rejected
(fail-closed, by design).

## 6. 🔑 Point Stripe at the webhook

Stripe Dashboard → Developers → Webhooks → Add endpoint:

```
https://<api-host>/api/modules/webhooks/<prod-tenant-id>/kelta-billing
```

Events: `checkout.session.completed`, `customer.subscription.created`,
`customer.subscription.updated`, `customer.subscription.deleted`.

Copy the signing secret back into the credential's `webhookSecret` (step 5).

Sanity-check the negative — an unsigned request must never be accepted:

```bash
curl -s -o /dev/null -w '%{http_code}\n' -X POST \
  https://<api-host>/api/modules/webhooks/<prod-tenant-id>/kelta-billing \
  -H 'Content-Type: application/json' -d '{}'
```

| Response | Meaning |
|---|---|
| **404** | Nothing to dispatch to — module not installed or not ACTIVE. This is the expected answer *before* step 3, and was confirmed live on 2026-08-29. |
| **401** | The module received it and rejected the signature. Expected *after* install. |
| **2xx** | 🚨 Signature verification is not happening. Stop. |

A `404` before install also proves the route is reachable **without authentication** — the gateway
is not rejecting it, the dispatcher is.

## 7. Seed plans

```bash
kelta api POST /api/billing_plans -d '{"code":"FREE","name":"Free","kind":"DEFAULT","active":true,"entitlements":{"watches":1}}'
kelta api POST /api/billing_plans -d '{"code":"PRO","name":"Pro","kind":"SUBSCRIPTION","active":true,"stripePriceId":"price_...","entitlements":{"watches":25}}'
```

`stripePriceId` must match a real test-mode price, or checkout returns "Plan is not available for
purchase".

## 8. Exercise the path

| Check | How | Expected |
|---|---|---|
| Checkout | `billing:create-checkout-session` with `planCode=PRO` + allowed URLs | a `checkout.stripe.com` URL |
| Pay | Stripe test card `4242 4242 4242 4242` | redirected to `successUrl` |
| Webhook | Stripe Dashboard → webhook attempts | `200`, not 401/404/500 |
| Mirror | `kelta api GET /api/billing_subscriptions` | one row, `status: active`, `currentPeriodEnd` **non-null** |
| Entitlements | `billing:resolve-entitlements` | `planCode: PRO`, `watches: 25` |
| Portal | `billing:create-portal-session` | a `billing.stripe.com` URL |
| Expiry | `billing:expire-passes` | `{"expired":0,"batchFull":false}` on a clean tenant |

**`currentPeriodEnd` is the one to look at closely.** Stripe moved it from the subscription onto
each item in `2025-03-31.basil`, and webhooks render with the *account's* API version, not the one
the client pins — so this field silently lands null if the payload shape differs from what the
parser expects. A null here is a real bug, not a cosmetic gap.

## 9. Quota hook

```bash
kelta api POST /api/billing_entitlement_rules \
  -d '{"collectionName":"watches","limitKey":"watches","appliesTo":"ALL","active":true,"message":"Upgrade to add more watches."}'
```

Create watches past the limit as a member — the create should be rejected with that message.

⚠️ Use `appliesTo: ALL`. **`PORTAL` rules are skipped entirely by the module** — it cannot read the
gateway's `X-User-Type` to tell staff from members. If you rely on `PORTAL` rules today, that is a
behaviour change to decide on before any cutover, not a detail.

## 10. Record the outcome

Update `status.md` and the module README either way. If it passes, the remaining cutover blocker is
the `PORTAL`-rule difference and the weaker webhook idempotency documented in the README — both
decisions, not bugs.

## Rollback

```bash
kelta api POST /api/modules/kelta-billing/disable   # unregisters handlers + hooks immediately
kelta api DELETE /api/modules/kelta-billing         # uninstall; collections and data are KEPT
```

Disable is enough to stop the module affecting anything: it removes the wildcard quota hook and
every action handler. Uninstall deliberately leaves the collections and their rows behind.

## Email → UUID rewrite of `userId` (KLT-790, one-off per tenant)

Until KLT-790, `ModuleHttpController` handed module routes the raw `X-User-Id` header, which the
gateway fills with the caller's **email**. Checkout stamped that email on Stripe
(`client_reference_id`, `metadata[userId]`, `subscription_data[metadata][userId]`), so the webhook
wrote it into `billing_customers.userId`, `billing_subscriptions.userId` and
`billing_passes.userId`. `EntitlementResolver` and the quota hook look members up by **platform
user UUID**, so those rows were invisible: a paying member resolved to the DEFAULT plan.

After the fix, module routes receive the UUID, and the webhook ignores a non-UUID
`client_reference_id` / `metadata.userId` (Stripe replays the old email on every renewal) and
falls back to the `billing_customers` mapping by Stripe customer id. That fallback only helps once
the mapping itself holds the UUID — so existing rows must be rewritten, **`billing_customers`
first**. Until it is, renewal events for a legacy customer are logged
(`maps to a non-UUID member id`) and skipped rather than written under the email.

Run as a tenant admin against the tenant (`kelta-cli-as.sh <name> …` from the fleet), after the
fix is deployed:

```bash
# 1. Every row whose userId is an email, per collection — customers FIRST.
for c in billing_customers billing_subscriptions billing_passes; do
  kelta api GET "/api/$c?filter[userId][contains]=%40&page[size]=200" \
    | jq -r --arg c "$c" '.data[] | [$c, .id, .attributes.userId] | @tsv'
done > email-rows.tsv

# 2. For each row: resolve the member's platform user by email IN THE SAME TENANT, then rewrite.
while IFS=$'\t' read -r c id email; do
  uuid=$(kelta api GET "/api/users?filter[email][ieq]=$(jq -rn --arg e "$email" '$e|@uri')" \
    | jq -r '.data | if length == 1 then .[0].id else empty end')
  if [ -z "$uuid" ]; then echo "SKIP $c/$id: no unique user for $email" >&2; continue; fi
  kelta records update "$c" "$id" --data "{\"userId\":\"$uuid\"}" --yes
done < email-rows.tsv
```

A `SKIP` line means no single platform user has that email in the tenant (deleted member, typo);
decide those by hand — do not guess an owner.

**Done when** every collection returns zero rows:

```bash
for c in billing_customers billing_subscriptions billing_passes; do
  printf '%s ' "$c"
  kelta api GET "/api/$c?filter[userId][contains]=%40&page[size]=1" | jq '.data | length'
done
# expect: billing_customers 0 / billing_subscriptions 0 / billing_passes 0
```

Then confirm the effect, not just the data: `billing:resolve-entitlements` (or
`GET /api/modules/kelta-billing/x/me`) as the paying member returns the paid `planCode`, not
DEFAULT.

---

# Outcome — 2026-08-31: PASSED

> **Reinstalled 2026-08-31 after #1391** (subscription metadata) and #1393 merged: JAR rebuilt from
> `83e0845d`, re-signed, uninstalled + reinstalled + enabled, collections and rows preserved.
> Re-gated (`resolve-entitlements` → `PRO`/`watches: 25`, not stubs) and a fresh checkout session
> created — Stripe **accepted** `subscription_data[metadata]`, which it rejects when malformed, so
> the fix is confirmed against the live API and not only the stub server in the unit test.

Run against real Stripe **test-mode** keys on the sandbox tenant
`<sandbox-tenant-id>` (`<sandbox-slug>`), worker at
`main-0064fa2` (JVM image, `/app/app.jar`).

| Check | Result |
|---|---|
| Not running as stubs (step 4 gate) | real handler output, never `"mode": "stub"` |
| Five collections provisioned | `billing_{customers,entitlement_rules,passes,plans,subscriptions}` |
| Checkout | `https://checkout.stripe.com/c/pay/cs_test_…` |
| Payment (`4242…`) | redirected to `successUrl` |
| Webhook signature (negative) | unsigned `POST` → `401` |
| `checkout.session.completed` | customer mirrored → ``cus_…`` |
| `customer.subscription.*` | subscription mirrored, `status: active`, plan `PRO` |
| `currentPeriodEnd` | **`2026-09-30T00:59:51Z` — non-null**, parsed correctly |
| Entitlements | `{"planCode":"PRO","entitlements":{"watches":25},"subscriptionStatus":"active"}` |
| Portal | `https://billing.stripe.com/p/session?…` |
| Expiry | `{"expired":0,"batchFull":false}` |
| Quota hook | limit lowered to 1 → 1st create OK, 2nd rejected `400 beforeSaveHook: "Upgrade to add more watches."` |

The quota check deliberately used a throwaway collection (`quota_probe`), not a billing one: the
claim being tested is that a **runtime-loaded module's wildcard hook enforces on an arbitrary
tenant collection**. Sandbox state (PRO `watches`, probe rule, probe collection) was restored after.

## Three real bugs this found, none visible from unit tests

1. **Tenant slug not bound on webhook dispatch** (#1390). `RuntimeModuleManager` bound only the
   tenant *id*; `PhysicalTableStorageAdapter#getTableRef` resolves the schema from
   `TenantContext.getSlug()`, so a null slug fell back to the public schema and every module write
   failed with `relation "billing_customers" does not exist`. The original test asserted
   `TenantContext.get()` — the mechanism, not the outcome — so it passed against the bug.
2. **Handlers read the wrong input level** (#1390). `TaskStateExecutor` passes the whole state
   envelope as `resolvedData`, so `resolvedData.get("planCode")` found nothing and checkout failed
   with `"planCode is required"` against a request that supplied it. Fixed by `ActionInputs`.
3. **Metadata stamped on the session, not the subscription** (#1391). Stripe does not copy session
   metadata onto the subscription, so `customer.subscription.*` arrived with no `userId` and fell
   back to the order-dependent customer mapping — `"no resolvable member"`, live. Stripe does not
   guarantee delivery order, so this drops subscriptions intermittently, not deterministically.

## Installing: address the tenant by URL path, not by header

`POST /api/modules/install-jar` is multipart, which `kelta api` cannot send, so install is raw
`curl` + a PAT from `kelta token create`. **The slug goes in the URL path, not an
`X-Tenant-Slug` header**:

```
https://<api-host>/<tenant-slug>/api/modules/install-jar
```

This is what the SDK does (`KeltaClient.ts`: `baseUrl = ${rawBase}/${tenantSlug}`), and it is what
the tenant-slug extraction filter reads. With the header form instead, the tenant never resolves,
so `UserIdentityResolutionFilter` returns early without populating `profileId` and
`RouteAuthorizationFilter` rejects the request `403 "User identity not resolved"` — on *every*
route, which makes it look like a broken token rather than a wrong URL.

Reinstalling over an existing install returns `409`; uninstall first. Uninstall keeps the
collections and their rows (verified: plan/customer/subscription counts unchanged across a
reinstall), so this is safe on a tenant with real data.

## Cutover blockers (decisions, not bugs)

Unchanged from the README: `appliesTo=PORTAL` rules are skipped by the module (it cannot read
`X-User-Type`), entitlements are uncached, pass expiry is flow-scheduled rather than `@Scheduled`,
and webhook idempotency converges on redelivery rather than claiming each event id transactionally.

---

# Prod rollout — 2026-08-31

Installed and ACTIVE on the `<tenant-slug>` tenant (`<prod-tenant-id>`), worker
`main-1480dff` (JVM image).

| Check | Result |
|---|---|
| Service published | `EntitlementProvider -> ModuleEntitlementProvider` on all 3 pods |
| Manager log | `5 real handlers, 1 hooks and 1 services` |
| Platform → module | `GET /api/billing/me` → `200`, plan `member`, `push, email`, 10 watches |
| Fallback / slug errors | 0 |

The `/api/billing/me` result is the proof, not the log lines: at the time, the member's plan and
subscription existed **only** in the module's collections, so a compiled-in fallback could not have
produced that answer. A compiled-in Spring controller resolved a member's entitlements out of a
signed, runtime-uploaded JAR.

## Install activates the module — `enable` does not

`install-jar` registers handlers, hooks and services immediately, on every pod. `status` stays
`INSTALLED` until `/enable`; that flag is bookkeeping, not a gate. On a tenant with existing data
this inverts the obvious order — see `playbooks.md` → "Installing onto a tenant that already has
data". Here the plans, customer and subscription were copied into the module's collections **first**
(additive; the compiled-in tables were never written to), and only then was the JAR installed;
`ModuleCollectionProvisioner` skips collections that already exist.

Had it gone the other way, every member would have resolved to no plan, and because
`AlertDispatchService` reads an empty channel entitlement as "this tenant isn't gating channels",
channel limits would have stopped applying — including paid SMS for free-tier members.

## What the rollout uncovered, none of it caused by the module

- **Two Stripe accounts.** The tenant's plans and API key point at the live account
  (``acct_…LIVE``); its then-existing customer and subscription lived in the **test**
  account (``acct_…TEST``). Prices and the credential were both re-pointed on 2026-08-10
  (plans 00:24, credential 00:28) and the customer/subscription — created 08-09 — were left behind.
  Hence `No such customer` on every "Manage billing" click. Those orphaned rows have since been
  deleted from both stores.
- **No webhook reached prod between 2026-08-10 and 08-30.** The subscription row was last touched
  08-09 16:40. Two stale endpoints in the test account still pointed at the prod tenant and failed
  100% on signature; they have been deleted. The live account's endpoint had **zero** deliveries
  ever, so its signing secret had never been exercised — it was rolled and the credential updated
  on 08-31.
- **A signing secret cannot be verified without a real event.** Stripe offers "Send test events"
  only in test mode, so a live endpoint's `whsec_` is unprovable until the first real transaction.
  Roll it and update the credential in one sitting; that is the only guarantee available.
- **Portal magic-link login is fine — read its audit log carefully.** Login tokens are single-use
  and expire in 15 minutes, and issuance is capped at **3 per 15 minutes per user**. When mail is
  slow, a person naturally clicks several links, including ones an earlier click already consumed;
  each stale click logs `PORTAL_LOGIN actor=unknown result=failure detail=invalid_or_expired_token`
  interleaved with the genuine successes. That interleaving looks like a re-verify defect and is
  not one: an uninterrupted request→verify pair logs a single success with nothing after it.
  Diagnose from a clean attempt, not from a burst.
