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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
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
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = { TextButton(onClick = onConfirm, modifier = Modifier.heightIn(min = MinTouch)) { Text(confirmLabel) } },
        dismissButton = { TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = MinTouch)) { Text(dismissLabel) } },
    )
}

/** Information dialog with one button. */
@Composable
fun AronInfoDialog(title: String, message: String, okLabel: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
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
        modifier.fillMaxWidth().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = titleColor, textAlign = TextAlign.Center)
        if (hint != null) Text(hint, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        if (actionLabel != null && onAction != null) AronSecondaryButton(actionLabel, onAction)
    }
}

/**
 * Press-and-hold button for destructive or irreversible actions (Sales Submit, delete): [onConfirmed] fires only after
 * the finger has stayed down for [holdMillis]; lifting early cancels. Accessibility: exposes a long-click action so
 * TalkBack users can confirm without holding. The fill shows progress.
 */
@Composable
fun AronPressAndHoldButton(text: String, onConfirmed: () -> Unit, modifier: Modifier = Modifier, holdMillis: Int = 1200, enabled: Boolean = true) {
    val progress = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val scheme = MaterialTheme.colorScheme
    val fillColor = scheme.secondary.copy(alpha = 0.5f)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = MinTouch)
            .clip(RoundedCornerShape(12.dp))
            .background(if (enabled) scheme.secondary.copy(alpha = 0.25f) else scheme.surfaceVariant, RoundedCornerShape(12.dp))
            .drawBehind { drawRect(fillColor, size = androidx.compose.ui.geometry.Size(size.width * progress.value, size.height)) }
            .semantics { role = Role.Button; onLongClick(label = text) { if (enabled) { onConfirmed(); true } else false } }
            .pointerInput(enabled, holdMillis) {
                if (!enabled) return@pointerInput
                awaitEachGesture {
                    awaitFirstDown()
                    val job = scope.launch {
                        progress.snapTo(0f)
                        progress.animateTo(1f, tween(holdMillis))
                        onConfirmed()
                        progress.snapTo(0f)
                    }
                    waitForUpOrCancellation()
                    if (progress.value < 1f) { job.cancel(); scope.launch { progress.snapTo(0f) } }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp))
    }
}
