---
title: Anonymous access (Guest profile)
description: Let unauthenticated callers reach a tenant's API through a profile named Guest — how to turn it on, what it is scoped to, the shared guest identity, caching, and a hardening checklist.
section: security
order: 55
---

A tenant can accept requests that carry **no credential at all** — a public form submission, a public read — by
creating a profile named `Guest`. Every unauthenticated request to that tenant then runs as the Guest profile and is
authorized exactly like any other caller. Nothing else changes: a request that sends an `Authorization` header is
authenticated as usual, and an invalid or expired token is still `401`, never downgraded to Guest.

## Off by default

Anonymous access exists only while the tenant has a profile named exactly `Guest` (case-sensitive). Without one, a
request with no `Authorization` header is `401 UNAUTHORIZED` with `detail` `Missing Authorization header`, exactly
as on any tenant that never configured it.

## Turning it on

1. Create a profile named `Guest` (Setup → Administration → Profiles, or `POST /api/profiles`).
2. Grant it the system permission `API_ACCESS`. The gateway checks `API_ACCESS` first and only then the object
   permission, so without it every guest request is `403 API access not permitted`.
3. Grant the minimum object permissions — typically `canCreate` on one collection and nothing else. The Guest
   profile starts with no permissions and Cerbos denies by default, so creating the profile alone grants nothing.

Permissions are granted the same way as for any profile (`profile-system-permissions`,
`profile-object-permissions`, `profile-field-permissions`) — see
[Profiles, permissions and record sharing](/docs/security/profiles-and-permissions/).

## Scope: the URL's tenant only

Guest access applies only to the tenant named by the **URL** — the `/{slug}/api/...` path prefix or a verified
custom domain. A tenant selected by an `X-Tenant-ID` or `X-Tenant-Slug` header on a bare `/api/...` path is a claim
with no credential behind it, so an anonymous request that names its tenant that way is refused with `401`, whether
or not the tenant has a Guest profile. See [Tenant resolution](/docs/api/authentication/#tenant-resolution).

## Identity: every guest is the same user

Every anonymous request runs as the user id `00000000-0000-0000-0000-000000000000` (the nil UUID). Records a guest
creates carry that value in `createdBy`, and every guest shares it — the platform cannot tell one anonymous caller
from another.

That matters for owner-scoped collections. A collection with `ownerScope=ALL` narrows every caller without
`VIEW_ALL_DATA` / `MODIFY_ALL_DATA` to the rows it owns — and for Guest that is every row any guest has created.
**Do not grant Guest create on an `ownerScope=ALL` collection**: anything one guest submits, the next guest can read
back. Prefer create-only access, with no `canRead`, on public intake collections.

## Changes take up to 10 minutes

The gateway caches each tenant's Guest-profile lookup — including the answer "this tenant has no Guest profile" —
for up to **10 minutes**. Creating, renaming or deleting the `Guest` profile can therefore take that long to take
effect. The worker's lookup endpoint keeps its own 10-minute cache behind it, so in the worst case allow up to 20
minutes. To shut anonymous access off quickly, remove the Guest profile's `API_ACCESS` grant instead: permission
grants and revocations are synced to Cerbos and evicted from every gateway's authorization cache by event, not by
this lookup cache.

## Worked example: a public contact form

A `contact-requests` collection that anyone may submit to and nobody anonymous may read. Grant the Guest profile
`API_ACCESS` and one `profile-object-permissions` row with `canCreate: true` and every other flag `false`.

The commands and responses below are **illustrative**: `my-tenant` stands in for your tenant slug and
`api.example.com` for your API host; they were not captured from a live tenant.

Submit with no `Authorization` header:

```bash
curl -X POST https://api.example.com/my-tenant/api/contact-requests \
  -H 'Content-Type: application/vnd.api+json' \
  -d '{"data":{"type":"contact-requests","attributes":{"name":"Ada","email":"ada@example.com","message":"Please call me back"}}}'
```

The record is created; its `createdBy` is `00000000-0000-0000-0000-000000000000`.

Reading the same collection anonymously is refused by the gateway's object-permission check, because the Guest
profile has no `canRead`:

```bash
curl https://api.example.com/my-tenant/api/contact-requests
```

```text
HTTP/1.1 403 Forbidden

{"errors":[{"status":"403","code":"FORBIDDEN","detail":"Insufficient permissions for read on contact-requests", …}]}
```

The same request with an `X-Tenant-Slug: my-tenant` header on `https://api.example.com/api/contact-requests` instead of
the path prefix is `401` with `detail` `Missing Authorization header`.

## Hardening checklist

- **Field-level security still applies.** Mark internal fields `HIDDEN` or `READ_ONLY` for Guest so a submission
  cannot set them — see [Field-level security and data masking](/docs/security/field-security-and-masking/).
- **Validate public input.** Add [validation rules](/docs/data-model/validation-rules/) for required fields, length
  and format on every collection Guest can write.
- **Rate limits.** Guest requests count against the tenant's daily API quota, and because every guest is one user
  they share a single per-user share of it. A burst of anonymous traffic can therefore use up most of the tenant's
  budget — watch it under [Governor limits](/docs/platform/governor-limits/) and
  [Rate limits](/docs/api/rate-limits/).
- **Audit.** Records created anonymously carry the nil UUID in `createdBy`; filter on it to review them. Changes to
  the Guest profile and its permissions are configuration changes — review them in the
  [audit logs](/docs/security/audit-log/).
- **Grant the minimum.** No `canRead`, `canEdit` or `canDelete` unless the data is meant to be public, no
  `VIEW_ALL_DATA` / `MODIFY_ALL_DATA`, and no admin permissions.
