---
title: Approval processes
description: Route records through one or more approval steps, lock them while a decision is pending, and act on submissions from the inbox, the API or a flow.
section: automation
order: 70
status: partial
---

An approval process attaches to a collection. Submitting a record creates an **approval instance** that walks the
process's steps; each step assigns an approver, and the record stays locked until the instance is approved,
rejected or recalled.

## Define a process

Setup → Automation → Approval processes (`MANAGE_APPROVALS`), or records in `approval-processes` and
`approval-steps`.

**Process** (`approval-processes`):

| Attribute | Meaning |
|---|---|
| `collectionId`, `name`, `description`, `active` | |
| `entryCriteria` | JSON criteria a record must meet to be submitted |
| `recordEditability` | `LOCKED` — writes to the record are rejected while an instance is pending |
| `initialSubmitterField` | field naming who may submit (for example an owner lookup) |
| `onSubmitFieldUpdates`, `onApprovalFieldUpdates`, `onRejectionFieldUpdates`, `onRecallFieldUpdates` | field values applied at each transition |
| `allowRecall` | whether the submitter can withdraw |
| `executionOrder` | when several processes match a collection |

**Steps** (`approval-steps`): `stepNumber`, `name`, `entryCriteria`, `approverType` (`USER` with `approverId`;
`FIELD` with `approverField` — a user lookup on the record; `QUEUE` with `approverId` naming a queue group),
`unanimityRequired`, `escalationTimeoutHours`, `escalationAction`, `onApproveAction`, `onRejectAction`.

## Submit

| From | How |
|---|---|
| The app | *Submit for approval* in the record header |
| API | `POST /api/approvals/submit` `{ "collectionName": "…", "recordId": "…", "processId": "…" }` |
| A flow | `Task` with `Resource: SUBMIT_FOR_APPROVAL` (auto-detects the process when `processId` is omitted) |
| MCP | `submit_for_approval` |

The actor is always the authenticated user (a `userId` in the body is ignored).

## Act on a submission

```http
POST /api/approvals/{instanceId}/approve   { "comments": "…" }
POST /api/approvals/{instanceId}/reject    { "comments": "…" }
POST /api/approvals/{instanceId}/recall
GET  /api/approvals/status?recordId=…       # current instance and step
GET  /api/approvals/history?recordId=…
GET  /api/approvals/lock-status?recordId=…
```

Approvers see pending items in the app's **Approvals inbox** (`/app/approvals`), the bell in the top bar shows
the live pending count, and MCP `list_approvals` lists them. Instance status is `PENDING`, `APPROVED`, `REJECTED`
or `RECALLED`; each step instance is `PENDING`, `APPROVED`, `REJECTED` or `REASSIGNED`.

## Record locking

With `recordEditability: LOCKED`, any write to the record — console, API, flow — is rejected while an instance is
`PENDING`. The record page shows a lock badge; `GET /api/approvals/lock-status` tells clients in advance.

## Current limitations

- `escalationTimeoutHours` / `escalationAction` are stored but escalation is not executed automatically.
- A `QUEUE` step assigns to the queue's first member rather than presenting the item to every member of the queue.
- Approval instances are readable by anyone with read access to the record; there is no separate visibility
  restriction on approval history.
