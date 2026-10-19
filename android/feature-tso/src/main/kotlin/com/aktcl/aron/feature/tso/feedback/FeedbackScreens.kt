package com.aktcl.aron.feature.tso.feedback

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aktcl.aron.core.common.AppLanguage
import com.aktcl.aron.core.ui.AronTokens
import com.aktcl.aron.core.ui.LocalAppLanguage
import com.aktcl.aron.core.ui.localizedDigits
import com.aktcl.aron.feature.tso.R
import kotlinx.coroutines.launch
import com.aktcl.aron.core.ui.aron.AronCard
import com.aktcl.aron.core.ui.aron.AronListRow
import com.aktcl.aron.feature.tso.TsoButton
import com.aktcl.aron.core.ui.aron.AronTabs
import com.aktcl.aron.feature.tso.TsoAction
import com.aktcl.aron.feature.tso.TsoBanner
import com.aktcl.aron.feature.tso.TsoBannerKind
import com.aktcl.aron.feature.tso.TsoBottomBar
import com.aktcl.aron.feature.tso.TsoEmpty
import com.aktcl.aron.feature.tso.TsoError
import com.aktcl.aron.feature.tso.TsoFrame
import com.aktcl.aron.feature.tso.TsoSectionTitle
import com.aktcl.aron.feature.tso.TsoStepper

object FeedbackTags {
    const val LIST = "tso_fb_list"
    const val EMPTY = "tso_fb_empty"
    const val ERROR = "tso_fb_error"
    const val OFFLINE = "tso_fb_offline"
    const val NEW = "tso_fb_new"
    const val TITLE = "tso_fb_title"
    const val DESCRIPTION = "tso_fb_description"
    const val SEND = "tso_fb_send"
    const val QUEUED = "tso_fb_queued"
    const val PHOTO = "tso_fb_photo"
    const val NO_CATEGORIES = "tso_fb_no_categories"
    const val ERRORS = "tso_fb_errors"
    fun card(uuid: String) = "tso_fb_card_$uuid"
    fun unsent(uuid: String) = "tso_fb_unsent_$uuid"
    fun status(uuid: String) = "tso_fb_status_$uuid"
}

@Composable
private fun statusText(s: FeedbackStatus) = stringResource(
    when (s) {
        FeedbackStatus.NEW -> R.string.tso_fb_status_new
        FeedbackStatus.IN_PROGRESS -> R.string.tso_fb_status_in_progress
        FeedbackStatus.RESOLVED -> R.string.tso_fb_status_resolved
        FeedbackStatus.CLOSED -> R.string.tso_fb_status_closed
    },
)

/** F-TSO-029: the TSO's own feedback with title, category, date and status; "New feedback" opens the form. */
@Composable
fun FeedbackListScreen(state: FeedbackListState, categories: List<FeedbackCategory>, onBack: () -> Unit, onNew: (() -> Unit)?, modifier: Modifier = Modifier) {
    val ui by state.ui.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    LaunchedEffect(state) { state.load() }
    val bn = LocalAppLanguage.current == AppLanguage.BN
    TsoFrame(stringResource(R.string.tso_fb_title), onBack, modifier, tile = com.aktcl.aron.feature.home.RoleHomes.TsoTile.FEEDBACK, bottomBar = { if (onNew != null) TsoBottomBar(primary = TsoAction(stringResource(R.string.tso_fb_new), onNew), modifier = Modifier.testTag(FeedbackTags.NEW)) },
    ) {
        when (val u = ui) {
            FeedbackListUi.Loading -> com.aktcl.aron.feature.tso.TsoLoading()
            FeedbackListUi.Empty -> TsoEmpty(stringResource(R.string.tso_fb_empty), Modifier.testTag(FeedbackTags.EMPTY), hint = stringResource(R.string.tso_fb_empty_hint))
            FeedbackListUi.Error -> TsoError(stringResource(R.string.tso_fb_error), stringResource(R.string.tso_retry), { scope.launch { state.load() } }, Modifier.testTag(FeedbackTags.ERROR))
            is FeedbackListUi.Content -> Column(Modifier.fillMaxSize()) {
                if (u.offlineCached) TsoBanner(stringResource(R.string.tso_offline_cached), Modifier.testTag(FeedbackTags.OFFLINE), kind = TsoBannerKind.Offline)
                LazyColumn(
                    Modifier.weight(1f).fillMaxWidth().testTag(FeedbackTags.LIST), contentPadding = PaddingValues(AronTokens.Space.Screen),
                    verticalArrangement = Arrangement.spacedBy(AronTokens.Space.M),
                ) {
                    items(u.items, key = { it.feedbackUuid }) { f ->
                        val cat = categories.firstOrNull { it.code == f.categoryCode }?.label(bn) ?: f.categoryCode
                        AronCard(Modifier.fillMaxWidth().testTag(FeedbackTags.card(f.feedbackUuid))) {
                            Column(Modifier.fillMaxWidth().padding(AronTokens.Space.L), verticalArrangement = Arrangement.spacedBy(AronTokens.Space.S)) {
                                Text(f.title, style = MaterialTheme.typography.titleSmall)
                                Text(localizedDigits(cat + "  ·  " + f.createdAt.take(10)), style = MaterialTheme.typography.bodyMedium)
                                TsoBanner(statusText(f.status), Modifier.testTag(FeedbackTags.status(f.feedbackUuid)), kind = if (f.status == FeedbackStatus.NEW) TsoBannerKind.Warning else TsoBannerKind.Info)
                                if (!f.sent) TsoBanner(stringResource(R.string.tso_leave_not_sent), Modifier.testTag(FeedbackTags.unsent(f.feedbackUuid)), kind = TsoBannerKind.Offline)
                            }
                        }
                    }
                }
                u.asOf?.takeIf { it.isNotBlank() }?.let { Text(localizedDigits(stringResource(R.string.tso_as_of, it)), Modifier.padding(AronTokens.Space.Screen), style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
}

/** F-TSO-018: category, title, description and one compressed photo; Send saves to the outbox at once (toast), offline included. */
@Composable
fun FeedbackFormScreen(state: FeedbackFormState, onBack: () -> Unit, modifier: Modifier = Modifier, photosEnabled: Boolean = false) {
    val form by state.form.collectAsStateWithLifecycle()
    val result by state.result.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val bn = LocalAppLanguage.current == AppLanguage.BN
    val queuedText = stringResource(R.string.tso_fb_toast_ok)
    val failedText = stringResource(R.string.tso_fb_toast_failed)
    LaunchedEffect(result) {
        when (state.consumeToast()) {
            FeedbackResult.Queued -> Toast.makeText(context, queuedText, Toast.LENGTH_SHORT).show()
            FeedbackResult.Failed -> Toast.makeText(context, failedText, Toast.LENGTH_LONG).show()
            else -> Unit
        }
    }
    val queued = result is FeedbackResult.Queued
    TsoFrame(stringResource(R.string.tso_fb_new), onBack, modifier, tile = com.aktcl.aron.feature.home.RoleHomes.TsoTile.FEEDBACK, bottomBar = {
            TsoBottomBar(
                primary = if (queued) TsoAction(stringResource(R.string.tso_leave_done), onBack) else TsoAction(stringResource(R.string.tso_fb_send), { scope.launch { state.submit() } }, enabled = result !is FeedbackResult.NoCategories),
                modifier = Modifier.testTag(FeedbackTags.SEND),
            )
        },
    ) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(AronTokens.Space.Screen), verticalArrangement = Arrangement.spacedBy(AronTokens.Space.M)) {
            if (result is FeedbackResult.NoCategories) TsoBanner(stringResource(R.string.tso_fb_no_categories), Modifier.testTag(FeedbackTags.NO_CATEGORIES), kind = TsoBannerKind.Warning)
            if (queued) {
                TsoBanner(stringResource(R.string.tso_fb_queued), Modifier.testTag(FeedbackTags.QUEUED), kind = TsoBannerKind.Success)
                TsoButton(stringResource(R.string.tso_fb_another), { state.startNew() })
            }
            TsoSectionTitle(stringResource(R.string.tso_fb_category))
            val cats = state.categoryList
            if (cats.isNotEmpty()) AronTabs(cats.map { it.label(bn) }, cats.indexOfFirst { it.code == form.categoryCode }, { i -> state.edit { f -> f.copy(categoryCode = cats[i].code) } })
            TsoSectionTitle(stringResource(R.string.tso_fb_title_label))
            com.aktcl.aron.feature.tso.TsoTextField(form.title, { v -> state.edit { f -> f.copy(title = v.take(MAX_TITLE * 2)) } }, Modifier.fillMaxWidth().testTag(FeedbackTags.TITLE), singleLine = true, enabled = !queued, name = stringResource(R.string.tso_fb_title_label))
            TsoSectionTitle(stringResource(R.string.tso_fb_description_label))
            com.aktcl.aron.feature.tso.TsoTextField(form.description, { v -> state.edit { f -> f.copy(description = v.take(MAX_DESCRIPTION * 2)) } }, Modifier.fillMaxWidth().testTag(FeedbackTags.DESCRIPTION), minLines = 4, enabled = !queued, name = stringResource(R.string.tso_fb_description_label))
            if (photosEnabled && !queued) {
                if (form.photoUuid == null) TsoButton(stringResource(R.string.tso_fb_add_photo), { scope.launch { state.takePhoto() } }, Modifier.testTag(FeedbackTags.PHOTO))
                else {
                    Text(stringResource(R.string.tso_fb_photo_added), style = MaterialTheme.typography.bodyMedium)
                    TsoButton(stringResource(R.string.tso_fb_remove_photo), { scope.launch { state.removePhoto() } })
                }
            }
            (result as? FeedbackResult.Invalid)?.errors?.forEach { e ->
                TsoBanner(
                    stringResource(
                        when (e) {
                            FeedbackError.CATEGORY -> R.string.tso_fb_err_category
                            FeedbackError.TITLE_EMPTY -> R.string.tso_fb_err_title_empty
                            FeedbackError.TITLE_LONG -> R.string.tso_fb_err_title_long
                            FeedbackError.DESCRIPTION_EMPTY -> R.string.tso_fb_err_description_empty
                            FeedbackError.DESCRIPTION_LONG -> R.string.tso_fb_err_description_long
                        },
                    ),
                    Modifier.testTag(FeedbackTags.ERRORS), kind = TsoBannerKind.Error,
                )
            }
        }
    }
}
