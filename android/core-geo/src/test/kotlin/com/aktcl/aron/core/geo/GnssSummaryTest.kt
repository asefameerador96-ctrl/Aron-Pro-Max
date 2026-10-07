package com.aktcl.aron.core.geo

import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.snakeyaml.engine.v2.api.Load
import org.snakeyaml.engine.v2.api.LoadSettings

class GnssSummaryTest {
    @Suppress("UNCHECKED_CAST")
    private fun parse(json: String): Map<String, Any?> = Load(LoadSettings.builder().build()).loadFromString(json) as Map<String, Any?>

    @Suppress("UNCHECKED_CAST")
    private val contract: Map<String, Any?> by lazy {
        val root = File(System.getProperty("aron.openapi")!!).inputStream().use { Load(LoadSettings.builder().build()).loadFromInputStream(it) } as Map<String, Any?>
        ((root["components"] as Map<String, Any?>)["schemas"] as Map<String, Any?>)["GnssSummary"] as Map<String, Any?>
    }

    private fun sat(c: Int, cn0: Double, used: Boolean = true, eph: Boolean = true) = SatelliteSample(c, cn0, used, eph)

    @Test fun aRealSkyGivesCountsConstellationsAndCn0Statistics() {
        val acc = GnssAccumulator(1_000, rawSupported = true)
        acc.onStatus(listOf(sat(1, 20.0)))
        acc.onStatus(listOf(sat(1, 31.0), sat(1, 22.0), sat(6, 35.0), sat(3, 27.0), sat(5, 18.0, used = false, eph = false)))
        acc.onMeasurements(12, listOf(1.5, 2.5))
        acc.onMeasurements(10, emptyList())
        val s = parse(acc.summaryJson(4_200))
        assertEquals(3_200, s["window_ms"])
        assertEquals(5, s["satellites_visible"])
        assertEquals(4, s["satellites_used"])
        assertEquals(listOf("GPS", "GLONASS", "GALILEO"), s["constellations_used"])
        assertEquals(28.75, s["cn0_used_mean_dbhz"])
        assertEquals(35.0, s["cn0_used_max_dbhz"])
        assertEquals(4.82, s["cn0_used_stddev_dbhz"])
        assertEquals(26.6, s["cn0_all_mean_dbhz"])
        assertEquals(0.8, s["ephemeris_share"])
        assertEquals(true, s["raw_supported"])
        assertEquals(22, s["raw_measurement_count"])
        assertEquals(2.0, s["agc_db_mean"])
    }

    @Test fun noGnssDuringTheWindowIsAnExplicitZeroAndNotSupportedIsAFlag() {
        val s = parse(GnssAccumulator(0, rawSupported = false).summaryJson(15_000))
        assertEquals(0, s["satellites_visible"]); assertEquals(0, s["satellites_used"])
        assertEquals(emptyList<String>(), s["constellations_used"])
        assertEquals(false, s["raw_supported"])
        assertEquals(null, s["raw_measurement_count"])
        assertEquals(null, s["cn0_used_mean_dbhz"])
    }

    @Test fun everyMemberIsInTheContractAndRequiredOnesArePresent() {
        val s = parse(GnssAccumulator(0, true).summaryJson(70_000))
        @Suppress("UNCHECKED_CAST") val props = (contract["properties"] as Map<String, Any?>).keys
        @Suppress("UNCHECKED_CAST") val required = (contract["required"] as List<String>).toSet()
        assertEquals(props, s.keys)
        assertTrue(s.keys.containsAll(required))
        assertEquals(60_000, s["window_ms"]) // clamped to the contract maximum
    }

    @Test fun simulatorLikeFlatCn0IsVisibleInTheStatistics() {
        val acc = GnssAccumulator(0, true)
        acc.onStatus((1..8).map { sat(1, 45.0) })
        val s = parse(acc.summaryJson(1_000))
        assertEquals(0.0, s["cn0_used_stddev_dbhz"])
        assertEquals(45.0, s["cn0_used_mean_dbhz"])
    }

    @Test fun constellationCodesMatchTheContractEnum() {
        val names = (0..8).map { GnssAccumulator.constellationName(it) }.toSet()
        assertEquals(setOf("GPS", "GLONASS", "GALILEO", "BEIDOU", "QZSS", "IRNSS", "SBAS", "UNKNOWN"), names)
    }

    @Test fun theFixCarriesTheWindowSummary() = runTest {
        val clock = FakeClock()
        val observer = FixWindowObserver {
            val acc = GnssAccumulator(clock.elapsedMs, true)
            acc.onStatus(listOf(sat(1, 30.0), sat(1, 25.0)))
            FixWindow { acc.summaryJson(clock.elapsedMs) }
        }
        val m = FixManager(FakeSource(clock), FakeAccess(), { honestDevice }, clock, gnss = observer)
        val f = m.take(FixPurpose.VISIT_OPEN)
        assertEquals(2, parse(f.gnssJson!!)["satellites_used"])
        assertEquals(3_000, parse(f.gnssJson!!)["window_ms"])
    }

    @Test fun unknownCapabilityIsLearnedFromTheWindow() {
        assertEquals(false, parse(GnssAccumulator(0, null).summaryJson(1_000))["raw_supported"])
        val seen = GnssAccumulator(0, null).apply { onMeasurements(5, emptyList()) }
        assertEquals(true, parse(seen.summaryJson(1_000))["raw_supported"])
        assertEquals(5, parse(seen.summaryJson(1_000))["raw_measurement_count"])
        val refused = GnssAccumulator(0, null).apply { markRawSupported(false) }
        assertEquals(false, parse(refused.summaryJson(1_000))["raw_supported"])
    }
}
