package com.aktcl.aron.core.geo

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import android.os.Looper
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.Granularity
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

/** Converts an Android [Location]; the mock flag uses `isMock()` on API 31+ and `isFromMockProvider()` below. */
@Suppress("DEPRECATION")
fun Location.toRaw(): RawLocation = RawLocation(
    lat = latitude,
    lng = longitude,
    accuracyM = if (hasAccuracy()) accuracy.toDouble() else null,
    altitudeM = if (hasAltitude()) altitude else null,
    verticalAccuracyM = if (Build.VERSION.SDK_INT >= 26 && hasVerticalAccuracy()) verticalAccuracyMeters.toDouble() else null,
    speedMps = if (hasSpeed()) speed.toDouble() else null,
    bearingDeg = if (hasBearing()) bearing.toDouble() else null,
    provider = provider,
    timeMs = time,
    elapsedRealtimeNanos = elapsedRealtimeNanos,
    isMock = if (Build.VERSION.SDK_INT >= 31) isMock else isFromMockProvider,
)

/**
 * The fused provider's single current location (`getCurrentLocation`), balanced power by default, `maxUpdateAge` 0 so a
 * cached last-known location is never returned, fine granularity, and the request duration as the timeout.
 */
class FusedLocationSource(context: Context) : LocationSource {
    private val app = context.applicationContext
    private val client by lazy { LocationServices.getFusedLocationProviderClient(app) }

    fun isAvailable(): Boolean =
        GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(app) == ConnectionResult.SUCCESS

    @SuppressLint("MissingPermission") // FixManager checks LocationAccess first and maps a SecurityException.
    override suspend fun currentLocation(priority: FixPriority, timeoutMs: Long): SourceResult {
        if (!isAvailable()) return SourceResult.Unavailable
        val request = CurrentLocationRequest.Builder()
            .setPriority(if (priority == FixPriority.HIGH_ACCURACY) Priority.PRIORITY_HIGH_ACCURACY else Priority.PRIORITY_BALANCED_POWER_ACCURACY)
            .setDurationMillis(timeoutMs)
            .setMaxUpdateAgeMillis(0)
            .setGranularity(Granularity.GRANULARITY_FINE)
            .build()
        val cancel = CancellationTokenSource()
        return suspendCancellableCoroutine { cont ->
            cont.invokeOnCancellation { cancel.cancel() }
            client.getCurrentLocation(request, cancel.token)
                .addOnSuccessListener { loc -> if (cont.isActive) cont.resume(loc?.let { SourceResult.Located(it.toRaw()) } ?: SourceResult.TimedOut) }
                .addOnFailureListener { e ->
                    if (!cont.isActive) return@addOnFailureListener
                    if (e is SecurityException) cont.resumeWithException(e) else cont.resume(SourceResult.Unavailable)
                }
                .addOnCanceledListener { if (cont.isActive) cont.resume(SourceResult.TimedOut) }
        }
    }
}

/**
 * The platform provider for phones without working Google Play services: one current location from GPS (high accuracy)
 * or network (balanced, falling back to GPS when network location is off), released on return.
 */
class PlatformLocationSource(context: Context) : LocationSource {
    private val app = context.applicationContext
    private val lm = app.getSystemService(LocationManager::class.java)

    @SuppressLint("MissingPermission")
    override suspend fun currentLocation(priority: FixPriority, timeoutMs: Long): SourceResult {
        val manager = lm ?: return SourceResult.Unavailable
        val enabled = runCatching { manager.getProviders(true) }.getOrDefault(emptyList())
        val provider = when {
            priority == FixPriority.BALANCED && LocationManager.NETWORK_PROVIDER in enabled -> LocationManager.NETWORK_PROVIDER
            LocationManager.GPS_PROVIDER in enabled -> LocationManager.GPS_PROVIDER
            LocationManager.NETWORK_PROVIDER in enabled -> LocationManager.NETWORK_PROVIDER
            else -> return SourceResult.Unavailable
        }
        val located = withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine<Location?> { cont ->
                if (Build.VERSION.SDK_INT >= 30) {
                    val signal = CancellationSignal()
                    cont.invokeOnCancellation { signal.cancel() }
                    manager.getCurrentLocation(provider, signal, app.mainExecutor) { loc -> if (cont.isActive) cont.resume(loc) }
                } else {
                    val listener = object : LocationListener {
                        override fun onLocationChanged(location: Location) {
                            manager.removeUpdates(this)
                            if (cont.isActive) cont.resume(location)
                        }
                    }
                    cont.invokeOnCancellation { manager.removeUpdates(listener) }
                    @Suppress("DEPRECATION")
                    manager.requestSingleUpdate(provider, listener, Looper.getMainLooper())
                }
            }
        }
        return located?.let { SourceResult.Located(it.toRaw()) } ?: SourceResult.TimedOut
    }
}

/** Fused first; the platform provider only when the fused provider is unavailable (no or broken Play services). */
class FallbackLocationSource(private val primary: LocationSource, private val secondary: LocationSource) : LocationSource {
    override suspend fun currentLocation(priority: FixPriority, timeoutMs: Long): SourceResult =
        when (val r = primary.currentLocation(priority, timeoutMs)) {
            SourceResult.Unavailable -> secondary.currentLocation(priority, timeoutMs)
            else -> r
        }

    companion object {
        fun of(context: Context): LocationSource = FallbackLocationSource(FusedLocationSource(context), PlatformLocationSource(context))
    }
}

/** Permission and location-switch checks before every request (docs/24 s11.2: either off blocks the visit screen). */
class AndroidLocationAccess(context: Context) : LocationAccess {
    private val app = context.applicationContext

    override fun state(requirePrecise: Boolean): LocationAccessState {
        val fine = app.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = app.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!fine && (requirePrecise || !coarse)) return LocationAccessState.PERMISSION_DENIED
        val lm = app.getSystemService(LocationManager::class.java) ?: return LocationAccessState.LOCATION_OFF
        val on = if (Build.VERSION.SDK_INT >= 28) lm.isLocationEnabled
        else runCatching { lm.isProviderEnabled(LocationManager.GPS_PROVIDER) || lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) }.getOrDefault(false)
        return if (on) LocationAccessState.OK else LocationAccessState.LOCATION_OFF
    }
}
