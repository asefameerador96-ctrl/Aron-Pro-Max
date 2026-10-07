package com.aktcl.aron.feature.outlet

/** The force reasons the SR can choose (`force_reason` code list); `no_outlet_location` is chosen by the app, never typed. */
enum class ForceReason(val code: String) { INTERNET_PROBLEM("internet_problem"), LOCATION_CHANGE("location_change"), NO_OUTLET_LOCATION("no_outlet_location") }

sealed interface ForceSaleResult {
    data class Started(val visit: OpenVisit, val locationRequestRaised: Boolean) : ForceSaleResult
    data class Invalid(val missing: Set<ForceSaleMissing>) : ForceSaleResult
    data object NotAvailable : ForceSaleResult
}

enum class ForceSaleMissing { REASON, PHOTO, LOCATION_PERMISSION }

/**
 * Force Sale (F-SR-018): out of range, one reason and an outlet photo let the sale proceed with `photo_validated = true`
 * and `geo_validated = false`; a location-change reason raises a location outlet request with the same photo and fix;
 * a denied location permission blocks the sale (no force without a fix attempt). Offline: nothing here calls the network.
 */
class ForceSaleController(
    private val flow: VisitFlow,
    private val capture: GeoPhotoCapture,
    private val requests: OutletRequests,
    private val locationGranted: () -> Boolean,
    private val forceRequiresPhoto: () -> Boolean = { true },
) {
    suspend fun confirm(reason: ForceReason?, outlet: VisitOutlet): ForceSaleResult {
        val st = flow.state.value
        if (st !is VisitUiState.NeedsDecision || !st.forceSaleAvailable) return ForceSaleResult.NotAvailable
        val cap = capture.state.value
        val missing = linkedSetOf<ForceSaleMissing>()
        if (!locationGranted()) missing += ForceSaleMissing.LOCATION_PERMISSION
        // A no-location outlet uses its own reason automatically.
        val effective = if (st.result.verdict == com.aktcl.aron.rules.GeoVerdict.NO_OUTLET_LOCATION) ForceReason.NO_OUTLET_LOCATION else reason
        if (effective == null) missing += ForceSaleMissing.REASON
        if (forceRequiresPhoto() && cap.photo == null) missing += ForceSaleMissing.PHOTO
        if (missing.isNotEmpty()) return ForceSaleResult.Invalid(missing)
        val after = flow.forceSale(effective!!.code, cap.photo?.photoUuid)
        if (after !is VisitUiState.Open) return ForceSaleResult.NotAvailable
        val raise = effective == ForceReason.LOCATION_CHANGE || effective == ForceReason.NO_OUTLET_LOCATION
        if (raise && cap.fix?.isOk == true && cap.photo != null) {
            requests.submit(
                OutletRequestForm(
                    kind = OutletRequestKind.LOCATION, outletId = outlet.outletId, fix = cap.fix, photoUuids = listOf(cap.photo.photoUuid),
                    originVisitUuid = after.visit.visitUuid, requestUuid = requests.newRequestUuid(),
                ),
            )
        }
        return ForceSaleResult.Started(after.visit, raise && cap.fix?.isOk == true)
    }
}
