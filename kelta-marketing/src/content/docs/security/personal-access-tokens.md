---
title: Personal access tokens
description: Bearer tokens for scripts, CI and AI agents — format, creation, listing and revocation, scoping through profiles, and usage metering.
section: security
order: 70
---

A personal access token (PAT) is a bearer credential that authenticates **as its owner**. It is not a separate
identity: a request made with a PAT has exactly the owner's profile, groups and shares. This is what the CLI, MCP
clients and integrations use.

## Format

`klt_` followed by 40 random characters. The full value is shown **once** at creation; the server stores only a
SHA-256 hash and the first characters (`tokenPrefix`) for recognition.

## Create

Self-service (the app: *Profile → API tokens*; CLI: `kelta token create --name ci --expires-in 90`):

```http
POST /api/me/tokens
{ "name": "ci", "expiresInDays": 90 }
```

`expiresInDays` is 1–365 (default 90). A user may hold at most 10 active tokens.

Administrators can mint a token **for another user** — typically a service user — with `MANAGE_USERS`:

```http
POST /api/admin/users/{userId}/tokens
{ "name": "catalog-feed", "expiresInDays": 365 }
```

(Setup → Administration → Users → *Mint token*; `kelta users token-create`.) This is audited as
`PAT_ADMIN_CREATED`, distinct from self-service `PAT_CREATED`.

## Use

```
Authorization: Bearer klt_...
```

Tenant resolution works as for any request: the `/<tenant>/api/...` path prefix, an `X-Tenant-Slug` /
`X-Tenant-ID` header, or a custom domain. See [API authentication](/docs/api/authentication/).

## List and revoke

```http
GET /api/me/tokens          # id, name, tokenPrefix, scopes, expiresAt, lastUsedAt, createdAt, requestCount
DELETE /api/me/tokens/{id}  # immediate — the hash is added to a revocation set every gateway consults
```

Only the owner can list their tokens; there is no admin listing of another user's tokens. `kelta token list|revoke`,
`kelta auth logout --revoke`.

## Scoping a token: profiles, not scopes

The `scopes` attribute is informational. **What a PAT can do is what its owner's profile allows.** To build a
narrowly scoped integration token, create a service user with a purpose-built profile:

1. Profile with `API_ACCESS` and nothing else.
2. Object permission `canRead` on the one collection (no create/edit/delete).
3. Service user on that profile; mint a PAT for it as an administrator.

Reads succeed; every write and every other collection answers `403`. The full recipe with a verified transcript is
in [PAT-based read-only API access](/docs/reference/api-access/).

## Usage metering

Every authenticated request with a PAT — allowed or refused — increments its lifetime `requestCount`, readable by
the owner on `GET /api/me/tokens`. This is independent of the tenant's daily `apiCallsPerDay` governor budget,
which every request also counts against.

## Caveats

- `lastUsedAt` is not currently maintained; rely on `requestCount`.
- Deactivating the owner does not invalidate an already-cached token instantly (the gateway caches identity for a
  few minutes). Revoke the token, or remove `API_ACCESS` from the profile, for immediate effect.
- MCP endpoints accept **only** PATs; JWTs are rejected there.
