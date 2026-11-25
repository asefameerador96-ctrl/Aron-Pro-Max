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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aktcl.aron.core.ui.LocalAronLook
import com.aktcl.aron.rules.Formats
import com.aktcl.aron.rules.Percent
import com.aktcl.aron.rules.UiLocale

object StarTags {
    const val CARD = "star_card"
    const val TITLE = "star_title"
    const val THROUGH = "star_through"
    const val LM_OPEN = "star_lm_open"
    const val EMPTY = "star_empty"
    const val OFFLINE = "star_offline"
    const val TRUNCATED = "star_truncated"
    const val TARGET = "star_target"
    const val STD = "star_std"
    const val PROJECTION = "star_projection"
    const val ACHIEVEMENT = "star_achievement"
    const val LMS = "star_lms"
    const val LMADS = "star_lmads"
    const val PADS = "star_pads"
    const val GROWTH = "star_growth"
    const val STATUS = "star_status"
    const val SLAB = "star_slab"
    const val SLAB_AMOUNT = "star_slab_amount"
    const val PROJECTED = "star_projected"
    const val TODAY = "star_today"
    const val UNCONFIRMED = "star_unconfirmed"
    const val REASON_LIST = "star_reason_list"
    const val REASON_SKU = "star_reason_sku"
    const val UNCONFIRMED_SHORT = "star_unconfirmed_short"
}

object StarFormat {
    /** A Decimal3 figure (thousandths) as two decimals, rounded once half up ("2.83"); a dash when null (a divisor was 0: never 0). */
    fun decimal(milli: Long?, locale: UiLocale): String {
        if (milli == null) return Percent.DASH
        val neg = milli < 0L
        val h = (kotlin.math.abs(milli) + 5L) / 10L
        return (if (neg) "-" else "") + Formats.integer(h / 100L, locale) + Formats.digits("." + (h % 100L).toString().padStart(2, '0'), locale)
    }

    /** The growth in basis points with its arrow and sign ("↓ -39.87%"); a dash when null. */
    fun growth(bp: Long?, locale: UiLocale): String = when {
        bp == null -> Percent.DASH
        bp < 0L -> "↓ " + Percent.text(bp, 2, locale).let { if (it.startsWith("-")) it else "-$it" }
        bp > 0L -> "↑ " + Percent.text(bp, 2, locale)
        else -> Percent.text(0L, 2, locale)
    }

    /** Whole taka with the "Tk" unit ("5,700 Tk"); a dash and the unit when null (no slab reached: "— Tk"). */
    fun tk(mtk: Long?, locale: UiLocale, unit: String): String = (if (mtk == null) Percent.DASH else Formats.digits(com.aktcl.aron.rules.Money.formatTaka(mtk, 0), locale)) + " " + unit

    /** True when last month has NOT fully closed at the figures' cut: [throughDate] is before the last day of the month before [month] (LMS and LMADS are then partial). */
    fun lastMonthOpen(month: String, throughDate: String): Boolean = runCatching { throughDate < java.time.LocalDate.parse("$month-01").minusDays(1).toString() }.getOrDefault(false)
}

/**
 * Match Box Lifting Star (manual 62): the server's snapshot as sent (`RouteSnapshot.star`), read-only, in the reference layout: the title block, the Target / STD / Projection = Achievement row, the LMS /
 * LMADS / PADS = Growth row, the Status, Achieved Slab and Slab Amount pills and the Projected Amount band. Every ratio is the SERVER's figure with its through_date; a figure the server could not
 * compute (a divisor of 0) is a dash, never 0, and the slab and projected amounts are a dash with "Tk" until a slab is reached. When last month has not closed at the cut the page says LMS and LMADS are not final.
 * [stars] empty is the empty state; loading and error belong to the host. Under a row whose server figures name the SKUs the STD counts (`sku_ids` or `sku_category`, contract 1.26.0) a separate "today on this phone" line shows this phone's own sales since the closed day, in the programme unit, never added to the STD (see [StarTodayModel]).
 */
@Composable
fun StarScreen(stars: List<RouteStar>, locale: UiLocale, modifier: Modifier = Modifier, truncated: Boolean = false, offline: Boolean = false, today: List<StarToday?> = emptyList()) {
    val look = LocalAronLook.current
    Column(modifier.fillMaxSize().background(look.page).verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (offline) Text(stringResource(R.string.pg_tgt_offline), Modifier.testTag(StarTags.OFFLINE), style = TextStyle(fontSize = 14.sp, color = look.purple, fontWeight = FontWeight.Medium))
        if (stars.isEmpty()) {
            Box(Modifier.fillMaxWidth().heightIn(min = 160.dp).testTag(StarTags.EMPTY), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.pg_star_empty), style = TextStyle(fontSize = 18.sp, color = look.ink, textAlign = TextAlign.Center))
            }
            return@Column
        }
        stars.forEachIndexed { i, s -> StarCard(s, locale, today.getOrNull(i)) }
        if (truncated) Text(stringResource(R.string.pg_star_truncated), Modifier.testTag(StarTags.TRUNCATED), style = TextStyle(fontSize = 14.sp, color = look.purple, fontWeight = FontWeight.Medium))
    }
}

@Composable private fun StarCard(s: RouteStar, locale: UiLocale, today: StarToday? = null) {
    val look = LocalAronLook.current
    val deep = Color(0xFF3F1A9E)
    val tk = stringResource(R.string.pg_star_tk)
    val names = stringArrayResource(R.array.pg_months_full)
    val monthLabel = names.getOrElse((s.month.substring(5, 7).toIntOrNull() ?: 1) - 1) { s.month } + "-" + Formats.digits(s.month.take(4), locale)
    val programme = if (locale == UiLocale.BN) s.nameBn?.takeIf { it.isNotBlank() } ?: s.name else s.name
    val status = if (locale == UiLocale.BN) s.statusBn.ifBlank { s.statusEn } else s.statusEn.ifBlank { s.statusBn }
    val large = LocalDensity.current.fontScale >= 1.3f
    val fromFirst = s.throughDate.take(7) < s.month
    Column(Modifier.fillMaxWidth().testTag(StarTags.CARD).clip(RoundedCornerShape(20.dp)).background(look.card)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("$programme ($monthLabel)", Modifier.testTag(StarTags.TITLE), style = TextStyle(fontSize = 19.sp, fontWeight = FontWeight.Medium, color = look.ink, textAlign = TextAlign.Center))
            Text(stringResource(R.string.pg_star_sr_name, s.srName ?: Percent.DASH, s.srCode ?: Percent.DASH), style = TextStyle(fontSize = 15.sp, color = look.inkMuted, textAlign = TextAlign.Center))
            Text(stringResource(R.string.pg_star_sr_route, s.routeName), style = TextStyle(fontSize = 15.sp, color = look.inkMuted, textAlign = TextAlign.Center))
            Text(
                if (fromFirst) stringResource(R.string.pg_tgt_through_first) else stringResource(R.string.pg_tgt_through, LoyaltyModel.dateText(s.throughDate, locale)),
                Modifier.testTag(StarTags.THROUGH), style = TextStyle(fontSize = 13.sp, color = look.inkMuted, textAlign = TextAlign.Center),
            )
            Text(stringResource(R.string.pg_star_days, Formats.integer(s.lmwd.toLong(), locale), Formats.integer(s.wd.toLong(), locale), Formats.integer(s.ewd.toLong(), locale)), style = TextStyle(fontSize = 14.sp, color = look.ink, textAlign = TextAlign.Center))
            if (StarFormat.lastMonthOpen(s.month, s.throughDate)) Text(stringResource(R.string.pg_star_lm_open), Modifier.testTag(StarTags.LM_OPEN), style = TextStyle(fontSize = 13.sp, color = look.purple, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center))
        }
        Divider()
        FigureRow(
            listOf(
                Fig(stringResource(R.string.pg_star_target), StarFormat.decimal(s.targetMilli, locale), deep, Color.White, StarTags.TARGET),
                Fig(stringResource(R.string.pg_star_std), StarFormat.decimal(s.stdMilli, locale), look.card, look.ink, StarTags.STD, outline = true),
                Fig(stringResource(R.string.pg_star_projection), StarFormat.decimal(s.projectionMilli, locale), deep, Color.White, StarTags.PROJECTION),
            ),
            Fig(stringResource(R.string.pg_star_achievement), Percent.text(s.achievementBp, 2, locale), look.red, Color.White, StarTags.ACHIEVEMENT), large,
        )
        Divider()
        FigureRow(
            listOf(
                Fig(stringResource(R.string.pg_star_lms), StarFormat.decimal(s.lmsMilli, locale), look.card, look.ink, StarTags.LMS, outline = true),
                Fig(stringResource(R.string.pg_star_lmads), StarFormat.decimal(s.lmadsMilli, locale), deep, Color.White, StarTags.LMADS),
                Fig(stringResource(R.string.pg_star_pads), StarFormat.decimal(s.padsMilli, locale), deep, Color.White, StarTags.PADS),
            ),
            Fig(stringResource(R.string.pg_star_growth), StarFormat.growth(s.growthBp, locale), if ((s.growthBp ?: 0L) < 0L) Color(0xFFFCE8E8) else look.card, if ((s.growthBp ?: 0L) < 0L) look.red else look.ink, StarTags.GROWTH, outline = true, outlineColor = look.red), large,
        )
        Divider()
        val teal = Color(0xFF2A8470)
        val pills = listOf(
            Triple(stringResource(R.string.pg_star_status), status, StarTags.STATUS),
            Triple(stringResource(R.string.pg_star_slab), s.achievedSlab ?: Percent.DASH, StarTags.SLAB),
            Triple(stringResource(R.string.pg_star_slab_amount), StarFormat.tk(s.slabAmountMtk, locale, tk), StarTags.SLAB_AMOUNT),
        )
        if (large) Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { pills.forEach { (l, v, t) -> Pill(l, v, teal, t, Modifier.fillMaxWidth()) } }
        else Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) { pills.forEach { (l, v, t) -> Pill(l, v, teal, t, Modifier.weight(1f)) } }
        Column(
            Modifier.fillMaxWidth().testTag(StarTags.PROJECTED).background(Brush.horizontalGradient(listOf(Color(0xFF6B3A6E), Color(0xFFA57BA8)))).padding(vertical = 18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(stringResource(R.string.pg_star_projected), style = TextStyle(fontSize = 18.sp, color = Color(0xCCFFFFFF)))
            Text(StarFormat.tk(s.projectedAmountMtk, locale, tk), style = TextStyle(fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Color.White))
        }
        // this phone's own sales since the closed day, SEPARATE from the server's STD (never added to it) and only when the row names the SKUs it counts
        if (today != null) Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            val dash = today.failed || today.noRoute || today.unknownDash
            val memosPhrase = if (dash) Percent.DASH else pluralStringResource(R.plurals.pg_star_today_memos, today.memoCount.coerceIn(0L, 1_000_000L).toInt(), StarTodayModel.memoCountText(today, locale))
            // a SKU the phone cannot place with some lines counted: the figures are a lower bound ("at least"), never presented as exact
            val qty = StarTodayModel.qtyText(today, locale).let { if (today.lowerBound) stringResource(R.string.pg_star_at_least, it) else it }
            val memos = if (today.lowerBound) stringResource(R.string.pg_star_at_least, memosPhrase) else memosPhrase
            Text(
                stringResource(if (today.failed) R.string.pg_star_today_failed else if (today.noRoute) R.string.pg_star_today_no_route else if (today.incomplete || today.unknownSku) R.string.pg_star_today_partial else R.string.pg_star_today, qty, memos),
                Modifier.testTag(StarTags.TODAY), style = TextStyle(fontSize = 14.sp, color = look.ink),
            )
            // each reason apart: the server cut the programme's SKU list, or this phone sold a SKU it does not know
            if (today.incomplete) Text(stringResource(R.string.pg_star_reason_list), Modifier.testTag(StarTags.REASON_LIST), style = TextStyle(fontSize = 13.sp, color = look.purple, fontWeight = FontWeight.Medium))
            if (today.unknownSku) Text(stringResource(R.string.pg_star_reason_sku), Modifier.testTag(StarTags.REASON_SKU), style = TextStyle(fontSize = 13.sp, color = look.purple, fontWeight = FontWeight.Medium))
            // the server cut the programme's SKU list: the count of unconfirmed earlier-day memos can be short too
            if (today.incomplete) Text(stringResource(R.string.pg_star_unconfirmed_short), Modifier.testTag(StarTags.UNCONFIRMED_SHORT), style = TextStyle(fontSize = 13.sp, color = look.purple, fontWeight = FontWeight.Medium))
            if (today.unconfirmedEarlier > 0) Text(
                pluralStringResource(R.plurals.pg_tgt_unconfirmed, today.unconfirmedEarlier, Formats.integer(today.unconfirmedEarlier.toLong(), locale)),
                Modifier.testTag(StarTags.UNCONFIRMED), style = TextStyle(fontSize = 13.sp, color = look.purple, fontWeight = FontWeight.Medium),
            )
        }
    }
}

@Composable private fun Divider() = Box(Modifier.fillMaxWidth().heightIn(min = 1.dp, max = 1.dp).background(LocalAronLook.current.rowRule))

private data class Fig(val label: String, val value: String, val fill: Color, val ink: Color, val tag: String, val outline: Boolean = false, val outlineColor: Color = Color(0xFFB9A8D8))

/** Three figures, a grey "=" chip and the result figure; at a large font size they stack one per line so nothing is cut. */
@Composable private fun FigureRow(figs: List<Fig>, result: Fig, large: Boolean) {
    val look = LocalAronLook.current
    if (large) Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { (figs + result).forEach { FigBox(it, Modifier.fillMaxWidth()) } }
    else Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.Top) {
        figs.forEach { FigBox(it, Modifier.weight(1f)) }
        Box(Modifier.padding(top = 10.dp).clip(RoundedCornerShape(8.dp)).background(look.rowAlt).padding(horizontal = 6.dp, vertical = 8.dp)) { Text("=", style = TextStyle(fontSize = 14.sp, color = look.inkMuted)) }
        FigBox(result, Modifier.weight(1.1f))
    }
}

@Composable private fun FigBox(f: Fig, modifier: Modifier) {
    val look = LocalAronLook.current
    val shape = RoundedCornerShape(10.dp)
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.fillMaxWidth().testTag(f.tag).clip(shape).background(f.fill).then(if (f.outline) Modifier.border(1.5.dp, f.outlineColor, shape) else Modifier).padding(vertical = 10.dp), contentAlignment = Alignment.Center) {
            Text(f.value, style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Medium, color = f.ink, textAlign = TextAlign.Center), maxLines = 1)
        }
        Text(f.label, Modifier.padding(top = 4.dp), style = TextStyle(fontSize = 12.sp, color = look.inkMuted, textAlign = TextAlign.Center), maxLines = 2)
    }
}

@Composable private fun Pill(label: String, value: String, fill: Color, tag: String, modifier: Modifier) {
    val look = LocalAronLook.current
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, style = TextStyle(fontSize = 14.sp, color = look.ink, textAlign = TextAlign.Center), maxLines = 1)
        Box(Modifier.fillMaxWidth().padding(top = 4.dp).testTag(tag).clip(RoundedCornerShape(12.dp)).background(fill).padding(vertical = 12.dp, horizontal = 4.dp), contentAlignment = Alignment.Center) {
            Text(value, style = TextStyle(fontSize = 15.sp, color = Color.White, textAlign = TextAlign.Center), maxLines = 2)
        }
    }
}
