---
title: Connecting Claude Code, Claude Desktop, Cursor and others
description: Create a token, add the Kelta MCP server to your client, choose admin or user, and verify the connection.
section: mcp
order: 20
---

## 1. Create a token

In the app, *Profile → API tokens → New*, or:

```bash
kelta token create --name agent --expires-in 90
```

For an agent that should only read, mint the token for a service user whose profile grants `API_ACCESS` and
`canRead` on the relevant collections ([recipe](/docs/reference/api-access/)).

## 2. Add the server

**Claude Code**

```bash
claude mcp add kelta-admin --transport http \
  --url https://api.example.com/acme/mcp/admin \
  --header "Authorization: Bearer klt_..."
```

**Claude Desktop, Cursor and other JSON-configured clients**

```json
{
  "mcpServers": {
    "kelta-admin": {
      "type": "http",
      "url": "https://api.example.com/acme/mcp/admin",
      "headers": { "Authorization": "Bearer klt_..." }
    },
    "kelta-user": {
      "type": "http",
      "url": "https://api.example.com/acme/mcp/user",
      "headers": { "Authorization": "Bearer klt_..." }
    }
  }
}
```

**Through the CLI (no token in the client config)** — `kelta mcp install claude-code|claude-desktop|cursor|generic`
prints a stdio configuration that uses your saved profile and adds the local-only tools
([Local MCP bridge](/docs/cli/mcp-bridge/)).

## 3. Choose admin, user or both

Add `…/mcp/admin` to build and change metadata; `…/mcp/user` to work with records. Many agents want both.

## 4. Verify

Ask the agent to call `ping`, then `list_collections`. A `401` means the token is not a `klt_` PAT or has
expired; a `403` on an admin tool means the token's owner lacks the permission.

## Tips for agents

- Read `kelta://docs/page-layouts`, `kelta://docs/list-views`, `kelta://docs/ui-pages`, `kelta://docs/ui-menus`
  and `kelta://docs/dashboards` before authoring those objects by hand — they are the same documents as the
  [authoring reference](/docs/reference/page-layouts/).
- Prefer the `apply_*` tools; re-running them is safe.
- `execute_flow` input is **double-wrapped**: `{ "input": { "input": { … } } }` ([why](/docs/automation/flow-data/#the-double-wrap-rule)).
- Validation-rule formulas are error conditions — `TRUE` rejects.
