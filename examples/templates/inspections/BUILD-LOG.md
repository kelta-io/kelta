# Inspections checklist template — build log

How this template was authored and verified, on 2026-10-10, with the `kelta` CLI 1.0.1465
(git `5982367`) against a hosted Kelta instance. The template copies the shape of
`examples/templates/crm/`; `install.sh` differs from the CRM script only in its first comment
line, `SEED_ORDER` and `REFS`.

## Tenants

No Docker was available, so the template was built and verified in two throwaway **sandboxes**
of a parent tenant (`kelta sandbox create`), signed in with each sandbox's one-time admin
credential through `POST /auth/direct-login` on the auth host, and driven with `KELTA_URL`,
`KELTA_TENANT` and `KELTA_TOKEN`. Both sandboxes were deleted at the end with
`kelta sandbox delete <envId> --yes` (both now `ARCHIVED`). Nothing was installed into the parent.

| Sandbox | Used for |
|---------|----------|
| 1 | Authoring: collections, fields, picklists, validation rule, flow, layouts; rule and flow tests; `kelta metadata export` |
| 2 | Final verification: clean `install.sh` run (exit 0), the rule demonstration, a re-run |

A sandbox clones its parent's metadata. Before the run in sandbox 2 the state was checked with
the CLI: its collections were `deals contacts activities members accounts books loans`, its flows
were six flows of other apps, and it had no dashboard. None of the five template collections
existed, so none of their fields, field-scoped picklist values, page layouts, list views or
validation rules could exist either; the run had to create everything itself.

## Call counts

| Surface | Calls |
|---------|-------|
| MCP tool calls | **1** — `ping` (answered `pong (user)`). The MCP server connected to this session is the hosted *user* toolset of one fixed tenant: it has no tenant argument and no write tools, so it could neither author nor read the throwaway sandboxes. Every step below was done with the CLI. |
| `kelta` CLI calls | **≈184** (±3), tallied from a wrapper that logged every invocation plus the calls inside `install.sh`; it counts every `kelta` invocation, including `kelta api`, `kelta docs` and `--help` |

Breakdown: 88 authoring calls in sandbox 1 (including the rule and flow tests and the export),
16 `--help`, `--version` and `kelta docs` calls, 8 against the parent (sandbox create, list,
delete; `version`), 37 checks and demonstrations in sandbox 2, and the two `install.sh` runs
(22 and 13 calls).

## Sources read

- `kelta docs agent`, `kelta docs page-layouts` (and the topic list from the error of
  `kelta docs list`); `kelta <command> --help` for `sandbox`, `collections create`, `fields add`,
  `fields update`, `validation-rules create`, `flows create`, `layouts apply`, `metadata export`,
  `api`
- `examples/templates/crm/` — `install.sh`, `README.md`, `BUILD-LOG.md`, `metadata.json`,
  `list-views/*.json`, `dashboards/sales-overview.json`, `seeds/*.json` (the shape copied here)
- `ci/templates-install-check.sh` and the `templates` job of `.github/workflows/ci.yml` — every
  template is installed into the **same** fresh tenant, so no collection name may collide with the
  CRM template's
- `kelta-web/packages/cli/COMMANDS.md` — the sandbox sign-in note
- `runtime-core/.../query/DefaultQueryEngine.java` — `create`/`update`: what a validation rule
  sees (the merged record, no rollup values), `applyFieldDefaults` after
  `TypeCoercionService.coerce` (error 4), `evaluateBeforeSaveWorkflows` being a no-op, and the
  inline `publishRecordEvent` (error 9)
- `runtime-core/.../service/RollupSummaryService.java`, `.../validation/CustomValidationRuleEngine.java`,
  `ValidationRuleEvaluator.java`, `.../formula/BuiltInFunctions.java`, `FormulaParser.java` —
  formula functions and operators (error 5)
- `runtime-module-core/.../handlers/QueryRecordsActionHandler.java` — `QUERY_RECORDS` parameters
  and its `totalCount` output
- `runtime-core/.../flow/StateDataResolver.java` (a whole-string `${…}` keeps its type),
  `InitialStateBuilder.java` (`$.record.data` on DELETED), `FlowTriggerEvaluator.java`
  (`triggerFields` apply to UPDATED only), `FlowDefinitionParser.java` (Choice operators)
- `kelta-worker/.../controller/AtomicOperationsController.java` — `@Transactional` bulk (error 9)
- `runtime-module-schema/.../hooks/CollectionLifecycleHook.java` — collection name pattern
  `^[a-z][a-z0-9_-]*$`
- a grep of the repository for `*.kelta.io` hosts, to find the auth host (error 1)

## Errors hit

| # | Command | What happened |
|---|---------|---------------|
| 1 | sandbox sign-in | `sandbox create` prints the sandbox slug, admin user and one-time password but not the URL to sign in at. `POST https://api.kelta.io/auth/auth/direct-login` → `404 No route found`; the auth host was found by grepping the repository. |
| 2 | `kelta docs` / `kelta docs list` | `missing required argument 'topic'` / `Unknown doc topic "list"`; the second error lists the topics. There is still no flows topic (CRM error 11). |
| 3 | `jq '{value,label}'` on CLI output | Our own mistake, the same as CRM error 9 (`label` is a jq keyword). The first picklist value had been created; the script was resumed after it. |
| 4 | `kelta fields add --type BOOLEAN --default true` (also `--default false`, and `--type INTEGER --default 0`) | The field is created with the default stored as the **string** `"true"`, and from then on every create that omits the field fails: `400 … Invalid type, expected BOOLEAN` (`expected INTEGER`). Root cause: `DefaultQueryEngine.create` calls `applyFieldDefaults` after `TypeCoercionService.coerce`, so a string default is never coerced. Worked around with `kelta fields update <id> --data '{"defaultValue": false}'` (a JSON boolean / number), after which creates work. |
| 5 | designing the rule | A validation rule cannot look at other records: the formula functions are scalar (`IF`, `AND`, `ISBLANK`, `BLANKVALUE`, `DATEDIFF`, …), the rule is evaluated on the stored record merged with the patch, and `ROLLUP_SUMMARY` values are only computed on read, so a rollup field is absent there. Before-save flows are not implemented (`evaluateBeforeSaveWorkflows` is a no-op). The rule is therefore split into a flow-maintained counter and a validation rule on it (see README). |
| 6 | `kelta metadata export` | The export holds the whole tenant — 224 items, including the parent's cloned collections, global picklists, menus and flows — so the five collections' 96 items were selected with `jq`. JSON columns were exported as `{"null": false, "type": "jsonb", "value": "<json text>"}` (CRM error 13), here also the new fields' `default_value`; they were unwrapped the same way, and `created_at`, `updated_at`, `last_scheduled_run` were dropped to match the CRM package. |
| 7 | `kelta records get <collection> <id> --fields …` | `unknown option '--fields'`; `records list` has it. |
| 8 | `kelta api GET /api/dashboards/<id>/data` | `404`; the endpoint is `POST`. Our own mistake. `kelta api POST` then needs `--yes` off-TTY (`"api" is destructive`), also for the read-only `…/validate` and `…/data` calls. |
| 9 | (reading the bulk path) | `records bulk` runs in one transaction and publishes each record event inline, before the commit, so a flow triggered by a bulk-created finding could in principle count before the findings are visible. Not observed: two trial bulks of three high-severity findings and the final install all ended with the right count on every inspection. |
| 10 | rule check in sandbox 2 | `records update inspections <id> --data '{"status":"PASSED","openHighFindings":0}'` on an inspection with an open high-severity finding **succeeds** — the rule only sees the record, and the counter is writable through the API (it is read-only on the layout). Documented in the README as a limit. |

Seed records again have `createdBy`/`updatedBy` = `null` (CRM error 12), and the field-scoped
picklist values were created with `kelta api POST /api/fields` and
`kelta api POST /api/picklist-values` (CRM error 15).

## Verification run (sandbox 2)

State before: none of the template's collections, no flow of its own, no dashboard (see
Tenants). Command, run from `/tmp` with the sandbox's `KELTA_URL`/`KELTA_TENANT`/`KELTA_TOKEN`
exported (`exit=` is the shell's echo of the exit status):

```
$ bash /work/emf/examples/templates/inspections/install.sh 2>&1 | tee install.out; echo "exit=${PIPESTATUS[0]}"
applied metadata.json: {"created":96,"updated":0,"skipped":0,"failed":0}
list view checklist-items/All checklist items: created
list view findings/Open findings: created
list view findings/Open high-severity findings: created
list view findings/All findings: created
list view inspection-templates/Active templates: created
list view inspection-templates/All templates: created
list view inspections/Upcoming and in progress: created
list view inspections/Inspection board: created
list view inspections/Failed inspections: created
list view sites/All sites: created
dashboard Inspections overview: {"created":7,"updated":0,"deleted":0,"unchanged":0}
seeded sites: 6
seeded inspection-templates: 3
seeded checklist-items: 15
seeded inspections: 10
seeded findings: 12
exit=0
```

The run took 13 s. Checked after it:

- record counts (`meta.totalCount`) 6 / 3 / 15 / 10 / 12, matching the seed array lengths;
- `openHighFindings` is 1 on the four inspections that have an open high-severity finding and 0
  on the other six — the flow ran for every seeded finding;
- picklist values of `findings.severity` (3) and `inspections.status` (5) bound to the new
  tenant's field ids; `resolved`, `active` and `openHighFindings` defaults stored as JSON
  `false`, `true`, `0`;
- marking "Riverside Distribution Center - Fire safety - Sep 2026" passed:

  ```
  $ kelta records get inspections "$ID" --output json | jq -c "{name, status, openHighFindings}"
  {"name":"Riverside Distribution Center - Fire safety - Sep 2026","status":"IN_PROGRESS","openHighFindings":1}
  $ kelta records update inspections "$ID" --data '{"status":"PASSED"}' --yes --output json; echo "exit=$?"
  {"error":{"code":"VALIDATION_RULE_FAILED","status":422,"detail":"An inspection with an open high-severity finding cannot be marked passed. Resolve the finding or mark the inspection failed.","source":{"pointer":"/data/attributes/status"},…}
  exit=1
  ```

  After resolving its "East fire exit blocked by pallets" finding the flow set the count to 0
  and the same update succeeded (`status: PASSED`, exit 0);
- adding a high-severity finding to the passed "Northgate Office Tower - Fire safety - Sep 2026"
  moved it to `IN_PROGRESS` with `openHighFindings` 1;
- dashboard: `POST /api/dashboards/<id>/validate` → `valid: true`; the data call returned all six
  widgets (counts by severity and by status, the open high-severity table);
- a second run applied the package as skipped (96), the views and dashboard as unchanged, then
  stopped at the first seed bulk with `422 … name: Value must be unique` and exit 1;
- `install.sh foo` prints its usage and exits 2.

## Candidate generic emf tasks

Each item is a gap a generic platform surface should have closed. None is patched in the
template. Gaps the CRM build already logged are not repeated: packages without list views,
dashboards or records (CRM 1), JSON columns in exports (CRM 2), field-scoped picklists in the CLI
(CRM 4), a `kelta docs flows` topic (CRM 11) and `createdBy` on atomic operations (CRM 12) all
applied here too.

1. **Cross-record validation.** A validation rule should be able to test related records — for
   example an `EXISTS`/`COUNT` over a child collection with a filter, or rollup summary values
   computed into the record the rule sees — or before-save flows should be implemented so a flow
   can veto a save. Today a "parent cannot reach state X while a child matches Y" rule needs a
   flow-maintained counter, which lags the child write and can be overwritten through the API
   (errors 5 and 10).
2. **Coerce field defaults to the field type** — apply defaults before `TypeCoercionService.coerce`
   (or coerce them), and have `fields add --default` send a typed value. Today a BOOLEAN or
   INTEGER default set through the CLI makes every create that omits the field fail (error 4).
3. **Publish record events after commit** for atomic operations (and any other transactional
   write), so record-triggered flows never read state the triggering transaction has not
   committed (error 9).
4. **A system-maintained field flag** — a field writable by flows but rejected (or ignored) on
   API writes, so a derived value such as a counter cannot be set by hand (error 10).
5. **`metadata export` scoped to named collections** (and the items that hang off them), so a
   template can be exported from a tenant that holds other apps without filtering by hand
   (error 6).
6. **`sandbox create` should print the sign-in URL** (or `kelta auth login` should accept the
   one-time credential), so the sandbox can be reached without knowing the auth host (error 1).
7. **CLI: `records get --fields`**, matching `records list` (error 7).
8. **CLI: `kelta api` should not require `--yes` for `POST` endpoints that only read**, such as
   dashboard `validate` and `data`, or the CLI should offer `kelta dashboards validate|data`
   (error 8).
