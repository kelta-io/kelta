# Kelta CLI — Cross-Platform Admin CLI + Local MCP (Parent Spec)

> **Status:** partially shipped (verified against main 2026-10-09) — slices 1
> (`1-foundation.md`), 2 (`2-browser-login.md`) and 3 (`3-admin-surface.md`) **shipped**;
> slices 4 (`4-ai-ergonomics.md`), 5 (`5-local-mcp.md`) and 6 (`6-distribution.md`)
> **partially shipped** — each lists its remaining gaps under `## Open`. Do not file tasks
> for anything not listed there.
>
> Authoritative contract for rebuilding `@kelta/cli` into a
> first-class, AI-oriented, cross-platform CLI (`kelta`) that covers core admin features,
> multi-project login with locally stored PAT profiles, collection/record CRUD, multiple
> output formats, a local MCP surface, and self-update from the cluster. Each slice in the
> [Slice plan](#slice-plan) is expanded into its own child spec in this directory and
> **extends, never contradicts** the [Decisions](#decisions), [shared
> contracts](#shared-contracts), and [Security](#security) sections below.
>
> Source-verified against the codebase on 2026-08-06. If code and this doc disagree, trust
> the code and fix this doc.

## How to use this document

This parent defines the cross-cutting architecture once. Child specs each cover one PR-sized
slice with acceptance criteria, exact contracts, migrations (or "none"), file-by-file
changes, and a test plan — per the child-spec template in `specs/app-surfacing/README.md`
(sections 1–8; sections that don't apply state "N/A — reason"). Read this parent first;
every child references it.

## Slice plan

| Slice | Child spec | Axis |
|-------|-----------|------|
| 0 — This spec + doc wiring | (this file) | foundation (docs) |
| 1 — CLI foundation (registry, profiles, output, SDK re-base, CI) | `1-foundation.md` | frontend/CLI |
| 2 — Browser login + PAT lifecycle | `2-browser-login.md` | **auth, security — NO auto-merge** |
| 3 — Admin command surface | `3-admin-surface.md` | CLI (API parity) |
| 4 — AI ergonomics (`manifest`, `api`, non-interactive contract) | `4-ai-ergonomics.md` | CLI (agent UX) |
| 5 — Local MCP (`kelta mcp serve` / `install`) | `5-local-mcp.md` | CLI + MCP |
| 6 — Distribution: binaries, downloads service, self-update, pipeline | `6-distribution.md` | CI/CD + new service |

**Dependency order (hard edges): 1 → {2, 3, 4} → 5 → 6.** Slices 2/3/4 are independent of
each other once 1 lands. Slice 5 consumes the command registry (1) and profiles/PAT (2).
Slice 6 can start its pipeline work in parallel but publishes only after 5.

External track (contracts owned here, code outside this repo): **homelab-argo** gains a
`downloads.kelta.io` ingress + Deployment for the `emf-cli-downloads` image (slice 6), same
GitOps bump flow as every other service.

## Context — what exists today (2026-08-06 recon)

Reuse, do not rebuild:

1. **`@kelta/cli` exists** (`kelta-web/packages/cli`): commander program `kelta` with
   `auth login|logout|status` (paste-a-token into `~/.keltarc`), read-only `collections`,
   JSON:API `records` CRUD, `metadata export|diff|apply`, `sandbox`/`promote`, `sdk types`.
   It talks through its **own axios client** (duplicating SDK logic), is **excluded from the
   root build and CI**, hardcodes version `1.0.0`, and is published nowhere.
2. **`@kelta/sdk` already maps nearly the whole API**: `KeltaClient` (discovery, retry,
   token injection), `ResourceClient` (JSON:API CRUD + `QueryBuilder`), `AdminClient`
   (~2.6k lines covering collections, fields, profiles, picklists, validation rules, flows,
   users, governor limits, environments, promotions, PATs, …), typed error taxonomy, and an
   OpenAPI type generator (`@kelta/sdk/cli`).
3. **PAT system is complete** (`klt_` + 40 chars): `PersonalAccessTokenController`
   (`POST/GET/DELETE /api/me/tokens`, ≤10 active, 1–365 day expiry, SHA-256 stored,
   Redis-cached) and gateway validation (`PatAuthenticationFilter`, Redis-first with worker
   fallback, revocation keys).
4. **kelta-auth supports authorization_code + PKCE public clients** (`kelta-platform` SPA
   client: `ClientAuthenticationMethod.NONE`, `requireProofKey(true)`), plus public-client
   refresh. **No device-authorization grant is configured** and no loopback-redirect
   precedent exists — slice 2 adds the loopback client.
5. **kelta-mcp is a stateless HTTP MCP server** at `/{tenantSlug}/mcp/{user|admin}` — one
   POST per JSON-RPC message, PAT bearer pass-through, 38 tools auto-registered from
   `UserTool`/`AdminTool` markers. Perfect proxy target for a stdio bridge.
6. **CI/CD ships containers only**: merge to main → Harbor images `main-<sha>` → kustomize
   bump in homelab-argo → ArgoCD sync. No GitHub Releases, no npm publish, all runners are
   self-hosted **linux/amd64** (no macOS/Windows runners). Static files are served by
   nginx-based images (kelta-ui pattern); the gateway deliberately serves no static assets.

Added by this effort:

7. **Named profiles** with browser-based login (loopback PKCE → mint PAT) instead of
   paste-a-token single config.
8. **Full admin command surface** with table/json/yaml/csv/ndjson output and a raw
   `kelta api` escape hatch.
9. **A command registry** that derives the CLI parser, `kelta manifest` (machine-readable
   catalog), and local MCP tools from one definition — the core AI-use bet.
10. **Local MCP**: stdio server bridging to hosted kelta-mcp + local-only tools.
11. **Self-updating single-file binaries** for macOS/Linux/Windows served from the cluster,
    published on every merge to main by the existing pipeline.

## Decisions

### D1 — Language/runtime: TypeScript, compiled to native binaries with Bun

The tool stays in `kelta-web/packages/cli` (TypeScript, strict). Distribution uses
`bun build --compile` targeting `linux-x64`, `linux-arm64`, `darwin-x64`, `darwin-arm64`,
`windows-x64` — **all cross-compiled from the existing linux self-hosted runner** (no new
runner classes). Rationale:

- `@kelta/sdk` already implements the entire client surface (JSON:API, retry, errors,
  AdminClient) — a Go/Java rewrite re-implements ~3k lines for zero functional gain.
- Team stack is Java + TypeScript; the CLI is a frontend-monorepo citizen with shared lint,
  vitest, coverage gates.
- Bun is the only route to single-file, no-runtime-required binaries for all three OSes
  from a linux-only CI fleet (GraalVM native-image cannot cross-compile; we have no
  macOS/Windows runners).

Trade-offs accepted: binary size ~60–90 MB (fine for an internal tool); Bun pinned in CI
(`bun.lock` + pinned version env, same discipline as `JAVA_VERSION`). Fallback documented in
slice 6 risks: if Bun compile hits a blocker, Node SEA is the plan-B before any rewrite.

### D2 — Rebuild `@kelta/cli` in place on top of `@kelta/sdk`

No new package. The CLI's private axios client dies; all HTTP goes through `KeltaClient` /
`ResourceClient` / `AdminClient` with a `tokenProvider` fed from the profile store. Existing
command groups (`metadata`, `sandbox`, `promote`, `records`, `collections`, `sdk types`)
are ported onto the registry, keeping flag compatibility where cheap.

### D3 — Command registry as single source of truth

Every command is a data record: `{ group, name, summary, input: zod schema, dangerous?,
handler(ctx, input) => Result }`. From it we derive: commander wiring + `--help`,
`kelta manifest` (JSON catalog with JSON-Schema per command via zod-to-json-schema), local
MCP tool specs (slice 5), and docs. Handlers return structured data; a separate renderer
serializes per `--output`. This is the mechanism that keeps CLI, MCP, and agent docs from
drifting.

### D4 — Auth: loopback PKCE → mint PAT; PAT is the only stored credential

`kelta login` runs authorization_code+PKCE against kelta-auth with a new `kelta-cli` public
client (RFC 8252 loopback redirect, any port on `127.0.0.1`), then immediately calls
`POST /api/me/tokens` with the short-lived JWT to mint a named PAT, stores the PAT, and
discards the JWT. No refresh tokens on disk. Manual `kelta login --token klt_…` remains for
headless boxes. Device grant is a deliberate non-goal for v1 (see slice 2 open questions).

### D5 — Distribution from the cluster, GitOps like everything else

A new `emf-cli-downloads` nginx image (kelta-ui pattern) carries the five binaries,
`manifest.json`, and install scripts; it joins the existing build matrix, Harbor push, and
homelab-argo kustomize bump. `kelta update` self-updates against
`https://downloads.kelta.io/cli/manifest.json` (sha256-verified, atomic swap). Version
scheme: `MAJOR.MINOR` from `package.json`, patch = CI run number (monotonic), git sha
embedded — no manual tagging on the merge path.

## Shared contracts

- **Config**: `~/.kelta/config.json` (profiles, default profile, update settings) +
  `~/.kelta/credentials.json` (mode 0600, PATs only). One-time silent migration from legacy
  `~/.keltarc`. Env overrides: `KELTA_PROFILE`, `KELTA_URL`, `KELTA_TENANT`, `KELTA_TOKEN`,
  `KELTA_UPDATE_URL`. Precedence: flags > env > profile.
- **Profile shape**: `{ name, apiUrl, authUrl, tenantSlug, tokenPrefix, tokenExpiresAt }`
  (credential file holds `{ [profileName]: { token } }`).
- **Output contract**: global `--output table|json|yaml|csv|ndjson` (default: `table` on a
  TTY, `json` when piped), `--raw` for the unflattened JSON:API envelope, `--quiet` for
  ids-only. Data on stdout; diagnostics/progress on stderr, always.
- **Error contract**: non-zero exits with a single-line JSON error object on stderr when
  `--output` is machine-readable: `{ "error": { "code", "status", "detail", "requestId" } }`
  — `code` is the platform's stable UPPER_SNAKE_CASE JSON:API code. Exit codes: 0 success,
  1 API error, 2 usage, 3 auth/expired, 4 not found, 5 conflict/limit.
- **Non-interactive contract**: no prompts when stdin is not a TTY; destructive commands
  (`dangerous: true` in the registry) require `--yes` off-TTY and confirm on-TTY.
- **Pagination**: `--page`/`--size` map to `page[number]`/`page[size]` (HTTP clamp 200);
  `--all` auto-paginates with a hard client cap (10k) and a stderr warning when hit.
- **Filters**: repeatable `--filter field[.op]=value` → `filter[field][op]=value`
  (op defaults to `eq`), `--sort`, `--fields`, `--include` pass through JSON:API semantics.

## Security

- Slice 2 (new OAuth client, redirect validation change, credential storage) is
  security-typed: **never enable auto-merge** (SECURITY.md + standing rule).
- Loopback listener binds `127.0.0.1` only, one-shot, random port, `state` + PKCE S256
  verified, 120 s timeout.
- PATs at rest: plain file with 0600 (AWS-CLI model). OS-keychain integration is a tracked
  follow-up, not v1 — Bun-compile + native keychain modules is unproven.
- CLI never logs a full token (prefix only, same redaction rule as kelta-mcp).
- Self-update: sha256 from the manifest verified before swap; manifest served from our
  cluster over TLS. Binary signing/notarization (macOS Gatekeeper, Windows SmartScreen) is
  a documented follow-up before any public distribution.

## Non-goals (v1)

- Device-authorization grant (headless uses `--token`); OS keychain storage; npm publishing
  of the CLI; GitHub Releases; signed/notarized binaries; Windows arm64; plugin system.

## Docs impact (parent-level)

Slice PRs update: `status.md` (new capability rows), `ci-cd.md` (slice 6), `CLAUDE.md`
Module Map note for `kelta-web` (CLI now built in CI) and Stack table (Bun pin, slice 6),
`kelta-web` README (install + develop instructions), `conventions.md` (CLI output/error
contract pointer). This README is the doc-wiring slice 0.
