# Inspections checklist template

Site inspections against reusable checklists: the places you inspect, the checklist templates
and their questions, each inspection visit, and the findings recorded during it. An inspection
with an open high-severity finding cannot be marked passed. Install it into an empty tenant with
one command.

## What is inside

| Piece | Where | Installed by |
|-------|-------|--------------|
| Collections `sites`, `inspection-templates`, `checklist-items`, `inspections`, `findings` with their fields and references | `metadata.json` | `kelta metadata apply` |
| Picklists: inspection status, finding severity (field-scoped) | `metadata.json` | `kelta metadata apply` |
| One page layout per collection, with related lists (a site shows its inspections, a template its checklist items and inspections, an inspection its findings, a checklist item its findings) | `metadata.json` | `kelta metadata apply` |
| Validation rule **No pass with open high-severity findings** on `inspections` | `metadata.json` | `kelta metadata apply` |
| Flow **Count open high-severity findings**: on every created, updated or deleted finding it recounts the open `HIGH` findings of that finding's inspection and stores the count in `inspections.openHighFindings`; a passed inspection that gains one is moved back to In progress | `metadata.json` | `kelta metadata apply` |
| List views: 1 for sites, 2 for inspection templates, 1 for checklist items, 3 for inspections (including a Kanban board by status), 3 for findings | `list-views/<collection>.json` | `kelta list-views apply` |
| Dashboard **Inspections overview**: open findings, open high-severity findings, site count, open findings by severity, inspections by status, the high-severity findings still open | `dashboards/inspections-overview.json` | `kelta dashboards apply` |
| Seed data: 6 sites, 3 inspection templates, 15 checklist items, 10 inspections, 12 findings (fictional sites and people) | `seeds/<collection>.json` | `kelta records bulk` |

Inspection statuses are Scheduled, In progress, Passed, Failed and Cancelled. Finding severities
are Low, Medium and High; a finding is open until its `resolved` flag is set.

## The "no pass with an open high-severity finding" rule

A validation rule only sees the record being saved, so the check is split in two:

1. The flow keeps `inspections.openHighFindings` equal to the number of findings of that
   inspection with `severity = HIGH` and `resolved = false`. It runs after each finding is
   created, deleted, or has its `severity`, `resolved` or `inspection` changed.
2. The validation rule rejects a save of an inspection when
   `status == "PASSED" && BLANKVALUE(openHighFindings, 0) > 0`, with a 422
   `VALIDATION_RULE_FAILED` on `status`.

So marking the seeded "Riverside Distribution Center - Fire safety - Sep 2026" inspection passed
is rejected until its "East fire exit blocked by pallets" finding is resolved.

Limits that follow from this design (see `BUILD-LOG.md`):

- The count is updated by a flow after the finding is saved, so for a moment after a new
  high-severity finding is saved the inspection can still be passed. If it is, the flow moves it
  back to In progress.
- `openHighFindings` is read-only on the page layout, but the API accepts it. A write that sets
  `status` to `PASSED` and `openHighFindings` to `0` in the same request passes the rule; the
  next change to one of that inspection's findings sets the count again.
- Moving a finding to another inspection recounts the new inspection only.

## Install

You need the `kelta` CLI, `jq`, and a profile that is signed in to the tenant you want to install
into as a user who can create collections, flows, list views and dashboards.

```bash
kelta profile use <profile>     # or export KELTA_PROFILE / KELTA_URL + KELTA_TENANT + KELTA_TOKEN
./examples/templates/inspections/install.sh
```

The script takes no arguments and stops at the first command that fails. On success it ends with
one line per seeded collection:

```
seeded sites: 6
seeded inspection-templates: 3
seeded checklist-items: 15
seeded inspections: 10
seeded findings: 12
```

Install into a tenant that does not already have `sites`, `inspection-templates`,
`checklist-items`, `inspections` or `findings` collections. `kelta metadata apply` skips an item
that already exists, so a same-named collection from something else is left as it is and the
seed step then fails against it.

Running the script again is safe up to the seed step: the package, list views and dashboard are
applied idempotently (reported as skipped or unchanged). The seed step then fails on the unique
site name, and because each `records bulk` call is all-or-nothing, no duplicate records are
written.

## Seed files

Each `seeds/<collection>.json` is a JSON array of attribute objects; its length is the number of
records the script creates. A reference field holds the parent's natural key instead of an id,
and `install.sh` resolves it after loading the parent:

| Field | Holds |
|-------|-------|
| `checklist-items.template`, `inspections.template` | the inspection template `name` |
| `inspections.site` | the site `name` |
| `findings.inspection` | the inspection `name` |
| `findings.checklistItem` | the checklist item `code` |

`inspections.openHighFindings` is not seeded: the flow sets it while the findings load. To add a
record, append an object to the right file. A key that matches no parent stops the install with
a message naming the field and the value. Keep a seeded inspection with an open high-severity
finding out of `PASSED`: the flow moves such an inspection to In progress while the findings load.

## How it was built

`BUILD-LOG.md` records how the template was authored and verified, every error hit on the way,
and the platform gaps the template had to route around.
