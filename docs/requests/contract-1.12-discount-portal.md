# Contract 1.12.0, 1.12.1 and 1.12.2: discount-programme portal (lane portal-discount-api, 2026-11-11)

Approved by the lead as **D-LEAD-CONTRACT-112-01** (DECISIONS.md, 2026-11-11 08:26 UTC). The change is additive except one
response enum that gains a value; the oasdiff gate (`tools/ci/contract-breaking.sh`) reports that one as breaking, and this file
is the lead-approved request that lets the same push (info.version 1.11.2 to 1.12.0) pass. No shipped client reads it: the bundle
`offers` array is empty, the apps do not call the offer operations, and the web page does not exist yet. The field is unchanged
(memo offer line zero, bundle `offers` empty; docs/27). Records: D-DISC-API-01 to 05.

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

## 1.12.1 (train 2 of the lane; approved by the lead as D-LEAD-CONTRACT-1121-01, 2026-11-11 11:12 UTC)

The changelog entry of 1.12.0 in `info.description` is as it shipped; everything added after train 92 is under 1.12.1.

Version 1.12.0 is on INT (train 92). Train 2 adds the routes and two contract changes that the lead asked for in the verdict on 5f694a0b5:
`AdminModule` gains `discounts` (a response enum: `Me.modules`, `UserAccess.modules`), and `OfferSkuUnit.field_unit` takes the contract's
`QtyUnit` words (`pack`, `dozen`, `stick`, `piece`) instead of packet/box/dozen/piece, so there is one vocabulary (the member was never served
before the routes of this release; bidi in packets is pending the owner's confirmation, docs/34 s4.2). oasdiff reports both as
`response-property-enum-value-added`; no shipped client reads either (the apps do not call the offer operations; the `modules` claim is read
by the web portal, which ignores an unknown module, and Lane B adds it). The gate needs a new `info.version` for them: 1.12.1.

## 1.12.2 (train 94; approved by the lead as D-LEAD-CONTRACT-1122-01)

Additive only: `setOfferStatus` takes an OPTIONAL `If-Match` request header (the offer's ETag; when given and stale the move is refused) and
gains the `412` response. No header keeps the 1.12.1 behaviour, so no client changes. The same train brings V0156 (the database refuses any
status move but draft to active to inactive, and a change of the current version once the offer has left draft), V0157 (the row version a call
produced is stored, so a replay answers the first result's status and ETag; null for rows made earlier) and the pack-size check (a base
quantity must be a multiple of the SKU's `base_per_pack`, units rule 5; 400 `not_a_multiple_of_pack_factor`). oasdiff reports the 39
`response-property-enum-value-added` of 1.12.1 against a base older than train 93 only; against INT 82642d313 the additions of this version
are an optional parameter and a new response status.

## Also in 1.12.0 (additive)

`listOffers`: its `status` filter takes `OfferStatus` instead of the shared `StatusQuery` (a wider request enum); `updateOffer`
gains the `409` response (an offer that is not a draft, or a reused `request_uuid`). What the descriptions say the server requires or
refuses (`request_uuid`, `drp_slide`, the `channel` node) holds from the lane's train 2, where the routes exist.

## Units and money

Money members stay integer milli-taka per base unit (`discount_mtk_per_unit`) or basis points (`discount_bp`); quantities are base units
(`*_qty_base`). `OfferSkuUnit` gives the SKU's `pack_factor` and `field_unit`, in the contract's own `QtyUnit` vocabulary (`pack`, `dozen`, or the SKU's base unit), so the portal shows both (docs/34 s4.2); bidi in packets is pending the owner's confirmation.
