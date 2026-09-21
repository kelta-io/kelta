---
title: Users, invitations, groups and delegated admin
description: The user record, inviting internal and portal users, groups and nested membership, and delegating limited user administration.
section: security
order: 40
---

## The user record

Users are records in the `users` system collection:

| Attribute | Values |
|---|---|
| `email` | login identifier (unique per tenant) |
| `userType` | `INTERNAL` (staff) or `PORTAL` (external) |
| `status` | `PENDING_ACTIVATION`, `ACTIVE`, `INACTIVE`, `LOCKED` |
| `profileId` | the one profile that grants permissions |
| `managerId` | optional manager lookup (used by approval `hierarchy` routing) |
| `firstName`, `lastName`, `username`, `locale`, `timezone` | profile data |
| `mfaEnabled`, `lastLoginAt`, `loginCount` | read-only status |

Writes to `users` and `group-memberships` require `MANAGE_USERS` (or `MODIFY_ALL_DATA`), whichever path they take —
console, JSON:API, batch operations or CLI. Setup → Administration → Users is the console surface.

## Inviting users

```http
POST /api/users
{ "data": { "type": "users", "attributes": { "email": "ana@example.com", "firstName": "Ana", "profileId": "<profile id>" } } }

POST /api/admin/users/{userId}/invite     # (re)send the invitation email
```

The invitation carries a one-time link; the user sets a password (and enrols MFA if required) and becomes
`ACTIVE`. Users created by SSO just-in-time provisioning or SCIM skip this step.

Portal users are invited with `POST /api/admin/users/portal-invite` and get the *Portal User* profile; the
`maxPortalUsers` governor limit applies.

CLI: `kelta users list|get|invite|portal-invite|reset-password|token-create|logins`; create with `kelta api POST /api/users`.

## Deactivating

Set `status: INACTIVE` (console *Deactivate*, or PATCH). The user can no longer log in and existing sessions stop
refreshing. Two things to know:

- A personal access token owned by the user may keep authenticating for up to the gateway's identity-cache window
  (a few minutes). To cut access immediately, revoke the token or remove `API_ACCESS` from the profile.
- Records the user created keep their `createdBy` reference; ownership is not reassigned automatically.

## Groups

`user-groups` collect users and other groups:

| Attribute | Values |
|---|---|
| `groupType` | `PUBLIC` (sharing), `QUEUE` (assignable work queue — approval steps can route to a queue), `SYSTEM` |
| `source` | `MANUAL` or synchronised from a provider (SCIM) |
| `oidcGroupName` | external identifier of a synchronised group |

Membership rows (`group-memberships`) have `memberType: USER` or `GROUP`, so groups nest. Effective membership is
resolved transitively when permissions and shares are evaluated.

There is no dedicated console page yet; manage groups through the Resource Browser (`/resources/user-groups`,
`/resources/group-memberships`), the JSON:API, or `kelta api`. Membership writes require `MANAGE_USERS` (or `MODIFY_ALL_DATA`).

## Delegated administration

Full administrators can let selected users manage *some* users without granting `MANAGE_USERS`:

1. Create a **delegated-admin scope** (Setup → Administration → Delegated admins, requires
   `MANAGE_DELEGATED_ADMINS`): the delegates (`delegatedUserIds`), the profiles they may assign
   (`manageableProfileIds`), and the switches `canCreateUsers`, `canDeactivateUsers`, `canResetPasswords`.
2. Delegates use the scoped endpoints under `/api/admin/delegated/*` (`GET /me` for their effective scope,
   `GET|POST /users`, `PATCH /users/{id}`, invite, reset password). The console shows them a filtered Users page.

Guard rails: a scope cannot include a profile that grants a privileged permission (no delegating admin-of-admins);
delegates cannot edit their own record, change email, manager or MFA, or move a user outside the manageable
profiles; scopes are re-checked on every request, so revoking one is immediate.
