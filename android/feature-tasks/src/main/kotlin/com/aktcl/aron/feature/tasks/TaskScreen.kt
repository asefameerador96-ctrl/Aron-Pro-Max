package com.aktcl.aron.feature.tasks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.background
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import com.aktcl.aron.core.ui.AronIcons
import com.aktcl.aron.core.ui.AronLook
import com.aktcl.aron.core.ui.AronTokens
import com.aktcl.aron.core.ui.LocalAronColors
import com.aktcl.aron.core.ui.LocalAronLook
import com.aktcl.aron.core.ui.aron.AronPrimaryButton
import com.aktcl.aron.core.ui.localizedDigits
import com.aktcl.aron.core.ui.localizedNumber

object TaskTags {
    const val EMPTY = "tsk_empty"
    const val LIST = "tsk_list"
    const val TABS = "tsk_tabs"
    fun item(uuid: String) = "tsk_item_$uuid"
    fun resolve(uuid: String) = "tsk_resolve_$uuid"
}

private const val ONGOING = "ongoing"

/** screens-sr s12: a swipe to the end past this share of the card width resolves the task (F-SR-047). */
private const val SWIPE_RESOLVE_FRACTION = 0.4f

/**
 * The Task screen (F-SR-046, F-SR-047; screens-sr s12): the ongoing and completed tabs (segmented, with counts), one
 * field card per task (title, description, outlet, due date, the status chip and a "Resolve" text button at the card's
 * end: discoverable and TalkBack-friendly, no dialog and no note), and the empty state with the synced-at caption so an
 * empty list is not mistaken for a stale one. An ongoing card also resolves on a swipe to the end past 40 percent of its
 * width (F-SR-047 parity, a Confirm haptic; the card springs back, the task moves to "Completed"). Resolved tasks stay in
 * place under "Completed".
 */
@Composable
fun TaskContent(state: TaskListState, onResolve: (String) -> Unit, modifier: Modifier = Modifier) {
    val open = state.items.filter { it.status == ONGOING }
    val done = state.items.filter { it.status != ONGOING }
    val synced = state.syncedAt?.let { stringResource(R.string.tsk_synced_at, localizedDigits(dhakaStamp(it))) } ?: stringResource(R.string.tsk_never_synced)
    // the completed tab opens first only when nothing is ongoing (the usual case is the open work)
    var tab by rememberSaveable { mutableIntStateOf(if (open.isEmpty() && done.isNotEmpty()) 1 else 0) }
    val look = LocalAronLook.current
    Column(modifier.fillMaxSize().background(look.page)) {
        if (state.showEmptyState) {
            EmptyTasks(stringResource(R.string.tsk_empty), synced, Modifier.testTag(TaskTags.EMPTY))
        } else {
            TaskTabs(
                listOf(
                    stringResource(R.string.tsk_tab_open, localizedNumber(open.size.toLong())),
                    stringResource(R.string.tsk_tab_done, localizedNumber(done.size.toLong())),
                ),
                tab, { tab = it },
                Modifier.padding(horizontal = AronTokens.Space.Screen, vertical = AronTokens.Space.M).testTag(TaskTags.TABS),
            )
            val shown = if (tab == 0) open else done
            if (shown.isEmpty()) {
                EmptyTasks(stringResource(if (tab == 0) R.string.tsk_none_open else R.string.tsk_none_done), synced)
            } else {
                LazyColumn(
                    Modifier.weight(1f).fillMaxWidth().testTag(TaskTags.LIST),
                    contentPadding = PaddingValues(start = AronTokens.Space.Screen, end = AronTokens.Space.Screen, bottom = AronTokens.Space.L),
                    verticalArrangement = Arrangement.spacedBy(AronTokens.Space.M),
                ) {
                    itemsIndexed(shown, key = { _, t -> t.taskUuid }) { _, t ->
                        if (t.status == ONGOING) SwipeToResolve(t.taskUuid, onResolve, Modifier.testTag(TaskTags.item(t.taskUuid))) { TaskCard(t, onResolve) }
                        else TaskCard(t, onResolve, Modifier.testTag(TaskTags.item(t.taskUuid)))
                    }
                }
            }
        }
    }
}

/** The empty state of sr/tasks.png: the grey page with the plain sentence centred, and the synced-at line so an empty list is not mistaken for a stale one. */
@Composable
private fun EmptyTasks(text: String, synced: String, modifier: Modifier = Modifier) {
    val look = LocalAronLook.current
    Column(modifier.fillMaxSize().padding(AronTokens.Space.Screen), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text(text, style = MaterialTheme.typography.bodyLarge, color = look.ink, textAlign = TextAlign.Center)
        Text(synced, style = MaterialTheme.typography.bodySmall, color = look.inkMuted, textAlign = TextAlign.Center, modifier = Modifier.padding(top = AronTokens.Space.S))
    }
}

/** The ongoing / completed tabs: the selected one filled `aron.purple`, the other white; each carries its count and the tab role. */
@Composable
private fun TaskTabs(labels: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val look = LocalAronLook.current
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(AronTokens.Space.S)) {
        labels.forEachIndexed { i, label ->
            val on = i == selected
            Box(
                Modifier.weight(1f).heightIn(min = AronLook.Touch).clip(RoundedCornerShape(AronLook.CardRadius))
                    .background(if (on) look.purple else look.card)
                    .selectable(selected = on, role = Role.Tab, onClick = { onSelect(i) }),
                contentAlignment = Alignment.Center,
            ) { Text(label, style = MaterialTheme.typography.titleSmall, color = if (on) Color.White else look.purple, textAlign = TextAlign.Center, modifier = Modifier.padding(AronTokens.Space.S)) }
        }
    }
}

/**
 * The swipe of F-SR-047 around an ongoing card: start to end only, past [SWIPE_RESOLVE_FRACTION] of the width; the
 * `success.container` panel with the check glyph shows under the card while it moves. The value change is refused so
 * the card springs back in place (the row leaves the list by its status, never by a dismiss animation); the resolve
 * fires once per swipe with a Confirm haptic. TalkBack users use the button on the card.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeToResolve(taskUuid: String, onResolve: (String) -> Unit, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val c = LocalAronColors.current
    val haptic = LocalHapticFeedback.current
    // the refused change is asked again on every move past the threshold: one resolve per gesture, re-armed once the card has settled
    var armed by remember { mutableStateOf(true) }
    val box = rememberSwipeToDismissBoxState(
        confirmValueChange = { v ->
            if (v == SwipeToDismissBoxValue.StartToEnd && armed) { armed = false; haptic.performHapticFeedback(HapticFeedbackType.Confirm); onResolve(taskUuid) }
            false
        },
        positionalThreshold = { it * SWIPE_RESOLVE_FRACTION },
    )
    LaunchedEffect(box.targetValue) { if (box.targetValue == SwipeToDismissBoxValue.Settled) armed = true }
    val panel = c.successContainer
    SwipeToDismissBox(
        state = box,
        modifier = modifier,
        enableDismissFromEndToStart = false,
        backgroundContent = {
            Box(
                Modifier.fillMaxSize().drawBehind { drawRoundRect(panel, cornerRadius = CornerRadius(AronTokens.Radius.Card.toPx())) }.padding(horizontal = AronTokens.Space.L),
                contentAlignment = Alignment.CenterStart,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AronTokens.Space.S)) {
                    Icon(AronIcons.Check, contentDescription = null, tint = c.successOnContainer, modifier = Modifier.size(AronTokens.Size.Icon))
                    Text(stringResource(R.string.tsk_resolve), style = MaterialTheme.typography.labelMedium, color = c.successOnContainer)
                }
            }
        },
        content = { content() },
    )
}

/** One task on a white card: the type code in red ahead of the purple title, description (4 lines), the outlet and the due date, the status word and a "Resolve" button. */
@Composable
private fun TaskCard(t: TaskItem, onResolve: (String) -> Unit, modifier: Modifier = Modifier) {
    val look = LocalAronLook.current
    val ongoing = t.status == ONGOING
    Column(modifier.fillMaxWidth().clip(RoundedCornerShape(AronLook.CardRadius)).background(look.card).padding(AronTokens.Space.L), verticalArrangement = Arrangement.spacedBy(AronTokens.Space.S)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AronTokens.Space.S)) {
            // T2: the task type code as a red tag ("OOS") ahead of the title, as on the manual's card
            if (t.taskTypeCode.isNotBlank()) Text(t.taskTypeCode.uppercase(java.util.Locale.ROOT), style = MaterialTheme.typography.labelLarge, color = look.red)
            Text(t.title, style = MaterialTheme.typography.titleSmall, color = look.purple, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        }
        StatusWord(if (ongoing) AronIcons.Clock else AronIcons.Check, stringResource(if (ongoing) R.string.tsk_status_ongoing else R.string.tsk_status_completed))
        t.description?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = look.ink, maxLines = 4, overflow = TextOverflow.Ellipsis) }
        t.outletName?.let { MetaLine(AronIcons.Outlet, stringResource(R.string.tsk_outlet, it)) }
        t.dueDate?.let { MetaLine(AronIcons.Flag, stringResource(R.string.tsk_due, dueDateText(it))) }
        if (ongoing) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                AronPrimaryButton(stringResource(R.string.tsk_resolve), { onResolve(t.taskUuid) }, Modifier.testTag(TaskTags.resolve(t.taskUuid)))
            }
        }
    }
}

@Composable
private fun MetaLine(icon: ImageVector, text: String) {
    val look = LocalAronLook.current
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AronTokens.Space.S)) {
        Icon(icon, contentDescription = null, tint = look.inkMuted, modifier = Modifier.size(AronTokens.Size.IconSmall))
        Text(text, style = MaterialTheme.typography.bodySmall, color = look.inkMuted)
    }
}

/** The status as an icon and a word (never colour alone). */
@Composable
private fun StatusWord(icon: ImageVector, label: String) {
    val look = LocalAronLook.current
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AronTokens.Space.Xs)) {
        Icon(icon, contentDescription = null, tint = look.inkMuted, modifier = Modifier.size(AronTokens.Size.IconSmall))
        Text(label, style = MaterialTheme.typography.labelMedium, color = look.inkMuted, maxLines = 1)
    }
}

/** The due date as ISO with the year ("2026-11-30", digits in the user's language), as the manual's "Completion on: 2025-11-30" (T4, F-SYS-051). */
@Composable
private fun dueDateText(isoDate: String): String = localizedDigits(isoDate)

/** `yyyy-MM-dd HH:mm` in Asia/Dhaka (UTC+6) of an RFC 3339 instant. */
internal fun dhakaStamp(iso: String): String = runCatching {
    val local = java.time.Instant.parse(iso).plusSeconds(6 * 3600L).atOffset(java.time.ZoneOffset.UTC)
    java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", java.util.Locale.ROOT).format(local)
}.getOrDefault(iso.take(16).replace('T', ' '))
