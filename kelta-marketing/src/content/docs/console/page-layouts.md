---
title: Page layouts
description: How record pages are composed — sections, columns, field placements, header and rail, related lists, assignment rules — and the one-call tree API for authoring them.
section: console
order: 30
---

A **page layout** decides how a collection's record page looks: which fields appear, in which sections and
columns, what the header shows, and which related lists sit below. The same layout drives the end-user app's
record page and the admin Resource Browser.

For the exact JSON of every row and the tree document, read the
[authoring reference](/docs/reference/page-layouts/); this page explains the model and the workflow.

## Anatomy

| Part | Stored as | What it controls |
|---|---|---|
| Layout | `page-layouts` | `layoutType` (`DETAIL`, `EDIT`, `MINI`, `LIST`), `isDefault`, header config, rail blocks, related-list defaults |
| Sections | `layout-sections` | heading, `columns` (1–4), collapsed, style, `sectionType` (`STANDARD`, `FIELDS`, `HIGHLIGHTS_PANEL`), optional tab group, visibility rule |
| Field placements | `layout-fields` | which field, which section, `columnNumber` (**0-based**), sort order, span, required/read-only on this layout, label and help overrides, visibility rule |
| Related lists | `layout-related-lists` | a child collection, the lookup that points back, columns, sort, row limit |
| Header | `headerConfig` JSON | `titleFields`, `avatarFrom`, `metaFields` shown under the title |
| Rail | `railBlocks` JSON | side-rail widgets: metadata card, stat strip, score card, tags, AI card, timeline |

A collection can have several layouts; mark one `DETAIL` and one `EDIT` as default. Without any layout the record
page still renders every field in one section.

## Editing in the console

Setup → Data model → Page layouts (`CUSTOMIZE_APPLICATION`): pick the collection, create or open a layout, then
drag sections and fields on the canvas; the inspector edits section columns, field overrides and visibility
rules. Save applies the whole layout at once.

**Assignments** (the *Assignments* button on a layout) attach a layout to a **record type**, a **profile**, or a
JSON condition, with an evaluation order; the app resolves the assignment when it opens a record. Assignment is
client-side selection of which layout to render — field access is still enforced by field-level security.

## Visibility and rules

Sections and placements accept a `visibilityRule` — a JSON conditional expression over the record — so a
section appears only when, say, `status = "CLOSED"`. Layouts can also carry client-side **rules** that compute,
default, validate or transform values as the user edits (evaluated by the `RuleEngine` in `@kelta/components`).
Server validation still applies on save.

## Authoring outside the console — the tree

Writing a layout row by row means four collections and a field-id lookup per placement. The **tree** collapses
that into one idempotent call addressed by names:

```bash
kelta layouts get <layoutId> --tree > main.json     # exactly the document PUT accepts
# edit main.json
kelta layouts apply invoices --file main.json --name Main
```

```http
PUT /api/collections/invoices/layouts/Main/tree     # creates the layout if missing
PUT /api/page-layouts/{layoutId}/tree
```

MCP: `apply_layout` with `collectionName` + `name` (or `layoutId`); it also fills in a 0-based `column` for
placements that omit one, laying fields left-to-right.

The response is a diff — `{created, updated, deleted, unchanged}` — and applying the same document twice reports
zero changes, so setup scripts can assert convergence. Sections match on heading, placements on field name,
related lists on (collection, relationship field); anything absent from the body is deleted, except that
omitting `relatedLists` entirely leaves them untouched. Validation errors carry a JSON Pointer such as
`/sections/0/fields/2/name`.

Changes are broadcast to every pod; an open record page picks up the new layout on its next load.

## Tips

- `columnNumber` is 0-based; the tree endpoint rejects an out-of-range column, the raw collection clamps it.
- Related-list `displayColumns` and `sortField` may name the system audit columns (`createdAt`, `updatedBy`, …).
- Use `MINI` layouts for lookups and hover cards, `LIST` to define the default columns of related lists.
