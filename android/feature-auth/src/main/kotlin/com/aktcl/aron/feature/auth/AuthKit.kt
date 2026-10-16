package com.aktcl.aron.feature.auth

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.aktcl.aron.core.common.AppLanguage
import com.aktcl.aron.core.ui.AronPrimaryButton
import com.aktcl.aron.core.ui.AronSecondaryButton
import com.aktcl.aron.core.ui.AronTokens
import com.aktcl.aron.core.ui.GlassKind
import com.aktcl.aron.core.ui.GlassSurface
import com.aktcl.aron.core.ui.LanguageToggle
import com.aktcl.aron.core.ui.LocalAppLanguage
import com.aktcl.aron.core.ui.LocalAronColors
import com.aktcl.aron.core.ui.SunlightToggle
import com.aktcl.aron.core.ui.aronFocusRing

/** The 56 dp field of android-glass s6.12. */
private val FieldHeight = 56.dp

/** The brand mark: 72 dp (screens-sr s1), a squircle of radius 22. */
private val MarkSize = 72.dp
private val MarkRadius = 22.dp

/**
 * The top row of the logged-out screens (screens-sr s1): the language toggle and, when the host wires it, the sun
 * switch (reps log in outdoors; it works before sign-in). No title bar.
 */
@Composable
internal fun AuthTopRow(onLanguageSelect: (AppLanguage) -> Unit, sunlight: Boolean, onSunlight: ((Boolean) -> Unit)?) {
    Row(
        Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top)).heightIn(min = AronTokens.Touch.Min),
        horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically,
    ) {
        LanguageToggle(current = LocalAppLanguage.current, onSelect = onLanguageSelect)
        if (onSunlight != null) SunlightToggle(sunlight, onSunlight)
    }
}

/**
 * The brand block: a 72 dp `accent.container` squircle carrying a glyph drawn here (a peak with a point at its top: the
 * route and the outlet; no vendor artwork, docs/32 s4) and the app title in `type.title`, centred.
 */
@Composable
internal fun BrandBlock(appTitle: String) {
    val c = LocalAronColors.current
    val fill = c.accentContainer
    val ink = c.accentOnContainer
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(AronTokens.Space.M)) {
        Box(
            Modifier.size(MarkSize).drawBehind {
                drawRoundRect(fill, cornerRadius = CornerRadius(MarkRadius.toPx()))
                val w = size.width; val h = size.height
                val stroke = 6.dp.toPx()
                // the peak: two strokes from the lower corners up to the apex, round caps and join
                val peak = Path().apply {
                    moveTo(w * 0.26f, h * 0.74f)
                    lineTo(w * 0.50f, h * 0.30f)
                    lineTo(w * 0.74f, h * 0.74f)
                }
                drawPath(peak, ink, style = Stroke(stroke, cap = StrokeCap.Round, join = StrokeJoin.Round))
                // the bar of the A as a short stroke, and the point above the apex
                drawLine(ink, Offset(w * 0.40f, h * 0.60f), Offset(w * 0.60f, h * 0.60f), strokeWidth = stroke, cap = StrokeCap.Round)
                drawCircle(ink, radius = stroke * 0.55f, center = Offset(w * 0.50f, h * 0.17f))
            },
        )
        Text(appTitle, style = MaterialTheme.typography.titleLarge, color = c.textPrimary, textAlign = TextAlign.Center)
    }
}

/**
 * The kit-styled text field (android-glass s6.12): 56 dp, `radius.chip`, `surface.solid.raised`, a 1 dp `border.input`
 * (2 dp `border.focus` when focused, `danger` on [error]), label above in `type.caption`, placeholder in `text.placeholder`,
 * an optional 48 dp trailing text action, and a helper or error line below. No floating label. [modifier] (and its test
 * tag) goes on the field itself, which also carries [label] as its accessible name, so TalkBack never meets an unnamed
 * edit box (docs/32 s4) and `performTextInput` on the tag reaches the field.
 */
@Composable
internal fun AuthField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    error: Boolean = false,
    placeholder: String? = null,
    helper: String? = null,
    singleLine: Boolean = true,
    minLines: Int = 1,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    trailing: (@Composable () -> Unit)? = null,
) {
    val c = LocalAronColors.current
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val fill = if (enabled) c.surfaceSolidRaised else c.stateDisabledFill
    val border = when { error -> c.danger; focused -> c.borderFocus; else -> c.borderInput }
    val borderWidth = when {
        focused || error -> if (c.sunlight) AronTokens.Stroke.SunlightFocus else AronTokens.Stroke.Focus
        c.sunlight -> AronTokens.Stroke.SunlightHairline
        else -> AronTokens.Stroke.Hairline
    }
    val textStyle = MaterialTheme.typography.bodyLarge.copy(color = if (enabled) c.textPrimary else c.textDisabled)
    val placeholderColor = c.textPlaceholder
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(AronTokens.Space.Xs)) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = modifier.fillMaxWidth().semantics { contentDescription = label },
            enabled = enabled,
            textStyle = textStyle,
            keyboardOptions = keyboardOptions,
            keyboardActions = keyboardActions,
            singleLine = singleLine,
            minLines = minLines,
            visualTransformation = visualTransformation,
            interactionSource = interaction,
            cursorBrush = SolidColor(c.accent),
            decorationBox = { inner ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = FieldHeight)
                        .drawBehind {
                            val r = CornerRadius(AronTokens.Radius.Chip.toPx())
                            drawRoundRect(fill, cornerRadius = r)
                            val w = borderWidth.toPx()
                            drawRoundRect(border, Offset(w / 2, w / 2), Size(size.width - w, size.height - w), CornerRadius(r.x - w / 2), style = Stroke(w))
                        }
                        .padding(start = AronTokens.Space.L, end = if (trailing != null) AronTokens.Space.Xs else AronTokens.Space.L, top = AronTokens.Space.S, bottom = AronTokens.Space.S),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                        if (value.isEmpty() && placeholder != null) Text(placeholder, style = MaterialTheme.typography.bodyLarge, color = placeholderColor)
                        inner()
                    }
                    if (trailing != null) trailing()
                }
            },
        )
        if (helper != null) Text(helper, style = MaterialTheme.typography.bodySmall, color = if (error) c.dangerOnContainer else c.textSecondary)
    }
}

/**
 * The action bar of the logged-out screens (the `AronBottomBar` geometry of android-glass s6.7, with test tags on its
 * buttons): the ActionBar recipe, 80 dp (104 with a reason) plus the navigation inset, riding above the keyboard; a
 * disabled primary shows its reason as a caption (never while [busy]: the label stays and the arc turns, screens-sr s1);
 * an optional secondary takes 40 percent beside the primary. The reason's height is reserved, so the bar never animates.
 */
@Composable
internal fun AuthBar(
    primaryText: String,
    onPrimary: () -> Unit,
    primaryTag: String,
    enabled: Boolean = true,
    busy: Boolean = false,
    reason: String? = null,
    secondaryText: String? = null,
    onSecondary: (() -> Unit)? = null,
    secondaryTag: String? = null,
    secondaryEnabled: Boolean = true,
) {
    val c = LocalAronColors.current
    val reserve = if (reason != null) AronTokens.Size.BottomBarWithReason else AronTokens.Size.BottomBar
    GlassSurface(modifier = Modifier.fillMaxWidth(), kind = GlassKind.ActionBar) {
        Column(
            Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))
                .imePadding()
                .heightIn(min = reserve)
                .padding(horizontal = AronTokens.Space.Screen, vertical = AronTokens.Space.M),
            verticalArrangement = Arrangement.spacedBy(AronTokens.Space.S),
        ) {
            if (reason != null && !enabled && !busy) Text(reason, style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AronTokens.Space.M)) {
                if (secondaryText != null && onSecondary != null) {
                    AronSecondaryButton(
                        secondaryText, onSecondary, Modifier.weight(0.4f).then(if (secondaryTag != null) Modifier.testTag(secondaryTag) else Modifier),
                        enabled = secondaryEnabled, tall = true,
                    )
                }
                AronPrimaryButton(
                    primaryText, onPrimary,
                    Modifier.weight(if (secondaryText != null) 0.6f else 1f).widthIn(min = AronTokens.Size.BarPrimaryMin).testTag(primaryTag),
                    enabled = enabled, busy = busy,
                )
            }
        }
    }
}

/** A hairline divider inside a card. */
@Composable
internal fun CardDivider() {
    val c = LocalAronColors.current
    val color = c.borderDivider
    val h = if (c.sunlight) AronTokens.Stroke.SunlightHairline else AronTokens.Stroke.Hairline
    Spacer(Modifier.fillMaxWidth().height(h).drawBehind { drawRect(color) })
}

/** The footer of the logged-out screens: the version (localised digits) and the AKTCL line, centred captions. */
@Composable
internal fun AuthFooter(versionText: String, versionModifier: Modifier = Modifier) {
    val c = LocalAronColors.current
    Column(Modifier.fillMaxWidth().padding(vertical = AronTokens.Space.L), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(versionText, style = MaterialTheme.typography.bodySmall, color = c.textSecondary, textAlign = TextAlign.Center, modifier = versionModifier)
        Text(stringResource(com.aktcl.aron.core.ui.R.string.core_ui_brand_footer), style = MaterialTheme.typography.bodySmall, color = c.textSecondary, textAlign = TextAlign.Center)
    }
}
