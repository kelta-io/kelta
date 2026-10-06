# App Platform (App-UX Phase 3) — Parent Spec

> **Status:** parent planning spec. Phase 3 of the app-UX roadmap (Phase 1
> `specs/app-surfacing/` and Phase 2 `specs/app-data-entry/` are complete). Each slice
> below gets a child spec in this directory before (or with) its implementation PR.
> Child specs extend, never contradict, this parent.
>
> Source-verified against the codebase on 2026-07-08 (post page-builder-parity slices
> 2a–2g, post app-data-entry slices 1–7). If code and this doc disagree, trust the code
> and fix this doc.

## Goal

The page-builder-parity effort (`specs/page-builder-parity.md`) shipped a real builder:
widget registry, dnd-kit layout, `{$bind}` expressions, page variables + data sources,
an event/action runtime, typed forms, and widget breadth. Phase 3 turns builder output
plus runtime metadata into something that behaves like an **app platform**:

1. **Conditional visibility** — show/hide any widget from an expression.
2. **Computed variables** — derived page state that re-evaluates reactively.
3. **Apps (nav v2)** — named workspaces with their own navigation + an app switcher.
4. **Undo/redo** — a real editing history in the builder.

## Verified current state (trust these over stale docs)

- **Bindings**: any prop may be `{ $bind, mode:'path'|'expr' }`, resolved client-side in
  `PageBuilderPage/model/resolveBindings.ts` over `@kelta/formula`; scope =
  `{vars, data, page}` (`model/bindingScope.ts`). **There is no `visible` prop or any
  conditional rendering** in `widgets/renderTree.tsx` — every node always renders.
- **Variables**: `PageVariable {name, type: string|number|boolean|json, default?}`
  (`PageBuilderPage/pageConfig.ts`); `hooks/usePageVariables.ts` seeds defaults + exposes
  `setVar` (used by the 2e `setVar` action). **No computed/derived kind.**
- **Builder editor state**: separate `useState`s in `PageBuilderPage.tsx` (~line 550) —
  `components`, `pageVariables`, `pageDataSources`, `pageAccess`, `pageIsHomePage`;
  `model/treeOps.ts` mutations are pure. **No undo/redo anywhere in the app.**
- **Nav model**: `ui-menus` (fields `name`, `description`, `displayOrder` — no
  default/active/icon) and `ui-menu-items` (`menuId` master-detail, `label`, `path`,
  `icon`, `displayOrder`, `active`) in `SystemCollectionDefinitions.java`. Bootstrap
  fetches `/{slug}/api/ui-menus?include=ui-menu-items` into `BootstrapConfig.menus`
  (`types/config.ts`, `utils/bootstrapCache.ts`); `EndUserShell/navTabs.ts
  buildNavTabs()` **flattens the items of ALL menus into one nav bar** (`NavTab.kind ∈
  collection|page|dashboard|report`; other paths dropped). `MenuBuilderPage` authors
  menus/items (nested items are editor-only — flattened on save). **No app/workspace
  concept, no switcher, no default-menu field.**
- **Menu config invalidation**: system-collection writes evict the router-local
  `SystemCollectionCache` on the serving pod; **there is no NATS broadcast for menu
  changes** (client freshness relies on bootstrap refetch). Critical Rule 1 applies the
  moment we make menu shape load-bearing — slice 3 adds the broadcast.
- **User preference seam** (Phase 2 slice 1): `usePreferenceValue(prefType, prefKey)` —
  server row per user + localStorage mirror. The app switcher persists selection with it.
- Flyway numbering continues pre-flatten history: **V162/V163 used; check the migration
  dir for the true head before adding V164+.**

## Key decisions

- **An "app" IS a `ui-menu`.** The model already groups nav destinations per menu; the
  flatten-all-menus behavior in `buildNavTabs` is an accident of the single-menu era,
  not a contract. Slice 3 adds `icon`/`isDefault`/`active` to `ui-menus`, renders ONE
  menu's items at a time, and puts an app switcher in `TopNavBar`. No new collection.
  Single-active-menu tenants see zero change (switcher hidden).
- **Visibility is a universal base prop, not per-widget.** `renderTree` checks a
  resolved `visible` before rendering any node (absent ⇒ `true`); the inspector offers
  it on every widget with the standard `fx` literal↔expression toggle. Runtime hides;
  the editor canvas ghosts (opacity + badge) so authors can still select hidden nodes.
- **Computed variables are client-derived, never persisted values.** `PageVariable`
  gains `kind?: 'static'|'computed'` + `expression?`; computed vars evaluate over the
  binding scope with dependency ordering + cycle detection (cycle ⇒ `null` + one console
  warn, fail-open like the 2d expression rules); `setVar` targeting a computed variable
  is a no-op with an error toast. Server stays uninvolved — same trust model as 2d/2e
  (client convenience; Cerbos/FLS enforced server-side on every data access).
- **Undo/redo snapshots the authored artifact, not UI state.** One history of
  `{components, variables, dataSources}` (selection/preview/drawer state excluded),
  capped depth, coalesced pushes for rapid prop edits, cleared on page switch. The
  pure `treeOps` + immutable state make snapshotting safe.
- **Menu changes get the Rule-1 NATS broadcast** (`kelta.config.menu.changed.<tenantId>`)
  published from a `BeforeSaveHook` on `ui-menus`/`ui-menu-items`, consumed by every pod
  to evict `SystemCollectionCache` — reference impl `CollectionConfigEventPublisher`.
  Messaging table + `integrations.md` updated in the same PR.

## Reuse Map

| Need | Reuse | Path |
|------|-------|------|
| Expression evaluation | `resolveBindings` / `@kelta/formula` | `kelta-ui/app/src/pages/PageBuilderPage/model/resolveBindings.ts` |
| Binding scope | `buildBindingScope` | `.../model/bindingScope.ts` |
| fx literal↔expression editor | `BindableField` | `.../inspector/fields/` |
| Page-settings authoring | `PageSettingsDrawer` + `VariablesSection` | `.../inspector/pageSettings/` |
| Pure tree mutations | `treeOps` | `.../model/treeOps.ts` |
| Server-persisted user pref | `usePreferenceValue` | `kelta-ui/app/src/hooks/usePreferenceStore.ts` |
| Nav tab mapping | `buildNavTabs`/`menuItemToTab` | `kelta-ui/app/src/shells/EndUserShell/navTabs.ts` |
| Menu authoring | `MenuBuilderPage` | `kelta-ui/app/src/pages/MenuBuilderPage/` |
| Config broadcast pattern | `CollectionConfigEventPublisher` | `kelta-worker/.../listener/` |
| Bootstrap config | `bootstrapCache` + `ConfigContext` | `kelta-ui/app/src/utils/`, `src/context/` |

## Slice plan

| Slice | Child spec | Axis |
|-------|-----------|------|
| 1 — Conditional visibility | `1-conditional-visibility.md` — **SHIPPED 2026-07-08** (universal bindable `visible`; runtime hides, editor ghosts/badges) | builder/runtime (FE) |
| 2 — Computed variables | `2-computed-variables.md` — **SHIPPED 2026-07-08** (bare-identifier expressions — the formula parser rejects dotted refs; data.* not readable in v1) | builder/runtime (FE) |
| 3 — Apps (nav v2) | `3-apps-nav.md` — **SHIPPED 2026-07-08** (V164 icon/isDefault/active on ui_menu; `kelta.config.menu.changed` broadcast; TopNavBar switcher persisted via user preference) | **backend+FE** (migration + NATS) |
| 4 — Builder undo/redo | `4-undo-redo.md` — **SHIPPED 2026-07-08** (effect-recorded history over {components,variables,dataSources}; 400ms coalescing, cap 50, ⌘Z/⇧⌘Z) — **PHASE 3 COMPLETE** | builder editor (FE) |

**Dependency order: none hard.** 1 and 2 share the binding layer (land 1 first for
review simplicity); 3 and 4 are independent and can pair with anything. Batch into
combined PRs per CI preference (suggest 1+2, then 3, then 4 — 3 is the only
backend-touching slice and reviews cleaner alone).

- **Slice 1 — Conditional visibility** (FE). Universal bindable `visible` prop:
  `renderTree` resolves it per node against the live scope and skips the subtree at
  runtime when falsy; editor canvas ghosts hidden nodes (selectable, eye-off badge);
  inspector "Visibility" row on every descriptor via a registry-level base prop (no
  per-widget schema edits). Absent prop ⇒ visible (zero behavior change for existing
  pages).
- **Slice 2 — Computed variables** (FE). `PageVariable.kind: 'computed'` +
  `expression`; evaluation order = dependency topological sort over `vars.*` references
  with cycle → `null` + warn; re-evaluates when data sources resolve or a static var
  changes; `setVar` on computed ⇒ error toast, no write; `VariablesSection` gains
  kind toggle + expression editor (reuses the fx editor); computed values feed
  `scope.vars` transparently (bindings/actions see them like any var).
- **Slice 3 — Apps (nav v2)** (backend+FE). Migration (check head; V164 expected) adds
  `icon`, `is_default`, `active` to `ui_menu` + `SystemCollectionDefinitions` fields;
  `MenuConfigEventPublisher` BeforeSaveHook broadcasts
  `kelta.config.menu.changed.<tenantId>` on menu/menu-item writes, all pods evict
  `SystemCollectionCache` (new `NatsSubscriptionConfig` entry); shell renders ONE
  active menu: switcher in `TopNavBar` (menus sorted by `displayOrder`, hidden when <2
  active menus), active app = `usePreferenceValue('nav','active-app')` → `isDefault`
  menu → first; `buildNavTabs(menu)` takes the selected menu only; `MenuBuilderPage`
  authors the new fields (icon picker reused, default toggle enforces at-most-one
  client-side, active toggle). Bootstrap shape unchanged (fields ride the same
  include). CLAUDE.md messaging table + `integrations.md` + `architecture.md` updated.
- **Slice 4 — Builder undo/redo** (FE). `useEditorHistory` hook wrapping the authored
  artifact `{components, variables, dataSources}`: push-on-change with 400ms coalescing
  for prop edits, structural ops push immediately; depth cap 50; Cmd/Ctrl+Z undo,
  Shift+Cmd/Ctrl+Z redo (no-op inside text inputs' native undo), toolbar buttons with
  disabled states; history resets on page open/save-reload; unsaved-changes flag stays
  accurate (undo back to the saved snapshot clears it).

Every FE slice: Vitest in-PR; post-deploy Playwright skip-gated under
`e2e-tests/tests/`; i18n keys for all new strings; DESIGN.md rules. Slice 3 backend:
worker unit tests + a `kelta-test-harness` scenario only if a real-DB behavior (RLS,
constraint) is in play — the column-add + broadcast path is covered by unit + existing
harness warm-up.

## Child-spec template

Identical to the app-data-entry template — every section present or "N/A — <reason>":
Goal & scope · UI samples · Data & API contracts · DB migrations (verify head; next
expected **V164**) · File-by-file changes · Test plan · Docs to update · Risks & open
questions.

## Risks / open questions

- **Multi-menu tenants change behavior in slice 3**: today all menus flatten into one
  bar; afterwards only the active app's items show. Intentional (that's the feature),
  but call it out in the PR; the switcher makes every item reachable.
- Computed-variable re-evaluation is synchronous over the scope on each change — fine
  at page scale (≤12 data sources, ≤200 repeater rows caps from 2d); no memo layer
  until proven needed.
- Undo history holds deep-cloned trees; 50 × a large page is still small (config JSON
  ≤ ~100KB), but the child spec must state the clone strategy (`structuredClone`).
- Per-item authz on nav: the menu builder's old "Access Policies" picker (menu-item
  `policies[]`) was removed — it read the deleted legacy `/api/policies` endpoint and
  `ui-menu-items` never stored the field. Per-item visibility, if wanted, is a future
  slice built on current RBAC (profiles / permission sets), not the legacy policy model.
