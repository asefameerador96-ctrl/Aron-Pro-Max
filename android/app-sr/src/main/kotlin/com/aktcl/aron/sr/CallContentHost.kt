package com.aktcl.aron.sr

import android.graphics.BitmapFactory
import android.widget.VideoView
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import com.aktcl.aron.core.database.entity.ContentItemEntity
import com.aktcl.aron.core.database.entity.SurveyEntity
import com.aktcl.aron.core.database.entity.SurveyQuestionEntity
import com.aktcl.aron.core.ui.AronConfirmDialog
import com.aktcl.aron.core.ui.AronPrimaryButton
import com.aktcl.aron.core.ui.AronSecondaryButton
import com.aktcl.aron.core.ui.AronTokens
import com.aktcl.aron.feature.outlet.AnswerType
import com.aktcl.aron.feature.outlet.ContentOutcome
import com.aktcl.aron.feature.outlet.PosmSurvey
import com.aktcl.aron.feature.outlet.SurveyAnswer
import com.aktcl.aron.feature.outlet.SurveyQuestion
import kotlinx.coroutines.launch
import java.io.File

/**
 * The part of the call between "start the call" and the sale (F-SR-020/021): the outlet's AV, then KV, then the POSM survey.
 * Everything reads local data; a missing file is logged `skipped_missing` and skipped without blocking the sale.
 */
@Composable
fun CallContentHost(day: SrDay, visitUuid: String, outletId: Long, sunlightKey: Boolean = false, onDone: () -> Unit) {
    var items by remember(visitUuid) { mutableStateOf<List<ContentItemEntity>?>(null) }
    var index by remember(visitUuid) { mutableStateOf(0) }
    var survey by remember(visitUuid) { mutableStateOf<Pair<SurveyEntity, List<SurveyQuestionEntity>>?>(null) }
    var surveyChecked by remember(visitUuid) { mutableStateOf(false) }
    LaunchedEffect(visitUuid) { items = day.pendingContent(visitUuid, outletId) }
    val list = items ?: return
    if (index < list.size) {
        val item = list[index]
        ContentItemStep(day, visitUuid, item, index + 1, onEnded = { index++ })
        return
    }
    LaunchedEffect(visitUuid, surveyChecked) {
        if (!surveyChecked) { survey = day.pendingSurvey(visitUuid); surveyChecked = true }
    }
    if (!surveyChecked) return
    val s = survey
    if (s == null) { LaunchedEffect(Unit) { onDone() }; return }
    SurveyStep(day, visitUuid, s.first, s.second, onDone)
}

@Composable
private fun ContentItemStep(day: SrDay, visitUuid: String, item: ContentItemEntity, sequenceNo: Int, onEnded: () -> Unit) {
    val scope = rememberCoroutineScope()
    var file by remember(item.contentId) { mutableStateOf<File?>(null) }
    var loaded by remember(item.contentId) { mutableStateOf(false) }
    val startedAt = remember(item.contentId) { day.currentMs() }
    LaunchedEffect(item.contentId) {
        file = runCatching { day.contentFile?.invoke(item) }.getOrNull()
        loaded = true
        if (file == null) {
            day.logContentView(visitUuid, item, ContentOutcome.SKIPPED_MISSING, null, null, sequenceNo)
            onEnded()
        }
    }
    val f = file
    if (!loaded || f == null) return
    fun finish(outcome: ContentOutcome) {
        scope.launch {
            day.logContentView(visitUuid, item, outcome, startedAt, (day.currentMs() - startedAt).coerceAtLeast(0), sequenceNo)
            onEnded()
        }
    }
    Box(Modifier.fillMaxSize()) {
        if (item.kind == "av") {
            AndroidView(
                factory = { ctx ->
                    VideoView(ctx).apply {
                        setVideoPath(f.absolutePath)
                        setOnCompletionListener { finish(ContentOutcome.VIEWED) }
                        setOnErrorListener { _, _, _ -> finish(ContentOutcome.SKIPPED_MISSING); true }
                        start()
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            val bitmap = remember(f.absolutePath) { runCatching { BitmapFactory.decodeFile(f.absolutePath)?.asImageBitmap() }.getOrNull() }
            if (bitmap == null) {
                LaunchedEffect(f.absolutePath) { finish(ContentOutcome.SKIPPED_MISSING) }
            } else {
                Image(bitmap, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
            }
        }
        // "বন্ধ করুন": the SR can always close an item; closing an AV early counts as skipped by the user.
        AronSecondaryButton(
            stringResource(R.string.sr_content_close),
            { finish(if (item.kind == "av") ContentOutcome.SKIPPED_USER else ContentOutcome.VIEWED) },
            Modifier.padding(AronTokens.Space.L).align(androidx.compose.ui.Alignment.TopEnd),
        )
    }
}

@Composable
private fun SurveyStep(day: SrDay, visitUuid: String, survey: SurveyEntity, rows: List<SurveyQuestionEntity>, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    val bn = androidx.compose.ui.platform.LocalContext.current.resources.configuration.locales[0].language == "bn"
    val questions = remember(rows) {
        rows.sortedBy { it.ordinal }.map {
            SurveyQuestion(
                it.questionId, runCatching { AnswerType.valueOf(it.answerType.uppercase()) }.getOrDefault(AnswerType.TEXT),
                it.questionKey ?: ("q" + it.questionId), it.required ?: true, it.requiresPhoto || it.answerType == "photo_only", it.showIfKey, it.showIfBool,
            ) to (if (bn) it.labelBn ?: it.labelEn else it.labelEn)
        }
    }
    val defs = questions.map { it.first }
    var answers by remember(visitUuid) { mutableStateOf<Map<String, SurveyAnswer>>(emptyMap()) }
    var confirm by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    val visible = PosmSurvey.visible(defs, answers).map { it.key }.toSet()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(AronTokens.Space.L), verticalArrangement = Arrangement.spacedBy(AronTokens.Space.M)) {
        Text(stringResource(R.string.sr_survey_title), style = MaterialTheme.typography.headlineSmall)
        questions.filter { it.first.key in visible }.forEach { (q, label) ->
            Text(label, style = MaterialTheme.typography.titleMedium)
            when (q.answerType) {
                AnswerType.BOOL -> androidx.compose.foundation.layout.Row(horizontalArrangement = Arrangement.spacedBy(AronTokens.Space.M)) {
                    val cur = answers[q.key]?.bool
                    val yes = { answers = answers + (q.key to SurveyAnswer(bool = true)) }
                    val no = { answers = answers + (q.key to SurveyAnswer(bool = false)) }
                    if (cur == true) AronPrimaryButton(stringResource(R.string.sr_yes), yes, Modifier.weight(1f)) else AronSecondaryButton(stringResource(R.string.sr_yes), yes, Modifier.weight(1f))
                    if (cur == false) AronPrimaryButton(stringResource(R.string.sr_no), no, Modifier.weight(1f)) else AronSecondaryButton(stringResource(R.string.sr_no), no, Modifier.weight(1f))
                }
                AnswerType.PHOTO_ONLY -> {
                    val taken = answers[q.key]?.photoUuid != null
                    AronSecondaryButton(
                        stringResource(if (taken) R.string.sr_survey_photo_retake else R.string.sr_survey_photo),
                        {
                            val uuid = com.aktcl.aron.core.common.ClientIds.newUuid()
                            scope.launch { day.photoPipeline.captureAndCompress(uuid)?.let { answers = answers + (q.key to SurveyAnswer(photoUuid = uuid)) } }
                        },
                        Modifier.fillMaxWidth(),
                    )
                    if (taken) Text(stringResource(R.string.sr_survey_photo_taken), style = MaterialTheme.typography.labelLarge)
                }
                else -> Unit
            }
        }
        AronPrimaryButton(stringResource(R.string.sr_survey_submit), { confirm = true }, Modifier.fillMaxWidth(), enabled = !saving && PosmSurvey.canSubmit(defs, answers))
    }
    if (confirm) {
        AronConfirmDialog(
            stringResource(R.string.sr_survey_title), stringResource(R.string.sr_survey_confirm), stringResource(R.string.sr_yes), stringResource(R.string.sr_no),
            onConfirm = {
                confirm = false; saving = true
                scope.launch {
                    runCatching { day.saveSurvey(visitUuid, survey, PosmSurvey.rows(visitUuid, survey.surveyId, survey.version, defs, answers)) }
                    onDone()
                }
            },
            onDismiss = { confirm = false },
        )
    }
}
