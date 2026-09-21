---
title: "Flows: definitions and state types"
description: The flow model — a Step-Functions-style JSON state machine — its nine state types, choice rules, data-flow keys, and a complete example.
section: automation
order: 10
---

A **flow** is an automation defined as a state machine in the style of AWS Step Functions. You can draw it in the
visual designer (Setup → Automation → Flows) or write the JSON directly through the API, CLI (`kelta flows`) or
MCP (`create_flow`, `update_flow`). Both produce the same definition.

## The flow record

| Attribute | Meaning |
|---|---|
| `name`, `description` | |
| `flowType` | `RECORD_TRIGGERED`, `SCHEDULED`, `NATS_TRIGGERED` or `AUTOLAUNCHED` (manual / API / webhook) — see [Triggers](/docs/automation/triggers/) |
| `triggerConfig` | JSON specific to the flow type (collection and events, cron, topic) |
| `definition` | the state machine below |
| `active` | inactive flows never start |
| `version` | incremented on publish; see [Runs, versions and retention](/docs/automation/flow-runs/) |
| `runAsUserId` | the user the flow acts as when no initiating user exists |

## Definition format

```json
{
  "StartAt": "CheckAmount",
  "States": {
    "CheckAmount": {
      "Type": "Choice",
      "Choices": [
        { "Variable": "$.record.data.amount", "NumericGreaterThan": 10000, "Next": "SubmitForApproval" }
      ],
      "Default": "MarkApproved"
    },
    "SubmitForApproval": {
      "Type": "Task",
      "Resource": "SUBMIT_FOR_APPROVAL",
      "Parameters": {},
      "End": true
    },
    "MarkApproved": {
      "Type": "Task",
      "Resource": "FIELD_UPDATE",
      "Parameters": { "updates": [ { "field": "status", "value": "APPROVED" } ] },
      "End": true
    }
  }
}
```

Every state has a `Type` and either `Next` (the following state) or `End: true`; `Comment` is free text.
Execution starts at `StartAt` and runs until a terminal state.

## State types

| Type | Purpose | Keys |
|---|---|---|
| `Task` | Run an [action handler](/docs/automation/action-handlers/) | `Resource` (handler name), `Parameters`, `InputPath`, `ResultPath`, `OutputPath`, `Retry`, `Catch` |
| `Choice` | Branch on the state data | `Choices[]` (rules with `Next`), `Default` |
| `Parallel` | Run branches concurrently, collect their results in an array | `Branches[]` (each a full `{StartAt, States}`), `ResultPath`, `Retry`, `Catch` |
| `Map` | Run an iterator over each item of an array | `ItemsPath`, `Iterator` (`{StartAt, States}`), `MaxConcurrency`, `FailOnPartial`, `ResultPath` |
| `Wait` | Pause | `Seconds`, `Timestamp`, `TimestampPath`, or `EventName` |
| `Pass` | Inject a static `Result` or reshape data | `Result`, `InputPath`, `ResultPath`, `OutputPath` |
| `InvokeFlow` | Run another flow synchronously and merge its result | `FlowId` or `FlowName`, `Input`, `ResultPath`, `Retry`, `Catch` |
| `Succeed` | Terminate successfully | |
| `Fail` | Terminate with an error | `Error`, `Cause` |

The designer exposes the first eight visually; `InvokeFlow` is authored in JSON (or through the API/MCP) and is
bounded to a nesting depth of 10.

## Choice rules

A rule names a `Variable` (JSONPath into the state data), one comparison, and `Next`:

| Comparison | |
|---|---|
| Strings | `StringEquals`, `StringNotEquals`, `StringGreaterThan`, `StringLessThan`, `StringGreaterThanEquals`, `StringLessThanEquals`, `StringMatches` (regex) |
| Numbers | `NumericEquals`, `NumericNotEquals`, `NumericGreaterThan`, `NumericLessThan`, `NumericGreaterThanEquals`, `NumericLessThanEquals` |
| Booleans / presence | `BooleanEquals`, `IsPresent`, `IsNull` |
| Composition | `And: [rules]`, `Or: [rules]`, `Not: rule` |

`Default` names the state to go to when no rule matches; without it a non-match fails the run with
`States.NoChoiceMatched`.

## Data flow

Every run carries a JSON **state envelope**; states read and write it through JSONPath:

- `InputPath` selects what the state sees (default `$`, the whole state).
- `Parameters` builds the handler's config; `"${$.input.customerId}"` substitutes a value from the state.
- `ResultPath` says where the state's result is merged (default `$`, replacing the state; `"$.lookup"` nests it).
- `OutputPath` selects what is passed on.

The envelope's shape and the all-important `$.input.<key>` rule are on [Flow data](/docs/automation/flow-data/).

## Map

```json
"NotifyEach": {
  "Type": "Map",
  "ItemsPath": "$.lookup.records",
  "MaxConcurrency": 5,
  "FailOnPartial": true,
  "Iterator": {
    "StartAt": "Email",
    "States": { "Email": { "Type": "Task", "Resource": "EMAIL_ALERT", "Parameters": { "to": "${$.email}", "subject": "Hello" }, "End": true } }
  },
  "ResultPath": "$.notified",
  "End": true
}
```

Each iteration sees the item as its state. `FailOnPartial: true` fails the run if any iteration fails; otherwise
failures are collected in the result.

## Invoke another flow

```json
"Enrich": {
  "Type": "InvokeFlow",
  "FlowName": "enrich-customer",
  "Input": { "customerId": "${$.record.data.customer}" },
  "ResultPath": "$.enriched",
  "Next": "Continue"
}
```

The sub-flow runs to completion in the same execution context and its final state lands at `ResultPath`.

## Where to go next

- [Action handler reference](/docs/automation/action-handlers/) — what a `Task` can do
- [Triggers](/docs/automation/triggers/) — how runs start
- [Retries, catch, waits and resume](/docs/automation/error-handling/)
- [Runs, versions and retention](/docs/automation/flow-runs/)
