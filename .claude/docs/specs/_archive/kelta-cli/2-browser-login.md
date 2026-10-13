# Slice 2 — Browser Login + PAT Lifecycle

> **Status:** shipped — `kelta-web/packages/cli/src/auth/{pkce,loopbackServer,loginFlow,browser}.ts`, `src/commands/{auth,token}.ts`, expiry warning in `src/context.ts`; kelta-auth `ConnectedAppRegistrar` registers `kelta-cli` and `PlatformRedirectUriValidator` accepts its loopback callback. The commands shipped as `kelta auth login` / `kelta auth logout [--revoke]` (not top-level `kelta login`/`logout`). Verified against main 2026-10-09. No open items in the CLI; the live login round-trip against a deployed kelta-auth is still owed (see `status.md`).

> Child of `specs/kelta-cli/README.md`, decision D4. **Security-typed slice: two PRs
> (auth-server change, then CLI), neither gets auto-merge** (SECURITY.md rule; PR #1116
> precedent).

## 1. Goal & scope

`kelta login` opens the browser, runs authorization_code + PKCE against kelta-auth, mints a
PAT with the resulting JWT, stores the PAT in the active profile, and discards the JWT.

- **PR A (kelta-auth)**: register a `kelta-cli` public client and allow RFC 8252 loopback
  redirects for it.
- **PR B (CLI)**: the login flow, token lifecycle commands, expiry UX.

Flow (PR B):

1. `kelta login --url https://api.kelta.io --tenant acme [--profile prod]` (flags optional
   when re-authenticating an existing profile).
2. CLI binds a one-shot HTTP listener on `127.0.0.1:<random port>`, generates
   `state` + PKCE verifier/S256 challenge. The callback path is
   **`/{tenantSlug}/auth/callback`** — kelta-auth's `TenantContextFilter` derives the
   login's tenant from the `redirect_uri` path, so a slug-less path authorizes and then
   fails with "no tenant context in session".
3. Opens `<authUrl>/oauth2/authorize?client_id=kelta-cli&redirect_uri=http://127.0.0.1:<port>/callback&code_challenge=…&state=…&scope=openid`
   via OS opener (`open`/`xdg-open`/`start`); prints the URL as fallback. MFA/SSO ride the
   normal browser session.
4. Callback → verify `state` → exchange code at `/oauth2/token` (public client, PKCE).
5. With the access JWT: `POST {apiUrl}/{tenant}/api/me/tokens`
   `{ name: "kelta-cli <hostname> <yyyy-mm-ddThh-mm-ss>", expiresInDays: <--expires-in, default 90> }`
   (second-precision stamp — `(user_id, name)` is unique on `user_api_token`, so a
   date-only name collided on the second login of the day; the worker returns 409
   `UNIQUE_VIOLATION` on a duplicate name instead of 500)
   → store returned `klt_` token + `tokenPrefix` + `expiresAt` in the profile; drop JWT.
6. Browser tab shows a static "you can close this window" page; CLI prints profile summary.

Also in scope:

- `kelta login --token klt_…` — headless path (unchanged behavior, now writes profile store).
- `kelta logout [--revoke]` — removes local credential; `--revoke` first calls
  `DELETE /api/me/tokens/{id}` (id resolved by prefix via `GET /api/me/tokens`).
- `kelta token list|create|revoke` — thin wrappers over `/api/me/tokens` (registry commands,
  so they surface in manifest/MCP later).
- Expiry UX: any command with a profile whose `tokenExpiresAt` is <7 days away prints a
  one-line stderr warning (TTY only); expired → exit 3 with "run `kelta login`".

Out of scope: device grant, refresh-token persistence, keychain storage (parent non-goals).

## 2. UI samples

```console
$ kelta login --url https://api.kelta.io --tenant acme --profile prod
Opening browser… (or visit: https://auth.kelta.io/oauth2/authorize?…)
✔ Logged in as craig@acme.com
✔ Created PAT klt_A1b2C3d4… (expires 2026-11-04) → profile "prod" (default)
```

## 3. Data & API contracts

**PR A — kelta-auth** (no new endpoints, registration + validation only):

- `ConnectedAppRegistrar`: register client `kelta-cli` — `ClientAuthenticationMethod.NONE`,
  grant `authorization_code` (no refresh_token: the JWT lives seconds, PAT is the durable
  credential), `requireProofKey(true)`, `requireAuthorizationConsent(false)`, redirect URI
  template `http://127.0.0.1/callback`.
- `PlatformRedirectUriValidator`: for client `kelta-cli` **only**, accept
  `http://127.0.0.1:<any port>/{slug}/auth/callback` and the `[::1]` equivalent per
  RFC 8252 §7.3 (port ignored; loopback literal only — **not** `localhost`, which can
  be DNS-poisoned; slug shape `[a-z][a-z0-9-]*` mirrors the gateway). All other clients
  keep exact-match semantics.

**PR B — CLI**: consumes existing `POST/GET/DELETE /api/me/tokens` (worker) through the
gateway. No server change. Note: create requires the JWT path (`X-User-Id` derived by
gateway JWT filter); PAT-authenticated callers can list/revoke but minting a new PAT with a
PAT is allowed by the same endpoint — CLI only mints during login.

## 4. DB migrations

None.

## 5. File-by-file code changes

- kelta-auth: `config/ConnectedAppRegistrar.java` (+`registerCliClient()`),
  `config/PlatformRedirectUriValidator.java` (loopback rule), unit tests beside each.
- CLI: `src/auth/{loopbackServer,pkce,browserOpen,loginFlow}.ts`;
  `src/commands/{auth,token}.ts` registry entries; expiry check in `src/context.ts`.

## 6. Test plan

- kelta-auth unit: validator accepts loopback any-port for kelta-cli, rejects for
  `kelta-platform`; rejects `localhost` hostname; rejects non-`/callback` paths.
- CLI unit: pkce vectors, state mismatch → hard fail, listener timeout (120 s) → exit 3,
  port-in-use retry (new random port), callback with error param.
- Integration (kelta-test-harness or e2e compose): full login against real kelta-auth with
  a scripted browser (Playwright driving the consent-less login form), asserting a usable
  PAT lands in the credential file and a subsequent `collections list` succeeds.
- Manual live check post-deploy (memory precedent: SPA refresh probe): run login against
  staging tenant before enabling anywhere else.

## 7. Docs to update

- `status.md`: "CLI browser login (loopback PKCE → PAT)" row.
- `kelta-auth/CLAUDE.md`: registered-clients table gains `kelta-cli`.
- `kelta-web/README.md`: login instructions incl. headless `--token` path.

## 8. Risks & open questions

- **Loopback validator is the security-critical diff** — scoped per-client, covered by
  tests both directions; reviewer checklist in the PR description.
- Multi-pod kelta-auth: client registration is DB-backed (`RegisteredClientRepository` is
  JDBC) → no NATS concern; registrar is idempotent add-if-absent like existing clients.
- Browsers on remote/SSH sessions can't reach `127.0.0.1` of the remote host — that's the
  documented `--token` path; device grant (SAS supports it) is the tracked follow-up if
  demand shows up.
- PAT cap is 10 active/user; login names tokens deterministically and `token list` +
  clearer 4xx mapping ("revoke an old token") handle the cap error.
