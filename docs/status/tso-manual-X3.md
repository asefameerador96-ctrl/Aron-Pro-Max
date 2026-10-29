# TSO manual build, lane X3 (integration round: web): status

Lane X3 of `docs/requests/tso-manual-build-2026-10.md` s10: lead ruling R-W-13 (all eight items) and the W1 asks that R-W-06
and R-W-13 unblock. Branch `tso-man/X3`, worktree `.claude/worktrees/tso-X3`, base `tso-man/X1` (`f4ba4501d`, contract
1.10.0 with the R-W-06 members regenerated into `web/src/contract/openapi.d.ts`). 2026-10-29. No pull request, nothing pushed.
Owned: `web/` and this file; nothing outside `web/` was changed.

## Done

| Item | What | Proving test |
|---|---|---|
| R-W-13 (1) icon | `MENU_ICON["admin-tickets"] = "inbox"` in `web/src/components/kit/icons.tsx` as ruled. The sidebar does not read `MENU_ICON` (no caller of `menuIcon` exists); it draws `web/src/components/nav-icons.tsx` `BY_ID`, so that map gets an `inbox` line glyph (original geometry, 24 px grid, 1.75 stroke) and `"admin-tickets": "inbox"` too (D-X3-01); before, the entry drew the fallback dot | `tso-integration-web.test.tsx` "(1) the Tickets entry's icon" (menu id, both maps, the NavLink draws the inbox path beside "Tickets" / "টিকিট") |
| R-W-13 (2) leave note | `NOTE_MAX` 300 -> 500 (`leave-rows.ts`); the BFF (`web/src/app/api/bff/leave-decision/route.ts`) now takes `.max(NOTE_MAX)` from it, so page, proxy and `DecisionRequest.note` maxLength 500 are one number; the page's textarea `maxLength` and its hint follow | `tso-integration-web.test.tsx` "(2) …": NOTE_MAX equals the contract's maxLength (read from `contract/openapi.yaml`); 500 Bangla characters stored through the BFF and the mock; 400 English characters pass; 501 refused at the BFF (400, leave stays pending); hint "Up to 500 characters" en, "সর্বোচ্চ ৫০০ অক্ষর" bn. Existing `leave-applicant.test.tsx` (NOTE_MAX-relative) still green |
| R-W-13 (3) Submit | `finalSubmitButton(role, preview)` in `web/src/lib/admin/ops.ts`: `preview.can_submit` when sent, else the TSO-only rule (`FINAL_SUBMIT_BUTTON_ROLES`) for an older server; never for a role the team-op proxy does not forward (`day.final-submit`: TSO, DMO, WM). `final-submit-page.tsx` passes it to the view; `already_submitted` stays the view's own check. A delegated DMO or WM opens the page (`FINAL_SUBMIT_PAGE_ROLES`, unchanged) and now gets the button | `final-submit-roles.test.tsx`: the delegate case end to end (WM and DMO with `can_submit: true` see the form, the proxy forwards, the API's 200 passes through), `can_submit: false` for DMO/WM and for a TSO hides it, an older server keeps TSO only, ADMIN/SUPERADMIN never (even with true), already submitted hides it, bn ("ফাইনাল সাবমিট"), the pure rule matrix |
| R-W-13 (4) till-date % | `fmt.ts tillDatePctText(locale, row)`: `pctBp(bp, 2)` whenever `tilldate_target_achievement_bp` is sent (a sent null prints "—"); the deprecated float only when the bp member is absent. Used by TSO Daily Tracking and by the Daily Tracking table's Achievement column (D-X3-02). Mock sends the bp (integer, half up) and the float as bp / 100 | `tso-daily-tracking.test.tsx`: every corpus `percent` entry noted "s4.2" and every `details` (s4.3) entry prints the corpus's bp text in en and bn (the float beside it ignored); null bp -> "—" even with a float; absent bp -> the float ("87.50%", "৮৭.৫০%"); no second rounding (4342 -> 43.42%, 245 -> 2.45%, 123456 -> 1,234.56%); the page (bp over float; fallback row; bn "১০৪.৯৪%"); the table column (en, bn) |
| R-W-13 (5) route bars | `fmt.ts pctWholeSalesCard(sales, monthly, W)` = round_half_up(100 x sales x W / monthly) once, monthly (Decimal3) read exactly as thousandths, BigInt inside; null for W = 0, no or 0 target, bad input. `routeBarText`: that rule when `monthly_target_qty` and `working_days` are both sent; else W1's exact sales-over-Decimal3-daily-target path; else the bp at 0 dp. `types.ts` / `server.ts` carry the two members; the route-map page prints through `routeBarText` (the bp still draws the bar). Mock sends monthly "26000.000" and W 26 | `national-exact-pct.test.tsx` "route-map bars by the Sales-card rule": 30 / "689.000" / W 24 -> 104 (the daily "28.708" path and the bp 10450 both give 105), 12 / 101 / 21 -> 250 (daily path 249), X1's 50 / 1991 / 27 -> 68, nulls; agrees with the corpus `whole` on every `percent` entry at W = 1; precedence of the three paths; the rendered page in en and bn ("104%", "১০৪%", W = 0 "—", an older entry "2%"). `national-server.test.ts`: the adapter carries both members, undefined from an older server |
| R-W-13 (6) channel legends | `fmt.ts channelShareText(locale, value, denominator)` = `pctText(pctTenths(value, denominator), 1)`. `ChannelSlice.denominator`: `total + unclassified` (By Channel Calls), `total` (By Channel STD), set in `server.ts`. The legends of both cards and the STD pie's slice labels (were a float `frac * 100` at 1 dp) print it; `share_pct_bp` no longer prints | `national-exact-pct.test.tsx` "channel legends from the counts": 29 / 1186 -> "2.4%" (bp 245 at 1 dp: 2.5%; over total alone: 2.6%), 1100 / 1186 -> "92.7%" (bp 9275 at 1 dp: 92.8%), bn "২.৪%", one channel "100.0%" / "১০০.০%" on legend and pie, 0 denominator "—". `national-server.test.ts`: calls 1129 + 57 -> 1186, STD total |
| R-W-13 (7) support grant | Already built by W1 (R-F-11: `GRANT_MODULES` has `support`, label `grant.module.support` "Support tickets" / "সাপোর্ট টিকিট"); X3 pins it | `tso-integration-web.test.tsx` "(7) …": `GRANT_MODULES` equals the contract's `AdminModule` enum (from the YAML, and typed as `Schemas["AdminModule"][]`); the editor's `grant-support` row in en and bn with its radio labels and the stored level; a Super Admin grant `{support: "w"}` through `user-access.put` stored by the mock |
| R-W-13 (8) truncated note | Already built by W1 (`truncatedText`, `tsoman.drill.truncated`, shown only when `truncated`); X3 pins it on the rendered page | `drill-columns.test.tsx` "the truncated note on the page": en on the cut tab only (and with a picked route), bn text with Bengali digits ("তালিকাটি ২,০০০ সারিতে কাটা হয়েছে…"), never for an uncut 2,000-row list |

Every new rule was mutation-checked: putting the old behaviour back (12 mutations: either icon map, NOTE_MAX 300, a literal
300 in the BFF, TSO-only button, no proxy guard, float first, null bp falling back, bar via the daily target, legend from the
bp, calls over `total` only, the note hidden) makes 1 to 4 tests fail each; none survived (script
`x3-mutate.mjs` in the session scratchpad, not committed).

## W1 asks this lane closes (docs/status/tso-manual-W1.md)

- Ask 2 (MENU_ICON): R-W-13 (1), done, plus the sidebar's own map (D-X3-01).
- Ask 4 (`FinalSubmitPreview.can_submit`): R-W-13 (3), done; W1's "Next step 1" (the delegate test expects the button) done.
- Ask 8 (`tilldate_target_achievement_bp`): R-W-13 (4), done. `pct2dpText` stays only as the fallback for a server without
  the member, as the ruling says ("fall back to the float member only when bp is absent"); its pinning test stays.
- Ask 9 (leave note 500): R-W-13 (2), done.
- Ask 10 (channel denominators): R-W-13 (6), done (D-W1-24 superseded).
- Ask 11 (route card monthly target and W): R-W-13 (5), done.
- Asks 1 and 12 were ratified by R-W-02. Asks 3, 5, 6, 7 are server-side (B1/B3, now X2 under R-W-09); nothing for the web.

## Decisions (D-X3-NN)

- **D-X3-01 Two icon maps.** R-W-13 (1) names `MENU_ICON` in `kit/icons.tsx`, which W1's ask assumed draws the sidebar. It
  does not: `menuIcon` has no caller, and `sidebar.tsx` draws `navIconFor` from `components/nav-icons.tsx` (a separate line-icon
  set, PARITY-SPEC s4). The ruled line is in; the same entry and an `inbox` glyph went into `nav-icons.tsx` so the entry
  actually shows the inbox. The unused `MENU_ICON` is left in place (not this lane's call to remove).
- **D-X3-02 The Daily Tracking table prints the same figure.** Its Achievement column printed the same member
  (`tilldate_target_achievement_pct`) re-rounded to one decimal: a second rounding of a two-decimal figure, and the float the
  contract now says the web never prints. It now uses `tillDatePctText` (two decimals, from the bp). Visible change on
  `/daily-tracking`: "87.5%" becomes "87.50%".
- **D-X3-03 Channel shares keep one decimal on the web card.** The national legends printed tenths before (W1 D-W1-24's plan:
  `pctText(pctTenths(value, denominator), 1)`); s4.2's "100.00% Astha Channel" is the TSO phone's pie label at 2 dp, not the
  national card. The STD pie's slice labels now print the legend's text (were a float at one decimal over the listed rows);
  the slice geometry still draws the listed rows.
- **D-X3-04 The bar rule needs both members.** `routeBarText` takes the Sales-card path only when `sales`,
  `monthly_target_qty` and `working_days` are all sent; with one missing it falls back to W1's daily-target path (never mixes
  a server's W with a guessed target). `monthly_target_qty` null or "0.000", or W = 0, print "—" (R-F-13). The Decimal3 parse
  accepts up to 15 integer digits and 3 decimals (the existing `thousandths`), so the type question in X1 ask 5 (Decimal3 or
  int64) does not affect the web: an int64 there would need one line (`String(n)`).
- **D-X3-05 One limit constant for the note.** The BFF imports `NOTE_MAX` from the page's `leave-rows.ts` (a route file may only
  export handlers, so the constant lives with the page). Both sides count UTF-16 units after trimming, like `LeaveApi`
  (`req.note.length > 500`); the JSON Schema maxLength counts code points, so the web is never looser than the server.
- **D-X3-06 The button never outruns the proxy.** `finalSubmitButton` also requires a role the team-op proxy forwards, so an
  ADMIN or SUPERADMIN never gets a button that can only fail, even if a server said `can_submit: true`.

## Deviations (files outside the lane's list)

None. Every changed file is under `web/` (plus this status file).

## Asks

1. **X2:** send `FinalSubmitPreview.can_submit`, `DailyTrackingRow.tilldate_target_achievement_bp` and
   `TargetVsAchievement.monthly_target_qty` / `working_days` (X1 asks 1 and 2). The web reads all of them now and falls back
   when they are absent, so the order of merges does not matter; until X2 lands the pages behave as before.
2. **Lead (goldens, R-W-15):** no web screenshot case was added or changed, but two recorded Linux goldens may show small
   content changes when the lead's e2e run happens: `e2e/national-shots.spec.ts-snapshots/national-grid-chromium-linux.png`
   (By Channel STD legend: a share just under 0.05 % whose bp rounds to 5 now prints 0.0% instead of 0.1%; computed, not
   rendered, for the mock's unscaled MT 16,780 of 36,421,040; the other mock shares print the same text either way)
   and `e2e/admin-states-shots.spec.ts-snapshots/admin-*-bn-light-laptop-chromium-linux.png` (the Tickets sidebar entry, if
   shown for `msuper1`, draws the inbox instead of a dot). Both changes are a few pixels against `maxDiffPixelRatio: 0.03`;
   re-record only if the run reports a diff.

## Verify (this worktree, Windows laptop, Node 22; CI not run)

Run on the four code commits (`6c7ac8481`, `64bdea1c6`, `ae8f9f65c`, `6599d26f3`); see "Verify (final)" for the rerun on the
head with this file.

| Command | Result |
|---|---|
| `cd web && npm ci --ignore-scripts` | exit 0 (fresh worktree) |
| `npx vitest run tests/tso-daily-tracking.test.tsx tests/final-submit-roles.test.tsx` | 2 files, 32 passed |
| `npx vitest run tests/national-exact-pct.test.tsx tests/national-server.test.ts` | 2 files, 27 passed |
| `npx vitest run tests/tso-integration-web.test.tsx` | 1 file, 10 passed |
| `npx vitest run tests/drill-columns.test.tsx` | 1 file, 22 passed |
| `npm run typecheck` | `tsc --noEmit` exit 0 |
| `npm run lint` | `eslint .` exit 0 |
| `npm test` (whole web) | 156 files, 1960 passed, 1 skipped, 0 failed (X1 base: 155 files, 1923 passed; +1 file, +37 tests) |
| `python3 tools/ci/money-float-scan.py` (the brief's python3.exe, repo root) | "13 money line(s) with floating point, 0 not allowed, 0 stale and 0 ambiguous allow entr(ies), 13/13 allowed (0 open finding(s))", exit 0 |
| mutation script (12 mutations, each restoring the file) | 12 killed, 0 survived |

Not run: Playwright e2e (Linux runner), a live browser against the dev API, Gradle (no Kotlin touched).

## Not done

- Server members (X2): until X2 sends them, the new paths are exercised by tests and the mock only.
- Playwright e2e and the Linux goldens (ask 2).
- `MENU_ICON` / `menuIcon` in `kit/icons.tsx` stay unused (D-X3-01).
- Not seen in a live browser; every page change was rendered server-side against the contract mock or stubs in the tests above.

## Next step

The lead merges X3 after X1 (X3 is based on it). When X2 is on dev (test account), open `/final-submit/submit` as a delegated
DMO, `/tso-daily-tracking`, a route map and the national dashboard there and check the figures against the phone.
