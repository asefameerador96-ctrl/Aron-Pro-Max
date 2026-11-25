package com.aktcl.aron.feature.programmes

import com.aktcl.aron.rules.UiLocale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KpiModelTest {
    private fun route(kpi: String, calendar: Boolean = true) = RouteFigures.parse(
        """{"route_id":7,"kpi":$kpi${if (calendar) ""","calendar":{"month":"2026-11","working_days":30,"elapsed_working_days_before_today":10,"remaining_working_days_after_today":20,"today_is_working":true,"last_month_working_days":26,"through_date":"2026-11-17","as_of":"x"}""" else ""}}""",
    )!!
    private fun brand(id: Int, vt: Long, va: Long, mt: Long, ma: Long, packs: String = """[{"pack_factor":20,"target":$vt,"achieved":$va}]""", today: String = "null") =
        """{"brand_id":$id,"volume_target":$vt,"volume_achieved":$va,"volume_bp":${if (vt > 0) va * 10000 / vt else "null"},"memo_target":$mt,"memo_achieved":$ma,"memo_bp":${if (mt > 0) ma * 10000 / mt else "null"},"kpi_alloc_mtk":100000,"kpi_today_mtk":$today,"by_pack":$packs}"""
    private fun kpi(brands: List<String>, vol: String = """{"target":2000,"achieved":600,"bp":3000}""", cats: String = "[]", achieved: String = "null", bp: String = "null", cpr: String = "null") =
        """{"month":"2026-11","through_date":"2026-11-17","as_of":"x","allocated_mtk":5700000,"achieved_mtk":$achieved,"achievement_bp":$bp,"volume":$vol,"memo":{"target":100,"achieved":30,"bp":3000},"brands":[${brands.joinToString(",")}],"brands_truncated":false,"categories":$cats,"cpr":$cpr}"""
    private val names = mapOf(1L to BrandName("ARIS", "অ্যারিস"), 2L to BrandName("Avon", null), 3L to BrandName("Safe Match", null), 4L to BrandName("Lighter Co", null))
    private val cats1 = mapOf(1L to "cigarette", 2L to "cigarette")

    @Test fun theMonthCardTakesDaysFromTheServersCalendarAndKeepsUndefinedMoneyNull() {
        val d = KpiModel.build(listOf(route(kpi(listOf(brand(1, 1000, 300, 50, 20))))), names, cats1)!!
        assertEquals(10, d.daysElapsed); assertEquals(30, d.workingDays)
        assertEquals(5_700_000L, d.allocatedMtk); assertNull(d.achievedMtk); assertNull(d.achievementBp)
        assertEquals("৳5,700", KpiModel.takaText(d.allocatedMtk, UiLocale.EN)); assertEquals("৳৫,৭০০", KpiModel.takaText(d.allocatedMtk, UiLocale.BN)); assertEquals("—", KpiModel.takaText(null, UiLocale.EN))
        assertEquals("—", KpiModel.bpText(null, UiLocale.EN)); assertEquals("29.63%", KpiModel.bpText(2_963, UiLocale.EN)); assertEquals("২৯.৬৩%", KpiModel.bpText(2_963, UiLocale.BN))
    }

    @Test fun volumeIsShownInPacketsFromThePerPackFiguresNeverFromAStickSumOfMixedPacks() {
        val b1 = brand(1, 1000, 300, 50, 20, packs = """[{"pack_factor":20,"target":800,"achieved":200},{"pack_factor":10,"target":200,"achieved":100}]""")
        val d = KpiModel.build(listOf(route(kpi(listOf(b1), vol = """{"target":1000,"achieved":300,"bp":3000}"""))), names, cats1)!!
        assertEquals("packet", d.volume.target.unitKey)
        assertEquals("60", KpiModel.qtyText(d.volume.target, UiLocale.EN)) // 800 sticks of 20s = 40 packets + 200 sticks of 10s = 20 packets
        assertEquals("20", KpiModel.qtyText(d.volume.achieved, UiLocale.EN))
        assertEquals("60", KpiModel.qtyText(d.brands.single().volumeTarget!!, UiLocale.EN))
    }

    @Test fun whenThePacksDoNotAddUpTheBaseUnitFigureIsShownAsSticksNotGuessed() {
        val d = KpiModel.build(listOf(route(kpi(listOf(brand(1, 1000, 300, 50, 20, packs = "[]")), vol = """{"target":1000,"achieved":300,"bp":3000}"""))), names, cats1)!!
        assertEquals("stick", d.volume.target.unitKey); assertEquals("1,000", KpiModel.qtyText(d.volume.target, UiLocale.EN))
    }

    @Test fun blmCategoriesAreInDozensAndBoxesAndTheNullPercentStaysADash() {
        val cats = """[{"category_code":"match","volume_target":30,"volume_achieved":12,"volume_bp":4000,"by_pack":[{"pack_factor":1,"target":30,"achieved":12}]},
                       {"category_code":"lighter","volume_target":100,"volume_achieved":0,"volume_bp":null,"by_pack":[{"pack_factor":25,"target":100,"achieved":0}]}]"""
        val d = KpiModel.build(listOf(route(kpi(emptyList(), cats = cats))), names)!!
        val match = d.categories.first { it.categoryCode == "match" }; val lighter = d.categories.first { it.categoryCode == "lighter" }
        assertEquals("dozen", match.pair.target.unitKey); assertEquals("30", KpiModel.qtyText(match.pair.target, UiLocale.EN))
        assertEquals("box", lighter.pair.target.unitKey); assertEquals("4", KpiModel.qtyText(lighter.pair.target, UiLocale.EN)) // 100 pieces = 4 boxes of 25
        assertNull(lighter.pair.bp)
        assertEquals("match", d.categories.first().categoryCode) // match first, as the manual lists them
    }

    @Test fun brandRowsCarryTheNamesAndANullKpiTodayIsADash() {
        val d = KpiModel.build(listOf(route(kpi(listOf(brand(1, 1000, 300, 50, 20, today = "420000"), brand(2, 0, 0, 0, 0))))), names, cats1)!!
        assertEquals("অ্যারিস", d.brands[0].name!!.text(UiLocale.BN)); assertEquals("Avon", d.brands[1].name!!.text(UiLocale.BN))
        assertEquals(420_000L, d.brands[0].kpiTodayMtk); assertNull(d.brands[1].volumeBp); assertNull(d.brands[1].memoBp) // a zero target has no percentage
    }

    @Test fun severalRoutesAreSummedAndTheirPercentagesRecomputedAndCprIsShownForOneRouteOnly() {
        val a = route(kpi(listOf(brand(1, 1000, 300, 50, 20)), cpr = """{"target_bp":5000,"alloc_mtk":600000,"achieved_bp":null}"""))
        val b = route(kpi(listOf(brand(1, 1000, 100, 50, 10))))
        val one = KpiModel.build(listOf(a), names)!!; assertEquals(5000L, one.cprTargetBp); assertNull(one.cprAchievedBp)
        val two = KpiModel.build(listOf(a, b), names)!!
        assertEquals(2000L, two.brands.single().volumeBp) // (300+100)/(1000+1000) = 20.00 %
        assertEquals(3000L, two.brands.single().memoBp); assertNull(two.cprTargetBp)
        assertEquals(11_400_000L, two.allocatedMtk)
    }

    @Test fun noKpiSectionIsNullAndTheFirstOfTheMonthIsLabelled() {
        assertNull(KpiModel.build(listOf(RouteFigures.EMPTY), names)); assertNull(KpiModel.build(emptyList(), names))
        assertTrue(KpiModel.build(listOf(route(kpi(emptyList()).replace("2026-11-17", "2026-10-31"))), names)!!.fromFirst)
    }

    @Test fun severalRoutesWithAMissingKpiOrACutBrandListShowDashesAndSayIncomplete() {
        val a = route(kpi(listOf(brand(1, 1000, 300, 50, 20))))
        val noKpi = RouteFigures.parse("""{"route_id":8}""")!!
        val missing = KpiModel.build(listOf(a, noKpi), names)!!
        assertTrue(missing.incomplete); assertNull(missing.volume.bp); assertNull(missing.memo.bp); assertNull(missing.brands.single().volumeBp); assertNull(missing.achievementBp)
        val cut = KpiModel.build(listOf(a, route(kpi(listOf(brand(1, 1000, 100, 50, 10))).replace("\"brands_truncated\":false", "\"brands_truncated\":true"))), names)!!
        assertTrue(cut.incomplete); assertNull(cut.brands.single().memoBp)
        val ok = KpiModel.build(listOf(a, route(kpi(listOf(brand(1, 1000, 100, 50, 10))))), names)!!
        assertEquals(false, ok.incomplete); assertEquals(3000L, ok.brands.single().memoBp) // Percent.bp of the raw sums, once
        assertEquals(false, KpiModel.build(listOf(a), names)!!.incomplete) // one route: the server's figures as sent
    }

    // K1/K2 (lead, recheck of 11bb7ac89): a brand's unit comes from the product tree's category; mixed or unknown brands are never summed
    private val cigA = brand(1, 1000, 300, 50, 20)                                                               // 20s cigarettes: 50 packets, 15 sold
    private val matchM = brand(3, 30, 12, 10, 4, packs = """[{"pack_factor":1,"target":30,"achieved":12}]""")      // matches: 30 dozen
    private val lighterL = brand(4, 100, 0, 5, 0, packs = """[{"pack_factor":25,"target":100,"achieved":0}]""")  // lighters: 100 pieces = 4 boxes
    private val cats2 = mapOf(1L to "cigarette", 3L to "match", 4L to "lighter")

    @Test fun k2_theCigaretteVolumeTotalIsCheckedAgainstTheCigaretteBrandsOnlySoMatchBrandsDoNotForceSticks() {
        val d = KpiModel.build(listOf(route(kpi(listOf(cigA, matchM), vol = """{"target":1000,"achieved":300,"bp":3000}"""))), names, cats2)!!
        assertEquals("packet", d.volume.target.unitKey); assertEquals("50", KpiModel.qtyText(d.volume.target, UiLocale.EN)); assertEquals("15", KpiModel.qtyText(d.volume.achieved, UiLocale.EN))
    }

    @Test fun k1_aLighterOnlyBrandIsNotInTheCigaretteListAndPrintsBoxesAMatchBrandPrintsDozens() {
        val d = KpiModel.build(listOf(route(kpi(listOf(cigA, matchM, lighterL), vol = """{"target":1000,"achieved":300,"bp":3000}"""))), names, cats2)!!
        assertEquals(listOf(1L), d.volumeBrands.map { it.brandId }) // only the cigarette brand is in the cigarette list
        val l = d.brands.first { it.brandId == 4L }.volumeTarget!!; assertEquals("box", l.unitKey); assertEquals("4", KpiModel.qtyText(l, UiLocale.EN))
        val m = d.brands.first { it.brandId == 3L }.volumeTarget!!; assertEquals("dozen", m.unitKey); assertEquals("30", KpiModel.qtyText(m, UiLocale.EN))
        val c = d.brands.first { it.brandId == 1L }.volumeTarget!!; assertEquals("packet", c.unitKey); assertEquals("50", KpiModel.qtyText(c, UiLocale.EN))
        assertEquals(3, d.brands.size) // the memo list still has every brand
    }

    @Test fun k1_aBrandThatMixesCategoriesIsNotSummedItsVolumeIsADashWithANote() {
        // brand 1 holds a 20s cigarette SKU (2,000 sticks) and a match SKU (30 dozen): the tree gives no single category, so it is absent from the map
        val mixed = brand(1, 2030, 300, 50, 20, packs = """[{"pack_factor":20,"target":2000,"achieved":300},{"pack_factor":1,"target":30,"achieved":0}]""")
        val d = KpiModel.build(listOf(route(kpi(listOf(mixed), vol = """{"target":2000,"achieved":300,"bp":1500}"""))), names, emptyMap())!!
        val row = d.brands.single()
        assertNull(row.category); assertNull(row.volumeTarget) // never "Target 130"
        assertEquals(listOf(1L), d.volumeBrands.map { it.brandId }) // it stays in the cigarette list, with the dash and the note
        assertEquals("stick", d.volume.target.unitKey) // and the cigarette total is not guessed from its packs: base units with their word
    }

    @Test fun anUnresolvedBrandIsADashWithANoteNotAGuess() {
        val d = KpiModel.build(listOf(route(kpi(listOf(cigA)))), names, emptyMap())!!
        assertNull(d.brands.single().volumeTarget)
    }

    @Test fun aRouteWithBlmBrandsShowsPacketsForTheCigaretteTotalAndTheCategoriesInTheirOwnUnits() {
        val cats = """[{"category_code":"match","volume_target":30,"volume_achieved":12,"volume_bp":4000,"by_pack":[{"pack_factor":1,"target":30,"achieved":12}]},{"category_code":"lighter","volume_target":100,"volume_achieved":0,"volume_bp":null,"by_pack":[{"pack_factor":25,"target":100,"achieved":0}]}]"""
        val d = KpiModel.build(listOf(route(kpi(listOf(cigA, matchM, lighterL), vol = """{"target":1000,"achieved":300,"bp":3000}""", cats = cats))), names, cats2)!!
        assertEquals("50", KpiModel.qtyText(d.volume.target, UiLocale.EN)); assertEquals("packet", d.volume.target.unitKey)
        assertEquals("dozen", d.categories.first { it.categoryCode == "match" }.pair.target.unitKey); assertEquals("box", d.categories.first { it.categoryCode == "lighter" }.pair.target.unitKey)
    }

    @Test fun packSizesAreAddedAsAnExactQuotientAndRoundedOnceAtDisplay() {
        // 100 sticks of 12s (8.333 packets) + 100 sticks of 12s again from a second brand = 16.666 packets: one exact sum, then one rounding (16.7)
        val b = brand(1, 200, 0, 5, 0, packs = """[{"pack_factor":12,"target":200,"achieved":0}]""")
        val d = KpiModel.build(listOf(route(kpi(listOf(b), vol = """{"target":200,"achieved":0,"bp":0}"""))), names, cats1)!!
        assertEquals("16.7", KpiModel.qtyText(d.volume.target, UiLocale.EN))
    }
}
