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

## Client IP and trusted proxies

Set `KELTA_SECURITY_TRUSTED_PROXIES` on the gateway and the auth server to the pod CIDR of your ingress
controller (comma-separated CIDRs or bare IPs, e.g. `10.42.0.0/16`). Kelta then believes `X-Forwarded-For` and
`X-Real-IP` only when the connection comes from one of those addresses, and takes the client to be the right-most
hop that is not a trusted proxy. That address is what the security audit log, login history
(`login_history.source_ip`), geolocation, rate limits and tenant IP allowlists all use; the gateway passes it to
the worker in a header it sets itself.

Your ingress must preserve the client address (for example `externalTrafficPolicy: Local`, or PROXY protocol
from the load balancer), or every request will look like it came from the node.

Left empty (the default), Kelta keeps its earlier behaviour so upgrades don't change anything: with
`IP_ALLOWLIST_TRUST_XFF=true` the left-most `X-Forwarded-For` hop is treated as the client and a tenant
allowlist admits a request if **any** forwarded hop matches. Both are client-controlled, so a caller can name an
allowed address in `X-Forwarded-For` to get past an allowlist, and can put any address they like in the audit
trail. `IP_ALLOWLIST_TRUST_XFF=true` without trusted proxies stays spoofable; setting it to `false` closes that but
makes every client look like the ingress.

## Auth server

- `DIRECT_LOGIN_ENABLED=false`.
- Set `KELTA_SECURITY_TRUSTED_PROXIES` (above). Without it, `KELTA_AUTH_RATE_LIMIT_TRUSTED_PROXY_COUNT` must equal
  the number of proxies in front of the auth server (`0` if none); otherwise a client can forge
  `X-Forwarded-For` and get a fresh limit bucket per request.
- Enable the proof-of-work bot challenge on signup and magic-link requests for public portals
  (`KELTA_AUTH_BOT_CHALLENGE_ENABLED=true` with one shared HMAC key across pods).
- Require MFA for internal users ([MFA policy](/docs/security/mfa-and-password-policy/)).

## Tenant IP allowlists

Offer tenants the IP allowlist ([Authentication](/docs/security/authentication/#tenant-ip-allowlist)) only once
`KELTA_SECURITY_TRUSTED_PROXIES` is set; then the allowlist judges the one resolved client address and forwarded
headers from anyone else are ignored.

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
- [ ] `DIRECT_LOGIN_ENABLED=false`
- [ ] `KELTA_SECURITY_TRUSTED_PROXIES` set to the ingress pod CIDR on the gateway and the auth server
- [ ] `KELTA_MODULES_SIGNING_REQUIRED=true`
- [ ] Only the gateway, auth and ui are reachable from the internet
- [ ] Secrets in a secret manager; `KELTA_ENCRYPTION_KEY` backed up
- [ ] Database role without `BYPASSRLS`
- [ ] Redis and NATS not exposed
- [ ] Flow-webhook URLs treated as secrets; module webhooks verify signatures
- [ ] Alerts on `kelta.gateway.auth.failures`, `authz.denied` and `ratelimit.exceeded`
