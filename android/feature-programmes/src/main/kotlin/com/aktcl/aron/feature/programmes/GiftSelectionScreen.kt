package com.aktcl.aron.feature.programmes

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aktcl.aron.core.ui.LocalAronLook
import com.aktcl.aron.core.ui.aron.AronSolidButton
import com.aktcl.aron.core.ui.aron.LetterFilterBar
import com.aktcl.aron.core.ui.aron.RetailerPicker
import com.aktcl.aron.rules.Formats
import com.aktcl.aron.rules.UiLocale
import kotlinx.coroutines.launch

object GiftTags {
    const val PICKER = "gift_picker"
    const val DETAIL = "gift_detail"
    const val SUBMIT = "gift_submit"
    const val LOCKED = "gift_locked"
    const val ON_PHONE = "gift_on_phone"
    const val REFUSED = "gift_refused"
    const val EMPTY = "gift_empty"
    const val NO_GIFTS = "gift_no_gifts"
    const val FAILED = "gift_failed"
    const val SAVED_OK = "gift_saved_ok"
    fun card(giftId: Long) = "gift_card_$giftId"
}

/**
 * Astha gift selection (manual 52): the retailer, its details, the gifts for its tier, Submit. The server holds the choice; this phone writes a
 * `gift_choice` record (the later choice wins until the hand-over photo locks it). A locked outlet shows its gift and no Submit. [onSubmit]
 * returns false when nothing was saved. "Saved" means saved on this phone and queued; a refusal by the server comes back through the refused-records screen.
 */
@Composable
fun GiftSelectionScreen(
    shops: List<ShopInfo>, data: GiftChoiceData?, quarter: String, local: List<LocalGiftChoice>, locale: UiLocale,
    onSubmit: suspend (outletId: Long, giftId: Long) -> SaveOutcome, modifier: Modifier = Modifier, initialOutlet: Long? = null, initialGift: Long? = null,
) {
    val look = LocalAronLook.current
    var outletId by rememberSaveable { mutableStateOf(initialOutlet) }
    var letter by rememberSaveable { mutableStateOf("") }
    var picked by rememberSaveable(outletId) { mutableStateOf(initialGift) }
    var saved by rememberSaveable { mutableStateOf(false) }
    var failed by rememberSaveable { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    Column(modifier.fillMaxSize().background(look.page).verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (data == null || shops.isEmpty()) {
            Box(Modifier.fillMaxWidth().padding(vertical = 80.dp).testTag(GiftTags.EMPTY), contentAlignment = Alignment.Center) {
                Text(stringResource(if (data == null) R.string.pg_no_programme else R.string.pg_no_shops), style = TextStyle(fontSize = 18.sp, color = look.ink, textAlign = TextAlign.Center))
            }
            return@Column
        }
        val all = stringResource(R.string.pg_all)
        val letters = listOf(all) + shops.map { it.chip }.filter { it.isNotBlank() }.distinct().sorted()
        val shownLetter = letter.ifEmpty { all }
        val shown = if (shownLetter == all) shops else shops.filter { it.chip == shownLetter }
        val chosen = shops.firstOrNull { it.outletId == outletId }
        RetailerPicker(chosen, shown, { it.nameLine }, stringResource(R.string.pg_loyalty_pick_outlet), { outletId = it.outletId }, Modifier.fillMaxWidth().testTag(GiftTags.PICKER))
        LetterFilterBar(letters, shownLetter, { letter = if (it == all) "" else it })
        if (chosen == null) return@Column
        Column(Modifier.fillMaxWidth().testTag(GiftTags.DETAIL).clip(RoundedCornerShape(12.dp)).background(look.card).padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Detail(R.string.pg_shop_code, Formats.digits(chosen.code, locale))
            if (chosen.routeName.isNotBlank()) Detail(R.string.pg_gift_route, chosen.routeName)
            Detail(R.string.pg_shop_sub, chosen.subChannel ?: "—")
            if (chosen.clusterName.isNotBlank()) Detail(R.string.pg_gift_cluster, chosen.clusterName)
        }
        val state = data.stateOf(chosen.outletId, quarter, local)
        val offered = data.offeredTo(chosen.tierCode)
        fun nameOf(id: Long) = data.gifts.firstOrNull { it.id == id }?.name(locale) ?: "#$id"
        when (state) {
            is GiftState.Locked -> Text(stringResource(R.string.pg_gift_locked, nameOf(state.giftId)), Modifier.testTag(GiftTags.LOCKED), style = TextStyle(fontSize = 16.sp, color = look.ink, fontWeight = FontWeight.Medium))
            is GiftState.OnThisPhone -> if (state.refused) Text(stringResource(R.string.pg_gift_refused, nameOf(state.giftId)), Modifier.testTag(GiftTags.REFUSED), style = TextStyle(fontSize = 15.sp, color = look.red, fontWeight = FontWeight.Medium))
                else Text(stringResource(R.string.pg_gift_on_phone, nameOf(state.giftId)), Modifier.testTag(GiftTags.ON_PHONE), style = TextStyle(fontSize = 15.sp, color = look.purple, fontWeight = FontWeight.Medium))
            is GiftState.Chosen -> Text(stringResource(R.string.pg_gift_chosen, nameOf(state.giftId)), style = TextStyle(fontSize = 15.sp, color = look.purple, fontWeight = FontWeight.Medium))
            GiftState.None -> Unit
        }
        if (state is GiftState.Locked) return@Column
        if (offered.isEmpty()) { Text(stringResource(R.string.pg_gift_none_for_tier), Modifier.testTag(GiftTags.NO_GIFTS), style = TextStyle(fontSize = 16.sp, color = look.ink)); return@Column }
        Text(stringResource(R.string.pg_gift_pick), style = TextStyle(fontSize = 16.sp, color = look.ink, fontWeight = FontWeight.Bold))
        val cols = if (androidx.compose.ui.platform.LocalDensity.current.fontScale >= 1.5f) 1 else 2
        offered.chunked(cols).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { g ->
                    val on = picked == g.id
                    Box(
                        Modifier.weight(1f).testTag(GiftTags.card(g.id)).clip(RoundedCornerShape(12.dp)).background(look.card)
                            .then(if (on) Modifier.border(2.dp, look.purple, RoundedCornerShape(12.dp)) else Modifier)
                            .semantics { role = Role.RadioButton; selected = on }.clickable { picked = g.id }.padding(14.dp),
                        contentAlignment = Alignment.Center,
                    ) { Text(g.name(locale), style = TextStyle(fontSize = 16.sp, color = look.ink, textAlign = TextAlign.Center)) }
                }
                if (row.size < cols) Box(Modifier.weight((cols - row.size).toFloat()))
            }
        }
        AronSolidButton(stringResource(R.string.pg_gift_submit), {
            val g = picked
            if (g != null && !busy) {
                busy = true
                scope.launch {
                    try {
                        val out = onSubmit(chosen.outletId, g)
                        if (out == SaveOutcome.SAVED || out == SaveOutcome.ALREADY_SAVED) { saved = true; failed = null } else failed = out.name
                    } finally { busy = false }
                }
            }
        }, Modifier.fillMaxWidth().testTag(GiftTags.SUBMIT), enabled = picked != null)
        failed?.let { f ->
            Text(stringResource(when (f) { "CONFLICT" -> R.string.pg_gift_conflict; "DAY_SUBMITTED" -> R.string.pg_day_submitted; "CHECK_IN_FIRST" -> R.string.pg_check_in_first; else -> R.string.pg_gift_failed }), Modifier.testTag(GiftTags.FAILED), style = TextStyle(fontSize = 14.sp, color = look.red))
        }
        if (saved) {
            AlertDialog(
                onDismissRequest = { saved = false },
                title = { Text(stringResource(R.string.pg_gift_saved), style = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.Bold)) },
                confirmButton = { AronSolidButton(stringResource(R.string.pg_ok), { saved = false; picked = null }, Modifier.testTag(GiftTags.SAVED_OK)) },
            )
        }
    }
}

@Composable
private fun Detail(labelRes: Int, value: String) {
    val look = LocalAronLook.current
    Column(Modifier.fillMaxWidth()) {
        Text(stringResource(labelRes), style = TextStyle(fontSize = 13.sp, color = look.inkMuted))
        Text(value, style = TextStyle(fontSize = 16.sp, color = look.ink, fontWeight = FontWeight.Medium))
    }
}
