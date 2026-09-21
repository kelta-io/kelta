---
title: Local MCP bridge (kelta mcp)
description: Run the CLI as a stdio MCP server that bridges the hosted toolsets and adds local tools, and print ready-made client configuration for Claude Code, Claude Desktop, Cursor and others.
section: cli
order: 60
---

`kelta mcp serve` turns the CLI into an MCP server over stdio. It proxies the hosted admin and user toolsets
([MCP server overview](/docs/mcp/overview/)) using the active profile's token and adds **local** tools for the
things only the CLI can do (metadata packages, sandboxes, promotion, type generation, applying page/menu/dashboard
files).

```bash
kelta mcp serve --toolset all --source auto
```

| Flag | Values | Meaning |
|---|---|---|
| `--toolset` | `user`, `admin`, `all` (default) | which hosted toolsets to bridge |
| `--source` | `auto` (default), `remote`, `local` | `auto` tries the hosted server and degrades to local tools; `local` never connects (works against a compose stack without `kelta-mcp`) |
| `--mcp-url` | URL | override the hosted MCP origin (default: the profile's API origin) |
| `--enable-api-tool` | flag | expose `cli_api`, the raw request escape hatch (off by default) |

Local tools are prefixed `cli_` and mirror the command groups `metadata`, `sandbox`, `promote`, `sdk`, `pages`,
`menus`, `dashboards`, `reports`, plus `profile list`, `token list|revoke` and `docs`. Everything else comes from
the hosted server.

## Client configuration

`kelta mcp install <client>` prints the configuration for `claude-code`, `claude-desktop`, `cursor` or `generic`,
using the absolute path of the installed binary:

```bash
kelta mcp install claude-code                # stdio bridge
kelta mcp install claude-desktop --toolset admin
kelta mcp install cursor --direct            # hosted HTTP config instead of the bridge
```

Bridge (stdio):

```json
{ "mcpServers": { "kelta": { "command": "/home/me/.local/bin/kelta", "args": ["mcp", "serve", "--toolset", "all"] } } }
```

Direct (hosted HTTP, no CLI involved at runtime):

```bash
claude mcp add kelta-admin --transport http --url https://api.example.com/acme/mcp/admin \
  --header "Authorization: Bearer <YOUR_PAT>"
```

## Bridge or direct?

| | Bridge | Direct |
|---|---|---|
| Needs the CLI on the machine | yes | no |
| Token handling | the profile's stored PAT | you paste a PAT into the client config |
| Local-only tools (`metadata`, `sandbox`, `promote`, `sdk types`) | yes | no |
| Works against a compose stack without `kelta-mcp` | yes (`--source local`) | no |
