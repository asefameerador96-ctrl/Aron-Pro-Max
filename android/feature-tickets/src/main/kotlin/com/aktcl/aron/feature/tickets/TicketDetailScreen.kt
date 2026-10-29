package com.aktcl.aron.feature.tickets

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aktcl.aron.core.common.LocaleDigits
import com.aktcl.aron.core.ui.LocalAppLanguage
import kotlinx.coroutines.launch

/** The Ticket Details state; the comment draft (its uuid and text) survives rotation and process death. */
@Composable
fun rememberTicketDetailState(deps: TicketDeps, ticketUuid: String): TicketDetailState {
    val comment = rememberSaveable(ticketUuid, stateSaver = TicketSavers.Comment) { mutableStateOf(CommentDraft(deps.newUuid())) }
    return remember(deps, ticketUuid) { TicketDetailState(deps, ticketUuid, comment) }
}

/**
 * Screenshot 14: number, category and age; title; priority and status; Submit Review & Close on a Resolved ticket; the
 * thread (Support is never named, D-TSO-MAN-21); the composer only for New and In Progress, otherwise the fixed note.
 */
@Composable
fun TicketDetailScreen(state: TicketDetailState, frame: TicketFrame, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    LaunchedEffect(state) { state.load() }
    // back with an unsent comment: its staged photos go (the comment draft is gone for good; a rotation keeps both)
    OnLeaveScreen { state.discardLater(state.leaveComment()) }
    val ui = state.ui
    val p = ticketPalette()
    val now = state.now()
    val closeSaved = stringResource(R.string.tickets_close_saved)
    val commentSaved = stringResource(R.string.tickets_comment_saved)
    val failed = stringResource(R.string.tickets_save_failed)
    LaunchedEffect(state.phase) {
        when (state.consumeToast()) {
            DetailPhase.ClosedSaved -> Toast.makeText(context, closeSaved, Toast.LENGTH_SHORT).show()
            DetailPhase.CommentSaved -> Toast.makeText(context, commentSaved, Toast.LENGTH_SHORT).show()
            DetailPhase.Failed -> Toast.makeText(context, failed, Toast.LENGTH_LONG).show()
            else -> Unit
        }
    }
    frame(stringResource(R.string.tickets_title_details)) {
        TicketPage(modifier) {
            val t = ui.ticket
            when {
                t == null && ui.loading -> CircularProgressIndicator(Modifier.align(Alignment.Center), color = p.accentInk)
                t == null -> TicketNote(
                    stringResource(if (ui.notFound) R.string.tickets_not_found else R.string.tickets_offline_none),
                    Modifier.align(Alignment.TopStart).padding(16.dp).testTag(TicketTags.NOT_FOUND),
                )
                else -> Column(Modifier.fillMaxSize().imePadding()) {
                    // the header scrolls with the thread, so large text never pushes the thread off the screen
                    LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        item {
                            Header(t, ui, now) { scope.launch { state.close() } }
                            HorizontalDivider(color = p.cardBorder)
                        }
                        if (ui.offline) item { OfflineNote(null, now, Modifier.padding(horizontal = 16.dp)) }
                        if (t.description.isNotBlank()) {
                            item { Box(Modifier.padding(horizontal = 16.dp)) { Bubble(TicketEvent(t.uuid, EventKind.CREATED, t.description, false, "", t.capturedAtMs, mine = true), now) } }
                        }
                        items(ui.events, key = { it.uuid }) { e -> Box(Modifier.padding(horizontal = 16.dp)) { EventRow(e, now) } }
                    }
                    Footer(state, t, ui)
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Header(t: Ticket, ui: TicketDetailUi, now: Long, onClose: () -> Unit) {
    val p = ticketPalette()
    Column(Modifier.fillMaxWidth().background(p.raised).padding(horizontal = 16.dp, vertical = 18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth()) {
            val head = t.number ?: stringResource(if (t.delivery == Delivery.Sent) R.string.tickets_number_follows else R.string.tickets_not_sent)
            Text(stringResource(R.string.tickets_line, head, stringResource(R.string.tickets_category)), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, color = p.inkMuted)
            Text(ageText(now, t.capturedAtMs), style = MaterialTheme.typography.bodyLarge, color = p.inkMuted)
        }
        Text(t.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = p.ink)
        // pills and the close button in a flow: one row as screenshot 14, wrapping (never squeezing) at large text
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TicketPill(priorityText(t.priority))
                TicketPill(statusText(ui.status?.wire ?: t.status))
            }
            if (ui.canClose) {
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                    Box(
                        Modifier
                            .width(IntrinsicSize.Max)
                            .heightIn(min = 48.dp)
                            .clip(RoundedCornerShape(50))
                            .background(p.accentInk)
                            .clickable(role = Role.Button, onClick = onClose)
                            .testTag(TicketTags.CLOSE)
                            .padding(horizontal = 18.dp, vertical = 12.dp),
                        contentAlignment = Alignment.Center,
                    ) { Text(stringResource(R.string.tickets_close), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = p.onAccent, maxLines = 2) }
                }
            }
        }
        ui.close?.let { c ->
            when (val d = c.delivery) {
                Delivery.Pending -> Text(stringResource(R.string.tickets_closing), Modifier.testTag(TicketTags.CLOSING), style = MaterialTheme.typography.bodyMedium, color = p.warning)
                is Delivery.Refused -> Text(refusalText(d.code), Modifier.testTag(TicketTags.refused(c.uuid)), style = MaterialTheme.typography.bodyMedium, color = p.danger)
                Delivery.Sent -> Unit
            }
        }
        (t.delivery as? Delivery.Refused)?.let { Text(refusalText(it.code), Modifier.testTag(TicketTags.refused(t.uuid)), style = MaterialTheme.typography.bodyMedium, color = p.danger) }
    }
}

@Composable
private fun EventRow(e: TicketEvent, now: Long) {
    val p = ticketPalette()
    when (e.kind) {
        EventKind.COMMENT, EventKind.RESOLUTION -> Bubble(e, now)
        EventKind.CREATED -> Unit // the description is the first bubble
        else -> {
            val text = when (e.kind) {
                EventKind.CLOSE_REVIEW -> stringResource(R.string.tickets_event_closed)
                EventKind.STATUS_CHANGE -> stringResource(R.string.tickets_event_status, statusText(e.toStatus ?: ""))
                EventKind.PRIORITY_CHANGE -> stringResource(R.string.tickets_event_priority, e.body?.let { priorityText(it) } ?: "")
                else -> stringResource(R.string.tickets_event_assigned)
            }
            Text(
                "$text · ${timeText(e.atMs, now)}", Modifier.fillMaxWidth().testTag(TicketTags.event(e.uuid)),
                style = MaterialTheme.typography.bodySmall, color = p.inkFaint, textAlign = TextAlign.Center,
            )
            (e.delivery as? Delivery.Refused)?.let { Text(refusalText(it.code), Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodySmall, color = p.danger, textAlign = TextAlign.Center) }
        }
    }
}

/** A message: Support's on the left with the headset avatar (screenshot 14), the reporter's own on the right. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Bubble(e: TicketEvent, now: Long) {
    val p = ticketPalette()
    val language = LocalAppLanguage.current
    val support = e.actorIsSupport
    val who = when {
        support -> stringResource(R.string.tickets_support)
        e.mine || e.actorDisplay.isBlank() -> stringResource(R.string.tickets_you)
        else -> e.actorDisplay
    }
    Row(Modifier.fillMaxWidth().testTag(TicketTags.event(e.uuid)), horizontalArrangement = if (support) Arrangement.Start else Arrangement.End) {
        if (support) {
            Box(Modifier.padding(end = 12.dp).size(44.dp).clip(CircleShape).background(p.supportAvatar), contentAlignment = Alignment.Center) {
                Icon(TicketGlyphs.Headset, contentDescription = null, tint = p.supportIcon, modifier = Modifier.size(22.dp))
            }
        }
        val shape = RoundedCornerShape(18.dp)
        Column(
            Modifier
                .weight(1f, fill = support)
                .widthIn(max = 320.dp)
                .clip(shape)
                .background(if (support) p.bubble else p.bubbleMine)
                .border(1.5.dp, if (support) p.bubbleBorder else p.cardBorder, shape)
                .padding(horizontal = 20.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            FlowRow(
                if (support) Modifier.fillMaxWidth() else Modifier,
                horizontalArrangement = if (support) Arrangement.SpaceBetween else Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(who, Modifier.padding(end = 16.dp), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = p.ink)
                Text(timeText(e.atMs, now), style = MaterialTheme.typography.bodyMedium, color = p.inkFaint)
            }
            e.body?.takeIf { it.isNotBlank() }?.let { Text(it, Modifier.padding(start = 2.dp), style = MaterialTheme.typography.bodyLarge, color = p.ink.copy(alpha = 0.9f), fontSize = 17.sp, lineHeight = 26.sp) }
            if (e.attachments.isNotEmpty()) {
                Text(stringResource(R.string.tickets_photos_attached, LocaleDigits.localize(e.attachments.size.toString(), language)), style = MaterialTheme.typography.bodySmall, color = p.inkMuted)
            }
            when (val d = e.delivery) {
                Delivery.Pending -> Text(stringResource(R.string.tickets_not_sent), style = MaterialTheme.typography.bodySmall, color = p.warning)
                is Delivery.Refused -> Text(refusalText(d.code), Modifier.testTag(TicketTags.refused(e.uuid)), style = MaterialTheme.typography.bodySmall, color = p.danger)
                Delivery.Sent -> Unit
            }
        }
    }
}

@Composable
private fun Footer(state: TicketDetailState, t: Ticket, ui: TicketDetailUi) {
    val p = ticketPalette()
    val scope = rememberCoroutineScope()
    if (!ui.canComment) {
        val note = when {
            t.delivery is Delivery.Refused -> null
            ui.status == TicketStatus.RESOLVED -> R.string.tickets_no_comments_resolved
            ui.status == TicketStatus.CLOSED -> R.string.tickets_no_comments_closed
            ui.status == TicketStatus.CANCELLED -> R.string.tickets_no_comments_cancelled
            else -> null
        }
        if (note != null) {
            Text(
                stringResource(note), Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 20.dp).testTag(TicketTags.NO_COMMENTS),
                style = MaterialTheme.typography.bodyLarge, color = p.inkFaint,
            )
        }
        return
    }
    val c = state.comment
    val photos = state.photos
    val current by rememberUpdatedState(state)
    val gallery = photos?.rememberGallery { picks -> scope.launch { current.addPickedForComment(picks) } }
    var menu by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().background(p.raised).padding(12.dp).testTag(TicketTags.COMPOSER), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        PhotoStrip(c.photoUuids, photos, { id -> scope.launch { state.removeCommentPhoto(id) } })
        (state.phase as? DetailPhase.CommentInvalid)?.let { inv ->
            Text(
                stringResource(
                    when (inv.error) {
                        CommentError.EMPTY -> R.string.tickets_err_comment
                        CommentError.TOO_LONG -> R.string.tickets_err_comment_long
                        CommentError.TOO_MANY_PHOTOS -> R.string.tickets_err_photos
                    },
                ),
                Modifier.testTag(TicketTags.ERRORS), style = MaterialTheme.typography.bodySmall, color = p.danger,
            )
        }
        (state.phase as? DetailPhase.PhotoFailed)?.let { f -> Text(stringResource(f.messageRes ?: R.string.tickets_err_photo), style = MaterialTheme.typography.bodySmall, color = p.danger) }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.weight(1f)) {
                TicketField(c.body, state::editComment, stringResource(R.string.tickets_comment_hint), Modifier.testTag(TicketTags.COMMENT), singleLine = false)
            }
            if (photos != null && state.maxPhotos > 0) {
                Box {
                    RoundAction(TicketGlyphs.AddPhoto, stringResource(R.string.tickets_attach), p.chip, p.ink, enabled = state.canAddPhoto) { menu = true }
                    PhotoChoiceMenu(
                        menu, { menu = false },
                        onGallery = { gallery?.invoke(state.maxPhotos - state.comment.photoUuids.size) },
                        onCamera = { scope.launch { state.captureForComment() } },
                    )
                }
            }
            RoundAction(TicketGlyphs.Send, stringResource(R.string.tickets_send), p.accent, p.onAccent, Modifier.testTag(TicketTags.SEND)) { scope.launch { state.sendComment() } }
        }
    }
}

@Composable
private fun RoundAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    fill: androidx.compose.ui.graphics.Color,
    tint: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Box(
        modifier
            .size(48.dp)
            .clip(CircleShape)
            .background(if (enabled) fill else fill.copy(alpha = 0.4f))
            .clickable(enabled = enabled, role = Role.Button, onClickLabel = label, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) { Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp)) }
}
