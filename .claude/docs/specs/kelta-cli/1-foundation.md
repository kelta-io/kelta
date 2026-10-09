# Slice 1 — CLI Foundation: Registry, Profiles, Output, SDK Re-base, CI

> **Status:** shipped — `kelta-web/packages/cli/src/registry/` (command registry), `src/config/store.ts` (profiles + `~/.keltarc` migration), `src/render/`, `src/errors.ts`, `src/context.ts`; built in the kelta-web root `build`; e2e smoke `e2e-tests/tests/admin/cli-smoke.spec.ts`. Verified against main 2026-10-09. No open items.

> Child of `specs/kelta-cli/README.md`. Implements decisions D2 (SDK re-base) and D3
> (command registry), plus the profile store and output/error contracts every later slice
> builds on.

## 1. Goal & scope

Rebuild `kelta-web/packages/cli` internals without changing its package identity:

- **Command registry** (`src/registry/`): `CommandDef` records with zod input schemas and
  handlers returning structured results; commander wiring generated from the registry.
- **Profile store** (`src/config/`): `~/.kelta/config.json` + `~/.kelta/credentials.json`
  (0600) per the parent's shared contract; silent one-time migration from `~/.keltarc`;
  `kelta profile list|use|show|remove|rename`; global `--profile` flag + env overrides.
- **SDK re-base**: delete `src/client.ts` axios duplication; construct one `KeltaClient`
  (baseUrl + tenantSlug from the resolved profile, `tokenProvider` from the credential
  store, retry left at SDK defaults). Admin calls go through `client.admin`.
- **Renderer** (`src/render/`): table/json/yaml/csv/ndjson + `--raw` + `--quiet`, TTY
  detection, JSON:API flattening (`{ id, ...attributes }`), stderr-only diagnostics.
- **Error mapper**: SDK error taxonomy → parent exit codes + machine-readable stderr JSON.
- **Port existing commands** onto the registry with no behavior regression:
  `collections list|describe`, `records list|get|create|update|delete`,
  `metadata export|diff|apply`, `sandbox *`, `promote *`, `sdk types`, `auth status|logout`
  (`auth login` stays paste-token until slice 2 replaces its internals).
- **CI inclusion**: add `cli` to the kelta-web root `build` script and the frontend CI job
  (lint, typecheck, format, vitest + coverage). Fixes the standing "CLI can silently break"
  gap.
- Version string read from `package.json` at build time (kills the hardcoded `1.0.0`).

Out of scope: browser login (slice 2), new admin commands (3), manifest/api (4), MCP (5),
binaries (6).

## 2. UI samples

```console
$ kelta profile list
NAME      URL                     TENANT     TOKEN      EXPIRES
prod *    https://api.kelta.io    acme       klt_A1b2…  2026-11-04
staging   https://api.stg.k.io    acme-stg   klt_C3d4…  2026-09-01

$ kelta records list invoices --filter status=open --sort -createdAt --output json | jq '.[0].id'
```

## 3. Data & API contracts

No server-side changes. Client-side contracts (config/credentials shape, env precedence,
output/error/exit-code/pagination/filter contracts) are defined in the parent — this slice
implements them verbatim and adds vitest coverage locking each one.

## 4. DB migrations

N/A — client-only.

## 5. File-by-file code changes

All under `kelta-web/packages/cli/`:

- `src/registry/types.ts`, `src/registry/index.ts` — `CommandDef`, registry, commander
  binder (flag names derived from zod shape + per-field metadata).
- `src/config/{paths,configStore,credentialStore,migrateKeltarc}.ts` — stores, 0600
  enforcement, env/flag precedence resolver.
- `src/context.ts` — builds `CommandContext` (resolved profile, `KeltaClient`, renderer,
  logger) once per invocation.
- `src/render/{renderer,table,flatten}.ts`; `src/errors.ts` (exit-code map).
- `src/commands/*.ts` — rewritten as registry entries; `src/client.ts` deleted.
- `src/index.ts` — slim bootstrap: parse global flags, build context, dispatch.
- `package.json` — deps: add `zod`, `yaml`, small table util (or hand-rolled), keep
  commander; `@kelta/sdk` moves from type-only to runtime dep.
- `kelta-web/package.json` — root `build` gains cli; CI workflow `ci.yml` frontend job
  already runs workspace-wide scripts, verify cli is included (coverage gate 80%).

## 6. Test plan

- Vitest unit: registry→commander binding (flags, defaults, dangerous gating), config
  precedence matrix (flag/env/profile), keltarc migration (happy + corrupt file), renderer
  snapshots per format, flattening incl. relationships, error→exit-code map.
- MSW-based integration of each ported command against recorded JSON:API fixtures
  (existing SDK test infra), asserting request shape parity with the pre-rewrite client.
- One smoke e2e in `e2e-tests/` (node spawn of built CLI against the compose stack:
  `profile` + `collections list` + `records` CRUD round-trip) — tagged for the existing
  Playwright/e2e job.

## 7. Docs to update

- `status.md`: add "Kelta CLI foundation (profiles, registry, SDK-based)" row 🟡.
- `kelta-web/README.md`: CLI develop/build instructions; note ~/.kelta config layout.
- `CLAUDE.md` Module Map line for kelta-web: mark cli as CI-built.

## 8. Risks & open questions

- `AdminClient` is a known oversized file (`concerns.md`) — the CLI consumes it, must not
  bloat it; any missing endpoint wrapper gets added surgically.
- Flag-compat: `metadata`/`promote` flags are kept verbatim with ONE exception — the file
  flag on `metadata export`/`sdk types` is now `-o/--out` (`--output` belongs to the global
  format flag; commander rejects the collision). `records --data` accepts inline JSON,
  `@file`, and `-` (stdin) going forward (additive).
- Renderer table output is for humans only — no format guarantee; machine consumers use
  json/ndjson (documented in help text so agents don't parse tables).
