package com.aktcl.aron.amo

import com.aktcl.aron.feature.amo.AmoFrame
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.aktcl.aron.core.database.entity.OutletEntity
import com.aktcl.aron.core.database.repo.RouteDay
import com.aktcl.aron.core.ui.AronScreen
import com.aktcl.aron.core.ui.AronTopBar
import com.aktcl.aron.feature.amo.AmoRoutePick
import com.aktcl.aron.feature.amo.AssignError
import com.aktcl.aron.feature.amo.AssignedTask
import com.aktcl.aron.feature.amo.AssignedTasksContent
import com.aktcl.aron.feature.amo.ControlCall
import com.aktcl.aron.feature.amo.TaskAssignContent
import com.aktcl.aron.feature.amo.TaskDelegation
import com.aktcl.aron.feature.amo.TaskSrPickContent
import com.aktcl.aron.feature.amo.TaskType
import com.aktcl.aron.feature.amo.TeamSr
import com.aktcl.aron.feature.outlet.OutletPicker
import com.aktcl.aron.feature.outlet.OutletPickerContent
import com.aktcl.aron.feature.amo.R as AmoR
import kotlinx.coroutines.launch

private enum class TaskLeaf { LIST, PICK_ROUTE, PICK_OUTLET, PICK_SR, FORM }

/**
 * The Team hub's Task Delegation (F-AMO-018 list, F-AMO-019 assign), in the manual's order: route, then outlet, then the SR (derived
 * from the route: asked only when more than one SR covers it), then type, date and text. Assigning queues a `task` record offline.
 */
@Composable
fun AmoTaskHub(day: AmoDay, onBack: () -> Unit) {
    var leaf by rememberSaveable { mutableStateOf(TaskLeaf.LIST) }
    var srId by rememberSaveable { mutableStateOf<Long?>(null) }
    var routeId by rememberSaveable { mutableStateOf<Long?>(null) }
    var outletId by rememberSaveable { mutableStateOf<Long?>(null) }
    var team by remember { mutableStateOf<List<TeamSr>>(emptyList()) }
    var days by remember { mutableStateOf<List<RouteDay>?>(null) }
    LaunchedEffect(day) {
        team = runCatching { day.teamSrs() }.getOrDefault(emptyList())
        days = runCatching { day.routes() }.getOrDefault(emptyList())
    }
    // After process death the picks that need an earlier pick start over from the route.
    if ((leaf == TaskLeaf.PICK_OUTLET && routeId == null) || (leaf == TaskLeaf.PICK_SR && (routeId == null || outletId == null)) ||
        (leaf == TaskLeaf.FORM && (srId == null || outletId == null))
    ) leaf = TaskLeaf.PICK_ROUTE
    val candidates = TaskDelegation.srsOfRoute(team, routeId ?: -1L)
    val back: () -> Unit = {
        when (leaf) {
            TaskLeaf.LIST -> onBack()
            TaskLeaf.PICK_ROUTE -> leaf = TaskLeaf.LIST
            TaskLeaf.PICK_OUTLET -> leaf = TaskLeaf.PICK_ROUTE
            TaskLeaf.PICK_SR -> leaf = TaskLeaf.PICK_OUTLET
            TaskLeaf.FORM -> leaf = if (candidates.size > 1) TaskLeaf.PICK_SR else TaskLeaf.PICK_OUTLET
        }
    }
    BackHandler(onBack = back)
    val sr = team.firstOrNull { it.userId == srId }
    AmoFrame(stringResource(AmoR.string.amo_task_title), back, icon = com.aktcl.aron.core.ui.R.drawable.aron_ic_task_delegation) {
        when (leaf) {
            TaskLeaf.LIST -> AmoTaskList(day, team, days.orEmpty(), onAssign = { srId = null; routeId = null; outletId = null; leaf = TaskLeaf.PICK_ROUTE })
            TaskLeaf.PICK_ROUTE -> {
                val all = days ?: run { Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }; return@AmoFrame }
                AmoRoutePick(ControlCall.routes(all), { routeId = it.routeId; leaf = TaskLeaf.PICK_OUTLET })
            }
            TaskLeaf.PICK_OUTLET -> {
                var chip by rememberSaveable { mutableStateOf(OutletPicker.ALL_CHIP) }
                val outlets = ControlCall.outlets(days.orEmpty(), routeId ?: -1L)
                val all = OutletPicker.rows(outlets)
                OutletPickerContent(OutletPicker.rows(outlets, chip), OutletPicker.chips(all), chip, { chip = it }, { r ->
                    outletId = r.outlet.outletId
                    // One SR covers the route: that SR gets the task, no question asked.
                    val one = TaskDelegation.srsOfRoute(team, routeId ?: -1L).singleOrNull()
                    if (one != null) { srId = one.userId; leaf = TaskLeaf.FORM } else { srId = null; leaf = TaskLeaf.PICK_SR }
                })
            }
            TaskLeaf.PICK_SR -> TaskSrPickContent(candidates, { srId = it.userId; leaf = TaskLeaf.FORM })
            TaskLeaf.FORM -> {
                val outlet = days.orEmpty().flatMap { it.outlets }.firstOrNull { it.outletId == outletId }
                if (sr == null || outlet == null) Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
                else AmoTaskForm(day, sr, outlet, onDone = { leaf = TaskLeaf.LIST })
            }
        }
    }
}

@Composable
private fun AmoTaskList(day: AmoDay, team: List<TeamSr>, days: List<RouteDay>, onAssign: () -> Unit) {
    var tasks by remember { mutableStateOf<List<AssignedTask>?>(null) }
    var stale by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var types by remember { mutableStateOf<List<TaskType>>(emptyList()) }
    val bangla = androidx.compose.ui.platform.LocalConfiguration.current.locales[0].language == "bn"
    LaunchedEffect(day) {
        types = runCatching { day.taskTypes() }.getOrDefault(emptyList())
        val local = runCatching { day.localTasks() }.getOrDefault(emptyList())
        val from = java.time.LocalDate.parse(day.businessDate()).minusDays(30).toString()
        val fetched = day.api.tasks(from)
        val json = fetched.jsonOrNull()
        stale = fetched is AmoFetch.Cached; failed = fetched !is AmoFetch.Fresh
        tasks = TaskDelegation.merge(TaskDelegation.assignedBy(json, day.userId), local)
    }
    val list = tasks ?: run { Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }; return }
    val outletNames = remember(days, day.zoneOutlets.size) { (days.flatMap { it.outlets } + day.zoneOutlets).associate { it.outletId to it.name } }
    AssignedTasksContent(
        list, team.associate { it.userId to it.name }, outletNames,
        { code -> types.firstOrNull { it.code == code }?.let { if (bangla) it.labelBn ?: it.labelEn else it.labelEn }.orEmpty() },
        stale = stale, loadFailed = failed && !stale, onAssign = onAssign,
    )
}

@Composable
private fun AmoTaskForm(day: AmoDay, sr: TeamSr, outlet: OutletEntity, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    var types by remember { mutableStateOf<List<TaskType>?>(null) }
    LaunchedEffect(day) { types = runCatching { day.taskTypes() }.getOrDefault(emptyList()) }
    val today = remember { day.businessDate() }
    // Fixed when the form opens: a double tap or a retry queues one task.
    val draftKey = "task:${sr.userId}:${outlet.outletId}"
    val uuid = remember(draftKey) { day.drafts.uuid(draftKey) }
    var typeCode by rememberSaveable { mutableStateOf<String?>(null) }
    var due by rememberSaveable { mutableStateOf<String?>(today) }
    var description by rememberSaveable { mutableStateOf("") }
    var errors by remember { mutableStateOf<Set<AssignError>>(emptySet()) }
    var busy by remember { mutableStateOf(false) }
    val bangla = androidx.compose.ui.platform.LocalConfiguration.current.locales[0].language == "bn"
    val failedText = stringResource(AmoR.string.amo_task_failed)
    val context = androidx.compose.ui.platform.LocalContext.current
    val savedText = stringResource(AmoR.string.amo_task_saved)
    val loaded = types ?: run { Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }; return }
    TaskAssignContent(
        srName = sr.name, outletLines = listOfNotNull(outlet.name, outlet.code, outlet.clusterName.takeIf { it.isNotBlank() }, outlet.ownerName.takeIf { it.isNotBlank() }),
        types = loaded, bangla = bangla, typeCode = typeCode, dueChoices = TaskDelegation.dueChoices(today), due = due, description = description,
        errors = errors, busy = busy, noTypes = loaded.isEmpty(), onType = { typeCode = it }, onDue = { due = it }, onDescription = { description = it.take(TaskDelegation.MAX_DESCRIPTION) },
        onSave = {
            val e = TaskDelegation.validate(sr.userId, typeCode, outlet.outletId, due, description.trim().ifEmpty { null }, today)
            errors = e
            if (e.isEmpty() && !busy) {
                busy = true
                scope.launch {
                    val type = loaded.first { it.code == typeCode }
                    val ok = try {
                        // A false answer means this form's task is already queued (a retry after a kill): the toast is true either way.
                        day.assignTask(uuid, type.code, sr.userId, outlet.outletId, TaskDelegation.title(type.labelEn, outlet.name), description.trim().ifEmpty { null }, due)
                        true
                    } catch (c: kotlinx.coroutines.CancellationException) { throw c } catch (x: Exception) { false }
                    busy = false
                    if (ok) { day.drafts.clear(draftKey); android.widget.Toast.makeText(context, savedText, android.widget.Toast.LENGTH_SHORT).show(); onDone() } else android.widget.Toast.makeText(context, failedText, android.widget.Toast.LENGTH_SHORT).show()
                }
            }
        },
    )
}
