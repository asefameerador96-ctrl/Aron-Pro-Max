package com.aktcl.aron.core.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/** The taka sign, drawn from the Bengali face even in English (the Latin subset has no `৳`). */
const val TAKA_SIGN = '৳'
private const val NBSP = ' '

/** The smallest the taka sign and the decimals may render (tokens s7.1: "0.8 em, floor 14 sp"). */
private const val MONEY_SPAN_FLOOR_SP = 14f

/**
 * Builds the annotated amount of tokens s7.1: the `৳` after the amount at 0.8 em (floor 14 sp) in [AronFonts.Bengali],
 * joined with U+00A0 so it never wraps alone; on display and numeral sizes (26 sp and up) the two decimals at 0.75 em
 * (floor 14 sp). The em values are relative to the SMALLEST size the text may shrink to ([moneyFloorSp] of [baseSp]), so
 * the floors hold at the last auto-size step. Pure, so a row model can precompute it off the main thread. [baseSp] is the
 * style's font size in sp.
 */
fun moneyAnnotated(text: String, baseSp: Float, largeDecimals: Boolean): AnnotatedString {
    val signAt = text.indexOf(TAKA_SIGN)
    val amount = (if (signAt >= 0) text.substring(0, signAt) else text).trimEnd()
    val hasSign = signAt >= 0
    val smallest = moneyFloorSp(baseSp)
    val signEm = if (smallest > 0f) maxOf(0.8f, MONEY_SPAN_FLOOR_SP / smallest) else 0.8f
    val decimalsEm = if (smallest > 0f) maxOf(0.75f, MONEY_SPAN_FLOOR_SP / smallest) else 0.75f
    // the decimals: the last '.' followed by exactly two digits (ASCII or Bengali)
    val dot = amount.lastIndexOf('.')
    val decimals = largeDecimals && dot >= 0 && amount.length - dot == 3 && amount.substring(dot + 1).all { it in '0'..'9' || it in '০'..'৯' }
    return buildAnnotatedString {
        if (decimals) {
            append(amount.substring(0, dot))
            withStyle(SpanStyle(fontSize = decimalsEm.em)) { append(amount.substring(dot)) }
        } else {
            append(amount)
        }
        if (hasSign) {
            append(NBSP)
            withStyle(SpanStyle(fontSize = signEm.em, fontFamily = AronFonts.Bengali)) { append(TAKA_SIGN) }
        }
    }
}

/** The floor a money style may shrink to (tokens s7.3): display 24 sp, numeral 18 sp, anything smaller does not shrink. */
fun moneyFloorSp(baseSp: Float): Float = when {
    baseSp >= 34f -> 24f
    baseSp >= 26f -> 18f
    else -> baseSp
}

/**
 * The size a large-text role renders at under the system font scale (tokens s7.3 `fontScale.cap`): `min(base x fontScale,
 * base x 1.5)` expressed in sp (Compose multiplies sp by the font scale itself), so display and numeral stop growing at 1.5.
 * Roles below 26 sp are not capped. Pure.
 */
fun moneyCappedSp(baseSp: Float, fontScale: Float): Float =
    if (baseSp >= 26f && fontScale > AronTokens.Size.LargeTextCap) baseSp * AronTokens.Size.LargeTextCap / fontScale else baseSp

/**
 * A pre-formatted amount such as "4,391.00 ৳" (tokens s7.1, s7.3): single line, no ellipsis, capped at 1.5 x the base size
 * under the system font scale, then shrink-to-fit down to the style's floor; the sign at 0.8 em (floor 14 sp) in the
 * Bengali face; `.00` at 0.75 em on display and numeral sizes. Works for any number too (a count has no sign and no
 * decimals). Formatting and digits are the caller's job.
 */
@Composable
fun MoneyText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.headlineMedium,
    color: Color = LocalAronColors.current.textPrimary,
    textAlign: TextAlign = TextAlign.Start,
) {
    val roleSp = if (style.fontSize.isSp) style.fontSize.value else 16f
    val fontScale = LocalDensity.current.fontScale
    val baseSp = moneyCappedSp(roleSp, fontScale)
    val annotated = remember(text, roleSp) { moneyAnnotated(text, roleSp, largeDecimals = roleSp >= 26f) }
    val floor = minOf(moneyFloorSp(roleSp), baseSp)
    val autoSize = if (floor < baseSp) TextAutoSize.StepBased(minFontSize = floor.sp, maxFontSize = baseSp.sp, stepSize = 1.sp) else null
    BasicText(
        text = annotated,
        modifier = modifier,
        style = style.copy(color = color, textAlign = textAlign, fontSize = baseSp.sp),
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Clip,
        autoSize = autoSize,
    )
}

/**
 * Progress meter (android-glass s6.16): 6 dp (sunlight 8 dp plus a 1.5 dp outline), `radius.full`, track `state.skeleton`,
 * fill `accent`. The value is printed by the caller beside it; the semantics carry the range.
 */
@Composable
fun AronMeter(value: Int, max: Int, modifier: Modifier = Modifier) {
    val c = LocalAronColors.current
    val sun = c.sunlight
    val h = if (sun) AronTokens.Size.MeterSunlight else AronTokens.Size.Meter
    val track = c.stateSkeleton; val fill = c.accent; val outline = c.textPrimary
    val fraction = if (max <= 0) 0f else (value.toFloat() / max).coerceIn(0f, 1f)
    Spacer(
        modifier
            .fillMaxWidth()
            .height(h)
            .semantics { progressBarRangeInfo = ProgressBarRangeInfo(value.toFloat().coerceIn(0f, max.toFloat().coerceAtLeast(0f)), 0f..max.toFloat().coerceAtLeast(0f)) }
            .drawBehind {
                val r = CornerRadius(size.height / 2f)
                drawRoundRect(track, cornerRadius = r)
                if (fraction > 0f) drawRoundRect(fill, size = Size(size.width * fraction, size.height), cornerRadius = r)
                if (sun) {
                    val w = AronTokens.Stroke.Strong.toPx()
                    drawRoundRect(outline, Offset(w / 2, w / 2), Size(size.width - w, size.height - w), CornerRadius(r.x - w / 2), style = Stroke(w))
                }
            },
    )
}

/** A field card with a caption label and a numeral value (KPI tiles, totals). */
@Composable
fun AronStatCard(label: String, value: String, modifier: Modifier = Modifier, caption: String? = null) {
    val c = LocalAronColors.current
    AronCard(modifier) {
        Column(Modifier.padding(AronTokens.Space.L), verticalArrangement = Arrangement.spacedBy(AronTokens.Space.Xs)) {
            Text(label, style = MaterialTheme.typography.bodySmall, color = c.textSecondary, maxLines = 2)
            MoneyText(value, style = MaterialTheme.typography.headlineMedium)
            if (caption != null) Text(caption, style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
        }
    }
}

/**
 * The Home hero (screens-sr s2): the day's total in `type.display`, an optional meter row (label, meter, value) and a
 * meta line; the whole card is the tap target when [onClick] is given (a chevron shows it).
 */
@Composable
fun AronHeroCard(
    label: String,
    moneyText: String,
    modifier: Modifier = Modifier,
    meterLabel: String? = null,
    meterValue: Int? = null,
    meterMax: Int? = null,
    meterValueText: String? = null,
    metaText: String? = null,
    onClick: (() -> Unit)? = null,
) {
    val c = LocalAronColors.current
    AronCard(modifier, onClick = onClick) {
        Column(Modifier.fillMaxWidth().padding(AronTokens.Space.L), verticalArrangement = Arrangement.spacedBy(AronTokens.Space.S)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(label, style = MaterialTheme.typography.bodySmall, color = c.textSecondary, modifier = Modifier.weight(1f), maxLines = 2)
                if (onClick != null) Icon(AronIcons.ChevronRight, contentDescription = null, tint = c.textSecondary, modifier = Modifier.size(AronTokens.Size.Icon))
            }
            MoneyText(moneyText, Modifier.fillMaxWidth(), style = MaterialTheme.typography.displayLarge)
            if (meterValue != null && meterMax != null) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AronTokens.Space.S)) {
                    if (meterLabel != null) Text(meterLabel, style = MaterialTheme.typography.bodySmall, color = c.textSecondary, maxLines = 2)
                    AronMeter(meterValue, meterMax, Modifier.weight(1f))
                    if (meterValueText != null) Text(meterValueText, style = MaterialTheme.typography.labelMedium, color = c.textPrimary, maxLines = 1, softWrap = false)
                }
            }
            if (metaText != null) Text(metaText, style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
        }
    }
}
