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
| SRM-A-29 quantity popup | done (ADS needs contract) | Tap the picture or code of a SKU: popup with the packshot and badge, -/+ , the quick buttons (+n left, -n right) in the entry unit (cigarette and bidi 1,5,10,20,50,100 packets; lighter 1,5,10 boxes; match 1,5,10,20 dozens), the আগের দিন / ADS / SDLW panel and সংরক্ষণ. আগের দিন and SDLW come from the outlet's sales already on the phone (EIGHT local days: SDLW is seven days back, Sale History keeps its seven), in the SKU's own unit; ADS shows N/A until C4. The inline stepper stays. Q10 default used. |
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

app-sr 97, app-amo 122, feature-sale 131, feature-outlet 119, feature-memo 61; 0 failures. New: `ManualCallFlowTest` (domain), `ManualCallScreensTest` (popup, zero reasons, target, QC pack equivalent; en and bn), `ManualCallStartTest` (call question, box, manual rows, photo step), `SaleDataScreenTest`. One existing test changed on purpose: `SrDayLockHostsTest.anotherCallsRefusalIsNotShownOnThisCall` now looks for the manual's question "Do you want to start the call?" instead of the old prompt "Start the call?" (same intent: the rep stays in the valid call). No print golden or MoneyGate test changed.
Only ONE complete green run of the app suites exists at this commit (the earlier runs had the failures fixed above); the lane's "three green runs" are not yet done.


## Fix round after the Opus FAIL on 777a0ad04 (declared as the lead asked)

Fixed: SDLW now shows (8-day stats read, host test through `RoomMemoStore`); the call-start screen names the outlet, also after a relaunch (host test); the frame title is "সেল ডাটা" from both entries (host test of the callback); the day-lock tests assert `CallQuestionTags.QUESTION`; the old-date picker limit; no plus on the quick buttons; "start the call first" box has no title; colours from tokens; `askZero`/`askReason` survive recreation; the unused `StartCallPrompt` import is gone; `kit.history()` reads keep cancellation.
Stays, declared: bidi uses the cigarette steps (owner pending); the Sale History, No sale and Go back controls stay under হ্যাঁ/না; না is a no-op and the visit already exists before হ্যাঁ; the zero-sale reason is not kept after Save; `CallPhotoStep` records nothing and is off; ADS is N/A.

## Tests after the fix round

Three consecutive runs, each after cleaning the test tasks of the six modules, each running the unit tests of app-sr, app-amo, feature-sale, feature-outlet, feature-memo, core-ui and lintDebug of app-sr, feature-sale, feature-outlet, feature-memo: all three exit 0. Counts of the last run: app-sr 100, app-amo 122, feature-sale 132, feature-outlet 119, feature-memo 61, core-ui 175; 0 failures. New host tests in `SrCallStartHostTest` (outlet on the call-start screen incl. relaunch; "সেল ডাটা" frame callback; SDLW and previous day through `RoomMemoStore`, Sale History keeping seven days).
