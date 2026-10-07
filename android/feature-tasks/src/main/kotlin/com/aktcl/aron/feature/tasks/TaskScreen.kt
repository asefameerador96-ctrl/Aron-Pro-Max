package com.aktcl.aron.feature.tasks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.aktcl.aron.core.ui.AronEmptyState
import com.aktcl.aron.core.ui.AronPrimaryButton
import com.aktcl.aron.core.ui.AronTokens
import com.aktcl.aron.core.ui.localizedDigits

object TaskTags {
    const val EMPTY = "tsk_empty"
    const val LIST = "tsk_list"
    fun item(uuid: String) = "tsk_item_$uuid"
    fun resolve(uuid: String) = "tsk_resolve_$uuid"
}

/** The Task screen (F-SR-046) with swipe-then-tap Resolve (F-SR-047). Resolved tasks stay; the empty state shows synced-at. */
@Composable
fun TaskContent(state: TaskListState, onResolve: (String) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxSize().padding(AronTokens.Space.L), verticalArrangement = Arrangement.spacedBy(AronTokens.Space.M)) {
        Text(stringResource(R.string.tsk_title), style = MaterialTheme.typography.headlineSmall)
        if (state.showEmptyState) {
            AronEmptyState(
                stringResource(R.string.tsk_empty), Modifier.testTag(TaskTags.EMPTY),
                hint = state.syncedAt?.let { stringResource(R.string.tsk_synced_at, localizedDigits(it.take(16).replace('T', ' '))) } ?: stringResource(R.string.tsk_never_synced),
            )
        } else {
            Text(stringResource(R.string.tsk_swipe_hint), style = MaterialTheme.typography.bodySmall)
            LazyColumn(Modifier.testTag(TaskTags.LIST), verticalArrangement = Arrangement.spacedBy(AronTokens.Space.S)) {
                items(state.items, key = { it.taskUuid }) { t -> TaskRow(t, onResolve) }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TaskRow(t: TaskItem, onResolve: (String) -> Unit) {
    var revealed by remember(t.taskUuid) { mutableStateOf(false) }
    val box = rememberSwipeToDismissBoxState()
    // The swipe only reveals the Resolve button; the tap on it resolves (a swipe alone never completes a task).
    LaunchedEffect(box.currentValue) {
        if (box.currentValue != SwipeToDismissBoxValue.Settled) { revealed = true; box.snapTo(SwipeToDismissBoxValue.Settled) }
    }
    Column(Modifier.testTag(TaskTags.item(t.taskUuid))) {
        if (t.status == "ongoing") {
            SwipeToDismissBox(state = box, backgroundContent = {}, content = { TaskCard(t) })
            if (revealed) AronPrimaryButton(stringResource(R.string.tsk_resolve), { onResolve(t.taskUuid) }, Modifier.testTag(TaskTags.resolve(t.taskUuid)))
        } else {
            TaskCard(t)
        }
    }
}

@Composable
private fun TaskCard(t: TaskItem) {
    Column(Modifier.fillMaxSize()) {
        Row(horizontalArrangement = Arrangement.spacedBy(AronTokens.Space.S)) {
            Text(t.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Text(stringResource(if (t.status == "ongoing") R.string.tsk_status_ongoing else R.string.tsk_status_completed), style = MaterialTheme.typography.labelLarge)
        }
        t.outletName?.let { Text(stringResource(R.string.tsk_outlet, it)) }
        t.description?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        t.dueDate?.let { Text(stringResource(R.string.tsk_due, localizedDigits(it)), style = MaterialTheme.typography.bodySmall) }
    }
}
