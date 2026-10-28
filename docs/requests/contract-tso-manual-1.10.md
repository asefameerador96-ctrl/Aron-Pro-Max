# Contract 1.10.0: TSO manual build (breaking-change gate file)

Status: written by lane F1 (foundation-contract) on 2026-10-29 for `docs/requests/tso-manual-build-2026-10.md` s3. That
build contract approves this file (D-TSO-MAN-43: "F1 adds `docs/requests/contract-tso-manual-1.10.md`, which this file
approves"). `contract/openapi.yaml` `info.version` goes 1.9.0 to 1.10.0 in the same push, so `tools/ci/contract-breaking.sh`
reports the items below as warnings, not errors (docs/30 s3).

## 1. Breaking-gate items (response enums an old reader does not know)

Each is an added value only; nothing is removed, renamed or narrowed. An old app or web build that meets a new value
must treat it as unknown (the Kotlin wire DTOs carry every enum as a String; `UnknownEnumToleranceTest`).

| Enum | Added values | Where a response carries it | Reader impact |
|---|---|---|---|
| `RecordType` | `support_ticket`, `support_ticket_event` | `RecordAck.type`, quarantine and rejected-record reads, `MediaMetaPayload.ref_type` | Only the TSO app sends them, after A2 ships; other apps never see them in their own acks |
| `RecordOutcomeCode` | `after_final_submit` (quarantined), `day_final_submitted` (rejected, not retryable), `ticket_state_invalid` (rejected, not retryable), `issue_type_unknown` (rejected, not retryable) | `RecordAck.code`, `SalesSubmitDigest.rejected[].code`, quarantine page | A rejected ack carries `retryable: false`, so an app on an old build marks the row final and shows the bundle's `reason_texts` (bn and en added in `ReasonTexts.kt`); a quarantined ack needs no code knowledge. The four codes are in docs/24 s4.5 |
| `ProblemCode` | `ERR_TICKET_TRANSITION_INVALID`, `ERR_TICKET_COMMENTS_CLOSED`, `ERR_DAY_FINAL_SUBMIT_REOPENED` (all 409) | `Problem.code` | `ERR_DAY_FINAL_SUBMIT_REOPENED` reaches only a TSO app that replays a reopened Final Submit; an old TSO app shows the generic conflict message. The ticket codes reach only the web support desk |
| `CodeListKey` | `ticket_issue_type`, `ticket_resolution_code` | bundle code lists, `GET /v1/admin/code-lists` | The phones keep the bundle's code lists as raw JSON keyed by `list_key` (`ReferenceRepository`), so a list an old app does not use is inert; the server sends only `CodeListKey` lists (`BundleService`) |
| `AdminModule` (lead ruling R-F-11) | `support` | `Me.modules` and `UserAccess.modules` (keys) | Only ADMIN and SUPERADMIN web sessions carry module grants; the field apps get `modules: null`. The web reads grants through its own `GRANT_MODULES` list, so an old web build ignores a key it does not list. The server sends `support` only once B1 adds the `AdminModules` line and F2 the grant CHECK value |
| `ReportKey` (lead ruling R-F-12) | `tso-visit-log` | `GET /v1/reports` definitions, export log `report_key` | The web shows only the reports in its own catalogue; an export-log row of a key it does not know is shown by its raw key. B3 registers the report, W1 adds the catalogue entry and labels |
| `format: int64` on two 1.9.0 response members (lead ruling R-F-16) | `NationalSalesCard.achievement_pct_bp`, `TargetVsAchievement.achievement_pct_bp` | `queryNationalSales`, `getRouteMap` | Same JSON integers on the wire; the format only tells a typed reader to use a 64-bit integer (an achievement against a tiny target can exceed the 32-bit range). Listed in case oasdiff reports a response format change; the web's `number` is unchanged and no Kotlin wire DTO decodes either schema today |

## 2. Additive changes (not breaking; listed for the reviewer)

- New operations (10): `listSupportTickets`, `getSupportTicketSummary`, `getSupportTicket`, `updateSupportTicket`,
  `addSupportTicketEvent`, `listMyNotifications`, `markMyNotificationsRead`, `queryNationalBrandCpr`,
  `queryNationalSegmentShare`, `queryNationalLoginSubmitUsers` (docs/24 Appendix A updated: 268 operations on 217 paths).
- New schemas (26): `TicketNumber`, `SupportTicketStatus`, `SupportTicketPriority`, `SupportTicketEventKind`,
  `NotificationKind`, `SupportTicketPayload`, `SupportTicketRecord`, `SupportTicketEventPayload`,
  `SupportTicketEventRecord`, `SupportTicket`, `SupportTicketPage`, `SupportTicketEvent`, `SupportTicketDetail`,
  `SupportTicketSummary`, `SupportTicketUpdate`, `SupportTicketEventWrite`, `NotificationItem`, `NotificationInbox`,
  `NotificationReadRequest`, `NotificationReadResult`, `NationalBrandCpr`, `NationalSegmentShare`, `LoginSubmitUserRow`,
  `NationalLoginSubmitUsers`, `TargetAchievementRow` (the former inline row of `TargetAchievement`, same members plus the
  integer ones), `TargetAchievementNode`. Every object schema is `additionalProperties: false`.
- `SyncRecord` oneOf and discriminator gain `SupportTicketRecord` and `SupportTicketEventRecord`.
- `getTargetAchievement`: optional query `children` (enum `zone`), a declared 400; `TargetAchievement` gains required
  `day`, `elapsed_days`, `period_days`, `last_updated_at` and optional `children`; each row gains required `category_code`,
  `target_qty_base`, `achieved_qty_base`, `tilldate_target_qty_base`, `remaining_qty_base`, `remaining_tilldate_qty_base`,
  `achievement_pct_bp`, `tilldate_achievement_pct_bp`. The server answers 404 for this operation today (no reader of the
  old shape exists; the AMO app shows its unavailable state), so the new required members break nobody.
- Optional members on responses the server already sends (required in a 1.10.x batch once the server lanes ship them,
  as 1.9.0 did with `digest`): `NationalSalesCard.monthly_target_qty`, `NationalLoginSubmit.submit_of_target_pct_bp`,
  `FinalSubmitPreview.submit_seq`, `.warn_route_ids`, `.routes[].route_class`, `.visit_kind`, `.ff_role`, `.upload_state`,
  `LeaveApplication.applicant_username`, `.applicant_name`, `.territory_name`, `Feedback.business_date`, `.captured_at`,
  `.username`, `.zone_id`, `TeamLocation.mock_fixes_today`, `Me.support_desk`.
- Optional request member: `DayReopenRequest.client_uuid`.
- Checker round 1 (same 1.10.0, nothing released yet): `NationalLoginSubmitUsers.truncated` (required boolean; the
  2000-row cap is flagged, as the other capped lists); `SupportTicketEvent.body` maxLength 2400 (the D-TSO-MAN-20
  resolution message is at most 2204 characters, comments and replies stay 1..2000); `TicketNumber` maxLength 22 (bigint
  sequence); `updateSupportTicket` description: check order, the `op_uuid` replay before `expected_version`, 409
  `ERR_CONFLICT` for a reused `op_uuid`, deterministic event UUIDs; `TargetAchievement` / `TargetAchievementRow`
  descriptions: the item-sum rule with the 21/26 example (33114, not 33115).
- Fix round 2, lead rulings R-F-05 to R-F-18 of the build contract s9 (same 1.10.0, nothing released yet):
  - R-F-14: `SupportTicketSummary` loses `resolved_pct_bp` and `open_pct_bp` (removed from a schema that is new in 1.10.0
    and was never released; the base 1.9.0 has no `SupportTicketSummary`, so oasdiff has nothing to compare). The phone
    and the web compute the whole percents once from the counts; total 0 prints "0%". `getSupportTicketSummary`
    description follows.
  - R-F-15: `TargetAchievement.period_days` 0..31 (was 1..31); `TargetAchievementRow.tilldate_target_qty_base` and
    `remaining_tilldate_qty_base` become nullable (as `tilldate_achievement_pct_bp`), all three null when `period_days`
    is 0. New members against 1.9.0, and the operation answers 404 today, so no reader of the old shape exists.
  - R-F-16: `format: int64` on every uncapped `*_bp` member: the two 1.9.0 members in s1, and the new
    `TargetAchievementRow.achievement_pct_bp`, `tilldate_achievement_pct_bp`, `NationalBrandCpr.brands[].cpr_bp`,
    `cpr_of_successful_bp`, `cpr_of_target_bp`, `NationalLoginSubmit.submit_of_target_pct_bp` (WireDtos: `Long?`).
  - R-F-11, R-F-12: the two enum values in s1, their mirrors (`ContractCatalogues.AdminModule` new with its
    `ContractDriftTest` row; `ReportKey.TSO_VISIT_LOG`), the docs/24 s12.3 row and `web/src/lib/admin/report-keys.ts`.
  - Description-only: R-F-05 (`SupportTicketPayload`, `SupportTicketEventPayload`: a requester role outside the
    allowed list is quarantined `scope_out_of_reach` and an accept runs the role check again), R-F-13
    (`NationalSalesCard.daily_target`, `remaining`, `achievement_pct_bp` and `TargetVsAchievement.daily_target`,
    `achievement_pct_bp`: null when W = 0; all were already nullable), R-F-17 (`NationalSales.working_days` and
    `NationalSalesCard.daily_target` per D-TSO-MAN-08), R-F-18 (`getTargetAchievement` and its `children` parameter:
    `children=zone` only at a territory or zone node, else 400 `ERR_VALIDATION`, already declared).
- Description-only: `NationalSalesCard.achievement_pct_bp` (exact formula), `DashboardSummary.by_category` (sold quantity
  of sale lines only), `FinalSubmitRequest.client_uuid` (the D-TSO-MAN-28 rule), `postFinalSubmit`, `getFinalSubmitPreview`,
  `postDayReopen`, `RecordOutcomeCode`, `ProblemCode`, `CodeListKey`, `RecordType`, tags `support` and `notifications`.

## 3. Deploy order and wiring

Server before apps (as R18). Server lanes: B1 (tickets, inbox, `Me.support_desk`), B2 (dashboard cards, Target Status),
B3 (Final Submit preview members, reopen, late-after-final codes, leave and feedback members, `mock_fixes_today`). Data
model: F2 (V0130 to V0140). Apps: A1, A2, A3; web: W1. Regenerated in this push: `contract/slices/**`, `WireDtos.kt`
(new groups for the ticket, inbox, dashboard and target DTOs), the hand mirrors `ContractEnums.kt` and
`ContractCatalogues.kt`, `web/src/contract/openapi.d.ts`, and, because the contract's record types and outcome codes feed
them, `PayloadShapes.kt` (generated), two `TypeRules` lines and four `ReasonTexts` entries in backend/sync (see
`docs/status/tso-manual-F1.md`).

## 4. Proof

- `python tools/slice-contract.py --check`, `python shared/contract/tools/gen_wire_dtos.py --check`,
  `python backend/sync/tools/gen_payload_shapes.py --check`, `shared/contract/tools/check-web-contract-drift.sh`: clean.
- `./gradlew :shared:contract:jvmTest` (ContractDriftTest, WireDtosDriftTest, SpecCrossCheckTest, BacklogCoverageTest and
  the new TsoManualContractTest): green.
- Redocly lint (`@redocly/cli@2.59.0`, `contract/redocly.yaml`): 0 errors, 0 warnings.
- `tools/ci/contract-breaking.sh`: runs in CI (the pinned oasdiff is a Linux binary). The approval conditions it checks
  (version 1.9.0 to 1.10.0 and this added request file) hold in the push.
