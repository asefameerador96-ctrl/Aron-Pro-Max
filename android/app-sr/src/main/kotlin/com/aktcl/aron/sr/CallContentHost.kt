package com.aktcl.aron.sr

import android.graphics.BitmapFactory
import android.widget.VideoView
import androidx.compose.foundation.Image
import androidx.compose.ui.unit.dp
import com.aktcl.aron.core.ui.aron.barBrush
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.background
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
import com.aktcl.aron.core.ui.AronBanner
import com.aktcl.aron.core.ui.AronConfirmDialog
import com.aktcl.aron.core.ui.AronPrimaryButton
import com.aktcl.aron.core.ui.AronSecondaryButton
import com.aktcl.aron.core.ui.AronTokens
import com.aktcl.aron.core.ui.BannerKind
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
fun CallContentHost(
    day: SrDay, visitUuid: String, outletId: Long, sunlightKey: Boolean = false,
    /** tso-manual X4a fix round (R-W-23): the survey's Save was refused by the route-day; the call's host words it. */
    onRefused: (com.aktcl.aron.core.database.repo.DayRefusal) -> Unit = {},
    onDone: () -> Unit,
) {
    var items by remember(visitUuid) { mutableStateOf<List<ContentItemEntity>?>(null) }
    var index by remember(visitUuid) { mutableStateOf(0) }
    var survey by remember(visitUuid) { mutableStateOf<Pair<SurveyEntity, List<SurveyQuestionEntity>>?>(null) }
    var surveyChecked by remember(visitUuid) { mutableStateOf(false) }
    LaunchedEffect(visitUuid) { items = runCatching { day.pendingContent(visitUuid, outletId) }.getOrDefault(emptyList()) }
    val list = items ?: return
    if (index < list.size) {
        val item = list[index]
        ContentItemStep(day, visitUuid, item, item.sequence, onEnded = { index++ })
        return
    }
    LaunchedEffect(visitUuid, surveyChecked) {
        if (!surveyChecked) { survey = runCatching { day.pendingSurvey(visitUuid) }.getOrNull(); surveyChecked = true }
    }
    if (!surveyChecked) return
    val s = survey
    if (s == null) { LaunchedEffect(Unit) { onDone() }; return }
    SurveyStep(day, visitUuid, s.first, s.second, onRefused, onDone)
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
    var ended by remember(item.contentId) { mutableStateOf(false) }
    fun finish(outcome: ContentOutcome) {
        if (ended) return // completion, error and a double tap can all arrive: one log, one step forward
        ended = true
        scope.launch {
            day.logContentView(visitUuid, item, outcome, startedAt, (day.currentMs() - startedAt).coerceAtLeast(0), sequenceNo)
            onEnded()
        }
    }
    // SRM-B-05 (shot 35 left): a gradient title band ("AV" or "KV"), the item centred, a full-width gradient "× বন্ধ করুন" at the bottom
    val close = { finish(if (item.kind == "av") ContentOutcome.SKIPPED_USER else ContentOutcome.VIEWED) }
    Column(Modifier.fillMaxSize()) {
        ContentBand(stringResource(if (item.kind == "av") R.string.sr_content_av else R.string.sr_content_kv))
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = androidx.compose.ui.Alignment.Center) {
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
        }
        // the SR can always close an item; closing an AV early counts as skipped by the user.
        AronPrimaryButton(stringResource(R.string.sr_content_close_x), close, Modifier.fillMaxWidth().padding(AronTokens.Space.L))
    }
}

/** The red-to-purple title band of a content or survey step (shots 35): in the kit's bar brush, no status-bar inset (the frame's bar is above). */
@Composable
internal fun ContentBand(title: String) {
    val look = com.aktcl.aron.core.ui.LocalAronLook.current
    Box(
        Modifier.fillMaxWidth().background(look.barBrush()).heightIn(min = 48.dp).padding(horizontal = AronTokens.Space.L),
        contentAlignment = androidx.compose.ui.Alignment.Center,
    ) { Text(title, style = MaterialTheme.typography.titleMedium, color = look.onBar, modifier = Modifier.semantics { heading() }) }
}

private const val SEP = "\u0001"

/** Answers as strings "key SEP bool SEP photoUuid" (the survey screen sets only those two fields). */
private val AnswersSaver = androidx.compose.runtime.saveable.Saver<Map<String, SurveyAnswer>, ArrayList<String>>(
    save = { m -> ArrayList(m.map { (k, a) -> listOf(k, a.bool?.toString().orEmpty(), a.photoUuid.orEmpty()).joinToString(SEP) }) },
    restore = { l ->
        l.mapNotNull { row ->
            val p = row.split(SEP)
            if (p.size < 3) null else p[0] to SurveyAnswer(bool = p[1].toBooleanStrictOrNull(), photoUuid = p[2].ifEmpty { null })
        }.toMap()
    },
)

@Composable
private fun SurveyStep(
    day: SrDay, visitUuid: String, survey: SurveyEntity, rows: List<SurveyQuestionEntity>,
    onRefused: (com.aktcl.aron.core.database.repo.DayRefusal) -> Unit, onDone: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val bn = androidx.compose.ui.platform.LocalConfiguration.current.locales[0].language == "bn"
    // This screen answers yes/no and photo questions. A required question of another type could never be answered and would
    // block the sale, so it is not shown and counts as optional.
    val supported = setOf(AnswerType.BOOL, AnswerType.PHOTO_ONLY)
    val questions = remember(rows) {
        rows.sortedBy { it.ordinal }.map {
            val type = runCatching { AnswerType.valueOf(it.answerType.uppercase()) }.getOrDefault(AnswerType.TEXT)
            val q = SurveyQuestion(
                it.questionId, type, it.questionKey ?: ("q" + it.questionId), (it.required ?: true) && type in supported,
                it.requiresPhoto || type == AnswerType.PHOTO_ONLY, it.showIfKey, it.showIfBool,
            )
            q to (if (bn) it.labelBn ?: it.labelEn else it.labelEn)
        }
    }
    val defs = questions.map { it.first }
    // Survives rotation, a language switch and process death, so a half-answered survey (and its photo uuid) is not lost.
    var answers by androidx.compose.runtime.saveable.rememberSaveable(visitUuid, stateSaver = AnswersSaver) { mutableStateOf<Map<String, SurveyAnswer>>(emptyMap()) }
    var confirm by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    val visible = PosmSurvey.visible(defs, answers).map { it.key }.toSet()
    fun takePhoto(key: String) {
        val uuid = com.aktcl.aron.core.common.ClientIds.newUuid()
        scope.launch {
            val shot = runCatching { day.photoPipeline.captureAndCompress(uuid) }.getOrNull() ?: return@launch
            answers = answers + (key to (answers[key] ?: SurveyAnswer()).copy(photoUuid = shot.photoUuid))
        }
    }
    val look = com.aktcl.aron.core.ui.LocalAronLook.current
    // SRM-B-06 (shot 35 right): the "সার্ভে" band, the survey's own name in red, the info card, numbered questions ("1*", "1.1*") with a
    // "উত্তরঃ" dropdown, the photo box under a sub-question, and "সাবমিট →" in a bar pinned to the bottom
    val shown = questions.filter { it.first.key in visible && it.first.answerType in supported }
    // numbered over the supported questions only (a hidden or unsupported one leaves no gap), in Latin digits as the manual shows (shot 35)
    val numbers = remember(questions) { SurveyNumbering.of(questions.filter { it.first.answerType in supported }.map { it.first.key to it.first.showIfKey }) }
    Column(Modifier.fillMaxSize()) {
        ContentBand(stringResource(R.string.sr_survey_title))
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(AronTokens.Space.L), verticalArrangement = Arrangement.spacedBy(AronTokens.Space.M)) {
            (if (bn) survey.titleBn ?: survey.titleEn else survey.titleEn ?: survey.titleBn)?.let { Text(it, style = MaterialTheme.typography.titleLarge, color = look.red) }
            // the reference's info card (X3): starred questions must be answered
            if (shown.any { it.first.required }) AronBanner(stringResource(R.string.sr_survey_required_info), kind = BannerKind.Info)
            shown.forEach { (q, label) ->
                val no = numbers[q.key].orEmpty()
                Text(
                    androidx.compose.ui.text.buildAnnotatedString {
                        append(no); if (q.required) withStyle(androidx.compose.ui.text.SpanStyle(color = look.red)) { append("*") }
                        append(" "); append(label)
                    },
                    style = MaterialTheme.typography.titleMedium,
                )
                val cur = answers[q.key]
                if (q.answerType == AnswerType.BOOL) {
                    var open by remember(q.key) { mutableStateOf(false) }
                    Box(Modifier.fillMaxWidth()) {
                        androidx.compose.foundation.layout.Row(
                            Modifier.fillMaxWidth().heightIn(min = 48.dp).border(1.dp, look.inkMuted, androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
                                .clickable(role = androidx.compose.ui.semantics.Role.DropdownList) { open = true }.padding(horizontal = AronTokens.Space.M),
                            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                        ) {
                            Text(
                                stringResource(R.string.sr_survey_answer) + " " + (cur?.bool?.let { stringResource(if (it) R.string.sr_yes else R.string.sr_no) } ?: stringResource(R.string.sr_survey_pick)),
                                style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f),
                            )
                            Text(stringResource(R.string.sr_glyph_dropdown), color = look.inkMuted)
                        }
                        androidx.compose.material3.DropdownMenu(open, { open = false }) {
                            listOf(true to R.string.sr_yes, false to R.string.sr_no).forEach { (v, res) ->
                                androidx.compose.material3.DropdownMenuItem(
                                    text = { Text(stringResource(res)) },
                                    onClick = { answers = answers + (q.key to (cur ?: SurveyAnswer()).copy(bool = v)); open = false },
                                )
                            }
                        }
                    }
                }
                if (q.requiresPhoto) {
                    val taken = cur?.photoUuid != null
                    AronSecondaryButton(stringResource(if (taken) R.string.sr_survey_photo_retake else R.string.sr_survey_photo), { takePhoto(q.key) }, Modifier.fillMaxWidth())
                    if (taken) Text(stringResource(R.string.sr_survey_photo_taken), style = MaterialTheme.typography.labelLarge)
                }
            }
            // The sale is never blocked: if the survey cannot be completed (no camera, no light) the SR can go on to the sale without saving it.
            AronSecondaryButton(stringResource(R.string.sr_survey_skip), { if (!saving) onDone() }, Modifier.fillMaxWidth())
        }
        AronPrimaryButton(stringResource(R.string.sr_survey_submit), { confirm = true }, Modifier.fillMaxWidth().padding(AronTokens.Space.L), enabled = !saving && PosmSurvey.canSubmit(defs, answers))
    }
    if (confirm) {
        AronConfirmDialog(
            stringResource(R.string.sr_survey_title), stringResource(R.string.sr_survey_confirm), stringResource(R.string.sr_yes), stringResource(R.string.sr_no),
            onConfirm = {
                confirm = false; saving = true
                scope.launch {
                    // X4a fix round (R-W-23): a Save the route-day refused is worded by the call's host (nothing stored); any other
                    // failure goes on to the sale as before
                    val why = day.saveSurveyRefusal(visitUuid, survey, PosmSurvey.rows(visitUuid, survey.surveyId, survey.version, defs, answers))
                    if (why != null) onRefused(why) else onDone()
                }
            },
            onDismiss = { confirm = false },
        )
    }
}

/** SRM-B-06: question numbers as the manual shows them: top-level 1, 2, ...; a sub-question (shown only for its parent) 1.1, 1.2. Pure, tested. */
internal object SurveyNumbering {
    /** [questions] are (key, showIfKey) in screen order; the result maps each key to its number. */
    fun of(questions: List<Pair<String, String?>>): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        var top = 0
        val subs = HashMap<String, Int>()
        for ((key, parent) in questions) {
            val parentNo = parent?.let { out[it] }
            if (parentNo == null) { top++; out[key] = top.toString() }
            else { val n = (subs[parent] ?: 0) + 1; subs[parent] = n; out[key] = "$parentNo.$n" }
        }
        return out
    }
}
