package com.aktcl.aron.feature.outlet

import com.aktcl.aron.core.geo.FixManager
import com.aktcl.aron.core.geo.FixPurpose
import com.aktcl.aron.core.geo.TakenFix

/**
 * The real [LocationFixSource]: one on-demand balanced-power fix from android-geo-dpc's [FixManager] (N-021), mapped
 * field by field. [cycle] enables fix reuse inside one outlet-list cycle; a refresh (count above 0) never reuses.
 */
class FixManagerSource(private val manager: FixManager, private val cycle: () -> String? = { null }) : LocationFixSource {
    override suspend fun readFix(purpose: String, refreshCount: Int): FixReading {
        val p = FixPurpose.entries.firstOrNull { it.wire == purpose } ?: FixPurpose.VISIT_OPEN
        return manager.take(p, cycle(), refreshCount).toReading()
    }
}

internal fun TakenFix.toReading() = FixReading(
    status = fixStatus.wire, lat = lat, lng = lng, accuracyM = accuracyM, isMock = isMock, provider = provider.wire,
    fixTime = fixTime, fixElapsedRealtimeMs = fixElapsedRealtimeMs, fixAgeMs = fixAgeMs, timeToFixMs = timeToFixMs,
    requestPriority = requestPriority.wire, reused = reused, gnssJson = gnssJson,
    deviceOwner = device.deviceOwner, devOptionsEnabled = device.devOptionsEnabled, adbEnabled = device.adbEnabled,
    autoTimeEnabled = device.autoTimeEnabled, mockAppPresent = device.mockAppPresent, integrityRef = device.integrityRef,
)
