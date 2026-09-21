---
title: Connected apps (OAuth clients)
description: Register OAuth 2.0 clients — client-credentials for services, authorization-code with PKCE for user-facing apps — with live registration, consent, IP restrictions and token audit.
section: security
order: 80
---

A **connected app** is an OAuth 2.0 client registered in a tenant. Use one when a third-party or in-house
application needs to call the API with its own identity (client credentials) or on behalf of users who sign in
through Kelta (authorization code). For scripts and agents acting as a single user, a
[personal access token](/docs/security/personal-access-tokens/) is simpler.

## Register an app

Setup → Integration → Connected apps (`MANAGE_CONNECTED_APPS`), or a record in `connected-apps`:

| Attribute | Meaning |
|---|---|
| `name`, `description` | |
| `clientId` | Generated; the OAuth `client_id` |
| `clientSecretHash` | The secret is shown once at creation and stored hashed |
| `grantTypes` | `client_credentials`, `authorization_code` (with refresh) |
| `redirectUris` | Required for authorization code |
| `requirePkce` | Enforce PKCE (recommended for public/SPA clients) |
| `consentRequired` | Show a consent screen on first authorization |
| `scopes` | Scopes the app may request |
| `ipRestrictions` | CIDR list the app may call from |
| `rateLimitPerHour` | Per-app token issuance cap |
| `active` | Deactivating revokes the registration immediately |

Registrations are read live by the auth server (cached ~30 s), so creating, editing or deactivating an app takes
effect without a restart.

## Client credentials

```bash
curl -u "$CLIENT_ID:$CLIENT_SECRET" \
  -d grant_type=client_credentials \
  https://auth.example.com/acme/oauth2/token
```

The resulting bearer token identifies the app; authorize it like a user by giving the app a profile.

## Authorization code with PKCE

Standard flow against `https://auth.example.com/acme/oauth2/authorize` and `/oauth2/token`. Tokens carry the
signed-in user's identity, so the app can do exactly what that user can. Access tokens live 1 hour, refresh tokens
8 hours.

## Token audit

Every issued token — both grants — is recorded in `connected-app-tokens` with its `jti`, and a `TOKEN_ISSUED`
security-audit event is written. The app's *Tokens* tab shows issuance, last use and revocations; `lastUsedAt` on
the app reflects real activity.
