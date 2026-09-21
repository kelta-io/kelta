---
title: "@kelta/components"
description: React building blocks over the SDK — provider and hooks, data table, resource form and detail, filter builder, layout renderer with visibility and client rules, record-detail blocks.
section: sdk
order: 20
---

`@kelta/components` is the React library the console and end-user app are built from; use it to build your own
front end on the same primitives. Peer dependency: `@kelta/sdk` (and React 19).

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
| Hooks | `useResource`, `useResourceList`, `useDiscovery` |
| Data | `DataTable` (`ColumnDefinition`) |
| Forms and detail | `ResourceForm` (+ `setComponentRegistry`/`getComponentRegistry` for custom field renderers), `ResourceDetail` (+ its own registry) |
| Filtering | `FilterBuilder` |
| Navigation and layout | `Navigation`, `PageLayout`, `TwoColumnLayout`, `ThreeColumnLayout` |
| Layout rendering | `LayoutRenderer`; visibility rules `parseVisibilityRule`, `evaluateVisibilityRule`, `isVisible`; client rules `RuleEngine`, `useLayoutRules`, `topologicalSort`, `downstreamRules` |
| Record-detail blocks | `RecordHeader`, `FieldSection`, `AddressMap`, `InteractiveMap`, `StatStrip`, `ScoreCard`, `TagsCard`, `MetadataCard` |
| Chat primitives | `MessageList`, `MessageComposer`, `ConversationListItem` |
| UI primitives | `DropdownMenu*` (re-exported so host apps share one Radix context) |

`LayoutRenderer` renders a [page layout](/docs/console/page-layouts/) fetched from the API — sections,
placements, visibility rules — and `useLayoutRules` evaluates the layout's compute/validate/default/transform
rules as the user edits, matching what the app does.
