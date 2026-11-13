# Contract 1.16.0: loyalty push C (gift choice, gift photo rule, overdraw signal)

Ruling: D-LEAD-CONTRACT-116-01 (lead, written at the gate). Predecessor: `docs/requests/contract-1.15-loyalty-field.md` (sections 11 and 12 described this push while it was still numbered 1.15.0 and 1.17.0 provisional). Lane: portal-loyalty-api, push C. Additive only.

## Additive changes

- `RecordType` gains `gift_choice`; new schemas `GiftChoiceRecord` and `GiftChoicePayload` (`outlet_id`, `gift_id`, `quarter`, optional `fix`). Server table `app.gift_choice` (V0182, immutable, idempotent by `client_uuid`); the later `captured_at` wins until the hand-over photo locks the outlet's gift. The quarter must be the open quarter (`cfg.astha.quarter_start_month`, Dhaka date).
- `RecordOutcomeCode` gains `gift_locked` (choice after the photo lock, rejected, not retryable) and `gift_not_for_tier` (rejected, not retryable). Mirrored in `shared/contract`.
- `ProgrammesSection` gains `pending_gift_verifications`; `truncated_sections` gains the values `gift_assignments` and `pending_gift_verifications` and is now bounded by `maxItems: 5`.
- `LoyaltyBalance` gains the required boolean `overdrawn` (= `balance_points < 0`). A negative balance is the overdraw signal. It is derived on read and never stored on a ledger row.
- `gift_photo`, first photo wins (V0185): the FIRST photo by `captured_at` (tie: client uuid) takes the lock; a later one is stored as a flagged duplicate, never refused or deleted. `gift_photo_exists` stays in the enum for old rows but is no longer produced. `GiftPhotoPayload` text is updated.
- Config: default of `cfg.astha.gift_choice_roles` is SR and TSO (V0182; explicit values untouched).

## Not built

`cfg.loyalty.negative_balance_policy = reject` is NOT enforced at ingest (not built; the ledger job posts every stored redemption and the overdraw is the derived `overdrawn` flag). Config validation refuses the value `reject` until it is built (V0182 narrows the validation to `["accept_and_flag"]`).

## Compatibility

A phone on 1.15.0 never sends `gift_choice` and reads a bundle where the new members are ignorable; a phone that does not know `overdrawn` ignores it. Old clients that see `gift_locked` or `gift_not_for_tier` treat an unknown rejected code as final. Migrations V0182 to V0186.
