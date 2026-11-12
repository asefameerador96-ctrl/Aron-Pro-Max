# Contract 1.13.0: loyalty-programme portal (lane portal-loyalty-api, 2026-11-12)

For the lead's approval as **D-LEAD-CONTRACT-113-01** (DECISIONS.md; the lead appends that ruling and this file cites it by id). The change
is additive except two response enums that gain a value; the oasdiff gate (`tools/ci/contract-breaking.sh`) reports those as breaking, and
this file is the lead-approved request that lets the same push (info.version 1.12.2 to 1.13.0) pass. No shipped client reads either: the
apps do not call the programme or gift operations, the bundle `programmes` stays null, and the web Loyalty section (INT train 99) runs on mocks against its own assumed paths; the divergences it must close after 1.13.0 lands are listed below. The field is
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

The new operations sit under `/v1/admin/programmes/{id}/...` and `/v1/admin/gifts/{id}` (the existing paths), not under `/v1/admin/loyalty/...`
as brief s7 sketched: a recorded difference from the brief, so the old paths keep one home.

`listProgrammes`, `createProgramme`, `updateProgramme`, `putProgrammeEnrolments`, `listGifts`, `createGift` and `updateGift` keep their
paths. `listProgrammes` takes its `status` filter as `ProgrammeStatus` (a wider request enum) and an optional `kind`; `updateProgramme`
and `updateGift` take the new `ProgrammePatch` and `GiftPatch` (fewer required members than `ProgrammeWrite`/`GiftWrite`) and gain `409`.
**Plainly: these two request bodies become closed schemas that no longer accept `kind` and `code` (programme) or `programme_id` and `code` (gift)**:
they are immutable, and a client that still sends them gets `400` from the routes of train 2. The INT web BFF (`web/src/lib/admin/loyalty-server.ts`,
its update schemas) still sends them; the web lane aligns in its next train. oasdiff reports only minProperties/minLength warnings for this;
`putProgrammeEnrolments` gains `409`. The old `/v1/admin/programmes` and `/gifts` paths are re-homed to the permission module `loyalty`
in the backend module map (invisible to oasdiff).

## New schemas and members

- New: `ProgrammeStatus`, `ProgrammeTier`, `ProgrammeVersion`, `ProgrammeVersionPage`, `ProgrammeStatusChange`, `ProgrammePatch`,
  `GiftPatch`, `LoyaltyProductRef`, `LoyaltyScopeNode`.
- `ProgrammeWrite`: optional `request_uuid` (the server requires it on this surface; making it required in the schema would be a second
  breaking change), `points_expire_on`, `tiers`, `qualifying`, `scope`; its `status` member stays (deprecated, refused with 400).
- `GiftWrite`: optional `request_uuid`.
- `Programme`: optional `programme_version_id`, `version_no`, `tiers`, `qualifying`, `scope`, `change_reason`, `created_at`, `updated_at`.

## Web divergences to close after 1.13.0 lands (web lane, not this lane)

The BFF calls `/v1/admin/loyalty/programmes/{id}/status` (web/src/lib/admin/loyalty-server.ts:115), `/versions` and `/progress` (loyalty-load.ts:19, :23), while the contract has `/v1/admin/programmes/{id}/status` and `/versions` and no progress operation (deferred, D-LOY-API-02); `createProgramme` sends tier `threshold_value` without `sort_order` and a `channels` array, which the closed `ProgrammeWrite`/`ProgrammeTier` refuse (the contract names the tier member `threshold` as a decimal string with `threshold_basis`, `sort_order` is required, and channels are `scope` nodes of type `channel`/`sub_channel`); the PATCH bodies send `kind`/`code` and `programme_id`/`code`; `web/src/lib/admin/modules.ts:46` still maps `/v1/admin/loyalty/**`; `web/tests/tso-integration-web.test.tsx` (about line 96) keeps a now-unneeded typecheck exception for `loyalty`.

## Database view contract

`dw.view_contract_version()` moves 1.6 to 1.7 (V0162: `dw.v_programme`, `dw.v_programme_version`, `dw.v_gift`, additive views;
`docs/dw-view-contract.md` regenerated). The database contract is separate from this API contract.

## Units and money

No money member is added. Tier `threshold` is a non-negative decimal string in STD as stored (the unit of the existing target model, owner
question Q1 default), with `threshold_basis: std`; no threshold is invented (docs/27:15). `points_cost` stays integer points. Quantities
that appear later (progress views) stay in base units for storage and aggregates, shown per rule 5.

## 1.13.1 (description-only; lane portal-loyalty-api train 2, after the lead's Opus pass on 17be785bd)

No schema, operation, parameter or status code changes, so there is no oasdiff finding and nothing new to approve; the version moves 1.13.0 to 1.13.1 because the
text of released 1.13.0 was corrected (house rule: a description-only change is a patch bump, as 1.2.1, 1.12.1, 1.12.2). The corrections: `listProgrammeVersions` says each
version is judged on its own scope (the routes did not do that in the first build; they do from this head); the read rules of the enrolments moved from `putProgrammeEnrolments`
to `listProgrammeEnrolments`; `listGifts` gained its read rules; the list and path reads state their `400`. The only existing test that pins the exact version is
`AmoManualContractTest` (1.13.0 to 1.13.1).

