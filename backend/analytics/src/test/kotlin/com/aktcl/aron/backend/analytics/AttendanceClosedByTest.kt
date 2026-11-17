package com.aktcl.aron.backend.analytics

import com.aktcl.aron.contract.Role
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Round 1.20.0 (D-SV2-15): a check-out the phone made for a forgotten day (`closed_by = 'phone'`) is "no check-out" in every attendance reader of this module:
 * `dw.fact_attendance` (and so `dw.v_attendance` and its hours), and the attendance report (`gigo`). sr001 checks in and the phone closes the day three days
 * later (no location); sr002 checks in and out by hand (the control, eight hours).
 */
class AttendanceClosedByTest : ReportFixture() {
    override val successfulLogin = false
    override val extraSql = """
INSERT INTO app.attendance_event (client_uuid, family_uuid, business_date, user_id, route_id, captured_at, config_version, kind, fix_status, fix_lat, fix_lng, fix_accuracy_m, fix_is_mock, address_display)
  SELECT gen_random_uuid(), gen_random_uuid(), DATE '2026-10-04', u.id, r.id, TIMESTAMPTZ '2026-10-04 03:00Z', 1, 'check_in', 'ok', 23.78, 90.41, 8, false, 'Banani'
    FROM app.app_user u, app.route r WHERE u.username = 'sr001' AND r.code = 'R1';
INSERT INTO app.attendance_event (client_uuid, family_uuid, business_date, user_id, route_id, captured_at, config_version, kind, fix_status, closed_by)
  SELECT gen_random_uuid(), gen_random_uuid(), DATE '2026-10-04', u.id, r.id, TIMESTAMPTZ '2026-10-07 04:00Z', 1, 'check_out', 'provider_unavailable', 'phone'
    FROM app.app_user u, app.route r WHERE u.username = 'sr001' AND r.code = 'R1';
INSERT INTO app.attendance_event (client_uuid, family_uuid, business_date, user_id, route_id, captured_at, config_version, kind, fix_status, fix_lat, fix_lng, fix_accuracy_m, fix_is_mock, address_display)
  SELECT gen_random_uuid(), gen_random_uuid(), DATE '2026-10-04', u.id, r.id, e.at, 1, e.kind, 'ok', 23.78, 90.41, 8, false, 'Gulshan'
    FROM app.app_user u, app.route r, (VALUES ('check_in', TIMESTAMPTZ '2026-10-04 03:00Z'), ('check_out', TIMESTAMPTZ '2026-10-04 11:00Z')) e(kind, at) WHERE u.username = 'sr002' AND r.code = 'R2';
    """.trimIndent()

    private fun q(sql: String): String? = fresh.db.jdbi.withHandle<String?, Exception> { it.createQuery(sql).mapTo(String::class.java).findOne().orElse(null) }

    @Test
    fun aPhoneCloseIsNoCheckOutInTheFactTheViewAndTheReport() = app {
        val w = AggregationWorker(fresh.db); w.requestRebuild(day); w.runUntilIdle()
        fun fact(user: String, col: String) = q("SELECT a.$col::text FROM dw.fact_attendance a JOIN app.app_user u ON u.id = a.user_id WHERE u.username = '$user'")
        assertEquals("2026-10-04 03:00:00+00", fact("sr001", "check_in_at"))
        assertNull(fact("sr001", "check_out_at"), "the phone's close is not a check-out time")
        assertNull(fact("sr001", "check_out_lat")); assertNull(fact("sr001", "check_out_is_mock"))
        assertEquals("2026-10-04 11:00:00+00", fact("sr002", "check_out_at"), "the rep's own check-out is unchanged")
        // The stable view: hours only when both ends are the rep's.
        fun hours(user: String) = q("SELECT v.hours_in_field::text FROM dw.v_attendance v JOIN app.app_user u ON u.id = v.user_id WHERE u.username = '$user'")
        assertNull(hours("sr001")); assertEquals("8.00", hours("sr002"))

        val rows = run(10, Role.ANALYST, "gigo").result().rows()
        val sr1 = rows.single { it.str("username") == "sr001" }; val sr2 = rows.single { it.str("username") == "sr002" }
        assertNull(sr1.str("check_out_at")); assertNull(sr1.str("hours")); assertNull(sr1.str("check_out_fix"))
        assertEquals(true, sr1.str("check_in_at") != null, "the check-in still shows")
        assertEquals(8.0, sr2.num("hours"))
        assertEquals("no check-out (closed by the phone)", sr1.str("check_out_note"), "a TSO sees why there is no check-out")
        assertNull(sr2.str("check_out_note"))
    }
}
