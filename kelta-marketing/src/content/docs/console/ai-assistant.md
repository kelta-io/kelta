---
title: AI assistant and proposals
description: The in-console assistant — what it can read, what it proposes, the human-apply model, flow generation, governed agents and the privacy safeguards.
section: console
order: 80
---

The assistant is a chat panel in the admin console backed by the `kelta-ai` service and Anthropic Claude. It can
inspect your metadata and data, and it **proposes** changes that a human applies — it never writes metadata on
its own.

## Requirements

- The `kelta-ai` service deployed with an `ANTHROPIC_API_KEY` ([Configuration](/docs/deploy/configuration/)).
- AI settings at `/ai-settings` (`GET|PUT /api/ai/config`): `model`, `maxTokens`, `temperature`, the monthly
  `aiTokensPerMonth` budget and the `aiEnabled` switch. The assistant enforces the budget and the switch stored
  here, not the AI values on the tenant's [governor limits](/docs/platform/governor-limits/). Changing them needs
  `MANAGE_TENANTS`, the platform-administrator permission; reading them needs only API access. A `PUT` accepts
  only those five keys: `maxTokens` and `aiTokensPerMonth` are positive whole numbers, `temperature` is from 0
  to 1 and `aiEnabled` is `true` or `false`. Anything else is a `400 VALIDATION_FAILED` naming the key, and
  nothing is changed.

## The panel

Streaming replies (`POST /api/ai/chat/stream`), tool-call indicators, a token-usage badge (`GET /api/ai/usage`)
and conversation history (`GET /api/ai/conversations`). Every request runs **as the signed-in user**: the tools
call the platform API with that user's permissions, so the assistant cannot see or change what the user cannot.

## What it can read

`get_collection_schema`, `list_picklists`, `get_picklist`, `list_page_layouts`, `list_validation_rules`, and
`query_records` (with the user's field-level security and masking applied).

## What it can propose

| Tool | Result |
|---|---|
| `propose_collection` | a new collection with fields |
| `propose_add_fields`, `propose_update_field`, `propose_remove_field` | field changes |
| `propose_picklist` | a global picklist |
| `propose_layout` | a page layout |
| `propose_ui_page` | a page-builder page, created as an **unpublished draft** |

Each proposal renders as a card with a diff; **Apply** (`POST /api/ai/proposals/{id}/apply`) performs the change
through the normal API, so validation, hooks and broadcasts apply. Dismissed proposals do nothing.

## Flow generation

In the flow designer, *Generate with AI* (`POST /api/ai/flows/generate`) drafts a flow definition from a
description; you review it on the canvas before saving.

## Governed agents

Setup → Automation → AI agents defines **agents**: a system prompt, an allowed subset of tools, model and token
overrides. `POST /api/ai/agents/{id}/run` executes a bounded tool-use loop (8 iterations, 100k tokens per run)
as the invoking user, returns the final text with the tool-call trace, and records every run — including
refusals — under `GET /api/ai/agents/{id}/executions`.

## Safeguards

- Tool results are scrubbed of emails, phone numbers, card and national-id patterns before they reach the model
  or the audit trail.
- The monthly token quota is checked per turn; usage is recorded per tenant.
- Requests are rate-limited per user (`AI_RATE_LIMIT_*`).
