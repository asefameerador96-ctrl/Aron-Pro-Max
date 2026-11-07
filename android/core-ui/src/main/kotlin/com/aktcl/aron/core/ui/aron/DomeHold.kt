package com.aktcl.aron.core.ui.aron

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aktcl.aron.core.ui.AronTokens
import kotlinx.coroutines.launch

/**
 * The half-dome press-and-hold button of the AMO check-in / check-out sheet (notes 15, 16): the upper half of a circle, a gradient from
 * [from] (lower left) to [to] (upper right), the white [icon] over the white [label]. [onConfirmed] fires only after the finger has stayed
 * down for [holdMillis]; lifting early cancels (same rule as AronPressAndHoldButton). A white bar along the bottom edge shows the progress; TalkBack
 * users get a long-click action instead of a hold. Sunlight passes one solid deep colour in both stops.
 */
@Composable
fun AronDomeHoldButton(
    label: String,
    icon: ImageVector,
    from: Color,
    to: Color,
    onConfirmed: () -> Unit,
    modifier: Modifier = Modifier,
    holdMillis: Int = AronTokens.Motion.Hold,
) {
    val progress = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val confirm by rememberUpdatedState(onConfirmed)
    val dome = remember { GenericShape { size, _ -> arcTo(Rect(0f, 0f, size.width, size.width), 180f, 180f, true); lineTo(size.width, size.height); lineTo(0f, size.height); close() } }
    Box(
        modifier
            .fillMaxWidth()
            .height(150.dp)
            .clip(dome)
            .background(Brush.linearGradient(listOf(from, to), start = Offset(0f, Float.POSITIVE_INFINITY), end = Offset(Float.POSITIVE_INFINITY, 0f)))
            .drawBehind {
                // the progress: a bar along the bottom edge, as the kit's hold button draws it (never a translucent fill under the label)
                val h = 8.dp.toPx()
                drawRect(Color.White.copy(alpha = 0.9f), topLeft = Offset(0f, size.height - h), size = androidx.compose.ui.geometry.Size(size.width * progress.value, h))
            }
            .semantics(mergeDescendants = true) {
                role = Role.Button
                onLongClick(label = label) { confirm(); true }
            }
            .pointerInput(holdMillis) {
                awaitEachGesture {
                    awaitFirstDown()
                    val job = scope.launch {
                        progress.snapTo(0f)
                        progress.animateTo(1f, tween(holdMillis))
                        confirm()
                        progress.snapTo(0f)
                    }
                    try {
                        waitForUpOrCancellation()
                    } finally {
                        if (progress.value < 1f) { job.cancel(); scope.launch { progress.snapTo(0f) } }
                    }
                }
            },
        contentAlignment = Alignment.BottomCenter,
    ) {
        Column(Modifier.padding(bottom = 28.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(32.dp))
            Text(label, color = Color.White, fontSize = 16.sp, textAlign = TextAlign.Center)
        }
    }
}
