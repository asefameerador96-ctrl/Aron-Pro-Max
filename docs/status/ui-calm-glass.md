# UI "Calm Glass" implementation (laptop session, 2026-10-15)

Owner ask (2026-10-15, evening): design the apps from the vendor screenshots in `E:\Aron SR App Screenshots` and make the
SR, AMO and TSO apps and the web dashboard look like one designed product, not plain text. Branch `lane/ui-calm-glass`
(worktree `.claude/worktrees/ui-calm-glass`), cut from INT `26cfb26a` plus `origin/lane/web-dashboard` (36 unmerged web
commits, merged in so the web work does not conflict with that lane). Nothing is pushed until the owner says so.

## What the vendor app does (55 screenshots, read 2026-10-15)

Red-to-purple gradient header on every screen, white tiles with gradient line icons on Home, a 4-column tile grid, then a
Target and Achievement card (deferred, docs/27) and the KPI strip. Flows seen: Attendance (address card, two big
check-in / check-out buttons, a hold-to-confirm sheet), Stock (SKU rows with pack photo, red minus, purple plus, a
purple category-totals bar with Save and Print), Sale (retailer drop-down, dark alphabet strip, "start the call?" yes/no,
SKU cards with an Offer ribbon, a purple bottom bar with the cart total and "এগিয়ে যান"), Review (SKU table, discount
table, grand total, "mark as credit" checkbox, Product QC and Print buttons), QC (pack carousel, fault-type table), Memo
(outlet drop-down, tables, Print and Edit, credit box with "পরিশোধিত করুন"), Sales Summary (table plus Print), Sales
Submit (device vs server count table, sync and submit buttons), Outlet menu (New shop, Permanent close, Edit info,
Cluster) with forms, Sales Journey (visit cards with duration), Astha and gift selection (deferred). Everything we
reproduce functionally is already in the screen specs `docs/design/screens-sr.md` v1; this work implements the look.

## What changes

1. **Kit (`android/core-ui`)**: page gradient with two glows (tier A/B) or flat (C, sunlight); `AronScreen` scaffold with a
   glass top bar and a solid bottom action bar; a vector icon set (`AronIcons`, our own drawings, no vendor artwork);
   tiles with icon wells, label plates, badges and the target ring; hero money card, stat cards, meter; the quiet status
   chip (synced = no container); gradient primary button with press scale; opaque secondary button; banners with icons
   and five kinds; list rows with leading and trailing slots; empty states with an illustration; screen transitions.
2. **SR app**: Home rebuilt per `screens-sr.md` s2 (header, status row, hero, two tile groups, KPI row, stock and money
   cards, device line, next-step dock); every screen gets the top bar and bottom bar frame; the sale flow restyled.
3. **AMO and TSO apps**: the same frame and a real Home (header, status, tiles per `docs/07` and `docs/08`) instead of the
   placeholder text; tile destinations are designed empty states until their lanes land the features.
4. **Web**: `tokens.css` and `glass.css` per `docs/design/web-glass.md`, the Calm Glass shell (sidebar, top bar), stat and
   hero cards on the dashboard home per `docs/design/screens-web.md`, the login card.

## Verification

- `TokenContrastTest`, `HardcodedStringScanTest`, `KitGalleryTest` and the feature Robolectric tests stay green.
- Roborazzi goldens in `android/ui-screenshots` are re-recorded (the look changed on purpose) and the PNGs are checked by eye.
- The SR debug build is installed on the Honor X5c Plus from this laptop.

## Log

### 2026-10-16 to 2026-10-17: phase 1 (commit `886c76eb`, pre-review checkpoint)

Four packages built in parallel by agents, each with two reviewers and a fix pass (the session limit interrupted the
review and fix stages overnight; they were resumed on 2026-10-17):

- **Kit** (`android/core-ui`): GlassKind recipes, page gradient and glows, `AronScreen`/`AronTopBar`/`AronBottomBar`,
  gradient primary and opaque secondary buttons, `MoneyText`/`AronMeter`/`AronStatCard`/`AronHeroCard`, motion helpers
  and `AronScreenTransition`, tiles with icon wells and badges, the quiet status chip, banners with icons, empty states
  with illustrations, 36 new strings with Bangla twins. Every Android module compiles against it; core-ui tests green
  except three pre-existing environmental failures on this laptop (JDK 17 vs Robolectric SDK 36; a Windows path assert).
  Reviewers found two majors (ragged tile rows, bottom-bar primary width) and polish items; fix pass pending.
- **Web**: `tokens.css` + `glass.css` (Tailwind v4 theme), pre-paint theme stamping, glass sidebar and top bar, kit
  components, restyled dashboard home and login; lint, typecheck, 961 vitest tests and the build green; 35 Playwright
  e2e checks green on this laptop. Review pending.
- **Real data**: `tools/data/*` (data-free importer and radius parser), `db/seed/06_dev_real_route.sql`,
  `docs/evidence/geofence-radius-2026-09.csv` (291 territories, totals match the PDF), `docs/status/real-data.md`.
  Loaded into dev through the Container Apps job (no public DB access needed): the whole country hierarchy and all
  14,039 Mirpur-division outlets with coordinates; the proposed radii as one audited config version (author
  `aron.system`); sr1001 now also has two real Mirpur 11 routes (16685 Sat/Mon/Wed, 16686 Sun/Tue/Thu). Review pending.
- **Device owner**: runbook `docs/runbooks/honor-device-owner.md` (adb path A is the working one), token 3 minted
  (dev lockdown, 2 uses, expires 2026-10-19 17:30 UTC), facts appended to `docs/status/device-checks.md`. Two blockers
  for the QR path filed: `docs/requests/laptop-dev-release-row-and-admin-releases.md` and the existing
  `android-geo-dpc-provisioning-activities.md`.

A production-readiness audit ran alongside (six dimensions, adversarial verification); its write-up is
`docs/audit/production-readiness-2026-10-15.md` once the resumed run completes.

### 2026-10-17: phase 1 fixes, phase 2 screens, build 724 on the Honor

- Phase 1 review fixes landed (`dec53bab`): kit bottom-bar and tile sizing, web page gradient and the 48 px touch floor,
  versioned radius imports; `.pyc` files dropped from git.
- Audit written: `docs/audit/production-readiness-2026-10-15.md` (`766c79ab`): no critical after verification, 25
  confirmed majors with lane and hours, a 15-item go-live checklist.
- Phase 2 (`bd579f7e`, `93e4d98c`): SR Home (hero, status row, tile groups with icons, KPI/stock/money cards, device
  line, next-step dock), the SR frame on every screen (top bar, chip, printer, transitions), selling screens (Stock,
  Picker, Visit with range view, Sale entry, Review, QC, Outlet menu), closing screens (Attendance with hold step, Memo,
  Summary, Submit, Tasks, Login/OTP/Enrolment), AMO and TSO homes with designed destinations. 36 goldens re-recorded and
  verified at 360 x 800 (Robolectric SDK 36 needs the Android Studio JBR locally; CI needs JDK 21+ for that job).
- Built locally as CI does: `assembleRelease` with `-Paron.versionCode=724 -Paron.versionName=0.1.724` against the dev
  Front Door, signed with the laptop keystore (certificate `468d9b4e…a4fb`, same as the dev app_release), installed with
  `adb install -r` over 722 so the enrolment and the sr1001 bind survived. Cold start 1.7 s, no crash on Home, Stock and
  the outlet picker. Fixed on the phone: white status-bar icons over the light top bar (now dark via WindowCompat).
- Deferred to save the owner's allowance: web page-by-page restyle (the theme, shell, kit, dashboard and login are
  done), kit polish items (bar label wrap at 360 dp, tile plate padding, segmented thumb animation), the hardening
  items of the audit (next phase, owner's call).
