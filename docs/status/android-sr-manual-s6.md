# Lane android-sr-manual S6 (B3 + B4): settings rows SRM-B-51..56 and glossary SRM-B-91..113

Branch `lane/android-sr-manual-s6` from INT `claude/wonderful-thompson-k6ejnf` (ab78d0d4b). Standing brief: `docs/requests/sr-manual-lane-brief.md` (lead docs branch). Build traps: `tools/android-sdk.sh`, `local.properties` (`sdk.dir=/opt/android-sdk`), Gradle project paths are `:android:<module>`, goldens `-Proborazzi.record=true --tests <class>`.

## Settings (B3)

| Row | State | What |
|---|---|---|
| SRM-B-51 | done | `SettingsContent` (feature-home) opens with the reference tile grid, two per row: language drop-down tile (current language, menu বাংলা / English), PDA টু সাপোর্ট, Update Configuration, লগ আউট. The extra rows (sun, Wi-Fi photos, printer, device status, version, app update) follow as before. Tags kept (`set_support`, `set_logout`, `set_language`). Art: the settings gear for PDA and Update Configuration; the language and log-out tiles use the kit gradient square (no exit/globe art in the repo, owner/designer to supply). AMO and TSO settings are separate screens and untouched. |
| SRM-B-52 | done, in place | New tile `onUpdateConfig` (suspend, returns `ConfigUpdate` UPDATED / ALREADY_CURRENT / FAILED). SR wires it to the bundle download (config rides the bundle; `downloadBundle` applies it in place and refreshes `DayConfig`). A download already running is waited for and its outcome reported (no second request, no false failure); 304/UNCHANGED says the phone already has the latest configuration; failure text is neutral (network or server problem, saved work safe). The busy label follows the day's `downloading` flag plus a non-saved local flag, so a rotation cannot leave the tile stuck. **The app does not close** (owner question 5 default). Bangla texts as the lead worded them. Tile label stays English "Update Configuration" in both languages. |
| SRM-B-53 | done (screen kept) | SR `SupportHost` shows the dialog সফল / ডাটা ফাইল পাঠানো হয়েছে। / ওকে once the file is confirmed sent (status Sent). New keys in core-system: `support_done_*`. The Support screen stays (tile opens it); title is "PDA টু সাপোর্ট" (SRM-B-98). AMO keeps its own English dialog (not in this lane's scope). |
| SRM-B-54 | MATCH | none |
| SRM-B-55 | done | `submitPending` + `onSalesSubmit` on `SettingsContent`; SR reads `SrDay.salesSubmitPending()` (memo or visit today, no local Sales Submit, no Final Submit lock; offline read). The warning offers "বিক্রয় জমা" (opens Sales Submit) or "তবুও লগ আউট" (goes on to the normal confirm); back or a tap outside cancels and stays in Settings. A failed read falls back to the normal confirm (CancellationException rethrown). AMO/TSO pass nothing. |
| SRM-B-56 | done | Attendance label now from `SrDay.checkoutEarliestText()` (cfg.day.checkout_earliest_time via `DayConfig`) instead of the literal "17:00". EN `review_credit_switch` is "Mark this sale as due". |
| tutorial | no row in the table | the tutorial list exists (`tutorial_bn` shot); no change needed. |

## Glossary (B4), one pass over the string files

| Row | State | What |
|---|---|---|
| 91 | MATCH | none |
| 92 | not changed | `call_start_title` stays as on INT ("কল শুরু করবেন?"): S4 (364c5596b) renders the call question itself in feature-outlet (`CallQuestionTags.QUESTION`). `call_start_title` is still used by `CallPrompts.kt` in feature-sale on that branch. |
| 93 | deferred | আজকের টার্গেট / টার্গেট: target programme (docs/27), hook only. |
| 94 | needs server | আগের দিন / ADS / SDLW need `outlet_sku_stats` (SV part A s7). Strings added with the data by the sale lane. |
| 95 | done | `out_history` (feature-outlet), `history_title` (feature-memo) and `sr_sale_history` (app-sr) say "সেল ডাটা". `sale_history` keeps "পূর্বের সেল ডাটা দেখুন" (the manual button SR-S-32). |
| 96 | not changed (lead ruling) | `edit_reason_wrong_quantity` / `edit_reason_wrong_price_type` stay as on INT: they are AMO's legacy list and relabelling changes the meaning of a stored code. The SR list is S5's new keys `edit_reason_wrong_sku_qty` / `edit_reason_promotion_issue` with new wire codes. |
| 97 | kept কারণ | Standard spelling kept (audit recommendation); the manual copied the vendor's "কারন". Lead/owner may overrule; one-line change in feature-sale values-bn. |
| 98 | done | core-system `support_title` bn "PDA টু সাপোর্ট". |
| 99 | done | see 53. |
| 100 | done | app-sr `sr_ok` and core-ui `core_ui_kit_gallery_ok` are "ওকে". "ছাপা ঠিক আছে?" (print confirmation question) is a different sentence and stays. |
| 101 | key added | `sale_submit_plain` ("জমা দিন" / "Submit") in feature-sale; the slide and edit-reason screens have no submit button today (edit-reason picks on tap), so wiring it belongs to the sale-side lane (S5). |
| 102 | not mine | cluster list/create strings are new keys: S2 (A2 + B2 outlets/clusters). |
| 103 to 107 | deferred | Astha, gifts, loyalty, target tile, নিশ্চিত করুন: programmes (docs/27, D-OWNER-SR-PROGRAMMES-01), strings come with those screens. |
| 108 | not mine | journey দূরত্ব / মোট: S7 (B7). |
| 109 | done | `kpi_visited` bn "আউটলেট ভ্রমণ". |
| 110 | not changed | খুচরা বিক্রেতা only where a screenshot shows it; `out_picker_select`, `credit_body` and the dues dialogs keep "রিটেইলার" (notes 18, 27; shared with AMO). Memo print label unchanged. |
| 111 | manual prose vs screenshot, screenshot kept | `out_req_owner` stays "দোকানের মালিকের নাম" (notes 42:12, 43:31, 44:12; AMO manual too). |
| 112 | intentional difference | The manual does not show the printed paper, so print labels (শূন্য মেমো, সংশোধিত মেমো, ক্রেডিট মেমো, দৈনিক সারাংশ) are unchanged. Layout untouched. |
| 113 | done | `submit_success` "বিক্রয় সফল ভাবে জমা হয়েছে।". `memo_paid_done` outside the manual quote, unchanged. |

## Goldens re-recorded (wording legitimately changed)
`visit_in_bn_light`, `visit_in_bn_sunlight`, `visit_out_bn_light`, `visit_out_bn_sunlight` (out_history "সেল ডাটা"), `memo_bn_light` (the sale-data link under the memo, `history_title` now "সেল ডাটা"; not kpi_visited), `review_en_light` ("Mark this sale as due"). No AMO or TSO golden changed. `settings_bn` is a record-only kit shot (no committed golden).

## Tests
No existing test edited. New in `SettingsScreenTest`: update tile confirms in place, already-current text, failure dialog, busy while a download runs, tile hidden without callback; language drop-down switches; logout warns and offers Sales Submit, back cancels, "Log out anyway" reaches the normal confirm, nothing pending skips the warning, a failed read falls back to the confirm.

## Shared-key effects that remain (AMO and TSO)
- AMO shows the changed `support_title` (app-amo `SystemScreens.kt:31`), `submit_success` (the Sales Submit success text) and the English `review_credit_switch`. TSO shows `support_title`. All match the manuals.
- AMO does not render `kpi_visited` (`KpiStripView` is only imported), `out_history` or `history_title` (AMO passes no `onPreviousSales` / `onHistory`).

## Declared differences from notes 63/64 (settings)
- Tile layout: two tiles per row, art on the gear tiles only; the language and log-out tiles use the kit gradient square (no globe/exit art in the repo). The extra rows (sun, Wi-Fi photos, printer, device status, version, app update) follow below the tiles.
- Language label: the tile shows "English" / "বাংলা" (the kit's names); the reference shows "en" / "বাংলা".
- Update Configuration: applied in place, not closed (owner question 5 default), so the dialog text differs from the vendor's.

_Not run on a device: all checks are JVM host tests, lint and Roborazzi._

## For others
- S5: wire `sale_submit_plain`; keep the edit-reason code names.
- SV: nothing needed for this lane's rows (94 needs `outlet_sku_stats`).
- Lead: Opus Bangla check on the Update Configuration Bangla body and the logout warning text.
