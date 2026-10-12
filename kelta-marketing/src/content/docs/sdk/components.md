---
title: "@kelta/components"
description: React building blocks over the SDK — provider and hooks, data table, resource form and detail, filter builder, layout renderer with visibility and client rules, record-detail blocks.
section: sdk
order: 20
---

`@kelta/components` is the React library the console and end-user app are built from; use it to build your own
front end on the same primitives. Peer dependency: `@kelta/sdk` (and React 19).

> **Deprecated.** Exports the Kelta apps no longer use are marked `@deprecated` and are scheduled for removal
> in the next major release: `ResourceDetail` (and its registry), `PageLayout`,
> `TwoColumnLayout`, `ThreeColumnLayout`, `LayoutRenderer`, the `useResource` / `useResourceList` /
> `useDiscovery` hooks, and the public re-exports of `parseVisibilityRule`, `evaluateVisibilityRule`,
> `RuleEngine`, `downstreamRules` and `InteractiveMap` (use `isVisible`, `useLayoutRules` and `AddressMap`).
> The example below still works; copy any of these into your app if you depend on them.

```tsx
import { KeltaProvider, useResourceList, DataTable } from '@kelta/components';

<KeltaProvider client={client}>
  <Invoices />
</KeltaProvider>

function Invoices() {
  const { data, isLoading } = useResourceList<Invoice>('invoices', { size: 50 });
  // or let the table fetch for itself:
  return <DataTable<Invoice> resourceName="invoices" columns={columns} pageSize={50} onRowClick={open} />;
}
```

## Exports

| Group | Exports |
|---|---|
| Context | `KeltaProvider`, `useKeltaClient`, `useCurrentUser` |
| Hooks | `useResource`, `useResourceList`, `useDiscovery` (all deprecated) |
| Data | `DataTable` (`ColumnDefinition`) |
| Forms and detail | `ResourceForm` (+ `setComponentRegistry`/`getComponentRegistry` for custom field renderers), `ResourceDetail` (+ its own registry) (deprecated) |
| Filtering | `FilterBuilder` |
| Navigation and layout | `Navigation`; `PageLayout`, `TwoColumnLayout`, `ThreeColumnLayout` (deprecated) |
| Layout rendering | `LayoutRenderer` (deprecated); visibility rules `isVisible` (`parseVisibilityRule`, `evaluateVisibilityRule` deprecated); client rules `useLayoutRules`, `topologicalSort` (`RuleEngine`, `downstreamRules` deprecated) |
| Record-detail blocks | `RecordHeader`, `FieldSection`, `AddressMap`, `InteractiveMap` (deprecated), `StatStrip`, `ScoreCard`, `TagsCard`, `MetadataCard` |
| Chat primitives | `MessageList`, `MessageComposer`, `ConversationListItem` |
| UI primitives | `DropdownMenu*` (re-exported so host apps share one Radix context) |

`LayoutRenderer` (deprecated) renders a [page layout](/docs/console/page-layouts/) fetched from the API — sections,
placements, visibility rules — and `useLayoutRules` evaluates the layout's compute/validate/default/transform
rules as the user edits, matching what the app does.
