# Contract 1.18.0 (provisional): master-data fit (lane masterdata-fit, 2026-11-13)

Owner basis: D-OWNER-MASTERDATA-01 and D-OWNER-PARITY-02 ("when our system launches we don't want any of the users to feel that anything changed"). The real master data
is the truth; where our build could not hold or would reject it, OUR build changed. Gap numbers (M01..M22) are those of `docs/reference/master-data-mapping.md`; the
decisions are `D-MD-nn` in `DECISIONS.md`. **Status: applied in `contract/openapi.yaml` (info.version 1.18.0) with push 1 of the lane.** The number is provisional:
1.16.0 and 1.17.0 belong to other lanes, landing order decides, the lead may order a rename. Migrations V0195..V0200 (also landing order). Everything is additive for a
request body; the three response widenings (a new enum value, a lower minimum, a new code-list key) are the reason the breaking-change gate needs this file.

## 1. What changes on the wire

| Where | Change | Why |
|---|---|---|
| `Route`, `RouteWrite`, `RoutePatch` | `visit_kind` gains the value `none`; `visit_days_mask` minimum becomes 0 | M03: a route with no visit plan is stored as it is. `none` and mask 0 go together (a mask of 0 with any other kind, or `none` with a non-zero mask, is refused). |
| bundle `routes[]` (`Route`) | the same two widenings | The phone receives such a route. `RouteSnapshot.planned_today` is false and `target_outlets` is 0 for it, always. |
| `CodeListKey` | gains `cluster_type` | M22: six field values with Bangla labels; `attrs.value` is the field's wording. |
| `importOutlets` request row | `channel` optional (free text); new optional `sub_channel`, `geo_class`, `created_at`, `updated_at` | M05, M06, M16, M20. |
| `importOutlets` request | new optional `location_confirmed` (default false), `on_row_error` (`reject_batch` default, `skip_rows`) | M01 and the rule "a bad row is a row error, never a whole-batch failure". |
| `importOutlets` report | `OutletImportRowError.code` gains `unknown_channel`; Preview and Result gain `warnings`, `warnings_truncated`, `warnings_by_code`; Result gains `creates`, `updates`, `skipped`, `errors`, `errors_truncated` | Honest reporting of what loaded with something to look at. |
| memo number and username patterns | `^[a-z][a-z0-9]{2,31}` replaces `{3,31}` (3 to 32 characters) | M13: a rep id of 3 characters. |

No other member changes. Astha tiers are data, not shape: Gold, Diamond, Platinum, Titanium are sub-channels of Astha, Silver is `inactive` (V0199).

## 2. Rules in one place (for readers of the wire)

* **Visit plan.** `visit_kind` `daily` means every SELLING day of the zone (mask 127; Friday is off by the weekend rule; a make-up Friday in the calendar plans the daily
  routes that day, nothing else does). `none` means no visit plan. A named-day pattern that contains a weekend day is loaded as in the file and flagged in the load report.
* **Channels** the field writes ("GT Channel", "Astha Channel", "DCC Channel", "MT Channel", "RCC", "HoReCa") map to GT, Astha, DCC, MT, RCC, HoReCa. Empty: from the sub-channel,
  else GT with the warning `channel_defaulted`.
* **Phones** in every field shape (10 digits, `880` prefix, spaces, dashes, Bengali digits) are repaired to `01XXXXXXXXX` on every write path. The stored form never changes.
* **Warning codes** (report only): `phone_unusable`, `phone_repaired`*, `coordinates_rounded`*, `coordinates_outside_bangladesh`, `channel_derived`*, `channel_defaulted`,
  `sub_channel_mismatch`, `sub_channel_retired`, `unknown_sub_channel`, `astha_without_tier`*, `unknown_geo_class`, `created_at_unreadable`, `updated_at_unreadable`
  (* = informational, counted in `warnings_by_code` but not listed).
* **Generated outlet codes** are `gen.<id>` (never `AD-`): a code the server makes can never equal a field code.

## 3. For the Android lanes (this lane changes no Android screen)

Mirrored DTOs: `Route.visit_kind` is already a nullable `String` and `visit_days_mask` an `Int`: no DTO change, but the VALUES widen. The lanes must:

1. **SR app, route picker, route header, Today.** A route whose `visit_kind` is `none` (and mask 0) shows the label **"No visit plan" / "ভিজিট প্ল্যান নেই"** in the place
   where the day text shows today (`display_label` stays the printed text, never a key). State to show: *not planned today, never planned*. It is never offered as "today's
   route"; it can still be opened from the full route list (the rep holds it); its outlets are listed but nothing counts toward the strike rate (`target_outlets` is 0).
   Do not treat mask 0 as an error or as "all days". A route is "planned today" ONLY by `RouteSnapshot.planned_today` from the server (or, offline, mask bit AND selling day).
2. **SR app, bit-mask helpers.** Any helper that asserts `mask in 1..127` or `kind in {daily,3f,2f}` must accept `0`/`none`. `daily` still means mask 127.
3. **AMO and TSO apps.** Wherever a route's visit days are listed (AMO team routes, TSO route list) show the same "No visit plan" state; the supervisor bundle already
   carries `visit_days_mask`/`visit_kind` of the zone's routes.
4. **Memo numbers.** `android/core-database/.../MemoNumbers.kt` requires the username to match `^[a-z][a-z0-9]{3,31}$` (4 to 32). The server now accepts 3 to 32
   (`{2,31}`); relax the `require` to `{2,31}` so a rep with a 3-character id can sell. The memo number format `<username>-<yyMMdd>-<seq>` is otherwise unchanged.
5. **Outlet requests and phone entry.** Android already repairs phones with `TextRules.normalisePhone` (shared:rules). Nothing to change; keep the rule and the server's in step
   (the server calls the same function).
6. **Astha tier labels.** The tier of an outlet is its `sub_channel` (Gold, Diamond, Platinum, Titanium). Any hard-coded tier list or colour in the apps (Silver) must follow the
   field: Gold, Diamond, Platinum, Titanium, in that order; an outlet that still references Silver (inactive sub-channel) keeps showing it as a plain label with no tier styling.
7. **Cluster types.** If a cluster type is shown, read the code list `cluster_type` from the bundle (`label_bn`, `label_en`); the six field values are Transit Hub, Residential,
   Commercial Hub, University Hub, Bazar Hub, Industrial Hub. An unknown value is shown as text.
8. **Imported pins.** Outlets loaded with `location_confirmed` = false carry `location_basis` `master` and `location_confirmed` false: the apps already treat that as
   "first visit corrects the pin" (Nearby hides it). If the owner loads with the switch ON, the same outlets arrive confirmed. No app change; this is stated so the lanes do not
   hard-code either.

## 4. Not in this contract

The bulk loader of push 2 is a server-side tool, not an endpoint. No endpoint is added.
