---
title: "@kelta/formula"
description: The TypeScript port of the formula engine used by validation rules, formula fields and page-builder expressions — API, grammar, built-ins, parity and custom functions.
section: sdk
order: 40
---

`@kelta/formula` evaluates the same expression language the server uses, so a client can validate before it
submits or compute a formula field locally. Parity with the Java engine is covered by shared tests.

```ts
import { FormulaEvaluator } from '@kelta/formula';

const evaluator = new FormulaEvaluator();
evaluator.evaluate('amount * (1 + tax_rate)', { amount: 100, tax_rate: 0.23 });      // 123
evaluator.evaluate('ISBLANK(email) OR NOT CONTAINS(email, "@")', { email: 'x' });   // true → a rule would reject
```

## API

| Export | Purpose |
|---|---|
| `FormulaEvaluator` (`evaluate(expr, fields)`, `registerFunction(fn)`) | evaluate against a field map; add custom functions |
| `FormulaParser` | parse to an AST |
| `evaluateAst`, `extractFieldRefs` | evaluate a parsed AST; list the fields an expression reads (for dependency tracking) |
| `toDouble`, `toBoolean` | coercion helpers matching the server |
| `FormulaException` | parse/evaluation errors |

## Grammar and built-ins

Identical to the server: field references, string/number/boolean literals, `+ - * /`, comparisons
(`= == != <> < <= > >=`), `AND`/`&&`, `OR`/`||`, `NOT`/`!`, unary minus, parentheses, and the 21 built-in
functions `TODAY NOW ISBLANK BLANKVALUE IF AND OR NOT LEN CONTAINS UPPER LOWER TRIM TEXT VALUE ROUND ABS MAX MIN
REGEX DATEDIFF`. Details: [Formula and roll-up fields](/docs/data-model/formula-fields/).

## Where it runs

- The app evaluates `enforceOnClient` validation rules before submitting (the server always re-evaluates).
- Page-builder `{{= … }}` expressions and computed variables.
- Your own forms, via the evaluator above.

A custom function registered client-side has no server counterpart; keep client-only functions to display
logic.
