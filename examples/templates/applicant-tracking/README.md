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
| List views: 2 for job openings, 1 for candidates, 3 for applications (including a Kanban **Hiring pipeline** board by stage), 2 for interviews | `list-views/<collection>.json` | `kelta list-views apply` |
| Dashboard **Hiring overview**: open roles, active applications, hires, applications by stage, candidates by source, applications by job opening, upcoming interviews | `dashboards/hiring-overview.json` | `kelta dashboards apply` |
| Seed data: 6 job openings, 14 candidates, 16 applications, 12 interviews (invented people on `example.com`) | `seeds/<collection>.json` | `kelta records bulk` |

The application stages are Applied, Screening, Interview, Offer, Hired and Rejected. The flow
runs on stage *changes* only, so loading seed applications does not overwrite the
`stageChangedAt` and `hiredAt` values the seed file carries.

## Install

You need the `kelta` CLI, `jq`, and a profile that is signed in to the tenant you want to install
into as a user who can create collections, flows, list views and dashboards.

```bash
kelta profile use <profile>     # or export KELTA_PROFILE / KELTA_URL + KELTA_TENANT + KELTA_TOKEN
./examples/templates/applicant-tracking/install.sh
```

The script takes no arguments and stops at the first command that fails. On success it ends with
one line per seeded collection:

```
seeded job-openings: 6
seeded candidates: 14
seeded job-applications: 16
seeded interviews: 12
```

Install into a tenant that does not already have `job-openings`, `candidates`,
`job-applications` or `interviews` collections. `kelta metadata apply` skips an item that already
exists, so a same-named collection from something else is left as it is and the seed step then
fails against it. The names do not overlap with the CRM or inspections templates, so all three
can live in one tenant.

Running the script again is safe up to the seed step: the package, list views and dashboard are
applied idempotently (reported as skipped or unchanged). The seed step then fails on the unique
job opening title, and because each `records bulk` call is all-or-nothing, no duplicate records
are written.

## Seed files

Each `seeds/<collection>.json` is a JSON array of attribute objects; its length is the number of
records the script creates. A reference field holds the parent's natural key instead of an id,
and `install.sh` resolves it after loading the parent:

| Field | Holds |
|-------|-------|
| `job-applications.candidate` | the candidate `email` |
| `job-applications.job` | the job opening `title` |
| `interviews.application` | the application `name` ("<candidate> - <job title>") |

To add a record, append an object to the right file. A key that matches no parent stops the
install with a message naming the field and the value.

## How it was built

Unlike the CRM and inspections templates, this one was authored through the admin MCP server
alone (`kelta mcp serve` bridged to the hosted admin and user toolsets), not the CLI.
`BUILD-LOG.md` records every MCP call, every error hit on the way, and the platform gaps the
build had to route around. CI replays those calls every week from `mcp-replay/calls.json` and
fails when the build needs more than 20% more of them than `mcp-replay/baseline.txt`; how to
regenerate both is in `BUILD-LOG.md` → "Replaying the build in CI".
