# Slice 1 — `FieldControl` Registry (keystone)

> Child of [README.md](./README.md). Extends its [unified core](./README.md#the-unified-core)
> and [Reuse Map](./README.md#reuse-map). This is the foundation every later slice consumes.

## 1. Goal & scope

**Delivers:** one `FieldType`-keyed registry in `@kelta/components` that unifies today's split
display (`FieldRenderer`, view-only) and edit (`formFieldRenderers`) paths into a single entry
per type: `{ View, Edit, InlineEdit, coerce, validate }`. Adds the **missing edit/inline
controls** so every field type is editable. Keeps plugin overrides.

**Does NOT:** change any page yet (Slices 2–4 consume it), touch the backend, or alter the client
rule engine (Slice 6). It is a pure additive library slice — existing `FieldRenderer` /
`formFieldRenderers` stay in place until their consumers migrate, then get deleted in Slice 8.

**Conforms to:** parent "keystone is a `FieldControl` registry"; plugin override via
`componentRegistry` preserved.

## 2. UI samples

Same visual output as today's `FieldRenderer` for `View` (parity-locked by golden snapshot).
New editors:

```
reference/lookup/master_detail  → LookupSelect (searchable, resolves display label)
multi_picklist                  → MultiPicklistSelect (chips)
rich_text                       → RichTextEditor (view = sanitized HTML via existing stripHtml)
json                            → JSON textarea w/ parse-validate (invalid → field error, no submit)
geolocation                     → two numeric inputs (lat, lng) with range validation
formula/rollup_summary/auto_number/encrypted → View + disabled Edit (server-computed; never POSTed)
```

InlineEdit (click value in a grid/detail):
```
[ Acme Corp ▼ ]   ← click reference value opens LookupSelect popover; Esc cancels, Enter/blur PATCHes
```

## 3. Data & API contracts

```ts
// kelta-web/packages/components/src/record/fieldControl/types.ts
export interface FieldControlContext {
  tenantSlug?: string
  targetCollection?: string      // reference/lookup/master_detail
  displayLabel?: string          // resolved FK label
  enumValues?: string[]          // picklist/multi_picklist
  globalPicklistId?: string      // from fieldTypeConfig
  readOnly?: boolean             // layout override / server-computed
  required?: boolean
}
export interface FieldViewProps   { type: FieldType; value: unknown; ctx: FieldControlContext; truncate?: boolean; className?: string }
export interface FieldEditProps   { type: FieldType; value: unknown; ctx: FieldControlContext; onChange(next: unknown): void; onBlur?(): void; error?: string; id?: string }
export interface FieldInlineProps extends FieldEditProps { onCommit(next: unknown): void; onCancel(): void }

export interface FieldControl {
  View: React.ComponentType<FieldViewProps>
  Edit: React.ComponentType<FieldEditProps>
  InlineEdit: React.ComponentType<FieldInlineProps>   // may === Edit wrapped in commit/cancel affordances
  coerce(raw: unknown): unknown                        // string "10" → 10; "" → null (mirrors DefaultQueryEngine coercion)
  validate(value: unknown, ctx: FieldControlContext): string | null  // client-side; server is the gate
  editable: boolean                                    // false for formula/rollup/auto_number/encrypted
}

// registry.ts
export function getFieldControl(type: FieldType): FieldControl   // falls back to a string control for unknown types
export function registerFieldControl(type: string, control: Partial<FieldControl>): void
// Plugin override: getFieldControl consults componentRegistry.getFieldRenderer(type) first for View,
// keeping the existing plugin contract intact.
```

No endpoint changes. Coercion + validate mirror `DefaultValidationEngine` semantics so client
pre-checks match server verdicts (server remains authoritative).

## 4. DB migrations

None — pure frontend library slice.

## 5. File-by-file code changes

**Create** under `kelta-web/packages/components/src/record/fieldControl/`:
- `types.ts` — the interfaces above.
- `registry.ts` — singleton map + `getFieldControl`/`registerFieldControl` + `componentRegistry`
  view fallback + unknown-type string control.
- `controls/` — one file per type group, each wrapping the reused component:
  - `text.tsx` (string/external_id), `number.tsx` (number/currency/percent), `boolean.tsx`,
    `date.tsx` (date/datetime), `contact.tsx` (email/phone/url), `picklist.tsx`,
    `multiPicklist.tsx` (→ `MultiPicklistSelect`), `reference.tsx` (→ `LookupSelect`),
    `richText.tsx` (→ `RichTextEditor` / sanitized view), `json.tsx`, `geolocation.tsx`,
    `computed.tsx` (formula/rollup_summary/auto_number/encrypted — View + disabled Edit).
  - Each `View` delegates to the existing `FieldRenderer` formatting to guarantee parity.
- `index.ts` — register all built-ins; re-export `getFieldControl`.

**Modify:** `kelta-web/packages/components/src/index.ts` — export the `record/fieldControl`
surface.

**Note on location:** `LookupSelect`/`MultiPicklistSelect`/`RichTextEditor`/`InlineEditCell`/
`FieldRenderer` live in `kelta-ui/app`, not `kelta-web`. Slice 1 either (a) moves the shared
primitives into `@kelta/components`, or (b) defines the registry in `kelta-ui/app` first and
promotes to `@kelta/components` in Slice 2. **Decision:** build the registry in
`kelta-ui/app/src/components/fieldControl/` in Slice 1 (co-located with the reused controls),
promote to `@kelta/components` in Slice 2 when `RecordShell` needs it cross-package. This avoids a
big cross-package move landing in the keystone PR. Update parent Reuse-Map paths when promoted.

## 6. Test plan

- **Vitest per type** (23 controls): `View` renders expected output; `Edit` fires `onChange` with
  coerced value; `InlineEdit` commits on Enter/blur, cancels on Esc; `validate` returns the right
  message (required, json-parse, geo-range, enum membership).
- **Golden snapshot:** capture all 21 `FieldRenderer` outputs *before* the refactor; assert each
  `FieldControl.View` matches (parity lock).
- **Plugin override:** a registered plugin field renderer still wins for `View`.
- No BE / harness / e2e in this slice (library only; e2e owned by Slice 8).

## 7. Docs to update

- `.claude/docs/conventions.md` — add the `FieldControl` registry contract (the canonical
  view+edit+inline source; how plugins override; coercion mirrors `DefaultValidationEngine`).
- `.claude/docs/status.md` — note the registry as the new field-rendering foundation.

## 8. Risks & open questions

- **Cross-package location** (see §5 decision) — keep the keystone PR small; promote in Slice 2.
- **Parity risk** — `View` MUST match `FieldRenderer` exactly; the golden snapshot is the guard.
- **`json`/`geolocation` edit UX** — first-pass textarea/inputs; richer editors are a follow-up,
  not a blocker.
- Server-computed types must never be POSTed even if a plugin marks them editable — `coerce`
  drops them from the payload; assert in test.
