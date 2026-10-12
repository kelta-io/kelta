# Slice 7 — Gallery View

> Child spec of [App Data-Entry (Phase 2)](./README.md), authored with the implementation
> (same PR as slices 5+6). Frontend-only; not security-typed.

## 1. Goal & scope

`viewType='gallery'` renderer on `ObjectListPage`: responsive card grid (CSS grid,
`minmax` columns), optional image header from `typeConfig.gallery.imageField` (a `url`
field; non-URL/absent value falls back to an initial-letter placeholder), title from
`typeConfig.gallery.titleField ?? schema.displayFieldName ?? 'name'`, up to 4
`cardFields ?? visibleColumns` rendered via `FieldRenderer`, card click-through, same
query/pagination as the table. **Not delivered:** attachment-field image resolution
(no attachment field type renders images today — `url` fields only), cover-crop
controls, inline edit on cards.

## 2. UI samples

Toolbar adds `[Image: Photo ▾]` (url fields + None) when gallery is active. Cards:
16:9 image (or placeholder with the title's initial), bold title, label+value pairs
below, hover ring, whole card clickable.

## 3. Data & API contracts

- Same records query as the table — sort/filters/pagination untouched. No backend
  change.
- Image: `String(value)` must start `http(s)://` to render an `<img loading="lazy">`;
  anything else falls back to the placeholder (no broken-image icons; `onError` also
  swaps to the placeholder).
- Title: gallery `titleField` → schema `displayFieldName` → `name` → record id.
- Card body fields come from the view's `visibleColumns` (minus the title field),
  capped at 4.

## 4. DB migrations

None.

## 5. File-by-file code changes

`components/GalleryGrid/` (new, +tests) · `ObjectListPage.tsx` (image picker, renderer
branch) · `en.json` (`altViews.*`).

## 6. Test plan

Vitest: cards render with title + body fields, image renders for http(s) URLs,
placeholder for null/non-URL, click-through, field cap at 4. Playwright post-deploy:
switch to gallery, open a record. `/verify` green.

## 7. Docs to update (same PR)

Parent README slice row → SHIPPED · status.md · memory.

## 8. Risks & open questions

- External image URLs render from arbitrary hosts (user data) — same exposure as the
  `url` FieldRenderer link today; CSP for the app shell is the systemic control.
- Attachment-backed images deferred until an attachment field type exists end-to-end.
