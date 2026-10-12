# Slice 4 — Tenant i18n Authoring

> Child spec of [App Intelligence (Phase 4)](./README.md), authored with the
> implementation (same PR). Worker + FE; **not security-typed** (translations are
> presentation config; writes ride the normal system-collection authz).

## 1. Goal & scope

Tenants override UI text per locale without a deploy: a new tenant-scoped
`ui-translations` system collection (`locale`, `key`, `value`; unique per
tenant+locale+key), served through the standard dynamic collection API, broadcast on
change per Critical Rule 1, loaded at bootstrap, and merged into `I18nContext` as an
**overlay** — tenant value wins, then the locale bundle, then the en bundle (the
existing chain). An admin Translations page (Setup) does CRUD with a locale filter.
The overlay accepts ANY key, including a documented metadata-label keyspace
(`meta.collection.<name>.displayName`) for future consumption. **Not delivered:**
automatic wiring of metadata labels into components (keyspace convention only),
missing-key report (editor v1 is CRUD + search), per-user locale sync to the server,
new locales beyond the six bundles.

## 2. UI samples

Setup → Translations: locale select `[en ▾]`, search box, table `key | value | ✎ 🗑`,
add row (`listPower.groupBy` → "Agrupar por"). End-user app: the overlaid string
renders immediately after reload (bootstrap-cached).

## 3. Data & API contracts

- **Migration V165** (verified head V164): `ui_translation` table — `id`, `tenant_id`,
  `locale varchar(10)`, `translation_key varchar(200)`, `translation_value
  varchar(2000)`, audit columns; `UNIQUE (tenant_id, locale, translation_key)`;
  RLS `tenant_isolation` + `admin_bypass` (V163 pattern).
- `SystemCollectionDefinitions.uiTranslations()` — fields `locale` / `key`
  (→`translation_key`) / `value` (→`translation_value`), display field `key`;
  served by the dynamic collection route as `/api/ui-translations` (no static
  gateway route needed — same as `ui-menus`).
- **NATS (Rule 1)**: `TranslationConfigEventPublisher` (BeforeSaveHook) publishes
  `kelta.config.translation.changed.<tenantId>`;
  `TranslationCacheInvalidationListener` (broadcast `worker-translation-cache`)
  evicts the `SystemCollectionCache` `ui-translations` entries on every pod.
  Messaging table + integrations.md updated.
- **Bootstrap**: `bootstrapCache` fetches `/api/ui-translations?page[size]=2000`
  (failure-tolerant → `{}`) and groups rows into
  `BootstrapConfig.translations: Record<locale, Record<key, value>>`.
- **Overlay**: `I18nContext` gains `setTenantOverlay(map)`; `t()` checks
  `overlay[locale]?.[key]` FIRST (flat dotted keys, same `{{param}}` interpolation),
  then the existing bundle→en→inline-fallback chain. A `TenantTranslationsBridge`
  component (mounted inside Config+I18n providers in `App`) pushes the bootstrap map
  in — `I18nProvider` itself stays ConfigProvider-free so every existing test harness
  keeps working.
- **Admin editor**: `TranslationsPage` (`/{tenant}/translations`, Setup entry,
  `CUSTOMIZE_APPLICATION`-gated) — locale filter, search, add/edit/delete rows via
  the JSON:API; duplicate (tenant, locale, key) surfaces the server's constraint
  error.

## 4. DB migrations

`V165__create_ui_translation.sql` (above). Check the directory head before merge.

## 5. File-by-file code changes

Worker: migration · `SystemCollectionDefinitions.java` ·
`listener/TranslationConfigEventPublisher.java` (new, +test) ·
`listener/TranslationCacheInvalidationListener.java` (new, +test) ·
`NatsSubscriptionConfig` (+test count) · `FlowConfig`. Harness:
`UiTranslationScenarioTest` (unique constraint via the real API/DB).
FE: `types/config.ts` · `utils/bootstrapCache.ts` (+`groupTranslations`, +tests) ·
`context/I18nContext.tsx` (overlay, +tests) · `components/TenantTranslationsBridge`
(in `App.tsx`) · `pages/TranslationsPage/` (new, +tests) · `App.tsx` route ·
`SetupHomePage` entry · `en.json`. Docs: CLAUDE.md messaging table ·
integrations.md · status.md · parent README.

## 6. Test plan

Worker unit: publisher subject/payload/skip, listener evictions/malformed (mirror the
menu pair). Harness: create translation via API → duplicate (tenant, locale, key)
rejected by the DB constraint; different locale same key succeeds. FE Vitest: overlay
precedence (tenant > bundle > en > inline fallback), `{{param}}` interpolation in
overlay values, `groupTranslations`, bridge pushes config into i18n, TranslationsPage
CRUD wiring. Playwright post-deploy: author an override, reload, assert the string.
`/verify` green.

## 7. Docs to update (same PR)

CLAUDE.md Messaging table · integrations.md · status.md · parent README · memory.

## 8. Risks & open questions

- Overlay values are plain text through the same `interpolate()` — never HTML; the
  editor stores strings verbatim and React escaping covers rendering.
- 2000-row bootstrap page is the v1 ceiling — beyond that, per-locale lazy fetch is
  the follow-up (bootstrap already tolerates the endpoint failing).
- Metadata-label keyspace is convention-only until a consumer slice wires it into
  collection/field rendering.
