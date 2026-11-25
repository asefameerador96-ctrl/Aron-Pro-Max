package com.aktcl.aron.feature.programmes

import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aktcl.aron.core.common.AppLanguage
import com.aktcl.aron.core.ui.AppLocale
import com.aktcl.aron.core.ui.AronTheme
import com.aktcl.aron.rules.UiLocale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class StarTodayTest {
    @get:Rule val compose = createComposeRule()

    private fun star(ids: List<Long>? = null, cat: String? = null, divisor: Long? = null, idsCut: Boolean = false) = RouteStar(
        3, "Match Box Lifting Star", null, "2026-11", "2026-11-10", "2026-11-11T01:00:00Z", "SR", "sr1", "Route", 26, 26, 10,
        20_000, 2_833, 7_366, 3_683, 12_250, 471, 283, -3_987, "Keep Pushing", "চালিয়ে যান", null, null, null, null, divisor, cat, ids, idsCut,
    )
    private fun memo(uuid: String, date: String, vararg lines: Pair<Long, Long>, state: String = "pending") =
        LocalMemo(uuid, 5, date, null, state, null, lines.map { LocalMemoLine(it.first, "sale", it.second, 1, 0) }, routeId = 7)
    private fun frame(memos: List<LocalMemo>, keep: (Long) -> Boolean) = FigureFrame.of(memos, "2026-11", "2026-11-10", "x", emptySet(), lineIn = { keep(it.skuId) })

    private val category = mapOf(1L to "match", 2L to "match", 3L to "cigarette")

    @Test fun theSkuRuleIsTheListedIdsElseTheCategoryAndNoneWhenTheRowNamesNeither() {
        val ids = StarTodayModel.skuFilter(star(ids = listOf(1, 3))) { category[it] }!!; assertTrue(ids(1)); assertTrue(ids(3)); assertTrue(!ids(2))
        val cat = StarTodayModel.skuFilter(star(cat = "match")) { category[it] }!!; assertTrue(cat(1)); assertTrue(cat(2)); assertTrue(!cat(3))
        assertNull(StarTodayModel.skuFilter(star()) { category[it] }) // an older server: no line
        assertNull(StarTodayModel.skuFilter(star(ids = emptyList())) { category[it] }) // an empty list counts nothing: no line
    }

    @Test fun todayCountsOnlyTheProgrammesSkusAndOnlyDaysAfterThroughDateInTheProgrammeUnit() {
        val memos = listOf(memo("a", "2026-11-11", 1L to 30L, 3L to 500L), memo("b", "2026-11-12", 2L to 20L), memo("c", "2026-11-10", 1L to 99L), memo("d", "2026-11-12", 3L to 10L))
        val keep = StarTodayModel.skuFilter(star(cat = "match", divisor = 12)) { category[it] }!!
        val t = StarTodayModel.of(star(cat = "match", divisor = 12), frame(memos, keep))
        assertEquals(50L, t.qtyBase); assertEquals(2L, t.memoCount) // memo c is on the closed day (a COUNT only if unconfirmed), memo d is a cigarette memo
        assertEquals("4.17", StarTodayModel.qtyText(t, UiLocale.EN)) // 50 / 12 = 4.1666: rounded once, half up
        assertEquals(1, t.unconfirmedEarlier) // memo c: a closed day the server has not confirmed: a count, not in the quantity
        assertEquals("৪.১৭", StarTodayModel.qtyText(t, UiLocale.BN))
    }

    @Test fun noDivisorMeansBaseUnitsAndTheRoundingIsOnceFromTheExactQuotient() {
        assertEquals("0.13", StarTodayModel.qtyText(StarToday(1, 8, 1, 0, false), UiLocale.EN)) // 0.125 half up
        assertEquals("7.00", StarTodayModel.qtyText(StarToday(7, 1, 1, 0, false), UiLocale.EN))
        assertEquals("—", StarTodayModel.qtyText(StarToday(7, 0, 1, 0, false), UiLocale.EN)); assertEquals("—", StarTodayModel.qtyText(StarToday(-1, 1, 1, 0, false), UiLocale.EN))
        assertEquals("—", StarTodayModel.qtyText(StarToday(Long.MAX_VALUE, 1, 1, 0, false), UiLocale.EN)) // overflow is a dash
    }

    @Test fun theNewFieldsAreParsedAndAnOlderServerLeavesThemNull() {
        val base = """{"programme_id":3,"name_en":"Star","month":"2026-11","through_date":"2026-11-10","route_name":"R","lmwd":26,"wd":26,"ewd":10,"target":"20.000","std":"2.833","lms":"12.250","status_en":"a","status_bn":"b""""
        val old = RouteFigures.parse("""{"route_id":7,"star":$base}}""")!!.star!!
        assertNull(old.unitDivisor); assertNull(old.skuCategory); assertNull(old.skuIds); assertTrue(!old.skuIdsTruncated)
        val new = RouteFigures.parse("""{"route_id":7,"star":$base,"unit_divisor":12,"sku_ids":[2,5,9],"sku_ids_truncated":true}}""")!!.star!!
        assertEquals(12L, new.unitDivisor); assertEquals(listOf(2L, 5L, 9L), new.skuIds); assertTrue(new.skuIdsTruncated); assertNull(new.skuCategory)
        val cat = RouteFigures.parse("""{"route_id":7,"star":$base,"sku_category":" Match "}}""")!!.star!!
        assertEquals("match", cat.skuCategory); assertNull(cat.skuIds)
    }

    private fun show(language: AppLanguage, star: RouteStar, today: StarToday?) {
        val ctx = AppLocale.wrap(ApplicationProvider.getApplicationContext<Context>(), language)
        val loc = if (language == AppLanguage.BN) UiLocale.BN else UiLocale.EN
        compose.setContent { CompositionLocalProvider(LocalContext provides ctx) { AronTheme(language) { StarScreen(listOf(star), loc, today = listOf(today)) } } }
        compose.waitForIdle()
    }

    @Test fun theLineIsSeparateFromTheServerStdAndTheStdIsUntouched() {
        show(AppLanguage.EN, star(cat = "match", divisor = 12), StarToday(50, 12, 2, 0, false))
        compose.onNodeWithTag(StarTags.TODAY).assertExists()
        compose.onNodeWithText("Today on this phone: 4.17 STD, 2 memos (not in the STD above)").assertExists()
        compose.onNodeWithTag(StarTags.STD).assertExists(); compose.onNodeWithText("2.83").assertExists() // the server's STD as sent
        assertEquals(0, compose.onAllNodesWithTag(StarTags.UNCONFIRMED).fetchSemanticsNodes().size)
    }

    @Test fun noLineWhenTheRowHasNoSkuRule() {
        show(AppLanguage.EN, star(), null)
        assertEquals(0, compose.onAllNodesWithTag(StarTags.TODAY).fetchSemanticsNodes().size)
    }

    @Test fun aTruncatedSkuListSaysMayBeIncompleteAndTheUnconfirmedCountIsItsOwnLine() {
        show(AppLanguage.EN, star(ids = listOf(1), idsCut = true), StarToday(0, 1, 0, 2, true))
        compose.onNodeWithText("Today on this phone: 0.00 STD, 0 memos (may be incomplete; not in the STD above)").assertExists()
        compose.onNodeWithTag(StarTags.REASON_LIST).assertExists(); compose.onNodeWithText("The programme lists more SKUs than this phone received.").assertExists()
        compose.onNodeWithTag(StarTags.UNCONFIRMED).assertExists()
        compose.onNodeWithText("2 memos of earlier days are not confirmed by the server yet; they are not in the figures.").assertExists()
    }

    @Test fun banglaLineIsExactWithBanglaDigits() {
        show(AppLanguage.BN, star(cat = "match", divisor = 12), StarToday(50, 12, 2, 0, false))
        compose.onNodeWithText("আজ এই ফোনে: ৪.১৭ STD, ২ মেমো (উপরের STD-তে নেই)").assertExists()
    }

    // ---- lead review S1, S2 and lows: the wiring (StarTodayModel.forRoutes is what the host calls)
    private fun routeFigures(routeId: Long, extra: String) = RouteFigures.parse(
        """{"route_id":$routeId,"star":{"programme_id":3,"name_en":"Star","month":"2026-11","through_date":"2026-11-10","route_name":"R$routeId","lmwd":26,"wd":26,"ewd":10,"target":"20.000","std":"2.833","lms":"12.250","status_en":"a","status_bn":"b"${if (extra.isEmpty()) "" else ",$extra"}}}""",
    )!!
    private fun memoOn(uuid: String, route: Long?, vararg lines: Pair<Long, Long>) =
        LocalMemo(uuid, 5, "2026-11-12", null, "pending", null, lines.map { LocalMemoLine(it.first, "sale", it.second, 1, 0) }, routeId = route)

    @Test fun s1_aMemoWithNoRouteIsOnNoRoutesLineAndAMemoOfOneRouteIsOnThatRoutesLineOnly() {
        val figs = listOf(routeFigures(7, """"sku_category":"match","unit_divisor":12"""), routeFigures(8, """"sku_category":"match","unit_divisor":12"""))
        val memos = listOf(memoOn("none", null, 1L to 24L), memoOn("r7", 7, 2L to 12L))
        val t = StarTodayModel.forRoutes(figs, category, { memos }, emptySet())
        assertEquals(12L, t[0]!!.qtyBase); assertEquals(1L, t[0]!!.memoCount) // route 7: its own memo only, not the route-less one
        assertEquals(0L, t[1]!!.qtyBase); assertEquals(0L, t[1]!!.memoCount)  // route 8: nothing
        assertEquals(0, t[0]!!.unconfirmedEarlier); assertEquals(0, t[1]!!.unconfirmedEarlier)
    }

    @Test fun s1b_aRouteLessMemoOfAClosedDayIsNotCountedAsUnconfirmedOnAnyCard() {
        val figs = listOf(routeFigures(7, """"sku_category":"match""""), routeFigures(8, """"sku_category":"match""""))
        val memos = listOf(memo("closed", "2026-11-09", 1L to 5L).copy(routeId = null))
        StarTodayModel.forRoutes(figs, category, { memos }, emptySet()).forEach { assertEquals(0, it!!.unconfirmedEarlier) }
    }

    @Test fun s2_aSaleOfAWithdrawnSkuStillCountsWhenTheFullCategoryMapHasItAndAnUnknownSkuMakesTheLineIncomplete() {
        val figs = listOf(routeFigures(7, """"sku_category":"match""""))
        val full = category + (9L to "match") // 9 is withdrawn but still in the phone's table
        val withdrawn = StarTodayModel.forRoutes(figs, full, { listOf(memoOn("a", 7, 9L to 6L)) }, emptySet())[0]!!
        assertEquals(6L, withdrawn.qtyBase); assertTrue(!withdrawn.unknownSku)
        val unknown = StarTodayModel.forRoutes(figs, category, { listOf(memoOn("a", 7, 77L to 6L)) }, emptySet())[0]!! // 77 is not in the table at all
        assertEquals(0L, unknown.qtyBase); assertTrue(unknown.unknownSku); assertTrue(!unknown.incomplete) // it may belong to the programme: the line says so, with its own reason
        val ids = StarTodayModel.forRoutes(listOf(routeFigures(7, """"sku_ids":[1]""")), category, { listOf(memoOn("a", 7, 77L to 6L)) }, emptySet())[0]!!
        assertTrue(!ids.unknownSku) // an explicit id list needs no category: a SKU outside it is simply not the programme's
    }

    @Test fun lowARowWithoutARuleIsNullAndNeverReadsAnyMemo() {
        var reads = 0
        val t = StarTodayModel.forRoutes(listOf(routeFigures(7, "")), category, { reads++; emptyList() }, emptySet())
        assertNull(t[0]); assertEquals(0, reads); assertTrue(!StarTodayModel.hasRule(star()))
    }

    @Test fun lowAFailedMemoReadGivesADashLineNotAVanishedOne() {
        val t = StarTodayModel.forRoutes(listOf(routeFigures(7, """"sku_category":"match"""")), category, { error("db") }, emptySet())[0]!!
        assertTrue(t.failed); assertEquals("—", StarTodayModel.qtyText(t, UiLocale.EN)); assertEquals("—", StarTodayModel.memoCountText(t, UiLocale.EN))
        show(AppLanguage.EN, star(cat = "match"), t)
        compose.onNodeWithText("Today on this phone: — STD, — (this phone could not read its memos just now)").assertExists()
    }

    @Test fun lowOneMemoIsSingular() {
        show(AppLanguage.EN, star(cat = "match"), StarToday(12, 12, 1, 0, false))
        compose.onNodeWithText("Today on this phone: 1.00 STD, 1 memo (not in the STD above)").assertExists()
    }

    // ---- next round: unresolved SKUs in the unconfirmed count, live tips only, separate reasons, no route id
    private fun memoDay(uuid: String, date: String, route: Long, state: String, supersedes: String? = null, vararg lines: Pair<Long, Long>) =
        LocalMemo(uuid, 5, date, supersedes, state, null, lines.map { LocalMemoLine(it.first, "sale", it.second, 1, 0) }, routeId = route)

    @Test fun n2_anUnconfirmedEarlierDayMemoOfAnUnknownSkuIsCountedAndAnAckedOneIsNot() {
        val figs = listOf(routeFigures(7, """"sku_category":"match""""))
        val pending = memoDay("p", "2026-11-09", 7, "pending", null, 77L to 5L) // 77 is not in the table: may be a match, may be in the server figure
        val acked = memoDay("a", "2026-11-08", 7, "acked", null, 77L to 5L)
        assertEquals(1, StarTodayModel.forRoutes(figs, category, { listOf(pending, acked) }, setOf("a"))[0]!!.unconfirmedEarlier)
        // a memo with a counted line AND an unknown line is counted once
        val both = memoDay("b", "2026-11-09", 7, "pending", null, 1L to 5L, 77L to 5L)
        assertEquals(1, StarTodayModel.forRoutes(figs, category, { listOf(both) }, emptySet())[0]!!.unconfirmedEarlier)
    }

    @Test fun n2b_aSupersededVersionWithAnUnknownSkuDoesNotFlagTheLineButTheLiveTipDoes() {
        val figs = listOf(routeFigures(7, """"sku_category":"match""""))
        val old = memoDay("old", "2026-11-12", 7, "pending", null, 77L to 5L)
        val edit = memoDay("new", "2026-11-12", 7, "pending", "old", 1L to 5L) // the edit dropped the unknown SKU
        assertTrue(!StarTodayModel.forRoutes(figs, category, { listOf(old, edit) }, emptySet())[0]!!.unknownSku)
        val live = memoDay("live", "2026-11-12", 7, "pending", null, 77L to 5L)
        assertTrue(StarTodayModel.forRoutes(figs, category, { listOf(live) }, emptySet())[0]!!.unknownSku)
        val rejected = memoDay("rej", "2026-11-12", 7, "rejected", null, 77L to 5L)
        assertTrue(!StarTodayModel.forRoutes(figs, category, { listOf(rejected) }, emptySet())[0]!!.unknownSku)
    }

    @Test fun n3_theTwoReasonsAreSeparateFlagsAndSeparateLines() {
        val figs = listOf(routeFigures(7, """"sku_category":"match""""))
        val t = StarTodayModel.forRoutes(figs, category, { listOf(memoOn("a", 7, 77L to 6L)) }, emptySet())[0]!!
        assertTrue(t.unknownSku); assertTrue(!t.incomplete) // the phone's own reason does not set the server's list-cut reason
        show(AppLanguage.EN, star(cat = "match"), StarToday(0, 1, 0, 0, incomplete = false, unknownSku = true))
        compose.onNodeWithTag(StarTags.REASON_SKU).assertExists(); compose.onNodeWithText("This phone sold a SKU it does not know; it may belong to this programme.").assertExists()
        assertEquals(0, compose.onAllNodesWithTag(StarTags.REASON_LIST).fetchSemanticsNodes().size)
    }

    @Test fun n3b_bothReasonsShowBothLines() {
        show(AppLanguage.EN, star(ids = listOf(1), idsCut = true), StarToday(0, 1, 0, 0, incomplete = true, unknownSku = true))
        compose.onNodeWithTag(StarTags.REASON_LIST).assertExists(); compose.onNodeWithTag(StarTags.REASON_SKU).assertExists()
    }

    @Test fun n4_aStarWithoutARouteIdShowsADashNotZero() {
        val noRoute = RouteFigures.parse(
            """{"star":{"programme_id":3,"name_en":"Star","month":"2026-11","through_date":"2026-11-10","route_name":"R","lmwd":26,"wd":26,"ewd":10,"target":"20.000","std":"2.833","lms":"12.250","status_en":"a","status_bn":"b","sku_category":"match"}}""",
        )!!
        val t = StarTodayModel.forRoutes(listOf(noRoute), category, { listOf(memoOn("a", null, 1L to 6L)) }, emptySet())[0]!!
        assertTrue(t.noRoute); assertEquals("—", StarTodayModel.qtyText(t, UiLocale.EN)); assertEquals("—", StarTodayModel.memoCountText(t, UiLocale.EN))
        show(AppLanguage.EN, star(cat = "match"), t)
        compose.onNodeWithText("Today on this phone: — STD, — (this download has no route for this Star, so this phone cannot tell which memos are on it)").assertExists()
    }

    // ---- round 3: an unknown SKU makes the quantity a dash or a visible lower bound; a cut list also makes the unconfirmed count possibly short
    @Test fun f1_aLiveTipOfOnlyUnresolvedSkusIsADashNotZeroAndAMixedDayIsALowerBound() {
        val figs = listOf(routeFigures(7, """"sku_category":"match","unit_divisor":12"""))
        val onlyUnknown = StarTodayModel.forRoutes(figs, category, { listOf(memoOn("u", 7, 77L to 6L)) }, emptySet())[0]!!
        assertTrue(onlyUnknown.unknownSku); assertTrue(onlyUnknown.unknownDash); assertTrue(!onlyUnknown.lowerBound)
        assertEquals("—", StarTodayModel.qtyText(onlyUnknown, UiLocale.EN)); assertEquals("—", StarTodayModel.memoCountText(onlyUnknown, UiLocale.EN))
        val mixed = StarTodayModel.forRoutes(figs, category, { listOf(memoOn("u", 7, 77L to 6L), memoOn("k", 7, 1L to 50L)) }, emptySet())[0]!!
        assertTrue(mixed.lowerBound); assertTrue(!mixed.unknownDash); assertEquals("4.17", StarTodayModel.qtyText(mixed, UiLocale.EN)) // a lower bound: the 77 sale may add more
        val clean = StarTodayModel.forRoutes(figs, category, { listOf(memoOn("k", 7, 1L to 50L)) }, emptySet())[0]!!
        assertTrue(!clean.unknownSku && !clean.lowerBound && !clean.unknownDash)
        val none = StarTodayModel.forRoutes(figs, category, { emptyList() }, emptySet())[0]!!
        assertEquals("0.00", StarTodayModel.qtyText(none, UiLocale.EN)) // nothing sold and nothing unknown: a real zero
    }

    @Test fun f1b_theScreenShowsADashOrAnAtLeastLineNeverAnExactZero() {
        show(AppLanguage.EN, star(cat = "match", divisor = 12), StarToday(0, 12, 0, 0, incomplete = false, unknownSku = true))
        compose.onNodeWithText("Today on this phone: — STD, — (may be incomplete; not in the STD above)").assertExists()
    }

    @Test fun f1c_aLowerBoundIsLabelledAtLeast() {
        show(AppLanguage.EN, star(cat = "match", divisor = 12), StarToday(50, 12, 2, 0, incomplete = false, unknownSku = true))
        compose.onNodeWithText("Today on this phone: at least 4.17 STD, at least 2 memos (may be incomplete; not in the STD above)").assertExists()
    }

    @Test fun f2_aCutSkuListSaysTheUnconfirmedCountMayAlsoBeShortEvenAtZero() {
        show(AppLanguage.EN, star(ids = listOf(1), idsCut = true), StarToday(0, 1, 0, 0, incomplete = true))
        compose.onNodeWithTag(StarTags.UNCONFIRMED_SHORT).assertExists()
        compose.onNodeWithText("The count of earlier-day memos not confirmed yet may also be short: the programme lists more SKUs than this phone received.").assertExists()
        assertEquals(0, compose.onAllNodesWithTag(StarTags.UNCONFIRMED).fetchSemanticsNodes().size) // no count line at 0, only the warning
    }

    @Test fun f2b_noShortNoteWhenTheListWasNotCut() {
        show(AppLanguage.EN, star(cat = "match"), StarToday(12, 12, 1, 2, incomplete = false))
        assertEquals(0, compose.onAllNodesWithTag(StarTags.UNCONFIRMED_SHORT).fetchSemanticsNodes().size)
    }

    @Test fun f4_theFailedLineShowsDashesInBanglaToo() {
        show(AppLanguage.BN, star(cat = "match"), StarToday(0, 1, 0, 0, false, failed = true))
        compose.onNodeWithText("আজ এই ফোনে: — STD, — (এই ফোন এখন মেমো পড়তে পারেনি)").assertExists()
    }

    @Test fun lowAMalformedDivisorIsAPlainDashNeverAtLeastDash() {
        show(AppLanguage.EN, star(cat = "match"), StarToday(50, 0, 2, 0, incomplete = false, unknownSku = true))
        compose.onNodeWithText("Today on this phone: — STD, at least 2 memos (may be incomplete; not in the STD above)").assertExists()
    }
}
