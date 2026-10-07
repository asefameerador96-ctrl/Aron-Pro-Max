package com.aktcl.aron.feature.tasks

import com.aktcl.aron.core.database.entity.CaptureMeta
import com.aktcl.aron.core.database.entity.TaskEntity
import com.aktcl.aron.core.database.entity.TaskEventEntity
import com.aktcl.aron.core.database.repo.CaptureRepository
import com.aktcl.aron.core.database.repo.ReferenceRepository

/** Task rows as the screen shows them; the outlet name is looked up from the route's outlets by the caller. */
fun TaskEntity.toItem(outletName: String?) = TaskItem(
    taskUuid = taskUuid, taskTypeCode = taskTypeCode, title = title, description = description, outletId = outletId,
    outletName = outletName, dueDate = dueDate, status = status, resolvedAt = resolvedAt,
)

/**
 * Room binding of [TaskStore] (F-SR-046/047): tasks come from the bundle (a local resolution is kept until its event is
 * acked); Resolve is one transaction that completes the task and queues the `task_event` (CaptureRepository.resolveTask).
 */
class RoomTaskStore(
    private val reference: ReferenceRepository,
    private val capture: CaptureRepository,
    private val outletName: suspend (Long) -> String?,
    private val metaFor: () -> CaptureMeta,
    private val syncedAtIso: suspend () -> String?,
) : TaskStore {
    override suspend fun tasks(): List<TaskItem> = reference.tasks().map { it.toItem(it.outletId?.let { id -> outletName(id) }) }

    override suspend fun resolve(taskUuid: String, resolvedAt: String, write: TaskEventWrite) {
        capture.resolveTask(TaskEventEntity(write.eventUuid, metaFor(), taskUuid, write.event, write.note), resolvedAt)
    }

    override suspend fun syncedAt(): String? = syncedAtIso()
}
