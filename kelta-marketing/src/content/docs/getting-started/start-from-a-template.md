---
title: Start from a template
description: Install a ready-made CRM or inspections checklist app into a tenant with one command — collections, layouts, a validation rule, a flow, list views, a dashboard and seed data.
section: getting-started
order: 25
---

The repository ships application templates under
[`examples/templates/`](https://github.com/kelta-io/kelta/tree/main/examples/templates). Each one is a complete
small app — collections, picklists, page layouts, a validation rule, a flow, list views, a dashboard and seed
records — in one package file, `metadata.json`, that a single `kelta metadata apply` installs. Install one to see
a working app in a few seconds, then change it in the console like anything you built yourself.

## Before you start

You need:

- A running tenant — your own Docker Compose stack from the [quickstart](/docs/getting-started/quickstart/), or
  any Kelta tenant you can sign in to.
- The `kelta` CLI with a signed-in profile for that tenant ([Install the CLI](/docs/getting-started/install-cli/)),
  as a user who can create collections, flows, list views and dashboards.
- A clone of the repository, which holds the templates:

```bash
git clone https://github.com/kelta-io/kelta.git
cd kelta
```

Install into a tenant that does not already have collections with the template's names. `kelta metadata apply`
skips an item that already exists, so a same-named collection is left as it is and its seed records then fail
against it.

## Install a template

A template installs into the active `kelta` profile with one command, run from the template's directory (its
`install.sh` runs the same command):

```bash
kelta profile use <profile>     # or export KELTA_PROFILE / KELTA_URL + KELTA_TENANT + KELTA_TOKEN
cd examples/templates/crm
kelta metadata apply metadata.json --yes
```

```bash
kelta profile use <profile>
cd examples/templates/inspections
kelta metadata apply metadata.json --yes
```

The package carries every piece: collections, fields, picklists, layouts, the validation rule, the flow, the list
views (`LIST_VIEW` items), the dashboard (a `DASHBOARD` item) and the seed data. The seed data is the package's
`records` sections, one per collection, parents before children; each section names the `key` fields that identify
a row and the `references` whose values name a parent by its natural key, which the import resolves to the
parent's record id.

The command prints the created, updated, skipped and failed totals with a count per item type. Running it again
is safe: every item that already exists is skipped, and so is every seed row whose key fields match an existing
record, so the second run creates nothing.

## CRM

A small sales CRM: companies, the people who work there, the deals you are working and the activities you log
against them.

| Piece | Contents |
|---|---|
| Collections | `accounts`, `contacts`, `deals`, `activities`, with their fields and references |
| Picklists | account industry, deal stage, activity type (field-scoped) |
| Page layouts | one per collection, with related lists — an account shows its contacts and deals, a deal its activities |
| Validation rule | **Amount cannot be negative** on `deals` |
| Flow | **Stamp closed deals** — when a deal's stage changes to Closed Won or Closed Lost it stamps `closedAt`; a won deal also gets a "Kick-off call" activity |
| List views | 2 for accounts, 1 for contacts, 3 for deals (including a Kanban pipeline board by stage), 2 for activities |
| Dashboard | **Sales overview** — open pipeline value, won revenue, account count, pipeline by stage, accounts by industry, open activities |
| Seed data | 8 accounts, 12 contacts, 12 deals, 16 activities (fictional companies on `example.com`) |

Deal stages are Prospecting, Qualification, Proposal, Negotiation, Closed Won and Closed Lost. The flow runs on
stage *changes* only, so loading seed deals that are already closed does not create extra activities. See the
template's [README](https://github.com/kelta-io/kelta/blob/main/examples/templates/crm/README.md).

## Inspections checklist

Site inspections against reusable checklists: the places you inspect, the checklist templates and their
questions, each inspection visit and the findings recorded during it. An inspection with an open high-severity
finding cannot be marked passed.

| Piece | Contents |
|---|---|
| Collections | `sites`, `inspection-templates`, `checklist-items`, `inspections`, `findings`, with their fields and references |
| Picklists | inspection status, finding severity (field-scoped) |
| Page layouts | one per collection, with related lists — a site shows its inspections, a template its checklist items and inspections, an inspection its findings, a checklist item its findings |
| Validation rule | **No pass with open high-severity findings** on `inspections` |
| Flow | **Count open high-severity findings** — on every created, updated or deleted finding it recounts the open `HIGH` findings of that finding's inspection into `inspections.openHighFindings`; a passed inspection that gains one is moved back to In progress |
| List views | 1 for sites, 2 for inspection templates, 1 for checklist items, 3 for inspections (including a Kanban board by status), 3 for findings |
| Dashboard | **Inspections overview** — open findings, open high-severity findings, site count, open findings by severity, inspections by status, the high-severity findings still open |
| Seed data | 6 sites, 3 inspection templates, 15 checklist items, 10 inspections, 12 findings (fictional sites and people) |

A validation rule only sees the record being saved, so the check is split in two: the flow keeps the count of
open high-severity findings on the inspection, and the rule rejects saving an inspection as `PASSED` while that
count is above zero. Because the count is written by a flow after a finding is saved, there is a short window in
which the inspection can still be passed; the flow then moves it back to In progress. The template's
[README](https://github.com/kelta-io/kelta/blob/main/examples/templates/inspections/README.md) lists the other
limits of this design.

## How the templates were built

Each template was authored by an agent in throwaway sandboxes with the `kelta` CLI, exported with
`kelta metadata export`, and verified with a clean install run that exited 0. No MCP tool
did any of the authoring: the CRM log records no MCP tool calls, the inspections log one `ping`.
The build logs record every command, every error hit on the way and the platform gaps each template had to route
around:

- [CRM build log](https://github.com/kelta-io/kelta/blob/main/examples/templates/crm/BUILD-LOG.md)
- [Inspections build log](https://github.com/kelta-io/kelta/blob/main/examples/templates/inspections/BUILD-LOG.md)

To build an app of your own from scratch instead, continue with [your first app](/docs/getting-started/first-app/).
