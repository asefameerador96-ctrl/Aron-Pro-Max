# Screens: SR app (Calm Glass), v1 draft

Status: v1 draft for owner review, 2026-10-07. Inputs: `docs/32` (direction), `docs/design/tokens.md` v1 (**wins on any value**), `docs/design/android-glass.md` (kit parts, states, haptics, Bangla rules), `docs/31` s1 (gates), `docs/ui-reference/sr/*.md` (what each current screen shows, `UI-SR-nn`), `docs/25-build-backlog.md` (acceptance rows `F-SR-nn`). Same functions and same information as the current Aron, restyled and re-laid-out for one hand. **Not designed (docs/27, hidden not greyed):** target and Target and Achievement card, loyalty and Astha tiles, Photo Capture (its only children are Astha and campaign gifts), outlet eligibility dots, offers and discount programmes. Their hooks stay: the money model keeps offer and DRP discount lines (zero today), rows keep a zero-width leading slot. Strings quoted in Bangla are the current app's (reference) or `values-bn` keys; **new Bangla needs the native reviewer's sign-off (docs/20 role NB)**.

Reading guide: each screen has Purpose, Wireframe (y in dp on 360 x 800, gesture navigation), Primary action, States, Microcopy (BN / EN), Motion, Kit parts and paint budget. Standard states (loading skeleton after 150 ms, offline chip, empty, error with retry, Bangla long text, font scale 1.0, 1.3, 2.0, tiers A, B, C, sunlight) are assumed everywhere; tables list only what is specific.

## 0. Shared frame and rules

| Item | Value |
|---|---|
| Frame | 360 x 800 dp, side gutter `space.screen` 16, content width 328. Insets are read, never hard-coded: status about 28, gesture navigation 16, 3-button navigation 48 |
| Top bar | 64 + status inset, `bar` recipe: back 48 dp at start, title, status chip at end. Title is `type.title` (22/30 Latin, 22/32 Bangla); if it does not fit one line it steps down to `type.heading` 18/26 and may wrap to 2 lines (64 dp holds 2 x 26); if it still needs more, the chip drops to its dot form |
| Bottom bar (`AronBottomBar`) | 80 + nav inset, y 704 to 800 on gesture navigation, primary 56 dp at y 716 to 772, x 16 to 344. Two actions: secondary 40 percent, primary 60 percent, 12 gap. A disabled primary shows its reason as a `type.caption` line above it (bar grows 80 to 104 over `motion.base`) |
| Free height | 612 dp between the bars on gesture navigation, 580 with 3-button navigation |
| Thumb map (right hand, 6.5 to 6.7 inch) | easy y 380 and below; stretch y 200 to 380; hard y under 200. Primary action, steppers, row taps, outlet filters live at y 380 and below. Things used about once a day (gear, sync chip, printer chip, back arrow) may sit in the top bar; system back also works |
| Status chip (`AronStatusChip`) | forms: **full** (icon, label, count; Home), **compact** (icon plus one word; every other top bar), **dot** (28 dp circle; font scale 1.3 and up, or a 3-line title). Touch target 48. Tap opens the sync detail sheet. Never a modal |
| Page | `AronBackground` once at the root; screens are transparent over it. Sunlight forces tier C |

**Colour roles used below** (full table in tokens s2; light / dark / sunlight):

| Role | Light | Dark | Sunlight | Used for |
|---|---|---|---|---|
| `accent` / `accent.container` | `#0A58CC` / `#DCE9FD` | `#84B6FF` / `#1B3568` | `#0041B3` / `#CFE0FF` | the one primary per screen, selected, syncing |
| `text.primary` / `text.secondary` | `#0B1B33` / `#475569` | `#F1F5FB` / `#A9B6CB` | `#050A14` / `#2B3648` | numbers and titles / captions |
| `success` / `.container` | `#0B7A45` / `#D6F0E1` | `#4CD38B` / `#12382A` | `#005C2E` / `#CFEBD9` | in range, synced, done |
| `warning` / `.container` | `#9A5200` / `#FFE9C7` | `#FFB84D` / `#45330F` | `#7A3E00` / `#FFE0A8` | out of range, dues, waiting, stale |
| `danger` / `.container` | `#C0182D` / `#FDDDE1` | `#FF7A8A` / `#4A1B25` | `#A30018` / `#FAD0D6` | wrong password, storage full only |
| `offline` / `.container` | `#53627C` / `#E3E8F0` | `#9FB0CB` / `#27324A` | `#3B4658` / `#DDE2EA` | offline (never danger) |
| `surface.solid.raised` / `border.input` | `#F2F5FA` / `#66768F` | `#1C2740` / `#8394B0` | `#EEF1F6` / `#2B3648` | fields, stepper well, inner boxes |

**Money and quantity words.** One Bangla word per term, from one catalogue (UI-SR-07, D-346): gross `মোট` (Total), offer discount `ডিসকাউন্ট` (Discount), DRP `স্লাইড` (Slide), QC deduction `QC`, net `সর্বমোট` (Grand total). Rules: gross and net always shown; **every other component only when non-zero**; deductions use a true minus `−` and their word, never colour alone; two decimals, `৳` after the amount at 0.6 em in the Bengali face (tokens s7.1); units `স্টিক` (stick), `পিস` (piece), `ডজন` (dozen) beside every quantity and **never summed across units** (UI-SR-10, 15, 36). Numerals in `type.numeral` or `type.display`, Bold, `text.primary`, right-aligned in columns. Cross-fade 150 ms when a number changes, no count-up.

**Motion defaults** (tokens s8): forward screen change = new content fades in 220 ms `ease.out` with an 8 dp slide from the end, back = 180 ms `ease.in`; press scale 0.96 tile, 0.97 button, 0.92 stepper over 90 ms; sheets `motion.sheet` spring; shared element only outlet row to outlet card (220 ms); reduced motion and tier C: 120 ms fade, no transforms. **Haptics** (android-glass s8): one per action: `Confirm` on save, check-in, commit, printed; `Reject` on geofence fail, validation, print failed; `VirtualKey` on stepper tap; none on scrolling, tiles, rows, connectivity.

**Wireframe legend.** `( )` chip, `[ ]` button or field, `[ ]*` the one primary, `< >` icon, `▕██░░▏` meter, `...` scrolls, `~` flat list row (no shadow).

## 1. Login (F-SR-001, 002, 003)

**Purpose.** Authenticate once per phone, bind the phone with the TSO code if new, load the first bundle. Works offline for a previously bound phone (F-SR-001; offline rules in `feature-auth`).

```
y  28- 76  (বাংলা | EN) language toggle, end-aligned, 48 touch          [no title bar]
y 120-200  mark 72 dp (accent.container disc, redrawn Aron glyph; artwork rule Q-UI-13) + "ARON SR" type.title, centred
y 264-492  GLASS CARD 328 (radius.card 20, Card recipe, padding 20)
             ইউজারনেম             label type.caption
             [ sr334001                      ]  56 dp, radius.chip, border.input
             পাসওয়ার্ড
             [ ••••••••                 দেখান ]  56 dp, trailing text button 48
             (error banner slot, expands base)
y 640-700  সংস্করণ 1.0.25 · © AKTCL        type.caption, text.secondary, centred
y 704-800  BAR  [ লগইন করুন ]*  rides above the keyboard
```

**Primary action.** "লগইন করুন" in the bar; IME action Next then Done (Done submits). Disabled until both fields are non-empty (caption "ইউজারনেম ও পাসওয়ার্ড দিন।").

**Layout notes.** Username: ASCII keyboard, no auto-capitalise, autofill hint `username`; **not pre-filled** (shared phones). Password: show or hide toggle (new; the reference has none; reduces typos on a Bangla keyboard). The brand block collapses when free height is under 520 dp (keyboard open). Footer carries AKTCL branding, not the vendor credit (F-SR-001). The version comes from the single source also shown in Settings.

| State | What the user sees | Behaviour |
|---|---|---|
| Busy | button label stays, 20 dp arc, fields read-only | debounce 400 ms; no spinner overlay |
| Wrong credentials | `danger.container` banner, both field borders `danger`, one message that never says which was wrong | `Reject` haptic; focus to password |
| Locked, rate limited | `warning.container` banner with the minutes (plurals) | button disabled with the countdown caption |
| Offline, never online on this phone | `offline.container` banner, the only blocking case | login impossible without network once; chip shows offline |
| Offline, bound phone | no banner; works within 7 days of the last online login | the three offline failure texts (stale, wrong password, clock) appear as the same banner |
| Device not bound | card swaps to the OTP step (below) | `auth_error_bind_required` text above it |
| Update required | `warning.container` banner, button "আপডেট" opens the in-app update page | blocks a new login, never an open offline day |
| First bundle | full-screen progress: 6 dp meter, percent, one line | resumable; Back is ignored; ends with a 220 ms cross-fade to Home |
| Long Bangla | banners wrap, card grows (`heightIn`), never clipped at 2.0 | |

**OTP step (F-SR-002).** Same card: title "ডিভাইস যাচাই", hint, four boxes 56 x 64 dp with 12 gaps (width 260, centred), numeric keypad, one hidden text field behind them so paste, SMS autofill and TalkBack see a single field. Bar primary "যাচাই করুন" enabled at 4 digits. Wrong, expired and too-many-attempts texts replace the hint inside a `danger.container` line.

**Permission rationale (F-SR-003, before each system prompt, never microphone).** `GlassSurface(Sheet)`: 40 dp icon in `accent.container`, title, one line, primary "অনুমতি দিন", text button "পরে". Denied location later shows the Permission empty state with "সেটিংসে যান".

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

**Motion.** Card enters 220 ms (fade plus 8 dp); error banner expands over `motion.base`; OTP box fills with a 90 ms press scale. **Kit.** `AronBackground`, `GlassSurface(Card)`, `AronPrimaryButton`, `LanguageToggle`, `AronBanner`. Paint: 1 shadowed card, 0 lists.

## 2. Home (F-SR-008 to 010, 063, 064, 065, 069, 072)

**Purpose.** One look answers "how is my day going", one tap reaches every tool, and the dock offers the next step. Reads Room only, so it opens at once offline.

```
y  28-116  HEADER (collapsing)  <○ 48>  SR - Testing Banani (sr334001)          <⚙ 48>
                                         Apsis RouteDaily, 2026-10-05   (3F routes read "Name (Sun, Tue, Thu), date")
y 116-156  STATUS ROW (FlowRow, wraps to a 2nd line)  (☁ অফলাইন · ৩ অপেক্ষায়) (✓ চেক ইন ০৮:৫২) (Daily)
y 168-312  HERO CARD   আজকের সর্বমোট                                          ›
                       ৪,৩৯১.০০ ৳                    type.display 34/48
                       আউটলেট ভ্রমণ  ▕██░░░░░░░░░░▏  ৬/৬০
y 324-420  TILES  <অ্যাটেনডেন্স> <স্টক>     <বিক্রয়>        3 columns, 101 x 96, gap 12
y 432-528         <মেমো>        <সারসংক্ষেপ> <বিক্রয় জমা>
y 540-636         <আউটলেট>      <টিউটোরিয়াল> <টাস্ক ডেলিগেশন ②>
y 648-744         <বিক্রয় যাত্রা> <কেপিআই>              (56 dp show above the dock: scroll cue)
- - - below the fold - - -
          KPI ROW  (স্ট্রাইক রেট ১০%) (নন ভিজিট ৫৪) (বিক্রি নেই ০)       3 x 101 x 88
          STOCK CARD   স্টক:  ইস্যু | বর্তমান   লাইটার ৪০০ পিস | ২৫ পিস   ম্যাচ ৪০০ ডজন | ৩৯৪ ডজন
          MONEY CARD   আজকের বিক্রয়: ম্যাচ ১৪১.০০ · লাইটার ৪,৬৮৭.৫০ → মোট ৪,৮২৮.৫০ → সর্বমোট ৪,৩৯১.০০ ৳
          DEVICE LINE  ব্যাটারি ৬২% · খালি জায়গা ১.২ জিবি · অপেক্ষায় ৩ · শেষ সিঙ্ক ১০:৪২
y 704-800  DOCK  [ বিক্রয় শুরু করুন ]*
```

**Primary action: the next-step dock** (shortcut only; every target stays a tile, config-free, from local day state; a design default):

| Local day state | Dock label (BN / EN) | Opens |
|---|---|---|
| not checked in | চেক ইন / Check in | Attendance (a prompt, not a block: Q-UI-04) |
| checked in, no stock issued today | স্টক নিন / Take stock | Stock |
| in the field | বিক্রয় শুরু করুন / Start selling | Route list |
| route finished or after `cfg.day.checkout_earliest_time`, not submitted | বিক্রয় জমা / Sales submit | Sales Submit |
| submitted, not checked out, after 17:00 | চেক আউট / Check out | Attendance |
| day complete | no dock; hero shows a `success` chip "আজকের কাজ সম্পন্ন" | |

**Layout notes.**
- **Header** keeps `SR - <name> (<username>)` verbatim (F-SR-008, `type.heading`, up to 2 lines), caption `<route name (visit days)>, <ISO date>`, a generic initial avatar (no photo), gear 48 for Settings. With two or more routes the subtitle gets a drop-down arrow and opens a route sheet (F-SR-065). Collapses over the first 88 dp of scroll into a 64 dp `bar` with name, chip and gear. Route kind chip `Daily`, `3F`, `2F` is AKTCL's own wording (kept in English).
- **Hero** is the whole-card tap target to Summary; meter track `state.skeleton` (`#0B1B33` 7 percent), fill `accent`, 6 dp high, value also printed. Strike rate is successful calls over the day's target outlets (6 of 60 is 10 percent, UI-SR-09).
- **Tiles** exactly as `home.md` in order, with deferred ones removed: Attendance, Stock, Sale, Memo, Summary, Sales Submit, Outlet, Tutorial, Task Delegation, Sales Journey, KPI. Sales Journey and KPI open the assumed views (Q-UI-05, F-SR-067/068; KPI shows sales only, no target). Task badge is the open count (cap `৯৯+`). Tiles are shadowless; font scale 1.5 gives 2 columns.
- **KPI, stock and money cards.** Reference data is kept but the double "total" cards are merged into one money card with canonical words; reference `সর্ব মোট` (gross) is now `মোট` and `মোট টাকা` is now `সর্বমোট`. Sticks sold, pieces and dozens are listed as separate quantity lines under the card title (never added). Zero categories and zero deductions are hidden.
- **Device line** (F-SR-064) is caption text, no card. Warning (`warning` icon and word) under 20 percent battery or under 500 MB free (design defaults); **danger only under 200 MB** ("ফোনে জায়গা কম", the one danger case).

| State | What the user sees | Behaviour |
|---|---|---|
| Loading | header and tiles at once (local); hero and KPI skeleton only if Room takes over 150 ms | no spinner |
| No route today | info banner "আজকের জন্য কোনো রুট নির্ধারিত নেই"; tiles that do not need a route stay | hero shows `০.০০ ৳` |
| Bundle 1 day old | `warning` banner with the Dhaka time of the last bundle | selling continues, rows flagged stale (F-SR-063) |
| Bundle 3 days old | `warning` banner; Sale tile opens memos and dues read-only | check-in still works |
| Login expired, update required | `warning` banner with the existing keys (`home_reauth_required`, `home_update_required`) | never blocks an open day |
| Shared phone | first capture of a date with two bound users opens a dialog "আপনি কি <নাম> (<কোড>)?" (F-SR-072) | the other answer stores an acting-for id |
| Stats fail | the card shows "পরিসংখ্যান দেখানো যাচ্ছে না" and retry | tiles unaffected |
| Long Bangla, font 1.5 | tile labels wrap to 2 lines, 2 columns, header wraps | no clipping at 2.0 |

| Key | BN | EN |
|---|---|---|
| hero / meter | আজকের সর্বমোট / আউটলেট ভ্রমণ | Today's total / Outlets visited |
| chips | চেক ইন বাকি, চেক ইন ০৮:৫২ | Check-in due, Checked in 08:52 |
| KPI | স্ট্রাইক রেট, নন ভিজিট, বিক্রি নেই | Strike rate, Non-visit, No sale |
| stock card | স্টক, ইস্যু, বর্তমান স্টক | Stock, Issue, Current stock |
| money card | আজকের বিক্রয়, মোট, ডিসকাউন্ট, QC, সর্বমোট | Today's sales, Total, Discount, QC, Grand total |
| stale | রুটের তথ্য ১ দিন আগের (শেষ হালনাগাদ গতকাল ১৮:৩০)। বিক্রয় চলবে। | Route data is 1 day old (updated yesterday 18:30). Selling continues. |

**Motion.** Header collapse follows the finger (no animation of its own). Tile press 0.96. Dock label cross-fades 150 ms when the day state changes. **Kit.** `LargeHeader` (screen composable), `AronStatusChip(full)`, `AronTileGrid`, `AronTile`, `GlassSurface(Card)` x3, `AronBottomBar`. Paint: 3 shadowed cards, 11 shadowless tiles, 3 shadowless KPI tiles (within the 8 and 16 caps).

## 3. Attendance (F-SR-011, 012)

**Purpose.** Check in before work, check out from 17:00 Dhaka time, with one location reading each time.

```
y  28- 92  TOP BAR  <←> অ্যাটেনডেন্স                                  (☁)
y 108-196  IDENTITY CARD  <○> SR - Testing Banani (sr334001) / Apsis RouteDaily, 2026-10-05
y 208-336  LOCATION CARD  <📍> আপনার বর্তমান লোকেশন                    <⟳ 48>
                          ২৩.৭৯২৫৮, ৯০.৪০৭৮৩            (offline, always)
                          RC38+J3Q, Gulshan, Dhaka        (online, second line, display only)
                          (✓ GPS ±১৮ মি · ভালো)
y 348-480  DAY CARD   (✓) চেক ইন সম্পন্ন হয়েছে  ০৮:৫২
                      (○) চেক আউট  বিকাল ৫টার পরে চালু হবে
y 496-560  MESSAGE    আপনি এখনো চেক ইন করেননি। কাজ শুরু করার আগে অনুগ্রহ করে চেক ইন করুন।
y 704-800  BAR  [ চেক ইন ]*       (disabled: reason caption above)
```

**Primary action.** One primary in the bar that follows the day card: "চেক ইন" then "চেক আউট". Tap opens the hold sheet (below), never commits on tap (parity SR-S-13/14). The old side-by-side green and grey buttons become the two rows of the day card.

**Hold sheet** (`AronBottomSheet`; blurs 28 dp only on tier A, so never in the SR flavour, whose ceiling is tier B): title "চেক ইন করা হচ্ছে" with a `<login>` icon (`<logout>` for check-out), time chip "বিকাল ০৪:৪৭" from the corrected Dhaka clock, one line "লোকেশন ±১৮ মি", then `AronPressAndHoldButton` 72 dp tall, full width, radius full, track `accent.container`, fill `accent` sweeping start to end over **1,200 ms** (the kit's `holdMillis`), label "চাপ দিয়ে ধরে রাখুন". Early release: fill retreats in 150 ms, caption "আরও একটু ধরে রাখুন". TalkBack: double tap confirms directly. Reduced motion keeps the fill (it is progress, not decoration). On commit: `Confirm` haptic, sheet closes over 150 ms, the day card row flips to done with a 150 ms cross-fade.

| State | Day card and bar | Notes |
|---|---|---|
| A not checked in | rows: check-in open, check-out dim; bar "চেক ইন" | reference message |
| C checked in, before 17:00 | check-in done with time; check-out row `warning` clock icon "চেক আউট বিকাল ৫টার পরে চালু হবে"; bar disabled, reason "বিকাল ৫টার পরে চালু হবে" | enabling time is `cfg.day.checkout_earliest_time`, on corrected Dhaka time (F-SR-012) |
| D check-out open | bar "চেক আউট" | message "আপনি এখনো চেক আউট করেননি। আজকের কাজ শেষ করার আগে অনুগ্রহ করে চেক আউট করুন।" |
| F day complete | both rows done, bar hidden, `success` banner | "আপনি আজকের জন্য চেক ইন এবং চেক আউট সম্পন্ন করেছেন। সহযোগিতার জন্য ধন্যবাদ।" |
| Reading fix | location card arc "লোকেশন নেওয়া হচ্ছে…", up to `cfg.geo.fix_timeout_s` (15 s) | refresh icon disabled while reading |
| Fix quality | chip: at most 30 m `success` "ভালো"; 31 to 100 m `warning` "মোটামুটি"; over 100 m `warning` "দুর্বল" (design defaults; over `cfg.geo.max_accuracy_m` it cannot be geo-valid) | word plus icon plus number |
| No fix after timeout | `warning` line "লোকেশন পাওয়া যায়নি" plus retry | check-in stays possible, recorded `no_fix` (Q-SD-01) |
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

**Motion.** Refresh icon rotates once per read (functional, 1,200 ms linear while reading). **Kit.** `GlassSurface(Card)` x3, `AronStatusChip`, `AronBottomSheet`, `AronPressAndHoldButton`, `AronBottomBar`. Paint: 3 shadowed cards.

## 4. Stock (F-SR-013, 014, 015, 050, 081)

**Purpose.** Record what the distributor issued today per SKU, see category totals, save, and print the slip that is handed to the distributor's manager.

```
y  28- 92  TOP BAR  <←> স্টক                                  (☁) <🖨 36 warning disc, slashed>
y 100-140  COLUMN HEAD  এসকেইউ            ইস্যু (এখন যোগ হবে)           স্টক
y 148-...  ~ STICKY GROUP  লাইটার · পিস                                      (flat, surface.glass.strong 92%)
           ~ ROW 136:  [thumb 48]<৪০০ badge>  Aster                         স্টক
                                              আজ লোড ৪০০ পিস                ২৫ পিস
                       ( −)[ ০ ](＋) পিস
           ~ ROW ...   (about 40 SKUs today, designed for 60)
y 640-704  CATEGORY DOCK (collapsed)  (লাইটার ৪০০ পিস) (ম্যাচ ৪০০ ডজন) (সিগারেট ০ স্টিক)   tap: sheet
y 704-800  BAR  [ প্রিন্ট ]   [ সংরক্ষণ ]*
```

**Primary action.** "সংরক্ষণ" (60 percent, accent) in the bar, "প্রিন্ট" (40 percent, secondary with the printer state icon). Save is enabled only when an entered increment is non-zero; otherwise disabled with reason "কোনো পরিবর্তন নেই". Print is enabled only when saved and the printer is connected (F-SR-013); otherwise reason "আগে সংরক্ষণ করুন" or "প্রিন্টার কানেক্ট করুন". Save never waits for the printer (Q-UI-03 default).

**Row (flat, 136 dp, no shadow).** Line 1: 48 dp thumbnail (`radius.chip`, decoded at 96 x 96 px) with a 20 dp `accent.container` circle badge at its bottom end showing the **packs equivalent** (quantity over pack size; Q-UI-11 default; TalkBack "৬৫০ প্যাক"); SKU code `type.bodyStrong`; caption "আজ লোড ৪০০ পিস" (read-only total loaded today); at the end the derived stock `type.numeral` over its unit. Line 2: `AronStepper` for the increment: step is the pack size for sticks (10 or 20), 1 for pieces and dozens; tap the value to type; hold accelerates; value 0 after a save. Group headers (সিগারেট, বিড়ি, লাইটার, ম্যাচ) are sticky and carry the unit once. A sheet (`AronBottomSheet`) opens from the category dock with the full table: ক্যাটাগরি, মোট ইস্যু, স্টক per category, each with its unit. A top-bar overflow item "মোট সংশোধন" (F-SR-081) opens a reason sheet and posts a signed adjustment, never an overwrite.

| State | What the user sees | Behaviour |
|---|---|---|
| Saved | top `success` banner "সংরক্ষিত হয়েছে। স্লিপ এখনো প্রিন্ট হয়নি।" with text action "প্রিন্ট"; `Confirm` haptic; steppers reset to 0 | same-values re-save within the guard window (120 s) shows "এইমাত্র সংরক্ষণ হয়েছে" and does nothing |
| Printer not connected | top-bar icon `warning` with a diagonal line; tap reconnects | green banner "প্রিন্টার কানেক্ট করা হয়েছে" on success; permission rationale if Nearby devices is denied |
| Printing | secondary shows the arc, bar inert except back | success: slip flagged printed; failure: `warning` banner "প্রিন্ট হয়নি" with "আবার চেষ্টা" (stock saved either way) |
| Unprinted at day end | a `warning` caption on Sales Submit, not a block (`cfg.stock.require_printed_slip`, Q-UI-03) | |
| Typed value invalid | well `danger.container` plus message, not committed | `Reject` haptic |
| Empty (no SKUs in the zone plan) | `AronEmptyState` "আপনার জোনের জন্য কোনো SKU নেই" with synced-at | |

| Key | BN | EN |
|---|---|---|
| columns | এসকেইউ, ইস্যু, স্টক | SKU, Issue, Stock |
| totals | ক্যাটাগরি, মোট ইস্যু, স্টক | Category, Total issue, Stock |
| actions | সংরক্ষণ, প্রিন্ট | Save, Print |
| helper | আজ লোড | Loaded today |

**Motion.** Stepper press 0.92 and `VirtualKey` haptic; hold repeats 6, 15, 30 steps per second (android-glass 6.5); sheet spring. **Kit.** `AronListRow` (flat, `RowPosition`), `AronStepper`, `AronBottomSheet`, `AronBanner`. Paint: rows flat, 0 shadowed cards; 60 rows scroll inside the frame gate.

## 5. Route outlet list (Sale, step 1; F-SR-016, 074, 057)

**Purpose.** Pick the outlet to sell to from today's route. The old drop-down becomes a full list, because 40 to 200 outlets are easier to scan and tap than to open a picker. Programme dots are removed (docs/27); the row keeps a 0 dp leading slot, so a later column only widens it.

```
y  28- 92  TOP BAR  <←> বিক্রয়                                           (☁)
y 100-180  রিটেইলার নির্বাচন করুন            ৬/৬০ ভিজিট হয়েছে ▕█░░░░░░░▏
           (বাকি ৫৪) (ভিজিট হয়েছে ৬) (সব ৬০)              segmented, 40 dp, thumb slides
y 188-...  ~ ROW 72+:  বনানী স্টোর                                  ভিজিট হয়েছে · ৮৪.০০ ৳ ✓
                       DHK-344-003 · 01711000000 · Apsis Cluster     (বাকি ২৯১.৫০ ৳)
           ~ ROW ...    (flat rows, one rounded group per letter section)
y 656-704  ALPHABET DOCK  <🔍> (সব) (A) (B) (E) (J) (M) (ক) (খ) (#)  chips 48 wide, scrolls sideways
y 704-800  (system navigation inset only; no primary: tapping a row is the action)
```

**Primary action.** Tapping a row (full width, 72 dp or more). The filters sit in the dock at y 656 to 704, in thumb reach, instead of the top.

**Layout notes.** Rows: name `type.bodyStrong` up to 2 lines; caption "code · phone · cluster" (phone 11 digits normalised, ASCII, visible to this route's SR only, UI-SR-24; closed outlets hidden) up to 2 lines. Trailing: visited rows show "ভিজিট হয়েছে" with a `success` check and the memo total; skip outcomes show their word ("বন্ধ", "মালিক নেই"); a due shows a `warning.container` chip "বাকি ২৯১.৫০ ৳". Chips are case-insensitive for Latin and one per first Bangla letter, `#` for others (UI-SR-23, SRA-04); selected chip uses `accent.container` plus a check. The search icon turns the dock into a text field. "কাছের আগে" (nearest first) appears as a toggle chip only when `cfg.sale.sort_by_distance` is on and a fix exists (F-SR-074); default order A to Z.

| State | What the user sees | Behaviour |
|---|---|---|
| Empty route | `AronEmptyState` "আজকের রুটে কোনো আউটলেট নেই" hint "অনলাইনে এসে রুট আবার নামান", action "রুট আবার নামান" | synced-at caption |
| Filtered empty | "এই অক্ষরে কোনো আউটলেট নেই" with "সব দেখান" | |
| Stale bundle | `warning` banner as on Home | 3 days old: rows open read-only |
| Load error | `AronErrorState` "রুট লোড করা যায়নি", "আবার চেষ্টা করুন" | local data normally loads |
| Long Bangla name | wraps to 2 lines, row grows | never ellipsis on the second line of a button or chip |
| Two routes planned | route name in the subtitle with a drop-down (F-SR-065) | |

| Key | BN | EN |
|---|---|---|
| subtitle, segments | রিটেইলার নির্বাচন করুন; বাকি, ভিজিট হয়েছে, সব | Select a retailer; Remaining, Visited, All |
| row words | ভিজিট হয়েছে, বাকি ২৯১.৫০ ৳, কাছের আগে | Visited, Due 291.50 ৳, Nearest first |
| empty | আজকের রুটে কোনো আউটলেট নেই; অনলাইনে এসে রুট আবার নামান | No outlets on today's route; come online and download the route again |
| filtered | এই অক্ষরে কোনো আউটলেট নেই | No outlet starts with this letter |

**Motion.** Row press is an instant `state.pressed` overlay (no scale). Row to outlet card is the one shared-element transition (220 ms). No list entrance stagger. **Kit.** `AronListRow` (flat), `AlphabetDock` (screen composable on the bar recipe), `AronStatusChip`. Paint: 0 shadowed cards, 1 bar.

## 6. Visit open and geo check (F-SR-017, 018, 019, 057, 060, 049, 054)

**Purpose.** Prove the rep is at the outlet with one on-device fix, or record why not. Everything runs in airplane mode; the visit row exists at once, the fix is read, the verdict decides (`VisitFlow`: Idle, ReadingFix, NeedsDecision, Open, LocationBlocked, CommitFailed, Blocked).

```
y  28- 92  TOP BAR  <←> বনানী স্টোর (2 lines allowed)                       (☁)
y 104-236  OUTLET CARD  বনানী স্টোর · মালিক: রহিম উদ্দিন
                        DHK-344-003 · 01711000000 · Apsis Cluster
                        (GT) (বাকি ২৯১.৫০ ৳) (শেষ ভিজিট ২ দিন আগে)
y 248-420  GEO CARD (out of range, warning.container)
             <⚠> আপনি নির্বাচিত খুচরা বিক্রেতার সীমার মধ্যে নেই।
             আনুমানিক ১৮০ মি দূরে · সীমা ১০০ মি · GPS ±১২ মি          [ম্যাপ দেখুন]
             রিফ্রেশ বাকি: ২ বার
y 432-480  [পূর্বের বিক্রয় দেখুন ›]   (text button)         [বিক্রয় হবে না ›] (text button)
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

**Force Sale sheet** (`AronBottomSheet`, title "ফোর্স সেল প্রক্রিয়া", `warning` icon): question "আপনার ফোর্স সেলের কারণ কী?", two radio rows 56 dp "ইন্টারনেট সমস্যা" / "লোকেশন চেঞ্জ" (exactly one, required), line "ছবির লোকেশন আউটলেটের ঠিকানা সংশোধনের অনুরোধ হিসেবে যাবে।", sticky primary "ছবি তুলুন" (disabled until a reason is chosen, reason shown). Camera opens `GeoPhotoCapture` (one fix at the shutter, thumbnail, "GEO captured ±n m", one retake, compressed to at most 200 KB; F-SR-079). Back on the gate: card reads "ফোর্স সেল: ছবি সংরক্ষিত" (`photo_validated` true, `geo_validated` false, never a clean geo sale), bar primary "কল শুরু করুন". Camera denied: Permission empty state. Map (N-041): "ম্যাপ দেখুন" loads the map only on tap, shows pin, radius circle and own position; a map failure never blocks Force Sale.

**Outcome sheet** "বিক্রয় হবে না" (F-SR-057, no fix needed): radio rows দোকান বন্ধ (closed), মালিক নেই (owner absent), বিক্রয়ে রাজি নন (refused), প্রতিযোগীর এক্সক্লুসিভ (competitor exclusive), পৌঁছাতে পারিনি (not reached); primary "সংরক্ষণ". The row on the list shows the outcome word.

**Sale history** (F-SR-054): text button opens a sheet with a date strip and a table (SKU, quantity with unit, value, footer equal to the sum of rows) from the local 7-day window; older dates need a connection and say so in an `offline` banner.

| Key | BN | EN |
|---|---|---|
| refresh / force | রিফ্রেশ / ফোর্স সেল | Refresh / Force Sale |
| reasons | ইন্টারনেট সমস্যা / লোকেশন চেঞ্জ | Internet problem / Location changed |
| start | কল শুরু করুন | Start call |
| distance | আনুমানিক ১৮০ মি দূরে · সীমা ১০০ মি | About 180 m away · limit 100 m |
| in range | আপনি আউটলেটের সীমার মধ্যে আছেন। | You are within the outlet's range. |
| out of range | আপনি নির্বাচিত খুচরা বিক্রেতার সীমার মধ্যে নেই। | You are not within the selected retailer's range. |
| force question | আপনার ফোর্স সেলের কারণ কী? | Why are you doing a force sale? |
| location off | লোকেশন বন্ধ আছে। চালু করলে বিক্রয় শুরু করা যাবে। | Location is off. Turn it on to start selling. |
| mock | নকল লোকেশন (Fake GPS) পাওয়া গেছে। এটি বন্ধ করে রিফ্রেশ করুন। | Mock location found. Turn it off, then Refresh. |
| skip reasons | দোকান বন্ধ, মালিক নেই, বিক্রয়ে রাজি নন, প্রতিযোগীর এক্সক্লুসিভ, পৌঁছাতে পারিনি | Shop closed, Owner absent, Refused, Competitor exclusive, Could not reach |

**Motion.** Geo card cross-fades between verdicts over `motion.fast` (width and height by `animateContentSize`); `Reject` haptic once on out of range, `Confirm` once on in range; the outlet card is the destination of the list's shared element. **Kit.** `OutletCard` (existing), `GeoStatusCard` (screen composable on `GlassSurface(Card)`), `AronBottomSheet`, `AronBottomBar`. Paint: 2 shadowed cards.

## 7. Sale: quantity entry (F-SR-023, 050)

**Purpose.** Enter what the shopkeeper wants, by SKU, in the SKU's own unit, with a running total and a stock warning that never blocks.

```
y  28- 92  TOP BAR  <←> বিক্রয় · বনানী স্টোর                            (☁)
y 100-148  [পূর্বের বিক্রয় দেখুন ›]            <🔍>
y 148-196  CATEGORY CHIPS  (সব) (সিগারেট ২) (বিড়ি) (লাইটার) (ম্যাচ)     jump links, scroll sideways
y 204-...  ~ GROUP  এই আউটলেটে আগে বিক্রি
           ~ ROW 136:  [thumb 48]<২৬ badge>  MaxR-10S                        ১৬০.০০ ৳ (line total)
                                              ৮.০০ ৳ / স্টিক · স্টকে ৩৯৪
                       ( −)[ ২০ ](＋) স্টিক
           ~ GROUP  সিগারেট · স্টিক   ...   (60 SKU scroll)
y 704-800  BAR  মোট            [ এগিয়ে যান → ]*
                ২৮০.৫০ ৳
```

**Primary action.** "এগিয়ে যান →" at the end of the bar; the running gross total sits at the bar's start (caption "মোট", `type.numeral`). With no line entered it opens the zero-sale confirm (reference text, `AronConfirmDialog`: "বিক্রয়ের জন্য কোনও SKU নির্বাচন করা হয় নি" / "আপনি কি জিরো (০) বিক্রয় করতে চান?", হ্যাঁ and না), because a zero sale consumes a memo number and counts as a visit (F-SR-029).

**Row.** Same two-line flat row as Stock. Stepper step is the SKU's pack step for sticks, 1 for pieces and dozens; hold accelerates; tap the value to type (Bengali or ASCII digits, stored ASCII). A line with quantity above 0 shows its line total and `accent.container` fill (the total is the non-colour cue). Unit price caption always carries the unit. **Recent-first group:** SKUs sold to this outlet in the last 7 local days are listed first as a jump aid (no pre-filled quantities, so nothing can be sold by mistake). The reference's purple badge is the packs equivalent (Q-UI-11); the three unlabelled icon rows of the old card are not reproduced (meaning unknown, F-SR-023 assumes hidden). A per-outlet suggested quantity (docs/05) shows as a chip "প্রস্তাবিত ৫০০" under the stepper only if the bundle carries one (not today). The "স্লাইড সংগ্রহ" secondary appears in the bar only when the bundle has an active offer (none today, docs/27).

| State | What the user sees | Behaviour |
|---|---|---|
| Over stock | well `warning.container`, `warning` icon, caption "স্টকে আছে ৩৯৪; এর বেশি বিক্রি হচ্ছে।" | sale still allowed (F-SR-023), no haptic |
| At max (`cfg.sale.max_line_qty_base`) | plus disabled, caption "সর্বোচ্চ" | one `Reject` haptic when hold hits it |
| 60-line limit | banner "একটি মেমোতে সর্বোচ্চ ৬০টি লাইন।", plus disabled on SKUs with 0 | |
| Typed invalid | well `danger.container`, not committed | |
| Resumed draft | one-time banner "আপনার অসমাপ্ত বিক্রয় ফিরিয়ে আনা হয়েছে।" | draft survives kill and relaunch |
| Stale 3-day bundle | read-only, banner | no stepper |
| Search, no match | "কোনো SKU মেলেনি" with clear | |

| Key | BN | EN |
|---|---|---|
| history / proceed | পূর্বের বিক্রয় দেখুন / এগিয়ে যান | View previous sales / Proceed |
| total / badge | মোট / প্যাক | Total / packs |
| units | স্টিক, পিস, ডজন | sticks, pieces, dozens |

**Motion.** Line total fades in 150 ms; stepper rules as Stock; the bar total cross-fades. **Kit.** `AronListRow` (flat), `AronStepper`, `AronBottomBar` (summary slot), `AronConfirmDialog`. Paint: 0 shadowed cards; sale save to Room in one transaction under 300 ms.

## 8. Review (নিরীক্ষণ), credit and Product QC (F-SR-025, 026, 027, 028)

**Purpose.** Check lines and money, optionally mark credit with a part payment, add QC, then commit the immutable memo (and print).

```
y  28- 92  TOP BAR  <←> নিরীক্ষণ                                         (☁) <🖨 36>
y 100-160  বনানী স্টোর (C-1042)  ·  রুট: Apsis RouteDaily · ক্লাস্টার: Apsis Cluster
y 168-...  LINES CARD  এসকেইউ | পরিমাণ | মূল্য        (rows 48, category subtotal rows 40)
                       MaxR-10S   ২০ স্টিক   ১৬০.০০
                       মোট সিগারেট ২০ স্টিক  ১৬০.০০ ...
           TOTALS CARD   মোট ৩৬০.৫০ ; ডিসকাউন্ট − ... ; স্লাইড − ... ; QC − ... (non-zero only)
                         সর্বমোট  ৩৬০.৫০ ৳   (type.display 34/48)
           CREDIT ROW (56)  [switch] এই বিক্রয়টি বাকি হিসাবে চিহ্নিত করুন
           QC ROW (56)      প্রোডাক্ট QC ›    (secondary)
           caption          আপনি পরে মেমো সেকশন থেকে প্রিন্ট করতে পারবেন।
y 704-800  BAR  সর্বমোট ৩৬০.৫০ ৳      [ প্রিন্ট ]*
```

**Primary action.** "প্রিন্ট" (parity label, printer icon) at the bar's end with the net at its start. Tap opens `AronConfirmDialog` "আপনি কি নিশ্চিত?" / "বিক্রয় জমা হবে" (হ্যাঁ, না): the one irreversible step (the memo becomes immutable and is committed **before and regardless of printing**). After হ্যাঁ a `Confirm` haptic and a sheet: "বিক্রয় সফলভাবে জমা হয়েছে" with "আপনি কি এই বিক্রয়টি প্রিন্ট করতে চান?" and primary "প্রিন্ট করুন", text button "পরে" (memo stays reprintable from Memo). Zero sale uses the same screen with one row "সর্বমোট ০.০০" and no credit row (F-SR-029).

**Credit sheet** (from the switch): title "পরিশোধিত টাকার পরিমাণ লিখুন", body "এখন আপনি রিটেইলার থেকে কত টাকা সংগ্রহ করছেন?", field "আদায়কৃত অর্থ" (numeric, two decimals, 56 dp) and a live line "বাকিঃ ৬১.৫০" in `type.numeral`; primary "নিশ্চিত করুন". Rule: at least 0 and strictly below the grand total; error "আদায়ের অঙ্ক মোটের চেয়ে কম হতে হবে।". After confirming, the row label reads "বাকি ৬১ টাকা" with the switch on. Credit and QC can be done in either order.

**Product QC (opened from the QC row, F-SR-027).** Step 1: an info card "QC এ প্রবেশ করতে, আপনি যে পণ্যটি যোগ করতে চান সেটি নির্বাচন করুন" and a list of this memo's SKUs (a list, not the old sideways carousel; empty: "QC করার জন্য কোনো পণ্য নেই"). Step 2, QC entry: card with "সর্বোচ্চ QC", "QC হয়ে গেছে", "বাকি আছে" (taka), two groups of rows each with a stepper in sticks: **উৎপাদন ত্রুটি** (ড্যামেজড ও ক্রাশড – প্যাক / আউটার CBC; আউটার / প্যাক / স্টিক কম থাকা; অন্যান্য ত্রুটি) and **পরিবহন ত্রুটি** (মেয়াদোত্তীর্ণ স্টক (৪ মাস+); স্টক ড্যামেজড – রুট সার্ভিস কালীন; স্বাদ সংক্রান্ত সমস্যা); bar secondary "বাতিল", primary "সংরক্ষণ", text "মুছুন". Step 3, QC summary: table SKU, QC type, quantity; "মোট QC নিষ্পত্তি", "সেটেলমেন্ট পরিমাণ"; primary "QC জমা দিন" with a confirm. Result on Review: a `QC − ৮০.০০` line and a blocked-edit note. The maximum-QC basis and deduction rule are unknown (MQ-03, MQ-04).

| State | What the user sees | Behaviour |
|---|---|---|
| Printer off | chip `warning`; sheet still offers "পরে" | memo saved, `printed_at` empty |
| Print failed | banner "প্রিন্ট হয়নি। প্রিন্টার চালু ও কানেক্ট আছে কিনা দেখুন।" with "আবার চেষ্টা" | `Reject` haptic; no duplicate marker on the retry |
| Net negative (zero sale plus QC) | `warning` caption "এটি একটি ক্রেডিট হিসেবে সংরক্ষিত হবে" | `cfg.memo.allow_negative_net` |
| Draft after kill | Review reopens with lines, credit and QC intact | |
| Many lines | list scrolls, totals card follows the last line, bar fixed | |

| Key | BN | EN |
|---|---|---|
| title, rows | নিরীক্ষণ; এসকেইউ, পরিমাণ, মূল্য | Review; SKU, Quantity, Value |
| money | মোট, ডিসকাউন্ট, স্লাইড, QC, সর্বমোট | Total, Discount, Slide, QC, Grand total |
| credit | এই বিক্রয়টি বাকি হিসাবে চিহ্নিত করুন; বাকি ৬১ টাকা | Mark this sale as credit; Due 61 taka |
| collected | আদায়কৃত অর্থ; আদায়ের অঙ্ক মোটের চেয়ে কম হতে হবে। | Amount collected; The amount collected must be less than the total. |
| commit | আপনি কি নিশ্চিত? বিক্রয় জমা হবে | Are you sure? The sale will be submitted. |
| done | বিক্রয় সফলভাবে জমা হয়েছে; আপনি কি এই বিক্রয়টি প্রিন্ট করতে চান? | Sale submitted successfully; Do you want to print this sale? |
| QC | প্রোডাক্ট QC, QC সারাংশ, QC জমা দিন | Product QC, QC summary, Submit QC |

**Motion.** Totals cross-fade 150 ms; credit sheet spring; dialog 220 ms scale 0.94 to 1. **Kit.** `GlassSurface(Card)` x2, `AronListRow`, `AronBottomSheet`, `AronConfirmDialog`, `AronStepper`. Paint: 2 shadowed cards.

## 9. Memo and print (F-SR-028, 030, 031, 032, 033, 066, 073)

**Purpose.** Look at a saved memo of today, reprint it, mark a credit memo paid, or edit it.

```
y  28- 92  TOP BAR  <←> মেমো                                              (☁) <🖨 36>
y 100-148  INFO BANNER (dismissible per day)  আপনি আউটলেট নির্বাচন করে একটি মেমো দেখতে পারবেন...
y 156-216  SELECTOR ROW  বনানী স্টোর · মেমো ০০১২ · ১০:৪২ · ৮৪.০০ ৳ (বাকি)        <▾>  → sheet list
y 228-...  MEMO CARD (receipt look)   মেমো নং ০০১২ · ১০:৪২                      (অনুলিপি) on reprint
              এসকেইউ | পরিমাণ | দাম     FB  ৩ ডজন  ৮৪.০০ ...  মোট ম্যাচ ...
              মোট ৮৪.০০ ; মোট ডিসকাউন্ট − ; মোট QC − ; সর্বমোট ৮৪.০০ ৳
              CREDIT BOX (warning.container)  এই বিক্রয়টি বাকিতে করা হয়েছে।     [পরিশোধিত করুন]
y 704-800  BAR  [ এডিট ]   [ প্রিন্ট ]*
```

**Primary action.** "প্রিন্ট" (reprint) at the bar; "এডিট" secondary. The selector is a bottom sheet list of today's memos (name, memo number or time to tell several memos of one outlet apart, total, a due chip) with the alphabet dock inside it, so the screen stays one-handed; it is a query on local memos for the business date.

**Rules and states.**
- **Reprint** prints with the duplicate marker "অনুলিপি (ডুপ্লিকেট)" and counts toward `cfg.memo.reprint_max` (5); at the limit the button is disabled with reason "পুনঃমুদ্রণের সীমা শেষ" (F-SR-031). An edited memo prints "প্রতিস্থাপিত মেমো <নং>" with its own number (F-SR-066).
- **Discount table** (SKU, quantity, value, total) renders only when discount lines exist; the reference rows `SL Match 3 / 0.00` stay an open question (Q-UI-01).
- **Mark paid.** "পরিশোধিত করুন" (48 dp button inside the credit box) opens `AronConfirmDialog` "আপনি কি নিশ্চিত?", then a `success` banner "এই মেমোটি সফলভাবে পরিশোধিত হিসেবে চিহ্নিত হয়েছে।"; the due chip disappears from the row. Whole-memo settlement, as today.
- **Edit.** Enabled only inside the outlet geofence and before QC; otherwise disabled with the reason "আউটলেটের সীমার মধ্যে যান" or "QC হয়ে গেছে, এডিট করা যাবে না". Opens a reason sheet "দয়া করে বিক্রয় এডিট করার কারণ লিখুন" with the three configured reasons as radio rows (first: "ভুল SKU নির্বাচিত।"), actions "বাতিল" and "জমা দিন"; then the Sale screen opens pre-filled with the outlet read-only, and the new memo supersedes the old.

**Print flow (shared by Review, Memo, Stock, Summary):**

| Step | Surface | Copy (BN / EN) |
|---|---|---|
| Connect | top-bar printer chip, tap to connect; auto-reconnect | প্রিন্টার কানেক্ট করা হয়েছে / Printer connected |
| Printing | secondary or primary shows the arc, about 3 to 8 s on a 58 mm slip | মেমো প্রিন্ট হচ্ছে… / Printing the memo… |
| Check | one-tap row under the bar: two chips | ছাপা ঠিক আছে? [হ্যাঁ] [না, আবার ছাপুন] / Is the print readable? [Yes] [No, print again] |
| Not connected | `warning` banner, never a dialog | প্রিন্টার কানেক্ট নেই। মেমো সংরক্ষিত আছে, পরে প্রিন্ট করতে পারবেন। / Printer not connected. The memo is saved; print later. |
| Paper out, cover open | `warning` banner with printer status | কাগজ শেষ বা ঢাকনা খোলা আছে। / Out of paper or cover open. |

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

**Motion.** Selector sheet spring; credit box collapses over `motion.base` after paying. **Kit.** `GlassSurface(Card)`, `AronBottomSheet`, `AronConfirmDialog`, `AronBanner`, `AronBottomBar`. Paint: 1 shadowed card.

## 10. Day summary (F-SR-036)

**Purpose.** The rep's own whole-day sales, per SKU, then per category, then the day total, with what goes back to the distributor; printable as the day-summary slip. A live local aggregate, offline.

```
y  28- 92  TOP BAR  <←> বিক্রয় সারসংক্ষেপ                                 (☁) <🖨 36>
y 100-156  INFO BANNER  আপনার পুরো দিনের বিক্রয় বিবরণ এখানে থাকবে
y 164-...  ~ GROUP HEAD  লাইটার · পিস
           ~ SKU ROW 112   Aster                                         ৪,৬৮৭.৫০ ৳
                           মেমো ৪    পরিমাণ ৩৭৫ পিস    ফেরত ২৫ পিস
                           ডিসকাউন্ট − ৪৩৭.৫০ · ডিসকাউন্টের পর ৪,২৫০.০০         (non-zero only)
           ~ GROUP FOOT   লাইটার মোট  ৩৭৫ পিস · ৪,৬৮৭.৫০ · ৪,২৫০.০০ · ফেরত ২৫
           TOTALS CARD    ডিসকাউন্ট এবং অন্যান্য (−) ৪৩৭.৫০  [›]   সর্বমোট ৪,৩৯১.০০ ৳ (display)
y 704-800  BAR  [ প্রিন্ট ]*
```

**Primary action.** "প্রিন্ট" (full width) prints the day-summary slip. **Layout.** The six reference columns (মেমো, পরিমাণ, মূল্য, ডিসকাউন্ট, ডিসকাউন্ট মূল্য, ফেরত) are kept as the card fields above; a 6-column table does not fit 328 dp and a sideways-scrolling table cannot be read in sun. Memo count is memos containing the SKU (UI-SR-34); return is issued minus sold, per SKU, in its unit. "ডিসকাউন্ট এবং অন্যান্য (−)" sums offer discount, slide and QC; tapping it expands the non-zero components (UI-SR-32). Quantities are never added across units; each group foot has its own unit.

| State | What the user sees | Behaviour |
|---|---|---|
| No sales yet | the banner plus `AronEmptyState` "আজ এখনো কোনো বিক্রয় হয়নি" | print disabled, reason "বিক্রয় হলে চালু হবে" |
| Printer off | chip and banner as the print flow | |
| Long list (40 SKUs) | flat rows, sticky group heads | |

| Key | BN | EN |
|---|---|---|
| columns | মেমো, পরিমাণ, মূল্য, ডিসকাউন্ট, ডিসকাউন্ট মূল্য, ফেরত | Memos, Quantity, Value, Discount, Discounted value, Return |
| totals | ডিসকাউন্ট এবং অন্যান্য (-), সর্বমোট | Discount and others (-), Grand total |
| empty | আজ এখনো কোনো বিক্রয় হয়নি | No sales yet today |

**Motion.** Expand of the components line 220 ms. **Kit.** `AronListRow` (flat), `GlassSurface(Card)` totals, `AronBottomBar`. Paint: 1 shadowed card.

## 11. Sales Submit (F-SR-034, 035)

**Purpose.** Confirm that what is on the phone equals what the server holds, then close the day. Upload already happens in the background as soon as a connection exists (UI-SR-35), so this screen reads as a reconciliation, with Sync data as a manual retry.

```
y  28- 92  TOP BAR  <←> বিক্রয় জমা                                       (☁ compact)
y 104-144  ডিভাইস স্ট্যাটাস   (● অনলাইন · শেষ যোগাযোগ ১০:৪২)       derived from last server contact
y 152-196  PROGRESS  ① সিঙ্ক  ─  ② মেলানো  ─  ③ জমা          3 steps, current one accent
y 204-440  RECONCILE CARD   বিষয়     ডিভাইস   সার্ভার
                            আউটলেট      ৬        ৬     ✓
                            মেমো         ৬        ৫     ⏳ অপেক্ষায়
                            বিক্রয় (টাকা) ৪,৩৯১.০০  ৪,৩৯১.০০ ✓
                            স্টক লাইন     ২        ২     ✓
                            কিউসি          ০        ০     ✓
                            [আরও দেখুন ▾] (attendance, outlet requests, money per category)
y 452-528  INFO  আজকের সব তথ্য সিঙ্ক হলে বিক্রয় জমা দেওয়া যাবে। সিঙ্ক নিজে থেকেই চলছে।
y 704-800  BAR  [ ডাটা সিঙ্ক করুন ]   [ বিক্রয় জমা ]*     (disabled: reason caption above)
```

**Primary action.** "বিক্রয় জমা" (60 percent). The secondary "ডাটা সিঙ্ক করুন" retries. The reference's red notice becomes the calm `accent` info line in the wireframe and turns `warning` only when sync is stuck past the config threshold.

**Reconciliation.** Rows compare **record counts per entity** and **money per category in milli-taka** (never unit-less sums, UI-SR-36); the legacy five rows are kept for parity (আউটলেট, বিক্রয়, স্টক, কিউসি, প্রমোশন), where "প্রমোশন" shows only when it has a value (discount programmes are deferred). Each row carries an icon and a word, not colour alone: ✓ "মিলেছে", ⏳ "অপেক্ষায়", ⚠ "মিলছে না". Submit is enabled only when every record is acknowledged and the counts match; dues warn and never block (Q-UI-08 default).

| State | What the user sees | Behaviour |
|---|---|---|
| Syncing | step ① active, rows update live, bar disabled "সিঙ্ক চলছে… ৩টি বাকি" | polite live region, at most once per 10 s |
| All matched | step ② done, `success` line "সব তথ্য মিলেছে", bar enabled | |
| Offline | chip `offline`; primary stays enabled and reads "বিক্রয় জমা (সংযোগ পেলে যাবে)" | the submit queues as the last event of the day (`cfg.day.sales_submit_offline_queue`) and shows "অপেক্ষায়" until the server settles |
| Mismatch after full sync | `warning` card "মিলছে না: মেমো ডিভাইসে ৬, সার্ভারে ৫" with "আবার সিঙ্ক" and "সাপোর্টকে ডাটা পাঠান" | submit stays disabled, mismatch never silent |
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

**Motion.** Counts cross-fade 150 ms; step connector fills 220 ms; success disc scales 0.9 to 1 with a fade (none when reduced). **Kit.** `GlassSurface(Card)`, `AronStatusChip(device)`, `AronBanner`, `AronBottomBar`, `AronConfirmDialog`. Paint: 1 shadowed card.

## 12. Tasks (F-SR-046, 047)

**Purpose.** Tasks the AMO or TSO assigned; resolve them offline. A push message is only a nudge: the task arrives with the next sync (UI-SR-43).

```
y  28- 92  TOP BAR  <←> টাস্ক                                            (☁)
y 100-148  (চলমান ৩) (সম্পন্ন ২)                      segmented, 40 dp
y 156-...  TASK CARD (glass, 20 radius, 120)  (OOS)  Babu Store                      (চলমান)
                    Babu Store দোকানে Maxim এ OOS আছে
                    <⚑> শেষ তারিখ ৩০ নভেম্বর             [সমাধান করুন]   swipe →
           ...
y 704-800  (no primary; the card's own action is the primary)
```

**Primary action.** Swipe the card right past 40 percent of its width (parity, `Confirm` haptic), or tap "সমাধান করুন" (48 dp text button at the card's end; discoverable and TalkBack-friendly). No dialog and no note (parity); the resolved card stays in place with a `success.container` "সম্পন্ন" chip, and the resolve is queued offline with a client UUID. Ongoing uses `warning.container` with a clock icon; overdue adds "মেয়াদ পেরিয়েছে" (icon plus word).

| State | What the user sees | Behaviour |
|---|---|---|
| Empty | `AronEmptyState` "আপনার এএমও (AMO) কোনো কাজ বরাদ্দ করেনি।" and "সিঙ্ক হয়েছে ১০:৪২" | so an empty list is not mistaken for a stale one |
| New task by push | badge on the Home tile rises after sync; the list shows it at the top | no polling |
| Resolved offline | chip "সম্পন্ন · অপেক্ষায়" until acknowledged | no undo (immutable, a new event) |
| Long text | description wraps to 4 lines then "আরও দেখুন" | |

| Key | BN | EN |
|---|---|---|
| title, segments | টাস্ক; চলমান, সম্পন্ন | Task; Ongoing, Completed |
| action, date | সমাধান করুন; শেষ তারিখ ৩০ নভেম্বর | Resolve; Completion on 30 Nov |
| empty | আপনার এএমও (AMO) কোনো কাজ বরাদ্দ করেনি। সিঙ্ক হয়েছে ১০:৪২ | Your AMO has not assigned any task. Synced 10:42 |

**Motion.** Swipe follows the finger, the action panel reveals `success.container` with a check; settle 150 ms. **Kit.** `TaskCard` (screen composable on `GlassSurface(Card)`), `AronEmptyState`. Paint: up to 8 shadowed cards visible (cap 8); beyond that cards turn flat.

## 13. Open items and defaults taken (log to DECISIONS.md)

| ID | Item | Default taken |
|---|---|---|
| Q-SD-01 | Is check-in allowed when no fix arrives in 15 s? | Yes, recorded `no_fix`, visible to the supervisor (offline-first: no sensor wait blocks the day) |
| Q-SD-02 | Home dock "next step" shortcut | Built as a shortcut only, no new function; owner may switch it off in review |
| Q-SD-03 | Refresh as the primary and Force Sale as the secondary while refreshes remain | Honest path first; reference had them reversed |
| Q-SD-04 | Hold duration for check-in and check-out | 1,200 ms, the kit default; the reference never states it |
| Q-SD-05 | Accuracy bands (30 m, 100 m) on Attendance | Design defaults; 100 m is `cfg.geo.max_accuracy_m` |
| Q-SD-06 | The new Bangla strings above (skip outcome labels, hold sheet, banners, tooltips) | Need native-reviewer sign-off before string freeze |
| Q-UI-01, 03, 04, 05, 08, 10, 11 | memo discount rows, printer-blocked Save, check-in gate, Sales Journey and KPI tiles, Sales Submit rule, Return column acknowledgement (printout only), pack badge | Defaults of `docs/ui-reference/questions.md` are used as written above |
| Q-SD-07 | Number grouping on money (`12,34,567` or `1,234,567`) | `LocaleDigits` stays the single source (tokens s10 item 1) |
