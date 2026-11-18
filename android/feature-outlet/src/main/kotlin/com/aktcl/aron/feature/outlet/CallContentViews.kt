package com.aktcl.aron.feature.outlet

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.aktcl.aron.core.ui.AronIcons
import com.aktcl.aron.core.ui.AronManualDialog
import com.aktcl.aron.core.ui.ManualDialogKind
import com.aktcl.aron.core.ui.aron.AronPrimaryButton
import com.aktcl.aron.core.ui.aron.AronSecondaryButton
import com.aktcl.aron.core.ui.AronTokens
import com.aktcl.aron.core.ui.LocalAronLook
import com.aktcl.aron.core.ui.aron.barBrush

/**
 * The look of the SR's call-content steps (SR manual 35), as stateless views: the SR host (`CallContentHost`, app-sr) reads Room and the files and gives these the
 * words and the figures; nothing here reads or writes data. P-SR1b: split out of the host so each state can be recorded as a golden.
 */
object CallContentTags {
    const val BAND = "content_band"
    const val CLOSE = "content_close"
    const val SUBMIT = "survey_submit"
    const val SKIP = "survey_skip"
    fun answer(key: String) = "survey_answer_$key"
    fun photo(key: String) = "survey_photo_$key"
}

/** The red-to-purple title band of a content or survey step (shot 35): the kit's bar brush, no status-bar inset (the frame's bar is above). */
@Composable
fun ContentBandView(title: String, modifier: Modifier = Modifier) {
    val look = LocalAronLook.current
    Box(
        modifier.fillMaxWidth().background(look.barBrush()).heightIn(min = 48.dp).padding(horizontal = AronTokens.Space.L).testTag(CallContentTags.BAND),
        contentAlignment = Alignment.Center,
    ) { Text(title, style = MaterialTheme.typography.titleMedium, color = look.onBar, modifier = Modifier.semantics { heading() }) }
}

/** An AV or KV step (shot 35 left): the band, the item ([picture]) centred on the grey page, and the full-width gradient close button at the bottom. */
@Composable
fun ContentStepView(title: String, closeLabel: String, onClose: () -> Unit, modifier: Modifier = Modifier, picture: @Composable BoxScope.() -> Unit) {
    Column(modifier.fillMaxSize()) {
        ContentBandView(title)
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center, content = picture)
        AronPrimaryButton(closeLabel, onClose, Modifier.fillMaxWidth().padding(AronTokens.Space.L).testTag(CallContentTags.CLOSE))
    }
}

/** One question row of the survey: its number ("1", "1.1"), whether it is starred, its words, and what it asks for. */
data class SurveyRowUi(
    val key: String, val number: String, val required: Boolean, val label: String,
    /** A yes/no dropdown; [answer] is the chosen word, null while nothing is chosen. */
    val yesNo: Boolean, val answer: String?,
    /** A photo is asked for; [photoTaken] once one is stored. */
    val photo: Boolean, val photoTaken: Boolean,
)

/** The words of the survey screen, read by the host from its own resources (English and Bangla). */
data class SurveyTexts(
    val bandTitle: String, val answerWord: String, val pickHint: String, val yes: String, val no: String,
    val requiredInfo: String, val photo: String, val photoRetake: String, val photoTaken: String, val skip: String, val submit: String,
)

/**
 * The survey step (shot 35 right): the "সার্ভে" band, the survey's own name in red and centred, the white info card (starred questions must be answered), the
 * numbered questions ("1*", "1.1*") each with "উত্তরঃ" and a purple-outlined dropdown, the photo box under a sub-question, the skip link, and "সাবমিট →" on the right of a
 * deep-purple bottom bar.
 */
@Composable
fun SurveyStepView(
    title: String?, texts: SurveyTexts, rows: List<SurveyRowUi>, submitEnabled: Boolean,
    onPick: (key: String, yes: Boolean) -> Unit, onPhoto: (key: String) -> Unit, onSkip: () -> Unit, onSubmit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val look = LocalAronLook.current
    Column(modifier.fillMaxSize()) {
        ContentBandView(texts.bandTitle)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(AronTokens.Space.L), verticalArrangement = Arrangement.spacedBy(AronTokens.Space.M)) {
            title?.let { Text(it, Modifier.fillMaxWidth(), style = MaterialTheme.typography.titleLarge, color = look.red, textAlign = TextAlign.Center) }
            // the reference's note box (shot 35): white, thin grey border, a dark info icon and the one line; it is the kit's info card, not a coloured banner
            if (rows.any { it.required }) Row(
                Modifier.fillMaxWidth().background(look.card, RoundedCornerShape(12.dp)).border(1.dp, look.infoBorder, RoundedCornerShape(12.dp)).padding(AronTokens.Space.L),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AronTokens.Space.S),
            ) {
                Icon(AronIcons.Info, contentDescription = null, tint = look.ink)
                Text(texts.requiredInfo, style = MaterialTheme.typography.bodyMedium, color = look.ink)
            }
            Column(Modifier.fillMaxWidth().background(look.card, RoundedCornerShape(12.dp)).padding(AronTokens.Space.L), verticalArrangement = Arrangement.spacedBy(AronTokens.Space.M)) {
                rows.forEach { r ->
                    Text(
                        buildAnnotatedString {
                            append(r.number); if (r.required) withStyle(SpanStyle(color = look.red)) { append("*") }
                            append(" "); append(r.label)
                        },
                        style = MaterialTheme.typography.titleMedium, color = look.ink,
                    )
                    if (r.yesNo) SurveyAnswerBox(r, texts, onPick)
                    if (r.photo) SurveyPhotoBox(r, texts) { onPhoto(r.key) }
                }
            }
            // the sale is never blocked: when the survey cannot be completed (no camera, no light) the SR goes on to the sale without saving it
            AronSecondaryButton(texts.skip, onSkip, Modifier.fillMaxWidth().testTag(CallContentTags.SKIP))
        }
        Row(Modifier.fillMaxWidth().background(look.panel).padding(AronTokens.Space.L), verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.weight(1f))
            AronPrimaryButton(texts.submit, onSubmit, Modifier.weight(1f).testTag(CallContentTags.SUBMIT), enabled = submitEnabled)
        }
    }
}

/** "উত্তরঃ" and the purple-outlined box holding the chosen word and the caret; a tap opens the two choices. */
@Composable
private fun SurveyAnswerBox(r: SurveyRowUi, texts: SurveyTexts, onPick: (String, Boolean) -> Unit) {
    val look = LocalAronLook.current
    var open by remember(r.key) { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AronTokens.Space.S)) {
        Text(texts.answerWord, style = MaterialTheme.typography.bodyLarge, color = look.ink)
        Box(Modifier.weight(1f)) {
            Row(
                Modifier.fillMaxWidth().heightIn(min = AronTokens.Touch.Min).border(1.5.dp, look.purple, RoundedCornerShape(4.dp))
                    .clickable(role = Role.DropdownList) { open = true }.padding(horizontal = AronTokens.Space.M).testTag(CallContentTags.answer(r.key))
                    // TalkBack names the QUESTION and the answer ("Is there POSM at your outlet?, Answer: Yes"), not just "Answer: Yes"
                    .semantics { contentDescription = r.label + ", " + texts.answerWord + " " + (r.answer ?: texts.pickHint) },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(r.answer ?: texts.pickHint, Modifier.weight(1f).clearAndSetSemantics { }, style = MaterialTheme.typography.bodyLarge, color = if (r.answer != null) look.ink else look.inkMuted)
                Icon(AronIcons.ExpandMore, contentDescription = null, tint = look.ink)
            }
            androidx.compose.material3.DropdownMenu(open, { open = false }) {
                listOf(true to texts.yes, false to texts.no).forEach { (v, word) ->
                    androidx.compose.material3.DropdownMenuItem(text = { Text(word) }, onClick = { onPick(r.key, v); open = false })
                }
            }
        }
    }
}

/** "উত্তরঃ" and a purple-outlined photo box: a tap takes (or retakes) the photo; the box says when one is saved. */
@Composable
private fun SurveyPhotoBox(r: SurveyRowUi, texts: SurveyTexts, onTake: () -> Unit) {
    val look = LocalAronLook.current
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(AronTokens.Space.S)) {
        Text(texts.answerWord, style = MaterialTheme.typography.bodyLarge, color = look.ink)
        Column(
            Modifier.weight(1f).heightIn(min = 96.dp).border(1.5.dp, look.purple, RoundedCornerShape(4.dp)).clickable(role = Role.Button, onClick = onTake)
                .padding(AronTokens.Space.M).testTag(CallContentTags.photo(r.key)),
            verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(if (r.photoTaken) texts.photoRetake else texts.photo, style = MaterialTheme.typography.bodyLarge, color = look.purple, textAlign = TextAlign.Center)
            if (r.photoTaken) Text(texts.photoTaken, style = MaterialTheme.typography.labelLarge, color = look.inkMuted, textAlign = TextAlign.Center)
        }
    }
}

/** "Are you sure you want to submit the survey?" (shot 35, right): the old SR's question box, the blue "i", the blue sentence, the green yes and the red no. */
@Composable
fun SurveyConfirmDialog(question: String, yes: String, no: String, onYes: () -> Unit, onNo: () -> Unit) {
    AronManualDialog(ManualDialogKind.Info, question, null, yes, onYes, no, onNo)
}
