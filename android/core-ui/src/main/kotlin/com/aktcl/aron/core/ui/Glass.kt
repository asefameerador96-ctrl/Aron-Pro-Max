package com.aktcl.aron.core.ui

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.PowerManager
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
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
 * The focus indicator for keyboard and switch access (docs/32 s2a item 5): a ring of [AronTokens.Stroke.Focus]
 * (3 dp in sunlight) in `border.focus`, drawn 2 dp outside the shape. Place it BEFORE `clickable`/`toggleable` in the chain
 * so it observes that node's focus.
 */
@Composable
fun Modifier.aronFocusRing(shape: Shape): Modifier {
    val c = LocalAronColors.current
    var focused by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    val width = if (c.sunlight) AronTokens.Stroke.SunlightFocus else AronTokens.Stroke.Focus
    return this
        .onFocusChanged { focused = it.isFocused }
        .drawWithContent {
            drawContent()
            if (focused) {
                val gap = 2.dp.toPx(); val w = width.toPx()
                val outline = shape.createOutline(Size(size.width + 2 * gap, size.height + 2 * gap), layoutDirection, this)
                translate(-gap, -gap) { drawOutline(outline, c.borderFocus, style = Stroke(w)) }
            }
        }
}

/**
 * The surface kinds of android-glass s2: each has its own paint recipe per tier. Field is every card that carries a
 * number, name, status or action (96 percent, lit edge, soft shadow); Tile is the translucent Home tile (its label sits
 * on an opaque plate); Row is a flat list row; TopBar and ActionBar are the two bars; Sheet and Dialog are fully solid.
 */
enum class GlassKind { Field, Tile, Row, TopBar, ActionBar, Sheet, Dialog }

/** The default shape of each kind (tokens s4): cards 20 dp, bars square, sheets 28 dp on top, dialogs 28 dp all round. */
fun glassShapeFor(kind: GlassKind): Shape = when (kind) {
    GlassKind.Field, GlassKind.Tile, GlassKind.Row -> RoundedCornerShape(AronTokens.Radius.Card)
    GlassKind.TopBar, GlassKind.ActionBar -> RectangleShape
    GlassKind.Sheet -> RoundedCornerShape(topStart = AronTokens.Radius.Sheet, topEnd = AronTokens.Radius.Sheet)
    GlassKind.Dialog -> RoundedCornerShape(AronTokens.Radius.Sheet)
}

/** The default elevation of each kind (tokens s5): only field cards, sheets and dialogs cast a shadow. */
fun glassElevationFor(kind: GlassKind): Dp = when (kind) {
    GlassKind.Field -> AronTokens.Elevation.Card
    GlassKind.Sheet -> AronTokens.Elevation.Sheet
    GlassKind.Dialog -> AronTokens.Elevation.Dialog
    else -> AronTokens.Elevation.Page
}

/**
 * The geometry of one surface, built once per size inside `drawWithCache`: a uniform rounded rectangle is drawn with
 * `drawRoundRect` (no allocation per frame); any other shape (the sheet's top corners, a custom shape) keeps one cached
 * `Outline` for the fill and one, inset by half the stroke, for the edge (android-glass s2: "non-uniform shapes cache one Path per size").
 */
private class SurfaceGeometry(shape: Shape, size: Size, strokePx: Float, density: Density, layoutDirection: LayoutDirection) {
    private val radius: Float? = density.uniformCornerPx(shape, size)
    private val corner: CornerRadius = CornerRadius(radius ?: 0f)
    private val strokeCorner: CornerRadius = CornerRadius(((radius ?: 0f) - strokePx / 2).coerceAtLeast(0f))
    private val half = strokePx / 2
    private val strokeSize = Size(size.width - strokePx, size.height - strokePx)
    private val fillOutline: Outline? = if (radius == null) shape.createOutline(size, layoutDirection, density) else null
    private val strokeOutline: Outline? = if (radius == null) shape.createOutline(strokeSize, layoutDirection, density) else null
    val strokeWidth = strokePx

    fun DrawScope.fill(brush: Brush) { if (fillOutline != null) drawOutline(fillOutline, brush) else drawRoundRect(brush, cornerRadius = corner) }
    fun DrawScope.fill(color: Color) { if (fillOutline != null) drawOutline(fillOutline, color) else drawRoundRect(color, cornerRadius = corner) }
    fun DrawScope.edge(brush: Brush) {
        if (strokeOutline != null) translate(half, half) { drawOutline(strokeOutline, brush, style = Stroke(strokeWidth)) }
        else drawRoundRect(brush, Offset(half, half), strokeSize, strokeCorner, style = Stroke(strokeWidth))
    }
    fun DrawScope.edge(color: Color) {
        if (strokeOutline != null) translate(half, half) { drawOutline(strokeOutline, color, style = Stroke(strokeWidth)) }
        else drawRoundRect(color, Offset(half, half), strokeSize, strokeCorner, style = Stroke(strokeWidth))
    }
}

/** The uniform corner radius of [shape] in px, or null when all four corners are not the same (then an outline is cached). */
private fun Density.uniformCornerPx(shape: Shape, size: Size): Float? = when (shape) {
    RectangleShape -> 0f
    is CornerBasedShape -> {
        val a = shape.topStart.toPx(size, this); val b = shape.topEnd.toPx(size, this)
        val c = shape.bottomStart.toPx(size, this); val d = shape.bottomEnd.toPx(size, this)
        if (a == b && b == c && c == d) a else null
    }
    else -> null
}

/** True on the API levels where gradient strokes and coloured shadows are available (android-glass s2). */
private val gradientEdgeSupported: Boolean get() = Build.VERSION.SDK_INT >= 28

/**
 * The one glass painter (android-glass s1 item 1): nothing else in the apps calls `shadow`, `blur` or `graphicsLayer` for look.
 * Tier A and B: one `drawWithCache` per surface, brushes built once per size; children are NOT clipped (a child that needs
 * rounding carries its own radius). Tier C and sunlight: a plain fill plus a solid stroke, no shadow, no gradient.
 * [kind] selects the recipe (android-glass s2 and s3); the older `(modifier, shape, tier, elevation, onClick, enabled)`
 * call shape still works and paints the Field recipe. A clip is applied only when [onClick] is given, to bound the ripple.
 */
@Composable
fun GlassSurface(
    modifier: Modifier = Modifier,
    shape: Shape? = null,
    tier: GlassTier = LocalGlassTier.current,
    elevation: Dp? = null,
    onClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    kind: GlassKind = GlassKind.Field,
    content: @Composable BoxScope.() -> Unit,
) {
    val c = LocalAronColors.current
    val resolvedShape = shape ?: glassShapeFor(kind)
    val resolvedElevation = elevation ?: glassElevationFor(kind)
    val paint = Modifier.glassPaint(kind, tier, resolvedShape, resolvedElevation)
    val click = if (onClick != null) Modifier.clip(resolvedShape).clickable(enabled = enabled, role = Role.Button, onClick = onClick) else Modifier
    Box(modifier.aronFocusRing(resolvedShape).then(paint).then(click), content = content)
}

/**
 * The OPAQUE content surface (docs/32 s2a item 1): the Field recipe (96 percent fill over the page, lit 1 dp edge, `elev.card`
 * shadow; sunlight and tier C: a solid border and no shadow), so every number, status and primary action on it keeps its
 * contrast in sun. Children are unclipped unless [onClick] needs a ripple bound.
 */
@Composable
fun AronCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    content: @Composable BoxScope.() -> Unit,
) = GlassSurface(modifier = modifier, kind = GlassKind.Field, onClick = onClick, enabled = enabled, content = content)

/**
 * The paint of one kind in one tier: shadow (A, B only), then the body, shade, sheen and edge in one draw pass, all from
 * the resolved [shape] (a sheet keeps its 28 dp top corners, a custom shape is honoured). Tier C and sunlight: a plain
 * fill plus a solid stroke, no shadow, no gradient.
 */
@Composable
internal fun Modifier.glassPaint(kind: GlassKind, tier: GlassTier, shape: Shape, elevation: Dp): Modifier {
    val c = LocalAronColors.current
    val solid = tier == GlassTier.C || c.sunlight
    if (solid) {
        val stroke = if (c.sunlight) AronTokens.Stroke.SunlightHairline else AronTokens.Stroke.Hairline
        val fill = c.surfaceSolid
        val edge = c.borderSolid
        return drawWithCache {
            val w = stroke.toPx()
            val g = SurfaceGeometry(shape, size, w, this, layoutDirection)
            onDrawBehind {
                with(g) {
                    fill(fill)
                    when (kind) {
                        GlassKind.Row -> Unit // rows carry a divider, not a stroke (android-glass s2)
                        GlassKind.TopBar -> drawRect(edge, Offset(0f, size.height - w), Size(size.width, w))
                        GlassKind.ActionBar -> drawRect(edge, Offset.Zero, Size(size.width, w))
                        else -> edge(edge)
                    }
                }
            }
        }
    }
    val shadowed = elevation > AronTokens.Elevation.Page && kind != GlassKind.Row && kind != GlassKind.Tile && kind != GlassKind.TopBar && kind != GlassKind.ActionBar
    val shadow = if (!shadowed) Modifier else Modifier.shadow(
        if (gradientEdgeSupported) elevation else elevation / 2, shape, clip = false,
        ambientColor = if (c.dark) AronTokens.ShadowTint.Dark else AronTokens.ShadowTint.Light,
        spotColor = if (c.dark) AronTokens.ShadowTint.Dark else AronTokens.ShadowTint.Light,
    )
    val dark = c.dark
    val tierA = tier == GlassTier.A
    return this.then(shadow).drawWithCache {
        val h = size.height; val w = size.width
        val hairline = AronTokens.Stroke.Hairline.toPx()
        val g = SurfaceGeometry(shape, size, hairline, this, layoutDirection)
        // body
        val body: Brush = when (kind) {
            GlassKind.Field, GlassKind.ActionBar -> if (dark) Brush.verticalGradient(listOf(c.surfaceField, c.surfaceField)) else Brush.verticalGradient(listOf(c.surfaceField, c.surfaceFieldBottom))
            GlassKind.Tile -> if (dark) Brush.verticalGradient(listOf(Color.White.copy(alpha = AronTokens.Alpha.TileDark), Color.White.copy(alpha = AronTokens.Alpha.TileDark)))
            else Brush.verticalGradient(listOf(Color.White.copy(alpha = AronTokens.Alpha.TileTop), Color.White.copy(alpha = AronTokens.Alpha.TileBottom)))
            GlassKind.Row -> Brush.verticalGradient(listOf(c.surfaceField, c.surfaceField))
            GlassKind.TopBar -> {
                val base = c.surfaceGlassStrong.copy(alpha = if (tierA) AronTokens.Alpha.BarTierA else AronTokens.Alpha.BarTierB)
                Brush.verticalGradient(listOf(base, base))
            }
            GlassKind.Sheet, GlassKind.Dialog -> Brush.verticalGradient(listOf(c.surfaceSolid, c.surfaceSolid))
        }
        // dark bottom shade over the bottom 55 percent (fade-in from the same RGB at alpha 0, never Color.Transparent)
        val shade: Brush? = if (dark && (kind == GlassKind.Field || kind == GlassKind.Tile) && h > 0f) {
            Brush.verticalGradient(0f to Color.Black.copy(alpha = 0f), 1f to Color.Black.copy(alpha = AronTokens.Alpha.DarkShade), startY = h * 0.45f, endY = h)
        } else null
        // tile sheen over the top 45 percent
        val sheen: Brush? = if (!dark && kind == GlassKind.Tile && h > 0f) {
            Brush.verticalGradient(0f to Color.White.copy(alpha = AronTokens.Alpha.TileSheen), 1f to Color.White.copy(alpha = 0f), startY = 0f, endY = h * 0.45f)
        } else null
        val tint: Color? = if (tierA && kind == GlassKind.TopBar) c.accent.copy(alpha = AronTokens.Alpha.BarTintA) else null
        // edge
        val edge: Brush? = when (kind) {
            GlassKind.Field, GlassKind.Tile, GlassKind.Sheet, GlassKind.Dialog ->
                if (gradientEdgeSupported) Brush.verticalGradient(listOf(c.borderHairlineTop, c.borderHairlineBottom)) else Brush.verticalGradient(listOf(c.borderHairlineBottom, c.borderHairlineBottom))
            else -> null
        }
        val lineEdge: Color? = when (kind) { GlassKind.TopBar, GlassKind.ActionBar -> c.borderHairlineBottom; else -> null }
        onDrawBehind {
            with(g) {
                fill(body)
                shade?.let { fill(it) }
                sheen?.let { fill(it) }
                tint?.let { fill(it) }
                edge?.let { edge(it) }
            }
            lineEdge?.let {
                if (kind == GlassKind.TopBar) drawRect(it, Offset(0f, h - hairline), Size(w, hairline)) else drawRect(it, Offset.Zero, Size(w, hairline))
            }
        }
    }
}
