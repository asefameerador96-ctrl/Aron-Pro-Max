# Owner question: the unit of a campaign's STD (request, no change made)

Lane sr-targets-server (T-KPI3), 2026-11-25. Asked by the lead; answer needed from the owner. **The STD figure itself is NOT changed by this request.**

## What the server sends today
`RouteCampaign.std` is `{target, achieved, remaining}` in ONE number in base units: the sum of the sale-line base quantities of ALL the campaign's SKUs at the outlet, whatever the SKU category. `std.target` is the outlet's campaign STD target as uploaded (`outlet_campaign_target.std_target`), `std.achieved` is the sold sum, and the table `skus[]` carries each SKU's own `target_qty_base` and `sold_qty_base`. Base units mean sticks (cigarettes, bidi), pieces (lighters) and dozens (matches), so a campaign that covers cigarettes, lighters and matches adds sticks, pieces and dozens together: the sum is arithmetically valid but has no unit a rep or a manager can read (owner rule 5: the field counts cigarettes in packets, matches in dozens, lighters in boxes of 25).

## The question for the owner
Should a campaign's STD be reported (a) as today, one base-unit sum across the campaign's SKUs, (b) per category, or (c) per category in the field's units (packets, dozens, boxes)? And, for each, what does the STD TARGET mean: one number the owner uploads for the outlet, or one per category?

## What each answer changes
| Answer | Meaning | Contract change (additive, no migration unless the target is per category) |
|---|---|---|
| (a) Keep one sum | The campaign STD stays a single base-unit number; the phone shows it as "STD" with no unit and the SKU table carries the real quantities. | None. |
| (b) Per category, base units | Cigarettes in sticks, lighters in pieces, matches in dozens, each with its own target and achieved. | A new optional `RouteCampaign.std_by_category[]` of `{category_code, target, achieved}` (the target is the sum of the outlet's SKU targets of that category, which exists today in `outlet_campaign_sku_target`; the achieved is the sold sum of the same SKUs). `std` stays for older phones. No migration: both numbers come from existing tables. If the owner wants an uploaded target per category (not the sum of the SKU targets), a new target table and an upload column are needed (a migration and an admin-page change). |
| (c) Per category in field units | Packets of cigarettes (20s and 10s counted apart), dozens of matches, boxes of lighters. | As (b), plus a `by_pack` list per category entry like the KPI's (`pack_factor`, `target`, `achieved`; contract 1.25.0 `category_code`) so the phone converts without guessing; the lighter box (25) is a phone-side unit rule already owned by `shared/rules`. |

## What the server can say about the data today
- The campaign SKU targets are stored per SKU in base units, so (b) and (c) are computable from the stored data without a new upload.
- A campaign whose SKUs are all of one category (the usual Super Star Low case) is unaffected by the choice: the sum already has one unit.
- A route's `campaigns[]` cut flags (`truncated` per outlet, `campaigns_truncated` per route) and the rule that the figure covers ALL the campaign's SKUs apply to every answer.

## Proposed default if the owner does not answer
(a): no change. The phone shows the SKU table in each SKU's unit and the STD line as a plain number.
