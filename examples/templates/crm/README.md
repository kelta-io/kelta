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
| List views: 2 for accounts, 1 for contacts, 3 for deals (including a Kanban pipeline board by stage), 2 for activities | `metadata.json` (`LIST_VIEW` items) | `kelta metadata apply` |
| Dashboard **Sales overview**: open pipeline value, won revenue, account count, pipeline by stage, accounts by industry, open activities | `metadata.json` (a `DASHBOARD` item) | `kelta metadata apply` |
| Seed data: 8 accounts, 12 contacts, 12 deals, 16 activities (fictional companies on `example.com`) | `metadata.json` (`records` sections) | `kelta metadata apply` |

The deal stages are Prospecting, Qualification, Proposal, Negotiation, Closed Won and Closed
Lost. The flow runs on stage *changes* only, so loading seed deals that are already closed does
not create extra activities.

## Install

You need the `kelta` CLI and a profile that is signed in to the tenant you want to install into
as a user who can create collections, flows, list views and dashboards. From the template
directory, one command installs everything:

```bash
kelta profile use <profile>     # or export KELTA_PROFILE / KELTA_URL + KELTA_TENANT + KELTA_TOKEN
cd examples/templates/crm
kelta metadata apply metadata.json --yes
```

`./install.sh` runs exactly that command from its own directory. The command prints one line of
totals with a count per item type; the `RECORD` count is the 48 seed rows. An item that fails
is reported as `failed` with its error, and the other items are still applied.

Install into a tenant that does not already have `accounts`, `contacts`, `deals` or `activities`
collections. `kelta metadata apply` skips an item that already exists, so a same-named collection
from something else is left as it is and its seed rows then fail against it.

Running the command again is safe: every item that already exists is reported as skipped, and
so is every seed row whose key fields (below) match an existing record, so a second run reports
`created=0`.

## Seed records

The package's top-level `records` array holds one section per collection, applied in order
(parents before children) after every metadata item. A section names its `collection`, the
`key` fields that identify a row, and its `rows`, each a plain attribute object:

| Collection | Key | Rows |
|------------|-----|------|
| `accounts` | `name` | 8 |
| `contacts` | `email` | 12 |
| `deals` | `name` | 12 |
| `activities` | `subject` | 16 |

A reference field holds the parent's natural key instead of an id; the section's `references`
names the parent collection and field, and the importer resolves the value to the parent's id:

| Field | Parent collection | Parent field |
|-------|-------------------|--------------|
| `contacts.account` | `accounts` | `name` |
| `deals.account` | `accounts` | `name` |
| `deals.primaryContact` | `contacts` | `email` |
| `activities.deal` | `deals` | `name` |
| `activities.contact` | `contacts` | `email` |

To add a record, append an object to the right section's `rows`. A reference value that matches
no parent fails that row with a message naming the field and the value, and its whole section is
not written: a section writes all of its new rows or none.

## How it was built

`BUILD-LOG.md` records how the template was authored and verified, every error hit on the way,
and the platform gaps the template had to route around.
