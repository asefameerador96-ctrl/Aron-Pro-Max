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

## 4. Stock (F-SR-013, 014, 015, 050, 081)

**Purpose.** Record what the distributor issued today per SKU, see category totals, save, and print the slip that is handed to the distributor's manager.

```
y  28- 92  TOP BAR  <←> স্টক                       (✓ ১০:৪২) <🖨 36 warning disc, slashed>   (the one screen where print is required)
y 100-140  COLUMN HEAD  এসকেইউ            ইস্যু (এখন যোগ হবে)           স্টক
y 148-...  ~ GROUP  লাইটার · পিস                                         (flat field rows)
           ~ ROW 136:  [thumb 48]<৪০০ badge>  Aster                      ৩৯৪   (derived stock, type.numeral)
                                              আজ লোড ৪০০ পিস              স্টক · পিস
                                           ( −)[ ০ ](＋) পিস              (stepper at the trailing edge)
           ~ ROW ...   (about 40 SKUs today, designed for 60)
y 640-704  CATEGORY DOCK  (লাইটার ৪০০ পিস) (ম্যাচ ৪০০ ডজন) (সিগারেট ০ স্টিক)   tap: sheet; hides on scroll down
y 704-800  BAR  [ প্রিন্ট ]   [ সংরক্ষণ ]*
```

**Primary action.** "সংরক্ষণ" (60 percent) in the bar, "প্রিন্ট" (40 percent, secondary with the printer icon). Save is enabled only when an entered increment is non-zero; otherwise disabled with reason "কোনো পরিবর্তন নেই". Print is enabled only when saved and the printer is connected (F-SR-013); otherwise reason "আগে সংরক্ষণ করুন" or "প্রিন্টার কানেক্ট করুন". Save never waits for the printer (Q-UI-03 default). Stock is the only screen whose printer icon is the **`warning` disc** (print is required to hand the slip over); elsewhere it is a neutral `text.secondary` icon, `warning` again only after a failed print.

**Row (flat `surface.glass.field`, 136 dp, no shadow).** Line 1: 48 dp thumbnail with a 24 dp `accent.container` pill at its bottom end showing the **packs equivalent** (quantity over pack size; Q-UI-11 default; TalkBack "৬৫০ প্যাক"); SKU code `type.bodyStrong`; caption "আজ লোড ৪০০ পিস" (read-only total loaded today); at the end the derived stock `type.numeral` over its unit. Line 2: `AronStepper` for the increment at the trailing edge: step is the pack size for sticks (10 or 20), 1 for pieces and dozens; tap the value to type; hold accelerates and writes the draft once on release; value 0 after a save. Group headers (সিগারেট, বিড়ি, লাইটার, ম্যাচ) carry the unit once. The category dock opens `AronBottomSheet` with the full table (ক্যাটাগরি, মোট ইস্যু, স্টক per category, each with its unit); from font scale 1.3 it becomes a text button at the top of the list. A top-bar overflow item "মোট সংশোধন" (F-SR-081) opens a reason sheet and posts a signed adjustment, never an overwrite. Each row is one TalkBack node ("Aster, লাইটার, আজ লোড ৪০০ পিস, স্টক ২৫ পিস, ইস্যু ০ পিস, ৬৫০ প্যাক") with the increase, decrease and type actions.

| State | What the user sees | Behaviour |
|---|---|---|
| Saved | top `success` banner "সংরক্ষিত হয়েছে। স্লিপ এখনো প্রিন্ট হয়নি।" with text action "প্রিন্ট"; `Confirm` haptic; steppers reset to 0 | same-values re-save within the guard window (120 s) shows "এইমাত্র সংরক্ষণ হয়েছে" and does nothing |
| Printer not connected | the top-bar icon on its `warning` disc with a diagonal line; tap reconnects | banner "প্রিন্টার কানেক্ট করা হয়েছে" on success; permission rationale if Nearby devices is denied |
| Printing | secondary shows the arc, bar inert except back | success: slip flagged printed; failure: `warning` banner "প্রিন্ট হয়নি" with "আবার চেষ্টা" (stock saved either way) |
| Unprinted at day end | a `warning` caption on Sales Submit, not a block (`cfg.stock.require_printed_slip`, Q-UI-03) | |
| Typed value invalid | well `danger.container` plus message, not committed | `Reject` haptic |
| Empty (no SKUs in the zone plan) | `AronEmptyState` "আপনার জোনের জন্য কোনো SKU নেই" with synced-at | |
| Compact height | thumbnail hidden, "· স্টক ৩৯৪" in the caption, stepper beside the name (112 dp) | |

| Key | BN | EN |
|---|---|---|
| columns | এসকেইউ, ইস্যু, স্টক | SKU, Issue, Stock |
| totals | ক্যাটাগরি, মোট ইস্যু, স্টক | Category, Total issue, Stock |
| actions | সংরক্ষণ, প্রিন্ট | Save, Print |
| helper | আজ লোড | Loaded today |

**Motion.** Stepper press 0.92 and `VirtualKey` haptic; hold repeats 6, 15, 30 steps per second (android-glass 6.5), haptic at most every 150 ms; sheet spring. **Kit.** `AronListRow` (flat, `RowPosition`), `AronStepper`, `AronFilterDock`, `AronBottomSheet`, `AronBanner`. Paint: rows flat, 0 shadowed cards; 60 rows scroll inside the frame gate.

## 5. Route outlet list (Sale, step 1; F-SR-016, 074, 057)

**Purpose.** Pick the outlet to sell to from today's route. The old drop-down becomes a full list, because 40 to 200 outlets are easier to scan and tap than to open a picker. Programme dots are removed (docs/27); the row keeps a 0 dp leading slot.

```
y  28- 92  TOP BAR  <←> বিক্রয়                                           (✓ ১০:৪২)
y 100-180  রিটেইলার নির্বাচন করুন            ৬/৬০ ভিজিট হয়েছে ▕█░░░░░░░▏
           [✓ বাকি ৫৪][ ভিজিট হয়েছে ৬ ][ সব ৬০ ]            segmented, 48 dp, weighted segments, thumb follows bounds
y 188-...  ~ ROW 72+:  বনানী স্টোর                                  ভিজিট হয়েছে · ৮৪.০০ ৳ ✓
                       DHK-344-003 · 01711000000 · Apsis Cluster     (বাকি ২৯১.৫০ ৳)
           ~ ROW ...    (flat rows, one rounded group per letter section)
y 656-704  ALPHABET DOCK  <🔍 48> (সব) (A) (B) (E) (J) (M) (ক) (খ) (#)  chips 40 visual in 48 dp hit area, 48 pitch, scrolls sideways
y 704-800  (system navigation inset only; no primary: tapping a row is the action)
```

**Primary action.** Tapping a row (full width, 72 dp or more). The filters sit in the dock at y 656 to 704, in thumb reach.

**Layout notes.** Rows: name `type.bodyStrong` up to 2 lines; caption "code · phone · cluster" (phone 11 digits normalised, ASCII, visible to this route's SR only, UI-SR-24; closed outlets hidden; the code uses non-breaking hyphens) up to 2 lines. Trailing: visited rows show "ভিজিট হয়েছে" in `success.onContainer` with a check and the memo total; skip outcomes show their word ("বন্ধ", "মালিক নেই"); a due shows a `warning.container` chip "বাকি ২৯১.৫০ ৳". **Segmented control (android-glass 6.15):** 48 dp, selected = `accent.container`, 1.5 dp `accent` ring, `accent.onContainer` label and a check; widths weighted by label width ("ভিজিট হয়েছে ৬" is 91 dp, 108 with three digits); from font scale 1.5 it is a vertical stack of radio rows. Chips are case-insensitive for Latin and one per first Bangla letter, `#` for others (UI-SR-23, SRA-04). The search icon turns the dock into a text field. "কাছের আগে" (nearest first) appears as a toggle chip only when `cfg.sale.sort_by_distance` is on and a fix exists (F-SR-074); default order A to Z.

| State | What the user sees | Behaviour |
|---|---|---|
| Empty route | `AronEmptyState` "আজকের রুটে কোনো আউটলেট নেই" hint "অনলাইনে এসে রুট আবার নামান", action "রুট আবার নামান" | synced-at caption |
| Filtered empty | "এই অক্ষরে কোনো আউটলেট নেই" with "সব দেখান" | |
| Stale bundle | `warning` banner as on Home | 3 days old: rows open read-only |
| Load error | `AronErrorState` "রুট লোড করা যায়নি", "আবার চেষ্টা করুন" | local data normally loads |
| Long Bangla name | wraps to 2 lines, row grows | no ellipsis on the second line of a button or chip |
| Two routes planned | route name in the subtitle with a drop-down (s20) | |

| Key | BN | EN |
|---|---|---|
| subtitle, segments | রিটেইলার নির্বাচন করুন; বাকি, ভিজিট হয়েছে, সব | Select a retailer; Remaining, Visited, All |
| row words | ভিজিট হয়েছে, বাকি ২৯১.৫০ ৳, কাছের আগে | Visited, Due 291.50 ৳, Nearest first |
| empty | আজকের রুটে কোনো আউটলেট নেই; অনলাইনে এসে রুট আবার নামান | No outlets on today's route; come online and download the route again |
| filtered | এই অক্ষরে কোনো আউটলেট নেই | No outlet starts with this letter |

**Motion.** Row press is an instant `state.pressed` overlay (no scale). Row to outlet card is the one shared-element transition (220 ms). No list entrance stagger. **Kit.** `AronListRow` (flat), `AronSegmented`, `AronFilterDock` (alphabet), `AronStatusChip`. Paint: 0 shadowed cards, 1 dock.

## 6. Visit open and geo check (F-SR-017, 018, 019, 057, 060, 049, 054)

**Purpose.** Prove the rep is at the outlet with one on-device fix, or record why not. Everything runs in airplane mode; the visit row exists at once, the fix is read, the verdict decides (`VisitFlow`: Idle, ReadingFix, NeedsDecision, Open, LocationBlocked, CommitFailed, Blocked).

```
y  28- 92  TOP BAR  <←> বনানী স্টোর (2 lines allowed)                       (✓ ১০:৪২)
y 104-236  OUTLET CARD  বনানী স্টোর · মালিক: রহিম উদ্দিন
                        DHK-344-003 · 01711000000 · Apsis Cluster
                        (GT) (বাকি ২৯১.৫০ ৳) (শেষ ভিজিট ২ দিন আগে)        chips wrap (no nowrap), 28 dp, 2 lines allowed
y 248-460  GEO CARD (out of range, warning.container)
             <⚠> আপনি নির্বাচিত খুচরা বিক্রেতার সীমার মধ্যে নেই।
             আনুমানিক ১৮০ মি দূরে · সীমা ১০০ মি · GPS ±১২ মি
             রিফ্রেশ বাকি: ২ বার
             RANGE VIEW (offline Canvas): outlet pin, radius circle, own dot, bearing arrow, scale bar
             [Maps অ্যাপে খুলুন ›]  (geo: intent)
y 472-520  [পূর্বের বিক্রয় দেখুন ›]   (text button)         [বিক্রয় হবে না ›] (text button)
y 704-800  BAR  [ ফোর্স সেল ]   [ রিফ্রেশ ]*
```

**Primary action by verdict (bar):**

| Verdict | Geo card | Bar |
|---|---|---|
| Reading | `accent.container`, arc, "লোকেশন নেওয়া হচ্ছে…" (to 15 s) | primary disabled, reason "লোকেশন পেলে চালু হবে" |
| **In range** | `success.container`, check, "আপনি আউটলেটের সীমার মধ্যে আছেন।" and "GPS ±১২ মি" | **"কল শুরু করুন"*** (replaces the yes or no dialog; Back is "না": no visit counted, F-SR-060) |
| **Out of range**, refresh left | as wireframe | primary **"রিফ্রেশ (২ বার বাকি)"**, secondary "ফোর্স সেল" (the honest path is the easy one; reference had them the other way round) |
| Out of range, refresh used up | caption "আর রিফ্রেশ করা যাবে না" | primary becomes **"ফোর্স সেল"**, refresh disabled |
| Mock, `warn_rep` | the out-of-range card plus a `warning` line "নকল লোকেশন (Fake GPS) পাওয়া গেছে। এটি বন্ধ করে রিফ্রেশ করুন।" | as out of range; a mocked fix is never valid |
| Mock, `block_sale` | `warning` card with `cfg.geo.mock_blocked` text, "নকল লোকেশনের কারণে এই বিক্রয় করা যাবে না।" | no Force Sale; primary "ফিরে যান" |
| Location off or precise denied (`LocationBlocked`) | `warning` "লোকেশন বন্ধ আছে। চালু করলে বিক্রয় শুরু করা যাবে।" | primary "লোকেশন চালু করুন" (system settings); no Force Sale |
| Outlet has no coordinates | `warning` "এই আউটলেটের লোকেশন সংরক্ষিত নেই।" | primary "ফোর্স সেল" |
| `CommitFailed` | `danger.container` "সংরক্ষণ করা যায়নি। আবার চেষ্টা করুন।" | primary "আবার চেষ্টা" |
| Kill and relaunch | resumes the same visit with a fresh fix and a fresh refresh count | |

**Distance disclosure.** The distance and the radius show only when out of range (the rep needs to know how far to walk); in range shows accuracy only. Refresh reads one fix, capped by `cfg.geo.refresh_max` (3); refresh fixes are not stored.

**Range view replaces the map in the SR app (N-041).** A Compose `Canvas` of about 100 lines draws, from data already on the phone, the outlet pin, the radius circle scaled to fit, the phone's position dot, a bearing arrow, a scale bar and the label "আনুমানিক ১৮০ মি দূরে". It has no dependency, works in airplane mode and never blocks Force Sale. "Maps অ্যাপে খুলুন" is a `geo:` intent to the installed Maps app (zero size, needs the network only there). Reason: a `MapView` costs about 2 MB of APK (docs/17 estimate), a transient 100 MB or more of RAM, a cold init of several hundred ms and a Play services dependency, and it needs tiles from the network at the moment the rep is offline and out of range; docs/17 s8.2b and the version catalogue keep the Maps SDK out of the SR app, while N-041 asks for a map. The AMO and TSO flavours keep the Maps SDK, loaded only on tap. **N-041 and docs/17 must be made to agree (README decision 6; the android-sr-a request offers options A and B, this is a third).**

**Force Sale sheet** (`AronBottomSheet`, title "ফোর্স সেল প্রক্রিয়া", `warning` icon): question "আপনার ফোর্স সেলের কারণ কী?", two radio rows 56 dp "ইন্টারনেট সমস্যা" / "লোকেশন চেঞ্জ" (exactly one, required), line "ছবির লোকেশন আউটলেটের ঠিকানা সংশোধনের অনুরোধ হিসেবে যাবে।", sticky primary "ছবি তুলুন" (disabled until a reason is chosen, reason shown). Camera opens `GeoPhotoCapture` (one fix at the shutter, thumbnail, "GEO captured ±n m", one retake, compressed to at most 200 KB; F-SR-079). Back on the gate: card reads "ফোর্স সেল: ছবি সংরক্ষিত" (`photo_validated` true, `geo_validated` false, never a clean geo sale), bar primary "কল শুরু করুন". Camera denied: Permission empty state.

**Outcome sheet** "বিক্রয় হবে না" (F-SR-057, no fix needed): radio rows দোকান বন্ধ (closed), মালিক নেই (owner absent), বিক্রয়ে রাজি নন (refused), প্রতিযোগীর এক্সক্লুসিভ (competitor exclusive), পৌঁছাতে পারিনি (not reached); primary "সংরক্ষণ". The row on the list shows the outcome word.

**Sale history** (F-SR-054): text button opens a sheet with a date strip and a table (SKU, quantity with unit, value, footer equal to the sum of rows) from the local 7-day window; older dates need a connection and say so in an `offline` banner.

| Key | BN | EN |
|---|---|---|
| refresh / force | রিফ্রেশ / ফোর্স সেল | Refresh / Force Sale |
| reasons | ইন্টারনেট সমস্যা / লোকেশন চেঞ্জ | Internet problem / Location changed |
| start | কল শুরু করুন | Start call |
| distance | আনুমানিক ১৮০ মি দূরে · সীমা ১০০ মি | About 180 m away · limit 100 m |
| open in Maps | Maps অ্যাপে খুলুন | Open in Maps |
| in range | আপনি আউটলেটের সীমার মধ্যে আছেন। | You are within the outlet's range. |
| out of range | আপনি নির্বাচিত খুচরা বিক্রেতার সীমার মধ্যে নেই। | You are not within the selected retailer's range. |
| force question | আপনার ফোর্স সেলের কারণ কী? | Why are you doing a force sale? |
| location off | লোকেশন বন্ধ আছে। চালু করলে বিক্রয় শুরু করা যাবে। | Location is off. Turn it on to start selling. |
| mock | নকল লোকেশন (Fake GPS) পাওয়া গেছে। এটি বন্ধ করে রিফ্রেশ করুন। | Mock location found. Turn it off, then Refresh. |
| skip reasons | দোকান বন্ধ, মালিক নেই, বিক্রয়ে রাজি নন, প্রতিযোগীর এক্সক্লুসিভ, পৌঁছাতে পারিনি | Shop closed, Owner absent, Refused, Competitor exclusive, Could not reach |

**Motion.** Geo card cross-fades between verdicts over `motion.fast` (a fixed-height slot, no `animateContentSize` on the bar); `Reject` haptic once on out of range, `Confirm` once on in range; the outlet card is the destination of the list's shared element. **Kit.** `OutletCard`, `GeoStatusCard` (on `GlassSurface(Field)`), `RangeView`, `AronBottomSheet`, `AronBottomBar`. Paint: 2 shadowed cards.

## 7. Sale: quantity entry (F-SR-023, 050)

**Purpose.** Enter what the shopkeeper wants, by SKU, in the SKU's own unit, with a running total and a stock warning that never blocks.

```
y  28- 92  TOP BAR  <←> বিক্রয় · বনানী স্টোর                            (✓ ১০:৪২)
y 100-148  [পূর্বের বিক্রয় দেখুন ›]
y 156-...  ~ GROUP  এই আউটলেটে আগে বিক্রি
           ~ ROW 72 idle:   [thumb 48]  Prime Gold-10S                              ( ＋ 48 )
                                        ৬.৫০ ৳ / স্টিক · স্টকে ৬১০
           ~ ROW expanded (quantity above 0, accent.container):  [thumb]<২৬ pill>  MaxR-10S      ১৬০.০০ ৳ (line total)
                                        ৮.০০ ৳ / স্টিক · স্টকে ৩৯৪
                                        ( −)[ ২০ ](＋) স্টিক
           ~ GROUP  সিগারেট · স্টিক   ...   (60 SKU scroll: about 4,300 dp, not 7,900)
y 648-696  FILTER DOCK  <🔍 48> (✓সব) (সিগারেট) (লাইটার) (ম্যাচ)         hides on scroll down, returns on scroll up
y 704-800  BAR  মোট            [ এগিয়ে যান → ]*
                ২৮০.৫০ ৳
```

**Primary action.** "এগিয়ে যান →" at the end of the bar; the running gross total sits at the bar's start (caption "মোট", `type.numeral`). With no line entered it opens the zero-sale confirm (reference text, `AronConfirmDialog`: "বিক্রয়ের জন্য কোনও SKU নির্বাচন করা হয় নি" / "আপনি কি জিরো (০) বিক্রয় করতে চান?", হ্যাঁ and না), because a zero sale consumes a memo number and counts as a visit (F-SR-029).

**Row.** The v1 draft's row was 132 dp, so 60 SKUs needed 13 screens to find 3 to 8 lines and the stepper sat mid-screen. Now an **idle row is 72 dp** with a trailing 48 dp `+` under the right thumb (x 296 to 344); the first tap adds one pack step (step is the SKU's pack step for sticks, 1 for pieces and dozens) and the row **morphs to the stepper line** (`animateContentSize`, `motion.fast`); rows with quantity above 0 stay expanded with `accent.container` and show the line total (the non-colour cue). Hold accelerates and changes in-memory state only; tap the value to type (Bengali or ASCII digits, stored ASCII). **Search and the category chips moved into the bottom filter dock** (the zone the thumb map calls hard is no longer where the most-used controls live); from font scale 1.3 the dock is the first item of the list. Unit price caption always carries the unit. **Recent-first group:** SKUs sold to this outlet in the last 7 local days are listed first as a jump aid (no pre-filled quantities). The reference's purple badge is the packs equivalent (Q-UI-11); the three unlabelled icon rows of the old card are not reproduced (meaning unknown, F-SR-023). A per-outlet suggested quantity (docs/05) shows as a chip "প্রস্তাবিত ৫০০" only if the bundle carries one (not today). The "স্লাইড সংগ্রহ" secondary appears in the bar only when the bundle has an active offer (none today, docs/27). Each row is one TalkBack node with the same actions as Stock.

| State | What the user sees | Behaviour |
|---|---|---|
| Over stock | well `warning.container`, `warning` icon, caption "স্টকে আছে ৩৯৪; এর বেশি বিক্রি হচ্ছে।" | sale still allowed (F-SR-023), no haptic |
| At max (`cfg.sale.max_line_qty_base`) | plus disabled, caption "সর্বোচ্চ" | one `Reject` haptic when hold hits it |
| 60-line limit | banner "একটি মেমোতে সর্বোচ্চ ৬০টি লাইন।", plus disabled on SKUs with 0 | |
| Typed invalid | well `danger.container`, not committed | |
| Resumed draft | one-time banner "আপনার অসমাপ্ত বিক্রয় ফিরিয়ে আনা হয়েছে।" | draft survives kill and relaunch |
| Stale 3-day bundle | read-only, banner | no stepper |
| Search, no match | "কোনো SKU মেলেনি" with clear | |
| Compact height | 72 dp rows unchanged; the bar is 72 dp | the dock still hides on scroll |

| Key | BN | EN |
|---|---|---|
| history / proceed | পূর্বের বিক্রয় দেখুন / এগিয়ে যান | View previous sales / Proceed |
| total / badge | মোট / প্যাক | Total / packs |
| units | স্টিক, পিস, ডজন | sticks, pieces, dozens |

**Motion.** Line total fades in 150 ms; stepper rules as Stock; **the bar total updates at once with no crossfade while a stepper repeats** (a 150 ms crossfade of two `Text`s up to four times overlapped otherwise). **Kit.** `AronListRow` (flat), `AronStepper`, `AronFilterDock`, `AronBottomBar` (summary slot), `AronConfirmDialog`. Paint: 0 shadowed cards; sale save to Room in one transaction under 300 ms.

## 8. Review (নিরীক্ষণ), credit and Product QC (F-SR-025, 026, 027, 028)

**Purpose.** Check lines and money, optionally mark credit with a part payment, add QC, then commit the immutable memo (and print).

```
y  28- 92  TOP BAR  <←> নিরীক্ষণ                                         (✓ ১০:৪২) <🖨 36 neutral>
y 100-160  বনানী স্টোর (C-1042)  ·  রুট: Apsis RouteDaily · ক্লাস্টার: Apsis Cluster
y 168-...  LINES CARD  এসকেইউ | পরিমাণ | মূল্য (৳)        (rows 48, category subtotal rows 40; no per-row ৳)
                       MaxR-10S   ২০ স্টিক   ১৬০.০০
                       মোট সিগারেট ২০ স্টিক  ১৬০.০০ ...
           TOTALS CARD   মোট ৩৬০.৫০ ৳ ; ডিসকাউন্ট − ... ; স্লাইড − ... ; QC − ... (non-zero only)
                         সর্বমোট  ৩৬০.৫০ ৳   (type.display 34/48; stacked label over value from font scale 1.3)
           CREDIT ROW (56, the whole row is the switch)  এই বিক্রয়টি বাকি হিসাবে চিহ্নিত করুন        [switch]
           QC ROW (56)      প্রোডাক্ট QC ›    (secondary)
           caption          আপনি পরে মেমো সেকশন থেকে প্রিন্ট করতে পারবেন।
y 704-800  BAR  সর্বমোট ৩৬০.৫০ ৳      [ প্রিন্ট ]*
```

**Primary action.** "প্রিন্ট" (parity label, printer icon) at the bar's end with the net at its start. Tap opens `AronConfirmDialog` "আপনি কি নিশ্চিত?" / "বিক্রয় জমা হবে" (হ্যাঁ, না): the one irreversible step (the memo becomes immutable and is committed **before and regardless of printing**). After হ্যাঁ a `Confirm` haptic and a sheet: "বিক্রয় সফলভাবে জমা হয়েছে" with "আপনি কি এই বিক্রয়টি প্রিন্ট করতে চান?" and primary "প্রিন্ট করুন", text button "পরে" (the memo stays reprintable from Memo). Zero sale uses the same screen with one row "সর্বমোট ০.০০" and no credit row (F-SR-029).

**Credit sheet** (from the switch row): title "পরিশোধিত টাকার পরিমাণ লিখুন", body "এখন আপনি রিটেইলার থেকে কত টাকা সংগ্রহ করছেন?", field "আদায়কৃত অর্থ" (numeric, two decimals, 56 dp) and a live line "বাকিঃ ৬১.৫০" in `type.numeral`; primary "নিশ্চিত করুন". Rule: at least 0 and strictly below the grand total; error "আদায়ের অঙ্ক মোটের চেয়ে কম হতে হবে।". After confirming, the row label reads "বাকি ৬১ টাকা" with the switch on. Credit and QC can be done in either order. Closing the sheet without confirming turns credit off.

**Product QC (opened from the QC row, F-SR-027).** Step 1: an info card "QC এ প্রবেশ করতে, আপনি যে পণ্যটি যোগ করতে চান সেটি নির্বাচন করুন" and a list of this memo's SKUs (a list, not the old sideways carousel; empty: "QC করার জন্য কোনো পণ্য নেই"). Step 2, QC entry: card with "সর্বোচ্চ QC", "QC হয়ে গেছে", "বাকি আছে" (taka), two groups of rows each with a stepper in sticks: **উৎপাদন ত্রুটি** (ড্যামেজড ও ক্রাশড – প্যাক / আউটার CBC; আউটার / প্যাক / স্টিক কম থাকা; অন্যান্য ত্রুটি) and **পরিবহন ত্রুটি** (মেয়াদোত্তীর্ণ স্টক (৪ মাস+); স্টক ড্যামেজড – রুট সার্ভিস কালীন; স্বাদ সংক্রান্ত সমস্যা); bar secondary "বাতিল", primary "সংরক্ষণ", text "মুছুন". Step 3, QC summary: table SKU, QC type, quantity; "মোট QC নিষ্পত্তি", "সেটেলমেন্ট পরিমাণ"; primary "QC জমা দিন" with a confirm. Result on Review: a `QC − ৮০.০০` line and a blocked-edit note. The maximum-QC basis and deduction rule are unknown (MQ-03, MQ-04).

| State | What the user sees | Behaviour |
|---|---|---|
| Printer off | neutral slashed icon plus a `warning` banner; sheet still offers "পরে" | memo saved, `printed_at` empty |
| Print failed | the icon on its `warning` disc plus banner "প্রিন্ট হয়নি। প্রিন্টার চালু ও কানেক্ট আছে কিনা দেখুন।" with "আবার চেষ্টা" | `Reject` haptic; no duplicate marker on the retry |
| Net negative (zero sale plus QC) | `warning` caption "এটি একটি ক্রেডিট হিসেবে সংরক্ষিত হবে" | `cfg.memo.allow_negative_net` |
| Draft after kill | Review reopens with lines, credit and QC intact | |
| Many lines | list scrolls, totals card follows the last line, bar fixed | |
| Font scale 1.5 and up | the three-column table reflows: name line over "২০ স্টিক x ৮.০০ = ১৬০.০০"; two-button bars stack | |

| Key | BN | EN |
|---|---|---|
| title, rows | নিরীক্ষণ; এসকেইউ, পরিমাণ, মূল্য (৳) | Review; SKU, Quantity, Value (৳) |
| money | মোট, ডিসকাউন্ট, স্লাইড, QC, সর্বমোট | Total, Discount, Slide, QC, Grand total |
| credit | এই বিক্রয়টি বাকি হিসাবে চিহ্নিত করুন; বাকি ৬১ টাকা | Mark this sale as credit; Due 61 taka |
| collected | আদায়কৃত অর্থ; আদায়ের অঙ্ক মোটের চেয়ে কম হতে হবে। | Amount collected; The amount collected must be less than the total. |
| commit | আপনি কি নিশ্চিত? বিক্রয় জমা হবে | Are you sure? The sale will be submitted. |
| done | বিক্রয় সফলভাবে জমা হয়েছে; আপনি কি এই বিক্রয়টি প্রিন্ট করতে চান? | Sale submitted successfully; Do you want to print this sale? |
| QC | প্রোডাক্ট QC, QC সারাংশ, QC জমা দিন | Product QC, QC summary, Submit QC |

**Motion.** Totals cross-fade 150 ms; credit sheet spring; dialog 220 ms scale 0.94 to 1 with `ModulateAlpha`. **Kit.** `GlassSurface(Field)` x2, `AronListRow`, `AronSwitchRow`, `AronBottomSheet`, `AronConfirmDialog`, `AronStepper`. Paint: 2 shadowed cards.

## 9. Memo and print (F-SR-028, 030, 031, 032, 033, 066, 073)

**Purpose.** Look at a saved memo of today, reprint it, mark a credit memo paid, or edit it.

```
y  28- 92  TOP BAR  <←> মেমো                                              (✓ ১০:৪২) <🖨 36 neutral>
y 100-160  INFO BANNER (dismissible per day, 48 dp dismiss)  আপনি আউটলেট নির্বাচন করে একটি মেমো দেখতে পারবেন...
y 168-232  SELECTOR ROW  বনানী স্টোর · মেমো ০০১২ · ১০:৪২ · ৮৪.০০ ৳ (বাকি)        <▾>  → sheet list
y 244-...  MEMO CARD (receipt look)   মেমো নং ০০১২ · ১০:৪২                      (অনুলিপি) on reprint
              এসকেইউ | পরিমাণ | দাম (৳)     FB  ৩ ডজন  ৮৪.০০ ...  মোট ম্যাচ ...
              মোট ৮৪.০০ ৳ ; মোট ডিসকাউন্ট − ; মোট QC − ; সর্বমোট ৮৪.০০ ৳
              CREDIT BOX (warning.container)  এই বিক্রয়টি বাকিতে করা হয়েছে।     [পরিশোধিত করুন]  (opaque secondary)
              PRINT CHECK  ছাপা ঠিক আছে?  (হ্যাঁ) (না, আবার ছাপুন)              opaque chips, 48 dp targets
y 704-800  BAR  [ এডিট ]   [ প্রিন্ট ]*
```

**Primary action.** "প্রিন্ট" (reprint) at the bar; "এডিট" secondary. The selector is a bottom sheet list of today's memos (name, memo number or time to tell several memos of one outlet apart, total, a due chip) with the alphabet dock inside it, so the screen stays one-handed; it is a query on local memos for the business date.

**Rules and states.**
- **Reprint** prints with the duplicate marker "অনুলিপি (ডুপ্লিকেট)" and counts toward `cfg.memo.reprint_max` (5); at the limit the button is disabled with reason "পুনঃমুদ্রণের সীমা শেষ" (F-SR-031). An edited memo prints "প্রতিস্থাপিত মেমো <নং>" with its own number (F-SR-066).
- **Discount table** (SKU, quantity, value, total) renders only when discount lines exist; the reference rows `SL Match 3 / 0.00` stay an open question (Q-UI-01).
- **Mark paid.** "পরিশোধিত করুন" (a 48 dp **opaque** secondary button inside the credit box: the glass recipe nested on the warning container measured 4.07:1 in dark) opens `AronConfirmDialog` "আপনি কি নিশ্চিত?", then a `success` banner "এই মেমোটি সফলভাবে পরিশোধিত হিসেবে চিহ্নিত হয়েছে।"; the due chip disappears from the row. Whole-memo settlement, as today.
- **Edit.** Enabled only inside the outlet geofence and before QC; otherwise disabled with the reason "আউটলেটের সীমার মধ্যে যান" or "QC হয়ে গেছে, এডিট করা যাবে না". Opens a reason sheet "দয়া করে বিক্রয় এডিট করার কারণ লিখুন" with the three configured reasons as radio rows (first: "ভুল SKU নির্বাচিত।"), actions "বাতিল" and "জমা দিন"; then the Sale screen opens pre-filled with the outlet read-only, and the new memo supersedes the old.

**Print flow (shared by Review, Memo, Stock, Summary):**

| Step | Surface | Copy (BN / EN) |
|---|---|---|
| Connect | top-bar printer icon, tap to connect; auto-reconnect | প্রিন্টার কানেক্ট করা হয়েছে / Printer connected |
| Printing | secondary or primary shows the arc, about 3 to 8 s on a 58 mm slip | মেমো প্রিন্ট হচ্ছে… / Printing the memo… |
| Check | one row of two opaque chips under the bar | ছাপা ঠিক আছে? [হ্যাঁ] [না, আবার ছাপুন] / Is the print readable? [Yes] [No, print again] |
| Not connected | `warning` banner, never a dialog | প্রিন্টার কানেক্ট নেই। মেমো সংরক্ষিত আছে, পরে প্রিন্ট করতে পারবেন। / Printer not connected. The memo is saved; print later. |
| Paper out, cover open | `warning` banner with printer status | কাগজ শেষ বা ঢাকনা খোলা আছে। / Out of paper or cover open. |
| Choose a printer | sheet from Settings (s20) | |

"না" marks the job `failed_user`; the next print carries no duplicate marker and does not count toward the reprint limit (F-SR-073).

| State | What the user sees | Behaviour |
|---|---|---|
| No memo today | `AronEmptyState` "আজ এখনো কোনো মেমো হয়নি" | |
| Offline | nothing changes | all local |
| Long Bangla name or many lines | name wraps, memo card scrolls, bar fixed | |

| Key | BN | EN |
|---|---|---|
| banner | আপনি আউটলেট নির্বাচন করে একটি মেমো দেখতে পারবেন। এখান থেকে মেমো পুনরায় প্রিন্ট করতে পারবেন। | You can select an outlet and view its memo. You can reprint the memo from here. |
| totals | মোট, মোট ডিসকাউন্ট, মোট QC, সর্বমোট | Total, Total discount, Total QC, Grand total |
| credit | এই বিক্রয়টি বাকিতে করা হয়েছে।; পরিশোধিত করুন | This sale was made on credit.; Mark as paid |
| edit reason | দয়া করে বিক্রয় এডিট করার কারণ লিখুন | Please give the reason for editing this sale |
| paper | অনুলিপি (ডুপ্লিকেট), প্রতিস্থাপিত মেমো | Duplicate, Replaces memo |

**Motion.** Selector sheet spring; credit box collapses over `motion.base` after paying. **Kit.** `GlassSurface(Field)`, `AronBottomSheet`, `AronConfirmDialog`, `AronBanner`, `AronBottomBar`. Paint: 1 shadowed card.

## 10. Day summary (F-SR-036)

**Purpose.** The rep's own whole-day sales, per SKU, then per category, then the day total, with what goes back to the distributor; printable as the day-summary slip. A live local aggregate, offline.

```
y  28- 92  TOP BAR  <←> বিক্রয় সারসংক্ষেপ                                 (✓ ১০:৪২) <🖨 36 neutral>
y 100-156  INFO BANNER  আপনার পুরো দিনের বিক্রয় বিবরণ এখানে থাকবে
y 164-...  ~ GROUP HEAD  লাইটার · পিস
           ~ SKU ROW 112   Aster                                         ৪,৬৮৭.৫০ ৳
                           মেমো ৪    পরিমাণ ৩৭৫ পিস    ফেরত ২৫ পিস
                           ডিসকাউন্ট − ৪৩৭.৫০ · ডিসকাউন্টের পর ৪,২৫০.০০         (non-zero only)
           ~ GROUP FOOT   লাইটার মোট        পরিমাণ ৩৭৫ পিস    মূল্য ৪,৬৮৭.৫০        2 x 2 pairs, caption over value
                                            ছাড়ের পর ৪,২৫০.০০   ফেরত ২৫ পিস
           TOTALS CARD    ডিসকাউন্ট এবং অন্যান্য (−) ৪৩৭.৫০  [›]  (48 dp row, expands the components)
y 704-800  BAR  সর্বমোট ৪,৩৯১.০০ ৳      [ প্রিন্ট ]*
```

**Primary action.** "প্রিন্ট" prints the day-summary slip. **The grand total sits in the bar's summary slot** (type.numeral, beside Print, as on Sale and Review): in the v1 draft the key figure came last, under about 40 SKU rows. **Layout.** The six reference columns (মেমো, পরিমাণ, মূল্য, ডিসকাউন্ট, ডিসকাউন্ট মূল্য, ফেরত) are kept as the card fields above; a 6-column table does not fit 328 dp and a sideways-scrolling table cannot be read in sun. Each **group foot is four caption-over-value pairs** (পরিমাণ, মূল্য, ছাড়ের পর, ফেরত; label `type.caption`, value `type.bodyStrong`): the v1 draft's single line of four unlabeled numbers could not tell a value from a discounted value. Memo count is memos containing the SKU (UI-SR-34); return is issued minus sold, per SKU, in its unit. "ডিসকাউন্ট এবং অন্যান্য (−)" sums offer discount, slide and QC; tapping it expands the non-zero components (UI-SR-32). Quantities are never added across units; each group foot has its own unit.

| State | What the user sees | Behaviour |
|---|---|---|
| No sales yet | the banner plus `AronEmptyState` "আজ এখনো কোনো বিক্রয় হয়নি" | print disabled, reason "বিক্রয় হলে চালু হবে" |
| Printer off | neutral slashed icon and the banner of the print flow | |
| Long list (40 SKUs) | flat rows, group heads | |

| Key | BN | EN |
|---|---|---|
| columns | মেমো, পরিমাণ, মূল্য, ডিসকাউন্ট, ডিসকাউন্ট মূল্য, ফেরত | Memos, Quantity, Value, Discount, Discounted value, Return |
| foot | পরিমাণ, মূল্য, ছাড়ের পর, ফেরত | Quantity, Value, After discount, Return |
| totals | ডিসকাউন্ট এবং অন্যান্য (-), সর্বমোট | Discount and others (-), Grand total |
| empty | আজ এখনো কোনো বিক্রয় হয়নি | No sales yet today |

**Motion.** Expand of the components line 220 ms. **Kit.** `AronListRow` (flat), `GlassSurface(Field)` totals, `AronBottomBar` (summary slot). Paint: 1 shadowed card.

## 11. Sales Submit (F-SR-034, 035)

**Purpose.** Confirm that what is on the phone equals what the server holds, then close the day. Upload already happens in the background as soon as a connection exists (UI-SR-35), so this screen reads as a reconciliation, with Sync data as a manual retry.

```
y  28- 92  TOP BAR  <←> বিক্রয় জমা                                       (✓ compact)
y 104-144  ডিভাইস স্ট্যাটাস   (✓ অনলাইন · শেষ যোগাযোগ ১০:৪২)       derived from last server contact; quiet when online
y 152-196  PROGRESS  ① সিঙ্ক  ─  ② মেলানো  ─  ③ জমা          3 steps, current one accent (only the current label from 1.5)
y 204-440  RECONCILE CARD   আউটলেট                                   ✓ মিলেছে
                            ডিভাইস ৬ · সার্ভার ৬
                            মেমো                                      ⏳ অপেক্ষায়
                            ডিভাইস ৬ · সার্ভার ৫
                            বিক্রয় (৳)  ·  স্টক  ·  কিউসি  (same two-line rows)
                            [আরও দেখুন ▾] (attendance, outlet requests, money per category)
y 452-528  INFO  আজকের সব তথ্য সিঙ্ক হলে বিক্রয় জমা দেওয়া যাবে। সিঙ্ক নিজে থেকেই চলছে।
y 704-800  BAR  [ ডাটা সিঙ্ক করুন ]   [ বিক্রয় জমা ]*     (disabled: reason caption above)
```

**Primary action.** "বিক্রয় জমা" (60 percent). The secondary "ডাটা সিঙ্ক করুন" retries (no icon: the label needs the width). The reference's red notice becomes the calm `accent` info line in the wireframe and turns `warning` only when sync is stuck past the config threshold.

**Reconciliation.** Each entity is **a two-line row** (name and the status word on line 1, "ডিভাইস N · সার্ভার M" on line 2) instead of the reference's three-column table: the money row "৪,৩৯১.০০ · ৪,২৫৭.০০" does not fit beside two more columns at 328 dp (the status word was cut off in the v1 draft). Rows compare **record counts per entity** and **money per category in milli-taka** (never unit-less sums, UI-SR-36); the legacy five rows are kept for parity (আউটলেট, বিক্রয়, স্টক, কিউসি, প্রমোশন), where "প্রমোশন" shows only when it has a value (discount programmes are deferred). Each row carries an icon and a word, not colour alone: ✓ "মিলেছে" in `success.onContainer`, ⏳ "অপেক্ষায়", ⚠ "মিলছে না" in `warning.onContainer`. Submit is enabled only when every record is acknowledged and the counts match; dues warn and never block (Q-UI-08 default).

| State | What the user sees | Behaviour |
|---|---|---|
| Syncing | step ① active, rows update live, bar disabled "সিঙ্ক চলছে… ৩টি বাকি" | polite live region, at most once per 10 s |
| All matched | step ② done, `success` banner "সব তথ্য মিলেছে", bar enabled | |
| Offline | chip `offline`; primary stays enabled and reads "বিক্রয় জমা (সংযোগ পেলে যাবে)" | the submit queues as the last event of the day (`cfg.day.sales_submit_offline_queue`) and shows "অপেক্ষায়" until the server settles |
| Mismatch after full sync | `warning` banner "মিলছে না: মেমো ডিভাইসে ৬, সার্ভারে ৫" with "আবার সিঙ্ক" and "সাপোর্টকে ডাটা পাঠান" | submit stays disabled, mismatch never silent |
| Dues remain | `AronConfirmDialog` "আপনার এখনো ১টি রিটেইলারের কাছে বাকি রয়েছে। আপনি আপনার বিক্রয় জমা দিতে চান?" actions "না, বাকি আদায় করি" (to Memo) and "হ্যাঁ, জমা দিন" | warn, not block |
| Unprinted stock slip | `warning` caption above the bar | not a block |
| Submitted | full-screen success: 96 dp `success.container` disc, "বিক্রয় সফলভাবে জমা হয়েছে", counts, and, after 17:00, a hint "এবার চেক আউট করুন" | `Confirm` haptic |

| Key | BN | EN |
|---|---|---|
| status | ডিভাইস স্ট্যাটাস; অনলাইন, অফলাইন | Device status; Online, Offline |
| table | বিষয়, ডিভাইস, সার্ভার; আউটলেট, বিক্রয়, স্টক, কিউসি, প্রমোশন | Item, Device, Server; Outlets, Sales, Stock, QC, Promotion |
| row status | মিলেছে, অপেক্ষায়, মিলছে না | Matched, Waiting, Not matching |
| buttons | ডাটা সিঙ্ক করুন, বিক্রয় জমা | Sync data, Sales submit |
| success | বিক্রয় সফলভাবে জমা হয়েছে | Sales submitted successfully |
| dues | আপনার এখনো ১টি রিটেইলারের কাছে বাকি রয়েছে। আপনি আপনার বিক্রয় জমা দিতে চান? | 1 retailer still owes you. Do you want to submit your sales? |

**Motion.** Counts cross-fade 150 ms; step connector fills 220 ms; success disc scales 0.9 to 1 with `ModulateAlpha` (none when reduced). **Kit.** `GlassSurface(Field)`, `AronStatusChip(device)`, `AronBanner`, `AronBottomBar`, `AronConfirmDialog`. Paint: 1 shadowed card.

## 12. Tasks (F-SR-046, 047)

**Purpose.** Tasks the AMO or TSO assigned; resolve them offline. A push message is only a nudge: the task arrives with the next sync (UI-SR-43).

```
y  28- 92  TOP BAR  <←> টাস্ক                                            (✓ ১০:৪২)
y 100-148  [✓ চলমান ৩][ সম্পন্ন ২ ]                      segmented, 48 dp
y 156-...  TASK CARD (field card, radius 20, 120)  (OOS)  Babu Store            (চলমান)
                    Babu Store দোকানে Maxim এ OOS আছে
                    <⚑> শেষ তারিখ ৩০ নভেম্বর             [সমাধান করুন]   swipe →
           ...
y 704-800  (no primary; the card's own action is the primary)
```

**Primary action.** Swipe the card right past 40 percent of its width (parity, `Confirm` haptic), or tap "সমাধান করুন" (48 dp text button at the card's end; discoverable and TalkBack-friendly). No dialog and no note (parity); the resolved card stays in place with a quiet "সম্পন্ন" check, and the resolve is queued offline with a client UUID. **Ongoing uses `accent.container` with a clock icon** (it is the normal state); **only overdue uses `warning.container`**, with "মেয়াদ পেরিয়েছে" (icon plus word), so overdue is visibly different. The card header (tag, state chip) is a `FlowRow`.

| State | What the user sees | Behaviour |
|---|---|---|
| Empty | `AronEmptyState` "আপনার এএমও (AMO) কোনো কাজ বরাদ্দ করেনি।" and "সিঙ্ক হয়েছে ১০:৪২" | so an empty list is not mistaken for a stale one |
| New task by push | badge on the Home tile rises after sync; the list shows it at the top | no polling |
| Resolved offline | chip "সম্পন্ন · অপেক্ষায়" (`warning`, waiting) until acknowledged | no undo (immutable, a new event) |
| Long text | description wraps to 4 lines then "আরও দেখুন" | |

| Key | BN | EN |
|---|---|---|
| title, segments | টাস্ক; চলমান, সম্পন্ন | Task; Ongoing, Completed |
| action, date | সমাধান করুন; শেষ তারিখ ৩০ নভেম্বর | Resolve; Completion on 30 Nov |
| empty | আপনার এএমও (AMO) কোনো কাজ বরাদ্দ করেনি। সিঙ্ক হয়েছে ১০:৪২ | Your AMO has not assigned any task. Synced 10:42 |

**Motion.** Swipe follows the finger, the action panel reveals `success.container` with a check; settle 150 ms. **Kit.** `TaskCard` (screen composable on `GlassSurface(Field)`), `AronSegmented`, `AronEmptyState`. Paint: up to 8 shadowed cards visible (cap 8); beyond that cards turn flat.

## 13. Open items and defaults taken (log to DECISIONS.md)

| ID | Item | Default taken |
|---|---|---|
| Q-SD-01 | Is check-in allowed when no fix arrives in 15 s? | Yes, recorded `no_fix`, visible to the supervisor (offline-first: no sensor wait blocks the day) |
| Q-SD-02 | Home dock "next step" shortcut | Built as a shortcut only, no new function; owner may switch it off in review |
| Q-SD-03 | Refresh as the primary and Force Sale as the secondary while refreshes remain | Honest path first; reference had them reversed |
| Q-SD-04 | Hold duration for check-in and check-out | 1,200 ms, the kit default; the reference never states it |
| Q-SD-05 | Accuracy bands (30 m, 100 m) on Attendance | Design defaults; 100 m is `cfg.geo.max_accuracy_m` |
| Q-SD-06 | The new Bangla strings (skip outcomes, hold sheet, banners, Settings, Outlet requests, Tutorial, Journey, KPI, sync sheet, reconcile rows) | Need native-reviewer sign-off before string freeze |
| Q-UI-01, 03, 04, 05, 08, 10, 11 | memo discount rows, printer-blocked Save, check-in gate, Sales Journey and KPI tiles, Sales Submit rule, Return column acknowledgement (printout only), pack badge | Defaults of `docs/ui-reference/questions.md` are used as written above |
| Q-SD-07 | Number grouping on money (`12,34,567` or `1,234,567`) | `LocaleDigits` stays the single source (tokens s10 item 1) |
| Q-SD-08 | Sunlight suggestion trigger | System brightness 85 percent or more, read once on resume, as docs/32 2a.5 says; **no light-sensor sample** (the review proposed one; docs/32 says no sensor, no polling) |
| Q-SD-09 | What the Tutorial tile opens | A list of short guides (video or illustrated steps), admin-managed, downloaded over Wi-Fi only and played offline; the reference shows only the tile |
| Q-SD-10 | Logout while records are unsynced | Sync first ("সিঙ্ক করে লগআউট"); offline the button is disabled with its reason; **data is never deleted by logout**; no force-logout path is designed (the owner may ask for a supervisor-PIN path) |
| Q-SD-11 | Settings contents beyond the reference (Look, haptics, display-speed check, version and update) | Added; language, "PDA to Support" and logout are the reference's |
| Q-SD-12 | Compact variant below 700 dp window height | As s0; confirm on the D-12 class at 360 x 640 |

## 14. Settings (reference: gear on Home; language, "PDA to Support", logout)

**Purpose.** Change language, the look, the printer, send data to support, see the version, log out safely.

```
y  28- 92  TOP BAR  <←> সেটিংস                                            (✓ ১০:৪২)
y 100-    ভাষা                       (group label)
          [✓ বাংলা][ English ]      segmented, 48 dp
          প্রদর্শন
          GROUP  রোদ মোড  বড় লেখা, মোটা রেখা, সর্বোচ্চ উজ্জ্বলতা               [switch]     56 dp rows, one field group
                 লুক        [✓ অটো][ হালকা গ্লাস ][ সিম্পল ]                              (Auto | Light glass | Simple)
                 হ্যাপটিক                                                                 [switch]
                 ডিসপ্লে গতি পরীক্ষা  শেষ ফল: ভালো · B                                      ›
          ডিভাইস
          GROUP  প্রিন্টার  RPP02N-4F21 · কানেক্টেড ›  /  সাপোর্টকে ডাটা পাঠান ›  /  সিঙ্ক অবস্থা  শেষ সিঙ্ক ১০:৪২ ›
          অ্যাকাউন্ট
          GROUP  সংস্করণ ১.০.২৫  আপডেট আছে ›  /  লগআউট (danger text button)
```

**Layout notes.** iOS-style grouped list: each group is one flat field group (`radius.card`, 56 dp rows, dividers `border.divider`), group labels `type.label` in `text.secondary` above. The **sun switch** is the first row of "প্রদর্শন" (the same switch as the Home header and the sync sheet). "লুক" is the user setting of android-glass s4 row 4 (Auto, Light glass, Simple: Simple gives tier C, Light glass caps at B). "ডিসপ্লে গতি পরীক্ষা" re-runs the frame-time probe (android-glass s5) and shows the result as a word and the tier. "সাপোর্টকে ডাটা পাঠান" is the reference's "PDA to Support": it uploads the diagnostic bundle (counters, heartbeat, log tail, no secrets). The printer row shows the paired printer and its state and opens the picker sheet (s20).

**Logout** is a `danger.onContainer` text button. Tapping it never loses work: with unsynced records a sheet shows "সিঙ্ক হয়নি এমন ৩টি রেকর্ড আছে" with primary "সিঙ্ক করে লগআউট" and text "বাতিল"; offline the primary is disabled with the reason "ইন্টারনেট পেলে চালু হবে" and the caption "ডাটা ফোনে নিরাপদ, কখনো মুছবে না।" (Q-SD-10). With nothing unsynced a confirm dialog "লগআউট করবেন?" appears.

| State | What the user sees | Behaviour |
|---|---|---|
| Update available | version row shows "আপডেট আছে" | opens the update page (s20); never blocks an open day |
| Printer not connected | printer row reads "কানেক্ট নেই" | opens the picker |
| Haptics off by the system | the haptics row is disabled with the reason "ফোনের সেটিংসে বন্ধ" | |
| Long Bangla, font 2.0 | rows grow, segmented controls become radio rows | no clipping |

| Key | BN | EN |
|---|---|---|
| groups | ভাষা, প্রদর্শন, ডিভাইস, অ্যাকাউন্ট | Language, Display, Device, Account |
| rows | রোদ মোড, লুক, হ্যাপটিক, ডিসপ্লে গতি পরীক্ষা, প্রিন্টার, সাপোর্টকে ডাটা পাঠান, সিঙ্ক অবস্থা, সংস্করণ, লগআউট | Sunlight mode, Look, Haptics, Check display speed, Printer, Send data to support, Sync status, Version, Log out |
| look | অটো, হালকা গ্লাস, সিম্পল | Auto, Light glass, Simple |
| logout | সিঙ্ক হয়নি এমন ৩টি রেকর্ড আছে; সিঙ্ক করে লগআউট; ডাটা ফোনে নিরাপদ, কখনো মুছবে না। | 3 records are not synced yet; Sync and log out; The data is safe on the phone and is never deleted. |

**Kit.** `AronSegmented`, `AronSwitchRow`, grouped `AronListRow`, `AronBottomSheet`. Paint: 4 flat groups, 0 shadowed cards.

## 15. Outlet menu (reference: `outlet-menu.md`, UI-SR-40 to 42)

**Purpose.** Ask for a new shop, a permanent close, a change of information or a cluster change; follow the requests. Every request is queued offline with a client UUID and verified by the AMO, then approved on the web (Q-UI-06 default, route unchanged).

```
y  28- 92  TOP BAR  <←> আউটলেট                                            (✓ ১০:৪২)
y 108-    TILES 2 x 2   <নতুন দোকান> <স্থায়ী বন্ধ>        160 x 112, 44 dp squircle well, label type.heading
                        <তথ্য পরিবর্তন করুন> <ক্লাস্টার>      (the reference order, four tiles)
          আমার অনুরোধ
          ~ ROW  Rahim Variety Store · নতুন দোকান                         (জমা হয়েছে)
          ~ ROW  Mim Enterprise · ক্লাস্টার                               (AMO যাচাই)
          ~ ROW  করিম টি স্টল · তথ্য পরিবর্তন                             (অনুমোদিত)
          ~ ROW  Sathi Store · স্থায়ী বন্ধ                                (বাতিল)
```

**Flows.** Each is a sheet or a short step sequence on the existing kit: (1) **pick the outlet** (reuse the route list with its dock; not for "নতুন দোকান"); (2) **a form** with 56 dp fields, label above, error line below: new shop (name, owner, phone, address), change information (the editable fields, current values shown), cluster (a radio list of clusters, a reason); (3) **photo step** (`GeoPhotoCapture`: one fix at the shutter, one retake, at most 200 KB; required for a new shop, optional for a change), with "লোকেশন একবার নেওয়া হবে; AMO যাচাই করবেন।"; (4) a sticky primary "জমা দিন" disabled with its reason until valid. **Permanent close** uses the destructive dialog "স্থায়ী বন্ধ" with reason radio rows (the shop has closed down, owner changed) and a `danger.fill` primary "অনুরোধ পাঠান". Status chips (word plus icon): "জমা হয়েছে" (`accent.container`), "AMO যাচাই" (`warning`, waiting), "অনুমোদিত" (`success`), "বাতিল" (neutral). The cluster tile is the live app's fourth tile (UI-SR-40); its fields, verifier and route effect are `MUST-CONFIRM` (Q-UI-06).

| State | What the user sees | Behaviour |
|---|---|---|
| Empty requests | `AronEmptyState` "কোনো অনুরোধ নেই" | |
| Offline | a request is accepted and shows "জমা হয়েছে · অপেক্ষায়" until synced | never blocked |
| Rejected by the AMO | "বাতিল" chip; tapping shows the AMO's reason in a sheet | |

**Kit.** `GlassSurface(Field)` tiles (these carry labels and are the primary actions: 96 percent), `AronListRow`, `AronTextField`, `AronBottomSheet`, `AronConfirmDialog`, `GeoPhotoCapture`. Paint: 4 shadowed cards.

## 16. Tutorial (reference: tile only; content assumed, Q-SD-09)

**Purpose.** Short guides for the rep. No video travels over the 2 GB data pack.

```
y  28- 92  TOP BAR  <←> টিউটোরিয়াল                                       (✓ ১০:৪২)
y 100-160  BANNER (info)  ভিডিও শুধু ওয়াই-ফাইয়ে নামে · মোবাইল ডাটা খরচ হবে না।     (offline: "ডাউনলোড করতে ওয়াই-ফাই লাগবে")
y 168-...  ~ ROW  <▶ 48>  অ্যাপ পরিচিতি            ২:৩০ · ৮.২ এমবি           (✓ অফলাইনে দেখা যাবে)
           ~ ROW  <▶ 48>  বিক্রয় ও মেমো             ৪:১০ · ১৪.৬ এমবি          ▕██░░▏ ৪০%  (নামছে)
           ~ ROW  <▶ 48>  স্টক ও স্লিপ প্রিন্ট        ৩:২০ · ১১.০ এমবি          [ডাউনলোড] (শুধু ওয়াই-ফাই)
```

**Rules.** Each row shows the title, length, size and one of three states: downloaded (plays offline, quiet check), downloading (meter with a spoken percent), not downloaded (a 48 dp "ডাউনলোড" secondary button and a neutral "শুধু ওয়াই-ফাই" chip; disabled with the reason when the phone is not on Wi-Fi, by `NetworkCapabilities` unmetered). Downloads run in WorkManager on unmetered networks only and respect battery saver. Tapping a downloaded row plays it full screen. States: loading skeleton, empty ("কোনো টিউটোরিয়াল নেই"), error with retry, long titles wrap. **Kit.** `AronListRow`, `AronBanner`, `AronMeter`, `AronSecondaryButton`. Paint: 1 flat group.

## 17. Sales Journey (assumed view, Q-UI-05, UI-SR-01)

**Purpose (assumed).** Today's route as a progress timeline; not built until AKTCL confirms (docs/ui-reference/questions.md).

```
y  28- 92  TOP BAR  <←> বিক্রয় যাত্রা                                    (✓ ১০:৪২)
y 100-190  FIELD CARD  আজকের রুট            ৬/৬০ ভিজিট হয়েছে      ▕█░░░░░░░░▏
y 202-...  TIMELINE (one field card)
             (✓) বনানী স্টোর            ০৯:১২ · ১৩৪.০০ ৳          visited: success.container dot, time and memo total
             (✓) করিম টি স্টল            ০৯:৪৮ · ৫৬.৫০ ৳
             (✕) জননী ফার্মেসি           ১০:০৫ · মালিক নেই         skipped: neutral dot, the outcome word
             (📍) Babu Store            পরের আউটলেট                  next: accent.container dot with a ring
             (○) Jamal Traders          বাকি                          remaining: hollow dot
```

Visited rows carry the memo total, skipped rows their word, the next outlet a pin; all from local data, offline, no map. Tapping an outlet opens its visit (s6). States: empty route ("আজকের রুটে কোনো আউটলেট নেই"), loading skeleton, long names wrap. **Kit.** `GlassSurface(Field)`, a `Timeline` composable (a 2 dp line and 32 dp dots; each dot has an icon and the row has a word, never colour alone), `AronMeter`. Paint: 2 shadowed cards.

## 18. KPI (assumed view, Q-UI-05, UI-SR-02; sales only)

**Purpose (assumed).** Three KPI cards of the day, from the local aggregate; target and achievement are deferred (docs/27).

```
y 100-    CARD  স্ট্রাইক রেট          ১০%            ▕█░░░░░░▏   ৬০টি টার্গেট আউটলেটের মধ্যে ৬টি সফল কল
          CARD  আউটলেট ভ্রমণ          ৬/৬০           ▕█░░░░░░▏   নন ভিজিট ৫৪ · বিক্রি নেই ০
          CARD  ক্যাটাগরি অনুযায়ী বিক্রয় (৳)    লাইটার ৪,৬৮৭.৫০  ▕██████████▏   ম্যাচ ১৪১.০০  ▕█░░▏   সর্বমোট ৪,৩৯১.০০ ৳
```

Values are `type.display`, meters carry a spoken value, money bars print their values and are never summed across units. States: empty ("এখনো কোনো সংখ্যা নেই"), loading skeleton, error with retry. **Kit.** `GlassSurface(Field)` x3, `AronMeter`. Paint: 3 shadowed cards.

## 19. Sync detail sheet (opened from the status chip on every screen)

**Purpose.** The one place a rep checks that offline data is safe and what to do when it is stuck. No dialog, no blocking state.

```
SHEET (AronBottomSheet, max 85%)
  সিঙ্কের অবস্থা                                              title
  (☁ অফলাইন · ৩ অপেক্ষায়)                                     the chip, current state
  GROUP  রোদ মোড  বড় লেখা, মোটা রেখা, সর্বোচ্চ উজ্জ্বলতা          [switch]      the sun switch, one tap from every screen
  GROUP  মেমো       ২   অপেক্ষায়     /  ভিজিট ১ অপেক্ষায়  /  স্টক ০ সব পাঠানো হয়েছে  /  ছবি ০ ...  /  টাস্ক ০ ...
  শেষ চেষ্টা ১০:৪২ · পরের চেষ্টা ১০:৫৭
  ফোনে জায়গা: ৪২০ এমবি   (warning under 500 MB, danger under 200 MB, with the word)
  [ এখন সিঙ্ক করুন ]*       sticky primary (disabled offline with "ইন্টারনেট পেলে চালু হবে")
  [ সাপোর্টকে ডাটা পাঠান ]    text button
```

A flat list of **pending counts per entity** (memo, visit, stock, photo, task), each with a word ("অপেক্ষায়", "পাঠানো হচ্ছে", "ব্যর্থ", "সব পাঠানো হয়েছে"); the last and next retry times (Dhaka); the storage line; the sticky primary and the support button. The chip, the sun switch and every count are in one scroll; no polling: it reads the same local counters as the chip. TalkBack reads the title, then the groups. States: nothing pending ("সব তথ্য সিঙ্ক হয়েছে।"), offline ("ইন্টারনেট নেই, কিন্তু বিক্রয় চলবে। সংযোগ পেলে নিজে থেকেই সিঙ্ক হবে।"), backing off, stuck beyond the threshold (the primary reads "আবার চেষ্টা করুন"), storage low. **Kit.** `AronBottomSheet`, `AronSwitchRow`, `AronListRow`, `AronStatusChip`.

## 20. Route sheet, printer picker, update page

- **Route sheet (F-SR-065, two or more routes).** Opens from the Home subtitle (drop-down arrow). Radio rows: route name with its visit days ("Apsis RouteDaily", "Apsis Route3F (Sun, Tue, Thu)") and today's outlet count; choosing one reloads Home and the route list from local data; sticky primary "রুট বদলান" disabled until a change. `AronBottomSheet`; not previewed (same pattern as the printer sheet).
- **Printer picker.** Opens from the printer icon or Settings. Rows: each paired printer (name such as `RPP02N-4F21`, "58 mm", state "কানেক্টেড" with a check), then "নতুন প্রিন্টার খুঁজুন" (secondary, scans after the Nearby devices rationale). Choosing a row connects (the arc on the row, then "প্রিন্টার কানেক্ট করা হয়েছে"). Not connected: the row says "কানেক্ট নেই" and tapping retries. Never a dialog.
- **Update page.** A full-screen page (not a dialog) from the login banner or Settings: a 96 dp `accent.container` disc with a download glyph, "নতুন সংস্করণ ১.০.২৬", one line "আজকের কাজ শেষ হলে আপডেট করুন। চলতি দিন বন্ধ হবে না।", primary "আপডেট", text button "পরে". A required update blocks a **new** login only, never an open offline day (F-SR-001, the login banner of s1).
