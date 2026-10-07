package com.aktcl.aron.backend.masterdata

import com.aktcl.aron.backend.platform.DbServerConfig
import com.aktcl.aron.backend.platform.RegistryDefaults
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** F-SYS-013: teleport speed, zero jitter, one coordinate across a route, mock fixes and phone-versus-server disagreement, replayed from fixtures. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RiskSignalsTest {
    private val t = GeoThresholds(60, 500, 8, 2, 80, 30, 8, 10)
    private val t0 = Instant.parse("2027-01-03T04:00:00Z")
    private fun fix(i: Int, secs: Long, lat: Double, lng: Double, acc: Double? = 12.0, route: Long? = 1, mock: Boolean = false, purpose: String = "visit_open") =
        FixPoint(i.toLong(), 7, route, t0.plusSeconds(secs), lat, lng, acc, mock, purpose, "visit", UUID.randomUUID().toString())

    @Test
    fun teleportNeedsBothDistanceAndSpeed() {
        // ~1.1 km in 60 s = 66 km/h: fires. The same hop in 10 minutes = 6.6 km/h: quiet. 300 m in 5 s is fast but under the minimum distance.
        assertNotNull(RiskRules.teleport(listOf(fix(1, 0, 23.80, 90.36), fix(2, 60, 23.81, 90.36)), t))
        assertNull(RiskRules.teleport(listOf(fix(1, 0, 23.80, 90.36), fix(2, 600, 23.81, 90.36)), t))
        assertNull(RiskRules.teleport(listOf(fix(1, 0, 23.80, 90.36), fix(2, 5, 23.8027, 90.36)), t))
        // Out-of-order input is ordered by capture time first.
        assertNotNull(RiskRules.teleport(listOf(fix(2, 60, 23.81, 90.36), fix(1, 0, 23.80, 90.36)), t))
    }

    @Test
    fun zeroJitterNeedsEnoughStillPairsWithIdenticalAccuracy() {
        val still = (0 until 9).map { fix(it, it * 120L, 23.8000001 + it * 1e-7, 90.36) }
        assertNotNull(RiskRules.zeroJitter(still, t))
        // A real phone drifts: 10 m between fixes, and accuracy varies.
        val drifting = (0 until 9).map { fix(it, it * 120L, 23.80 + it * 0.0001, 90.36, acc = 10.0 + it) }
        assertNull(RiskRules.zeroJitter(drifting, t))
        assertNull(RiskRules.zeroJitter(still.take(5), t), "fewer fixes than the minimum")
    }

    @Test
    fun oneCoordinateAcrossARouteAndMockFixes() {
        val same = (0 until 10).map { fix(it, it * 600L, 23.8, 90.36 + it * 1e-6) }
        assertEquals("GEO_ROUTE_SINGLE_POINT", RiskRules.routeSinglePoint(same, t)?.code)
        val spread = (0 until 10).map { fix(it, it * 600L, 23.80 + it * 0.002, 90.36) }
        assertNull(RiskRules.routeSinglePoint(spread, t))
        assertNull(RiskRules.routeSinglePoint(same.take(5), t), "fewer visits than the minimum")
        assertEquals("GEO_MOCK", RiskRules.mock(listOf(fix(1, 0, 23.8, 90.36, mock = true)))?.code)
        assertNull(RiskRules.mock(spread))
    }

    private lateinit var env: SeededAdminDb
    @BeforeAll fun up() { env = SeededAdminDb() }
    @AfterAll fun down() = env.close()

    @Test
    fun evaluationStoresIdempotentSignalsWithWeightsAndKeepsAReviewersStatus() {
        val db = env.fresh.db
        val cfg = DbServerConfig(db, RegistryDefaults())
        val uid = env.ids.getValue("sr1001")
        val day = LocalDate.parse("2027-01-03")
        val base = Instant.parse("2027-01-03T03:00:00Z")
        db.jdbi.useHandle<Exception> { h ->
            // A teleporting phone with a mocked fix: 23.80 then 23.81 (1.1 km) 60 s later.
            listOf(Triple(0L, 23.80, false), Triple(60L, 23.81, true)).forEach { (s, lat, mock) ->
                h.createUpdate(
                    "INSERT INTO app.geo_fix (business_date, source_type, source_client_uuid, user_id, captured_at, purpose, fix_status, lat, lng, accuracy_m, provider, is_mock, reused, device_owner, dev_options_enabled, adb_enabled, auto_time_enabled, mock_app_present) " +
                        "VALUES (:d, 'visit', :u, :uid, :at, 'visit_open', 'ok', :lat, 90.36, 12, 'fused', :m, false, true, false, false, true, false)",
                ).bind("d", day).bind("u", UUID.randomUUID()).bind("uid", uid).bind("at", java.time.OffsetDateTime.ofInstant(base.plusSeconds(s), java.time.ZoneOffset.UTC)).bind("lat", lat).bind("m", mock).execute()
            }
        }
        val ev = RiskSignalEvaluator(db, cfg)
        assertEquals(2, ev.evaluateDay(day))
        fun count() = env.scalar("SELECT count(*) FROM app.risk_signal WHERE user_id = $uid AND business_date = '$day'")!!.toInt()
        assertEquals(2, count())
        assertEquals("100.00", env.scalar("SELECT score FROM app.risk_signal WHERE code = 'GEO_MOCK' AND user_id = $uid"))
        assertEquals("40.00", env.scalar("SELECT score FROM app.risk_signal WHERE code = 'GEO_TELEPORT' AND user_id = $uid"))
        db.jdbi.useHandle<Exception> { h -> h.execute("UPDATE app.risk_signal SET status = 'confirmed' WHERE code = 'GEO_TELEPORT' AND user_id = $uid") }
        assertEquals(2, ev.evaluateDay(day)); assertEquals(2, count(), "re-evaluation upserts, never duplicates")
        assertEquals("confirmed", env.scalar("SELECT status FROM app.risk_signal WHERE code = 'GEO_TELEPORT' AND user_id = $uid"))
        // A fix voided by a data void no longer counts on the next evaluation (the existing rows stay for review).
        assertTrue(env.scalar("SELECT evidence::text FROM app.risk_signal WHERE code = 'GEO_MOCK' AND user_id = $uid")!!.contains("mock_fixes"))
    }
}
