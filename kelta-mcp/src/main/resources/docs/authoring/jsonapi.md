# JSON:API Conventions

The platform's record API follows JSON:API for reads and writes on
collections, plus one platform-specific batch extension
(`POST /api/operations`). This is the contract every collection endpoint
follows, regardless of which collection you're authoring against.

## Attributes vs relationships

On a read, each field is placed based on its type: relationship fields
(lookup/master-detail, i.e. `referenceConfig != null`) are emitted under
`relationships.<field> = { "data": { "type": "<targetCollection>", "id":
"<value>" } }` (or `"data": null` when unset); every other field is a
plain key under `attributes`. `id` is always a top-level sibling of
`attributes`/`relationships`, never duplicated inside `attributes`.

```json
{
  "type": "invoices",
  "id": "42",
  "attributes": { "amount": 120, "status": "open" },
  "relationships": { "account": { "data": { "type": "accounts", "id": "9" } } }
}
```

Fields deleted from the schema but still present on the physical table are
silently dropped from the response rather than erroring.

## Filter operators

Canonical operator set, used as `filter[field][op]=value`:

```
eq neq gt gte lt lte isnull contains starts ends icontains istarts iends ieq in
```

`in` expects a collection of values (matches any of them). The public
end-user UI also accepts long-form aliases (`equals`, `not_equals`,
`greater_than`, `greater_than_or_equal`, `less_than`,
`less_than_or_equal`, `starts_with`, `ends_with`, and `any` for `in`);
author against the short canonical names above unless you're specifically
targeting UI-authored filter strings.

## Pagination and `pageSizeClamped`

`page[size]` defaults to 20 and is clamped to a **hard ceiling of 200**
for any HTTP caller. Ask for more than that and the server serves 200
rows anyway and adds to the response `meta`:

```json
{ "meta": { "pageSizeClamped": true, "requestedPageSize": 500 } }
```

Check `meta.pageSizeClamped` rather than assuming your requested size was
honored — silently getting fewer rows than expected is a common source of
missed records in paginated exports. (A separate, larger internal ceiling
of 1000 exists for server-side report/export/include batching that never
goes through the HTTP layer — it isn't reachable from a normal API call.)

## Sparse fieldsets

`fields[<type>]=a,b` restricts the response to those attributes/relationship
names (plus `id`, always included). The bare non-standard form `fields=a,b`
(no bracketed type) is also accepted as a convenience. The `<type>` in the
bracketed form is not validated against the actual resource type — any
bracket key works — so getting the type name wrong there won't raise an
error, it just won't filter anything.

The same parameter applies to `GET /api/<collection>/<id>`
(`kelta records get <collection> <id> --fields a,b`): `data.attributes` and
`data.relationships` keep only the named keys; `id`, `type` and `included`
are unchanged, and so is the `ETag`.

## System-maintained fields

A field flagged `systemMaintained: true` holds a value the platform keeps
up to date for you: a counter a validation rule relies on, a
last-computed timestamp. Flows and server-side code (before-save hooks,
NATS listeners, schedulers) may write it. An API caller, whether a user
session or a PAT, may read it but not set it:

- a create that supplies a non-null value, or an update that changes the
  stored value, fails with **400**, constraint `systemMaintained`,
  detail `Field '<field>' on collection '<collection>' is system-maintained
  and cannot be written through the API`. In `POST /api/operations` the
  pointer is `/atomic:operations/<i>/data/attributes/<field>` and the whole
  batch rolls back;
- an update that re-sends the value already stored (a full-record
  round-trip) is accepted, and the field is left out of the write. Numbers
  compare by value, an ISO timestamp equals the stored instant, and `""`
  equals null.

Discover the flag in `GET /api/collections/{name}/schema` (every field
carries `systemMaintained`), the MCP `get_collection_schema` tool, or the
OpenAPI spec, where such attributes are `readOnly`. Set it on the field
itself, on the `fields` system collection: `kelta fields add
--system-maintained`, `kelta fields update <fieldId> --system-maintained
true|false`, or `systemMaintained` in a field's attributes. A package
exported with `kelta metadata export` carries the flag, and `kelta
metadata apply` restores it; a package without the key applies the field
with the flag `false`. To maintain the value, write it from a flow step
(e.g. an update-record action), not from the API client.

### Picklist values in the schema

In the same response, every `PICKLIST` and `MULTI_PICKLIST` field of a
tenant collection carries `picklistValues`: its active values, in
`sortOrder`, whether the field owns them or is bound to a global picklist
(`fieldTypeConfig.globalPicklistId`). Inactive values are left out. A
picklist field with no active values has `picklistValues: []`; other
fields have no `picklistValues` key. System collections keep listing their
allowed values under `enum`.

```json
{
  "name": "priority",
  "type": "PICKLIST",
  "required": false,
  "isRelationship": false,
  "description": null,
  "systemMaintained": false,
  "picklistValues": [
    { "value": "LOW",  "label": "Low",  "isDefault": true,  "sortOrder": 1 },
    { "value": "HIGH", "label": "High", "isDefault": false, "sortOrder": 2 }
  ]
}
```

Write a field's `value` (not its `label`) in a create or update; for a
`MULTI_PICKLIST`, write an array of values.

## `--yes` on every non-GET

Every CLI/API mutation is treated as requiring explicit confirmation:
programmatic non-GET calls need an off-TTY confirmation (`--yes` on the
CLI) or they're rejected with a usage error — this applies uniformly to
create/update/delete, not just to obviously destructive operations like
delete. When scripting against the API directly (not through the CLI),
this shows up as the CLI's own gate, not a server-side requirement — the
server accepts a well-formed mutation regardless of confirmation flags;
the gate exists so an agent or script can't silently perform a write it
didn't mean to.

## Atomic operations (`POST /api/operations`) and `lid`

The request envelope key is `"atomic:operations"` (not `data` or
`operations`):

```json
{
  "atomic:operations": [
    { "op": "add", "data": { "type": "accounts", "lid": "a1", "attributes": { "name": "Acme" } } },
    { "op": "update", "ref": { "type": "contacts", "id": "9" },
      "data": { "type": "contacts", "relationships": { "account": { "data": { "type": "accounts", "lid": "a1" } } } } }
  ]
}
```

- `op` is `add`, `update`, or `remove`. `add` requires `data.type`;
  `update`/`remove` require `ref.type` plus either `ref.id` or `ref.lid`.
- `lid` is a client-chosen local id, scoped to one batch. When an `add`
  op supplies `data.lid`, the server remembers the mapping from that lid
  to the real generated id for the rest of the batch — later ops in the
  *same* batch can reference the not-yet-existing row via `ref.lid`, or
  point a relationship at it via `data: { "lid": "..." }` in place of
  `data: { "id": "..." }`. This is how you create a parent and a child
  that references it in one atomic call, without a round trip to learn
  the parent's real id.
- The whole batch is one transaction — all operations succeed or all are
  rolled back. Referencing a `lid` that no earlier op in the batch created
  fails the whole batch.
- Batch size defaults to 100 operations and cannot be configured above a
  hard ceiling of 500.

### Which collections a batch may write

- **Tenant collections** (every collection you define) are supported.
- **Identity system collections** `users`, `group-memberships`,
  `delegated-admin-scopes` and `user-permission-sets` are supported; their
  writes are still checked by the identity write guard (`MANAGE_USERS`,
  `MODIFY_ALL_DATA`, or `MANAGE_DELEGATED_ADMINS` for scopes).
- **Every other system collection** (`fields`, `picklist-values`,
  `collections`, `flows`, …) is rejected. These have their own endpoints,
  which run side effects a batch would skip (DDL, registry refresh, cache
  eviction); write them there instead.

A rejected collection fails the whole batch with **400** before any
operation runs, so nothing is written. The pointer names the first
offending operation:

```json
{
  "errors": [{
    "status": "400",
    "code": "UNSUPPORTED_COLLECTION",
    "title": "Unsupported collection",
    "detail": "Atomic operations do not support the system collection 'fields'; use POST /api/fields instead",
    "source": { "pointer": "/atomic:operations/1" }
  }]
}
```

A failure while an operation executes is a different error: **422**
`OPERATION_FAILED` (or **400** for a field validation failure) pointing at
the failing operation, and the batch rolls back.
