# Lane P-SR1 (android-parity-sr-1): SR parity, first half of the journey

## COVERAGE TABLE (P-SR1b, 2026-11-18): every SR screen of the owner's manual and docs/ui-reference/ (Memo, Summary, Sales Submit and gate logic excluded: lane P-SR2b)

Status words: COMPARED = recorded through the production host flags and compared with the manual image (golden named); KEPT = differences kept with the reason; GOLDEN-ONLY = a golden exists from an older test frame, not yet compared with the manual; NOT YET = no golden of the production frame; OS = drawn by Android, not by us.

| Manual | Screen | Status | Golden / reason |
|---|---|---|---|
| 01, 02 | Find the APK, install | OS | Android installer |
| 03 | Login and location permission | COMPARED | srp1_login_bn_empty(_sunlight), srp1_login_en_empty (D-PAR-SR1 login row) |
| 04 | OTP, first login on a device | COMPARED | srp1_otp_bn_empty, _filled |
| 05 | Camera / audio / nearby-device permission | COMPARED (our blocked screen); OS prompts | srp1b_perm_* (D-PAR-SR1-15) |
| 06, 07, 08 | New update, download, update inside the app | COMPARED | srp1_update_bn_available, _downloading |
| 09 | Dashboard (Home) | COMPARED, KEPT (field units, flag tile, card title wrap) | home_bn_tall, home_bn_tall_target (D-PAR-SR1-06, 07) |
| 10 | Target and achievement by SKU | NOT BUILT (no SR code; needs a data source) | programme screen (D-OWNER-SR-PROGRAMMES-01) |
| 11, 12, 13 | Check-in, check-out, done | COMPARED, KEPT (coordinates not address, cancel link, darker dome) | srp1_att_* (8) |
| 14 | Stock issue | COMPARED, KEPT (row density, PackCard shared) | stock_bn_*, stock_bn_packshots |
| 15, 16, 17 | Bluetooth, pairing, connect | COMPARED (green bar; OS dialogs are not ours) | srp1b_stock_printer_connected (D-PAR-SR1-16) |
| 18 | Select the outlet | COMPARED, KEPT (search field, due chip) | picker_bn_open(_sunlight) |
| 19 | Start the call | COMPARED | srp1b_call_question_* |
| 20 | Outlet photo | COMPARED, KEPT (the camera is the OS view; our explained step before it stays) | srp1b_call_photo_step (D-PAR-SR1-21) |
| 21 | Outlet target (today's target) popup | COMPARED (lavender box, purple Okay); the full target screen (right half) is a programme screen, NOT BUILT | srp1b_outlet_target* (D-PAR-SR1-21) |
| 22 | Enter quantity | COMPARED, KEPT (ADS N/A) | srp1b_qty_* |
| 23 | Sale history (সেল ডাটা) | COMPARED, KEPT (offline card, Latin vs Bangla ISO digits, no packshots in rows) | srp1b_sale_data*, D-PAR-SR1-20 (history_bn_* are the Memo menu) |
| 24 | Review | COMPARED, KEPT (no discount table, field units) | srp1b_review_* |
| 25, 26 | Product QC (summary step) and QC entry (per-SKU step) | COMPARED, rebuilt as the two steps for the SR (D-PAR-SR1-23); AMO keeps one scroll | srp1b_qc_step1*, srp1b_qc_step2* (qc_bn_* are the AMO/one-scroll frame) |
| 27, 28, 29 | Partial payment, confirm and print, saved | COMPARED | srp1b_credit_dialog*, srp1b_dialog_* |
| 30, 31, 32 | Out of range, force reason, zero sale | COMPARED, KEPT (force gate) | srp1b_out_of_range*, srp1b_force_reason*, srp1b_zero_* |
| 33, 34 | DRP slide collection, enter slides | DEFERRED (docs/27 offers; the Slide step only exists while a SKU carries an offer; layout differs from the manual and is left until the owner lifts the deferral) | slide_bn_offer (old frame) |
| 35 | KV and POSM survey | COMPARED, KEPT (D-PAR-SR1-27): the KV step and the survey are recorded as stateless views (`ContentStepView`, `SurveyStepView`, `SurveyConfirmDialog` in feature-outlet) with a stand-in picture; the AV step shares the KV layout (platform video view, nothing to record). KEPT: the "Continue without the survey" link (the sale is never blocked), the photo box is a tap target saying "photo saved" (no thumbnail, no delete: no such logic exists) | srp1b_content_kv(_sunlight,_font20), srp1b_content_survey(_answered,_photo_asked,_sunlight,_font20,_confirm) |
| 36, 37, 38 | Find memo, edit sale, collect due | excluded (P-SR2b) | |
| 39, 40, 41 | Sales summary, sync and submit, submitted | excluded (P-SR2b) | |
| 42, 43, 44, 45 | New shop, permanent close, change information, clusters (and the outlet menu) | COMPARED, KEPT (GEO info line); the opened cluster search recorded from its stateless inside (`PickerSheet`; a text field inside a dialog WINDOW keeps the test clock from idling, so the window itself is not recorded): srp1b_cluster_picker_open, _filtered, _none (D-PAR-SR1-28) | srp1b_outlet_menu*, srp1b_req_*, srp1b_clusters* (D-PAR-SR1-17) |
| 46 | Tutorial | COMPARED (empty sentence matches) | sr_tutorial_* (D-PAR-SR1-20) |
| 47 | Tasks from the AMO | COMPARED, KEPT (Resolve link, my-requests link) | srp1b_tasks, _sunlight, _font20, _empty, _resolve_ask (tasks_bn_* are the stale frame) |
| 48, 49 | Astha menu, year and quarter | NOT BUILT (Home tile goes to ComingSoon) | programme |
| 50 | Route STD and memo target | NOT BUILT | programme |
| 51 | Shop-wise info | NOT BUILT | programme |
| 52, 53, 54 | Gift selection, redemption, confirm | NOT BUILT | programme |
| 55 | Outlet points | NOT BUILT | programme |
| 56, 57, 58 | Photo menu, Astha gift photo, campaign gift photo | NOT BUILT | programme |
| 59 | Sales Journey | COMPARED, KEPT (status dot colours) | srp1b_journey, _font20, _empty (D-PAR-SR1-18) |
| 60, 61 | KPI dashboard (monthly campaign cards), brand-wise KPI | NOT BUILT: KpiHost shows the Home KPI strip and money cards, not manual 60 | |
| 62 | Match Box Lifting Star | NOT BUILT | |
| 63 | Settings and language | COMPARED (tiles fixed, three to a row) | srp1b_settings, _sunlight, _font20 |
| 64 | Configuration and data file | NOT YET | |
| 65 | Log out | COMPARED (the submit-first warning box; the plain confirm box is the kit dialog) | srp1b_settings_logout_ask |
| ui-reference | home-1/2, stock, attendance, sale-select, outlet-menu, tasks, astha, summary | as the rows above | |


## HANDOVER P-SR1b (2026-11-18, supersedes the order below)
**Update (late 2026-11-18, batch head d60e2e2f, D-PAR-SR1-25 to 29; train 120 merged):** QC two-step fix round (B1 locked Cancel, B2 restored page; PASS at e017b2d7, pinned for train 120); the locked-sale crash fix in `SaleFlow`/`SaleViewModel`/`SaleRoute` with route-level `SrSaleLockedRouteTest` (StateRestorationTester; proven to fail on the old code); KV and POSM survey as stateless views in feature-outlet with `SrParity1bContentTests` goldens; the opened cluster search from `PickerSheet`. Method gotchas learnt: run core-ui tests too (the hardcoded-string scan takes only `//` ignore comments); a text field in a dialog window cannot be recorded; `createComposeRule` in app-sr needs `ComponentActivity` registered in the class initialiser. Review lows closed in D-PAR-SR1-29 (DraftRefusedException, beginEdit checks, handler test). WAITING FOR THE LEAD: wire the Sales-screen entry to the outlet points page only when told the programme screens landed. Remaining NOT YET rows: only the programme screens (other lanes) and the deferred DRP slide.
Lane P-SR1b did, in order, with D-PAR-SR1-11 to 15: Sale page (call question, out of range, force reason, zero sale), quantity pop-up, Review + partial-payment box + confirm/print/success boxes, permissions blocked screen. All goldens are `srp1b_*` in `android/ui-screenshots/src/test/screenshots/` via `SrParity1bSaleTests`; every existing image unchanged. Method as before (`export ANDROID_HOME=/opt/android-sdk`, record with `-Proborazzi.record=true --tests '*SrParity1bSaleTests*'`, read git status for amo_/tso_). Open for the next owner: (1) R-PAR-SR1-02 ADS consumer (data lane); (2) Force Sale shows only when `forceSaleAvailable` allows it, the old page shows both at once (flow gate, lead decides); (3) the Review keeps field-unit rows and no discount table (deferred); (4) box widths in goldens are Robolectric full width for AlertDialog, device margin not walked; (5) gotchas: a text field inside Material `AlertDialog` hangs the compose test clock (use plain `Dialog`, `focusable=false` in goldens); new core-ui options are default-off so AMO/TSO stay 0 changed (CORRECTION D-PAR-SR1-19: the out-of-range SR layout was once gated on geoPanel, which the AMO control call also passes; it is now gated on LocalSrManualLook and proved against 951292a48); the existing `ManualCallScreensTest` scrolls to সংরক্ষণ, keep the pop-up's scroll container. Nothing was walked on a device; no printer.

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

| Quantity pop-up (manual 22): blue-tinted full-width box, plain X, no field underline, pale stats panel | FIX (D-PAR-SR1-12): lavender inset box, circled red X, underlined field, amber stats panel with dark ink (7:1), wider সংরক্ষণ. KEEP: ADS reads N/A (no `outlet_sku_stats` source on INT) | this push | srp1b_qty_* (5) |

| Review (manual 24): table, Baki card, buttons | RECORD (D-PAR-SR1-14): srp1b_review_lines, _baki, _font20 match the structure. KEEP: no discount table (deferred), SKU name over unit and field-unit quantity (rule 5) | this push | srp1b_review_* |
| Partial payment (manual 27): full screen vs box over Review with live বাকিঃ | FIX: `CreditDialog` for the SR (rules and setPaid unchanged) | this push | srp1b_credit_dialog(_due, _error, _sunlight) |
| Confirm / print / success boxes (manual 28, 29) | FIX: AronManualDialog via SaveAndPrintDialogs(manual) | this push | srp1b_dialog_* (5) |

| Permissions (manual 05): OS dialogs; our blocked screen after a denial | FIX look in the SR only (D-PAR-SR1-15): purple title, gradient primary, charcoal back; AMO/TSO unchanged | this push | srp1b_perm_* (6) |
| Printer connect (manual 15-17): OS dialog, green toast | NO CHANGE: toast and flow exist (D-SR-MAN-S3-02); OS dialog not ours; no new golden | - | - |

(Rows for the remaining screens are added slice by slice.)

## Not run
Device walk, Android CI job (until its run id is reported), nothing on a real printer.
