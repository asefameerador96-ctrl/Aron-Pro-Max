package com.aktcl.aron.core.geo.spoof

import android.content.Context

/** The previous good fix of this phone (one row), for the jump and speed checks. Written once per fresh fix. */
interface PreviousFixStore {
    fun load(): PreviousFix?
    fun save(fix: PreviousFix)
    /** Forget the stored fix (logout: the next rep on a shared phone starts with no earlier position, and the old one is not kept). */
    fun clear()

    class Memory : PreviousFixStore {
        @Volatile private var f: PreviousFix? = null
        override fun load() = f
        override fun save(fix: PreviousFix) { f = fix }
        override fun clear() { f = null }
    }
}

class PrefsPreviousFixStore(context: Context) : PreviousFixStore {
    private val prefs = context.applicationContext.getSharedPreferences("aron-geo-previous-fix", Context.MODE_PRIVATE)
    override fun load(): PreviousFix? = runCatching {
        if (!prefs.contains("lat")) return null
        PreviousFix(java.lang.Double.longBitsToDouble(prefs.getLong("lat", 0)), java.lang.Double.longBitsToDouble(prefs.getLong("lng", 0)), prefs.getLong("el", 0))
    }.getOrNull()
    override fun save(fix: PreviousFix) {
        prefs.edit().putLong("lat", java.lang.Double.doubleToRawLongBits(fix.lat)).putLong("lng", java.lang.Double.doubleToRawLongBits(fix.lng)).putLong("el", fix.elapsedMs).apply()
    }
    /** `commit()`, not `apply()`: logout must not return before the position is gone from the file. */
    override fun clear() { prefs.edit().clear().commit() }
}

/** What the fix manager asks at the moment of a fix; [None] keeps every existing caller and test unchanged. */
interface SpoofChecker {
    fun check(loc: RawLocationView?, gnssJson: String?, reused: Boolean): SpoofResult
    /** A fresh good fix was taken: remember it as the previous one. */
    fun onFreshFix(loc: com.aktcl.aron.core.geo.RawLocation)
    /** Called (suspending) before a fix is built: lets an implementation do its slow work off the caller's thread. Default: nothing. */
    suspend fun prepare() {}

    object None : SpoofChecker {
        override fun check(loc: RawLocationView?, gnssJson: String?, reused: Boolean) = SpoofResult.NONE
        override fun onFreshFix(loc: com.aktcl.aron.core.geo.RawLocation) = Unit
    }
}

/** Alias kept for readability of the port signature. */
typealias RawLocationView = com.aktcl.aron.core.geo.RawLocation

/** The production checker: facts from [AndroidSpoofProbes], satellites from the fix window's `GnssSummary`, previous fix from the store. */
class AndroidSpoofChecker(
    private val probes: AndroidSpoofProbes,
    private val previous: PreviousFixStore,
    private val clock: com.aktcl.aron.core.common.WallClock,
    private val thresholds: SpoofThresholds = SpoofThresholds(),
) : SpoofChecker {
    override fun check(loc: RawLocationView?, gnssJson: String?, reused: Boolean): SpoofResult {
        val nowElapsed = clock.elapsedRealtimeMs()
        val network = if (loc != null) probes.networkFix(nowElapsed * 1_000_000L) else null
        val result = SpoofEvaluator.evaluate(
            SpoofInput(loc, nowElapsed, clock.nowMs(), reused, gnssFacts(gnssJson), previous.load(), network, probes.facts()), thresholds,
        )
        // A refusal that rests on the cached facts (a selected mock app, a test provider) is the rep's cue to fix something and open the outlet again: the next
        // fix must see the change, not the 10-minute cache (the refusal text says "turn it off, open the outlet again").
        if (result.codes.any { it == SpoofCode.MOCK_APP_SELECTED || it == SpoofCode.TEST_PROVIDER }) probes.invalidate()
        return result
    }

    /** The package, AppOps, root-hint and `/proc/self/maps` reads are cached for 10 minutes; take them off the caller's (often the main) thread. */
    override suspend fun prepare() { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { runCatching { probes.facts() } } }

    override fun onFreshFix(loc: com.aktcl.aron.core.geo.RawLocation) {
        if (loc.elapsedRealtimeNanos > 0 && !loc.isMock) runCatching { previous.save(PreviousFix(loc.lat, loc.lng, loc.elapsedRealtimeNanos / 1_000_000L)) }
    }

    companion object {
        /** From the fix window's `GnssSummary` JSON: null when GNSS reported nothing at all (no status), else the used count. */
        fun gnssFacts(json: String?): GnssFacts? {
            if (json == null) return null
            val visible = Regex("\"satellites_visible\":(\\d+)").find(json)?.groupValues?.get(1)?.toIntOrNull() ?: return null
            if (visible == 0) return null
            val used = Regex("\"satellites_used\":(\\d+)").find(json)?.groupValues?.get(1)?.toIntOrNull() ?: return null
            return GnssFacts(used)
        }
    }
}
