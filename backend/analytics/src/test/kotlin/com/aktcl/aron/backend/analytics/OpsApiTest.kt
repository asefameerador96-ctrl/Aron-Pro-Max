package com.aktcl.aron.backend.analytics

import com.aktcl.aron.backend.platform.AronClock
import com.aktcl.aron.backend.platform.AuthGuardDeps
import com.aktcl.aron.backend.platform.ReachResolver
import com.aktcl.aron.backend.platform.RegistryDefaults
import com.aktcl.aron.contract.Role
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.routing.Route
import io.ktor.server.testing.ApplicationTestBuilder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** F-SYS-026 / F-API-028 on the seeded day: figures equal the hand calculation, scope holds, a quarantine action changes state and is audited. */
class OpsApiTest : ReportFixture() {
    override val extraSql = """
        INSERT INTO app.device (device_uuid, flavour, app_package, status, device_owner, lockdown_level, public_key_jwk, public_key_thumbprint, app_signing_cert_sha256, app_version, last_contact_at, pending_rows_reported, config_version_applied, device_info, trust_level)
          SELECT gen_random_uuid(), 'sr', 'com.aktcl.aron.sr', 'active', true, 'dev', '{}'::jsonb, 'thumb-' || d.n, decode(repeat('ab', 32), 'hex'), '1.0.0+1', d.contact, d.pending, d.cfg, jsonb_build_object('model', d.model), d.trust
            FROM (VALUES (1, TIMESTAMPTZ '2026-10-04 03:00Z', 5, 5, 'Itel A', 'normal'), (2, TIMESTAMPTZ '2026-10-04 11:50Z', 0, 7, 'Tecno B', 'high'), (3, TIMESTAMPTZ '2026-10-04 11:30Z', 2, 7, 'Infinix C', 'normal')) d(n, contact, pending, cfg, model, trust);
        INSERT INTO app.device_binding (device_id, user_id, bind_ordinal, status)
          SELECT dv.id, u.id, 0, 'active' FROM app.device dv JOIN app.app_user u ON u.username = 'sr00' || right(dv.public_key_thumbprint, 1);
        INSERT INTO app.sync_rejected (client_uuid, record_type, code, retryable, payload_sha256, payload, user_id, business_date, stored_at)
          SELECT gen_random_uuid(), 'memo', 'unknown_sku', true, decode(repeat('01', 32), 'hex'), '{}'::jsonb, u.id, DATE '2026-10-04', s.stored FROM app.app_user u,
                 (VALUES (NULL::timestamptz), (NULL::timestamptz), (TIMESTAMPTZ '2026-10-04 10:00Z')) s(stored) WHERE u.username = 'sr001';
        INSERT INTO app.sync_quarantine (client_uuid, record_type, code, payload_sha256, payload, user_id, route_id, business_date)
          SELECT gen_random_uuid(), 'memo', q.code, decode(repeat('02', 32), 'hex'), '{"type":"memo"}'::jsonb, u.id, r.id, DATE '2026-10-04'
            FROM app.app_user u, app.route r, (VALUES ('arithmetic_mismatch'), ('memo_no_duplicate')) q(code) WHERE u.username = 'sr001' AND r.code = 'R1';
        UPDATE app.route_day SET submit_count_mismatch = true WHERE route_id = (SELECT id FROM app.route WHERE code = 'R3');
    """.trimIndent()

    override fun mount(r: Route, clock: AronClock, reach: ReachResolver, guard: AuthGuardDeps) {
        val dash = DashboardService(fresh.db, clock)
        r.opsRoutes(OpsDeps(OpsService(fresh.db, RegistryDefaults(), clock), dash, reach, guard, clock))
    }

    private suspend fun ApplicationTestBuilder.get(uid: Long, role: Role, path: String): HttpResponse = client.get(path) { bearerAuth(TestTokens.web(uid, role)) }
    private suspend fun ApplicationTestBuilder.post(uid: Long, role: Role, path: String, json: String): HttpResponse =
        client.post(path) { bearerAuth(TestTokens.web(uid, role)); contentType(ContentType.Application.Json); setBody(json) }
    private suspend fun HttpResponse.obj(): JsonObject = Json.parseToJsonElement(bodyAsText()).jsonObject
    private fun JsonObject.s(k: String) = this[k]!!.jsonPrimitive.content
    private fun JsonObject.i(k: String) = s(k).toInt()

    @Test
    fun syncHealthFiguresEqualTheHandComputedValues() = app {
        val o = get(10, Role.ANALYST, "/v1/dashboards/sync-health?business_date=2026-10-04").obj()
        val sum = o["summary"]!!.jsonObject
        assertEquals(3, sum.i("devices")); assertEquals(2, sum.i("devices_with_pending")); assertEquals(1, sum.i("held_rows_alerts"))   // sr001 holds 5 rows, silent since 03:00Z (> 4 h before 12:00Z)
        assertEquals(2, sum.i("rejected")); assertEquals(2, sum.i("quarantined")); assertEquals(1, sum.i("mismatched_route_days"))        // the stored resend and the other users do not count
        val items = o["items"]!!.jsonArray.map { it.jsonObject }
        val sr1 = items.first { it.s("username") == "sr001" }
        assertEquals(5, sr1.i("pending_rows_reported")); assertEquals(2, sr1.i("rejected_count")); assertEquals(2, sr1.i("quarantined_count"))
        assertEquals("true", sr1.s("held_rows_alert")); assertEquals("Itel A", sr1.s("device_model")); assertEquals(3600.0, sr1["sync_p95_s"]!!.jsonPrimitive.content.toDouble())  // received one hour after commit
        assertEquals("false", items.first { it.s("username") == "sr003" }.s("held_rows_alert"))
        assertEquals("true", items.first { it.s("username") == "sr003" }.s("submit_count_mismatch"))
        assertEquals(setOf("sr001", "sr003"), get(10, Role.ANALYST, "/v1/dashboards/sync-health?business_date=2026-10-04&only_problems=true").obj()["items"]!!.jsonArray.map { it.jsonObject.s("username") }.toSet())
        // Paging, scope and role gating.
        val first = get(10, Role.ANALYST, "/v1/dashboards/sync-health?business_date=2026-10-04&limit=2").obj()
        assertEquals(2, first["items"]!!.jsonArray.size); assertTrue(first["next_cursor"]!!.jsonPrimitive.content != "null")
        assertEquals(1, get(10, Role.ANALYST, "/v1/dashboards/sync-health?business_date=2026-10-04&limit=2&cursor=${first["next_cursor"]!!.jsonPrimitive.content}").obj()["items"]!!.jsonArray.size)
        assertEquals(listOf("sr003"), get(14, Role.TSO, "/v1/dashboards/sync-health?business_date=2026-10-04").obj()["items"]!!.jsonArray.map { it.jsonObject.s("username") })
        assertEquals(HttpStatusCode.Forbidden, get(11, Role.TSO, "/v1/dashboards/sync-health?business_date=2026-10-04&level=zone&node_id=$z2").status)
        assertEquals(HttpStatusCode.BadRequest, get(10, Role.ANALYST, "/v1/dashboards/sync-health").status)
        assertEquals(HttpStatusCode.Forbidden, get(12, Role.AMO, "/v1/dashboards/sync-health?business_date=2026-10-04").status)
    }

    @Test
    fun loginSubmitListsAndConfigAckShare() = app {
        val o = get(10, Role.ANALYST, "/v1/dashboards/login-submit?business_date=2026-10-04").obj()
        val k = o["kpis"]!!.jsonObject
        assertEquals(100.0, k["login_pct"]!!.jsonPrimitive.content.toDouble()); assertEquals(66.67, k["submit_pct_of_logged_in"]!!.jsonPrimitive.content.toDouble())
        assertEquals(0, o["not_logged_in"]!!.jsonArray.size)
        assertEquals(listOf("R2"), o["logged_in_not_submitted"]!!.jsonArray.map { it.jsonObject.s("route_code") })
        assertEquals(setOf("R1", "R3"), o["submitted"]!!.jsonArray.map { it.jsonObject.s("route_code") }.toSet())
        assertEquals(listOf("Route3"), get(14, Role.TSO, "/v1/dashboards/login-submit?business_date=2026-10-04").obj()["submitted"]!!.jsonArray.map { it.jsonObject.s("route_name") })
        // Config ack: version 7 is applied by 2 of the 3 selling phones.
        val svc = OpsService(fresh.db, RegistryDefaults(), AronClock { java.time.Instant.parse("2026-10-04T12:00:00Z") })
        assertEquals(66.67, svc.configAckPct(7, day)); assertEquals(100.0, svc.configAckPct(5, day))
    }

    /** v1.2 additions: config ack share, pending photos, quarantine backlog, by_zone[] and LoginSubmitStatus.zones[]. */
    @Test
    fun v12AdditionsAreScopedAndConsistent() = app {
        sql(
            """
            INSERT INTO app.media (client_uuid, family_uuid, business_date, user_id, captured_at, config_version, received_at, purpose, ref_type, ref_client_uuid, sha256, bytes, width, height, blob_path, taken_at, status)
              SELECT gen_random_uuid(), gen_random_uuid(), DATE '2026-10-04', u.id, TIMESTAMPTZ '2026-10-04 08:00Z', 1, TIMESTAMPTZ '2026-10-04 09:00Z', 'force_sale', 'visit', gen_random_uuid(), decode(repeat('cd', 32), 'hex'), 100, 100, 100,
                     'photos/2026-10-04/' || gen_random_uuid() || '/' || gen_random_uuid() || '.jpg', TIMESTAMPTZ '2026-10-04 08:00Z', 'pending_blob'
                FROM app.app_user u WHERE u.username = 'sr001';
            """.trimIndent(),
        )
        val o = get(10, Role.ANALYST, "/v1/dashboards/sync-health?business_date=2026-10-04").obj()
        val sum = o["summary"]!!.jsonObject
        val photos = sum["pending_photos"]!!.jsonObject
        assertEquals(1, photos.i("count")); assertEquals(10_800, photos.i("oldest_age_s"))                       // received 09:00Z, now 12:00Z
        assertTrue(sum.i("quarantine_backlog") >= sum.i("quarantined"))
        assertTrue(sum["config_ack_pct"]!!.jsonPrimitive.content.toDouble() in 0.0..100.0)
        val zones = o["by_zone"]!!.jsonArray.map { it.jsonObject }
        assertEquals(2, zones.size); assertEquals(1, zones.sumOf { it.i("pending_photos") })
        assertEquals(100.0, zones.first { it.s("zone_id").toLong() == z2 }["login_pct"]!!.jsonPrimitive.content.toDouble())
        // A zone-2 TSO sees only its own zone and no photo of zone 1.
        val own = get(14, Role.TSO, "/v1/dashboards/sync-health?business_date=2026-10-04").obj()
        assertEquals(listOf(z2), own["by_zone"]!!.jsonArray.map { it.jsonObject.s("zone_id").toLong() }); assertEquals(0, own["summary"]!!.jsonObject["pending_photos"]!!.jsonObject.i("count"))
        val ls = get(10, Role.ANALYST, "/v1/dashboards/login-submit?business_date=2026-10-04").obj()["zones"]!!.jsonArray.map { it.jsonObject }
        assertEquals(2, ls.size); assertEquals(mapOf(z2 to "true"), ls.filter { it.s("final_submitted") == "true" }.associate { it.s("zone_id").toLong() to "true" })   // only R3 (zone 2) is final-submitted
        assertEquals(listOf(z2), get(14, Role.TSO, "/v1/dashboards/login-submit?business_date=2026-10-04").obj()["zones"]!!.jsonArray.map { it.jsonObject.s("zone_id").toLong() })
    }

    @Test
    fun quarantineListAndActionsChangeStateAndAreAudited() = app {
        val list = get(13, Role.ADMIN, "/v1/admin/quarantine?status=open").obj()
        val items = list["items"]!!.jsonArray.map { it.jsonObject }
        assertEquals(2, items.size); assertEquals(setOf("arithmetic_mismatch", "memo_no_duplicate"), items.map { it.s("code") }.toSet())
        assertEquals(1, get(13, Role.ADMIN, "/v1/admin/quarantine?code=memo_no_duplicate").obj()["items"]!!.jsonArray.size)
        assertEquals(HttpStatusCode.Forbidden, get(11, Role.TSO, "/v1/admin/quarantine").status)
        val id = items.first { it.s("code") == "arithmetic_mismatch" }.s("quarantine_id")
        assertEquals(HttpStatusCode.Forbidden, post(10, Role.ANALYST, "/v1/admin/quarantine/$id/resolve", """{"action":"discard","reason":"duplicate row"}""").status)
        val r = post(13, Role.ADMIN, "/v1/admin/quarantine/$id/resolve", """{"action":"discard","reason":"duplicate row"}""")
        assertEquals(HttpStatusCode.OK, r.status)
        val done = r.obj(); assertEquals("discarded", done.s("status")); assertEquals(13, done.i("resolved_by_user_id")); assertEquals("duplicate row", done.s("resolution_note"))
        assertEquals(1, fresh.db.jdbi.withHandle<Int, Exception> { it.createQuery("SELECT count(*) FROM app.audit_log WHERE entity = 'quarantine' AND entity_id = '$id' AND action = 'discard'").mapTo(Int::class.java).one() })
        assertEquals(HttpStatusCode.OK, post(13, Role.ADMIN, "/v1/admin/quarantine/$id/resolve", """{"action":"discard","reason":"again again"}""").status)   // same actor, same decision: the stored outcome
        assertEquals(HttpStatusCode.Conflict, post(13, Role.ADMIN, "/v1/admin/quarantine/$id/resolve", """{"action":"return_to_device","reason":"change of mind"}""").status)
        assertEquals(1, get(13, Role.ADMIN, "/v1/admin/quarantine?status=open").obj()["items"]!!.jsonArray.size)
        val other = items.first { it.s("code") == "memo_no_duplicate" }.s("quarantine_id")
        assertEquals(HttpStatusCode.OK, post(13, Role.ADMIN, "/v1/admin/quarantine/$other/resolve", """{"action":"return_to_device","reason":"re-send after refresh"}""").status)
        assertTrue(get(13, Role.ADMIN, "/v1/admin/quarantine?status=discarded").obj()["items"]!!.jsonArray.any { it.jsonObject.s("resolution_note").startsWith("return_to_device") })
        // Accepting needs the ingest hook, which is not wired yet: 503, nothing changes. Bad requests are 400, unknown ids 404.
        assertEquals(HttpStatusCode.BadRequest, post(13, Role.ADMIN, "/v1/admin/quarantine/$other/resolve", """{"action":"accept_with_fix","reason":"fix the record"}""").status)
        assertEquals(HttpStatusCode.BadRequest, post(13, Role.ADMIN, "/v1/admin/quarantine/$other/resolve", """{"action":"wipe","reason":"fix the record"}""").status)
        assertEquals(HttpStatusCode.NotFound, post(13, Role.ADMIN, "/v1/admin/quarantine/999999/resolve", """{"action":"discard","reason":"nothing there at all"}""").status)
    }

    // ---------------- independent checker ----------------

    private fun sql(s: String) = fresh.db.jdbi.useHandle<Exception> { it.execute(s) }
    private fun newDevice(thumb: String, user: String, ordinal: Int, contact: String? = "2026-10-04 11:00Z", pending: Int = 0, cfg: Int = 7) = sql(
        """
        INSERT INTO app.device (device_uuid, flavour, app_package, status, device_owner, lockdown_level, public_key_jwk, public_key_thumbprint, app_signing_cert_sha256, app_version, last_contact_at, pending_rows_reported, config_version_applied, device_info, trust_level)
          VALUES (gen_random_uuid(), 'sr', 'com.aktcl.aron.sr', 'active', true, 'dev', '{}'::jsonb, '$thumb', decode(repeat('ab', 32), 'hex'), '1.0.0+1', ${contact?.let { "TIMESTAMPTZ '$it'" } ?: "NULL"}, $pending, $cfg, '{"model":"X"}'::jsonb, 'normal');
        INSERT INTO app.device_binding (device_id, user_id, bind_ordinal, status)
          SELECT dv.id, u.id, $ordinal, 'active' FROM app.device dv, app.app_user u WHERE dv.public_key_thumbprint = '$thumb' AND u.username = '$user';
        """.trimIndent(),
    )

    /** Scope leak: the per-user subqueries count rows by user and date, not by reach. A zone-1 TSO must not see sr001's quarantine row on R3 (zone 2). */
    @Test
    fun checker_syncHealthCountsOnlyRowsInsideTheViewersReach() = app {
        sql(
            """
            INSERT INTO app.sync_quarantine (client_uuid, record_type, code, payload_sha256, payload, user_id, route_id, business_date)
              SELECT gen_random_uuid(), 'memo', 'scope_out_of_reach', decode(repeat('03', 32), 'hex'), '{"type":"memo"}'::jsonb, u.id, r.id, DATE '2026-10-04'
                FROM app.app_user u, app.route r WHERE u.username = 'sr001' AND r.code = 'R3';
            """.trimIndent(),
        )
        val o = get(11, Role.TSO, "/v1/dashboards/sync-health?business_date=2026-10-04").obj()
        val sr1 = o["items"]!!.jsonArray.map { it.jsonObject }.first { it.s("username") == "sr001" }
        assertEquals(2, sr1.i("quarantined_count"), "zone-2 quarantine row leaked into a zone-1 view")
        assertEquals(2, o["summary"]!!.jsonObject.i("quarantined"))
    }

    /** Shared phones (CLAUDE.md): one device bound to two users becomes two rows, so summary.devices / devices_with_pending / held_rows_alerts double-count it. */
    @Test
    fun checker_summaryCountsASharedDeviceOnce() = app {
        sql("INSERT INTO app.device_binding (device_id, user_id, bind_ordinal, status) SELECT dv.id, u.id, 1, 'active' FROM app.device dv, app.app_user u WHERE dv.public_key_thumbprint = 'thumb-1' AND u.username = 'sr002'")
        val sum = get(10, Role.ANALYST, "/v1/dashboards/sync-health?business_date=2026-10-04").obj()["summary"]!!.jsonObject
        assertEquals(3, sum.i("devices")); assertEquals(2, sum.i("devices_with_pending")); assertEquals(1, sum.i("held_rows_alerts"))
    }

    /** A user with two active bindings (ordinals 0..3 are allowed) gets one row per device; summing the per-user rejected/quarantined counts doubles them. */
    @Test
    fun checker_summaryDoesNotDoubleRejectsOfAUserWithTwoDevices() = app {
        newDevice("thumb-x9", "sr001", 1)
        val sum = get(10, Role.ANALYST, "/v1/dashboards/sync-health?business_date=2026-10-04").obj()["summary"]!!.jsonObject
        assertEquals(2, sum.i("rejected")); assertEquals(2, sum.i("quarantined"))
    }

    /** OpsService.configAckPct's own contract: "selling phones (those with a contact that day)". A never-contacted bound phone must not lower the share. */
    @Test
    fun checker_configAckIgnoresPhonesWithNoContactThatDay() = app {
        newDevice("thumb-x8", "sr001", 1, contact = null, cfg = 0)
        val svc = OpsService(fresh.db, RegistryDefaults(), AronClock { java.time.Instant.parse("2026-10-04T12:00:00Z") })
        assertEquals(66.67, svc.configAckPct(7, day))
    }

    /** docs/24 s3.x online commands: repeating the same decision by the same actor returns 200 with the stored outcome; a different transition is 409 ERR_REQUEST_STATE. */
    @Test
    fun checker_repeatingTheSameResolveIsIdempotent() = app {
        val id = get(13, Role.ADMIN, "/v1/admin/quarantine?status=open").obj()["items"]!!.jsonArray.first().jsonObject.s("quarantine_id")
        val body = """{"action":"discard","reason":"duplicate row, discarded"}"""
        assertEquals(HttpStatusCode.OK, post(13, Role.ADMIN, "/v1/admin/quarantine/$id/resolve", body).status)
        assertEquals(HttpStatusCode.OK, post(13, Role.ADMIN, "/v1/admin/quarantine/$id/resolve", body).status, "a retried identical resolve must replay 200")
        val other = post(13, Role.ADMIN, "/v1/admin/quarantine/$id/resolve", """{"action":"return_to_device","reason":"different transition"}""")
        assertEquals(HttpStatusCode.Conflict, other.status)
        assertTrue(other.bodyAsText().contains("ERR_REQUEST_STATE"), other.bodyAsText())
    }

    /** contract ChangeReason: minLength 10. The service accepts 3. */
    @Test
    fun checker_resolveReasonFollowsContractMinLength() = app {
        val id = get(13, Role.ADMIN, "/v1/admin/quarantine?status=open").obj()["items"]!!.jsonArray.first().jsonObject.s("quarantine_id")
        assertEquals(HttpStatusCode.BadRequest, post(13, Role.ADMIN, "/v1/admin/quarantine/$id/resolve", """{"action":"discard","reason":"dup"}""").status)
    }

    /** docs/24 s4.6: a parked row turned final "is listed on the quarantine page"; listQuarantine: "Rejected and quarantined records". */
}
