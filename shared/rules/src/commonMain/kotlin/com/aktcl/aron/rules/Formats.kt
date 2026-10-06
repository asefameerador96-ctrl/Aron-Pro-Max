package com.aktcl.aron.rules

import kotlinx.datetime.LocalDate

/** Display locale of the apps and the web: Bangla (Bengali digits) or English (Latin digits). */
enum class UiLocale { BN, EN }

/** The one formatting profile (F-SYS-051): Western grouping in both locales, Bengali digits only in Bangla, taka sign after the amount. */
object Formats {
    /** The taka sign (U+09F3 is the Bengali rupee mark; the field uses U+09F3 "৳" as printed on the memo). */
    const val TAKA_SIGN: String = "৳"
    private const val BN_ZERO = '০'

    /** Replaces ASCII digits with Bengali digits when [locale] is BN; other characters are untouched. */
    fun digits(s: String, locale: UiLocale): String =
        if (locale == UiLocale.EN) s else s.map { if (it in '0'..'9') BN_ZERO + (it - '0') else it }.joinToString("")

    /** Integer with `,` thousands grouping (Western, never lakh), e.g. 1234567 -> "1,234,567" / "১,২৩৪,৫৬৭". */
    fun integer(n: Long, locale: UiLocale): String = digits(Money.formatTaka(n * Money.MTK_PER_TAKA, 0), locale)

    /** Money: mtk rendered as `1,234.50 ` + taka sign (2 decimals by default, 3 for unit prices), digits per [locale]. */
    fun money(mtk: Long, locale: UiLocale, decimals: Int = 2): String = digits(Money.formatTaka(mtk, decimals), locale) + " " + TAKA_SIGN

    /** Date as `dd/MM/yyyy`, digits per [locale]. */
    fun date(d: LocalDate, locale: UiLocale): String =
        digits(d.dayOfMonth.toString().padStart(2, '0') + "/" + d.monthNumber.toString().padStart(2, '0') + "/" + d.year.toString().padStart(4, '0'), locale)

    /** Dhaka wall time of a UTC instant as 24-hour `HH:mm`, digits per [locale]. */
    fun time(epochMs: Long, locale: UiLocale): String {
        val minuteOfDay = ((epochMs + BusinessDate.DHAKA_OFFSET_MS).floorMod(86_400_000L) / 60_000L).toInt()
        return digits((minuteOfDay / 60).toString().padStart(2, '0') + ":" + (minuteOfDay % 60).toString().padStart(2, '0'), locale)
    }

    private fun Long.floorMod(m: Long): Long = ((this % m) + m) % m
}
