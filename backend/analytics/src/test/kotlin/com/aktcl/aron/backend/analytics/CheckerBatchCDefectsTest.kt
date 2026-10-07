package com.aktcl.aron.backend.analytics

import com.aktcl.aron.contract.Role
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

/** Independent checker (N-052, F-API-034): the suspicious-location verdict must follow cfg.geo.suspicious_score_threshold like the dashboards do. */
class CheckerSuspiciousThresholdTest : ReportFixture() {
    override val configOverrides: Map<String, JsonElement> = mapOf("cfg.geo.suspicious_score_threshold" to JsonPrimitive(30))
    override val extraSql = """
        INSERT INTO app.risk_signal (code, severity, business_date, subject_type, subject_id, user_id, route_id, zone_id, score, config_version)
          SELECT 'GEO_OUT_OF_BOUNDS', 3, DATE '2026-10-04', 'user', u.id::text, u.id, r.id, r.zone_id, 40, 1 FROM app.app_user u, app.route r WHERE u.username = 'sr003' AND r.code = 'R3';
    """.trimIndent()

    @Test
    fun suspiciousFlagUsesTheConfiguredThresholdNotTheHardcodedFifty() = app {
        // score 40 >= configured threshold 30: the user-day is suspicious (aggregator/dashboards read the config key; the report hardcodes 50).
        val row = run(10, Role.ANALYST, "suspicious-location").result().rows().single()
        assertEquals("true", row.str("suspicious"))
    }
}

/** Independent checker (F-SYS-069): an admin data-voided memo number was consumed, so it is not a gap. */
class CheckerMemoGapsAdminVoidTest : ReportFixture() {
    // Seed series of sr001 is 001..004. An admin data-void tombstones 002 (voided_at set).
    override val extraSql = """
        ALTER TABLE app.memo DISABLE TRIGGER USER;
        UPDATE app.memo SET voided_at = TIMESTAMPTZ '2026-10-05 00:00Z' WHERE memo_no = 'sr001-261004-002';
        ALTER TABLE app.memo ENABLE TRIGGER USER;
    """.trimIndent()

    @Test
    fun anAdminVoidedMemoNumberIsNotReportedAsMissing() = app {
        val rows = run(10, Role.ANALYST, "memo-number-gaps").result().rows()
        assertEquals(emptyList(), rows.map { it.str("memo_no") })
    }
}

/** Independent checker (F-API-017a): scope must not manufacture gaps from a partly visible series. */
class CheckerMemoGapsPartialScopeTest : ReportFixture() {
    // sr001's complete series 001..005; 005 was sold on R3 (zone 2). Nothing is missing.
    override val extraSql = """
        SELECT pg_temp.memo('00000000-0000-4000-8000-0000000000c5', '00000000-0000-4000-8000-000000000005', 'sr001', 'R3', 'O5', 'sr001-261004-005', TIMESTAMPTZ '2026-10-04 08:00Z', 0, 0, 0, 'active');
    """.trimIndent()

    @Test
    fun aCallerWhoSeesOnlyPartOfASeriesIsNotShownFalseGaps() = app {
        assertEquals(0, run(10, Role.ANALYST, "memo-number-gaps").result().rows().size)    // national: complete series, no gaps
        // zone 2 only holds 005: numbers 001..004 exist (in zone 1) and must not be reported missing to this caller.
        assertEquals(0, run(14, Role.TSO, "memo-number-gaps").result().rows().size)
    }
}
