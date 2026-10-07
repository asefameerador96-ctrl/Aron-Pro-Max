package com.aktcl.aron.core.printing

import android.content.Context
import android.content.res.Configuration
import com.aktcl.aron.core.common.AppLanguage
import java.util.Locale

/**
 * Paper labels from this module's string resources (values and values-bn) in [language], read through a
 * configuration context so printing never changes the app's own locale. The explicit id map keeps the
 * resources referenced (R8 resource shrinking) and is checked against strings.xml by `PrintLabelsTest`.
 */
class AndroidPrintLabels(context: Context, language: AppLanguage) : PrintLabels {
    private val res = context.createConfigurationContext(
        Configuration(context.resources.configuration).apply {
            setLocale(if (language == AppLanguage.BN) Locale.forLanguageTag("bn") else Locale.ENGLISH)
        },
    ).resources

    override fun label(key: String): String? = IDS[key]?.let { res.getString(it) }

    companion object {
        val IDS: Map<String, Int> = mapOf(
        "print_company" to R.string.print_company,
        "print_cash_memo" to R.string.print_cash_memo,
        "print_credit_memo" to R.string.print_credit_memo,
        "print_offer_memo" to R.string.print_offer_memo,
        "print_drp_memo" to R.string.print_drp_memo,
        "print_zero_memo" to R.string.print_zero_memo,
        "print_edited_memo" to R.string.print_edited_memo,
        "print_duplicate" to R.string.print_duplicate,
        "print_reprint" to R.string.print_reprint,
        "print_supersedes" to R.string.print_supersedes,
        "print_memo_no" to R.string.print_memo_no,
        "print_date" to R.string.print_date,
        "print_time" to R.string.print_time,
        "print_outlet" to R.string.print_outlet,
        "print_sr" to R.string.print_sr,
        "print_route" to R.string.print_route,
        "print_distributor" to R.string.print_distributor,
        "print_sku" to R.string.print_sku,
        "print_qty" to R.string.print_qty,
        "print_price" to R.string.print_price,
        "print_value" to R.string.print_value,
        "print_total" to R.string.print_total,
        "print_discount" to R.string.print_discount,
        "print_total_discount" to R.string.print_total_discount,
        "print_total_qc" to R.string.print_total_qc,
        "print_rounding" to R.string.print_rounding,
        "print_grand_total" to R.string.print_grand_total,
        "print_paid" to R.string.print_paid,
        "print_due" to R.string.print_due,
        "print_thanks" to R.string.print_thanks,
        "print_stock_slip" to R.string.print_stock_slip,
        "print_category" to R.string.print_category,
        "print_day_summary" to R.string.print_day_summary,
        "print_memo_count" to R.string.print_memo_count,
        "print_void_slip" to R.string.print_void_slip,
        "print_voided_memo" to R.string.print_voided_memo,
        "print_reason" to R.string.print_reason,
        "print_amount" to R.string.print_amount,
        "print_due_receipt" to R.string.print_due_receipt,
        "print_due_before" to R.string.print_due_before,
        "print_collected" to R.string.print_collected,
        "print_due_after" to R.string.print_due_after,
        "print_signature" to R.string.print_signature,
        )
    }
}
