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

    // ---- contract 1.26.0: the per-outlet `truncated` flag. The phone knows the server is 1.26.0 only by that field (a member only a 1.26.0 server sends, exactly when an outlet is cut); the bundle has no contract version.
    private fun campaignCut(outlet: Long, skus: String, flag: String = """"truncated":true,""") =
        campaign(outlet = outlet, skus = skus).replaceFirst("\"std\"", flag + "\"std\"")

    @Test fun nW1_anOlderServerKeepsTodaysBehaviourACutRouteListCutsEveryOutlet() {
        val d = OutletTargetModel.build(listOf(route(listOf(campaign(skus = sku(900, 500, 120))), truncated = true)), 5, skus, noFrame)!!
        val c = d.campaigns.single()
        assertTrue(d.routeCut); assertTrue(c.achievedPartial); assertTrue(!c.outletCut); assertTrue(c.incomplete); assertNull(c.stdUnitKey)
    }

    @Test fun nW2_aNewServerOutletThatIsNotCutIsCompleteEvenWhenAnotherOutletOfTheRouteWas() {
        val r = route(listOf(campaignCut(9, sku(900, 500, 0)), campaignCut(5, sku(900, 500, 120), flag = """"truncated":false,""")), truncated = true) // 1.27.0: the member on EVERY entry
        assertTrue(r.perOutletCut)
        val c = OutletTargetModel.build(listOf(r), 5, skus, noFrame)!!.let { assertTrue(!it.routeCut); it.campaigns.single() }
        assertTrue(!c.achievedPartial); assertTrue(!c.outletCut); assertTrue(!c.incomplete)
        assertEquals("stick", c.stdUnitKey); assertEquals(24L, c.totals.single().pct); assertTrue(c.totals.single().targetNum != null)
    }

    @Test fun nW3_aNewServerCutOutletShowsAchievementAndRemainingAsSentAndDashesOnlyThePhoneTotals() {
        val r = route(listOf(campaignCut(5, sku(900, 500, 120))), truncated = true)
        val d = OutletTargetModel.build(listOf(r), 5, skus, noFrame)!!
        val c = d.campaigns.single()
        assertTrue(!d.routeCut); assertTrue(c.outletCut); assertTrue(!c.achievedPartial) // the server's STD and memo cover all the SKUs: shown as sent
        assertEquals(CampaignFigure(1000, 300, 700), c.std); assertEquals(CampaignFigure(10, 4, 6), c.memo)
        assertTrue(c.incomplete); assertNull(c.stdUnitKey)
        val t = c.totals.single(); assertNull(t.targetNum); assertNull(t.soldNum); assertNull(t.pct) // computed on the phone from the cut table: a dash
        assertEquals(24L, c.rows.single().pct) // the row is the server's own figure
    }

    @Test fun nW4_anEntryWithTheFlagAbsentOrNullOrFalseIsNotCutOnANewServerRoute() {
        val r = route(listOf(campaignCut(9, sku(900, 500, 0)), campaignCut(5, sku(900, 500, 0), flag = """"truncated":false,"""), campaignCut(6, sku(900, 500, 0), flag = """"truncated":false,""")), truncated = true)
        listOf(5L, 6L).forEach { o -> assertTrue(!OutletTargetModel.build(listOf(r), o, skus, noFrame)!!.campaigns.single().outletCut) }
    }

    @Test fun nW5_aRouteWithNoCutAtAllIsCompleteOnBothServerGenerations() {
        val d = OutletTargetModel.build(listOf(route(listOf(campaign(skus = sku(900, 500, 120))))), 5, skus, noFrame)!!
        assertTrue(!d.routeCut); assertTrue(!d.campaigns.single().incomplete); assertTrue(!d.campaigns.single().outletCut)
    }

    // ---- contract 1.27.0: `truncated` on EVERY entry (true or false) and `campaigns_outlets_truncated` (true or absent). An older server sends neither.
    private fun routeV27(campaigns: List<String>, campaignsTruncated: Boolean, outletsTruncated: Boolean?) = RouteFigures.parse(
        """{"route_id":7,"campaigns":[${campaigns.joinToString(",")}],"campaigns_truncated":$campaignsTruncated${if (outletsTruncated == true) ""","campaigns_outlets_truncated":true""" else ""}}""",
    )!!
    private fun entry(outlet: Long, cut: Boolean, skus: String = sku(900, 500, 120)) = campaignCut(outlet, skus, flag = """"truncated":$cut,""")

    @Test fun nW6_aNewServerRouteWhoseOnlyCutIsAnotherOutletsSkuListHasNoMissingOutletsSoAnOutletWithNoEntryIsSimplyNone() {
        val r = routeV27(listOf(entry(9, true), entry(8, false)), campaignsTruncated = true, outletsTruncated = null)
        assertTrue(r.perOutletCut); assertTrue(!r.campaignsOutletsTruncated)
        assertNull(OutletTargetModel.build(listOf(r), 5, skus, noFrame)) // the outlet list was not cut: outlet 5 has no campaign, and nothing is said to be missing
    }

    @Test fun nW7_aNewServerRouteWhoseOutletListWasCutSaysAnOutletWithNoEntryMayBeMissing() {
        val r = routeV27(listOf(entry(8, false)), campaignsTruncated = true, outletsTruncated = true)
        val d = OutletTargetModel.build(listOf(r), 5, skus, noFrame)!!
        assertTrue(d.campaigns.isEmpty()); assertTrue(d.truncated)
    }

    @Test fun nW8_anOutletWithItsOwnEntryIsCompleteEvenWhenTheOutletListWasCutAndItsFlagIsFalse() {
        val r = routeV27(listOf(entry(5, false)), campaignsTruncated = true, outletsTruncated = true)
        val d = OutletTargetModel.build(listOf(r), 5, skus, noFrame)!!
        val c = d.campaigns.single()
        assertTrue(!c.outletCut); assertTrue(!c.incomplete); assertTrue(!d.routeCut); assertEquals(24L, c.totals.single().pct)
    }

    @Test fun nW9_aNewServerOutletWithItsFlagTrueIsCutAndTheOthersOnTheRouteAreNot() {
        val r = routeV27(listOf(entry(5, true), entry(6, false)), campaignsTruncated = true, outletsTruncated = null)
        val cut = OutletTargetModel.build(listOf(r), 5, skus, noFrame)!!.campaigns.single()
        assertTrue(cut.outletCut); assertTrue(!cut.achievedPartial); assertNull(cut.totals.single().pct)
        assertTrue(!OutletTargetModel.build(listOf(r), 6, skus, noFrame)!!.campaigns.single().outletCut)
    }

    @Test fun nW10_anOlderServerPayloadWithNeitherMemberBehavesAsBefore() {
        val r = route(listOf(campaign(skus = sku(900, 500, 120))), truncated = true) // no `truncated` on the entry, no marker
        assertTrue(!r.perOutletCut)
        val d = OutletTargetModel.build(listOf(r), 5, skus, noFrame)!!
        assertTrue(d.routeCut); assertTrue(d.campaigns.single().achievedPartial); assertTrue(d.campaigns.single().incomplete)
        val other = OutletTargetModel.build(listOf(r), 9, skus, noFrame)!! // an outlet with no entry on a cut old route: the empty cut answer
        assertTrue(other.campaigns.isEmpty()); assertTrue(other.truncated)
    }

    @Test fun nW11_theOutletsTruncatedMemberIsReadTrueOnlyAndNullFalseOrAbsentIsFalse() {
        fun parse(m: String) = RouteFigures.parse("""{"route_id":7,"campaigns":[]$m}""")!!.campaignsOutletsTruncated
        assertTrue(parse(""","campaigns_outlets_truncated":true"""))
        assertTrue(!parse(""","campaigns_outlets_truncated":null""")); assertTrue(!parse(""","campaigns_outlets_truncated":false""")); assertTrue(!parse(""))
    }

    // ---- a cached 1.26.0 bundle: `truncated` ONLY on cut entries, no outlets marker, campaigns_truncated = outlet cap OR any entry cut. Read the old way.
    @Test fun nX1_a126RouteWithOneFlaggedEntryAndOthersUnflaggedIsOldStyleSoAnOutletWithNoEntryGetsTheCutNote() {
        val r = route(listOf(campaignCut(9, sku(900, 500, 0)), campaign(outlet = 8, skus = sku(900, 500, 120))), truncated = true) // 1.26.0 shape; the outlet cap may be the cause
        assertTrue(!r.perOutletCut); assertTrue(r.outletListMayBeCut)
        val none = OutletTargetModel.build(listOf(r), 5, skus, noFrame)!!
        assertTrue(none.campaigns.isEmpty()); assertTrue(none.truncated) // the cut note, not "No campaign target is set"
        val listed = OutletTargetModel.build(listOf(r), 8, skus, noFrame)!!
        assertTrue(listed.routeCut); assertTrue(listed.campaigns.single().achievedPartial); assertTrue(!listed.campaigns.single().outletCut) // the old rule: the route flag cuts every outlet
    }

    @Test fun nX2_theShapeThatCannotBeToldApartEveryEntryCutRouteFlagSetNoMarkerTakesTheCautiousReadingForANoEntryOutlet() {
        val r = route(listOf(campaignCut(9, sku(900, 500, 0)), campaignCut(8, sku(900, 500, 0))), truncated = true)
        assertTrue(r.perOutletCut) // every entry carries the member
        assertTrue(r.outletListMayBeCut) // cannot exclude a cap cut (1.26.0): the cautious reading
        val none = OutletTargetModel.build(listOf(r), 5, skus, noFrame)!!
        assertTrue(none.campaigns.isEmpty()); assertTrue(none.truncated)
        assertTrue(OutletTargetModel.build(listOf(r), 9, skus, noFrame)!!.campaigns.single().outletCut) // an entry that is cut is cut either way
    }

    @Test fun nX3_aRealV127RouteWithAFalseEntryAndNoMarkerKnowsTheOutletListWasNotCut() {
        val r = routeV27(listOf(entry(9, true), entry(8, false)), campaignsTruncated = true, outletsTruncated = null)
        assertTrue(r.perOutletCut); assertTrue(!r.outletListMayBeCut) // a false entry is only ever sent by 1.27.0: no outlet is missing
    }

    @Test fun nX4_aRouteWithNoEntriesIsOldStyleAndTheMarkerAloneMakesItNewStyle() {
        val none = route(emptyList(), truncated = true)
        assertTrue(!none.perOutletCut); assertTrue(none.outletListMayBeCut)
        val marker = routeV27(emptyList(), campaignsTruncated = true, outletsTruncated = true)
        assertTrue(marker.perOutletCut); assertTrue(marker.outletListMayBeCut)
        assertTrue(!routeV27(emptyList(), campaignsTruncated = false, outletsTruncated = null).outletListMayBeCut)
    }

    // ---- two routes of different shapes in one figures list: each entry is read by ITS route's shape, a cut route never cuts another route's outlets
    @Test fun nY1_anUncutOldRouteNextToACutNewRouteStaysComplete() {
        val old = route(listOf(campaign(outlet = 5, skus = sku(900, 500, 120))), truncated = false) // old-style, nothing cut
        val new = routeV27(listOf(entry(9, true), entry(8, false)), campaignsTruncated = true, outletsTruncated = null) // 1.27.0, one outlet's SKU list cut
        val figs = listOf(old, new)
        val five = OutletTargetModel.build(figs, 5, skus, noFrame)!!.campaigns.single()
        assertTrue(!five.achievedPartial); assertTrue(!five.outletCut); assertTrue(!five.incomplete) // not cut by the other route's flag
        assertTrue(OutletTargetModel.build(figs, 9, skus, noFrame)!!.campaigns.single().outletCut)
        assertNull(OutletTargetModel.build(figs, 6, skus, noFrame)) // no entry anywhere and neither route may have missing outlets
    }

    @Test fun nY2_a126AllFlaggedRouteNextToAnUncutOldRouteIsCautiousOnlyForTheNoEntryOutlet() {
        val old = route(listOf(campaign(outlet = 5, skus = sku(900, 500, 120))), truncated = false)
        val allFlagged = routeV27(listOf(entry(9, true), entry(8, true)), campaignsTruncated = true, outletsTruncated = null) // the indistinguishable shape
        val figs = listOf(old, allFlagged)
        assertTrue(!OutletTargetModel.build(figs, 5, skus, noFrame)!!.campaigns.single().incomplete) // the uncut old route's outlet is complete
        assertTrue(OutletTargetModel.build(figs, 8, skus, noFrame)!!.campaigns.single().outletCut)
        val none = OutletTargetModel.build(figs, 6, skus, noFrame)!!
        assertTrue(none.campaigns.isEmpty()); assertTrue(none.truncated) // cautious: a cap cut cannot be excluded
    }

    @Test fun nY3_aCutOldRouteNextToAnUncutNewRouteCutsOnlyItsOwnOutlets() {
        val old = route(listOf(campaign(outlet = 5, skus = sku(900, 500, 120))), truncated = true) // old-style and cut
        val new = routeV27(listOf(entry(8, false)), campaignsTruncated = false, outletsTruncated = null)
        val figs = listOf(old, new)
        val five = OutletTargetModel.build(figs, 5, skus, noFrame)!!.campaigns.single()
        assertTrue(five.achievedPartial); assertTrue(five.incomplete)
        val eight = OutletTargetModel.build(figs, 8, skus, noFrame)!!.campaigns.single()
        assertTrue(!eight.achievedPartial); assertTrue(!eight.incomplete) // the new route's outlet is not cut by the old route's flag
    }
}
