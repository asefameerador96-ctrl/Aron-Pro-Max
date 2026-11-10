package com.aktcl.aron.feature.amo

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.aktcl.aron.core.ui.LocalAronLook

object AmoDialogTags { const val OK = "amo_dialog_ok"; const val SAVED = "amo_dialog_saved"; const val CARD = "amo_dialog_card"; const val REASON_INTERNET = "amo_fs_internet"; const val REASON_LOCATION = "amo_fs_location" }

/** Ink colours of the vendor dialogs (screenshots 23, 28, 32, 44): blue bold message, green tick, green OK. All hold 4.5:1 on white. */
internal val DialogBlue = Color(0xFF0B4A9E)
internal val DialogGreen = Color(0xFF1B7F3A)

/** The body of a dialog card (kept apart from the window so goldens can draw it): [icon] (a tick or a warning), the blue message, then [actions]. */
@Composable
fun AmoDialogCard(message: String, icon: String, iconTint: Color, modifier: Modifier = Modifier, actions: @Composable () -> Unit) {
    Column(
        modifier.fillMaxWidth().testTag(AmoDialogTags.CARD).clip(RoundedCornerShape(20.dp)).background(LocalAronLook.current.card).padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(Modifier.size(60.dp).clip(CircleShape).background(iconTint), contentAlignment = Alignment.Center) { Text(icon, style = amoText(32, bold = true, color = Color.White)) }
        Text(message, style = amoText(18, bold = true, color = DialogBlue, align = TextAlign.Center), modifier = Modifier.testTag(AmoDialogTags.SAVED))
        actions()
    }
}

/** A solid action of a dialog (the vendor's green OK, the blue-to-purple choices). */
@Composable
fun AmoDialogButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, start: Color = DialogGreen, end: Color = DialogGreen, tag: String? = null) {
    Box(
        modifier.heightIn(min = 48.dp).clip(RoundedCornerShape(12.dp)).background(Brush.horizontalGradient(listOf(start, end)))
            .let { if (tag != null) it.testTag(tag) else it }.clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(text, style = amoText(17, bold = true, color = Color.White, align = TextAlign.Center), modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) }
}

/** "ডেটা সফলভাবে সংরক্ষিত হয়েছে।" with OK at the right (screenshots 28, 32): the window around [AmoSavedCard]. */
@Composable
fun AmoSavedDialog(message: String, okLabel: String, onOk: () -> Unit) {
    Dialog(onDismissRequest = onOk) { AmoSavedCard(message, okLabel, onOk, Modifier.padding(horizontal = 8.dp)) }
}

@Composable
fun AmoSavedCard(message: String, okLabel: String, onOk: () -> Unit, modifier: Modifier = Modifier) =
    AmoDialogCard(message, "✓", DialogGreen, modifier) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { AmoDialogButton(okLabel, onOk, tag = AmoDialogTags.OK) }
    }

/** The force-sale reason question (screenshot 23): "ইন্টারনেট সমস্যা" or "লোকেশন চেঞ্জ"; [onReason] gets the contract code (`internet_problem` / `location_change`). */
@Composable
fun AmoForceReasonDialog(onReason: (String) -> Unit, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) { AmoForceReasonCard(onReason, Modifier.padding(horizontal = 8.dp)) }
}

@Composable
fun AmoForceReasonCard(onReason: (String) -> Unit, modifier: Modifier = Modifier) =
    AmoDialogCard(androidx.compose.ui.res.stringResource(R.string.amo_fs_question), "!", Color(0xFFC62828), modifier) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            AmoDialogButton(androidx.compose.ui.res.stringResource(R.string.amo_fs_internet), { onReason("internet_problem") }, Modifier.fillMaxWidth(), Color(0xFF0D4FA0), Color(0xFF4B1A9A), AmoDialogTags.REASON_INTERNET)
            AmoDialogButton(androidx.compose.ui.res.stringResource(R.string.amo_fs_location), { onReason("location_change") }, Modifier.fillMaxWidth(), Color(0xFF0D4FA0), Color(0xFF4B1A9A), AmoDialogTags.REASON_LOCATION)
        }
    }
