package com.aktcl.aron.feature.tso.feedback

import android.content.ActivityNotFoundException
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aktcl.aron.core.common.AppLanguage
import com.aktcl.aron.core.ui.AronIcons
import com.aktcl.aron.core.ui.AronTokens
import com.aktcl.aron.core.ui.LocalAppLanguage
import com.aktcl.aron.core.ui.LocalAronLook
import com.aktcl.aron.core.ui.localizedDigits
import com.aktcl.aron.feature.tso.R
import com.aktcl.aron.feature.tso.TsoBanner
import com.aktcl.aron.feature.tso.TsoBannerKind
import com.aktcl.aron.feature.tso.TsoButtonStyle
import com.aktcl.aron.feature.tso.TsoCard
import com.aktcl.aron.feature.tso.TsoEmpty
import com.aktcl.aron.feature.tso.TsoError
import com.aktcl.aron.feature.tso.TsoFrame
import com.aktcl.aron.feature.tso.TsoGlowField
import com.aktcl.aron.feature.tso.TsoGradientButton
import com.aktcl.aron.feature.tso.TsoLoading
import com.aktcl.aron.feature.tso.TsoNav
import com.aktcl.aron.feature.tso.TsoPillTone
import com.aktcl.aron.feature.tso.TsoStatusPill
import com.aktcl.aron.feature.tso.leave.backOnly
import kotlinx.coroutines.launch

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
    /** Browse Gallery (D-TSO-MAN-36; the camera is not offered on this form). */
    const val PHOTO = "tso_fb_photo"
    const val PHOTO_ADDED = "tso_fb_photo_added"
    const val PHOTO_REMOVE = "tso_fb_photo_remove"
    const val GALLERY_ERROR = "tso_fb_gallery_error"
    const val NO_CATEGORIES = "tso_fb_no_categories"
    const val ERRORS = "tso_fb_errors"
    fun category(code: String) = "tso_fb_category_$code"
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

/**
 * My feedback (D-TSO-MAN-14: an improvement, the TSO's own list): title, category and business date, the status pill and
 * "Not sent yet" for a row still in the outbox; "Add new feedback" opens the form ([onNew]). Cached first, one refresh;
 * offline the cache stays and says so. Home button.
 */
@Composable
fun FeedbackListScreen(
    state: FeedbackListState, categories: List<FeedbackCategory>, onBack: () -> Unit, onNew: (() -> Unit)?, modifier: Modifier = Modifier,
    nav: TsoNav = backOnly(onBack),
) {
    val ui by state.ui.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    LaunchedEffect(state) { state.load() }
    val bn = LocalAppLanguage.current == AppLanguage.BN
    val look = LocalAronLook.current
    TsoFrame(
        stringResource(R.string.tso_fb_list_title), nav, homeButton = true, modifier = modifier, onBack = onBack,
        bottomBar = if (onNew == null) null else { { TsoGradientButton(stringResource(R.string.tso_fb_add_new), onNew, Modifier.testTag(FeedbackTags.NEW), style = TsoButtonStyle.Lime) } },
    ) {
        when (val u = ui) {
            FeedbackListUi.Loading -> TsoLoading()
            FeedbackListUi.Empty -> TsoEmpty(stringResource(R.string.tso_fb_empty), Modifier.testTag(FeedbackTags.EMPTY), hint = stringResource(R.string.tso_fb_empty_hint))
            FeedbackListUi.Error -> TsoError(stringResource(R.string.tso_fb_error), stringResource(R.string.tso_retry), { scope.launch { state.load() } }, Modifier.testTag(FeedbackTags.ERROR))
            is FeedbackListUi.Content -> Column(Modifier.fillMaxSize()) {
                if (u.offlineCached) TsoBanner(stringResource(R.string.tso_offline_cached), Modifier.padding(horizontal = AronTokens.Space.Screen).testTag(FeedbackTags.OFFLINE), kind = TsoBannerKind.Offline)
                LazyColumn(
                    Modifier.weight(1f).fillMaxWidth().testTag(FeedbackTags.LIST), contentPadding = PaddingValues(AronTokens.Space.Screen),
                    verticalArrangement = Arrangement.spacedBy(AronTokens.Space.M),
                ) {
                    items(u.items, key = { it.feedbackUuid }) { f ->
                        val cat = categories.firstOrNull { it.code == f.categoryCode }?.label(bn) ?: f.categoryCode
                        TsoCard(Modifier.fillMaxWidth().testTag(FeedbackTags.card(f.feedbackUuid))) {
                            Column(Modifier.fillMaxWidth().padding(AronTokens.Space.L), verticalArrangement = Arrangement.spacedBy(AronTokens.Space.S)) {
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                                    Text(f.title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = look.ink)
                                    TsoStatusPill(statusText(f.status), Modifier.testTag(FeedbackTags.status(f.feedbackUuid)), tone = if (f.status == FeedbackStatus.NEW) TsoPillTone.Warning else TsoPillTone.Info)
                                }
                                Text(localizedDigits(cat + "  ·  " + f.shownDate), style = MaterialTheme.typography.bodyMedium, color = look.inkMuted)
                                if (!f.sent) TsoStatusPill(stringResource(R.string.tso_leave_not_sent), Modifier.testTag(FeedbackTags.unsent(f.feedbackUuid)), tone = TsoPillTone.Neutral)
                            }
                        }
                    }
                }
                u.asOf?.takeIf { it.isNotBlank() }?.let { Text(localizedDigits(stringResource(R.string.tso_as_of, it)), Modifier.padding(horizontal = AronTokens.Space.Screen), style = MaterialTheme.typography.bodySmall, color = look.inkMuted) }
            }
        }
    }
}

/** One category of the vertical list of screenshot 43: the whole row is the target, the chosen one filled and ticked. */
@Composable
private fun CategoryRow(label: String, selected: Boolean, enabled: Boolean, tag: String, onPick: () -> Unit) {
    val look = LocalAronLook.current
    Row(
        Modifier.fillMaxWidth().heightIn(min = 52.dp).background(if (selected) look.underline else look.card)
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onPick).testTag(tag).padding(horizontal = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal, color = look.ink)
        if (selected) Icon(AronIcons.Check, contentDescription = null, tint = look.ink)
    }
}

@Composable
private fun galleryErrorText(e: GalleryPick): String = stringResource(
    when (e) {
        GalleryPick.NO_SPACE -> R.string.tso_fb_gallery_no_space
        GalleryPick.TOO_LARGE -> R.string.tso_fb_gallery_too_large
        GalleryPick.NO_PICKER -> R.string.tso_fb_gallery_unavailable
        GalleryPick.UNREADABLE, GalleryPick.OK -> R.string.tso_fb_gallery_failed
    },
)

/**
 * Create New Feedback (screenshot 43, D-TSO-MAN-36): "Feedback Category" with the categories of the code list as a
 * vertical list (suggestion and feedback), the title field ("Enter title"), "Descriptions" ("Enter description"), Browse
 * Gallery (one picture from the phone's gallery through A2's import, at most `cfg.feedback.max_images`; no camera) and
 * Save. Save queues the feedback on the phone at once (offline included) and shows a toast. With [onSaved] the flow then
 * goes back (the app); without it the form says it was saved and Save turns into Done (the legacy wiring). Home button.
 */
@Composable
fun FeedbackFormScreen(
    state: FeedbackFormState, onBack: () -> Unit, modifier: Modifier = Modifier, photosEnabled: Boolean = false,
    nav: TsoNav = backOnly(onBack), onSaved: (() -> Unit)? = null,
) {
    val form by state.form.collectAsStateWithLifecycle()
    val result by state.result.collectAsStateWithLifecycle()
    val galleryError by state.galleryError.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val bn = LocalAppLanguage.current == AppLanguage.BN
    val look = LocalAronLook.current
    val queuedText = stringResource(R.string.tso_fb_toast_ok)
    val failedText = stringResource(R.string.tso_fb_toast_failed)
    LaunchedEffect(result) {
        when (state.consumeToast()) {
            FeedbackResult.Queued -> { Toast.makeText(context, queuedText, Toast.LENGTH_SHORT).show(); onSaved?.invoke() }
            FeedbackResult.Failed -> Toast.makeText(context, failedText, Toast.LENGTH_LONG).show()
            else -> Unit
        }
    }
    // the system photo picker needs the activity's result registry; without one (a preview, a test host) the button says the
    // phone has no picker instead of crashing
    val registry = androidx.activity.compose.LocalActivityResultRegistryOwner.current
    val pick = if (photosEnabled && registry != null) {
        rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> if (uri != null) scope.launch { state.addFromGallery(uri) } }
    } else null
    val queued = result is FeedbackResult.Queued
    val errors = (result as? FeedbackResult.Invalid)?.errors.orEmpty()
    TsoFrame(stringResource(R.string.tso_fb_create_title), nav, homeButton = true, modifier = modifier, onBack = onBack) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(AronTokens.Space.Screen), verticalArrangement = Arrangement.spacedBy(AronTokens.Space.M)) {
            if (result is FeedbackResult.NoCategories) TsoBanner(stringResource(R.string.tso_fb_no_categories), Modifier.testTag(FeedbackTags.NO_CATEGORIES), kind = TsoBannerKind.Warning)
            Text(stringResource(R.string.tso_fb_category_label), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = look.ink)
            val cats = state.categoryList
            if (cats.isNotEmpty()) Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).selectableGroup()) {
                cats.forEach { c -> CategoryRow(c.label(bn), c.code == form.categoryCode, !queued, FeedbackTags.category(c.code)) { state.edit { f -> f.copy(categoryCode = c.code) } } }
            }
            ErrorLine(errors, FeedbackError.CATEGORY)
            TsoGlowField(
                form.title, { v -> state.edit { f -> f.copy(title = v.take(MAX_TITLE * 2)) } }, Modifier.testTag(FeedbackTags.TITLE),
                placeholder = stringResource(R.string.tso_fb_enter_title), enabled = !queued,
                isError = FeedbackError.TITLE_EMPTY in errors || FeedbackError.TITLE_LONG in errors,
            )
            ErrorLine(errors, FeedbackError.TITLE_EMPTY); ErrorLine(errors, FeedbackError.TITLE_LONG)
            TsoGlowField(
                form.description, { v -> state.edit { f -> f.copy(description = v.take(MAX_DESCRIPTION * 2)) } }, Modifier.testTag(FeedbackTags.DESCRIPTION),
                label = stringResource(R.string.tso_fb_descriptions), placeholder = stringResource(R.string.tso_fb_enter_description),
                enabled = !queued, singleLine = false, minLines = 4,
                isError = FeedbackError.DESCRIPTION_EMPTY in errors || FeedbackError.DESCRIPTION_LONG in errors,
            )
            ErrorLine(errors, FeedbackError.DESCRIPTION_EMPTY); ErrorLine(errors, FeedbackError.DESCRIPTION_LONG)
            if (photosEnabled && state.maxImages > 0 && !queued) {
                Column(Modifier.fillMaxWidth().padding(top = AronTokens.Space.M), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(AronTokens.Space.S)) {
                    TsoGradientButton(
                        stringResource(R.string.tso_fb_browse_gallery),
                        {
                            if (pick == null) state.galleryUnavailable()
                            else try { pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) } catch (_: ActivityNotFoundException) { state.galleryUnavailable() }
                        },
                        Modifier.fillMaxWidth(0.62f).testTag(FeedbackTags.PHOTO), style = TsoButtonStyle.Blue,
                        icon = com.aktcl.aron.core.ui.AronIcons.Image, // screenshot 43: the picture glyph (R-W-12 (3))
                    )
                    if (form.photoUuid != null) Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.tso_fb_picture_added), Modifier.testTag(FeedbackTags.PHOTO_ADDED), style = MaterialTheme.typography.bodyMedium, color = look.ink)
                        TextButton({ scope.launch { state.removePhoto() } }, Modifier.heightIn(min = 48.dp).testTag(FeedbackTags.PHOTO_REMOVE), colors = ButtonDefaults.textButtonColors(contentColor = look.purple)) {
                            Text(stringResource(R.string.tso_fb_remove_picture))
                        }
                    }
                    galleryError?.let { Text(galleryErrorText(it), Modifier.testTag(FeedbackTags.GALLERY_ERROR), style = MaterialTheme.typography.bodySmall, color = look.red) }
                }
            }
            Column(Modifier.fillMaxWidth().padding(top = AronTokens.Space.M), horizontalAlignment = Alignment.CenterHorizontally) {
                TsoGradientButton(
                    stringResource(if (queued && onSaved == null) R.string.tso_leave_done else R.string.tso_fb_send),
                    { if (queued) onBack() else scope.launch { state.submit() } },
                    Modifier.fillMaxWidth(0.62f).testTag(FeedbackTags.SEND), style = TsoButtonStyle.Lime,
                    enabled = result !is FeedbackResult.NoCategories && !(queued && onSaved != null),
                )
            }
            // under the button, so Save does not move when the form says it was saved (legacy wiring only)
            if (queued && onSaved == null) TsoBanner(stringResource(R.string.tso_fb_queued), Modifier.testTag(FeedbackTags.QUEUED), kind = TsoBannerKind.Success)
        }
    }
}

@Composable
private fun ErrorLine(errors: List<FeedbackError>, e: FeedbackError) {
    if (e !in errors) return
    Text(
        stringResource(
            when (e) {
                FeedbackError.CATEGORY -> R.string.tso_fb_err_category
                FeedbackError.TITLE_EMPTY -> R.string.tso_fb_err_title_empty
                FeedbackError.TITLE_LONG -> R.string.tso_fb_err_title_long
                FeedbackError.DESCRIPTION_EMPTY -> R.string.tso_fb_err_description_empty
                FeedbackError.DESCRIPTION_LONG -> R.string.tso_fb_err_description_long
            },
        ),
        Modifier.testTag(FeedbackTags.ERRORS), style = MaterialTheme.typography.bodySmall, color = LocalAronLook.current.red,
    )
}
