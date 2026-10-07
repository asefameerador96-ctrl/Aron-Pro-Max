package com.aktcl.aron.core.map

import android.content.Context
import android.location.Geocoder
import android.os.Build
import com.aktcl.aron.core.common.AppLanguage
import com.aktcl.aron.rules.Geo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import kotlin.coroutines.resume

/** One reverse-geocode lookup; null when nothing is known. Implementations may throw: the resolver catches. */
fun interface GeocodeBackend {
    suspend fun reverse(lat: Double, lng: Double, language: AppLanguage): String?
}

/** The platform geocoder (no Maps key, no APK cost). Its answer needs the network; the resolver never calls it offline. */
class AndroidGeocodeBackend(private val context: Context) : GeocodeBackend {
    override suspend fun reverse(lat: Double, lng: Double, language: AppLanguage): String? {
        if (!Geocoder.isPresent()) return null
        val geocoder = Geocoder(context, Locale(language.tag, "BD"))
        val address = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            suspendCancellableCoroutine { c ->
                geocoder.getFromLocation(lat, lng, 1, object : Geocoder.GeocodeListener {
                    override fun onGeocode(addresses: MutableList<android.location.Address>) { if (c.isActive) c.resume(addresses.firstOrNull()) }
                    override fun onError(errorMessage: String?) { if (c.isActive) c.resume(null) }
                })
            }
        } else {
            @Suppress("DEPRECATION")
            withContext(Dispatchers.IO) { geocoder.getFromLocation(lat, lng, 1)?.firstOrNull() }
        }
        return address?.getAddressLine(0)?.trim()?.takeIf { it.isNotEmpty() }
    }
}

/** The last address that resolved, with its point, so an offline fix nearby can show it beside the coordinates. */
data class KnownAddress(val lat: Double, val lng: Double, val text: String)

interface LastAddressStore {
    fun read(): KnownAddress?
    fun write(value: KnownAddress)
}

class InMemoryLastAddressStore : LastAddressStore {
    @Volatile private var value: KnownAddress? = null
    override fun read() = value
    override fun write(value: KnownAddress) { this.value = value }
}

/** Per user (shared phones keep users apart), app-private, survives a kill and relaunch. */
class PrefsLastAddressStore(context: Context, userId: Long) : LastAddressStore {
    private val prefs = context.getSharedPreferences("aron_map_last_address_u$userId", Context.MODE_PRIVATE)

    override fun read(): KnownAddress? = runCatching {
        val text = prefs.getString("text", null) ?: return null
        KnownAddress(java.lang.Double.longBitsToDouble(prefs.getLong("lat", 0)), java.lang.Double.longBitsToDouble(prefs.getLong("lng", 0)), text)
    }.getOrNull()

    override fun write(value: KnownAddress) {
        prefs.edit().putLong("lat", value.lat.toRawBits()).putLong("lng", value.lng.toRawBits()).putString("text", value.text).apply()
    }
}

sealed interface AddressResult {
    /** Resolved online just now. */
    data class Resolved(val text: String) : AddressResult
    /** Not resolved (offline, timeout, no geocoder); [lastNearby] is the last resolved address within [AddressResolver.NEARBY_M]. */
    data class Fallback(val lastNearby: String?) : AddressResult
}

/**
 * F-SYS-074: the attendance address resolves online only, with the coordinates as the fallback (docs/15 row "Reverse-
 * geocoded address on Attendance": address when online, else coordinates plus the last address). Display only: the
 * attendance record keeps the coordinates and never waits for this; one lookup per call, bounded by [timeoutMs], and no
 * lookup at all while [online] is false, so offline costs neither data nor battery.
 */
class AddressResolver(
    private val online: () -> Boolean,
    private val backend: GeocodeBackend,
    private val language: () -> AppLanguage,
    private val last: LastAddressStore = InMemoryLastAddressStore(),
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
) {
    suspend fun resolve(lat: Double, lng: Double): AddressResult {
        if (!lat.isFinite() || !lng.isFinite() || lat !in -90.0..90.0 || lng !in -180.0..180.0) return AddressResult.Fallback(null)
        if (runCatching(online).getOrDefault(false)) {
            val text = try {
                withTimeoutOrNull(timeoutMs) { backend.reverse(lat, lng, language()) }
            } catch (t: CancellationException) {
                throw t
            } catch (t: Throwable) {
                null
            }?.trim()?.takeIf { it.isNotEmpty() }?.take(MAX_LEN)
            if (text != null) {
                runCatching { last.write(KnownAddress(lat, lng, text)) }
                return AddressResult.Resolved(text)
            }
        }
        val known = runCatching { last.read() }.getOrNull()
        return AddressResult.Fallback(known?.takeIf { Geo.haversineM(lat, lng, it.lat, it.lng) <= NEARBY_M }?.text)
    }

    /**
     * The text for `AttendanceFlow.addressResolver`: the address, or the coordinates with the last nearby address, or
     * null (the flow then keeps the coordinates it already shows).
     */
    suspend fun displayText(context: Context, lat: Double, lng: Double): String? = when (val r = resolve(lat, lng)) {
        is AddressResult.Resolved -> r.text
        is AddressResult.Fallback -> r.lastNearby?.let { context.getString(R.string.map_address_last_known, coordinates(lat, lng), it) }
    }

    companion object {
        const val DEFAULT_TIMEOUT_MS = 5_000L
        /** A fix this close to the last resolved point may show that address, marked as last known. */
        const val NEARBY_M = 300.0
        private const val MAX_LEN = 200

        fun coordinates(lat: Double, lng: Double): String = String.format(Locale.ROOT, "%.6f, %.6f", lat, lng)
    }
}
