package com.aktcl.aron.feature.amo

import com.aktcl.aron.rules.UiLocale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
}
