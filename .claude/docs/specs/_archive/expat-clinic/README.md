# Expat Clinic Tenant — "Atlântico Health & Relocation" Build Plan

**Status:** PLAN (2026-07-11) · **Owner:** Craig · **Type:** Sample-tenant buildout + one small platform slice + new Next.js repo

A complete sample medical practice for expats in Portugal, built ON the Kelta platform in the
new telehealth tenant: medical specialties, relocation/settling-in services, a generic product
store (physical / digital / service products), scheduling (in-person + online video),
provider availability, price lists, billing & invoicing (prepaid and postpaid), and a
multi-language customer-facing Next.js site with registration, booking, cart, checkout, and
payment.

Everything in this plan is grounded against the codebase as of main @ `87a603a1`
(telehealth slices 1–7 shipped). Sections marked **PLATFORM GAP** are the only items
requiring code in the `emf` repo; everything else is tenant metadata, flows, seed data,
or the new Next.js repo.

---

## 1. Concept

**Practice:** *Atlântico Health & Relocation, Lda.* (fictional) — Lisbon clinic + Cascais
clinic + Online. Serves expats/immigrants in Portugal with three lines of business:

| Line | Examples | Delivery |
|---|---|---|
| **Medical** | GP/family medicine, mental health, pediatrics, dermatology, gynecology, physiotherapy, nutrition, travel medicine/vaccination | In-person or video |
| **Relocation** | NIF acquisition, AIMA appointment prep, SNS registration, visa consults (D7/D8/digital nomad), driving-license exchange (IMT), banking setup, housing search, school enrollment, pet import, tax briefing (IFICI / "NHR 2.0") | Mostly video, some in-person |
| **Store** | Physical goods (adapters, guidebooks, first-aid, BP monitors), digital goods (e-guides, webinar recordings), service products (session packs) | Ship, pickup, or online |

**Customer languages:** en (default), pt-PT, fr, de, es, uk. Staff app: en + pt-PT.
**Currency:** EUR only. **Timezone:** Europe/Lisbon (mind WET/WEST DST); customers may book from abroad, so the portal always renders both clinic time and viewer-local time for online visits.

---

## 2. Architecture Overview

```
                       ┌────────────────────────────────────────────────┐
                       │  Next.js portal (new repo, e.g. atlantico-web) │
  Customer browser ───▶│  App Router · next-intl · Tailwind             │
                       │  BFF routes hold ALL Kelta + Stripe secrets    │
                       └───────┬───────────────────────┬────────────────┘
                               │ service-account PAT   │ portal-user JWT (after login)
                               ▼                       ▼
                    api.kelta.io (gateway) ── /api/<collections> · /api/telehealth/** · /api/chat/**
                               │
                     kelta-worker (flows, telehealth, email) ── NATS ── LiveKit webhook
                               │
   Stripe ──webhook──▶ Next.js /api/stripe-webhook (sig verify) ──▶ Kelta (payments record / flow)
   LiveKit ─────────── browser connects directly wss://livekit.<domain> for video
```

Non-negotiable facts driving this shape (verified):

1. **No anonymous reads.** Every gateway request needs JWT or PAT (only HMAC-token escapes:
   `/api/telehealth/visits/{token}`, tracking links). The public catalog is therefore served by
   the Next.js server, which calls Kelta with a **service-account PAT** and caches (ISR).
2. **Portal users have zero collection access** (Portal User profile = `API_ACCESS` only;
   product path is `/api/chat/**` + `/api/telehealth/**` + `record_share` grants). So orders,
   invoices, client profiles etc. are read/written **by the BFF** using the service account,
   with ownership enforced in BFF code (session → clientId → filter).
3. **PATs have no per-token scoping** — scope comes from the service account's profile. We
   create dedicated INTERNAL service users with minimal custom profiles (§5).
4. **CORS/custom-domain for the API is not a solved path** — irrelevant here: browser never
   calls Kelta directly except LiveKit (own host) and, later, the realtime WebSocket.

---

## 3. What the Platform Already Provides (do NOT rebuild)

| Capability | Shipped as | How this tenant uses it |
|---|---|---|
| Portal identity (passwordless magic-link, invite API, `user_type=PORTAL` JWT) | Telehealth slice 1, V167 | Customer accounts. Registration = BFF calls `POST /api/admin/users/portal-invite` |
| Provider availability (RULE/EXCEPTION rows, per-row timezone) | `telehealth-availability` system collection | Weekly schedules per provider + holiday/vacation exceptions |
| Slot computation | `GET /api/telehealth/slots?providerId&from&to&duration` (62-day cap) | Booking wizard slot grid (portal + Next.js) |
| Race-safe booking | `POST /api/telehealth/appointments` (pg advisory lock + re-check) | All bookings funnel through this — never write appointment rows directly |
| Appointments | `telehealth-appointments` system collection (`status`, `visitType`, `reason`, `videoSessionId`, `conversationId`) | `visitType` carries our service code; extended data lives in companion collection (§6.3) |
| Visit links | HMAC token → `GET /api/telehealth/visits/{token}` → magic-login → `/app/visits/{id}` | Patient video entry from email. v1 portal "Join" button = this link |
| Video | LiveKit self-hosted; token endpoints; webhook → `kelta.video.session.<tenant>.<sessionId>` NATS; consent; recording | Online visits; post-visit flows trigger off the ENDED event |
| Reminders + emails + .ics | `AppointmentReminderSweep`; seeded `appointment_confirmed/reminder/cancelled` templates (tenant-overridable) | Override with branded, per-locale templates (§12) |
| Chat | `chat-*` collections, `/api/chat/**`, WS `chat.join`; portal widget + staff console | "Reception" support queue; pre-visit questions |
| Archival & retention | `telehealth-archives`, retention settings, legal hold, purge (dry-run default) | GDPR/telemedicine record keeping (§13.6) |
| Calendar/kanban/gallery/saved views, mass edit | app-data-entry slices (2026-07-08) | Provider "My Schedule" calendar view; product gallery; order kanban |
| Flows | Triggers: RECORD_CHANGE, API_INVOCATION, WEBHOOK, SCHEDULED (cron), NATS_TRIGGERED; 16 handlers incl. email, HTTP callout, script (GraalVM), SQL query, delay, NATS publish | All automation in §11. Inputs read as `$.input.<key>` (double-wrap rule!) |
| Field types | 29 incl. CURRENCY, JSON, ARRAY, MULTI_PICKLIST, AUTO_NUMBER, FORMULA, ROLLUP_SUMMARY, ENCRYPTED, MASTER_DETAIL | Data model §6; invoice numbers = AUTO_NUMBER; totals = rollup + snapshot |
| Composite unique constraints (runtime) | `POST /api/admin/collections/{name}/unique-constraints` | SKU/tenant, (product, location) inventory, appointmentId 1:1 companions |
| Validation rules (CEL cross-field), record scripts | V157+ | NIF format, date sanity, qty ≥ 0, state-transition guards |
| Attachments (S3 presigned up/down, 15-min URLs) | Shipped | Product images (BFF re-signs + caches), invoice PDFs later |
| Tenant SMTP + email templates w/ `${field}` merge | Shipped | Branded transactional mail |
| Tenant i18n overlay (`ui-translations`) | app-intelligence slice 4 (UI labels only) | Staff-app label tweaks; NOT record data (§12) |
| Governor limits | API/day by tier, `telehealthEnabled`, `videoMinutesPerMonth`, `maxPortalUsers` | Phase 0 checks; ISR caching keeps API/day sane |
| Outbound webhooks (Svix) on record change | Shipped | Optional: Next.js ISR revalidation pings (§14.6) |

---

## 4. Gaps — the short list

### 4.1 PLATFORM GAP (required): Headless portal auth

Portal login today is Thymeleaf pages in kelta-auth; `verify` 302-redirects to
`{ui-base-url}/{tenantSlug}/app` (kelta-ui, not our site). A third-party frontend cannot
complete login cleanly.

**New platform slice (small, ~1–1.5 days, PR to `emf`):** `specs/_archive/telehealth/8-portal-auth-headless.md`

- `POST /portal/api/login/request {email}` → 202 (JSON, enumeration-safe, same token machinery).
- `POST /portal/api/login/verify {token}` → 200 `{accessToken, expiresAt}` (same JWT the session flow mints; portal claims).
- Per-tenant **redirect/origin allowlist** so magic-link emails can land on `https://<portal-domain>/auth/callback?token=…` instead of kelta-ui. Config on tenant settings; broadcast via NATS per Critical Rule 1.
- Invite email template gains a tenant-configurable landing URL (same allowlist).
- Keep Thymeleaf pages working (kelta-ui portal unaffected).
- Security review path (no auto-merge, per SECURITY.md).

Until it lands, Next.js dev can proceed with catalog/store/checkout (service PAT only) — login-dependent pages come after.

### 4.2 Accepted platform limitations (design around, no code)

| Limitation | Consequence / workaround |
|---|---|
| System collections (`telehealth-*`) fixed shape | Companion tenant collection `appointment-details` 1:1 by `appointmentId` (§6.3) |
| Availability authoring = admin JSON:API only | Practice manager maintains schedules in admin UI; provider self-service = later platform idea |
| Record-data i18n not a platform feature | i18n JSON fields on catalog collections (`nameI18n` etc.), resolved by Next.js (§12.2) |
| Email localization has no per-recipient locale | Template-key-per-locale convention `<key>.<locale>` + flow Decision/script picks by client language (§12.4) |
| Flow email actions: no attachments | Invoice "PDF" = portal HTML page (print CSS) v1; certified-invoicing integration later (§9.6) |
| No payment processor integration | Stripe entirely in Next.js BFF; webhook writes back via service PAT (§10) |
| Presigned S3 URLs expire in 15 min | BFF endpoint `/api/img/[attachmentId]` re-signs + CDN-caches product images (§8.5) |
| Appointments created as CONFIRMED (no draft status param) | Prepaid = book-then-pay with auto-cancel sweep for unpaid holds (§7.3) |
| PAT = full user permission (no token scoping) | Minimal-profile service accounts (§5.2) |

---

## 5. Identity & Security Model

### 5.1 Human users

| Persona | Kelta type | Profile (custom) | Sees |
|---|---|---|---|
| Practice admin (Craig/demo admin) | INTERNAL | System Administrator | Everything |
| Practice manager / front desk | INTERNAL | `Front Desk` | Clients, appointments (all), orders, availability admin, chat console; **no** invoices config, no setup |
| Provider (doctor / relocation specialist) | INTERNAL | `Provider` | Own appointments (record-level), own client context, chat, join video; read-only services/prices |
| Finance | INTERNAL | `Finance` | Invoices, credit notes, payments, orders (read), reports; client billing fields incl. NIF |
| Store manager | INTERNAL | `Store Manager` | Products, categories, inventory, orders fulfillment |
| Customer (expat) | PORTAL | Portal User (seeded, immutable) | `/api/telehealth/**`, `/api/chat/**`, own archives — everything else via BFF |

Notes:
- **Providers must be INTERNAL users** — `telehealth-appointments.providerId` is a lookup to `users`. The `providers` collection (§6.1) is an *extension profile* keyed by `userId`, not the identity itself.
- Field-level security: mask `nif`, `dateOfBirth`, `insurancePolicyNumber` on `clients` for `Store Manager` (data-masking shipped, PR #1173); clinical `notes` visible to `Provider`/`Front Desk` only.
- Record-level: `Provider` profile restricted to appointments where `providerId == currentUser` via Cerbos record-level rules.

### 5.2 Service accounts (INTERNAL users + PATs, max 10 tokens/user)

| Account | Profile grants | Used by |
|---|---|---|
| `svc-portal-read` | Read: products, product-categories, services, providers, price-list-entries, inventory-items (qty only) | Next.js public catalog (ISR) |
| `svc-portal-write` | CRUD: clients, orders, order-lines, payments, appointment-details, service-credits; read invoices; MANAGE_USERS (portal-invite only path) | Next.js BFF authenticated actions + Stripe webhook |
| `svc-automation` | What flows need beyond flow-engine context (usually nothing extra) | reserved |

Rotate PATs via `/api/me/tokens`; store in the portal's K8s Secret (sealed — remember the
live-only-keys hazard when sealing).

### 5.3 Tenant prerequisites (Phase 0 checklist)

- [ ] Tenant tier/limits: `telehealthEnabled=true`, `videoMinutesPerMonth` ≥ 3000, `maxPortalUsers` ≥ 1000, API/day ≥ 100k (or set explicit limits JSON).
- [ ] Create admin PAT in the telehealth tenant → add **new MCP server entry** (e.g. `kelta-th-admin` / `kelta-th-user`). Current `kelta-admin` MCP is bound to the streaming-TV demo tenant (`5dc71a70…`) — verified, wrong tenant. Do not build against it.
- [ ] Tenant SMTP settings (from: `care@<domain>`); Mailpit for dev.
- [ ] Stripe test account (+ webhook signing secret).
- [ ] Decide portal domain (suggest `atlantico.rzware.com` for homelab; kelta custom-domain not needed since the site is standalone).
- [ ] TURN-TLS 5349 already live (LiveKit infra done 2026-07-11); production gate: test video from a restrictive network.
- [ ] Retention settings: keep defaults (`retentionYears=7`), purge stays dry-run.

---

## 6. Data Model (tenant collections)

Conventions: kebab-case collection names; `CURRENCY` fields EUR; all catalog display text
uses paired fields `name` (staff/default, English) + `nameI18n` (JSON `{en,pt,fr,de,es,uk}`);
lookups are LOOKUP unless cascade delete is wanted (MASTER_DETAIL for line items).

### 6.1 Parties

**`clients`** — the patient/customer profile (portal user extension)
| Field | Type | Notes |
|---|---|---|
| portalUserId | STRING(36), unique | Link to platform PORTAL user |
| firstName / lastName | STRING | |
| email | EMAIL, unique | |
| phone | PHONE | +351 default hint |
| dateOfBirth | DATE | masked for store staff |
| nationality | PICKLIST | ISO country list |
| preferredLanguage | PICKLIST(en,pt,fr,de,es,uk) | drives email locale |
| nif | STRING(9) | PT tax number; validation rule: 9 digits + record-script check-digit; optional |
| snsNumber | STRING | optional |
| addressStreet/City/PostalCode/Country | STRING | postal regex `\d{4}-\d{3}` when country=PT |
| insuranceProvider | PICKLIST | Médis, Multicare, AdvanceCare, Allianz Care, Cigna Global, SafetyWing, Other, None |
| insurancePolicyNumber | STRING | masked |
| gdprHealthConsentAt / gdprMarketingConsentAt | DATETIME | + consentVersion STRING |
| emergencyContactName/Phone | STRING/PHONE | |
| notes | TEXT | clinical-adjacent; FLS-restricted |

**`providers`** — extension profile for INTERNAL provider users
| Field | Type | Notes |
|---|---|---|
| userId | STRING(36), unique | platform user |
| displayName | STRING | "Dr.ª Ana Ferreira" |
| providerType | PICKLIST | PHYSICIAN, PSYCHOLOGIST, PHYSIOTHERAPIST, NUTRITIONIST, NURSE, RELOCATION_SPECIALIST, TAX_ADVISOR |
| specialties | MULTI_PICKLIST | §13.2 list |
| languagesSpoken | MULTI_PICKLIST(en,pt,fr,de,es,uk,…) | booking filter |
| bio / bioI18n | TEXT / JSON | portal display |
| licenseNumber | STRING | Ordem dos Médicos cédula etc.; shown on portal (PT expectation) |
| photoAttachmentId | STRING(36) | |
| defaultLocationId | LOOKUP(locations) | |
| visitModes | MULTI_PICKLIST(IN_PERSON, ONLINE) | |
| acceptingNew | BOOLEAN | |
| active | BOOLEAN | |

**`locations`** — Lisbon, Cascais, Online
name, nameI18n, kind PICKLIST(CLINIC, VIRTUAL), address fields, timezone (Europe/Lisbon), directionsI18n JSON, active.

### 6.2 Services & pricing

**`services`** (bookable catalog — medical + relocation)
| Field | Type | Notes |
|---|---|---|
| code | STRING, unique | e.g. `GP_ONLINE_30` — written into `telehealth-appointments.visitType` |
| name / nameI18n / descriptionI18n | STRING/JSON | |
| category | PICKLIST | MEDICAL, MENTAL_HEALTH, RELOCATION, WELLNESS |
| specialty | PICKLIST | matches provider specialties |
| durationMinutes | INTEGER | feeds slots `duration` |
| visitModes | MULTI_PICKLIST(IN_PERSON, ONLINE) | |
| paymentPolicy | PICKLIST | **PREPAID**, **POSTPAID** |
| vatClass | PICKLIST | EXEMPT_M07, STANDARD_23, REDUCED_6 (§9.3) |
| cancellationHours / lateCancelFeePct / noShowFeePct | INTEGER | policy §13.8 |
| requiresIntake | BOOLEAN | stretch |
| active, sortOrder | | |

**`provider-services`** — join: providerId, serviceId, priceOverride CURRENCY (nullable), active. Unique (providerId, serviceId).

**`price-lists`** — name, code (STANDARD, INSURANCE_DIRECT, CORPORATE), currency (EUR), active, validFrom/To.
**`price-list-entries`** — priceListId (MASTER_DETAIL), itemType PICKLIST(SERVICE, PRODUCT), serviceId LOOKUP, productId LOOKUP, unitPrice CURRENCY, taxIncluded BOOLEAN (true — PT B2C prices displayed VAT-inc). Unique (priceListId, serviceId), (priceListId, productId). v1 portal uses STANDARD only; structure supports insurer/corporate lists later.

### 6.3 Scheduling companion

**`appointment-details`** — 1:1 extension of the system appointment (system collections can't take custom fields)
| Field | Type | Notes |
|---|---|---|
| appointmentId | STRING(36), unique | the `telehealth-appointments` id |
| clientId | LOOKUP(clients) | denormalized from portalUserId for reporting |
| serviceId | LOOKUP(services) | |
| providerUserId | STRING(36) | denormalized |
| mode | PICKLIST(IN_PERSON, ONLINE) | |
| locationId | LOOKUP(locations) | Online → virtual location |
| priceSnapshot | CURRENCY | price at booking |
| vatClassSnapshot | PICKLIST | |
| paymentPolicy | PICKLIST(PREPAID, POSTPAID) | |
| paymentStatus | PICKLIST | PENDING, PAID, INVOICED, WAIVED, REFUNDED, CREDIT_USED |
| orderId | LOOKUP(orders) | when paid via checkout |
| invoiceId | LOOKUP(invoices) | when invoiced post-visit |
| bookedVia | PICKLIST(PORTAL, ADMIN) | |
| holdExpiresAt | DATETIME | prepaid auto-cancel deadline |

**`practice-holidays`** — date DATE, name, locationId LOOKUP nullable (null = all), closesBooking BOOLEAN. The manager mirrors these into `telehealth-availability` EXCEPTION rows per provider (flow-assisted, §11 F9); seed PT national holidays 2026 (§13.9).

### 6.4 Store

**`product-categories`** — name/nameI18n, slug unique, parentId LOOKUP(self), sortOrder, imageAttachmentId, active. Two trees seeded: Physical (Adapters & Power, Health at Home, Books & Guides, Comfort) and Digital (E-Guides, Webinars) + Service Packs.

**`products`** — the generic catalog
| Field | Type | Notes |
|---|---|---|
| sku | STRING, unique | |
| name / nameI18n / descriptionI18n | | |
| productType | PICKLIST | **PHYSICAL**, **DIGITAL**, **SERVICE** |
| categoryId | LOOKUP(product-categories) | |
| fulfillmentModes | MULTI_PICKLIST | SHIP, PICKUP, ONLINE — matrix: PHYSICAL→SHIP/PICKUP, DIGITAL→ONLINE(download), SERVICE→ONLINE(booking entitlement) |
| vatClass | PICKLIST | EXEMPT_M07 / STANDARD_23 / REDUCED_6 |
| linkedServiceId | LOOKUP(services) | SERVICE products: what the purchase entitles (e.g. 5-pack) |
| creditQty | INTEGER | SERVICE products: sessions granted |
| digitalAttachmentId | STRING(36) | DIGITAL products: S3 asset |
| weightGrams | INTEGER | PHYSICAL shipping |
| imageAttachmentIds | ARRAY | |
| trackInventory | BOOLEAN | PHYSICAL true |
| active | BOOLEAN | |

Variants: **deferred** — sample catalog doesn't need size/color. If needed later: `product-variants` child collection with option JSON; order-lines already reference by id + snapshot text, so additive.

**`inventory-items`** — productId LOOKUP, locationId LOOKUP, onHand INTEGER, reserved INTEGER, reorderPoint INTEGER. Unique (productId, locationId). Validation: onHand ≥ 0, reserved ≥ 0.
**`inventory-movements`** — ledger (truth): productId, locationId, movementType PICKLIST(RECEIPT, SALE, RESERVATION, RELEASE, ADJUSTMENT), qty INTEGER (signed), orderId LOOKUP nullable, note. Flow F5 folds movements into `inventory-items` (§11).

### 6.5 Orders & billing

**`orders`** — orderNumber AUTO_NUMBER (`ORD-{00000}`), clientId, status PICKLIST(PENDING_PAYMENT, PAID, PROCESSING, SHIPPED, READY_FOR_PICKUP, DELIVERED, COMPLETED, CANCELLED, REFUNDED), fulfillmentMode PICKLIST(SHIP, PICKUP, ONLINE, MIXED), ship-to fields, shippingFee CURRENCY, subtotal/vatTotal/grandTotal CURRENCY (snapshot, stamped by flow), stripeSessionId STRING, source PICKLIST(WEB, ADMIN), placedAt DATETIME. Convenience read-time rollup `linesTotal` (ROLLUP_SUMMARY SUM over order-lines) for admin sanity vs snapshot.

**`order-lines`** — orderId MASTER_DETAIL, itemType PICKLIST(PRODUCT, SERVICE_APPOINTMENT), productId LOOKUP nullable, serviceId LOOKUP nullable, appointmentDetailId LOOKUP nullable, descriptionSnapshot STRING, qty INTEGER ≥1, unitPrice CURRENCY (VAT-inc), vatRatePct INTEGER (0/6/23), vatExemptionCode STRING nullable (`M07`), lineTotal CURRENCY.

**`invoices`** — invoiceNumber AUTO_NUMBER (`FT ATL2026/{00000}`), clientId, clientNameSnapshot, clientNifSnapshot, clientAddressSnapshot, issueDate DATE, dueDate DATE, status PICKLIST(DRAFT, ISSUED, PAID, PARTIALLY_PAID, OVERDUE, VOID, CREDITED), sourceType PICKLIST(ORDER, APPOINTMENT, MANUAL), orderId/appointmentDetailId LOOKUPs, subtotal/vatTotal/grandTotal CURRENCY, vatBreakdown JSON (`[{rate, base, vat, exemptionCode?}]`), notes.
**`invoice-lines`** — invoiceId MASTER_DETAIL, description, qty, unitPrice, vatRatePct, vatExemptionCode, lineTotal (all snapshots; invoices are immutable once ISSUED — validation rule blocks edits except status transitions).
**`credit-notes`** — creditNoteNumber AUTO_NUMBER (`NC ATL2026/{00000}`), invoiceId LOOKUP, reason, amount, vatBreakdown JSON, status(ISSUED, SETTLED).

**`payments`** — clientId, method PICKLIST(CARD, MULTIBANCO, MBWAY, SEPA_TRANSFER, CASH, INSURANCE_DIRECT), amount CURRENCY, currency, stripePaymentIntentId STRING unique nullable, orderId/invoiceId LOOKUPs, status PICKLIST(SUCCEEDED, PENDING, FAILED, REFUNDED), receivedAt DATETIME, raw JSON (webhook payload subset).

**`service-credits`** (stretch, Phase 8) — clientId, serviceId, purchasedQty, usedQty, orderLineId, expiresAt. Booking flow consumes a credit instead of payment when available.

### 6.6 Content & i18n support

**`email-templates`** — platform system collection; we add tenant override rows per key **and per locale** using key convention `<baseKey>.<locale>` (§12.4).
**`legal-pages`** (optional; else MDX in the Next repo) — slug, locale, title, body RICH_TEXT. Privacy, terms, complaints-book notice.

---

## 7. Scheduling Design

### 7.1 Availability

- One `telehealth-availability` RULE row per provider × weekday × window (timezone `Europe/Lisbon`), e.g. Dr. Ferreira: Mon–Fri 09:00–13:00, 14:00–18:00.
- Online-only windows: platform availability has no mode dimension → convention: providers who do both get whole-window availability; **mode is chosen per service** (service.visitModes) and location capacity is not modeled in v1 (each provider = one room). Documented simplification.
- Vacations/holidays: EXCEPTION rows (`closed=true`). Flow F9 fans a `practice-holidays` row out to EXCEPTION rows for all active providers.

### 7.2 Booking sequence (portal, both policies)

1. Next.js wizard: service → mode → provider (filtered by `providers.languagesSpoken` + specialty + visitModes) → slot grid from `GET /api/telehealth/slots?providerId&from&to&duration=<service.durationMinutes>` (proxied by BFF with the **portal user's JWT** once logged in).
2. `POST /api/telehealth/appointments {providerId, start, visitType: service.code, reason?}` as the portal user (platform enforces slot race-safety; creates CONFIRMED; sends confirmation email + .ics; creates record_share).
3. BFF immediately creates `appointment-details` (service PAT): snapshot price from STANDARD price list, paymentPolicy, mode, location, `paymentStatus=PENDING`, and for PREPAID `holdExpiresAt = now+30min`.

### 7.3 Prepaid vs postpaid state machines

**PREPAID** (`GP_ONLINE_30`, psychology, NIF service…):
```
book → appointment CONFIRMED + details PENDING(hold 30min)
  → Stripe Checkout (order of type SERVICE_APPOINTMENT, §10)
  → webhook PAID → details PAID → invoice ISSUED+PAID (flow F3) → receipt email
  → hold sweep (flow F2, SCHEDULED */5min): details PENDING && holdExpiresAt<now
      → cancel appointment via telehealth API → details CANCELLED_UNPAID → "slot released" email
```
Trade-off (accepted): slot is held during payment via a real CONFIRMED appointment, reusing
platform race-safety; the platform confirmation email fires at booking — template copy says
"reserved — complete payment within 30 minutes" for prepaid (per-locale templates, §12.4).

**POSTPAID** (most in-person medical):
```
book → CONFIRMED, details PENDING (no hold)
  → visit happens; ONLINE: kelta.video.session ENDED event (flow F4) marks COMPLETED;
    IN_PERSON: provider/front-desk marks COMPLETED in staff app
  → flow F6: COMPLETED && POSTPAID && no invoice → invoice ISSUED (due 15 days) → email w/ pay link
  → customer pays online (portal /account/invoices/{id} → Stripe) or at desk (front desk logs payments row)
```

### 7.4 Cancellation / reschedule

Portal cancel (BFF): allowed until `service.cancellationHours` before start; prepaid+paid →
credit-note + Stripe refund (F7). Later than cutoff: fee % per service (v1: forfeit per policy
text; automated partial refunds Phase 8). Reschedule v1 = cancel + rebook (platform has no
reschedule endpoint). No-show: staff marks NO_SHOW; postpaid may invoice no-show fee (manual v1).

### 7.5 Provider experience (kelta-ui, config only)

- Saved views on appointments for the staff app: "My Schedule — Today", "My Week (calendar)"
  (calendar saved view on `scheduledStart`), filtered `providerId = currentUser`.
- Join video: existing `/app/appointments` "Join visit" (window-gated) — nothing to build.
- Record-level Cerbos rule limits Provider profile to own appointments; Front Desk sees all.
- Chat console for reception queue; escalate chat→video already built.

---

## 8. Store & Fulfillment Design

### 8.1 Fulfillment matrix

| productType | SHIP | PICKUP | ONLINE |
|---|---|---|---|
| PHYSICAL | CTT/DPD flat €4.90, free ≥€50 (config in BFF) | Lisbon or Cascais clinic, "READY" email | — |
| DIGITAL | — | — | download via portal account (fresh presigned URL each click) |
| SERVICE | — | — | grants booking entitlement (`service-credits`) or is bought as part of appointment checkout |

Mixed carts allowed; `orders.fulfillmentMode=MIXED`, per-line handling driven by productType.

### 8.2 Checkout flow

Cart lives client-side (localStorage) → `POST /api/checkout` (BFF): validate prices/stock
server-side against Kelta, create `orders` + `order-lines` (PENDING_PAYMENT), create
RESERVATION movements for tracked physical lines, create Stripe Checkout Session
(metadata: orderId) → redirect. Webhook (§10) flips to PAID; flow F5 converts reservations
to SALE and decrements; digital lines become downloadable; pickup lines notify staff.
Unpaid orders: sweep flow F2 releases reservations + cancels after 2h.

### 8.3 Inventory

Movements ledger is the source of truth; `inventory-items.onHand/reserved` maintained by flow
F5 on movement create (single-writer via flow queue; sample scale fine). Low stock: flow F8
(onHand − reserved < reorderPoint → email store manager). Receiving stock: staff creates
RECEIPT movements (mass-edit friendly list view).

### 8.4 Staff UX (config)

Gallery saved view on products (image+name+price), kanban on orders by status
(PENDING_PAYMENT → PAID → PROCESSING → SHIPPED/READY → DELIVERED/COMPLETED), list view on
inventory-items with mass edit for reorderPoint, layouts for all §6.4/6.5 collections.

### 8.5 Product images on a public site

Attachments give 15-min presigned URLs only. BFF route `GET /api/img/{attachmentId}` →
service-PAT fetch of fresh presigned URL → 302 (or stream) with `Cache-Control: public, max-age=86400`
+ Next/image optimization in front. Good enough for sample; CDN/public-bucket adapter = later.

---

## 9. Pricing, VAT & Invoicing (Portugal specifics baked in)

### 9.1 Price list (seed, STANDARD, EUR, VAT-inclusive)

| Service | Mode | Duration | Price | Policy | VAT |
|---|---|---|---|---|---|
| GP consultation | Online | 30m | €55 | PREPAID | EXEMPT_M07 |
| GP consultation | In-person | 30m | €70 | POSTPAID | EXEMPT_M07 |
| Specialist consult (derm/gyn/ped) | Either | 30m | €95 | POSTPAID | EXEMPT_M07 |
| Psychology session | Online | 50m | €65 | PREPAID | EXEMPT_M07 |
| Physiotherapy session | In-person | 45m | €55 | POSTPAID | EXEMPT_M07 |
| Nutrition plan consult | Online | 45m | €60 | PREPAID | EXEMPT_M07 |
| Travel vaccination visit | In-person | 20m | €40 + vaccine | POSTPAID | EXEMPT_M07 |
| NIF acquisition service | Online | 30m | €120 | PREPAID | STANDARD_23 |
| Visa strategy consult (D7/D8/nomad) | Online | 60m | €150 | PREPAID | STANDARD_23 |
| SNS registration assistance | Online | 30m | €90 | PREPAID | STANDARD_23 |
| Driving-license exchange assistance | Online | 30m | €90 | PREPAID | STANDARD_23 |
| Relocation full package | Mixed | n/a | €1,500 | PREPAID | STANDARD_23 |
| Tax briefing (IFICI/"NHR 2.0") | Online | 60m | €180 | PREPAID | STANDARD_23 |

Store seed: EU adapter set €12 (23%), PT SIM starter kit €15 (23%), "Moving to Portugal" paperback €24 (**6%** books), first-aid kit €19 (23%), BP monitor €49 (23%), e-guide "Healthcare in Portugal for Expats" €9 (**6%** e-book), webinar recording "PT taxes for expats" €19 (23% electronic service), 5-session therapy pack €290 (SERVICE, EXEMPT_M07, grants 5 credits).

### 9.2 Why VAT classes matter (blank-filling)

- **Medical acts by licensed professionals: VAT-exempt** under **art. 9.º CIVA** → invoice lines must carry **exemption code `M07` ("Isento artigo 9.º do CIVA")** and zero VAT.
- Relocation/advisory services: **23%** standard (mainland).
- Printed books AND e-books: **6%**.
- General goods, supplements, electronic services (webinars): **23%**.
- B2C prices displayed **VAT-inclusive** (PT norm); invoice shows per-rate breakdown.

### 9.3 Invoice content (fields the model must carry — done in §6.5)

Supplier name/NIF/address (tenant constants), sequential number in a series (`FT ATL2026/NNNNN`
via AUTO_NUMBER), issue date, client name + **client NIF if provided**, line descriptions,
per-rate taxable base + VAT, exemption reason code per exempt line, total. Credit notes (`NC`)
reference the original invoice.

**Expat-relevant detail:** customers *want* their NIF on health invoices — health expenses are
IRS-deductible via **e-Fatura**. Portal profile nags (gently) for NIF; checkout offers
"add NIF to invoice" (fatura com contribuinte).

### 9.4 Insurance

v1: reimbursement model — customer pays, downloads invoice/receipt, claims from insurer
(Médis/Multicare/etc. picklist recorded on client). `INSURANCE_DIRECT` payment method +
price list exist structurally for a later direct-billing phase. EHIC/GHIC note on the site
for EU visitors (private practice — info only).

### 9.5 ⚠️ Compliance caveat (must appear in the demo's docs/footer)

Real Portuguese invoicing requires **AT-certified invoicing software** (certified number,
**ATCUD**, QR code, SAF-T(PT) exports). This build **emulates** correct structure (series,
sequences, exemption codes, VAT breakdown) but is **not certified** — a real practice would
integrate InvoiceXpress / Moloni / Vendus via an HTTP-callout flow at `invoice ISSUED`
(hook point reserved: flow F6 has an optional callout step, disabled in sample). Same for
**Livro de Reclamações Eletrónico** (mandatory complaints-book link in footer) and **ERS**
(health-regulator registration shown in site footer as fictional sample text).

### 9.6 Invoice rendering

v1: portal HTML invoice page with print stylesheet (per-locale). **PDF DELIVERED
(2026-07-12, atlantico-web #16):** branded A4 PDF via pdf-lib (no headless browser),
ownership-gated `GET /api/invoices/[id]/pdf` (streams from the session — invoices are PII,
never a public URL), Download-PDF button on the invoice page + list, F3/F6 emails link there.
True email *attachment* stays out (flow EMAIL_ALERT has none) — the account-download path
covers the intent.

---

## 10. Payments (Stripe)

- **Methods v1:** cards + **Multibanco** (Stripe payment method, voucher-style async) + SEPA debit optional. **MB WAY is not on Stripe** → note on site; ifthenpay/easypay integration listed as future work (locals expect MB WAY; expats live on cards — acceptable for sample).
- **Integration:** Stripe Checkout Sessions from the BFF (no Stripe code in Kelta). Async
  Multibanco → rely on webhook, orders stay PENDING_PAYMENT meanwhile.
- **Webhook:** `POST /api/stripe-webhook` (Next.js) verifies signature (stripe lib), then with
  service PAT: create `payments` row (idempotent on `stripePaymentIntentId` unique constraint —
  409 = already processed) and PATCH order → PAID / details → PAID. Downstream automation is
  Kelta flows on RECORD_CHANGE (keeps business logic demo-able in the platform).
- **Refunds:** finance triggers flow F7 (manual API_INVOCATION from a record action):
  HTTP callout Stripe refund (credential store holds the secret key) → credit-note → payments
  REFUNDED. (Alternative kept simple: refund via Stripe dashboard + flow reconciles.)
- Do **not** use platform `POST /api/webhooks/{flowId}` for Stripe — it can't verify Stripe
  signatures; the BFF must front it.

---

## 11. Automation Inventory (flows)

All triggers/handlers verified available. Inputs read `$.input.<key>` (double-wrap on manual/HTTP/MCP).

| # | Flow | Trigger | Steps (handlers) |
|---|---|---|---|
| F1 | Welcome + profile-complete nudge | RECORD_CHANGE create `clients` | Decision(locale) → EmailAlert `welcome.<locale>` |
| F2 | Unpaid hold sweep | SCHEDULED `0 */5 * * * *` | SqlQuery/QueryRecords: details PENDING & holdExpiresAt<now → HTTP callout cancel appointment (telehealth API) → UpdateRecord details → email `hold-released.<locale>`; same sweep cancels stale PENDING_PAYMENT orders (2h) + RELEASE movements |
| F3 | Prepaid receipt + invoice | RECORD_CHANGE update `appointment-details` (paymentStatus→PAID) | CreateRecord invoice+lines (snapshots, M07 logic) → UpdateRecord invoice ISSUED+PAID → email receipt `payment-receipt.<locale>` |
| F4 | Post-visit completion | NATS_TRIGGERED — but note: video ENDED arrives on `kelta.video.session.<tenant>.<sessionId>` which feeds NATS_TRIGGERED flows | UpdateRecord appointment COMPLETED (HTTP callout to telehealth API) → TriggerFlow F6 |
| F5 | Inventory fold | RECORD_CHANGE create `inventory-movements` | UpdateRecord inventory-items onHand/reserved; Decision by movementType |
| F6 | Postpaid invoicing | RECORD_CHANGE update appointments/details (status→COMPLETED, policy POSTPAID, no invoice) | CreateRecord invoice ISSUED (due+15d) → email `invoice-issued.<locale>` w/ pay link; optional (disabled) HTTP-callout step to certified-invoicing API |
| F7 | Refund + credit note | API_INVOCATION (finance action) | HTTP callout Stripe refund → CreateRecord credit-note → UpdateRecord payments REFUNDED → email |
| F8 | Low-stock alert | RECORD_CHANGE update `inventory-items` | Decision(onHand−reserved<reorderPoint) → email store manager |
| F9 | Holiday fan-out | RECORD_CHANGE create `practice-holidays` | QueryRecords active providers → loop CreateRecord EXCEPTION rows in `telehealth-availability` |
| F10 | Order fulfillment emails | RECORD_CHANGE update `orders` (PAID / SHIPPED / READY_FOR_PICKUP) | Decision(status+locale) → EmailAlert per state |
| F11 | Digital delivery | RECORD_CHANGE update `orders`→PAID with DIGITAL lines | email `download-ready.<locale>` linking portal account (fresh presigned mint on click) |
| F12 | Daily ops digest (nice-to-have) | SCHEDULED 07:00 Lisbon | SqlQuery today's appointments per provider → email |

Platform confirmation/reminder/cancellation emails (with .ics + visit link) stay platform-owned — we only override template content per locale.

---

## 12. i18n Strategy (four layers)

Locales: **en** (fallback), **pt-PT**, **fr**, **de**, **es**, **uk**.

| Layer | Mechanism | Notes |
|---|---|---|
| 1. Next.js UI strings | `next-intl` message catalogs in the portal repo, locale-prefixed routes `/{locale}/…` | Full 6 locales; hreflang + localized metadata for SEO |
| 2. Catalog/record data | `*I18n` JSON fields on services/products/categories/locations/providers.bio; BFF resolver `t(record.nameI18n, locale) ?? record.name` | Platform has no record-data i18n; this convention is portable |
| 3. Transactional email | Tenant override rows in `email-templates` keyed `<baseKey>.<locale>`; flows Decision on `clients.preferredLanguage`; platform telehealth templates (`appointment_confirmed` etc.) overridden per locale the same way — **verify during Phase 2 how the platform picks templates; if it takes only the base key, confirmation emails fall back to bilingual EN/PT static copy (base template carries both languages), and per-locale goes to F-flows only** | Bilingual base template = safe fallback pattern |
| 4. Staff app labels | `ui-translations` overlay (en+pt only) | Low priority |

**Translation production:** seed en+pt-PT by hand (me, in-session); generate fr/de/es/uk drafts
with Claude in implementation sessions (catalog is small: ~40 services/products × 3 fields);
store straight into the `*I18n` JSON. A `translate-catalog` flow (HTTP callout → Anthropic API)
is a Phase 8 stretch — the student-incentives "AI translation drafter" was tenant-authored, not
a platform feature, so nothing to reuse directly.

Rules of the road: dates/times rendered per locale but always with explicit timezone label for
online visits; prices always `€X,XX` (pt) / `€X.XX` (en) via `Intl.NumberFormat`; pt-PT (not
pt-BR) spelling — Brazilians (largest immigrant group) read pt-PT fine.

---

## 13. Portugal / Expat Blank-Filling (the "fill in any blanks" section)

1. **NIF everywhere it matters** — optional 9-digit field with check-digit validation on client + invoice snapshot; e-Fatura/IRS health-deduction is WHY customers care (§9.3).
2. **Specialty set tuned to expat demand** — GP, mental health (top expat need), pediatrics, gynecology, dermatology, physiotherapy, nutrition, travel medicine; relocation: NIF, AIMA (ex-SEF) prep, SNS registration, D7/D8/nomad visas, IMT license exchange, banking, housing, schools, pets, IFICI tax briefing.
3. **Insurance picklist** for PT-market insurers (Médis, Multicare, AdvanceCare, Allianz Care, Cigna Global, SafetyWing) + reimbursement-receipt workflow (§9.4).
4. **Regulatory surface on the site** (sample text, clearly fictional): ERS registration line, medical director (diretor clínico), provider cédula numbers, **Livro de Reclamações Eletrónico** footer link, AT-certified-invoicing caveat (§9.5).
5. **Store legality** — catalog excludes medicines (pharmacy/MNSRM licensing); supplements, devices, books, comfort goods only.
6. **GDPR + health data (Art. 9)** — explicit health-data-processing consent (timestamp + version) at registration; marketing consent separate; privacy notice per locale; platform audit + telehealth archival/retention (7y default, legal-hold, purge dry-run) is the retention story; right-to-erasure vs clinical-retention tension documented (erasure request ≠ deleting encounter archives before retentionUntil).
7. **Telemedicine** — allowed in PT; recording consent already a platform flow; prescriptions (PEM/SNS) explicitly OUT of scope — site copy must not promise prescriptions.
8. **Cancellation policy norms** — free >24h, fee ≤24h, no-show fee; per-service fields (§6.2).
9. **PT national holidays 2026 seed** — Jan 1, Apr 3, Apr 5, Apr 25, May 1, Jun 4, Jun 10, Aug 15, Oct 5, Nov 1, Dec 1, Dec 8, Dec 25 (+ municipal: Lisbon Jun 13; Cascais per municipality — config row, verify) → `practice-holidays` → F9 fan-out. August capacity dip is culturally real; demo seeds a vacation EXCEPTION week for one provider.
10. **Payment habits** — MB WAY ubiquitous locally (future ifthenpay), Multibanco references familiar, cards fine for expats; SEPA for packages.
11. **Formats** — postal `NNNN-NNN`, phone `+351`, address lines PT-style; Europe/Lisbon DST honesty in all scheduling UI.

---

## 14. Next.js Portal (new repo)

### 14.1 Repo & stack

`~/GitHub/atlantico-web` (own repo — keeps platform repo clean). Next.js 15 (App Router,
standalone output), TypeScript strict, Tailwind, `next-intl`, `stripe`, `iron-session`
(httpOnly cookie sessions). **Kelta client:** thin fetch-based typed client in `lib/kelta/`
(~200 lines) + types generated by `kelta-generate-types` against the tenant — `@kelta/sdk` is
workspace-only (not published) and a cross-repo `file:` dep would break container builds.
Revisit when the SDK publishes.

### 14.2 Sitemap (all under `/{locale}/`)

```
/                       landing (lines of business, languages, trust/regulatory footer)
/services               grouped by category → /services/[slug] (+ Book CTA)
/providers              directory w/ language & specialty filters → /providers/[slug]
/book                   wizard: service → mode → provider → slot → (login) → confirm → pay
/store                  categories → /store/c/[slug] → /store/p/[slug] (gallery, price w/ VAT)
/cart  /checkout        address / pickup / NIF-on-invoice option → Stripe
/guides                 static MDX expat guides (SEO)
/account                profile (NIF, language, insurance, consents)
/account/appointments   upcoming/past · cancel · JOIN (visit link) 
/account/orders         status, tracking, downloads (digital)
/account/invoices       list · HTML invoice (print CSS) · Pay now (postpaid)
/auth/*                 magic-link request + callback (needs platform slice §4.1)
/legal/*                privacy, terms, complaints-book, cookies
```

### 14.3 BFF API routes

`/api/auth/{request,callback,logout}` · `/api/register` (portal-invite + clients row + consents)
· `/api/slots` (proxy, portal JWT) · `/api/book` (§7.2) · `/api/cancel-appointment` ·
`/api/checkout` · `/api/stripe-webhook` · `/api/img/[id]` (§8.5) · `/api/download/[orderLineId]`
(ownership check → fresh presigned URL) · `/api/account/*` (profile, orders, invoices — service
PAT + ownership filter) · `/api/revalidate` (optional Svix target).

### 14.4 Auth/session model

Session cookie ↔ `{portalUserId, clientId, locale}`. Login: email → BFF calls headless
request endpoint → user clicks mail link → `/auth/callback?token` → BFF verifies → JWT held
server-side in session (used for telehealth+chat calls); registration = portal-invite path.
Until slice §4.1 lands, `/auth` shows "check your email" flow via kelta-ui as interim (dev only).

### 14.5 Video join

v1: the platform's visit-link email + "Join" button linking the same HMAC URL → kelta-ui
VisitPage (waiting room, consent, LiveKit) — zero code. v2 (stretch): native join page using
`livekit-client` + portal-JWT `POST /api/telehealth/appointments/{id}/video-token`.

### 14.6 Caching vs governor limits

Catalog pages ISR (revalidate 300s) + `page[size]=200` batch reads keep API/day trivial;
optional Svix outbound webhook on products/services record-change → `/api/revalidate` for
instant publishing. Per-tenant API/day must be ≥100k anyway (Phase 0).

### 14.7 Deploy

Dockerfile (node:22-alpine, standalone) → `harbor.rzware.com/atlantico/web` → ArgoCD app in
`~/GitHub/homelab-argo` → ingress `atlantico.rzware.com` + cert-manager (DNS-01 public-resolver
pattern, same as LiveKit cert). Env: PATs + Stripe keys via SealedSecret (diff live-vs-git
before sealing — known hazard). Health endpoint `/api/healthz`.

---

## 15. Seed Data Plan

- 2 clinics + Online location; 9 providers (mix: 5 medical, 3 relocation, 1 dual) with
  languagesSpoken combos covering all 6 locales; availability rules Mon–Sat; 1 vacation week.
- ~14 services (§9.1) + provider-services matrix; STANDARD price list.
- ~15 products across categories (§9.1 store list) with images (generated/stock), inventory
  RECEIPT movements (Lisbon 20–50 units, Cascais 10–20).
- 6 sample clients (varied locales/nationalities, some with NIF), 2 weeks of appointment
  history incl. COMPLETED postpaid → invoices in every status, 5 orders across fulfillment
  modes, PT holidays 2026.
- Seeding = idempotent TS script in the portal repo (`scripts/seed.ts`, service PAT) so demo
  resets are one command; MCP used interactively during build.

---

## 16. Delivery Phases

Each phase = 1 combined PR per repo touched (CI too slow for per-item PRs), `/verify` green,
docs updated in-PR. Estimates are focused working days.

| Phase | Scope | Repo(s) | Est | Exit criteria |
|---|---|---|---|---|
| **0** | Prereqs §5.3: tenant limits, PATs + `kelta-th-*` MCP servers, SMTP, Stripe test, domain | tenant, MCP config | 0.5d | MCP `list_collections` shows telehealth tenant; test email delivered |
| **1** | Platform slice: headless portal auth §4.1 (spec + impl + security review) | emf | 1.5d | Next.js-style curl login round-trip; kelta-ui portal unaffected |
| **2** | Clinical core: picklists, clients/providers/locations/services/price-lists (+entries, provider-services), profiles/permission sets, FLS/masking, layouts, provider saved views incl. calendar; provider users + availability seed; per-locale overrides of telehealth email templates (verify base-key behavior §12.3) | tenant | 1.5d | Admin books test appointment; provider sees only own schedule; confirmation email branded |
| **3** | Scheduling companion: appointment-details, practice-holidays, flows F2/F4/F6-skeleton/F9; postpaid completion path end-to-end (video ENDED → COMPLETED → invoice draft) | tenant | 1d | Online test visit auto-completes and drafts invoice |
| **4** | Store: categories/products/inventory-±movements, F5/F8, staff gallery/kanban views, product images | tenant | 1d | Stock movements fold correctly; low-stock mail fires |
| **5** | Billing: orders/order-lines/invoices/invoice-lines/credit-notes/payments, AUTO_NUMBER series, VAT/M07 logic in F3/F6, unique constraint on stripePaymentIntentId | tenant | 1d | Invoice numbers sequential; VAT breakdown correct incl. exempt + 6% lines |
| **6** | Portal foundation: repo, i18n routing, catalog (ISR), providers, services, guides, legal; img proxy | atlantico-web | 2d | Public site browsable in 6 locales, no Kelta creds in browser |
| **7** | Portal auth + booking + account: register/login (slice from P1), booking wizard, prepaid Stripe path (checkout, webhook, F3), cancel, account pages, invoice HTML+pay, postpaid pay | atlantico-web (+tenant flows F1/F7/F10/F11) | 3d | E2E: prepaid online booking → pay → visit link joins video; postpaid → complete → invoice → pay |
| **8** | Store checkout + fulfillment: cart/checkout/Stripe (incl. Multibanco), reservations, digital downloads, pickup/ship mails; deploy to K8s | atlantico-web, homelab-argo | 2d | E2E: ship + pickup + digital orders; site live at atlantico.rzware.com |
| **9** | Content & polish: translation fill (fr/de/es/uk), seed script, Playwright e2e pack, demo script, docs | atlantico-web, tenant | 1.5d | Demo runbook executes clean start-to-finish |
| ✅ | Stretch: service-credits | tenant, atlantico-web | done | Session packs grant + redeem — see §20 |
| ✅ | Stretch: invoice PDFs | atlantico-web | done | pdf-lib, ownership-gated download — see §9.6 |
| ✅ | Stretch: intake forms | tenant, atlantico-web | done | Per-service pre-visit questionnaire — see §21 |
| ✅ | Stretch: native video-visit embed | atlantico-web | done | In-portal LiveKit join — see §22 |
| ✅ | Stretch: Svix instant revalidation | atlantico-web, svix | done | Record change → webhook → ISR bust — see §23 |
| ✅ | Stretch: provider self-service availability | kelta-worker, kelta-ui | done | Providers edit own schedule — see §24 |
| — | Stretch backlog | | | MB WAY (ifthenpay), certified-invoicing callout, insurer direct billing |

**Total ≈ 14 focused days.** Dependencies: P1 blocks P7 auth; P3 needs P2; P5 blocks P7/P8 payment paths; P6 can start parallel to P2–P5.

---

## 17. Testing

- **Tenant metadata:** validation-rule unit checks via API (bad NIF, negative qty, overlapping unique constraints); flow tests by firing triggers with fixture records.
- **Platform slice (P1):** normal emf standards — unit + integration (Testcontainers) + security review, no auto-merge.
- **Portal:** Vitest for BFF logic (slot proxy, ownership filters, VAT math mirrors); Playwright e2e — the three golden paths (prepaid-online-video, postpaid-inperson-invoice, store-3-fulfillments) + locale smoke (pt-PT + uk render, hreflang) + auth (magic link via Mailpit).
- **Manual gates:** video from restrictive network (TURN-TLS), Stripe Multibanco async flow, governor headroom check after a crawl of the site.

## 18. Risks & open questions

| Risk / question | Handling |
|---|---|
| Telehealth email template selection may not support `<key>.<locale>` | Verified in P2; fallback = bilingual base templates (§12.3) |
| Confirmation email at prepaid booking (before payment) could confuse | Template copy states payment deadline; acceptable for sample |
| Availability has no per-mode windows | Documented simplification (§7.1) |
| Stripe Multibanco availability on the account | Check in P0; cards-only is an acceptable floor |
| Inventory race on concurrent checkouts | Movements ledger + flow fold; sample scale OK; note for real use |
| **Name/domain choice** (Atlântico/atlantico.rzware.com are placeholders) | Craig confirms before P6 SEO work |
| Locale set (drop/add uk? add nl/it?) | Confirm before P9 translation fill |
| Where slice spec lives | `specs/_archive/telehealth/8-portal-auth-headless.md`, written in P1 |

---

*Doc is untracked at creation (never commit to main); commit via the P1/P2 feature branches. When committed, add this spec to the CLAUDE.md specs index per Keeping Docs Current.*

---

## 19. Build status (2026-07-12 — COMPLETE)

All phases 0–9 delivered; the sample is live at https://atlantico.rzware.com
(repo `cklinker/atlantico-web`, deploy in `homelab-argo/atlantico-web/`).

| Phase | Landed as |
|---|---|
| 0–1 | Tenant + MCP (`kelta-si-*`), headless portal auth = emf #1245 |
| 2–5 | Clinical core, scheduling companion, store, billing — all tenant metadata; flows F1/F3/F4/F5+F8/F6/F10/F11 live (F2/F9 replaced by BFF lazy sweeps — flow engine has no relative-time trigger; F7/F12 backlog) |
| 6–7 | atlantico-web PRs #1–#2: public i18n site + auth/booking/payments/account |
| 8 | PRs #3–#7 + homelab-argo #155–158: cart/checkout (Stripe cards+Multibanco, simulate fallback), reservations→SALE fold, digital downloads, ORDER invoices, deploy |
| 9 | PR #8+: fr/de/es/uk record-data translations (39 records), `npm run seed` demo reset, Playwright pack, `docs/DEMO.md` runbook |

**Platform yield: 18 bugs found and fixed while building** (emf #1241–#1257 +
homelab-argo #159) — field defaults, availability columns, create-user dialog,
gateway atomic route, field-delete cascade, native script Date, AUTO_NUMBER
kebab, JetStream drift self-heal, telehealth record advice, permission Cerbos
sync, resolver negative cache, DATE patch validation, route prefix shadowing
(security), atomic-ops native reflection, auth→worker internal email token.
Deviations from this spec are recorded in the memory file
`project_expat_clinic_tenant.md`; stretch backlog unchanged (§16).

---

## 20. Service credits (stretch — DELIVERED 2026-07-12)

SERVICE products (session packs) now grant redeemable booking credits instead
of the placeholder "team activates manually" copy.

- **Collection** `service-credits`: clientId, serviceId, purchasedQty, usedQty,
  status (ACTIVE/USED/EXPIRED), expiresAt (+1y), sourceOrderId. BFF is the
  single writer; svc-portal-write CRUD, svc-portal-read read.
- **Grant** (`completeOrderPayment`): one row per SERVICE order-line,
  purchasedQty = product.creditQty x line qty. Idempotent on sourceOrderId.
- **Redeem** (`/api/book`): before the payment step, an available credit
  (ACTIVE, remaining > 0, not expired) is consumed and the appointment
  companion set to **CREDIT_USED** — a payment status F3/F6 both skip, so the
  covered session is never re-invoiced (the pack purchase already produced its
  ORDER invoice). Returns `next: "credited"`.
- **UI**: booking wizard shows "covered by your package (N left)"; account
  lists a Session-credits balance with expiry; SERVICE product page shows
  "buy once, book N sessions".
- Verified E2E live: buy PACK-THERAPY-5 -> 5 credits -> book PSYCH_50 ->
  covered (0 charged, no session invoice) -> 4 left.

---

## 21. Pre-visit intake forms (stretch — DELIVERED 2026-07-12)

Services flagged `requiresIntake` (psychology, relocation package) collect a
dynamic questionnaire before the visit.

- **Collections**: `intake-forms` (serviceId unique lookup, `questions` JSON —
  each `{key,label,type: text|longtext|boolean|choice, options?, required}`,
  titleI18n, active) and `intake-responses` (clientId/serviceId lookups,
  appointmentDetailId, answers JSON, submittedAt). BFF single writer.
- **BFF** (`intake.ts`): `getFormForService`, `intakeContext` (ownership via
  appointment-details), `submitIntake` with `missingRequired` validation
  (422 + missing keys), upsert one response per appointment,
  `submittedDetailIds` for gating. Routes `GET/POST /api/intake`.
- **UI**: dynamic form page `/account/appointments/[detailsId]/intake`
  (renders each question type); appointments list shows "Complete your intake
  form →" or "Intake complete ✓"; booking wizard success screen reminds when
  the booked service needs intake. Seeded psychology + relocation forms; i18n ×6.
- Verified E2E: book PSYCH_50 → GET form (6 questions) → submit-invalid 422
  (missing required) → submit-valid 200 → response persisted → gating flips to
  complete; edit re-opens prefilled.

Deployed `main-f84bd42`.

---

## 22. Native video-visit embed (stretch — DELIVERED 2026-07-12)

Online visits now join a LiveKit room **inside the portal** (§14.5 v2) instead
of the platform's generic visit page.

- **Join window** (`visit.ts`, unit-tested): the waiting room opens 15 min before
  start and the room stays joinable 30 min after end (early/open/ended). This is a
  deliberate subset of the platform's own gate (15 min early / 60 min late) so the
  portal's Join CTA never fires outside the platform window (a click always mints).
- **Token** (`mintVideoToken`, BFF, as the portal user): the platform binds the
  LiveKit JWT to `portal_user_id` and lazily creates the `video_session`.
  `POST /api/visit/[detailsId]/token` — session + ownership gated, ONLINE-only.
- **UI**: `VisitRoom` = pre-join card (camera/mic start on click) -> `LiveKitRoom`
  + prebuilt `VideoConference` (`@livekit/components-react`); leaving or a dropped
  connection returns to appointments. The appointments list shows a live pulsing
  "Join video visit" CTA only inside the window. 6 locales, 6 window unit tests.
- **Platform contract**: `POST /api/telehealth/appointments/{id}/video-token` ->
  `{url, token, roomName, sessionId, expiresAt}` (worker `VideoSessionController`;
  requires status CONFIRMED + within the visit window, else 409).
- Verified E2E live: magic-link login as the canary patient -> in-window mint
  returns 200 + `wss://livekit.kelta.io` + a JWT granting roomJoin/publish/
  subscribe scoped to the appointment room (`sub` = the patient's portalUserId);
  the pre-join card renders. An out-of-window mint correctly 409s
  ("Outside the visit window").

Deployed `main-2cf226c` (homelab-argo #167). Deps: `livekit-client`,
`@livekit/components-react`/`-styles` (pure JS — alpine-standalone safe).

---

## 23. Svix instant catalog revalidation (stretch — DELIVERED 2026-07-12)

Catalog edits (a price, a service, a provider) show on the public site in
**~2 s** instead of waiting out the 5-minute ISR window.

- **No platform change** — the worker already bridges `kelta.record.changed.>`
  → Svix (`RecordWebhookPublisher`, event `record.created|updated`, payload
  `{collectionName, recordId, data, …}`, channel = collection name).
- **Consumer** (`atlantico-web`): `client.ts` tags every catalog read with its
  collection name (`next.tags`); `POST /api/revalidate` verifies the Svix
  signature (`svix` pkg) and calls `revalidateTag` for the changed collection.
  `revalidate.ts` is a pure `collection → tags` map (catalog-guarded) with
  fan-out — a `price-list-entries` change also busts `services` + `products`.
  4 unit tests. Signing secret ships as a SealedSecret (`kubeseal --raw`).
- **Tenant Svix endpoint** → the atlantico-web service, subscribed to catalog
  channels (services, products, price-list-entries, providers,
  product-categories, provider-services).
- **Infra gotcha (important):** Svix's default **SSRF guard blocks private
  targets**, and in this homelab *every* target is private (public LB
  192.168.x via hairpin, ClusterIP 10.x) → all delivery failed with
  `response_code=0`. Fix: `SVIX_WHITELIST_SUBNETS: '["10.0.0.0/8","192.168.0.0/16"]'`
  (JSON array — Svix wants a sequence) on the shared svix config, and point the
  endpoint at the **in-cluster** service URL
  (`http://atlantico-web.atlantico-web.svc/api/revalidate`). This relaxes a
  security control fleet-wide, so it ships as a **non-auto-merged** PR
  (homelab-argo #169).
- Verified live: a price edit reflected on the public page in **~2 s**, Svix
  delivery attempts returning **200**; forged-signature POST → **401**;
  valid signed POST → **200** `{revalidated:[…]}`.

Deployed `main-7a328c6` (atlantico-web #23). Dep: `svix`.

---

## 24. Provider self-service availability (stretch — DELIVERED 2026-07-12)

Providers manage their own weekly schedule + date exceptions in the staff app,
instead of the practice manager editing raw `telehealth-availability` rows via
admin JSON:API (the §16 "later platform idea"). A **platform** feature (kelta
worker + kelta-ui) — every telehealth tenant gets it, not just this sample.

- **Scoped endpoint** (`kelta-worker`, `TelehealthController` +
  `AvailabilityService`): `GET/PUT /api/telehealth/availability/me`. The
  provider id is the authenticated user (`actor.userId()`), never the body — a
  provider can only edit their own schedule; portal users are 403'd. `PUT` is a
  transactional validate-all-then-replace (weekday 0–6, `HH:mm` times with
  end>start, ISO exception dates, valid timezone). `SlotService` reads
  availability live per request, so no cache/NATS broadcast is needed. Map
  in/out — no new native-reflection types. 7 unit tests (`AvailabilityServiceTest`).
- **Editor page** (`kelta-ui`, `ProviderAvailabilityPage`, route
  `/app/provider-availability`): a weekly grid (windows per weekday, add/remove)
  + a date-exceptions list (whole-day closures or one-off windows), localized
  weekday names via `Intl` (no hardcoded day translations), save → toast +
  refetch. Reached from a **"My availability"** link on the staff Appointments
  page. i18n en + pt-PT; 3 unit tests. (kelta-ui has no typecheck in its prod
  image — `tsc -b && vite build` was run locally; the changed files are type-clean.)
- `provider-availability.spec.ts` (skip-gated at the time; un-gated in the e2e-honesty
  batch and now runs in CI's E2E job).

Deployed via the platform image pipeline (kelta-worker native + kelta-ui).

