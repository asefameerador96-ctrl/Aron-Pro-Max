# Lane P-SR1 (android-parity-sr-1): SR parity, first half of the journey

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

(Rows for the remaining screens are added slice by slice.)

## Not run
Device walk, Android CI job (until its run id is reported), nothing on a real printer.
