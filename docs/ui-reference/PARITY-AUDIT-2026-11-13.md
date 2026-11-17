# Parity audit: the existing Aron apps against our recorded screens (2026-11-13)

Why: the owner still finds many mismatches between the Apsis-made apps (his screenshots in docs/ui-reference/) and ours, and at
launch no user may feel anything changed (D-OWNER-PARITY-02, D-OWNER-REA-01: the user journey as similar as possible).
How: for each app an inventory paired every reference image with our recorded screen image(s) (android/ui-screenshots, 501 images);
each pair was compared element by element (top bar, labels and wording, fields, buttons, tiles, order, icons, pack pictures, totals);
one section per app below. Standing rules that override a screenshot were not counted (field units, 7:1 contrast, the Bangla spelling
ruling, manual corrections, zero programme discount on the memo).
Limits, plainly: this compares still images, not a walk on a phone; sample data differs; behaviour between screens, validation
messages and hidden states are not covered; a finding marked "not recorded" means we have no image of that state, which is either a
missing screen or a missing recording and has to be settled per screen. The live device walk (docs/device-lab/) covers what this cannot.

## SR app

**Summary:** 181 findings (45 high, 73 medium, 63 low). 22 matched pairs. 36 reference screens have no recorded image of ours.

### High findings

| Screen | Old app shows | Ours shows | Fix |
|---|---|---|---|
| Stock empty, Sales submit, Sales summary, Tasks, Start the call, Zero sale/visit, Memo list | Red-to-purple gradient top bar, white chevron, screen icon, centred white title | White top bar, dark arrow, dark title, no icon, '✓ ১০:৪২' chip, pastel blue-pink page on some | Use the gradient top bar with icon and centred title on every SR screen. Use a flat grey page. |
| Stock | Column and totals header 'স্টক' | 'লোড' and 'মোট লোড' | Rename to 'স্টক' and 'মোট ইস্যু / স্টক'. |
| Login | Diagonal gradient hero with indigo stripe and a large pastel logo circle | Small logo in a rectangular 'ARON SR' card | Rebuild the hero as in manual 03. |
| Target by SKU | Separate 'টার্গেট ও অ্যাচিভমেন্ট' screen with per-SKU cards (টার্গেট, অ্যাচিভ, বাকি, অর্জন, ADS, TADS, PADS, RADS) | No screen. বিস্তারিত opens Summary | Target is deferred. Add a hook screen, or stop বিস্তারিত from opening Summary. |
| Start the call | Same Sale page after picking the outlet. 'আপনি কি কল শুরু করতে চান?' with হ্যাঁ / না | Separate screen with outlet card, green banner and one navy 'কল শুরু করুন' | Keep the SR on the Sale page. Use the হ্যাঁ / না prompt. |
| Outside outlet range | Sale page with dropdown, A-Z bar, Sale History / Points, warning, 'ফোর্স সেল' and charcoal 'রিফ্রেশ' | Outlet card, amber box, map, one navy refresh button, no 'ফোর্স সেল' | Restore the Sale page layout and the 'ফোর্স সেল' button. Keep the map below it. |
| Enter quantity | Lavender pop-up with quick-step buttons, 'আগের দিন / ADS / SDLW' panel and 'সংরক্ষণ' | QuantityPopup is in code but not recorded | Record it and compare with manual 22. |
| Sale history (সেল ডাটা) | 'সেল ডাটা' screen with a date field, calendar picker and SKU rows with pack pictures | 'মেমো' screen with a fixed date list, no calendar, no pack pictures | Build the সেল ডাটা screen with the date picker and packshots. |
| Product QC | 'QC' tile page with packshots, 'QC সারাংশ' and a purple bar with 'QC জমা দিন'. A confirm dialog follows | Only the entry sheet. No tiles, no summary, no confirm dialog | Add the tile page, the summary and the confirm dialog. |
| QC entry | Table 'ফল্ট টাইপ / প্রকার / পরিমাণ'. Buttons 'সংরক্ষণ', 'বাতিল', 'ডিলিট'. Saved table with 'মোট QC নিষ্পত্তি / সেটেলমেন্ট পরিমান' | Stacked groups with steppers. Only 'বাতিল' and 'QC জমা দিন'. No saved table | Restore the table, the three buttons and the saved summary. |
| Partial payment | Modal over Review with a live 'বাকিঃ' figure and green হ্যাঁ / red না. Then a ticked 'Baki … টাকা' card | Full page, no না button, no live due, amber banner instead | Use the modal and the ticked card. |
| Collect a due | One tap 'পরিশোধিত করুন', confirm, success dialog. No amount is entered | Amount-entry screen | Use the one-tap mark-as-paid flow. |
| Confirm/print, Sale saved, Sales submitted | 'আপনি কি নিশ্চিত?' then a print question. 'সফল' success dialogs with green 'ওকে' | No dialog recorded | Record the dialogs and match them. |
| Force sale reason, Zero sale | Reason buttons 'ইন্টারনেট সমস্যা' / 'লোকেশন চেঞ্জ'. Zero-sale confirm dialog and zero Review page | Not recorded | Record both flows and compare. |
| DRP slide | Title 'স্লাইড', outlet name, route, cluster, table header, OFFER ribbon and pop-up, quick-add dialog, 'জমা দিন' | Title 'নিরীক্ষণ', no outlet lines, no ribbon, no dialog, no submit | Match manual 33 and 34. |
| KV and POSM survey | KV viewer and 'সার্ভে' page | Not found | Build it or record it. |
| Memo list | Outlet dropdown, A-Z bar, printer tab | Flat 'আজকের মেমো' list | Restore the dropdown flow. |
| Edit a sale | Reason dialog with three reasons | No dialog recorded | Record it and compare. |
| Sync and submit | Rows: আউটলেট, বিক্রয়, স্টক, কিউসি, প্রমোশন | বিক্রয় and প্রমোশন rows missing | Add বিক্রয়. Show প্রমোশন as zero (deferred). |
| Change information | Edit form with cluster, shop, owner and mobile fields and a GEO/photo button | Only an empty requests list | Record the form and match it. |
| Tasks | Swipe left to show a red 'Resolve' action | 'সমাধান করুন' button on every card | Use swipe-to-resolve. |
| KPI Dashboard, Brand-wise KPI | Full KPI pages | No screen in the code | Build them, or confirm whether they are deferred. |
| Settings: configuration | Fourth green 'Update…' tile and two success dialogs | No tile. The update is a list row | Add the tile and record the dialogs. |

### Medium findings

- **Home:** Astha, Loyalty and Match icons are the wrong pictures. There is an extra 'Match Program' tile. The issue and stock cards use the English word 'Cigarette' and show per-category lists, not one figure. The money cards are not recorded.
- **Attendance:** The card shows raw coordinates, not an address. The visit-day list is missing. The check-in sheet is not recorded.
- **Stock:** There is no purple count badge on the packshots. Rows are denser and the packshots are smaller.
- **Outlet picker:** The empty state uses a white bar and a pastel page. The opened list (dots) is not recorded.
- **Start the call:** There are no dots, no due figure, no A-Z bar and no 'Sale History' button. There is an extra in-range banner, and the guard dialog is not recorded.
- **Sale:** The top area has no dots, no due and no A-Z bar. Placeholder packshots are shown and there are no badges.
- **Sale history:** The header is charcoal and there is no lilac total band. 'দাম (৳)' is shown, not 'মূল্য'. The title is 'মেমো'.
- **Review:** There is no printer icon. Pack-size sub-rows are added. There is no discount table, and the word 'স্লাইড' is added. The QC button is flat and the print gradient is reversed. Credit banners are added.
- **QC:** The info card is blue and the title is 'প্রোডাক্ট QC'. The entry card has a placeholder pack image and the colons are missing.
- **Partial payment:** The হ্যাঁ button is grey and the field is a plain underline.
- **Outside range / Zero sale:** Amber box, navy button and pastel page. The Sale page controls are missing.
- **DRP slide:** Placeholder pack image. One card per SKU, with the stepper not in a grey box.
- **Memo / Edit / Collect due:** No print or edit buttons on the list. The table header is grey and the buttons are stacked. There is no printer icon or info card. The due box is a solid panel.
- **Sales summary:** The title is missing 'বিক্রয়'. There is no printer circle. Category rows are two lines with units, and the ম্যাচ row is missing.
- **Sync and submit:** Three extra warning boxes. The due dialog is shown inline. Owner-case cards push the table down. The enabled submit style is not recorded.
- **Tasks:** The title is 'টাস্ক ডেলিগেশন' and is left-aligned. Tabs are added. Raw codes VISIT_OUTLET and COLLECT_DUE are shown. The card layout is different.
- **Login:** Language, Sunlight and show-password controls are added. The fields are underlined. The button is pinned below the footer.
- **Not recorded:** OTP, update available, printer connect, outlet photo, new shop, permanent close, clusters, Sales Journey, Photo Capture menu, SR Settings, SR logout.
- **Change information:** White top bar.
- **Tutorial:** An extra play icon. The text is bold and sits near the top.
- **Settings and logout (AMO proxy):** The top bar is flat purple. Extra sections are added. PDA and Logout icons differ. The logout dialog has an extra body line.

### Low findings (count per screen)

Home 12, Tasks 8, Sales summary 5, Stock 4, Outlet picker 4, Attendance 3, Login/permissions 3, Deferred programmes 3, Memo/edit 3, Tutorial 3, Settings/logout 3, Sale history 2, Review 2, Outside range 2, Start the call 1, Printer/Bluetooth 1, Sale quantity 1, QC 1, Collect due 1, Sync and submit 1.

### No recorded image of ours

- `sr/outlet-menu.png`, `sr/astha.png`
- Manual: 01 install APK, 02 install complete, 04 OTP, 05 camera/audio permission, 06 update available, 07 download/update, 08 in-app update
- Manual: 15 Bluetooth on, 16 pair PIN, 17 connect printer, 20 outlet photo, 21 outlet target, 31 force sale reason, 35 KV/POSM survey
- Manual: 42 new shop, 43 permanent close, 45 clusters
- Manual: 48 Astha menu, 49 year/quarter, 50 route STD and memo target, 51 shop-wise info, 52 gift selection, 53 redemption, 54 confirm redemption, 55 outlet points
- Manual: 56 photo capture menu, 57 Astha gift photo, 58 campaign gift photo, 59 sales journey, 61 brand-wise KPI, 62 match box lifting star
- Manual: 63 settings/language, 64 configuration/data file, 65 log out

## AMO app

**Counts:** 226 findings: 40 high, 101 medium, 85 low. 11 matched pairs. 11 reference screens have no recorded image of ours.

### High findings

| Screen | Old app shows | Ours shows | Fix |
|---|---|---|---|
| Update available | Back arrow; "Network Status ● অনলাইন" at top right | No back arrow, no online indicator; "Network: Wi-Fi" line instead | Restore the back arrow and the Network Status indicator |
| In-app update prompt (07) | Own screen: illustration, mandatory-update Bangla text, "আপডেট" button, "Current version" line | Reuses the screen-05 card | Build the 07 screen |
| Dashboard home | Row 6 holds Report and Campaign Enrollment; old icon set (globe pin, LIVE badge, donut) | Report moved to row 5, no Campaign Enrollment tile; most icons differ | Restore the tile order and the Campaign Enrollment tile; swap in the old icons |
| Control call | Manual Override opens the camera directly; tiles show on the same screen under the route, outlet and alphabet bar | Extra override page with two more taps; separate menu page of green buttons with no route, outlet or alphabet bar | Open the camera directly; show the tiles in place |
| Sales route and outlet pick | Route drop-down, retailer field, alphabet bar, Sales tile | English route cards; no drop-downs, alphabet bar or tile | Rebuild as drop-downs plus the tile |
| Sale entry | Packshot with a blue qty badge; rate, discount and stock icon column; header card with shop icon, "খুচরা বিক্রেতাঃ" and "রুট:" | Grey placeholders, no badge, one stock figure; name pill only | Add packshots, the badge, the three-line column and the header card |
| Review | Crossed-out printer icon; "আপনি পরে মেমো সেকশন থেকে প্রিন্ট করতে পারবেন।" note | Neither shown | Add both |
| Paid-amount dialog | Dialog with collected-amount field, live due, হ্যাঁ/না | Not recorded | Record it and verify |
| Previous sale data | Calendar date-picker field | Previous/next arrows only | Add the date picker |
| Joint call | Route/outlet pick, LIVE badge, out-of-range warning, override and refresh, in-range tile, star-rating form | None of these recorded; older forms shown | Build or record each state |
| Team location | Google map, scooter marker, info bubble | Flat placeholder with text | Render the real map and marker |
| Team performance | Cards per zone/route with bars and "বিস্তারিত →" | Older flat list layout | Replace with the card layout |
| Task assign (EN) | Route/outlet drop-downs, alphabet bar, description box, date field | Different card-and-list form | Rebuild to match |
| SR stock list | Avatar, route line, "মোট আউটলেট" count | Name and phone only | Add the missing fields |
| Stock | Packshots with a purple count badge | Grey silhouettes, no badge | Add packshots and the badge |
| Memo | Route and outlet drop-downs, alphabet bar, memo tile, reprint of an old memo | Flat list of today's memos | Rebuild the pick flow |
| Sales summary | "মোট বিক্রয়" tab, totals table (মোট, ডিসকাউন্ট, সর্ব মোট), print, printer circle | Empty state lacks the table; data state is a per-route list with no print | Rebuild to the old single-table screen |
| Outlet requests | Separate titled lists (নতুন দোকান, স্থায়ী বন্ধ, তথ্য পরিবর্তন করুন); editable name, owner and mobile | One "অনুরোধ যাচাই" list; read-only fields | Split the lists; make the fields editable |
| New shop form | "GEO এবং ছবি ধারণ করুন" button above save | Button not visible | Show the button |
| Permanent close form | Labels above the cluster and retailer drop-downs | No labels | Add the labels |
| Change information | "GEO এবং ছবি ধারণ করুন"; gradient save with icon | "আবার ছবি তুলুন"; flat save | Restore the wording and the style |
| Base recalibration | Empty route/retailer boxes, full-width button under the form; full Google map to confirm | Pre-selected route, chips first, small corner button; no map, nudge buttons | Restore the layout and the map |
| Sales submit | Grey submit until synced, then blue; bright green sync | Both dark green | Restore the grey/blue cue |
| STD memo report (EN) | Date range plus route table | Different memo-list screen | Use the Bangla layout in EN |
| Sales till now | Bangla digits, decimals kept, memo counts | Latin digits, no decimals, memo "—" | Fix the digits, decimals and memo data |

### Medium findings

- **Login:** diagonal header, oval logo and lavender page missing; fields pre-filled, left-aligned, with an added "Show" link.
- **Update available / progress:** flat ribbon, no cloud icon; Bangla text where the old app is English; navy pill button, content packed at top; extra "Later" button; progress caption, warning box and bar style differ; no "প্রসেসিং(n/m)" caption or red tip on the 07 updating screen.
- **Target cards:** dark teal, left-aligned instead of sky blue and centred.
- **Attendance:** gradient bar with logo instead of a flat bar with centred title; checked-in message missing; day-complete labels Bangla instead of English.
- **Control call:** outlet wraps to two lines; alphabet bar incomplete with "সব"; smaller single-colour tile icons; extra "মূল্য সম্মতি" and "অন্য বিক্রেতা" items; green buttons; outlet list under the bar, 6 rows.
- **Sales:** field outlines differ; Sales tile missing on the empty state; "Sale History" now Bangla and solid green; red/purple minus/plus instead of green; force-sale reason is a floating dialog; retailer is a pill, not an info card.
- **Review / previous sale:** totals lack quantity, doubled "মোট"; previous-sale table has no column header or quantity.
- **SR performance assessment:** no shop icon, English capture lacks the card; header colours, purple checkboxes, broken brand names; POSM title and Save differ; saved dialog sits on a blank page.
- **SR call assessment:** amber stars, wrong start ratings, black titles; content wording and section titles differ; post-save context and title differ.
- **Team performance and brand detail:** "Till Date Target", "Zone:", "Item" and "Monthly Target" translated; narrow columns break figures; two brand rows absent.
- **Tasks:** "Assigned Tasks", flag label and statuses translated; "সব" chip; plain shop glyph; "General Task" translated; description is an underline field.
- **Live data:** no refresh icon; "Successful Calls" translated.
- **Stock:** red minus; flat totals panel, stray "৪" glyph in place of the save icon.
- **Memo:** detail lacks the banner, shop icon and category subtotals, with stacked buttons; no printer circle.
- **Outlet menu and requests:** icons differ; extra "আমার অনুরোধ" button; English list labels; Sub-Channel/Geo labels translated; route/cluster labels missing; extra internet banners; flat buttons without icons; dialogs edge-to-edge, purple text, nothing visible behind; extra empty-state screen; "Permanent close" title on the wrong dialog.
- **New shop:** no labels or hint; extra disabled save button; underline inputs; labels translated.
- **Permanent close / change info / base:** table layout, save pinned at the bottom; no info icon; হ্যাঁ/না order reversed; no saved dialog for close; alphabet bar cut off, "সব"; no retailer label; base chips placed before the retailer box; warning triangle missing.
- **Confirm location:** flat button; extra banner and nudge buttons.
- **Sales submit:** due-warning and success dialogs not recorded.
- **Reports and till now:** tile icons; extra "মেমো: অপেক্ষমাণ" card; "Route:" in English; CPR translated, Latin digits.
- **Sales journey:** flat page, filled radio, solid icons; "New Sale" translated plus extra statuses.
- **Settings and logout:** extra sections; icons differ; send-to-support dialog not recorded; extra logout line.

### Low findings (count per screen)

Login 3; Dashboard home 3; Target cards 3; Attendance and check-in/out 12; Control call and outlet list 6; Sales and sale entry 7; Top bar (all screens) 1; Review 1; SR call assessment 2; Team location 3; Team performance and detail 4; Tasks 4; Live data 3; SR list and lifted stock 2; Stock 1; Memo and payment dialogs 2; Outlet requests and forms 14; Base and location 4; Sales submit 3; Reports and STD 2; Sales journey and SR list 4; Settings and logout 7.

### No recorded image of ours

- 01 Find the APK and install
- 02 Installation complete
- 04 Camera and audio permission
- 10 Turn on Bluetooth and find the printer
- 11 Pair with PIN 0000
- 12 Allow Bluetooth in the app
- 13 Printer connected
- 67 Route info, STD target
- 68 Route info, memo target
- 69 Shop-wise info
- 70 Shop-wise memo target

## TSO app

**Summary:** 89 findings (6 high, 27 medium, 56 low). 23 matched pairs. 4 reference screens with no recorded image of ours.

### High findings

| Screen | What the old app shows | What ours shows | Fix |
|---|---|---|---|
| Final Submit: select the zone (23) | Five cascading dropdowns in order: Wing, Division, Territory, House, Zone, then Get Sales Data. | The "pick" screen (`tso_final_submit_pick_bn.png`) shows only one Zone dropdown, preselected "জোন ১০". `tso_fs_s23` does have all five. | Use the five-dropdown screen on every path into Final Submit. Remove the single-Zone variant. |
| Final Submit: routes of the zone (25) | Route cards that have data show a red trash/clear icon at the right. | No trash icon on any route card. | Add the red trash icon to cards with data, with the old app's clear action. |
| Drawer header (27 My Periphery; also 09 and 30, medium) | The drawer starts directly with the Dashboard row. No header block, no gear icon. | A "TSO / name" header with a gear icon and a divider above Dashboard. | Remove the header block and the gear. Move settings to a place the old app has, or hide it. |
| My Team (28) and Retailer outlets (29) | A large live street map fills the screen in a rounded card, with markers. My Team also has a fullscreen/recentre button. | No map. A brown "No network: the map is not loaded" banner and a text list instead. No map-loaded state is recorded. | Record and match the online map state, including the fullscreen button. Keep the offline list only as the no-network fallback. |
| Visit query (37) | The two SR questions use free-text fields ("Enter your answer"). Delegate uses Yes/No radios with No preselected. | `tso_visit_query_bn.png` shows the two SR questions as Yes/No radios with the first preselected. This contradicts our own `tso_mc_s37_bn.png`, which has text fields. | Keep only the text-field version. Delete the radio variant. |

### Medium findings

**Login**
- Extra "বাংলা | English" toggle and an eye icon in the Password field. The old app has neither.

**Main menu drawer (09) and My Call drawer (30)**
- Extra gear icon and profile header. This is the same defect as the high drawer row.
- 09: wrong icons for Leave, My Periphery, Target Status, My Feedback and Final Submit (route zigzag, dotted circle, radar, plain bubble, outlined document). The old app has two joined pins, a dot burst, a crosshair, a monitor with chat lines, and a filled document with an up arrow.

**Leave**
- List (19): a pinned "Apply For Leave" button cuts off the last card. Find the old app's entry point to the form and match it.
- Apply form (20), leave type (21), completed form (22): the Apply button is dull olive-green with a bold label. The old app uses a bright lime-to-green vertical gradient with a regular label and a glow. 21 also has weaker field glow.
- Completed form (22): extra "Last day: ..." helper line. The Reason box is shorter, the calendar glyph is different and the dropdown arrow is smaller.

**Quick ticket**
- Describe sheet (16): extra optional "username of the person affected" field on some issue types. Remove it.
- Quick report (15): the footer "Doesn't match? / Report a different issue" is not confirmed in our render. Verify it exists and opens Create Ticket.

**Final Submit**
- Get sales data (24): the enabled button is dull olive, not bright lime.
- Routes (25): extra "not due" / "আজ নির্ধারিত নয়" line and a "Not uploaded" pill. Remove both.
- Done (26): the Success dialog is a full-bleed band. The old app has a floating, inset, rounded card with a small right-aligned OK.

**My Team (28)**
- Extra counts line, Refresh button and a member list with last-seen, check-in and mock-GPS lines.

**Retailer outlets (29)**
- Extra info card "No connection: worked out on this phone...", a "4 outlets within 50 m" caption and a refresh button.

**My Call**
- Set plan (31): the "Show Outlet" button is dull olive with a bold label and no glow.
- Choose the route (32): the title is "Set Plan" and should be "My Visit Plan". "Select Zone" stays visible above the list when it should not.
- Select outlets (33): extra "0 of 7 selected" count and a "Select all" link. The button is dull olive. The page has no blue glow, and the cards are solid, not translucent.

**Visit query (37)**
- In English mode the two SR questions are translated. The old app shows them in Bangla in both modes.

**Target details (41, 42)**
- Extra "Show all categories" link in the card (41 Cigarette, 42 Lighter till-date).
- Bangla details shows a mixed all-categories view. The old app opens only the chosen category.

**My Feedback (43)**
- "Browse Gallery" is flat dark blue and "Save" is dull olive. The old app uses bright sky-blue and lime gradients with glow. The spacing between the buttons is tighter.
- Extra "My feedback" list screen. The old app goes straight from the menu to the create form.

**Log out dialog (44)**
- The dialog is lighter and translucent. The title is smaller. The buttons are larger, centred, darker green and brown-amber, with no glow. The old app has small, right-aligned, vivid green and orange buttons with glow.

### Low findings (count per screen)

Login 2; Dashboard top 1; Dashboard lower 2; Login & Bikroy Joma Status 1; Main menu drawer 1; Drawer with Tickets 2; Ticket dashboard 1; All tickets list 1; All tickets filtered by Resolved 2; Ticket details 2; Quick ticket describe 1; Quick report 1; Leave list 2; Leave apply form 2; Choose leave type 1; Final Submit select zone 1; Final Submit get sales data 1; Final Submit routes 2; Final Submit done 1; Drawer with My Periphery 2; My Team 1; Retailer outlets 1; Drawer with My Call 2 (one is Sunlight mode, not a mismatch); Set plan 1; Choose the route 1; Select outlets 2; My visit plan 1; Visit plan tabs 2; Planned outlet 1; Visit query 2; Assign task 3; Monthly target 2; Till-date target 1; Target details by brand 1; Target details till-date 1; Create new feedback 2; Log out dialog 3.

### No recorded image of ours

- 01-find-the-apk-file.jpg
- 02-install.jpg
- 03-installation-complete.jpg
- 04-app-icon.jpg
