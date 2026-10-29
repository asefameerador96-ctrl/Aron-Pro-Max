package com.aktcl.aron.feature.tickets

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aktcl.aron.core.ui.LocalAppLanguage
import kotlinx.coroutines.launch

/**
 * Screenshots 17 and 18: "Report a new issue", the Issue type list (the 31 types in Annex A order), Title and
 * Description (both required), Upload Screenshot (gallery or camera) and Submit ticket. Saved to the outbox at once,
 * offline included; [onSubmitted] gets the new ticket's uuid. [initial] and [issueListOpen] preset the form (goldens).
 */
@Composable
fun CreateTicketScreen(
    deps: TicketDeps,
    frame: TicketFrame,
    onSubmitted: (String) -> Unit,
    modifier: Modifier = Modifier,
    initial: TicketDraft? = null,
    issueListOpen: Boolean = false,
) {
    val types = remember(deps) { IssueTypesState(deps.source) }
    LaunchedEffect(types) { types.load() }
    val draft = rememberSaveable(stateSaver = TicketSavers.Draft) { mutableStateOf(initial ?: TicketRules.newDraft(deps.newUuid(), TicketOrigin.MANUAL)) }
    var listOpen by rememberSaveable { mutableStateOf(issueListOpen) }
    val list = types.types
    val ready = if (list != null) remember(list) { TicketFormState(deps, list, draft) } else null
    // back without a save: the form is gone for good, so are its staged photos (a rotation keeps both; a save that is
    // running owns them)
    OnLeaveScreen { deps.discardLater(ready?.leave() ?: draft.value.takeUnless { it.queued }?.photoUuids.orEmpty()) }
    val p = ticketPalette()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val saved = stringResource(R.string.tickets_saved)
    val failed = stringResource(R.string.tickets_save_failed)
    frame(stringResource(R.string.tickets_title_create)) {
        TicketPage(modifier) {
            if (list == null || ready == null) {
                CircularProgressIndicator(Modifier.align(Alignment.Center), color = p.accentInk)
                return@TicketPage
            }
            val form: TicketFormState = ready
            val current by rememberUpdatedState(form)
            val gallery = deps.photos?.rememberGallery { picks -> scope.launch { current.addPicked(picks) } }
            LaunchedEffect(form.phase) {
                if (form.consumeSavedToast()) {
                    Toast.makeText(context, saved, Toast.LENGTH_SHORT).show()
                    onSubmitted(form.draft.uuid)
                } else if (form.phase == FormPhase.Failed) {
                    Toast.makeText(context, failed, Toast.LENGTH_LONG).show()
                }
            }
            val language = LocalAppLanguage.current
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding().padding(horizontal = 20.dp, vertical = 24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(stringResource(R.string.tickets_create_heading), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = p.ink)
                Text(stringResource(R.string.tickets_create_sub), style = MaterialTheme.typography.bodyLarge, color = p.inkMuted)
                if (list.isEmpty()) TicketNote(stringResource(R.string.tickets_no_issue_types), Modifier.testTag(TicketTags.NO_ISSUE_TYPES), color = p.warning)
                Spacer(Modifier.height(4.dp))
                IssueField(form.type?.label(language), enabled = list.isNotEmpty() && !form.draft.queued) { listOpen = true }
                TicketField(form.draft.title, form::setTitle, stringResource(R.string.tickets_title_hint), Modifier.testTag(TicketTags.TITLE), label = stringResource(R.string.tickets_title_label), enabled = !form.draft.queued)
                TicketField(
                    form.draft.description, form::setDescription, stringResource(R.string.tickets_description_hint), Modifier.testTag(TicketTags.DESCRIPTION),
                    label = stringResource(R.string.tickets_description_label), singleLine = false, minLines = 5, enabled = !form.draft.queued,
                )
                if (form.type?.asksAffectedUser == true) {
                    TicketField(
                        form.draft.affectedUsername, form::setAffected, stringResource(R.string.tickets_affected_hint), Modifier.testTag(TicketTags.AFFECTED),
                        label = stringResource(R.string.tickets_affected_label), enabled = !form.draft.queued,
                    )
                }
                if (form.maxPhotos > 0) {
                    UploadBox(
                        enabled = form.canAddPhoto,
                        onGallery = { gallery?.invoke(form.maxPhotos - form.draft.photoUuids.size) },
                        onCamera = { scope.launch { form.capture() } },
                    )
                    PhotoStrip(form.draft.photoUuids, deps.photos, { id -> scope.launch { form.removePhoto(id) } })
                }
                FormMessages(form)
                Spacer(Modifier.height(4.dp))
                TicketPrimaryButton(
                    stringResource(R.string.tickets_submit_manual), { scope.launch { form.submit() } }, Modifier.testTag(TicketTags.SUBMIT),
                    enabled = form.phase != FormPhase.Saving && !form.draft.queued,
                )
            }
            if (listOpen) IssueList(list, form.draft.issueTypeCode, onPick = { form.setIssueType(it); listOpen = false }, onDismiss = { listOpen = false })
        }
    }
}

/** The closed Issue type field: the chosen label or "Select Issue Type", and the chevron (screenshot 17). */
@Composable
private fun IssueField(shown: String?, enabled: Boolean, onOpen: () -> Unit) {
    val p = ticketPalette()
    val label = stringResource(R.string.tickets_issue_type)
    val hint = stringResource(R.string.tickets_issue_type_hint)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = p.ink)
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(p.field)
                .border(1.dp, p.fieldBorder, RoundedCornerShape(12.dp))
                .clickable(enabled = enabled, role = Role.DropdownList, onClickLabel = label, onClick = onOpen)
                .semantics { contentDescription = "$label, ${shown ?: hint}" }
                .testTag(TicketTags.ISSUE_FIELD)
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(shown ?: hint, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, color = if (shown != null) p.ink else p.inkFaint)
            Icon(TicketGlyphs.ExpandMore, contentDescription = null, tint = p.inkMuted)
        }
    }
}

/** Upload Screenshot (screenshot 17): one tap offers the gallery or the camera. */
@Composable
private fun UploadBox(enabled: Boolean, onGallery: () -> Unit, onCamera: () -> Unit) {
    val p = ticketPalette()
    var menu by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 120.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(p.card)
                .border(1.5.dp, if (enabled) p.accentInk.copy(alpha = 0.55f) else p.fieldBorder, RoundedCornerShape(14.dp))
                .clickable(enabled = enabled, role = Role.Button) { menu = true }
                .testTag(TicketTags.UPLOAD)
                .padding(horizontal = 20.dp, vertical = 24.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterHorizontally),
        ) {
            Box(Modifier.size(56.dp).clip(CircleShape).background(hueFill(p.card, p.accentInk, 0.18f)), contentAlignment = Alignment.Center) {
                Icon(TicketGlyphs.AddPhoto, contentDescription = null, tint = p.accentInk, modifier = Modifier.size(30.dp))
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.tickets_upload), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = p.accentInk, fontSize = 18.sp)
                Text(stringResource(R.string.tickets_upload_sub), style = MaterialTheme.typography.bodyMedium, color = p.inkMuted)
            }
        }
        PhotoChoiceMenu(menu, { menu = false }, onGallery, onCamera)
    }
}

/** The issue type list over the form (screenshot 18), drawn in the screen so goldens capture it; back closes it. */
@Composable
private fun androidx.compose.foundation.layout.BoxScope.IssueList(types: List<IssueType>, selected: String?, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    val p = ticketPalette()
    val language = LocalAppLanguage.current
    BackHandler(onBack = onDismiss)
    Box(Modifier.matchParentSize().background(p.scrim).clickable(onClickLabel = stringResource(R.string.tickets_close_sheet), onClick = onDismiss))
    LazyColumn(
        Modifier
            .align(Alignment.TopCenter)
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(p.sheet)
            .testTag(TicketTags.ISSUE_LIST),
        contentPadding = PaddingValues(vertical = 6.dp),
    ) {
        items(types, key = { it.code }) { t ->
            val on = t.code == selected
            Text(
                t.label(language),
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)
                    .background(if (on) p.sheetField else p.sheet)
                    .clickable(role = Role.Button) { onPick(t.code) }
                    .testTag(TicketTags.issue(t.code))
                    .padding(horizontal = 24.dp, vertical = 16.dp),
                style = MaterialTheme.typography.bodyLarge, color = p.ink,
            )
        }
    }
}
