# Cutover runbook: JAR billing → Rust billing

Moves one tenant from the Java JAR build of `kelta-billing` (`kelta-modules/billing-jar`) to the
Rust external service (`kelta-modules/billing`). Same module id, same five collections, same data.
The data does not move: the JAR's uninstall keeps every collection, and the Rust install adopts
them.

> **Status: not executed on any production tenant.** Run it only when the owner says so. Rehearse
> it first on a sandbox tenant with Stripe **test-mode** keys ([`VERIFICATION.md`](VERIFICATION.md) §4).

**Steps marked 🔑 handle secrets or signing keys. Do not delegate them to an agent.** Keep every
export this runbook makes **outside the repository**: it holds member ids.

## What changes for the tenant

| Area | Before (JAR) | After (Rust) | Action needed |
|---|---|---|---|
| Flow action keys | `billing:<key>` | `kelta-billing:<key>` | Update every flow that calls one (step 5). |
| Pass expiry | A tenant flow runs `billing:expire-passes` | Built-in hourly schedule | Delete that flow (step 5). |
| `appliesTo=PORTAL` quota rules | Skipped | Enforced for portal members | Decide per rule (step 0). |
| Read access | In-process | `VIEW_ALL_DATA` on the module's service user | Approve it on the install review. |
| Stripe credential | Resolved in-process | Read per request over the module credential endpoint, audited | None: the same `stripe` credential. |
| Webhook URL | `/api/modules/webhooks/<tenantId>/kelta-billing` | the same | None. |
| Member routes | `/api/modules/kelta-billing/x/{plans,me,checkout-sessions,portal-sessions}` | the same paths and shapes | None. |
| Stripe `Idempotency-Key` on flow actions | Random | Derived from the platform's step key | None. |
| Pass grant | On `checkout.session.completed` | Only once paid (`payment_status` `paid`/`no_payment_required`); a delayed payment grants on `checkout.session.async_payment_succeeded` | Subscribe the webhook endpoint to the two `async_payment` events (step 0). |
| One pass per checkout session | An existence check | The check plus a unique `stripeCheckoutSessionId` | Check for duplicates (step 0), then set the flag (step 4b): the install does not change adopted fields. |
| Out-of-order subscription events | Applied in arrival order | Older than the row's `lastStripeEventAt`: ignored; a `canceled`/`incomplete_expired` row is never moved out | Add `lastStripeEventAt` if the install did not (step 4b). |
| An event whose `metadata.tenantId` names another tenant | Applied | Acknowledged and ignored, logged at WARN | None. |

## The gap

Between step 3 (JAR uninstalled) and step 4 (Rust installed) the tenant has no billing module:

- every member resolves **no entitlements**: watch limits lift, and SMS alerts are not sent;
- the quota hook does not run;
- the member routes answer `404`;
- Stripe webhooks answer `404`. Stripe retries a failed delivery for up to 3 days, so nothing is
  lost; resend them in step 6 to settle them at once.

Keep the gap to minutes: prepare everything in steps 0–2 first, and run steps 3–4 back to back at a
quiet time.

## Preconditions

- [ ] The emf PR for part 9f is merged and its worker is deployed (`kubectl -n emf get pods` shows
      only the new tag, no old pod draining).
- [ ] The homelab-argo `kelta-billing` app is synced; the pod is `Running` on a `main-<sha>` tag.
- [ ] The NetworkPolicy checks in [`VERIFICATION.md`](VERIFICATION.md) §3 pass in both directions.
- [ ] The worker's `KELTA_MODULE_ALLOWED_HOSTS` is exactly `kelta-billing.kelta-billing.svc.cluster.local`.
- [ ] The worker's issuer equals the module's `KELTA_MODULE_ISSUER`
      (`https://<api-host>/api/modules`), and `GET https://<api-host>/api/modules/.well-known/jwks.json`
      answers a key set.
- [ ] The sandbox rehearsal (VERIFICATION.md §4) passed and is recorded.
- [ ] 🔑 **Rollback is ready:** the JAR is built and signed, and the signature verified, per
      [`../billing-jar/VERIFICATION.md`](../billing-jar/VERIFICATION.md) steps 1–2. Rollback then
      takes minutes instead of a build.

Set the CLI profile once:

```bash
export P='--profile <tenant-profile>'
SNAP=~/kelta-billing-cutover/$(date -u +%Y%m%dT%H%MZ); mkdir -p "$SNAP"
```

## Step 0 — Inventory (read-only)

```bash
kelta api GET /api/modules $P > "$SNAP/modules.json"
jq -r '.data[] | select(.attributes.moduleId=="kelta-billing") | .attributes | "\(.status) \(.version) baseUrl=\(.baseUrl)"' "$SNAP/modules.json"
# expect: ACTIVE 1.0.0 baseUrl=null   (the JAR; an external module has a baseUrl)

kelta api GET '/api/flows?page[size]=200' $P > "$SNAP/flows.json"
jq -r '.data[] | select(.attributes.definition | tostring | test("\"billing:")) | "\(.id) \(.attributes.name) active=\(.attributes.active)"' "$SNAP/flows.json"
# every flow listed here is updated in step 5

kelta api GET '/api/billing_entitlement_rules?page[size]=200' $P > "$SNAP/rules.json"
jq -r '.data[] | .attributes | select(.active == true) | "\(.appliesTo) \(.collectionName) \(.limitKey)"' "$SNAP/rules.json"
```

- Every active `PORTAL` rule starts to be enforced at cutover. For each one, decide with the tenant
  owner: keep it (newly enforced), change it to `ALL`, or deactivate it. Do that **after** step 6,
  so the comparison below stays like for like.
- Check that the `stripe` credential exists and has `secretKey`, `webhookSecret` and
  `allowedReturnOrigins` (`kelta api GET '/api/credentials?filter[name]=stripe' $P`; secrets are not
  returned).
- Check the plans: exactly one active `DEFAULT` plan, or know which one wins (the first by
  `sortOrder`, then `code`, in both builds).
- Stripe dashboard → the tenant's webhook endpoint: add `checkout.session.async_payment_succeeded`
  and `checkout.session.async_payment_failed` to its events. The JAR answers `200` to event types
  it ignores, so this is safe before the cutover.
- **Duplicate passes.** Step 4b makes `billing_passes.stripeCheckoutSessionId` unique, which fails
  while two passes share a session id. List any now:

  ```bash
  n=1; : > "$SNAP/passes.ndjson"
  while :; do
    page=$(kelta api GET "/api/billing_passes?page[size]=200&page[number]=$n&fields[billing_passes]=stripeCheckoutSessionId" $P)
    echo "$page" | jq -c '.data[] | {id, s: .attributes.stripeCheckoutSessionId}' >> "$SNAP/passes.ndjson"
    [ "$(echo "$page" | jq '.data | length')" -lt 200 ] && break
    n=$((n + 1))
  done
  jq -rs 'group_by(.s) | map(select(length > 1)) | .[] | "\(.[0].s): \(map(.id) | join(" "))"' "$SNAP/passes.ndjson"
  # expect no output
  ```

  Each line is one checkout session that minted more than one pass (a concurrent redelivery under
  the JAR). Decide with the tenant owner which pass stays. Set each other one's `status` to
  `EXPIRED` (only `ACTIVE` passes entitle) **and** its `stripeCheckoutSessionId` to
  `<session id>#dup<n>`, before step 4b. Deleting them also works, but loses the record.

## Step 1 — Baseline: the JAR's answers

Export the five collections:

```bash
for c in billing_plans billing_customers billing_subscriptions billing_passes billing_entitlement_rules; do
  kelta api GET "/api/$c?page[size]=200&sort=id" $P > "$SNAP/before-$c.json"
  echo "$c $(jq '.data | length' "$SNAP/before-$c.json")"
done
```

If any count is 200, page through the rest before going on.

**There is no admin API that resolves another member's entitlements**: every billing answer is for
the **calling** member. So the comparison has three parts:

1. **The data is unchanged** (step 6 re-exports and diffs). Both builds compute entitlements from
   these five collections only, with the same rules. Same data, same rules, same answer.
2. **Live answers for members you can sign in as.** Pick at least one free member and, if one
   exists, one paying member (a test account of the owner's). For each, signed in as that member:

   ```bash
   kelta api GET /api/modules/kelta-billing/x/me --profile <member-profile> | jq -S . > "$SNAP/before-me-<member>.json"
   kelta api GET /api/modules/kelta-billing/x/plans --profile <member-profile> | jq -S . > "$SNAP/before-plans.json"
   ```

3. **Every other member with billing rows, by hand.** List them:

   ```bash
   jq -r '.data[].attributes.userId' "$SNAP"/before-billing_{customers,subscriptions,passes}.json | sort -u
   ```

   For each, write down the expected plan: the plan of their newest subscription whose status is
   `active`, `trialing` or `past_due`, otherwise the DEFAULT plan, plus any pass that is `ACTIVE`
   with `expiresAt` in the future. A member with no rows gets the DEFAULT plan. This is the rule
   both builds implement (`src/entitlements.rs`; the JAR's `EntitlementResolver`).

Also record one behaviour the platform derives from entitlements, for a test member at a known
watch limit: `POST /api/watches` past the limit → `422` with the plan-limit message.

## Step 2 — Prepare the flow changes (no writes yet)

For each flow from step 0, prepare its new `definition` with every `"billing:` resource replaced
by `"kelta-billing:`. Mark the flow that only runs `billing:expire-passes` for deletion. Keep the
originals in `$SNAP/flows.json` for rollback.

## Step 3 — Uninstall the JAR (the gap starts)

```bash
kelta api DELETE /api/modules/kelta-billing $P --yes
```

No `planHash`: a JAR module's uninstall removes nothing, so all five collections and their rows
stay. Check: `kelta api GET /api/modules $P` no longer lists `kelta-billing`, and
`kelta api GET '/api/billing_plans?page[size]=1' $P` still answers rows.

## Step 4 — Install the Rust module by URL (the gap ends)

```bash
URL=http://kelta-billing.kelta-billing.svc.cluster.local:8080
kelta api POST /api/modules/plan $P --data "{\"baseUrl\":\"$URL\"}" > "$SNAP/plan.json"
jq '.data.attributes | {mode, blockers, grants, metadata}' "$SNAP/plan.json"
```

Review the plan before approving:

- `mode` is `INSTALL`, with no blockers. `INSTALLED_AS_JAR` means step 3 did not finish.
- Each of the five collections is `ADOPT` or `KEEP` (`KEEP` when the JAR install's provenance rows
  survive), never `CREATE`. A `CREATE` means the collection is missing: stop and investigate.
- The grants are the `stripe` credential, read/create/edit on the five collections, and
  `VIEW_ALL_DATA`, and nothing else.

```bash
HASH=$(jq -r '.data.attributes.planHash' "$SNAP/plan.json")
kelta api POST /api/modules/install $P --yes --data "{\"baseUrl\":\"$URL\",\"planHash\":\"$HASH\"}"
# expect "status": "ACTIVE"
```

## Step 4b — Add what the adopted collections lack

The install never changes a field that already exists, and it writes no field under an `ADOPT`
collection. So on a cutover `stripeCheckoutSessionId` stays non-unique, and `lastStripeEventAt`
exists only if `billing_subscriptions` was `KEEP`. Until this step the module still works: a
duplicate grant is caught by its existence check (not under a race), and without
`lastStripeEventAt` only the terminal-status rule orders subscription events.

```bash
kelta fields list billing_subscriptions $P --output json \
  | jq -r '.[] | select(.name == "lastStripeEventAt") | .id'
# no output: add it
kelta fields add billing_subscriptions $P --name lastStripeEventAt --type DATETIME --display-name 'Last Stripe Event'
```

Re-run the duplicate-pass check from step 0; it must print nothing. Then:

```bash
FID=$(kelta fields list billing_passes $P --output json \
  | jq -r '.[] | select(.name == "stripeCheckoutSessionId") | .id')
kelta fields update "$FID" $P --data '{"uniqueConstraint": true}'
kelta fields list billing_passes $P --output json \
  | jq '.[] | select(.name == "stripeCheckoutSessionId") | .uniqueConstraint'
# expect true
```

If the update fails, a duplicate slipped in since the check: resolve it as in step 0 and retry.
**Remaining race:** between step 4 and this command, two concurrent deliveries of one checkout
can still mint two passes. Run 4b straight after step 4.

## Step 5 — Update the flows

Apply the prepared definitions from step 2 (`kelta api PATCH /api/flows/<id> $P --data …`), and
delete the `expire-passes` flow. The module's own schedule now expires passes hourly.

## Step 6 — Verify against the baseline

```bash
for c in billing_plans billing_customers billing_subscriptions billing_passes billing_entitlement_rules; do
  kelta api GET "/api/$c?page[size]=200&sort=id" $P > "$SNAP/after-$c.json"
  diff <(jq -S '[.data[] | {id, attributes}]' "$SNAP/before-$c.json") \
       <(jq -S '[.data[] | {id, attributes}]' "$SNAP/after-$c.json") > /dev/null \
    && echo "$c unchanged" || echo "$c CHANGED — inspect"
done
```

A pass that expired between the two exports is the only expected change (`status` → `EXPIRED`,
with a new `updatedAt`).

- [ ] For each member from step 1 part 2: `me` and `plans` equal the baseline
      (`diff "$SNAP/before-me-<member>.json" <(… | jq -S .)` prints nothing).
- [ ] The watch-limit check from step 1 gives the same `422`.
- [ ] Stripe dashboard → the webhook endpoint: resend each delivery that failed during the gap,
      and the latest successful one. Each answers `200`. (A redelivery converges; it does not
      duplicate.)
- [ ] As a test member: `POST /api/modules/kelta-billing/x/checkout-sessions` returns a Stripe URL
      (do not pay), and `portal-sessions` returns a portal URL if the member has a customer.
- [ ] `kubectl -n kelta-billing logs deploy/kelta-billing --since=30m` shows no `ERROR` lines and
      no secret or token.
- [ ] `kelta api GET /api/modules $P` shows `kelta-billing` `ACTIVE` with the module's `baseUrl`.
- [ ] Step 4b is done: `billing_passes.stripeCheckoutSessionId` is unique and
      `billing_subscriptions.lastStripeEventAt` exists.

Then apply the PORTAL-rule decisions from step 0, and record the cutover in
[`VERIFICATION.md`](VERIFICATION.md) §4 (date, image tag, tenant placeholder, result).

## Rollback

Roll back when a step 6 check fails and the cause is not a one-line fix. The data never moved, so
rollback swaps the code back.

1. Uninstall the Rust module **without** a `planHash`, so nothing it adopted is removed:

   ```bash
   kelta api DELETE /api/modules/kelta-billing $P --yes
   ```

2. 🔑 Install and enable the pre-signed JAR:
   [`../billing-jar/VERIFICATION.md`](../billing-jar/VERIFICATION.md) step 3. Its install skips
   the existing collections.
3. Restore each flow's original `definition` from `$SNAP/flows.json`, and re-create the
   `expire-passes` flow.

   The step 4b changes can stay: the JAR ignores `lastStripeEventAt`, and the unique flag only
   refuses a second pass for one session, which the JAR's own check already prevents.
4. Resend the Stripe deliveries that failed while billing was down.
5. Re-run step 6 against the baseline. The answers must match it again.

The Rust deployment can stay running after a rollback: nothing calls it once it is uninstalled.

## After the cutover

- The JAR build stays in `kelta-modules/billing-jar` for rollback until part 9g removes it, along
  with the JAR SPI.
- `.claude/docs/status.md` → the portal billing row: record which tenants run which build.
