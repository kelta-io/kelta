---
title: MFA and password policy
description: Multi-factor methods, enrolment and challenge, tenant-wide MFA enforcement, administrator resets, and the per-tenant password policy.
section: security
order: 30
---

## Multi-factor methods

| Method | Notes |
|---|---|
| TOTP (RFC 6238) | Any authenticator app; the secret is stored encrypted. |
| Recovery codes | Generated at enrolment; each code is single-use. |
| SMS one-time code | Requires an SMS provider configured on the deployment ([Messaging](/docs/platform/messaging/)). |

Challenges are rate-limited.

## Enrolment and challenge

Users enrol from their profile in the app, or are sent to enrolment at next login when the tenant requires MFA.
The flow lives on the auth server (`/mfa-setup`, `/mfa-setup/complete`, `/mfa-challenge`,
`/mfa-challenge/recovery`) and runs between password verification and token issuance, so every client — the app,
the CLI's browser login, connected apps — gets MFA for free.

## Requiring MFA for a tenant

```http
PUT /api/admin/mfa/policy
{ "mfaRequired": true }
```

(`GET` reads it; Setup → Security → MFA policy in the console; needs `MANAGE_USERS`.) Once required, users without
an enrolled factor are taken to enrolment at their next login.

## Administrator actions

| Action | Endpoint |
|---|---|
| See whether a user has MFA enrolled | `GET /api/admin/mfa/users/{userId}/status` |
| Reset a user's MFA (lost device) | `POST /api/admin/mfa/users/{userId}/reset` |

Both are in Setup → Administration → Users and require `MANAGE_USERS`. A reset removes the factor and recovery
codes; the user re-enrols at next login.

## Password policy

One policy per tenant, edited under Setup → Security → Password policy:

| Setting | Default |
|---|---|
| Minimum / maximum length | 8 / 128 |
| Require uppercase, lowercase, digit, special character | off |
| Password history (previous passwords rejected) | 3 |
| Dictionary check (common passwords rejected) | on |
| Personal-data check (name, email fragments rejected) | on |
| Lockout threshold (failed attempts) | 5 |
| Lockout duration | 30 minutes |
| Maximum age (days; forces rotation) | unset |

Locked accounts show `status: LOCKED` and unlock automatically after the lockout duration or when an
administrator resets the password. Administrator-initiated resets and new invitations force a password change at
next login.
