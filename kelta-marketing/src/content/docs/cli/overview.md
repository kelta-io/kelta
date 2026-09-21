---
title: CLI overview and installation
description: What the kelta CLI covers, how its commands are organised, global flags, discovery for people and agents, and the generated reference pages.
section: cli
order: 10
---

`kelta` is the command-line interface to everything in this documentation: the admin surface (collections,
fields, layouts, list views, pages, menus, dashboards, flows, users, limits, sandboxes, promotion), record CRUD,
metadata packages, a raw API escape hatch and a local MCP bridge. It is designed for scripts and AI agents as
much as for people: JSON when piped, stable error codes, a machine-readable command catalogue.

Install it with the one-liner on [Install the Kelta CLI](/docs/getting-started/install-cli/), then
`kelta auth login --url https://api.example.com --tenant <slug>`.

## Command groups

| Group | Commands |
|---|---|
| `auth` | `login`, `logout`, `status` |
| `token` | `list`, `create`, `revoke` |
| `profile` | `list`, `use`, `show`, `remove`, `rename` |
| `collections` | `list`, `describe`, `create`, `update`, `delete` |
| `fields` | `list`, `add`, `update`, `remove` |
| `picklists` | `list`, `get`, `create`, `delete`, `value-add`, `value-update`, `value-deactivate` |
| `validation-rules` | `list`, `create`, `update`, `delete` |
| `constraints` | `list`, `create`, `delete` |
| `layouts` | `list`, `get --tree`, `create`, `apply`, `update`, `delete` |
| `list-views` | `list`, `get`, `create`, `apply`, `update`, `delete` |
| `pages` | `list`, `get`, `create`, `apply`, `update`, `publish`, `delete` |
| `menus` | `list`, `get --tree`, `create`, `apply`, `update`, `delete` |
| `dashboards`, `reports` | list / get / apply |
| `flows` | `list`, `describe`, `create`, `update`, `execute`, `runs`, `run`, `cancel`, `retry`, `publish`, `versions`, `webhook-url` |
| `users` | `list`, `get`, `invite`, `portal-invite`, `reset-password`, `token-create`, `logins` |
| `limits` | `get`, `set-tier`, `set` |
| `audit` | `setup`, `security`, `logins` |
| `records` | `list`, `get`, `create`, `update`, `delete`, `bulk`, `search`, `semantic-search` |
| `metadata` | `export`, `diff`, `apply` |
| `sandbox`, `promote` | environment and promotion lifecycle |
| `sdk` | `types` — generate TypeScript types from the tenant's OpenAPI document |
| `mcp` | `serve`, `install` |
| top level | `api`, `docs`, `manifest`, `version`, `update` |

The complete list with every flag and default is generated from the command registry:
[Command reference](/docs/cli/commands/).

## Global flags

| Flag | Effect |
|---|---|
| `--profile <name>` | which saved connection to use |
| `--output table\|json\|yaml\|csv\|ndjson` | output format (table on a TTY, JSON when piped) |
| `--raw` | the unflattened JSON:API envelope |
| `--quiet` | ids only, one per line |
| `--yes` | confirm destructive commands non-interactively |

## Discovery

- `kelta <group> --help`, `kelta <group> <command> --help` — human help.
- `kelta manifest [--group records]` — the full machine-readable catalogue: every command with a JSON Schema of
  its input, positionals, options, and `dangerous`/auth flags. Agents start here.
- `kelta docs agent` — the [agent guide](/docs/cli/agents/); `kelta docs jsonapi|page-layouts|list-views|dashboards|ui-pages|ui-menus`
  — the authoring reference, identical to the pages under [Authoring reference](/docs/reference/jsonapi/).

## Examples

```bash
kelta collections list --output json | jq '.[].name'
kelta records list invoices --filter status=open --filter amount.gte=100 --sort -createdAt
kelta records create invoices --data '{"amount": 120, "status": "open"}'
kelta fields add invoices --name due_date --type date --required
kelta flows execute <flowId> --input '{"key":"value"}' --wait
kelta layouts apply invoices --file main.json
kelta api GET '/api/governor-limits'
```

Next: [Authentication and profiles](/docs/cli/auth-and-profiles/), [Output contract and scripting](/docs/cli/scripting/).
