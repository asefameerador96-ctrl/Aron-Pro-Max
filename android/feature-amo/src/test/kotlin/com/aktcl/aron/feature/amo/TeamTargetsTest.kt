package com.aktcl.aron.feature.amo

import com.aktcl.aron.rules.Band
import com.aktcl.aron.rules.UiLocale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TeamTargetsTest {
    private val json = """{"month":"2026-11","as_of":"2026-11-06T10:00:00Z","node":{"type":"zone","id":5,"name":"Dariapur"},"tilldate_basis":"calendar_days","period_days":30,"elapsed_days":6,
      "rows":[
      {"product_level":"brand","product_id":1,"name":"Avon","category_code":"cigarette","target_qty_base":160000,"achieved_qty_base":323980,"tilldate_target_qty_base":32000},
      {"product_level":"brand","product_id":2,"name":"ARIS","category_code":"cigarette","target_qty_base":16800,"achieved_qty_base":9520,"tilldate_target_qty_base":3360},
      {"product_level":"brand","product_id":3,"name":"Abul","category_code":"bidi","target_qty_base":6800,"achieved_qty_base":7250,"tilldate_target_qty_base":null},
      {"product_level":"brand","product_id":4,"name":"Aster","category_code":"lighter","target_qty_base":0,"achieved_qty_base":5,"tilldate_target_qty_base":0}]}"""
    private val node = TeamTargets.parse(json)!!

    @Test fun categoryIsTheSumOfItsBrands() {
        val c = TeamTargets.categoryCards(node.rows, node.periodDays)
        assertEquals(listOf("cigarette", "bidi", "lighter"), c.map { it.categoryCode })
        assertEquals(176800L, c[0].target); assertEquals(333500L, c[0].achieved); assertEquals(35360L, c[0].tillDateTarget)
        assertNull(c[1].tillDateTarget) // one brand has no till-date figure, so the category has none
    }

    @Test fun percentAndBandsOnThePrintedPercent() {
        fun r(a: Long, t: Long) = TeamTargetRow("x", "cigarette", t, a, t)
        assertEquals(Band.RED, TeamTargets.band(r(39, 100), PerfTab.MONTHLY)); assertEquals(Band.AMBER, TeamTargets.band(r(395, 1000), PerfTab.MONTHLY)) // 39.5 prints 40
        assertEquals(Band.AMBER, TeamTargets.band(r(40, 100), PerfTab.MONTHLY)); assertEquals(Band.AMBER, TeamTargets.band(r(79, 100), PerfTab.MONTHLY))
        assertEquals(Band.GREEN, TeamTargets.band(r(80, 100), PerfTab.MONTHLY)); assertEquals(Band.GREEN, TeamTargets.band(r(796, 1000), PerfTab.MONTHLY)) // 79.6 prints 80
        assertEquals(100L, TeamTargets.cardPct(r(150, 100), PerfTab.MONTHLY)); assertNull(TeamTargets.cardPct(r(5, 0), PerfTab.MONTHLY)); assertNull(TeamTargets.band(r(5, 0), PerfTab.MONTHLY))
    }

    @Test fun detailsPercentTwoDecimalsUncappedRemainingFloored() {
        val avon = node.rows[0]; val aris = node.rows[1]
        assertEquals("202.49%", TeamTargets.detailsText(avon, PerfTab.MONTHLY, UiLocale.EN)); assertEquals(0L, TeamTargets.remaining(avon, PerfTab.MONTHLY))
        assertEquals("56.67%", TeamTargets.detailsText(aris, PerfTab.MONTHLY, UiLocale.EN)); assertEquals(7280L, TeamTargets.remaining(aris, PerfTab.MONTHLY))
        assertEquals("২০২.৪৯%", TeamTargets.detailsText(avon, PerfTab.MONTHLY, UiLocale.BN))
        assertEquals(3360L, TeamTargets.target(aris, PerfTab.TILL_DATE))
    }

    @Test fun fractionIsAchievementOverTargetWithGroupingAndBanglaDigits() {
        val c = TeamTargets.categoryCards(node.rows, node.periodDays)[0]
        assertEquals("333,500/176,800", TeamTargets.fraction(c, PerfTab.MONTHLY, UiLocale.EN)); assertEquals("৩৩৩,৫০০/১৭৬,৮০০", TeamTargets.fraction(c, PerfTab.MONTHLY, UiLocale.BN))
    }

    @Test fun categoryFilterSelectsAndClearsOnSecondTap() {
        assertEquals("bidi", TeamTargets.toggle(null, "bidi")); assertNull(TeamTargets.toggle("bidi", "bidi")); assertEquals("match", TeamTargets.toggle("bidi", "match"))
        assertEquals(listOf("Abul"), TeamTargets.items(node.rows, "bidi").map { it.name }); assertEquals(4, TeamTargets.items(node.rows, null).size)
    }

    @Test fun periodZeroDropsTillDateAndGarbageIsNull() {
        val n = TeamTargets.parse(json.replace("\"period_days\":30", "\"period_days\":0"))!!
        assertNull(n.rows[0].tillDateTarget); assertNull(TeamTargets.parse("nope")); assertNull(TeamTargets.parse(null))
    }
}
