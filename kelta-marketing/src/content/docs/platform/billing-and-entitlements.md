---
title: Billing and entitlements (module)
description: Portal billing is delivered by the kelta-billing runtime module — plans, Stripe checkout and portal, subscriptions and passes, entitlement rules and per-member quotas.
section: platform
order: 70
status: partial
---

Portal billing — letting external (portal) users buy plans and passes — ships as a **runtime module**
(`kelta-billing`) rather than compiled-in platform code, and is the reference implementation of the
[module SPI](/docs/platform/modules/). Install it only on tenants that sell to portal users.

## Concepts

| Collection (created by the module) | Holds |
|---|---|
| `billing_plans` | `kind` `SUBSCRIPTION`, `ONE_TIME` or `DEFAULT` (the free baseline), the Stripe price id, and the entitlements the plan grants |
| `billing_customers` | one Stripe customer per member |
| `billing_subscriptions`, `billing_passes` | coarse state and ids — never card data or amounts |
| `billing_entitlement_rules` | rules the module's before-save hook enforces as per-member quotas |

## Setup

1. Create a credential of type `stripe` named `stripe` with `secretKey`, `webhookSecret` and
   `allowedReturnOrigins` (checkout may only return to these origins).
2. Point a Stripe webhook at `https://api.example.com/<tenant>/api/modules/webhooks/<tenantId>/kelta-billing`
   subscribed to `checkout.session.completed` and `customer.subscription.created|updated|deleted`. The module
   verifies the HMAC over the raw body.
3. Create `billing_plans` rows with matching `stripePriceId`s.
4. Schedule the `billing:expire-passes` action in a scheduled flow — a module has no scheduler of its own.

## Member surface

The module serves the member endpoints under its authenticated route prefix:

| | |
|---|---|
| `GET /api/modules/kelta-billing/x/plans` | purchasable plans (safe fields only) |
| `GET /api/modules/kelta-billing/x/me` | the caller's plan, subscription, live passes and merged entitlements |
| `POST /api/modules/kelta-billing/x/checkout-sessions` | start a Stripe hosted checkout; returns the redirect URL |
| `POST /api/modules/kelta-billing/x/portal-sessions` | open Stripe's billing portal |

The same operations exist as flow actions (`billing:create-checkout-session`, `billing:create-portal-session`,
`billing:resolve-entitlements`, `billing:list-plans`, `billing:me`), and a *Billing plans* page-builder widget ships
in the module's UI bundle.

## Entitlements

The module publishes an entitlement provider to the platform, so features that gate on entitlements (alert
delivery, watch limits, quota hooks) resolve them from the module on tenants where it is installed. Entitlements
are resolved live (not cached); a failing module falls back to the platform default rather than widening access.

## Limitations

- Webhook idempotency relies on convergence (a pass is granted once per checkout session, subscriptions upsert by
  Stripe id) rather than a transactional event claim.
- Quota rules with `appliesTo: PORTAL` are skipped — a module cannot read the caller's user type; use
  `appliesTo: ALL`.
- Live verification against a real Stripe account is still owed by the platform team; run the module's
  verification runbook in your own environment before relying on it.
