---
title: SCIM 2.0 provisioning
description: Provision users and groups from your identity provider over SCIM 2.0 — base URL, authentication, endpoints, filters, attribute mapping and limits.
section: security
order: 90
---

Kelta implements a SCIM 2.0 service provider so an identity provider (Okta, Microsoft Entra ID, OneLogin, …) can
create, update and deactivate users and manage groups without anyone touching the console.

## Base URL and authentication

```
https://api.example.com/scim/v2
```

`scim` is a reserved first path segment — there is **no tenant slug** in SCIM URLs. The tenant is derived from
the bearer token:

```
Authorization: Bearer <scim client token>
```

Tokens belong to a **SCIM client** registered for the tenant and are stored hashed. An invalid or inactive token
answers `401`. SCIM clients are issued by your Kelta operator today; there is no self-service page for minting one
yet.

Requests and responses use `application/scim+json` (plain `application/json` is accepted on input).

## Discovery

| Endpoint | Returns |
|---|---|
| `GET /scim/v2/ServiceProviderConfig` | supported features (below) |
| `GET /scim/v2/ResourceTypes` | `User` and `Group` |
| `GET /scim/v2/Schemas` | the core User and Group schemas |

Supported: `patch`, `filter` (max 1000 results). Not supported: `bulk`, `sort`, `etag`, `changePassword`.

## Users

`/scim/v2/Users` — `GET` (list), `GET /{id}`, `POST`, `PUT /{id}`, `PATCH /{id}`, `DELETE /{id}`.

| SCIM attribute | Kelta user |
|---|---|
| `userName` | `email` |
| `name.givenName`, `name.familyName` | `firstName`, `lastName` |
| `displayName` | derived (`firstName lastName`) |
| `emails[].value` | `email` |
| `active` | `status` (`true` → `ACTIVE`, `false` → `INACTIVE`) |
| `externalId` | stored on `username` |
| `locale`, `timezone` | `locale`, `timezone` (defaults `en_US`, `UTC`) |

- **Create** sets the user `ACTIVE` (or `INACTIVE` when `active: false`) with **no profile and no password**.
  An invitation email is sent unless the tenant has turned auto-invite off. Assign a profile in the console or
  API, or let SSO group mapping resolve one on the user's first login — until then the user can sign in but has no
  permissions.
- **PATCH** supports `replace`/`add` on `active`, `userName`, `name.givenName`, `name.familyName`, `externalId`,
  `locale`, `timezone`; unsupported paths are ignored.
- **DELETE deactivates** (`status: INACTIVE`) rather than removing the row, so audit history and record
  references stay intact.

## Groups

`/scim/v2/Groups` — same verbs. `displayName` maps to the group `name`, `externalId` to `oidcGroupName`,
`members[]` to `group-memberships` (users only). Groups created over SCIM are `source: SCIM`.

## Filtering and paging

```
GET /scim/v2/Users?filter=userName eq "ana@example.com"
GET /scim/v2/Users?filter=active eq "true" and name.familyName sw "Sa"&startIndex=1&count=100
```

Operators: `eq`, `ne`, `co`, `sw`, `ew`, `pr`; combine with `and` / `or`. Comparison operators (`gt`, `lt`, …)
are not supported. `startIndex` is 1-based; `count` defaults to 100 and is capped at 1000.

## Identity-provider setup

Point the IdP's SCIM connector at `https://api.example.com/scim/v2` with the bearer token, enable user and group
push, and map the attributes above. Test with `GET /scim/v2/ServiceProviderConfig` first — it needs the token and
confirms the tenant resolves.

Pair SCIM with [SSO](/docs/security/sso/) so provisioned users can sign in; JIT provisioning and SCIM coexist.
