# Applicant tracking template — build log

How this template was authored and verified, on 2026-10-20, **through the admin MCP server
alone**: `kelta mcp serve` (kelta CLI 1.0.1589) bridging the hosted kelta-mcp toolsets of a
throwaway sandbox, driven by a small JSON-RPC stdio client that appended every tool call and its
outcome to a tally file as it ran. The counts below come from that file, not from memory. The
template copies the shape of `examples/templates/crm/`; `install.sh` differs from the CRM script
only in its first comment lines, `SEED_ORDER` and `REFS`.

## Tenants

The examples tenant's own credential was used only to create, list and delete sandboxes; nothing
was installed into the examples tenant itself. Each sandbox was signed in to with its one-time
admin credential through `POST https://auth.kelta.io/auth/direct-login` (the auth host from the
`api.`→`auth.` rule in `kelta auth login --help`), and a personal access token was minted inside it
with `kelta token create`, because the hosted MCP endpoint takes PATs only. The MCP bridge was
pointed at the sandbox with `KELTA_URL`, `KELTA_TENANT` and `KELTA_TOKEN`: the installed CLI
1.0.1589 has no `mcp serve --tenant/--token-env` flags yet (see Follow-ups), and the environment
variables rank the same way.

| Sandbox | Used for |
|---------|----------|
| 1 `ats-build-klt632-a1` | Authoring through MCP: collections, fields, picklists, layouts, flow, list views, dashboard, seed records, flow and dashboard checks, the metadata export |
| 2 `ats-verify-klt632-a1` | Final verification: clean `install.sh` run (exit 0), checks through MCP, a re-run and the usage check |

Both were deleted at the end with `kelta sandbox delete <envId> --yes`; `kelta sandbox list`
afterwards shows `ats-build-klt632-a1` and `ats-verify-klt632-a1` as `ARCHIVED`.

A sandbox clones its parent's metadata. Before authoring, sandbox 1 was checked through MCP
(`list_collections` without system collections, `list_picklists`, `list_flows`,
`cli_dashboards_list`): its collections were `deals contacts activities members accounts books
loans`, its global picklists `book-status crm-industry crm-activity-type crm-deal-stage`, its
flows seven flows of other apps (three named as undeletable leftovers), and its one dashboard
"Sales overview". None of them uses a name of this template, so **no clashing leftover had to be
deleted** (0 clean-up calls). Sandbox 2 was checked the same way before `install.sh` ran, with the
same result, so the verification run had to create everything itself.

## Call counts

| Surface | Calls |
|---------|-------|
| **MCP tool calls** | **101** (budget 150): 84 in sandbox 1 (authoring), 17 in sandbox 2 (checks after the verification run). 5 of them returned an error. |
| Sandbox setup and clean-up (not in the MCP budget) | 10: through the examples credential `sandbox list` ×2, `sandbox create` ×2, `sandbox delete` ×2; per sandbox one `direct-login` and one `kelta token create` |
| `kelta docs` and `--help` reads | 13: `kelta docs agent`, `flows`, `list-views`, `dashboards`, `page-layouts` (and the topic list from the errors of `kelta docs` / `kelta docs list`); `kelta mcp --help`, `mcp serve --help`, `sandbox --help`, `sandbox create --help`, plus `auth --help` and `auth login --help` |
| `kelta` CLI for authoring | **0** — no `kelta api`, no authoring command |
| Verification (`install.sh`, CLI by design) | 3 runs: the clean run, a re-run, `install.sh foo` |

The 101 MCP calls by step:

| Step | Calls | Tools |
|------|-------|-------|
| Discovery and sandbox 1 state check | 7 | `tools/list` ×2 (admin, then admin + user), `list_collections` ×2, `list_picklists`, `list_flows`, `cli_dashboards_list` |
| Collections and display fields | 8 | `create_collection` ×4 (with inline fields), `update_collection` ×4 (`displayFieldName`) |
| Picklist fields (7, field-scoped) | 8 | `create_record` on `fields` ×7; 1 failed `bulk_apply` (error 2) |
| Picklist values (33) | 35 | `create_record` on `picklist-values` ×33; 1 failed `bulk_apply` (error 3), 1 duplicate (error 4) |
| Page layouts (4) | 5 | `apply_layout` ×5 (error 5) |
| Flow | 1 | `create_flow` |
| List views (8) | 9 | `apply_listview` ×7, `create_listview` ×2 for the Kanban board (error 6) |
| Dashboard | 1 | `apply_dashboard` (7 components) |
| Seed records (48) | 4 | `bulk_apply` ×4, one per collection, keys resolved from the previous call's results |
| Flow test | 3 | `update_record` ×2, `query_collection` |
| Dashboard check | 2 | `cli_dashboards_validate`, `cli_dashboards_data` |
| Export | 1 | `cli_metadata_export` with `collections` |
| Sandbox 2 state check | 4 | `list_collections`, `list_flows`, `cli_dashboards_list`, `list_picklists` |
| Sandbox 2 verification | 13 | `query_collection` ×7, `get_collection_schema`, `update_record`, `get_record`, `cli_dashboards_list`, `cli_dashboards_validate`, `cli_dashboards_data` |

`create_record`, `update_record`, `get_record`, `query_collection`, `bulk_apply` and
`get_collection_schema` are on the *user* toolset, so the bridge ran with `--toolset all` for
those calls and `--toolset admin` for the rest. Every one of them is counted above.

## Sources read

**Source reads: 0.** No file of the `emf` source tree outside `examples/` was read during the
build.

Read, all documentation or examples:

- `kelta docs agent`, `kelta docs flows`, `kelta docs list-views`, `kelta docs dashboards`,
  `kelta docs page-layouts`; `--help` of the commands listed under Call counts
- the tool descriptions and input schemas returned by `tools/list`
- the build pod's own `kelta-cli-as.sh` helper (outside the repository), to see how it passes the
  examples credential
- `examples/templates/crm/` (`install.sh`, `README.md`, `BUILD-LOG.md`, `metadata.json`,
  `list-views/*.json`, `dashboards/sales-overview.json`), `examples/templates/inspections/BUILD-LOG.md`
  and `examples/README.md` — the shape copied here
- the prerequisite the brief names: the KLT-628 commit message, its file list,
  `docs/authoring/mcp-admin-coverage.md` and its changes to `kelta-web/packages/cli/COMMANDS.md`
  and `kelta-marketing/src/content/docs/mcp/tools.md` (documentation of how to point
  `kelta mcp serve` at a sandbox)

`ci/templates-install-check.sh` was not read; the brief states what it does.

## Errors hit

**6 errors**: 5 MCP calls that returned an error, and 1 call that succeeded only in part.

| # | Call | What happened |
|---|------|---------------|
| 1 | `create_collection job-openings` with three `picklist` fields `{"picklistSourceType": "FIELD"}` | The collection and six fields were created; the three picklist fields failed with `picklist fields require "picklistSourceId" (UUID of the source picklist).` The hosted admin toolset predates KLT-628: its `add_field` still needs a global picklist, and `apply_field_picklist` and `seed_records` are not in `tools/list`. Worked around through the user toolset: each picklist field is a `create_record` on the `fields` collection (`{collectionId, name, type: "PICKLIST"}`), each value a `create_record` on `picklist-values` (`picklistSourceType: "FIELD"`, `picklistSourceId: <field id>`). |
| 2 | `bulk_apply` of three `fields` rows | `422 OPERATION_FAILED "Failed to create record in collection: fields"`, pointer `/atomic:operations/0`, no cause. The same row through `create_record` worked. |
| 3 | `bulk_apply` of 13 `picklist-values` rows | The same `422 OPERATION_FAILED` for `picklist-values`; each value then cost one `create_record`. |
| 4 | `create_record picklist-values` (ENGINEERING) | Our own mistake: a broken `sed` edit of the loop script re-sent the first value. `409 UNIQUE_VIOLATION … field 'id' … already has value '<uuid>'` — the conflict names the `id` column, not the duplicate value (CRM error 6, still open). |
| 5 | `apply_layout` with `"name": "Job opening"` | `400 "Body name 'Job opening' does not match the layout named in the path ('Job+opening')"`: the tool encodes the space in the path segment as `+`, which the endpoint does not decode. Renamed the layout to "Opening". |
| 6 | `create_listview` with `displayedFields` as a JSON array | Our own mistake (the input schema says a comma-separated string), but the tool sent the array's string form and the server answered `Unknown field '[name'`. `apply_listview` takes `columns` as an array; `create_listview` cannot be avoided for a Kanban board because `apply_listview` has no `viewType`/`typeConfig`. |

Observed, not counted as errors:

- `create_collection` with `displayFieldName` set left `displayFieldId` null: the display field
  is looked up before the inline fields exist. Set afterwards with `update_collection`, one call
  per collection.
- `get_collection_schema` does not list a field-scoped picklist's values (its `stage` entry
  carries no enum), so checking them in sandbox 2 took two `query_collection` calls (`fields`,
  `picklist-values`).
- `cli_metadata_export` now writes JSON columns as JSON (CRM error 13 is gone) and also exports
  the 8 list views and the dashboard. Those two item types were dropped from `metadata.json` so
  that `list-views/` and `dashboards/` stay the one source `install.sh` applies, as in the CRM and
  inspections templates. Nothing else in the export was changed.
- Records created through `bulk_apply` and `kelta records bulk` now carry `createdBy` (CRM error
  12 no longer seen).

## Verification run (sandbox 2)

State before: none of the template's collections, picklists, flow, layouts, list views or
dashboard (see Tenants). Command, run from `/tmp` with the sandbox's `KELTA_URL`, `KELTA_TENANT`
and `KELTA_TOKEN` exported (`exit=` is the shell's echo of the exit status):

```
$ bash /work/emf/examples/templates/applicant-tracking/install.sh 2>&1 | tee install-final.out; echo "exit=${PIPESTATUS[0]}"
applied metadata.json: {"created":124,"updated":0,"skipped":0,"failed":0}
list view candidates/All candidates: created
list view interviews/Upcoming interviews: created
list view interviews/All interviews: created
list view job-applications/Active applications: created
list view job-applications/Hiring pipeline: created
list view job-applications/Hires: created
list view job-openings/Open roles: created
list view job-openings/All job openings: created
dashboard Hiring overview: {"created":8,"updated":0,"deleted":0,"unchanged":0}
seeded job-openings: 6
seeded candidates: 14
seeded job-applications: 16
seeded interviews: 12
exit=0
```

The run took 11 s. Checked after it, through MCP:

- record counts (`query_collection` `meta.totalCount`) 6 / 14 / 16 / 12, matching the seed array
  lengths;
- picklist values: 33 `FIELD` values, every one bound to a field id of sandbox 2 — department 6,
  employment type 3, status 4, source 5, stage 6, interview type 4, outcome 5;
- flow: moving "Rafael Duarte - Account Executive" from Interview to Hired (`update_record`)
  stamped `stageChangedAt` and `hiredAt` with the update time (`2026-10-20T01:09:24.918029Z`)
  over the seeded `stageChangedAt` of `2026-10-12T16:00:00Z`. In sandbox 1 the same test also
  moved one application to Offer, which stamped `stageChangedAt` and left `hiredAt` null;
- dashboard: `cli_dashboards_validate` → `valid: true` for all 7 components;
  `cli_dashboards_data` returned open roles 4, active applications 10, hires 3, applications by
  stage Applied 2 / Screening 3 / Interview 3 / Offer 2 / Hired 3 / Rejected 3, candidates by
  source Referral 3 / Job board 3 / Careers site 4 / Event 2 / Agency 2, applications by job
  opening (6 groups), and 4 upcoming interviews;
- a second run applied the package as skipped (124), the views and dashboard as unchanged (8),
  then stopped at the first seed bulk with `400 … title: Value must be unique` and exit 1;
- `install.sh foo` prints its usage and exits 2.

## Follow-ups

The build stayed inside the budget (101 of 150 MCP calls), so there are no surplus calls to
account for. Each line below is a gap the build hit or routed around, with the calls it cost,
written as a generic platform task title for the ceo to file. None is patched here.

1. **Deploy the KLT-628 kelta-mcp admin tools (`apply_field_picklist`, `seed_records`, `add_field`
   with a `FIELD` picklist source) to the hosted MCP endpoint** — 33 calls: 7 `create_record`
   calls for fields `create_collection` could have created inline, 26 extra `create_record` calls
   for picklist values that 7 `apply_field_picklist` calls would have written (errors 1–3).
2. **Atomic operations: support system collections such as `fields` and `picklist-values`, or
   return the underlying cause instead of a bare `OPERATION_FAILED`** — 2 calls (errors 2, 3).
3. **`apply_layout`: percent-encode the layout name in the tree path, so a name with a space
   works** — 1 call (error 5).
4. **`create_listview`/`update_listview`: take `displayedFields` as an array like
   `apply_listview`'s `columns`, and reject a value of the wrong type instead of stringifying it;
   add `viewType`/`typeConfig` to `apply_listview`** (the latter is already listed in
   `docs/authoring/mcp-admin-coverage.md`) — 2 calls (error 6, and a non-idempotent create for
   the Kanban board).
5. **`create_collection`: resolve `displayFieldName` after creating the inline fields** — 4
   `update_collection` calls.
6. **`get_collection_schema`: list a field-scoped picklist field's values** — 2 calls in
   verification.
7. **A duplicate picklist value should return a conflict that names the value, not the `id`
   column** (CRM error 6) — 1 call (error 4).
8. **Publish a kelta CLI release that includes `mcp serve --tenant/--token-env`** — 0 calls;
   the installed 1.0.1589 lacks the flags, and the environment variables stood in for them.
