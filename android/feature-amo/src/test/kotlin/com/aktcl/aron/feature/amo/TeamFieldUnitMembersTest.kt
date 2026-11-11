package com.aktcl.aron.feature.amo

import com.aktcl.aron.rules.UiLocale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract 1.11.2 (D-AMO-FND-16/18): the AMO team screens read the server's own field-unit members (`report_unit`, `*_qty_report`, `remaining_*_qty_report`,
 * Decimal3 strings) and show them as given; a row without them, or one the server answered in its base unit (mixed 10s/20s/units), shows the base figure with
 * the base unit word. Nothing is converted from base on the phone and no Float or Double is involved.
 */
class TeamFieldUnitMembersTest {
    private fun node(rows: String, period: Int = 6) = TeamTargets.parse("""{"node":{"type":"zone","id":5,"name":"Gulshan"},"period_days":$period,"rows":[$rows]}""")!!

    // a brand row as the 1.11.2 server sends it: base members (unchanged) plus the field-unit members
    private fun row(name: String, cat: String, tBase: Long, aBase: Long, tillBase: Long, extra: String) =
        """{"product_level":"brand","product_id":1,"name":"$name","category_code":"$cat","unit":"stick","target_qty":"$tBase.000","achieved_qty":"$aBase.000","tilldate_target_qty":"$tillBase.000",
            "remaining_qty":"0.000","target_qty_base":$tBase,"achieved_qty_base":$aBase,"tilldate_target_qty_base":$tillBase,"remaining_qty_base":0,"achievement_pct_bp":0,"tilldate_achievement_pct_bp":0 $extra}"""

    private val cigarette = row(
        "Marise", "cigarette", 50_000, 40_999, 25_000,
        ""","report_unit":"pack","target_qty_report":"2500.000","achieved_qty_report":"2199.950","tilldate_target_qty_report":"1250.000","remaining_qty_report":"300.050","remaining_tilldate_qty_report":"0.000"""",
    )
    private val lighter = row(
        "Aster", "lighter", 1_000, 810, 500,
        ""","report_unit":"box","target_qty_report":"40.000","achieved_qty_report":"32.400","tilldate_target_qty_report":"20.000","remaining_qty_report":"7.600","remaining_tilldate_qty_report":"0.000"""",
    )
    private val match = row(
        "Salmon", "match", 30, 12, 15,
        ""","report_unit":"dozen","target_qty_report":"30.000","achieved_qty_report":"12.000","tilldate_target_qty_report":"15.000","remaining_qty_report":"18.000","remaining_tilldate_qty_report":"3.000"""",
    )

    @Test fun theServersFieldUnitMembersAreShownAsGivenInPacketsBoxesAndDozens() {
        val n = node("$cigarette,$lighter,$match")
        val by = n.rows.associateBy { it.categoryCode }
        assertEquals("packet", TeamTargets.unitKey(by.getValue("cigarette"))); assertEquals("box", TeamTargets.unitKey(by.getValue("lighter"))); assertEquals("dozen", TeamTargets.unitKey(by.getValue("match")))
        val c = by.getValue("cigarette")
        assertEquals("2,500", TeamTargets.targetText(c, PerfTab.MONTHLY, UiLocale.EN)); assertEquals("2,199.95", TeamTargets.achievedText(c, UiLocale.EN))
        assertEquals("1,250", TeamTargets.targetText(c, PerfTab.TILL_DATE, UiLocale.EN))
        assertEquals("2,199.95/2,500", TeamTargets.fraction(c, PerfTab.MONTHLY, UiLocale.EN))
        assertEquals("32.4", TeamTargets.achievedText(by.getValue("lighter"), UiLocale.EN)); assertEquals("12", TeamTargets.achievedText(by.getValue("match"), UiLocale.EN))
    }

    @Test fun aLighterRowTheServerLabelsBoxAndAPackRowBothReadAsBoxes() {
        // D-AMO-FND-16 labels lighters `box`; `pack` on a lighter maps to the same box word through UnitWords (units rule 5)
        val box = node(row("Aster", "lighter", 1_000, 810, 500, ""","report_unit":"box","target_qty_report":"40.000","achieved_qty_report":"32.400","tilldate_target_qty_report":"20.000","remaining_qty_report":"7.600","remaining_tilldate_qty_report":"0.000"""")).rows.single()
        assertEquals("box", TeamTargets.unitKey(box)); assertEquals("32.4/40", TeamTargets.fraction(box, PerfTab.MONTHLY, UiLocale.EN))
        assertEquals("box", TeamTargets.unitKey(node(lighter).rows.single()))
        // the alias: `pack` on a lighter reads as the same box word (kept as the one `pack` case)
        val alias = node(row("Aster", "lighter", 1_000, 810, 500, ""","report_unit":"pack","target_qty_report":"40.000","achieved_qty_report":"32.400"""")).rows.single()
        assertEquals("box", TeamTargets.unitKey(alias))
    }

    @Test fun remainingIsTheServersOwnMemberNotRecomputedOnThePhone() {
        // the server floors from the ROUNDED target and achievement and may differ from T - A by 0.001; the phone prints what it was sent
        val odd = node(row("X", "cigarette", 50_000, 40_999, 25_000, ""","report_unit":"pack","target_qty_report":"10.000","achieved_qty_report":"4.001","tilldate_target_qty_report":"5.000","remaining_qty_report":"5.998","remaining_tilldate_qty_report":"0.9"""")).rows.single()
        assertEquals("5.998", TeamTargets.remainingText(odd, PerfTab.MONTHLY, UiLocale.EN)) // 10 - 4.001 would be 5.999
        assertEquals("0.9", TeamTargets.remainingText(odd, PerfTab.TILL_DATE, UiLocale.EN)) // 5 - 4.001 would be 0.999
        assertEquals("৫.৯৯৮", TeamTargets.remainingText(odd, PerfTab.MONTHLY, UiLocale.BN))
    }

    @Test fun aCategoryCardIsTheExactSumOfItsRowsInTheirOneUnitAndItsRemainingIsFlooredOnThoseDecimals() {
        val a = row("A", "cigarette", 100, 50, 60, ""","report_unit":"pack","target_qty_report":"5.000","achieved_qty_report":"2.500","tilldate_target_qty_report":"3.000","remaining_qty_report":"2.500","remaining_tilldate_qty_report":"0.500"""")
        val b = row("B", "cigarette", 100, 120, 60, ""","report_unit":"pack","target_qty_report":"10.000","achieved_qty_report":"12.000","tilldate_target_qty_report":"6.000","remaining_qty_report":"0.000","remaining_tilldate_qty_report":"0.000"""")
        val card = TeamTargets.categoryCards(node("$a,$b").rows, 6).single()
        assertEquals("packet", TeamTargets.unitKey(card))
        assertEquals("14.5/15", TeamTargets.fraction(card, PerfTab.MONTHLY, UiLocale.EN))
        assertEquals("0.5", TeamTargets.remainingText(card, PerfTab.MONTHLY, UiLocale.EN)) // max(15 - 14.5, 0) on the sums; the rows' own remainders (2.5 and 0) are not added
    }

    @Test fun withoutTheMembersTheRowIsInBaseUnitsWithTheBaseWord() {
        val old = node("""{"product_level":"brand","product_id":1,"name":"Marise","category_code":"cigarette","target_qty_base":50000,"achieved_qty_base":40999,"tilldate_target_qty_base":25000}""").rows.single()
        assertNull(old.qty); assertEquals("stick", TeamTargets.unitKey(old))
        assertEquals("40,999/50,000", TeamTargets.fraction(old, PerfTab.MONTHLY, UiLocale.EN)); assertEquals("9,001", TeamTargets.remainingText(old, PerfTab.MONTHLY, UiLocale.EN))
        val lighterOld = node("""{"product_level":"brand","product_id":2,"name":"Aster","category_code":"lighter","target_qty_base":1000,"achieved_qty_base":810}""").rows.single()
        assertEquals("piece", TeamTargets.unitKey(lighterOld)); assertEquals("dozen", TeamTargets.unitKey(node("""{"name":"S","category_code":"match","target_qty_base":30,"achieved_qty_base":12}""").rows.single()))
    }

    @Test fun aMixedUnitRowTheServerAnsweredInItsBaseUnitShowsBaseFiguresAndTheBaseWord() {
        // `report_unit` = the base unit and the *_qty_report members carry the base quantities (D-AMO-FND-16): the base word comes out of the same path
        val mixed = node(row("Mix", "cigarette", 50_000, 40_999, 25_000, ""","report_unit":"stick","target_qty_report":"50000.000","achieved_qty_report":"40999.000","tilldate_target_qty_report":"25000.000","remaining_qty_report":"9001.000","remaining_tilldate_qty_report":"0.000"""")).rows.single()
        assertEquals("stick", TeamTargets.unitKey(mixed)); assertEquals("40,999/50,000", TeamTargets.fraction(mixed, PerfTab.MONTHLY, UiLocale.EN)); assertEquals("9,001", TeamTargets.remainingText(mixed, PerfTab.MONTHLY, UiLocale.EN))
        // a category with one packet row and one base-unit row cannot be one figure: the card falls back to base integers and the base word
        val packRow = cigarette
        val card = TeamTargets.categoryCards(node("$packRow,${row("Mix", "cigarette", 50_000, 40_999, 25_000, ""","report_unit":"stick","target_qty_report":"50000.000","achieved_qty_report":"40999.000","tilldate_target_qty_report":"25000.000","remaining_qty_report":"9001.000","remaining_tilldate_qty_report":"0.000"""")}").rows, 6).single()
        assertNull(card.qty); assertEquals("stick", TeamTargets.unitKey(card)); assertEquals("81,998/100,000", TeamTargets.fraction(card, PerfTab.MONTHLY, UiLocale.EN))
    }

    @Test fun anUnknownOrMissingReportUnitOrAMissingOrNegativeQuantityFallsBackToTheBaseFigureAndWord() {
        // the 1.11.2 server also keeps sending the base members (`unit` = the base unit, `target_qty` = the base integer as Decimal3): that is the fallback
        fun check(extra: String) {
            val r = node(row("X", "cigarette", 10, 4, 5, extra)).rows.single()
            assertEquals("stick", TeamTargets.unitKey(r)); assertEquals("10", TeamTargets.targetText(r, PerfTab.MONTHLY, UiLocale.EN)); assertEquals("4", TeamTargets.achievedText(r, UiLocale.EN))
            assertEquals("6", TeamTargets.remainingText(r, PerfTab.MONTHLY, UiLocale.EN)) // never the unreadable report figures (1 / 0.4 / -1)
        }
        check(""","report_unit":"carton","target_qty_report":"1.000","achieved_qty_report":"0.400"""") // a unit this phone does not know
        check(""","report_unit":"pack","target_qty_report":"1.000"""") // achieved missing
        check(""","report_unit":"pack","target_qty_report":"-1.000","achieved_qty_report":"0.400"""") // negative
        check(""","target_qty_report":"1.000","achieved_qty_report":"0.400"""") // report_unit missing
    }

    @Test fun aNodeWithoutAPeriodHasNoTillDateFigureEvenWhenTheServerSentOne() {
        val r = node(cigarette, period = 0).rows.single()
        assertNull(r.tillDateTarget); assertNull(r.qty!!.tillDate); assertNull(r.qty!!.remainingTillDate)
        assertEquals(PerfTabDash, TeamTargets.targetText(r, PerfTab.TILL_DATE, UiLocale.EN))
    }

    private val PerfTabDash = com.aktcl.aron.rules.Percent.DASH

    // ---- sales-summary memos_<category> (route level, repeated on every brand row of a route)
    private fun summary(vararg rows: String) = Reports.routeCards(Reports.parse("""{"as_of":"x","columns":[],"rows":[${rows.joinToString(",")}],"total_rows":${rows.size}}""")!!)
    private fun srow(id: Int, brand: String, memos: String) = """{"route_id":$id,"route_code":"R$id","route_name":"Route $id","target_outlets":80,"successful_calls":20,"cpr_pct":25.0,"memos":18,"net_mtk":1,"brand_name":"$brand","brand_memos":3,"bsr_pct":10.0 $memos}"""

    @Test fun routeMemosAreTheServersCountsOncePerRouteNotSummedOverBrandRows() {
        val cards = summary(srow(1, "A", ""","memos_cigarette":12,"memos_bidi":0,"memos_match":4,"memos_lighter":7"""), srow(1, "B", ""","memos_cigarette":12,"memos_bidi":0,"memos_match":4,"memos_lighter":7"""))
        assertEquals(mapOf("cigarette" to 12L, "bidi" to 0L, "match" to 4L, "lighter" to 7L), cards.single().categoryMemos)
        assertEquals(emptyMap<String, Long>(), summary(srow(1, "A", "")).single().categoryMemos) // an older server: no member, no figure
        // D-AMO-CALLS-31 (2): a count is never negative; a negative member is no figure (the others stay)
        assertEquals(mapOf("match" to 4L), summary(srow(1, "A", ""","memos_cigarette":-3,"memos_match":4""")).single().categoryMemos)
    }

    @Test fun theZoneMemosAreTheSumOfEveryRoutesCountOnlyForCategoriesEveryRouteReported() {
        val cards = summary(srow(1, "A", ""","memos_cigarette":12,"memos_match":4"""), srow(2, "A", ""","memos_cigarette":5,"memos_match":1,"memos_bidi":9"""))
        assertEquals(mapOf("cigarette" to 17L, "match" to 5L), TeamReports.zoneMemos(cards)) // bidi: route 1 did not report it, so no partial sum
        assertEquals(emptyMap<String, Long>(), TeamReports.zoneMemos(emptyList()))
    }

    @Test fun theTillCardPrintsTheMemoCountAndADashWhereTheServerSentNone() {
        val node = node("$cigarette,$match")
        val card = TeamReports.tillCard("Route: R1", node, false, mapOf("cigarette" to 12L), UiLocale.EN)
        val rows = card.rows.associateBy { it.category }
        assertEquals("12", rows.getValue("cigarette").memo); assertEquals(com.aktcl.aron.rules.Percent.DASH, rows.getValue("match").memo)
        assertTrue(rows.getValue("cigarette").target.isNotEmpty())
    }
}
