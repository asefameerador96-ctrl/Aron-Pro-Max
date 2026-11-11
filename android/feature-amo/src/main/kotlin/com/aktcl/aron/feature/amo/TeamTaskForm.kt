package com.aktcl.aron.feature.amo

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.aktcl.aron.core.ui.BannerKind
import com.aktcl.aron.core.ui.LocalAronLook
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/** What the info card of the assign form shows for the chosen outlet (screenshot 38); [srPhone] is in clear for the AMO's own zone. */
data class TaskOutletInfo(val name: String, val owner: String?, val srName: String?, val srPhone: String?)

object TaskFormTags { const val ROUTE = "task_form_route"; const val OUTLET = "task_form_outlet"; const val TYPE = "task_form_type"; const val DATE = "task_form_date"; const val TEXT = "task_form_text"; const val LETTER = "task_form_letter_" }

private val longDate = DateTimeFormatter.ofPattern("MMMM d, yyyy", Locale.ENGLISH)

/** "November 3, 2025": the date field of the form prints the vendor's English long date in both languages. */
fun formDateText(iso: String): String = runCatching { LocalDate.parse(iso).format(longDate) }.getOrDefault(iso)

/** The alphabet bar (screenshot 38): a dark rounded panel of letters; the selected one is a green pill. "All" is the empty choice. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AlphabetBar(letters: List<String>, selected: String?, onSelect: (String?) -> Unit, modifier: Modifier = Modifier) {
    val look = LocalAronLook.current
    FlowRow(modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color(0xFF4F4F4F)).padding(8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        (listOf<String?>(null) + letters).forEach { l ->
            val on = l == selected
            Box(
                Modifier.heightIn(min = 40.dp).clip(RoundedCornerShape(20.dp)).background(if (on) look.goEnd else Color.Transparent).clickable(role = androidx.compose.ui.semantics.Role.Button) { onSelect(l) }
                    .padding(horizontal = 12.dp).testTag(TaskFormTags.LETTER + (l ?: "all")),
                contentAlignment = Alignment.Center,
            ) { Text(l ?: stringResource(R.string.amo_team_task_all), style = amoText(16, bold = on, color = if (on) Color.Black else Color.White)) }
        }
    }
}

/** A date field that opens the calendar; dates before [today] cannot be chosen. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AmoDateField(iso: String, today: String, onPick: (String) -> Unit, modifier: Modifier = Modifier) {
    val look = LocalAronLook.current
    var open by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(8.dp)
    Row(
        modifier.fillMaxWidth().clip(shape).background(look.card).border(BorderStroke(2.dp, look.purple), shape).clickable(role = androidx.compose.ui.semantics.Role.Button) { open = true }
            .heightIn(min = 48.dp).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        TeamIcon(R.drawable.amo_team_ic_calendar, tint = look.ink, size = 22.dp)
        Text(formDateText(iso), style = amoText(16))
    }
    if (open) {
        val min = LocalDate.parse(today).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val state = rememberDatePickerState(
            initialSelectedDateMillis = LocalDate.parse(iso).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
            selectableDates = object : SelectableDates { override fun isSelectableDate(utcTimeMillis: Long) = utcTimeMillis >= min },
        )
        DatePickerDialog(
            onDismissRequest = { open = false },
            confirmButton = { TextButton(onClick = { state.selectedDateMillis?.let { onPick(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate().toString()) }; open = false }) { Text(stringResource(R.string.amo_team_ok)) } },
            dismissButton = { TextButton(onClick = { open = false }) { Text(stringResource(R.string.amo_team_cancel)) } },
        ) { DatePicker(state) }
    }
}

/**
 * Assign a task (screenshot 38): route and outlet drop-downs, the alphabet bar, the outlet info card (name, owner, SR name and mobile in
 * clear), the type from the server list, a real date picker (default today, never in the past), the description (required, at most 500) and
 * "টাস্ক বরাদ্দ করুন".
 */
@Composable
fun TaskFormPageContent(
    routes: List<Pair<String, String>>, routeText: String?, onRoute: (String) -> Unit,
    outlets: List<Pair<String, String>>, outletText: String?, onOutlet: (String) -> Unit, letters: List<String>, letter: String?, onLetter: (String?) -> Unit,
    info: TaskOutletInfo?, types: List<TaskType>, bangla: Boolean, typeCode: String?, onType: (String) -> Unit,
    due: String, today: String, onDue: (String) -> Unit, description: String, onDescription: (String) -> Unit,
    errors: Set<AssignError>, busy: Boolean, noSr: Boolean, onSave: () -> Unit, modifier: Modifier = Modifier,
) {
    val look = LocalAronLook.current
    Column(modifier.fillMaxSize()) {
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            AmoDropdown(routeText ?: stringResource(R.string.amo_team_task_select_route), routes, onRoute, Modifier.testTag(TaskFormTags.ROUTE))
            if (AssignError.ROUTE_REQUIRED in errors) AmoBanner(stringResource(R.string.amo_team_task_route_required), kind = BannerKind.Error)
            AmoDropdown(outletText ?: stringResource(R.string.amo_team_task_select_outlet), outlets, onOutlet, Modifier.testTag(TaskFormTags.OUTLET), enabled = routeText != null)
            if (AssignError.OUTLET_REQUIRED in errors) AmoBanner(stringResource(R.string.amo_team_task_outlet_required), kind = BannerKind.Error)
            if (letters.isNotEmpty()) AlphabetBar(letters, letter, onLetter)
            if (info != null) {
                val shape = RoundedCornerShape(12.dp)
                Row(Modifier.fillMaxWidth().shadow(2.dp, shape).clip(shape).background(look.card).padding(14.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    TeamIcon(R.drawable.amo_team_ic_store, tint = look.purple, size = 44.dp)
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        InfoLine(stringResource(R.string.amo_team_task_outlet_name), info.name); InfoLine(stringResource(R.string.amo_team_task_owner), info.owner.orEmpty())
                        InfoLine(stringResource(R.string.amo_team_task_sr_name), info.srName.orEmpty()); InfoLine(stringResource(R.string.amo_team_task_sr_mobile), info.srPhone.orEmpty())
                    }
                }
                if (noSr) AmoBanner(stringResource(R.string.amo_team_task_no_sr), kind = BannerKind.Warning)
            }
            AmoDropdown(types.firstOrNull { it.code == typeCode }?.let { if (bangla) it.labelBn ?: it.labelEn else it.labelEn } ?: stringResource(R.string.amo_team_task_select_type), types.map { it.code to (if (bangla) it.labelBn ?: it.labelEn else it.labelEn) }, onType, Modifier.testTag(TaskFormTags.TYPE))
            if (AssignError.TYPE_REQUIRED in errors) AmoBanner(stringResource(R.string.amo_task_type_required), kind = BannerKind.Error)
            AmoDateField(due, today, onDue, Modifier.testTag(TaskFormTags.DATE))
            if (AssignError.DATE_INVALID in errors) AmoBanner(stringResource(R.string.amo_task_date_invalid), kind = BannerKind.Error)
            AmoField(description, { onDescription(it.take(TaskDelegation.MAX_FORM_DESCRIPTION)) }, stringResource(R.string.amo_team_task_description), error = AssignError.TEXT_TOO_LONG in errors || AssignError.DESCRIPTION_REQUIRED in errors, singleLine = false, modifier = Modifier.testTag(TaskFormTags.TEXT))
            if (AssignError.DESCRIPTION_REQUIRED in errors) AmoBanner(stringResource(R.string.amo_team_task_description_required), kind = BannerKind.Error)
        }
        Column(Modifier.fillMaxWidth().padding(12.dp)) { AmoPrimary(stringResource(R.string.amo_team_task_assign_btn), onSave, Modifier.fillMaxWidth().testTag(TaskTags.FORM_SAVE), enabled = !busy, busy = busy) }
    }
}

@Composable
private fun InfoLine(label: String, value: String) {
    val look = LocalAronLook.current
    Column { Text(label, style = amoText(14, bold = true)); if (value.isNotEmpty()) Text(value, style = amoText(14, color = look.inkMuted)) }
}
