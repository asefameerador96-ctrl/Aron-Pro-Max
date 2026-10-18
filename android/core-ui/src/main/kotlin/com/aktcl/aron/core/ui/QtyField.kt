package com.aktcl.aron.core.ui

import androidx.compose.foundation.layout.Column
import com.aktcl.aron.core.common.LocaleDigits
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/** What a typed quantity means: the whole number it holds, and whether it had to be pulled down to [QtyInput.parse]'s limit. */
data class TypedQty(val value: Int, val clamped: Boolean)

/** Parsing of a typed quantity (Latin or Bengali digits, anything else ignored); pure, shared by [AronQtyField] and its tests. */
object QtyInput {
    /** Digits only; an empty or digit-free text is 0; a value above [max] is accepted down to [max] and reported as clamped. */
    fun parse(text: String, max: Int): TypedQty {
        val digits = text.map { if (it in '০'..'৯') '0' + (it - '০') else it }.filter { it in '0'..'9' }.take(MAX_DIGITS)
        if (digits.isEmpty()) return TypedQty(0, false)
        val n = digits.joinToString("").toLong()
        return if (n > max) TypedQty(max.coerceAtLeast(0), true) else TypedQty(n.toInt(), false)
    }

    /** Longer inputs are cut: no quantity of the app needs more than nine digits, and it keeps the arithmetic in Long. */
    const val MAX_DIGITS = 9
}

/**
 * The typed-quantity field that sits next to [AronStepper] (owner ruling D-17: a rep may type a count instead of stepping to it).
 * The field is a numeric keypad box showing [value] in the app's digits; every keystroke is parsed by [QtyInput.parse], and a
 * number above [max] is accepted down to [max] and flagged in words ([clampedText], e.g. "Most you can enter: 40") with a
 * warning icon, so the rep sees what happened and never silently loses a digit. [name] is the spoken name. A change made
 * elsewhere (the stepper) replaces the text.
 */
@Composable
fun AronQtyField(
    value: Int,
    onValueChange: (Int) -> Unit,
    name: String,
    max: Int,
    clampedText: @Composable (Int) -> String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val c = LocalAronColors.current
    val lang = LocalAppLanguage.current
    val shown = { n: Int -> if (n > 0) LocaleDigits.formatInteger(n.toLong(), lang) else "" }
    var text by remember { mutableStateOf(shown(value)) }
    var clamped by remember { mutableStateOf(false) }
    // the stepper (or a restore) changed the number: show it, unless the text already means that number
    LaunchedEffect(value) { if (QtyInput.parse(text, Int.MAX_VALUE).value != value) { text = shown(value); clamped = false } }
    Column {
        OutlinedTextField(
            value = text,
            onValueChange = { raw ->
                val typed = QtyInput.parse(raw, max)
                clamped = typed.clamped
                text = shown(typed.value)
                if (typed.value != value) onValueChange(typed.value)
            },
            enabled = enabled, singleLine = true, isError = clamped,
            placeholder = { Text(localizedNumber(0L), style = MaterialTheme.typography.titleMedium, color = c.textPlaceholder, textAlign = TextAlign.Center) },
            modifier = modifier.width(QtyFieldWidth).heightIn(min = AronTokens.Touch.Primary).semantics { contentDescription = name },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            shape = RoundedCornerShape(AronTokens.Radius.Chip),
            textStyle = MaterialTheme.typography.titleMedium.copy(textAlign = TextAlign.Center),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = c.surfaceSolidRaised, unfocusedContainerColor = c.surfaceSolidRaised, errorContainerColor = c.surfaceSolidRaised, disabledContainerColor = c.stateDisabledFill,
                focusedBorderColor = c.borderFocus, unfocusedBorderColor = c.borderInput, errorBorderColor = c.danger,
                focusedTextColor = c.textPrimary, unfocusedTextColor = c.textPrimary, errorTextColor = c.textPrimary, disabledTextColor = c.stateDisabledLabel,
                cursorColor = c.accent,
            ),
        )
        if (clamped) {
            // colour is never the only cue: the icon and the words say the number was pulled down
            Row(Modifier.padding(top = AronTokens.Space.Xs), verticalAlignment = Alignment.CenterVertically) {
                Icon(AronIcons.Warning, contentDescription = null, tint = c.danger, modifier = Modifier.size(AronTokens.Size.IconSmall))
                Text(clampedText(max), Modifier.padding(start = AronTokens.Space.Xs), style = MaterialTheme.typography.bodySmall, color = c.textPrimary)
            }
        }
    }
}

private val QtyFieldWidth = 104.dp
