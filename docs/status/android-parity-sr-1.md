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

(Rows for the remaining screens are added slice by slice.)

## Not run
Device walk, Android CI job (until its run id is reported), nothing on a real printer.
