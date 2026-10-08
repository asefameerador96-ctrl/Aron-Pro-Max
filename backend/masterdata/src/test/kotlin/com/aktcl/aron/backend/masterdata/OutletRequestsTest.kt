package com.aktcl.aron.backend.masterdata

import com.aktcl.aron.contract.Role
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * F-API-055 (BC-84): outlet change requests through their life cycle on the seeded database. Reach (an SR sees only
 * its own; another territory gets 404), the role of each step, separation of duties, idempotent repeats by state, and
 * the approval applied to the outlet master (new outlet with a code and a confirmed pin, close, a large move that only
 * the TSO approves).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OutletRequestsTest {
    private lateinit var env: AdminEnv

    @BeforeAll fun setUp() { env = AdminEnv() }
    @AfterAll fun tearDown() = env.close()

    private fun t(user: String, role: Role) = env.token(user, role)
    private val sr get() = t("sr1001", Role.SR)
    private val amo get() = t("amo1001", Role.AMO)
    private val tso get() = t("tso1001", Role.TSO)
    private val dmo get() = t("dmo1001", Role.DMO)
    private val admin get() = t("admin1001", Role.ADMIN)

    private fun id(sql: String) = env.sql(sql)!!.toLong()
    private val route get() = id("SELECT id FROM app.route WHERE code = 'MIR-SR-D'")
    private val cluster get() = id("SELECT cluster_id FROM app.outlet WHERE route_id = $route ORDER BY id LIMIT 1")
    private val sub get() = id("SELECT id FROM app.sub_channel WHERE code = 'GT-TEA'")

    /** An app request as ingest stores it (outlet_change_request record of sr1001). */
    private fun appRequest(type: String, outletId: Long?, proposed: String, ageDays: Int = 0): UUID {
        val u = UUID.randomUUID()
        env.db.fresh.db.jdbi.useHandle<Exception> { h ->
            h.createUpdate(
                """
                INSERT INTO app.outlet_change_request (client_uuid, family_uuid, business_date, user_id, route_id, captured_at, config_version, request_type, outlet_id, proposed, fix_status, fix_lat, fix_lng, fix_accuracy_m, fix_is_mock)
                VALUES (:u, :u, current_date, (SELECT id FROM app.app_user WHERE username = 'sr1001'), :r, now() - make_interval(days => :age), 1, :t, :o, CAST(:p AS jsonb), 'ok', 23.81, 90.41, 12, false)
                """.trimIndent(),
            ).bind("age", ageDays).bind("u", u).bind("r", route).bind("t", type).bind("o", outletId).bind("p", proposed).execute()
        }
        return u
    }

    @Test
    fun aNewOutletRequestIsVerifiedThenApprovedIntoTheMasterOnce() = env.app {
        val u = appRequest("new", null, """{"name":"Request Store","owner_name":"Rahim Uddin","contact_number":"01712345679","cluster_id":$cluster,"sub_channel_id":$sub,"lat":23.8111,"lng":90.4111}""")
        val list = sendA1(HttpMethod.Get, "/outlet-requests?status=pending", amo).objA1().itemsA1()
        assertTrue(list.any { it.strA1("request_uuid") == u.toString() }, "the AMO of the zone sees it")
        assertTrue(sendA1(HttpMethod.Get, "/outlet-requests", t("sr2001", Role.SR)).objA1().itemsA1().isEmpty(), "another SR sees none")
        assertEquals(HttpStatusCode.NotFound, sendA1(HttpMethod.Get, "/outlet-requests/$u", t("tso2001", Role.TSO)).status, "another territory: 404")

        val approve = """{"change_reason":"Verified on site by the AMO"}"""
        assertEquals("ERR_CONFLICT", sendA1(HttpMethod.Post, "/outlet-requests/$u/approve", dmo, approve).code(), "pending cannot be approved")
        assertEquals(HttpStatusCode.Forbidden, sendA1(HttpMethod.Post, "/outlet-requests/$u/verify", sr, """{"sub_channel_id":$sub}""").status)
        val v = sendA1(HttpMethod.Post, "/outlet-requests/$u/verify", amo, """{"sub_channel_id":$sub,"geo_class":"Urban","note":"Seen open"}""")
        assertEquals(HttpStatusCode.OK, v.status, v.objA1().toString())
        assertEquals("verified", v.objA1().strA1("status")); assertEquals("web", v.objA1().strA1("verified_via"))
        assertEquals(HttpStatusCode.OK, sendA1(HttpMethod.Post, "/outlet-requests/$u/verify", amo, """{"sub_channel_id":$sub}""").status, "a repeat by the verifier is the same answer")
        assertEquals(HttpStatusCode.Forbidden, sendA1(HttpMethod.Post, "/outlet-requests/$u/approve", amo, approve).status, "the AMO does not approve")

        val a = sendA1(HttpMethod.Post, "/outlet-requests/$u/approve", dmo, approve)
        assertEquals(HttpStatusCode.OK, a.status, a.objA1().toString())
        val outlet = a.objA1().lng("resulting_outlet_id")
        assertEquals("approved", a.objA1().strA1("status"))
        assertEquals("AD-$outlet", env.sql("SELECT code FROM app.outlet WHERE id = $outlet"))
        assertEquals("true|$route|Urban", env.sql("SELECT location_confirmed || '|' || route_id || '|' || geo_class FROM app.outlet WHERE id = $outlet"))
        assertEquals(listOf("requested", "verified", "approved"), a.objA1().arr("events").map { (it as kotlinx.serialization.json.JsonObject).strA1("event") })
        assertEquals(1, env.audit("outlet", outlet, "create"))
        assertEquals(HttpStatusCode.OK, sendA1(HttpMethod.Post, "/outlet-requests/$u/approve", dmo, approve).status, "a repeat by the approver")
        assertEquals("ERR_CONFLICT", sendA1(HttpMethod.Post, "/outlet-requests/$u/approve", admin, approve).code(), "decided: another approver gets 409")
        assertEquals("ERR_CONFLICT", sendA1(HttpMethod.Post, "/outlet-requests/$u/reject", dmo, """{"reason":"Changed my mind about it"}""").code())
        assertEquals(1L, env.count("SELECT count(*) FROM app.outlet WHERE name = 'Request Store'"), "applied once")
    }

    @Test
    fun aWebCloseRequestIsIdempotentByUuidAndRejectedWithAReason() = env.app {
        val outlet = id("SELECT id FROM app.outlet WHERE route_id = $route AND status = 'active' ORDER BY id DESC LIMIT 1")
        val u = UUID.randomUUID()
        val body = """{"request_uuid":"$u","request_type":"close","outlet_id":$outlet,"proposed":{"close_reason_code":"shop_shut"}}"""
        assertEquals(HttpStatusCode.Forbidden, sendA1(HttpMethod.Post, "/outlet-requests", amo, body).status, "TSO and DMO raise web requests")
        val c = sendA1(HttpMethod.Post, "/outlet-requests", tso, body)
        assertEquals(HttpStatusCode.Created, c.status, c.objA1().toString())
        assertEquals(HttpStatusCode.Created, sendA1(HttpMethod.Post, "/outlet-requests", tso, body).status, "the same uuid again: the stored request")
        assertEquals(1L, env.count("SELECT count(*) FROM app.outlet_change_request WHERE client_uuid = '$u'"))
        assertEquals("ERR_CONFLICT", sendA1(HttpMethod.Post, "/outlet-requests", tso, body.replace("\"close\"", "\"cluster\"").replace("\"close_reason_code\":\"shop_shut\"", "\"cluster_id\":$cluster")).code())
        assertEquals(HttpStatusCode.BadRequest, sendA1(HttpMethod.Post, "/outlet-requests", tso, body.replace("$u", UUID.randomUUID().toString()).replace("close_reason_code", "nid")).status)
        assertEquals(HttpStatusCode.Forbidden, sendA1(HttpMethod.Post, "/outlet-requests", t("tso2001", Role.TSO), body.replace("$u", UUID.randomUUID().toString())).status, "outlet outside reach")

        assertEquals("verified", sendA1(HttpMethod.Post, "/outlet-requests/$u/verify", amo, """{"sub_channel_id":$sub}""").objA1().strA1("status"))
        assertEquals("ERR_SEPARATION_OF_DUTIES", sendA1(HttpMethod.Post, "/outlet-requests/$u/approve", tso, """{"change_reason":"Closing as asked by me"}""").code(), "the requester cannot decide")
        val r = sendA1(HttpMethod.Post, "/outlet-requests/$u/reject", dmo, """{"reason":"The shop is open again"}""")
        assertEquals("rejected", r.objA1().strA1("status")); assertEquals("The shop is open again", r.objA1().strA1("rejection_reason"))
        assertEquals("active", env.sql("SELECT status FROM app.outlet WHERE id = $outlet"), "a rejected close changes nothing")
    }

    @Test
    fun aLargeMoveOfAConfirmedPinIsApprovedByTheTsoAndTheVerifierCannotDecide() = env.app {
        val outlet = id("SELECT id FROM app.outlet WHERE route_id = $route AND status = 'active' AND location_confirmed ORDER BY id LIMIT 1")
        env.db.fresh.db.jdbi.useHandle<Exception> { it.execute("UPDATE app.outlet SET lat = 23.8000, lng = 90.4000 WHERE id = $outlet") }
        val u = appRequest("location", outlet, """{"lat":23.8100,"lng":90.4000}""") // about 1.1 km north
        val v = sendA1(HttpMethod.Post, "/outlet-requests/$u/verify", amo, """{"sub_channel_id":$sub}""").objA1()
        assertEquals(true, v.getValue("requires_tso").toString().toBoolean()); assertTrue(v.getValue("moved_m").toString().toDouble() > 1000)
        assertEquals(HttpStatusCode.Forbidden, sendA1(HttpMethod.Post, "/outlet-requests/$u/approve", dmo, """{"change_reason":"Pin moved after a visit"}""").status)
        val a = sendA1(HttpMethod.Post, "/outlet-requests/$u/approve", tso, """{"change_reason":"Pin moved after a visit"}""")
        assertEquals(HttpStatusCode.OK, a.status, a.objA1().toString())
        assertEquals("23.81|true", env.sql("SELECT lat || '|' || location_confirmed FROM app.outlet WHERE id = $outlet"))

        // An admin who verified a request cannot also approve it.
        val u2 = appRequest("cluster", outlet, """{"cluster_id":$cluster}""")
        assertEquals("verified", sendA1(HttpMethod.Post, "/outlet-requests/$u2/verify", admin, """{"sub_channel_id":$sub}""").objA1().strA1("status"))
        assertEquals("ERR_SEPARATION_OF_DUTIES", sendA1(HttpMethod.Post, "/outlet-requests/$u2/approve", admin, """{"change_reason":"Approving my own check"}""").code())
        assertNotNull(sendA1(HttpMethod.Get, "/outlet-requests/$u2", sr).objA1().strA1("request_uuid"), "the requester reads its own request")
    }

    @Test
    fun approvingACloseClosesTheOutletOnceAndThePhoneNumberNeedsThePiiClaim() = env.app {
        val outlet = id("SELECT id FROM app.outlet WHERE route_id = $route AND status = 'active' ORDER BY id DESC OFFSET 1 LIMIT 1")
        val u = UUID.randomUUID()
        assertEquals(HttpStatusCode.Created, sendA1(HttpMethod.Post, "/outlet-requests", tso, """{"request_uuid":"$u","request_type":"close","outlet_id":$outlet,"proposed":{"close_reason_code":"owner_moved"}}""").status)
        sendA1(HttpMethod.Post, "/outlet-requests/$u/verify", amo, """{"sub_channel_id":$sub}""")
        val approve = """{"change_reason":"The owner moved away last week"}"""
        val a = sendA1(HttpMethod.Post, "/outlet-requests/$u/approve", dmo, approve)
        assertEquals(HttpStatusCode.OK, a.status, a.objA1().toString())
        assertEquals("closed|true", env.sql("SELECT status || '|' || (closed_at IS NOT NULL) FROM app.outlet WHERE id = $outlet"))
        assertEquals(outlet, a.objA1().lng("resulting_outlet_id"))
        assertEquals(HttpStatusCode.OK, sendA1(HttpMethod.Post, "/outlet-requests/$u/approve", dmo, approve).status)
        assertEquals(1, env.audit("outlet", outlet, "update"), "applied once")

        // An info request with a phone number: the requester and a pii reader see it, an AMO without the claim does not.
        val other = id("SELECT id FROM app.outlet WHERE route_id = $route AND status = 'active' ORDER BY id LIMIT 1")
        val u2 = UUID.randomUUID()
        assertEquals(HttpStatusCode.Created, sendA1(HttpMethod.Post, "/outlet-requests", tso, """{"request_uuid":"$u2","request_type":"info","outlet_id":$other,"proposed":{"owner_name":"New Owner","contact_number":"01812345678"}}""").status)
        assertEquals(null, sendA1(HttpMethod.Get, "/outlet-requests/$u2", amo).objA1().getValue("proposed").let { (it as kotlinx.serialization.json.JsonObject).strA1("contact_number") })
        assertEquals("01812345678", sendA1(HttpMethod.Get, "/outlet-requests/$u2", tso).objA1().getValue("proposed").let { (it as kotlinx.serialization.json.JsonObject).strA1("contact_number") })
        assertEquals("01812345678", sendA1(HttpMethod.Get, "/outlet-requests/$u2", env.token("amo1001", Role.AMO, pii = true)).objA1().getValue("proposed").let { (it as kotlinx.serialization.json.JsonObject).strA1("contact_number") })
        assertEquals("ERR_CONFLICT", sendA1(HttpMethod.Post, "/outlet-requests", tso, """{"request_uuid":"$u2","request_type":"info","outlet_id":$other,"proposed":{"owner_name":"Another Owner"}}""").code(), "same uuid, other content")
    }

    /** N-044: an approved cluster request moves the outlet, writes placement history, and yesterday's memo keeps its cluster. */
    @Test
    fun anApprovedClusterRequestLeavesYesterdaysMemoInTheOldCluster() = env.app {
        val outlet = id("SELECT id FROM app.outlet WHERE route_id = $route AND status = 'active' ORDER BY id OFFSET 2 LIMIT 1")
        val old = id("SELECT cluster_id FROM app.outlet WHERE id = $outlet")
        val target = id("SELECT c.id FROM app.cluster c JOIN app.outlet o ON o.zone_id = c.zone_id WHERE o.id = $outlet AND c.id <> $old ORDER BY c.id LIMIT 1")
        val memo = UUID.randomUUID()
        env.db.fresh.db.jdbi.useHandle<Exception> { h ->
            h.createUpdate(
                """
                INSERT INTO app.memo (client_uuid, family_uuid, business_date, user_id, route_id, captured_at, config_version, visit_client_uuid, outlet_id, memo_no,
                                      memo_kind, committed_at, price_list_date, price_type, gross_mtk, offer_discount_mtk, drp_discount_mtk, qc_deduction_mtk, round_adj_mtk,
                                      net_mtk, paid_mtk, due_mtk, is_credit, line_count, discount_line_count, qc_line_count, status)
                SELECT :m, :m, :d, u.id, :r, now() - interval '1 day', 1, gen_random_uuid(), :o, 'sr1001-' || to_char(:d, 'YYMMDD') || '-901',
                       'zero_sale', now() - interval '1 day', :d, 'outlet', 0, 0, 0, 0, 0, 0, 0, 0, false, 0, 0, 0, 'active'
                  FROM app.app_user u WHERE u.username = 'sr1001'
                """.trimIndent(),
            ).bind("m", memo).bind("d", env.today.minusDays(1)).bind("r", route).bind("o", outlet).execute()
        }
        assertEquals(old, id("SELECT cluster_id FROM app.memo WHERE client_uuid = '$memo'"), "the memo froze its cluster at capture")

        val u = appRequest("cluster", outlet, """{"cluster_id":$target}""")
        sendA1(HttpMethod.Post, "/outlet-requests/$u/verify", amo, """{"sub_channel_id":$sub}""")
        val a = sendA1(HttpMethod.Post, "/outlet-requests/$u/approve", dmo, """{"change_reason":"The outlet belongs to the next cluster"}""")
        assertEquals(HttpStatusCode.OK, a.status, a.objA1().toString())
        assertEquals(target, id("SELECT cluster_id FROM app.outlet WHERE id = $outlet"), "the outlet's current cluster changed")
        assertEquals("$target", env.sql("SELECT cluster_id::text FROM app.outlet_placement_history WHERE outlet_id = $outlet AND valid_to IS NULL"), "a new open history row")
        assertEquals(old, id("SELECT cluster_id FROM app.memo WHERE client_uuid = '$memo'"), "yesterday's memo stays in the old cluster")
    }

    @Test
    fun theAmoAppVerificationRecordMovesAPendingRequestOnce() {
        val u = appRequest("cluster", id("SELECT id FROM app.outlet WHERE route_id = $route ORDER BY id LIMIT 1"), """{"cluster_id":$cluster}""")
        fun rec(user: String, decision: String) = com.aktcl.aron.backend.platform.IngestRecord(
            "outlet_request_verification", UUID.randomUUID().toString(), java.time.LocalDate.now(), // wall-clock-ok: the date is not read by the handler
            kotlinx.serialization.json.buildJsonObject { put("captured_at", kotlinx.serialization.json.JsonPrimitive("2027-01-03T04:00:00.000Z")) },
            kotlinx.serialization.json.buildJsonObject { put("request_uuid", kotlinx.serialization.json.JsonPrimitive(u.toString())); put("decision", kotlinx.serialization.json.JsonPrimitive(decision)) },
            env.db.ids.getValue(user), Role.AMO, 1, UUID.randomUUID().toString(), java.time.Instant.parse("2027-01-03T04:00:00Z"),
        )
        val hd = OutletRequestVerificationHandler()
        fun status() = env.sql("SELECT status || '|' || coalesce(verified_by::text, '-') FROM app.outlet_change_request WHERE client_uuid = '$u'")
        env.db.fresh.db.jdbi.useHandle<Exception> { h -> hd.afterStored(h, rec("sr1001", "verified"), null) }
        assertEquals("pending|-", status(), "the requester cannot verify its own request")
        env.db.fresh.db.jdbi.useHandle<Exception> { h -> hd.afterStored(h, rec("amo1001", "verified"), null) }
        assertEquals("verified|${env.db.ids.getValue("amo1001")}", status())
        env.db.fresh.db.jdbi.useHandle<Exception> { h -> hd.afterStored(h, rec("amo1001", "discarded"), null) }
        assertEquals("verified|${env.db.ids.getValue("amo1001")}", status(), "only a pending request moves")

        // BC-84: an AMO whose reach does not hold the request's zone is quarantined scope_out_of_reach.
        env.db.fresh.db.jdbi.useHandle<Exception> { h ->
            h.execute("INSERT INTO app.app_user (username, full_name, role, locale, home_zone_id, pilot, must_change_password) SELECT 'amo2001', 'Other AMO', 'AMO', 'bn', z.id, true, false FROM app.zone z WHERE z.code = 'Z-OTHER'")
            h.execute("INSERT INTO app.user_scope (user_id, node_type, node_id, valid_from) SELECT u.id, 'zone', z.id, DATE '2026-01-01' FROM app.app_user u, app.zone z WHERE u.username = 'amo2001' AND z.code = 'Z-OTHER'")
        }
        val geo = GeoRepository(env.db.fresh.db)
        val scoped = OutletRequestVerificationHandler(SqlReachResolver(env.db.fresh.db, geo))
        fun recOf(user: String) = rec("amo1001", "verified").copy(userId = env.db.fresh.db.jdbi.withHandle<Long, Exception> { h ->
            h.createQuery("SELECT id FROM app.app_user WHERE username = :u").bind("u", user).mapTo(Long::class.java).one()
        }, businessDate = java.time.LocalDate.parse("2027-01-03"))
        env.db.fresh.db.jdbi.useHandle<Exception> { h ->
            assertEquals(com.aktcl.aron.contract.RecordOutcomeCode.SCOPE_OUT_OF_REACH, scoped.check(h, recOf("amo2001"))?.code)
            assertEquals(null, scoped.check(h, recOf("amo1001")), "the zone's AMO is in reach")
        }
    }

    /** BC-84: pending and verified requests lapse 30 days after capture, once, with a `lapsed` event; decided ones never. */
    @Test
    fun undecidedRequestsLapseAfterThirtyDays() {
        val outlet = id("SELECT id FROM app.outlet WHERE route_id = $route ORDER BY id LIMIT 1")
        val old = appRequest("close", outlet, """{"close_reason_code":"shop_closed"}""", ageDays = 31)
        val oldVerified = appRequest("close", outlet, """{"close_reason_code":"shop_closed"}""", ageDays = 31)
        val oldDecided = appRequest("close", outlet, """{"close_reason_code":"shop_closed"}""", ageDays = 31)
        val young = appRequest("close", outlet, """{"close_reason_code":"shop_closed"}""", ageDays = 29)
        env.db.fresh.db.jdbi.useHandle<Exception> { h ->
            h.execute("UPDATE app.outlet_change_request SET status = 'verified' WHERE client_uuid = '$oldVerified'")
            h.execute("UPDATE app.outlet_change_request SET status = 'discarded' WHERE client_uuid = '$oldDecided'")
        }
        val job = OutletRequestLapseJob(env.db.fresh.db)
        assertTrue(job.runOnce() >= 2)
        fun st(u: UUID) = env.sql("SELECT status FROM app.outlet_change_request WHERE client_uuid = '$u'")
        assertEquals(listOf("lapsed", "lapsed", "discarded", "pending"), listOf(st(old), st(oldVerified), st(oldDecided), st(young)))
        assertEquals(0, job.runOnce(), "idempotent")
        assertEquals("1", env.sql("SELECT count(*) FROM app.outlet_request_event WHERE request_uuid = '$old' AND event = 'lapsed' AND via = 'job'"))
    }

    /**
     * BC-84: another AMO's offline `verified` record that reached an already verified request stays stored but is no
     * step of the trail, and its classification never overrides the real verifier's at approval.
     */
    @Test
    fun aNoOpVerificationIsNotInTheTrailAndDoesNotClassify() = env.app {
        val u = appRequest("new", null, """{"name":"Noop Store","owner_name":"Karim Mia","contact_number":"01712345670","cluster_id":$cluster,"sub_channel_id":$sub,"lat":23.8112,"lng":90.4112}""")
        assertEquals(HttpStatusCode.OK, sendA1(HttpMethod.Post, "/outlet-requests/$u/verify", amo, """{"sub_channel_id":$sub,"geo_class":"Urban"}""").status)
        env.db.fresh.db.jdbi.useHandle<Exception> { h ->
            h.execute("INSERT INTO app.app_user (username, full_name, role, locale, pilot, must_change_password) SELECT 'amo3003', 'Late AMO', 'AMO', 'bn', true, false WHERE NOT EXISTS (SELECT 1 FROM app.app_user WHERE username = 'amo3003')")
            h.execute(
                "INSERT INTO app.outlet_request_event (client_uuid, family_uuid, config_version, request_uuid, event, actor_user_id, via, business_date, at, geo_class) " +
                    "SELECT g, g, 1, CAST('$u' AS uuid), 'verified', id, 'device', current_date, now() + interval '1 minute', 'Rural' FROM app.app_user, gen_random_uuid() g WHERE username = 'amo3003'",
            )
        }
        val trail = sendA1(HttpMethod.Get, "/outlet-requests/$u", dmo).objA1().arr("events").map { (it as kotlinx.serialization.json.JsonObject).strA1("event") }
        assertEquals(listOf("requested", "verified"), trail, "the late no-op verification is not a step")
        val a = sendA1(HttpMethod.Post, "/outlet-requests/$u/approve", dmo, """{"change_reason":"Verified on site by the AMO"}""")
        assertEquals(HttpStatusCode.OK, a.status, a.objA1().toString())
        assertEquals("Urban", env.sql("SELECT geo_class FROM app.outlet WHERE id = ${a.objA1().lng("resulting_outlet_id")}"), "the real verifier's class")
    }
}
