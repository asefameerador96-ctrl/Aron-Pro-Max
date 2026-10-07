package com.aktcl.aron.backend.analytics

import com.aktcl.aron.contract.Role
import kotlin.test.Test
import kotlin.test.assertEquals

/** F-API-034: flagged visits with review events, scoped; body equals the seeded signals. */
class SuspiciousLocationTest : ReportFixture() {
    override val extraSql = """
        INSERT INTO app.risk_signal (code, severity, business_date, subject_type, subject_id, user_id, route_id, zone_id, score, config_version)
          SELECT 'GEO_MOCK', 4, DATE '2026-10-04', 'visit', '00000000-0000-4000-8000-000000000003', u.id, r.id, r.zone_id, 100, 1 FROM app.app_user u, app.route r WHERE u.username = 'sr001' AND r.code = 'R1'
          UNION ALL SELECT 'GEO_PERFECT_ACCURACY', 2, DATE '2026-10-04', 'user', u.id::text, u.id, r.id, r.zone_id, 20, 1 FROM app.app_user u, app.route r WHERE u.username = 'sr002' AND r.code = 'R2'
          UNION ALL SELECT 'GEO_OUT_OF_BOUNDS', 3, DATE '2026-10-04', 'user', u.id::text, u.id, r.id, r.zone_id, 40, 1 FROM app.app_user u, app.route r WHERE u.username = 'sr003' AND r.code = 'R3';
        INSERT INTO app.risk_signal_review (client_uuid, family_uuid, business_date, user_id, captured_at, config_version, signal_id, action, note, source)
          SELECT gen_random_uuid(), gen_random_uuid(), DATE '2026-10-04', u.id, TIMESTAMPTZ '2026-10-04 09:00Z', 1, s.id, 'confirmed', 'Mock app found on the phone', 'record'
            FROM app.risk_signal s, app.app_user u WHERE s.code = 'GEO_MOCK' AND u.username = 'sr001';
    """.trimIndent()

    @Test
    fun flaggedVisitsCarryTheirReviewEventsAndTheUserDayVerdict() = app {
        val o = run(10, Role.ANALYST, "suspicious-location").result()
        assertEquals(3, o.rows().size)
        val mock = o.rows().by("code", "GEO_MOCK")
        assertEquals("sr001", mock.str("username")); assertEquals("R1", mock.str("route_code")); assertEquals("O3", mock.str("outlet_code"))   // the mocked visit was at O3
        assertEquals(100.0, mock.num("score")); assertEquals(1, mock.num("reviews").toInt()); assertEquals("confirmed", mock.str("last_review_action")); assertEquals("Mock app found on the phone", mock.str("last_review_note"))
        assertEquals("true", mock.str("suspicious"))
        // One weight-20 signal alone does not make a suspicious user-day (threshold 50); 40 does not either.
        assertEquals("false", o.rows().by("code", "GEO_PERFECT_ACCURACY").str("suspicious")); assertEquals(20.0, o.rows().by("code", "GEO_PERFECT_ACCURACY").num("user_day_score"))
        assertEquals("false", o.rows().by("code", "GEO_OUT_OF_BOUNDS").str("suspicious"))
        // Reach: zone 2's TSO sees only zone 2's signal; zone 1's TSO sees the other two.
        assertEquals(listOf("GEO_OUT_OF_BOUNDS"), run(14, Role.TSO, "suspicious-location").result().rows().map { it.str("code")!! })
        assertEquals(setOf("GEO_MOCK", "GEO_PERFECT_ACCURACY"), run(11, Role.TSO, "suspicious-location").result().rows().map { it.str("code")!! }.toSet())
        assertEquals(0, run(10, Role.ANALYST, "suspicious-location", """{"period":{"date":"2026-10-05"},"output":{"format":"json"}}""").result().rows().size)
    }
}
