package com.aktcl.aron.feature.programmes

import com.aktcl.aron.rules.UiLocale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OutletTargetModelTest {
    private fun campaign(outlet: Long = 5, programme: Long = 1, skus: String, std: String = """{"target":1000,"achieved":300,"remaining":700}""", memo: String = """{"target":10,"achieved":4,"remaining":6}""", through: String = "2026-11-17") =
        """{"outlet_id":$outlet,"programme_id":$programme,"label_en":"Super Star Low","label_bn":"সুপার স্টার লো","month":"2026-11","through_date":"$through","as_of":"2026-11-18T01:00:00Z","std":$std,"memo":$memo,"skus":[$skus]}"""
    private fun sku(id: Long, target: Long, sold: Long) = """{"sku_id":$id,"target_qty_base":$target,"sold_qty_base":$sold}"""
    private fun route(campaigns: List<String>, truncated: Boolean = false) =
        RouteFigures.parse("""{"route_id":7,"campaigns":[${campaigns.joinToString(",")}],"campaigns_truncated":$truncated}""")!!
    private val skus = mapOf(
        900L to SkuInfo(900, "Maxim", "ম্যাক্সিম", "cigarette", "stick", 20, "packet"),
        901L to SkuInfo(901, "Avon 10s", null, "cigarette", "stick", 10, "packet"),
        902L to SkuInfo(902, "Safe Match", null, "match", "dozen", 1, "dozen"),
        903L to SkuInfo(903, "Lighter", null, "lighter", "piece", 25, "box"),
        905L to SkuInfo(905, "Single stick", null, "cigarette", "stick", 1, "stick"),
    )
    private val noFrame: (RouteCampaign, (Long) -> Boolean) -> FigureFrame = { c, _ -> FigureFrame(c.month, c.throughDate, c.asOf, false, LocalToday(0, 0, 0, emptyMap()), 0, 0) }

    @Test fun theServerFiguresAreShownAsSentAndTheRowsInPhoneUnits() {
        val d = OutletTargetModel.build(listOf(route(listOf(campaign(skus = sku(900, 500, 120) + "," + sku(901, 300, 100) + "," + sku(902, 24, 6) + "," + sku(903, 100, 50))))), 5, skus, noFrame)!!
        val c = d.campaigns.single()
        assertEquals(CampaignFigure(1000, 300, 700), c.std); assertEquals(CampaignFigure(10, 4, 6), c.memo); assertEquals("2026-11-17", c.throughDate)
        val r = c.rows.associateBy { it.skuId }
        assertEquals("25", OutletTargetModel.qtyText(r.getValue(900).targetMilli, r.getValue(900).factor, UiLocale.EN)); assertEquals(24L, r.getValue(900).pct) // 120 of 500
        assertEquals("30", OutletTargetModel.qtyText(r.getValue(901).targetMilli, r.getValue(901).factor, UiLocale.EN)) // 300 sticks of 10s = 30 packets
        assertEquals("box", r.getValue(903).unitKey); assertEquals("4", OutletTargetModel.qtyText(r.getValue(903).targetMilli, r.getValue(903).factor, UiLocale.EN))
        assertEquals(listOf("cigarette", "cigarette", "lighter", "match"), c.rows.map { it.categoryCode }) // cigarette, lighter, match as the manual lists
    }

    @Test fun aCategoryTotalIsTheExactSumInItsOwnUnitAndItsPercentIsOfTheBaseQuantity() {
        val d = OutletTargetModel.build(listOf(route(listOf(campaign(skus = sku(900, 500, 120) + "," + sku(901, 300, 100))))), 5, skus, noFrame)!!
        val t = d.campaigns.single().totals.single()
        assertEquals("packet", t.unitKey)
        assertEquals("55", OutletTargetModel.totalText(t.targetNum, t.den, UiLocale.EN)) // 25 packets of 20s + 30 packets of 10s
        assertEquals("16", OutletTargetModel.totalText(t.soldNum, t.den, UiLocale.EN)) // 6 + 10
        assertEquals(28L, t.pct) // 220 of 800 base sticks = 27.5 %, rounded once half up
    }

    @Test fun aZeroTargetIsADashNotZeroPercent() {
        val d = OutletTargetModel.build(listOf(route(listOf(campaign(skus = sku(900, 0, 40))))), 5, skus, noFrame)!!
        assertNull(d.campaigns.single().rows.single().pct); assertEquals("—", OutletTargetModel.percentText(null, UiLocale.EN))
    }

    @Test fun aSkuTheTableDoesNotKnowIsLeftOutAndFlaggedNotInventedAsARow() {
        val d = OutletTargetModel.build(listOf(route(listOf(campaign(skus = sku(900, 500, 0) + "," + sku(999, 10, 0))))), 5, skus, noFrame)!!
        assertEquals(listOf(900L), d.campaigns.single().rows.map { it.skuId }); assertTrue(d.campaigns.single().skuMissing)
    }

    @Test fun anOutletWithNoCampaignIsNullButACutListIsAnEmptyTruncatedAnswer() {
        assertNull(OutletTargetModel.build(listOf(route(listOf(campaign(outlet = 9, skus = sku(900, 1, 0))))), 5, skus, noFrame))
        val cut = OutletTargetModel.build(listOf(route(listOf(campaign(outlet = 9, skus = sku(900, 1, 0))), truncated = true)), 5, skus, noFrame)!!
        assertTrue(cut.campaigns.isEmpty()); assertTrue(cut.truncated)
    }

    @Test fun theSameOutletOnTwoRoutesGivesOneViewPerProgramme() {
        val a = route(listOf(campaign(skus = sku(900, 500, 0)))); val b = route(listOf(campaign(skus = sku(900, 500, 0)), campaign(programme = 2, skus = sku(902, 12, 0))))
        assertEquals(listOf(1L, 2L), OutletTargetModel.build(listOf(a, b), 5, skus, noFrame)!!.campaigns.map { it.programmeId })
    }

    @Test fun thePhonesOwnLinesAreCarriedSeparatelyAndNeverChangeTheServerFigure() {
        val frame: (RouteCampaign, (Long) -> Boolean) -> FigureFrame = { c, _ -> FigureFrame(c.month, c.throughDate, c.asOf, false, LocalToday(40, 2, 40_000, mapOf(20 to 40L)), 3, 1) }
        val c = OutletTargetModel.build(listOf(route(listOf(campaign(skus = sku(900, 500, 120))))), 5, skus, frame)!!.campaigns.single()
        assertEquals(CampaignFigure(1000, 300, 700), c.std) // untouched
        assertEquals(40L, c.today.qtyBase); assertEquals(3, c.unconfirmedEarlier); assertEquals(1, c.forkLeftOut)
        assertEquals(120_000L, c.rows.single().soldMilli) // the SKU row has no local sale in it either
    }

    @Test fun theFirstOfTheMonthWindowIsMarked() {
        val c = OutletTargetModel.build(listOf(route(listOf(campaign(skus = sku(900, 500, 0), through = "2026-10-31")))), 5, skus, noFrame)!!.campaigns.single()
        assertTrue(c.fromFirst)
    }

    // O1 (lead review): today on this phone is per category in the field unit, never one number across categories
    @Test fun todayOnThisPhoneIsPerCategoryInEachCategorysOwnUnitNeverOneNumber() {
        // 2 packets of a 20s SKU (40 sticks) + 1 box of lighters (25 pieces): the old line read "65 STD"
        val frame: (RouteCampaign, (Long) -> Boolean) -> FigureFrame = { c, keep ->
            val q = when { keep(900) -> 40L; keep(903) -> 25L; else -> 0L }
            FigureFrame(c.month, c.throughDate, c.asOf, false, LocalToday(q, 2, 0, emptyMap()), 0, 0)
        }
        val c = OutletTargetModel.build(listOf(route(listOf(campaign(skus = sku(900, 500, 0) + "," + sku(903, 100, 0))))), 5, skus, frame)!!.campaigns.single()
        val cats = c.todayByCategory.associateBy { it.categoryCode }
        assertEquals(listOf("cigarette", "lighter"), c.todayByCategory.map { it.categoryCode })
        assertEquals("2", OutletTargetModel.totalText(cats.getValue("cigarette").num, cats.getValue("cigarette").den, UiLocale.EN)); assertEquals("packet", cats.getValue("cigarette").unitKey)
        assertEquals("1", OutletTargetModel.totalText(cats.getValue("lighter").num, cats.getValue("lighter").den, UiLocale.EN)); assertEquals("box", cats.getValue("lighter").unitKey)
    }

    @Test fun todayWithTwoPackSizesIsAnExactSumOfPackets() {
        // 40 sticks of a 20s SKU = 2 packets, 30 sticks of a 10s SKU = 3 packets: 5 packets
        val frame: (RouteCampaign, (Long) -> Boolean) -> FigureFrame = { c, keep ->
            val q = when { keep(900) -> 40L; keep(901) -> 30L; else -> 0L }
            FigureFrame(c.month, c.throughDate, c.asOf, false, LocalToday(q, 1, 0, emptyMap()), 0, 0)
        }
        val t = OutletTargetModel.build(listOf(route(listOf(campaign(skus = sku(900, 500, 0) + "," + sku(901, 300, 0))))), 5, skus, frame)!!.campaigns.single().todayByCategory.single()
        assertEquals("5", OutletTargetModel.totalText(t.num, t.den, UiLocale.EN))
    }

    // O2: a cut list or a missing SKU must not read as complete
    @Test fun aCutListDashesTheCategoryTotalsAndTheirPercentButKeepsTheRows() {
        val d = OutletTargetModel.build(listOf(route(listOf(campaign(skus = sku(900, 500, 120))), truncated = true)), 5, skus, noFrame)!!
        val c = d.campaigns.single()
        assertTrue(d.truncated); assertTrue(c.incomplete)
        assertEquals(24L, c.rows.single().pct) // the row is the server's own figure
        val t = c.totals.single(); assertNull(t.targetNum); assertNull(t.soldNum); assertNull(t.pct)
        assertEquals("—", OutletTargetModel.totalText(t.targetNum, t.den, UiLocale.EN))
    }

    @Test fun aMissingSkuAlsoDashesTheTotals() {
        val c = OutletTargetModel.build(listOf(route(listOf(campaign(skus = sku(900, 500, 120) + "," + sku(999, 10, 0))))), 5, skus, noFrame)!!.campaigns.single()
        assertTrue(c.skuMissing); assertTrue(c.incomplete); assertNull(c.totals.single().pct)
    }

    @Test fun aNegativeSoldQuantityDropsThatRowNotTheWholeReadAndMarksTheListIncomplete() {
        val c = OutletTargetModel.build(listOf(route(listOf(campaign(skus = sku(900, 500, 120) + "," + sku(901, 300, -5))))), 5, skus, noFrame)!!.campaigns.single()
        assertEquals(listOf(900L), c.rows.map { it.skuId }); assertTrue(c.incomplete); assertNull(c.totals.single().targetNum)
    }

    @Test fun aSkuWhoseUnitRuleFailsDropsThatRowOnly() {
        val bad = skus + (904L to SkuInfo(904, "Odd", null, "cigarette", "stick", 0, "packet"))
        val c = OutletTargetModel.build(listOf(route(listOf(campaign(skus = sku(900, 500, 0) + "," + sku(904, 10, 0))))), 5, bad, noFrame)!!.campaigns.single()
        assertEquals(listOf(900L), c.rows.map { it.skuId }); assertTrue(c.incomplete)
    }

    @Test fun anOverflowingTotalIsADashNotAWrongNumber() {
        assertEquals("—", OutletTargetModel.totalText(1_000L, Long.MAX_VALUE, UiLocale.EN))
    }

    // R1: the STD unit word only when the whole campaign has one base unit
    @Test fun theStdUnitWordIsNamedOnlyWhenEveryCampaignSkuHasOneBaseUnit() {
        val one = OutletTargetModel.build(listOf(route(listOf(campaign(skus = sku(900, 500, 0) + "," + sku(901, 300, 0))))), 5, skus, noFrame)!!.campaigns.single()
        assertEquals("stick", one.stdUnitKey)
        val mixed = OutletTargetModel.build(listOf(route(listOf(campaign(skus = sku(900, 500, 0) + "," + sku(903, 100, 0))))), 5, skus, noFrame)!!.campaigns.single()
        assertNull(mixed.stdUnitKey) // 40 sticks + 25 pieces would read "65 stick"
        val dozens = OutletTargetModel.build(listOf(route(listOf(campaign(skus = sku(902, 24, 0))))), 5, skus, noFrame)!!.campaigns.single()
        assertEquals("dozen", dozens.stdUnitKey)
    }

    // R2: a cut list: achieved is partial
    @Test fun aCutListMarksAchievedAndRemainingPartialButAWholeListDoesNot() {
        assertTrue(OutletTargetModel.build(listOf(route(listOf(campaign(skus = sku(900, 500, 0))), truncated = true)), 5, skus, noFrame)!!.campaigns.single().achievedPartial)
        assertEquals(false, OutletTargetModel.build(listOf(route(listOf(campaign(skus = sku(900, 500, 0))))), 5, skus, noFrame)!!.campaigns.single().achievedPartial)
    }

    // R3: one line per category AND unit
    @Test fun aTwentiesSkuAndAOneStickSkuInOneCategoryAreNeverAddedIntoSevenPackets() {
        val frame: (RouteCampaign, (Long) -> Boolean) -> FigureFrame = { c, keep ->
            val q = when { keep(900) -> 40L; keep(905) -> 5L; else -> 0L } // 2 packets of 20s and 5 loose sticks
            FigureFrame(c.month, c.throughDate, c.asOf, false, LocalToday(q, 2, 0, emptyMap()), 0, 0)
        }
        val cats = OutletTargetModel.build(listOf(route(listOf(campaign(skus = sku(900, 500, 0) + "," + sku(905, 100, 0))))), 5, skus, frame)!!.campaigns.single().todayByCategory
        assertEquals(listOf("packet", "stick"), cats.map { it.unitKey }.sorted())
        val p = cats.first { it.unitKey == "packet" }; val k = cats.first { it.unitKey == "stick" }
        assertEquals("2", OutletTargetModel.totalText(p.num, p.den, UiLocale.EN)); assertEquals("5", OutletTargetModel.totalText(k.num, k.den, UiLocale.EN))
    }

    @Test fun aNegativeNumeratorIsADash() { assertEquals("—", OutletTargetModel.totalText(-1L, 1L, UiLocale.EN)) }

    @Test fun aCutListHasNoStdUnitWordEvenWhenEverySentSkuIsOneUnit() {
        // 20 stick SKUs sent, 2 lighter SKUs cut: the kept Target STD covers the lighters too, so "stick" would be false
        val c = OutletTargetModel.build(listOf(route(listOf(campaign(skus = sku(900, 500, 0) + "," + sku(901, 300, 0))), truncated = true)), 5, skus, noFrame)!!.campaigns.single()
        assertNull(c.stdUnitKey); assertTrue(c.achievedPartial)
    }
}
