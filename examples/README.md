# Examples

Application templates you can install into a tenant with one command. Each directory holds the
metadata package, list views, dashboard, seed data, an `install.sh` that applies them through the
`kelta` CLI, and a `BUILD-LOG.md` recording how it was built. Prerequisites, install commands and
contents are on the docs page
[Start from a template](https://www.kelta.io/docs/getting-started/start-from-a-template/).

| Template | What it is | Install |
|----------|------------|---------|
| [`templates/crm`](templates/crm/README.md) | Sales CRM: accounts, contacts, deals and activities, a flow that stamps closed deals, and a sales dashboard | `./examples/templates/crm/install.sh` |
| [`templates/inspections`](templates/inspections/README.md) | Site inspections against reusable checklists, with a rule and flow that stop an inspection with an open high-severity finding from being marked passed | `./examples/templates/inspections/install.sh` |
