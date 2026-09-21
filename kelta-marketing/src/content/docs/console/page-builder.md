---
title: Page builder
description: Compose custom pages from a widget palette — bindings, variables, data sources, events and actions, conditional visibility, validation and publishing — and generate them with AI.
section: console
order: 50
---

The page builder (Setup → UI customization → Pages, `CUSTOMIZE_APPLICATION`) builds custom screens from widgets
and publishes them into the end-user app at `/app/p/<slug>`. Pages are records in `ui-pages`; the `config` JSON
that describes them is documented in the [authoring reference](/docs/reference/ui-pages/).

## The canvas

- A 12-column responsive grid; every component has a `span` per breakpoint.
- A **palette** grouped by category, dragged onto the canvas or into a container.
- An **inspector** generated from each widget's prop schema — literal values or bindings.
- Undo/redo, a data-source panel, a variables panel, and a live preview with real data.
- **Save** stores the draft; **Publish** makes it live. Saving validates the config first.

## Widgets

Thirty-three built-ins ship, in six categories:

| Category | Widgets |
|---|---|
| Content | `heading`, `text`, `button`, `link`, `image`, `icon` |
| Layout | `container`, `card`, `grid`, `row`, `column`, `divider`, `tab-panel` |
| Data | `table`, `list`, `repeater`, `field-value`, `metric`, plus `chat-panel`, `video-visit`, `appointment-scheduler` for optional feature areas |
| Input | `form`, `text-input`, `number-input`, `checkbox`, `datepicker`, `dropdown`, `lookup`, `multi-picklist`, `rich-text` |
| Navigation | `nav`, `tabs` |
| Chart | `chart` |

`GET /api/pages/widgets` returns the machine-readable catalogue (props, which are bindable, accepted children,
events); it is generated from the builder's registry so it never drifts. Plugins and module UI bundles can add
components to the palette in the browser, but pages that use them cannot be validated server-side yet.

## Data

- **Data sources** — up to 12 per page, each `{ name, collection, mode: "list" | "single", filter, sort, limit ≤ 200 }`,
  fetched through the normal authorized API. A list widget renders at most 200 rows.
- **Bindings** — a prop value can be `{ "$bind": "data.invoices.length" }` or a string with merge tags:
  `{{ record.name }}` (path) or `{{= data.invoices.length > 0 }}` (expression, formula engine). Scope roots are
  `record`, `vars`, `page`, `item` (inside repeaters) and `data`.
- **Variables** — page state (`vars.*`) set by actions, plus **computed variables** derived from expressions that
  update as their inputs change.

## Conditional visibility

Every widget has a `visible` prop. Absent means visible; a bound expression that cannot be resolved evaluates to
hidden (fail closed), so a page never shows a widget whose condition is broken.

## Events and actions

Widgets emit events (`onClick`, `onSubmit`, `onChange`, `onLoad`) that run an ordered list of actions; the first
failure stops the sequence:

`runFlow` · `navigate` · `openUrl` · `createRecord` · `updateRecord` · `refreshData` · `setVar` · `showToast`

`runFlow` calls `POST /api/flows/{id}/execute` with the page's payload as `$.input`; `createRecord` /
`updateRecord` go through the authorized API, so validation, hooks and security apply.

## Forms

The `form` widget groups typed inputs; client-side validation is advisory, the server enforces validation rules,
field-level security and permissions on submit.

## Validation

`POST /api/ui-pages/validate` checks a config without saving; the same validator runs on every write and rejects
`error`-severity problems (unknown widget type, more than 12 sources, a source without a collection, a limit over
200, a non-`EQ` filter) while allowing warnings (a binding to a not-yet-declared source), so pages can be built
incrementally.

## Publish and navigate

Publishing sets `published: true`; the page is served at `/app/p/<slug>` and can be added to an app's menu with
the path `/p/<slug>` ([Apps and navigation menus](/docs/console/apps-and-menus/)). A page can also be marked as
the tenant's **home page**. Published pages are cached for cold-offline viewing.

## Authoring outside the browser

```bash
kelta pages apply page.json --dry-run     # validate only
kelta pages apply page.json               # create-or-update keyed on path
kelta pages publish /open-invoices
```

MCP: `apply_page` (validate-then-upsert, keyed on `path`).

## Generating a page with AI

The [AI assistant](/docs/console/ai-assistant/) can propose a page (`propose_ui_page`): it validates the widget
tree against the catalogue and creates an **unpublished draft**. Publishing stays a human action in the builder.
