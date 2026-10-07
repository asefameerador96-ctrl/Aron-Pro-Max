package com.aktcl.aron.core.ui

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.PowerManager
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.border
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp

/** Glass tiers (docs/32 s2). B is the default on field phones: translucent look, no runtime blur layers. */
enum class GlassTier { A, B, C }

/** The admin key `cfg.app.ui_glass`: `auto`, `lite`, `off`; also the user setting. */
enum class UiGlassConfig { AUTO, LITE, OFF;
    companion object { fun parse(value: String?): UiGlassConfig = when (value?.lowercase()) { "lite" -> LITE; "off" -> OFF; else -> AUTO } }
}

/** What the phone says about itself; read once, never polled. */
data class GlassSignals(
    val sdkInt: Int,
    val totalMemMb: Int,
    val batterySaver: Boolean,
    val reduceTransparency: Boolean = false,
    val highContrast: Boolean = false,
    val frameProbeFailed: Boolean = false,
)

object GlassPolicy {
    /** Pure and testable. Any "needs legibility" signal forces C; tier A needs Android 12+, 4 GB+ and `auto`. */
    fun resolve(config: UiGlassConfig, s: GlassSignals): GlassTier = when {
        config == UiGlassConfig.OFF -> GlassTier.C
        s.batterySaver || s.reduceTransparency || s.highContrast || s.frameProbeFailed -> GlassTier.C
        config == UiGlassConfig.LITE -> GlassTier.B
        s.sdkInt >= 31 && s.totalMemMb >= 4096 -> GlassTier.A
        else -> GlassTier.B
    }

    fun read(context: Context): GlassSignals {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mem = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return GlassSignals(Build.VERSION.SDK_INT, (mem.totalMem / (1024 * 1024)).toInt(), pm.isPowerSaveMode)
    }
}

val LocalGlassTier = compositionLocalOf { GlassTier.B }

@Composable
fun rememberGlassTier(config: UiGlassConfig = UiGlassConfig.AUTO): GlassTier {
    val context = LocalContext.current
    return remember(config) { GlassPolicy.resolve(config, GlassPolicy.read(context)) }
}

/**
 * The one glass surface, for CHROME only (top bar, bottom bar, sheet scrims, tile backdrops, empty-state backdrops).
 * Anything that carries a number, status or primary action belongs on an [AronCard] (docs/32 s2a item 1).
 * Tier A: stronger tint (a backdrop-blur layer plugs in here later, only for A); B: tinted translucent fill, soft sheen,
 * lit edge, soft shadow, no blur; C and sunlight: opaque fill and a solid border, no gradient, no shadow.
 */
@Composable
fun GlassSurface(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(AronTokens.Radius.Card),
    tier: GlassTier = LocalGlassTier.current,
    elevation: Dp = AronTokens.Elevation.Card,
    onClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    val c = LocalAronColors.current
    val solid = tier == GlassTier.C || c.sunlight
    val base = when {
        solid -> Modifier.background(c.surfaceSolid, shape)
        tier == GlassTier.A -> Modifier.background(c.surfaceGlass.copy(alpha = (c.surfaceGlass.alpha + AronTokens.Alpha.TierABoost).coerceAtMost(1f)), shape)
        else -> Modifier
            .background(c.surfaceGlass, shape)
            .background(Brush.verticalGradient(listOf(Color.White.copy(alpha = if (c.dark) AronTokens.Alpha.SheenDark else AronTokens.Alpha.SheenLight), Color.Transparent)), shape)
    }
    val shadow = if (solid || elevation == AronTokens.Elevation.Page) Modifier else Modifier.shadow(elevation, shape, clip = false, ambientColor = Color.Black.copy(alpha = AronTokens.Alpha.Shadow), spotColor = Color.Black.copy(alpha = AronTokens.Alpha.Shadow))
    val stroke = if (c.sunlight) AronTokens.Stroke.SunlightHairline else AronTokens.Stroke.Hairline
    val border = if (solid) BorderStroke(stroke, c.borderSolid) else BorderStroke(stroke, Brush.verticalGradient(listOf(c.borderHairlineTop, c.borderHairlineBottom)))
    // clip BEFORE clickable so the ripple follows the rounded corners
    val click = if (onClick != null) Modifier.clickable(enabled = enabled, role = Role.Button, onClick = onClick) else Modifier
    Box(modifier.then(shadow).then(base).border(border, shape).clip(shape).then(click)) { content() }
}

/**
 * The OPAQUE content surface (docs/32 s2a item 1): 100 percent solid fill, never translucent over a gradient, so every
 * number, status and primary action on it keeps its contrast in sun. Depth comes from a soft shadow and a hairline
 * (sunlight and tier C: a solid border and no shadow).
 */
@Composable
fun AronCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    val c = LocalAronColors.current
    val tier = LocalGlassTier.current
    val shape = RoundedCornerShape(AronTokens.Radius.Card)
    val flat = tier == GlassTier.C || c.sunlight
    val shadow = if (flat) Modifier else Modifier.shadow(AronTokens.Elevation.Card, shape, clip = false, ambientColor = Color.Black.copy(alpha = AronTokens.Alpha.Shadow), spotColor = Color.Black.copy(alpha = AronTokens.Alpha.Shadow))
    val stroke = if (c.sunlight) AronTokens.Stroke.SunlightHairline else AronTokens.Stroke.Hairline
    val edge = if (flat) c.borderSolid else c.borderHairlineBottom
    val click = if (onClick != null) Modifier.clickable(enabled = enabled, role = Role.Button, onClick = onClick) else Modifier
    Box(modifier.then(shadow).background(c.surfaceSolid, shape).border(BorderStroke(stroke, edge), shape).clip(shape).then(click)) { content() }
}
