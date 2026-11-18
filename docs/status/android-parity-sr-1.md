# Lane P-SR1 (android-parity-sr-1): SR parity, first half of the journey

## HANDOVER (written 2026-11-18, branch tip pushed; read this first)

State: branch `lane/android-parity-sr-1`, INT merged up to f5bfee39 (train 115). Gated by the lead: shell + bar, Stock, Login/OTP/update goldens, Home (+7 lows), AMO R-PAR-AMO-06/07. Pushed after the last gate (not yet gated): Attendance goldens (8, D-PAR-SR1-09), opened picker goldens (D-PAR-SR1-10). Local method that works: `export ANDROID_HOME=$HOME/android-sdk` (SDK: `bash tools/android-sdk.sh`, `echo sdk.dir=$HOME/android-sdk > local.properties`), record with `./gradlew :android:ui-screenshots:testDebugUnitTest -Proborazzi.record=true [--tests ...] -q`, verify without the flag; ALWAYS read the git status afterwards: any `amo_`/`tso_` file that changed must be explained.

Key finding for the rest of the lane: most audit rows of the "old white bar / separate start-call screen / no dots" kind come from the OLD TEST FRAMES (`Frame()` in `SrStateScreenshotTests`/`ScreenshotTests`, calls without the SR host's flags), not from `SrApp`. Production already has: `VisitCheckContent(callQuestion = ..., geoPanel = true, mapSlot = ...)` (SrSaleHosts.kt:111-125, the inline question with হ্যাঁ/না, Sale History and Points buttons), `OutletPickerContent(manualRows = true)`, `AttendanceContent(statusBoxes = true)`, `StockContent(srParity = true)`. So each remaining row is RECORD with the host's flags, compare with the manual image, then FIX the differences.

DONE by P-SR1b (D-PAR-SR1-11): slice 1 items below marked DONE in the audit table. NOT STARTED (my screens, in this order): (1) Sale page [DONE, 2026-11-18]: record `VisitCheckContent` as SrApp draws it: in range with `callQuestion` (relaunch path and with `geo`), outside range (`VisitUiState.NeedsDecision` out of range + geoPanel: check "ফোর্স সেল" + "রিফ্রেশ" and our map below), force-sale reason (`SrScreen.FORCE`, manual 31), zero sale + its Review (manual 32); compare with manual 19, 30, 31, 32. (2) Quantity pop-up `QuantityPopup` (feature-sale ui, manual 22): record, check quick steps and the আগের দিন / ADS / SDLW panel (wire to `outlet_sku_stats` only if it is on INT: grep `outlet_sku_stats` and `SkuStats.kt` in feature-sale/domain; else dashes and say so). (3) Review (manual 24: printer icon, discount table with zero, buttons), partial payment as a DIALOG over Review with live "বাকিঃ" and হ্যাঁ/না then the ticked Baki card (manual 27), confirm / print question / "সফল" dialogs (manual 28, 29), printer connect (manual 15-17; Bluetooth screens). (4) Permissions screen (manual 05) and the Sale History button target (do not route to a wrong screen; target/KPI programmes are deferred, keep hooks). Server/contract needs go in `docs/requests/android-parity-sr.md` (R-PAR-SR1-01 is the first entry).

Open questions to the lead (sent): Stock row density/packshot size (PackCard is shared with Sale and AMO; KEEP-WITH-REASON unless told otherwise).

Rules that bit me (do not repeat): a shared-kit change must be proven against the AMO/TSO goldens (my two-line title cap clipped an AMO title: cap only on the legacy path); append-only DECISIONS.md (a wrong entry is corrected by a NEW entry, D-PAR-SR1-05 corrected the text of 02); the stock head must name the figure under it ("স্টক" only when stockBase is supplied); never put a real-looking password in a fixture; the verify can fail on a fresh image only after the record; check `git status` for amo_/tso_ files.


Branch `lane/android-parity-sr-1` from INT 4e064555. Nothing here was walked on a device; the lane CI Android job is the proof.

## Shell slice (D-PAR-SR1-01)

Finding: the white top bar, "✓ ১০:৪২" chip and pastel page in the audit come from the TEST frames `Frame()` in `SrStateScreenshotTests` / `ScreenshotTests`, which used the legacy kit `AronTopBar` / `AronScreen` (glass bar, gradient page). Production SR hosts (`SrApp`) already draw `aron.AronScaffold` with the gradient bar. Fix once in the kit:

- `core-ui/Screen.kt` `AronTopBar` delegates to the gradient `aron.AronTopBar` (new optional `subtitle`), `AronScreen` paints the look's flat `page`.
- The two test frames lose the chip, so the recorded images show the production chrome.

### Callers of the legacy bar (checked in the shell slice, changed by intent)
Production: `app-amo` ControlCallHost, AmoSaleHub, AmoTaskHub, AmoReportHub, AmoTeamHub; `feature-amo` AmoAttendance; `app-tso` MainActivity (one AronScreen); SR: `feature-home` RoleHome (settings, device OTP), OnboardingScreens, FirstBundleScreen; `app-sr` OutletMapActivity. In the full re-record no `amo_*` or `tso_*` image changed (0), because the AMO and TSO frames are built on `aron.AronScaffold` / the look's own bar; the legacy path on a real AMO/TSO phone now draws that look's bar brush and page (AMO purple, TSO navy), which is the intended fix, not a recorded change.
Status chip: `KitGallery` keeps its chip in the bar on purpose (the gallery shows the chip component; it is not a shipped screen); `SellShot` is an unverified look-and-fix capture and is left as is.
Lows from the lead's gate: title is two lines then ellipsis, horizontal safe insets restored (top + horizontal, as the old bar), golden `srp1_bar_bn_long_title_font20` (D-PAR-SR1-04).

## Audit table (SR, my screens)

| Finding | Decision | Commit | Image |
|---|---|---|---|
| Gradient bar / flat grey page on every SR screen | FIX (kit) | 102753eb | stock_bn_empty etc. (38) |
| Stock heads 'লোড' / 'মোট লোড' | FIX ('স্টক', D-PAR-SR1-02) | 0e27e7ed | stock_bn_* |
| Stock: no purple badge on packshots | FIX (badge on every row) | 0e27e7ed | stock_bn_packshots |
| Stock: denser rows, smaller packshots | KEEP-WITH-REASON: PackCard is shared with Sale and AMO; the 44 dp packshot is what lets the stepper fit 360 dp; not changed in this wave | - | - |
| Login hero | RECORD: SrLoginContent already is the manual-03 hero; the golden used the generic login, now switched; empty states recorded | 3583523a | login_bn_light, srp1_login_* |
| OTP, update available/downloading | RECORD | 3583523a | srp1_otp_*, srp1_update_* |

| Home: Astha, Loyalty pictures wrong | FIX (vector redraw, D-PAR-SR1-06) | this push | home_bn_* (10) |
| Home: English 'Cigarette' in KPI/stock | RECORD: fixture data only, production uses sr_cat_*; fixtures now Bangla | this push | home_bn_tall |
| Home: issue/stock boxes per category, not one figure; sales head (প্যাকেট) not (স্টিক) | KEEP-WITH-REASON: field units rule (5) | - | - |
| Home: money cards not recorded | RECORD: home_bn_tall shows both cards, matches the reference structure | this push | home_bn_tall |
| Home: target card title wraps to 2 lines | KEEP-WITH-REASON: AronTargetCard shared with the AMO home | - | - |
| Home: extra 'Match Program' tile | KEEP-WITH-REASON: flag-driven (registry default off; fixture sets it on) | - | - |

| Attendance: card shows coordinates, not an address | KEEP-WITH-REASON (UI-SR-11, offline-first): coordinates + accuracy always; a resolved address only when online and display-only. The reverse-geocode needs the network: logged as a request | - | srp1_att_before |
| Attendance: visit-day list missing | NO CHANGE: the manual 11 shows it inside the route name ("Savar Bazar(Sat, Mon, Wed)"), which is bundle data already shown in the second line | - | srp1_att_before_font20 |
| Attendance: check-in sheet and the other states not recorded | RECORD (D-PAR-SR1-09): before, check-in sheet, checked in waiting (amber), checked in with check-out active, check-out sheet, done, failed fix, font 2.0; compared with manual 11 to 13: text, boxes, colours, half-dome hold button all match. Differences kept: a "বাতিল" link under the dome (needed to cancel), the dome gradient is darker (7:1 rule) | this push | srp1_att_* (8) |

| Outlet picker: empty state, white bar, pastel page | FIXED by the shell slice (picker_bn_empty re-recorded) | 102753eb | picker_bn_empty |
| Outlet picker: opened list (dots) not recorded | RECORD (D-PAR-SR1-10): picker_bn_open and _sunlight, drawn as SrApp draws it (manualRows = true): amber dot per row, one string per row, the due chip, the A-Z bar above. Kept on purpose: the search field "খুঁজুন" under the bar and the due chip (the vendor list is a plain overlay), medium-weight row text that wraps to 3 lines for long Bangla names | this push | picker_bn_open(_sunlight) |

| Sale call question (manual 19): fix panel pushed the question under the cart bar; "না" a text link | FIX (D-PAR-SR1-11): question first, card after; solid charcoal না | this push | srp1b_call_question_relaunch, _geo, _font20 |
| Out of range (manual 30): amber card + bottom bar, no ফোর্স সেল shown | FIX look: triangle, sentence, ফোর্স সেল + রিফ্রেশ on the page. KEEP: force appears per the flow's gate (not touched) | this push | srp1b_out_of_range(_sunlight, _refresh_only, _no_refresh) |
| Force reason (manual 31): radio form + photo at once | FIX: two gradient reason buttons first, photo and bar after the tap | this push | srp1b_force_reason, _missing_photo |
| Zero sale (manual 32): dialog look, reason list, Review | FIX dialog (triangle, blue, green/red side by side); reasons and Review RECORD (Review table is slice 3) | this push | srp1b_zero_question, _reasons, _review |

(Rows for the remaining screens are added slice by slice.)

## Not run
Device walk, Android CI job (until its run id is reported), nothing on a real printer.
