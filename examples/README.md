# Examples

Four worked examples: three application templates you can install into a tenant with one command,
and a walkthrough of an AI agent building an app over MCP.

Each template directory holds one metadata package, `metadata.json`, carrying the collections, list
views, dashboard and seed records; an `install.sh` that applies it with the one command in the
Install column; and a `BUILD-LOG.md` recording how it was built. Prerequisites, install commands
and contents are on the docs page
[Start from a template](https://www.kelta.io/docs/getting-started/start-from-a-template/).

| Template | What it is | Install |
|----------|------------|---------|
| [`templates/crm`](templates/crm/README.md) | Sales CRM: accounts, contacts, deals and activities, a flow that stamps closed deals, and a sales dashboard | `cd examples/templates/crm && kelta metadata apply metadata.json --yes` |
| [`templates/inspections`](templates/inspections/README.md) | Site inspections against reusable checklists, with a rule and flow that stop an inspection with an open high-severity finding from being marked passed | `cd examples/templates/inspections && kelta metadata apply metadata.json --yes` |
| [`templates/applicant-tracking`](templates/applicant-tracking/README.md) | Applicant tracking: job openings, candidates, applications moving through hiring stages and their interviews, a flow that stamps stage changes and hires, a Kanban hiring pipeline and a hiring dashboard. Built through the admin MCP server alone | `cd examples/templates/applicant-tracking && kelta metadata apply metadata.json --yes` |
| [`agents-mcp`](agents-mcp/README.md) | Walkthrough: an AI agent builds a task tracker over MCP — a `tasks` collection with a status picklist, records, a filtered query, and a flow that stamps `completed_at` when a task is done | [`agents-mcp/README.md`](agents-mcp/README.md) (walkthrough, nothing to install) |
