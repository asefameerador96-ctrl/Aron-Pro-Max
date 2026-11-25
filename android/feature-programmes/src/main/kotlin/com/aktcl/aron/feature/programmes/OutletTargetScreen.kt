package com.aktcl.aron.feature.programmes

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aktcl.aron.core.ui.LocalAronLook
import com.aktcl.aron.core.ui.aron.AronSolidButton
import com.aktcl.aron.rules.UiLocale

object OutletTargetTags {
    const val POPUP = "ot_popup"
    const val OKAY = "ot_okay"
    const val DETAILS = "ot_details"
    const val PAGE = "ot_page"
    const val EMPTY = "ot_empty"
    const val EMPTY_CUT = "ot_empty_cut"
    const val LOADING = "ot_loading"
    const val FAILED = "ot_failed"
    const val OFFLINE = "ot_offline"
    const val THROUGH = "ot_through"
    const val UNCONFIRMED = "ot_unconfirmed"
    const val FORK = "ot_fork"
    const val SKU_MISSING = "ot_sku_missing"
    fun today(programmeId: Long) = "ot_today_$programmeId"
    fun figure(programmeId: Long, key: String) = "ot_fig_${programmeId}_$key"
    fun row(skuId: Long) = "ot_row_$skuId"
    fun percent(skuId: Long) = "ot_pct_$skuId"
    fun total(categoryCode: String) = "ot_total_$categoryCode"
}

@Composable private fun campaignName(c: OutletCampaignView, locale: UiLocale): String {
    val names = androidx.compose.ui.res.stringArrayResource(R.array.pg_months_full)
    val month = names.getOrElse((c.month.substring(5, 7).toIntOrNull() ?: 1) - 1) { c.month }
    return (if (locale == UiLocale.BN) c.labelBn?.takeIf { it.isNotBlank() } ?: c.labelEn else c.labelEn) + " (" + month + ")"
}

/**
 * "Today's target" pop-up of an outlet (manual 21, left): for each campaign the name and month, Target / Achievement / Remaining STD and Target / Achievement / Remaining Memo, the SERVER's figures as sent
 * with the closed day they cover; what this phone sold since then is a separate, labelled line and a count of unconfirmed earlier memos, never added into a figure ([FigureFrame]).
 * [data] null is the empty state ("no campaign target"), and a cut download says it may be missing. [onDetails] opens the SKU page (null hides the button).
 */
@Composable
fun OutletTargetPopup(
    outletLabel: String, data: OutletTargetData?, locale: UiLocale, onOkay: () -> Unit, modifier: Modifier = Modifier, offline: Boolean = false, onDetails: (() -> Unit)? = null,
    /** The read has not finished (loading) or failed (error): the dialog says so instead of the empty state. */
    loading: Boolean = false, failed: Boolean = false,
) {
    androidx.compose.ui.window.Dialog(onDismissRequest = onOkay) {
        OutletTargetPopupCard(outletLabel, data, locale, onOkay, modifier, offline, onDetails, loading, failed)
    }
}

/**
 * The pop-up as the old SR shows it (manual 21 left, the owner's screenshot; same look as the parity lane's pop-up): a lavender rounded box, the outlet in bold, the campaign line in the accent colour, the
 * figures between hairlines and a full-width purple Okay (Sunlight: a solid surface). Also what the goldens capture, since a dialog window is not part of the root.
 */
@Composable
fun OutletTargetPopupCard(
    outletLabel: String, data: OutletTargetData?, locale: UiLocale, onOkay: () -> Unit, modifier: Modifier = Modifier, offline: Boolean = false, onDetails: (() -> Unit)? = null,
    loading: Boolean = false, failed: Boolean = false,
) {
    val c = com.aktcl.aron.core.ui.LocalAronColors.current
    Column(
        modifier.fillMaxWidth().testTag(OutletTargetTags.POPUP).clip(RoundedCornerShape(28.dp)).background(if (c.sunlight) c.surfaceSolid else com.aktcl.aron.core.ui.AronLook.LavenderPanel).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(outletLabel, style = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.Bold, color = c.textPrimary))
        OutletTargetPopupBody(data, locale, offline, loading, failed, Modifier.weight(1f, fill = false))
        if (onDetails != null && data != null && data.campaigns.isNotEmpty()) com.aktcl.aron.core.ui.aron.AronTextButton(stringResource(R.string.pg_ot_details), onDetails, Modifier.fillMaxWidth().testTag(OutletTargetTags.DETAILS))
        AronSolidButton(stringResource(R.string.pg_ot_okay), onOkay, Modifier.fillMaxWidth().testTag(OutletTargetTags.OKAY))
    }
}

/** The body of the pop-up (also what the goldens capture, since a dialog window is not part of the root): the notes, then each campaign's figures, or the loading, failed or empty state. */
@Composable
fun OutletTargetPopupBody(data: OutletTargetData?, locale: UiLocale, offline: Boolean = false, loading: Boolean = false, failed: Boolean = false, modifier: Modifier = Modifier) {
    val look = LocalAronLook.current
    Column(modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (offline) Note(stringResource(R.string.pg_tgt_offline), OutletTargetTags.OFFLINE)
        if (loading) com.aktcl.aron.core.ui.aron.AronLoadingCard(stringResource(com.aktcl.aron.core.ui.R.string.core_ui_loading), Modifier.testTag(OutletTargetTags.LOADING))
        else if (failed) Text(stringResource(R.string.pg_ot_failed), Modifier.testTag(OutletTargetTags.FAILED), style = TextStyle(fontSize = 16.sp, color = look.ink))
        else if (data == null || data.campaigns.isEmpty()) EmptyText(data?.truncated == true)
        else data.campaigns.forEach { CampaignFigures(it, locale) }
    }
}

/** The six figures of one campaign plus the closed day and the phone's separate lines. */
@Composable
private fun CampaignFigures(c: OutletCampaignView, locale: UiLocale) {
    val look = LocalAronLook.current
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(campaignName(c, locale), style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Medium, color = com.aktcl.aron.core.ui.LocalAronColors.current.accent))
        Note(if (c.fromFirst) stringResource(R.string.pg_tgt_through_first) else stringResource(R.string.pg_tgt_through, LoyaltyModel.dateText(c.throughDate, locale)), OutletTargetTags.THROUGH)
        Figure(c.programmeId, "std_t", stringResource(R.string.pg_ot_std_target), OutletTargetModel.countText(c.std.target, locale))
        Figure(c.programmeId, "std_a", stringResource(R.string.pg_ot_std_ach), OutletTargetModel.countText(c.std.achieved, locale))
        Figure(c.programmeId, "std_r", stringResource(R.string.pg_ot_std_rem), OutletTargetModel.countText(c.std.remaining, locale))
        Figure(c.programmeId, "memo_t", stringResource(R.string.pg_ot_memo_target), OutletTargetModel.countText(c.memo.target, locale))
        Figure(c.programmeId, "memo_a", stringResource(R.string.pg_ot_memo_ach), OutletTargetModel.countText(c.memo.achieved, locale))
        Figure(c.programmeId, "memo_r", stringResource(R.string.pg_ot_memo_rem), OutletTargetModel.countText(c.memo.remaining, locale))
        PhoneLines(c, locale)
    }
}

@Composable private fun PhoneLines(c: OutletCampaignView, locale: UiLocale) {
    val look = LocalAronLook.current
    if (!c.today.isEmpty) Text(
        stringResource(R.string.pg_ot_today_phone, OutletTargetModel.countText(c.today.qtyBase, locale), OutletTargetModel.countText(c.today.memoCount, locale)),
        Modifier.testTag(OutletTargetTags.today(c.programmeId)), style = TextStyle(fontSize = 13.sp, color = look.purple, fontWeight = FontWeight.Medium),
    )
    if (c.unconfirmedEarlier > 0) Note(countText(R.plurals.pg_tgt_unconfirmed, c.unconfirmedEarlier, locale), OutletTargetTags.UNCONFIRMED)
    if (c.forkLeftOut > 0) Note(countText(R.plurals.pg_tgt_fork, c.forkLeftOut, locale), OutletTargetTags.FORK)
}

@Composable private fun Figure(programmeId: Long, key: String, label: String, value: String) {
    val look = LocalAronLook.current
    Column(Modifier.testTag(OutletTargetTags.figure(programmeId, key))) {
        Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, Modifier.weight(1f), style = TextStyle(fontSize = 15.sp, color = look.ink))
            Text(value, style = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Bold, color = look.ink, textAlign = TextAlign.End))
        }
        HorizontalDivider()
    }
}

@Composable private fun EmptyText(cut: Boolean) {
    val look = LocalAronLook.current
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(stringResource(R.string.pg_ot_empty), Modifier.testTag(OutletTargetTags.EMPTY), style = TextStyle(fontSize = 16.sp, color = look.ink))
        if (cut) Note(stringResource(R.string.pg_ot_empty_cut), OutletTargetTags.EMPTY_CUT)
    }
}

@Composable private fun Note(text: String, tag: String) {
    val look = LocalAronLook.current
    Text(text, Modifier.testTag(tag), style = TextStyle(fontSize = 13.sp, color = look.purple, fontWeight = FontWeight.Medium))
}

@Composable private fun unitWord(key: String): String = stringResource(
    when (key) { "packet" -> R.string.pg_tgt_unit_packet; "box" -> R.string.pg_tgt_unit_box; "dozen" -> R.string.pg_tgt_unit_dozen; "piece" -> R.string.pg_tgt_unit_piece; else -> R.string.pg_tgt_unit_stick },
)

@Composable private fun categoryWord(code: String) = stringResource(
    when (code) { "cigarette" -> R.string.pg_ot_cat_cigarette; "bidi" -> R.string.pg_ot_cat_bidi; "match" -> R.string.pg_kpi_cat_match; else -> R.string.pg_kpi_cat_lighter },
)

/**
 * The "টার্গেট" page of an outlet (manual 21, right): the outlet and route lines, then for each campaign the SKU table (target, sales and % in the unit the field counts in) and a total per category
 * (cigarette, lighter, match), all the server's figures as sent with their closed day; the phone's own lines stay separate. [data] null is the empty state; loading and error belong to the host.
 */
@Composable
fun OutletTargetScreen(outletLabel: String, routeLine: String?, data: OutletTargetData?, locale: UiLocale, modifier: Modifier = Modifier, offline: Boolean = false) {
    val look = LocalAronLook.current
    Column(modifier.fillMaxSize().testTag(OutletTargetTags.PAGE).background(look.page).verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.clip(RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp)).background(Brush.horizontalGradient(listOf(look.barStart, look.barEnd))).padding(horizontal = 18.dp, vertical = 10.dp)) {
            Text(stringResource(R.string.pg_ot_tab), style = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Medium, color = look.onBar))
        }
        Text(outletLabel, style = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.Bold, color = look.ink))
        routeLine?.let { Text(it, style = TextStyle(fontSize = 14.sp, color = look.inkMuted)) }
        if (offline) Note(stringResource(R.string.pg_tgt_offline), OutletTargetTags.OFFLINE)
        if (data == null || data.campaigns.isEmpty()) {
            Box(Modifier.fillMaxWidth().heightIn(min = 140.dp), contentAlignment = Alignment.Center) { EmptyText(data?.truncated == true) }
            return@Column
        }
        data.campaigns.forEach { CampaignTable(it, locale) }
    }
}

@Composable
private fun CampaignTable(c: OutletCampaignView, locale: UiLocale) {
    val look = LocalAronLook.current
    val shape = RoundedCornerShape(12.dp)
    Column(Modifier.fillMaxWidth().clip(shape).background(look.card).border(2.dp, look.red, shape).padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(campaignName(c, locale), style = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Bold, color = look.purple))
        Note(if (c.fromFirst) stringResource(R.string.pg_tgt_through_first) else stringResource(R.string.pg_tgt_through, LoyaltyModel.dateText(c.throughDate, locale)), OutletTargetTags.THROUGH)
        if (c.skuMissing) Note(stringResource(R.string.pg_ot_sku_missing), OutletTargetTags.SKU_MISSING)
        Line(stringResource(R.string.pg_ot_col_sku), stringResource(R.string.pg_ot_col_target), stringResource(R.string.pg_ot_col_sold), stringResource(R.string.pg_ot_col_pct), header = true)
        c.rows.forEach { r ->
            val name = if (locale == UiLocale.BN) r.nameBn ?: r.name else r.name
            Line(
                name, OutletTargetModel.qtyText(r.targetMilli, r.factor, locale), OutletTargetModel.qtyText(r.soldMilli, r.factor, locale), OutletTargetModel.percentText(r.pct, locale),
                unit = unitWord(r.unitKey), tag = OutletTargetTags.row(r.skuId), pctTag = OutletTargetTags.percent(r.skuId),
            )
        }
        HorizontalDivider(color = look.rowRule)
        c.totals.forEach { t ->
            Line(
                categoryWord(t.categoryCode), OutletTargetModel.totalText(t.targetNum, t.den, locale), OutletTargetModel.totalText(t.soldNum, t.den, locale), OutletTargetModel.percentText(t.pct, locale),
                header = true, unit = unitWord(t.unitKey), tag = OutletTargetTags.total(t.categoryCode),
            )
        }
        PhoneLines(c, locale)
    }
}

/** One table line: four cells; at a large font the numbers keep their column and the name wraps. */
@Composable
private fun Line(a: String, b: String, c: String, d: String, header: Boolean = false, unit: String? = null, tag: String? = null, pctTag: String? = null) {
    val look = LocalAronLook.current
    val w = if (header) FontWeight.Bold else FontWeight.Normal
    val st = TextStyle(fontSize = 14.sp, fontWeight = w, color = look.ink)
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp).then(if (tag != null) Modifier.testTag(tag) else Modifier), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.Top) {
        Column(Modifier.weight(1.5f)) {
            Text(a, style = st)
            if (unit != null) Text(unit, style = TextStyle(fontSize = 12.sp, color = look.inkMuted))
        }
        Text(b, Modifier.weight(1f), style = st.copy(textAlign = TextAlign.End))
        Text(c, Modifier.weight(1f), style = st.copy(textAlign = TextAlign.End))
        Text(d, Modifier.weight(0.8f).then(if (pctTag != null) Modifier.testTag(pctTag) else Modifier), style = st.copy(textAlign = TextAlign.End))
    }
}
