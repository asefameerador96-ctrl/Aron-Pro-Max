package com.aktcl.aron.feature.tso.feedback

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.aktcl.aron.feature.tso.RetainedCounter
import com.aktcl.aron.feature.tso.rememberRetained

/**
 * My Feedback (F-TSO-018/029): the own list and the new-feedback form, moved out of TsoApp by a1-stubs with the behaviour
 * unchanged. Categories come from the login snapshot; a null [queue] hides New; a null [photos] hides the photo row;
 * [nowIso] is trusted `now` as RFC 3339. Every retained key carries [userKey].
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
