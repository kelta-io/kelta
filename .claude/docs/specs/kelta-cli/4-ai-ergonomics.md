# Slice 4 — AI Ergonomics: Manifest, `kelta api`, Agent Contract

> **Status:** partially shipped — `kelta manifest [--group]` (`kelta-web/packages/cli/src/commands/manifest.ts`, `src/registry/manifest.ts`), `kelta api` (`src/commands/api.ts`), `kelta docs agent` + generated `AGENTS.md`/`COMMANDS.md` (`src/docsgen/`, `kelta-web/scripts/gen-docs.mjs`, freshness pinned by `docsgen.test.ts`), `requestId` on errors (`src/errors.ts`), `--all` 10k cap (`src/commands/records.ts`). See [Open](#open). Verified against main 2026-10-09.

> Child of `specs/kelta-cli/README.md`. The "focus the tool for AI use" slice: make every
> capability discoverable and predictable for an agent without a human in the loop.

## 1. Goal & scope

- **`kelta manifest`** — full machine-readable catalog derived from the registry: every
  command with summary, JSON Schema of inputs (zod → json-schema), dangerous flag, output
  shape hints, examples. One stable top-level shape `{ version, commands: [...] }`; an
  agent can plan calls without reading prose help.
- **`kelta api <METHOD> <path>`** — raw escape hatch (gh-api style): profile auth + tenant
  slug prefix applied automatically; `--data <json|@file|->`, `--header`, query params
  passed through; response body verbatim on stdout; JSON:API error → standard error
  contract. Covers every endpoint the CLI has no sugar for.
- **`kelta docs agent`** — prints a condensed agent guide (~150 lines: auth model, output
  contract, exit codes, pagination/filter grammar, 10 canonical examples). Same content
  checked in as `kelta-web/packages/cli/AGENTS.md` and referenced from the repo root.
- **Docs generator** — `npm run gen:docs` renders the command reference for
  `kelta-web/README.md` from the registry (keeps docs and code from drifting; CI check
  fails when output is stale, same idea as format:check).
- **Contract hardening** (already specified in the parent, enforced + tested here):
  TTY-aware default output, no-prompt off-TTY, `--yes` gating, stderr JSON errors, stable
  exit codes, `--all` cap warning. Add `KELTA_NO_COLOR`/`NO_COLOR` and `--no-color`.
- **Request id surfacing**: every error prints `requestId` when the platform returns one
  (`meta.requestId` in the JSON:API error) — the hook agents need for log correlation.

Out of scope: MCP (next slice) — this slice is what makes MCP generation trivial.

## 2. UI samples

```console
$ kelta manifest | jq '.commands[] | select(.name=="fields add").input' | head
$ kelta api GET '/api/collections?page[size]=5&filter[systemCollection][eq]=false'
$ kelta records delete invoices 01J8…   # off-TTY, no --yes
{"error":{"code":"CONFIRMATION_REQUIRED","detail":"pass --yes to delete"}}  # exit 64→2
```

## 3. Data & API contracts

Client-only. Manifest schema is versioned (`manifestVersion: 1`) and covered by a snapshot
test — additive changes only within a major.

## 4. DB migrations

N/A.

## 5. File-by-file code changes

- `src/commands/{manifest,api,docs}.ts`; `src/registry/toJsonSchema.ts`;
  `scripts/gen-docs.ts`; `AGENTS.md`; CI: stale-docs check added to the frontend job.

## 6. Test plan

- Manifest snapshot (schema-validated); round-trip test: for each registry command, build
  a valid input from its JSON Schema and assert the parser accepts it (catches
  zod↔schema drift).
- `kelta api`: MSW tests for method/query/body/header pass-through, slug prefixing, error
  envelope mapping.
- Contract tests: piped-vs-TTY default output, `--yes` gating matrix, NO_COLOR.

## 7. Docs to update

- `status.md`; `conventions.md` gains a short "CLI output & error contract" pointer to the
  parent spec; repo-root `CLAUDE.md` Reference-docs table mentions `AGENTS.md` for CLI use.

## 8. Risks & open questions

- `kelta api` bypasses sugar-level validation by design — it inherits only auth + tenant
  scoping; document that Cerbos/gateway remain the enforcement layer (they do today).
- Manifest size (~all commands) is fine for context windows now; if it grows, add
  `kelta manifest --group <g>` filtering (already trivial via registry).

## Open

Promised above but absent from `kelta-web/packages/cli/` on main (2026-10-09):

- **`--no-color` global flag and `KELTA_NO_COLOR`** — not registered (global flags are
  `--profile`, `--output`, `--raw`, `--quiet`, `--yes`). The CLI emits no ANSI color at all
  (`src/docsgen/agentGuide.ts`: "NO_COLOR needs no special handling"), so the flag would be
  a no-op; close by adding it as one or by dropping the requirement.
- **Per-command `examples` and output-shape hints in `kelta manifest`** — `ManifestCommand`
  in `src/registry/manifest.ts` carries summary, dangerous, requiresAuth, positionals,
  options and `inputSchema` only.
