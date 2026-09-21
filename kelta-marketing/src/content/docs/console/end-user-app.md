---
title: End-user app tour
description: The runtime app your users see — apps and navigation, home, lists, records, search, approvals, analytics, API tokens, shortcuts and themes.
section: console
order: 20
---

The end-user app renders the metadata you build: menus become apps and tabs, list views become lists and boards,
page layouts become record pages, custom pages appear at their routes. It lives at
`https://app.example.com/<tenant>/app/…`.

## Shell

- **App switcher** — each `ui-menu` is an app; the active app's items are the tabs across the top. The default
  app is the user's saved preference, else the menu marked default, else the first one.
- **Global search** (`Cmd/Ctrl+K`, or `/app/search`) across every collection the user can read.
- **Notifications bell** — live count of approvals waiting on the user.
- **User menu** — profile, API tokens, theme (light / dark / system), keyboard shortcuts, sign out.
- **Presence avatars** on a record when others have it open; an offline banner when the network drops.

## Routes

| Route | What it shows |
|---|---|
| `/app/home` | greeting, quick actions per collection, recent items, favourites |
| `/app/o/<collection>` | the collection's list — table, kanban, calendar or gallery ([List views](/docs/console/list-views/)) |
| `/app/o/<collection>/new` | create form driven by the layout |
| `/app/o/<collection>/<id>` | record page: layout sections, related lists, attachments, notes, activity timeline, history |
| `/app/o/<collection>/<id>/edit` | edit form |
| `/app/p/<slug>` | a published custom page ([Page builder](/docs/console/page-builder/)) |
| `/app/search` | global search |
| `/app/approvals` | approvals inbox: pending on me, my submissions |
| `/app/analytics`, `/app/dashboards/<id>`, `/app/reports/<id>` | analytics hub, dashboard and report viewers (`VIEW_ANALYTICS`) |
| `/app/api-tokens` | personal access tokens |

Optional feature areas add `/app/chat`, `/app/mailbox`, `/app/appointments`, `/app/provider-availability` and
`/app/visits/<id>` when those modules are enabled for the tenant.

## The record page

A record page is composed from the collection's [page layout](/docs/console/page-layouts/): header (title
fields, avatar, meta row), sections with field placements, a left section navigator, related lists as tabs, and
a side rail of configurable blocks. Without a layout every field renders in one section. The page also carries:

- **Activity timeline** — field history, record versions, flow runs and approvals that touched the record.
- **History tab** — per-field history and full versions when tracking is on.
- **Attachments and notes.**
- **Actions** — edit, delete, submit for approval, share (admin browser), plus any page-builder or module actions.
- **Lock badge** while an approval is pending.

Field-level security and masking are applied server-side; a masked field shows a locked, redacted value.

## Lists

Every list supports filtering, multi-sort, column choice, density, grouping, mass edit, CSV import and saved views;
kanban, calendar and gallery renderers can be published by an admin or switched by the user. See
[List views and saved views](/docs/console/list-views/).

## Personal access tokens

`/app/api-tokens` lets a user create (max 10), see usage of, and revoke their own `klt_` tokens for the CLI, MCP
clients and scripts. See [Personal access tokens](/docs/security/personal-access-tokens/).

## Keyboard shortcuts

| Keys | Action |
|---|---|
| `g h` / `g c` / `g r` | go home / collections / resources |
| `Cmd/Ctrl+K` | global search |
| `/` | focus the list filter |
| `e` / `n` | edit / new record |
| `Backspace` | back |
| `?` | show shortcuts |
| `Esc` | close dialog |

## Offline and installable

The app is a PWA: it can be installed, caches its shell, keeps a per-tenant replica of recently used data for
offline reads and queues writes in an outbox that replays on reconnect. See
[Realtime, presence and offline](/docs/console/realtime-and-offline/).

## Locale and theme

The interface is available in six languages and can be extended with tenant translations; users choose light,
dark or system theme. See [Translations, locales and theming](/docs/console/localization-and-theming/).
