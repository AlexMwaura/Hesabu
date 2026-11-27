# Hesabu TODO

## Sandbox registrations (Alex, later). Faked until then
- [ ] **Daraja sandbox app.** Create the app and get the consumer key and secret. Then set `DARAJA_BASE_URL=https://sandbox.safaricom.co.ke`, `DARAJA_CONSUMER_KEY` and `DARAJA_CONSUMER_SECRET` on the server, and set `PUBLIC_BASE_URL` to a public HTTPS tunnel. Until then the server talks to `fake-daraja` on port 8089.
- [ ] **KRA eTIMS sandbox and VSCU test JAR.** Register, apply for VSCU, and run the JAR on port 8088. Then stop `fake-vscu` and set `VSCU_TIN`, `VSCU_BHF_ID` and `VSCU_DEVICE_SERIAL` on the hub. Nothing else changes; the fake uses the same port and paths.
- [ ] After both: replay the end-to-end flow by hand against the real services and resolve every `TODO(sandbox)` and `TODO(kra-sandbox)` in the code:
  - VSCU field names (`itemCd`/`itemClsCd`, `taxAmt`), number formats, and credit-note amounts and `orgInvcNo`;
  - the VSCU's error code after 24 hours offline, and when the 24 hours start;
  - Daraja v2 C2B payload (masked MSISDN), callback timing, and Buy Goods vs Paybill STK `TransactionType`;
  - item classification codes validated against KRA's list.

## Questions for KRA or an integrator (`TODO(kra-confirm)`)
- [ ] How to code split cash + M-Pesa receipts (`pmtTyCd`). Currently "07".
- [ ] Whether `invcNo` is one series across sales and credit notes. Currently one series.
- [ ] Whether a lost VSCU answer can be recovered by querying the VSCU. Currently the sale is marked `NEEDS_ATTENTION` for support.

## Phase 1 build
- [x] `core`: shared rules and hub–cloud messages.
- [x] `fake-vscu` and `fake-daraja`.
- [x] `hub`: till API, signing with safe retries, credit notes, stock ledger, shifts, deni, M-Pesa checks and STK, outbox sync.
- [x] `server`: onboarding, catalogue, hub sync, Daraja callbacks, M-Pesa matching, owner daily summary.
- [x] End-to-end test of all four apps (`server` `EndToEndTest`).
- [ ] **Android till** (Kotlin + Compose + Room): basket, barcode, receipt printing (58/80 mm), payment screens using `/mpesa/check` and `/mpesa/stk`, shift open/close, deni. Needs the Android SDK installed on this machine.
- [ ] **Owner web dashboard** (Next.js) on `/api/owner/v1`: daily summary, catalogue editor, deni list.
- [ ] **Hub packaging**: install the hub and the VSCU JAR on a mini PC as services, with auto-start, backups of the SQLite file, and remote updates.

## Hardening before pilot shops
- [ ] `TODO(pairing)`: per-till credentials instead of one shared till token per hub.
- [ ] `TODO(auth)`: owner sign-in by phone OTP instead of owner keys; real operator sign-in instead of the admin token.
- [ ] `TODO(secrets)`: encrypt per-branch STK passkeys at rest.
- [ ] Restrict `/hooks` to Safaricom's callback IP ranges.
- [ ] Poison events: an event the server can't project is retried forever. Add a dead-letter table and alerting.
- [ ] KRA stock reporting through the VSCU (`/stock/saveStockItems`, `/stockMaster/saveStockMaster`), using the stock movement codes the hub already records (KRA notice 07/09/2026).
- [ ] Hub backups and restore; telemetry from every hub (sync lag, VSCU state, unsent receipts).
- [ ] Register with the ODPC under the Data Protection Act before storing customer phone numbers in production.
