package com.aktcl.aron.feature.tasks

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aktcl.aron.core.ui.AronCard
import com.aktcl.aron.core.ui.AronEmptyState
import com.aktcl.aron.core.ui.AronIcons
import com.aktcl.aron.core.ui.AronSegmented
import com.aktcl.aron.core.ui.AronTextButton
import com.aktcl.aron.core.ui.AronTokens
import com.aktcl.aron.core.ui.GlassKind
import com.aktcl.aron.core.ui.GlassSurface
import com.aktcl.aron.core.ui.LocalAronColors
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

/** screens-sr s12: at most this many task cards carry a shadow; beyond it the cards turn flat (no shadow layer per row on the 2 GB phones). */
private const val SHADOWED_CARDS = 8

/**
 * The Task screen (F-SR-046, F-SR-047; screens-sr s12): the ongoing and completed tabs (segmented, with counts), one
 * field card per task (title, description, outlet, due date, the status chip and a "Resolve" text button at the card's
 * end: discoverable and TalkBack-friendly, no dialog and no note), and the empty state with the synced-at caption so an
 * empty list is not mistaken for a stale one. Resolved tasks stay in place under "Completed". Only the first eight cards
 * cast a shadow; the rest are flat (same fill, edge and radius), so a long list never draws a shadow layer per row.
 */
@Composable
fun TaskContent(state: TaskListState, onResolve: (String) -> Unit, modifier: Modifier = Modifier) {
    val open = state.items.filter { it.status == ONGOING }
    val done = state.items.filter { it.status != ONGOING }
    val synced = state.syncedAt?.let { stringResource(R.string.tsk_synced_at, localizedDigits(dhakaStamp(it))) } ?: stringResource(R.string.tsk_never_synced)
    // the completed tab opens first only when nothing is ongoing (the usual case is the open work)
    var tab by rememberSaveable { mutableIntStateOf(if (open.isEmpty() && done.isNotEmpty()) 1 else 0) }
    Column(modifier.fillMaxSize()) {
        if (state.showEmptyState) {
            AronEmptyState(stringResource(R.string.tsk_empty), Modifier.testTag(TaskTags.EMPTY), hint = synced, icon = AronIcons.Tasks)
        } else {
            AronSegmented(
                listOf(
                    stringResource(R.string.tsk_tab_open, localizedNumber(open.size.toLong())),
                    stringResource(R.string.tsk_tab_done, localizedNumber(done.size.toLong())),
                ),
                tab, { tab = it },
                Modifier.padding(horizontal = AronTokens.Space.Screen, vertical = AronTokens.Space.M).testTag(TaskTags.TABS),
                role = Role.Tab,
            )
            val shown = if (tab == 0) open else done
            if (shown.isEmpty()) {
                AronEmptyState(stringResource(if (tab == 0) R.string.tsk_none_open else R.string.tsk_none_done), hint = synced, icon = AronIcons.Tasks)
            } else {
                LazyColumn(
                    Modifier.weight(1f).fillMaxWidth().testTag(TaskTags.LIST),
                    contentPadding = PaddingValues(start = AronTokens.Space.Screen, end = AronTokens.Space.Screen, bottom = AronTokens.Space.L),
                    verticalArrangement = Arrangement.spacedBy(AronTokens.Space.M),
                ) {
                    itemsIndexed(shown, key = { _, t -> t.taskUuid }) { i, t -> TaskCard(t, onResolve, Modifier.testTag(TaskTags.item(t.taskUuid)), flat = i >= SHADOWED_CARDS) }
                }
            }
        }
    }
}

/** One task on a field card: status chip, title, description (4 lines), the outlet and the due date with their icons, Resolve. [flat]: no shadow. */
@Composable
private fun TaskCard(t: TaskItem, onResolve: (String) -> Unit, modifier: Modifier = Modifier, flat: Boolean = false) {
    val c = LocalAronColors.current
    val ongoing = t.status == ONGOING
    val card: @Composable (@Composable () -> Unit) -> Unit = { body ->
        if (flat) GlassSurface(modifier.fillMaxWidth(), kind = GlassKind.Field, elevation = AronTokens.Elevation.Page) { body() }
        else AronCard(modifier.fillMaxWidth()) { body() }
    }
    card {
        Column(Modifier.fillMaxWidth().padding(AronTokens.Space.L), verticalArrangement = Arrangement.spacedBy(AronTokens.Space.S)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AronTokens.Space.S)) {
                Text(t.title, style = MaterialTheme.typography.titleSmall, color = c.textPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                if (ongoing) Pill(c.accentContainer, c.accentOnContainer, AronIcons.Clock, stringResource(R.string.tsk_status_ongoing))
                else Pill(null, c.textSecondary, AronIcons.Check, stringResource(R.string.tsk_status_completed))
            }
            t.description?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = c.textPrimary, maxLines = 4, overflow = TextOverflow.Ellipsis) }
            t.outletName?.let { MetaLine(AronIcons.Outlet, stringResource(R.string.tsk_outlet, it)) }
            t.dueDate?.let { MetaLine(AronIcons.Flag, stringResource(R.string.tsk_due, localizedDigits(it))) }
            if (ongoing) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    AronTextButton(stringResource(R.string.tsk_resolve), { onResolve(t.taskUuid) }, Modifier.testTag(TaskTags.resolve(t.taskUuid)), icon = AronIcons.Check)
                }
            }
        }
    }
}

@Composable
private fun MetaLine(icon: ImageVector, text: String) {
    val c = LocalAronColors.current
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AronTokens.Space.S)) {
        Icon(icon, contentDescription = null, tint = c.textSecondary, modifier = Modifier.size(AronTokens.Size.IconSmall))
        Text(text, style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
    }
}

/** A 28 dp pill with an icon and a word in opaque container colours; [container] null is the quiet form. */
@Composable
private fun Pill(container: Color?, ink: Color, icon: ImageVector, label: String) {
    Row(
        Modifier
            .heightIn(min = AronTokens.Size.Chip)
            .drawBehind { container?.let { drawRoundRect(it, cornerRadius = CornerRadius(AronTokens.Radius.Chip.toPx())) } }
            .padding(horizontal = if (container != null) 10.dp else 0.dp, vertical = AronTokens.Space.Xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = ink, modifier = Modifier.size(AronTokens.Space.L))
        Spacer(Modifier.width(AronTokens.Space.S))
        Text(label, style = MaterialTheme.typography.labelMedium, color = ink, maxLines = 1)
    }
}

/** `yyyy-MM-dd HH:mm` in Asia/Dhaka (UTC+6) of an RFC 3339 instant. */
internal fun dhakaStamp(iso: String): String = runCatching {
    val local = java.time.Instant.parse(iso).plusSeconds(6 * 3600L).atOffset(java.time.ZoneOffset.UTC)
    java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", java.util.Locale.ROOT).format(local)
}.getOrDefault(iso.take(16).replace('T', ' '))
