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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.aktcl.aron.core.ui.LocalAronLook
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
import com.aktcl.aron.core.ui.AronNoButton
import com.aktcl.aron.core.ui.AronYesButton
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
    /** D-NIGHT-CHECKOUT-01: the "Sales Submit first" notice and its link. */
    const val SUBMIT_FIRST = "att_submit_first"
}

/** `HH:mm` Asia/Dhaka (UTC+6) of an RFC 3339 instant; the screen shows corrected business time, never the raw clock. */
internal fun dhakaClock(iso: String): String = runCatching {
    val minutes = (Instant.parse(iso).epochSecond / 60 + 6 * 60).mod(24 * 60L)
    "%02d:%02d".format(java.util.Locale.ROOT, minutes / 60, minutes % 60)
}.getOrDefault(iso)

/** The 12-hour form of an `HH:mm` clock ("16:47" -> "04:47 PM"), as the manual's hold sheet shows it (A5, A6); bad input comes back unchanged. */
internal fun clock12(hhmm: String): String = runCatching {
    val (h, m) = hhmm.split(":").map { it.trim().toInt() }
    require(h in 0..23 && m in 0..59)
    "%02d:%02d %s".format(java.util.Locale.ROOT, if (h % 12 == 0) 12 else h % 12, m, if (h < 12) "AM" else "PM")
}.getOrDefault(hhmm)

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
    /** The user card of the reference (`SR - Name (login)` over `route, date`); null leaves the card out (tests, AMO/TSO hosts). */
    userTitle: String? = null,
    userSubtitle: String? = null,
    /** D-NIGHT-CHECKOUT-01: opens the Sales Submit screen from the "Sales Submit first" notice; null shows the notice without the link. */
    onOpenSalesSubmit: (() -> Unit)? = null,
) {
    val c = LocalAronColors.current
    val look = LocalAronLook.current
    var hold by rememberSaveable { mutableStateOf<String?>(null) }
    val checkOutPhase = state.checkedInAt != null
    val dayComplete = state.checkedInAt != null && state.checkedOutAt != null
    // the hold sheet closes itself when the flow is done or the day moved on (a kill and relaunch never reopens it);
    // an effect, not a write during composition
    LaunchedEffect(checkOutPhase, dayComplete) {
        if (hold == HOLD_IN && checkOutPhase) hold = null
        if (hold == HOLD_OUT && dayComplete) hold = null
    }
    Box(modifier.fillMaxSize()) {
        ScrollColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (userTitle != null) UserCard(userTitle, userSubtitle)
            if (state.lastFixMock) AronBanner(stringResource(R.string.att_mock_warning), kind = BannerKind.Warning)
            if (dayComplete) AronBanner(stringResource(R.string.att_day_complete_message), kind = BannerKind.Success)
            if (state.fixFailed && !state.readingFix && !state.busy) {
                AronBanner(
                    stringResource(R.string.att_fix_none), kind = BannerKind.Warning,
                    actionLabel = if (onRefreshFix != null) stringResource(R.string.att_fix_retry) else null, onAction = onRefreshFix,
                )
            }
            LocationCard(state, onRefreshFix)
            DayLines(state, checkoutAtLabel)
            Spacer(Modifier.height(32.dp)) // with the column gap, about 56 to 64 dp between the location card and the message (sr/attendance.png)
            // the reference message once, centred above the two big buttons (screens-sr s3)
            val prompt = when {
                !checkOutPhase && state.showCheckInPrompt -> stringResource(R.string.att_prompt_checkin)
                checkOutPhase && !dayComplete && state.checkOutEnabled -> stringResource(R.string.att_prompt_checkout)
                else -> null
            }
            // D-NIGHT-CHECKOUT-01: no check-out before the day's Sales Submit; one tap to the Sales Submit screen
            if (checkOutPhase && !dayComplete && state.needsSalesSubmit) {
                AronBanner(
                    stringResource(R.string.att_submit_first), kind = BannerKind.Warning,
                    actionLabel = if (onOpenSalesSubmit != null) stringResource(R.string.att_submit_first_open) else null, onAction = onOpenSalesSubmit,
                    modifier = Modifier.testTag(AttendanceTags.SUBMIT_FIRST),
                )
            }
            if (prompt != null) {
                Text(
                    prompt, color = look.ink, fontSize = 16.sp, textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().testTag(AttendanceTags.PROMPT).padding(vertical = 8.dp),
                )
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                // green in, red out once open, grey while not available (sr/attendance.png); a tap opens the hold sheet, never commits
                BigAction(
                    stringResource(R.string.att_check_in), AronIcons.Login, { hold = HOLD_IN }, AttendanceTags.CHECK_IN,
                    enabled = !checkOutPhase && state.checkInEnabled && !state.busy, enabledColors = GreenStops, modifier = Modifier.weight(1f),
                )
                BigAction(
                    stringResource(R.string.att_check_out), AronIcons.Logout, { hold = HOLD_OUT }, AttendanceTags.CHECK_OUT,
                    enabled = checkOutPhase && !dayComplete && state.checkOutEnabled && !state.busy, enabledColors = RedStops, modifier = Modifier.weight(1f),
                )
            }
        }
        // the sheet keeps its last kind while it slides out, so the words do not change mid-exit
        var lastHold by remember { mutableStateOf(HOLD_IN) }
        hold?.let { lastHold = it }
        val out = lastHold == HOLD_OUT
        val time = localizedDigits(remember(hold) { clock12(dhakaClock(nowIso())) })
        val fixLine = state.fixAccuracyM?.let { stringResource(R.string.att_fix_line, localizedNumber(it.toLong())) } ?: stringResource(R.string.att_fix_line_none)
        HoldSheet(
            visible = hold != null,
            title = stringResource(if (out) R.string.att_hold_title_out else R.string.att_hold_title_in),
            icon = if (out) AronIcons.Logout else AronIcons.Login,
            timeText = time, fixLine = fixLine, tint = if (out) LocalAronColors.current.noFill else LocalAronColors.current.yesFill,
            onCancel = { hold = null },
            onConfirmed = { hold = null; if (out) onCheckOut() else onCheckIn() },
        )
    }
}

private const val HOLD_IN = "in"
private const val HOLD_OUT = "out"

private val GreenStops = listOf(Color(0xFF00A650), Color(0xFF5CBB8A))
private val RedStops = listOf(Color(0xFFD5484A), Color(0xFFE79B9C))

/** A 32 dp lavender rounded-square tile with a purple 18 dp glyph (sr/attendance.png: user tile #D7D3DF, location tile #C7C3CF). */
@Composable
private fun LeadingTile(glyph: ImageVector, fill: Color) {
    val look = LocalAronLook.current
    Box(Modifier.size(32.dp).clip(RoundedCornerShape(8.dp)).background(if (look.sunlight) look.silhouette else fill), contentAlignment = Alignment.Center) {
        Icon(glyph, contentDescription = null, tint = look.purple, modifier = Modifier.size(18.dp))
    }
}

/** The user card (sr/attendance.png): a lavender tile with the person glyph, the name line and the route line, over a red rule. */
@Composable
private fun UserCard(title: String, subtitle: String?) {
    val look = LocalAronLook.current
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            LeadingTile(AronIcons.Person, Color(0xFFD7D3DF))
            Column(Modifier.weight(1f)) {
                Text(title, color = look.ink, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                if (subtitle != null) Text(subtitle, color = look.ink, fontSize = 12.sp)
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(look.red))
    }
}

/** The location card (sr/attendance.png): a pin tile, the coordinates or address, the fix-quality chip and the red refresh icon. */
@Composable
private fun LocationCard(state: AttendanceState, onRefreshFix: (() -> Unit)?) {
    val c = LocalAronColors.current
    val look = LocalAronLook.current
    val reduced = LocalReducedMotion.current
    // one 1,200 ms linear turn of the refresh glyph per read (screens-sr s3 Motion); static under reduced motion
    val spin = remember { Animatable(0f) }
    LaunchedEffect(state.readingFix, reduced) {
        spin.snapTo(0f)
        if (state.readingFix && !reduced) spin.animateTo(360f, tween(AronTokens.Motion.SyncTurnMillis, easing = LinearEasing))
    }
    val refreshLabel = stringResource(R.string.att_refresh)
    val shape = RoundedCornerShape(12.dp)
    // a compact, borderless #D7D7D7 block with a wide soft shadow, one text block of at most two lines (sr/attendance.png)
    Row(
        Modifier.fillMaxWidth().heightIn(min = 64.dp).shadow(12.dp, shape, ambientColor = Color(0x33000000), spotColor = Color(0x33000000)).clip(shape)
            .background(if (look.sunlight) look.card else Color(0xFFD7D7D7)).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        LeadingTile(AronIcons.Place, Color(0xFFC7C3CF))
        Column(Modifier.weight(1f)) {
            // the address when it is known, else the coordinates in Latin digits on one line (UI-SR-11); the accuracy is a small grey suffix
            val line = state.addressText ?: state.coordinatesText
            if (state.readingFix) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AronTokens.Space.S)) {
                    ReadingArc(c.textSecondary)
                    Text(stringResource(R.string.att_reading_fix), color = Color(0xFF444444), fontSize = 16.sp, modifier = Modifier.testTag(AttendanceTags.ADDRESS))
                }
            } else {
                Text(
                    line ?: stringResource(R.string.att_location_none),
                    color = if (line != null) Color(0xFF444444) else look.inkMuted, fontSize = 16.sp,
                    modifier = Modifier.testTag(AttendanceTags.ADDRESS),
                )
            }
            state.fixAccuracyM?.let { if (!state.readingFix) Text(stringResource(R.string.att_accuracy_short, it.toString()), color = look.inkMuted, fontSize = 12.sp) }
        }
        if (onRefreshFix != null) {
            // the re-sample icon in aron.red, as in the reference
            Box(
                Modifier.size(48.dp).clip(CircleShape).clickable(enabled = !state.readingFix && !state.busy, role = Role.Button, onClick = onRefreshFix)
                    .semantics { contentDescription = refreshLabel }.testTag(AttendanceTags.REFRESH),
                contentAlignment = Alignment.Center,
            ) {
                Icon(AronIcons.Refresh, contentDescription = null, tint = if (state.readingFix || state.busy) look.disabledText else look.red, modifier = Modifier.size(24.dp).graphicsLayer { rotationZ = spin.value })
            }
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

/** The day's two lines: when the rep checked in and out (a dot per line: done = check circle, open = hollow), the check-out hint when it is not open yet. */
@Composable
private fun DayLines(state: AttendanceState, checkoutAtLabel: String) {
    val inAt = state.checkedInAt
    val outAt = state.checkedOutAt
    if (inAt == null && outAt == null) return
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        DayRow(
            done = inAt != null,
            title = if (inAt != null) stringResource(R.string.att_checked_in, localizedDigits(clock12(dhakaClock(inAt)))) else stringResource(R.string.att_check_in),
            caption = null, dim = false, tag = if (inAt != null) AttendanceTags.STATUS else null,
        )
        DayRow(
            done = outAt != null,
            title = if (outAt != null) stringResource(R.string.att_checked_out, localizedDigits(clock12(dhakaClock(outAt)))) else stringResource(R.string.att_check_out),
            caption = if (outAt == null && inAt != null && !state.checkOutEnabled && !state.checkOutTimeReached) stringResource(R.string.att_check_out_hint, localizedDigits(clock12(checkoutAtLabel))) else null,
            dim = inAt == null, tag = null,
        )
        // the saved-offline notice appears only once a check-in or check-out is actually stored (not on the idle screen)
        Text(stringResource(R.string.att_offline_saved), color = LocalAronLook.current.inkMuted, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
    }
}

@Composable
private fun DayRow(done: Boolean, title: String, caption: String?, dim: Boolean, tag: String?) {
    val c = LocalAronColors.current
    val look = LocalAronLook.current
    val ring = if (dim) look.disabledText else look.inkMuted
    Row(
        Modifier.fillMaxWidth().heightIn(min = 40.dp),
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
                title, color = if (dim) look.disabledText else look.ink, fontSize = 16.sp, fontWeight = FontWeight.Medium,
                modifier = if (tag != null) Modifier.testTag(tag) else Modifier,
            )
            if (caption != null) Text(caption, color = look.inkMuted, fontSize = 13.sp)
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
 * One of the two big buttons of the reference (sr/attendance.png): a 100 dp rounded gradient block with the glyph over the label.
 * [enabledColors] are the gradient stops when the action is open; otherwise a grey gradient (solid in Sunlight).
 */
@Composable
private fun BigAction(label: String, glyph: ImageVector, onClick: () -> Unit, tag: String, enabled: Boolean, enabledColors: List<Color>, modifier: Modifier = Modifier) {
    val look = LocalAronLook.current
    val shape = RoundedCornerShape(12.dp)
    val brush = when {
        look.sunlight -> SolidColor(if (enabled) look.purple else look.disabledFill)
        enabled -> Brush.horizontalGradient(enabledColors)
        else -> Brush.horizontalGradient(listOf(Color(0xFF4F4F4F), Color(0xFFBBBBBB)))
    }
    Box(
        modifier
            .heightIn(min = 100.dp)
            .shadow(if (enabled) 3.dp else 1.dp, shape)
            .clip(shape)
            .background(brush)
            .testTag(tag)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(8.dp)) {
            Icon(glyph, contentDescription = null, tint = if (look.sunlight && !enabled) look.disabledText else Color.White, modifier = Modifier.size(20.dp))
            Text(label, color = if (look.sunlight && !enabled) look.disabledText else Color.White, fontSize = 14.sp, textAlign = TextAlign.Center)
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
private fun HoldSheet(visible: Boolean, title: String, icon: ImageVector, timeText: String, fixLine: String, tint: Color, onCancel: () -> Unit, onConfirmed: () -> Unit) {
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
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AronTokens.Space.M)) {
                        Pill(c.accentContainer, c.accentOnContainer, AronIcons.Clock, timeText)
                        Text(fixLine, style = MaterialTheme.typography.bodyMedium, color = c.textSecondary, modifier = Modifier.weight(1f))
                    }
                    AronPressAndHoldButton(stringResource(R.string.att_hold_label), onConfirmed, Modifier.testTag(AttendanceTags.HOLD), tint = tint)
                    Text(stringResource(R.string.att_hold_hint), style = MaterialTheme.typography.bodySmall, color = c.textSecondary, textAlign = TextAlign.Center)
                    AronTextButton(stringResource(R.string.att_cancel), onCancel, Modifier.fillMaxWidth())
                }
            }
        }
    }
}
