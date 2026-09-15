# Authoring Custom UI Pages

A custom page (the page/screen builder) is a tree of components rendered
inside the end-user app at a chosen path. Stored on the `ui-pages`
collection.

## Collection shape

`ui-pages` has only five real columns: `name`, `path`, `slug`, `title`,
and `config` (JSON) — plus `active` (default true) and `published`
(default false). Everything about the page's content — layout,
components, variables, data sources, access, and home-page status — lives
**inside `config`**, not as top-level attributes. Sending `layout` or
`components` as top-level attributes is silently dropped by the worker;
they only take effect nested under `config`.

## `config` schema

```json
{
  "schemaVersion": 2,
  "components": [ /* PageComponent tree, see below */ ],
  "variables": [ /* PageVariable[] */ ],
  "dataSources": [ /* PageDataSource[], see below */ ],
  "access": { "requiredPermission": "..." },
  "isHomePage": false
}
```

`isHomePage: true` makes this page override the end-user app's default
landing page (`/app/home`). This is a convention, not an enforced
uniqueness constraint — if more than one published+active page sets it,
the resolver picks the first match; author at most one per tenant.

## Component tree

Each `PageComponent` is `{ id, type, props, events?, span?, children? }`.
`span` is a responsive 12-column grid span per breakpoint:
`{ base, sm?, md?, lg? }`, each an integer 1-12.

## Widget catalogue

`type` selects the widget. Consult the page builder's component palette
in the admin UI for the current full list (it changes as widgets are
added); common types include text, image, button, form, table/repeater,
and container/layout components. `props` values are either literals or
bindings (below).

## Data bindings

A prop value can be a binding object, `{ "$bind": "record.name", "mode":
"path" }` (`mode` defaults to `"path"`), or a literal string containing
one or more merge tags:

```
{{ data.accounts.length }}          -- path mode (default)
{{= data.accounts.length > 0 }}     -- expr mode: leading "=" runs the formula engine
```

Path-mode resolution is a dotted walker supporting array indices and
`.length` (e.g. `record.contacts[0].name`, `data.accounts.length`).
Valid scope roots are `record`, `vars`, `page`, `item`, and `data`. The
server never parses `$bind` or `{{...}}` — it stores and returns `config`
verbatim; all binding resolution happens client-side when the page
renders.

## Data sources

Each `PageDataSource` is `{ name, collection, fields?, filter?, sort?,
limit?, mode: "list" | "single", recordId? }`, fetched client-side over
the normal authorized JSON:API path. Two limits apply:

- **At most 12 data sources per page** (`MAX_PAGE_DATA_SOURCES`) —
  additional entries are dropped, not rejected.
- **At most 200 rows per repeater/list widget** (`MAX_REPEATER_ROWS`) —
  this is a per-widget rendering cap, not a whole-page row cap. A data
  source's own `limit` is separately clamped to the server's page-size
  ceiling of 200 (see `kelta docs jsonapi`).
