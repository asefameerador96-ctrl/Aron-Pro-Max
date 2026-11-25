# SR programmes A-PROG5 slice 1 (cd6ba277d2): KPI uses by_pack category_code, 2026-11-25

Reviewers read code and the golden only. Lane CI run 45596528837 green (lane's report).

## coded-model: FAIL (blocking raised 1, upheld 1)

I checked KpiModel.kt and RouteFigures.kt at cd6ba277d2 against da7fd7b1f5, plus the screen, the tests and the golden. Every case in the brief was traced. The old-server path is unchanged: merge() groups by (null, packFactor) with the same order and sums. The coded single-category, cigarette+match (including same pack_factor 1 across routes), lighter-only, partial-code, sums-not-adding-up, mixed-route and cigarette Volume total paths are all correct. The golden shows what its name says. One blocking issue: in BrandKpiScreen, a coded brand that mixes cigarette (or match) with bidi or an unknown category drops that category through mapNotNull. The row prints e.g. 'Target 100 packet' as if it were the whole brand target, with no dash and no left-out note. That breaks 'bidi stays a dash / unknown = dash', and no test covers it.

### UPHELD: A mixed brand with a bidi (or unknown-category) entry silently drops that category instead of showing a dash

android/feature-programmes/src/main/kotlin/com/aktcl/aron/feature/programmes/KpiScreens.kt

KpiModel.categoryVolume returns null for "bidi" and any other code, which is right. But BrandKpiScreen builds `perCategory = b.categoryVolumes.mapNotNull { c -> c.target?.let { ... } }`, so a null category disappears from the line. Concrete input from a 1.25.0 server: brand X, volume_target 2500, by_pack [{cigarette,20,2000,..},{bidi,25,500,..}], entries adding up. codedCategories gives {cigarette, bidi}, so cat=null, volumeTarget=null and categoryVolumes=[cigarette 100 packet, bidi null]. The row prints "Target 100 packet - KPI Today —". That reads as the brand's whole target, with no dash or note for the 500 bidi sticks. It breaks the lane's own rule that bidi stays a dash, and is inconsistent with the cigarette+match case, where every category appears. The brand is listed in volumeBrands because it has cigarette volume, and volumeLeftOut is false because categoryVolumes is non-empty, so no note appears either. The same happens for any unknown category string next to a known one. Fix: map a null target to Percent.DASH plus the unit-unknown or bidi label (from resources) instead of dropping it. Add a KpiModelTest/KpiScreensTest case for cigarette+bidi. Tests c1-c8 and the golden cover only cigarette+match.

Lows:
- The ' · ' category separator is a literal in KpiScreens.kt, not a string resource. It is locale-neutral punctuation, but rule 'strings bn+en only in resources' is read strictly.
- categoryVolume('lighter') reuses pairFromPacks(..., 'box'), which divides by the entry's pack_factor and labels it 'box'. That is correct only when lighter SKUs have base_per_pack 25. A lighter SKU sent with another pack_factor would be shown as 'box' of the wrong size. The behaviour comes from brandVolume and was already there; the new path inherits it.
- For a coded brand whose single category is bidi or unknown, cat is non-null. volumeBp therefore carries the server's percentage while the volume is a dash. This matches the old tree path for bidi, and the percentage is single-unit, so it is not mixed. Noting it only for consistency.
- RouteFigures.packs lowercases category_code but does not trim it, so ' cigarette' would be treated as an unknown code. The contract enum makes this unlikely.
- Old-server path checked line by line and found unchanged: merge() groups by (null, packFactor), which keeps the same first-seen order and the same sums. When coded==null, resolved falls back to resolvedCategory. cigarettePacks takes b.byPack only for tree-resolved cigarette brands. brandVolume is called with the full byPack. volumeBrands and volumeLeftOut take the old branch when categoryVolumes is empty. The BLM category packs still group by packFactor only.
- Traced and OK: pure cigarette 10s+20s coded gives packets via each entry's pack factor plus the server bp. cigarette(1)+match(1) with pack_factor 1 is kept apart, both in codedCategories (single route) and in merge (keyed by category and pack size; test c7). A lighter-only brand shows boxes and is not in the volume list. A missing code or entries that do not add up fall back to the tree rule (c5). With one route coded and one older route, the codes don't line up, so the tree rule applies with the same pack totals as before. The cigarette Volume total adds coded cigarette entries only and shows the server total untouched, in sticks, when the packs do not add up. Multi-route single-category % is Percent.bp of same-unit sums. The golden kpi_brands_bn_mixed_categories shows 'মিক্সড কো Target ১০০ প্যাকেট · ৩০ ডজন' with a volume dash, as its name says.

## screen-tests: FAIL (blocking raised 1, upheld 1)

The change mostly does what the lane says. A mixed cigarette+match brand shows each category in its own unit, the volume % is a dash, the cigarette Volume total excludes matches, merge() keeps colliding pack sizes apart, and older servers take the unchanged tree path. Tests c1 to c8 and the golden match their names, and nothing existing was weakened. One blocking defect: a coded brand that includes bidi (valid in the server enum) has its null-unit bidi entry silently dropped by mapNotNull in KpiScreens.kt. The row then shows only the non-bidi categories (for example \"100 packet\") as if that were the brand's whole volume. The case is untested. The hardcoded \" · \" separator and the unverified font-scale 2.0 rendering are low-severity.

### UPHELD: Mixed brand with a bidi entry prints only the other categories, silently dropping the bidi volume

android/feature-programmes/src/main/kotlin/com/aktcl/aron/feature/programmes/KpiScreens.kt

The server contract enum for by_pack category_code includes bidi (KpiPackFigure.yaml: cigarette, bidi, match, lighter), and a brand can have one entry per category. For a coded brand of cigarette 20s plus bidi, KpiModel.categoryVolume() returns null for bidi, so KpiCategoryVolume("bidi", null) is kept. The new KpiScreens.kt line does `b.categoryVolumes.mapNotNull { c -> c.target?.let{...} }`, which drops the null entry. The row then reads "Target 100 packet - KPI Today ..." with no mention of bidi, no dash and no unit-unclear note. That is an incomplete, understated figure for the brand. The row's category is null, so the volume % is a dash, but the label implies the brand's whole volume is 100 packets. It is not a mixed unit, but it is a wrong, unlabelled figure and it breaks the 'bidi stays a dash' rule. The brand is also listed under cigarette sales because it has a cigarette entry. No test covers cigarette+bidi, bidi+match or bidi-only in KpiModelTest c1 to c8 or KpiScreensTest. Fix: keep a dash entry for a null-target category (for example "100 packet · —"), or fall back to the dash with the 'unit not clear' note when any category has a null target, and add a test.

Lows:
- The separator " · " in KpiScreens.kt is a hardcoded literal (joinToString(" · ")) and not a string resource. It is punctuation with no language-specific content and the golden renders it correctly in Bangla, so it is low severity, but the brief says strings live in resources only.
- No golden or test shows a mixed brand at font scale 2.0 (the existing kpi_brands_bn_font20 uses the old data). The 360dp golden shows the mixed line wrapping to a second row ("... ৩০ ডজন - KPI / Today —") without clipping, so wrapping works at scale 1.0, but the scale 2.0 claim is unverified.
- Golden kpi_brands_bn_mixed_categories.png is honest to its name: the Mixed Co row reads "Target ১০০ প্যাকেট · ৩০ ডজন - KPI Today —", the volume % is a dash, and the memo % is 40.00%. The other rows are unchanged.
- No existing test or golden was changed, weakened or removed: the android diff only adds tests (c1 to c8, one KpiScreensTest case, one screenshot test and the new PNG). c1 to c8 do assert what the lane lists. c6 covers the older-server tree rule only through one route with no codes, and c8 covers parsing (lower-casing, absent = null).
- Older-server behaviour looks unchanged by code reading: codedCategories returns null when any by_pack entry has no category_code, volumeLeftOut and volumeBrands keep the old tree rule when categoryVolumes is empty, and merge() groups by (null, factor), which equals the old grouping by factor. The cigarette packet check and 0-versus-dash handling are consistent with this.


# Recheck of the slice 1 fix, head 0cf576b41a, 2026-11-25: PASS

One Opus reviewer read the diff and the golden. Lane CI run 45599638134 green (lane's report).

## fix: PASS

The fix in 0cf576b41a closes the upheld B1 finding. BrandKpiScreen now uses map instead of mapNotNull. A category with a null target prints as its own part, written as the category word from resources and a dash, '%1$s —'. Parts are joined with pg_kpi_part_sep, which is in both en and bn. I traced the cases at 0cf576b41a. cigarette+bidi gives '100 packet · Bidi —'. cigarette+unknown code gives '… · Other —'. A bidi entry with target 0 and achieved > 0 still gives 'Bidi —', never 0. Three categories including bidi come out in category order with bidi last. A lighter with pack_factor 25 and 10 mixed inside a mixed brand is summed exactly as pieces. Volume % stays a dash for any mixed brand (cat null). The dashboard cigarette Volume total still takes coded cigarette entries only, and its bars use volumeBp, a dash for mixed brands, so no partial value leaks. Multi-route merge groups by (category, pack_factor), so bidi and unknown parts stay as dashes. An older server (no category_code) leaves categoryVolumes empty, so the line expression is byte-for-byte the old path. The trim-before-lowercase change applies to coded entries only. Every figure is rounded once, in qtyText. The golden kpi_brands_bn_mixed_bidi_pending.png shows 'মিক্সড কো Target ১০০ প্যাকেট · বিড়ি — - KPI Today —', wrapped onto two lines without clipping, with the volume % as a dash, so it matches its name. No test was weakened or deleted. Lows: the lighter pack-25 rule is not applied in brandVolume or in the BLM category rows (already there before this round), c10 tests a field that the single-lighter brand does not display, and the BN screen test is a substring match. Read-only review; git show/diff/grep at 0cf576b41a only; golden extracted to /tmp/claude-0/-home-user-Aron-Pro-Max/a5c3c45a-b89c-5768-9d5f-6bc05c04fc63/scratchpad/g.png.

Lows:
- The lighter pack-25 rule is applied only in KpiModel.categoryVolume, which feeds mixed coded brands. Two other paths still label any lighter pack_factor as 'box': brandVolume('lighter') (KpiModel.kt:205), used for a coded single-category lighter brand and for tree-resolved lighter brands, and the BLM category rows (KpiModel.kt:103-107, pairFromPacks(..., 'box')). Example: a lighter-only brand, or the BLM lighter row, with pack_factor 10 and target 100 gives FieldQty 10 'box'. The brand path is not visible today, because a lighter-only brand is never in volumeBrands and the MEMO list prints the memo target. The BLM dashboard and BLM list rows would show it. This behaviour was already there before this round and is outside the B1 fix, but the rule should be one rule.
- c10 builds a single-category lighter brand and asserts categoryVolumes.single().target. For that brand the row would print volumeTarget, which comes from brandVolume and is still 'box'. So c10 checks a model field that is not what that brand displays. The case where the rule actually reaches the screen, cigarette plus lighter with pack_factor 10 or a mix of 25 and 10, has no test. Pieces summing by hand: t is the sum of the lighter entries' targets in pieces, exact and rounded once. The percentage path is unchanged: a mixed brand has cat null, so volumeBp is a dash.
- Tests c9, c10 and c11 are model or parse tests. They assert categoryVolumes, the category order, the null target, qtyText of '100'/'4' and the trimmed code, not the displayed text. Of the two KpiScreensTest rows, only the EN one asserts the exact line ('Target 100 packet · Bidi — · Other — - KPI Today —'). The BN row uses substring 'বিড়ি —', so it does not prove the resource separator or the cigarette part in Bangla. The golden shows both. Both screen tests build KpiBrandRow by hand, so no single test runs build() → screen with exact text.
- Bidi-only coded brand: cat='bidi', so volume is null and volumeBp is the server's %. categoryVolumes=[bidi null]. It is not in volumeBrands (no cigarette entry), and volumeLeftOut is false because categoryVolumes is not empty, so it drops out of the cigarette Volume list with no note. This matches the old tree path for a 'bidi' category. In the MEMO list the row shows the memo target with the server's single-unit volume % beside it, which makes sense. Nothing prints a dash next to a %.
- Readability: the line 'Target 100 packet · Bidi — · Other — - KPI Today —' puts the em-dash for unknown right before the ' - ' of the line template. It is not wrong, but a user could misread it.
- The literal ' (' + … + ')' around pg_kpi_unit_unknown (KpiScreens.kt:321) was already there before this round and was not touched. The new separator and part strings are all resources: pg_kpi_part_cigarette, _bidi, _other, _sep and _unknown are present in both values and values-bn, and no ' · ' literal is left in KpiScreens.kt.


# Slice 2 (cbd39e2447): outlet target uses the per-outlet cut flag; one lighter rule, 2026-11-25: PASS

Reviewers read code and the golden only. Lane CI run 45602462680 18/18 (lane's report).

## version-inference: PASS

I checked these at cbd39e2447: RouteFigures.perOutletCut (campaigns.any { truncated != null }), OutletTargetModel.build (routeCut = globalTruncated && !perOutlet(route); outletCut = perOutlet && c.truncated == true; incomplete = skuMissing || routeCut || outletCut; stdUnit null on either cut; achievedPartial = routeCut), the screen, readRouteFigures/ReferenceRepository section storage, and server SrCampaigns.kt plus RouteCampaign.yaml. The member and the all-SKU figures arrive in the same server commit aba1b10ec8, and the member is absent from the 0cf576b41a contract as 1.26.0 only.
(1) Old server, route flag false: perOutlet false, nothing cut, everything complete, as before.
(2) Old server, flag true because A has 22 SKUs: perOutlet false, so routeCut is true for A and B. Achievement and Remaining are dashed, totals dashed, today lines say "may be incomplete", STD unit word off, route note. This is byte-for-byte the earlier behaviour. OutletTargetData.routeCut = views.any(achievedPartial) equals the old default truncated whenever views exist.
(3) New server, A flagged: perOutlet true. A shows Achievement and Remaining as sent, phone totals dashed, today lines "may be incomplete", STD unit word off, outlet note. B is complete and has its unit word, which is right because the server computes over all SKUs.
(4) New server, cap only, nothing flagged: perOutlet false, so every outlet reads the conservative old way and nothing partial is shown as complete.
(5) New server, flagged for a hidden second campaign: treated as an outlet cut, so it is conservative on figures. The note text is a low.
(6) The inference is per route (f.perOutletCut is paired with each campaign), while the route flag `truncated` is global. A global flag can only add cuts, never remove them, so one route's flag cannot make another route's outlets complete. An old route never carries the member. For an outlet on both an old cut route and a new route, distinctBy keeps the first. If that is the old one it is dashed. If it is the new one, it holds the newer server's all-SKU figure.
(7) Route sections are never merged. A full apply clears all sections, a delta replaces whole route.<id> sections with putSection, and editSection only adds open_memos. So a cached older section cannot mix with a newer one inside one RouteFigures.
I found no state where an old server's partial achieved figure is shown as complete. "May be incomplete" is applied wherever the phone's table is cut (route cut, outlet cut or a missing SKU), and the STD unit word is off for a flagged outlet. The lighter rule (lighterPair) is applied the same way to brand rows, BLM category rows and coded categories: box only when every pack factor is 25, otherwise pieces, never added across units. No test was deleted or weakened (one KpiScreens assertion became stricter). The new strings are in res for EN and BN. The new golden image shows what its name says.

Lows:
- Case (5), a new server flagging an outlet because a second active campaign is not shown (its table is not cut): the page and the pop-up now show pg_ot_cut_outlet, "The SKU list of this outlet was cut...". That gives the wrong reason, and it drops the old note's warning that the outlet may have "one more campaign, than shown". No figure is wrong: totals are dashed, today lines say "may be incomplete", and the STD unit word is off, which is more cautious than needed. But the rep is no longer told a campaign may be missing. Suggest wording for the outlet note that covers both causes (OutletTargetScreen.kt:114/216, strings.xml pg_ot_cut_outlet).
- pg_ot_cut_outlet (EN and BN) says "the STD and memo figures above". On the page (CampaignTable) no STD or memo figures are shown at all. In the pop-up the note sits above the figures, not below them. Copy only.
- Golden fixture outlet_target_bn_page_outlet_cut uses view(incomplete = true).copy(outletCut = true). The screenshot helper's last positional argument sets achievedPartial = incomplete = true, a state the model never produces (outletCut implies achievedPartial false). The page does not render achievedPartial, so the image shows what its name says (the outlet note, rows as sent, totals dashed, golden checked). The fixture should still set achievedPartial = false.
- No screen test or golden covers the pop-up (CampaignFigures) for an outletCut view showing STD/memo Achievement and Remaining as sent (300/700, 4/6). Only the model test nW3 asserts achievedPartial false. The new OutletTargetScreenTest is named 'KeepsAchievement' but renders the page, which has no achievement figures.
- For a cut outlet stdUnitKey is null, so the pop-up also shows pg_ot_std_mixed ("STD adds base units of different SKUs"). That may not be true when every sent SKU is in sticks. This behaved the same way for route cuts before this push.

## lighter-tests: PASS

No blocking finding on the checked claims; I read the diff and `git show` of the revision only and ran no builds or tests.

**lighterPair**
- Every lighter site goes through it: `build()` BLM category rows, `categoryVolume` (coded categories) and `brandVolume` (brand rows).
- Pack 25 only gives boxes through `pairFromPacks`.
- Any entry with a pack factor other than 25, pure 10 or a 25/10 mix, gives pieces. The piece figure is the server's total target and achieved times 1000, so a mix is counted once. Target and achieved use the same unit.
- `bp` is passed through, so the percentage path is unchanged.
- Empty packs, or per-pack figures that do not add up to the total, keep the old fallback to base-unit pieces.
- The old brand-row behaviour (pack 10 shown as 'boxes' via 100/10) is fixed.

**Outlet cut logic**
- A route counts as 1.26.0 only when some entry has a non-null `truncated`. The parser reads `o.bool("truncated")`, which the lane says gives null for an absent or null member.
- Old route: `routeCut` equals the route flag, `achievedPartial` equals `routeCut`, `incomplete` and `stdUnit` are as before, and the data-level `routeCut` default equals `truncated`. Old-server behaviour is unchanged.
- New route, entry flagged true: Achievement and Remaining stay as sent, the phone's totals and today lines are dashed or marked incomplete, the STD unit word is off, and the per-outlet note appears.
- New route, entry unflagged, null or false: complete, even when the route flag is true because another outlet was cut. This matches the server (SrCampaigns.kt sets the flag per entry and counts the flagged outlets into the route flag).
- Outlet absent from the answer on a cut route: the empty answer with the cut note (nW6).

**Tests**
- The `OutletTargetModelTest` additions nW1 to nW6 assert what the lane lists, with concrete figures (std 1000/300/700, memo 10/4/6, totals null, pct 24, stdUnit "stick" when not cut).
- KpiModelTest c12 covers the old tree path and coded entries, pack 10 giving pieces and pack 25 giving boxes, for both brand and category rows.
- The one removed test line in the diff is the Bangla row substring check. It was replaced with the whole exact line, which is stronger. No assertion was weakened or deleted.
- The new KpiScreensTest cases feed server JSON through `RouteFigures.parse` and `KpiModel.build` into the screen. They assert the exact lines 'Target 100 packet · 30 dozen - KPI Today —' and 'Target 100 piece'.

**Golden and strings**
- outlet_target_bn_page_outlet_cut.png shows the per-outlet note, the SKU rows, and the cigarette, lighter and match totals and percentage lines as dashes, with nothing clipped. It does not show Achievement or Remaining because the page has no STD rows. Those are on the pop-up, which has no outlet-cut golden (see the lows).
- `pg_ot_cut_outlet` is present in both values and values-bn. No hardcoded strings were added.

Lows:
- The new note pg_ot_cut_outlet (values and values-bn) says the STD and memo figures are 'above' (উপরের). On the target PAGE (OutletTargetScreen) no STD or memo figures are shown at all, and in the pop-up the note sits above the figures, not below them. The golden outlet_target_bn_page_outlet_cut shows exactly this: the note points at figures that are not on the page. The wording is misleading, but it does not print a wrong figure.
- The same note says 'The SKU list of this outlet was cut'. The server also sets the per-entry flag when another active campaign of the outlet is not shown (moreThanOne in SrCampaigns.kt) while the SKU table is complete. In that case the note describes the wrong cause. The old pg_ot_cut text named both causes. The phone dashes the totals either way, so this is conservative.
- No screenshot and no UI test shows Achievement and Remaining as figures for an outlet-cut entry. The only new golden is the page (no STD rows) and the only new screen test, aCutOutletOfANewServerKeepsAchievementAndNamesWhatIsPartial, asserts the note tag and text only. The behaviour is covered at model level (nW3: achievedPartial false, std 1000/300/700 and memo 10/4/6 as sent, stdUnitKey null, totals null) and by the unchanged CampaignFigures code (std_a, std_r, memo_a and memo_r dash only on achievedPartial). A pop-up golden or a tagged-figure assertion with outletCut=true, routeCut=false would close the gap. Likewise the old-server, flag-null/false and empty-cut cases are tested only in OutletTargetModelTest (nW1, nW4, nW6), not on the screen.
- In RouteFigures.kt the new KDoc for perOutletCut was inserted between the existing '/** The month of the figures ... */' comment and `val month`. The month comment is now detached from the property it describes. Cosmetic only.
- A bundle that mixes routes can read conservatively. The route flag is any() over all routes, while perOutletCut is per route, so a route without flagged entries in a bundle where another route's list was cut is read the old way (dashed). That is safe, not a wrong figure.
- The Bangla note writes 'SKU' in Latin script while neighbouring strings use এসকেইউ. This matches pg_ot_cut and pg_ot_std_mixed, so it is consistent with existing practice.


# Slice 3 (3d003bf851): Lifting Star today line, 2026-11-25

Reviewers read code and goldens only. Lane CI run 45606187996 green (lane's report).

## star-today-model: FAIL (blocking raised 2, upheld 2)

FAIL at 3d003bf851. The new StarToday model is correct on its own: the SKU rule order, the unit divisor with one half-up rounding, dash cases, truncated wording, sale-lines-only through FigureFrame, through_date edge, separate unconfirmed count, bn/en strings and goldens all check out. With all new fields null, the screen output is unchanged for an older server. Two wiring problems in ProgrammeHosts.readStars break the comparability rule. (1) memoIn = routeId == null || routeId == f.routeId counts every route-less memo on every Star of the rep, so one memo appears on two routes' cards. The server STD (SrMemoFigures: m.route_id = ANY(:r), nullable column) never counts such a memo on any route. (2) The category map comes from activeSkus(), while the server matches category over all catalogue SKUs. A sale of a since-withdrawn or unknown SKU is dropped silently, with no 'may be incomplete' marking. ReferenceDao.skuCategories() already exists for this. Neither path has a host-level test.

### UPHELD: Route-less memos are counted on every route's Star (double counting), and the server never counts them in any route's STD

android/app-sr/src/main/kotlin/com/aktcl/aron/sr/ProgrammeHosts.kt

readStars builds each Star's frame with `memoIn = { it.routeId == null || it.routeId == f.routeId }`. The server STD (SrStar via SrMemoFigures, line 78) selects only `m.route_id = ANY(:r)`, and `app.memo.route_id` is nullable (V0007), so a memo stored with no route is never in any route's STD. Concrete input: a rep with two routes 7 and 9, each with a Star (category match), and one live sale memo dated after through_date with routeId = null and a match line of 24 sticks, divisor 12. Both Star cards show 'Today on this phone: 2.00 STD, 1 memo'. The memo is counted on two routes' Stars, and neither server STD will ever include it, so the line does not count the same memo set as the server STD. The same predicate also feeds `unconfirmedEarlier`, so an unconfirmed route-less earlier-day memo raises the count on every card. 'As in KPI' does not justify this: readKpi uses one aggregate frame over all the rep's routes, so it counts each memo once. ProgrammeStore.readSideOf states the rule this code breaks: 'a memo with no route belongs to no route in particular: counted once, by the caller's first route'. Fix: count only `it.routeId == f.routeId`, the server rule; if route-less memos must show at all, show them once and labelled. Add a host-level test for two routes plus one route-less memo.

### UPHELD: Category rule uses only ACTIVE SKUs, while the server counts every catalogue SKU of the category; the undercount is silent

android/app-sr/src/main/kotlin/com/aktcl/aron/sr/ProgrammeHosts.kt

`val categories = day.reference.skus().associate { it.skuId to it.categoryCode }`, and ReferenceRepository.skus() is `dao.activeSkus()` (`WHERE status = 'active'`). The server's accept rule is `dim.skus[sku]?.category == category`, where SrMemoFigures.dim reads every row of app.sku, inactive ones too. Concrete input: Star with sku_category 'match'; today the phone sold 36 sticks of match SKU 41; SKU 41 is then withdrawn (status inactive) in the next bundle. The server STD counts SKU 41's lines, but `categoryOf(41)` returns null on the phone, so the line drops them and shows 0.00 STD with nothing marked. A SKU missing from the phone's tree is undercounted the same way, also unmarked (the 'may be incomplete' wording fires only on sku_ids_truncated). The DAO already has `skuCategories()`, documented as 'Every SKU of the bundle, inactive ones too (a memo line of an SKU since withdrawn still has a category)', and Reconciliation uses it. Fix: use skuCategories(). For a memo line whose SKU has no category on the phone, either show a dash or switch to the 'may be incomplete' wording.

Lows:
- Older-server path is not fully unchanged in failure behaviour: readStars now calls memosOfMonth and ackedBeforeRequest for every Star month even when no row has a SKU rule (skuFilter is null for all). If either read throws, the whole Star load fails to the error/retry state, where before it rendered. Skip the memo reads when no row yields a filter. With a successful read the composed output is identical: today[i] == null takes the unchanged StarCard branch.
- StarScreen KDoc still says 'The phone's own sales are not shown here: the programme's SKU list is not in the bundle.' That is now false; update it.
- StarTodayModel.of KDoc says 'null when the row has no SKU rule', but it returns non-null StarToday; the null comes from skuFilter.
- pg_star_today / pg_star_today_partial use a fixed 'memo' noun with a count ('2 memo' in the EN golden and tests). Consider a plurals resource, like pg_tgt_unconfirmed.
- No test covers readStars itself (route filter, category map source, months per Star). StarTodayTest exercises the model and screen only, so both blocking issues pass the current tests.
- runCatching around FigureFrame.of in readStars swallows any exception: the line silently disappears for that Star instead of showing a dash. Acceptable, but it hides failures.
- Checked and fine: sku_ids precedence over category, and an empty list gives no line; the server never sends both. Sale lines only, memoKind sale, rejected/void/superseded handled by FigureFrame. A memo dated exactly on through_date is excluded from today and only counted when unconfirmed. Half-up rounding happens once (50/12 gives 4.17, 1/8 gives 0.13). Divisor below 1, negative and overflow give a dash. Divisor null is treated as 1, matching the server default. sku_ids_truncated selects the partial wording. Strings exist in bn and en. Goldens star_bn_today, star_en_today_partial_unconfirmed and outlet_target_en_popup_outlet_cut show what their names say, and the server's figures are untouched (2.83 STD as sent). The OutletTargetScreenTest substring change follows the new wording and is not weakened, and the new pop-up test asserts that 300/700 are shown as sent.

## star-screen-tests: FAIL (blocking raised 1, upheld 1)

The slice is mostly sound, but FAIL on one concrete defect. StarToday for each route uses memoIn = routeId == null || routeId == this route, so a route-less memo is added to every Star's today line, although the server STD (route_id = ANY) counts no route-less memo. With two Star routes in the bundle the same memo shows on both, which breaks the 'never double counted across routes' and 'same route' rules. No test covers it. The rest checks out against the code and the two new Star goldens: SKU rule, single rounding, dash cases, parse of new versus older server, the separate line under an untouched server STD 2.83, truncated wording plus unconfirmed line, the exact Bangla line, bn and en strings with matching placeholders, and the outlet-cut wording, popup test and goldens. No assertion was weakened and no star_* golden changed.

### UPHELD: Route-less memos counted on every Star's today line (not what the server STD counts, and double counted across routes)

android/app-sr/src/main/kotlin/com/aktcl/aron/sr/ProgrammeHosts.kt

readStars passes memoIn = { it.routeId == null || it.routeId == f.routeId } for each Star. The server STD reads memos with `m.route_id = ANY(:r)` (SrMemoFigures), so it never counts a route-less memo. Concrete input: a bundle day with two routes that both have a Star (routesOfDay can return several; readStars builds one StarToday per route) and one live sale memo with routeId == null dated after through_date on a programme SKU. Both Star cards add its quantity and memo count to their 'Today on this phone' line. The server counted it on neither STD, so the lines are not comparable, and the same memo is on two routes' Stars. This breaks the binding rule 'never double counted across routes' and 'this route only'. The lane's justification 'as in KPI' does not hold: KPI sums over all the day's routes, and Star is per route. The fix is memoIn = { it.routeId == f.routeId }. Route-less memos could instead be left out or counted in a separate labelled line. No test covers the route filter: StarTodayTest builds every memo with routeId = 7 and never calls it with another route or a null one, and the filter lives in app-sr where no test reaches it.

Lows:
- StarTodayTest never tests the route filter (another route's memo, a route-less memo). The host-level wiring (skuFilter with the reference category map, memoIn, month grouping) has no test, so the blocking defect above is not caught.
- readStars now reads day.reference.skus(), day.programmes.ackedBeforeRequest() and memosOfMonth() outside runCatching, for every bundle including an older server whose rows name no SKU rule (those rows always produce null). If any of those reads throws, the Star screen now shows the load-failed state where it used to render. Only the per-row FigureFrame call is guarded. The reads could be skipped when no row has a rule, or wrapped.
- The StarScreen KDoc still says 'The phone's own sales are not shown here: the programme's SKU list is not in the bundle.' That is now false.
- The popup test asserts Achievement/Remaining with substring matches '300' and '700', which are loose and could match other text such as '1,300'. The golden does show 300 and 700 as sent, so the claim holds, but the assertion is weak. A tag-scoped check would be tighter.
- StarToday.of's KDoc says 'null when the row has no SKU rule' but the function always returns a value; the null comes from skuFilter and the host. This is documentation only.
- No blocking problem found in the rest. The SKU rule matches the server: ids if non-empty, else category, else none (the server never sends both, and it omits the category outside the enum). unit_divisor below 1 falls back to 1 on both sides. The rounding arithmetic checks out: 50/12 gives 4.17, 1/8 gives 0.13, a divisor below 1, a negative quantity or an overflow gives a dash, 0 base units gives 0.00. The strings exist in values and values-bn with matching %1$s/%2$s placeholders and the pg_tgt_unconfirmed plural is reused. The two Star goldens show the new line with the server figures (Target 20.00, STD 2.83) unchanged and nothing clipped. The outlet-cut popup golden shows the note (both causes, no 'above'), with Achievement 300 and Remaining 700 as sent. No existing star_* golden changed, and no assertion was weakened: the one changed assertion moves to the new wording and a test was added. An older server leaves skuIds, skuCategory and unitDivisor null, so the filter is null and no line shows.


# Recheck of the slice 3 fixes, head dbb34bcd08, 2026-11-25: PASS

Reviewers read code and the golden only. Lane CI run 45610073619 green (lane's report).

## wiring: PASS

I reviewed dbb34bcd08 against 3d003bf851 in android/ and found nothing blocking. Each point below was checked against the code.

S1 is fixed. StarTodayModel.forRoutes uses `mine = { f.routeId != null && it.routeId == f.routeId }`, both as FigureFrame's memoIn (which drives today and unconfirmedEarlier) and in the unresolved check. A memo with no route is on no card. A memo of route 7 counts only on route 7. This matches the server: SrMemoFigures selects `m.route_id = ANY(:r)` and groups by route.

S2 is fixed. ReferenceRepository.skuCategories() calls ReferenceDao.skuCategories, which is `SELECT sku_id, category_code FROM sku` with no status filter, so inactive SKUs are included. SKUs deleted by a delta or a full clear are absent from the table and therefore count as unresolved. The unresolved flag only applies when skuIds is null or empty (the category rule). It only looks at this route's non-rejected memos of kind sale in s.month dated after through_date, and only at their sale lines. A memo of another route or an earlier day does not mark the line. With sku_ids listed, the category map is not used, and test s2 checks this.

Older server: readStars checks `rows.none { model.hasRule(it) }` first. In that case it maps every row to null and does not read skuCategories, ackedBeforeRequest or memosOfMonth. StarScreen then renders the previous branch (no today block, since the unconfirmed line sits inside `if (today != null)`).

Failed read: the host catch (which rethrows CancellationException) gives failedOf for rows with a rule and null for the rest. The line reads '— STD, —' plus the failed reason, and the rest of the card (server figures) renders as before.

Plurals: en uses one 'memo' / other 'memos'; bn has the same text for both. All new strings are in values and values-bn. Existing assertions were changed only for the memo/memos wording; nothing was removed.

The golden star_en_today_partial_unconfirmed.png shows what its name says: '4.17 STD, 2 memos (may be incomplete…)' and the line about 2 unconfirmed memos. The bn golden needed no change.

Tests s1, s1b, s2 and the read-count test call forRoutes, the same function readStars calls. However, the host-level skip and the host catch path in readStars have no test (see lows).

Main files: /home/user/Aron-Pro-Max/android/feature-programmes/src/main/kotlin/com/aktcl/aron/feature/programmes/StarToday.kt, /home/user/Aron-Pro-Max/android/app-sr/src/main/kotlin/com/aktcl/aron/sr/ProgrammeHosts.kt, /home/user/Aron-Pro-Max/android/core-database/src/main/kotlin/com/aktcl/aron/core/database/dao/ReferenceDao.kt.

Lows:
- Unconfirmed count, S2 edge (StarToday.kt forRoutes / FigureFrame.of): an unconfirmed memo of this route dated on or before through_date whose only sale lines are SKUs missing from the phone's sku table (removed by a delta `skus` delete) fails `relevant()`. It is left out of unconfirmedEarlier and the line is not marked incomplete either, because the unresolved check only looks at days after through_date. The server would count it by category once received, so the unconfirmed count can silently come out one short. This is rare and outside the stated S2 scope (the 'today' line). Applying the same unresolved check to the window memos, or adding them to the count, would close it.
- The unresolved check in forRoutes looks at every non-rejected sale memo of the route after through_date, including memos already superseded or voided and fork tips that FigureFrame leaves out of today. An old version holding an unknown SKU therefore marks the line 'may be incomplete' even when the live edit dropped it. This only over-flags; it never undercounts.
- When the rule is a category, the incomplete text still says 'the programme lists more SKUs than this phone received'. That is roughly true for an unresolved SKU but was written for truncation; a separate reason string would be clearer.
- A Star whose RouteFigures has route_id null gets '0.00 STD, 0 memos' rather than a dash, because the `f.routeId != null` guard makes it count nothing. route_id is required in contract RouteSnapshot, so this only happens with an off-contract payload. Returning failedOf or null for a row with no route id would follow the 'unknown = dash' rule.
- Host wiring is not covered by tests. The read-count test (lowARowWithoutARuleIsNullAndNeverReadsAnyMemo) and the failed-read test call forRoutes directly. In production, readStars reads memosOfMonth, acked and skuCategories eagerly BEFORE forRoutes and catches failures in its own try. So the 'no reads when no row has a rule' guard (`rows.none { model.hasRule(it) }`) and the host catch path that feeds failedOf are untested, and forRoutes's own per-row catch never fires in production. I checked by reading the code that the host is correct: with no rule nothing is read, today is all null and StarScreen renders the previous branch. A readStars test with a fake SrDay would lock this in.
- readStars reads memosOfMonth for the months of all Star rows, including rows with no rule, whenever at least one row has a rule. This only costs extra reads; it is not a correctness problem.
- Already there before this round, not changed here: FigureFrame keeps only memoKind == 'sale', while the server's SrMemoFigures ACTIVE_MEMO filter has no memo-kind predicate. Also, if an edit ever moved a memo to another route, the superseded original would remain a tip on the old route's frame. Edits keep the visit/outlet today, so this cannot happen now.

## strings-golden: PASS

Lens check passes. Strings: pg_star_today, _partial, _failed and the plural pg_star_today_memos exist in both values and values-bn with matching %1$s/%2$s placeholders. English has one/other (memo/memos); Bangla has one/other items with the same text. The old trailing "memo" was moved out of the three line strings into the plural, so there is no double word. StarScreen.kt: the plural count is coerced to a safe Int range, and the failed state uses the dash for both the quantity and the memo phrase. The only non-resource literal is Percent.DASH, a typographic dash and not user-facing prose. The Compose imports for pluralStringResource and Percent were already present. Golden star_en_today_partial_unconfirmed.png is the only png in the diff. Viewed, it shows "Today on this phone: 4.17 STD, 2 memos (may be incomplete: ...; not in the STD above)" and the unconfirmed line "2 memos of earlier days are not confirmed...". Server figures are unchanged (Target 20.00, STD 2.83, Projection 7.37, Achievement 36.83%, LMS 12.25) and nothing is clipped. star_bn_today.png is not in the diff, and the Bangla assertion "আজ এই ফোনে: ৪.১৭ STD, ২ মেমো (উপরের STD-তে নেই)" is unchanged and still matches the new bn string and plural. StarTodayTest: no assertion was removed or weakened. The two existing English assertions only changed "memo" to "memos" to match the plural for counts 2 and 0, which is correct. New tests were added for S1, S1b, S2, no-rule-no-read, failed-read dash line and the singular form. The Kotlin diff has no hardcoded user-facing text. The other lens items (SKU set, unit, kind, route) were not my scope and were not exhaustively checked.

Lows:
- values-bn pg_star_today_memos has identical one and other items. That is acceptable (the 'one' form covers 0 and 1 in Bengali) but redundant.
- The failed branch in StarScreen skips the plural and uses Percent.DASH directly, so pg_star_today_failed always receives a dash for %2$s. It works and the test covers it. Folding the memo text into the string would be marginally cleaner.
- pg_star_today_failed is covered only in English by a test; the bn string has the same placeholders but no bn render assertion.

# 355bfe83d8 (Star follow-up round), 2026-11-25: PASS, 0 blocking (2 read-only lenses)

Lenses: figures; seam, tests and strings. Neither ran a test; both read the diff and the full files. Lane CI 45620449054 green by the lane's report.

Confirmed by reading: no rule = no database read and no line; a failed read = dash line, no crash; reads on the IO dispatcher, none repeated; unresolved SKU on live tips flags the line and is counted as unconfirmed; the two reasons are separate; a Star without a route shows dashes; rounding once; new strings in English and Bangla with matching placeholders; tests n2, n2b, n4 fail on the earlier code.

Lows sent to the lane for its next round:
1. A live tip whose only sale lines are unresolved SKUs shows "0.00 STD, 0 memos (may be incomplete)". Unknown should be a dash, or the figure visibly a lower bound.
2. When the server cut the SKU list, the unconfirmed count can also be short; the wording does not say so.
3. StarReadTest does not assert read counts in the good-read case (memo reads = distinct months, acked reads = 1).
4. The failed-line dash text is not asserted on the screen.

# f2a9669dd8 (Star round 3 + contract 1.27.0 consumption), 2026-11-25: FAIL, 1 blocking upheld 3 of 3

Star round 3: pass by reading (dash for unknown-only, "at least" for mixed, true 0.00 kept, short-count note only when the server cut the SKU list). Lows: "at least" before a dash is possible with a malformed divisor; a fork plus an unknown SKU shows a dash where a lower bound would do (safe direction); f4 and the failed-read assertions pass on the earlier code too.

Blocking (contract lens, upheld by three Opus refuters): a bundle from the 1.26.0 server (train 133) for a route with more than 150 campaign outlets and at least one entry carrying truncated=true is read as new-style, because perOutletCut is true as soon as ANY entry has the member. The outlets flag is absent, so an outlet with no entry gets "No campaign target is set for this outlet" although the list was cut. Before this diff it got the cut note; the test that pinned it (old nW6) was removed. Fix: a route is new-style only when campaigns_outlets_truncated is present or EVERY entry carries the member; otherwise the old route-flag rule. Restore the old-payload test. Cases (a) to (g) of the 1.27.0 reading are right.

# ec48c3684d (old/new bundle detection fix), 2026-11-25: PASS, 0 blocking (2 read-only lenses)

Note: a first run of this recheck read the previous head because the lead had not fetched the branch; that run is void. This result is from the run pinned to ec48c3684d.

Truth table over servers 1.25, 1.26 and 1.27 and every cut combination: no payload gives a complete figure for a cut entry, and none gives the definite "no campaign target" answer for an outlet missing from a cut list. A route is read as new-style only with the outlets marker or when every entry carries the member; the one shape that cannot be told apart (every entry cut, route flag set, no marker) takes the cautious note. Older payloads read as before (nW10 restored; nX1 would fail on the previous head). The only callers are RouteFigures and OutletTargetModel. Nothing was run by the reviewers; lane CI 45641349999 green by the lane's report.

Lows: orphan KDoc above perOutletCut and two stale KDoc lines; no test for two routes of different shapes in one list; a malformed entry dropped by the parser can change the reading (as before this diff).

Not verified: any of this on a device or against a live 1.27.0 bundle.
