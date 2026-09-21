---
title: "@kelta/plugin-sdk"
description: Extend the front end — the Plugin interface and context, the component registry for field renderers and page components, and how module UI bundles register at runtime.
section: sdk
order: 30
---

`@kelta/plugin-sdk` defines how code that is not part of the platform extends the console and app.

## Plugin

```ts
import { BasePlugin, ComponentRegistry, type PluginContext } from '@kelta/plugin-sdk';

export class ErpPlugin extends BasePlugin {
  name = 'erp';
  version = '1.0.0';
  async init(ctx: PluginContext) {
    ComponentRegistry.registerFieldRenderer('erp-status', ErpStatusBadge);
    ComponentRegistry.registerPageComponent('erp-orders', '/erp/orders', ErpOrders);
  }
}
```

`PluginContext` gives a plugin the API `client`, the current `user` (or `null`) and a `router`. `Plugin` declares
`name`, `version`, `init(context)`, `mount(container)` and `unmount()`; `BasePlugin` provides defaults for the
lifecycle methods.

## Component registry

| Registration | Used by |
|---|---|
| `ComponentRegistry.registerFieldRenderer(fieldType, component)` (`FieldRendererProps`) | `ResourceForm` / `ResourceDetail` and the app's record pages, for custom field UIs |
| `ComponentRegistry.registerPageComponent(name, route, component)` (`PageComponentProps`) | page-builder palette entries and routed pages |
| `list*()` | Setup → Integration → Modules shows what is registered |

## How plugins load in the app

The app's `PluginProvider` initialises the configured plugins at startup, syncs their registrations into the
app's registry, and isolates a failing plugin so it cannot break the shell. Plugins are compiled into the app
bundle you deploy — there is no runtime upload of arbitrary front-end code except through signed
[module UI bundles](/docs/platform/modules/), which are fetched from `GET /api/modules/{id}/ui-bundle.js`,
verified against the module's signature, and evaluated to register components through the same registry.

Page-builder pages that use plugin or module components render in the browser, but the server-side page
validator does not know those component types yet — see [Page builder](/docs/console/page-builder/#widgets).
