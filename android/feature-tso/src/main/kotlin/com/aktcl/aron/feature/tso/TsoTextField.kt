package com.aktcl.aron.feature.tso

import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.input.VisualTransformation
import com.aktcl.aron.core.ui.LocalAronColors

/**
 * Every TSO input sits on a SOLID surface (docs/32 s2a: a field is a figure the rep reads in sunlight, never text over the page
 * gradient), with the kit's text and border colours; the contrast of those pairs is the kit ledger's.
 */
@Composable
fun TsoTextField(
    value: String, onValueChange: (String) -> Unit, modifier: Modifier = Modifier, singleLine: Boolean = false, enabled: Boolean = true,
    label: (@Composable () -> Unit)? = null, minLines: Int = 1, visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default, supportingText: (@Composable () -> Unit)? = null,
    /** What TalkBack says for the field: the visible label sits above it as separate text, so the field itself needs its own name. */
    name: String? = null,
) {
    val c = LocalAronColors.current
    // the label sits ABOVE the field (never in the outline notch, which would paint a patch over the page gradient and clip at large fonts)
    androidx.compose.foundation.layout.Column(androidx.compose.ui.Modifier.fillMaxWidth(), verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(4.dp)) {
    if (label != null) androidx.compose.material3.ProvideTextStyle(androidx.compose.material3.MaterialTheme.typography.labelMedium.copy(color = c.textSecondary)) { label() }
    OutlinedTextField(
        value, onValueChange, if (name != null) modifier.semantics { contentDescription = name } else modifier, enabled = enabled, singleLine = singleLine, minLines = minLines, supportingText = supportingText,
        visualTransformation = visualTransformation, keyboardOptions = keyboardOptions,
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = c.surfaceSolid, unfocusedContainerColor = c.surfaceSolid, disabledContainerColor = c.surfaceSolid,
            focusedTextColor = c.textPrimary, unfocusedTextColor = c.textPrimary, disabledTextColor = c.textSecondary,
            focusedBorderColor = c.borderFocus, unfocusedBorderColor = c.borderInput, focusedLabelColor = c.accentText, unfocusedLabelColor = c.textSecondary,
            cursorColor = c.accent,
        ),
    )
    }
}
