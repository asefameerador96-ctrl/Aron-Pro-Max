package com.aktcl.aron.feature.sale.domain

/** The three edit reasons (assumed, G-man-012: reason 1 known as wrong SKU; 2 and 3 are assumptions). Wire codes follow `cfg.memo.edit_reasons`. */
enum class EditReason(val wire: String) { WRONG_SKU("wrong_sku"), WRONG_QUANTITY("wrong_quantity"), WRONG_PRICE_TYPE("wrong_price_type") }

sealed interface EditDenied {
    /** Edit works only inside the outlet geofence: the SR must be at the shop (the server re-checks the new fix). */
    data object OutsideGeofence : EditDenied
    data object QcDone : EditDenied
    data object AlreadySuperseded : EditDenied
    data object NoLiveMemo : EditDenied
}

object EditGate {
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
