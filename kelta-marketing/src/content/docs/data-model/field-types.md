---
title: Field types reference
description: Every field type, how it is stored, its type-specific configuration, the aliases the API accepts, and the common field options.
section: data-model
order: 20
---

A field is a record in the `fields` system collection with a `type`, common options and a `fieldTypeConfig` JSON
object for type-specific settings. The canonical type names are the upper-case values below; the API, CLI and MCP
tools also accept the lower-case aliases.

## Type table

| Type | Stored as | Notes |
|---|---|---|
| `STRING` | `TEXT` | Default text type. Aliases: `text`, `string`. |
| `TEXT` | `TEXT` | Long text. API/CLI/MCP only. |
| `RICH_TEXT` | `TEXT` | HTML-formatted text rendered with a rich editor. |
| `INTEGER` | `INTEGER` | 32-bit. API/CLI/MCP only — the console's *Number* is `DOUBLE`. |
| `LONG` | `BIGINT` | 64-bit. API/CLI/MCP only. |
| `DOUBLE` | `DOUBLE PRECISION` | Alias `number` in the console. |
| `BOOLEAN` | `BOOLEAN` | |
| `DATE` | `DATE` | ISO `YYYY-MM-DD`. |
| `DATETIME` | `TIMESTAMP` | ISO 8601. |
| `JSON` | `JSONB` | Arbitrary JSON. Aliases `object`, `array`. |
| `ARRAY` | `JSONB` | JSON array. API/CLI/MCP only. |
| `PICKLIST` | `VARCHAR(255)` | One value from a field-level or global picklist. See [Picklists](/docs/data-model/picklists-and-record-types/). |
| `MULTI_PICKLIST` | `TEXT[]` | Several values from a picklist. |
| `CURRENCY` | `NUMERIC(18,2)` + `<field>_currency_code VARCHAR(3)` | Amount plus ISO 4217 code companion column. |
| `PERCENT` | `NUMERIC(8,4)` | |
| `AUTO_NUMBER` | `VARCHAR(100)` | Generated from a database sequence (`autoNumberSequenceName`) with a format in `fieldTypeConfig`. |
| `PHONE` | `VARCHAR(40)` | |
| `EMAIL` | `VARCHAR(320)` | |
| `URL` | `VARCHAR(2048)` | |
| `ENCRYPTED` | `BYTEA` | AES-256-GCM with a per-tenant derived key; decrypted on read for authorized callers. |
| `EXTERNAL_ID` | `VARCHAR(255)` + unique index | Identifier from another system; unique per collection. |
| `GEOLOCATION` | `DOUBLE PRECISION` + `<field>_longitude` | Latitude in the primary column, longitude in the companion column. |
| `VECTOR` | `vector(N)` (pgvector) | Embedding for semantic search. `fieldTypeConfig.dimension` 1–16000 (default 1536); optional `embeddingSource` names a text field to embed on write. An HNSW cosine index is created automatically. API/CLI/MCP only. |
| `LOOKUP` | `VARCHAR(36)` + FK `ON DELETE SET NULL` | Optional relationship. See [Relationships](/docs/data-model/relationships/). |
| `MASTER_DETAIL` | `VARCHAR(36)` + FK `ON DELETE CASCADE`, `NOT NULL` | Required, owning relationship. Alias `reference`. |
| `FORMULA` | no column | Computed on read from `fieldTypeConfig.expression`; `returnType` `TEXT`, `NUMBER` or `BOOLEAN`. See [Formula fields](/docs/data-model/formula-fields/). |
| `ROLLUP_SUMMARY` | no column | `COUNT`/`SUM`/`AVG`/`MIN`/`MAX` over a master-detail child collection, computed on read. |

`REFERENCE` still deserialises for compatibility and is treated as `MASTER_DETAIL`; do not create new fields with it.

### Aliases accepted on write

`text`→`STRING`, `number`→`DOUBLE`, `object`/`array`→`JSON`, `reference`→`MASTER_DETAIL`, plus every canonical
name in lower case. The CLI additionally maps `number`→`INTEGER` and `decimal`→`DOUBLE`; check
`kelta fields add --help`.

### Console vs API

The field editor in the console offers 22 types: `string number boolean date datetime json master_detail lookup
picklist multi_picklist currency percent auto_number phone email url rich_text encrypted external_id geolocation
formula rollup_summary`. `TEXT`, `INTEGER`, `LONG`, `ARRAY` and `VECTOR` are created through the API, CLI or MCP.

## Common field options

| Attribute | Effect |
|---|---|
| `name` | `^[a-zA-Z][a-zA-Z0-9_]*$`. Reserved: `id`, `createdAt`, `updatedAt`, `createdBy`, `updatedBy`, `tenantId`, `type`, `attributes`, `relationships`. |
| `displayName`, `description` | UI label and one-line help (the description is exposed by the schema endpoint and OpenAPI). |
| `required` | `NOT NULL`; enforced by validation before the database sees it. |
| `uniqueConstraint` | Single-column unique constraint; violations answer `409`. Composite constraints: [Validation rules and unique constraints](/docs/data-model/validation-rules/). |
| `indexed` | Maintain a B-tree index on the column. |
| `defaultValue` | Applied when the write supplies no value. |
| `constraints` | JSON field-level checks: `min`, `max`, `pattern`, `email`, `url` depending on type. |
| `searchable` | Include the value in the collection's full-text index. See [Search and attachments](/docs/data-model/search-and-attachments/). |
| `trackHistory` | Record old/new values in `field-history`. See [Record history](/docs/data-model/record-history/). |
| `fieldOrder` | Ordinal position in the collection. |
| `fieldTypeConfig` | Type-specific JSON — currency code, formula expression, roll-up config, vector dimension, **masking** (`{"masking": {"type": "FULL" \| "LAST4" \| "EMAIL" \| "CUSTOM"}}` on string-typed fields; see [Field-level security and data masking](/docs/security/field-security-and-masking/)). |

## Adding and changing fields

```bash
kelta fields add invoices --name amount --type currency --required
kelta fields update <fieldId> --display-name "Invoice amount"
kelta fields remove <fieldId> --yes    # drops the column and its data
```

Adding a field adds a column; the change is broadcast to every pod like a collection change. Changing a field's
type after it has data is not a generic update — use the schema migration planner (Setup → Platform → Migrations)
which computes and applies a typed migration. Removing a field drops the column and its data.

Maskable types (`string`, `rich_text`, `email`, `phone`, `url`, `encrypted`, `external_id`) accept a masking
configuration; numbers and dates use `HIDDEN` field permissions instead.
