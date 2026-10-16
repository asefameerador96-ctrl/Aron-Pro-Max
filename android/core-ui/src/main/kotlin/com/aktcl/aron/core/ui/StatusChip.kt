package com.aktcl.aron.core.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** The steady sync state on every main screen (docs/32 s1.6). Never a modal. Meaning is in the word, not the colour alone. */
@Immutable
sealed interface SyncChipState {
    data object Offline : SyncChipState
    data object Syncing : SyncChipState
    data object Synced : SyncChipState
    data class Waiting(val count: Int) : SyncChipState
    /** Synced, with the Dhaka time of the last sync (pre-formatted, localised digits) so the chip can show "10:42". */
    data class SyncedAt(val timeText: String) : SyncChipState
}

/** Full (Home: icon and the whole sentence), Compact (icon plus one word or the time), Dot (28 dp circle, font scale 1.3 and up). */
enum class ChipForm { Full, Compact, Dot }

/** The compact chip, kept for existing callers. */
@Composable
fun StatusChip(state: SyncChipState, modifier: Modifier = Modifier) = AronStatusChip(state, modifier, ChipForm.Compact)

private class ChipLook(val container: Color?, val ink: Color, val icon: ImageVector, val label: String)

/**
 * The status chip (android-glass s6.6). Synced is QUIET: no container, a check and the time or word in `text.secondary`;
 * Offline is `offline.container`; Syncing is `accent.container` with the sync icon turning at most three times after
 * entering the state, then holding (static under reduced motion and on tier C); Waiting is `warning.container`. Each form
 * reserves a fixed slot width so a state change never relayouts; the 48 dp target opens the sync sheet through [onClick].
 */
@Composable
fun AronStatusChip(state: SyncChipState, modifier: Modifier = Modifier, form: ChipForm = ChipForm.Compact, onClick: (() -> Unit)? = null) {
    val c = LocalAronColors.current
    // opaque container colours (docs/design/tokens.md s2.5): contrast never depends on what is behind the chip
    val look = when (state) {
        SyncChipState.Offline -> ChipLook(c.offlineContainer, c.offlineOnContainer, AronIcons.CloudOff, stringResource(R.string.core_ui_chip_offline))
        SyncChipState.Syncing -> ChipLook(c.accentContainer, c.accentOnContainer, AronIcons.Sync, stringResource(R.string.core_ui_chip_syncing))
        SyncChipState.Synced -> ChipLook(null, c.textSecondary, AronIcons.Check, stringResource(R.string.core_ui_chip_synced))
        is SyncChipState.SyncedAt -> ChipLook(
            null, c.textSecondary, AronIcons.Check,
            if (form == ChipForm.Compact) state.timeText else stringResource(R.string.core_ui_chip_synced_at, state.timeText),
        )
        is SyncChipState.Waiting -> ChipLook(c.warningContainer, c.warningOnContainer, AronIcons.CloudUpload, stringResource(R.string.core_ui_chip_waiting, localizedNumber(state.count.toLong())))
    }
    val openLabel = stringResource(R.string.core_ui_chip_open_sync)
    val reduced = LocalReducedMotion.current
    val tier = LocalGlassTier.current
    val turning = state == SyncChipState.Syncing && !reduced && tier != GlassTier.C
    val rotation = remember { Animatable(0f) }
    LaunchedEffect(turning) {
        if (turning) {
            rotation.snapTo(0f)
            rotation.animateTo(360f * AronTokens.Motion.SyncMaxTurns, tween(AronTokens.Motion.SyncTurnMillis * AronTokens.Motion.SyncMaxTurns, easing = LinearEasing))
        } else {
            rotation.snapTo(0f)
        }
    }
    val target = Modifier.sizeIn(minWidth = MinTouch, minHeight = MinTouch)
    val click = if (onClick != null) Modifier.clip(RoundedCornerShape(AronTokens.Radius.Chip)).clickable(role = Role.Button, onClickLabel = openLabel, onClick = onClick) else Modifier
    val slot: Dp = when (form) { ChipForm.Full -> AronTokens.Size.ChipSlotFull; ChipForm.Compact -> AronTokens.Size.ChipSlotCompact; ChipForm.Dot -> MinTouch }
    Box(
        modifier.semantics(mergeDescendants = true) { contentDescription = look.label }.then(target).widthIn(min = slot).then(click),
        contentAlignment = if (form == ChipForm.Dot) Alignment.Center else Alignment.CenterStart,
    ) {
        if (form == ChipForm.Dot) {
            Box(
                Modifier.size(AronTokens.Size.Dot).drawBehind { look.container?.let { drawCircle(it) } },
                contentAlignment = Alignment.Center,
            ) {
                Icon(look.icon, contentDescription = null, tint = look.ink, modifier = Modifier.size(AronTokens.Space.L).graphicsLayer { rotationZ = rotation.value })
            }
        } else {
            Row(
                Modifier
                    .drawBehind { look.container?.let { drawRoundRect(it, cornerRadius = androidx.compose.ui.geometry.CornerRadius(AronTokens.Radius.Chip.toPx())) } }
                    .heightIn(min = AronTokens.Size.Chip)
                    .padding(horizontal = if (look.container != null) ChipPadding else 0.dp, vertical = AronTokens.Space.Xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(look.icon, contentDescription = null, tint = look.ink, modifier = Modifier.size(AronTokens.Space.L).graphicsLayer { rotationZ = rotation.value })
                Spacer(Modifier.width(AronTokens.Space.S))
                Text(look.label, style = MaterialTheme.typography.labelMedium, color = look.ink, maxLines = 2)
            }
        }
    }
}

/** The chip's horizontal padding (android-glass s6.6). */
private val ChipPadding: Dp = 10.dp
