---
title: API authentication and tenant resolution
description: The two credentials the API accepts, how the gateway resolves the tenant, which headers are trusted, and what a caller needs to reach the API at all.
section: api
order: 20
---

## Credentials

| Credential | Header | Obtain |
|---|---|---|
| JWT | `Authorization: Bearer eyJ…` | Kelta's OIDC provider — the app's session, a connected app's OAuth flow, or the CLI's browser login |
| Personal access token | `Authorization: Bearer klt_…` | *Profile → API tokens* in the app, `kelta token create`, or an administrator minting one for a service user |

Both authenticate a **user**: a PAT acts exactly as its owner. There is no anonymous access to `/api/**` except
the inbound-webhook endpoints documented under [Triggers](/docs/automation/triggers/).

The caller's profile must grant `API_ACCESS`; without it every `/api/**` call answers `403 API access not
permitted`, whatever the credential.

## Tenant resolution

The gateway resolves the tenant before authentication, in this order:

1. **Custom domain** — the request host matches a verified domain.
2. **Path prefix** — `https://api.example.com/<slug>/api/...`; the slug is stripped before forwarding.
3. **Header** — `X-Tenant-Slug: <slug>` or `X-Tenant-ID: <uuid>` on a bare `/api/...` path.

The JWT or PAT must belong to that tenant; a token from another tenant is rejected.

## Trusted and stripped headers

Client-supplied identity headers (`X-User-*`, `X-Forwarded-User`, `X-Geo-*`, `X-Cerbos-*`) are stripped at the
edge; the gateway stamps its own after authentication. Never rely on setting them. `X-Forwarded-For` is honoured
for rate-limit keys and the IP allowlist only as far as the deployment's trusted-proxy configuration allows.

## Identity introspection

```http
GET /api/me/identity      # { userId, email, profileId, userType, tenantId }
GET /api/me/permissions   # effective system permissions
```

`userId` is the canonical UUID to use in bodies that reference the current user.

## Realtime and MCP

- The WebSocket endpoint takes the JWT as a query parameter on the upgrade — see [Realtime](/docs/api/realtime/).
- MCP endpoints accept **only** PATs — see [MCP server overview](/docs/mcp/overview/).

## Errors

`401 UNAUTHORIZED` — missing, expired or revoked credential. `403` — authenticated but not permitted (the `detail`
names the action and collection). See [Error responses](/docs/api/errors/).
