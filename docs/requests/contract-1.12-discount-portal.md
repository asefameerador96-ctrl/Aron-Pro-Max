# Contract 1.12.0: discount-programme portal (lane portal-discount-api, 2026-11-11)

Approved by the lead as **D-LEAD-CONTRACT-112-01** (DECISIONS.md, 2026-11-11 08:26 UTC). The change is additive except one
response enum that gains a value; the oasdiff gate (`tools/ci/contract-breaking.sh`) reports that one as breaking, and this file
is the lead-approved request that lets the same push (info.version 1.11.2 to 1.12.0) pass. No shipped client reads it: the bundle
`offers` array is empty, the apps do not call the offer operations, and the web page does not exist yet. The field is unchanged
(memo offer line zero, bundle `offers` empty; docs/27). Records: D-DISC-API-01 to 04.

## The enum addition (the one breaking-by-rule change)

`Offer.status` changes from `ActiveStatus` (active, inactive) to the new `OfferStatus` (draft, active, inactive). `inactive` stays
(the portal shows it as "Ended"), so no value is removed. The database CHECK of `app.offer.status` is widened the same way
(V0152, validated in V0153).

## New operations (admin-products tag)

- `getOffer`: `GET /v1/admin/offers/{id}`.
- `listOfferVersions`: `GET /v1/admin/offers/{id}/versions`.
- `setOfferStatus`: `POST /v1/admin/offers/{id}/status` (draft to active by a SUPERADMIN, active to inactive by SUPERADMIN or an ADMIN with `w`).

`listOffers`, `createOffer` and `updateOffer` keep their paths and schemas; `listOffers` takes its `status` filter as `OfferStatus`,
and their descriptions state the new rules (create makes a draft; update appends a version while draft).

## New schemas and members

- New: `OfferStatus`, `OfferVersion`, `OfferVersionPage`, `OfferStatusChange`, `OfferSkuUnit`.
- `OfferWrite`, `OfferPatch`: optional `request_uuid` (the server requires it on this surface; making it required in the schema would be a
  second breaking change). `OfferPatch` also gains optional `valid_from`, `offer_type`, `level`, `stacking`, `reward_sku_id`,
  `qualifying`, `scope`; its `status` member stays (deprecated, refused with 400).
- `Offer`: optional `version_no`, `change_reason`, `created_at`, `updated_at`, `sku_units`.
- `AdminModule` gains `discounts`. It is also a response enum (`Me.modules`, `UserAccess.modules` key on it), so this line is the second part of the same approval under D-LEAD-CONTRACT-112-01 (the permission module of the portal; `/v1/admin/offers/**` moves off `targets`; migration V0154/V0155 widens the grant CHECK and carries every ADMIN's `targets` access over).

## Also in 1.12.0 (additive)

`listOffers`: its `status` filter takes `OfferStatus` instead of the shared `StatusQuery` (a wider request enum); `updateOffer`
gains the `409` response (an offer that is not a draft, or a reused `request_uuid`). What the descriptions say the server requires or
refuses (`request_uuid`, `drp_slide`, the `channel` node) holds from the lane's train 2, where the routes exist.

## Units and money

Money members stay integer milli-taka per base unit (`discount_mtk_per_unit`) or basis points (`discount_bp`); quantities are base units
(`*_qty_base`). `OfferSkuUnit` gives the SKU's `pack_factor` and `field_unit`, in the contract's own `QtyUnit` vocabulary (`pack`, `dozen`, or the SKU's base unit), so the portal shows both (docs/34 s4.2); bidi in packets is pending the owner's confirmation.
