package com.aktcl.aron.feature.amo

import com.aktcl.aron.rules.Formats
import com.aktcl.aron.rules.UiLocale
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Field-unit quantities on the AMO report screens (units rule 5, lead ruling 2026-11-11): a figure that arrives with its pack factor (or as the
 * server's own Decimal3 in the row's unit) is shown in packets / boxes / dozens; one that arrives only as a base aggregate stays in base units
 * WITH its base unit word. All arithmetic is exact [BigDecimal] (never Float or Double); a conversion is `qty_base / base_per_pack` to three
 * decimals, and a column total is the sum of the CONVERTED rows (10s and 20s differ, so a category total is never divided by one factor).
 */
object FieldQty {
    /** The base unit label key of a category (stick, piece, dozen), or null for a category this phone does not know (no word is invented). */
    fun baseUnitKey(category: String): String? = when (category) { "cigarette", "bidi" -> "stick"; "lighter" -> "piece"; "match" -> "dozen"; else -> null }

    /** A non-negative Decimal3 string exactly as the server sent it (never through a float); null when absent or unreadable. */
    fun parse(s: String?): BigDecimal? = s?.trim()?.takeIf { it.isNotEmpty() }?.let { runCatching { BigDecimal(it) }.getOrNull() }?.takeIf { it.signum() >= 0 }

    /** `qty_base / base_per_pack` as Decimal3 (half up); null without a usable pack factor. */
    fun converted(qtyBase: Long, basePerPack: Long, scale: Int = 3): BigDecimal? =
        if (basePerPack <= 0L || qtyBase < 0L) null else BigDecimal.valueOf(qtyBase).divide(BigDecimal.valueOf(basePerPack), scale, RoundingMode.HALF_UP)

    /** "12.5" / "7": the Decimal3 without trailing zeros and without grouping, Latin digits (the Live Data panel prints plain figures). */
    fun plain(v: BigDecimal): String = v.setScale(3, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()

    /** "12,500" / "12.5" / "7": grouped whole part, the fraction without trailing zeros, in the user's digits. */
    fun text(v: BigDecimal, locale: UiLocale): String {
        val t = v.setScale(3, RoundingMode.HALF_UP).stripTrailingZeros()
        val whole = t.setScale(0, RoundingMode.DOWN)
        val frac = t.subtract(whole)
        val head = Formats.integer(whole.toLong(), locale)
        return if (frac.signum() == 0) head else head + "." + Formats.digits(frac.toPlainString().substringAfter('.'), locale)
    }
}
