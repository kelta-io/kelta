# Authoring a Template Through the Admin MCP Toolset

Every authoring step of the two example templates (`examples/templates/crm/` and
`examples/templates/inspections/`) mapped to the MCP tool that performs it, so an agent can
build the same app in a sandbox through MCP instead of the CLI. The steps and error numbers come
from each template's `BUILD-LOG.md` (CRM `#n`, Inspections `I#n`).

## Connecting to a sandbox

A sandbox is its own tenant: the parent's token is refused on it (the gateway rejects a
personal access token on any tenant other than the one it was issued in). Point the stdio
bridge at the sandbox with the sandbox's own token:

```bash
kelta sandbox create -n authoring            # prints the sandbox slug and a one-time admin credential
# sign in to the sandbox (see `kelta sandbox --help`), then mint a PAT inside it:
KELTA_TENANT=<sandbox-slug> KELTA_TOKEN=<sandbox session token> kelta token create --name mcp
export SANDBOX_PAT=klt_...                   # the token printed once by `token create`
kelta mcp serve --toolset all --tenant <sandbox-slug> --token-env SANDBOX_PAT
```

- `--tenant` replaces the profile's tenant for **every** tool the server exposes: the bridged
  hosted tools (`{mcpUrl}/{slug}/mcp/user` and `/mcp/admin`) and the `cli_` local tools
  (`cli_metadata_export`, `cli_metadata_apply`, `cli_dashboards_validate`, …).
- `--token-env` names the variable holding the token, so the token is never in argv or in the
  client config. `KELTA_TENANT` / `KELTA_TOKEN` work too and rank below the flags.
- `--tenant` without a token for that tenant is refused at start-up instead of failing every
  call with 401.
- `kelta mcp install <client> --toolset admin --tenant <slug> --token-env SANDBOX_PAT` prints the
  client configuration with the same flags.
- The hosted MCP endpoint takes personal access tokens (`klt_…`) only. A session token from the
  sandbox sign-in works for the CLI but not for the bridge, hence the `token create` step.
- Create and delete sandboxes from a server (or CLI) bound to the **parent**: with `--tenant` set,
  `cli_sandbox_*` acts on the sandbox itself.

## Coverage

`--toolset all` bridges the user and admin toolsets; tools marked *(user)* are on the user
toolset. `cli_` tools are local to `kelta mcp serve`.

| Step | Build-log evidence | MCP tool | Status |
|------|--------------------|----------|--------|
| Collections | both logs: every collection created by hand in the build sandbox | `apply_collection` (idempotent: collection, inline fields, field-scoped picklist values and display field in one call), `create_collection` (optionally with inline fields), `update_collection`, `delete_collection` | covered |
| Fields, including native types and typed defaults | #7, #8 (`--type TEXT` became `STRING`), I#4 (string default broke every create) | `add_field`, `update_field`, `remove_field`. Every uppercase `FieldType` name passes through verbatim (`TEXT` stays `TEXT`); `defaultValue` takes a JSON boolean or number | covered |
| Global picklists | #4 (`picklists get` printed no values), #5, #6 | `apply_picklist` (idempotent), `get_picklist` (includes the values), `list_picklists`, `deactivate_picklist_value` | covered |
| Field-scoped picklists | #14, #15 (needed raw `POST /api/fields` and `/api/picklist-values`) | `apply_collection` with inline `values` on the field, or `add_field` with `picklistSourceType: "FIELD"` and no `picklistSourceId`, then `apply_field_picklist` (idempotent, `prune` deactivates extras) | covered |
| Validation rules | Inspections rule on `inspections.status` | `create_validation_rule`, `update_validation_rule`, `list_validation_rules`, `delete_validation_rule` | covered |
| Page layouts | both logs: one layout per collection with sections and related lists | `apply_layout` (whole tree, idempotent) | covered |
| List views (table) | `list-views/*.json` in both templates | `apply_listview` (idempotent) | covered |
| List views (kanban: "Pipeline board", "Inspection board") | `viewType: KANBAN` + `laneField`/`cardFields` in `list-views/*.json` | `apply_listview` (idempotent), `create_listview` and `update_listview` take `viewType` and `typeConfig` | covered |
| Flows | #3 (no delete), #11, I#5 | `create_flow`, `update_flow`, `delete_flow`, `list_flows`; reference `kelta://docs/flows` | covered |
| Flow test runs | Inspections counter flow; CRM "Stamp closed deals" | *(user)* `update_record` to trigger, `get_flow_run`, `execute_flow` | covered |
| Dashboard | #10 (widget `config` keys undocumented) | `apply_dashboard` (idempotent); reference `kelta://docs/dashboards` | covered |
| Dashboard validate / data | I#8 (`kelta api POST …/validate` needed `--yes`) | `cli_dashboards_validate`, `cli_dashboards_data` | covered |
| Seed records with parent references | `install.sh` step 4: `records bulk` plus a jq natural-key → id mapping (`REFS`) | `seed_records` (`references: {"account": "accounts.name"}` resolves keys before writing; batches of 100) | covered; records still carry `createdBy: null` (#12, a platform gap in atomic operations, not in the tool) |
| Install a package | `install.sh` step 1: `kelta metadata apply metadata.json` | `cli_metadata_apply`, `cli_metadata_diff` (act on the `--tenant` tenant) | covered |
| Export | #13, I#6 (whole tenant exported, filtered with jq) | `cli_metadata_export` with `collections` (only those collections and what hangs off them); runs against the `--tenant` tenant and writes the file on the machine running `kelta mcp serve` | covered; no hosted admin tool returns the package, so a remote-only client cannot export — follow-up: an `export_metadata` admin tool returning the package JSON |
| Sandbox lifecycle | both logs: `sandbox create` / `sandbox delete` | `cli_sandbox_create`, `cli_sandbox_list`, `cli_sandbox_delete` (from a server bound to the parent) | covered |

## Follow-ups

- An `export_metadata` admin tool that returns the package JSON (with the `collections` scope of
  `cli_metadata_export`), for clients that connect to the hosted endpoint directly.
- Atomic operations should stamp `createdBy` / `updatedBy` (CRM #12); `seed_records` inherits the
  gap.
