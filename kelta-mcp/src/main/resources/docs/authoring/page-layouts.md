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
