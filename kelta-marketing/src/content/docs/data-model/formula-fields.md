---
title: Formula and roll-up fields
description: Computed fields — the formula expression language, its functions, evaluation rules, and roll-up summaries over child collections.
section: data-model
order: 60
---

## Formula fields

A `FORMULA` field has no stored value. Its `fieldTypeConfig` holds an `expression` and a `returnType`
(`TEXT`, `NUMBER` or `BOOLEAN`). The expression is evaluated against the record on every read, so it is always
current and can never drift from the fields it derives from.

```bash
kelta fields add invoices --name total_with_tax --type formula \
  --data '{"fieldTypeConfig": {"expression": "amount * (1 + tax_rate)", "returnType": "NUMBER"}}'
```

- `returnType` is immutable once the field exists.
- A blank expression yields `null`. An expression that throws (bad reference, type error, cycle) yields
  `"#ERROR"` so the failure is visible rather than silently `null`.
- Formula fields are computed after the query runs, so `filter` and `sort` on them are not supported; they are not part of the full-text index.

## Expression syntax

| Element | Syntax |
|---|---|
| Field reference | the field's API name: `amount`, `due_date` |
| Literals | numbers `42`, `3.5`; strings `"text"`; booleans `TRUE`, `FALSE` |
| Arithmetic | `+ - * /`, unary `-`, parentheses |
| Comparison | `=` or `==`, `!=` or `<>`, `<`, `<=`, `>`, `>=` |
| Logic | `AND` / `&&`, `OR` / `||`, `NOT` / `!` |
| Functions | `NAME(arg, …)` — see below |

Operator precedence: parentheses → unary → `* /` → `+ -` → comparison → `AND` → `OR`.

## Function reference

| Function | Description |
|---|---|
| `TODAY()` | Current date. |
| `NOW()` | Current timestamp. |
| `ISBLANK(x)` | `TRUE` when `x` is null or empty. |
| `BLANKVALUE(x, fallback)` | `x` unless blank, else `fallback`. |
| `IF(cond, then, else)` | Conditional. |
| `AND(a, b, …)`, `OR(a, b, …)`, `NOT(a)` | Logic as functions. |
| `LEN(text)` | Length. |
| `CONTAINS(text, search)` | Substring test. |
| `UPPER(text)`, `LOWER(text)`, `TRIM(text)` | Text transforms. |
| `TEXT(x)` | Convert to text. |
| `VALUE(text)` | Parse a number. |
| `ROUND(n, places)` | Round. |
| `ABS(n)` | Absolute value. |
| `MAX(a, b)`, `MIN(a, b)` | Larger / smaller of two values. |
| `REGEX(text, pattern)` | `TRUE` when the whole text matches the pattern. |
| `DATEDIFF(d1, d2)` | Days from `d2` to `d1` (`d1 - d2`). |

The same engine runs [validation rules](/docs/data-model/validation-rules/) and flow trigger `filterFormula`s, and
has a TypeScript port with parity tests in [`@kelta/formula`](/docs/sdk/formula/) for client-side evaluation.

## Roll-up summary fields

A `ROLLUP_SUMMARY` field on a **parent** collection aggregates over a **master-detail child** collection, computed
on read:

```json
{
  "fieldTypeConfig": {
    "childCollection": "invoice-lines",
    "foreignKeyField": "invoice",
    "aggregateFunction": "SUM",
    "aggregateField": "line_total",
    "filter": { "status": "ACTIVE" }
  }
}
```

`aggregateFunction` is `COUNT`, `SUM`, `AVG`, `MIN` or `MAX`; `aggregateField` is omitted for `COUNT`. The
optional `filter` is an equality filter on child rows. The console's field editor offers a picker for the child
collection and field.
