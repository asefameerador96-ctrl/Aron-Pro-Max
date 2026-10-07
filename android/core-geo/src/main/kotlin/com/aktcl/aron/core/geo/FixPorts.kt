package com.aktcl.aron.core.geo

/** What one on-demand provider request produced. */
sealed interface SourceResult {
    data class Located(val location: RawLocation) : SourceResult

    /** No location inside the timeout (the provider answered null or the request was cut off). */
    data object TimedOut : SourceResult

    /** The provider failed (Play services missing or broken, no provider enabled). */
    data object Unavailable : SourceResult
}

/**
 * One current location on demand (docs/24 s5.5): never a stream, never a cached last-known location. Implementations
 * must release every listener before returning and honour coroutine cancellation.
 */
interface LocationSource {
    suspend fun currentLocation(priority: FixPriority, timeoutMs: Long): SourceResult
}

/** Whether a fix can be requested at all, checked before every provider request. */
enum class LocationAccessState { OK, PERMISSION_DENIED, LOCATION_OFF }

interface LocationAccess {
    /** [requirePrecise] (`cfg.geo.require_precise`): approximate-only permission counts as denied. */
    fun state(requirePrecise: Boolean): LocationAccessState
}

/** Reads `FixDeviceState` at the moment of a fix. Must be cheap: it runs once per fix. */
fun interface DeviceStateReader {
    fun read(): FixDeviceState
}

/**
 * Hook for GNSS evidence collected only while a provider request is open (docs/24 s5.5, N-025). [open] registers the
 * callbacks; [FixWindow.close] unregisters them and returns the `GnssSummary` JSON or null.
 */
fun interface FixWindowObserver {
    fun open(): FixWindow

    companion object {
        val None: FixWindowObserver = FixWindowObserver { FixWindow { null } }
    }
}

fun interface FixWindow {
    fun close(): String?
}

/**
 * Per business date counters for `DeviceDayTelemetry.gps` (fixes requested from the provider) and `gps_ms` (time the
 * provider was busy). Reused fixes and fixes refused before the provider (permission, location off) are not counted.
 */
interface FixLedger {
    fun recordProviderRequest(businessDate: String, busyMs: Long)
    fun fixes(businessDate: String): Int
    fun busyMs(businessDate: String): Long
}

/** In-memory ledger (tests, and the fallback when no persistent ledger is wired). */
class MemoryFixLedger : FixLedger {
    private val counts = HashMap<String, Pair<Int, Long>>()

    @Synchronized
    override fun recordProviderRequest(businessDate: String, busyMs: Long) {
        val (n, ms) = counts[businessDate] ?: (0 to 0L)
        counts[businessDate] = (n + 1) to (ms + busyMs.coerceAtLeast(0))
    }

    @Synchronized override fun fixes(businessDate: String): Int = counts[businessDate]?.first ?: 0
    @Synchronized override fun busyMs(businessDate: String): Long = counts[businessDate]?.second ?: 0L
}

/**
 * The `cfg.geo.*` values the fix manager reads (docs/24 s9.5), coerced into their registry ranges so a bad config value
 * can never produce a stream of fixes or an endless wait.
 */
data class FixSettings(
    /** `cfg.geo.fix_timeout_s`: 5..30, default 15. */
    val timeoutS: Int = 15,
    /** `cfg.geo.fix_accuracy_mode`. */
    val priority: FixPriority = FixPriority.BALANCED,
    /** `cfg.geo.fix_reuse_max_age_s`: 0..300, default 60; 0 disables reuse. */
    val reuseMaxAgeS: Int = 60,
    /** `cfg.geo.require_precise`. */
    val requirePrecise: Boolean = true,
) {
    val timeoutMs: Long get() = timeoutS.coerceIn(5, 30) * 1_000L
    val reuseMaxAgeMs: Long get() = reuseMaxAgeS.coerceIn(0, 300) * 1_000L
}
