# Security Policy

Kelta Platform is a homelab / single-user project. Security is taken seriously
but this repository is not backed by a dedicated security team — response times
are best-effort.

## Reporting a Vulnerability

Please report suspected vulnerabilities privately by email to
**cklinker@rzware.com**. Do not open a public GitHub issue for security
reports.

Include:
- A description of the issue and its impact
- Steps to reproduce (or a proof-of-concept)
- Affected component(s) (e.g. `kelta-gateway`, `kelta-worker`, `kelta-ui`)

You should expect an acknowledgement within a few days. Triage, fix, and
disclosure timelines are best-effort and depend on severity.

## Data Masking Scope

Field data masking (`MASKED` visibility + `fieldTypeConfig.masking`) is a
**presentation-layer read control** on the JSON:API surface: values are
redacted per user at response time, filter/sort probing is blocked, and
realtime broadcasts suppress record data for masked collections. It is not
full egress control — system-tier paths (flows, scripts, webhooks, exports,
reports, search indexes) still see plaintext; see the data-masking entry in
[`.claude/docs/concerns.md`](.claude/docs/concerns.md) for the current
coverage matrix. Encrypt-at-rest (`ENCRYPTED` field type) and `HIDDEN`
visibility remain the stronger controls where masking is insufficient.

## Known Issues

A running list of known security risks, fragile areas, and tech debt is kept
in [`.claude/docs/concerns.md`](.claude/docs/concerns.md). Check there before
reporting to avoid duplicates.

## Autopilot and Security-Typed Changes

This repository uses an autopilot loop that can open and auto-merge pull
requests for routine work. Tasks of `type: security` are gated to manual
review and are **never** auto-merged — a human approves every security
change before it lands on `main`.

## Tenant Provisioning

A newly provisioned tenant has **no default credential**. Its seeded System
Administrator (`<slug>-admin`) is created with an unusable password and can only
be claimed by invite:

- pass `adminEmail` when creating the tenant (`POST /api/tenants`) — the admin
  gets that email and the `user.invite` email is sent; `adminEmail` is not
  stored on the tenant; or
- send the invite later from Setup › Tenants › Invite admin
  (`POST /api/tenants/{id}/admin-invite`, requires `MANAGE_TENANTS`, written
  to the security audit log).

Tenants provisioned before this change had a well-known default admin
password; migration V204 invalidated it wherever it was never rotated. See the
PLT-337 entry in [`.claude/docs/concerns.md`](.claude/docs/concerns.md),
including the open question about the baseline platform admin.
