package com.aktcl.aron.core.geo

import com.aktcl.aron.core.common.WallClock
import kotlinx.coroutines.awaitCancellation

/** A controllable wall clock and elapsed realtime (independent, as on a phone whose user changes the date). */
class FakeClock(var wallMs: Long = 1_791_000_000_000L, var elapsedMs: Long = 10_000_000L) : WallClock {
    override fun nowMs(): Long = wallMs
    override fun elapsedRealtimeMs(): Long = elapsedMs
    fun advance(ms: Long) { wallMs += ms; elapsedMs += ms }
}

/** Scripted provider: counts every request; [next] decides the answer; [busyMs] is how long the provider works. */
class FakeSource(private val clock: FakeClock, var busyMs: Long = 3_000) : LocationSource {
    var requests = 0
    val priorities = mutableListOf<FixPriority>()
    val timeouts = mutableListOf<Long>()
    var next: (Int) -> SourceResult = { located() }
    var hang = false
    var throwing: Exception? = null

    fun located(lat: Double = 23.7808, lng: Double = 90.4152, accuracy: Double? = 12.0, mock: Boolean = false, provider: String = "fused") =
        SourceResult.Located(
            RawLocation(
                lat = lat, lng = lng, accuracyM = accuracy, provider = provider,
                timeMs = clock.wallMs, elapsedRealtimeNanos = clock.elapsedMs * 1_000_000L, isMock = mock,
            ),
        )

    override suspend fun currentLocation(priority: FixPriority, timeoutMs: Long): SourceResult {
        requests++
        priorities += priority
        timeouts += timeoutMs
        throwing?.let { throw it }
        if (hang) awaitCancellation()
        clock.advance(busyMs)
        return next(requests)
    }
}

class FakeAccess(var state: LocationAccessState = LocationAccessState.OK) : LocationAccess {
    var lastRequirePrecise: Boolean? = null
    override fun state(requirePrecise: Boolean): LocationAccessState { lastRequirePrecise = requirePrecise; return state }
}

val honestDevice = FixDeviceState(
    deviceOwner = true, devOptionsEnabled = false, adbEnabled = false, autoTimeEnabled = true, mockAppPresent = false,
)
