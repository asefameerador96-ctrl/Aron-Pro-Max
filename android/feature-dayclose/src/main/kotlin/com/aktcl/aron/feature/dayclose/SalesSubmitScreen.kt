package com.aktcl.aron.feature.dayclose

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.aktcl.aron.core.common.AppLanguage
import com.aktcl.aron.core.ui.AronConfirmDialog
import com.aktcl.aron.core.ui.AronEasing
import com.aktcl.aron.core.ui.AronIcons
import com.aktcl.aron.core.ui.AronLook
import com.aktcl.aron.core.ui.AronTokens
import com.aktcl.aron.core.ui.LocalAronLook
import com.aktcl.aron.core.ui.aron.AronDataTable
import com.aktcl.aron.core.ui.aron.AronHoldButton
import com.aktcl.aron.core.ui.aron.AronNoticeCard
import com.aktcl.aron.core.ui.aron.AronSheet
import com.aktcl.aron.core.ui.aron.AronTextButton as AronLinkButton
import com.aktcl.aron.core.ui.aron.AronPrimaryButton
import com.aktcl.aron.core.ui.aron.AronStatusDot
import com.aktcl.aron.core.ui.aron.ButtonGlyph
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.draw.clip
import com.aktcl.aron.core.ui.LocalAppLanguage
import com.aktcl.aron.core.ui.LocalAronColors
import com.aktcl.aron.core.ui.LocalReducedMotion
import com.aktcl.aron.core.ui.localizedDigits
import com.aktcl.aron.core.ui.localizedNumber
import com.aktcl.aron.rules.Formats
import com.aktcl.aron.rules.UiLocale

/** What the Sales Submit screen shows after the submit was tapped. */
enum class SubmitProgress { Idle, Queued, Settled }

object SalesSubmitTags {
    /** The press-and-hold button of the hold step (the bar primary opens it; the hold commits). */
    const val HOLD = "submit_hold"
    /** D-NIGHT-DAYCLOSE-01 (b): the listed rejected rows. */
    const val REJECTED = "submit_rejected"
}

/** The least width of the count columns of the reconciliation table on a 328 dp content width (screens-sr s11); a wider count grows its column. */
private val CountColumn = 64.dp

private val OnlineGreen = Color(0xFF3BA346)
private val OkInk = Color(0xFF1F7A2E)
private val WarnInk = Color(0xFF9A5B00)
private val NoticeFill = Color(0xFFE6C8CA)

/** From `fontScale.vertical` the table stacks each row's counts under its item (screens-sr s0: numbers never wrap or clip). */
@Composable
private fun tableStacked(): Boolean = LocalDensity.current.fontScale >= AronTokens.Size.VerticalFontScale

/**
 * Sales Submit (F-SR-034/035; PARITY-SPEC sr/sales-submit.png): the device status at the end of the first line (green dot and word),
 * the purple-headed reconciliation table (item, device, server; a waiting or mismatching row carries its word), the gate notices
 * (the mismatch notice carries "Sync again"), the red sync-before-submit notice, then the full-width "Sync data" and "Submit sales"
 * buttons. The primary never commits on its tap: it opens the hold step, and [onSubmit] fires only when the press-and-hold
 * completes. Submit is enabled only by [SubmitGate]; offline the primary says the submit will go when the phone connects (the
 * submit is queued as the last event of the day, [SalesSubmitRules]); once the server has settled the submit the table gives way
 * to the success state (the record count and the check-out reminder).
 */
@Composable
fun SalesSubmitScreen(
    online: Boolean,
    counts: List<CountRow>,
    gate: SubmitGate,
    progress: SubmitProgress,
    typeLabel: (String) -> String,
    onSync: () -> Unit,
    onSubmit: () -> Unit,
    modifier: Modifier = Modifier,
    syncing: Boolean = false,
    /** The Dhaka time of the last server contact (pre-formatted, localised digits), beside the status when known. */
    lastContactText: String? = null,
    /** DISPLAY ONLY: record types shown as one table row (see [SubmitDisplay]); null keeps one row per type. The gate and the submit never see it. */
    rowGroups: List<List<String>>? = null,
    /** DISPLAY ONLY: without [rowGroups], show the legacy five rows (outlet, sale, stock, QC, promotion) instead of one row per record type; the gate never sees it. */
    legacyFiveRows: Boolean = true,
    /** D-NIGHT-DAYCLOSE-01 (b): the refused, held and retry rows of the phone's ack registry, listed with type, memo or outlet, reason and outcome. */
    rejected: List<RejectedRow> = emptyList(),
    /** A digest has answered for the date; null infers it from the counts (a known server column). Before it: "Nothing to compare yet". */
    serverKnown: Boolean? = null,
    /** The business date on screen; a listed row of another date shows its date. */
    today: String? = null,
) {
    // A type the host has no label for (memo_line, day_submit, ...) still reads in words, never as its wire name (checker)
    val known = recordTypeLabels()
    val res = androidx.compose.ui.platform.LocalContext.current.resources
    // used by the server-more notes and the rejected list (the table and the gate notices keep the host's own labels)
    val wordLabel: (String) -> String = { t -> typeLabel(t).takeIf { it != t } ?: known[t] ?: res.getString(R.string.submit_type_other, t) }
    val look = LocalAronLook.current
    val bn = LocalAppLanguage.current == AppLanguage.BN
    var hold by rememberSaveable { mutableStateOf(false) }
    var duesAsk by rememberSaveable { mutableStateOf(false) }
    val canSubmit = gate.enabled && progress == SubmitProgress.Idle
    // the hold step closes itself once the submit is no longer possible (an effect, not a write during composition)
    LaunchedEffect(canSubmit) { if (!canSubmit) { hold = false; duesAsk = false } }
    val reason = when {
        progress == SubmitProgress.Queued -> stringResource(R.string.submit_reason_queued)
        progress == SubmitProgress.Settled || gate.blocks.any { it == SubmitBlock.AlreadySubmitted } -> stringResource(R.string.submit_blocked_done)
        gate.blocks.any { it is SubmitBlock.Unsynced } -> stringResource(R.string.submit_reason_unsynced)
        gate.blocks.any { it is SubmitBlock.CountMismatch } -> stringResource(R.string.submit_reason_mismatch)
        else -> stringResource(R.string.submit_reason_unsynced)
    }
    Box(modifier.fillMaxSize().background(look.page)) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))
                .padding(AronTokens.Space.Screen),
            verticalArrangement = Arrangement.spacedBy(AronTokens.Space.M),
        ) {
            // device status: derived from the last server contact
            AronStatusDot(
                stringResource(R.string.submit_status_title), stringResource(if (online) R.string.submit_status_online else R.string.submit_status_offline), online,
                Modifier.fillMaxWidth(),
            )
            if (progress == SubmitProgress.Settled) {
                // the day is closed: the success state replaces the table (screens-sr s11)
                SettledState(counts.sumOf { it.device })
            } else {
                if (progress == SubmitProgress.Queued) NoticeCard(stringResource(R.string.submit_queued), Notice.Info)
                // the gate: the sync-before-submit notice and a mismatch are warnings, never silent; notes inform
                val mismatches = gate.blocks.filterIsInstance<SubmitBlock.CountMismatch>()
                gate.blocks.filter { it !is SubmitBlock.CountMismatch }.forEach { b -> NoticeCard(blockText(b, typeLabel), Notice.Warning) }
                if (mismatches.isNotEmpty()) {
                    // one notice names every mismatch (never silent) and offers the sync again; the table row carries the same word
                    val lines = mismatches.map { blockText(it, typeLabel) }
                    AronNoticeCard(
                        lines.joinToString(separator = "\n"),
                        action = if (SalesSubmitRules.syncButtonEnabled(online, syncing)) stringResource(R.string.submit_sync_again) else null, onAction = onSync,
                    )
                }
                // D-NIGHT-DAYCLOSE-01 (b): the rows themselves, not a bare count, whether or not a digest has run
                if (rejected.isNotEmpty()) RejectedList(rejected, wordLabel, bn, today)
                else gate.notes.forEach { n -> NoticeCard(blockText(n, typeLabel), Notice.Info) }
                // (a): a server count above the phone's is information (other phones, earlier installs of this login), never a mismatch
                val extras = counts.filter { it.serverExtra > 0 }
                if (extras.isNotEmpty()) {
                    NoticeCard(
                        extras.map { stringResource(R.string.submit_server_more, localizedNumber(it.serverExtra.toLong()), wordLabel(it.recordType)) }.joinToString("\n"),
                        Notice.Info,
                    )
                }
                gate.duesWarning?.let { d ->
                    val money = Formats.money(d.outstandingMtk, if (bn) UiLocale.BN else UiLocale.EN)
                    NoticeCard(stringResource(R.string.submit_dues_warning, localizedNumber(d.retailersWithDues.toLong()), money), Notice.Warning)
                }
                CountsTable(
                    if (rowGroups == null && legacyFiveRows) SubmitDisplay.legacyFive(counts).ifEmpty { SubmitDisplay.views(counts, null) } else SubmitDisplay.views(counts, rowGroups), typeLabel,
                    serverKnown = serverKnown ?: counts.any { it.server != null },
                )
                if (progress == SubmitProgress.Idle) { Spacer(Modifier.height(AronTokens.Space.M)); NoticeCard(stringResource(R.string.submit_info), Notice.Warning) }
            }
            AronPrimaryButton(
                stringResource(R.string.submit_sync), onSync, Modifier.fillMaxWidth(),
                enabled = SalesSubmitRules.syncButtonEnabled(online, syncing), glyph = ButtonGlyph.Sync,
            )
            AronPrimaryButton(
                stringResource(if (canSubmit && !online) R.string.submit_button_offline else R.string.submit_button), { if (gate.duesWarning != null) duesAsk = true else hold = true },
                Modifier.fillMaxWidth(), enabled = canSubmit, glyph = ButtonGlyph.Submit,
            )
            if (!canSubmit) Text(reason, style = MaterialTheme.typography.bodySmall, color = look.inkMuted, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        }
        if (duesAsk) {
            // SS4: the manual asks first, in a green Yes / red No dialog; Yes opens the hold step (the close of the day stays irreversible)
            AronConfirmDialog(
                title = stringResource(R.string.submit_dues_title),
                message = stringResource(R.string.submit_dues_ask, localizedNumber((gate.duesWarning?.retailersWithDues ?: 0).toLong())),
                confirmLabel = stringResource(R.string.submit_yes), dismissLabel = stringResource(R.string.submit_no),
                onConfirm = { duesAsk = false; hold = true }, onDismiss = { duesAsk = false },
            )
        }
        if (hold) {
            // the hold step (screens-sr s11, the irreversible close of the day): the kit sheet with the press-and-hold confirm; Back, a tap outside or "Cancel" close it without committing
            AronSheet(stringResource(R.string.submit_hold_title), onDismiss = { hold = false }) {
                Text(stringResource(R.string.submit_hold_summary, localizedNumber(counts.sumOf { it.device }.toLong())), style = MaterialTheme.typography.bodyMedium, color = look.inkMuted, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(AronTokens.Space.M))
                AronHoldButton(stringResource(R.string.submit_hold_label), { hold = false; onSubmit() }, Modifier.testTag(SalesSubmitTags.HOLD))
                Text(stringResource(R.string.submit_hold_hint), style = MaterialTheme.typography.bodySmall, color = look.inkMuted, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(vertical = AronTokens.Space.S))
                AronLinkButton(stringResource(R.string.submit_cancel), { hold = false }, Modifier.fillMaxWidth())
                Spacer(Modifier.height(AronTokens.Space.M))
            }
        }
    }
}

private enum class Notice { Info, Warning, Success }

/** A notice card (sr/sales-submit.png): the warning is the pink card with the red edge, an info or success note the white card. */
@Composable
private fun NoticeCard(text: String, kind: Notice, title: String? = null, actionLabel: String? = null, onAction: () -> Unit = {}) {
    if (kind == Notice.Warning && title == null && actionLabel == null) { AronNoticeCard(text); return }
    val look = LocalAronLook.current
    val warn = kind == Notice.Warning
    val shape = RoundedCornerShape(AronLook.CardRadius)
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).clip(shape).background(if (warn) NoticeFill else look.card)) {
        if (warn) Box(Modifier.width(5.dp).fillMaxHeight().background(look.red))
        Column(Modifier.weight(1f).padding(AronTokens.Space.L), verticalArrangement = Arrangement.spacedBy(AronTokens.Space.S)) {
            if (title != null) Text(title, style = MaterialTheme.typography.titleSmall, color = look.ink)
            Text(text, style = MaterialTheme.typography.bodyMedium, color = if (kind == Notice.Success) OkInk else look.ink)
            if (actionLabel != null) AronLinkButton(actionLabel, onAction)
        }
    }
}

/** The success state once the server has settled the submit: the check disc, the title, the record count and the check-out reminder. */
@Composable
private fun SettledState(records: Int) {
    val look = LocalAronLook.current
    Column(
        Modifier.fillMaxWidth().padding(vertical = AronTokens.Space.Xl),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(AronTokens.Space.M),
    ) {
        Box(Modifier.size(96.dp).clip(CircleShape).background(OnlineGreen), contentAlignment = Alignment.Center) {
            Icon(AronIcons.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(56.dp))
        }
        Text(stringResource(R.string.submit_success), style = MaterialTheme.typography.titleMedium, color = look.ink, textAlign = TextAlign.Center)
        Text(stringResource(R.string.submit_success_records, localizedNumber(records.toLong())), style = MaterialTheme.typography.bodyLarge, color = look.inkMuted, textAlign = TextAlign.Center)
        Text(stringResource(R.string.submit_success_checkout), style = MaterialTheme.typography.bodySmall, color = look.inkMuted, textAlign = TextAlign.Center)
    }
}

/** The reconciliation table: the purple header (item, device, server) over white rows; only a waiting or mismatching row carries its word. */
@Composable
private fun CountsTable(counts: List<CountView>, typeLabel: (String) -> String, serverKnown: Boolean) {
    val look = LocalAronLook.current
    val stacked = tableStacked()
    if (!stacked && counts.isNotEmpty()) {
        val waitingWord = stringResource(R.string.submit_row_waiting)
        val mismatchWord = stringResource(R.string.submit_row_mismatch)
        val blank = stringResource(R.string.submit_server_blank)
        AronDataTable(
            headers = listOf(stringResource(R.string.submit_col_item), stringResource(R.string.submit_col_device), stringResource(R.string.submit_col_server)),
            // D-NIGHT-DAYCLOSE-01 (c): the count columns are wide enough for "Phone" / "Server" in one line at 360 px (never "Serve r")
            weights = listOf(1.5f, 1f, 1f),
            rows = counts.map { row ->
                val word = when { row.server == null -> waitingWord; !row.matches -> mismatchWord; else -> null }
                listOf(listOfNotNull(typeLabel(row.key), word).joinToString("\n"), localizedNumber(row.device.toLong()), row.server?.let { localizedNumber(it.toLong()) } ?: blank)
            },
        )
        return
    }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(AronLook.CardRadius)).background(look.card)) {
        Row(
            Modifier.fillMaxWidth().background(look.purple).padding(horizontal = AronTokens.Space.L, vertical = AronTokens.Space.M),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AronTokens.Space.S),
        ) {
            Text(stringResource(R.string.submit_col_item), style = MaterialTheme.typography.titleMedium, color = Color.White, modifier = Modifier.weight(1f))
            if (!stacked) {
                Text(stringResource(R.string.submit_col_device), style = MaterialTheme.typography.titleMedium, color = Color.White, textAlign = TextAlign.End, modifier = Modifier.widthIn(min = CountColumn))
                Text(stringResource(R.string.submit_col_server), style = MaterialTheme.typography.titleMedium, color = Color.White, textAlign = TextAlign.End, modifier = Modifier.widthIn(min = CountColumn))
            }
        }
        if (counts.isEmpty()) Text(stringResource(if (serverKnown) R.string.submit_no_records else R.string.submit_no_counts), style = MaterialTheme.typography.bodyMedium, color = look.inkMuted, modifier = Modifier.padding(AronTokens.Space.L))
        counts.forEachIndexed { i, row ->
            val waiting = row.server == null
            val mismatch = !waiting && !row.matches
            val ink = if (mismatch) WarnInk else look.ink
            if (i > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(look.tileHairline))
            Column(Modifier.fillMaxWidth().padding(horizontal = AronTokens.Space.L, vertical = AronTokens.Space.M), verticalArrangement = Arrangement.spacedBy(AronTokens.Space.Xs)) {
                val deviceText = localizedNumber(row.device.toLong())
                val serverText = row.server?.let { localizedNumber(it.toLong()) } ?: stringResource(R.string.submit_server_blank)
                if (stacked) {
                    // large text: the item on its own line, then "Device" and "Server" as caption-over-value cells
                    Text(typeLabel(row.key), style = MaterialTheme.typography.bodyLarge, color = ink, maxLines = 2)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(AronTokens.Space.M)) {
                        CountCell(stringResource(R.string.submit_col_device), deviceText, ink, Modifier.weight(1f))
                        CountCell(stringResource(R.string.submit_col_server), serverText, ink, Modifier.weight(1f))
                    }
                } else {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AronTokens.Space.S)) {
                        Text(typeLabel(row.key), style = MaterialTheme.typography.bodyLarge, color = ink, maxLines = 2, modifier = Modifier.weight(1f))
                        Text(deviceText, Modifier.widthIn(min = CountColumn), style = MaterialTheme.typography.bodyLarge, color = ink, textAlign = TextAlign.End)
                        Text(serverText, Modifier.widthIn(min = CountColumn), style = MaterialTheme.typography.bodyLarge, color = ink, textAlign = TextAlign.End)
                    }
                }
                when {
                    waiting -> Text(stringResource(R.string.submit_row_waiting), style = MaterialTheme.typography.bodySmall, color = WarnInk)
                    mismatch -> Text(stringResource(R.string.submit_row_mismatch), style = MaterialTheme.typography.bodySmall, color = WarnInk)
                }
            }
        }
    }
}

/** A caption over a count (the stacked form of the table at large text). */
@Composable
private fun CountCell(caption: String, value: String, ink: Color, modifier: Modifier = Modifier) {
    val look = LocalAronLook.current
    Column(modifier) {
        Text(caption, style = MaterialTheme.typography.bodySmall, color = look.inkMuted, maxLines = 1)
        Text(value, style = MaterialTheme.typography.titleSmall, color = ink)
    }
}

/** Words for the record types a host may not label (the Sales Submit notes and list name them; never a wire name alone). */
@Composable
private fun recordTypeLabels(): Map<String, String> = mapOf(
    "memo_line" to stringResource(R.string.submit_type_memo_line),
    "day_submit" to stringResource(R.string.submit_type_day_submit),
    "print_event" to stringResource(R.string.submit_type_print_event),
    "outlet_change_request" to stringResource(R.string.submit_type_outlet_request),
    "task_event" to stringResource(R.string.submit_type_task_event),
)

/** D-NIGHT-DAYCLOSE-01 (b): one card listing each refused, held or retry row: what it is, why, and what happens to it. */
@Composable
private fun RejectedList(rows: List<RejectedRow>, typeLabel: (String) -> String, bn: Boolean, today: String?) {
    val look = LocalAronLook.current
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(AronLook.CardRadius)).background(look.card).padding(AronTokens.Space.L).testTag(SalesSubmitTags.REJECTED),
        verticalArrangement = Arrangement.spacedBy(AronTokens.Space.M),
    ) {
        Text(stringResource(R.string.submit_rejected_title, localizedNumber(rows.size.toLong())), style = MaterialTheme.typography.titleSmall, color = look.ink)
        rows.forEach { r ->
            Column(verticalArrangement = Arrangement.spacedBy(AronTokens.Space.Xs)) {
                val which = r.memoNo ?: r.outletName
                val date = r.businessDate.takeIf { today != null && it != today }?.let { localizedDigits(it) }
                Text(listOfNotNull(typeLabel(r.recordType), which, date).joinToString(" · "), style = MaterialTheme.typography.bodyLarge, color = look.ink)
                val reason = (if (bn) r.reasonBn ?: r.reasonEn else r.reasonEn ?: r.reasonBn) ?: r.code?.let { stringResource(R.string.submit_rejected_code, it) }
                if (reason != null) Text(reason, style = MaterialTheme.typography.bodyMedium, color = look.inkMuted)
                Text(
                    stringResource(
                        when (r.outcome) {
                            RejectedOutcome.STORED_FLAGGED -> R.string.submit_rejected_flagged
                            RejectedOutcome.DROPPED -> R.string.submit_rejected_dropped
                            RejectedOutcome.RETRY -> R.string.submit_rejected_retry
                        },
                    ),
                    style = MaterialTheme.typography.bodySmall, color = if (r.outcome == RejectedOutcome.RETRY) WarnInk else OkInk,
                )
            }
        }
    }
}

@Composable
private fun blockText(b: SubmitBlock, typeLabel: (String) -> String): String = when (b) {
    is SubmitBlock.Unsynced -> stringResource(R.string.submit_blocked_unsynced, localizedNumber((b.pending + b.inFlight).toLong()))
    is SubmitBlock.CountMismatch -> stringResource(R.string.submit_blocked_mismatch, typeLabel(b.recordType), localizedNumber(b.device.toLong()), b.server?.let { localizedNumber(it.toLong()) } ?: stringResource(R.string.submit_server_blank))
    SubmitBlock.AlreadySubmitted -> stringResource(R.string.submit_blocked_done)
    is SubmitBlock.Rejected -> stringResource(R.string.submit_note_rejected, localizedNumber(b.count.toLong()))
    is SubmitBlock.Quarantined -> stringResource(R.string.submit_note_quarantined, localizedNumber(b.count.toLong()))
}

