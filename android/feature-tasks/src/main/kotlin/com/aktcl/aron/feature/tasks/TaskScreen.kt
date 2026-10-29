package com.aktcl.aron.feature.tasks

import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.foundation.clickable
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
import com.aktcl.aron.core.ui.aron.AronTabs
import com.aktcl.aron.core.ui.localizedDigits
import com.aktcl.aron.core.ui.localizedNumber

object TaskTags {
    const val EMPTY = "tsk_empty"
    const val LIST = "tsk_list"
    const val TABS = "tsk_tabs"
    const val MY_REQUESTS = "tsk_my_requests"
    fun item(uuid: String) = "tsk_item_$uuid"
    fun resolve(uuid: String) = "tsk_resolve_$uuid"
    fun type(uuid: String) = "tsk_type_$uuid"
}

/** One label of the bundle code list `task_type` (tso-manual A3, D-TSO-MAN-35): English, and Bangla once AKTCL supplies it. */
data class TaskTypeLabel(val en: String, val bn: String?) {
    fun text(bangla: Boolean): String = if (bangla && !bn.isNullOrBlank()) bn else en
}

/**
 * The task type labels of the signed-in user's bundle, by code. The day object of the app ([hold] from the stored bundle
 * section `code_lists`, after every reload) keeps them here as compose state, so the Task screen shows labels with no extra
 * read; the TSO's task types (D-TSO-MAN-35) reach the SR card this way. Reference data only, never business figures.
 */
object TaskTypeLabels {
    private val held = androidx.compose.runtime.mutableStateOf<Map<String, TaskTypeLabel>>(emptyMap())

    /** The labels now (read in composition: the screen redraws when they change). */
    val current: Map<String, TaskTypeLabel> get() = held.value

    fun hold(labels: Map<String, TaskTypeLabel>) { if (held.value != labels) held.value = labels }

    /** The `task_type` list of a `code_lists` section; bad items are dropped; empty when absent or unreadable. */
    fun parse(codeLists: String?): Map<String, TaskTypeLabel> = runCatching {
        val a = org.json.JSONArray(codeLists ?: return emptyMap())
        val list = (0 until a.length()).map { a.getJSONObject(it) }.firstOrNull { it.optString("list_key") == "task_type" }?.optJSONArray("items") ?: return emptyMap()
        (0 until list.length()).mapNotNull { i ->
            runCatching {
                val o = list.getJSONObject(i)
                val code = o.getString("code"); val en = o.getString("label_en")
                require(code.isNotBlank() && en.isNotBlank())
                code to TaskTypeLabel(en, if (o.isNull("label_bn")) null else o.optString("label_bn").ifBlank { null })
            }.getOrNull()
        }.toMap()
    }.getOrDefault(emptyMap())
}

private const val ONGOING = "ongoing"

/** A synced-at older than this is shown under the empty state, so an empty list is not mistaken for a stale one. */
private const val STALE_AFTER_MS = 6 * 60 * 60 * 1000L

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
fun TaskContent(
    state: TaskListState, onResolve: (String) -> Unit, modifier: Modifier = Modifier, onMyRequests: (() -> Unit)? = null, nowMs: Long = System.currentTimeMillis(),
    // tso-manual A3 (D-TSO-MAN-35, XA-01): the task type's label from the bundle code list `task_type`, never the code
    typeLabels: Map<String, TaskTypeLabel> = TaskTypeLabels.current,
) {
    val open = state.items.filter { it.status == ONGOING }
    val done = state.items.filter { it.status != ONGOING }
    val synced = state.syncedAt?.let { stringResource(R.string.tsk_synced_at, localizedDigits(dhakaStamp(it))) } ?: stringResource(R.string.tsk_never_synced)
    // the completed tab opens first only when nothing is ongoing (the usual case is the open work)
    var tab by rememberSaveable { mutableIntStateOf(if (open.isEmpty() && done.isNotEmpty()) 1 else 0) }
    val look = LocalAronLook.current
    // the synced-at line shows only when the list may be stale: never synced, or the last sync is older than [STALE_AFTER_MS]
    val stale = state.syncedAt?.let { runCatching { nowMs - java.time.Instant.parse(it).toEpochMilli() > STALE_AFTER_MS }.getOrDefault(true) } ?: true
    Column(modifier.fillMaxSize().background(look.page)) {
        Column(Modifier.weight(1f).fillMaxWidth()) {
            if (state.showEmptyState) {
                EmptyTasks(stringResource(R.string.tsk_empty), synced.takeIf { stale }, Modifier.testTag(TaskTags.EMPTY))
            } else {
                AronTabs(
                    listOf(
                        stringResource(R.string.tsk_tab_open, localizedNumber(open.size.toLong())),
                        stringResource(R.string.tsk_tab_done, localizedNumber(done.size.toLong())),
                    ),
                    tab, { tab = it },
                    Modifier.padding(horizontal = AronTokens.Space.Screen, vertical = AronTokens.Space.M).testTag(TaskTags.TABS),
                )
                val shown = if (tab == 0) open else done
                if (shown.isEmpty()) {
                    EmptyTasks(stringResource(if (tab == 0) R.string.tsk_none_open else R.string.tsk_none_done), synced.takeIf { stale })
                } else {
                    LazyColumn(
                        Modifier.weight(1f).fillMaxWidth().testTag(TaskTags.LIST),
                        contentPadding = PaddingValues(start = AronTokens.Space.Screen, end = AronTokens.Space.Screen, bottom = AronTokens.Space.L),
                        verticalArrangement = Arrangement.spacedBy(AronTokens.Space.M),
                    ) {
                        itemsIndexed(shown, key = { _, t -> t.taskUuid }) { _, t ->
                            if (t.status == ONGOING) SwipeToResolve(t.taskUuid, onResolve, Modifier.testTag(TaskTags.item(t.taskUuid))) { TaskCard(t, onResolve, typeLabels = typeLabels) }
                            else TaskCard(t, onResolve, Modifier.testTag(TaskTags.item(t.taskUuid)), typeLabels)
                        }
                    }
                }
            }
        }
        // "my outlet requests" (F-SR-040) has no place on the outlet menu of sr/outlet-menu.png, so it lives here
        if (onMyRequests != null) {
            Text(
                stringResource(R.string.tsk_my_requests), style = MaterialTheme.typography.titleSmall, color = look.purple, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().heightIn(min = AronLook.Touch).testTag(TaskTags.MY_REQUESTS).clickable(role = Role.Button, onClick = onMyRequests).padding(AronTokens.Space.M),
            )
        }
    }
}

/** The empty state of sr/tasks.png: the grey page with the plain sentence centred (balanced breaks), and the synced-at line only when the list may be stale. */
@Composable
private fun EmptyTasks(text: String, synced: String?, modifier: Modifier = Modifier) {
    val look = LocalAronLook.current
    Column(modifier.fillMaxSize().padding(AronTokens.Space.Screen), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text(text, style = MaterialTheme.typography.bodyLarge.copy(lineBreak = LineBreak.Paragraph), color = look.ink, textAlign = TextAlign.Center, modifier = Modifier.widthIn(max = 240.dp))
        if (synced != null) Text(synced, style = MaterialTheme.typography.bodySmall, color = look.inkMuted, textAlign = TextAlign.Center, modifier = Modifier.padding(top = AronTokens.Space.S))
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

/** One task on a white card: the type's label in red ahead of the purple title, description (4 lines), the outlet and the due date, the status word and a "Resolve" button. */
@Composable
private fun TaskCard(t: TaskItem, onResolve: (String) -> Unit, modifier: Modifier = Modifier, typeLabels: Map<String, TaskTypeLabel> = emptyMap()) {
    val look = LocalAronLook.current
    val ongoing = t.status == ONGOING
    val bangla = com.aktcl.aron.core.ui.LocalAppLanguage.current == com.aktcl.aron.core.common.AppLanguage.BN
    Column(modifier.fillMaxWidth().clip(RoundedCornerShape(AronLook.CardRadius)).background(look.card).padding(AronTokens.Space.L), verticalArrangement = Arrangement.spacedBy(AronTokens.Space.S)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AronTokens.Space.S)) {
            // T2: the task type as a red tag ahead of the title, as on the manual's card. tso-manual A3 (D-TSO-MAN-35): the
            // bundle's label ("Stock out"), not the code; the code shows only when the bundle has no label for it yet
            if (t.taskTypeCode.isNotBlank()) Text(
                typeLabels[t.taskTypeCode]?.text(bangla) ?: t.taskTypeCode.uppercase(java.util.Locale.ROOT),
                Modifier.testTag(TaskTags.type(t.taskUuid)), style = MaterialTheme.typography.labelLarge, color = look.red,
            )
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
