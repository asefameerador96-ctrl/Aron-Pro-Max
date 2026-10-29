package com.aktcl.aron.feature.tso.feedback

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import com.aktcl.aron.feature.tso.R
import com.aktcl.aron.feature.tso.RetainedCounter
import com.aktcl.aron.feature.tso.TsoFrame
import com.aktcl.aron.feature.tso.TsoLoading
import com.aktcl.aron.feature.tso.TsoNav
import com.aktcl.aron.feature.tso.TsoScreen
import com.aktcl.aron.feature.tso.rememberRetained

/**
 * My Feedback > Add new feedback as its own drawer destination (TsoScreen.FEEDBACK_NEW, D-TSO-MAN-36): the form of
 * screenshot 43 with one picture from the gallery ([photos]; null hides Browse Gallery). After Save a toast and back
 * through [nav]; the next visit starts a fresh form with a new uuid (the epoch moves on). A form left without saving keeps
 * its draft for this run of the app. Categories and the picture limit come from the bundle, so it works offline.
 */
@Composable
fun FeedbackNewEntry(source: FeedbackSource, queue: FeedbackQueue, photos: FeedbackPhotos?, userKey: String, nowIso: () -> String, nav: TsoNav) {
    val counter = rememberRetained("tso-$userKey-fb-new-epoch") { RetainedCounter() }
    val epoch = counter.n
    val setup = rememberRetained("tso-$userKey-fb-new-setup-$epoch") { FormSetup() }
    LaunchedEffect(epoch) {
        if (setup.categories == null) {
            setup.maxImages = runCatching { source.maxImages() }.getOrDefault(1)
            setup.categories = runCatching { source.categories() }.getOrDefault(emptyList())
        }
    }
    val cats = setup.categories
    if (cats == null) {
        TsoFrame(stringResource(R.string.tso_fb_create_title), nav, homeButton = true) { TsoLoading() }
        return
    }
    val form = rememberRetained("tso-$userKey-fb-new-form-$epoch") { FeedbackFormState(queue, cats, nowIso, photos, setup.maxImages) }
    FeedbackFormScreen(form, onBack = { nav.back() }, photosEnabled = photos != null, nav = nav, onSaved = { counter.n++; nav.back() })
}

/**
 * My Feedback > My feedback as its own drawer destination (TsoScreen.FEEDBACK_LIST): the TSO's own list; "Add new
 * feedback" opens [TsoScreen.FEEDBACK_NEW] on top of it, so back from the form returns here and the list reads again.
 */
@Composable
fun FeedbackListEntry(source: FeedbackSource, canWrite: Boolean, userKey: String, nav: TsoNav) {
    val cats = rememberRetained("tso-$userKey-fb-list-cats") { CategoryHolder() }
    LaunchedEffect(Unit) { if (cats.list == null) cats.list = runCatching { source.categories() }.getOrDefault(emptyList()) }
    val list = rememberRetained("tso-$userKey-fb-list") { FeedbackListState(source) }
    FeedbackListScreen(list, cats.list ?: emptyList(), onBack = { nav.back() }, onNew = if (canWrite) ({ nav.open(TsoScreen.FEEDBACK_NEW) }) else null, nav = nav)
}

/** The categories and the picture limit of one new form, read once per form. */
class FormSetup {
    private val state = androidx.compose.runtime.mutableStateOf<List<FeedbackCategory>?>(null)
    var categories: List<FeedbackCategory>?
        get() = state.value
        set(v) { state.value = v }
    var maxImages: Int = 1
}

/**
 * The former tile Home's My Feedback (the own list and the new-feedback form inside one destination), kept with its
 * behaviour for the legacy wiring and the shell tests that still compose it; the app's AreasHost opens [FeedbackListEntry]
 * and [FeedbackNewEntry]. Categories come from the login snapshot; a null [queue] hides New; a null [photos] hides the
 * picture row; [nowIso] is trusted `now` as RFC 3339. Every retained key carries [userKey].
 */
@Composable
fun FeedbackFlow(
    source: FeedbackSource, queue: FeedbackQueue?, photos: FeedbackPhotos?, userKey: String, nowIso: () -> String, onBack: () -> Unit,
) {
    var writing by rememberSaveable { mutableStateOf(false) }
    val counter = rememberRetained("tso-$userKey-fb-epoch") { RetainedCounter() }
    val epoch = counter.n
    val cats = rememberRetained("tso-$userKey-fb-cats-$epoch") { CategoryHolder() }
    LaunchedEffect(epoch) { if (cats.list == null) cats.list = runCatching { source.categories() }.getOrDefault(emptyList()) }
    val list = rememberRetained("tso-$userKey-fb-list-$epoch") { FeedbackListState(source) }
    BackHandler(enabled = writing) { writing = false; counter.n++ }
    val loaded = cats.list
    if (writing && queue != null && loaded != null) {
        val form = rememberRetained("tso-$userKey-fb-form-$epoch") { FeedbackFormState(queue, loaded, nowIso, photos) }
        FeedbackFormScreen(form, onBack = { writing = false; counter.n++ }, photosEnabled = photos != null)
    } else {
        FeedbackListScreen(list, loaded ?: emptyList(), onBack = onBack, onNew = if (queue != null && loaded != null) ({ writing = true }) else null)
    }
}
