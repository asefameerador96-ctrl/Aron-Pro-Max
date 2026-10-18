> **lead-backend, 2026-10-17 (lane/lead-backend):** 1 and 2 done; 3 not done.
> 1. `route-memo` and `route-std` are open to the AMO, scoped to its zone by the token (tested: zone 1 AMO sees R1 and R2 only).
> 2. `route-std` has a `category_name` column.
> 3. The brand Target column is a target-programme column, deferred by docs/27 (sponsor rule 2); `sales-summary` keeps memos and BSR %.

# Request: report access and columns for the AMO Report tile (from android-amo, 2026-10-18)

To: backend analytics owner.
1. `route-memo` (memos, gross, net per route) and `route-std` are `WEB_ROLES` only. The AMO app computes per-route totals from `std-memo` rows, which is only exact for a short report; please add `Role.AMO` to `route-memo` so the totals come from the server for any range.
2. docs/07 asks for "per-route STD by category": `route-std` has route x SKU but no category column. Please add a `category_name` column (and open it to the AMO), then the app can group.
3. docs/07's brand table has Target[fractional]/Sales/%/Memo; `sales-summary` returns only `brand_memos` and `bsr_pct`. The screen shows memos and the percentage.
