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
import com.aktcl.aron.core.ui.LocalAronLook

/**
 * Every TSO input sits on a SOLID surface (a field is a figure the rep reads in sunlight, never text over the page), in the existing-Aron look: white card fill, grey outline, purple while focused.
 */
@Composable
fun TsoTextField(
    value: String, onValueChange: (String) -> Unit, modifier: Modifier = Modifier, singleLine: Boolean = false, enabled: Boolean = true,
    label: (@Composable () -> Unit)? = null, minLines: Int = 1, visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default, supportingText: (@Composable () -> Unit)? = null,
    /** What TalkBack says for the field: the visible label sits above it as separate text, so the field itself needs its own name. */
    name: String? = null,
) {
    val c = LocalAronLook.current
    // the label sits ABOVE the field (never in the outline notch, which would paint a patch over the page gradient and clip at large fonts)
    androidx.compose.foundation.layout.Column(androidx.compose.ui.Modifier.fillMaxWidth(), verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(4.dp)) {
    if (label != null) androidx.compose.material3.ProvideTextStyle(androidx.compose.material3.MaterialTheme.typography.labelMedium.copy(color = c.inkMuted)) { label() }
    OutlinedTextField(
        value, onValueChange, if (name != null) modifier.semantics { contentDescription = name } else modifier, enabled = enabled, singleLine = singleLine, minLines = minLines, supportingText = supportingText,
        visualTransformation = visualTransformation, keyboardOptions = keyboardOptions,
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = c.card, unfocusedContainerColor = c.card, disabledContainerColor = c.card,
            focusedTextColor = c.ink, unfocusedTextColor = c.ink, disabledTextColor = c.inkMuted,
            focusedBorderColor = c.purple, unfocusedBorderColor = c.underline, focusedLabelColor = c.purple, unfocusedLabelColor = c.inkMuted,
            cursorColor = c.purple,
        ),
    )
    }
}
