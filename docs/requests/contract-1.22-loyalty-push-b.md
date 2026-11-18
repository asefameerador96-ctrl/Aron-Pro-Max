# Contract 1.22.0 (provisional): loyalty push B (record for the contract gate)

From: lane portal-loyalty-api, 2026-11-18 (D-LOY-API-32, -34). Additive only, no migration, no breaking change.

Added, all optional or new:
- `BundleUser.geography` (nullable): wing, division, territory and zone of the user's OWN zone, each `{id, code, name, name_bn}` (`BundleGeoName`, `BundleGeography`).
- `LoyaltyBalance.tier_code` (nullable).
- `AsthaTargetRow.brand_name`, `brand_name_bn`; `ProgrammesSection.astha_truncated_months`.
- `astha_targets` now carries the whole current quarter (one bound of 1,000 rows; the current month is filled first). Before this, only the current month; no deployed phone reads these rows.
- Description-only: `RedemptionPayload.balance_before_points` is informational (never compared with the ledger), cash-back is a redemption header field, the ACHIEVEMENT RULE of `ProgrammesSection`.

Removed: nothing (the changed lines are rewritten descriptions and the version number).
