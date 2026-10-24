---
title: Email, SMS and push configuration
description: Platform SMTP and per-tenant overrides, email templates and the transactional send endpoint, SMS via Twilio, and push through FCM, APNs and browser Web Push.
section: platform
order: 50
---

## Email

**Platform SMTP** is set by the operator (`SMTP_HOST`, `SMTP_PORT`, `SMTP_USERNAME`, `SMTP_PASSWORD`,
`SMTP_AUTH`, `SMTP_STARTTLS`, `EMAIL_FROM_ADDRESS`, `EMAIL_FROM_NAME`, `EMAIL_ENABLED`). The Docker Compose stack
points at Mailpit so nothing leaves the machine.

**Per-tenant override** — Setup → Email settings, or `GET|PUT /api/admin/tenant/email-settings` with
`{ "smtp": { host, port, username, password, useStartTls }, "fromAddress", "fromName", "autoInviteOnCreate" }`;
`POST /api/admin/tenant/email-settings/test` sends a test message. A tenant override takes precedence over the
platform configuration. `autoInviteOnCreate` controls whether newly created users (including SCIM-provisioned
ones) receive an invitation automatically.

**Templates** — Setup → Integration → Email templates (`MANAGE_EMAIL_TEMPLATES`). System defaults
(`user.invite`, `user.password_reset`, `welcome`, …) are addressed by key; a tenant row with the same key
overrides the default. Templates use `${var}` merge fields. `GET /api/admin/email-templates` lists each system
template with the tenant's override, if any; `POST /api/admin/email-templates/{key}/override` copies the system
default into a tenant-owned row to edit, and `DELETE` on the same path reverts to the default.

**Sending** — flows use `EMAIL_ALERT` ([action handlers](/docs/automation/action-handlers/#email_alert)); the
app's *Send email* quick action calls `POST /api/email/send` with a stored template only (no free-form body),
gated on `MANAGE_EMAIL_TEMPLATES` and rate-limited per tenant; reports can be scheduled for delivery. Every send is
recorded in the email log; `GET /api/email/logs?collection=…&recordId=…` returns the log of one record (the record
page's activity timeline), for a caller who can read that record and holds `MANAGE_EMAIL_TEMPLATES`.

**Bounces and complaints** — a tenant whose SMTP override points at Amazon SES can subscribe the identity's bounce
and complaint SNS topics to `POST /api/webhooks/ses`. The endpoint is unauthenticated and verifies the SNS
signature; hard bounces and complaints become suppressions, so later sends to that address are blocked.

## Campaigns

Mass-email campaigns are an optional feature area under `/api/admin/campaigns` (`MANAGE_CAMPAIGNS`; sends count
against the `campaignEmailsPerDay` governor limit). The `campaigns` collection is read-only over the generic API;
every change goes through these endpoints:

| Endpoint | Purpose |
|---|---|
| `GET`, `POST /api/admin/campaigns` | List, create a `DRAFT` |
| `GET`, `PATCH`, `DELETE /api/admin/campaigns/{id}` | Read, edit (only `DRAFT` or `SCHEDULED`), delete |
| `POST /api/admin/campaigns/{id}/test` | Send one test message to `{ "email" }` |
| `POST /api/admin/campaigns/{id}/send` | Queue for sending now |
| `POST /api/admin/campaigns/{id}/schedule` | Queue for `{ "scheduledAt" }` |
| `POST /api/admin/campaigns/{id}/cancel` | Cancel a `DRAFT`, `SCHEDULED` or `QUEUED` campaign |
| `GET /api/admin/campaigns/{id}/stats`, `/recipients` | Delivery, open and click counts; the per-recipient list |
| `GET`, `POST`, `DELETE /api/admin/campaigns/suppressions` | The tenant's suppression list (`DELETE` takes `?email=`) |

Each message carries signed tracking links, served without authentication — the HMAC token
(`CAMPAIGN_TRACKING_SECRET`) is the only credential: `GET /api/track/open` (the open pixel), `GET /api/track/click`
(records the click, then redirects) and `GET`/`POST /api/track/unsubscribe` (the unsubscribe page and its form).

## SMS

One provider for the deployment, selected by `KELTA_SMS_PROVIDER`:

| Value | Behaviour |
|---|---|
| `log` (default) | messages are written to the log — for development |
| `twilio` | Twilio Messages API |

Twilio settings: `TWILIO_ACCOUNT_SID` (always), and either an API key (`TWILIO_KEY_SID` + `TWILIO_AUTH_TOKEN` as
the key secret — recommended, revocable) or the account auth token (`TWILIO_KEY_SID` blank), plus
`TWILIO_FROM_NUMBER` in E.164. Missing credentials never stop the worker from starting; they surface as failed
deliveries.

SMS is used for MFA one-time codes and for alert channels in optional feature areas. Verify delivery in your own
environment before relying on it — the platform's automated tests cannot reach a live Twilio account.

## Push

`kelta.push.provider` selects one **mobile** provider — `log` (default), `fcm` or `apns` (with the `kelta.push.apns.*`
credentials). **Browser Web Push** registers independently whenever VAPID keys are present, so browser and native
subscribers can coexist:

| | |
|---|---|
| Keys | `KELTA_PUSH_VAPID_PUBLIC_KEY` (base64url uncompressed P-256 point), `KELTA_PUSH_VAPID_PRIVATE_KEY` (raw scalar), `KELTA_PUSH_VAPID_SUBJECT` (`mailto:` or `https:` URI) — `make gen-vapid` writes a dev pair |
| Discovery | `GET /api/devices/vapid-public-key` (404 when unconfigured) |
| Registration | `POST /api/devices` with the browser's `PushSubscription` JSON; `GET /api/devices` lists the caller's devices, `DELETE /api/devices/{deviceId}` removes one |
| Sending | `POST /api/admin/notifications` with `{ title, body, userId?, data? }` pushes to one user or the whole tenant (10 per hour per tenant) |
| Pruning | a `404`/`410` from the push service deletes the device; other failures keep it |
| Rotation | changing the VAPID pair invalidates every browser subscription — clients must re-subscribe |

Web Push is implemented on JDK cryptography (RFC 8291/8188/8292) without third-party libraries. Live verification
against real browsers is owed; treat it as functional-but-unproven in production until you have tested it.
