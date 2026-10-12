# Slice 6 — Client event rules (`SCRIPT` kind)

> Child of [README.md](./README.md). FE. Depends on Slice 1 (registry) + Slice 2 (form/detail).
> Precedes Slice 7 (server gate).

## 1. Goal & scope
**Delivers:** extends the existing layout `RuleEngine` (`layoutRules.ts` / `useLayoutRules`) with a
**`SCRIPT`** rule kind on events `onLoad` / `onChange` / `onBlur` / `onBeforeSubmit`, evaluating a
sandboxed expression over `@kelta/formula`'s AST evaluator. `onBeforeSubmit` can block or mutate
the payload before PATCH/POST. Editor UI in `PageLayoutsPage/RulesEditor.tsx`. **Does NOT:** run
arbitrary JS (that's server-only, Slice 7) or enforce anything server-side. Client rules are UX
sugar; Slice 7 is the gate. Conforms to parent "Event & validation model → Client rules".

## 2. UI samples
```
Rule: "Discount guard"   Kind: SCRIPT   On: onChange(discount), onBeforeSubmit
  Expression:  IF(discount > 0.5, "Discount over 50% needs approval", "")
  → non-empty result on onBeforeSubmit blocks submit + shows message on `discount`
```
Scope available to the expression: `{ record, previous, field, user, context }`.

## 3. Data & API contracts
```ts
// utils/layoutRules.ts — extend LayoutRule union
type ScriptRule = { kind:'SCRIPT'; events:('onLoad'|'onChange'|'onBlur'|'onBeforeSubmit')[]
                    targetField?:string; expression:string; message?:string }
// Stored inside the existing layout-rules system collection `body` JSON (no schema change).
```
Evaluator: `@kelta/formula` `FormulaEvaluator.evaluate(expr, flatScope)` — no `eval`/`window`
reach. Dotted paths flattened via `extractFieldRefs`.

## 4. DB migrations
None — rides the existing `layout_rule.body` JSON.

## 5. File-by-file code changes
- **Modify** `kelta-ui/app/src/utils/layoutRules.ts` — add `SCRIPT` case to
  `dtoToLayoutRule`/`dtosToLayoutRules`.
- **Modify** `@kelta/components` `useLayoutRules` — evaluate `SCRIPT` rules on the new events;
  `onBeforeSubmit` returns block/mutate result to `RecordFormEngine`.
- **Modify** `RecordFormEngine` (Slice 2) + `RecordDetailBody` inline path — invoke
  `onBeforeSubmit` before write.
- **Modify** `kelta-ui/app/src/pages/PageLayoutsPage/RulesEditor.tsx` — `SCRIPT` kind editor
  (expression field via `FieldExpressionPicker`, event multiselect, message).

## 6. Test plan
- Vitest: `onChange` compute updates a dependent field; `onBeforeSubmit` non-empty result blocks
  submit + attaches message to `targetField`; `onLoad` seeds a default; evaluator rejects
  `window`/`eval` tokens (parser guarantee).
- e2e owned by Slice 8 (onChange compute + onBeforeSubmit block).

## 7. Docs to update
- `conventions.md` (client `SCRIPT` rule kind + event names + evaluator sandbox guarantee),
  `status.md`.

## 8. Risks & open questions
- Keep the client evaluator strictly expression-based (`@kelta/formula`) — do NOT add a JS engine
  client-side. Arbitrary logic goes to Slice 7.
- `onBeforeSubmit` must not be the only guard — the server hook (Slice 7) is authoritative; a
  client-only block is bypassable via the API.
