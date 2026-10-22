package com.aktcl.aron.core.ui.aron

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.aktcl.aron.core.ui.AronLook
import com.aktcl.aron.core.ui.LocalAronLook

/** The white content card of the existing app (12 dp radius, soft 2 dp shadow, 16 dp padding); [onClick] makes the whole card a button. */
@Composable
fun AronCard(modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    val look = LocalAronLook.current
    val shape = RoundedCornerShape(AronLook.CardRadius)
    Column(
        modifier
            .fillMaxWidth()
            .shadow(2.dp, shape)
            .clip(shape)
            .background(look.card)
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .padding(16.dp),
        content = content,
    )
}

/**
 * A list row on a white card: optional [leading] picture (40 dp), [title] (17 sp medium), optional [subtitle] (14 sp grey) and a
 * [trailing] figure in purple bold at the end. At least 56 dp tall; [onClick] makes it a button.
 */
@Composable
fun AronListRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    trailing: String? = null,
    leading: Painter? = null,
    onClick: (() -> Unit)? = null,
) {
    val look = LocalAronLook.current
    val shape = RoundedCornerShape(AronLook.CardRadius)
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .shadow(1.dp, shape)
            .clip(shape)
            .background(look.card)
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) {
            Image(leading, contentDescription = null, modifier = Modifier.size(40.dp))
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = lookStyle(17.sp, color = look.ink, medium = true))
            if (subtitle != null) Text(subtitle, style = lookStyle(14.sp, color = look.inkMuted))
        }
        if (trailing != null) Text(trailing, style = lookStyle(17.sp, bold = true, color = look.purple, align = TextAlign.End), modifier = Modifier.padding(start = 12.dp))
    }
}

/**
 * A text field in the existing app's style: the [label] above in grey 14 sp, the value in 18 sp over a grey underline (purple while
 * focused, red with [errorText]). [password] hides the characters. The field is at least 48 dp tall and reads [label] to TalkBack.
 * [imeAction] / [onImeAction]: the keyboard action (Done on the password submits the login); [trailing]: an end slot inside the field
 * (the show/hide password text button); [minLines]: a taller box (the enrolment token); [fieldModifier] goes on the editable node
 * itself (test tags, performTextInput), [modifier] on the whole block.
 */
@Composable
fun AronField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    errorText: String? = null,
    keyboardType: KeyboardType = KeyboardType.Text,
    singleLine: Boolean = true,
    password: Boolean = false,
    enabled: Boolean = true,
    imeAction: ImeAction = ImeAction.Default,
    onImeAction: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    minLines: Int = 1,
    fieldModifier: Modifier = Modifier,
) {
    val look = LocalAronLook.current
    var focused by remember { mutableStateOf(false) }
    val rule = when {
        errorText != null -> look.red
        focused -> look.purple
        else -> look.underline
    }
    Column(modifier.fillMaxWidth()) {
        Text(label, style = lookStyle(14.sp, color = look.inkMuted))
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            enabled = enabled,
            singleLine = singleLine,
            minLines = if (singleLine) 1 else minLines,
            textStyle = lookStyle(18.sp, color = look.ink),
            cursorBrush = SolidColor(look.purple),
            keyboardOptions = KeyboardOptions(keyboardType = if (password) KeyboardType.Password else keyboardType, imeAction = imeAction),
            keyboardActions = if (onImeAction != null) KeyboardActions(onAny = { onImeAction() }) else KeyboardActions.Default,
            visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
            modifier = fieldModifier
                .fillMaxWidth()
                .heightIn(min = AronLook.Touch)
                .onFocusChanged { focused = it.isFocused }
                .semantics {
                    contentDescription = label
                    if (errorText != null) error(errorText)
                },
            decorationBox = { inner ->
                Box(
                    Modifier.fillMaxWidth().heightIn(min = AronLook.Touch).drawBehind {
                        val stroke = (if (focused || errorText != null) 2 else 1).dp.toPx()
                        drawLine(rule, Offset(0f, size.height - stroke), Offset(size.width, size.height - stroke), stroke)
                    },
                    contentAlignment = Alignment.CenterStart,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                            if (value.isEmpty() && placeholder != null) Text(placeholder, style = lookStyle(18.sp, color = look.underline))
                            inner()
                        }
                        if (trailing != null) trailing()
                    }
                }
            },
        )
        if (errorText != null) Text(errorText, style = lookStyle(13.sp, color = look.red), modifier = Modifier.padding(top = 4.dp))
    }
}

/** A strip of tabs on a white pill; the [selected] one is a gradient pill with white text, the others purple text. 48 dp targets. */
@Composable
fun AronTabs(tabs: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val look = LocalAronLook.current
    Row(modifier.fillMaxWidth().clip(RoundedCornerShape(50)).background(look.card).padding(4.dp)) {
        tabs.forEachIndexed { i, tab ->
            val on = i == selected
            Box(
                Modifier
                    .weight(1f)
                    .heightIn(min = AronLook.Touch)
                    .clip(RoundedCornerShape(50))
                    .then(if (on) Modifier.background(look.barBrush()) else Modifier)
                    .selectable(selected = on, role = Role.Tab, onClick = { onSelect(i) })
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                contentAlignment = Alignment.Center,
            ) { Text(tab, style = lookStyle(16.sp, bold = on, color = if (on) look.onBar else look.purple, align = TextAlign.Center)) }
        }
    }
}

/**
 * A confirm dialog in the existing look: white 12 dp card, purple bold [title], [text], then the grey [dismissText] and the gradient
 * [confirmText] buttons side by side. Omit [dismissText] for a one-button notice.
 */
@Composable
fun AronDialog(
    title: String,
    text: String,
    confirmText: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    dismissText: String? = null,
) {
    val look = LocalAronLook.current
    Dialog(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(AronLook.CardRadius)).background(look.card).padding(20.dp)) {
            Text(title, style = lookStyle(20.sp, bold = true, color = look.purple))
            Spacer(Modifier.height(12.dp))
            Text(text, style = lookStyle(16.sp, color = look.ink))
            Spacer(Modifier.height(20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (dismissText != null) AronSecondaryButton(dismissText, onDismiss, Modifier.weight(1f))
                AronPrimaryButton(confirmText, onConfirm, Modifier.weight(1f))
            }
        }
    }
}

/** A setting row: [label] (and an optional [subtitle]) with a purple switch at the end; the whole row toggles. At least 56 dp. */
@Composable
fun AronSwitchRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit, modifier: Modifier = Modifier, subtitle: String? = null) {
    val look = LocalAronLook.current
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clip(RoundedCornerShape(AronLook.CardRadius))
            .background(look.card)
            .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = lookStyle(17.sp, color = look.ink))
            if (subtitle != null) Text(subtitle, style = lookStyle(14.sp, color = look.inkMuted))
        }
        Switch(
            checked = checked,
            onCheckedChange = null,
            colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = look.purple, uncheckedThumbColor = look.inkMuted, uncheckedTrackColor = look.disabledFill),
        )
    }
}

/** The red logout button (the web sidebar's Logout, and the app settings): solid `aron.red`, white bold label, 8 dp radius, 52 dp. */
@Composable
fun AronLogoutButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val look = LocalAronLook.current
    Box(
        modifier
            .heightIn(min = AronLook.ButtonHeight)
            .clip(RoundedCornerShape(AronLook.ButtonRadius))
            .background(look.red)
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(text, style = lookStyle(18.sp, bold = true, color = Color.White, align = TextAlign.Center), modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) }
}
