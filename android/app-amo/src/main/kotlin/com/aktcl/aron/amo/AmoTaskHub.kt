package com.aktcl.aron.amo

import com.aktcl.aron.feature.amo.AmoFrame
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
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
import com.aktcl.aron.feature.amo.TaskFormPageContent
import com.aktcl.aron.feature.amo.TaskOutletInfo
import com.aktcl.aron.feature.amo.TeamMember
import com.aktcl.aron.feature.amo.TaskDelegation
import com.aktcl.aron.feature.amo.TaskType
import com.aktcl.aron.feature.amo.TeamSr
import com.aktcl.aron.feature.outlet.OutletPicker
import com.aktcl.aron.feature.outlet.OutletPickerContent
import com.aktcl.aron.feature.amo.R as AmoR
import kotlinx.coroutines.launch

private enum class TaskLeaf { LIST, FORM }

/**
 * Task Delegation (F-AMO-018 list, F-AMO-019 assign) as screenshots 37 and 38: the list with the orange +, and one form page (route, outlet,
 * type, date, description). The SR comes from the route (members list); the SR's phone is in clear for the AMO's own zone. Assigning queues a
 * `task` record offline; the list then shows the lime "Save Successfully!" banner for a few seconds.
 */
@Composable
fun AmoTaskHub(day: AmoDay, onBack: () -> Unit) {
    var leaf by rememberSaveable { mutableStateOf(TaskLeaf.LIST) }
    var saved by remember { mutableStateOf(false) }
    var team by remember { mutableStateOf<List<TeamSr>>(emptyList()) }
    var days by remember { mutableStateOf<List<RouteDay>?>(null) }
    val members = rememberMembers(day)
    LaunchedEffect(day) {
        team = runCatching { day.teamSrs() }.getOrDefault(emptyList())
        days = runCatching { day.routes() }.getOrDefault(emptyList())
    }
    LaunchedEffect(saved) { if (saved) { kotlinx.coroutines.delay(3000); saved = false } }
    val back: () -> Unit = { if (leaf == TaskLeaf.FORM) leaf = TaskLeaf.LIST else onBack() }
    BackHandler(onBack = back)
    AmoFrame(stringResource(if (leaf == TaskLeaf.FORM) AmoR.string.amo_team_task_form_title else AmoR.string.amo_task_title), back, icon = com.aktcl.aron.core.ui.R.drawable.aron_art_task_delegation) {
        val routeDays = days ?: run { com.aktcl.aron.core.ui.aron.AronLoadingScreen(); return@AmoFrame }
        when (leaf) {
            TaskLeaf.LIST -> AmoTaskList(day, team, routeDays, saved, onAssign = { leaf = TaskLeaf.FORM })
            TaskLeaf.FORM -> AmoTaskForm(day, routeDays, members?.rows.orEmpty(), team, onDone = { saved = true; leaf = TaskLeaf.LIST })
        }
    }
}

@Composable
private fun AmoTaskList(day: AmoDay, team: List<TeamSr>, days: List<RouteDay>, saved: Boolean, onAssign: () -> Unit) {
    var tasks by remember { mutableStateOf<List<AssignedTask>?>(null) }
    var stale by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var types by remember { mutableStateOf<List<TaskType>>(emptyList()) }
    val bangla = androidx.compose.ui.platform.LocalConfiguration.current.locales[0].language == "bn"
    LaunchedEffect(day, saved) {
        types = runCatching { day.taskTypes() }.getOrDefault(emptyList())
        val local = runCatching { day.localTasks() }.getOrDefault(emptyList())
        val from = java.time.LocalDate.parse(day.businessDate()).minusDays(30).toString()
        val fetched = day.api.tasks(from)
        val json = fetched.jsonOrNull()
        stale = fetched is AmoFetch.Cached; failed = fetched !is AmoFetch.Fresh
        tasks = TaskDelegation.merge(TaskDelegation.assignedBy(json, day.userId), local)
    }
    val list = tasks ?: run { com.aktcl.aron.core.ui.aron.AronLoadingScreen(); return }
    val outletNames = remember(days, day.zoneOutlets.size) { (days.flatMap { it.outlets } + day.zoneOutlets).associate { it.outletId to it.name } }
    AssignedTasksContent(
        list, team.associate { it.userId to it.name }, outletNames,
        { code -> types.firstOrNull { it.code == code }?.let { if (bangla) it.labelBn ?: it.labelEn else it.labelEn }.orEmpty() },
        stale = stale, loadFailed = failed && !stale, onAssign = onAssign, today = day.businessDate(), saved = saved,
    )
}

@Composable
private fun AmoTaskForm(day: AmoDay, days: List<RouteDay>, members: List<TeamMember>, team: List<TeamSr>, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    var types by remember { mutableStateOf<List<TaskType>?>(null) }
    LaunchedEffect(day) { types = runCatching { day.taskTypes() }.getOrDefault(emptyList()) }
    val today = remember { day.businessDate() }
    var routeId by rememberSaveable { mutableStateOf<Long?>(null) }
    var outletId by rememberSaveable { mutableStateOf<Long?>(null) }
    var letter by rememberSaveable { mutableStateOf<String?>(null) }
    var typeCode by rememberSaveable { mutableStateOf<String?>(null) }
    var due by rememberSaveable { mutableStateOf(today) }
    var description by rememberSaveable { mutableStateOf("") }
    var errors by remember { mutableStateOf<Set<AssignError>>(emptySet()) }
    var busy by remember { mutableStateOf(false) }
    val bangla = androidx.compose.ui.platform.LocalConfiguration.current.locales[0].language == "bn"
    val failedText = stringResource(AmoR.string.amo_task_failed)
    val context = androidx.compose.ui.platform.LocalContext.current
    val loaded = types ?: run { com.aktcl.aron.core.ui.aron.AronLoadingScreen(); return }
    val routes = ControlCall.routes(days)
    val allOutlets = routeId?.let { ControlCall.outlets(days, it).filter { o -> o.status == "active" }.sortedBy { o -> o.name.lowercase() } }.orEmpty()
    val outlets = allOutlets.filter { letter == null || it.name.trim().uppercase().startsWith(letter!!) }
    val outlet = allOutlets.firstOrNull { it.outletId == outletId }
    val member = routeId?.let { r -> members.firstOrNull { it.routeId == r && !it.vacant } }
    // the online members list names the SR of the day; without it (offline, first open) the bundle's team decides, as before
    val sr = member?.userId?.let { id -> team.firstOrNull { it.userId == id } } ?: routeId?.let { r -> TaskDelegation.srsOfRoute(team, r).singleOrNull() }
    // Keyed by route and outlet only (the SR may change when the members list arrives, which must not change the record): a double tap or a retry queues one task.
    val draftKey = TaskDelegation.draftKey(routeId, outletId)
    val uuid = remember(draftKey) { day.drafts.uuid(draftKey) }
    TaskFormPageContent(
        routes = routes.map { it.routeId.toString() to it.title }, routeText = routes.firstOrNull { it.routeId == routeId }?.title,
        onRoute = { routeId = it.toLong(); outletId = null; letter = null },
        outlets = outlets.map { it.outletId.toString() to (it.name + it.clusterName.takeIf { c -> c.isNotBlank() }?.let { c -> " ($c)" }.orEmpty()) },
        outletText = outlet?.let { it.name + it.clusterName.takeIf { c -> c.isNotBlank() }?.let { c -> " ($c)" }.orEmpty() }, onOutlet = { outletId = it.toLong() },
        letters = TaskDelegation.letters(allOutlets.map { it.name }), letter = letter, onLetter = { letter = it },
        info = outlet?.let { TaskOutletInfo(it.name, it.ownerName.takeIf { o -> o.isNotBlank() }, member?.name ?: sr?.name, member?.phone) },
        types = loaded, bangla = bangla, typeCode = typeCode, onType = { typeCode = it },
        due = due, today = today, onDue = { due = it }, description = description, onDescription = { description = it },
        errors = errors, busy = busy, noSr = outlet != null && sr == null,
        onSave = {
            val e = TaskDelegation.validateForm(routeId, outletId, sr?.userId, typeCode, due, description, today)
            errors = e
            if (e.isEmpty() && !busy) {
                busy = true
                scope.launch {
                    val type = loaded.first { it.code == typeCode }
                    val ok = try {
                        // A false answer means this form's task is already queued (a retry after a kill): the banner is true either way.
                        day.assignTask(uuid, type.code, sr!!.userId, outlet!!.outletId, TaskDelegation.title(type.labelEn, outlet.name), description.trim(), due)
                        true
                    } catch (c: kotlinx.coroutines.CancellationException) { throw c } catch (x: Exception) { false }
                    busy = false
                    if (ok) { day.drafts.clear(draftKey); onDone() } else android.widget.Toast.makeText(context, failedText, android.widget.Toast.LENGTH_SHORT).show()
                }
            }
        },
    )
}
