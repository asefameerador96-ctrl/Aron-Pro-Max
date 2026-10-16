package com.aktcl.aron.core.ui

import android.provider.Settings
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

/**
 * True when motion must be a plain fade or nothing (android-glass s9): the system "remove animations" setting, or tier C,
 * or sunlight. [AronTheme] provides it; the kit reads it before any transform animation.
 */
val LocalReducedMotion = staticCompositionLocalOf { false }

/** The system animator scale, read ONCE per composition (`Settings.Global.ANIMATOR_DURATION_SCALE == 0` means remove animations). */
@Composable
fun rememberSystemReducedMotion(): Boolean {
    val context = LocalContext.current
    return remember {
        runCatching { Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }.getOrDefault(false)
    }
}

/** The three curves of tokens s8. */
object AronEasing {
    val Out = CubicBezierEasing(0.2f, 0f, 0f, 1f)
    val In = CubicBezierEasing(0.4f, 0f, 1f, 1f)
    val InOut = CubicBezierEasing(0.4f, 0f, 0.2f, 1f)
}

/**
 * Pressed scale (tokens s8 `motion.press`): [pressedScale] over 90 ms linear while pressed, back over `motion.fast`
 * ease-out on release; no transform under reduced motion. The scale is applied in the layer phase only, so a press
 * never recomposes the control. Place it BEFORE the shadow in the chain (outermost): the shadow layer is then inside
 * the scaled layer and shrinks with the control.
 */
@Composable
fun Modifier.pressScale(interactionSource: InteractionSource, pressedScale: Float): Modifier {
    if (LocalReducedMotion.current) return this
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) pressedScale else 1f,
        animationSpec = if (pressed) tween(AronTokens.Motion.Press, easing = LinearEasing) else tween(AronTokens.Motion.Fast, easing = AronEasing.Out),
    )
    return graphicsLayer { scaleX = scale; scaleY = scale }
}

/**
 * Enter transition for SMALL IN-SCREEN CONTENT (a banner, a chip state, a row that appears; `AnimatedVisibility` or
 * `AnimatedContent` of a leaf): 8 dp from the end plus a 220 ms ease-out fade; reduced motion: a 120 ms fade. It is built
 * from `fadeIn`, which renders through an offscreen layer, so it is NEVER used for a whole screen, sheet or dialog
 * (android-glass s1 item 3): screen-to-screen navigation uses [AronScreenTransition], whose fade is `ModulateAlpha`.
 */
@Composable
fun aronEnterTransition(forward: Boolean = true): EnterTransition {
    if (LocalReducedMotion.current) return fadeIn(tween(AronTokens.Motion.Reduced, easing = LinearEasing))
    val offset = with(LocalDensity.current) { 8.dp.roundToPx() }
    val fromEnd = (LocalLayoutDirection.current == LayoutDirection.Ltr) == forward
    val spec = tween<Float>(if (forward) AronTokens.Motion.Base else AronTokens.Motion.BackOut, easing = if (forward) AronEasing.Out else AronEasing.In)
    val slide = tween<androidx.compose.ui.unit.IntOffset>(spec.durationMillis, easing = spec.easing)
    return fadeIn(spec) + slideInHorizontally(slide) { if (fromEnd) offset else -offset }
}

/** The exit twin of [aronEnterTransition], for small in-screen content only: a fade (180 ms ease-in on back, 220 ms ease-out forward); reduced motion: 120 ms. Whole screens use [AronScreenTransition]. */
@Composable
fun aronExitTransition(forward: Boolean = true): ExitTransition {
    if (LocalReducedMotion.current) return fadeOut(tween(AronTokens.Motion.Reduced, easing = LinearEasing))
    return fadeOut(tween(if (forward) AronTokens.Motion.Base else AronTokens.Motion.BackOut, easing = if (forward) AronEasing.Out else AronEasing.In))
}

/**
 * Screen-to-screen transition without an offscreen layer (android-glass s1 item 3): the entering screen translates 8 dp
 * from the end (forward) or the start (back) while fading in with `CompositingStrategy.ModulateAlpha`; the leaving screen
 * fades. [forward] says whether going from the first state to the second is a forward navigation. Reduced motion: a
 * 120 ms fade in place, no transform.
 */
@Composable
fun <T> AronScreenTransition(
    target: T,
    forward: (from: T, to: T) -> Boolean,
    modifier: Modifier = Modifier,
    content: @Composable (T) -> Unit,
) {
    val reduced = LocalReducedMotion.current
    val ltr = LocalLayoutDirection.current == LayoutDirection.Ltr
    val offsetPx = with(LocalDensity.current) { 8.dp.toPx() }
    val screen = updateTransition(target)
    fun duration(from: T, to: T) = if (reduced) AronTokens.Motion.Reduced else if (forward(from, to)) AronTokens.Motion.Base else AronTokens.Motion.BackOut
    fun easing(from: T, to: T) = if (reduced) LinearEasing else if (forward(from, to)) AronEasing.Out else AronEasing.In
    screen.AnimatedContent(
        modifier = modifier,
        transitionSpec = { EnterTransition.None togetherWith ExitTransition.None using SizeTransform(clip = false) },
    ) { state ->
        val alpha by screen.animateFloat(
            transitionSpec = { tween(duration(initialState, targetState), easing = easing(initialState, targetState)) },
        ) { s -> if (s == state) 1f else 0f }
        val dx by screen.animateFloat(
            transitionSpec = { tween(duration(initialState, targetState), easing = easing(initialState, targetState)) },
        ) { s ->
            when {
                reduced || s == state -> 0f
                state == screen.targetState -> { // this screen is entering: it starts 8 dp away, on the side it comes from
                    val fwd = forward(screen.currentState, screen.targetState)
                    if (fwd == ltr) offsetPx else -offsetPx
                }
                else -> 0f // the leaving screen stays in place and fades
            }
        }
        androidx.compose.foundation.layout.Box(
            Modifier.graphicsLayer {
                this.alpha = alpha
                translationX = dx
                compositingStrategy = CompositingStrategy.ModulateAlpha
            },
        ) { content(state) }
    }
}
