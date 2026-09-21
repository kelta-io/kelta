---
title: Validation rules and unique constraints
description: Field constraints, formula-based validation rules, immutable fields, single-column and composite unique constraints, and the errors they raise.
section: data-model
order: 50
---

Every write — console, API, CLI, MCP, flow — goes through the same validation pipeline: required and type checks,
field constraints, validation rules, immutability, uniqueness and referential integrity. A failure answers
`400 VALIDATION_FAILED` with one entry per problem.

## Field constraints

The `constraints` JSON on a field declares simple checks by type:

| Type | Constraints |
|---|---|
| `STRING`, `RICH_TEXT` | `min`, `max` (length), `pattern`, `email`, `url` |
| numbers, `CURRENCY`, `PERCENT` | `min`, `max` |
| `DATE`, `DATETIME` | `min`, `max` |
| `PHONE`, `EMAIL`, `URL`, `EXTERNAL_ID` | `pattern` |

`required` and picklist membership are checked here too.

## Validation rules

A validation rule is a **formula that describes the error**. When it evaluates to `TRUE`, the record is rejected
with the rule's message. (This is the opposite of "the record is valid when…" — a common mistake when writing rules
by hand or from an agent.)

```bash
kelta validation-rules create invoices \
  --name "Due date after issue date" \
  --formula "due_date < issue_date" \
  --message "The due date cannot be before the issue date" \
  --error-field due_date
```

| Attribute | Meaning |
|---|---|
| `errorConditionFormula` | The formula. Syntax and functions: [Formula fields](/docs/data-model/formula-fields/#expression-syntax). |
| `errorMessage` | Shown to the user and returned in `detail`. |
| `errorField` | Attaches the error to a field (`source.pointer: /data/attributes/<field>`); otherwise it is record-level. |
| `evaluateOn` | `CREATE`, `UPDATE` or `CREATE_AND_UPDATE` (default). |
| `severity` | `ERROR` blocks the write; `WARNING` is surfaced to the UI but does not block. |
| `enforceOnClient` | Evaluate in the browser before submitting for immediate feedback (the server always evaluates). |
| `active` | Inactive rules are skipped. |

Rules are managed under a collection's **Validation rules** tab, with `kelta validation-rules`, or MCP
`create_validation_rule` / `update_validation_rule` / `delete_validation_rule`.

## Immutable fields

A field can be declared immutable. An update that would change it fails loudly with `VALIDATION_FAILED`
(`constraint: "immutable"`) rather than silently ignoring the change. Re-sending the value a record already holds
is accepted, so read → edit one field → write the whole record still works.

## Unique constraints

**Single column.** Set `uniqueConstraint: true` on the field (`--unique` in the CLI). `EXTERNAL_ID` fields are
always unique.

**Composite.** Declare a constraint over several fields:

```http
POST /api/admin/collections/invoices/unique-constraints
{ "fieldNames": ["customer", "number"] }
```

`GET` lists them; `DELETE /api/admin/collections/{name}/unique-constraints/{indexName}` drops one. CLI:
`kelta constraints list|create|delete`; MCP: `create_unique_constraint`, `list_unique_constraints`,
`delete_unique_constraint`.

A violation answers `409 Conflict` naming the constraint.

## Referential integrity

A lookup or master-detail value must resolve to an existing record in the target collection; otherwise the write
fails with code `reference` and `meta.field`, `meta.targetCollection` and `meta.value`.

## Error shape

```json
{
  "errors": [
    {
      "status": "400",
      "code": "VALIDATION_FAILED",
      "title": "Validation Error",
      "detail": "The due date cannot be before the issue date",
      "source": { "pointer": "/data/attributes/due_date" },
      "meta": { "requestId": "abc12345" }
    }
  ]
}
```

Branch on `code`, never on `detail`. See [Error responses](/docs/api/errors/).
