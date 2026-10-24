# Applicant tracking template

A small applicant tracking system: the roles you are hiring for, the candidates, each
candidate's application to a role as it moves through the hiring stages, and the interviews
scheduled for it. Install it into an empty tenant with one command.

## What is inside

| Piece | Where | Installed by |
|-------|-------|--------------|
| Collections `job-openings`, `candidates`, `job-applications`, `interviews` with their fields and references | `metadata.json` | `kelta metadata apply` |
| Picklists: department, employment type and status of a job opening; candidate source; application stage; interview type and outcome (all field-scoped) | `metadata.json` | `kelta metadata apply` |
| One page layout per collection, with related lists (a job opening and a candidate show their applications, an application its interviews) | `metadata.json` | `kelta metadata apply` |
| Flow **Stamp application stage changes**: when an application's stage changes it stamps `stageChangedAt`; a move to Hired also stamps `hiredAt` | `metadata.json` | `kelta metadata apply` |
| List views: 2 for job openings, 1 for candidates, 3 for applications (including a Kanban **Hiring pipeline** board by stage), 2 for interviews | `metadata.json` (`LIST_VIEW` items) | `kelta metadata apply` |
| Dashboard **Hiring overview**: open roles, active applications, hires, applications by stage, candidates by source, applications by job opening, upcoming interviews | `metadata.json` (a `DASHBOARD` item) | `kelta metadata apply` |
| Seed data: 6 job openings, 14 candidates, 16 applications, 12 interviews (invented people on `example.com`) | `metadata.json` (`records` sections) | `kelta metadata apply` |

The application stages are Applied, Screening, Interview, Offer, Hired and Rejected. The flow
runs on stage *changes* only, so loading seed applications does not overwrite the
`stageChangedAt` and `hiredAt` values the seed rows carry.

## Install

You need the `kelta` CLI and a profile that is signed in to the tenant you want to install into
as a user who can create collections, flows, list views and dashboards. From the template
directory, one command installs everything:

```bash
kelta profile use <profile>     # or export KELTA_PROFILE / KELTA_URL + KELTA_TENANT + KELTA_TOKEN
cd examples/templates/applicant-tracking
kelta metadata apply metadata.json --yes
```

`./install.sh` runs exactly that command from its own directory. The command prints one line of
totals with a count per item type; the `RECORD` count is the 48 seed rows. An item that fails
is reported as `failed` with its error, and the other items are still applied.

Install into a tenant that does not already have `job-openings`, `candidates`, `job-applications`
or `interviews` collections. `kelta metadata apply` skips an item that already exists, so a
same-named collection from something else is left as it is and its seed rows then fail against
it. The names do not overlap with the CRM or inspections templates, so all three can live in one
tenant.

Running the command again is safe: every item that already exists is reported as skipped, and
so is every seed row whose key fields (below) match an existing record, so a second run reports
`created=0`.

## Seed records

The package's top-level `records` array holds one section per collection, applied in order
(parents before children) after every metadata item. A section names its `collection`, the
`key` fields that identify a row, and its `rows`, each a plain attribute object:

| Collection | Key | Rows |
|------------|-----|------|
| `job-openings` | `title` | 6 |
| `candidates` | `email` | 14 |
| `job-applications` | `name` | 16 |
| `interviews` | `title` | 12 |

A reference field holds the parent's natural key instead of an id; the section's `references`
names the parent collection and field, and the importer resolves the value to the parent's id:

| Field | Parent collection | Parent field |
|-------|-------------------|--------------|
| `job-applications.candidate` | `candidates` | `email` |
| `job-applications.job` | `job-openings` | `title` |
| `interviews.application` | `job-applications` | `name` |

To add a record, append an object to the right section's `rows`. A reference value that matches
no parent fails that row with a message naming the field and the value, and its whole section is
not written: a section writes all of its new rows or none.

## How it was built

Unlike the CRM and inspections templates, this one was authored through the admin MCP server
alone (`kelta mcp serve` bridged to the hosted admin and user toolsets), not the CLI.
`BUILD-LOG.md` records every MCP call, every error hit on the way, and the platform gaps the
build had to route around. CI replays those calls every week from `mcp-replay/calls.json` and
fails when the build needs more than 20% more of them than `mcp-replay/baseline.txt`; how to
regenerate both is in `BUILD-LOG.md` → "Replaying the build in CI".
