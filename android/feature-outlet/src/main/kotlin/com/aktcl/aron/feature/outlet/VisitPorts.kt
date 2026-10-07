package com.aktcl.aron.feature.outlet

import com.aktcl.aron.core.database.entity.CaptureMeta
import com.aktcl.aron.core.database.entity.GeoFixEntity
import com.aktcl.aron.core.database.entity.VisitEntity
import com.aktcl.aron.rules.GeoPolicy
import com.aktcl.aron.rules.MockPolicy
import com.aktcl.aron.rules.NoLocationPolicy

/**
 * Ports of the visit flow (F-SR-017, F-SR-019). Agreed with android-sr-b: sale screens read [VisitSession.current] and
 * never open a visit themselves. android-geo-dpc's fix manager implements [LocationFixSource]; the app shell supplies
 * the rest.
 */

/** One on-demand, balanced-power fix (docs/24 s11.1). Never a cached last-known location; never blocks on the network. */
interface LocationFixSource {
    /** Reads exactly one fix; a failure is a [FixReading] with a non-ok status, never an exception. */
    suspend fun readFix(purpose: String): FixReading
}

/** The fix and the device-integrity facts read with it; mirrors `GeoFix` and `FixDeviceState` of the contract. */
data class FixReading(
    /** Contract `FixStatus`: `ok`, `timeout`, `permission_denied`, `location_off` or `provider_unavailable`. */
    val status: String,
    val lat: Double?,
    val lng: Double?,
    val accuracyM: Double?,
    val isMock: Boolean,
    val provider: String = "fused",
    val fixTime: String? = null,
    val fixElapsedRealtimeMs: Long? = null,
    val fixAgeMs: Long? = null,
    val timeToFixMs: Long? = null,
    /** `high_accuracy` or `balanced` (contract enum). */
    val requestPriority: String? = "balanced",
    val reused: Boolean = false,
    val gnssJson: String? = null,
    val radioJson: String? = null,
    val deviceOwner: Boolean = false,
    val devOptionsEnabled: Boolean = false,
    val adbEnabled: Boolean = false,
    val autoTimeEnabled: Boolean = true,
    val mockAppPresent: Boolean = false,
    val integrityRef: String? = null,
) {
    val isOk: Boolean get() = status == "ok" && lat != null && lng != null

    fun toEntity(clientUuid: String, ownerClientUuid: String, purpose: String, refreshCount: Int) = GeoFixEntity(
        clientUuid = clientUuid, ownerClientUuid = ownerClientUuid, purpose = purpose, fixStatus = status,
        lat = lat, lng = lng, accuracyM = accuracyM, provider = provider, fixTime = fixTime,
        fixElapsedRealtimeMs = fixElapsedRealtimeMs, fixAgeMs = fixAgeMs, timeToFixMs = timeToFixMs,
        requestPriority = requestPriority, isMock = isMock, reused = reused, refreshCount = refreshCount,
        gnssJson = gnssJson, radioJson = radioJson, deviceOwner = deviceOwner, devOptionsEnabled = devOptionsEnabled,
        adbEnabled = adbEnabled, autoTimeEnabled = autoTimeEnabled, mockAppPresent = mockAppPresent, integrityRef = integrityRef,
    )
}

/** The outlet as downloaded, with its resolved radius and accuracy. */
data class VisitOutlet(
    val outletId: Long,
    val routeId: Long,
    val name: String,
    val code: String,
    val lat: Double?,
    val lng: Double?,
    /** `master`, `provisional`, `placeholder` or `none` (contract `LocationBasis`). */
    val locationBasis: String,
    val radiusM: Int,
    val maxAccuracyM: Int,
    val planned: Boolean = true,
    val visitKind: String = "sr_call",
) {
    val locationUsable: Boolean
        get() = lat != null && lng != null && (locationBasis == "master" || locationBasis == "provisional")
}

/** Settings read from the synced config (`cfg.geo.*`, `cfg.sale.*`); the shell fills them. */
data class GeoSettings(
    val policyBase: GeoPolicy,
    /** `cfg.sale.force_requires_photo`. */
    val forceRequiresPhoto: Boolean = true,
) {
    companion object {
        val DEFAULT = GeoSettings(
            GeoPolicy(
                mockPolicy = MockPolicy.SILENT_FLAG,
                noLocationPolicy = NoLocationPolicy.FORCE_SALE_REQUIRED,
                accuracyTolerant = false, refreshCount = 0, refreshMax = 3,
            ),
        )
    }
}

/** Builds the common record header (trusted time, Dhaka business date, bundle stamp) at the instant of capture. */
fun interface CaptureMetaProvider {
    fun meta(routeId: Long): CaptureMeta
}

/** Commits a visit with its fix and its outbox record in one transaction (production: `CaptureRepository.recordVisitOpen`). */
fun interface VisitCommitter {
    suspend fun commit(visit: VisitEntity, fix: GeoFixEntity)
}

/** Local, outbox-free record of the call in progress (R8): lets a kill and relaunch resume the visit. */
interface OpenVisitStore {
    /** Writes the OPEN row (client uuid, outlet, trusted start time) at once; no outbox record. Idempotent per uuid. */
    suspend fun begin(visitUuid: String, outletId: Long, routeId: Long, startedAtIso: String)

    /** The visit still OPEN on this phone for [businessDate], if any. */
    suspend fun findOpen(businessDate: String): OpenVisitRow?

    /** Removes the OPEN row when the visit was committed (the outbox record now owns it) or abandoned before a verdict. */
    suspend fun clear(visitUuid: String)

    companion object { val None = object : OpenVisitStore {
        override suspend fun begin(visitUuid: String, outletId: Long, routeId: Long, startedAtIso: String) = Unit
        override suspend fun findOpen(businessDate: String): OpenVisitRow? = null
        override suspend fun clear(visitUuid: String) = Unit
    } }
}

data class OpenVisitRow(val visitUuid: String, val outletId: Long, val routeId: Long, val startedAtIso: String)

/** F-SYS-092 (android-core implements): conditional config check on resume. Never blocks a visit; does nothing offline. */
fun interface ConfigCheck {
    suspend fun checkOnResume()
    companion object { val None = ConfigCheck { } }
}
