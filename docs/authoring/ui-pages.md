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

`type` selects the widget. `GET /api/pages/widgets` is the machine-readable
catalogue of every built-in:

```json
{ "widgets": [
  { "type": "heading", "label": "Heading", "category": "content",
    "acceptsChildren": false, "defaultProps": { "text": "Heading", "level": "h2" },
    "propSchema": [ { "key": "text", "label": "Text", "kind": "text", "bindable": true } ],
    "source": "builtin" } ] }
```

`propSchema[].bindable` says whether that prop accepts a binding;
`acceptsChildren` says whether the widget may carry a `children` array.
The file is generated from the builder's widget registry, so it never
drifts from what the palette offers. Page component types contributed at
runtime by a **plugin or a module UI bundle** are registered in the
browser only and are not in this catalogue — see Validation below.

`props` values are either literals or bindings (below).

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
  a page declaring more is rejected on save; the client also drops the
  extras at render time.
- **At most 200 rows per repeater/list widget** (`MAX_REPEATER_ROWS`) —
  this is a per-widget rendering cap, not a whole-page row cap. A data
  source's own `limit` is separately capped at 200, the server's
  page-size ceiling (see `kelta docs jsonapi`), and a larger one is
  rejected on save.

`filter` is a field-to-value map, and every entry is compared with `EQ` —
that is the only operator the client fetch emits. An operator map
(`{ "amount": { "GT": 100 } }`) would be serialized into the query string
as `[object Object]`, so it is rejected rather than silently mis-filtered.
Filter values may themselves be bindings.

## Validation

Two endpoints let an author check a page before — or instead of — saving
it, and the same checks run on every write:

- `GET /api/pages/config-schema` — the JSON Schema for the `config`
  document above (draft 2020-12).
- `POST /api/ui-pages/validate` — body is the config (or
  `{ "config": { ... } }`), response is
  `{ "valid": true|false, "errors": [ { "path", "message", "severity" } ] }`.
  `path` is a JSON Pointer into the config, e.g. `/components/0/type`.

`severity` is `error` or `warning`, and `valid` answers "would this
save?" — it is false only when an `error` was found. A **before-save hook
on `ui-pages` rejects a write with any `error`** (HTTP 400); warnings are
reported but still save, so a page can be authored incrementally.

| Problem | Severity |
|---|---|
| Widget `type` missing, or not in the built-in catalogue | error |
| `components` / `children` / `dataSources` not an array | error |
| More than 12 data sources | error |
| Data source without a `name` or `collection`, or with a duplicate name | error |
| Data source `limit` outside 1–200, or an unknown `mode` | error |
| A filter operator other than `EQ` | error |
| A binding (`$bind` or `{{ ... }}`) naming a data source that is not declared | warning |

Because the catalogue covers built-ins only, a page using a plugin- or
module-contributed component type is rejected as an unknown type. Ship
such a widget through the builder's widget registry if its pages need to
be saveable.

## Minimal example

A heading plus a repeater bound to a data source:

```json
{
  "schemaVersion": 2,
  "dataSources": [
    { "name": "accounts", "collection": "accounts", "mode": "list", "limit": 50 }
  ],
  "components": [
    { "id": "h1", "type": "heading", "props": { "text": "Accounts", "level": "h2" } },
    {
      "id": "r1",
      "type": "repeater",
      "props": { "source": { "$bind": "data.accounts" } },
      "children": [
        { "id": "t1", "type": "text", "props": { "content": "{{ item.name }}" } }
      ]
    }
  ]
}
```

## Authoring with `apply_page` and the CLI

`apply_page` (kelta-mcp, admin) validates a page's `config` via `POST
/api/ui-pages/validate` — same checks as Validation above — *before*
creating or updating anything, then upserts the `ui-pages` row keyed on
`path` (falling back to `slug` when path itself doesn't match an existing
row, e.g. a route rename). An invalid config fails the whole call with a
structured error (the validate response's JSON Pointer) and writes
nothing. Applying the identical body twice reports
`{"action":"unchanged",...}` on the second call; changing `config` (or
another attribute) reports `{"action":"updated","changed":[...]}` naming
only the keys that actually differ.

The CLI mirrors this: `kelta pages apply <file.json>` runs the same
validate-then-upsert sequence (`--dry-run` runs validation only and
writes nothing) and prints the same summary shape as `apply_page` —
`{action, id, path, changed, published}` — instead of the full record;
`changed` names only the keys that actually differ, counting a changed
`config` once rather than per nested key. Pass `--raw` to print the full
`ui-pages` record instead of the summary. `kelta pages publish <path>`
sets `published: true` on the page served at that route and prints
`{action: "published"|"unchanged", id, path}`.
