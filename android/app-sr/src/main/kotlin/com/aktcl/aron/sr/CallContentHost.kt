package com.aktcl.aron.sr

import android.graphics.BitmapFactory
import android.widget.VideoView
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import com.aktcl.aron.core.database.entity.ContentItemEntity
import com.aktcl.aron.core.database.entity.SurveyEntity
import com.aktcl.aron.core.database.entity.SurveyQuestionEntity
import com.aktcl.aron.feature.outlet.AnswerType
import com.aktcl.aron.feature.outlet.ContentOutcome
import com.aktcl.aron.feature.outlet.ContentStepView
import com.aktcl.aron.feature.outlet.SurveyRowUi
import com.aktcl.aron.feature.outlet.SurveyStepView
import com.aktcl.aron.feature.outlet.SurveyTexts
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
    ContentStepView(stringResource(if (item.kind == "av") R.string.sr_content_av else R.string.sr_content_kv), stringResource(R.string.sr_content_close_x), close,
        pictureDescription = stringResource(if (item.kind == "av") R.string.sr_content_av_desc else R.string.sr_content_kv_desc)) {
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
    var attendanceWhy by remember { mutableStateOf<com.aktcl.aron.core.database.repo.AttendanceRefusal?>(null) }
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
    val numbers = remember(questions) {
        val ok = questions.filter { it.first.answerType in supported }
        val keys = ok.map { it.first.key }.toSet()
        // a sub-question whose parent is unsupported can never be shown: it takes no number
        SurveyNumbering.of(ok.filter { it.first.showIfKey == null || it.first.showIfKey in keys }.map { it.first.key to it.first.showIfKey })
    }
    val yes = stringResource(R.string.sr_yes); val no = stringResource(R.string.sr_no)
    val texts = SurveyTexts(
        bandTitle = stringResource(R.string.sr_survey_title), answerWord = stringResource(R.string.sr_survey_answer), pickHint = stringResource(R.string.sr_survey_pick), yes = yes, no = no,
        requiredInfo = stringResource(R.string.sr_survey_required_info), photo = stringResource(R.string.sr_survey_photo), photoRetake = stringResource(R.string.sr_survey_photo_retake),
        photoTaken = stringResource(R.string.sr_survey_photo_taken), skip = stringResource(R.string.sr_survey_skip), submit = stringResource(R.string.sr_survey_submit),
    )
    val rowsUi = shown.map { (q, label) ->
        val cur = answers[q.key]
        SurveyRowUi(
            q.key, numbers[q.key].orEmpty(), q.required, label, q.answerType == AnswerType.BOOL, cur?.bool?.let { if (it) yes else no },
            q.requiresPhoto, cur?.photoUuid != null,
        )
    }
    SurveyStepView(
        title = (if (bn) survey.titleBn ?: survey.titleEn else survey.titleEn ?: survey.titleBn), texts = texts, rows = rowsUi, submitEnabled = !saving && PosmSurvey.canSubmit(defs, answers),
        onPick = { key, v -> answers = answers + (key to (answers[key] ?: SurveyAnswer()).copy(bool = v)) },
        onPhoto = ::takePhoto, onSkip = { if (!saving) onDone() }, onSubmit = { confirm = true },
    )
    attendanceWhy?.let { why ->
        com.aktcl.aron.core.ui.aron.AronDialog(
            stringResource(R.string.sr_survey_title),
            stringResource(when (why) {
                com.aktcl.aron.core.database.repo.AttendanceRefusal.CHECKED_OUT -> com.aktcl.aron.feature.sale.R.string.review_problem_checked_out
                com.aktcl.aron.core.database.repo.AttendanceRefusal.EARLIER_CALL_OPEN -> com.aktcl.aron.feature.sale.R.string.review_problem_earlier_call_open
                else -> com.aktcl.aron.feature.sale.R.string.review_problem_not_checked_in
            }),
            stringResource(R.string.sr_ok), onConfirm = { attendanceWhy = null }, onDismiss = { attendanceWhy = null },
        )
    }
    if (confirm) {
        com.aktcl.aron.feature.outlet.SurveyConfirmDialog(
            stringResource(R.string.sr_survey_confirm), stringResource(R.string.sr_yes), stringResource(R.string.sr_no),
            onYes = {
                confirm = false; saving = true
                scope.launch {
                    // X4a fix round (R-W-23): a Save the route-day refused is worded by the call's host (nothing stored); any other
                    // failure goes on to the sale as before
                    val result = day.saveSurveyResult(visitUuid, survey, PosmSurvey.rows(visitUuid, survey.surveyId, survey.version, defs, answers))
                    // D-LEAD-PREREQ-04 ruling 5: answers the check-in gate refused are NOT stored and never reported as saved: the rep is told and stays on the survey
                    if (result.attendance != null) { attendanceWhy = result.attendance; saving = false }
                    else if (result.dayRefusal != null) onRefused(result.dayRefusal) else onDone()
                }
            },
            onNo = { confirm = false },
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
