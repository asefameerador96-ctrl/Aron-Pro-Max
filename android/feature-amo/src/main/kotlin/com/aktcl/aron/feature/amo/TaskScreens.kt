package com.aktcl.aron.feature.amo

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.aktcl.aron.core.ui.localizedDigits
import com.aktcl.aron.core.ui.AronTokens
import com.aktcl.aron.core.ui.BannerKind
import com.aktcl.aron.core.ui.RowPosition

private fun pos(i: Int, n: Int) = if (n == 1) RowPosition.Only else if (i == 0) RowPosition.First else if (i == n - 1) RowPosition.Last else RowPosition.Middle

@Composable
private fun statusText(t: AssignedTask): String = stringResource(
    when {
        t.rejected -> R.string.amo_task_rejected
        t.waiting -> R.string.amo_task_waiting
        t.status == "completed" -> R.string.amo_task_completed
        t.status == "cancelled" -> R.string.amo_task_cancelled
        else -> R.string.amo_task_ongoing
    },
)

/** F-AMO-018: the tasks the AMO assigned (outlet, text, status, completion date and resolution); [stale] when the list is a kept copy. */
@Composable
fun AssignedTasksContent(
    tasks: List<AssignedTask>, srNames: Map<Long, String>, outletNames: Map<Long, String>, typeLabel: (String) -> String,
    stale: Boolean, loadFailed: Boolean, onAssign: () -> Unit, modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize()) {
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = AronTokens.Space.Screen)) {
            if (loadFailed) AmoBanner(stringResource(R.string.amo_task_list_offline), kind = BannerKind.Warning)
            else if (stale) AmoBanner(stringResource(R.string.amo_task_list_kept), kind = BannerKind.Info)
            if (tasks.isEmpty()) {
                AmoEmptyState(stringResource(R.string.amo_task_empty), Modifier.fillMaxWidth(), hint = stringResource(R.string.amo_task_empty_hint))
            }
            tasks.forEachIndexed { i, t ->
                val lines = listOfNotNull(
                    t.outletId?.let { outletNames[it] }, srNames[t.assigneeId], t.dueDate?.let { stringResource(R.string.amo_task_due, localizedDigits(it)) },
                    t.resolvedAt?.take(10)?.let { stringResource(R.string.amo_task_done_on, localizedDigits(it)) }, t.resolutionNote, t.description,
                )
                AmoListRow(
                    typeLabel(t.typeCode).ifBlank { t.title }, subtitle = lines.joinToString(stringResource(R.string.amo_verify_sep)),
                    trailing = statusText(t), position = pos(i, tasks.size), divider = i < tasks.lastIndex, flat = true,
                )
            }
        }
        Column(Modifier.fillMaxWidth().padding(AronTokens.Space.Screen)) { AmoPrimary(stringResource(R.string.amo_task_assign), onAssign, Modifier.fillMaxWidth()) }
    }
}

@Composable
fun TaskSrPickContent(team: List<TeamSr>, onPick: (TeamSr) -> Unit, modifier: Modifier = Modifier) {
    if (team.isEmpty()) {
        AmoEmptyState(stringResource(R.string.amo_task_no_srs), modifier.fillMaxSize())
        return
    }
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = AronTokens.Space.Screen)) {
        AmoSection(stringResource(R.string.amo_task_pick_sr))
        team.forEachIndexed { i, s -> AmoListRow(s.name, onClick = { onPick(s) }, position = pos(i, team.size), divider = i < team.lastIndex, flat = true) }
    }
}

/** F-AMO-019: the details step after the SR, route and outlet are picked; the outlet card above is read-only. */
@Composable
fun TaskAssignContent(
    srName: String, outletLines: List<String>, types: List<TaskType>, bangla: Boolean, typeCode: String?, dueChoices: List<String>, due: String?,
    description: String, errors: Set<AssignError>, busy: Boolean, noTypes: Boolean,
    onType: (String) -> Unit, onDue: (String) -> Unit, onDescription: (String) -> Unit, onSave: () -> Unit, modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize()) {
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = AronTokens.Space.Screen, vertical = AronTokens.Space.S),
            verticalArrangement = Arrangement.spacedBy(AronTokens.Space.S),
        ) {
            AmoListRow(srName, subtitle = stringResource(R.string.amo_task_assignee), flat = true)
            outletLines.forEach { AmoListRow(it, flat = true) }
            AmoSection(stringResource(R.string.amo_task_type))
            if (noTypes) AmoBanner(stringResource(R.string.amo_task_no_types), kind = BannerKind.Warning)
            types.forEachIndexed { i, t ->
                AmoListRow(
                    if (bangla) t.labelBn ?: t.labelEn else t.labelEn, onClick = { onType(t.code) }, trailing = if (t.code == typeCode) stringResource(R.string.amo_verify_selected) else null,
                    position = pos(i, types.size), divider = i < types.lastIndex, flat = true,
                )
            }
            if (AssignError.TYPE_REQUIRED in errors) AmoBanner(stringResource(R.string.amo_task_type_required), kind = BannerKind.Error)
            AmoSection(stringResource(R.string.amo_task_date))
            dueChoices.forEachIndexed { i, d ->
                AmoListRow(localizedDigits(d), onClick = { onDue(d) }, trailing = if (d == due) stringResource(R.string.amo_verify_selected) else null, position = pos(i, dueChoices.size), divider = i < dueChoices.lastIndex, flat = true)
            }
            if (AssignError.DATE_INVALID in errors) AmoBanner(stringResource(R.string.amo_task_date_invalid), kind = BannerKind.Error)
            AmoField(description, onDescription, stringResource(R.string.amo_task_description), error = AssignError.TEXT_TOO_LONG in errors, singleLine = false)
        }
        Column(Modifier.fillMaxWidth().padding(AronTokens.Space.Screen)) {
            AmoPrimary(stringResource(R.string.amo_task_save), onSave, Modifier.fillMaxWidth(), enabled = !busy, busy = busy)
        }
    }
}
