package com.aktcl.aron.feature.sale.domain

/**
 * The four reasons the SR picks after confirming a zero sale (SR manual s10, screenshot 32; D-SR-MAN-S4-03). The codes are the
 * proposal for the contract code list `zero_sale_reason` (TRAIN 2); until it lands the choice is kept in the local draft only.
 */
enum class ZeroSaleReason(val code: String) {
    STOCK_AVAILABLE("stock_available"),
    OUTLET_CLOSED("outlet_closed_during_visit"),
    OWNER_ABSENT("absence_of_owner"),
    INSUFFICIENT_CASH("insufficient_cash"),
    ;

    companion object {
        fun isKnown(code: String): Boolean = entries.any { it.code == code }
    }
}
