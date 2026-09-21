---
title: Tool catalog
description: Every tool on the admin and user endpoints with what it does and the API it wraps, plus the typical build order.
section: mcp
order: 30
---

## Admin endpoint

Collections and fields:

| Tool | Purpose |
|---|---|
| `list_collections` | list collections (also on the user endpoint) |
| `get_collection_schema` | fields, types, relationships of one collection (also on user) |
| `create_collection` | create a collection, optionally with an initial field set |
| `update_collection` | display name, display field, description |
| `delete_collection` | drop a collection and its records — irreversible |
| `add_field` | add a field; friendly type aliases, picklist and lookup payloads assembled for you |
| `update_field` | mutable attributes (not type or name) |
| `remove_field` | drop a field and its data |

Picklists, rules and constraints:

| Tool | Purpose |
|---|---|
| `list_picklists`, `get_picklist` | global picklists and values |
| `create_picklist`, `apply_picklist` | create / create-or-update with values (`prune` deactivates) |
| `add_picklist_value`, `update_picklist_value`, `deactivate_picklist_value`, `delete_picklist` | value lifecycle |
| `list_validation_rules`, `create_validation_rule`, `update_validation_rule`, `delete_validation_rule` | formula error conditions |
| `list_unique_constraints`, `create_unique_constraint`, `delete_unique_constraint` | composite unique constraints |

User experience:

| Tool | Purpose |
|---|---|
| `apply_layout` | whole page-layout tree, keyed on collection + name (`create_layout` is deprecated) |
| `update_layout`, `delete_layout` | layout metadata / removal |
| `list_listviews`, `create_listview`, `apply_listview`, `update_listview`, `delete_listview` | shared list views incl. `viewType`/`typeConfig` |
| `apply_page` | validate-then-upsert a page-builder page, keyed on path |
| `apply_menu` | whole app/menu tree, keyed on menu name and item labels |
| `apply_dashboard` | dashboard and its components, keyed on name and component titles |

Automation and integration:

| Tool | Purpose |
|---|---|
| `list_flows`, `create_flow`, `update_flow`, `delete_flow` | flow definitions (JSON state machines, `runAsUserId`) |
| `import_api_spec` | import an OpenAPI 3 document into the API spec library |
| `materialize_api_collection` | turn a GET operation into an external collection |

Plus `ping`.

## User endpoint

| Tool | Purpose |
|---|---|
| `list_collections`, `get_collection_schema` | discovery |
| `query_collection` | list/filter records (`EQ NEQ GT GTE LT LTE CONTAINS STARTS ENDS ICONTAINS ISTARTS IENDS IEQ ISNULL IN`; no OR) |
| `get_record` | one record by id |
| `create_record`, `update_record`, `delete_record` | single-record writes |
| `bulk_apply` | atomic batch through `/api/operations` |
| `search` | cross-collection full-text search |
| `semantic_search` | vector search on a collection with a `VECTOR` field |
| `describe_api` | the tenant's OpenAPI document (collection CRUD only) |
| `execute_flow` | start a flow (input is double-wrapped) |
| `get_flow_run` | status of an execution, optionally with steps |
| `submit_for_approval`, `list_approvals` | approvals |
| `ping` | |

## Conventions

- Arguments are friendly camelCase; the tool boundary translates to the platform's native JSON:API shape
  (uppercase enums, kebab-case collection names). Pass names as `list_collections` returns them.
- Collections and picklists may be addressed by name or UUID.
- Every `create_*`/`update_*` wraps one REST call; `apply_*` tools are create-or-update.
- Destructive tools are annotated `destructiveHint: true`.

## Typical build order

`create_collection` → `add_field` → `apply_picklist` → `create_validation_rule` / `create_unique_constraint` →
`apply_layout` → `apply_listview` → `apply_menu` → `apply_page` → `create_flow`. The
[tutorial](/docs/getting-started/first-app/#part-3--from-an-mcp-client) walks through it.
