# CRM template — build log

How this template was built, written for whoever builds the next one. The rule
for the build: use only the `kelta` CLI, its MCP server (`kelta mcp serve`) and
`kelta docs`, never platform source, and log every error a generic platform
surface should have prevented instead of working around it silently.

Built 2026-10-06 against a demo tenant (slug `examples`) with a personal access
token, CLI 1.0.1452. Fresh-tenant verification runs in CI (`quickstart` job →
`ci/template-apply.sh`).

## Call counts

| Surface | Calls | Notes |
|---------|------:|-------|
| `kelta` CLI | **217** | 198 against the tenant (≈110 of them inside 4 runs of `install.sh`), 19 local (`docs`, `manifest`, `--help`, `mcp --help`). 5 of the 217 are `kelta mcp serve` launches. |
| MCP | **9** | 1 `tools/list` + 6 tool calls through `kelta mcp serve` (`get_collection_schema`, `query_collection`, `list_listviews`, `list_flows`, `cli_dashboards_list`, `get_picklist`), plus 2 tool calls on an MCP connection that turned out to point at a different tenant (both failed: 404 / 403, nothing read). 3 earlier `kelta mcp serve` sessions answered nothing (see "Environment notes"). |

This was the second build attempt. The first one built the collections, layouts,
list views, flow, dashboard and seed data in the same tenant but ended before
writing anything down, so its counts and errors are lost. This attempt exported
what it had left (`kelta metadata export`), turned that into the template files,
deleted it from the tenant and reinstalled from the files to prove them.

## Build sequence

1. Read `kelta docs agent`, `jsonapi`, `page-layouts`, `list-views`, `dashboards`
   and `kelta manifest --group` for metadata, list-views, dashboards, records,
   layouts, flows, collections, fields, picklists.
2. `kelta metadata export` → kept the COLLECTION, FIELD, GLOBAL_PICKLIST,
   PICKLIST_VALUE, PAGE_LAYOUT, LAYOUT_SECTION, LAYOUT_FIELD and
   LAYOUT_RELATED_LIST items of the four collections; dropped timestamps and the
   `source` block (instance and tenant ids). Item `id`s stay: they are package-local
   keys that tie items together, and the import assigns fresh ids (checked: the
   reinstalled collections got new ids).
3. `kelta list-views list <collection>`, `kelta dashboards get <id> --components`,
   `kelta flows describe <id>`, `kelta records list <collection> --all` → the
   list-view, dashboard, flow and seed files.
4. Deleted the CRM from the tenant, `kelta metadata diff package.json` (97 creates,
   0 updates, 0 conflicts), then `install.sh` until it ran clean (errors below).
5. Exercised the result: flow stamps `closedAt` on create and on update to WON,
   clears it on reopen; re-running `install.sh` is a no-op; MCP reads agree.

## Errors hit

Numbers match the `BUILD-LOG #n` comments in `install.sh`.

| # | Command | Message | What should have prevented it | Handled here by |
|---|---------|---------|-------------------------------|-----------------|
| 1 | (design) | — metadata packages carry no list views, dashboards or records | Package format should include LIST_VIEW, DASHBOARD (+ components) and optional seed RECORD items | Separate `list-views/`, `dashboards/`, `seeds/` files applied by `install.sh` |
| 2 | `kelta metadata export` → `kelta metadata apply` | No error. Every JSON column came back as `{"null":false,"type":"jsonb","value":"…"}` in the export, and the import stored that wrapper verbatim: layout `headerConfig`, related-list `displayColumns` and field `fieldTypeConfig` were corrupt after the round trip | Export should emit the JSON value itself (or import should unwrap it); a round-trip test on the package format | Unwrapped every such value to plain JSON in `package.json` (import accepts plain JSON) |
| 3 | `kelta metadata apply package.json --yes` | Exit 0 with `"success": false, "failed": 1` in the body | The CLI should exit non-zero when any item fails | `install.sh` checks `.failed` |
| 4 | `kelta metadata apply package.json --yes` | No error. Picklist fields kept the source tenant's `fieldTypeConfig.globalPicklistId`, pointing at a picklist that does not exist in the target. `metadata diff` did not flag it | Import should remap `globalPicklistId` to the imported GLOBAL_PICKLIST by name (as it does for lookup targets) | `install.sh` relinks each picklist field with `kelta fields update` |
| 5 | `kelta metadata apply package.json --yes` | FLOW item `FAILED`: `createdBy: Referenced record '<the token owner's email>' does not exist in collection 'users' (field createdBy)`. `kelta flows create` with the same token works and stamps the user's UUID | Import should stamp the caller's user id, not their email. `metadata diff` previewed the flow as a plain create | Flow moved out of the package to `flows/`, applied with `kelta flows create/update` |
| 6 | `kelta records bulk --data @seeds… --yes` | `OPERATION_FAILED 422: account: Field is required` on the first deal. A batch `add` ignores `data.relationships` entirely — with `lid` (as `kelta docs jsonapi` documents) and with a plain `id` — so lookups stay null. Putting `{"lid": …}` in `attributes` fails with `account: Invalid type, expected LOOKUP` | Atomic `add` should honour `relationships` (and `lid` targets) as documented | One batch per collection; references written as `{"lid": …}` in attributes and replaced by `install.sh` with ids from the earlier batch's results |
| 7 | `kelta records bulk` (same failure as #6) | The batch failed at operation 24, yet the 24 records before it were committed. Reproduced with a two-op batch | `POST /api/operations` is documented as all-or-nothing; it must roll back | Not worked around. `install.sh` stops at the first failed batch; a failed seed can leave partial data |
| 8 | `kelta records update deals <id> --data '{"stage":"BOGUS"}'` | Accepted. `crm-deal-stage` is `restricted: true` | Restricted global picklists should reject values outside the list | Not worked around |
| 9 | `kelta dashboards apply --file dashboards/sales-overview.json` | `VALIDATION_FAILED: Unknown component property 'sortOrder'` (×8). The file was the shape `kelta dashboards get <id> --components` prints, which the `apply` help points to, and `kelta docs dashboards` lists `sortOrder` as required | `apply` should accept what `get --components` prints (or `get` should print the apply shape) | Dropped `sortOrder`; component order follows the array |
| 10 | `kelta api DELETE /api/flows/<id> --yes` (also `?force=true`) | `REFERENCED_RECORD 409: … is still referenced by other records and cannot be deleted`. The message does not say what references it (the flow's runs). There is no `kelta flows delete` | A flow delete that also removes (or archives) its runs, a `flows delete` command, and an error that names the referencing collection | Not worked around; see "Left behind" |
| 11 | `kelta collections delete <name> --yes` | `VALIDATION_FAILED: … Retry with ?force=true to confirm` | Fine as a guard; the message could name the CLI's `--force` flag | Used `--force` |
| 12 | MCP `list_listviews {"collection":"deals"}` | `Argument "collectionName" is required.` | MCP tools and CLI should agree on argument names (`get_collection_schema` and `query_collection` take `collection`) | Not retried |
| 13 | Quickstart CI: `ci/template-apply.sh` → `install.sh` (KLT-375, run 37444508790) | No error. The install step ran 26 min until the job timeout cancelled it; the cancel also skipped the `if: failure()` log dump, so neither the hung command nor the service logs survived | The CLI's HTTP client has no request timeout (axios default: none) and `kelta` has no `--timeout` flag or `KELTA_TIMEOUT` env, so one unanswered request blocks forever. A default per-request timeout in the CLI/SDK, overridable per call, would have turned the hang into an error naming the request | Every `kelta` call runs under coreutils `timeout`, every `curl` has `-m`, the readiness wait is a fixed-attempt poll, an EXIT trap prints `install failed at step: <name>`, and the CI step has `timeout-minutes: 10` with the log dump on `failure() \|\| cancelled()` (KLT-413) |
| 14 | `docker buildx build … .` in `ci/template-apply.sh` (KLT-375) | No error, no output (`-q`). The template-runner image copied all of `kelta-web` with its ~400 MB `node_modules` from a whole-repo build context on every run | The CLI ships as a multi-package workspace with no single-file Node bundle; the Bun binaries need Bun to build. A published one-file `kelta.mjs` (or an npm-installable `@kelta/cli`) would make it cheap to run anywhere | `template-apply.sh` bundles `packages/cli/dist/index.js` with esbuild into one ~2 MB file and builds from a temp context holding only that, the templates and the script (KLT-413) |

Smaller observations (no error, worth a look):

- `kelta list-views apply` takes flags only; `layouts apply` and `dashboards apply`
  take a `--file`. A list-view file needs `jq` to pull `--name`/`--columns` out.
- Records created by `kelta records bulk` have `createdBy: null`.
- MCP `get_collection_schema` showed no picklist values for `deals.stage`.
- `kelta docs` has no topic for the package format (item types, natural keys,
  what an import remaps) or for record-triggered flow input (`$.record.data.*`);
  both were learned from an export and from an existing flow in the tenant.

## CI install hardening (KLT-413)

The first CI attempts never got a verdict on the template: the install step hung
until the job was cancelled (#13). The rebuild changed only the install path, not
the template's metadata or seeds:

- `install.sh` authenticates from `KELTA_URL`/`KELTA_TENANT`/`KELTA_TOKEN` only,
  runs with `set -euo pipefail` and stdin from `/dev/null`, puts every CLI call
  under `timeout`, passes `--yes` on every mutating call and names the failing
  step on exit.
- `ci/template-apply.sh` bounds the image build, the container run, the direct
  login (`curl -m`) and every CLI call, and removes the named container on exit
  (killing the `docker` client does not stop it).
- `ci/template-files.test.mjs` fails the job before any stack time is spent if a
  call in either script is unbounded or a mutating call lacks `--yes`.

Dry run against a stub gateway (no Docker in the build pod), using the bundled
CLI: a full install is 26 CLI calls in about 8 s; a stub that never answers
`POST /api/operations` fails with `'kelta records bulk' timed out after 5s` /
`install failed at step: seed records: seeds/01-accounts.json (8 records)`; an
unreachable gateway fails the readiness poll after its attempt cap.

## Docs and files read

`kelta docs`: `agent`, `jsonapi`, `page-layouts`, `list-views`, `dashboards`.
`kelta manifest --group` for the nine groups above, and `--help` for
`list-views list`, `collections delete`, `fields update`, `flows update`,
`mcp`, `mcp serve`.

Repository files:

- `kelta-web/packages/cli/COMMANDS.md` — the `records bulk` section.
- `kelta-web/packages/cli/src/config/resolve.ts`, `src/commands/auth.ts`,
  `src/mcp/remote.ts`, `src/commands/mcp.ts` (grep only) — to confirm the CLI
  accepts a JWT in `KELTA_TOKEN` for the CI job. Docs gap: the agent guide says
  `KELTA_TOKEN=klt_…`; `kelta auth login --help` is the only place that says a
  JWT works.
- `kelta-web/package.json`, `kelta-web/packages/{cli,sdk,formula}/package.json`,
  `kelta-cli-downloads/Dockerfile` — how to build the CLI in CI;
  `kelta-web/packages/cli/src/update/passiveCheck.ts` — to confirm the CLI makes
  no update check off-TTY.
- `ci/quickstart-run.sh`, `ci/quickstart-check.sh`, `ci/admin-first-sign-in.sh`,
  `.github/workflows/ci.yml`, `.github/path-filters.yml` — the CI job.
- A grep of the Flyway migrations and `docker/bootstrap/` for collections that
  would clash with the template's names on a fresh tenant (none).

No platform service source was read to work out how to build the template.

## Left behind in the demo tenant

- The installed template (collections, picklists, layouts, list views, flow,
  dashboard, seed records). The flow has runs, so it cannot be deleted (#10).
- Two inactive flows from the first attempt, renamed
  `CRM build leftover A/B (inactive, undeletable: has runs)` (#10).

## Environment notes

- The CLI ran through a credential wrapper that buffers stdout until the
  process exits, so an interactive `kelta mcp serve` session never answered.
  Sending every JSON-RPC message up front and closing stdin worked. This is the
  wrapper, not the platform.
