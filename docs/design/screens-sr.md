# Screens: SR app (Calm Glass), v1

Status: v1, refined 2026-10-07 (changes: `docs/design/CHANGELOG.md`); pending owner approval. Inputs: `docs/32` (direction), `docs/design/tokens.md` v1 (**wins on any value**), `docs/design/android-glass.md` (kit parts, states, haptics, Bangla rules), `docs/31` s1 (gates), `docs/ui-reference/sr/*.md` (what each current screen shows, `UI-SR-nn`), `docs/25-build-backlog.md` (acceptance rows `F-SR-nn`). Same functions and same information as the current Aron, restyled and re-laid-out for one hand. **Not designed (docs/27, hidden not greyed):** target and Target and Achievement card, loyalty and Astha tiles, Photo Capture (its only children are Astha and campaign gifts), outlet eligibility dots, offers and discount programmes; their hooks stay (the memo model keeps offer and DRP discount lines at zero, rows keep a zero-width leading slot). Strings quoted in Bangla are the current app's (reference) or `values-bn` keys; **new Bangla needs the native reviewer's sign-off (docs/20 role NB)**. The preview (`preview/index.html`) shows every screen below in light, dark and sunlight, tiers A, B and C, font scales 100 to 200 percent, heights 800 and 640 dp, and the states loading, empty, error, stale, printer off and long Bangla.

Reading guide: each screen has Purpose, Wireframe (y in dp on 360 x 800, gesture navigation), Primary action, States, Microcopy (BN / EN), Motion, Kit parts and paint budget. Standard states (loading skeleton after 150 ms, offline chip, empty, error with retry, Bangla long text, font scales, tiers A, B, C, sunlight) are assumed everywhere; tables list only what is specific.

## 0. Shared frame and rules

| Item | Value |
|---|---|
| Frame | **Every wireframe is 360 x 800 dp** (A06 class), side gutter `space.screen` 16, content width 328. The **D-11 baseline is 360 x 640** (Android 8.x devices are often 720 x 1280): below 700 dp window height the **compact variant** applies (rows below). Insets are read, never hard-coded: status about 28, gesture navigation 16, 3-button navigation 48 |
| Compact variant (window under 700 dp) | action bar 72 dp (primary 48); SKU rows lose the thumbnail and put unit and stock in the caption (112 dp, stepper stays on the row); Home hero is one line plus the meter, the device line is hidden, the status row stays; Attendance keeps both cards; a 640 dp window (status 28, top bar 64, compact bar 72) leaves 460 dp on gesture navigation and 428 dp with 3 buttons, about 4 compact SKU rows |
| Top bar | 64 + status inset, `TopBar` recipe (96 percent in tier B, glass with blur only on A): back 48 dp, title, status chip at the end (+ printer icon where printing exists). Title `type.title`; a long dynamic name (outlet) takes up to 2 lines at `type.heading` and then an ellipsis, the full name is on the next card; a caption subtitle may sit under it; the chip drops to its dot form from font scale 1.3 or a 3-line title |
| Action bar (`AronBottomBar`) | **A layout slot below the list**, never an overlay: 80 + nav inset (compact 72), primary 56 dp, x 16 to 344. Two actions: secondary 40 percent, primary 60 percent, 12 gap. A disabled primary shows its reason as a caption above it (height 104; reserved on Stock, Sale, Attendance and Sales submit so it never animates). **Budget: at most 25 percent of the window height** (200 dp on 800): from font scale 1.3 the bar is one column (one-line summary above a full-width primary, about 160 to 175 dp at 2.0); a secondary that does not fit becomes the last item of the scroll content as a 48 dp text button. Measured stack heights are 162, 179, 191 and 232 dp at 1.0, 1.3, 1.5 and 2.0 (not +44) |
| Free height | 612 dp between the bars on gesture navigation (800), 580 with 3-button navigation; at 640 with the compact bar 460 and 428 |
| Thumb map (right hand, 6.5 to 6.7 inch) | easy y 380 and below; stretch y 200 to 380; hard y under 200. Primary action, steppers, row taps and outlet filters live at y 380 and below (filters are a **dock above the bar**). Things used about once a day (gear, sync chip, printer chip, back) may sit in the top bar; the **sun switch** is in the top bar of Home and Login because it is also needed once per outing, and one tap further in the sync sheet |
| Status chip (`AronStatusChip`) | forms **full** (Home), **compact**, **dot** (28 dp, font scale 1.3 and up); touch target 48; opens the sync detail sheet (s19); never a modal. **Synced is quiet** (check and time in `text.secondary`, no container); containers only for offline, syncing, waiting, storage |
| Page | the baked window background; screens are transparent over it. Sunlight forces tier C and flat `bg.solid` |
| Reading order (TalkBack) | header, status chips, hero, dock, tiles, cards (`isTraversalGroup` and `traversalIndex`); one merged node per list row; sticky group heads are headings |

**Colour roles used below** (full table in tokens s2; light / dark / sunlight):

| Role | Light | Dark | Sunlight | Used for |
|---|---|---|---|---|
| `accent` / `accent.text` | `#0A58CC` / `#063B84` | `#84B6FF` / `#A8CFFF` | `#0041B3` / `#0041B3` | icons, rings, meters / text, links, text buttons |
| `accent.hi` to `accent.fill` | `#052E6B` to `#041F49` | `#C9E1FF` to `#B3D4FF` | `#002A73` | the one primary action per screen |
| `accent.container` | `#DCE9FD` | `#1B3568` | `#CFE0FF` | selected, syncing, ongoing |
| `text.primary` / `text.secondary` | `#0B1B33` / `#303D52` | `#F8FAFD` / `#C2CCDD` | `#050A14` / `#2B3648` | numbers and titles / captions |
| `success` / `.container` | `#0B7A45` / `#D6F0E1` | `#4CD38B` / `#12382A` | `#005C2E` / `#CFEBD9` | in range, done (words in `.onContainer`) |
| `warning` / `.container` | `#9A5200` / `#FFE9C7` | `#FFB84D` / `#45330F` | `#7A3E00` / `#FFE0A8` | out of range, dues, waiting, stale, overdue |
| `danger` / `.container` | `#C0182D` / `#FDDDE1` | `#FF7A8A` / `#4A1B25` | `#A30018` / `#FAD0D6` | wrong password, storage full, invalid value only |
| `offline` / `.container` | `#53627C` / `#E3E8F0` | `#9FB0CB` / `#27324A` | `#3B4658` / `#DDE2EA` | offline (never danger) |
| `surface.glass.field` / `surface.solid.raised` | `#FFFFFF` 96% / `#F2F5FA` | `#141D30` 96% / `#1C2740` | `#FFFFFF` / `#EEF1F6` | cards, rows, action bar / fields, stepper well, opaque controls |

**Money and quantity words.** One Bangla word per term, from one catalogue (UI-SR-07, D-346): gross `মোট` (Total), offer discount `ডিসকাউন্ট` (Discount), DRP `স্লাইড` (Slide), QC deduction `QC`, net `সর্বমোট` (Grand total). Rules: gross and net always shown; every other component only when non-zero; deductions use a true minus `−` and their word, never colour alone; two decimals; **`৳` after the amount at 0.8 em with a 14 sp floor, joined by a no-break space** (tokens s7.1), the `.00` of large money at 0.75 em; in **dense columns** (Review lines, Memo table) the per-row sign is dropped and `(৳)` sits in the column header, **every total keeps its sign**; units `স্টিক` (stick), `পিস` (piece), `ডজন` (dozen) beside every quantity and never summed across units (UI-SR-10, 15, 36). **Numbers never wrap or clip** (tokens s7.3): single line, capped at 1.5 times, shrunk to fit, label above value from font scale 1.3. Numerals in `type.numeral` or `type.display`, right-aligned in columns. Cross-fade 150 ms when a number changes (none while a stepper repeats), no count-up.

**Reflow at large text.** Font scale 1.3: hero, totals and bar summaries stack label over value; bars become one column; 2 tile columns; dialogs stack their buttons; the Sale and Stock filter dock moves into the list. 1.5: the segmented control becomes vertical radio rows; steppers go on their own line; tables reflow to a name line over "পরিমাণ x দাম = মূল্য"; step labels show only the current one. 1.9: one tile column. Large-text roles (title, heading, display, numeral) stop growing at 1.5. Golden screenshots at 1.0, 1.3 and 2.0 (Home also 1.15) for Home, Sale, Review, Visit, Summary and Sales submit.

**Motion defaults** (tokens s8): forward screen change = translation 8 dp from the end plus a `ModulateAlpha` fade 220 ms `ease.out` (never the default `Crossfade`, which would render offscreen), back = 180 ms `ease.in`; press scale 0.96 tile, 0.97 button, 0.92 stepper over 90 ms; sheets `motion.sheet` spring as a translation; shared element only outlet row to outlet card (220 ms); reduced motion and tier C: 120 ms fade, no transforms. **Haptics** (android-glass s8): one per action: `Confirm` on save, check-in, commit, printed; `Reject` on geofence fail, validation, print failed; `VirtualKey` on stepper tap; none on scrolling, tiles, rows, connectivity.

**Wireframe legend.** `( )` chip, `[ ]` button or field, `[ ]*` the one primary, `< >` icon, `▕██░░▏` meter, `...` scrolls, `~` flat list row (no shadow).

## 1. Login (F-SR-001, 002, 003)

**Purpose.** Authenticate once per phone, bind the phone with the TSO code if new, load the first bundle. Works offline for a previously bound phone (F-SR-001).

```
y  28- 76  (বাংলা | EN) language toggle  <☀ 48 sun switch>               [no title bar]
y 120-200  mark 72 dp (accent.container squircle, redrawn Aron glyph; artwork rule Q-UI-13) + "ARON SR" type.title, centred
y 264-492  FIELD CARD 328 (radius.card 20, padding 20)
             ইউজারনেম             label type.caption
             [ sr334001                      ]  56 dp, radius.chip, border.input
             পাসওয়ার্ড
             [ ••••••••                 দেখান ]  56 dp, trailing text button 48
             (error banner slot, expands base)
y 640-700  সংস্করণ 1.0.25 · © AKTCL        type.caption, text.secondary, centred
y 704-800  BAR  [ লগইন করুন ]*  rides above the keyboard
```

**Primary action.** "লগইন করুন" in the bar; IME action Next then Done (Done submits). Disabled until both fields are non-empty (reason "ইউজারনেম ও পাসওয়ার্ড দিন।").

**Layout notes.** Reps log in outdoors, so the **sun switch** (48 dp, `Role.Switch`, "রোদ মোড", `ToggleOn` haptic) sits at the end of the top row beside the language toggle; it works before sign-in and is stored per device. Username: ASCII keyboard, no auto-capitalise, autofill hint `username`, **not pre-filled** (shared phones). Password: show or hide toggle (new; reduces typos on a Bangla keyboard). The brand block collapses when free height is under 520 dp (keyboard open). Footer carries AKTCL branding, not the vendor credit (F-SR-001).

| State | What the user sees | Behaviour |
|---|---|---|
| Busy | button label stays, 20 dp arc, fields read-only | debounce 400 ms; no spinner overlay |
| Wrong credentials | `danger.container` banner, both field borders `danger`, one message that never says which was wrong | `Reject` haptic; focus to password |
| Locked, rate limited | `warning.container` banner with the minutes (plurals) | button disabled with the countdown caption |
| Offline, never online on this phone | `offline.container` banner, the only blocking case | login impossible without network once |
| Offline, bound phone | no banner; works within 7 days of the last online login | the three offline failure texts (stale, wrong password, clock) appear as the same banner |
| Device not bound | card swaps to the OTP step (below) | `auth_error_bind_required` text above it |
| Update required | `warning.container` banner, button "আপডেট" opens the update page (s20) | blocks a new login, never an open offline day |
| First bundle | card shows a 6 dp meter, percent, one line | resumable; Back is ignored; ends with a 220 ms cross-fade to Home |
| Long Bangla | banners wrap, card grows (`heightIn`), never clipped at 2.0 | |

**OTP step (F-SR-002).** Same card: title "ডিভাইস যাচাই", hint, four boxes 56 x 64 dp with 12 gaps (width 260, centred), numeric keypad, one hidden text field behind them so paste, SMS autofill and TalkBack see a single field. Bar primary "যাচাই করুন" enabled at 4 digits. Wrong, expired and too-many-attempts texts replace the hint inside a `danger.container` line.

**Permission rationale (F-SR-003, before each system prompt, never microphone).** `AronBottomSheet`: 40 dp icon in `accent.container`, title, one line, primary "অনুমতি দিন", text button "পরে". Denied location later shows the Permission empty state with "সেটিংসে যান".

| Key | BN | EN |
|---|---|---|
| title / button | লগইন / লগইন করুন | Login / Log in |
| fields | ইউজারনেম, পাসওয়ার্ড (দেখান, লুকান) | Username, Password (Show, Hide) |
| busy | লগইন হচ্ছে… | Logging in… |
| wrong | ইউজারনেম বা পাসওয়ার্ড ভুল। | Wrong username or password. |
| OTP | আপনার TSO-র দেওয়া ৪ সংখ্যার কোড লিখুন। | Enter the 4-digit code from your TSO. |
| OTP wrong | কোডটি ঠিক নয়। আবার দিন। | That code is not right. Try again. |
| OTP expired | কোডের মেয়াদ শেষ। TSO-র কাছ থেকে নতুন কোড নিন। | This code has expired. Ask your TSO for a new one. |
| bundle | আজকের তথ্য নামানো হচ্ছে… ইন্টারনেট চলে গেলে এখান থেকেই আবার শুরু হবে। | Loading today's route… If the signal drops it continues from here. |
| location rationale | চেক ইন বা আউটলেট খোলার সময় একবার লোকেশন নেওয়া হয়। | Location is read once when you check in or open an outlet. |
| camera / Bluetooth | আউটলেটের ছবি তুলতে ক্যামেরা লাগে। / প্রিন্টারে মেমো ছাপাতে কাছের ডিভাইসের অনুমতি লাগে। | The camera is for outlet photos. / Nearby devices lets Aron print memos. |

**Motion.** Card enters 220 ms (translation plus `ModulateAlpha`); error banner expands over `motion.base`; OTP box fills with a 90 ms press scale. **Kit.** window background, `GlassSurface(Field)`, `AronPrimaryButton`, `LanguageToggle`, sun `AronSwitch`, `AronTextField`, `AronBanner`. Paint: 1 shadowed card, 0 lists.

## 2. Home (F-SR-008 to 010, 063, 064, 065, 069, 072)

**Purpose.** One look answers "how is my day going", one tap reaches every tool, and the dock offers the next step. Reads Room only, so it opens at once offline.

```
y  28- 92  HEADER (collapsing)  <○ 40>  SR - Testing Banani (sr334001)   <☀ 48> <⚙ 48>
                                         Apsis RouteDaily
                                         2026-10-05 · Daily             (route and date on separate lines, no-break hyphens)
y  92-140  (✓ সিঙ্ক হয়েছে ১০:৪২)  status row; quiet when synced, containers only for exceptions
y 148-292  HERO FIELD CARD  আজকের সর্বমোট                                    ›
                       ৪,৩৯১.০০ ৳                    type.display 34/48
                       আউটলেট ভ্রমণ  ▕██░░░░░░░░░░▏  ৬/৬০
                       ✓ চেক ইন ০৮:৫২ · Daily
y 304-    আজকের কাজ   (group label, type.label, 20 above)
          TILES 3 x 2   <অ্যাটেনডেন্স> <স্টক> <বিক্রয় ◯ring>   101 x 96, gap 12
                        <মেমো> <সারসংক্ষেপ> <বিক্রয় জমা>
          অন্যান্য
          TILES 3 x 2   <আউটলেট> <টিউটোরিয়াল> <টাস্ক ডেলিগেশন ②>   101 x 84, 36 dp wells
                        <বিক্রয় যাত্রা> <কেপিআই>
- - - below the fold - - -
          KPI ROW  (স্ট্রাইক রেট ১০%) (নন ভিজিট ৫৪) (বিক্রি নেই ০)       3 x field cards
          STOCK CARD   স্টক:  ইস্যু | বর্তমান   লাইটার ৪০০ পিস | ২৫ পিস   ম্যাচ ৪০০ ডজন | ৩৯৪ ডজন
          MONEY CARD   আজকের বিক্রয়: ম্যাচ ১৪১.০০ · লাইটার ৪,৬৮৭.৫০ → মোট ৪,৮২৮.৫০ → সর্বমোট ৪,৩৯১.০০ ৳
          DEVICE LINE  ব্যাটারি ৬২% · খালি জায়গা ১.২ জিবি · অপেক্ষায় ৩ · শেষ সিঙ্ক ১০:৪২
y 704-800  DOCK  [ বিক্রয় শুরু করুন ]*   (glyph of its target tile, which carries a 2 dp accent ring)
```

**Primary action: the next-step dock** (shortcut only; every target stays a tile; from local day state; a design default):

| Local day state | Dock label (BN / EN) | Opens |
|---|---|---|
| not checked in | চেক ইন / Check in | Attendance (a prompt, not a block: Q-UI-04) |
| checked in, no stock issued today | স্টক নিন / Take stock | Stock |
| in the field | বিক্রয় শুরু করুন / Start selling | Route list |
| route finished or after `cfg.day.checkout_earliest_time`, not submitted | বিক্রয় জমা / Sales submit | Sales Submit |
| submitted, not checked out, after 17:00 | চেক আউট / Check out | Attendance |
| day complete | no dock; hero shows a `success` chip "আজকের কাজ সম্পন্ন" | |

**Layout notes.**
- **Header** keeps `SR - <name> (<username>)` verbatim (F-SR-008, `type.heading`, up to 2 lines), caption lines `<route name (visit days)>` and `<ISO date> · <route kind>` (route kind `Daily`, `3F`, `2F` is AKTCL's wording, kept in English), a generic initial avatar (dropped from font scale 1.3 to give the title room), the **sun switch** (48 dp, between the title and the gear) and the gear 48 for Settings (s14). With two or more routes the subtitle gets a drop-down arrow and opens a route sheet (s20, F-SR-065). Collapses over the first 88 dp of scroll into a 64 dp bar with name, chip and gear.
- **Sunlight suggestion.** When the system brightness setting is 85 percent or more (read once on resume, no light sensor, no polling; adaptive brightness may under-report, so it is a hint only, verify on the three phones) a dismissible Info banner "রোদে পড়তে অসুবিধা হলে রোদ মোড চালু করুন [চালু করুন]" shows once.
- **Hero** is the whole-card tap target to Summary; the meter has a spoken value ("৬০টির মধ্যে ৬টি আউটলেট ভ্রমণ হয়েছে"), fill `accent`, value also printed. Strike rate is successful calls over the day's target outlets (6 of 60 is 10 percent, UI-SR-09). The check-in time and route kind sit in its meta line.
- **Tiles** in two labelled groups, **reference order kept**: "আজকের কাজ" (Attendance, Stock, Sale, Memo, Summary, Sales Submit) at 101 x 96 with 44 dp squircle wells, and "অন্যান্য" (Outlet, Tutorial, Task Delegation, Sales Journey, KPI) at 101 x 84 with 36 dp wells; the tile that the dock opens carries a 2 dp accent ring. Tiles are glass (62 percent) with an opaque label plate and no shadow; the reference grid had 4 columns, 3 is the 360 dp design (a one-time dismissible tip "মেনু এখন ৩ কলামে" for the first week). Sales Journey and KPI open the assumed views (s17, s18). The task badge is the open count in `accent.hi`, cap `৯৯+`. Columns: 3 below font scale 1.3, 2 from 1.3, 1 from 1.9.
- **KPI, stock and money cards** are field cards; the reference's double "total" cards are merged into one money card with canonical words (reference `সর্ব মোট` gross is now `মোট`, `মোট টাকা` is now `সর্বমোট`). Sticks sold, pieces and dozens are separate quantity lines, never added. Zero categories and zero deductions are hidden.
- **Device line** (F-SR-064) is caption text, no card. Warning (`warning` icon and word) under 20 percent battery or under 500 MB free; **danger only under 200 MB** ("ফোনে জায়গা কম", the one danger case). Battery and free space are read on `ON_RESUME` only, no receiver or timer (docs/04).
- **Composition rules.** HomeUiState is split into independent `@Immutable` sections (header, hero, kpis, stock, money, device) collected separately, so a battery change does not recompose 14 tiles; the collapse offset is read only in layout or draw lambdas; tile height is fixed (96 and 84) below font scale 1.3.

| State | What the user sees | Behaviour |
|---|---|---|
| Loading | header and tiles at once (local); hero and KPI skeleton only if Room takes over 150 ms | no spinner |
| No route today | info banner "আজকের জন্য কোনো রুট নির্ধারিত নেই"; tiles that do not need a route stay | hero shows `০.০০ ৳` |
| Bundle 1 day old | `warning` banner with the Dhaka time of the last bundle | selling continues, rows flagged stale (F-SR-063) |
| Bundle 3 days old | `warning` banner; Sale tile opens memos and dues read-only | check-in still works |
| Login expired, update required | `warning` banner with the existing keys (`home_reauth_required`, `home_update_required`) | never blocks an open day |
| Shared phone | first capture of a date with two bound users opens a dialog "আপনি কি <নাম> (<কোড>)?" (F-SR-072) | the other answer stores an acting-for id |
| Stats fail | the card shows "পরিসংখ্যান দেখানো যাচ্ছে না" and retry | tiles unaffected |
| Long Bangla, font 1.3 to 2.0 | tile labels wrap at spaces, 2 or 1 columns, header wraps, hero stacks | no clipping |

| Key | BN | EN |
|---|---|---|
| hero / meter | আজকের সর্বমোট / আউটলেট ভ্রমণ | Today's total / Outlets visited |
| groups | আজকের কাজ, অন্যান্য | Today's work, Others |
| chips | চেক ইন বাকি, চেক ইন ০৮:৫২ | Check-in due, Checked in 08:52 |
| sun | রোদ মোড; রোদে পড়তে অসুবিধা হলে রোদ মোড চালু করুন | Sunlight mode; Hard to read in the sun? Turn on sunlight mode |
| KPI | স্ট্রাইক রেট, নন ভিজিট, বিক্রি নেই | Strike rate, Non-visit, No sale |
| stock card | স্টক, ইস্যু, বর্তমান স্টক | Stock, Issue, Current stock |
| money card | আজকের বিক্রয়, মোট, ডিসকাউন্ট, QC, সর্বমোট | Today's sales, Total, Discount, QC, Grand total |
| stale | রুটের তথ্য ১ দিন আগের (শেষ হালনাগাদ গতকাল ১৮:৩০)। বিক্রয় চলবে। | Route data is 1 day old (updated yesterday 18:30). Selling continues. |

**Motion.** Header collapse follows the finger (layout or draw phase only). Tile press 0.96. Dock label cross-fades 150 ms when the day state changes. **Kit.** `LargeHeader` (screen composable), `AronStatusChip(full)`, `AronTileGrid`, `AronTile`, `GlassSurface(Field)` x4, `AronBottomBar`, `AronSwitch`. Paint: 4 shadowed cards, 11 tiles and 3 KPI cards shadowless (within the 8 and 16 caps).

## 3. Attendance (F-SR-011, 012)

**Purpose.** Check in before work, check out from 17:00 Dhaka time, with one location reading each time.

```
y  28- 92  TOP BAR  <←> অ্যাটেনডেন্স                                  (✓ ১০:৪২)
y  92-120  SR - Testing Banani (sr334001) · Apsis RouteDaily, 2026-10-05      (caption subtitle: the identity card is gone)
y 128-276  LOCATION CARD  <📍> আপনার বর্তমান লোকেশন                    <⟳ 48>
                          ২৩.৭৯২৫৮, ৯০.৪০৭৮৩            (offline, always)
                          RC38+J3Q, Gulshan, Dhaka        (online, second line, display only)
                          (✓ GPS ±১৮ মি · ভালো)
y 288-412  DAY CARD   (✓) চেক ইন সম্পন্ন হয়েছে  ০৮:৫২
                      (○) চেক আউট  বিকাল ৫টার পরে চালু হবে
y 636-800  MESSAGE (caption above the bar)  আপনি এখনো চেক ইন করেননি। কাজ শুরু করার আগে অনুগ্রহ করে চেক ইন করুন।
           BAR  [ চেক ইন ]*       (disabled: the reason caption above)
```

**Primary action.** One primary in the bar that follows the day card: "চেক ইন" then "চেক আউট". Tap opens the hold sheet (below), never commits on tap (parity SR-S-13/14). The reference message is **shown once, as the caption above the bar** (it was repeated in a message card and in the day card).

**Hold sheet** (`AronBottomSheet`, solid; no blur in the SR flavour): title "চেক ইন করা হচ্ছে" with a `<login>` icon (`<logout>` for check-out), time chip "বিকাল ০৪:৪৭" from the corrected Dhaka clock, one line "লোকেশন ±১৮ মি", then `AronPressAndHoldButton` 72 dp tall, full width, track `accent.container`, fill `accent.hi` sweeping over **1,200 ms** (`holdMillis`), label "চাপ দিয়ে ধরে রাখুন" (legible on the track and on the fill). Early release: fill retreats in 150 ms, caption "আরও একটু ধরে রাখুন". TalkBack and Enter confirm directly. Reduced motion keeps the fill (progress). On commit: `Confirm` haptic, sheet closes over 150 ms, the day card row flips to done with a 150 ms cross-fade.

| State | Day card and bar | Notes |
|---|---|---|
| A not checked in | rows: check-in open, check-out dim; bar "চেক ইন" | reference message above the bar |
| C checked in, before 17:00 | check-in done with time; check-out row "চেক আউট বিকাল ৫টার পরে চালু হবে"; bar disabled, reason "বিকাল ৫টার পরে চালু হবে" | enabling time is `cfg.day.checkout_earliest_time`, on corrected Dhaka time (F-SR-012) |
| D check-out open | bar "চেক আউট" | message "আপনি এখনো চেক আউট করেননি। আজকের কাজ শেষ করার আগে অনুগ্রহ করে চেক আউট করুন।" |
| F day complete | both rows done, bar hidden, `success` banner | "আপনি আজকের জন্য চেক ইন এবং চেক আউট সম্পন্ন করেছেন। সহযোগিতার জন্য ধন্যবাদ।" |
| Reading fix | location card arc "লোকেশন নেওয়া হচ্ছে…", up to `cfg.geo.fix_timeout_s` (15 s) | refresh icon disabled while reading |
| Fix quality | chip: at most 30 m `success` "ভালো"; 31 to 100 m `warning` "মোটামুটি"; over 100 m `warning` "দুর্বল" (design defaults; over `cfg.geo.max_accuracy_m` it cannot be geo-valid) | word plus icon plus number |
| No fix after timeout | `warning` banner "লোকেশন পাওয়া যায়নি" plus retry | check-in stays possible, recorded `no_fix` (Q-SD-01) |
| Location off or denied | `warning` card with "লোকেশন চালু করুন" or "সেটিংসে যান" | system settings |
| Mock location | `warning` line "নকল লোকেশন (Fake GPS) পাওয়া গেছে। এটি বন্ধ করুন।" | stored as the mock flag; never valid |
| Offline | coordinates only, address resolved later when online | chip only |

| Key | BN | EN |
|---|---|---|
| title, buttons | অ্যাটেনডেন্স, চেক ইন, চেক আউট | Attendance, Check in, Check out |
| not in | আপনি এখনো চেক ইন করেননি। কাজ শুরু করার আগে অনুগ্রহ করে চেক ইন করুন। | You have not checked in yet. Please check in before you start work. |
| done rows | চেক ইন সম্পন্ন হয়েছে, চেক আউট সম্পন্ন হয়েছে | Check-in completed, Check-out completed |
| later | চেক আউট বিকাল ৫টার পরে চালু হবে | Check-out opens after 5 PM |
| out | আপনি এখনো চেক আউট করেননি। আজকের কাজ শেষ করার আগে অনুগ্রহ করে চেক আউট করুন। | You have not checked out yet. Please check out when today's work is done. |
| day complete | আপনি আজকের জন্য চেক ইন এবং চেক আউট সম্পন্ন করেছেন। সহযোগিতার জন্য ধন্যবাদ। | You have checked in and out for today. Thank you. |
| sheet | চেক ইন করা হচ্ছে, চেক আউট করা হচ্ছে, চাপ দিয়ে ধরে রাখুন | Checking in, Checking out, Press and hold |
| fix | লোকেশন নেওয়া হচ্ছে…, লোকেশন পাওয়া যায়নি, ভালো, মোটামুটি, দুর্বল | Getting your location…, Location not found, Good, Fair, Weak |
| mock | নকল লোকেশন (Fake GPS) পাওয়া গেছে। এটি বন্ধ করুন। | Mock location found. Turn it off. |

**Motion.** Refresh icon rotates once per read (functional, 1,200 ms linear while reading). **Kit.** `GlassSurface(Field)` x2 (about 150 dp shorter than the v1 draft's four cards), `AronStatusChip`, `AronBottomSheet`, `AronPressAndHoldButton`, `AronBottomBar`. Paint: 2 shadowed cards.

