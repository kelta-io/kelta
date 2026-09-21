---
title: Field-level security and data masking
description: Hide, make read-only, or mask individual fields per profile; how masking is enforced across reads, writes, filters, search, exports and realtime.
section: security
order: 60
---

## Field visibility per profile

For each profile and field, a `profile-field-permissions` row sets `visibility`:

| Visibility | Read | Write |
|---|---|---|
| `VISIBLE` (default) | value returned | allowed |
| `READ_ONLY` | value returned | rejected |
| `HIDDEN` | key removed from `attributes` **and** from to-one `relationships` | rejected |
| `MASKED` | value returned redacted; record carries `meta.maskedFields` | rejected |

Edit them on a profile's **Field permissions** tab (Setup → Administration → Profiles). System audit fields
(`id`, `createdAt`, `updatedAt`, `createdBy`, `updatedBy`) are never stripped.

Enforcement is server-side on every JSON:API response — primary data and `included` resources — and on every
write; the console merely mirrors it. Has-many includes are filtered by the same rules on the included collection.

## Data masking

Masking keeps a field *present* but redacted, for cases where users must see that a value exists (a card number,
an ID) without seeing it. It has two halves:

1. **Shape** — on the field, `fieldTypeConfig.masking`:

   ```json
   { "masking": { "type": "LAST4", "maskChar": "•" } }
   ```

   `type` is `FULL` (all characters), `LAST4` (all but the last four), `EMAIL` (`a•••@example.com`) or `CUSTOM`
   (`customPattern`). Only string-typed fields are maskable: `string`, `rich_text`, `email`, `phone`, `url`,
   `encrypted`, `external_id`. Invalid configuration degrades to `FULL`, never to plaintext. Numbers and dates use
   `HIDDEN` instead. Set it in the field editor's *Data masking* section, or via MCP `add_field` / `update_field`
   (`maskingType`, `maskingChar`, `maskingCustomPattern`).
2. **Exposure** — which profiles see the mask: `visibility: MASKED` on the field permission. Profiles with
   `VISIBLE` see plaintext.

### What a masked caller sees

- The masked value as a string, and `meta.maskedFields: ["ssn"]` on the record. Clients branch on
  `meta.maskedFields`, never on the placeholder text.
- Filtering, sorting or grouping on a masked field answers `403 MASKED_FIELD_PREDICATE` with a deliberately
  uniform body — a specific error would be a value-probing oracle.
- Echoing the placeholder back in a write is stripped, so a masked value can never be overwritten with `••••`.

### Everywhere masking is enforced

| Surface | Behaviour |
|---|---|
| JSON:API reads, includes, record history | redacted |
| Data exports (CSV/JSON), report execution and export | redacted for the requester; filters/sort/group-by on a masked field rejected |
| Dashboards | redacted per viewer; masked collections bypass the shared widget cache |
| Full-text search index and display values | masking-configured fields are never indexed |
| Semantic search | never embedded; toggling masking purges existing vectors and re-indexes |
| Realtime events | record payload omitted when the record has masked fields (clients refetch) |
| Duplicate detection | matching on a masked field rejected |

### Not masked (system trust tier)

Flows, scripts, outbound webhooks and scheduled exports run without a user principal and see plaintext — the same
contract as field-level security. Treat what a flow sends outward as unmasked.
