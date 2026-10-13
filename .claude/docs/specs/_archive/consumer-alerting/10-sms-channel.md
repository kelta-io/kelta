# Slice 10 — SMS Alert Channel (Twilio)

> Child of `specs/consumer-alerting/README.md`. Backend-only, small. Turns the already-accepted
> `sms` alert channel from an honest seam into a real sender, so a Pro-tier member can get an
> availability alert as a text.
>
> Source-verified 2026-08-08. No DB migration.

## 1. Goal & scope

**Delivers:** a production `TwilioSmsProvider` behind the existing `SmsProvider` SPI, and the
wiring in `AlertDispatchService` that sends the `sms` channel through it (looking up the
member's `platform_user.phone_number`). Selected with `kelta.sms.provider=twilio`; the default
stays `LogOnlySmsProvider`.

Before this slice, `AlertDispatchService`'s `sms` case threw `UnsupportedOperationException`
("sms channel is not wired yet") — the channel was accepted, entitlement-gated, and recorded as
a FAILED delivery, but never sent. `DefaultSmsService`/`SmsProvider` existed only for MFA
verification codes; there was no production `SmsProvider` (only `LogOnlySmsProvider`) and no
Twilio provider anywhere.

**Does not deliver:** a live Twilio round-trip in CI (needs a real account — an external
provisioning step; the HTTP path is covered against a mock server instead); per-tenant Twilio
credentials (platform-level config for now — the credential-vault precedent exists if a
per-tenant sender is later needed); phone-number capture UX (the column exists;
collecting/verifying a member's number is portal-frontend work).

## 2. UI samples

N/A — backend. A member opts into SMS by having `sms` in their watch channels ∩ plan
entitlement and a `phone_number` on file.

## 3. Data & API contracts

No new endpoints or tables. Recipient number is read from `platform_user.phone_number` (E.164).
Config keys (from the deployment secret):

| Key | Meaning |
|-----|---------|
| `kelta.sms.provider` | `log` (default) or `twilio` |
| `kelta.sms.twilio.account-sid` | Twilio Account SID (`AC…`) — always used in the request URL |
| `kelta.sms.twilio.key-sid` | API Key SID (`SK…`), Basic-auth user — recommended; blank ⇒ use the account SID |
| `kelta.sms.twilio.auth-token` | API Key secret (with `key-sid`) or the account Auth Token (without) |
| `kelta.sms.twilio.from-number` | Sending number (E.164) |

## 4. DB migrations

None — `platform_user.phone_number` already exists (V1 baseline).

## 5. File-by-file code changes

- Worker `service/sms/TwilioSmsProvider.java` — `SmsProvider` impl,
  `@ConditionalOnProperty(kelta.sms.provider=twilio)`. **Plain REST** (form-encoded POST to the
  Twilio Messages API on Spring's `RestClient`), deliberately **not** the `twilio-java` SDK — same
  native-image reasoning as the payment client. **Constructs even when unconfigured** (validates
  on `send`), so selecting `provider=twilio` without a secret degrades to a failed SMS delivery,
  never a dead worker. Two constructors → `@Autowired` on the Spring one (the documented
  multi-constructor trap).
- Worker `service/availability/AlertDispatchService.java` — inject `SmsProvider`; the `sms` case
  looks up the member's phone (`memberPhone`, mirror of `memberEmail`) and calls
  `smsProvider.send(...)`; no phone on file → a FAILED sms delivery (like a stale push token),
  never a rollback.
- Worker `application.yml` — document the `kelta.sms.*` keys (defaults keep `log`).

## 6. Test plan

- **Unit** — `TwilioSmsProviderTest`: posts a form-encoded message with Basic auth to the
  Messages API and succeeds on 2xx (`MockRestServiceServer`); maps a Twilio error response to
  `SmsDeliveryException`; fails (no HTTP) when selected-but-unconfigured; rejects an empty
  message; **constructs with blank config** (boot-safety).
- **Unit** — `SmsProviderWiringTest` (`ApplicationContextRunner`): exactly one `SmsProvider` bean
  per `kelta.sms.provider` value (default→log, twilio→Twilio-only-and-boots-unconfigured,
  log→log) — the multi-bean/boot guard, the `PushProviderWiringTest` pattern.
- **Unit** — `AlertDispatchServiceTest`: sms is **sent** and marked SENT when the member has a
  phone; a member with no phone fails only the sms channel; an SMS provider failure is marked
  FAILED, not propagated. (Replaces the old "honest seam" assertion.)

## 7. Docs to update (same PR)

`status.md` (SMS-channel row; the slice-4 "honest seam" note now points here),
`integrations.md` (SMS/Twilio subsection beside push), parent `README.md` slice table.

## 8. Risks & open questions

- **Live verification is owed and needs a real account.** The native-image behavior of the
  `RestClient` POST + Basic-auth header is exactly what CI can't reach; verify one real send
  against a Twilio trial number after the secret is provisioned.
- **Cost + abuse.** SMS is a paid channel gated to the entitlement that lists `sms` — keep it a
  paid-tier entitlement so a free member cannot burn credits. Per-member send volume is bounded
  by the alert dedupe (one alert per opening) already.
- **Number validity.** `TwilioSmsProvider` sends whatever `platform_user.phone_number` holds;
  Twilio rejects a malformed number with an error that becomes a FAILED delivery. Server-side
  E.164 validation at phone-capture time (portal frontend) is the right place to prevent it.
- **Per-tenant sender.** Credentials are platform-level; a tenant wanting its own Twilio number
  would move these to the credential vault (the `StripeCredentialType` precedent) — deferred
  until a tenant needs it.
