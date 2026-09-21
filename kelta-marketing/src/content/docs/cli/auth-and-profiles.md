---
title: Authentication and profiles
description: Browser login with PKCE, headless login with a token, how the auth server is resolved, named profiles, environment variables for CI, and token lifecycle.
section: cli
order: 20
---

## Browser login

```bash
kelta auth login --url https://api.example.com --tenant acme --profile prod
```

The CLI starts a one-shot listener on `127.0.0.1`, opens the browser for an OAuth authorization-code flow with
PKCE against the tenant's auth server (MFA and SSO work exactly as in the app), then uses the short-lived token
once to mint a **personal access token** (`--expires-in`, default 90 days) and stores that. The JWT is discarded.
`--no-browser` prints the URL for manual navigation.

## Headless login

```bash
kelta auth login --url https://api.example.com --tenant acme --token klt_...
```

Store a token you created in the app (*Profile → API tokens*) or one an administrator minted for a service user.

## Finding the auth server

The auth server is resolved as: the profile's saved value → `--auth-url` → derive from the API host by
replacing `api.` with `auth.`. On `localhost` the derivation does not apply, so pass
`--auth-url http://localhost:8081`.

## Profiles

Connections are saved as named **profiles**: `~/.kelta/config.json` (URL, tenant, auth URL) and
`~/.kelta/credentials.json` (tokens, mode `0600`). `KELTA_CONFIG_DIR` relocates the directory.

```bash
kelta profile list
kelta profile use prod            # default profile
kelta profile show [name]
kelta profile rename old new
kelta profile remove name
kelta auth status --output json   # { "authenticated": true, "url": …, "tenant": … }
```

A sandbox is a separate tenant — log in to it as its own profile.

## Precedence and CI

Flags override environment variables, which override the profile file:

```bash
KELTA_URL=https://api.example.com KELTA_TENANT=acme KELTA_TOKEN=klt_... kelta collections list
```

No profile is needed in CI. `KELTA_PROFILE` selects a profile by name.

## Tokens

```bash
kelta token list
kelta token create --name ci --expires-in 30   # shown once
kelta token revoke <tokenId> --yes
kelta auth logout --revoke                     # revoke server-side, then forget locally
kelta users token-create <userId> --name feed  # administrator minting for a service user
```

A token has exactly its owner's permissions — see [Personal access tokens](/docs/security/personal-access-tokens/).

## Errors

Missing or expired credentials exit `3` with `{"error":{"code":"AUTH_REQUIRED",…}}`; a `403` from the API exits
`1` with the JSON:API envelope.
