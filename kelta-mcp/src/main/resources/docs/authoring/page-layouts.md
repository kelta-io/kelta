# Authoring Page Layouts

A page layout controls how a collection's record detail/edit page is
organized: sections, columns, field placement, header, and related lists.
Stored across `page-layouts` plus child collections `layout-sections`,
`layout-fields`, `layout-rules`, and `layout-related-lists`.

## Layout

- `collectionId` — the collection this layout applies to.
- `layoutType` — `DETAIL` (default), `EDIT`, `MINI`, or `LIST`.
- `isDefault` — convention only (see "One default", below).
- `defaultFilter` / `defaultSortField` / `defaultSortDirection` (`ASC`
  default) / `defaultRowLimit` (default `50`) — defaults for any related
  list rendered under this layout's records.
- `headerConfig` — JSON: `titleFields` (string[] used to build the record
  title), `avatarFrom` (string[] tried in order for an avatar image),
  `metaFields` (array of `{ key, icon?, prefix? }` shown under the title).
- `railBlocks` — JSON array of side-rail widgets. Each has a `kind`
  discriminator: `metadataCard` (`title`, `rows: [{label, value, mono?}]`),
  `statStrip` (`tiles`), `scoreCard` (freeform config), `tagsCard`
  (`title`, `tags: [{label, tone?}]`), `aiCard` (`title`, `summary`,
  `actions?`), or `timeline` (`title`, `events`). Unknown kinds are
  ignored by the renderer rather than erroring — safe to add new kinds
  without breaking old clients.

## Sections

`layout-sections` rows belong to a layout: `heading`, `columns` (integer,
default `2`), `sortOrder`, `collapsed` (default false), `style` (default
`DEFAULT`), `sectionType` (default `STANDARD`; also `FIELDS` and
`HIGHLIGHTS_PANEL`), and an optional `tabGroup`/`tabLabel` pair for
tabbed layouts. `visibilityRule` is a JSON conditional-visibility
expression (see the app-platform conditional-visibility spec).

## Fields and columns are 0-based

`layout-fields` rows place one field into one section: `fieldId`,
`columnNumber`, `sortOrder`, `columnSpan` (default `1`),
`isRequiredOnLayout`, `isReadOnlyOnLayout`, `labelOverride`,
`helpTextOverride`, `visibilityRule`.

**`columnNumber` is 0-based.** For a section with `columns: 2`, valid
values are `0` (first column) and `1` (second column) — not `1` and `2`.
This is the opposite convention from dashboards, whose grid positions are
1-based (see `kelta docs dashboards`); don't mix the two up when
generating both from the same script. A `columnNumber` at or past the
section's `columns` count is clamped down to the last column rather than
rejected, so an off-by-one here degrades quietly instead of erroring —
double-check the value against the section's `columns` field rather than
relying on a validation error to catch it.

## Related lists

Related lists are **not** embedded in the layout's own JSON — each is a
row in `layout-related-lists`: `layoutId`, `relatedCollectionId`,
`relationshipFieldId` (the lookup/master-detail field on the *related*
collection that points back), `displayColumns` (required JSON array of
field names), `sortField`, `sortDirection` (default `DESC`), `rowLimit`
(default `10`), and `sortOrder` (required — controls the order multiple
related lists stack on the page).

## One default per collection (convention only)

As with list views, `isDefault` on a layout is not enforced as unique per
collection — author at most one `DETAIL` and one `EDIT` default per
collection; if more than one is marked default the platform picks one
without erroring, which is rarely what you want.

## One call: the layout tree

Everything above is one request per row across four collections, with a
field-name → id lookup for every placement. The tree endpoint collapses
that into a single idempotent upsert, addressed by **names** instead of
ids.

### Entry points

- `GET /api/page-layouts/{layoutId}/tree` — reads the whole layout as the
  document below.
- `PUT /api/page-layouts/{layoutId}/tree` — applies that document to an
  existing layout.
- `PUT /api/collections/{collectionName}/layouts/{layoutName}/tree` —
  applies it to the named layout of the named collection, creating the
  layout row first if it doesn't exist yet.
- MCP `apply_layout` (kelta-mcp, admin) wraps both PUTs — pass `layoutId`,
  or `collectionName` + `name` to create-or-update. It also fills in a
  0-based `column` (`index % columns`) for any field placement that omits
  one, so "just list the fields" lands them left-to-right, top-to-bottom
  instead of stacking every field in column 0 (the tree endpoint's own
  default for an omitted `column`).
- `kelta layouts apply <collection> --file <tree.json> [--name <name>]`
  and `kelta layouts get <layoutId> --tree` (CLI) — the file is exactly
  the body below; `--name` overrides the file's own `name` and addresses
  the layout within the collection.

### Body shape

```json
{
  "name": "Main",
  "layoutType": "DETAIL",
  "isDefault": true,
  "description": "...",
  "headerConfig": { "titleFields": ["name"] },
  "sections": [
    {
      "heading": "Overview",
      "columns": 2,
      "collapsed": false,
      "fields": [
        { "name": "name", "column": 0 },
        { "name": "owner", "column": 1, "helpText": "Account owner", "required": true },
        { "name": "notes", "column": 0, "readOnly": true }
      ]
    }
  ],
  "relatedLists": [
    {
      "collection": "invoices",
      "relationshipField": "account",
      "displayColumns": ["number", "amount", "createdAt"],
      "sortField": "createdAt",
      "sortDirection": "DESC",
      "rowLimit": 10
    }
  ]
}
```

Fields are addressed **by name**, not id — the endpoint resolves each
`sections[].fields[].name` against the target collection itself.
`column` is **0-based** (see "Fields and columns are 0-based" above) and
must be less than its section's own `columns` (1-4, default `2`) — unlike
the raw `layout-fields` collection, an out-of-range `column` here is
rejected (400) rather than clamped. A field placement also accepts
`label` (label override), `helpText`, `readOnly`, and `required`.
`headerConfig` is the same shape as the layout's own `headerConfig`
field, above.

A `GET` response also carries `layoutId` and `collection` — read-only
echoes of the layout addressed, there so the same body can be fed
straight back into `PUT`. A `PUT` may include them, but only to agree
with the layout/collection it's already addressing; either one naming a
*different* layout or collection is rejected (400) rather than silently
repointing the write.

A layout scalar (`name`, `layoutType`, `isDefault`, `description`,
`headerConfig`) is applied only when the body carries that key, so a body
that only manages `sections` never blanks the header. The child arrays
are authoritative for what they cover: a section, field placement, or
related list absent from the body is deleted.

### Idempotency and the GET -> PUT round trip

Sections match on `heading`, field placements match on the field's
`name`, and related lists match on `(collection, relationshipField)`.
Applying the same body twice reports `created=0, updated=0, deleted=0` on
the second call — everything lands as `unchanged` — so a caller can
assert convergence from the response counts instead of re-reading the
layout. `GET .../tree` returns exactly the document `PUT` accepts, so
`GET` → edit → `PUT` is the supported authoring loop.

### `relatedLists`: omit vs. empty

`relatedLists` is managed only when the body carries the key at all:

- **Omitting** the key entirely leaves existing related lists untouched —
  a body that edits only `sections` never touches them.
- **`relatedLists: []`** is a real assertion — "this layout has no
  related lists" — and deletes every existing one.

### System fields in related-list `sortField` / `displayColumns`

A related list's `displayColumns`/`sortField` normally name a field on
the *related* collection, but they also accept the system audit columns
every record carries even though those have no row in that collection's
own field list: `id`, `createdAt`, `updatedAt`, `createdBy`, `updatedBy`,
`createdGeo`, `updatedGeo`. An unrecognized name still fails validation
(400) at the same JSON Pointer.
