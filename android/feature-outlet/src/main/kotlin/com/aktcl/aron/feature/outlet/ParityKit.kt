package com.aktcl.aron.feature.outlet

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.aktcl.aron.core.ui.AronIcons
import com.aktcl.aron.core.ui.AronLook
import com.aktcl.aron.core.ui.AronTokens
import com.aktcl.aron.core.ui.BannerKind
import com.aktcl.aron.core.ui.LocalAronLook
import com.aktcl.aron.core.ui.aron.AronInfoCard
import com.aktcl.aron.core.ui.aron.AronNoticeCard
import com.aktcl.aron.core.ui.aron.AronPrimaryButton
import com.aktcl.aron.core.ui.aron.AronSecondaryButton

// The outlet forms (new shop, permanent close, change information, cluster, add to route, force sale) in the parity look
// (PARITY-SPEC: grey page, white cards, gradient primary). The helpers the visit screens share (OutletKit) stay as they are.

/** The white 12 dp card of the parity look. */
@Composable
internal fun FormCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.clip(RoundedCornerShape(AronLook.CardRadius)).background(LocalAronLook.current.card), content = content)
}

/** A notice: the pink card with the red edge for an error or a warning, the white info card otherwise (the words carry the meaning). */
@Composable
internal fun FormNotice(text: String, modifier: Modifier = Modifier, kind: BannerKind = BannerKind.Info) {
    when (kind) {
        BannerKind.Error, BannerKind.Warning -> AronNoticeCard(text, modifier)
        else -> AronInfoCard(text, modifier)
    }
}

/** The label above the field (decorative: the field carries it as its name), then the 56 dp field on the grey fill with a purple focus edge. */
@Composable
internal fun FormField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    isError: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
    singleLine: Boolean = true,
    fieldModifier: Modifier = Modifier,
) {
    val look = LocalAronLook.current
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(AronTokens.Space.Xs)) {
        Text(label, Modifier.clearAndSetSemantics { }, style = MaterialTheme.typography.bodySmall, color = look.inkMuted)
        OutlinedTextField(
            value = value, onValueChange = onValueChange,
            placeholder = { Text(label, style = MaterialTheme.typography.bodyLarge, color = look.inkMuted) },
            modifier = fieldModifier.fillMaxWidth().heightIn(min = AronTokens.Touch.Primary).semantics { contentDescription = label },
            singleLine = singleLine, isError = isError,
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
            shape = RoundedCornerShape(AronLook.CardRadius),
            textStyle = MaterialTheme.typography.bodyLarge,
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = look.page, unfocusedContainerColor = look.page, errorContainerColor = look.page,
                focusedBorderColor = look.purple, unfocusedBorderColor = look.underline, errorBorderColor = look.red,
                focusedTextColor = look.ink, unfocusedTextColor = look.ink, errorTextColor = look.ink,
                focusedPlaceholderColor = look.inkMuted, unfocusedPlaceholderColor = look.inkMuted, errorPlaceholderColor = look.inkMuted,
                cursorColor = look.purple,
            ),
        )
    }
}

/** A choice chip: the selected one filled `aron.purple` with a check, the others grey with purple words; 48 dp target, tab role. */
@Composable
internal fun FormChip(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val look = LocalAronLook.current
    Box(
        modifier.heightIn(min = AronLook.Touch).clip(RoundedCornerShape(AronLook.CardRadius))
            .background(if (selected) look.purple else look.page)
            .selectable(selected = selected, role = Role.Tab, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Row(Modifier.padding(horizontal = AronTokens.Space.L), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AronTokens.Space.Xs)) {
            if (selected) Icon(AronIcons.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(AronTokens.Space.L))
            Text(label, style = MaterialTheme.typography.labelLarge, color = if (selected) Color.White else look.purple, maxLines = 1)
        }
    }
}

/** A radio row: a white card, a purple dot (checked inside when selected) and the label; the whole row is the target with radio semantics. */
@Composable
internal fun FormRadio(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val look = LocalAronLook.current
    Row(
        modifier.fillMaxWidth().heightIn(min = AronTokens.Touch.Primary).clip(RoundedCornerShape(AronLook.CardRadius)).background(look.card)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick).padding(horizontal = AronTokens.Space.L),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AronTokens.Space.M),
    ) {
        Box(Modifier.size(24.dp).clip(CircleShape).background(if (selected) look.purple else look.underline), contentAlignment = Alignment.Center) {
            Box(Modifier.size(20.dp).clip(CircleShape).background(if (selected) look.purple else look.card), contentAlignment = Alignment.Center) {
                if (selected) Icon(AronIcons.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(14.dp))
            }
        }
        Text(label, style = MaterialTheme.typography.bodyLarge, color = look.ink)
    }
}

/** An error line under a field or a form: a warning glyph and the words in `aron.red`. */
@Composable
internal fun FormError(text: String, modifier: Modifier = Modifier) {
    val look = LocalAronLook.current
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AronTokens.Space.S)) {
        Icon(AronIcons.Warning, contentDescription = null, tint = look.red, modifier = Modifier.size(AronTokens.Space.L))
        Text(text, style = MaterialTheme.typography.bodySmall, color = look.red)
    }
}

/** The action row of the forms: the gradient primary (and the grey secondary at 40 percent) on the grey page, above the keyboard; the reason shows while the primary is disabled. */
@Composable
internal fun FormBar(primary: BarAction, secondary: BarAction? = null, reason: String? = null, modifier: Modifier = Modifier) {
    val look = LocalAronLook.current
    Column(
        modifier.fillMaxWidth().background(look.page)
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))
            .imePadding()
            .padding(horizontal = AronTokens.Space.Screen, vertical = AronTokens.Space.M),
        verticalArrangement = Arrangement.spacedBy(AronTokens.Space.S),
    ) {
        if (reason != null) Text(reason, style = MaterialTheme.typography.bodySmall, color = look.inkMuted, textAlign = TextAlign.Start)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(AronTokens.Space.M), verticalAlignment = Alignment.CenterVertically) {
            if (secondary != null) {
                AronSecondaryButton(secondary.text, secondary.onClick, Modifier.weight(0.4f).then(secondary.tag?.let { Modifier.testTagged(it) } ?: Modifier), enabled = secondary.enabled)
            }
            AronPrimaryButton(
                primary.text, primary.onClick, Modifier.weight(if (secondary != null) 0.6f else 1f).then(primary.tag?.let { Modifier.testTagged(it) } ?: Modifier),
                enabled = primary.enabled,
            )
        }
    }
}

/** A text action in `aron.purple` (48 dp target). */
@Composable
internal fun FormLink(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val look = LocalAronLook.current
    Box(modifier.heightIn(min = AronLook.Touch).clickable(enabled = enabled, role = Role.Button, onClick = onClick), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.titleSmall, color = if (enabled) look.purple else look.disabledText, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = AronTokens.Space.M))
    }
}
