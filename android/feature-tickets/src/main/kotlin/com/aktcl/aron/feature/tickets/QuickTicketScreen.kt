package com.aktcl.aron.feature.tickets

import android.widget.Toast
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aktcl.aron.core.ui.AronIcons
import com.aktcl.aron.core.ui.AronTokens
import com.aktcl.aron.core.ui.LocalAppLanguage
import kotlinx.coroutines.launch

/**
 * Screenshots 15 and 16: the 31 issue tiles ("Tickets · High" from the type's attrs), "Report a different issue" (opens
 * Create Ticket); a tile opens the sheet: the issue name, an optional description, Pick from Gallery / Capture photo and
 * Submit Ticket. The ticket is saved to the outbox at once (title = the issue's English label), offline included.
 * [initialSheet] opens the sheet with that draft (goldens, deep links).
 */
@Composable
fun QuickTicketScreen(
    deps: TicketDeps,
    frame: TicketFrame,
    onCreate: () -> Unit,
    onSubmitted: (String) -> Unit,
    modifier: Modifier = Modifier,
    initialSheet: TicketDraft? = null,
) {
    val types = remember(deps) { IssueTypesState(deps.source) }
    LaunchedEffect(types) { types.load() }
    val sheet = rememberSaveable(stateSaver = TicketSavers.OptionalDraft) { mutableStateOf(initialSheet) }
    val list = types.types
    val p = ticketPalette()
    val language = LocalAppLanguage.current
    // two tiles a row as screenshot 15; one a row from the kit's one-column font scale, so labels stay readable
    val large = LocalDensity.current.fontScale >= AronTokens.Size.OneColumnFontScale
    frame(stringResource(R.string.tickets_title_quick)) {
        TicketPage(modifier) {
            when {
                list == null -> CircularProgressIndicator(Modifier.align(Alignment.Center), color = p.accentInk)
                list.isEmpty() -> TicketNote(stringResource(R.string.tickets_no_issue_types), Modifier.padding(16.dp).testTag(TicketTags.NO_ISSUE_TYPES))
                else -> LazyVerticalGrid(GridCells.Fixed(if (large) 1 else 2), Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Column(Modifier.padding(bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Icon(TicketGlyphs.Bolt, contentDescription = null, tint = p.accentInk, modifier = Modifier.size(24.dp))
                                Text(stringResource(R.string.tickets_quick_heading), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = p.ink)
                            }
                            Text(stringResource(R.string.tickets_quick_sub), style = MaterialTheme.typography.bodyMedium, color = p.inkMuted)
                        }
                    }
                    items(list, key = { it.code }) { type ->
                        IssueTile(type, language, if (large) 4 else 2) { if (sheet.value == null) sheet.value = TicketRules.newDraft(deps.newUuid(), TicketOrigin.QUICK, type.code) }
                    }
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Column(Modifier.padding(top = 18.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                HorizontalDivider(Modifier.weight(1f), color = p.cardBorder)
                                Text(stringResource(R.string.tickets_doesnt_match), Modifier.padding(horizontal = 12.dp), style = MaterialTheme.typography.bodyMedium, color = p.inkFaint)
                                HorizontalDivider(Modifier.weight(1f), color = p.cardBorder)
                            }
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 52.dp)
                                    .clip(RoundedCornerShape(14.dp))
                                    .border(1.dp, p.fieldBorder, RoundedCornerShape(14.dp))
                                    .clickable(role = Role.Button, onClick = onCreate)
                                    .testTag(TicketTags.REPORT_DIFFERENT),
                                contentAlignment = Alignment.Center,
                            ) { Text(stringResource(R.string.tickets_report_different), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = p.ink) }
                        }
                    }
                }
            }
            if (list != null) QuickSheet(deps, list, sheet, onSubmitted)
        }
    }
}

@Composable
private fun IssueTile(type: IssueType, language: com.aktcl.aron.core.common.AppLanguage, labelLines: Int, onClick: () -> Unit) {
    val p = ticketPalette()
    val hue = TicketHues.of(type.icon)
    val fill = hueFill(p.card, hue, if (p.night) 0.10f else 0.08f)
    TicketSurface(
        Modifier.fillMaxWidth().heightIn(min = 128.dp).testTag(TicketTags.tile(type.code)),
        fill = fill, border = hueFill(p.cardBorder, hue, if (p.night) 0.18f else 0.20f), radius = 16, onClick = onClick,
    ) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(Modifier.size(40.dp).clip(CircleShape).background(hueFill(fill, hue, if (p.night) 0.22f else 0.16f)), contentAlignment = Alignment.Center) {
                Icon(TicketGlyphs.issue(type.icon), contentDescription = null, tint = if (p.night) hue else p.ink, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.height(8.dp))
            Text(type.label(language), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = p.ink, maxLines = labelLines, overflow = TextOverflow.Ellipsis)
            Text(tileCaption(type), style = MaterialTheme.typography.labelSmall, color = p.inkFaint, maxLines = 1)
        }
    }
}

/** "Tickets · High": the type's category and urgency (attrs; V0131 seeds tickets / high for all 31). */
@Composable
internal fun tileCaption(type: IssueType): String {
    val category = if (type.category == "tickets") stringResource(R.string.tickets_category_tickets) else type.category
    val urgency = when (type.urgency) {
        "high" -> stringResource(R.string.tickets_urgency_high)
        "medium" -> stringResource(R.string.tickets_urgency_medium)
        "low" -> stringResource(R.string.tickets_urgency_low)
        else -> type.urgency
    }
    return stringResource(R.string.tickets_line, category, urgency)
}

@Composable
private fun androidx.compose.foundation.layout.BoxScope.QuickSheet(deps: TicketDeps, types: List<IssueType>, sheet: MutableState<TicketDraft?>, onSubmitted: (String) -> Unit) {
    val d = sheet.value
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val saved = stringResource(R.string.tickets_saved)
    val failed = stringResource(R.string.tickets_save_failed)
    val form = d?.let { draft -> remember(draft.uuid, types) { TicketFormState(deps, types, { sheet.value ?: draft }, { sheet.value = it }) } }
    val current by rememberUpdatedState(form)
    val gallery = deps.photos?.rememberGallery { picks -> scope.launch { current?.addPicked(picks) } }
    LaunchedEffect(form?.phase) {
        val f = form ?: return@LaunchedEffect
        if (f.consumeSavedToast()) {
            Toast.makeText(context, saved, Toast.LENGTH_SHORT).show()
            val id = f.draft.uuid
            sheet.value = null
            onSubmitted(id)
        } else if (f.phase == FormPhase.Failed) {
            Toast.makeText(context, failed, Toast.LENGTH_LONG).show()
        }
    }
    val dismiss: () -> Unit = {
        val f = form
        sheet.value = null
        if (f != null) scope.launch { f.abandon() }
    }
    TicketSheet(open = form != null, onDismiss = dismiss, modifier = Modifier.testTag(TicketTags.SHEET)) {
        val f = form ?: return@TicketSheet
        val p = ticketPalette()
        val language = LocalAppLanguage.current
        val type = f.type
        val closeLabel = stringResource(R.string.tickets_close_sheet)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(type?.label(language).orEmpty(), Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = p.ink)
            Box(
                Modifier.size(48.dp).clip(CircleShape).clickable(role = Role.Button, onClickLabel = closeLabel, onClick = dismiss).semantics { contentDescription = closeLabel }.testTag(TicketTags.SHEET_CLOSE),
                contentAlignment = Alignment.Center,
            ) { Icon(AronIcons.Close, contentDescription = null, tint = p.inkMuted, modifier = Modifier.size(28.dp)) }
        }
        TicketField(
            f.draft.description, f::setDescription, stringResource(R.string.tickets_quick_desc_hint), Modifier.testTag(TicketTags.DESCRIPTION),
            singleLine = false, minLines = 3, fill = p.sheetField, border = p.sheetFieldBorder,
        )
        if (type?.asksAffectedUser == true) {
            TicketField(
                f.draft.affectedUsername, f::setAffected, stringResource(R.string.tickets_affected_hint), Modifier.testTag(TicketTags.AFFECTED),
                label = stringResource(R.string.tickets_affected_label), fill = p.sheetField, border = p.sheetFieldBorder,
            )
        }
        if (f.maxPhotos > 0) {
            Column {
                TicketActionRow(TicketGlyphs.Gallery, TicketHues.new, stringResource(R.string.tickets_pick_gallery), { gallery?.invoke(f.maxPhotos - f.draft.photoUuids.size) }, Modifier.testTag(TicketTags.GALLERY), enabled = f.canAddPhoto)
                TicketActionRow(AronIcons.Camera, TicketHues.resolved, stringResource(R.string.tickets_capture_photo), { scope.launch { f.capture() } }, Modifier.testTag(TicketTags.CAMERA), enabled = f.canAddPhoto)
            }
            PhotoStrip(f.draft.photoUuids, deps.photos, { id -> scope.launch { f.removePhoto(id) } })
        }
        FormMessages(f)
        TicketPrimaryButton(stringResource(R.string.tickets_submit_quick), { scope.launch { f.submit() } }, Modifier.testTag(TicketTags.SUBMIT), enabled = f.phase != FormPhase.Saving)
    }
}

/** The form's validation errors and photo failures, under the fields. */
@Composable
internal fun FormMessages(f: TicketFormState) {
    val p = ticketPalette()
    when (val ph = f.phase) {
        is FormPhase.Invalid -> Column(Modifier.testTag(TicketTags.ERRORS), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            ph.errors.forEach { e -> Text(formErrorText(e), style = MaterialTheme.typography.bodyMedium, color = p.danger) }
        }
        is FormPhase.PhotoFailed -> Text(stringResource(ph.messageRes ?: R.string.tickets_err_photo), style = MaterialTheme.typography.bodyMedium, color = p.danger)
        FormPhase.Failed -> Text(stringResource(R.string.tickets_save_failed), style = MaterialTheme.typography.bodyMedium, color = p.danger, textAlign = TextAlign.Start)
        else -> Unit
    }
}

@Composable
internal fun formErrorText(e: TicketFormError): String = stringResource(
    when (e) {
        TicketFormError.ISSUE_TYPE -> R.string.tickets_err_issue
        TicketFormError.TITLE_EMPTY -> R.string.tickets_err_title
        TicketFormError.TITLE_LONG -> R.string.tickets_err_title_long
        TicketFormError.DESCRIPTION_EMPTY -> R.string.tickets_err_description
        TicketFormError.DESCRIPTION_LONG -> R.string.tickets_err_description_long
        TicketFormError.AFFECTED_USER -> R.string.tickets_err_affected
        TicketFormError.TOO_MANY_PHOTOS -> R.string.tickets_err_photos
    },
)
