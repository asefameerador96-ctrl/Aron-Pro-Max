package com.aktcl.aron.core.ui

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.PowerManager
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.ui.unit.dp

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
    elevation: Dp = 2.dp,
    content: @Composable () -> Unit,
) {
    val c = LocalAronColors.current
    val base = when (tier) {
        GlassTier.C -> Modifier.background(c.surfaceSolid, shape)
        GlassTier.A -> Modifier.background(c.surfaceGlass.copy(alpha = (c.surfaceGlass.alpha + 0.08f).coerceAtMost(1f)), shape)
        GlassTier.B -> Modifier
            .background(c.surfaceGlass, shape)
            .background(Brush.verticalGradient(listOf(Color.White.copy(alpha = if (c.dark) 0.06f else 0.22f), Color.Transparent)), shape)
    }
    val shadow = if (tier == GlassTier.C || elevation == 0.dp) Modifier else Modifier.shadow(elevation, shape, clip = false, ambientColor = Color.Black.copy(alpha = 0.10f), spotColor = Color.Black.copy(alpha = 0.10f))
    val borderColor = if (tier == GlassTier.C) c.textSecondary.copy(alpha = 0.35f) else c.borderHairline
    Box(modifier.then(shadow).then(base).border(BorderStroke(AronTokens.Hairline, borderColor), shape).clip(shape)) { content() }
}
