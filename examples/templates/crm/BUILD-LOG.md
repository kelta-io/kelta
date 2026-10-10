# CRM template — build log

How this template was authored and verified, on 2026-10-10, with the `kelta` CLI 1.0.1465
(git `5982367`) against a hosted Kelta instance.

## Tenants

No Docker was available, so the template was built and verified in throwaway **sandboxes** of a
parent tenant (`kelta sandbox create`), signed in with each sandbox's one-time admin credential
through `POST /auth/direct-login` and stored as a profile with `kelta auth login --token`. Every
sandbox was deleted at the end with `kelta sandbox delete <envId> --yes`.

| Sandbox | Used for |
|---------|----------|
| 1 | First authoring pass with global picklists; rehearsal `install.sh` run |
| 2 | First clean `install.sh` run (failed, error 13); then the rebuild with field-scoped picklists and the final `kelta metadata export` |
| 3 | Final verification: clean `install.sh` run, exit 0 |

A sandbox clones its parent's metadata, and the parent already held `accounts`, `contacts`,
`deals` and `activities` collections, three `crm-*` global picklists and three deal flows left
by earlier builds. In sandboxes 2 and 3 those were deleted **before** `install.sh` ran, and the
state was checked: no CRM collection, no `crm-*` picklist, no deal flow, no dashboard, no list
view and no page layout. So the verification run had to create everything itself.

## Call counts

| Surface | Calls |
|---------|-------|
| MCP tool calls | **0** — the CLI covered every step; no MCP client was connected to a sandbox |
| `kelta` CLI calls | **≈356**, tallied from the session's command log (±5); this counts every `kelta` invocation, including `kelta api`, `kelta docs`, `kelta manifest` and the 18 invocations inside each `install.sh` run |

Of the CLI calls, about 150 were authoring (picklists, collections, fields, layouts, flow, list
views, dashboard, export), about 60 were sandbox setup and clean-up, and the rest were the four
`install.sh` runs (18 + 1 + 18 + 11 calls) and the checks after each one.

## Sources read

- `kelta docs agent`, `kelta docs list-views`, `kelta docs dashboards`, `kelta docs page-layouts`
  (and the topic list from the error of `kelta docs list`)
- `kelta manifest --group fields`, `kelta <command> --help`
- `kelta-web/packages/cli/COMMANDS.md` — flags of every command used, and the sandbox sign-in note
- `e2e-tests/tests/agent-authoring.spec.ts` and `e2e-tests/fixtures/authoring/dashboard.json` —
  working `layouts/list-views/dashboards apply` invocations and a dashboard tree file
- `kelta-test-harness/.../SandboxAdminLoginScenarioTest.java` — the `/auth/direct-login` body for a
  sandbox admin
- `kelta-worker/.../service/DashboardDataService.java`, `DashboardComponentValidator.java` — widget
  `config` keys (error 10)
- `runtime-module-core/.../handlers/CreateRecordActionHandler.java` and a grep of
  `runtime-core/.../flow/` for `ResultPath` and `${…}` templates — flow task parameters (error 11)
- `runtime-core/.../model/FieldType.java` — the native field types (errors 7 and 8)
- `kelta-worker/.../service/PackageService.java`, `PackageImportService.java` — export and import of
  JSON columns and picklists (errors 13 and 14)
- `kelta-ui/app/src/hooks/usePicklistOptions.ts` — how the UI finds a picklist field's values
  (error 14)

## Errors hit

| # | Command | What happened |
|---|---------|---------------|
| 1 | `kelta sandbox create -n <name>` | `400 BAD_REQUEST "Request failed with status 400"` with no detail. The name was already used by an archived sandbox; a fresh name worked. |
| 2 | (sandbox clone) | Every sandbox carried the parent's leftover CRM collections, picklists and flows (two of the flows are named as undeletable in the parent because they have runs). They had to be deleted in each sandbox; see Tenants. |
| 3 | deleting a flow | There is no `kelta flows delete`; used `kelta api DELETE /api/flows/<id> --yes`. |
| 4 | `kelta picklists get <name>` | Documented as "Show a global picklist with its values", but returns only the picklist row, no values. Had to list `picklist-values` with `kelta records list`. |
| 5 | `kelta picklists delete <name>` | The picklist's values stay behind: `kelta records list picklist-values` still showed rows whose source picklist no longer exists. |
| 6 | `kelta picklists value-add` for a value that already exists | `409 UNIQUE_VIOLATION … field 'id' … already has value '<uuid>'` — names the `id` column, not the duplicate value. |
| 7 | `kelta fields add --type textarea` | `Unknown field type "textarea"`; the error lists no accepted alias or enum name. |
| 8 | `kelta fields add --type TEXT` | Silently created a `STRING` field: the friendly alias `text` shadows the native `TEXT` enum. Worked around while authoring by removing the field and adding it with `--data '{"type":"TEXT"}'`. |
| 9 | `jq '{value,label}'` on CLI output | Our own mistake (`label` is a jq keyword); the values had been created. |
| 10 | authoring the dashboard | `kelta docs dashboards` names the widget types but not their `config` keys (`collectionName`, `aggregateFunction`, `aggregateField`, `groupByField`, `fields`, `filters` as `[{field, operator, value}]`). Read from the worker source. |
| 11 | authoring the flow | No `kelta docs` topic covers flows: task `Resource` names, `UPDATE_RECORD` `updates[]`, `CREATE_RECORD` `fieldMappings[]`, `ResultPath`, `${$.record.data.<field>}` templates. Read from the handler source. |
| 12 | `kelta records bulk` | Records created through atomic operations have `createdBy`/`updatedBy` = `null`; the same record through `kelta records create`, or created by a flow, carries the user id. All seed records therefore have no creator. |
| 13 | `install.sh` → `kelta metadata apply` (sandbox 2) | `FLOW "Stamp closed deals" FAILED: definition: Required field 'StartAt' is missing or not a string`. Root cause: `kelta metadata export` writes every JSON column as the database driver's object `{"null": false, "type": "jsonb", "value": "<json text>"}`, and `metadata apply` stores that object verbatim. The flow definition fails validation; page-layout `headerConfig`, related-list `displayColumns` and field `fieldTypeConfig` are stored **silently corrupted** (checked in sandbox 2). The script stopped here, as designed. |
| 14 | `kelta metadata apply` of a picklist field (sandbox 2) | A field bound to a **global** picklist keeps the source tenant's `fieldTypeConfig.globalPicklistId`; import remaps picklist values by picklist name but never remaps that id, so in the target tenant the field points at a picklist id that does not exist there, and the UI's option lookup (`usePicklistOptions`) queries that id. Seen in sandbox 2: `deals.stage` carried the build sandbox's picklist id, not the id of the `crm-deal-stage` picklist the same import had just created. |
| 15 | `kelta fields add --type picklist` without `--picklist` | `PICKLIST fields need --picklist <name|id>`: the CLI cannot create a field-scoped picklist field, and `kelta picklists value-add` only takes a global picklist. Used `kelta api POST /api/fields` and `kelta api POST /api/picklist-values` with `picklistSourceType: "FIELD"`. |

### What the template does about errors 13 and 14

Neither is patched in `install.sh`; the script runs only stock `kelta` commands.

- **13:** `metadata.json` is the export with each `{"null","type","value"}` object replaced by the
  JSON it carries, which is the form `PackageImportService` documents for a package it reads
  ("raw JSON strings from a deserialized package") and imports correctly. A package exported
  from a tenant today still hits error 13.
- **14:** the three picklists are **field-scoped** (values owned by the `industry`, `stage` and
  `activityType` fields). Import remaps those by collection and field name, and the final run
  shows each field's values bound to the new tenant's field ids. Global picklists would only
  work once error 14 is fixed.

## Verification run (sandbox 3)

State before: no CRM collection, picklist, flow, layout, list view or dashboard (see Tenants).
Command, run from `/tmp` with the sandbox profile active (`exit=` is the shell's echo of the exit status):

```
$ bash /work/emf/examples/templates/crm/install.sh 2>&1 | tee /work/install-final.out; echo "exit=${PIPESTATUS[0]}"
applied metadata.json: {"created":93,"updated":0,"skipped":0,"failed":0}
list view accounts/All accounts: created
list view accounts/Largest accounts: created
list view activities/Open activities: created
list view activities/All activities: created
list view contacts/All contacts: created
list view deals/Open pipeline: created
list view deals/Pipeline board: created
list view deals/Closed deals: created
dashboard Sales overview: {"created":7,"updated":0,"deleted":0,"unchanged":0}
seeded accounts: 8
seeded contacts: 12
seeded deals: 12
seeded activities: 16
exit=0
```

Checked after the run:

- record counts 8 / 12 / 12 / 16, matching the seed array lengths;
- page-layout `headerConfig` stored as plain JSON;
- picklist values of `accounts.industry` (7), `deals.stage` (6), `activities.activityType` (4)
  bound to the new tenant's field ids;
- flow: moving "Tailspin patient intake" to `CLOSED_WON` stamped `closedAt` and created
  "Kick-off call: Tailspin patient intake"; the run is `COMPLETED`;
- dashboard: `POST /api/dashboards/<id>/validate` → `valid: true`; the data call returned open
  pipeline 555000, won revenue 158000, 8 accounts, 6 stage groups, 7 industry groups, 12 open
  activities;
- a second run applied the package as skipped, the views and dashboard as unchanged, and then
  stopped at the first seed bulk with `422 … name: Value must be unique` and exit 1;
- `install.sh foo` prints its usage and exits 2.

## Candidate generic emf tasks

Each item is a gap a generic platform surface should have closed. None is patched in the
template.

1. **Metadata packages should carry list views, dashboards and seed records.**
   `PackageImportService.TYPE_ORDER` has no type for any of them, so a template needs a script
   that calls `kelta list-views apply`, `kelta dashboards apply` and `kelta records bulk` after
   `kelta metadata apply`. Confirmed by this build: `install.sh` exists only for those three.
2. **`metadata export` must write JSON columns as JSON** (error 13), and `metadata apply` should
   reject or unwrap a `{"null","type","value"}` object instead of storing it. Today an
   export → apply round trip fails on every flow and silently corrupts layout headers, related
   list columns and field type configs.
3. **`metadata apply` must remap `fieldTypeConfig.globalPicklistId` by picklist name** (error 14).
4. **CLI: create field-scoped picklists** — `fields add --type picklist` without `--picklist`,
   and a `picklists value-add --field <collection>.<field>` (error 15).
5. **CLI: `kelta flows delete <flowId>`** (error 3).
6. **CLI: `kelta picklists get` should print the values** it is documented to print (error 4).
7. **Deleting a global picklist should delete or deactivate its values** (error 5).
8. **Duplicate picklist value → a conflict that names the value**, not the `id` column (error 6).
9. **CLI: `fields add --type`** — list the accepted aliases and enum names in the unknown-type
   error (error 7), and let a native enum name such as `TEXT` win over a friendly alias that
   maps elsewhere (error 8).
10. **`kelta docs dashboards`: document each widget type's `config` keys** (error 10).
11. **A `kelta docs flows` topic**: state types, task resources and their parameters, `ResultPath`,
    templates (error 11).
12. **Atomic operations should stamp `createdBy`/`updatedBy`** like single-record writes (error 12).
13. **`sandbox create` should say why it was refused** (for example "name already used"), not a
    bare 400 (error 1).
