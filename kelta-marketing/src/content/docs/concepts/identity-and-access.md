---
title: Users, profiles, groups and tokens
description: The identity model in one page — who a user is, what a profile grants, how groups and record shares widen access, and where tokens fit.
section: concepts
order: 30
---

This page is the conceptual map. Configuration detail lives under [Security & identity](/docs/security/authentication/).

## Users

A **user** belongs to exactly one tenant and has a `userType`:

- `INTERNAL` — staff who use the console and the app. They sign in with a password (plus MFA when required) or
  through a federated identity provider.
- `PORTAL` — external users (customers, patients, members) who sign in with a magic link or portal credentials and
  see only what has been shared with them.

A user's `status` is `PENDING_ACTIVATION`, `ACTIVE`, `INACTIVE` or `LOCKED`. Users are created by invitation, by
just-in-time provisioning on first SSO login, or by SCIM.

## Profiles grant permissions

Every user has one **profile**. A profile grants:

- **System permissions** — capabilities such as `API_ACCESS` (call the API at all), `VIEW_SETUP`,
  `CUSTOMIZE_APPLICATION`, `MANAGE_USERS`, `MANAGE_WORKFLOWS`, `MANAGE_REPORTS`, `VIEW_ALL_DATA` and
  `MODIFY_ALL_DATA`.
- **Object permissions** — create / read / edit / delete per collection.
- **Field permissions** — per field: visible, read-only, hidden or masked.

Eight profiles are seeded in every tenant: *System Administrator*, *Standard User*, *Read Only*, *Marketing
User*, *Contract Manager*, *Solution Manager*, *Minimum Access* and *Portal User*. You can edit them or create your
own.

## Groups

**Groups** collect users (and other groups) for sharing and assignment. Group types are `PUBLIC`, `QUEUE`
(assignable work queues, used by approval steps) and `SYSTEM`. Groups can be synchronised from an identity
provider's group claims.

## Most-permissive-wins, evaluated by Cerbos

Effective access is the union of what the profile grants, what groups grant and what has been shared with the user
directly. There is no deny rule that beats a grant. The decision is made by the Cerbos policy engine for every
API route, every record and every field, from policies the platform generates out of the tenant's metadata.

**Record shares** widen access to a single record for a user or group (`READ` or `EDIT`); they can never narrow
it.

## Tokens

Two credentials reach the API:

- A **JWT** issued by Kelta's OIDC provider to the app, the CLI's browser login, or a connected OAuth app.
- A **personal access token** (`klt_…`), created by a user for scripts, CI and MCP clients. A PAT acts as its
  owner: it has exactly the owner's profile and groups, nothing more, nothing less. Revoking it takes effect
  immediately.

→ [Personal access tokens](/docs/security/personal-access-tokens/), [API authentication](/docs/api/authentication/)
