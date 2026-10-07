package com.aktcl.aron.core.geo

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.BatteryManager
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority

/**
 * The one place in core-geo that requests location updates (D24-46): balanced power, interval = minimum interval =
 * `breadcrumb_interval_min`, minimum displacement, and `maxUpdateDelay` = interval so the chip batches deliveries.
 * Updates go to [BreadcrumbReceiver] through a PendingIntent, so they survive the app being killed; [stop] removes them.
 */
class AndroidBreadcrumbClient(context: Context) : BreadcrumbClient {
    private val app = context.applicationContext
    private val client by lazy { LocationServices.getFusedLocationProviderClient(app) }

    private fun pendingIntent(): PendingIntent = PendingIntent.getBroadcast(
        app, 0, Intent(app, BreadcrumbReceiver::class.java),
        // Mutable: the fused provider adds the LocationResult extras. Explicit component, so nobody else can fire it.
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
    )

    @SuppressLint("MissingPermission") // the DPC grants background location only while breadcrumbs are enabled
    override fun start(intervalMs: Long, minDisplacementM: Double) {
        val request = LocationRequest.Builder(Priority.PRIORITY_BALANCED_POWER_ACCURACY, intervalMs)
            .setMinUpdateIntervalMillis(intervalMs)
            .setMaxUpdateDelayMillis(intervalMs)
            .setMinUpdateDistanceMeters(minDisplacementM.toFloat())
            .setWaitForAccurateLocation(false)
            .build()
        client.requestLocationUpdates(request, pendingIntent())
    }

    override fun stop() {
        client.removeLocationUpdates(pendingIntent())
    }
}

/** Receives batched breadcrumb locations and hands them to the app's [BreadcrumbController]. */
class BreadcrumbReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val result = LocationResult.extractResult(intent) ?: return
        val controller = Breadcrumbs.controller ?: return // app not wired yet: drop, nothing stored
        val bm = context.getSystemService(BatteryManager::class.java)
        val pct = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)?.takeIf { it in 0..100 }
        val charging = bm?.isCharging == true
        controller.onLocations(result.locations.map { it.toRaw() }, pct, charging)
    }
}

/** Process-wide wiring: the app installs its controller at start (docs/requests/android-geo-dpc-wiring.md). */
object Breadcrumbs {
    @Volatile var controller: BreadcrumbController? = null
}

/** [BreadcrumbStateStore] in private preferences. */
class PrefsBreadcrumbStateStore(context: Context) : BreadcrumbStateStore {
    private val p = context.applicationContext.getSharedPreferences("aron-geo-breadcrumbs", Context.MODE_PRIVATE)

    @Synchronized override fun load(): BreadcrumbState = BreadcrumbState(
        dayStartElapsedMs = p.getLong("start", -1).takeIf { it >= 0 },
        lastKeptElapsedMs = p.getLong("last", -1).takeIf { it >= 0 },
        lastLat = p.getString("lat", null)?.toDoubleOrNull(),
        lastLng = p.getString("lng", null)?.toDoubleOrNull(),
        keptToday = p.getInt("kept", 0),
    )

    @Synchronized override fun save(s: BreadcrumbState) {
        p.edit().putLong("start", s.dayStartElapsedMs ?: -1).putLong("last", s.lastKeptElapsedMs ?: -1)
            .putString("lat", s.lastLat?.toString()).putString("lng", s.lastLng?.toString()).putInt("kept", s.keptToday).apply()
    }
}
