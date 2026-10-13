# Slice 6 — Web Push (VAPID)

> Child of `specs/consumer-alerting/README.md`. Depends on slice 4 (the dispatch path). Adds
> the browser push channel beside the existing native providers.

> **Landed.** `WebPushCrypto`, `WebPushProvider`, `PushProvider.supports()`, per-device
> routing, `V180`, the controller surface, and 29 unit tests. Four notes against the design
> below:
> - **Hand-rolled on JDK crypto, as §5 anticipated.** Every candidate library pulls
>   BouncyCastle. The check that makes this defensible is `WebPushCryptoTest` reproducing the
>   **RFC 8291 §5 published ciphertext byte-for-byte**: an encryption bug here does not throw
>   and does not fail a smoke test — the browser just discards the body — so vector
>   conformance is the only cheap proof. Do not touch that class without re-running it. The
>   VAPID JWT uses nimbus, already on the classpath and already native-proven via LiveKit.
> - **The key endpoint is `GET /api/devices/vapid-public-key`, not `/api/push/…`.** §2's path
>   would be a new top-level `/api/push/**` segment, and a segment without a gateway static
>   route 404s. Hanging it off the existing `/api/devices/**` route costs nothing and skips
>   that trap.
> - **No Cerbos change was needed.** §5's last bullet asked to confirm portal reach:
>   `/api/devices/**` is a static route, so it gets only the blanket `API_ACCESS` check, and
>   the seeded Portal User profile has exactly that (`TenantProvisioningHookTest` locks it).
> - **A missing/unparseable VAPID key fails WITHOUT pruning.** The obvious symmetry with
>   404/410 is wrong here: an operator config gap would silently delete every browser
>   subscription in the tenant. Only the push service saying the subscription is gone prunes.
>
> Also added beyond the spec: `make gen-vapid` (the key encoding — uncompressed point for the
> public half, raw scalar for the private — is easy to get wrong by hand, and a mismatch
> surfaces as an opaque 401 from the push service; `acceptsGeneratedPair` guards the recipe).
> **Live-stack verification is still owed** — §8's native-image crypto risk is real and CI
> cannot reach it.

## 1. Goal & scope

Delivers: standards-based browser Web Push (VAPID ES256 + RFC 8291 aes128gcm) as a
`WebPushProvider` in the existing `PushProvider` SPI, per-platform routing in
`DefaultPushService`, subscription storage, and portal-user access to `/api/devices`.

Does not deliver: the consumer frontend service worker (outside this repo — contract below),
user-facing service-worker update prompts, admin broadcast UI changes.

## 2. UI samples

N/A — backend. Frontend contract: service worker
`pushManager.subscribe({userVisibleOnly: true, applicationServerKey: <VAPID public key>})` →
`POST /api/devices` `{platform: "web", deviceToken: sha256(subscription.endpoint), deviceName,
subscription: <full JSON>}`. `GET /api/push/vapid-public-key` (or an FE env var) exposes the
public key.

## 3. Data & API contracts

- `PushProvider` SPI gains `default boolean supports(String platform) { return true; }`.
- `WebPushProvider` — registered whenever VAPID keys are configured
  (`kelta.push.vapid.{public-key,private-key,subject}` env/secret), **alongside** the
  property-selected mobile provider (not exclusive with `kelta.push.provider`).
- `DefaultPushService` routes per device: `web` → WebPushProvider (404/410 from the push
  service → prune the device, reusing the existing stale-token behavior); otherwise the
  current provider.
- `deviceToken = sha256(endpoint)` preserves `UNIQUE (tenant_id, device_token)` and the
  500-char column; the full subscription JSON goes in a new `web_push_subscription TEXT`
  column.
- Per-tenant VAPID overrides can ride `TenantPushSettings` later; platform-level keys in v1.

## 4. DB migrations

`V<n>__push_device_web_subscription.sql` (head+1 at implementation time):
`ALTER TABLE push_device ADD COLUMN web_push_subscription TEXT`.

## 5. File-by-file code changes

- `kelta-worker/.../service/push/PushProvider.java` — `supports()` default method.
- `kelta-worker/.../service/push/WebPushProvider.java` — NEW. Library choice in-slice: prefer
  a minimal RFC 8291/8292 implementation (a small web-push Java lib, or hand-rolled JOSE ES256
  + aes128gcm on JDK crypto). **If the library pulls BouncyCastle, every reflectively-used
  class needs worker `reflect-config.json` entries (native-image rule) — weigh hand-rolling on
  JDK EC crypto to avoid the dependency.**
- `kelta-worker/.../service/push/DefaultPushService.java` — platform routing + prune on
  404/410.
- `kelta-worker/.../controller/PushDeviceController.java` + `PushRepository` — accept/store
  `subscription`; the vapid-public-key endpoint.
- Gateway/Cerbos: confirm PORTAL users may reach `/api/devices` (add a per-endpoint allowance
  if not).

## 6. Test plan

Unit: WebPushProvider encryption round-trip against RFC 8291 test vectors; VAPID JWT header
shape; routing (web vs mobile); prune on 410. Harness: register a web device as a portal user
→ row carries the subscription JSON; dispatch an alert → a delivery row references the web
channel (send mocked). Live: a real browser subscription on the deployed stack receives a push
— native-image crypto verification is exactly the failure class CI cannot catch.

## 7. Docs to update

`status.md` PWA/push rows (web push shipped) · `README.md` env vars (`KELTA_PUSH_VAPID_*`) ·
`integrations.md` push section.

## 8. Risks & open questions

Native-image crypto reflection (BouncyCastle) — decide library vs JDK-only in-slice;
live-stack verification is mandatory. Push-service endpoints rotate — the prune path handles
it. Safari requires a strict VAPID subject (`mailto:`) — verify cross-browser post-deploy.
