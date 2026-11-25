package com.aktcl.aron.sr

import com.aktcl.aron.feature.programmes.LocalMemo
import com.aktcl.aron.feature.programmes.LocalMemoLine
import com.aktcl.aron.feature.programmes.RouteFigures
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Lifting Star read of the host with a fake phone (StarSource): no rule means no read at all, a failed read means a dash line, and a good read gives the model's line. */
class StarReadTest {
    private fun figures(routeId: Long, extra: String, month: String = "2026-11") = RouteFigures.parse(
        """{"route_id":$routeId,"star":{"programme_id":3,"name_en":"Star","month":"$month","through_date":"2026-11-10","route_name":"R$routeId","lmwd":26,"wd":26,"ewd":10,"target":"20.000","std":"2.833","lms":"12.250","status_en":"a","status_bn":"b"${if (extra.isEmpty()) "" else ",$extra"}}}""",
    )!!

    private class Fake(val figs: List<RouteFigures>, val failWith: Exception? = null, val memos: List<LocalMemo> = emptyList()) : StarSource {
        var categoryReads = 0; var ackedReads = 0; var memoReads = 0; val monthsRead = mutableListOf<String>()
        override suspend fun figures() = figs
        override suspend fun skuCategories(): Map<Long, String> { categoryReads++; failWith?.let { throw it }; return mapOf(1L to "match") }
        override suspend fun ackedBeforeRequest(): Set<String>? { ackedReads++; return emptySet() }
        override suspend fun memosOfMonth(month: String): List<LocalMemo> { memoReads++; monthsRead += month; return memos }
    }

    @Test fun anOlderServerNamesNoSkusSoNothingIsReadAndThereIsNoLine() = runTest {
        val fake = Fake(listOf(figures(7, "")))
        val load = readStars(fake)
        assertEquals(1, load.stars.size); assertNull(load.today.single())
        assertEquals(0, fake.categoryReads); assertEquals(0, fake.ackedReads); assertEquals(0, fake.memoReads)
    }

    @Test fun aFailedReadGivesADashLineForARowWithARuleAndNoLineForARowWithout() = runTest {
        val fake = Fake(listOf(figures(7, """"sku_category":"match""""), figures(8, "")), failWith = IllegalStateException("db"))
        val load = readStars(fake)
        assertTrue(load.today[0]!!.failed); assertNull(load.today[1])
        val t = load.today[0]!!
        assertEquals("—", com.aktcl.aron.feature.programmes.StarTodayModel.qtyText(t, com.aktcl.aron.rules.UiLocale.EN)) // the dash the screen prints
        assertEquals("—", com.aktcl.aron.feature.programmes.StarTodayModel.memoCountText(t, com.aktcl.aron.rules.UiLocale.EN))
    }

    @Test fun aGoodReadGivesTheModelsLineForThisRoutesMemosOnly() = runTest {
        val memos = listOf(
            LocalMemo("a", 5, "2026-11-12", null, "pending", null, listOf(LocalMemoLine(1, "sale", 24, 1, 0)), routeId = 7),
            LocalMemo("b", 5, "2026-11-12", null, "pending", null, listOf(LocalMemoLine(1, "sale", 99, 1, 0)), routeId = null), // no route: on no Star
        )
        val fake = Fake(listOf(figures(7, """"sku_category":"match","unit_divisor":12""")), memos = memos)
        val t = readStars(fake).today.single()!!
        assertEquals(24L, t.qtyBase); assertEquals(12L, t.divisor); assertEquals(1L, t.memoCount)
        assertEquals(1, fake.categoryReads); assertEquals(1, fake.ackedReads); assertEquals(1, fake.memoReads) // one month, one memo read
    }

    @Test fun memoReadsAreOnePerDistinctMonthAndTheCategoriesAndAckedSetAreReadOnce() = runTest {
        val rule = "\"sku_category\":\"match\""
        val fake = Fake(listOf(figures(7, rule), figures(8, rule), figures(9, rule, month = "2026-10")))
        readStars(fake)
        assertEquals(1, fake.categoryReads); assertEquals(1, fake.ackedReads)
        assertEquals(setOf("2026-11", "2026-10"), fake.monthsRead.toSet()); assertEquals(2, fake.memoReads) // distinct months, not rows
    }
}
