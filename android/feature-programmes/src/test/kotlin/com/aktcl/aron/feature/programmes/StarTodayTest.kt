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
        compose.onNodeWithText("Today on this phone: 0.00 STD, 0 memos (may be incomplete: the programme lists more SKUs than this phone received; not in the STD above)").assertExists()
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
        assertEquals(6L, withdrawn.qtyBase); assertTrue(!withdrawn.incomplete)
        val unknown = StarTodayModel.forRoutes(figs, category, { listOf(memoOn("a", 7, 77L to 6L)) }, emptySet())[0]!! // 77 is not in the table at all
        assertEquals(0L, unknown.qtyBase); assertTrue(unknown.incomplete) // it may belong to the programme: the line says so
        val ids = StarTodayModel.forRoutes(listOf(routeFigures(7, """"sku_ids":[1]""")), category, { listOf(memoOn("a", 7, 77L to 6L)) }, emptySet())[0]!!
        assertTrue(!ids.incomplete) // an explicit id list needs no category: a SKU outside it is simply not the programme's
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
}
