# CRM template

A small sales CRM: companies, the people who work there, the deals you are working and the
activities you log against them. Install it into an empty tenant with one command.

## What is inside

| Piece | Where | Installed by |
|-------|-------|--------------|
| Collections `accounts`, `contacts`, `deals`, `activities` with their fields and references | `metadata.json` | `kelta metadata apply` |
| Picklists: account industry, deal stage, activity type (field-scoped) | `metadata.json` | `kelta metadata apply` |
| One page layout per collection, with related lists (an account shows its contacts and deals, a deal its activities) | `metadata.json` | `kelta metadata apply` |
| Validation rule: a deal amount cannot be negative | `metadata.json` | `kelta metadata apply` |
| Flow **Stamp closed deals**: when a deal's stage changes to Closed Won or Closed Lost it stamps `closedAt`; a won deal also gets a "Kick-off call" activity | `metadata.json` | `kelta metadata apply` |
| List views: 2 for accounts, 1 for contacts, 3 for deals (including a Kanban pipeline board by stage), 2 for activities | `list-views/<collection>.json` | `kelta list-views apply` |
| Dashboard **Sales overview**: open pipeline value, won revenue, account count, pipeline by stage, accounts by industry, open activities | `dashboards/sales-overview.json` | `kelta dashboards apply` |
| Seed data: 8 accounts, 12 contacts, 12 deals, 16 activities (fictional companies on `example.com`) | `seeds/<collection>.json` | `kelta records bulk` |

The deal stages are Prospecting, Qualification, Proposal, Negotiation, Closed Won and Closed
Lost. The flow runs on stage *changes* only, so loading seed deals that are already closed does
not create extra activities.

## Install

You need the `kelta` CLI, `jq`, and a profile that is signed in to the tenant you want to install
into as a user who can create collections, flows, list views and dashboards.

```bash
kelta profile use <profile>     # or export KELTA_PROFILE / KELTA_URL + KELTA_TENANT + KELTA_TOKEN
./examples/templates/crm/install.sh
```

The script takes no arguments and stops at the first command that fails. On success it ends with
one line per seeded collection:

```
seeded accounts: 8
seeded contacts: 12
seeded deals: 12
seeded activities: 16
```

Install into a tenant that does not already have `accounts`, `contacts`, `deals` or `activities`
collections. `kelta metadata apply` skips an item that already exists, so a same-named collection
from something else is left as it is and the seed step then fails against it.

Running the script again is safe up to the seed step: the package, list views and dashboard are
applied idempotently (reported as skipped or unchanged). The seed step then fails on the unique
account name, and because each `records bulk` call is all-or-nothing, no duplicate records are
written.

## Seed files

Each `seeds/<collection>.json` is a JSON array of attribute objects; its length is the number of
records the script creates. A reference field holds the parent's natural key instead of an id,
and `install.sh` resolves it after loading the parent:

| Field | Holds |
|-------|-------|
| `contacts.account`, `deals.account` | the account `name` |
| `deals.primaryContact`, `activities.contact` | the contact `email` |
| `activities.deal` | the deal `name` |

To add a record, append an object to the right file. A key that matches no parent stops the
install with a message naming the field and the value.

## How it was built

`BUILD-LOG.md` records how the template was authored and verified, every error hit on the way,
and the platform gaps the template had to route around.
