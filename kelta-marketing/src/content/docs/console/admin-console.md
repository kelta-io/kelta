---
title: Admin console tour
description: The Setup hub and every administration page it contains, grouped by area, with the system permission that unlocks each one.
section: console
order: 10
---

The admin console is where builders and administrators work. It lives at `https://app.example.com/<tenant>/…`;
the end-user app that renders what you build is at `https://app.example.com/<tenant>/app/…` and is described in
the [end-user app tour](/docs/console/end-user-app/).

## The Setup hub

`/<tenant>/setup` is the entry point: a searchable grid of every administration page with pinned and recent
items, filterable by the groups *Core*, *Automation* and *Platform*. There is no permanent sidebar — global
search (`Cmd/Ctrl+K`) and the hub are the navigation. Pages you lack the permission for are hidden from the hub;
the backend enforces the permission regardless.

## Data model

| Page | Path | Permission |
|---|---|---|
| Collections — create and edit collections, fields, validation rules, record types, data sources | `/collections` | `CUSTOMIZE_APPLICATION` |
| Resources — browse and edit records of any collection (the admin record browser) | `/resources` | — |
| Picklists — global picklists and values | `/picklists` | `CUSTOMIZE_APPLICATION` |
| Page layouts — sections, fields, related lists, assignment rules | `/layouts` | `CUSTOMIZE_APPLICATION` |
| List views — shared saved views and their renderer | `/listviews` | `MANAGE_LISTVIEWS` |

## Administration

| Page | Path | Permission |
|---|---|---|
| Users — invite, edit, deactivate, mint tokens, reset MFA | `/users` | `MANAGE_USERS` |
| Profiles — system, object and field permissions | `/profiles` | `MANAGE_USERS` |
| Delegated admins — scoped user administration | `/delegated-admins` | `MANAGE_DELEGATED_ADMINS` |
| OIDC providers — SSO federation | `/oidc-providers` | `MANAGE_CONNECTED_APPS` |

## Security

| Page | Path | Permission |
|---|---|---|
| Password policy | `/password-policy` | `MANAGE_USERS` |
| MFA policy | `/mfa-policy` | `MANAGE_USERS` |
| Login history | `/login-history` | `MANAGE_USERS` |
| Security audit | `/security-audit` | `MANAGE_USERS` |
| Network access — tenant IP allowlist | `/network-access` | `MANAGE_TENANTS` |
| Audit trail — setup changes with before/after values | `/audit-trail` | `VIEW_SETUP` |

## Automation

| Page | Path | Permission |
|---|---|---|
| Approval processes | `/approvals` | `MANAGE_APPROVALS` |
| Flows — list, runs, and the visual designer at `/flows/:id/design` | `/flows` | `MANAGE_WORKFLOWS` |
| Scheduled jobs | `/scheduled-jobs` | `MANAGE_WORKFLOWS` |
| AI agents — governed agent definitions and runs | `/ai-agents` | `CUSTOMIZE_APPLICATION` |

## Integration

| Page | Path | Permission |
|---|---|---|
| Connected apps | `/connected-apps` | `MANAGE_CONNECTED_APPS` |
| Credentials — the vault referenced by flows and external collections | `/credentials` | `VIEW_CREDENTIALS` |
| API specs — imported OpenAPI documents | `/api-specs` | `VIEW_API_SPECS` |
| Webhooks — outbound webhook portal | `/webhooks` | `MANAGE_CONNECTED_APPS` |
| Email templates | `/email-templates` | `MANAGE_EMAIL_TEMPLATES` |
| Scripts — server-side scripts for flows | `/scripts` | `MANAGE_CONNECTED_APPS` |
| Modules — install and manage runtime modules | `/modules` | `MANAGE_CONNECTED_APPS` |
| Support mailboxes, Campaigns, Telehealth settings | `/mailboxes`, `/campaigns`, `/telehealth-settings` | feature-area permissions |

## UI customization

| Page | Path | Permission |
|---|---|---|
| Pages — the page builder | `/pages` | `CUSTOMIZE_APPLICATION` |
| Menus — apps and navigation | `/menus` | `CUSTOMIZE_APPLICATION` |
| Plugins | `/plugins` | `CUSTOMIZE_APPLICATION` |

## Analytics

| Page | Path | Permission |
|---|---|---|
| Analytics — reports, dashboards, embedded BI | `/analytics` | `MANAGE_REPORTS` |

## Platform

| Page | Path | Permission |
|---|---|---|
| Packages — metadata export and import | `/packages` | `CUSTOMIZE_APPLICATION` |
| Migrations — schema migration planner | `/migrations` | `CUSTOMIZE_APPLICATION` |
| Environments — sandboxes and promotions | `/environments` | `MANAGE_SANDBOXES` |
| Governor limits | `/governor-limits` | `VIEW_SETUP` |
| Monitoring — requests, logs, errors, performance, activity, config health | `/monitoring` | `VIEW_SETUP` |
| Tenants | `/tenants` | `MANAGE_TENANTS` |
| Search index | `/search-settings` | `CUSTOMIZE_APPLICATION` |
| Bulk jobs | `/bulk-jobs` | `MANAGE_DATA` |
| Translations | `/translations` | `CUSTOMIZE_APPLICATION` |
| Deduplicate | `/dedup` | `MANAGE_DATA` |

A few settings pages are reachable through global search or their URL rather than the hub: AI settings
(`/ai-settings`), Email settings (`/email-settings`), Observability settings (`/observability-settings`), System
health (`/system-health`) and the tenant dashboard (`/tenant-dashboard`).

## Keyboard shortcuts

`g h` home · `g c` collections · `g r` resources · `Cmd/Ctrl+K` global search · `/` focus the list filter ·
`e` edit record · `n` new record · `Backspace` back · `?` this list · `Esc` close.
