# Slice 3 — Apps (Nav v2)

> Child spec of [App Platform (Phase 3)](./README.md), authored with the implementation
> (own PR — the only backend-touching slice). **Not security-typed**: menu/app config is
> presentation metadata, not an authorization boundary (nav-item `policies[]` remains
> unenforced — flagged in the parent as a future security slice).

## 1. Goal & scope

**An app IS a `ui-menu`.** `ui-menus` gains `icon` / `isDefault` / `active`; the
end-user shell renders ONE active menu's items as the nav (instead of flattening every
menu) plus an app switcher in `TopNavBar` (hidden when fewer than two active menus).
The selected app persists per user via `usePreferenceValue('nav','active-app')`
(Phase 2 seam) with `isDefault` → first-by-`displayOrder` fallback. Menu/menu-item
writes now broadcast `kelta.config.menu.changed.<tenantId>` (Critical Rule 1 — today
only the serving pod evicts its `SystemCollectionCache`; a 10-min Caffeine TTL hid the
staleness); every pod evicts the `ui-menus`/`ui-menu-items` response caches on the
event. `MenuBuilderPage` authors the new fields. **Not delivered:** per-app theming or
home page, nav-item authz enforcement, app-level permissions, drag-reorder of apps
(displayOrder numeric field as today).

## 2. UI samples

TopNavBar left: the app-launcher button becomes the switcher when ≥2 active apps —
`[⊞ Sales ▾]` → dropdown listing apps (icon + name, check on active). Menu form dialog
grows: Icon (lucide name), `[✓] Default app`, `[✓] Active`.

## 3. Data & API contracts

- **Migration V164** (verified head V163): `ALTER TABLE ui_menu ADD COLUMN icon
  VARCHAR(100), is_default BOOLEAN NOT NULL DEFAULT false, active BOOLEAN NOT NULL
  DEFAULT true`. Existing menus stay active/non-default — single-menu tenants see zero
  change (switcher hidden).
- `SystemCollectionDefinitions.uiMenus()` adds the matching fields (`isDefault` →
  `is_default`); served through the same JSON:API path + bootstrap include — the
  client's `unwrapList` flattens attributes, so `BootstrapConfig.menus` picks the new
  fields up with a type widening only.
- **NATS** (new subject, messaging table updated): two `BeforeSaveHook` registrations
  of one `MenuConfigEventPublisher` class (`ui-menus` + `ui-menu-items`) publish
  `kelta.config.menu.changed.<tenantId>` (payload `CollectionChangedPayload`, mirrors
  `UIPageConfigEventPublisher`); `MenuCacheInvalidationListener` (broadcast
  subscription `worker-menu-cache`) evicts both collections' entries from
  `SystemCollectionCache` on every pod.
- **Shell resolution** (`navTabs.ts`): `activeMenus(menus)` = `active !== false`,
  sorted by `displayOrder` then name; `resolveActiveMenu(menus, preferredId)` =
  preferred → `isDefault` → first. `buildNavTabs` unchanged (the shell passes
  `[activeMenu]`). No active menus ⇒ empty nav (same as no menus today).
- Selected app: `usePreferenceValue<string>('nav','active-app')` — server-persisted,
  localStorage-mirrored; an id that no longer resolves falls back silently.
- **Behavior change (intentional):** multi-menu tenants stop seeing all menus'
  items flattened; the switcher makes every app reachable.

## 4. DB migrations

`V164__add_ui_menu_app_fields.sql` (above). Check the directory head before merge.

## 5. File-by-file code changes

Backend: migration · `SystemCollectionDefinitions.java` ·
`worker/listener/MenuConfigEventPublisher.java` (new, +test) ·
`worker/listener/MenuCacheInvalidationListener.java` (new, +test) ·
`NatsSubscriptionConfig` · `FlowConfig` (two hook beans). Frontend:
`sdk/admin/types.ts` (`UIMenu` fields) · `types/config.ts` · `navTabs.ts` (+tests) ·
`EndUserShell.tsx` · `TopNavBar.tsx` (switcher, +tests) · `MenuBuilderPage.tsx` (form
fields) · `en.json`. Docs: CLAUDE.md messaging table · `integrations.md` · status.md ·
parent README.

## 6. Test plan

Worker unit: publisher (subject/payload per create/update/delete, both collection
registrations), invalidation listener (evicts both collections, skips on missing
tenant). Vitest: `activeMenus`/`resolveActiveMenu` (filter, sort, preferred/default/
first/fallbacks), TopNavBar switcher (hidden <2 apps, lists apps, fires onAppChange),
existing navTabs suite green. Playwright post-deploy: two apps, switch, assert nav
swap + persistence across reload. `/verify` green.

## 7. Docs to update (same PR)

CLAUDE.md Messaging table (`kelta.config.menu.changed.<tenantId>`) · integrations.md
NATS section · status.md · parent README slice row · memory.

## 8. Risks & open questions

- The flatten→single-menu behavior change is the feature; PR calls it out. Rollback =
  revert (fields are additive).
- `ui-menu-items` writes publish per item — a bulk menu save fans out N events; the
  eviction is idempotent and cheap. Coalescing deferred until it shows up in metrics.
- Icon is a free-text lucide name in the menu form v1 (items use a predefined picker);
  unknown names render a fallback icon in the switcher.
