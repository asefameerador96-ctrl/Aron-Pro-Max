package com.aktcl.aron.feature.tasks

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** F-SR-046 and F-SR-047 over an in-memory store (airplane mode by construction). */
class TaskBoardTest {
    private class Mem(var rows: List<TaskItem>, var synced: String? = "2026-10-07T03:00:00.000Z") : TaskStore {
        val queue = mutableListOf<TaskEventWrite>()
        override suspend fun tasks() = rows
        override suspend fun resolve(taskUuid: String, resolvedAt: String, write: TaskEventWrite) {
            rows = rows.map { if (it.taskUuid == taskUuid) it.copy(status = "completed", resolvedAt = resolvedAt) else it }
            queue += write
        }
        override suspend fun syncedAt() = synced
    }

    private fun t(id: String, status: String = "ongoing", due: String? = "2026-10-09") =
        TaskItem(id, "visit_outlet", "Visit $id", null, 50001, "Rahim Store", due, status, null)

    @Test fun emptyStateWithSyncedAt() = runTest {
        val b = TaskBoard(Mem(emptyList()), { "now" }); b.load()
        assertTrue(b.state.value.showEmptyState); assertEquals("2026-10-07T03:00:00.000Z", b.state.value.syncedAt); assertEquals(0, b.state.value.openCount)
    }

    @Test fun badgeIsTheOpenCountAndResolvedTasksStay() = runTest {
        val b = TaskBoard(Mem(listOf(t("a"), t("b"), t("c", "completed"), t("d", "cancelled"))), { "now" }); b.load()
        assertEquals(3, b.state.value.items.size); assertEquals(2, b.state.value.openCount)
        assertEquals(listOf("a", "b", "c"), b.state.value.items.map { it.taskUuid }) // ongoing first
    }

    @Test fun resolveCompletesOfflineAndQueuesOneEventWithItsOwnUuid() = runTest {
        val m = Mem(listOf(t("a"), t("b")))
        val b = TaskBoard(m, { "2026-10-07T05:00:00.000Z" }, { "00000000-0000-4000-8000-000000000009" }); b.load()
        assertTrue(b.resolve("a", " done "))
        assertEquals(1, m.queue.size)
        val w = m.queue.single()
        assertEquals("resolved", w.event); assertEquals("a", w.taskUuid); assertEquals("00000000-0000-4000-8000-000000000009", w.eventUuid)
        assertEquals(1, b.state.value.openCount)
        assertEquals("completed", b.state.value.items.first { it.taskUuid == "a" }.status)
    }

    @Test fun resolvingTwiceOrAnUnknownTaskQueuesNothingMore() = runTest {
        val m = Mem(listOf(t("a"))); val b = TaskBoard(m, { "now" }); b.load()
        assertTrue(b.resolve("a")); assertFalse(b.resolve("a")); assertFalse(b.resolve("zzz"))
        assertEquals(1, m.queue.size)
    }

    @Test fun overlongNoteIsRejected() = runTest {
        val b = TaskBoard(Mem(listOf(t("a"))), { "now" }); b.load()
        assertTrue(runCatching { b.resolve("a", "x".repeat(501)) }.isFailure)
    }

    @Test fun survivesRelaunchBecauseStateIsReloadedFromTheStore() = runTest {
        val m = Mem(listOf(t("a"))); TaskBoard(m, { "now" }).also { it.load(); it.resolve("a") }
        val again = TaskBoard(m, { "now" }); again.load()
        assertEquals(0, again.state.value.openCount); assertEquals("completed", again.state.value.items.single().status)
    }
}
