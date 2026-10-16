package com.aktcl.aron.feature.attendance

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Animatable
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
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.aktcl.aron.core.ui.AronBanner
import com.aktcl.aron.core.ui.AronEasing
import com.aktcl.aron.core.ui.AronCard
import com.aktcl.aron.core.ui.AronIconButton
import com.aktcl.aron.core.ui.AronIcons
import com.aktcl.aron.core.ui.AronPressAndHoldButton
import com.aktcl.aron.core.ui.AronPrimaryButton
import com.aktcl.aron.core.ui.AronTextButton
import com.aktcl.aron.core.ui.AronTokens
import com.aktcl.aron.core.ui.BannerKind
import com.aktcl.aron.core.ui.GlassKind
import com.aktcl.aron.core.ui.GlassSurface
import com.aktcl.aron.core.ui.LocalAronColors
import com.aktcl.aron.core.ui.LocalReducedMotion
import com.aktcl.aron.core.ui.localizedDigits
import com.aktcl.aron.core.ui.localizedNumber
import java.time.Instant

object AttendanceTags {
    const val PROMPT = "att_prompt"
    const val CHECK_IN = "att_check_in"
    const val CHECK_OUT = "att_check_out"
    const val ADDRESS = "att_address"
    const val STATUS = "att_status"
    /** The press-and-hold button of the hold sheet (one sheet for check-in and check-out). */
    const val HOLD = "att_hold"
    const val REFRESH = "att_refresh"
}

/** `HH:mm` Asia/Dhaka (UTC+6) of an RFC 3339 instant; the screen shows corrected business time, never the raw clock. */
internal fun dhakaClock(iso: String): String = runCatching {
    val minutes = (Instant.parse(iso).epochSecond / 60 + 6 * 60).mod(24 * 60L)
    "%02d:%02d".format(java.util.Locale.ROOT, minutes / 60, minutes % 60)
}.getOrDefault(iso)

/** Fix quality of screens-sr s3 (design defaults): at most 30 m good, 31 to 100 fair, over 100 weak. Pure. */
enum class FixQuality {
    Good, Fair, Weak;

    companion object {
        fun of(accuracyM: Int): FixQuality = when {
            accuracyM <= 30 -> Good
            accuracyM <= 100 -> Fair
            else -> Weak
        }
    }
}

/**
 * Attendance (F-SR-011 check-in, F-SR-012 check-out; screens-sr s3). Stateless: the caller owns the flow. The location
 * card (coordinates offline, the address when known, the fix-quality chip), the day card (check-in and check-out rows),
 * the reference message as a caption above the bar, and one primary in the bar that follows the day. The tap never
 * commits: it opens the hold sheet, and [onCheckIn] or [onCheckOut] fires only when the press-and-hold completes.
 * Check-out is enabled from 17:00 on corrected Dhaka time. [nowIso] is the trusted clock of the hold sheet's time chip.
 */
@Composable
fun AttendanceContent(
    state: AttendanceState,
    checkoutAtLabel: String,
    onCheckIn: () -> Unit,
    onCheckOut: () -> Unit,
    modifier: Modifier = Modifier,
    onRefreshFix: (() -> Unit)? = null,
    nowIso: () -> String = { Instant.now().toString() },
) {
    val c = LocalAronColors.current
    var hold by rememberSaveable { mutableStateOf<String?>(null) }
    val checkOutPhase = state.checkedInAt != null
    val dayComplete = state.checkedInAt != null && state.checkedOutAt != null
    // the hold sheet closes itself when the flow is done or the day moved on (a kill and relaunch never reopens it)
    if (hold == HOLD_IN && checkOutPhase) hold = null
    if (hold == HOLD_OUT && dayComplete) hold = null
    Box(modifier.fillMaxSize()) {
        ScreenWithBar(Modifier.fillMaxSize(), bar = {
            Column(Modifier.fillMaxWidth()) {
                // the reference message once, as the caption above the bar (screens-sr s3)
                val prompt = when {
                    !checkOutPhase && state.showCheckInPrompt -> stringResource(R.string.att_prompt_checkin)
                    checkOutPhase && !dayComplete && state.checkOutEnabled -> stringResource(R.string.att_prompt_checkout)
                    else -> null
                }
                if (prompt != null) {
                    Text(
                        prompt, style = MaterialTheme.typography.bodySmall, color = c.textSecondary,
                        modifier = Modifier.testTag(AttendanceTags.PROMPT).padding(horizontal = AronTokens.Space.Screen, vertical = AronTokens.Space.S),
                    )
                }
                if (!dayComplete) {
                    // one bar geometry for both phases: the reason slot is reserved on Attendance, so the bar never jumps at check-in
                    if (!checkOutPhase) {
                        AttendanceBar(
                            stringResource(R.string.att_check_in), { hold = HOLD_IN }, AttendanceTags.CHECK_IN,
                            enabled = state.checkInEnabled && !state.busy, busy = state.busy, reason = null,
                        )
                    } else {
                        AttendanceBar(
                            stringResource(R.string.att_check_out), { hold = HOLD_OUT }, AttendanceTags.CHECK_OUT,
                            enabled = state.checkOutEnabled && !state.busy, busy = state.busy,
                            reason = stringResource(R.string.att_check_out_hint, localizedDigits(checkoutAtLabel)),
                        )
                    }
                }
            }
        }) {
            ScrollColumn(contentPadding = PaddingValues(AronTokens.Space.Screen), verticalArrangement = Arrangement.spacedBy(AronTokens.Space.M)) {
                if (state.lastFixMock) AronBanner(stringResource(R.string.att_mock_warning), kind = BannerKind.Warning)
                if (dayComplete) AronBanner(stringResource(R.string.att_day_complete_message), kind = BannerKind.Success)
                if (state.fixFailed && !state.readingFix && !state.busy) {
                    AronBanner(
                        stringResource(R.string.att_fix_none), kind = BannerKind.Warning,
                        actionLabel = if (onRefreshFix != null) stringResource(R.string.att_fix_retry) else null, onAction = onRefreshFix,
                    )
                }
                LocationCard(state, onRefreshFix)
                DayCard(state, checkoutAtLabel)
                Text(stringResource(R.string.att_offline_saved), style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
            }
        }
        // the sheet keeps its last kind while it slides out, so the words do not change mid-exit
        var lastHold by remember { mutableStateOf(HOLD_IN) }
        hold?.let { lastHold = it }
        val out = lastHold == HOLD_OUT
        val time = localizedDigits(remember(hold) { dhakaClock(nowIso()) })
        val fixLine = state.fixAccuracyM?.let { stringResource(R.string.att_fix_line, localizedNumber(it.toLong())) } ?: stringResource(R.string.att_fix_line_none)
        HoldSheet(
            visible = hold != null,
            title = stringResource(if (out) R.string.att_hold_title_out else R.string.att_hold_title_in),
            icon = if (out) AronIcons.Logout else AronIcons.Login,
            timeText = time, fixLine = fixLine,
            onCancel = { hold = null },
            onConfirmed = { hold = null; if (out) onCheckOut() else onCheckIn() },
        )
    }
}

private const val HOLD_IN = "in"
private const val HOLD_OUT = "out"

/** The location card: a Place well, the coordinates (offline, always), the address line when known, the fix-quality chip and the refresh icon. */
@Composable
private fun LocationCard(state: AttendanceState, onRefreshFix: (() -> Unit)?) {
    val c = LocalAronColors.current
    val reduced = LocalReducedMotion.current
    // one 1,200 ms linear turn of the refresh glyph per read (screens-sr s3 Motion); static under reduced motion
    val spin = remember { Animatable(0f) }
    LaunchedEffect(state.readingFix, reduced) {
        spin.snapTo(0f)
        if (state.readingFix && !reduced) spin.animateTo(360f, tween(AronTokens.Motion.SyncTurnMillis, easing = LinearEasing))
    }
    AronCard(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(AronTokens.Space.L), verticalArrangement = Arrangement.spacedBy(AronTokens.Space.S)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AronTokens.Space.M)) {
                IconWell(AronIcons.Place)
                Text(stringResource(R.string.att_location_title), style = MaterialTheme.typography.titleSmall, color = c.textPrimary, modifier = Modifier.weight(1f))
                if (onRefreshFix != null) {
                    AronIconButton(
                        AronIcons.Refresh, stringResource(R.string.att_refresh), onRefreshFix,
                        Modifier.testTag(AttendanceTags.REFRESH).graphicsLayer { rotationZ = spin.value },
                        enabled = !state.readingFix && !state.busy,
                    )
                }
            }
            val coords = state.coordinatesText ?: state.addressText
            if (state.readingFix) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AronTokens.Space.S)) {
                    ReadingArc(c.textSecondary)
                    Text(stringResource(R.string.att_reading_fix), style = MaterialTheme.typography.bodyLarge, color = c.textSecondary, modifier = Modifier.testTag(AttendanceTags.ADDRESS))
                }
            } else {
                Text(
                    coords?.let { localizedDigits(it) } ?: stringResource(R.string.att_location_none),
                    style = MaterialTheme.typography.bodyLarge, color = if (coords != null) c.textPrimary else c.textSecondary,
                    modifier = Modifier.testTag(AttendanceTags.ADDRESS),
                )
            }
            val address = state.addressText
            if (address != null && address != coords) Text(address, style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
            state.fixAccuracyM?.let { if (!state.readingFix) FixQualityChip(it) }
        }
    }
}

/**
 * A 20 dp three-quarter arc beside the reading line: one turn per 1.2 s for at most three turns, then it holds (the word
 * carries the meaning; no unbounded loop). Static under reduced motion. The same arc as the kit's busy buttons.
 */
@Composable
private fun ReadingArc(color: Color) {
    val reduced = LocalReducedMotion.current
    val rotation = remember { Animatable(0f) }
    LaunchedEffect(reduced) {
        rotation.snapTo(0f)
        if (!reduced) rotation.animateTo(360f * AronTokens.Motion.SyncMaxTurns, tween(AronTokens.Motion.SyncTurnMillis * AronTokens.Motion.SyncMaxTurns, easing = LinearEasing))
    }
    Canvas(Modifier.size(AronTokens.Size.IconSmall).graphicsLayer { rotationZ = rotation.value }) {
        val w = AronTokens.Stroke.Focus.toPx()
        drawArc(color, startAngle = 0f, sweepAngle = 270f, useCenter = false, topLeft = Offset(w / 2, w / 2), size = Size(size.width - w, size.height - w), style = Stroke(w, cap = StrokeCap.Round))
    }
}

/** The day card: the check-in row and the check-out row, each a dot (done: check circle; open: hollow) with its words. */
@Composable
private fun DayCard(state: AttendanceState, checkoutAtLabel: String) {
    val c = LocalAronColors.current
    val divider = c.borderDivider
    val hairline = if (c.sunlight) AronTokens.Stroke.SunlightHairline else AronTokens.Stroke.Hairline
    AronCard(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(horizontal = AronTokens.Space.L, vertical = AronTokens.Space.S)) {
            val inAt = state.checkedInAt
            DayRow(
                done = inAt != null,
                title = if (inAt != null) stringResource(R.string.att_checked_in, localizedDigits(dhakaClock(inAt))) else stringResource(R.string.att_check_in),
                caption = null, dim = false, tag = if (inAt != null) AttendanceTags.STATUS else null,
            )
            Box(Modifier.fillMaxWidth().height(hairline).drawBehind { drawRect(divider) })
            val outAt = state.checkedOutAt
            DayRow(
                done = outAt != null,
                title = if (outAt != null) stringResource(R.string.att_checked_out, localizedDigits(dhakaClock(outAt))) else stringResource(R.string.att_check_out),
                caption = if (outAt == null && inAt != null && !state.checkOutEnabled) stringResource(R.string.att_check_out_hint, localizedDigits(checkoutAtLabel)) else null,
                dim = inAt == null, tag = null,
            )
        }
    }
}

@Composable
private fun DayRow(done: Boolean, title: String, caption: String?, dim: Boolean, tag: String?) {
    val c = LocalAronColors.current
    val ring = if (dim) c.textDisabled else c.textSecondary
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(vertical = AronTokens.Space.S),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AronTokens.Space.M),
    ) {
        if (done) {
            Icon(AronIcons.CheckCircle, contentDescription = null, tint = c.success, modifier = Modifier.size(AronTokens.Size.Icon))
        } else {
            Canvas(Modifier.size(AronTokens.Size.Icon)) {
                val w = AronTokens.Stroke.Focus.toPx()
                drawCircle(ring, radius = size.minDimension / 2f - w / 2f, style = Stroke(w))
            }
        }
        Column(Modifier.weight(1f)) {
            Text(
                title, style = MaterialTheme.typography.titleSmall, color = if (dim) c.textDisabled else c.textPrimary,
                modifier = if (tag != null) Modifier.testTag(tag) else Modifier,
            )
            if (caption != null) Text(caption, style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
        }
    }
}

/** The fix-quality chip: word plus icon plus the number (never colour alone): success for good, warning for fair and weak. */
@Composable
private fun FixQualityChip(accuracyM: Int) {
    val c = LocalAronColors.current
    val q = FixQuality.of(accuracyM)
    val container = if (q == FixQuality.Good) c.successContainer else c.warningContainer
    val ink = if (q == FixQuality.Good) c.successOnContainer else c.warningOnContainer
    val icon = if (q == FixQuality.Good) AronIcons.CheckCircle else AronIcons.Warning
    val word = stringResource(when (q) { FixQuality.Good -> R.string.att_fix_good; FixQuality.Fair -> R.string.att_fix_fair; FixQuality.Weak -> R.string.att_fix_weak })
    val label = stringResource(R.string.att_fix_quality, word, localizedNumber(accuracyM.toLong()))
    Pill(container, ink, icon, label)
}

@Composable
private fun Pill(container: Color, ink: Color, icon: ImageVector, label: String) {
    Row(
        Modifier
            .heightIn(min = AronTokens.Size.Chip)
            .drawBehind { drawRoundRect(container, cornerRadius = CornerRadius(AronTokens.Radius.Chip.toPx())) }
            .padding(horizontal = 10.dp, vertical = AronTokens.Space.Xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = ink, modifier = Modifier.size(AronTokens.Space.L))
        Spacer(Modifier.width(AronTokens.Space.S))
        Text(label, style = MaterialTheme.typography.labelMedium, color = ink, maxLines = 1)
    }
}

/** A 44 dp squircle well in `accent.container` with a 24 dp glyph (the tile well of android-glass s6.4). */
@Composable
private fun IconWell(icon: ImageVector, size: Dp = AronTokens.Size.Well) {
    val c = LocalAronColors.current
    val fill = c.accentContainer
    Box(Modifier.size(size).drawBehind { drawRoundRect(fill, cornerRadius = CornerRadius(AronTokens.Radius.Well.toPx())) }, contentAlignment = Alignment.Center) {
        Icon(icon, contentDescription = null, tint = c.accentOnContainer, modifier = Modifier.size(AronTokens.Size.Icon))
    }
}

/**
 * The action bar of this screen (the `AronBottomBar` geometry of android-glass s6.7 with a tagged primary): the ActionBar
 * recipe at the 104 dp reason height plus the navigation inset (the reason slot is reserved on Attendance so the bar
 * never animates between check-in and check-out, screens-sr s0), riding above the keyboard; a disabled primary shows
 * its reason as a caption above it, never while busy.
 */
@Composable
private fun AttendanceBar(text: String, onClick: () -> Unit, tag: String, enabled: Boolean, busy: Boolean, reason: String?) {
    val c = LocalAronColors.current
    val reserve = AronTokens.Size.BottomBarWithReason
    GlassSurface(modifier = Modifier.fillMaxWidth(), kind = GlassKind.ActionBar) {
        Column(
            Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))
                .imePadding()
                .heightIn(min = reserve)
                .padding(horizontal = AronTokens.Space.Screen, vertical = AronTokens.Space.M),
            verticalArrangement = Arrangement.spacedBy(AronTokens.Space.S),
        ) {
            if (reason != null && !enabled && !busy) Text(reason, style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
            AronPrimaryButton(text, onClick, Modifier.testTag(tag), enabled = enabled, busy = busy)
        }
    }
}

/**
 * The hold sheet (screens-sr s3): an in-tree solid Sheet surface over a scrim, with the title and its icon, the time chip
 * from the corrected Dhaka clock, the fix line and the press-and-hold button; a tap on the scrim, Back or "Cancel" closes
 * it without committing. TalkBack users confirm with the long-click action. Motion (android-glass s6.8): the sheet
 * translates up on a spring and down over `motion.base` on exit while the scrim fades in the draw phase (no offscreen
 * layer); reduced motion: a 120 ms fade. Until an overlay host exists the sheet lives in the content slot, so the scrim
 * stops under the host top bar (open issue for core-ui).
 */
@Composable
private fun HoldSheet(visible: Boolean, title: String, icon: ImageVector, timeText: String, fixLine: String, onCancel: () -> Unit, onConfirmed: () -> Unit) {
    val c = LocalAronColors.current
    val reduced = LocalReducedMotion.current
    val scrim = c.surfaceScrim
    val handle = c.textSecondary.copy(alpha = 0.7f)
    BackHandler(enabled = visible, onBack = onCancel)
    val scrimT by animateFloatAsState(if (visible) 1f else 0f, tween(if (reduced) AronTokens.Motion.Reduced else AronTokens.Motion.Base, easing = LinearEasing), label = "scrim")
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
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = {}),
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
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AronTokens.Space.M)) {
                        Pill(c.accentContainer, c.accentOnContainer, AronIcons.Clock, timeText)
                        Text(fixLine, style = MaterialTheme.typography.bodyMedium, color = c.textSecondary, modifier = Modifier.weight(1f))
                    }
                    AronPressAndHoldButton(stringResource(R.string.att_hold_label), onConfirmed, Modifier.testTag(AttendanceTags.HOLD))
                    Text(stringResource(R.string.att_hold_hint), style = MaterialTheme.typography.bodySmall, color = c.textSecondary, textAlign = TextAlign.Center)
                    AronTextButton(stringResource(R.string.att_cancel), onCancel, Modifier.fillMaxWidth())
                }
            }
        }
    }
}
