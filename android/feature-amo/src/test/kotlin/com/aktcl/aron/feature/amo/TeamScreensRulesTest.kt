package com.aktcl.aron.feature.amo

import com.aktcl.aron.rules.UiLocale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Test

/** Rows 33, 37, 38, 71, 72 of the AMO manual build: the rules behind Team Location, Task Delegation and the reports. */
class TeamScreensRulesTest {
    @Test fun teamLocationListsNamedSrsOnlyWithPhonesInClear() {
        val pins = TeamLocations.parse("""{"items":[{"user_id":2,"full_name":"Shafikul Islam","route_ids":[1],"last_fix":{"lat":23.7,"lng":90.4,"at":"2026-11-06T04:00:00Z","source":"visit"}},
            {"user_id":3,"full_name":"N/A","route_ids":[]},{"user_id":4,"full_name":" ","route_ids":[]},{"user_id":1,"full_name":"Mahmud Hasan","route_ids":[]}]}""")
        val c = TeamLocationRules.choices(pins, mapOf(2L to "01812345678"))
        assertEquals(listOf("Mahmud Hasan", "Shafikul Islam"), c.map { it.name }) // no "N/A" row, no blank row
        assertEquals("Shafikul Islam · 01812345678", TeamLocationRules.bubble(c[1])); assertEquals("Mahmud Hasan", TeamLocationRules.bubble(c[0]))
    }

    @Test fun taskFormRules() {
        val ok = TaskDelegation.validateForm(1, 2, 3, "general", "2026-11-06", "Improve the sales quality", "2026-11-06")
        assertTrue(ok.isEmpty())
        assertTrue(AssignError.DATE_INVALID in TaskDelegation.validateForm(1, 2, 3, "general", "2026-11-05", "x", "2026-11-06")) // never in the past
        assertTrue(AssignError.DESCRIPTION_REQUIRED in TaskDelegation.validateForm(1, 2, 3, "general", "2026-11-06", "   ", "2026-11-06"))
        assertTrue(TaskDelegation.validateForm(1, 2, 3, "g", "2026-11-06", "a".repeat(500), "2026-11-06").isEmpty())
        assertTrue(AssignError.TEXT_TOO_LONG in TaskDelegation.validateForm(1, 2, 3, "g", "2026-11-06", "a".repeat(501), "2026-11-06"))
        val none = TaskDelegation.validateForm(null, null, null, null, null, null, "2026-11-06")
        assertTrue(none.containsAll(listOf(AssignError.ROUTE_REQUIRED, AssignError.OUTLET_REQUIRED, AssignError.ASSIGNEE_REQUIRED, AssignError.TYPE_REQUIRED, AssignError.DATE_INVALID, AssignError.DESCRIPTION_REQUIRED)))
        assertEquals(listOf("A", "B", "S"), TaskDelegation.letters(listOf("Sifat", "afifa", "Bismillah", "sabbir"))) // upper-case, only letters present
    }

    @Test fun statusChipsRunningDoneCancelledOverdue() {
        val t = AssignedTask("u", "general", "x", 1, 7, "ongoing", "2026-11-06", null, null, "2026-11-01T00:00:00Z")
        assertEquals(TaskChip.RUNNING, TaskDelegation.chip(t, "2026-11-06")); assertEquals(TaskChip.OVERDUE, TaskDelegation.chip(t, "2026-11-07"))
        assertEquals(TaskChip.DONE, TaskDelegation.chip(t.copy(status = "completed"), "2026-11-30")); assertEquals(TaskChip.CANCELLED, TaskDelegation.chip(t.copy(status = "cancelled"), "2026-11-30"))
        assertEquals(TaskChip.WAITING, TaskDelegation.chip(t.copy(waiting = true), "2026-11-07")); assertEquals(TaskChip.REJECTED, TaskDelegation.chip(t.copy(rejected = true), "2026-11-07"))
        assertEquals(TaskChip.RUNNING, TaskDelegation.chip(t.copy(dueDate = null), null))
    }

    @Test fun reportRangeDefaultsToMonthToTodayAndRejectsInvertedOrFuture() {
        assertEquals("2026-10-01" to "2026-10-25", TeamReports.monthToToday("2026-10-25")); assertEquals("2026-11-01" to "2026-11-01", TeamReports.monthToToday("2026-11-01"))
        assertEquals("October 1, 2026 - October 25, 2026", TeamReports.rangeText("2026-10-01", "2026-10-25"))
        assertTrue(TeamReports.rangeValid("2026-10-01", "2026-10-25", "2026-10-25")); assertFalse(TeamReports.rangeValid("2026-10-26", "2026-10-25", "2026-10-25")); assertFalse(TeamReports.rangeValid("2026-10-01", "2026-10-26", "2026-10-25"))
    }

    @Test fun stdMatrixSumsPerRouteAndColumnAndNeverAddsMixedUnits() {
        val m = TeamReports.stdMatrix(
            "Laxipur",
            listOf(RouteCategory("B", "cigarette", 200, "stick", 0), RouteCategory("A", "cigarette", 100, "stick", 0), RouteCategory("A", "lighter", 7, "piece", 0), RouteCategory("B", "match", null, null, 0)),
        )
        assertEquals("Laxipur", m.zone); assertEquals(listOf("cigarette", "lighter", "match"), m.categories); assertEquals(listOf("A", "B"), m.rows.map { it.route })
        assertEquals(300L, m.totals["cigarette"]); assertEquals(7L, m.totals["lighter"]); assertNull(m.totals["match"]) // a mixed-unit column has no total
        assertEquals(0L, m.rows[0].cells["match"]); assertNull(m.rows[1].cells["match"])
    }

    @Test fun aColumnNeverAddsTwoBaseUnitsAndSameCaseRoutesBothStay() {
        val m = TeamReports.stdMatrix("Z", listOf(RouteCategory("A", "lighter", 25, "piece", 0), RouteCategory("B", "lighter", 2, "box", 0), RouteCategory("Gulshan", "match", 1, "dozen", 0), RouteCategory("GULSHAN", "match", 2, "dozen", 0)))
        assertNull(m.totals["lighter"]); assertEquals(3L, m.totals["match"]); assertEquals(4, m.rows.size)
    }

    @Test fun tillNowFiguresAndCpr() {
        val node = TeamTargetNode("route", 11, "Math Bazer", 30, listOf(TeamTargetRow("Maxim", "cigarette", 246_000, 143_380, 204_998)))
        val c = TeamReports.tillCard("Route: Math Bazer", node, false, mapOf("cigarette" to 571L), UiLocale.EN).rows.single()
        assertEquals("204,998", c.target); assertEquals("143,380", c.sales); assertEquals("69.94%", c.pct); assertEquals("571", c.memo)
        assertEquals("—", TeamReports.tillCard("x", node, false, emptyMap(), UiLocale.EN).rows.single().memo) // no memo count from the server, no invented one
        assertEquals("79.67", TeamReports.cprText(7967, 10000, UiLocale.EN)); assertEquals("—", TeamReports.cprText(5, 0, UiLocale.EN))
        assertEquals("৭৯.৬৭", TeamReports.cprText(7967, 10000, UiLocale.BN))
    }

    @Test fun aRefusalIsNeverShownAsOffline() {
        assertEquals(FetchNote.REFUSED, FetchNote.of(failed = true, stale = false, refused = true)) // Gone also counts as missing: refusal wins
        assertEquals(FetchNote.REFUSED, FetchNote.of(failed = false, stale = true, refused = true))
        assertEquals(FetchNote.OFFLINE, FetchNote.of(failed = true, stale = true, refused = false))
        assertEquals(FetchNote.KEPT, FetchNote.of(failed = false, stale = true, refused = false)); assertEquals(FetchNote.NONE, FetchNote.of(false, false, false))
    }

    @Test fun categoryMemoColumnIsADashUntilTheServerSendsTheCountAndNeverDerived() {
        val node = TeamTargetNode("route", 11, "Math Bazer", 30, listOf(TeamTargetRow("Maxim", "cigarette", 1, 1, 1), TeamTargetRow("Panther", "match", 1, 1, 1)))
        val absent = LiveDataParser.parse("""{"as_of":"x","kpis":{},"sales_by_category":[{"category_code":"cigarette","base_unit":"stick","qty_base":10}]}""")!!
        assertEquals(emptyMap<String, Long>(), TeamReports.categoryMemos(absent.categories)) // field absent: nothing, never an estimate
        assertTrue(TeamReports.tillCard("x", node, false, TeamReports.categoryMemos(absent.categories), UiLocale.EN).rows.all { it.memo == "—" })
        val present = LiveDataParser.parse("""{"as_of":"x","kpis":{},"sales_by_category":[{"category_code":"cigarette","base_unit":"stick","qty_base":10,"memos":42},{"category_code":"match","base_unit":"dozen","qty_base":3,"memos":null}]}""")!!
        val rows = TeamReports.tillCard("x", node, false, TeamReports.categoryMemos(present.categories), UiLocale.EN).rows
        assertEquals("42", rows.first { it.category == "cigarette" }.memo); assertEquals("—", rows.first { it.category == "match" }.memo) // null stays a dash
    }

    @Test fun summaryPeriodIsMonthToTodayNotYesterday() {
        assertEquals("2026-11-01" to "2026-11-07", TeamReports.summaryPeriod("2026-11-07")); assertEquals("2026-11-01" to "2026-11-01", TeamReports.summaryPeriod("2026-11-01"))
    }

    @Test fun routeCategoriesGroupByRouteCodeAndShowTheName() {
        fun row(code: String, name: String, q: Long): ReportRow = mapOf("route_code" to JsonPrimitive(code), "route_name" to JsonPrimitive(name), "category_code" to JsonPrimitive("match"), "base_unit" to JsonPrimitive("dozen"), "sold_qty_base" to JsonPrimitive(q), "gross_mtk" to JsonPrimitive(0L))
        val cats = Reports.routeCategories(ReportData("2026-11-07", emptyList(), listOf(row("R1", "Gulshan", 2), row("R1", "Gulshan Zone Route", 3), row("R2", "Gulshan", 4)), 3))
        assertEquals(2, cats.size); assertEquals(setOf("R1", "R2"), cats.map { it.code }.toSet()); assertEquals(5L, cats.first { it.code == "R1" }.qty); assertEquals(4L, cats.first { it.code == "R2" }.qty)
        val m = TeamReports.stdMatrix("Z", cats); assertEquals(2, m.rows.size) // two routes with one name stay two rows
        assertEquals(9L, m.totals["match"])
    }

    @Test fun taskDraftKeyIsRouteAndOutletOnly() {
        assertEquals(TaskDelegation.draftKey(3, 50), TaskDelegation.draftKey(3, 50)); assertTrue(TaskDelegation.draftKey(3, 50) != TaskDelegation.draftKey(3, 51)); assertTrue(TaskDelegation.draftKey(3, 50) != TaskDelegation.draftKey(4, 50))
        assertTrue(!TaskDelegation.draftKey(null, null).contains("user"))
    }

    @Test fun f2ContractShapesParse() {
        // contract 1.11.1 names: journey distance_m / distance_so_far_m, members route_code, CategoryVolume.memos
        val j = TeamJourneys.journey("""{"user":{"user_id":7,"full_name":"R"},"route":{"route_id":3,"name":"G"},"business_date":"2026-11-06","distance_m":120.5,"calls":[{"visit_uuid":"a","outlet":{"id":1,"name":"O","code":"1","phone_masked":"017*****123","cluster":"C"},"status":"no_sale","opened_at":"2026-11-06T03:43:04Z","closed_at":null,"duration_s":null,"distance_from_prev_m":null,"distance_so_far_m":120.5,"gap_to_next_s":null}]}""")!!
        assertEquals(120.5, j.runningMetres, 0.0); assertEquals(120.5, j.calls.single().runningMetres, 0.0)
        val m = TeamJourneys.members("""{"items":[{"user_id":7,"full_name":"A","route_id":3,"route_code":"R-03","route_name":"G","route_days":[1],"phone":null,"phone_masked":"016*****244","total_outlets":9,"vacant":false}]}""")
        assertEquals("R-03", m.single().routeCode); assertNull(m.single().phone)
    }

    @Test fun aRouteCardIsMatchedByIdThenCodeNeverByName() {
        fun row(id: Long?, code: String, name: String, memos: Long): ReportRow = buildMap<String, JsonPrimitive> { if (id != null) put("route_id", JsonPrimitive(id)); put("route_code", JsonPrimitive(code)); put("route_name", JsonPrimitive(name)); put("memos", JsonPrimitive(memos)); put("brand_name", JsonPrimitive("Maxim")); put("brand_memos", JsonPrimitive(memos)) }
        val withIds = Reports.routeCards(ReportData("d", emptyList(), listOf(row(11, "R1", "Gulshan", 5), row(12, "R2", "Gulshan", 9)), 2))
        assertEquals(2, withIds.size) // two routes with one name stay two cards
        assertEquals(9L, TeamReports.cardFor(withIds, 12, "R2")!!.memos); assertEquals(5L, TeamReports.cardFor(withIds, 11, null)!!.memos); assertNull(TeamReports.cardFor(withIds, 99, "R1")) // an id that is present and unknown never falls back to the code
        val noIds = Reports.routeCards(ReportData("d", emptyList(), listOf(row(null, "R1", "Gulshan", 5)), 1))
        assertEquals(5L, TeamReports.cardFor(noIds, 11, "R1")!!.memos); assertNull(TeamReports.cardFor(noIds, 11, null)); assertNull(TeamReports.cardFor(noIds, 11, "Gulshan")) // a name is not a code
    }

    @Test fun routeStdWithOnlyCategoryNamesMapsToTheKnownCodes() {
        fun row(cat: String): ReportRow = mapOf("route_id" to JsonPrimitive(1), "route_code" to JsonPrimitive("R1"), "route_name" to JsonPrimitive("G"), "category_name" to JsonPrimitive(cat), "base_unit" to JsonPrimitive("stick"), "sold_qty_base" to JsonPrimitive(10), "gross_mtk" to JsonPrimitive(0))
        val cats = Reports.routeCategories(ReportData("d", emptyList(), listOf(row("Cigarettes"), row("Match Box"), row("Biscuit")), 3)).map { it.category }.toSet()
        assertEquals(setOf("cigarette", "match", "Biscuit"), cats) // the app's own title and order apply to the known ones; an unknown name stays as sent
        assertEquals(listOf("cigarette", "match", "Biscuit"), TeamReports.stdMatrix("Z", Reports.routeCategories(ReportData("d", emptyList(), listOf(row("Biscuit"), row("Match"), row("Cigarette")), 3))).categories)
    }

    // ---- units rule 5 on the AMO report screens (lead ruling 2026-11-11) -------------------------------------------------------

    @Test fun conversionIsExactDecimalAndNeverFloat() {
        assertEquals("2.250", FieldQty.converted(45, 20)!!.toPlainString()); assertEquals("1.000", FieldQty.converted(25, 25)!!.toPlainString())
        assertEquals("0.333", FieldQty.converted(1, 3)!!.toPlainString()); assertNull(FieldQty.converted(10, 0)); assertNull(FieldQty.parse("x")); assertNull(FieldQty.parse("-1.000"))
        assertEquals("12.5", FieldQty.text(FieldQty.parse("12.500")!!, UiLocale.EN)); assertEquals("1,200", FieldQty.text(FieldQty.parse("1200.000")!!, UiLocale.EN)); assertEquals("০.২৫", FieldQty.text(FieldQty.parse("0.250")!!, UiLocale.BN))
    }

    @Test fun targetRowsInFieldUnitsSumTheConvertedRowsAndFallBackToBase() {
        val node = TeamTargets.parse("""{"node":{"type":"route","id":1,"name":"G"},"period_days":30,"rows":[
          {"name":"Maxim","category_code":"cigarette","unit":"pack","target_qty":"10.000","achieved_qty":"4.500","tilldate_target_qty":"5.000","target_qty_base":100,"achieved_qty_base":45,"tilldate_target_qty_base":50},
          {"name":"Sheikh","category_code":"cigarette","unit":"pack","target_qty":"20.000","achieved_qty":"3.000","tilldate_target_qty":"10.000","target_qty_base":400,"achieved_qty_base":60,"tilldate_target_qty_base":200},
          {"name":"Gold","category_code":"bidi","unit":"stick","target_qty":"30.000","achieved_qty":"3.000","tilldate_target_qty":"15.000","target_qty_base":30,"achieved_qty_base":3,"tilldate_target_qty_base":15},
          {"name":"Old","category_code":"lighter","target_qty_base":50,"achieved_qty_base":5,"tilldate_target_qty_base":25}]}""")!!
        val cards = TeamTargets.categoryCards(node.rows, node.periodDays).associateBy { it.categoryCode }
        val cig = cards.getValue("cigarette")
        assertEquals("packet", cig.unit); assertEquals("30", TeamTargets.targetText(cig, PerfTab.MONTHLY, UiLocale.EN)); assertEquals("7.5", TeamTargets.achievedText(cig, UiLocale.EN)) // 10 + 20 and 4.5 + 3: the converted rows, never 500 sticks over one factor
        assertEquals("15", TeamTargets.targetText(cig, PerfTab.TILL_DATE, UiLocale.EN)); assertEquals("22.5", TeamTargets.remainingText(cig, PerfTab.MONTHLY, UiLocale.EN)); assertEquals("7.5/30", TeamTargets.fraction(cig, PerfTab.MONTHLY, UiLocale.EN))
        assertEquals("stick", TeamTargets.unitKey(cards.getValue("bidi")))
        val old = cards.getValue("lighter") // no field-unit figure from the server: base units with the base unit word, nothing converted
        assertEquals("piece", TeamTargets.unitKey(old)); assertEquals("50", TeamTargets.targetText(old, PerfTab.MONTHLY, UiLocale.EN)); assertEquals("45", TeamTargets.remainingText(old, PerfTab.MONTHLY, UiLocale.EN))
        // a card whose rows differ in unit is not summed across units: base figures with the base word
        val mixed = TeamTargets.categoryCards(listOf(node.rows[0], node.rows[0].copy(unit = "box")), 30).single()
        assertNull(mixed.qty); assertEquals("stick", TeamTargets.unitKey(mixed)); assertEquals(200L, mixed.target)
    }

    @Test fun stdColumnsAreSumsOfConvertedRowsWithTheirUnitWord() {
        fun row(route: String, cat: String, sku: String, q: Long, f: Long?): ReportRow = buildMap<String, JsonPrimitive> {
            put("route_id", JsonPrimitive(if (route == "A") 1 else 2)); put("route_code", JsonPrimitive(route)); put("route_name", JsonPrimitive("Route $route")); put("category_name", JsonPrimitive(cat))
            put("sku_code", JsonPrimitive(sku)); put("base_unit", JsonPrimitive("stick")); put("entry_unit", JsonPrimitive("pack")); if (f != null) put("base_per_pack", JsonPrimitive(f)); put("sold_qty_base", JsonPrimitive(q)); put("gross_mtk", JsonPrimitive(0))
        }
        val rows = listOf(row("A", "Cigarette", "s10", 100, 10), row("A", "Cigarette", "s20", 200, 20), row("B", "Cigarette", "s20", 50, 20), row("A", "Bidi", "b1", 100, 10), row("B", "Bidi", "b2", 100, null))
        val m = TeamReports.stdMatrix("Z", Reports.routeCategories(ReportData("d", emptyList(), rows, rows.size)))
        assertTrue(m.isField("cigarette")); assertEquals("packet", m.units["cigarette"])
        assertEquals("20", m.cellText(m.rows[0], "cigarette", UiLocale.EN)); assertEquals("2.5", m.cellText(m.rows[1], "cigarette", UiLocale.EN)); assertEquals("22.5", m.totalText("cigarette", UiLocale.EN)) // 10 + 10, 2.5; the total adds converted rows
        assertFalse(m.isField("bidi")); assertEquals("stick", m.units["bidi"]); assertEquals("100", m.cellText(m.rows[0], "bidi", UiLocale.EN)); assertEquals("200", m.totalText("bidi", UiLocale.EN)) // one SKU lacks its factor: the whole column stays base
    }

    @Test fun liveDataCategoryShowsTheServersEnteredFigureElseBase() {
        val d = LiveDataParser.parse("""{"as_of":"x","kpis":{},"sales_by_category":[
          {"category_code":"cigarette","base_unit":"stick","qty_base":450,"entry_unit":"pack","qty_entered":"22.500"},
          {"category_code":"lighter","base_unit":"piece","qty_base":50},
          {"category_code":"match","base_unit":"dozen","qty_base":3,"entry_unit":"dozen","qty_entered":"3.000"}]}""")!!
        val by = d.categories.associateBy { it.code }
        assertEquals("22.5" to "packet", by.getValue("cigarette").field()!!.let { FieldQty.plain(it.first) to it.second })
        assertNull(by.getValue("lighter").field()) // base 50 pieces with the piece word
        assertEquals("3" to "dozen", by.getValue("match").field()!!.let { FieldQty.plain(it.first) to it.second })
    }
}
