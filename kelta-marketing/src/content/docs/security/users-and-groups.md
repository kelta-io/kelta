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
| `status` | `PENDING_ACTIVATION`, `ACTIVE`, `INACTIVE`, `LOCKED`, `ANONYMIZED` (see [Anonymizing a user](#anonymizing-a-user)) |
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

## Anonymizing a user

`DELETE /api/users/{id}` is refused (409 `REFERENCED_RECORD`) for any user who created or owns records. To erase
such a user's personal data — a "delete my account" request, GDPR/CCPA-style erasure — anonymize them instead. The
user row and its id stay, so every record that points at the user keeps pointing at the same id.

```http
POST /api/admin/users/{id}/anonymize      # requires MANAGE_USERS
```

Answers `200 {"userId": "…", "status": "ANONYMIZED", "alreadyAnonymized": false}`. Anonymizing an anonymized user
answers 200 again with `alreadyAnonymized: true` and changes nothing. A user id from another tenant answers 404; the
`system` user and your own id answer 400.

From a flow, use the `ANONYMIZE_USER` task with the user id in its parameters:

```json
{ "Type": "Task", "Resource": "ANONYMIZE_USER", "Parameters": { "userId": "${$.input.userId}" }, "End": true }
```

The step fails unless the flow's user is an active user whose profile grants `MANAGE_USERS`. Its output is
`{"userId": "…", "alreadyAnonymized": true|false}`.

**Erased** — in one transaction:

| What | Becomes |
|---|---|
| `email` | `deleted+<id>@invalid` (unique, never deliverable) |
| `username`, `firstName`, `lastName`, phone number, email-verification token | cleared |
| `settings` | `{}` |
| `status` | `ANONYMIZED` |
| password, TOTP secret, MFA recovery codes | rows deleted (`mfaEnabled` becomes `false`) |
| saved list views, favorites and recents (UI preferences), portal magic-link tokens, registered push devices | rows deleted |
| personal access tokens | revoked |
| sign-in sessions and refresh tokens | ended; access tokens already issued are rejected by the gateway |

**Kept:**

- the user's `id`, `userType`, `profileId`, `managerId`, `locale`, `timezone`, `createdAt`, sign-in counters;
- the audit history — setup-audit, login-history and field-history rows, including values they recorded before the
  anonymize;
- every record the user created, updated or owns — `createdBy`, `updatedBy` and owner fields still hold the same id.
  Erasing *tenant data* about the person (their orders, messages, documents) is up to the tenant: delete or scrub
  those records first, for example in the same flow before the `ANONYMIZE_USER` step.

`ANONYMIZED` is final: an update that sets any other status, or a new email, answers 409 `USER_ANONYMIZED`, and
`status: ANONYMIZED` can only be set by the endpoint or the flow action; SCIM answers 404 for an anonymized user.
An anonymized user cannot sign in by any method. If the person later signs in through SSO with their old email, just-in-time provisioning creates a new,
unrelated user. Each anonymize writes one setup-audit entry (action `ANONYMIZED`) naming the actor and the user id,
without the erased values.

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
   API: `GET`/`POST /api/admin/delegated-admin-scopes`, `GET`/`PATCH`/`DELETE /api/admin/delegated-admin-scopes/{id}`.
2. Delegates use the scoped endpoints under `/api/admin/delegated/*` (`GET /me` for their effective scope,
   `GET|POST /users`, `PATCH /users/{id}`, invite, reset password). The console shows them a filtered Users page.

Guard rails: a scope cannot include a profile that grants a privileged permission (no delegating admin-of-admins);
delegates cannot edit their own record, change email, manager or MFA, or move a user outside the manageable
profiles; scopes are re-checked on every request, so revoking one is immediate.
