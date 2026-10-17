package com.aktcl.aron.feature.amo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskDelegationTest {
    private fun task(id: String, by: Long, at: String, status: String = "ongoing") =
        """{"task_uuid":"$id","task_type_code":"stock_check","title":"T $id","assignee_user_id":7,"assigned_by_user_id":$by,"status":"$status","created_at":"$at","source":"app","due_date":"2026-10-18"}"""

    @Test fun listKeepsOnlyMyTasksNewestFirst() {
        val page = """{"items":[${task("a", 9, "2026-10-17T01:00:00Z")},${task("b", 8, "2026-10-17T02:00:00Z")},${task("c", 9, "2026-10-17T03:00:00Z", "completed")}],"next_cursor":null}"""
        assertEquals(listOf("c", "a"), TaskDelegation.assignedBy(page, 9).map { it.uuid })
        assertTrue(TaskDelegation.assignedBy("garbage", 9).isEmpty())
    }

    @Test fun mergeShowsPhoneOnlyTasksFirstAndNeverTwice() {
        val s = TaskDelegation.assignedBy("""{"items":[${task("a", 9, "2026-10-17T01:00:00Z")}]}""", 9)
        val l = listOf(s[0], s[0].copy(uuid = "z", createdAt = "2026-10-18T00:00:00Z"))
        val m = TaskDelegation.merge(s, l)
        assertEquals(listOf("z", "a"), m.map { it.uuid }); assertEquals(listOf(true, false), m.map { it.waiting })
    }

    @Test fun teamKeepsSrsOnly() {
        val j = """{"team":[{"user_id":2,"username":"sr2","full_name":"Zed","role":"SR","route_ids":[5,6]},{"user_id":1,"username":"sr1","full_name":"Amin","role":"SR","route_ids":[]},{"user_id":3,"role":"TSO"}]}"""
        assertEquals(listOf(1L, 2L), TaskDelegation.team(j).map { it.userId }); assertEquals(listOf(5L, 6L), TaskDelegation.team(j)[1].routeIds)
    }

    @Test fun typesFromTheCodeList() {
        val j = """[{"list_key":"task_type","items":[{"code":"stock_check","label_en":"Stock check","label_bn":"স্টক","sort":1}]}]"""
        assertEquals(listOf(TaskType("stock_check", "Stock check", "স্টক")), TaskDelegation.types(j)); assertTrue(TaskDelegation.types(null).isEmpty())
    }

    @Test fun assignNeedsAnSrATypeAnOutletAndANotPastDate() {
        assertEquals(setOf(AssignError.ASSIGNEE_REQUIRED, AssignError.TYPE_REQUIRED, AssignError.OUTLET_REQUIRED), TaskDelegation.validate(null, null, null, null, null, "2026-10-18"))
        assertEquals(setOf(AssignError.DATE_INVALID), TaskDelegation.validate(1, "x", 2, "2026-10-17", null, "2026-10-18"))
        assertEquals(setOf(AssignError.DATE_INVALID), TaskDelegation.validate(1, "x", 2, "nope", null, "2026-10-18"))
        assertEquals(setOf(AssignError.TEXT_TOO_LONG), TaskDelegation.validate(1, "x", 2, "2026-10-18", "a".repeat(1001), "2026-10-18"))
        assertTrue(TaskDelegation.validate(1, "x", 2, "2026-10-18", "ok", "2026-10-18").isEmpty())
    }

    @Test fun titleAndChoices() {
        assertEquals(120, TaskDelegation.title("T".repeat(100), "S".repeat(100)).length)
        assertEquals("Stock check", TaskDelegation.title("Stock check", null))
        assertEquals(listOf("2026-10-30", "2026-10-31", "2026-11-01"), TaskDelegation.dueChoices("2026-10-30", 3))
    }
}
