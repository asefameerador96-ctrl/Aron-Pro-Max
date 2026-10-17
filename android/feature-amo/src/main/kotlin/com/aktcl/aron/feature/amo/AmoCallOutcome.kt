package com.aktcl.aron.feature.amo

/** How a Control Call closes (D-AMO-CLOSE-01), decided from what the call produced. */
enum class AmoCallOutcome(val wire: String) {
    /** A memo was committed for the visit. */
    SOLD("sold"),
    /** No memo, but an audit record (assessment, price check, survey): a real call, not counted as a sale. */
    AUDIT_ONLY("zero_sale_stock_ok"),
    /** Nothing was done: does not count as a visit. */
    ABANDONED("abandoned");

    companion object {
        /** [memoLines]: line count of the visit's live memo, null when it has none; a zero-sale memo (no lines) is not a sale. */
        fun of(memoLines: Int?, hasAuditRecord: Boolean): AmoCallOutcome = when {
            memoLines != null && memoLines > 0 -> SOLD
            memoLines != null -> AUDIT_ONLY
            hasAuditRecord -> AUDIT_ONLY
            else -> ABANDONED
        }
    }
}
