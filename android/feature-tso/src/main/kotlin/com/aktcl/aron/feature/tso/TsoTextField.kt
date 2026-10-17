package com.aktcl.aron.feature.tso

import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
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
) {
    val c = LocalAronColors.current
    OutlinedTextField(
        value, onValueChange, modifier, enabled = enabled, singleLine = singleLine, label = label, minLines = minLines, supportingText = supportingText,
        visualTransformation = visualTransformation, keyboardOptions = keyboardOptions,
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = c.surfaceSolid, unfocusedContainerColor = c.surfaceSolid, disabledContainerColor = c.surfaceSolid,
            focusedTextColor = c.textPrimary, unfocusedTextColor = c.textPrimary, disabledTextColor = c.textSecondary,
            focusedBorderColor = c.borderFocus, unfocusedBorderColor = c.borderInput, focusedLabelColor = c.accentText, unfocusedLabelColor = c.textSecondary,
            cursorColor = c.accent,
        ),
    )
}
