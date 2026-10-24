# Authoring Metadata Packages

A metadata package is one JSON file that carries a tenant's configuration
(collections, fields, layouts, flows, views, reports, approval processes,
dashboards and optional seed rows) from one tenant to another.
`kelta metadata export` writes one, `kelta metadata diff` previews it against
a tenant, and `kelta metadata apply` applies it. The MCP admin toolset has the
same three steps (`export_metadata`, `diff_metadata`, `apply_metadata`).

```json
{
  "formatVersion": 2,
  "name": "sales",
  "version": "1.0.0",
  "items": [
    { "type": "COLLECTION", "data": { "name": "orders", "display_name": "Orders" } },
    { "type": "FIELD", "data": { "collection_name": "orders", "name": "status", "type": "STRING" } }
  ],
  "records": [
    { "collection": "orders", "key": ["status"], "rows": [{ "status": "draft" }] }
  ]
}
```

Each `items` entry is `{ "type", "data" }`. `data` is the exported row in
snake_case column names, plus the extra `*_name` columns that carry its
references by name. `records` is optional and holds seed rows.

## Item types and natural keys

An item is identified by its **natural key**, never by its id: ids differ
between tenants. Apply looks the key up in the target tenant. If it is
there, the item already exists; if not, apply creates it. Items are applied
in the order of this table, whatever order the file lists them in, so a type
can only refer to the types above it.

| Type | Natural key | Notes |
|------|-------------|-------|
| `COLLECTION` | `<name>` | The display field travels as `display_field_name` and ownership as `owner_field`/`owner_scope`; both are set once the fields exist. |
| `GLOBAL_PICKLIST` | `<name>` | |
| `FIELD` | `<collection_name>.<name>` | A lookup's target travels as `reference_collection_name`; a global picklist binding as `global_picklist_name`. |
| `PICKLIST_VALUE` | `<picklist_name>:<value>` for a global picklist's value, `<field_collection_name>.<field_name>:<value>` for a field's own | `picklist_source_type` (`GLOBAL` or `FIELD`) says which. |
| `VALIDATION_RULE` | `<collection_name>:<name>` | |
| `PAGE_LAYOUT` | `<collection_name>:<name>` | |
| `LAYOUT_SECTION` | `<collection_name>:<layout_name>:<sort_order>` | |
| `LAYOUT_FIELD` | `<collection_name>:<layout_name>:<section_sort_order>:<field_name>` | The field travels as `field_collection_name` + `field_name`. |
| `LAYOUT_RELATED_LIST` | `<collection_name>:<layout_name>:<related_collection_name>:<relationship_field_name>` | |
| `FLOW` | `<name>` | Owned by the user running the apply (else the tenant's first user). |
| `UI_PAGE` | `<path>`, or `<name>` when the page has no path | |
| `UI_MENU` | `<name>` | |
| `UI_MENU_ITEM` | `<menu_name>:<parent_label>:<label>` | `parent_label` is empty for a top-level item. |
| `LIST_VIEW` | `<collection_name>:<name>` | |
| `UNIQUE_CONSTRAINT` | `<collection_name>:<field_names>` | `field_names` sorted and comma-joined, e.g. `orders:code,region`. The index is created with its columns in the package's `field_names` order. |
| `REPORT` | `<name>` | The collection travels as `primary_collection_name`, the folder as `folder_name`. |
| `APPROVAL_PROCESS` | `<collection_name>:<name>` | Steps are nested under `steps` and matched on `step_number`. |
| `DASHBOARD` | `<name>` | The dashboard tree document; widgets are nested under `components` and matched on `title`. |
| `RECORD` | `<collection>:<keyField>=<value>,...` | A row of the `records` section, matched on the section's `key` fields. Applied last. |

## How references resolve

A reference to another row never travels as an id. It travels as the other
row's name, and apply looks that name up in the target tenant (or among the
items the same package has already applied):

- a collection by name (`collection_name`, `reference_collection_name`,
  `primary_collection_name`); a lookup's target and the collection of a list
  view, report or approval process may also be a platform collection such as
  `users`;
- a field by `<collection>.<field>` (`field_name`, `display_field_name`,
  `relationship_field_name`);
- a global picklist by name: a FIELD's `global_picklist_name`, a
  PICKLIST_VALUE's `picklist_name`;
- an approval step's USER approver by email (`approver_email`,
  case-insensitive);
- an approval step's GROUP or QUEUE approver by group name
  (`approver_group_name`);
- a report folder by `folder_name`. This is the one soft reference: a folder
  the target does not have leaves the report unfiled.

**An unresolved reference fails that item only.** The item is reported
`FAILED` with a message naming what is missing, for example
`approver user 'ann@acme.io' not found in target`. Nothing of it is written:
an approval process and its steps are written together or not at all. Every
other item still applies, except items that refer to the failed one, which
fail in turn for the same reason.

## Previewing: `kelta metadata diff`

`kelta metadata diff <file>` is a dry run: it resolves every item exactly as
apply would, writes nothing, and sorts the items into four lists:

- `creates` — the natural key is not in the tenant yet;
- `updates` — the item exists and differs; `changes` names each differing
  attribute (`group_by`, `steps[2].approver_id`, `components[By status].config`,
  or `steps[3]` for a step the tenant does not have yet);
- `unchanged` — the item exists and is identical;
- `conflicts` — the item would fail; `error` says why.

The comparison only looks at attributes the package carries, after its
references are resolved to the target's rows. An existing seed row always
counts as unchanged: seed rows are insert-only. A tenant item that the
package does not carry is not reported.

## Applying: `kelta metadata apply`

With `--conflict skip` (the default), an item that already exists is left
alone and reported `SKIPPED`. With `--conflict overwrite`, it is updated to
the package's values. Under overwrite, approval steps are matched on
`step_number` (a target step the package lacks stays), dashboard widgets are
matched on `title` (a widget the package lacks is removed), and seed rows are
still never updated. `--dry-run` reports what apply would do without writing.
Re-applying a package is safe: every item upserts on its natural key, so
nothing is created twice.
