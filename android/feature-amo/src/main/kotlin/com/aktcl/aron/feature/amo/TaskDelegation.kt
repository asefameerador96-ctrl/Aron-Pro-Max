package com.aktcl.aron.feature.amo

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import java.time.LocalDate

data class TeamSr(val userId: Long, val name: String, val routeIds: List<Long>)
data class TaskType(val code: String, val labelEn: String, val labelBn: String?)

/** A task the AMO assigned: from the server, or still only on this phone ([waiting]). */
data class AssignedTask(
    val uuid: String, val typeCode: String, val title: String, val outletId: Long?, val assigneeId: Long, val status: String,
    val dueDate: String?, val resolvedAt: String?, val resolutionNote: String?, val createdAt: String, val waiting: Boolean = false,
    /** The server refused the task (for example the SR is outside the AMO's reach): it never reaches the SR. */
    val rejected: Boolean = false,
)

enum class AssignError { ASSIGNEE_REQUIRED, TYPE_REQUIRED, OUTLET_REQUIRED, DATE_INVALID, TEXT_TOO_LONG }

/** Pure rules of Task Delegation (F-AMO-018/019). The assign form queues a `task` record offline; the list reads the server with a kept copy. */
object TaskDelegation {
    const val MAX_DESCRIPTION = 1000
    const val MAX_TITLE = 120

    private fun str(o: JsonObject, k: String) = (o[k] as? JsonPrimitive)?.contentOrNull
    private fun lng(o: JsonObject, k: String) = (o[k] as? JsonPrimitive)?.longOrNull

    /** The SRs under this AMO with their routes (the bundle's `supervisor.team`). */
    fun team(supervisorJson: String?): List<TeamSr> {
        val o = runCatching { Json.parseToJsonElement(supervisorJson ?: return emptyList()) as? JsonObject }.getOrNull() ?: return emptyList()
        return (o["team"] as? JsonArray).orEmpty().mapNotNull { e ->
            val m = e as? JsonObject ?: return@mapNotNull null
            if (str(m, "role") != "SR") return@mapNotNull null
            TeamSr(lng(m, "user_id") ?: return@mapNotNull null, str(m, "full_name") ?: str(m, "username") ?: return@mapNotNull null,
                (m["route_ids"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.longOrNull })
        }.sortedBy { it.name }
    }

    /** The `task_type` code list. */
    fun types(codeListsJson: String?): List<TaskType> {
        val a = runCatching { Json.parseToJsonElement(codeListsJson ?: return emptyList()) as? JsonArray }.getOrNull() ?: return emptyList()
        val list = a.firstOrNull { (it as? JsonObject)?.let { l -> str(l, "list_key") } == "task_type" } as? JsonObject ?: return emptyList()
        return (list["items"] as? JsonArray).orEmpty().mapNotNull { e ->
            val i = e as? JsonObject ?: return@mapNotNull null
            TaskType(str(i, "code") ?: return@mapNotNull null, str(i, "label_en") ?: return@mapNotNull null, str(i, "label_bn"))
        }
    }

    /** Tasks this AMO assigned, newest first, from a `GET /v1/tasks` page. */
    /** `next_cursor` of a `GET /v1/tasks` page, or null on the last page. */
    fun nextCursor(pageJson: String?): String? = runCatching { ((Json.parseToJsonElement(pageJson ?: return null) as? JsonObject)?.get("next_cursor") as? JsonPrimitive)?.contentOrNull }.getOrNull()

    /** One list from several pages: the items of all of them. */
    fun combine(pages: List<String>): String = JsonObject(mapOf("items" to JsonArray(pages.flatMap { p -> ((runCatching { Json.parseToJsonElement(p) as? JsonObject }.getOrNull())?.get("items") as? JsonArray).orEmpty() }))).toString()

    fun assignedBy(pageJson: String?, ownUserId: Long): List<AssignedTask> {
        val o = runCatching { Json.parseToJsonElement(pageJson ?: return emptyList()) as? JsonObject }.getOrNull() ?: return emptyList()
        return (o["items"] as? JsonArray).orEmpty().mapNotNull { e ->
            val t = e as? JsonObject ?: return@mapNotNull null
            if (lng(t, "assigned_by_user_id") != ownUserId) return@mapNotNull null
            AssignedTask(
                str(t, "task_uuid") ?: return@mapNotNull null, str(t, "task_type_code").orEmpty(), str(t, "title").orEmpty(), lng(t, "outlet_id"),
                lng(t, "assignee_user_id") ?: return@mapNotNull null, str(t, "status") ?: "ongoing", str(t, "due_date"), str(t, "resolved_at"),
                str(t, "resolution_note"), str(t, "created_at").orEmpty(),
            )
        }.sortedByDescending { it.createdAt }
    }

    /** The server's tasks plus those only on this phone (not in the server list yet), newest phone-only ones first; each keeps its own waiting/rejected state. */
    fun merge(server: List<AssignedTask>, local: List<AssignedTask>): List<AssignedTask> {
        val known = server.map { it.uuid }.toSet()
        return local.filter { it.uuid !in known }.sortedByDescending { it.createdAt } + server
    }

    /** The assign form's rules: an SR, a type, an outlet, a due date from [today] on, and the text within its limit. */
    fun validate(assigneeId: Long?, typeCode: String?, outletId: Long?, dueDate: String?, description: String?, today: String): Set<AssignError> = buildSet {
        if (assigneeId == null) add(AssignError.ASSIGNEE_REQUIRED)
        if (typeCode == null) add(AssignError.TYPE_REQUIRED)
        if (outletId == null) add(AssignError.OUTLET_REQUIRED)
        val d = dueDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        if (dueDate != null && (d == null || d.isBefore(LocalDate.parse(today)))) add(AssignError.DATE_INVALID)
        if ((description?.length ?: 0) > MAX_DESCRIPTION) add(AssignError.TEXT_TOO_LONG)
    }

    /** The title the SR sees: the type label and the shop, within the contract's 120 characters. */
    fun title(typeLabel: String, outletName: String?): String = (if (outletName.isNullOrBlank()) typeLabel else "$typeLabel - $outletName").take(MAX_TITLE)

    /** The next [days] business dates from [today] (the picker offers them as chips; no calendar needed offline). */
    fun dueChoices(today: String, days: Int = 7): List<String> = LocalDate.parse(today).let { d -> (0 until days).map { d.plusDays(it.toLong()).toString() } }
}
