package com.aktcl.aron.core.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/** Confirm dialog: both buttons are 48 dp text buttons; [onDismiss] covers Back and tapping outside. */
@Composable
fun AronConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String,
    dismissLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val c = LocalAronColors.current
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.surfaceSolid, titleContentColor = c.textPrimary, textContentColor = c.textPrimary,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = { TextButton(onClick = onConfirm, modifier = Modifier.heightIn(min = MinTouch)) { Text(confirmLabel) } },
        dismissButton = { TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = MinTouch)) { Text(dismissLabel) } },
    )
}

/** Information dialog with one button. */
@Composable
fun AronInfoDialog(title: String, message: String, okLabel: String, onDismiss: () -> Unit) {
    val c = LocalAronColors.current
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.surfaceSolid, titleContentColor = c.textPrimary, textContentColor = c.textPrimary,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = { TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = MinTouch)) { Text(okLabel) } },
    )
}

/** Empty list or empty day: a title, an optional hint and an optional action. */
@Composable
fun AronEmptyState(title: String, modifier: Modifier = Modifier, hint: String? = null, actionLabel: String? = null, onAction: (() -> Unit)? = null) =
    CenteredState(title, hint, actionLabel, onAction, modifier, MaterialTheme.colorScheme.onSurface)

/** Error state: what went wrong in words, with a retry action. Never shows a raw code to the user. */
@Composable
fun AronErrorState(message: String, retryLabel: String, onRetry: () -> Unit, modifier: Modifier = Modifier, hint: String? = null) =
    CenteredState(message, hint, retryLabel, onRetry, modifier, MaterialTheme.colorScheme.error)

@Composable
private fun CenteredState(title: String, hint: String?, actionLabel: String?, onAction: (() -> Unit)?, modifier: Modifier, titleColor: androidx.compose.ui.graphics.Color) {
    Column(
        modifier.fillMaxWidth().padding(AronTokens.Space.Xl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(AronTokens.Space.M, Alignment.CenterVertically),
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = titleColor, textAlign = TextAlign.Center)
        if (hint != null) Text(hint, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        if (actionLabel != null && onAction != null) AronSecondaryButton(actionLabel, onAction)
    }
}

/**
 * Press-and-hold button for destructive or irreversible actions (Sales Submit, delete): [onConfirmed] fires only after
 * the finger has stayed down for [holdMillis]; lifting early, disabling or leaving composition cancels. TalkBack users
 * get a long-click action instead of a hold. It is a primary action, so 56 dp. The fill shows progress (draw phase only).
 */
@Composable
fun AronPressAndHoldButton(text: String, onConfirmed: () -> Unit, modifier: Modifier = Modifier, holdMillis: Int = AronTokens.Motion.Hold, enabled: Boolean = true) {
    val progress = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val currentOnConfirmed by rememberUpdatedState(onConfirmed)
    val currentEnabled by rememberUpdatedState(enabled)
    val c = LocalAronColors.current
    val trackColor = if (enabled) c.accentContainer else c.stateDisabledFill
    val barColor = c.accent
    val textColor = if (enabled) c.accentOnContainer else c.stateDisabledLabel
    Box(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = AronTokens.Touch.Primary)
            .clip(AronTokens.ButtonShape)
            .background(trackColor, AronTokens.ButtonShape)
            // progress is a bar along the bottom edge, never a translucent fill under the label (docs/32 s2a item 1)
            .drawBehind { val h = AronTokens.Space.S.toPx(); drawRect(barColor, topLeft = androidx.compose.ui.geometry.Offset(0f, size.height - h), size = androidx.compose.ui.geometry.Size(size.width * progress.value, h)) }
            .semantics(mergeDescendants = true) {
                role = Role.Button
                if (!enabled) disabled()
                onLongClick(label = text) { if (currentEnabled) { currentOnConfirmed(); true } else false }
            }
            .pointerInput(enabled, holdMillis) {
                if (!enabled) return@pointerInput
                awaitEachGesture {
                    awaitFirstDown()
                    val job = scope.launch {
                        progress.snapTo(0f)
                        progress.animateTo(1f, tween(holdMillis))
                        if (currentEnabled) currentOnConfirmed()
                        progress.snapTo(0f)
                    }
                    try {
                        waitForUpOrCancellation()
                    } finally {
                        // runs on early lift AND when this block is cancelled by a key change or leaving composition
                        if (progress.value < 1f) { job.cancel(); scope.launch { progress.snapTo(0f) } }
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = textColor, style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center, modifier = Modifier.padding(start = AronTokens.Space.Xl, end = AronTokens.Space.Xl, top = AronTokens.Space.M, bottom = AronTokens.Space.M + AronTokens.Space.S))
    }
}
