package com.aktcl.aron.feature.tasks

import com.aktcl.aron.core.common.ClientIds
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** A task as the phone holds it (contract `Task`): assigned by an AMO or TSO from the bundle or the delta. */
data class TaskItem(
    val taskUuid: String,
    val taskTypeCode: String,
    val title: String,
    val description: String?,
    val outletId: Long?,
    val outletName: String?,
    /** `YYYY-MM-DD` Dhaka business date, the completion (due) date. */
    val dueDate: String?,
    /** `ongoing`, `completed` or `cancelled`. */
    val status: String,
    val resolvedAt: String?,
)

/** The offline write: a `task_event` record keyed by its own client uuid (idempotent on retry). */
data class TaskEventWrite(val eventUuid: String, val taskUuid: String, val event: String, val note: String?, val capturedAt: String)

/** Local store and outbox for tasks; production binds it to Room and the outbox (REQUEST: docs/requests/android-sr-a-task-tables.md). */
interface TaskStore {
    suspend fun tasks(): List<TaskItem>
    /** Marks the task completed and queues [write] in ONE transaction. */
    suspend fun resolve(taskUuid: String, resolvedAt: String, write: TaskEventWrite)
    suspend fun syncedAt(): String?
}

data class TaskListState(
    val items: List<TaskItem> = emptyList(),
    /** The badge on the Home tile: tasks still ongoing. */
    val openCount: Int = 0,
    /** Shown with the empty state so an empty list is not mistaken for a stale one (UI-SR-44). */
    val syncedAt: String? = null,
    val showEmptyState: Boolean = false,
)

/**
 * Task list and swipe-resolve (F-SR-046, F-SR-047). Resolved tasks stay in the list; the badge equals the open count;
 * Resolve works offline and queues a `task_event` with a client uuid. Cancelled tasks are not shown.
 */
class TaskBoard(
    private val store: TaskStore,
    private val nowIso: () -> String,
    private val newUuid: () -> String = ClientIds::newUuid,
) {
    private val ui = MutableStateFlow(TaskListState())
    val state: StateFlow<TaskListState> = ui.asStateFlow()

    suspend fun load() {
        val shown = store.tasks().filter { it.status != "cancelled" }
            .sortedWith(compareBy<TaskItem> { it.status != "ongoing" }.thenBy(nullsLast()) { it.dueDate }.thenBy { it.taskUuid })
        ui.value = TaskListState(shown, shown.count { it.status == "ongoing" }, store.syncedAt(), shown.isEmpty())
    }

    /** Resolve after the swipe and the tap; a second call on a completed task changes nothing and queues nothing. */
    suspend fun resolve(taskUuid: String, note: String? = null): Boolean {
        require(note == null || note.length <= 500) { "note is limited to 500 characters" }
        val task = ui.value.items.firstOrNull { it.taskUuid == taskUuid } ?: return false
        if (task.status != "ongoing") return false
        val at = nowIso()
        store.resolve(taskUuid, at, TaskEventWrite(newUuid(), taskUuid, "resolved", note?.takeIf { it.isNotBlank() }, at))
        load()
        return true
    }
}
