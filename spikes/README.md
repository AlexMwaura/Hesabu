# Hesabu Phase 0 spikes

Throwaway code to de-risk two assumptions from the Hesabu Phase 0 plan before any product is built:

- **H6 / verified M-Pesa** — can a typed M-Pesa code be matched to Safaricom's confirmation, and can STK Push results be tied to the sale that sent them?
- **H5 / eTIMS during an outage** — can a shop keep issuing fiscal receipts when its internet to KRA is down, and for how long?

Kotlin 1.9, Spring Boot 3.3, Java 17, Maven. Everything is in memory. Not production code.

```bash
mvn test
```

21 tests, all passing as of 27 Nov 2026.

## What the spikes found

### eTIMS: the design in the market study was wrong, and this is the corrected one

Reading KRA's *VSCU Requirements & Communication Protocols v2.0* and *Technical Specification of TIS for OSCU & VSCU v2.0* (both April 2023) changed the offline design:

| Rule | Source | Consequence for Hesabu |
|---|---|---|
| The VSCU is a KRA-issued **JAR the business runs on its own web server**; the till calls its local REST API (`/trnsSales/saveSales` etc.). | VSCU spec 1.2, 2.1, 2.2 | Signing happens where the VSCU runs, not at KRA. |
| "If there is no internet connection for 24 hours after VSCU issues a receipt signature and an internal data, VSCU stops issuing a receipt number." | VSCU spec 2.2 item 4 | A VSCU can sign offline for up to **24 hours**, then selling stops. |
| The till may issue receipts "only if connected to OSCU/VSCU unit which is functioning", and "it shall be impossible to issue receipt if the TIS did not receive any response from the OSCU/VSCU." | TIS spec 6.7, 10 | **"Print now, sign later" is not allowed.** If the till can't reach the VSCU, the sale can't be receipted. |
| The till must "not issue a receipt of goods when the corresponding stock is less than the requested quantity." | TIS spec 6.29 | **Negative stock is not allowed** for goods. The study's "allow and flag" rule was wrong. |
| One ascending number series per receipt type; after a reset, numbering continues from the last transmitted invoice. | TIS spec 6.3 | Till restores its counter from the VSCU's `lastSaleInvcNo` at start-up. |

**Corrected architecture:** if the VSCU runs in the cloud, an internet outage at the shop stops receipting at once. To survive outages, the VSCU has to run **inside the shop** on a small always-on box (mini PC or similar) that the tills reach over the shop's local Wi-Fi. That box then gives the shop up to 24 hours of offline trading. Hesabu becomes a cloud back office plus, per branch, an **edge hub** running the VSCU JAR and a local sync relay. That adds hardware cost and a device to support, and Phase 0 should price it.

What `OutageExperimentTest` shows, against a fake VSCU that models rule 2.2.4:

1. Receipts keep printing for a 4-hour outage (8 sales signed while KRA is unreachable).
2. 24 hours after the first receipt that hasn't reached KRA, the VSCU refuses to sign.
3. When the link returns, the queue uploads and numbering continues without gaps.
4. If the VSCU itself is unreachable, the sale is blocked and no stock moves.
5. Goods beyond stock on hand are refused before the VSCU is called.

Assumptions the fake makes that must be checked against KRA's real JAR in the sandbox:

- the error code returned after 24 hours (`999` here);
- whether the 24 hours start at the first unsent signature (modelled) or at the last successful upload;
- signature formats (random base32 here, cryptographic in reality);
- the spec's own inconsistencies: the item field table labels `itemCd`/`itemClsCd` the other way round from its JSON sample, names the item tax field `totTaxAmt` in the table but `taxAmt` in the sample, and the sample uses an 18% rate. The code follows the samples and KRA's 16% rate table (A exempt, B 16%, C 0%, D non-VAT, E 8%).

### M-Pesa: the matcher works as designed

`PaymentMatcher` holds both sides — codes typed at the till and confirmations from Safaricom — and only marks a sale `VERIFIED` when the money arrived on the right till for at least the sale amount. Tested cases: confirmation before or after the code is typed, Safaricom retrying a callback, short payments, a payment to a different till, one customer message used for two sales, codes that never arrive (stale pending), and money received but attached to no sale. STK Push results are tied to the sale through the `CheckoutRequestID`.

What still needs the real sandbox: callback timing, the v2 C2B payload (MSISDN is masked or hashed), and whether a paybill STK payment also triggers a C2B confirmation (the matcher deduplicates either way).

## Running against the real sandboxes

Nothing here signs up for anything; both registrations are yours to do.

### Daraja (Safaricom sandbox)

1. Create an app on the Daraja developer portal and note its consumer key and secret. STK Push uses Safaricom's published test shortcode `174379` and its test passkey (defaults in `application.yml`); C2B uses the test shortcode your app is given.
2. Expose the app on public HTTPS, for example with a Cloudflare tunnel:
   ```bash
   cloudflared tunnel --url http://localhost:8080
   ```
3. Start the app with your values:
   ```bash
   DARAJA_CONSUMER_KEY=... DARAJA_CONSUMER_SECRET=... DARAJA_C2B_SHORTCODE=600xxx PUBLIC_BASE_URL=https://<tunnel-host> mvn spring-boot:run
   ```
4. Register C2B URLs, simulate a payment, then type its code against a sale:
   ```bash
   curl -X POST localhost:8080/spike/c2b/register
   ```
   ```bash
   curl -X POST localhost:8080/spike/c2b/simulate -H 'Content-Type: application/json' -d '{"code":"HQ-T1-000001","amount":100}'
   ```
   ```bash
   curl -X POST localhost:8080/spike/sales/s1/mpesa-code -H 'Content-Type: application/json' -d '{"code":"<TransID from the log>","amount":100}'
   ```
   ```bash
   curl localhost:8080/spike/shift-report
   ```
5. STK Push to the sandbox test phone:
   ```bash
   curl -X POST localhost:8080/spike/sales/s2/stk -H 'Content-Type: application/json' -d '{"phone":"254708374149","amount":1}'
   ```

Callback paths are `/hooks/...` because Daraja rejects callback URLs containing words such as "mpesa" or "safaricom".

### eTIMS (fake VSCU now, KRA sandbox later)

With no KRA access, the app talks to its own fake VSCU at `/fake-vscu`:

```bash
mvn spring-boot:run
```
```bash
curl -X POST localhost:8080/spike/etims/demo-sale
```
```bash
curl -X POST 'localhost:8080/fake-vscu/_control/kra?up=false'
```
```bash
curl localhost:8080/fake-vscu/_control/status
```

To use KRA's real test VSCU: register on the eTIMS sandbox (KRA's "Step by step guide on how to register in sandbox"), apply for VSCU and get the test JAR, run it locally, then point the spike at it:

```bash
VSCU_BASE_URL=http://localhost:8088 VSCU_TIN=<your sandbox PIN> VSCU_BHF_ID=00 VSCU_DEVICE_SERIAL=<registered serial> mvn spring-boot:run
```

Then repeat the outage test by hand: disconnect the machine running the JAR from the internet (leave the till-to-JAR link up), keep selling, and record when and how it stops.

## Layout

```
src/main/kotlin/ke/hesabu/spike/
  daraja/   DarajaClient (token, STK, C2B register/simulate), callback controllers, PaymentMatcher
  etims/    VSCU models and client, SaleToVscu (tax maths), FiscalReceiptService (till rules), FakeVscu
src/test/kotlin/...  PaymentMatcherTest, DarajaCallbackControllerTest, SaleToVscuTest, OutageExperimentTest
```
