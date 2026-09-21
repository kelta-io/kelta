---
title: Sandboxes and metadata promotion
description: Clone a tenant into a sandbox, move configuration as metadata packages with export/diff/apply, and promote changes to production through preview, approval, execution and rollback.
section: platform
order: 20
status: partial
---

## Sandboxes are tenants

A sandbox is a real child tenant (slug `<parent>--<name>`) with its own users, data and administrator. Creating
one copies the parent's limits and settings, replaces the seeded administrator's password with a one-time
secret, and then clones the parent's **metadata** — not its records — by exporting a package from the parent
and importing it into the sandbox.

```bash
kelta sandbox create --name dev --wait   # returns the sandbox tenant + one-time admin credential
kelta sandbox list
kelta sandbox status <envId>
kelta sandbox refresh <envId>            # re-clone metadata from the parent
kelta sandbox delete <envId>
```

Console: Setup → Platform → Environments (`MANAGE_SANDBOXES`). API: `/api/environments` (create, refresh, test,
snapshots, diff, archive, delete). Log in to a sandbox as you would to any tenant; the CLI treats it as a
separate profile.

## Metadata packages

A package is a JSON document (`formatVersion: 2`) with `source` provenance and a list of items, each addressed by
**natural keys** (collection name, field name, layout name, menu label…) so it applies across tenants and
clusters without shared UUIDs.

| Item types |
|---|
| `COLLECTION`, `FIELD`, `GLOBAL_PICKLIST`, `PICKLIST_VALUE`, `VALIDATION_RULE`, `PAGE_LAYOUT`, `LAYOUT_SECTION`, `LAYOUT_FIELD`, `LAYOUT_RELATED_LIST`, `FLOW`, `UI_PAGE`, `UI_MENU`, `UI_MENU_ITEM` |

Not yet packaged: scripts, record types, list views, layout rules, dashboards and reports — and **never** profiles
or permissions, which each tenant seeds itself.

```bash
kelta metadata export -n crm -v 1.2.0 -o crm.json   # whole tenant when no ids are given
kelta metadata diff crm.json                        # what would change on this tenant
kelta metadata apply crm.json --dry-run
kelta metadata apply crm.json --conflict overwrite  # skip (default) leaves existing items alone
```

Imports go through the normal write path, so quota hooks, table DDL and NATS broadcasts all fire; an item whose
reference cannot be resolved by name fails on its own without leaving dangling ids. `POST /api/packages/export`
and `/import` are the endpoints (`CUSTOMIZE_APPLICATION`); Setup → Platform → Packages is the console.

## Promotion

Promotion wraps export → import in a governed pipeline from a sandbox to its parent:

```bash
kelta promote create --source <sandboxEnvId> --target <prodEnvId> [--type SELECTIVE --item COLLECTION:invoices]
kelta promote preview <id>      # the diff against production
kelta promote approve <id>      # must be a different user from the creator
kelta promote execute <id>      # snapshots production first, then imports
kelta promote status <id>
kelta promote rollback <id>     # re-imports the pre-execution snapshot
```

API: `/api/promotions` (`create`, `preview`, `approve`, `execute`, `rollback`, `items`). Rules:

- Sandbox → parent only; a package whose source equals the target is rejected.
- The approver cannot be the creator (`409`).
- `FULL` or `SELECTIVE` (chosen natural keys, dependencies pulled in automatically).
- Execution snapshots the target first; rollback re-imports that snapshot in overwrite mode. Items the promotion
  *created* are not deleted by rollback, and promotion does not propagate deletions.

## Remote targets (multi-cluster)

An environment row can describe a target in another cluster: `remoteBaseUrl`, `remoteTenantSlug` and a
`credentialRef` naming a vault PAT whose user has `CUSTOMIZE_APPLICATION` there. `POST /api/environments/{id}/test`
checks connectivity; execution pushes the package to the remote `/api/packages/import`. Remote targets have no
local snapshot, so rollback answers `409` — restore on the remote side. The manual equivalent is
`kelta metadata export` on one cluster and `kelta metadata apply` on the other.

## Schema migrations

Changing a field's type on a collection with data is a separate, planned operation: Setup → Platform →
Migrations snapshots the schema, computes an `ADD/REMOVE/MODIFY` plan with risk and row counts, executes the
`ALTER TABLE`s as a restore-pointed run, and can roll back to the snapshot.
