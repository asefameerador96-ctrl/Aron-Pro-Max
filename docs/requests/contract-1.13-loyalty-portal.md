# Contract 1.13.0: loyalty-programme portal (lane portal-loyalty-api, 2026-11-12)

For the lead's approval as **D-LEAD-CONTRACT-113-01** (DECISIONS.md; the lead appends that ruling and this file cites it by id). The change
is additive except two response enums that gain a value; the oasdiff gate (`tools/ci/contract-breaking.sh`) reports those as breaking, and
this file is the lead-approved request that lets the same push (info.version 1.12.2 to 1.13.0) pass. No shipped client reads either: the
apps do not call the programme or gift operations, the bundle `programmes` stays null, and the web page does not exist yet. The field is
unchanged (loyalty in the apps stays deferred, docs/27; no points earned or redeemed; memo offer line zero). Records: D-LOY-API-01 to 03.

## The enum additions (the breaking-by-rule changes)

- `Programme.status` changes from `ActiveStatus` (active, inactive) to the new `ProgrammeStatus` (draft, active, inactive). `inactive`
  stays (the portal shows it as "Ended"), so no value is removed. The database CHECK of `app.programme.status` is widened the same way
  (V0158, validated in V0159).
- `AdminModule` gains `loyalty` (a response enum: `Me.modules`, `UserAccess.modules`). The web grants editor must know the value before
  the contract is deployed (web/src/lib/admin/access-grants.ts GRANT_MODULES, modules.ts); see the deploy hazard in
  docs/status/portal-loyalty-api.md. V0160 carries every ADMIN's `targets` grant over to `loyalty`.

## New operations (admin-content tag, under the existing paths)

- `getProgramme`: `GET /v1/admin/programmes/{id}`.
- `listProgrammeVersions`: `GET /v1/admin/programmes/{id}/versions`.
- `setProgrammeStatus`: `POST /v1/admin/programmes/{id}/status` (draft to active by a SUPERADMIN, active to inactive by SUPERADMIN or an ADMIN
  with `w`; optional `If-Match`, `412`).
- `getGift`: `GET /v1/admin/gifts/{id}`.

`listProgrammes`, `createProgramme`, `updateProgramme`, `putProgrammeEnrolments`, `listGifts`, `createGift` and `updateGift` keep their
paths. `listProgrammes` takes its `status` filter as `ProgrammeStatus` (a wider request enum) and an optional `kind`; `updateProgramme`
and `updateGift` take the new `ProgrammePatch` and `GiftPatch` (fewer required members than `ProgrammeWrite`/`GiftWrite`) and gain `409`;
`putProgrammeEnrolments` gains `409`. The old `/v1/admin/programmes` and `/gifts` paths are re-homed to the permission module `loyalty`
in the backend module map (invisible to oasdiff).

## New schemas and members

- New: `ProgrammeStatus`, `ProgrammeTier`, `ProgrammeVersion`, `ProgrammeVersionPage`, `ProgrammeStatusChange`, `ProgrammePatch`,
  `GiftPatch`, `LoyaltyProductRef`, `LoyaltyScopeNode`.
- `ProgrammeWrite`: optional `request_uuid` (the server requires it on this surface; making it required in the schema would be a second
  breaking change), `points_expire_on`, `tiers`, `qualifying`, `scope`; its `status` member stays (deprecated, refused with 400).
- `GiftWrite`: optional `request_uuid`.
- `Programme`: optional `programme_version_id`, `version_no`, `tiers`, `qualifying`, `scope`, `change_reason`, `created_at`, `updated_at`.

## Units and money

No money member is added. Tier `threshold` is a non-negative decimal string in STD as stored (the unit of the existing target model, owner
question Q1 default), with `threshold_basis: std`; no threshold is invented (docs/27:15). `points_cost` stays integer points. Quantities
that appear later (progress views) stay in base units for storage and aggregates, shown per rule 5.
