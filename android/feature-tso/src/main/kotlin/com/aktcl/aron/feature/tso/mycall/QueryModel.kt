package com.aktcl.aron.feature.tso.mycall

import java.time.LocalDate
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/** One yes/no question of the retailer questionnaire rubric (bundle `rubrics`, kind `retailer_questionnaire`). */
data class Criterion(val id: Long, val labelBn: String?, val labelEn: String) {
    fun label(bangla: Boolean) = if (bangla && !labelBn.isNullOrBlank()) labelBn else labelEn
}

data class Rubric(val id: Long, val version: Int, val criteria: List<Criterion>)

object RubricJson {
    /** The newest enabled-criteria `retailer_questionnaire` rubric out of the bundle section; only bool criteria are asked. */
    fun retailer(section: String?): Rubric? = runCatching {
        val a = JSONArray(section ?: return null)
        // the newest retailer_questionnaire version that actually has bool questions (a newer one without them must not hide a usable older one)
        (0 until a.length()).map { a.getJSONObject(it) }.filter { it.optString("kind") == "retailer_questionnaire" }.mapNotNull { r ->
            val c = r.getJSONArray("criteria")
            val crit = (0 until c.length()).map { c.getJSONObject(it) }.filter { (!it.has("enabled") || it.optBoolean("enabled", true)) && it.optString("answer_type") == "bool" }
                .mapNotNull { runCatching { Criterion(it.getLong("criterion_id"), if (it.isNull("label_bn")) null else it.optString("label_bn").ifBlank { null }, it.getString("label_en").also { l -> require(l.isNotBlank()) }) }.getOrNull() }
            if (crit.isEmpty()) null else Rubric(r.getLong("rubric_id"), r.getInt("version"), crit)
        }.maxByOrNull { it.version }
    }.getOrNull()
}

data class QuerySubmit(val planUuid: String, val outletId: Long, val routeId: Long, val rubric: Rubric, val answers: Map<Long, Boolean>, val delegate: Boolean)

/** One assessment per plan outlet: its uuid and each answer's uuid are derived, so a repeat Submit changes nothing. */
object QueryIds {
    fun task(planUuid: String, outletId: Long) = v3("aron.tso.query-task:$planUuid:$outletId")
    fun assessment(planUuid: String, outletId: Long) = v3("aron.tso.query:$planUuid:$outletId")
    fun answer(assessmentUuid: String, criterionId: Long) = v3("aron.tso.query-answer:$assessmentUuid:$criterionId")
    private fun v3(name: String) = com.aktcl.aron.feature.tso.StableIds.v4(name)
}

/** Queues call_assessment + its answers (one family) and marks the plan outlet completed locally, in one transaction. A false answer means the same assessment was already queued: the screen treats it as done. */
fun interface QueryQueue { suspend fun submit(q: QuerySubmit): Boolean }

sealed interface QueryResult {
    data object Editing : QueryResult
    /** [delegate]: the rep said Yes to "Delegate task?": the host opens Assign Task next. */
    data class Submitted(val delegate: Boolean) : QueryResult
    data object Unanswered : QueryResult
    data object Failed : QueryResult
}

/** F-TSO-015: the printed questions, the delegate radio defaulting to No, Submit with no GPS or photo. */
class VisitQueryState(private val rubric: Rubric, private val planUuid: String, private val outletId: Long, private val routeId: Long, private val queue: QueryQueue) {
    private val _s = MutableStateFlow(Ui())
    val ui: StateFlow<Ui> = _s.asStateFlow()
    val criteria: List<Criterion> get() = rubric.criteria
    private var busy = false

    data class Ui(val answers: Map<Long, Boolean> = emptyMap(), val delegate: Boolean = false, val result: QueryResult = QueryResult.Editing)

    fun answer(criterionId: Long, yes: Boolean) { if (_s.value.result is QueryResult.Submitted) return; _s.value = _s.value.copy(answers = _s.value.answers + (criterionId to yes), result = QueryResult.Editing) }
    fun delegate(yes: Boolean) { if (_s.value.result is QueryResult.Submitted) return; _s.value = _s.value.copy(delegate = yes, result = QueryResult.Editing) }

    suspend fun submit() {
        val s = _s.value
        if (busy || s.result is QueryResult.Submitted) return
        if (rubric.criteria.any { it.id !in s.answers }) { _s.value = s.copy(result = QueryResult.Unanswered); return }
        busy = true
        try {
            queue.submit(QuerySubmit(planUuid, outletId, routeId, rubric, s.answers, s.delegate))
            _s.value = _s.value.copy(result = QueryResult.Submitted(s.delegate))
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { _s.value = _s.value.copy(result = QueryResult.Failed) } finally { busy = false }
    }
}

// ---- F-TSO-016 Assign Task ----

data class TaskType(val code: String, val labelBn: String?, val labelEn: String, val sort: Int) { fun label(bangla: Boolean) = if (bangla && !labelBn.isNullOrBlank()) labelBn else labelEn }

private val TASK_CODE = Regex("^[a-z][a-z0-9_]{1,40}$")

object TaskTypes {
    fun parse(codeLists: String?): List<TaskType> = runCatching {
        val a = JSONArray(codeLists ?: return emptyList())
        (0 until a.length()).map { a.getJSONObject(it) }.firstOrNull { it.optString("list_key") == "task_type" }?.getJSONArray("items")?.let { items ->
            (0 until items.length()).mapNotNull { i -> runCatching { items.getJSONObject(i).let { o ->
                require(TASK_CODE.matches(o.getString("code")) && o.getString("label_en").isNotBlank())
                TaskType(o.getString("code"), if (o.isNull("label_bn")) null else o.optString("label_bn").ifBlank { null }, o.getString("label_en"), o.optInt("sort", 0)) } }.getOrNull() }
                .sortedWith(compareBy<TaskType> { it.sort }.thenBy { it.code })
        } ?: emptyList()
    }.getOrDefault(emptyList())
}

const val MAX_TASK_COMMENT = 1000
const val MAX_TASK_TITLE = 120

data class TaskSubmit(val planUuid: String?, val uuid: String, val typeCode: String, val title: String, val assigneeUserId: Long, val routeId: Long, val outletId: Long, val dueDate: String, val comment: String?)
/** false = a task with this uuid was already queued (the rep re-opened Assign Task): it is shown as assigned, never silently re-edited. */
fun interface TaskQueue { suspend fun create(t: TaskSubmit): Boolean }

enum class TaskError { NO_TYPE, DATE_FORMAT, DATE_PAST, DATE_FAR, COMMENT_LONG }

sealed interface TaskResult {
    data object Editing : TaskResult
    data object Assigned : TaskResult
    /** The route shows "SR Not Set": nobody to assign to, blocked with a message. */
    data object NoSr : TaskResult
    data class Invalid(val errors: List<TaskError>) : TaskResult
    data object Failed : TaskResult
}

fun validateTask(typeCode: String?, types: List<TaskType>, date: String, comment: String, today: String): List<TaskError> {
    val e = mutableListOf<TaskError>()
    if (typeCode == null || types.none { it.code == typeCode }) e += TaskError.NO_TYPE
    val d = runCatching { LocalDate.parse(date) }.getOrNull()
    if (d == null) e += TaskError.DATE_FORMAT else {
        val t = LocalDate.parse(today)
        if (d.isBefore(t)) e += TaskError.DATE_PAST
        if (d.isAfter(t.plusDays(MAX_PLAN_AHEAD_DAYS))) e += TaskError.DATE_FAR
    }
    if (comment.trim().length > MAX_TASK_COMMENT) e += TaskError.COMMENT_LONG
    return e
}

/** F-TSO-016: type, date and comment; the assignee is the SR active on the route (never chosen by the client from a free list). */
class AssignTaskState(
    private val types: List<TaskType>, private val route: PlanRoute?, private val outletId: Long, private val queue: TaskQueue, private val today: () -> String,
    private val planUuid: String? = null,
    /** From a Visit Query: one task per plan outlet, so re-opening Assign Task can never create a second one. */
    taskUuid: String? = null,
    private val newUuid: () -> String = { UUID.randomUUID().toString() },
) {
    private val uuid = taskUuid ?: newUuid()
    private var busy = false
    private val _s = MutableStateFlow(Ui(date = today(), result = if (route?.sr == null) TaskResult.NoSr else TaskResult.Editing))
    val ui: StateFlow<Ui> = _s.asStateFlow()
    val typeList: List<TaskType> get() = types
    val srName: String? get() = route?.sr?.fullName

    data class Ui(val typeCode: String? = null, val date: String, val comment: String = "", val result: TaskResult)

    fun pickType(code: String) = edit { it.copy(typeCode = code) }
    fun setDate(d: String) = edit { it.copy(date = d.take(10)) }
    fun setComment(c: String) = edit { it.copy(comment = c) }
    private fun edit(f: (Ui) -> Ui) { val s = _s.value; if (s.result is TaskResult.NoSr || s.result is TaskResult.Assigned) return; _s.value = f(s).copy(result = TaskResult.Editing) }

    suspend fun submit() {
        val s = _s.value; val sr = route?.sr
        if (sr == null) { _s.value = s.copy(result = TaskResult.NoSr); return }
        if (busy || s.result is TaskResult.Assigned) return
        val errors = validateTask(s.typeCode, types, s.date, s.comment, today())
        if (errors.isNotEmpty()) { _s.value = s.copy(result = TaskResult.Invalid(errors)); return }
        busy = true
        try {
            val type = types.first { it.code == s.typeCode }
            queue.create(TaskSubmit(planUuid, uuid, type.code, type.labelEn.take(MAX_TASK_TITLE), sr.userId, route.id, outletId, s.date, s.comment.trim().ifEmpty { null }))
            _s.value = _s.value.copy(result = TaskResult.Assigned)
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { _s.value = _s.value.copy(result = TaskResult.Failed) } finally { busy = false }
    }
}
