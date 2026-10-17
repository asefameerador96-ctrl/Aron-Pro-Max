package com.aktcl.aron.feature.tso

import com.aktcl.aron.core.common.AppLanguage
import com.aktcl.aron.core.common.LocaleDigits
import java.time.LocalDate

/**
 * The two date shapes of the manual (delta-tso G-27): ISO `2026-04-26` on lists and the Sales Date, the long form
 * `April 26, 2026` on plan and task screens. Under Bangla the long form is `২৬ এপ্রিল ২০২৬` (Bangla month names, Bengali digits).
 */
object TsoDates {
    private val EN = listOf("January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December")
    private val BN = listOf("জানুয়ারি", "ফেব্রুয়ারি", "মার্চ", "এপ্রিল", "মে", "জুন", "জুলাই", "আগস্ট", "সেপ্টেম্বর", "অক্টোবর", "নভেম্বর", "ডিসেম্বর")

    /** `YYYY-MM-DD` with the language's digits; an unparseable text is returned unchanged (never a crash). */
    fun iso(date: String, language: AppLanguage): String = LocaleDigits.localize(date, language)

    fun long(date: String, language: AppLanguage): String {
        val d = runCatching { LocalDate.parse(date) }.getOrNull() ?: return date
        return if (language == AppLanguage.BN) LocaleDigits.localize("${d.dayOfMonth} ${BN[d.monthValue - 1]} ${d.year}", language)
        else "${EN[d.monthValue - 1]} ${d.dayOfMonth}, ${d.year}"
    }
}
