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
    private fun cal(elapsed: Int, remaining: Int, through: String = "2026-11-17") = """"calendar":{"month":"2026-11","working_days":26,"elapsed_working_days_before_today":$elapsed,"remaining_working_days_after_today":$remaining,"today_is_working":true,"last_month_working_days":26,"through_date":"$through","as_of":"2026-11-18T01:00:00Z"}"""
    private fun route(targets: String, achieved: String = "", calendar: String? = cal(14, 11), through: String = "2026-11-17") = RouteFigures.parse(
        """{"route_id":7,"targets":[$targets],"achievement_mtd":[$achieved]${calendar?.let { ",$it".replace("2026-11-17", through) } ?: ""}}""",
    )!!
    private fun t(id: Long, q: String, unit: String = "stick") = """{"scope_type":"route","scope_id":7,"month":"2026-11","product_level":"sku","product_id":$id,"std_target":"$q","std_unit":"$unit","memo_target":0}"""
    private fun a(id: Long, q: Long) = """{"scope_type":"route","scope_id":7,"month":"2026-11","product_level":"sku","product_id":$id,"achieved_qty_base":$q,"memo_count":1,"as_of":"2026-11-18T01:00:00Z","through_date":"2026-11-17"}"""

    @Test fun theK13FixtureUsesTheServersDayCountsAndRoundsOnceAtDisplay() {
        val d = SkuTargetModel.build(listOf(route(t(903, "500.000") + "," + t(904, "900.000"))), skus + (904L to SkuInfo(904, "Avon", null, "cigarette", "stick", 1, "stick")))!!
        val r = d.rows.first { it.skuId == 903L }
        assertEquals(14, r.elapsedDays); assertEquals(11, r.remainingDays)
        // 500 sticks over 14 working days = 35.71 and over 11 = 45.45: one rounding, in the unit shown (a stick), to one decimal
        assertEquals("35.7", SkuTargetModel.perDayText(r.targetMilli, r.elapsedDays, r.factor, UiLocale.EN))
        assertEquals("45.5", SkuTargetModel.perDayText(r.remainingMilli, r.remainingDays, r.factor, UiLocale.EN))
        assertEquals("0", SkuTargetModel.perDayText(r.achievedMilli, r.elapsedDays, r.factor, UiLocale.EN))
        val avon = d.rows.first { it.skuId == 904L }
        assertEquals("64.3", SkuTargetModel.perDayText(avon.targetMilli, avon.elapsedDays, avon.factor, UiLocale.EN)); assertEquals("81.8", SkuTargetModel.perDayText(avon.remainingMilli, avon.remainingDays, avon.factor, UiLocale.EN))
        assertEquals(0L, r.achievementPct) // 0 achieved of 500: "0%", not a dash
    }

    // M1 (lead, slice 1 recheck): the percentage is the whole percent of the RAW figures, never a rounded basis point rounded again
    @Test fun m1_achievementPercentIsRoundedOnceFromTheRawFigures() {
        val d = SkuTargetModel.build(listOf(route(t(903, "20000.000"), a(903, 19_899))), skus)!!
        assertEquals(99L, d.rows.single().achievementPct) // 99.495 % -> 99; the old double rounding gave 9950 bp -> 100 %
        assertEquals("99%", SkuTargetModel.percentText(d.rows.single().achievementPct, UiLocale.EN))
        val tiny = SkuTargetModel.build(listOf(route(t(903, "1000000.000"), a(903, 4_995))), skus)!!
        assertEquals(0L, tiny.rows.single().achievementPct) // 0.4995 % -> 0, not 1
        assertEquals("0%", SkuTargetModel.percentText(tiny.rows.single().achievementPct, UiLocale.EN))
    }

    // M2 (lead, slice 1 recheck): daily figures are one exact quotient rounded once in the unit shown; a decimal3 target keeps its precision
    @Test fun m2_dailyFiguresAreRoundedOnceInThePacketNotFirstInSticks() {
        val d = SkuTargetModel.build(listOf(route(t(900, "520.000"), calendar = cal(15, 10))), skus)!!
        val r = d.rows.single()
        assertEquals("26", SkuTargetModel.qtyText(r.targetMilli, r.factor, UiLocale.EN)) // 520 sticks = 26 packets of 20s
        assertEquals("1.7", SkuTargetModel.perDayText(r.targetMilli, r.elapsedDays, r.factor, UiLocale.EN)) // 26 / 15 = 1.733 packets: the old two-step rounding showed 1.8
    }

    @Test fun m2_aDecimalTargetKeepsItsPrecisionInThePercentage() {
        val d = SkuTargetModel.build(listOf(route(t(901, "30.500", "dozen"), a(901, 30))), skus)!!
        assertEquals(98L, d.rows.single().achievementPct) // 30 / 30.5 = 98.36 %; rounding the target to 31 first gave 97 %
        assertEquals("30.5", SkuTargetModel.qtyText(d.rows.single().targetMilli, d.rows.single().factor, UiLocale.EN))
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
        val d = SkuTargetModel.build(listOf(route(t(903, "500.000") + "," + t(900, "0.000"), calendar = null)), skus)!!
        assertEquals(false, d.hasCalendar)
        d.rows.forEach { assertNull(it.elapsedDays); assertNull(it.remainingDays); assertEquals("—", SkuTargetModel.perDayText(it.targetMilli, it.elapsedDays, it.factor, UiLocale.EN)) }
        assertNull(d.rows.first { it.skuId == 900L }.achievementPct)
        assertEquals("—", SkuTargetModel.percentText(null, UiLocale.EN)); assertEquals("0%", SkuTargetModel.percentText(0L, UiLocale.EN)); assertEquals("104%", SkuTargetModel.percentText(104L, UiLocale.EN))
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

    @Test fun aTruncatedTargetListGivesNoHomeStdNotAPartialPercentage() {
        val d = SkuTargetModel.build(listOf(RouteFigures.parse("""{"route_id":7,"targets_truncated":true,"targets":[${t(903, "100.000")}],"achievement_mtd":[${a(903, 10)}]}""")!!), skus)!!
        assertEquals(true, d.truncated); assertNull(SkuTargetModel.homeStd(d))
    }

    @Test fun aNegativeAchievedQuantityIsADashNotACrashAndANegativeTargetGivesNoRow() {
        val d = SkuTargetModel.build(listOf(route(t(903, "100.000") + "," + t(900, "-5.000"), a(903, -3))), skus)!!
        val r = d.rows.single() // the negative target of SKU 900 gives no row
        assertEquals(903L, r.skuId); assertNull(r.achievedMilli); assertNull(r.remainingMilli); assertNull(r.achievementPct)
        assertEquals("—", SkuTargetModel.qtyText(r.achievedMilli, r.factor, UiLocale.EN))
        assertEquals(null to 100_000L, SkuTargetModel.homeStd(d)) // an invalid achieved figure: the achieved total is a dash, the valid target stays
    }

    @Test fun aDayCountOfZeroIsADashNotZero() {
        val d = SkuTargetModel.build(listOf(route(t(903, "500.000"), a(903, 10), calendar = cal(0, 25))), skus)!!
        val r = d.rows.single()
        assertEquals(0, r.elapsedDays)
        assertEquals("—", SkuTargetModel.perDayText(r.achievedMilli, r.elapsedDays, r.factor, UiLocale.EN)) // ADS, TADS, PADS: no elapsed day
        assertEquals("—", SkuTargetModel.perDayText(r.targetMilli, r.elapsedDays, r.factor, UiLocale.EN))
        assertEquals("19.6", SkuTargetModel.perDayText(r.remainingMilli, r.remainingDays, r.factor, UiLocale.EN)) // RADS 490 / 25
    }
}
