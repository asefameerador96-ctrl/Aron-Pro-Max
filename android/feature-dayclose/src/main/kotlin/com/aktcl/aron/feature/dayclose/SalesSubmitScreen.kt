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
import com.aktcl.aron.core.ui.AronBanner
import com.aktcl.aron.core.ui.AronBarAction
import com.aktcl.aron.core.ui.AronBottomBar
import com.aktcl.aron.core.ui.AronCard
import com.aktcl.aron.core.ui.AronEasing
import com.aktcl.aron.core.ui.AronIcons
import com.aktcl.aron.core.ui.AronIllustration
import com.aktcl.aron.core.ui.AronPressAndHoldButton
import com.aktcl.aron.core.ui.AronTextButton
import com.aktcl.aron.core.ui.AronTokens
import com.aktcl.aron.core.ui.BannerKind
import com.aktcl.aron.core.ui.GlassKind
import com.aktcl.aron.core.ui.GlassSurface
import com.aktcl.aron.core.ui.LocalAppLanguage
import com.aktcl.aron.core.ui.LocalAronColors
import com.aktcl.aron.core.ui.LocalReducedMotion
import com.aktcl.aron.core.ui.MoneyText
import com.aktcl.aron.core.ui.localizedNumber
import com.aktcl.aron.rules.Formats
import com.aktcl.aron.rules.UiLocale

/** What the Sales Submit screen shows after the submit was tapped. */
enum class SubmitProgress { Idle, Queued, Settled }

object SalesSubmitTags {
    /** The press-and-hold button of the hold step (the bar primary opens it; the hold commits). */
    const val HOLD = "submit_hold"
}

/** The least width of the count columns of the reconciliation table on a 328 dp content width (screens-sr s11); a wider count grows its column. */
private val CountColumn = 56.dp

/** From `fontScale.vertical` the table stacks each row's counts under its item (screens-sr s0: numbers never wrap or clip). */
@Composable
private fun tableStacked(): Boolean = LocalDensity.current.fontScale >= AronTokens.Size.VerticalFontScale

/** The three steps of the day close (screens-sr s11): sync, reconcile, submit. */
private enum class SubmitStep { Sync, Match, Submit }

/**
 * Sales Submit (F-SR-034/035; screens-sr s11): the device status chip row, the three-step row (sync, match, submit; the
 * current step in `accent.container`), the reconciliation table (item, device, server; equal rows quiet, a waiting row
 * and a mismatch row in warning words), the gate banners (the mismatch banner carries "Sync again"), the info caption,
 * and the bar with "Sync data" (secondary, busy arc while [syncing]) and "Submit sales" (primary). The primary never
 * commits on its tap: it opens the hold step, and [onSubmit] fires only when the press-and-hold completes. Submit is
 * enabled only by [SubmitGate]; offline the primary says the submit will go when the phone connects (the submit is
 * queued as the last event of the day, [SalesSubmitRules]); once the server has settled the submit the table gives way
 * to the full success state (the 96 dp disc, the record count and the check-out reminder).
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
    /** The Dhaka time of the last server contact (pre-formatted, localised digits), beside the status chip when known. */
    lastContactText: String? = null,
) {
    val c = LocalAronColors.current
    val bn = LocalAppLanguage.current == AppLanguage.BN
    var hold by rememberSaveable { mutableStateOf(false) }
    val canSubmit = gate.enabled && progress == SubmitProgress.Idle
    // the hold step closes itself once the submit is no longer possible (an effect, not a write during composition)
    LaunchedEffect(canSubmit) { if (hold && !canSubmit) hold = false }
    val allMatched = counts.isNotEmpty() && counts.all { it.matches }
    val step = when {
        progress != SubmitProgress.Idle || gate.blocks.any { it == SubmitBlock.AlreadySubmitted } || canSubmit -> SubmitStep.Submit
        gate.blocks.any { it is SubmitBlock.Unsynced } -> SubmitStep.Sync
        else -> SubmitStep.Match
    }
    val reason = when {
        progress == SubmitProgress.Queued -> stringResource(R.string.submit_reason_queued)
        progress == SubmitProgress.Settled || gate.blocks.any { it == SubmitBlock.AlreadySubmitted } -> stringResource(R.string.submit_blocked_done)
        gate.blocks.any { it is SubmitBlock.Unsynced } -> stringResource(R.string.submit_reason_unsynced)
        gate.blocks.any { it is SubmitBlock.CountMismatch } -> stringResource(R.string.submit_reason_mismatch)
        else -> stringResource(R.string.submit_reason_unsynced)
    }
    Box(modifier.fillMaxSize()) {
        ScreenWithBar(Modifier.fillMaxSize(), bar = {
            Column(Modifier.fillMaxWidth()) {
                AronBottomBar(
                    secondary = AronBarAction(
                        stringResource(R.string.submit_sync), onSync,
                        enabled = SalesSubmitRules.syncButtonEnabled(online, syncing), busy = syncing, // no icon: the Bangla label needs the width (screens-sr s11)
                    ),
                    primary = AronBarAction(
                        stringResource(if (canSubmit && !online) R.string.submit_button_offline else R.string.submit_button), { hold = true },
                        enabled = canSubmit, icon = AronIcons.Submit,
                    ),
                    reasonWhenDisabled = reason,
                )
            }
        }) {
            ScrollColumn(contentPadding = PaddingValues(AronTokens.Space.Screen), verticalArrangement = Arrangement.spacedBy(AronTokens.Space.M)) {
                // device status: derived from the last server contact; quiet when online
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AronTokens.Space.M)) {
                    Text(stringResource(R.string.submit_status_title), style = MaterialTheme.typography.labelMedium, color = c.textSecondary, modifier = Modifier.weight(1f))
                    if (online) Pill(null, c.successOnContainer, AronIcons.CheckCircle, stringResource(R.string.submit_status_online))
                    else Pill(c.offlineContainer, c.offlineOnContainer, AronIcons.CloudOff, stringResource(R.string.submit_status_offline))
                }
                lastContactText?.let { Text(stringResource(R.string.submit_last_contact, it), style = MaterialTheme.typography.bodySmall, color = c.textSecondary) }
                StepRow(step)
                if (progress == SubmitProgress.Settled) {
                    // the day is closed: the full success state replaces the table (screens-sr s11)
                    SettledState(counts.sumOf { it.device })
                } else {
                    if (progress == SubmitProgress.Queued) AronBanner(stringResource(R.string.submit_queued), kind = BannerKind.Info)
                    // the gate: the sync-before-submit notice and a mismatch are warnings, never silent; notes inform
                    val mismatches = gate.blocks.filterIsInstance<SubmitBlock.CountMismatch>()
                    gate.blocks.filter { it !is SubmitBlock.CountMismatch }.forEach { b -> AronBanner(blockText(b, typeLabel), kind = BannerKind.Warning) }
                    if (mismatches.isNotEmpty()) {
                        // one banner names every mismatch (never silent) and offers the sync again; the table row carries the same word
                        val lines = mismatches.map { blockText(it, typeLabel) }
                        AronBanner(
                            lines.joinToString(separator = "\n"), kind = BannerKind.Warning, title = stringResource(R.string.submit_row_mismatch),
                            actionLabel = if (SalesSubmitRules.syncButtonEnabled(online, syncing)) stringResource(R.string.submit_sync_again) else null, onAction = onSync,
                        )
                    }
                    gate.notes.forEach { n -> AronBanner(blockText(n, typeLabel), kind = BannerKind.Info) }
                    gate.duesWarning?.let { d ->
                        val money = Formats.money(d.outstandingMtk, if (bn) UiLocale.BN else UiLocale.EN)
                        AronBanner(stringResource(R.string.submit_dues_warning, localizedNumber(d.retailersWithDues.toLong()), money), kind = BannerKind.Warning)
                    }
                    if (allMatched && gate.blocks.isEmpty() && progress == SubmitProgress.Idle) AronBanner(stringResource(R.string.submit_all_matched), kind = BannerKind.Success)
                    CountsCard(counts, typeLabel)
                    if (progress == SubmitProgress.Idle) Text(stringResource(R.string.submit_info), style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
                }
            }
        }
        val summary = stringResource(R.string.submit_hold_summary, localizedNumber(counts.sumOf { it.device }.toLong()))
        HoldSheet(hold, stringResource(R.string.submit_hold_title), AronIcons.Submit, summary, onCancel = { hold = false }, onConfirmed = { hold = false; onSubmit() })
    }
}

/** The three-step row: three pills, the current step in `accent.container` with its number, the done steps with a check, the rest quiet. */
@Composable
private fun StepRow(current: SubmitStep) {
    val c = LocalAronColors.current
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(AronTokens.Space.S), verticalAlignment = Alignment.CenterVertically) {
        SubmitStep.entries.forEach { step ->
            val label = stringResource(when (step) { SubmitStep.Sync -> R.string.submit_step_sync; SubmitStep.Match -> R.string.submit_step_match; SubmitStep.Submit -> R.string.submit_step_submit })
            when {
                step == current -> Pill(c.accentContainer, c.accentOnContainer, AronIcons.ChevronRight, label)
                step.ordinal < current.ordinal -> Pill(null, c.successOnContainer, AronIcons.Check, label)
                else -> Pill(null, c.textSecondary, AronIcons.Clock, label)
            }
        }
    }
}

/** The success state once the server has settled the submit: the 96 dp disc, the title, the record count and the check-out reminder. */
@Composable
private fun SettledState(records: Int) {
    val c = LocalAronColors.current
    Column(
        Modifier.fillMaxWidth().padding(vertical = AronTokens.Space.Xl),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(AronTokens.Space.M),
    ) {
        AronIllustration(AronIcons.CheckCircle, c.successContainer, c.successOnContainer)
        Text(stringResource(R.string.submit_success), style = MaterialTheme.typography.titleMedium, color = c.textPrimary, textAlign = TextAlign.Center)
        Text(stringResource(R.string.submit_success_records, localizedNumber(records.toLong())), style = MaterialTheme.typography.bodyLarge, color = c.textSecondary, textAlign = TextAlign.Center)
        Text(stringResource(R.string.submit_success_checkout), style = MaterialTheme.typography.bodySmall, color = c.textSecondary, textAlign = TextAlign.Center)
    }
}

/** The reconciliation table: item, device, server, and the row's status word with its icon (equal rows quiet). */
@Composable
private fun CountsCard(counts: List<CountRow>, typeLabel: (String) -> String) {
    val c = LocalAronColors.current
    val stacked = tableStacked()
    AronCard(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(AronTokens.Space.L), verticalArrangement = Arrangement.spacedBy(AronTokens.Space.S)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AronTokens.Space.S)) {
                Text(stringResource(R.string.submit_col_item), style = MaterialTheme.typography.labelMedium, color = c.textSecondary, modifier = Modifier.weight(1f))
                if (!stacked) {
                    Text(stringResource(R.string.submit_col_device), style = MaterialTheme.typography.labelMedium, color = c.textSecondary, textAlign = TextAlign.End, modifier = Modifier.widthIn(min = CountColumn))
                    Text(stringResource(R.string.submit_col_server), style = MaterialTheme.typography.labelMedium, color = c.textSecondary, textAlign = TextAlign.End, modifier = Modifier.widthIn(min = CountColumn))
                }
            }
            if (counts.isEmpty()) Text(stringResource(R.string.submit_no_counts), style = MaterialTheme.typography.bodyMedium, color = c.textSecondary)
            counts.forEach { row ->
                val waiting = row.server == null
                val mismatch = !waiting && !row.matches
                val quiet = row.matches
                val bg = if (mismatch) c.warningContainer else null
                val ink = if (mismatch) c.warningOnContainer else c.textPrimary
                Column(
                    Modifier
                        .fillMaxWidth()
                        .drawBehind { bg?.let { drawRoundRect(it, cornerRadius = CornerRadius(AronTokens.Radius.Chip.toPx())) } }
                        .padding(horizontal = if (mismatch) AronTokens.Space.S else 0.dp, vertical = AronTokens.Space.Xs),
                    verticalArrangement = Arrangement.spacedBy(AronTokens.Space.Xs),
                ) {
                    val deviceText = localizedNumber(row.device.toLong())
                    val serverText = row.server?.let { localizedNumber(it.toLong()) } ?: stringResource(R.string.submit_server_blank)
                    if (stacked) {
                        // large text: the item on its own line, then "Device" and "Server" as caption-over-value cells
                        Text(typeLabel(row.recordType), style = MaterialTheme.typography.bodyLarge, color = ink, maxLines = 2)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(AronTokens.Space.M)) {
                            CountCell(stringResource(R.string.submit_col_device), deviceText, ink, Modifier.weight(1f))
                            CountCell(stringResource(R.string.submit_col_server), serverText, ink, Modifier.weight(1f))
                        }
                    } else {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AronTokens.Space.S)) {
                            Text(typeLabel(row.recordType), style = MaterialTheme.typography.bodyLarge, color = ink, maxLines = 2, modifier = Modifier.weight(1f))
                            MoneyText(deviceText, Modifier.widthIn(min = CountColumn), style = MaterialTheme.typography.titleSmall, color = ink, textAlign = TextAlign.End)
                            MoneyText(serverText, Modifier.widthIn(min = CountColumn), style = MaterialTheme.typography.titleSmall, color = ink, textAlign = TextAlign.End)
                        }
                    }
                    when {
                        quiet -> Pill(null, c.successOnContainer, AronIcons.Check, stringResource(R.string.submit_row_matched))
                        waiting -> Pill(c.warningContainer, c.warningOnContainer, AronIcons.Clock, stringResource(R.string.submit_row_waiting))
                        else -> Pill(null, c.warningOnContainer, AronIcons.Warning, stringResource(R.string.submit_row_mismatch))
                    }
                }
            }
        }
    }
}

/** A caption over a count (the stacked form of the table at large text). */
@Composable
private fun CountCell(caption: String, value: String, ink: Color, modifier: Modifier = Modifier) {
    val c = LocalAronColors.current
    Column(modifier) {
        Text(caption, style = MaterialTheme.typography.bodySmall, color = c.textSecondary, maxLines = 1)
        MoneyText(value, style = MaterialTheme.typography.titleSmall, color = ink)
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

/** A 28 dp pill with an icon and a word in opaque container colours; [container] null is the quiet form (word and icon only). */
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

/** A 44 dp squircle well in `accent.container` with a 24 dp glyph (android-glass s6.4). */
@Composable
private fun IconWell(icon: ImageVector) {
    val c = LocalAronColors.current
    val fill = c.accentContainer
    Box(Modifier.size(AronTokens.Size.Well).drawBehind { drawRoundRect(fill, cornerRadius = CornerRadius(AronTokens.Radius.Well.toPx())) }, contentAlignment = Alignment.Center) {
        Icon(icon, contentDescription = null, tint = c.accentOnContainer, modifier = Modifier.size(AronTokens.Size.Icon))
    }
}

/**
 * The hold step (screens-sr s11, the irreversible close of the day): an in-tree solid Sheet surface over a scrim with the
 * title, the record count and the press-and-hold button; the scrim, Back or "Cancel" close it without committing.
 * TalkBack users confirm with the long-click action. Motion (android-glass s6.8): the sheet translates up on a spring
 * and down over `motion.base` on exit while the scrim fades in the draw phase (no offscreen layer); reduced motion: a
 * 120 ms fade. Until an overlay host exists the sheet lives in the content slot, so the scrim stops under the host top
 * bar (open issue for core-ui).
 */
@Composable
private fun HoldSheet(visible: Boolean, title: String, icon: ImageVector, summary: String, onCancel: () -> Unit, onConfirmed: () -> Unit) {
    val c = LocalAronColors.current
    val reduced = LocalReducedMotion.current
    val scrim = c.surfaceScrim
    val handle = c.textSecondary.copy(alpha = 0.7f)
    BackHandler(enabled = visible, onBack = onCancel)
    val scrimT by animateFloatAsState(if (visible) 1f else 0f, tween(if (reduced) AronTokens.Motion.Reduced else AronTokens.Motion.Base, easing = LinearEasing))
    AnimatedVisibility(
        visible,
        enter = if (reduced) fadeIn(tween(AronTokens.Motion.Reduced, easing = LinearEasing)) else EnterTransition.None,
        exit = if (reduced) fadeOut(tween(AronTokens.Motion.Reduced, easing = LinearEasing)) else ExitTransition.None,
    ) {
        // the sheet's own enter and exit inside the overlay: a spring translation up, a `motion.base` ease-in down
        val sheetMotion = if (reduced) Modifier else Modifier.animateEnterExit(
            enter = slideInVertically(spring(stiffness = Spring.StiffnessMediumLow, visibilityThreshold = IntOffset.VisibilityThreshold)) { it },
            exit = slideOutVertically(tween(AronTokens.Motion.Base, easing = AronEasing.In)) { it },
        )
        Box(
            Modifier
                .fillMaxSize()
                .drawBehind { drawRect(scrim.copy(alpha = scrim.alpha * scrimT)) }
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onCancel),
        ) {
            GlassSurface(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .then(sheetMotion)
                    .pointerInput(Unit) { detectTapGestures { } }, // the sheet swallows taps without an actionable node for TalkBack
                kind = GlassKind.Sheet,
            ) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))
                        .padding(start = AronTokens.Space.L, end = AronTokens.Space.L, bottom = AronTokens.Space.L),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(AronTokens.Space.M),
                ) {
                    Box(Modifier.padding(top = AronTokens.Space.S, bottom = AronTokens.Space.S).size(36.dp, 4.dp).drawBehind { drawRoundRect(handle, cornerRadius = CornerRadius(2.dp.toPx())) })
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AronTokens.Space.M)) {
                        IconWell(icon)
                        Text(title, style = MaterialTheme.typography.titleLarge, color = c.textPrimary, modifier = Modifier.weight(1f))
                    }
                    Text(summary, style = MaterialTheme.typography.bodyMedium, color = c.textSecondary, modifier = Modifier.fillMaxWidth())
                    AronPressAndHoldButton(stringResource(R.string.submit_hold_label), onConfirmed, Modifier.testTag(SalesSubmitTags.HOLD))
                    Text(stringResource(R.string.submit_hold_hint), style = MaterialTheme.typography.bodySmall, color = c.textSecondary, textAlign = TextAlign.Center)
                    AronTextButton(stringResource(R.string.submit_cancel), onCancel, Modifier.fillMaxWidth())
                }
            }
        }
    }
}
