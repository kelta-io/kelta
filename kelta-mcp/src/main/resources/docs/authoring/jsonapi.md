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
