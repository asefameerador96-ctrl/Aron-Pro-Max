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
 * The one glass surface. Tier A: stronger tint (a backdrop-blur layer plugs in here later, only for A, see docs/32 s4);
 * B: tinted translucent fill, soft top sheen, hairline border, soft shadow, no blur; C: opaque fill and border.
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
    val base = when (tier) {
        GlassTier.C -> Modifier.background(c.surfaceSolid, shape)
        GlassTier.A -> Modifier.background(c.surfaceGlass.copy(alpha = (c.surfaceGlass.alpha + AronTokens.Alpha.TierABoost).coerceAtMost(1f)), shape)
        GlassTier.B -> Modifier
            .background(c.surfaceGlass, shape)
            .background(Brush.verticalGradient(listOf(Color.White.copy(alpha = if (c.dark) AronTokens.Alpha.SheenDark else AronTokens.Alpha.SheenLight), Color.Transparent)), shape)
    }
    val shadow = if (tier == GlassTier.C || elevation == AronTokens.Elevation.Page) Modifier else Modifier.shadow(elevation, shape, clip = false, ambientColor = Color.Black.copy(alpha = AronTokens.Alpha.Shadow), spotColor = Color.Black.copy(alpha = AronTokens.Alpha.Shadow))
    val borderColor = if (tier == GlassTier.C) c.textSecondary.copy(alpha = AronTokens.Alpha.SolidBorder) else c.borderHairline
    // clip BEFORE clickable so the ripple follows the rounded corners
    val click = if (onClick != null) Modifier.clickable(enabled = enabled, role = Role.Button, onClick = onClick) else Modifier
    Box(modifier.then(shadow).then(base).border(BorderStroke(AronTokens.Hairline, borderColor), shape).clip(shape).then(click)) { content() }
}
