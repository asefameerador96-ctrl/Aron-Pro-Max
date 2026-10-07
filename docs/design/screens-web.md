# Screens: web dashboard home and admin radius page (Calm Glass), v1

Status: v1, refined 2026-10-07 (changes: `docs/design/CHANGELOG.md`); pending owner approval. Two screens only; the preview shows both in light, dark, sunlight ("High contrast"), tiers A, B and C, and at 100 to 200 percent text. Inputs: `docs/32`, `docs/design/tokens.md` v1 (wins on any value), `docs/design/web-glass.md` (shell, tiers, components, charts, states; cited as "wg s7.3" and so on), `docs/31` s1 (dashboards p95 at most 1.5 s), `docs/09` and `docs/10` (what the dashboard reads), `docs/19` (radius key and risk), `docs/27` (no target, Astha, Superstar or discount cards). Class names are the Tailwind utilities of `tokens.md` s9 and `wg` s2.5 (`glass-card`, `glass-data`, `glass-panel`, `text-hero`, `bg-accent-container`, `outline-focus`, `border-outline`; **`text-accent` is the deep text blue, `bg-accent-graphic` the brighter blue for meters and icons**). Existing keys and strings in `web/src/lib/i18n` are reused; **new Bangla needs native-reviewer sign-off (docs/20 role NB)**. Figures in the examples are the seeded one-zone day (`৪,৩৯১.০০ ৳`); grouping of larger sums follows `LocaleDigits` (tokens s10 item 1).

## 1. Dashboard home for a manager (`/`, F-WEB-001)

**Who and why.** TSO, DMO and head-office roles, and an AMO on a phone; the scope is derived on the server from the token and shown read-only in the top bar. At 1366 x 768 the first screen must answer, in this order: (1) how much did we sell and how is it moving, (2) is the field working (visits, strike rate, geo-valid), (3) is the day closing (login, submit, final submit), (4) who needs me now. Everything reads stored aggregates, renders on load with no button press, and streams card by card. Trimmed by docs/27: no target, achievement band, Astha, Superstar or discount card; the layout leaves a row for them.

```
 1366 x 768, content 1,038 wide, 12 columns, gap 24, page padding 32, top bar 56
 sidebar 264 | top bar: (টেরিটরি: বনানী) | search Ctrl+K | (● লাইভ) | বাংলা|EN | theme | user
 ------------+---------------------------------------------------------------------------
             | ড্যাশবোর্ড  (text-display)   [আজ|গতকাল|তারিখ]  ডেটা ০৮:৪১ পর্যন্ত  [ফিল্টার ৩] [হালনাগাদ] [রপ্তানি]
             | info banner (only before the first sync of the day; adds 80 and pushes the rows down)
 A  y 152    | [HERO span 6 (507 x 168)           ][স্ট্রাইক রেট span 3][আউটলেট ভ্রমণ span 3]
             | [বিক্রয় (সর্বমোট)                   ][ ১০%                ][ ৬/৬০             ]
             | [ ৪,৩৯১.০০ ৳   text-hero 48/56     ][ ▕█░░░░▏ basis     ][ ▕█░░░░▏          ]
             | [ মোট ৪,৮২৮.৫০ ৳ · মেমো ৬  (▲ +২.৩%) ~~sparkline~~ ]
 B  y 344    | [জিও সঠিক %  ][লগইন %     ][সাবমিট %    ][ফাইনাল সাবমিট ৩/৫ জোন]   span 3 each, 128
 C  y 496    | [ বিক্রয়, মাসের শুরু থেকে (line) span 8 (684) ][ চ্যানেল (ranked bars) span 4 (330) ]
 D           | [ জোন র‍্যাঙ্কিং (ranked bars) span 8          ][ এখনই দেখুন (list) span 4          ]
 E           | [ টিম ম্যাপ span 12, 360 high: poster plus "মানচিত্র দেখান" ]
```

**Reading order and weight.** One hero only (`text-hero`, 48 px, the net sales figure; wg s7.3); every other number is `text-display` (34/40, Bangla 34/48). Large-text roles stop growing at 1.5 times (tokens s7.1), so at 200 percent the hero is 72 px and the first screen still reads in the same order. Row C begins about 500 px down, so about 270 px of it shows above the fold as the scroll cue. Under 800 px of height the filter cascade (Wing, Division, Territory, House, Zone) collapses to one "ফিল্টার (৩)" button (wg s5), so the header stays one row.

| Block | Span xl / lg / md / xs | Content and source | Form (dataviz) | Drill to |
|---|---|---|---|---|
| Hero | 6 / 12 / 6 / full | net `net_mtk`, gross `gross_mtk`, `active_memo_count`; delta against yesterday; 12-day sparkline | hero figure, stat tile, sparkline (current point `accent-graphic`, rest `text-secondary` 50 percent) | Summary report |
| Strike rate | 3 / 4 / 3 / half | `strike_rate_pct`, basis line, meter | stat tile plus **meter** (one ratio) | CPR report |
| Outlets visited | 3 / 4 / 3 / half | `visited_outlets` over `target_outlets`, meter | stat tile plus meter | By-outlet report |
| Geo valid % | 3 / 4 / 3 / half | geo-valid share (force sales excluded, docs/10), chips "ফোর্স সেল ১২" and "সন্দেহজনক ৩" (icon plus word) | stat tile plus meter | Geo report |
| Login % | 3 / 4 / 3 / half | `login_pct`, "৫১টি রুটের মধ্যে ৪৮টি" | stat tile plus meter | Daily tracking |
| Submit % | 3 / 4 / 3 / half | `submit_pct_of_logged_in` | stat tile plus meter | Daily tracking |
| Final submit | 3 / 4 / 3 / half | zones submitted over zones, one chip per zone (check icon or hollow circle plus the zone name) | status rows, not a chart | `/final-submit` |
| Sales by day | 8 / 12 / full / full | month to date, this month and last month on one axis | **line**, two series: slot 1 blue and slot 2 orange, **legend plus end-dot direct labels** (2 series), crosshair, tooltip value first | Summary report |
| Channels | 4 / 6 / full / full | `by_channel`: net, successful calls and memos | ranked horizontal bars, **one hue** (slot 1), value at the tip | Channel report |
| Zone ranking | 8 / 12 / full / full | child nodes by net sales, top 10 with "সব দেখুন"; metric segmented "বিক্রয়" or "স্ট্রাইক রেট" | ranked bars, one hue, colour does not encode rank | the zone's own dashboard |
| Needs attention | 4 / 6 / full / full | counts: not logged in, logged in not submitted, suspicious locations, exceptions | list rows, icon plus count | filtered Exceptions |
| Team map | 12 / 12 / full / full | last synced fix of each SR, outlets of the first zones | map (below) | Team map page |

Colour rules on this page: colour is for status only, and a nominal state is quiet (the "লাইভ" chip has no container). Deltas show an arrow, a sign and the period ("গতকালের তুলনায়"); up is `success.container`, down is `warning.container` (a sales dip is not an error, so never `danger`). **No severity colouring on meters until the owner names thresholds (Q-WD-01):** fill is `bg-accent-graphic`, the value is also printed and spoken (`role="progressbar"`). Numbers never wrap or clip (tokens s7.3); money carries the taka sign at 0.8 em, and every Bengali digit run is at least 14 px (ranked-bar values included). Charts sit on `glass-data` (wg s10), with a "View as table" button and a one-sentence `role="img"` summary; marks follow wg s10 (2 px lines, 4 px rounded bar ends, 2 px surface gaps).

**Hero card.** Label "বিক্রয় (সর্বমোট)" (`text-label text-secondary`), value `text-hero text-primary` with `৳` at 0.6 em in the Bengali face, line "মোট ৪,৮২৮.৫০ ৳ · মেমো ৬" (`text-body text-secondary`; gross and net use the SR words, UI-SR-07), delta chip, sparkline 140 x 48 at the end. The card is one link to the Summary report (stretched link, one tab stop). Every card carries the as-of time and, when it is not today, the reason chip ("আজকের লাইভ নয়", "ফোনের সিঙ্কের অপেক্ষায়", "এখনো তথ্য নেই", "আংশিক তথ্য").

**Needs attention.** Four rows of 48 px: icon, label, count, chevron; a count of 0 shows `০` with a check icon. All zero collapses to one `success` line "সব ঠিক আছে" with a check. After 17:00 Dhaka a footer primary "ব্যবস্থা নিন" (existing take-action component) appears while any count is above 0; this is the page's single primary button.

**Team map.** A 360 px glass plate with the SR count and the last sync time and a secondary button "মানচিত্র দেখান". The map loads **only on that click**, through the capped BFF loader (`MapPanel`, `MAPS_DAILY_CAP`; request `docs/requests/web-dashboard-radius-map-maps-key.md`), so a dashboard visit costs no Maps load. Pins use shape as well as colour: outlet a 10 px `accent-graphic` dot, SR fix a 12 px rounded square with a 2 px surface ring, a fix older than 2 hours hollow, a suspicious fix a hollow square with a 2 px `danger` stroke; a legend row names each. No key or cap reached: the schematic SVG plan plus the accessible pin list (wg s9.1 fallback).

| State | What the user sees | Behaviour |
|---|---|---|
| Loading | each card streams on its own Suspense boundary; label shown, value block `bg-skeleton`; skeleton after 150 ms | `aria-busy`, polite "Loading"; the slowest aggregate never blocks the page |
| Before first sync | info banner "আজকের তথ্য ফোন সিঙ্ক হওয়ার সাথে সাথে আসবে। শেষ সিঙ্ক ০৮:৪১"; values `০` | not blank, not an error |
| No figures | the existing empty: title "এখনও কোনো সংখ্যা নেই", body "ফিল্ড অ্যাপ সিঙ্ক শুরু করলে আপনার পরিধির বিক্রয়ের সংখ্যা এখানে দেখা যাবে।" | when `target_routes` is 0 |
| Past date | reason chip "আজকের লাইভ নয়" on each card | no live indicator |
| Card error | `danger` icon, "লোড করা যায়নি", request id with copy, "আবার চেষ্টা" | other cards stay alive |
| Busy (429, 503) | "ব্যস্ত, ৫ সেকেন্ডে আবার চেষ্টা" | one retry with jitter, honours `Retry-After` |
| Offline | top-bar chip "অফলাইন, ডেটা ০৮:৪১ পর্যন্ত"; "হালনাগাদ" disabled with its reason | reads come from the last response |
| 403 | the existing forbidden page | |
| Long names | zone names wrap to 2 lines (label column 220 px), full name in `title` and the tooltip | never truncate numbers |
| 360 px, 200 percent text | one column; **Needs attention moves above the charts**; KPI 2-up from 480; the map is a button | the date control becomes a select |

| Key | BN | EN |
|---|---|---|
| title, date | ড্যাশবোর্ড; আজ, গতকাল, তারিখ | Dashboard; Today, Yesterday, Pick a date |
| as of | ডেটা ০৮:৪১ পর্যন্ত; ব্যবসায়িক তারিখ ০৫ অক্টোবর ২০২৬ | Data as of 08:41; Business date 5 Oct 2026 |
| actions | ফিল্টার (৩), হালনাগাদ, রপ্তানি | Filters (3), Refresh, Export |
| cards | স্ট্রাইক রেট, আউটলেট ভ্রমণ, জিও সঠিক, লগইন %, সাবমিট %, ফাইনাল সাবমিট অবস্থা | Strike rate, Outlets visited, Geo valid, Login %, Submit %, Final Submit status |
| strike basis | ৬০টি টার্গেট আউটলেটের মধ্যে ৬টি সফল কল | 6 successful calls of 60 target outlets |
| attention | লগইন করেনি, লগইন করেছে সাবমিট করেনি, সন্দেহজনক লোকেশন, ব্যতিক্রম, ব্যবস্থা নিন | Not logged in, Logged in not submitted, Suspicious location, Exceptions, Take action |
| map | মানচিত্র দেখান; ৪৮ জন এসআর · শেষ সিঙ্ক ০৮:৪১ | Show map; 48 SRs · last sync 08:41 |

**Interaction.** On a coarse pointer the segmented control, the sidebar items and the buttons are 48 px high (`min-height: max(var(--ctl-h), 48px)`). Whole-card links use one stretched link per card (`after:absolute after:inset-0`), so each card is one tab stop and the name is the accessible label. Date, scope and metric live in the URL query (shareable). Refresh runs on tab focus and on the button, never faster than every 60 s. A changed value cross-fades over 150 ms (no count-up). A refetch keeps the old render at 60 percent opacity and a 2 px accent progress line, no skeleton, no layout jump (wg s12.1). Keyboard: card order is reading order; `Ctrl/Cmd+K` search as everywhere.

**Motion.** Content fades in once per navigation over `duration-base` with an 8 px rise, no stagger beyond what wg s11 allows, never on refetch; line draws once over 220 ms on first load; reduced motion removes both.

**Tailwind mapping.**

| Element | Classes |
|---|---|
| Page | `mx-auto w-full max-w-[1600px] px-4 py-6 md:px-6 xl:px-8` |
| Header | `flex flex-wrap items-end justify-between gap-3`; title `text-display text-primary`; caption `text-caption text-secondary` |
| Grid | `mt-6 grid grid-cols-1 gap-(--gap) min-[480px]:grid-cols-2 md:grid-cols-6 lg:grid-cols-12` |
| Hero | `glass-card relative p-(--card-pad) min-h-42 col-span-full md:col-span-6 lg:col-span-12 xl:col-span-6`; value `text-hero text-primary` |
| Stat card | `glass-card relative flex flex-col gap-2 p-(--card-pad) min-h-32 md:col-span-3 lg:col-span-4 xl:col-span-3`; label `text-label text-secondary`; value `text-display text-primary` (`text-numeral` in compact density) |
| Delta chip | `inline-flex h-7 items-center gap-1.5 rounded-chip bg-success-container px-2.5 text-label text-success-on-container` (down: `warning` pair) |
| Meter | track `h-1.5 w-full rounded-full bg-skeleton`; fill `h-full rounded-full bg-accent-graphic` with `style="width: 10%"`, `role="progressbar"` and the value printed |
| Data card | `glass-data p-(--card-pad)`; main charts `col-span-full lg:col-span-12 xl:col-span-8`, side cards `col-span-full md:col-span-6 xl:col-span-4` |
| Needs-attention row | `flex h-12 items-center gap-3 rounded-chip px-3 hover:bg-pressed focus-visible:outline-2 focus-visible:outline-offset-2 outline-focus` |
| Stretched link | `after:absolute after:inset-0 after:rounded-card focus-visible:after:outline-2 focus-visible:after:outline-offset-2 after:outline-focus` |
| Skeleton | `rounded-chip bg-skeleton h-10 w-32` |
| Primary (take action) | recipe in wg s7.1 |

## 2. Admin configuration: geofence radius (`/admin/config/geofence`, F-ADM-039)

**Who and why.** ADMIN and SUPERADMIN apply or request; TSO and DMO propose where `cfg.geo.tso_radius_mode` is `propose`; SUPPORT reads. The page decides how close a rep must be to an outlet to sell. Both extremes cost something and the page says so: too small and every sale becomes a Force Sale; too large and the gate proves "in this market", not "at this shop" (docs/19 `cfg.geo.radius_m`, docs/22 P-10). The page is built so a mistake is visible before it is requested: the map shows the circle, the what-if shows what flips on real fixes, the blast radius shows who is touched, and the risk class states the gate in words.

**Flow, in one panel, three questions.** (1) *কোথায়* (where: scope), (2) *কত দূর* (how far: the value, the live circle and its effect on real visits), (3) *নিশ্চিত করুন* (confirm: risk, reason, send). The panel keeps them in this order top to bottom (layout and map styling are wg s9.1; this section adds the decisions).

```
 xl: map fills the content area (1102 x 712)                         | glass-panel 380, inset 16, radius 28
     current circle: 2 px text-secondary "১০০ মি এখন"                 | জিওফেন্সের ব্যাসার্ধ            [‹ collapse]
     proposed circle: 2.5 px accent, 14% fill "১৫০ মি প্রস্তাবিত"      | প্রযোজ্য: টেরিটরি · বনানী · সংস্করণ ১২ · অডিট
     outlets that flip: hollow square, 2 px danger                    | ① কোথায়   [স্তর: টেরিটরি ▾] [এলাকা: বনানী ▾]  (বনানী)(গুলশান)
     [+][-] at LEFT_BOTTOM, Google logo never covered                 | ② কত দূর   ১০০ মি (display)  (বিভাগ থেকে)
                                                                      |    ছোট ◂━━━━●━━━━━━━━━━▸ বড়   [ ১৫০ ] মি
     bottom handle 56: ঘনত্ব ও ক্যালিব্রেশন                           |    ২০ থেকে ২,০০০ মি · ১৫০ মি-এর ওপরে দ্বিতীয় অনুমোদন
                                                                      |    প্রভাব  (৭|১৪|৩০ দিন) ১২৪ ভিজিট: ৯ বৈধ হতো, ২ অবৈধ…
                                                                      |            ▕████████▒▏  ; ৩টি জোন, ১৮টি রুট, ৪১২টি আউটলেট…
                                                                      | ③ নিশ্চিত করুন  (C3 দ্বিতীয় অনুমোদন)  কারণ [      ] ০/১০
                                                                      | [ অনুরোধ করুন ]*  sticky footer
```

| Part | Spec |
|---|---|
| Scope | level select (only the key's `scope_levels`) plus area combobox on `GeoCascade`, bounded by the token scope; three recent scopes as chips; the winning-row link and provenance chip ("বিভাগ থেকে") sit beside the current value. Query `?level&id&value&days` mirrors the state (shareable) |
| Current value | `text-display` "১০০ মি", caption "এখানে কার্যকর ব্যাসার্ধ" |
| Slider | native `type="range"` (`h-6 w-full accent-accent-graphic`), log scale between the key bounds (20 to 2,000 m), steps 5 m under 150, 10 m to 500, 50 m above; arrows move one step, PageUp and PageDown 50 m; `aria-valuetext="১৫০ মিটার"`. A notch at 150 m is labelled. **The two ends are labelled with their cost:** "ছোট: বেশি ফোর্স সেল" at the start and "বড়: ভুল দোকান বৈধ হওয়ার ঝুঁকি" at the end |
| Number field | 96 px wide, `tabular-nums`, commits on blur or Enter; out of bounds shows the message and **does not move the slider** |
| Circles | the proposed circle follows the slider live on the client (one `requestAnimationFrame` per change, no network); on a typed value it tweens 150 ms (none when reduced). The current circle never moves |
| What-if | days segmented 7, 14, **30** (default), the existing sentence, four numbers and the 8 px stacked bar (become valid slot 3, become invalid slot 2, unchanged `text-secondary` 35 percent, 2 px gaps, numbers printed under it). Calls the existing what-if endpoint through the BFF 400 ms after the last change, aborting the previous call, cached per (scope, value, days). The bar updates with a 150 ms opacity cross-fade, **no width animation** (no layout animation, wg s11) |
| Blast radius | "এই পরিবর্তনে প্রভাব পড়বে" and the existing line (zones, routes, outlets, users, phones) |
| Risk and gate | live chip with words: C1 "C1 এখনই কার্যকর" (outlet), C2 "C2 ১৫ মিনিট পরে" (route, zone, geo class, house, territory), C3 "C3 দ্বিতীয় অনুমোদন লাগবে" (division, wing, global, or any increase that ends above 150 m). The gate is also spelled under the button |
| Reason | textarea min 10 characters, live counter "৭/১০" |
| Footer button | the one primary, label by outcome: **Apply** "প্রয়োগ করুন" (C0 or C1 and allowed), **Request** "অনুরোধ করুন" (C2 and C3), **Propose** "প্রস্তাব করুন" (TSO in `propose` mode). Disabled with a visible reason when the value is unchanged ("ব্যাসার্ধ বদলায়নি"), the reason is short ("কারণ কমপক্ষে ১০ অক্ষর"), or the viewer may only read |
| After submit | applied: toast "ব্যাসার্ধ ১৫০ মি প্রয়োগ হয়েছে। ফোন পরের সিঙ্কে পাবে।" and the current value updates; requested: card "অনুরোধ #১২৩ জমা হয়েছে, অনুমোদনের অপেক্ষায়" with a link to `/admin/config/changes` |
| Calibration | bottom drawer (wg s9.1): the existing density and calibration tables, histogram `aria-label` text, and a "এই ব্যাসার্ধ দেখুন" chip that loads the suggested value into the slider |

| State | What the user sees | Behaviour |
|---|---|---|
| No scope chosen | map on the country bounds, panel empty text "একটি টেরিটরি বেছে নিলে আউটলেট দেখা যাবে" and three recent-scope chips | the panel is already usable |
| Loading a scope | gradient plate (no spinner), panel skeleton for current value and what-if, slider disabled | `aria-busy` |
| What-if running | bar skeleton and "যাচাই চলছে…" | a new change aborts it |
| What-if has no visits | "এই সময়ে কোনো ভিজিট নেই", bar hidden | request still allowed, caption "যাচাইয়ের তথ্য নেই" |
| Pending request for this scope | `accent` banner "এই এলাকার জন্য একটি অনুরোধ অপেক্ষায় আছে (#১২২)" | the footer button opens it instead of a second request |
| Conflict (412 or 409) | card "অন্য কেউ এটি বদলেছে" with the new value | "আবার প্রয়োগ করুন", never a silent overwrite |
| Read-only role | value shown, slider and field replaced by a lock icon and "শুধু দেখার অনুমতি" | what-if still works, so a reader can explore |
| Maps key missing or cap reached | schematic SVG plan plus the pin list, notice "আজকের জন্য মানচিত্র বন্ধ (দৈনিক সীমা পৌঁছেছে)।" | same panel |
| Map failed | inline notice "মানচিত্র লোড করা যায়নি।" with retry | panel unaffected |
| Offline | connection chip; footer disabled with "ইন্টারনেট নেই" | reads stay |
| Long names | scope names wrap to 2 lines then ellipsis with `title` | |
| Below `lg` | panel becomes a bottom sheet over the interactive map with three heights: peek (value and risk chip, 120 px), half, full | the footer stays sticky |

| Key | BN | EN |
|---|---|---|
| title, intro | জিওফেন্সের ব্যাসার্ধ; একজন প্রতিনিধি আউটলেটের কত কাছে থাকলে বিক্রি করতে পারবেন। পরিবর্তন ফোন পরের সিঙ্কে পাবে। | Geofence radius; How close a rep must be to an outlet to sell. Phones receive a change at their next sync. |
| steps | কোথায়, কত দূর (সাথে প্রভাব), নিশ্চিত করুন | Where, How far (with Effect), Confirm |
| current | এখানে কার্যকর ব্যাসার্ধ; বিভাগ থেকে | Radius in force here; from Division |
| what-if | ব্যাসার্ধ যদি হতো…; ১২৪টি ভিজিট যাচাই: ৯টি বৈধ হতো, ২টি অবৈধ হতো, ১১৩টি অপরিবর্তিত। | What if the radius were…; 124 visits checked: 9 would become valid, 2 would become invalid, 113 unchanged. |
| bounds error | ২০ থেকে ২,০০০ মিটারের মধ্যে একটি সংখ্যা দিন। | Enter a number from 20 to 2,000 metres. |
| increase warning | ১৫০ মি-এর ওপরে বাড়ালে দ্বিতীয় অনুমোদনকারী লাগবে। | An increase above 150 m needs a second approver. |
| tradeoff ends | ছোট: বেশি ফোর্স সেল; বড়: ভুল দোকান বৈধ হওয়ার ঝুঁকি | Small: more force sales; Large: risk that a neighbouring shop counts |
| buttons | প্রয়োগ করুন, অনুরোধ করুন, প্রস্তাব করুন | Apply, Request, Propose |

**Accessibility.** One `h1`; the panel is `role="complementary"` with a label and is reachable before the map; "মানচিত্র এড়িয়ে যান" skip link; the pin list is the keyboard and screen-reader alternative; `+` and `-` zoom; the what-if result is a polite live region announced once per settled value; the risk chip and every flip marker use icon, shape or text as well as colour; 200 percent text reflows the panel to a sheet.

**Motion.** Panel enters once, `translateX(24px)` to 0 plus opacity over `duration-sheet` with `ease-spring`; collapse to the 48 px handle over `duration-base`; during map drag or zoom the panel switches to its lite fill without blur and restores 120 ms after `idle` (wg s9.1); stat and what-if numbers cross-fade 150 ms; reduced motion: fades only.

**Tailwind mapping.**

| Element | Classes |
|---|---|
| Map region | `relative h-[calc(100dvh-var(--topbar-h))] w-full overflow-hidden` |
| Panel | `glass-panel absolute end-4 top-[calc(var(--topbar-h)+16px)] max-h-[calc(100dvh-var(--topbar-h)-32px)] w-[380px] overflow-y-auto rounded-sheet p-5 max-lg:w-[320px]` |
| Section label | `text-label text-secondary` with a step number in a 24 px `bg-accent-container text-accent-on-container rounded-full` disc |
| Current value | `text-display text-primary` |
| Number field | `h-(--ctl-h) w-24 rounded-chip border border-input bg-raised px-3 text-body text-primary tabular-nums focus-visible:outline-2 focus-visible:outline-offset-2 outline-focus` |
| Risk chip | `inline-flex h-7 items-center gap-1.5 rounded-chip bg-warning-container px-2.5 text-label text-warning-on-container` (C1 `accent` pair, C3 `danger` pair) |
| What-if bar | `flex h-2 gap-0.5 overflow-hidden rounded-full`; segments `bg-(--viz-3)`, `bg-(--viz-2)`, `bg-secondary/35` |
| Reason | `min-h-20 w-full rounded-chip border border-input bg-raised p-3 text-body text-primary`; counter `text-caption text-secondary tabular-nums` |
| Error strip | `flex items-center gap-2 rounded-chip bg-danger-container px-3 py-2 text-caption text-danger-on-container` |
| Footer | `sticky bottom-0 -mx-5 -mb-5 rounded-b-sheet glass-bar px-5 py-4`; primary per wg s7.1 |
| Bottom sheet (below lg) | `glass-sheet fixed inset-x-0 bottom-0 max-h-[85dvh] rounded-t-sheet` |

## 3. Open items and defaults taken (log to DECISIONS.md)

| ID | Item | Default taken |
|---|---|---|
| Q-WD-01 | Severity colours on meters (login, submit, geo-valid) | None until the owner names thresholds; value printed, fill `accent-graphic` |
| Q-WD-02 | Strike-rate basis | **The existing key says "successful of visited"; docs/10 and UI-SR-09 say successful calls over target outlets (6 of 60 is 10 percent).** The page uses target outlets; the `dashboard.kpi.strike_basis` string and its caller need the same fix (lane web-dashboard) |
| Q-WD-03 | Hero metric for each role | Net sales for every role; an AMO on a phone gets the same hero |
| Q-WD-04 | "Suspicious location" count | Needs a server aggregate of the plausibility flags (docs/05); the card shows `—` with "এখনো তথ্য নেই" until it exists |
| Q-WD-05 | Map on the dashboard loads only on click | Cost guard first (`MAPS_DAILY_CAP`); the owner may ask for auto-load on desktop |
| Q-WG-01 | Radius page: the three-question order, cost-labelled slider ends, one-panel flow | Additions to wg s9.1, no new components; the web-config lane builds them with the existing `GeoCascade`, `ConfigSetForm` and what-if endpoint |
| Q-WG-02 | Pending-request guard (one open request per scope) | Shown as a banner; the server's rule decides, the page never hides it |
| Q-WG-03 | Large-sum grouping in the hero (`12,34,567` or `1,234,567`) | `LocaleDigits` stays the single source (tokens s10 item 1) |
