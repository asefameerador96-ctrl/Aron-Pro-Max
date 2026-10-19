package com.aktcl.aron.feature.auth

import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.aktcl.aron.core.common.AppLanguage
import com.aktcl.aron.core.ui.AronBrandPlate
import com.aktcl.aron.core.ui.AronLook
import com.aktcl.aron.core.ui.AronTokens
import com.aktcl.aron.core.ui.BannerKind
import com.aktcl.aron.core.ui.LocalAronLook
import com.aktcl.aron.core.ui.aron.AronField
import com.aktcl.aron.core.ui.aron.AronInfoCard
import com.aktcl.aron.core.ui.aron.AronLanguageToggle
import com.aktcl.aron.core.ui.aron.AronSunToggle
import com.aktcl.aron.core.ui.aron.AronTextButton
import com.aktcl.aron.core.ui.aron.AronNoticeCard
import com.aktcl.aron.core.ui.aron.AronPrimaryButton
import com.aktcl.aron.core.ui.aron.AronSecondaryButton
import com.aktcl.aron.core.ui.aron.barBrush
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import com.aktcl.aron.core.ui.LocalAppLanguage
import com.aktcl.aron.core.ui.LocalAronColors
import com.aktcl.aron.core.ui.aronFocusRing

/** The 56 dp field of android-glass s6.12. */
private val FieldHeight = 56.dp

/** The brand plate: 72 dp (screens-sr s1), a squircle of radius 22, solid white. */
private val MarkSize = 72.dp
private val MarkRadius = 22.dp

/** The logo inside the plate: 64 dp, so the glyph (92 percent of the picture) leaves about 6 dp above and below it. */
private val MarkLogoSize = 64.dp

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
        AronLanguageToggle(
            stringResource(com.aktcl.aron.core.ui.R.string.core_ui_language_bangla), stringResource(com.aktcl.aron.core.ui.R.string.core_ui_language_english),
            banglaSelected = LocalAppLanguage.current == AppLanguage.BN,
            onSelect = { bangla -> onLanguageSelect(if (bangla) AppLanguage.BN else AppLanguage.EN) },
        )
        if (onSunlight != null) {
            Spacer(Modifier.width(AronTokens.Space.S))
            AronSunToggle(sunlight, onSunlight, stringResource(com.aktcl.aron.core.ui.R.string.core_ui_sunlight_toggle))
        }
    }
}

/**
 * The brand block: a rounded gradient card (`aron.gradient.bar`) carrying the Aron logo (D-BRAND-01) on a 72 dp solid white
 * squircle ([AronBrandPlate]) and the app title in white bold, centred. The plate is opaque in the standard look and in
 * Sunlight alike (docs/32 s2a), so the navy outline of the logo carries the glyph outdoors. The logo is decorative and
 * bundled, so it never waits on the network.
 */
@Composable
internal fun BrandBlock(appTitle: String) {
    val look = LocalAronLook.current
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(AronLook.CardRadius)).background(look.barBrush()).padding(vertical = AronTokens.Space.Xl),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(AronTokens.Space.M),
    ) {
        AronBrandPlate(size = MarkSize, markSize = MarkLogoSize, cornerRadius = MarkRadius)
        Text(appTitle, style = MaterialTheme.typography.titleLarge, color = look.onBar, textAlign = TextAlign.Center)
    }
}

/** The white 12 dp card of the parity look. */
@Composable
internal fun AuthCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.clip(RoundedCornerShape(AronLook.CardRadius)).background(LocalAronLook.current.card), content = content)
}

/** A notice: the pink card with the red edge for an error or a warning, the white info card otherwise (never colour alone: the words carry it). */
@Composable
internal fun AuthBanner(text: String, modifier: Modifier = Modifier, kind: BannerKind = BannerKind.Info) {
    when (kind) {
        BannerKind.Error, BannerKind.Warning -> AronNoticeCard(text, modifier)
        else -> AronInfoCard(text, modifier)
    }
}

/** A text action in `aron.purple`: the kit's underlined text button. */
@Composable
internal fun AuthLink(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) = AronTextButton(text, onClick, modifier, enabled)

/**
 * A text field of the logged-out screens: the kit's [AronField] (label above, value over a purple underline, red with [error]).
 * [modifier] (and its test tag) goes on the editable node, which also carries [label] as its accessible name, so `performTextInput` on
 * the tag reaches the field. [imeAction] with [onImeAction] is the keyboard action (Done on the password submits the login); [trailing]
 * is an end slot inside the field (the show or hide password text button).
 */
@Composable
internal fun AuthField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    error: Boolean = false,
    singleLine: Boolean = true,
    minLines: Int = 1,
    password: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
    imeAction: ImeAction = ImeAction.Default,
    onImeAction: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    AronField(
        value, onValueChange, label, errorText = if (error) " " else null, keyboardType = keyboardType, singleLine = singleLine, password = password,
        enabled = enabled, imeAction = imeAction, onImeAction = onImeAction, trailing = trailing, minLines = minLines, fieldModifier = modifier,
    )
}

/**
 * The action bar of the logged-out screens: the gradient primary (and an optional grey secondary at 40 percent beside it) on the
 * grey page, riding above the keyboard; a disabled primary shows its reason as a caption (never while [busy]: the label stays and
 * the button holds, screens-sr s1). The reason's height is reserved, so the bar never animates.
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
    val look = LocalAronLook.current
    val reserve = if (reason != null) AronTokens.Size.BottomBarWithReason else AronTokens.Size.BottomBar
    Column(
        Modifier
            .fillMaxWidth()
            .background(look.page)
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))
            .imePadding()
            .heightIn(min = reserve)
            .padding(horizontal = AronTokens.Space.Screen, vertical = AronTokens.Space.M),
        verticalArrangement = Arrangement.spacedBy(AronTokens.Space.S),
    ) {
        if (reason != null && !enabled && !busy) Text(reason, style = MaterialTheme.typography.bodySmall, color = look.inkMuted)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AronTokens.Space.M)) {
            if (secondaryText != null && onSecondary != null) {
                AronSecondaryButton(
                    secondaryText, onSecondary, Modifier.weight(0.4f).then(if (secondaryTag != null) Modifier.testTag(secondaryTag) else Modifier),
                    enabled = secondaryEnabled,
                )
            }
            AronPrimaryButton(
                primaryText, onPrimary,
                Modifier.weight(if (secondaryText != null) 0.6f else 1f).widthIn(min = AronTokens.Size.BarPrimaryMin).testTag(primaryTag),
                enabled = enabled && !busy,
            )
        }
    }
}

/** A hairline divider inside a card. */
@Composable
internal fun CardDivider() {
    val color = LocalAronLook.current.tileHairline
    Spacer(Modifier.fillMaxWidth().height(1.dp).drawBehind { drawRect(color) })
}

/** The footer of the logged-out screens: the version (localised digits) and the AKTCL line, centred captions. */
@Composable
internal fun AuthFooter(versionText: String, versionModifier: Modifier = Modifier) {
    val look = LocalAronLook.current
    Column(Modifier.fillMaxWidth().padding(vertical = AronTokens.Space.L), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(versionText, style = MaterialTheme.typography.bodySmall, color = look.inkMuted, textAlign = TextAlign.Center, modifier = versionModifier)
        Text(stringResource(com.aktcl.aron.core.ui.R.string.core_ui_brand_footer), style = MaterialTheme.typography.bodySmall, color = look.inkMuted, textAlign = TextAlign.Center)
    }
}
