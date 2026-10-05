---
title: "Example 2: automate the library with a record flow and an inbound webhook"
description: Add a record-triggered flow that keeps each book's status in step with its loans, and a key-checked inbound webhook for a returns scanner, then read the run history — including the runs that failed.
section: examples
order: 20
---

This example adds two flows to the lending library from [example 1](/docs/examples/library-app/):

- **Sync book status** — a record-triggered flow on `loans`: a new loan marks its book `ON_LOAN`; setting
  `returned_on` marks it `AVAILABLE` again.
- **Return scanner** — an inbound webhook a returns scanner (or any external system) calls with a loan id and a
  date. It checks a shared key before it writes anything.

The two chain: the webhook writes `returned_on`, which triggers the record flow, which updates the book.

> **Environment.** Every command and request on this page was run against the hosted **`examples`** demo tenant
> (`app.kelta.io/examples`) with the `kelta` CLI **1.0.1427** (git `b3a8869`) on 2026-10-05; the output blocks are
> what came back, with the webhook's shared key replaced by `<redacted>` and wide tables trimmed where noted. The
> platform does not expose a server build endpoint; the cluster's download service reported **1.0.1436** as the
> newest published release. IDs will differ in your tenant.

Background reading: [Flows](/docs/automation/flows/), [Triggers](/docs/automation/triggers/),
[Action handlers](/docs/automation/action-handlers/), [Runs and step logs](/docs/automation/flow-runs/).

## 1. The record-triggered flow

Save the definition as `sync-book-status.json`. A `Choice` state looks at the loan that changed; each branch is an
`UPDATE_RECORD` on the loan's book:

```json
{
  "StartAt": "Returned?",
  "States": {
    "Returned?": {
      "Type": "Choice",
      "Choices": [
        { "Variable": "$.record.data.returned_on", "IsNull": false, "Next": "MarkAvailable" }
      ],
      "Default": "MarkOnLoan"
    },
    "MarkOnLoan": {
      "Type": "Task",
      "Resource": "UPDATE_RECORD",
      "Parameters": {
        "targetCollectionName": "books",
        "recordId": "${$.record.data.book}",
        "updates": [ { "field": "status", "value": "ON_LOAN" } ]
      },
      "End": true
    },
    "MarkAvailable": {
      "Type": "Task",
      "Resource": "UPDATE_RECORD",
      "Parameters": {
        "targetCollectionName": "books",
        "recordId": "${$.record.data.book}",
        "updates": [ { "field": "status", "value": "AVAILABLE" } ]
      },
      "End": true
    }
  }
}
```

and the trigger as `sync-book-status.trigger.json` — start on new loans, and on updates only when `returned_on`
changed:

```json
{ "collection": "loans", "events": ["CREATED", "UPDATED"], "triggerFields": ["returned_on"] }
```

```bash
kelta flows create --name "Sync book status" --type RECORD_TRIGGERED \
  --trigger-config @sync-book-status.trigger.json \
  --definition @sync-book-status.json --active --quiet
```

```text
ba4ffdbc-55df-445b-98c3-3e2858f97fb0
```

> **Why `recordId` and not `recordIdField`?** The [action handler reference](/docs/automation/action-handlers/)
> shows `"recordIdField": "book"` — "the field on the triggering record that holds the target's id". The first
> version of this flow used exactly that, and its run failed with
> `Record not found: book in collection books`: on this release the handler looked `book` up in the run's state, not
> in the record, and fell back to using the literal string `book` as the id. The `${$.record.data.book}` template
> resolves the id explicitly and works. You can see the failed run in the history below.

## 2. Lend a book

```bash
kelta records create loans --data '{"book":"3232b8a4-efd0-4dc6-a9cb-cf0caffa5e8a","member":"dad55720-dece-4e0c-9149-0c5c51961de0","loaned_on":"2026-10-05","due_on":"2026-10-19"}'
```

```json
{
  "id": "52ac0536-105f-43f0-88d0-e707f13a5307",
  "createdAt": "2026-10-05T00:58:50.286673266Z",
  "updatedBy": "13de168a-ab36-4ec0-a811-647e447f1827",
  "recordTypeId": null,
  "createdBy": "13de168a-ab36-4ec0-a811-647e447f1827",
  "book": "3232b8a4-efd0-4dc6-a9cb-cf0caffa5e8a",
  "member": "dad55720-dece-4e0c-9149-0c5c51961de0",
  "returned_on": null,
  "due_on": "2026-10-19",
  "loaned_on": "2026-10-05",
  "updatedAt": "2026-10-05T00:58:50.286673266Z"
}
```

Record events are delivered asynchronously, so the flow runs a moment after the write returns. That first run used
the `recordIdField` definition and failed; after `kelta flows update ba4ffdbc-55df-445b-98c3-3e2858f97fb0
--definition @sync-book-status.json`, retrying it replays the original trigger against the corrected definition:

```bash
kelta flows retry ee07a32c-4669-4738-90f2-dc360a1065c8
```

```json
{
  "id": "9a355ad1-de2f-4b30-8ebb-4000bcf61118",
  "originalExecutionId": "ee07a32c-4669-4738-90f2-dc360a1065c8",
  "flowId": "ba4ffdbc-55df-445b-98c3-3e2858f97fb0",
  "mode": "full",
  "status": "RUNNING"
}
```

```bash
kelta flows runs ba4ffdbc-55df-445b-98c3-3e2858f97fb0 --output table
```

```text
STATUS     STARTED                      DURATION MS  STEPS
---------  ---------------------------  -----------  -----
COMPLETED  2026-10-05T00:59:10.473409Z  15           2
FAILED     2026-10-05T00:58:50.723867Z  15           2
```

The book is now on loan (table trimmed to the last columns):

```bash
kelta records get books 3232b8a4-efd0-4dc6-a9cb-cf0caffa5e8a --output table
```

```text
… title    updatedAt                    status
… -------  ---------------------------  -------
… Kindred  2026-10-05T00:59:10.484970Z  ON_LOAN
```

## 3. The inbound webhook, with a key

An `AUTOLAUNCHED` flow can be started by `POST /api/webhooks/{flowId}` **without** Kelta credentials
([Inbound webhooks](/docs/automation/triggers/#inbound-webhooks)): the flow id in the URL is the shared secret, and
there is no signature check. For a scanner on a shelf that is not enough, so the flow itself requires a second shared
key in the body and refuses to act without it.

Save as `return-scanner.json`, with your own long random value in place of `<redacted>`:

```json
{
  "StartAt": "CheckKey",
  "States": {
    "CheckKey": {
      "Type": "Choice",
      "Choices": [
        { "Variable": "$.input.key", "StringEquals": "<redacted>", "Next": "RecordReturn" }
      ],
      "Default": "Reject"
    },
    "Reject": {
      "Type": "Fail",
      "Error": "Unauthorized",
      "Cause": "Missing or wrong scanner key"
    },
    "RecordReturn": {
      "Type": "Task",
      "Resource": "UPDATE_RECORD",
      "Parameters": {
        "targetCollectionName": "loans",
        "recordId": "${$.input.loanId}",
        "updates": [ { "field": "returned_on", "value": "${$.input.returnedOn}" } ]
      },
      "End": true
    }
  }
}
```

The key travels in the body, not a header: the webhook endpoint passes only `content-type` and `user-agent` through
to `$.headers`, so a custom header cannot be checked in the flow.

```bash
kelta flows create --name "Return scanner" --type AUTOLAUNCHED \
  --definition @return-scanner.json --active --quiet
kelta flows webhook-url c56965bb-e04d-4ef2-852e-ac98d4cc1942
```

```text
c56965bb-e04d-4ef2-852e-ac98d4cc1942
{
  "id": "c56965bb-e04d-4ef2-852e-ac98d4cc1942",
  "webhookUrl": "https://api.kelta.io/api/webhooks/c56965bb-e04d-4ef2-852e-ac98d4cc1942",
  "flowId": "c56965bb-e04d-4ef2-852e-ac98d4cc1942"
}
```

### A call with the wrong key

The scanner needs no Kelta token — this is a plain `curl`:

```bash
curl -sS -X POST https://api.kelta.io/api/webhooks/c56965bb-e04d-4ef2-852e-ac98d4cc1942 \
  -H 'Content-Type: application/json' \
  -d '{"loanId":"52ac0536-105f-43f0-88d0-e707f13a5307","returnedOn":"2026-10-12","key":"not-the-key"}'
```

```json
{"data":{"type":"flow-executions","id":"95cd9654-bc94-4da7-9d25-7d1bd02cdb40","attributes":{"flowId":"c56965bb-e04d-4ef2-852e-ac98d4cc1942","status":"RUNNING"}}}
```

The endpoint answers `200` with a `RUNNING` execution either way — it starts the flow and returns; the flow decides.
The caller learns nothing about whether the key was right, which is what you want from a public URL. The run
history shows what happened.

### A call with the right key

```bash
curl -sS -X POST https://api.kelta.io/api/webhooks/c56965bb-e04d-4ef2-852e-ac98d4cc1942 \
  -H 'Content-Type: application/json' \
  -d '{"loanId":"52ac0536-105f-43f0-88d0-e707f13a5307","returnedOn":"2026-10-12","key":"<redacted>"}'
```

```json
{"data":{"type":"flow-executions","id":"20ca362a-e8bc-4a0d-bc10-ce5299f60e5c","attributes":{"flowId":"c56965bb-e04d-4ef2-852e-ac98d4cc1942","status":"RUNNING"}}}
```

## 4. Read the run history

Both webhook runs:

```bash
kelta flows runs c56965bb-e04d-4ef2-852e-ac98d4cc1942 --output table
```

```text
STATUS     STARTED                      DURATION MS  STEPS
---------  ---------------------------  -----------  -----
COMPLETED  2026-10-05T00:59:35.786897Z  12           2
FAILED     2026-10-05T00:59:30.930596Z  13           2
```

and the record flow, which the successful webhook run triggered by writing `returned_on`:

```bash
kelta flows runs ba4ffdbc-55df-445b-98c3-3e2858f97fb0 --output table
```

```text
STATUS     STARTED                      DURATION MS  STEPS
---------  ---------------------------  -----------  -----
COMPLETED  2026-10-05T00:59:36.331917Z  14           2
COMPLETED  2026-10-05T00:59:10.473409Z  15           2
FAILED     2026-10-05T00:58:50.723867Z  15           2
```

Runs are also a queryable collection, so you can ask for exactly the columns you care about (table trimmed: the
`createdAt`, `updatedBy`, `createdBy` and `updatedAt` columns are removed):

```bash
kelta records list flow-executions --filter flowId=c56965bb-e04d-4ef2-852e-ac98d4cc1942 \
  --sort startedAt --fields status,startedBy,currentNodeId,errorMessage,startedAt --output table
```

```text
id                                    startedBy                             currentNodeId  errorMessage                  startedAt                    status
------------------------------------  ------------------------------------  -------------  ----------------------------  ---------------------------  ---------
95cd9654-bc94-4da7-9d25-7d1bd02cdb40  13de168a-ab36-4ec0-a811-647e447f1827  Reject         Missing or wrong scanner key  2026-10-05T00:59:30.930596Z  FAILED
20ca362a-e8bc-4a0d-bc10-ce5299f60e5c  13de168a-ab36-4ec0-a811-647e447f1827  RecordReturn                                 2026-10-05T00:59:35.786897Z  COMPLETED
```

`startedBy` is the flow's owner: a webhook has no initiating user and this flow has no `runAsUserId`, so the
[actor](/docs/automation/triggers/) falls back to the owner, and the loan update is stamped with that user.

The same query for the record flow (same columns trimmed; it was captured after example 3, whose two new loans
added two more `MarkOnLoan` runs — those last two rows are omitted here):

```bash
kelta records list flow-executions --filter flowId=ba4ffdbc-55df-445b-98c3-3e2858f97fb0 \
  --sort startedAt --fields status,triggerRecordId,currentNodeId,errorMessage,startedAt --output table
```

```text
id                                    triggerRecordId                       currentNodeId  errorMessage                                startedAt                    status
------------------------------------  ------------------------------------  -------------  ------------------------------------------  ---------------------------  ---------
ee07a32c-4669-4738-90f2-dc360a1065c8  52ac0536-105f-43f0-88d0-e707f13a5307  MarkOnLoan     Record not found: book in collection books  2026-10-05T00:58:50.723867Z  FAILED
9a355ad1-de2f-4b30-8ebb-4000bcf61118  52ac0536-105f-43f0-88d0-e707f13a5307  MarkOnLoan                                                 2026-10-05T00:59:10.473409Z  COMPLETED
c8777714-0fed-4399-9031-994fe876597e  52ac0536-105f-43f0-88d0-e707f13a5307  MarkAvailable                                              2026-10-05T00:59:36.331917Z  COMPLETED
```

### Step by step

`--steps` lists each state with its input and output snapshots. The rejected call (snapshots shortened to the
`Reject` step's error fields):

```bash
kelta flows run 95cd9654-bc94-4da7-9d25-7d1bd02cdb40 --steps
```

```json
  {
    "id": "e49f2ddd-c36f-4a37-a2ed-a00239366c23",
    "stateId": "Reject",
    "stateName": "Reject",
    "stateType": "Fail",
    "status": "FAILED",
    …
    "errorMessage": "Missing or wrong scanner key",
    "errorCode": "Unauthorized",
    "attemptNumber": 1,
    "durationMs": 2,
    "startedAt": "2026-10-05T00:59:30.941026Z",
    "completedAt": "2026-10-05T00:59:30.943155Z"
  }
```

and the accepted call's `RecordReturn` step, whose output is the updated loan:

```json
    "outputSnapshot": {
      "record": {
        "id": "52ac0536-105f-43f0-88d0-e707f13a5307",
        "book": "3232b8a4-efd0-4dc6-a9cb-cf0caffa5e8a",
        "name": null,
        "due_on": "2026-10-19T00:00:00.000Z",
        "member": "dad55720-dece-4e0c-9149-0c5c51961de0",
        "createdAt": "2026-10-05T00:58:50.286673Z",
        "createdBy": "13de168a-ab36-4ec0-a811-647e447f1827",
        "loaned_on": "2026-10-05T00:00:00.000Z",
        "updatedAt": "2026-10-05T00:59:35.796753Z",
        "updatedBy": "13de168a-ab36-4ec0-a811-647e447f1827",
        "returned_on": "2026-10-12T00:00:00.000Z",
        "recordTypeId": null
      },
      "updatedFields": {
        "updatedBy": "13de168a-ab36-4ec0-a811-647e447f1827",
        "returned_on": "2026-10-12"
      },
      "targetRecordId": "52ac0536-105f-43f0-88d0-e707f13a5307",
      "targetCollectionName": "loans"
    },
```

> **The key is in the step log.** Each step's `inputSnapshot` holds the whole state, including `$.input.key` — the
> walkthrough's output above shows `<redacted>` only because it was redacted before publishing. Anyone who can read
> this flow's runs can read the key, so treat run-history access as access to the key, and rotate it by editing the
> flow when that set of people changes.

The book is back on the shelf:

```bash
kelta records get books 3232b8a4-efd0-4dc6-a9cb-cf0caffa5e8a
```

```json
{
  "id": "3232b8a4-efd0-4dc6-a9cb-cf0caffa5e8a",
  "createdAt": "2026-10-05T00:57:28.230910Z",
  "updatedBy": "13de168a-ab36-4ec0-a811-647e447f1827",
  "recordTypeId": null,
  "createdBy": "13de168a-ab36-4ec0-a811-647e447f1827",
  "author": "Octavia E. Butler",
  "isbn": "9780807083697",
  "title": "Kindred",
  "updatedAt": "2026-10-05T00:59:36.343755Z",
  "status": "AVAILABLE"
}
```

**Next:** [Example 3 — let an agent operate it](/docs/examples/agent-operates-library/).
