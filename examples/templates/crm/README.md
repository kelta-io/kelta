# CRM template

A lightweight CRM you can install into any Kelta tenant: accounts, the contacts
who work there, the deals you are chasing and the activities (calls, emails,
meetings, tasks) logged against them.

## Install

```bash
examples/templates/crm/install.sh
```

Prerequisites:

- The `kelta` CLI and a token for the target tenant, as a user who can manage
  metadata. The installer authenticates only from the environment — it never
  runs `kelta auth login` or reads a saved profile:

  ```bash
  export KELTA_URL=<api-url> KELTA_TENANT=<slug> KELTA_TOKEN=<klt_… or a JWT>
  ```

- `jq`, `bash` and coreutils `timeout`.
- A tenant without collections named `accounts`, `contacts`, `deals` or
  `activities`. Preview with `kelta metadata diff examples/templates/crm/package.json`:
  on a fresh tenant it lists only creates.

`install.sh --no-seed` installs everything except the sample records. The
script stops with a non-zero exit at the first error, printing
`install failed at step: <name>`, and nothing in it can wait forever: stdin is
`/dev/null`, every CLI call runs under `timeout` (`KELTA_CALL_TIMEOUT`, default
60 s; `KELTA_APPLY_TIMEOUT`, default 150 s, for the metadata apply), and it
waits for the tenant API at most `READY_ATTEMPTS` (10) polls `READY_INTERVAL`
(3 s) apart. It is safe to re-run:
metadata is applied in skip mode, the flow, list views and dashboard are
create-or-update, and seeding is skipped once `accounts` has records.

## What you get

| Collection | Fields | Lookups |
|------------|--------|---------|
| `accounts` | name, industry (picklist), website, phone, city, country, annualRevenue, description | — |
| `contacts` | firstName, lastName, email, phone, jobTitle | account → accounts |
| `deals` | name, stage (picklist), amount, expectedCloseDate, closedAt | account → accounts (required), primaryContact → contacts |
| `activities` | subject, activityType (picklist), dueDate, completed, notes | deal → deals, contact → contacts |

- **Picklists**: `crm-deal-stage` (PROSPECTING, QUALIFICATION, PROPOSAL,
  NEGOTIATION, WON, LOST), `crm-industry`, `crm-activity-type`.
- **Page layouts**: one detail layout per collection, with related lists
  (an account shows its contacts and deals, a contact its deals and activities, a deal its activities).
- **List views**: All accounts, All contacts, Open pipeline, Closed deals,
  Pipeline board (kanban by stage), Open activities.
- **Flow** `Stamp deal closed date`: when a deal's stage becomes WON or LOST it
  sets `closedAt`; moving the deal back to an open stage clears it.
- **Dashboard** `Sales overview`: open pipeline and won revenue, deal and
  account counts, deals by stage, accounts by industry, recently closed deals,
  open activities.
- **Seed data**: 8 accounts, 16 contacts, 12 deals and 16 activities. All
  companies, people and `example.com` addresses are fictional.

## Layout

| Path | Applied with |
|------|--------------|
| `package.json` | `kelta metadata apply` — collections, fields, picklists, page layouts |
| `flows/*.json` | `kelta flows create` / `kelta flows update` |
| `list-views/<collection>/*.json` | `kelta list-views apply <collection>` |
| `dashboards/*.json` | `kelta dashboards apply --file` |
| `seeds/NN-<collection>.json` | `kelta records bulk`, in file order |

A metadata package does not yet carry list views, dashboards or records, which
is why they sit beside it. Seed batches reference records from earlier batches
as `{"lid": "account-1"}` in `attributes`; `install.sh` swaps in the id the
earlier batch returned. `BUILD-LOG.md` records how the template was built and
the platform gaps the installer works around.
