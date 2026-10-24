---
title: Authentication and sessions
description: How users sign in — the built-in OIDC provider, token and session lifetimes, portal users and magic links, and the tenant IP allowlist.
section: security
order: 10
---

Kelta ships its own OpenID Connect provider (`kelta-auth`). No external identity server is required; external
providers plug in as federated identity sources ([SSO](/docs/security/sso/)).

## Ways to sign in

| Who | How |
|---|---|
| Internal users | Email + password on the auth server's login page, then MFA if enrolled or required. |
| Internal users via SSO | A configured OIDC or SAML provider; the account is provisioned on first login. |
| Portal users | A magic link sent by email — portal users have no password. |
| Scripts, CI, agents | A [personal access token](/docs/security/personal-access-tokens/) — no interactive login. |
| Third-party apps | OAuth 2.0 through a [connected app](/docs/security/connected-apps/). |

## The OIDC provider

The web app is a public OAuth client using the authorization-code flow with PKCE against
`https://auth.example.com/<tenant>/oauth2/authorize` and `/oauth2/token`. The issued JWT carries the user's
`tenantId`, `profileId`, groups and `user_type`; the gateway validates it on every request and re-stamps the
identity as internal headers the worker trusts (any client-supplied identity headers are stripped first).

The CLI's browser login uses the same flow with a loopback redirect (`http://127.0.0.1:<port>/<tenant>/auth/callback`)
and exchanges the short-lived token for a PAT immediately.

## Token and session lifetimes

| Credential | Lifetime |
|---|---|
| Web app access token | 8 hours; refreshed silently with a rotating refresh token (7 days) |
| CLI login token | 15 minutes — used only to mint a PAT, then discarded |
| Connected app tokens | 1 hour access / 8 hours refresh |
| Auth-server browser session | 8 hours |
| Personal access token | 1–365 days, chosen at creation (default 90) |
| Portal magic link | 15 minutes (login), 7 days (invitation) |

A refresh token is single-use; the response carries the next one. There is no separate per-tenant idle-timeout
setting: sessions end when the token chain expires, on explicit logout, or when the account is deactivated.

## Password policy and lockout

Password complexity, history, dictionary checks and account lockout are configured per tenant — see
[MFA and password policy](/docs/security/mfa-and-password-policy/). A user flagged for a forced password change
(new accounts, admin reset) must change it before reaching the app.

## Portal users and magic links

Portal users (`userType: PORTAL`) are external people — customers, patients, members. They are invited with
`POST /api/admin/users/portal-invite` (or self-sign-up where the deployment enables it), receive the seeded *Portal
User* profile (`API_ACCESS` only) and see only records explicitly shared with them. They sign in by requesting a
link at `/portal/login`; tokens are single-use, hashed at rest, and rate-limited per user. Responses are uniform
whether or not the email exists.

## Logging out

The app's logout goes through the auth server's end-session endpoint. For SAML-federated sessions the platform
initiates Single Logout at the identity provider first, then ends its own session, then returns to the app.

## Auth server endpoints

The auth server is its own host (`https://auth.example.com`), not behind the API gateway. Besides the standard
OIDC endpoints (`/oauth2/authorize`, `/oauth2/token`, discovery), it serves the sign-in pages and a few JSON
endpoints. "Open" means no session is needed; "signed in" needs the auth-server session or a bearer token.

| Endpoint | Access | Purpose |
|---|---|---|
| `GET /login` | open | Email + password sign-in page. |
| `GET /oauth2/consent` | signed in | Consent screen a [connected app](/docs/security/connected-apps/) shows on first authorization. |
| `GET`/`POST /mfa-challenge`, `POST /mfa-challenge/recovery` | open (mid sign-in) | Second factor after the password, or a recovery code. |
| `GET`/`POST /mfa-setup`, `POST /mfa-setup/complete` | open (mid sign-in) | Enrol a TOTP factor when MFA is required. |
| `POST /auth/sms/send`, `POST /auth/sms/verify` | signed in | Send and check an SMS one-time code (the SMS factor). |
| `GET`/`POST /change-password` | open (mid sign-in) | The forced password change after sign-in (new account, admin reset). |
| `POST /auth/password/change` | signed in | Change your own password; checked against the tenant's password policy and history. |
| `GET`/`POST /forgot-password`, `GET`/`POST /reset-password` | open | Signed-out password reset pages: request a link, then set a new password. |
| `POST /auth/password/reset-request`, `POST /auth/password/reset` | open | The same reset as JSON for an external frontend; the request always answers the same, account or not. |
| `POST /auth/email/send-verification`, `GET /auth/email/verify` | signed in | Email-ownership verification; the emailed link is valid for 48 hours. |
| `POST /auth/direct-login` | open, off by default | Username + password in, tokens out, for headless test runs. Enabled only with `kelta.auth.direct-login.enabled=true`. |
| `POST`/`DELETE /auth/session` | open | Exchange a federated provider's token for an auth-server session cookie, and end it. |
| `GET /logout/saml2/initiate` | open | Starts SAML Single Logout at the identity provider ([SSO](/docs/security/sso/)). |
| `GET /portal/login`, `POST /portal/login/request`, `GET /portal/login/verify` | open | Portal magic-link pages: request a link, then open it. |
| `POST /portal/api/login/request`, `POST /portal/api/login/verify` | open | The magic-link flow as JSON for a headless portal; the redirect URI must be on the tenant's allowlist. |
| `POST /portal/api/signup` | open, off by default | Portal self-sign-up, enabled per tenant; always answers `202`. |
| `GET /portal/api/challenge` | open | A proof-of-work challenge for sign-up and link requests; `404` when the deployment does not require one. |

The headless portal's redirect allowlist is `GET`/`PUT /api/admin/tenant/portal-auth-settings` on the API host
(`MANAGE_USERS`): `redirectUris`, plus the `inviteRedirectUri` invitation links land on.

## Tenant IP allowlist

A tenant can restrict API access to known networks. Under **Setup → Security → Network access** (requires
`MANAGE_TENANTS`) enable the allowlist and list CIDR ranges; the fields are `ipAllowlistEnabled` and
`ipAllowlistCidrs` on the `tenants` record.

- Applies to `/api/**` for every caller whose profile lacks `MANAGE_TENANTS` — administrators always get through,
  so a bad range cannot lock everyone out.
- With `KELTA_SECURITY_TRUSTED_PROXIES` set, the single resolved client address is checked; forwarded headers
  count only when they come from a trusted proxy. Without it, the socket address and every `X-Forwarded-For` /
  `X-Real-IP` hop are checked when forwarded-header trust is on (`IP_ALLOWLIST_TRUST_XFF`), which a client can
  spoof — see [Security hardening](/docs/deploy/security-hardening/#client-ip-and-trusted-proxies).
- Fails open when the configuration is missing or disabled.
- Inbound webhook endpoints are not subject to it (they authenticate differently — see
  [Triggers](/docs/automation/triggers/)).

Changes take effect on every gateway pod within seconds via the config event bus.
