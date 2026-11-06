package com.aktcl.aron.feature.amo

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
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

object TaskTags { const val CARD = "task_card_"; const val CHIP = "task_chip_"; const val FAB = "task_fab"; const val SAVED = "task_saved"; const val FORM_SAVE = "task_form_save" }

@Composable
private fun chipText(c: TaskChip): String = stringResource(
    when (c) {
        TaskChip.RUNNING -> R.string.amo_team_task_running; TaskChip.DONE -> R.string.amo_team_task_done; TaskChip.CANCELLED -> R.string.amo_team_task_cancelled
        TaskChip.OVERDUE -> R.string.amo_team_task_overdue; TaskChip.WAITING -> R.string.amo_task_waiting; TaskChip.REJECTED -> R.string.amo_task_rejected
    },
)

@Composable
private fun chipColor(c: TaskChip): androidx.compose.ui.graphics.Color {
    val look = com.aktcl.aron.core.ui.LocalAronLook.current
    return when (c) {
        TaskChip.RUNNING -> if (look.sunlight) androidx.compose.ui.graphics.Color(0xFF8A4B00) else androidx.compose.ui.graphics.Color(0xFFE08A00)
        TaskChip.DONE -> if (look.sunlight) androidx.compose.ui.graphics.Color(0xFF1B5E20) else androidx.compose.ui.graphics.Color(0xFF2E9E4F)
        TaskChip.CANCELLED -> look.inkMuted
        TaskChip.OVERDUE, TaskChip.REJECTED -> look.red
        TaskChip.WAITING -> look.purple
    }
}

/**
 * Task Delegation list (screenshot 37, R11): the "Assigned Tasks" tab, a card per task (outlet, status chip, the AMO's text, flag and
 * "Completion on: date"), the orange + button, and after a save the lime "Save Successfully!" banner. The vendor has no task detail screen.
 */
@Composable
fun AssignedTasksContent(
    tasks: List<AssignedTask>, srNames: Map<Long, String>, outletNames: Map<Long, String>, typeLabel: (String) -> String,
    stale: Boolean, loadFailed: Boolean, onAssign: () -> Unit, modifier: Modifier = Modifier, today: String? = null, saved: Boolean = false,
) {
    val look = com.aktcl.aron.core.ui.LocalAronLook.current
    androidx.compose.foundation.layout.Box(modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(AronTokens.Space.Screen), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (loadFailed) AmoBanner(stringResource(R.string.amo_task_list_offline), kind = BannerKind.Warning)
            else if (stale) AmoBanner(stringResource(R.string.amo_task_list_kept), kind = BannerKind.Info)
            // the single tab of the vendor screen
            androidx.compose.foundation.layout.Row(Modifier.fillMaxWidth().border(androidx.compose.foundation.BorderStroke(1.dp, look.rowRule)), horizontalArrangement = Arrangement.Start) {
                androidx.compose.foundation.layout.Box(Modifier.background(androidx.compose.ui.graphics.Color(0xFF1E6FD9)).padding(horizontal = 16.dp, vertical = 10.dp)) {
                    androidx.compose.material3.Text(stringResource(R.string.amo_team_task_tab), style = amoText(15, bold = true, color = androidx.compose.ui.graphics.Color.White))
                }
            }
            if (tasks.isEmpty()) AmoEmptyState(stringResource(R.string.amo_task_empty), Modifier.fillMaxWidth(), hint = stringResource(R.string.amo_task_empty_hint))
            tasks.forEach { t ->
                val chip = TaskDelegation.chip(t, today)
                val shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp)
                Column(Modifier.fillMaxWidth().shadow(2.dp, shape).clip(shape).background(look.card).padding(14.dp).testTag(TaskTags.CARD + t.uuid), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    androidx.compose.foundation.layout.Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        androidx.compose.material3.Text(t.outletId?.let { outletNames[it] } ?: srNames[t.assigneeId] ?: typeLabel(t.typeCode).ifBlank { t.title }, style = amoText(16, bold = true), modifier = Modifier.weight(1f))
                        androidx.compose.material3.Text(chipText(chip), style = amoText(14, bold = true, color = chipColor(chip)), modifier = Modifier.testTag(TaskTags.CHIP + t.uuid))
                    }
                    androidx.compose.material3.Text(t.description ?: t.title.ifBlank { typeLabel(t.typeCode) }, style = amoText(16))
                    t.dueDate?.let { d ->
                        androidx.compose.foundation.layout.Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            TeamIcon(R.drawable.amo_team_ic_flag, tint = look.inkMuted, size = 16.dp)
                            androidx.compose.material3.Text(stringResource(R.string.amo_team_task_completion, d), style = amoText(14, color = look.inkMuted))
                        }
                    }
                }
            }
            androidx.compose.foundation.layout.Spacer(Modifier.height(72.dp))
        }
        if (saved) androidx.compose.foundation.layout.Box(Modifier.align(Alignment.Center).fillMaxWidth().background(look.limeBanner()).padding(vertical = 12.dp).testTag(TaskTags.SAVED), contentAlignment = Alignment.Center) {
            androidx.compose.material3.Text(stringResource(R.string.amo_team_task_saved), style = amoText(16, bold = true, color = androidx.compose.ui.graphics.Color.Black))
        }
        androidx.compose.foundation.layout.Box(
            Modifier.align(Alignment.BottomEnd).padding(20.dp).size(60.dp).shadow(6.dp, androidx.compose.foundation.shape.CircleShape).clip(androidx.compose.foundation.shape.CircleShape)
                .background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(androidx.compose.ui.graphics.Color(0xFFFFA000), androidx.compose.ui.graphics.Color(0xFFE65100))))
                .clickable(onClickLabel = stringResource(R.string.amo_task_assign), role = androidx.compose.ui.semantics.Role.Button, onClick = onAssign).testTag(TaskTags.FAB),
            contentAlignment = Alignment.Center,
        ) { TeamIcon(R.drawable.amo_team_ic_add, tint = androidx.compose.ui.graphics.Color.White, size = 32.dp) }
    }
}

/** The lime of the success banner (screenshot 38, about #AFCB0A); black text on it holds 7:1, so Sunlight uses the same solid fill. */
private fun com.aktcl.aron.core.ui.AronLookColors.limeBanner() = androidx.compose.ui.graphics.Color(0xFFB7D40C)

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
