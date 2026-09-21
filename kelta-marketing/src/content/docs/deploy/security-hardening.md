---
title: Security hardening for public traffic
description: The checks to make before exposing a deployment — unauthenticated paths, rate limits, the auth server's public surface, secrets, module signing, header trust and internal endpoints.
section: deploy
order: 70
---

## Know the unauthenticated surface

| Path | Service | Protection |
|---|---|---|
| `/actuator/health/*` | all | per-IP limit |
| `/api/webhooks/{flowId}` | gateway → worker | the flow id is the secret; per-IP limit; no signature check |
| `/api/modules/webhooks/{tenantId}/{moduleId}` | gateway → worker | the module verifies the signature; per-IP limit |
| `/api/webhooks/mail/**` | gateway → worker | inbound mail (optional feature); per-IP limit |
| `/portal/**` | auth | magic-link and signup pages; per-IP limits in the auth server; optional bot challenge |
| `/oauth2/**`, `/login/**`, `/saml2/**` | auth | standard OAuth/SAML endpoints |

Everything else under `/api/**` requires a credential and `API_ACCESS`.

## Gateway limits

- `RATE_LIMIT_IP_PATHS` — per-IP budgets on the public prefixes above. Tune per endpoint; each prefix is its own
  bucket.
- The tenant window is the tenant's `apiCallsPerDay`; `RATE_LIMIT_USER_SHARE` caps one member. Tighten the
  share for tenants with many members.
- `RATE_LIMIT_EXEMPT_CIDRS` bypasses **both** limiters. Never list private ranges in production; keep it to
  monitoring probes and known webhook egress ranges.
- Limiters are Redis-backed and fail **open** when Redis is unreachable — monitor Redis.

## Auth server

- `DIRECT_LOGIN_ENABLED=false`.
- `KELTA_AUTH_RATE_LIMIT_TRUSTED_PROXY_COUNT` must equal the number of proxies in front of the auth server (`0` if
  none); otherwise a client can forge `X-Forwarded-For` and get a fresh limit bucket per request.
- Enable the proof-of-work bot challenge on signup and magic-link requests for public portals
  (`KELTA_AUTH_BOT_CHALLENGE_ENABLED=true` with one shared HMAC key across pods).
- Require MFA for internal users ([MFA policy](/docs/security/mfa-and-password-policy/)).

## Tenant IP allowlists

Offer tenants the IP allowlist ([Authentication](/docs/security/authentication/#tenant-ip-allowlist)). Set
`IP_ALLOWLIST_TRUST_XFF` only when the gateway sits behind a proxy you control.

## Secrets

- No signing or HMAC secret has a default; generate them once and store them in a secret manager.
- Rotating `KELTA_PUSH_VAPID_*` invalidates every browser push subscription; rotating `CAMPAIGN_TRACKING_SECRET`
  invalidates links in already-sent email. Plan rotations.
- `KELTA_ENCRYPTION_KEY` cannot be rotated without re-encrypting; back it up.

## Modules

`KELTA_MODULES_SIGNING_REQUIRED=true`. A module runs arbitrary code in the worker and its UI bundle runs in the
administrator's browser session; the signature is the whole trust model. Retire the legacy platform-wide key
once per-tenant keys are in place.

## Header trust

The gateway strips client-supplied identity headers before anything reads them, and stamps its own. Deploy the
worker, auth, ai and mcp services **only** behind the gateway or on a private network; a client that can reach
the worker directly could supply those headers itself.

## Internal endpoints

`/internal/**` on every service is protected by `KELTA_INTERNAL_TOKEN` and must not be routed from the public
ingress. Actuator endpoints beyond health should not be public.

## Checklist

- [ ] TLS everywhere; HSTS at the edge
- [ ] `CORS_ALLOWED_ORIGIN_PATTERN` set to your app origin, not `*`
- [ ] `DIRECT_LOGIN_ENABLED=false`, trusted-proxy count correct
- [ ] `KELTA_MODULES_SIGNING_REQUIRED=true`
- [ ] Only the gateway, auth and ui are reachable from the internet
- [ ] Secrets in a secret manager; `KELTA_ENCRYPTION_KEY` backed up
- [ ] Database role without `BYPASSRLS`
- [ ] Redis and NATS not exposed
- [ ] Flow-webhook URLs treated as secrets; module webhooks verify signatures
- [ ] Alerts on `kelta.gateway.auth.failures`, `authz.denied` and `ratelimit.exceeded`
