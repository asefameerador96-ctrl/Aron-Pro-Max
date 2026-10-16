package com.aktcl.aron.core.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush

/**
 * The page (tokens s2.1): the calm cool gradient (`bg.gradient`, start (0, 0), end (0.21 h, h)) plus the two static
 * radial glows (A `#9CC2FF` 50 to 0 percent at (12% w, 6% h) radius 90% w; B `#C9B8FF` 30 to 0 percent at (95% w, 78% h)
 * radius 80% w; dark theme values from the tokens) on tiers A and B; flat `bg.solid` on tier C and in sunlight.
 * Brushes are built once per size in `drawWithCache`; the fade-out stops are the same RGB at alpha 0, never
 * `Color.Transparent` (Skia interpolates unpremultiplied and a grey band appears).
 */
@Composable
fun Modifier.aronPageBackground(): Modifier {
    val c = LocalAronColors.current
    val tier = LocalGlassTier.current
    val flat = tier == GlassTier.C || c.sunlight
    val solid = c.bgSolid
    if (flat) return drawBehind { drawRect(solid) }
    val top = c.bgGradientTop; val mid = c.bgGradientMid; val bottom = c.bgGradientBottom
    val glowA = c.bgGlowA; val glowB = c.bgGlowB
    return drawWithCache {
        val w = size.width; val h = size.height
        val page = Brush.linearGradient(
            colorStops = arrayOf(0f to top, 0.5f to mid, 1f to bottom),
            start = Offset.Zero, end = Offset(h * 0.21f, h),
        )
        val a = if (glowA.alpha > 0f && w > 0f) Brush.radialGradient(
            colorStops = arrayOf(0f to glowA, 1f to glowA.copy(alpha = 0f)),
            center = Offset(w * 0.12f, h * 0.06f), radius = w * 0.90f,
        ) else null
        val b = if (glowB.alpha > 0f && w > 0f) Brush.radialGradient(
            colorStops = arrayOf(0f to glowB, 1f to glowB.copy(alpha = 0f)),
            center = Offset(w * 0.95f, h * 0.78f), radius = w * 0.80f,
        ) else null
        onDrawBehind {
            drawRect(page)
            a?.let { drawRect(it) }
            b?.let { drawRect(it) }
        }
    }
}
