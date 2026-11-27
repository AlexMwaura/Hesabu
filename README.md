# Hesabu

A point-of-sale system for Kenyan shops, built around two things existing tills do badly: **eTIMS receipts that keep printing through an internet outage**, and **M-Pesa payments that are verified, not just typed in**.

This repository holds the Phase 1 back end. The Android till and the owner web dashboard are not built yet (see [TODO.md](TODO.md)).

## How it fits together

```
 Android till ──LAN──▶ Hub (in the shop) ──LAN──▶ VSCU (KRA's JAR, in the shop)
                          │
                          └──internet──▶ Cloud server ◀── Safaricom Daraja callbacks
                                              ▲
                                     Owner dashboard
```

- **Hub**: a small always-on box per branch. It holds the branch's catalogue, stock, shifts and deni book, and asks the VSCU to sign every receipt. Because the VSCU runs in the shop too, selling continues when the internet is down, for up to 24 hours (KRA's VSCU spec 2.2.4).
- **Cloud server**: the catalogue master. It's where Safaricom delivers M-Pesa confirmations, where hubs sync what they sold, and what the owner's reports are built from.

Rules from KRA's specs that the code enforces:
- **No receipt without a VSCU signature** (TIS spec 6.7, 10). If the VSCU can't be reached, the sale is refused.
- **No sale of goods beyond stock on hand** (TIS spec 6.29).
- **One ascending invoice series** (TIS spec 6.3). It is restored from the VSCU at start-up and resynced after any doubt.
- **Every receipt prints the SCU block and QR content** (TIS spec 6.23). A reprint is labelled CS or CC.

## Modules

| Module | What it is |
|---|---|
| `core` | Plain Kotlin rules shared by everything (and later the Android till). Covers: VAT worked out of shelf prices, payments, the eTIMS invoice and credit-note builder, the receipt SCU block, M-Pesa claim rules, KRA stock codes, deni and shift maths, and the hub–cloud messages. |
| `hub` | Spring Boot on SQLite. Till API covering catalogue, stock receipts and adjustments, sales and credit notes, shifts, deni, M-Pesa checks and STK, and a status endpoint. Syncs to the cloud through a transactional outbox. |
| `server` | Spring Boot on Postgres. Admin onboarding, owner catalogue and daily summary, hub sync API, Daraja client and callbacks, and M-Pesa matching. |
| `fake-vscu` | Stands in for KRA's VSCU JAR on the same port (8088) and paths. Models the 24-hour offline limit. |
| `fake-daraja` | Stands in for Safaricom's Daraja (port 8089): OAuth, STK Push with a callback, C2B register and simulate. |
| `spikes` | The Phase 0 spikes (Maven), kept for reference. |

## Tests

```bash
./gradlew test
```

22 tests, all passing as of 27 Nov 2026. No Docker needed: the server tests start an embedded Postgres.

- `core`: tax, invoice and credit-note building, receipt block, payment rules, M-Pesa matching, stock, deni and shift maths.
- `hub` (against the fake VSCU):
  - signing, reprints and retrying the same sale;
  - refusing sales beyond stock;
  - the 24-hour cutoff, and retrying once KRA is back;
  - credit notes and cash variance;
  - deni limits and repayments;
  - refusing a reused M-Pesa code while offline.
- `server` `EndToEndTest` runs server, hub, fake VSCU and fake Daraja in one JVM:
  - onboarding and catalogue pull;
  - a typed code verified through C2B;
  - a short payment caught before the sale;
  - STK paid and STK cancelled;
  - a confirmation arriving after the sale;
  - a retried callback counted once;
  - the owner's daily summary, including low stock and money no sale claimed.

## Running it locally

Each command runs in its own terminal.

```bash
./gradlew :fake-vscu:bootRun
```
```bash
./gradlew :fake-daraja:bootRun
```
```bash
docker compose up -d postgres
```
```bash
DATABASE_URL=jdbc:postgresql://localhost:5433/hesabu ./gradlew :server:bootRun
```

Onboard a shop (save the keys printed; they are shown once):

```bash
curl -s -X POST localhost:8080/api/admin/v1/businesses -H 'X-Admin-Token: dev-admin-token' -H 'Content-Type: application/json' -d '{"name":"Duka la Mama"}'
```
```bash
curl -s -X POST localhost:8080/api/admin/v1/businesses/<business id>/branches -H 'X-Admin-Token: dev-admin-token' -H 'Content-Type: application/json' -d '{"name":"Kawangware","code":"KWG","mpesaShortCode":"600111","stkShortCode":"174379","stkPasskey":"bfb279f9aa9bdbcf158e97dd71a467cd2e0c893059b10f78e6b72ada1ed2c919"}'
```
```bash
curl -s -X POST localhost:8080/api/admin/v1/branches/<branch id>/c2b/register -H 'X-Admin-Token: dev-admin-token'
```
```bash
curl -s -X PUT localhost:8080/api/owner/v1/products/OIL -H 'Authorization: Bearer <owner key>' -H 'Content-Type: application/json' -d '{"itemClassCode":"5020230601","name":"Cooking oil 1L","priceCents":35000,"taxType":"B","reorderLevelMilli":5000}'
```

Start the hub with the branch's hub key, then use the till API:

```bash
HESABU_HUB_KEY=<hub key> HUB_BRANCH_CODE=KWG ./gradlew :hub:bootRun
```
```bash
curl -s -X POST localhost:8070/api/till/v1/sync -H 'Authorization: Bearer dev-till-token'
```
```bash
curl -s -X POST localhost:8070/api/till/v1/stock/receipts -H 'Authorization: Bearer dev-till-token' -H 'Content-Type: application/json' -d '{"lines":[{"productCode":"OIL","qty":20}]}'
```
```bash
curl -s -X POST localhost:8070/api/till/v1/shifts -H 'Authorization: Bearer dev-till-token' -H 'X-Till-Id: T1' -H 'Content-Type: application/json' -d '{"cashierId":"C01","cashierName":"Wanjiku","floatCents":100000}'
```
```bash
curl -s -X POST localhost:8070/api/till/v1/sales -H 'Authorization: Bearer dev-till-token' -H 'X-Till-Id: T1' -H 'Content-Type: application/json' -d '{"clientRef":"s-1","lines":[{"productCode":"OIL","qty":1}],"tenders":[{"method":"CASH","amountCents":35000}]}'
```

Fake controls:
- **Fake VSCU:**
  - `POST localhost:8088/_fake/kra?up=false` cuts the VSCU's link to KRA.
  - `POST localhost:8088/_fake/clock/advance?minutes=1441` moves its clock past 24 hours.
- **Fake Daraja:**
  - `POST localhost:8089/_fake/c2b/pay` with `{"shortCode":"600111","amount":350}` makes a customer pay and returns the M-Pesa code.
  - STK prompts to a phone ending in `0000` are cancelled.

## Conventions

- Money is in cents (`Long`) everywhere; quantities are in thousandths (1.5 kg = 1500).
- Every write the till makes carries its own `clientRef`, so retries never duplicate.
- `TODO(kra-sandbox)`, `TODO(sandbox)`, `TODO(kra-confirm)` and similar tags in the code mark assumptions to check. They are listed in [TODO.md](TODO.md).
