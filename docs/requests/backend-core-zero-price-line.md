# Request: reject a sale line with no positive unit price (lead → backend-core, 2026-10-24)

**From:** lead (ruling D-SALE-ZERO-PRICE-01, 2026-10-24 06:05 UTC). **To:** backend-core (#16 on its next session). **Why:** the owner's SR Sale screenshot on dev offers `MaxDB-10S_10HL` at ৳ 0.00 per packet. db #10 confirmed the zero is in the real catalogue (`db/seed/sku_catalog.csv`: `MaxDB-10S_10HL`, `SupSty-10S`, `SupSty-20S` carry 0.0 in all five price types) and that both dev seeds keep zero-price SKUs out of the sales plan; the Sale list is not filtered by price or plan.

**Ruling D-SALE-ZERO-PRICE-01:** a SKU with no positive unit price for the outlet's price type is never offered on the Sale screen and can never become a sale line. Discounts and free issues are deferred programmes (docs/27); a zero-price line is therefore always an error, never a sale.

**Server side (this request):**
1. `POST /v1/sync/upload` (and any other sale write path): a sale line whose unit price is zero or missing for the outlet's price type on the business date is rejected with the existing validation problem code (a per-record rejection in the upload result, not a whole-batch failure; idempotent on retry). Name the code you use in the candidate message; a new code goes to `docs/requests/contract-v1.8-queue.md`, not into the contract.
2. The sync bundle: do not change its shape (contract rule). If the bundle's price rows already carry the price-type prices, the app filters; if a SKU can reach the phone with no price row at all, say so in the candidate message.
3. Tests: a fuzz/property case with a zero-price line among valid lines; the valid lines land, the zero line is rejected, a retry is idempotent.

**App side:** android-tso #3 filters the Sale list (one slice, same ruling). **Owner questions (morning report 2026-10-24):** prices for the three SKUs or "discontinued"; whether the Sale screen should list only the sales-plan SKUs (the old app's behaviour is not recorded in docs/06–08).

| Status | Date |
|---|---|
| queued | 2026-10-24 06:05 UTC |
| done (backend-core #16, D-BC-ZERO-PRICE-01; code `schema_invalid`, `price_invalid` queued in contract-v1.8 row 2) | 2026-10-24 |
