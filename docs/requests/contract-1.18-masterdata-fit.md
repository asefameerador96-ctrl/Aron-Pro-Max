# Contract 1.18.0 (provisional): master-data fit (lane masterdata-fit, 2026-11-13)

Owner basis: D-OWNER-MASTERDATA-01 and D-OWNER-PARITY-02 ("when our system launches we don't want any of the users to feel that anything changed"). The real master data
is the truth; where our build could not hold or would reject it, OUR build changed. Gap numbers (M01..M22) are those of `docs/reference/master-data-mapping.md`; the
decisions are `D-MD-nn` in `DECISIONS.md`. **Status: applied in `contract/openapi.yaml`. Push 1 of the lane set info.version 1.18.0; push 1b (the lead's review round) adds the report members
`unchanged`, `skipped` and `by_channel`, the warning codes `phone_source_kept`, `tier_source_kept`, `tier_cleared`, `pin_kept_as_candidate`,
`coordinates_invalid` and `coordinates_incomplete`, the row-error code `sub_channel_inconsistent`, and `contact_number` as raw text. oasdiff counts a new
value in a response enum as breaking, so the repository's rule (docs/30 s3) needs a version bump in the same push: info.version is now **1.18.1**. The
lane's slot is unchanged (still 1.18); the lead may renumber the whole slot at landing as planned.** The number is provisional:
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
| `importOutlets` report | `OutletImportRowError.code` gains `unknown_channel` and `sub_channel_inconsistent`; Preview and Result gain `warnings`, `warnings_truncated`, `warnings_by_code`, `unchanged`, `skipped`; Result also gains `creates`, `updates`, `by_channel`, `errors`, `errors_truncated` | Honest reporting of what loaded, what changed nothing and what has something to look at. |
| `importOutlets` request row (push 1b) | `contact_number` is the sheet's RAW text (maxLength 300); the server repairs it | B1: the portal must not drop a phone it cannot repair. The server repairs, warns (`phone_unusable`), counts it and keeps the text in the outlet's `source_contact_number`. |
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
* **Generated outlet codes** (push 1b) are `gen.` plus 20 hex characters of a hash over the row's identifying fields (zone, route, cluster, name, owner), so the SAME codeless row imported twice finds the same outlet instead of creating a second one. Never `AD-`: a code the server makes can never equal a field code.
* **Nothing is lost** (push 1b). A phone that cannot be repaired, and a tier that was not stored (it disagrees with the channel, or is retired or unknown), are kept in the outlet's source columns `source_contact_number` / `source_sub_channel` (V0199) and counted (`phone_source_kept`, `tier_source_kept`). The sheet's Created At / Updated At go to `source_created_at` / `source_updated_at`; our own `created_at`/`updated_at` stay the time the SERVER wrote the row, so the delta readers see imported outlets.
* **Update rules** (push 1b, B3). The GT default and the channel derived from the sub-channel apply on CREATE only. On an update a blank channel or sub-channel means "keep what is stored"; a row that CHANGES the channel must carry a sub-channel of the new channel or none (else the row error `sub_channel_inconsistent`), and a stored tier of the old channel is cleared with the warning `tier_cleared`. A row that changes nothing is reported `unchanged`: no write, no version bump, no audit row (so a re-import of the same file is free and leaves the history clean).
* **Pins** (push 1b). A pin the field confirmed (or captured, `location_basis` `provisional`) is never overwritten or un-confirmed by a master pin: the master pin goes to `app.outlet_location_history` with basis `candidate` (warning `pin_kept_as_candidate`) and is never the pin in force. With `location_confirmed: true` the owner states that the master coordinates are confirmed and they replace it. A pin out of range, half given or outside the Bangladesh box never costs the row: the outlet loads with no pin or with an unconfirmed one (`coordinates_invalid`, `coordinates_incomplete`, `coordinates_outside_bangladesh`).

## 3. For the Android lanes (this lane changes no Android screen)

Mirrored DTOs: `Route.visit_kind` is already a nullable `String` and `visit_days_mask` an `Int`: no DTO change, but the VALUES widen. The lanes must:

1. **SR app, route picker, route header, Today.** A route whose `visit_kind` is `none` (and mask 0) shows the label **"No visit plan" / "ভিজিট প্ল্যান নেই"** in the place
   where the day text shows today (`display_label` stays the printed text, never a key). State to show: *not planned today, never planned*. It is never offered as "today's
   route"; it can still be opened from the full route list (the rep holds it); its outlets are listed but nothing counts toward the strike rate (`target_outlets` is 0).
   Do not treat mask 0 as an error or as "all days". A route is "planned today" ONLY by `RouteSnapshot.planned_today` from the server (or, offline, mask bit AND selling day).
2. **SR app, bit-mask helpers.** Any helper that asserts `mask in 1..127` or `kind in {daily,3f,2f}` must accept `0`/`none`. `daily` still means mask 127.
3. **AMO and TSO apps.** Wherever a route's visit days are listed (AMO team routes, TSO route list) show the same "No visit plan" state; the supervisor bundle already
   carries `visit_days_mask`/`visit_kind` of the zone's routes.
4. **Memo numbers — BLOCKING, the lead assigns this lane (push 1b, finding B2).** `android/core-database/src/main/kotlin/com/aktcl/aron/android/database/MemoNumbers.kt:12`
   requires the username to match `^[a-z][a-z0-9]{3,31}$` (4 to 32 characters) and THROWS on a shorter one, so a rep with a 3-character id (the field has them)
   could not sell on the installed build. Exact request:
   1. In `MemoNumbers.kt` relax the `require` regex to `^[a-z][a-z0-9]{2,31}$` (3 to 32). The format `<username>-<yyMMdd>-<seq>` is otherwise unchanged, and so is
      every other rule (lower case, the 3 or 4 digit sequence).
   2. Check every other Android place that asserts a username length or shape (login field validation, memo parsing and display, offline unlock) and widen it the
      same way; a 3-character username must survive a full offline day: login, memo numbering, print, sync.
   3. Add a test with a 3-character username (`ab1`) that builds, parses and prints a memo number, and one that the server's number for that rep is accepted.
   4. Tell the lead the version code of the release that carries this change. **Until then the SERVER REFUSES to create or rename an SR/AMO user to fewer than 4
      characters** (error `needs_app_release` on `body.username`), and the bulk loader reports such reps as "held: needs app release" instead of creating them.
      The gate is the config key `cfg.release.short_rep_id_version_code` (V0199, registry default 0 = refuse): once that release is published AND
      `cfg.release.min_version_code` for `sr`/`amo` has reached it, an admin sets the key to that version code and short rep ids are allowed. Nothing else changes.
5. **Outlet requests and phone entry.** Android already repairs phones with `TextRules.normalisePhone` (shared:rules). Nothing to change; keep the rule and the server's in step
   (the server calls the same function).
6. **Astha tier labels.** The tier of an outlet is its `sub_channel` (Gold, Diamond, Platinum, Titanium). Any hard-coded tier list or colour in the apps (Silver) must follow the
   field: Gold, Diamond, Platinum, Titanium, in that order; an outlet that still references Silver (inactive sub-channel) keeps showing it as a plain label with no tier styling.
7. **Cluster types.** If a cluster type is shown, read the code list `cluster_type` from the bundle (`label_bn`, `label_en`); the six field values are Transit Hub, Residential,
   Commercial Hub, University Hub, Bazar Hub, Industrial Hub. An unknown value is shown as text.
8. **Tier labels and Silver.** Silver's code-list item keeps NO `valid_to` (a passed `valid_to` would drop the item from the bundle and the phone would lose the
   label of every outlet still on Silver); it carries `attrs.retired = true` instead. A picker hides a retired item; a label is still shown for an outlet that
   references it. The server refuses to write a retired tier.

9. **Imported pins.** Outlets loaded with `location_confirmed` = false carry `location_basis` `master` and `location_confirmed` false: the apps already treat that as
   "first visit corrects the pin" (Nearby hides it). If the owner loads with the switch ON, the same outlets arrive confirmed. No app change; this is stated so the lanes do not
   hard-code either.

## 4. Not in this contract

The bulk loader of push 2 is a server-side tool, not an endpoint. No endpoint is added.
