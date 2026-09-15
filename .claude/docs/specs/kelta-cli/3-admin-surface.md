# Slice 3 — Admin Command Surface

> Child of `specs/kelta-cli/README.md`. Brings the CLI to parity with (and slightly beyond)
> the kelta-mcp admin toolset, all as registry commands so slice 5 inherits them.

## 1. Goal & scope

Command groups (each maps to existing gateway-fronted endpoints; **no server changes**):

| Group | Commands | Backend |
|-------|----------|---------|
| `collections` | `list`, `describe`, `create`, `update`, `delete` | `/api/collections` system collection (+ existing describe) |
| `fields` | `list <col>`, `add <col>`, `update`, `remove` | `/api/fields` |
| `picklists` | `list`, `get`, `create`, `delete`, `value add/update/deactivate` | `/api/global-picklists`, `/api/picklist-values` |
| `validation-rules` | `list <col>`, `create`, `update`, `delete` | `/api/validation-rules` |
| `constraints` | `list <col>`, `create`, `delete` | `/api/admin/collections/{name}/unique-constraints` |
| `layouts` | `list`, `create`, `update`, `delete`, `apply`, `get [--tree]`; `list-views list/create/update/get/delete` | `/api/page-layouts`, `/api/list-views` |
| `pages` | `list`, `get`, `create`, `update`, `delete`, `publish <path>` | `/api/ui-pages` (generic system collection; `publish` PATCHes `published=true` after resolving by `path` — there is no dedicated publish endpoint) |
| `menus` | `list`, `get [--tree]`, `create`, `update`, `delete` | `/api/ui-menus`, `/api/ui-menu-items` (`--tree` nests items by `parentId` client-side — no server tree endpoint) |
| `dashboards` | `list`, `get [--components]`, `create`, `update`, `delete` | `/api/dashboards`, `/api/dashboard-components` (`--components` fetches and sorts by `rowPosition`/`columnPosition` client-side — no server "dashboard with components" read) |
| `reports` | `list`, `get` | `/api/reports` (read-only sugar; authoring stays on `kelta api` — see below) |
| `flows` | `list`, `describe`, `create`, `update`, `publish`, `execute`, `runs`, `run <execId>`, `cancel`, `retry` | `/api/flows` + `FlowExecutionController` |
| `users` | `list`, `get`, `invite`, `portal-invite`, `reset-password` | `/api/users`, `/api/admin/users/*` |
| `limits` | `get`, `set-tier`, `set` | `GovernorLimitsController` |
| `audit` | `setup [--since]`, `security [--since]`, `logins` | `/api/setup-audit-entries`, `/api/security-audit-logs`, `/api/login-history` |
| `records` | adds `bulk apply` (atomic ops via `/api/_atomic`), `search`, `semantic-search` | existing routers |

Conventions:

- Mutations accept `--data <json|@file|->` (stdin) **or** ergonomic flags for the common
  fields (e.g. `kelta fields add invoices --name due_date --type date --required`); flags
  win over `--data` collisions. Friendly field-type aliases mirror the MCP tool's table.
- All destructive commands are `dangerous: true` (parent non-interactive contract).
- `flows execute` prints the execution id and, with `--wait`, polls `runs`-status to a
  terminal state (reuses the existing `promote --wait` poller util).
- Every list command supports the shared filter/sort/fields/include/pagination flags.
- `pages`/`menus`/`dashboards`/`reports` have no hosted kelta-mcp admin tool (unlike
  `layouts`/`list-views`, which are fully covered there), so they're also registered as
  local MCP tools in `mcp/localTools.ts` (`LOCAL_GROUPS`) rather than staying remote-only.

Out of scope: dashboard/report **authoring** (report column/filter/grouping definitions,
dashboard component layout beyond what `--data`/`kelta api` can express), campaign admin —
available via `kelta api` until demand justifies more sugar. Screen-builder pages, nav
menus and dashboard/report **read+CRUD** sugar landed in KLT-217.

## 2. UI samples

```console
$ kelta fields add invoices --name due_date --type date --required
✔ field due_date (DATE) added to invoices

$ kelta flows execute invoice-reminders --input '{"dryRun":true}' --wait
✔ execution 01J… COMPLETED (4 steps, 2.1s)
```

## 3. Data & API contracts

Client-only. Flow input note (standing platform rule): CLI wraps `--input` as
`{ "input": { … } }` exactly like manual/MCP invocation — flows read `$.input.<key>`.

## 4. DB migrations

N/A.

## 5. File-by-file code changes

- `src/commands/{fields,picklists,validationRules,constraints,layouts,flows,users,limits,audit}.ts`
  — registry entries; handlers call `ctx.client.admin.*` / `ctx.client.resource(...)`.
- `src/commands/records.ts` — `bulk`, `search`, `semantic-search` additions.
- `@kelta/sdk` — only if an endpoint wrapper is missing (surgical additions to
  `AdminClient`, matching its existing style; keep the file-size concern in mind).

## 6. Test plan

- Vitest + MSW per group: request-shape assertions against recorded JSON:API fixtures
  (including friendly-type alias mapping and `--data @file` merge precedence).
- e2e (compose stack) happy-path script: create collection → add field → validation rule →
  create record that violates it (expect stable error code) → flow execute --wait →
  delete collection. Asserts exit codes and json output shapes.

## 7. Docs to update

- `status.md` row update; `kelta-web/README.md` command reference regenerated (from the
  registry — see slice 4 docs generator, or hand-written until it lands).

## 8. Risks & open questions

- System-collection semantics: metadata mutations broadcast via NATS server-side (Critical
  Rule 1 is a server concern); CLI must **read-after-write tolerantly** — a `describe`
  right after `create` may race cache refresh on a multi-pod cluster; poll-with-backoff in
  the few commands that chain reads.
- Picklist legacy dialects (pre-#1222 `fieldTypeConfig`) — reuse the resolution rules from
  PR #1267 rather than reinventing (memory precedent).
- `users invite` flows depend on tenant SMTP config; surface the platform's error code
  verbatim instead of guessing.
