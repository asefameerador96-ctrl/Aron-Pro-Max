package com.aktcl.aron.backend.analytics

import kotlin.test.Test
import kotlin.test.assertEquals

/** F-SYS-096 (the facts the dw views read): attendance, geo fixes and the device-day of the seeded day land in `dw` and a second run changes nothing. */
class DeviceFactsTest : ReportFixture() {
    override val extraSql = """
INSERT INTO app.app_user (username, full_name, role, must_change_password) VALUES ('amo001', 'AMO One', 'AMO', false);
INSERT INTO app.attendance_event (client_uuid, family_uuid, business_date, user_id, route_id, captured_at, config_version, kind, fix_status, fix_lat, fix_lng, fix_accuracy_m, fix_is_mock, address_display)
  SELECT gen_random_uuid(), gen_random_uuid(), DATE '2026-10-04', u.id, r.id, e.at, 1, e.kind, 'ok', 23.78, 90.41, 8, false, 'Banani'
    FROM app.app_user u, app.route r, (VALUES ('check_in', TIMESTAMPTZ '2026-10-04 03:00Z'), ('check_out', TIMESTAMPTZ '2026-10-04 11:00Z')) e(kind, at) WHERE u.username = 'sr001' AND r.code = 'R1';
INSERT INTO app.device (device_uuid, flavour, app_package, status, device_owner, lockdown_level, public_key_jwk, public_key_thumbprint, app_signing_cert_sha256, app_version)
  VALUES (gen_random_uuid(), 'sr', 'com.aktcl.aron.sr', 'active', true, 'dev', '{}'::jsonb, 'thumb-x', decode(repeat('ab', 32), 'hex'), '1.0.0+1');
INSERT INTO app.sync_batch (device_id, batch_uuid, user_id, fingerprint, record_count, received_at, expires_at)
  SELECT (SELECT id FROM app.device LIMIT 1), gen_random_uuid(), u.id, decode(repeat('cd', 32), 'hex'), b.n, b.at, now() + interval '1 day'
    FROM app.app_user u, (VALUES ('sr001', 10, TIMESTAMPTZ '2026-10-04 05:00Z'), ('sr001', 5, TIMESTAMPTZ '2026-10-04 06:00Z'), ('sr002', 3, TIMESTAMPTZ '2026-10-04 05:30Z')) b(un, n, at) WHERE u.username = b.un;
SELECT pg_temp.line('00000000-0000-4000-8000-0000000000a1', 2, 'SKU2', 2, 0, 'free_sample');
INSERT INTO app.memo_discount (client_uuid, family_uuid, business_date, user_id, route_id, captured_at, config_version, memo_client_uuid, kind, sku_id, qty_base, value_mtk, line_no)
  SELECT gen_random_uuid(), gen_random_uuid(), DATE '2026-10-04', m.user_id, m.route_id, m.captured_at, 1, m.client_uuid, 'drp', s.id, 1, 500, 1 FROM app.memo m, app.sku s WHERE m.client_uuid = '00000000-0000-4000-8000-0000000000a1' AND s.code = 'SKU1';
INSERT INTO app.route_assignment (route_id, user_id, kind, valid_from) SELECT r.id, u.id, 'primary', DATE '2026-01-01' FROM app.route r, app.app_user u WHERE r.code = 'R1' AND u.username = 'sr001';
INSERT INTO app.due_ledger (outlet_id, business_date, entry_kind, amount_mtk, memo_client_uuid, memo_no, user_id, source_client_uuid)
  SELECT o.id, DATE '2026-10-04', k.kind, k.amt, '00000000-0000-4000-8000-0000000000a2', 'sr001-261004-002', u.id, gen_random_uuid() FROM app.outlet o, app.app_user u, (VALUES ('memo_due', 8000), ('collection', -3000)) k(kind, amt) WHERE o.code = 'O2' AND u.username = 'sr001';
SELECT pg_temp.visit('00000000-0000-4000-8000-0000000000c9', 'amo001', 'R1', 'O1', 1, TIMESTAMPTZ '2026-10-04 09:00Z', 'in_range', 'sale_allowed', false, 'closed');
ALTER TABLE app.visit DISABLE TRIGGER USER;
UPDATE app.visit SET visit_kind = 'amo_control_call', planned = false WHERE client_uuid = '00000000-0000-4000-8000-0000000000c9';
ALTER TABLE app.visit ENABLE TRIGGER USER;
INSERT INTO app.geo_fix (business_date, source_type, source_client_uuid, user_id, route_id, captured_at, purpose, fix_status, lat, lng, accuracy_m, provider, is_mock, reused, device_owner, dev_options_enabled, adb_enabled, auto_time_enabled, mock_app_present, satellites_used)
  SELECT DATE '2026-10-04', 'visit', '00000000-0000-4000-8000-000000000001', u.id, r.id, TIMESTAMPTZ '2026-10-04 04:00Z', 'visit_open', 'ok', 23.79, 90.4, 9, 'gps', false, false, true, false, false, true, false, 7 FROM app.app_user u, app.route r WHERE u.username = 'sr001' AND r.code = 'R1';
    """.trimIndent()

    private fun q(sql: String): String = fresh.db.jdbi.withHandle<String, Exception> { it.createQuery(sql).mapTo(String::class.java).one() }

    @Test
    fun attendanceGeoFixAndDeviceDayLandInDw() {
        app {
            assertEquals("2026-10-04T03:00:00Z", q("SELECT to_char(check_in_at AT TIME ZONE 'UTC', 'YYYY-MM-DD\"T\"HH24:MI:SS\"Z\"') FROM dw.fact_attendance a JOIN app.app_user u ON u.id = a.user_id WHERE u.username = 'sr001'"))
            assertEquals("2026-10-04T11:00:00Z", q("SELECT to_char(check_out_at AT TIME ZONE 'UTC', 'YYYY-MM-DD\"T\"HH24:MI:SS\"Z\"') FROM dw.fact_attendance a JOIN app.app_user u ON u.id = a.user_id WHERE u.username = 'sr001'"))
            assertEquals("SR", q("SELECT a.role FROM dw.fact_attendance a JOIN app.app_user u ON u.id = a.user_id WHERE u.username = 'sr001'"))
            assertEquals("1", q("SELECT count(*) FROM dw.fact_attendance"))
            assertEquals("1", q("SELECT count(*) FROM dw.fact_geo_fix WHERE purpose = 'visit_open' AND satellites_used = 7 AND NOT is_mock"))
            // One phone, three batches of the day (10 + 5 + 3 records), none rejected.
            assertEquals("3|18|0|0", q("SELECT batches || '|' || records || '|' || rejected || '|' || quarantined FROM dw.fact_device_day"))
            val snap = "SELECT coalesce(string_agg((to_jsonb(x) - 'updated_at' - 'last_event_id')::text, '|' ORDER BY 1), '') FROM (SELECT * FROM dw.%s) x"
            val before = q(snap.format("fact_attendance")) + q(snap.format("fact_device_day")) + q(snap.format("fact_geo_fix"))
            val w = AggregationWorker(fresh.db); w.requestRebuild(day); w.runUntilIdle()
            assertEquals(before, q(snap.format("fact_attendance")) + q(snap.format("fact_device_day")) + q(snap.format("fact_geo_fix")))
        }
    }
}
