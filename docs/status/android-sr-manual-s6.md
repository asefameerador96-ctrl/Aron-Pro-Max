# Lane android-sr-manual S6 (B3 + B4): settings rows SRM-B-51..56 and glossary SRM-B-91..113

Branch `lane/android-sr-manual-s6` from INT `claude/wonderful-thompson-k6ejnf` (ab78d0d4b). Standing brief: `docs/requests/sr-manual-lane-brief.md` (lead docs branch). Build traps: `tools/android-sdk.sh`, `local.properties` (`sdk.dir=/opt/android-sdk`), Gradle project paths are `:android:<module>`, goldens `-Proborazzi.record=true --tests <class>`.

## Settings (B3)

| Row | State | What |
|---|---|---|
| SRM-B-51 | done | `SettingsContent` (feature-home) opens with the reference tile grid, two per row: language drop-down tile (current language, menu বাংলা / English), PDA টু সাপোর্ট, Update Configuration, লগ আউট. The extra rows (sun, Wi-Fi photos, printer, device status, version, app update) follow as before. Tags kept (`set_support`, `set_logout`, `set_language`). Art: the settings gear for PDA and Update Configuration; the language and log-out tiles use the kit gradient square (no exit/globe art in the repo, owner/designer to supply). AMO and TSO settings are separate screens and untouched. |
| SRM-B-52 | done, in place | New tile `onUpdateConfig` (suspend, returns applied or not). SR wires it to the bundle download (config rides the bundle; `downloadBundle` already applies it in place and refreshes `DayConfig`). Success dialog "Configuration updated Successfully!" + OK; offline or failed shows "Configuration not updated". **The app does not close** (owner question 5 default; nothing in the outbox is at risk). The vendor's "your app will be closed" sentence is replaced by an in-place sentence; Bangla body written by this lane, **needs the Opus Bangla check**. Tile label stays English "Update Configuration" in both languages (the manual shows it only in English). |
| SRM-B-53 | done (screen kept) | SR `SupportHost` shows the dialog সফল / ডাটা ফাইল পাঠানো হয়েছে। / ওকে once the file is confirmed sent (status Sent). New keys in core-system: `support_done_*`. The Support screen stays (tile opens it); title is "PDA টু সাপোর্ট" (SRM-B-98). AMO keeps its own English dialog (not in this lane's scope). |
| SRM-B-54 | MATCH | none |
| SRM-B-55 | done | `submitPending` + `onSalesSubmit` on `SettingsContent`; SR reads `SrDay.salesSubmitPending()` (today has a memo or visit in the outbox, no local Sales Submit, no Final Submit lock; offline read). The warning "আজকের বিক্রয় জমা দেওয়া হয়নি" offers "বিক্রয় জমা" (opens Sales Submit) or "তবুও লগ আউট" (continues to the normal confirm). AMO/TSO pass nothing, so no change there. |
| SRM-B-56 | done | Attendance label now from `SrDay.checkoutEarliestText()` (cfg.day.checkout_earliest_time via `DayConfig`) instead of the literal "17:00". EN `review_credit_switch` is "Mark this sale as due". |
| tutorial | no row in the table | the tutorial list exists (`tutorial_bn` shot); no change needed. |

## Glossary (B4), one pass over the string files

| Row | State | What |
|---|---|---|
| 91 | MATCH | none |
| 92 | done | `call_start_title` bn "আপনি কি কল শুরু করতে চান?", en "Do you want to start the call?" (body line kept). |
| 93 | deferred | আজকের টার্গেট / টার্গেট: target programme (docs/27), hook only. |
| 94 | needs server | আগের দিন / ADS / SDLW need `outlet_sku_stats` (SV part A s7). Strings added with the data by the sale lane. |
| 95 | done | `out_history` (feature-outlet), `history_title` (feature-memo) and `sr_sale_history` (app-sr) say "সেল ডাটা". The full-width button `sale_history` keeps "পূর্বের সেল ডাটা দেখুন": that is the exact manual button (SR-S-32) and the row does not list it. |
| 96 | done | `edit_reason_wrong_quantity` "ভুল SKU পরিমাণ"; `edit_reason_wrong_price_type` "প্রমোশন ইস্যু" (en "Wrong SKU quantity", "Promotion issue"). The `EditReason` code sent to the server is unchanged. |
| 97 | kept কারণ | Standard spelling kept (audit recommendation); the manual copied the vendor's "কারন". Lead/owner may overrule; one-line change in feature-sale values-bn. |
| 98 | done | core-system `support_title` bn "PDA টু সাপোর্ট". |
| 99 | done | see 53. |
| 100 | done | app-sr `sr_ok` and core-ui `core_ui_kit_gallery_ok` are "ওকে". "ছাপা ঠিক আছে?" (print confirmation question) is a different sentence and stays. |
| 101 | key added | `sale_submit_plain` ("জমা দিন" / "Submit") in feature-sale; the slide and edit-reason screens have no submit button today (edit-reason picks on tap), so wiring it belongs to the sale-side lane (S5). |
| 102 | not mine | cluster list/create strings are new keys: S2 (A2 + B2 outlets/clusters). |
| 103 to 107 | deferred | Astha, gifts, loyalty, target tile, নিশ্চিত করুন: programmes (docs/27, D-OWNER-SR-PROGRAMMES-01), strings come with those screens. |
| 108 | not mine | journey দূরত্ব / মোট: S7 (B7). |
| 109 | done | `kpi_visited` bn "আউটলেট ভ্রমণ". |
| 110 | partly | খুচরা বিক্রেতা now in `out_picker_select` and `credit_body`. The dues dialogs keep "রিটেইলার": the manual's own quoted dialog (row 27) says "রিটেইলারের". The printed memo label "খুচরা বিক্রেতাঃ" is left (print wording only where the manual shows it; it shows no such label). |
| 111 | done | `out_req_owner` "মালিকের নাম". |
| 112 | intentional difference | The manual does not show the printed paper, so print labels (শূন্য মেমো, সংশোধিত মেমো, ক্রেডিট মেমো, দৈনিক সারাংশ) are unchanged. Layout untouched. |
| 113 | done | `submit_success` "বিক্রয় সফল ভাবে জমা হয়েছে।". `memo_paid_done` outside the manual quote, unchanged. |

## Goldens re-recorded (wording legitimately changed)
`credit_bn_prompt` (credit_body), `picker_bn_empty`, `picker_bn_light`, `picker_bn_sunlight` (picker title), `visit_in_bn_light`, `visit_in_bn_sunlight`, `visit_out_bn_light`, `visit_out_bn_sunlight` (out_history "সেল ডাটা"), `memo_bn_light` (KPI "আউটলেট ভ্রমণ"/history title), `review_en_light` ("Mark this sale as due"). No AMO or TSO golden changed. `settings_bn` is a record-only kit shot (no committed golden).

## Tests
Wording assertions updated: `SaleScreensTest` ("Wrong SKU quantity"), `SrRefusalHostsTest`, `SrDayLockHostsTest` ("Do you want to start the call?"). New in `SettingsScreenTest`: update tile confirms in place; failure dialog; tile hidden without callback; logout warns, offers Sales Submit; "Log out anyway" reaches the normal confirm; nothing pending skips the warning.

## For others
- S5: wire `sale_submit_plain`; keep the edit-reason code names.
- SV: nothing needed for this lane's rows (94 needs `outlet_sku_stats`).
- Lead: Opus Bangla check on the Update Configuration Bangla body and the logout warning text.
