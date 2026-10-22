package com.aktcl.aron.core.sync.device

import com.aktcl.aron.core.database.entity.CaptureMeta
import com.aktcl.aron.core.database.entity.GeoFixEntity
import com.aktcl.aron.core.geo.BreadcrumbSettings
import com.aktcl.aron.core.geo.BreadcrumbSink
import com.aktcl.aron.core.geo.TakenFix
import com.aktcl.aron.core.geo.withMdmSampling

/**
 * The breadcrumb call sites owed by docs/requests/android-geo-dpc-wiring.md (N-035), shared by SR and AMO (D-MDM-BREADCRUMB-01):
 * the settings the controller reads, and the sink that turns a kept point into a `geo_breadcrumb` outbox row.
 */
object BreadcrumbWiring {
    /**
     * `cfg.geo.breadcrumbs_enabled` / `breadcrumb_interval_min` from the bundle, over which the MDM location policy decides:
     * `off` stops them, `interval` turns them on at the policy's interval (floor 5 minutes), `on_demand` leaves the config.
     */
    fun settings(configEnabled: () -> Boolean, configIntervalMin: () -> Int, mdmMode: () -> String?, mdmIntervalMin: () -> Int?): () -> BreadcrumbSettings = {
        BreadcrumbSettings(enabled = configEnabled(), intervalMin = configIntervalMin())
            .withMdmSampling(runCatching(mdmMode).getOrNull(), runCatching(mdmIntervalMin).getOrNull())
    }

    /** The fix of a kept point as the row the record embeds; the fix is keyed to its own breadcrumb record. */
    fun fixEntity(fix: TakenFix, fixUuid: String, breadcrumbUuid: String) = GeoFixEntity(
        clientUuid = fixUuid, ownerClientUuid = breadcrumbUuid, purpose = fix.purpose.wire, fixStatus = fix.fixStatus.wire,
        lat = fix.lat, lng = fix.lng, accuracyM = fix.accuracyM, altitudeM = fix.altitudeM, verticalAccuracyM = fix.verticalAccuracyM,
        speedMps = fix.speedMps, bearingDeg = fix.bearingDeg, provider = fix.provider.wire, fixTime = fix.fixTime,
        fixElapsedRealtimeMs = fix.fixElapsedRealtimeMs, fixAgeMs = fix.fixAgeMs, timeToFixMs = fix.timeToFixMs,
        requestPriority = fix.requestPriority.wire, isMock = fix.isMock, reused = fix.reused, refreshCount = fix.refreshCount,
        gnssJson = fix.gnssJson, deviceOwner = fix.device.deviceOwner, devOptionsEnabled = fix.device.devOptionsEnabled,
        adbEnabled = fix.device.adbEnabled, autoTimeEnabled = fix.device.autoTimeEnabled, mockAppPresent = fix.device.mockAppPresent,
        integrityRef = fix.device.integrityRef,
    )

    /**
     * Stores each kept point through [write] (the capture repository of the signed-in user's database). Never throws: a point
     * that cannot be stored is dropped (the next one is a minutes away), it must never break the receiver.
     */
    fun sink(newUuid: () -> String, meta: () -> CaptureMeta?, write: (uuid: String, meta: CaptureMeta, fix: GeoFixEntity) -> Unit) = BreadcrumbSink { fix ->
        runCatching {
            val m = meta() ?: return@runCatching
            val id = newUuid()
            write(id, m, fixEntity(fix, newUuid(), id))
        }
    }
}
