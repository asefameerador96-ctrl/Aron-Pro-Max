package com.aktcl.aron.feature.sale.domain

/**
 * The edit reasons. [WRONG_SKU], [WRONG_SKU_QTY] and [PROMOTION_ISSUE] are the owner's manual (shot 37, SRM-B-15); the other two are the earlier
 * assumptions, kept so a stored draft or the AMO still restores. [PROMOTION_ISSUE] is only a reason label, not a programme (docs/27).
 * Wire codes follow `cfg.memo.edit_reasons`; the SV lane seeds `wrong_sku_qty` and `promotion_issue` in the `edit_reason` code list.
 */
enum class EditReason(val wire: String) {
    WRONG_SKU("wrong_sku"), WRONG_QUANTITY("wrong_quantity"), WRONG_PRICE_TYPE("wrong_price_type"),
    WRONG_SKU_QTY("wrong_sku_qty"), PROMOTION_ISSUE("promotion_issue");

    companion object {
        /** The SR's three reasons, in the manual's order. */
        val manual: List<EditReason> = listOf(WRONG_SKU, WRONG_SKU_QTY, PROMOTION_ISSUE)

        /** The AMO's list until its own manual rows say otherwise. */
        val legacy: List<EditReason> = listOf(WRONG_SKU, WRONG_QUANTITY, WRONG_PRICE_TYPE)
    }
}

sealed interface EditDenied {
    /** Edit works only inside the outlet geofence: the SR must be at the shop (the server re-checks the new fix). */
    data object OutsideGeofence : EditDenied
    data object QcDone : EditDenied
    data object AlreadySuperseded : EditDenied
    data object NoLiveMemo : EditDenied
}

object EditGate {
    /**
     * SRM-B-17: QC "has been entered for the outlet" when any QC line of the business date belongs to a visit of that outlet,
     * whichever visit it was recorded in. [qcVisitOutletIds] is the outlet of the visit of each QC line of the date (null = unknown visit).
     */
    fun qcEnteredForOutlet(outletId: Long, qcVisitOutletIds: List<Long?>): Boolean = qcVisitOutletIds.any { it == outletId }

    /**
     * Whether a memo may be edited now (F-SR-033): inside the geofence with a fresh fix, before QC at the outlet, and only the
     * live memo of the chain (an already superseded memo is never edited again).
     */
    fun check(inGeofenceNow: Boolean, qcCompleted: Boolean, memoIsLive: Boolean, memoExists: Boolean = true): EditDenied? = when {
        !memoExists -> EditDenied.NoLiveMemo
        !memoIsLive -> EditDenied.AlreadySuperseded
        qcCompleted -> EditDenied.QcDone
        !inGeofenceNow -> EditDenied.OutsideGeofence
        else -> null
    }

    /**
     * The edit starts from the old memo's lines: pre-filled quantities, the outlet read-only. The due moves to the new
     * memo (the old one is superseded), so the new memo's paid amount starts from what was already paid on the old one.
     */
    fun prefill(old: SaleDraft, oldPaidMtk: Long, newMemoUuid: String, reason: EditReason): SaleDraft =
        old.copy(memoUuid = newMemoUuid, edit = EditContext(old.memoUuid, reason.wire), paidMtk = oldPaidMtk.takeIf { it > 0 })
}
