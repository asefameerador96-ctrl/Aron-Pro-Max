# Request: bound `DuesAdjustmentWrite.amount_mtk` in the contract (backend-admin to lead)

**Status: declined 2026-10-15 by the lead; bundle into the next planned contract version (v1.4) if wanted. The contract is unchanged.**

From: backend-admin, 2026-10-15. Needs lead approval (oasdiff counts it as breaking).

db added CHECK `|amount_mtk| <= 10^13` on `app.dues_adjustment` and `app.due_ledger` (lane/db 20040eb7). The handler
(`DuesAdjustments.kt`) already answers 400 `body.amount_mtk` `above_max` BEFORE any insert for `|amount| > 10^13`, so the
database CHECK can never be reached from the API; test `DuesAdjustmentTest.theAmountCapIsInclusiveAndAnswers400BeforeAnyInsert`
(exactly 10^13 accepted on both sides, 10^13 + 1 and the int64 extremes rejected).

Proposed contract change (request body only, additive tightening): in `components.schemas.DuesAdjustmentWrite`

```yaml
amount_mtk: { allOf: [{ $ref: '#/components/schemas/Mtk' }], minimum: -10000000000000, maximum: 10000000000000 }
```

oasdiff 1.33.0 reports `request-property-min-set` on POST /v1/admin/dues-adjustments ("Setting a bound rejects values the previous
contract accepted"). The only client of this request is the web admin screen, and the server already rejected those values, so no real
client is affected. Approve and I add it in one commit with the `info.version` bump, slices and web types; or decline and the contract
stays as is (the 400 is documented by the existing `BadRequest` response).
