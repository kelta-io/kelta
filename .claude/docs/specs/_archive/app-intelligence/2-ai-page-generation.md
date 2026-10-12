# Slice 2 — AI Page Generation

> Child spec of [App Intelligence (Phase 4)](./README.md), authored with the
> implementation (same PR). kelta-ai + FE; **not security-typed** — the new tool rides
> the existing proposal lifecycle and authz (tools execute as the invoking user; the
> apply path is the same worker JSON:API every proposal uses). Generated pages are
> **drafts — never published automatically**.

## 1. Goal & scope

"Build me a customer-overview page with a KPI and a table" in the admin AI chat →
a `propose_ui_page` proposal card → Apply creates an **unpublished** ui-page whose
`config` carries a validated widget tree (+ optional variables/data sources,
`schemaVersion: 2`), ready to open in the page builder for human review and publish.
Extends the existing tool loop/proposal system — one new tool handler, one apply case,
one card. **Not delivered:** editing a proposal before apply, auto-publish, generating
menu entries for the page, streaming the tree into the builder canvas live.

## 2. UI samples

Chat: proposal card `📄 Customer Overview — 6 widgets · 1 data source` with a
per-widget outline (`grid › metric, table`), Apply/Dismiss; applied state collapses
with an "Open Page Builder" link (`/{tenant}/pages`).

## 3. Data & API contracts

- **Tool** `propose_ui_page` (ProposeToolHandler, auto-registered): input
  `{name, title?, description?, components[], variables?, dataSources?}`; the schema
  documents node shape `{type, props?, children?}` and the widget-type catalog.
- **Proposal type** `ui_page` (`proposalTypeFromToolName` + apply switch).
- **Apply** (`ProposalService.applyUiPageProposal`) validates before any write:
  - widget `type` ∈ the FE registry catalog (30 types — heading/text/button/image/
    card/container/table/form/grid/row/column/divider/field-value/list/repeater/
    chart/tabs/tab-panel/nav/icon/link/metric + the 8 input widgets); unknown types
    reject with the offending names (**keep this list in sync with
    `widgets/builtins` — noted in both files**);
  - caps: ≤200 nodes, depth ≤8; missing node ids assigned (`ai-<n>`);
  - then `POST /api/ui-pages` (new `WorkerApiClient.createUiPage`, JSON:API
    `type: "ui-pages"`) with `{name, title?, description?, published: false, active:
    true, config: {components, variables?, dataSources?, schemaVersion: 2}}` —
    `UIPageSlugHook` derives the slug server-side. Result `{pageId, name,
    componentCount, published: false}`.
- **FE**: `AiProposalType` + `'ui_page'`; `UiPageProposalCard` (tree outline capped at
  20 nodes, variables/data-source badges, Apply/Dismiss, applied → "Open Page
  Builder" link); `AiChatPanel` dispatch case. SSE/plumbing unchanged (proposals are
  generic `{type, data}`).
- **SystemPromptService**: a "UI Pages" authoring section — widget catalog + rules
  (bare-identifier expressions, `{$bind}` props, data-source shape, draft-only).

## 4. DB migrations

None.

## 5. File-by-file code changes

kelta-ai: `tools/handlers/ProposeUiPageHandler.java` (new, +test) ·
`ProposalService.java` (type mapping + apply + validation, +tests) ·
`WorkerApiClient.java` (`createUiPage`) · `SystemPromptService.java` (catalog).
FE: `AiChat/types.ts` · `AiChat/UiPageProposalCard.tsx` (new, +test) ·
`AiChat/AiChatPanel.tsx` (dispatch) · `AiChat/index.ts`.

## 6. Test plan

kelta-ai unit: handler name/schema/proposal; apply — happy path posts the JSON:API
body with `published: false` + `schemaVersion: 2`, unknown widget type rejects with
names, node/depth caps reject, ids assigned. FE Vitest: card renders outline +
counts, Apply/Dismiss callbacks, applied state links to the builder, panel dispatches
`ui_page`. Post-deploy: chat prompt → apply → page appears unpublished in the
builder. `/verify` green.

## 7. Docs to update (same PR)

Parent README slice row → SHIPPED · status.md AI row · memory.

## 8. Risks & open questions

- The widget-type allow-list is duplicated (kelta-ai validation vs FE registry) — a
  new widget needs both. Cross-referencing comments in both files; a build-time export
  is overkill at this cadence.
- Generated `{$bind}`/expression props are NOT validated server-side (the 2d resolver
  fail-opens to null client-side) — a bad binding renders empty, never breaks.
- Collections referenced by generated dataSources may not exist; the card shows the
  declared source names and the page simply renders "No data" — same behavior as a
  hand-built page pointing at a missing collection.
