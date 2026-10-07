package com.aktcl.aron.feature.outlet

import com.aktcl.aron.core.common.ClientIds
import com.aktcl.aron.rules.TextRules

/** Request types the SR raises (contract `OutletRequestType`). */
enum class OutletRequestKind(val wire: String, val needsGeoPhoto: Boolean, val needsConfirmation: Boolean) {
    NEW("new", true, false),
    INFO("info", true, true),
    CLOSE("close", false, true),
    CLUSTER("cluster", false, true),
    LOCATION("location", true, false),
    ROUTE_ADD("route_add", false, false),
}

/** What the form holds (F-SR-037, 038, 039, 076, N-040). Strings are what the rep typed; validation normalises them. */
data class OutletRequestForm(
    val kind: OutletRequestKind,
    /** Null only for [OutletRequestKind.NEW]. */
    val outletId: Long? = null,
    val name: String? = null,
    val ownerName: String? = null,
    val mobile: String? = null,
    val clusterId: Long? = null,
    /** The outlet's current cluster, to refuse a cluster change to the same cluster. */
    val currentClusterId: Long? = null,
    val closeReasonCode: String? = null,
    val note: String? = null,
    val fix: FixReading? = null,
    val photoUuids: List<String> = emptyList(),
    val confirmed: Boolean = false,
    val originVisitUuid: String? = null,
    /** Generated once when the form opens, so a double tap or a retry carries the same uuid (idempotent). */
    val requestUuid: String? = null,
    /** Open dues in milli-taka of the outlet being closed, for the dues warning. */
    val openDueMtk: Long = 0,
)

enum class RequestError {
    DUES_BLOCK_CLOSE, OUTLET_REQUIRED, NAME_INVALID, OWNER_INVALID, MOBILE_INVALID, CLUSTER_REQUIRED, CLUSTER_UNCHANGED, REASON_REQUIRED,
    GEO_REQUIRED, PHOTO_REQUIRED, CONFIRMATION_REQUIRED, NOTE_TOO_LONG, TOO_MANY_PHOTOS,
}

/** The request as committed: the proposed facts in contract shape (mobile is always the normalised 11 digits). */
data class OutletRequestDraft(
    val requestUuid: String,
    val kind: OutletRequestKind,
    val outletId: Long?,
    val name: String?,
    val ownerName: String?,
    val contactNumber: String?,
    val clusterId: Long?,
    val closeReasonCode: String?,
    val note: String?,
    val fix: FixReading?,
    val photoUuids: List<String>,
    val originVisitUuid: String?,
)

sealed interface RequestValidation {
    data class Ok(val draft: OutletRequestDraft, val warnOpenDues: Boolean) : RequestValidation
    data class Invalid(val errors: Set<RequestError>) : RequestValidation
}

/** Commits the request and its outbox record in one transaction; works offline (REQUEST: docs/requests/android-sr-a-outlet-request-capture.md). */
fun interface OutletRequestCommitter {
    suspend fun commit(draft: OutletRequestDraft)
}

/** Outlet request forms: pure validation plus an offline save. */
class OutletRequests(
    private val committer: OutletRequestCommitter,
    private val newUuid: () -> String = ClientIds::newUuid,
    /** `cfg.outlet.close_block_if_dues`: when true a close request with open dues is refused (default: only a warning). */
    private val closeBlockIfDues: () -> Boolean = { false },
) {
    /** A fresh uuid for a form that is just opening; keep it in the form state. */
    fun newRequestUuid(): String = newUuid()

    fun validate(f: OutletRequestForm): RequestValidation {
        val e = linkedSetOf<RequestError>()
        val k = f.kind
        if (k != OutletRequestKind.NEW && f.outletId == null) e += RequestError.OUTLET_REQUIRED
        if (k == OutletRequestKind.NEW || k == OutletRequestKind.INFO) {
            val n = f.name?.trim().orEmpty(); val o = f.ownerName?.trim().orEmpty()
            if (n.length !in 2..120) e += RequestError.NAME_INVALID
            if (o.length !in 2..120) e += RequestError.OWNER_INVALID
            if (f.mobile == null || TextRules.normalisePhone(f.mobile) == null) e += RequestError.MOBILE_INVALID
        }
        if (k == OutletRequestKind.NEW && f.clusterId == null) e += RequestError.CLUSTER_REQUIRED
        if (k == OutletRequestKind.CLUSTER) {
            if (f.clusterId == null) e += RequestError.CLUSTER_REQUIRED
            else if (f.clusterId == f.currentClusterId) e += RequestError.CLUSTER_UNCHANGED
            if (f.note.isNullOrBlank()) e += RequestError.REASON_REQUIRED
        }
        if (k == OutletRequestKind.CLOSE && f.openDueMtk > 0 && closeBlockIfDues()) e += RequestError.DUES_BLOCK_CLOSE
        if (k == OutletRequestKind.ROUTE_ADD && f.note.isNullOrBlank()) e += RequestError.REASON_REQUIRED
        if (k.needsGeoPhoto) {
            if (f.fix == null || !f.fix.isOk) e += RequestError.GEO_REQUIRED
            if (f.photoUuids.isEmpty()) e += RequestError.PHOTO_REQUIRED
        }
        if (f.photoUuids.size > 4) e += RequestError.TOO_MANY_PHOTOS
        if ((f.note?.length ?: 0) > 500) e += RequestError.NOTE_TOO_LONG
        if (k.needsConfirmation && !f.confirmed) e += RequestError.CONFIRMATION_REQUIRED
        if (e.isNotEmpty()) return RequestValidation.Invalid(e)
        val draft = OutletRequestDraft(
            requestUuid = f.requestUuid ?: newUuid(), kind = k, outletId = f.outletId,
            name = f.name?.trim()?.takeIf { it.isNotEmpty() }, ownerName = f.ownerName?.trim()?.takeIf { it.isNotEmpty() },
            contactNumber = f.mobile?.let { TextRules.normalisePhone(it)?.value },
            clusterId = f.clusterId, closeReasonCode = f.closeReasonCode, note = f.note?.trim()?.takeIf { it.isNotEmpty() },
            fix = f.fix, photoUuids = f.photoUuids, originVisitUuid = f.originVisitUuid,
        )
        return RequestValidation.Ok(draft, warnOpenDues = k == OutletRequestKind.CLOSE && f.openDueMtk > 0)
    }

    /** Saves offline with no network call. Returns the draft, or the validation errors. */
    suspend fun submit(f: OutletRequestForm): RequestValidation {
        val v = validate(f)
        if (v is RequestValidation.Ok) committer.commit(v.draft)
        return v
    }
}
