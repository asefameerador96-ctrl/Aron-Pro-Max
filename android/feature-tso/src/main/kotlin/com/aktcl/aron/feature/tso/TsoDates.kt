package com.aktcl.aron.feature.tso

import com.aktcl.aron.core.common.AppLanguage
import com.aktcl.aron.core.common.LocaleDigits
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringArrayResource
import java.time.LocalDate

/**
 * The two date shapes of the manual (delta-tso G-27): ISO `2026-04-26` on lists and the Sales Date, the long form
 * `April 26, 2026` on plan and task screens. Under Bangla the long form is `২৬ এপ্রিল ২০২৬` (Bangla month names, Bengali digits).
 */
object TsoDates {
    /** `YYYY-MM-DD` with the language's digits; an unparseable text is returned unchanged (never a crash). */
    fun iso(date: String, language: AppLanguage): String = LocaleDigits.localize(date, language)

    /** [months] is the 12 month names of the current language (`R.array.tso_months`). */
    fun long(date: String, language: AppLanguage, months: List<String>): String {
        val d = runCatching { LocalDate.parse(date) }.getOrNull() ?: return date
        if (months.size != 12) return iso(date, language)
        return if (language == AppLanguage.BN) LocaleDigits.localize("${d.dayOfMonth} ${months[d.monthValue - 1]} ${d.year}", language)
        else "${months[d.monthValue - 1]} ${d.dayOfMonth}, ${d.year}"
    }

    @Composable
    fun long(date: String, language: AppLanguage): String = long(date, language, stringArrayResource(R.array.tso_months).toList())
}
