# android-sr-manual-s4 (lane A4 "sale-call-flow", rows SRM-A-25 to SRM-A-40), TRAIN 1

Branch `lane/android-sr-manual-s4` from INT. Spec: lead branch `lane/lead-docs-1022` (`docs/requests/sr-manual-build-2026-11-part-a.md`, notes 18 to 32, D-SR-MAN-*). T1 lane (money, units, geofence, sync, print, Bangla).
Method: integer math only; every new local record keeps its client UUID (nothing new is synced in TRAIN 1); kill-and-relaunch safe (the zero-sale reason lives in the file-backed draft); the memo print format is unchanged and no print golden moved; AMO call paths take the new code only through default-off parameters.

## Row by row

| Row | Verdict after TRAIN 1 | What was built / why not |
|---|---|---|
| SRM-A-25 select the outlet | done | SR list rows are one string `Name (code-phone-cluster)` after a yellow-orange dot (`OutletPickerContent(manualRows = true)`, SR only); the letter bar already lists only the letters present on the route; search box and Skip kept. |
| SRM-A-26 start the call | done | After the outlet is picked the call-start screen shows Sale History (blue) and আজকের টার্গেট (green) buttons, the question "আপনি কি কল শুরু করতে চান?" with হ্যাঁ / না, the geo panel, and the cart bar (০.০০, এগিয়ে যান). এগিয়ে যান before the call opens the box "Please start the call to continue." (+ Bangla line, ওকে). না keeps the outlet on screen (nothing abandoned; the back arrow abandons as before). The amount beside the outlet name: **owner question Q7** (not built). |
| SRM-A-27 outlet photo at call start | UI ready, switched off, needs contract | `CallPhotoStep` (shutter + fix together via the existing `GeoPhotoBlock`, one retake, reasoned skip `camera_unavailable`) is wired in the sale host behind `SrSaleKit.callPhotoRequired = false`. See contract members C1, C2. |
| SRM-A-28 outlet target | UI ready, needs contract | `OutletTarget` model (integer STD and memo figures, remaining never negative), `OutletTargetPopup` (campaign + month, Target / Achievement / Remaining STD and Memo, Okay) and `OutletTargetPage` (SKU / target / sales / %, category totals). The button opens the popup; it says "no target set" until the bundle carries data. See C3. Owner questions Q4, Q9. |
| SRM-A-29 quantity popup | done (ADS needs contract) | Tap the picture or code of a SKU: popup with the packshot and badge, -/+ , the quick buttons (n on the left, -n on the right; the manual shows no plus sign) in the entry unit (cigarette and bidi 1,5,10,20,50,100 packets; lighter 1,5,10 boxes; match 1,5,10,20 dozens), the আগের দিন / ADS / SDLW panel and সংরক্ষণ. আগের দিন and SDLW come from the outlet's sales already on the phone (EIGHT local days: SDLW is seven days back, Sale History keeps its seven), in the SKU's own unit; ADS shows N/A until C4. The inline stepper stays. Q10 default used. |
| SRM-A-30 pack badges | unchanged | The popup shows the same read-only packs badge as the list. Meaning still unverified on a device (Q6). |
| SRM-A-31 sale history "সেল ডাটা" | done | New `SaleDataScreen` (SR): outlet name, white date field with calendar, the Material date picker with every future date disabled, ISO date, SKU table, total. Opened from the call-start button and from Memo detail. The AMO keeps `SaleHistoryScreen` unchanged. The total row carries the value only (adding packets to dozens is meaningless; rule 5). |
| SRM-A-32 review | unchanged (MATCH / DEFERRED) | Discount lines stay deferred. |
| SRM-A-33 product QC | unchanged (MATCH) | |
| SRM-A-34 QC entry | done | QC stays in sticks (Q11 default) with the pack equivalent beside it ("= 2 packets + 5 sticks"; none under one packet; none for pieces). |
| SRM-A-35, 36, 37 | unchanged (MATCH) | Partial payment, confirm and print, sale saved: no change, no print golden touched. |
| SRM-A-38 outside the range | unchanged (ours richer) | |
| SRM-A-39 force-sale reason | unchanged (order differs, low value) | Not split into two steps. |
| SRM-A-40 zero sale | done locally, needs contract | After হ্যাঁ the four-reason picker (Stock Available, Outlet Closed during visit, Absence of owner, Insufficient Cash; Bangla per Q12 default). Nothing is pre-selected. The reason is kept in the file-backed draft only while the sale is open; it is **not kept after Save** (nothing in the commit or the visit close carries it) and is not synced until C5. The info card "আপনি পরে মেমো সেকশন থেকে প্রিন্ট করতে পারবেন।" already exists on Review (`review_print_later`). |

## Contract members needed for TRAIN 2 (the lead assigns the minor)

- C1 `VisitPayload` (and the visit open record): `call_photo_uuid` (UUID v4, optional) and `call_photo_skip_reason_code` (code list `call_photo_skip`, first value `camera_unavailable`); media purpose `outlet_call` (ref type `visit`).
- C2 config `cfg.sale.call_photo_required` (default true, owner Q8) in the config bundle.
- C3 bundle `outlet_targets[]`: outlet, month, campaign (free text), `std_qty_base`, `memo_count`, and per SKU `sku_id` + `target_base`; plus per-outlet achievement (STD and memo count) as of the bundle date, so the phone adds its own unsynced memos; STD base to be defined once (Q4).
- C4 bundle `outlet_sku_stats[]`: outlet, sku, `ads_hundredths` (integer, 30-day average daily sales in base units), optionally the previous-day and SDLW figures for outlets whose history is older than the 7-day local window.
- C5 `VisitClosePayload` (and memo): `zero_sale_reason_code`, code list `zero_sale_reason` = `stock_available`, `outlet_closed_during_visit`, `absence_of_owner`, `insufficient_cash` (admin-editable), mapped to `visit_outcome` so reports keep one meaning; `dw` view column.

## Owner questions still open (defaults used)

Q4 (STD base and ADS formula), Q6 (badge meaning), Q7 (amount beside the outlet name), Q9 (campaign name), Q10 (steps, default used), Q11 (QC in sticks, default used), Q12 (Bangla reason labels, default used).

## Tests

See the end of this file (filled in at the push).

## Tests (last full run, all green, lintDebug of app-sr green)

app-sr 97, app-amo 122, feature-sale 131, feature-outlet 119, feature-memo 61; 0 failures. New: `ManualCallFlowTest` (domain), `ManualCallScreensTest` (popup, zero reasons, target, QC pack equivalent; en and bn), `ManualCallStartTest` (call question, box, manual rows, photo step), `SaleDataScreenTest`. Two existing tests changed on purpose, both now asserting the call question by tag (`CallQuestionTags.QUESTION`) instead of text: `SrDayLockHostsTest.anotherCallsRefusalIsNotShownOnThisCall` (the rep stays in the valid call) and `SrRefusalHostsTest` (the day-lock check that the removed prompt does not show). No print golden or MoneyGate test changed.
Only ONE complete green run of the app suites exists at this commit (the earlier runs had the failures fixed above); the lane's "three green runs" are not yet done.


## Fix round after the Opus FAIL on 777a0ad04 (declared as the lead asked)

Fixed: SDLW now shows (8-day stats read, host test through `RoomMemoStore`); the call-start screen names the outlet, also after a relaunch (host test); the frame title is "সেল ডাটা" from both entries (host test of the callback); the day-lock tests assert `CallQuestionTags.QUESTION`; the old-date picker limit; no plus on the quick buttons; "start the call first" box has no title; colours from tokens; `askZero`/`askReason` survive recreation; the unused `StartCallPrompt` import is gone; `kit.history()` reads keep cancellation.
Stays, declared: bidi uses the cigarette steps (owner pending); the Sale History, No sale and Go back controls stay under হ্যাঁ/না; না is a no-op and the visit already exists before হ্যাঁ; the zero-sale reason is not kept after Save; `CallPhotoStep` records nothing and is off; ADS is N/A.

## Tests after the fix round

Three consecutive runs, each after cleaning the test tasks of the six modules, each running the unit tests of app-sr, app-amo, feature-sale, feature-outlet, feature-memo, core-ui and lintDebug of app-sr, feature-sale, feature-outlet, feature-memo: all three exit 0. Counts of the last run: app-sr 100, app-amo 122, feature-sale 132, feature-outlet 119, feature-memo 61, core-ui 175; 0 failures. New host tests in `SrCallStartHostTest` (outlet on the call-start screen incl. relaunch; "সেল ডাটা" frame callback; SDLW and previous day through `RoomMemoStore`, Sale History keeping seven days).


## Sunlight round (second Opus pass on 364c5596b)

Fixed: না is the kit's secondary button (paired ink and fill); সংরক্ষণ is the kit's primary button; `TokenContrastTest.callFlowButtonsAndTextHoldTheirContrastInEveryLook` checks every pair the call flow draws in the Default and Sunlight looks (the TSO night look has no call flow, so it is not checked there). The outlet pill on the call-start screen is read-only (no caret, no dropdown role; `RetailerPickerField(readOnly = true)`); the same pill on the sale entry screen still has its old caret (not touched: screenshot goldens). Unused import removed. `SrCallStartHostTest` now asserts the leave half of `onSaleData`, tells the picker label from the id fallback (`SrSaleKit.callOutletLabel`), and waits 5 s like the other host tests.
All test runs here were JVM / Robolectric unit tests in the lane container (not CI, not a device).

## Third pass (glyph ink)

Fixed: the Save and Proceed arrows were black vectors in the primary button's untinted `icon` slot (about 1.35:1 on purple in Sunlight). They now use kit glyphs drawn in the label's ink: `ButtonGlyph.Save` for সংরক্ষণ, new `ButtonGlyph.Arrow` for the two এগিয়ে যান buttons (call-start cart bar and, pre-existing, the sale entry bar shared with the AMO; the AMO's bar loses a black arrow for a white one; CORRECTION: the 20x20 glyph is under Roborazzi's 0.2% threshold, so the suite stayed green while 11 goldens were stale; they are re-recorded, see "Goldens re-recorded"). The contrast test now holds the glyph pair at 3:1 on the ramp.
**Kit finding for the lead (not changed in this lane):** the primary ramp start `#D5484A` under white is 4.33:1, below the 4.5:1 an 18 sp bold label needs (large text starts at 18.66 sp bold). The test asserts middle and end at 7:1 and leaves the start unasserted rather than lowering the bar.
Cleanup: the one-argument `callOutletLabel` is gone; KDocs say the fallback is the bare id and "n" not "+n".
`StartCallPrompt` and the strings `call_start_title` / `call_start_message` are no longer rendered by the SR app (the call-start screen replaced them); the strings and the composable stay because S6 and a feature-sale test still reference them.

## Final head: merge of INT train 100 (0b5eba27a) + third-pass fixes

Merge conflicts resolved keeping both sides: SaleRoute (S3 `printerAssist` and my `zeroSaleReasonPicker`/`quantityPopup`/`skuStats`), the sale host call (`printerAssist = true` plus my parameters; one assist holder on Sale, the visit frame does not assist), the memo host (S5's due-adjusted rows, `outletLabel` selector and manual banner kept; "সেল ডাটা" replaces the history screen and uses S5's `outletLabel`), strings of feature-sale and feature-memo (union, no duplicate names), DECISIONS.md (union; no INT line lost).
Three consecutive runs, test tasks of the eight modules cleaned before each, all exit 0 (JVM / Robolectric in the lane container, not CI, not a device): unit tests of app-sr, app-amo, feature-sale, feature-outlet, feature-memo, feature-dayclose, core-ui, ui-screenshots, and lintDebug of app-sr, feature-sale, feature-outlet, feature-memo, feature-dayclose, core-ui. Counts (tests/failures) of the last run: app-sr 114/0; app-amo 120/0; feature-sale 133/0; feature-outlet 119/0; feature-memo 72/0; feature-dayclose 59/0; core-ui 180/0; ui-screenshots 598/0.

## Goldens re-recorded (arrow ink, reason: the এগিয়ে যান arrow went from a black vector to the label's white ink)

Eleven goldens in `android/ui-screenshots/src/test/screenshots/`, recorded with `-Proborazzi.record=true` over the whole ui-screenshots suite; git shows no other file changed. A pixel diff of each against its previous version is confined to one 16 x 14 px box at the arrow of the entry bar (same size, same position per shot): `amo_sale_entry_bn`, `sale_bn_empty_cart`, `sale_bn_empty_catalog`, `sale_bn_empty_catalog_sunlight`, `sale_bn_font20`, `sale_bn_light`, `sale_bn_light_font13`, `sale_bn_long_name`, `sale_bn_packshots`, `sale_bn_sunlight`, `sale_en_light`. The call-start cart bar is not drawn by any golden.
