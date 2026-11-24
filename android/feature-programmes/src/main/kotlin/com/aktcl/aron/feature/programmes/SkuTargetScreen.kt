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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aktcl.aron.core.ui.LocalAronLook
import com.aktcl.aron.core.ui.aron.PackCategory
import com.aktcl.aron.core.ui.aron.PackshotImage
import com.aktcl.aron.rules.UiLocale

object SkuTargetTags {
    const val TAB = "sku_tab"
    const val THROUGH = "sku_through"
    const val AS_OF = "sku_as_of"
    const val OFFLINE = "sku_offline"
    const val NO_CALENDAR = "sku_no_calendar"
    const val TRUNCATED = "sku_truncated"
    const val UNCONFIRMED = "sku_unconfirmed"
    const val FORK = "sku_fork"
    const val EMPTY = "sku_empty"
    fun card(skuId: Long) = "sku_card_$skuId"
    fun today(skuId: Long) = "sku_today_$skuId"
    fun percent(skuId: Long) = "sku_percent_$skuId"
}

/**
 * Target and achievement by SKU (manual 10): the server's figures per SKU as sent, with the closed day they cover; under a card the phone's own sales of today in a separate, labelled line;
 * at the top a count of this phone's earlier-day memos the server has not confirmed. Nothing local is ever added into a figure ([FigureFrame]).
 * Quantities are in the unit the field counts in (rule 5: packets, boxes, dozens), the ratios are the shared maths over the server's day counts, and a figure that cannot be computed is a dash.
 * [data] null (or no row) is the empty state; the loading and error states belong to the host.
 */
@Composable
fun SkuTargetScreen(
    data: SkuTargetData?, locale: UiLocale, modifier: Modifier = Modifier,
    /** `today on this phone` per SKU (this phone's live sale memos dated after the figures' last day). */
    todayBySku: Map<Long, LocalToday> = emptyMap(),
    /** The phone's memos of earlier days that the server has not confirmed (a count; never in a figure). */
    unconfirmedEarlier: Int = 0,
    /** Memos with two live edits, left out of the "today" lines. */
    forkLeftOut: Int = 0,
    offline: Boolean = false,
) {
    val look = LocalAronLook.current
    Column(modifier.fillMaxSize().background(look.page).verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.testTag(SkuTargetTags.TAB).clip(RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp)).background(androidx.compose.ui.graphics.Brush.horizontalGradient(listOf(look.barStart, look.barEnd))).padding(horizontal = 18.dp, vertical = 10.dp)) {
            Text(stringResource(R.string.pg_tgt_tab_std), style = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Medium, color = look.onBar))
        }
        if (offline) Note(stringResource(R.string.pg_tgt_offline), SkuTargetTags.OFFLINE)
        if (data == null || data.rows.isEmpty()) {
            Box(Modifier.fillMaxWidth().heightIn(min = 160.dp).testTag(SkuTargetTags.EMPTY), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.pg_tgt_empty), style = TextStyle(fontSize = 18.sp, color = look.ink, textAlign = TextAlign.Center))
            }
            return@Column
        }
        Note(
            if (data.fromFirst) stringResource(R.string.pg_tgt_through_first) else stringResource(R.string.pg_tgt_through, LoyaltyModel.dateText(data.throughDate, locale)),
            SkuTargetTags.THROUGH,
        )
        data.asOf?.let { Note(stringResource(R.string.pg_tgt_as_of, LoyaltyModel.dateText(it, locale)), SkuTargetTags.AS_OF) }
        if (!data.hasCalendar) Note(stringResource(R.string.pg_tgt_no_calendar), SkuTargetTags.NO_CALENDAR)
        if (data.truncated) Note(stringResource(R.string.pg_tgt_truncated), SkuTargetTags.TRUNCATED)
        if (unconfirmedEarlier > 0) Note(countText(R.plurals.pg_tgt_unconfirmed, unconfirmedEarlier, locale), SkuTargetTags.UNCONFIRMED)
        if (forkLeftOut > 0) Note(countText(R.plurals.pg_tgt_fork, forkLeftOut, locale), SkuTargetTags.FORK)
        data.rows.forEach { SkuCard(it, todayBySku[it.skuId], locale) }
    }
}

@Composable private fun Note(text: String, tag: String) {
    val look = LocalAronLook.current
    Text(text, Modifier.testTag(tag), style = TextStyle(fontSize = 14.sp, color = look.purple, fontWeight = FontWeight.Medium))
}

private fun category(c: String) = when (c) { "bidi" -> PackCategory.Bidi; "lighter" -> PackCategory.Lighter; "match" -> PackCategory.Match; else -> PackCategory.Cigarette }

@Composable private fun unitWord(key: String): String = stringResource(
    when (key) { "packet" -> R.string.pg_tgt_unit_packet; "box" -> R.string.pg_tgt_unit_box; "dozen" -> R.string.pg_tgt_unit_dozen; "piece" -> R.string.pg_tgt_unit_piece; else -> R.string.pg_tgt_unit_stick },
)

/** One SKU card: the red-bordered white card of the reference (packshot and name on the left; target, achieved, remaining, % over ADS, TADS, PADS, RADS). */
@Composable
private fun SkuCard(r: SkuTargetRow, today: LocalToday?, locale: UiLocale) {
    val look = LocalAronLook.current
    val shape = RoundedCornerShape(12.dp)
    val name = if (locale == UiLocale.BN) r.nameBn ?: r.name else r.name
    val unit = unitWord(r.unitKey)
    fun q(m: Long?) = SkuTargetModel.qtyText(m, r.factor, locale)
    // at a large font size the eight cells stack two per line so no label or figure is cut, and the name sits beside the pack picture
    val large = LocalDensity.current.fontScale >= 1.3f
    val cells = listOf(
        Triple(stringResource(R.string.pg_tgt_target), q(r.targetMilli), null as androidx.compose.ui.graphics.Color?), Triple(stringResource(R.string.pg_tgt_achieved), q(r.achievedMilli), null),
        Triple(stringResource(R.string.pg_tgt_remaining), q(r.remainingMilli), null), Triple(stringResource(R.string.pg_tgt_percent), SkuTargetModel.percentText(r.achievementBp, locale), if ((r.achievementBp ?: 0L) >= 10_000L) look.online else look.red),
        Triple(stringResource(R.string.pg_tgt_ads), q(r.adsMilli), look.purple), Triple(stringResource(R.string.pg_tgt_tads), q(r.tadsMilli), look.purple),
        Triple(stringResource(R.string.pg_tgt_pads), q(r.padsMilli), look.purple), Triple(stringResource(R.string.pg_tgt_rads), q(r.radsMilli), look.purple),
    )
    Column(Modifier.fillMaxWidth().testTag(SkuTargetTags.card(r.skuId)).clip(shape).background(look.card).border(2.dp, look.red, shape).padding(8.dp)) {
        if (large) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PackshotImage(r.skuId.toString(), Modifier.size(52.dp, 82.dp), name, category(r.categoryCode))
                Column {
                    Text(name, style = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Bold, color = look.purple))
                    Text(unit, style = TextStyle(fontSize = 13.sp, color = look.inkMuted))
                }
            }
            cells.chunked(2).forEachIndexed { row, pair ->
                Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
                    pair.forEachIndexed { k, (label, value, color) ->
                        val idx = row * 2 + k
                        Cell(label, value, Modifier.weight(1f).then(if (idx == 3) Modifier.testTag(SkuTargetTags.percent(r.skuId)) else Modifier), labelColor = if (idx >= 4) look.purple else null, valueColor = if (idx == 3) color else null, wrap = true)
                    }
                }
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.width(76.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    PackshotImage(r.skuId.toString(), Modifier.size(52.dp, 82.dp), name, category(r.categoryCode))
                    Text(name, style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Bold, color = look.purple, textAlign = TextAlign.Center), maxLines = 2)
                    Text(unit, style = TextStyle(fontSize = 12.sp, color = look.inkMuted, textAlign = TextAlign.Center))
                }
                Column(Modifier.weight(1f)) {
                    Row(Modifier.fillMaxWidth()) {
                        cells.take(4).forEachIndexed { idx, (label, value, color) ->
                            Cell(label, value, Modifier.weight(1f).then(if (idx == 3) Modifier.testTag(SkuTargetTags.percent(r.skuId)) else Modifier), valueColor = if (idx == 3) color else null)
                        }
                    }
                    Box(Modifier.fillMaxWidth().padding(vertical = 4.dp).height1().background(look.rowRule))
                    Row(Modifier.fillMaxWidth()) { cells.drop(4).forEach { (label, value, _) -> Cell(label, value, Modifier.weight(1f), labelColor = look.purple) } }
                }
            }
        }
        if (today != null && !today.isEmpty) {
            Text(
                stringResource(R.string.pg_tgt_today_phone, SkuTargetModel.qtyText(today.qtyBase * 1000L, r.factor, locale), unit),
                Modifier.padding(top = 6.dp).testTag(SkuTargetTags.today(r.skuId)), style = TextStyle(fontSize = 13.sp, color = look.purple, fontWeight = FontWeight.Medium),
            )
        }
    }
}

private fun Modifier.height1(): Modifier = this.then(Modifier.heightIn(min = 1.dp, max = 1.dp))

@Composable private fun Cell(label: String, value: String, modifier: Modifier, labelColor: androidx.compose.ui.graphics.Color? = null, valueColor: androidx.compose.ui.graphics.Color? = null, wrap: Boolean = false) {
    val look = LocalAronLook.current
    Column(modifier.padding(horizontal = 2.dp).semantics(mergeDescendants = true) { contentDescription = "$label $value" }, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, style = TextStyle(fontSize = 13.sp, color = labelColor ?: look.inkMuted, textAlign = TextAlign.Center), maxLines = if (wrap) 2 else 1)
        Text(value, style = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.Medium, color = valueColor ?: look.ink, textAlign = TextAlign.Center), maxLines = if (wrap) 2 else 1)
    }
}
