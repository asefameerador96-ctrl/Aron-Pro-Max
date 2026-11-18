package com.aktcl.aron.feature.programmes

import androidx.compose.foundation.background
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aktcl.aron.core.ui.LocalAronLook
import com.aktcl.aron.core.ui.aron.AronSolidButton
import com.aktcl.aron.core.ui.aron.AronTile
import com.aktcl.aron.core.ui.aron.LetterFilterBar
import com.aktcl.aron.core.ui.aron.RetailerPicker
import com.aktcl.aron.rules.Formats
import com.aktcl.aron.rules.UiLocale
import kotlinx.coroutines.launch
import com.aktcl.aron.core.ui.R as CoreR

object PhotoTags {
    const val MENU_ASTHA = "photo_menu_astha"
    const val MENU_CAMPAIGN = "photo_menu_campaign"
    const val PICKER = "photo_picker"
    const val NEED_GIFT = "photo_need_gift"
    const val CAPTURED = "photo_captured"
    const val SUBMIT = "photo_submit"
    const val CHECKING = "photo_checking"
    const val FAILED = "photo_failed"
    const val SAVED_OK = "photo_saved_ok"
    const val EMPTY = "photo_empty"
}

/** The photo capture menu (manual 56): Astha and Campaign. [onCampaign] null hides the campaign tile (no campaign programme). */
@Composable
fun PhotoMenuScreen(onAstha: () -> Unit, onCampaign: (() -> Unit)?, modifier: Modifier = Modifier) {
    val look = LocalAronLook.current
    Box(modifier.fillMaxSize().background(look.page).padding(12.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
            AronTile(stringResource(R.string.pg_photo_astha), painterResource(CoreR.drawable.aron_art_astha), onAstha, Modifier.weight(1f).testTag(PhotoTags.MENU_ASTHA), menu = true, labelColor = look.tileLabelOutlet)
            if (onCampaign != null) AronTile(stringResource(R.string.pg_photo_campaign), painterResource(CoreR.drawable.aron_art_loyalty_point), onCampaign, Modifier.weight(1f).testTag(PhotoTags.MENU_CAMPAIGN), menu = true, labelColor = look.tileLabelOutlet)
            else Box(Modifier.weight(1f))
        }
    }
}

/**
 * The Astha gift photo (manual 57): the outlet, the gift it chose, the camera block ([photoBlock], the shared GEO and photo capture with one retake,
 * drawn by the host), Submit. "Saved" means saved on this phone and queued: the photo uploads compressed, on Wi-Fi first. A photo that already exists
 * (the server's flag, a locked choice, or this phone's own record) shows "already captured" and no camera. [selected] is held by the host so it can
 * give each outlet its own capture. [checking] shows the geofence check while the fix is read.
 */
@Composable
fun GiftPhotoScreen(
    shops: List<ShopInfo>, data: GiftChoiceData?, quarter: String, local: List<LocalGiftPhoto>, locale: UiLocale, selected: Long?, onSelect: (Long) -> Unit,
    photoBlock: @Composable (outletId: Long) -> Unit, canSubmit: Boolean, checking: Boolean, onSubmit: suspend (outletId: Long, giftId: Long, assignmentId: Long) -> SaveOutcome,
    modifier: Modifier = Modifier,
) {
    val look = LocalAronLook.current
    var letter by rememberSaveable { mutableStateOf("") }
    var saved by rememberSaveable { mutableStateOf(false) }
    var failed by rememberSaveable { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    Column(modifier.fillMaxSize().background(look.page).verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (data == null || shops.isEmpty()) {
            Box(Modifier.fillMaxWidth().padding(vertical = 80.dp).testTag(PhotoTags.EMPTY), contentAlignment = Alignment.Center) {
                Text(stringResource(if (data == null) R.string.pg_no_programme else R.string.pg_no_shops), style = TextStyle(fontSize = 18.sp, color = look.ink, textAlign = TextAlign.Center))
            }
            return@Column
        }
        val all = stringResource(R.string.pg_all)
        val letters = listOf(all) + shops.map { it.chip }.filter { it.isNotBlank() }.distinct().sorted()
        val shownLetter = letter.ifEmpty { all }
        val shown = if (shownLetter == all) shops else shops.filter { it.chip == shownLetter }
        val chosen = shops.firstOrNull { it.outletId == selected }
        RetailerPicker(chosen, shown, { it.nameLine }, stringResource(R.string.pg_loyalty_pick_outlet), { onSelect(it.outletId); failed = null }, Modifier.fillMaxWidth().testTag(PhotoTags.PICKER))
        LetterFilterBar(letters, shownLetter, { letter = if (it == all) "" else it })
        if (chosen == null) return@Column
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(look.card).padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.pg_shop_code), style = TextStyle(fontSize = 13.sp, color = look.inkMuted))
            Text(Formats.digits(chosen.code, locale), style = TextStyle(fontSize = 16.sp, color = look.ink, fontWeight = FontWeight.Medium))
        }
        fun giftName(id: Long) = data.gifts.firstOrNull { it.id == id }?.name(locale) ?: "#$id"
        when (val st = GiftPhotoModel.statusOf(data, chosen.outletId, quarter, local)) {
            PhotoStatus.NoGift -> Text(stringResource(R.string.pg_photo_need_gift), Modifier.testTag(PhotoTags.NEED_GIFT), style = TextStyle(fontSize = 16.sp, color = look.ink))
            is PhotoStatus.Locked -> Text(stringResource(R.string.pg_photo_locked, giftName(st.giftId)), Modifier.testTag(PhotoTags.CAPTURED), style = TextStyle(fontSize = 16.sp, color = look.purple, fontWeight = FontWeight.Medium))
            is PhotoStatus.Captured -> Text(stringResource(if (st.onThisPhone) R.string.pg_photo_on_phone else R.string.pg_photo_already, giftName(st.giftId)), Modifier.testTag(PhotoTags.CAPTURED), style = TextStyle(fontSize = 16.sp, color = look.purple, fontWeight = FontWeight.Medium))
            is PhotoStatus.Ready -> {
                Text(stringResource(R.string.pg_gift_chosen, giftName(st.giftId)), style = TextStyle(fontSize = 15.sp, color = look.purple, fontWeight = FontWeight.Medium))
                photoBlock(chosen.outletId)
                if (checking) Text(stringResource(R.string.pg_photo_checking), Modifier.testTag(PhotoTags.CHECKING), style = TextStyle(fontSize = 14.sp, color = look.inkMuted))
                AronSolidButton(stringResource(R.string.pg_photo_submit), {
                    if (!busy) {
                        busy = true
                        scope.launch {
                            try {
                                val out = onSubmit(chosen.outletId, st.giftId, st.assignmentId)
                                if (out == SaveOutcome.SAVED || out == SaveOutcome.ALREADY_SAVED) { saved = true; failed = null } else failed = out.name
                            } finally { busy = false }
                        }
                    }
                }, Modifier.fillMaxWidth().testTag(PhotoTags.SUBMIT), enabled = canSubmit && !busy)
            }
        }
        failed?.let { f ->
            Text(stringResource(when (f) { "DAY_SUBMITTED" -> R.string.pg_day_submitted; "CHECK_IN_FIRST" -> R.string.pg_check_in_first; else -> R.string.pg_photo_failed }), Modifier.testTag(PhotoTags.FAILED), style = TextStyle(fontSize = 14.sp, color = look.red))
        }
        if (saved) {
            AlertDialog(
                onDismissRequest = { saved = false },
                title = { Text(stringResource(R.string.pg_photo_saved), style = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.Bold)) },
                confirmButton = { AronSolidButton(stringResource(R.string.pg_ok), { saved = false }, Modifier.testTag(PhotoTags.SAVED_OK)) },
            )
        }
    }
}

object CampaignTags {
    const val PICKER = "campaign_picker"
    const val EMPTY = "campaign_empty"
    const val SUBMIT = "campaign_submit"
    const val FAILED = "campaign_failed"
    const val SAVED_OK = "campaign_saved_ok"
    fun group(redemptionUuid: String, giftId: Long) = "campaign_group_${redemptionUuid}_$giftId"
}

/**
 * Campaign gift photo (manual 58, Gift Verify): the outlets with redeemed campaign gifts that still wait for photos, and for each redeemed gift one camera slot per
 * UNIT still without a photo ([CampaignGroup.openUnits]). The host draws each slot ([slot]: the shared GEO and photo capture with one retake). Submit of a gift is enabled only
 * when EVERY open slot of it has a complete capture ([canSubmit]), and writes one `gift_photo` record per unit (the host does the saving, [onSubmit]). Offline end to end; the photos upload
 * compressed, on Wi-Fi first.
 */
@Composable
fun CampaignPhotoScreen(
    shops: List<ShopInfo>, groups: List<CampaignGroup>, gifts: List<LoyaltyGift>, locale: UiLocale, selected: Long?, onSelect: (Long) -> Unit,
    slot: @Composable (group: CampaignGroup, unitNo: Int) -> Unit, canSubmit: (CampaignGroup) -> Boolean, onSubmit: suspend (CampaignGroup) -> SaveOutcome, modifier: Modifier = Modifier,
) {
    val look = LocalAronLook.current
    var saved by rememberSaveable { mutableStateOf(false) }
    var failed by rememberSaveable { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val withGroups = shops.filter { s -> groups.any { it.outletId == s.outletId } }
    Column(modifier.fillMaxSize().background(look.page).verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (withGroups.isEmpty()) {
            Box(Modifier.fillMaxWidth().padding(vertical = 80.dp).testTag(CampaignTags.EMPTY), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.pg_campaign_none), style = TextStyle(fontSize = 18.sp, color = look.ink, textAlign = TextAlign.Center))
            }
            return@Column
        }
        val chosen = withGroups.firstOrNull { it.outletId == selected }
        RetailerPicker(chosen, withGroups, { it.nameLine }, stringResource(R.string.pg_loyalty_pick_outlet), { onSelect(it.outletId); failed = null }, Modifier.fillMaxWidth().testTag(CampaignTags.PICKER))
        if (chosen == null) return@Column
        fun giftName(id: Long) = gifts.firstOrNull { it.id == id }?.name(locale) ?: "#$id"
        groups.filter { it.outletId == chosen.outletId }.forEach { g ->
            Column(Modifier.fillMaxWidth().testTag(CampaignTags.group(g.redemptionUuid, g.giftId)).clip(RoundedCornerShape(12.dp)).background(look.card).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(giftName(g.giftId), style = TextStyle(fontSize = 17.sp, color = look.purple, fontWeight = FontWeight.Bold))
                Text(stringResource(R.string.pg_campaign_progress, Formats.integer(g.doneUnits.size.toLong(), locale), Formats.integer(g.qty.toLong(), locale)), style = TextStyle(fontSize = 14.sp, color = look.inkMuted))
                g.openUnits.forEach { u ->
                    Text(stringResource(R.string.pg_campaign_unit, Formats.integer(u.toLong(), locale)), style = TextStyle(fontSize = 15.sp, color = look.ink, fontWeight = FontWeight.Medium))
                    slot(g, u)
                }
                AronSolidButton(stringResource(R.string.pg_photo_submit), {
                    if (!busy) {
                        busy = true
                        scope.launch {
                            try {
                                val out = onSubmit(g)
                                if (out == SaveOutcome.SAVED || out == SaveOutcome.ALREADY_SAVED) { saved = true; failed = null } else failed = out.name
                            } finally { busy = false }
                        }
                    }
                }, Modifier.fillMaxWidth().testTag(CampaignTags.SUBMIT), enabled = canSubmit(g) && !busy)
            }
        }
        failed?.let { f ->
            Text(stringResource(when (f) { "DAY_SUBMITTED" -> R.string.pg_day_submitted; "CHECK_IN_FIRST" -> R.string.pg_check_in_first; else -> R.string.pg_photo_failed }), Modifier.testTag(CampaignTags.FAILED), style = TextStyle(fontSize = 14.sp, color = look.red))
        }
        if (saved) {
            AlertDialog(
                onDismissRequest = { saved = false },
                title = { Text(stringResource(R.string.pg_photo_saved), style = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.Bold)) },
                confirmButton = { AronSolidButton(stringResource(R.string.pg_ok), { saved = false }, Modifier.testTag(CampaignTags.SAVED_OK)) },
            )
        }
    }
}
