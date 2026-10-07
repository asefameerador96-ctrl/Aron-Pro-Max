package com.aktcl.aron.backend.sync

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** N-028: the s11.4 GEO_GNSS_INCONSISTENT rule on single fixes (defaults 4 satellites, 1.0 dB-Hz stddev, 48 dB-Hz mean). */
class GnssRuleTest {
    private val t = GnssRule.Thresholds()

    private fun fix(provider: String = "gps", status: String = "ok", gnss: String?): JsonObject = Json.parseToJsonElement(
        """{"provider":"$provider","fix_status":"$status","lat":23.8,"lng":90.36,"gnss":${gnss ?: "null"}}""",
    ).jsonObject

    private fun gnss(used: Int, stddev: Double? = 5.2, mean: Double? = 31.4) =
        """{"window_ms":4000,"satellites_visible":22,"satellites_used":$used,"constellations_used":["GPS"],"cn0_used_stddev_dbhz":$stddev,"cn0_used_mean_dbhz":$mean}"""

    private fun reason(f: JsonObject) = GnssRule.check(f, t)?.get("reason")?.jsonPrimitive?.content

    @Test
    fun aNormalOpenSkyFixRaisesNothing() = assertNull(GnssRule.check(fix(gnss = gnss(14)), t))

    @Test
    fun anOpenSkyPositionWithZeroSatellitesIsInconsistent() = assertEquals("too_few_satellites", reason(fix(gnss = gnss(0))))

    @Test
    fun threeUsedIsTooFewFourIsEnough() {
        assertEquals("too_few_satellites", reason(fix(gnss = gnss(3))))
        assertNull(reason(fix(gnss = gnss(4))))
    }

    @Test
    fun identicalSignalStrengthAcrossSixOrMoreSatellitesIsInconsistent() {
        assertEquals("even_signal_strength", reason(fix(gnss = gnss(6, stddev = 0.2))))
        // Five used: too few to judge the spread.
        assertNull(reason(fix(gnss = gnss(5, stddev = 0.2))))
        // Exactly the minimum spread passes.
        assertNull(reason(fix(gnss = gnss(8, stddev = 1.0))))
    }

    @Test
    fun aMeanAboveFortyEightIsTooStrong() {
        assertEquals("signal_too_strong", reason(fix(gnss = gnss(10, mean = 52.0))))
        assertNull(reason(fix(gnss = gnss(10, mean = 48.0))))
    }

    @Test
    fun onlyAnOkGpsFixWithASummaryIsJudged() {
        assertNull(reason(fix(provider = "fused", gnss = gnss(0))))
        assertNull(reason(fix(provider = "network", gnss = gnss(0))))
        assertNull(reason(fix(status = "timeout", gnss = gnss(0))))
        assertNull(reason(fix(gnss = null)))
        // Missing C/N0 values are not judged on what they lack.
        assertNull(reason(fix(gnss = gnss(9, stddev = null, mean = null))))
    }

    @Test
    fun theEvidenceCarriesTheValuesAndTheThresholdsUsed() {
        val e = GnssRule.check(fix(gnss = gnss(2)), GnssRule.Thresholds(minUsed = 5))!!
        assertEquals(2, e["satellites_used"]!!.jsonPrimitive.content.toInt())
        assertEquals(5, e["min_satellites_used"]!!.jsonPrimitive.content.toInt())
    }
}
