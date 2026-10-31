---
title: Email, SMS and push configuration
description: Platform SMTP and per-tenant overrides, email templates and the transactional send endpoint, SMS via Twilio, push through FCM, APNs and browser Web Push, and availability-alert channels including webhooks.
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

## Availability alert channels

An availability alert is delivered over each channel its watch asks for, narrowed to the member's `channels`
entitlement when the tenant gates channels (a tenant that gates must list every channel it offers, including
`webhook`). With no `channels` entitlement the tenant is not gating, and every free channel is delivered.

| Channel | Delivery | Billable |
|---|---|---|
| `push` | the member's registered devices (see Push) | no |
| `email` | the `availability.alert` email template to the member's address | no |
| `sms` | the member's phone number (see SMS); only ever sent on an affirmative entitlement | yes |
| `webhook` | a JSON `POST` to the tenant's webhook URL | no |

Each delivery is recorded as `SENT` or `FAILED` with a reason; there are no retries. `GET /api/alerts/latency`
accepts `?channel=push|email|sms|webhook`.

### Webhook

The body is:

```json
{
  "alertId": "…",
  "watchId": "…",
  "targetId": "…",
  "slotKey": "2026-08-14",
  "eventTime": "2026-08-01T12:00:00Z",
  "dispatchTime": "2026-08-01T12:00:01.234Z"
}
```

`eventTime` is when the alert was created (the opening was detected); `dispatchTime` is when the worker sent the
request. Any `2xx` answer records the delivery `SENT`; anything else records `FAILED` with the HTTP status.

| | |
|---|---|
| Tenant setting | `alerts.webhookUrl` in the tenant's `settings` JSON, e.g. `{"alerts": {"webhookUrl": "https://hooks.example.com/alerts"}}` |
| Fallback | `KELTA_ALERTS_WEBHOOK_URL` (worker), used for every tenant without its own URL — for self-hosted installs |
| No URL | the delivery records `FAILED` with `webhook URL not configured`; watches are still accepted |
| Timeouts | 3 s to connect, 5 s for the whole request; redirects are not followed |

The URL is checked by the same outbound guard as other tenant-supplied URLs: it must resolve to a public address.
A receiver on a private network (an in-cluster service, an RFC 1918 host) is refused — recorded `FAILED` with
`webhook URL refused: the host resolves to a non-public address` — unless the operator lists its range in
`KELTA_OUTBOUND_ALLOWED_PRIVATE_CIDRS` on the worker (comma-separated CIDRs).

### Synthetic events

To test the alert path end to end (an E2E suite, a scheduled canary) without a provider poll, mark a watch target
`synthetic` and inject an observation for it. The injected observation goes through the same matcher and delivery
code as a polled one, so it creates the same alert and delivery rows, records the same latency metric, and sends
the same `availability` flow trigger. That trigger's summary carries `"synthetic": true`, so flows can ignore it.

**The `synthetic` flag.** `synthetic` is a boolean field on `watch-targets` (default `false`). A synthetic target
can only be driven by the injector:

- the poller work list (active targets for a source) leaves synthetic targets out;
- an event a poller publishes on `kelta.availability.event.<tenantId>.<source>` for a synthetic target is dropped
  before anything is recorded;
- the injector refuses a target that is not synthetic.

A poller that lists its targets over JSON:API should add `filter[synthetic][eq]=false` so it never polls a
synthetic target.

**The endpoint.** `POST /api/admin/availability/synthetic-events` requires an internal user whose profile holds
`MANAGE_DATA`. The body:

```json
{
  "targetId": "…",
  "slotKey": "2026-08-14",
  "window": { "start": "2026-08-14T00:00:00Z", "end": "2026-08-16T00:00:00Z" },
  "quantity": 2
}
```

Only `targetId` is required. The endpoint records one `OPEN` observation and answers `201`:

```json
{ "data": { "targetId": "…", "slotKey": "synthetic-…", "status": "OPEN", "alerts": 2, "alertIds": ["…", "…"] } }
```

`alerts` is the number of watches alerted. Each one was delivered over its channels before the response returned.

**Repeat injections.** An alert is sent once per opening of a slot. Without a `slotKey`, each injection uses a new
`synthetic-<uuid>` slot, so every call is a new opening and alerts each matching `ACTIVE` watch again. This is the
mode for a canary. With an explicit `slotKey`, the slot follows the normal rules: injecting again while it is still
`OPEN` alerts no one (`alerts: 0`). Each fresh slot leaves one `availability_state` row behind.

Refusals use the JSON:API error envelope:

| Status | `code` | When |
|---|---|---|
| `403` | `FORBIDDEN` | the caller is a portal user, or an internal user without `MANAGE_DATA`. The gateway also refuses portal users on every `/api/admin/**` path. |
| `400` | `INVALID_PAYLOAD` | `targetId` is missing, `window.start`/`window.end` is not an ISO-8601 instant, or `quantity` is not an integer |
| `404` | `TARGET_NOT_FOUND` | no watch target with that id in the tenant |
| `422` | `TARGET_NOT_SYNTHETIC` | the target is a real, polled target |
