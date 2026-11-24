package com.aktcl.aron.feature.programmes

import com.aktcl.aron.rules.UiLocale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SkuTargetModelTest {
    private val skus = mapOf(
        900L to SkuInfo(900, "Maxim", "ম্যাক্সিম", "cigarette", "stick", 20, "pack"),
        901L to SkuInfo(901, "Safe Match", null, "match", "dozen", 1, "dozen"),
        902L to SkuInfo(902, "Lighter", null, "lighter", "piece", 25, "pack"),
        903L to SkuInfo(903, "Loose", null, "cigarette", "stick", 1, "stick"),
    )
    private fun route(targets: String, achieved: String = "", calendar: Boolean = true, through: String = "2026-11-17") = RouteFigures.parse(
        """{"route_id":7,"targets":[$targets],"achievement_mtd":[$achieved]${if (calendar) ""","calendar":{"month":"2026-11","working_days":26,"elapsed_working_days_before_today":14,"remaining_working_days_after_today":11,"today_is_working":true,"last_month_working_days":26,"through_date":"$through","as_of":"2026-11-18T01:00:00Z"}""" else ""}}""",
    )!!
    private fun t(id: Long, q: String, unit: String = "stick") = """{"scope_type":"route","scope_id":7,"month":"2026-11","product_level":"sku","product_id":$id,"std_target":"$q","std_unit":"$unit","memo_target":0}"""
    private fun a(id: Long, q: Long) = """{"scope_type":"route","scope_id":7,"month":"2026-11","product_level":"sku","product_id":$id,"achieved_qty_base":$q,"memo_count":1,"as_of":"2026-11-18T01:00:00Z","through_date":"2026-11-17"}"""

    @Test fun theK13FixtureGivesTadsAndRadsFromTheServersDayCounts() {
        val d = SkuTargetModel.build(listOf(route(t(903, "500.000") + "," + t(904, "900.000"))), skus + (904L to SkuInfo(904, "Avon", null, "cigarette", "stick", 1, "stick")))!!
        val r = d.rows.first { it.skuId == 903L }
        assertEquals(36_000L, r.tadsMilli); assertEquals(45_000L, r.radsMilli); assertEquals(0L, r.adsMilli); assertEquals(0L, r.padsMilli)
        val avon = d.rows.first { it.skuId == 904L }
        assertEquals(64_000L, avon.tadsMilli); assertEquals(82_000L, avon.radsMilli) // 900: TADS 64, RADS 82
        assertEquals(0L, r.achievementBp) // 0 achieved of 500: "0%", not a dash
    }

    @Test fun quantitiesAreShownInThePacketBoxOrDozenTheFieldCountsIn() {
        val d = SkuTargetModel.build(listOf(route(t(900, "500.000") + "," + t(901, "30.000", "dozen") + "," + t(902, "100.000", "piece"), a(900, 120) + "," + a(902, 50))), skus)!!
        val maxim = d.rows.first { it.skuId == 900L }; val match = d.rows.first { it.skuId == 901L }; val lighter = d.rows.first { it.skuId == 902L }
        assertEquals("packet", maxim.unitKey); assertEquals("dozen", match.unitKey); assertEquals("box", lighter.unitKey)
        assertEquals("25", SkuTargetModel.qtyText(maxim.targetMilli, maxim.factor, UiLocale.EN)) // 500 sticks of 20s
        assertEquals("6", SkuTargetModel.qtyText(maxim.achievedMilli, maxim.factor, UiLocale.EN)) // 120 sticks
        assertEquals("19", SkuTargetModel.qtyText(maxim.remainingMilli, maxim.factor, UiLocale.EN))
        assertEquals("30", SkuTargetModel.qtyText(match.targetMilli, match.factor, UiLocale.EN))
        assertEquals("4", SkuTargetModel.qtyText(lighter.targetMilli, lighter.factor, UiLocale.EN)) // 100 pieces: boxes of 25
        assertEquals("২৫", SkuTargetModel.qtyText(maxim.targetMilli, maxim.factor, UiLocale.BN))
        assertEquals("3.5", SkuTargetModel.qtyText(70_000, 20, UiLocale.EN)) // a part packet keeps one decimal
    }

    @Test fun withNoCalendarTheDailyFiguresAreNullAndAZeroTargetHasNoPercent() {
        val d = SkuTargetModel.build(listOf(route(t(903, "500.000") + "," + t(900, "0.000"), calendar = false)), skus)!!
        assertEquals(false, d.hasCalendar)
        d.rows.forEach { assertNull(it.adsMilli); assertNull(it.tadsMilli); assertNull(it.radsMilli) }
        assertNull(d.rows.first { it.skuId == 900L }.achievementBp)
        assertEquals("—", SkuTargetModel.percentText(null, UiLocale.EN)); assertEquals("0%", SkuTargetModel.percentText(0L, UiLocale.EN)); assertEquals("104%", SkuTargetModel.percentText(10_449L, UiLocale.EN))
    }

    @Test fun routesAreSummedASkuTheTableLacksIsSkippedAndNothingGivesNull() {
        val d = SkuTargetModel.build(listOf(route(t(903, "100.000") + "," + t(999, "5.000"), a(903, 10)), route(t(903, "50.000"), a(903, 5))), skus)!!
        assertEquals(listOf(903L), d.rows.map { it.skuId }); assertEquals(150_000L, d.rows.single().targetMilli); assertEquals(15_000L, d.rows.single().achievedMilli)
        assertNull(SkuTargetModel.build(listOf(RouteFigures.EMPTY), skus)); assertNull(SkuTargetModel.build(emptyList(), skus))
    }

    @Test fun theFirstOfTheMonthIsLabelledFromTheFirstAndHomeStdSumsOnlyCigarettes() {
        val first = SkuTargetModel.build(listOf(route(t(903, "500.000"), through = "2026-10-31")), skus)!!
        assertEquals(true, first.fromFirst)
        val d = SkuTargetModel.build(listOf(route(t(903, "100.000") + "," + t(900, "200.000") + "," + t(901, "30.000", "dozen"), a(903, 10) + "," + a(900, 50) + "," + a(901, 7))), skus)!!
        assertEquals(60_000L to 300_000L, SkuTargetModel.homeStd(d)) // cigarettes only: 10 + 50 of 100 + 200 (the dozens of matches are not sticks)
        assertNull(SkuTargetModel.homeStd(SkuTargetModel.build(listOf(route(t(901, "30.000", "dozen"))), skus)))
        assertNull(SkuTargetModel.homeStd(null))
    }
}
