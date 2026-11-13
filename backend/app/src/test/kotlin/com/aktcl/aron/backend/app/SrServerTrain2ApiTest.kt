package com.aktcl.aron.backend.app

import com.aktcl.aron.backend.app.TsoWorld.Companion.ack
import com.aktcl.aron.backend.app.TsoWorld.Companion.json
import com.aktcl.aron.backend.app.TsoWorld.Companion.status
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import java.io.File
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * SR manual build, sr-server train 2, push B (contract 1.16.0) over HTTP on the Mirpur seed: the bundle's `clusters` and `outlet_sku_stats`
 * sections, the `posm-survey` report with its scope, the journey `repeat_sale` switch.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SrServerTrain2ApiTest {
    private val repoRoot = File(System.getProperty("aron.repoRoot") ?: "../..")
    private lateinit var world: TsoWorld
    private lateinit var sr: TsoPhone
    private lateinit var amo: TsoPhone
    private lateinit var tso: TsoPhone

    @BeforeAll
    fun setUp() {
        world = TsoWorld(repoRoot, mirrors = listOf("LKX"))
        sr = world.phone("sr1001", "sr")
        amo = world.phone("amo1001", "amo")
        tso = world.phone("tso1001", "tso")
    }

    @AfterAll
    fun tearDown() = world.close()

    private fun memo(w: TsoWorld, id: String, date: String, outlet: Long, no: Int, lines: List<Triple<String, String, Int>>, voided: Boolean = false, visit: String? = null) {
        w.fresh.db.jdbi.useHandle<Exception> { h ->
            h.execute("SET session_replication_role = replica")
            try {
                h.execute(
                    """INSERT INTO app.memo (client_uuid, family_uuid, business_date, user_id, route_id, captured_at, config_version, visit_client_uuid, outlet_id, memo_no,
                         memo_kind, committed_at, price_list_date, price_type, gross_mtk, offer_discount_mtk, drp_discount_mtk, qc_deduction_mtk, round_adj_mtk,
                         net_mtk, paid_mtk, due_mtk, is_credit, line_count, discount_line_count, qc_line_count, status, voided_by_client_uuid, received_at, voided_at)
                       SELECT '$id', '$id', DATE '$date', u.id, o.route_id, TIMESTAMPTZ '$date 05:00Z', 1, ${if (visit != null) "'$visit'" else "gen_random_uuid()"}, o.id, 'sr1001-${date.substring(2).replace("-", "")}-${900 + no}', 'sale',
                              TIMESTAMPTZ '$date 05:00Z', DATE '$date', 'outlet', 800, 0, 0, 0, 0, 800, 800, 0, false, ${lines.size}, 0, 0, 'active', NULL, TIMESTAMPTZ '$date 06:00Z',
                              ${if (voided) "now()" else "NULL"}
                         FROM app.app_user u, app.outlet o WHERE u.username = 'sr1001' AND o.id = $outlet""",
                )
                lines.forEachIndexed { i, (sku, kind, qty) ->
                    h.execute(
                        """INSERT INTO app.memo_line (client_uuid, family_uuid, business_date, user_id, route_id, captured_at, config_version, memo_client_uuid, line_no, sku_id,
                             line_kind, qty_entered, unit_entered, pack_factor, qty_base, price_type, price_valid_from, base_price_mtk, price_per_qty, gross_mtk)
                           SELECT gen_random_uuid(), '$id', DATE '$date', m.user_id, m.route_id, m.captured_at, 1, '$id', ${i + 1}, s.id, '$kind', $qty, s.base_unit, 1, $qty, 'outlet',
                                  DATE '$date', 800, 1, ${qty * 800} FROM app.memo m, app.sku s WHERE m.client_uuid = '$id' AND m.business_date = DATE '$date' AND s.code = '$sku'""",
                    )
                }
            } finally { h.execute("SET session_replication_role = origin") }
        }
    }

    @Test
    fun theSrBundleCarriesTheClustersOfItsRoutesAndTheOutletSkuStatsInBaseUnitsAndNoOtherRoleDoes() = world.withApi {
        with(world) {
            val d = today()
            val outlet = longs("SELECT o.id FROM app.outlet o JOIN app.route_assignment a ON a.route_id = o.route_id JOIN app.app_user u ON u.id = a.user_id WHERE u.username = 'sr1001' AND o.status = 'active' ORDER BY o.id LIMIT 1").single()
            val skus = rows("SELECT code FROM app.sku WHERE status = 'active' ORDER BY id LIMIT 2").map { it["code"] as String }
            val (a, b) = skus
            fun day(n: Long) = d.minusDays(n).toString()
            // d-7: A 10; d-3: A 6 and B 4; d-1: A 3. Left out: a voided memo (A 100), a drp_reward line (A 50), a memo 40 days ago, one today.
            memo(this, UUID.randomUUID().toString(), day(7), outlet, 1, listOf(Triple(a, "sale", 10)))
            memo(this, UUID.randomUUID().toString(), day(3), outlet, 2, listOf(Triple(a, "sale", 6), Triple(b, "sale", 4)))
            memo(this, UUID.randomUUID().toString(), day(1), outlet, 3, listOf(Triple(a, "sale", 3), Triple(a, "drp_reward", 50)))
            memo(this, UUID.randomUUID().toString(), day(2), outlet, 4, listOf(Triple(a, "sale", 100)), voided = true)
            memo(this, UUID.randomUUID().toString(), day(40), outlet, 5, listOf(Triple(a, "sale", 1000)))
            memo(this, UUID.randomUUID().toString(), day(0), outlet, 6, listOf(Triple(a, "sale", 7)))
            val skuId = { code: String -> one("SELECT id FROM app.sku WHERE code = '$code'") }
            val res = getAs(sr, "/v1/sync/bundle")
            assertEquals(HttpStatusCode.OK, res.status, res.bodyAsText())
            val bundle = json(res.bodyAsText())
            val entry = bundle["outlet_sku_stats"]!!.jsonArray.map { it.jsonObject }.single { it["outlet_id"]!!.jsonPrimitive.content.toLong() == outlet }
            // items are [sku_delta, prev, ads, sdlw]: the first sku_delta is the SKU id, later ones the difference from the previous item's id (items ascend by SKU id)
            val decoded = entry["items"]!!.jsonArray.map { q -> q.jsonArray.map { it.jsonPrimitive.content.toLong() } }.let { raw -> var id = 0L; raw.map { q -> id += q[0]; listOf(id) + q.drop(1) } }
            assertEquals(decoded.map { it[0] }, decoded.map { it[0] }.sorted(), "ascending by SKU id")
            fun item(code: String): List<Long> = decoded.single { it[0] == skuId(code) }   // [sku, prev, ads, sdlw]
            // ADS = quantity in the 30 calendar days before the bundle date / the ELAPSED calendar days of the window (30: the first selling memo is 40 days old):
            // A total 19 (3 visits) -> 19 x 100 / 30 = 63.33 -> 63 hundredths a day (NOT 633, the per-selling-day figure); B total 4 -> 13
            assertEquals(day(1), entry["previous_day"]!!.jsonPrimitive.content)
            assertEquals(3L, item(a)[1], "previous sale day quantity in base units")
            assertEquals(63L, item(a)[2], "per elapsed calendar day")
            assertEquals(10L, item(a)[3], "same weekday last week")
            assertEquals(0L, item(b)[1], "B was not sold on the previous sale day")
            assertEquals(13L, item(b)[2])
            assertEquals(0L, item(b)[3])
            // every stat row belongs to an outlet of the SR's own routes
            val own = bundle["routes"]!!.jsonArray.map { it.jsonObject }.flatMap { r -> r["outlets"]!!.jsonArray.map { it.jsonObject["outlet_id"]!!.jsonPrimitive.content.toLong() } }.toSet()
            assertTrue(bundle["outlet_sku_stats"]!!.jsonArray.all { it.jsonObject["outlet_id"]!!.jsonPrimitive.content.toLong() in own }, "stats of own outlets only")
            // clusters: one row per cluster and own route, from the outlets of that route, in the zone of the route
            val routeIds = bundle["routes"]!!.jsonArray.map { it.jsonObject["route_id"]!!.jsonPrimitive.content.toLong() }.toSet()
            val clusters = bundle["clusters"]!!.jsonArray.map { it.jsonObject }
            assertTrue(clusters.isNotEmpty(), "the SR's routes have clusters")
            assertTrue(clusters.all { it["route_id"]!!.jsonPrimitive.content.toLong() in routeIds }, "only the SR's own routes")
            assertEquals(clusters.size, clusters.map { it["cluster_id"]!!.jsonPrimitive.content to it["route_id"]!!.jsonPrimitive.content }.toSet().size, "one row per cluster and route")
            for (c in clusters) {
                val onRoute = scalar("SELECT count(*) FROM app.outlet WHERE route_id = ${c["route_id"]!!.jsonPrimitive.content} AND cluster_id = ${c["cluster_id"]!!.jsonPrimitive.content} AND status = 'active'")!!.toLong()
                assertTrue(onRoute > 0, "a cluster listed for a route has an active outlet there")
            }
            // another role gets neither section filled
            for (other in listOf(amo, tso)) {
                val b2 = json(getAs(other, "/v1/sync/bundle").bodyAsText())
                assertEquals(0, b2["outlet_sku_stats"]!!.jsonArray.size, "stats are the SR's")
                assertEquals(0, b2["clusters"]!!.jsonArray.size, "clusters are the SR's")
            }
            // the other division's SR sees none of Mirpur's clusters or outlets
            val lkx = phone("lkxsr1001", "sr")
            val lb = json(getAs(lkx, "/v1/sync/bundle").bodyAsText())
            assertFalse(lb["clusters"]!!.jsonArray.any { it.jsonObject["route_id"]!!.jsonPrimitive.content.toLong() in routeIds }, "no Mirpur route in the other division's clusters")
            assertFalse(lb["outlet_sku_stats"]!!.jsonArray.any { it.jsonObject["outlet_id"]!!.jsonPrimitive.content.toLong() == outlet })
        }
    }
    private val fix = """{"purpose":"outlet_capture","fix_status":"ok","lat":23.8069,"lng":90.3687,"accuracy_m":12.0,"provider":"fused","is_mock":false,"reused":false,
        "device":{"device_owner":true,"dev_options_enabled":false,"adb_enabled":false,"auto_time_enabled":true,"mock_app_present":false}}"""

    @Test
    fun aClusterProposalIsStoredOnceVerifiedApprovedIntoALiveClusterAndShownInTheSrBundle() = world.withApi {
        with(world) {
            val route = one("SELECT id FROM app.route WHERE code = 'MIR-SR-D'")
            val zone = one("SELECT zone_id FROM app.route WHERE id = $route")
            val name = "Lane 7 Bazar " + UUID.randomUUID().toString().take(6)
            val req = UUID.randomUUID().toString()
            val payload = """{"request_type":"cluster_create","proposed":{"name":"  $name "},"photo_uuids":[],"fix":$fix}"""
            val first = sync(sr, listOf(record("outlet_change_request", payload, route, cu = req, family = req)))
            assertEquals("accepted", ack(first, req).status(), first.toString())
            repeat(2) { assertEquals("duplicate", ack(sync(sr, listOf(record("outlet_change_request", payload, route, cu = req, family = req))), req).status(), "a replay") }
            assertEquals(1L, scalar("SELECT count(*) FROM app.outlet_change_request WHERE client_uuid = '$req'")!!.toLong())
            // the same name from another request (while this one is open), and an existing cluster's name (case-insensitive), are refused for good with the clear code
            val twin = UUID.randomUUID().toString()
            val twinAck = ack(sync(sr, listOf(record("outlet_change_request", payload.replace(name, name.uppercase()), route, cu = twin, family = twin))), twin)
            assertEquals("rejected", twinAck.status(), twinAck.toString())
            assertEquals("cluster_name_exists", twinAck["code"]!!.jsonPrimitive.content)
            val existing = scalar("SELECT name FROM app.cluster WHERE zone_id = $zone ORDER BY id LIMIT 1")!!
            val dup = UUID.randomUUID().toString()
            val dupAck = ack(sync(sr, listOf(record("outlet_change_request", payload.replace(name, existing.lowercase()), route, cu = dup, family = dup))), dup)
            assertEquals("cluster_name_exists", dupAck["code"]!!.jsonPrimitive.content, dupAck.toString())
            assertEquals(0L, scalar("SELECT count(*) FROM app.outlet_change_request WHERE client_uuid IN ('$twin', '$dup')")!!.toLong(), "no second request row for a refused name")
            // the field record is NOT lost: a final rejection keeps the whole payload in sync_rejected for the helpdesk, and a resend gets the same final answer
            assertEquals(2L, scalar("SELECT count(*) FROM app.sync_rejected WHERE client_uuid IN ('$twin', '$dup') AND code = 'cluster_name_exists' AND payload IS NOT NULL")!!.toLong())
            assertEquals("cluster_name_exists", ack(sync(sr, listOf(record("outlet_change_request", payload.replace(name, name.uppercase()), route, cu = twin, family = twin))), twin)["code"]!!.jsonPrimitive.content, "the same uuid again: the same answer")
            // reach is the route's zone from the token: another division's AMO cannot see or verify the Mirpur proposal
            val lkxAmo = phone("lkxamo1001", "amo")
            val vc = UUID.randomUUID().toString()
            val verif = ack(sync(lkxAmo, listOf(record("outlet_request_verification", """{"request_uuid":"$req","decision":"verified","fix":$fix}""", one("SELECT id FROM app.route WHERE code LIKE 'LKX-%' AND kind = 'sr' ORDER BY id LIMIT 1"), cu = vc, family = vc))), vc)
            assertEquals("quarantined", verif.status(), verif.toString())
            assertEquals("scope_out_of_reach", verif["code"]!!.jsonPrimitive.content, "another division's AMO cannot verify the proposal")
            assertEquals("pending", scalar("SELECT status FROM app.outlet_change_request WHERE client_uuid = '$req'"))
            // a cluster proposal names no outlet
            val withOutlet = UUID.randomUUID().toString()
            val wo = ack(sync(sr, listOf(record("outlet_change_request", payload.replace("\"request_type\"", "\"outlet_id\":${one("SELECT min(id) FROM app.outlet")},\"request_type\"").replace(name, name + "x"), route, cu = withOutlet, family = withOutlet))), withOutlet)
            assertEquals("schema_invalid", wo["code"]!!.jsonPrimitive.content, wo.toString())
            // not in the bundle until approved
            suspend fun bundleClusters() = json(getAs(sr, "/v1/sync/bundle").bodyAsText())["clusters"]!!.jsonArray.map { it.jsonObject }
            assertFalse(bundleClusters().any { it["name"]!!.jsonPrimitive.content == name })
            // verified by the AMO (web), approved by the DMO / admin
            val sub = one("SELECT id FROM app.sub_channel WHERE status = 'active' ORDER BY id LIMIT 1")
            val verified = postWeb("admin1001", "/v1/outlet-requests/$req/verify", """{"sub_channel_id":$sub}""")
            assertEquals(HttpStatusCode.OK, verified.status, verified.bodyAsText())
            // a shop that points at the cluster request cannot be approved before the cluster is (409), and after it lands in the made cluster
            val shopReq = UUID.randomUUID().toString()
            val shop = """{"request_type":"new","proposed":{"name":"Cluster Shop ${UUID.randomUUID().toString().take(4)}","owner_name":"Karim Uddin","contact_number":"01955667788","cluster_request_uuid":"$req"},"photo_uuids":[],"fix":$fix}"""
            assertEquals("accepted", ack(sync(sr, listOf(record("outlet_change_request", shop, route, cu = shopReq, family = shopReq))), shopReq).status())
            postWeb("admin1001", "/v1/outlet-requests/$shopReq/verify", """{"sub_channel_id":$sub}""")
            val early = postWeb("superadmin1001", "/v1/outlet-requests/$shopReq/approve", """{"route_id":$route,"change_reason":"approve the shop before its cluster"}""")
            assertEquals(HttpStatusCode.Conflict, early.status, early.bodyAsText())
            assertTrue(early.bodyAsText().contains("cluster_request_pending"), early.bodyAsText())
            val approved = postWeb("superadmin1001", "/v1/outlet-requests/$req/approve", """{"route_id":$route,"change_reason":"the SR works lane 7 and it is a new bazar"}""")
            assertEquals(HttpStatusCode.OK, approved.status, approved.bodyAsText())
            val made = one("SELECT created_cluster_id FROM app.outlet_change_request WHERE client_uuid = '$req'")
            assertEquals(name, scalar("SELECT name FROM app.cluster WHERE id = $made"), "trimmed, live")
            assertEquals(zone, one("SELECT zone_id FROM app.cluster WHERE id = $made"), "in the route's zone")
            assertEquals("active", scalar("SELECT status FROM app.cluster WHERE id = $made"))
            assertEquals(made, json(approved.bodyAsText())["resulting_cluster_id"]!!.jsonPrimitive.content.toLong())
            assertTrue(bundleClusters().any { it["cluster_id"]!!.jsonPrimitive.content.toLong() == made && it["route_id"]!!.jsonPrimitive.content.toLong() == route }, "the bundle lists the made cluster for the route")
            // approving twice changes nothing; now the shop can be approved and sits in the new cluster
            assertEquals(HttpStatusCode.OK, postWeb("superadmin1001", "/v1/outlet-requests/$req/approve", """{"route_id":$route,"change_reason":"the SR works lane 7 and it is a new bazar"}""").status)
            assertEquals(1L, scalar("SELECT count(*) FROM app.cluster WHERE zone_id = $zone AND name = '$name'")!!.toLong())
            val ok = postWeb("superadmin1001", "/v1/outlet-requests/$shopReq/approve", """{"route_id":$route,"change_reason":"approve the shop now its cluster exists"}""")
            assertEquals(HttpStatusCode.OK, ok.status, ok.bodyAsText())
            assertEquals(made, one("SELECT o.cluster_id FROM app.outlet o JOIN app.outlet_change_request q ON q.created_outlet_id = o.id WHERE q.client_uuid = '$shopReq'"))
        }
    }
    @Test
    fun theJourneyNamesARepeatSaleOnlyWhileTheSwitchIsOnAndByThePhonesRule() = world.withApi {
        with(world) {
            val route = one("SELECT id FROM app.route WHERE code = 'MIR-SR-D'")
            val outlets = longs("SELECT id FROM app.outlet WHERE route_id = $route AND status = 'active' ORDER BY id LIMIT 2")
            val sku = scalar("SELECT code FROM app.sku WHERE status = 'active' ORDER BY id LIMIT 1")!!
            val d = today().toString()
            val srId = one("SELECT id FROM app.app_user WHERE username = 'sr1001'")
            // calls in time order: outlet A sale, outlet B sale, outlet A sale again, outlet A no memo
            val plan = listOf(Triple(outlets[0], true, 1), Triple(outlets[1], true, 2), Triple(outlets[0], true, 3), Triple(outlets[0], false, 4))
            val visits = plan.map { (outlet, sale, n) ->
                val v = UUID.randomUUID().toString()
                fresh.db.jdbi.useHandle<Exception> { h ->
                    h.execute("SET session_replication_role = replica")
                    try {
                        h.execute(
                            """INSERT INTO app.visit (client_uuid, family_uuid, business_date, user_id, route_id, captured_at, config_version, visit_kind, outlet_id, opened_at, ended_at, sequence_no, planned,
                                                      verdict, radius_m_used, max_accuracy_m_used, location_basis, geo_action)
                               VALUES ('$v'::uuid, '$v'::uuid, DATE '$d', $srId, $route, now(), 0, 'sr_call', $outlet, TIMESTAMPTZ '$d 04:0$n:00Z', TIMESTAMPTZ '$d 04:0$n:30Z', $n, true, 'in_range', 100, 50, 'master', 'sale_allowed')""",
                        )
                    } finally { h.execute("SET session_replication_role = origin") }
                }
                if (sale) memo(this, UUID.randomUUID().toString(), d, outlet, 50 + n, listOf(Triple(sku, "sale", 1)), visit = v)
                v
            }
            suspend fun statuses() = json(getAs(amo, "/v1/team/journey?user_id=$srId&route_id=$route&business_date=$d").also { assertEquals(HttpStatusCode.OK, it.status, it.bodyAsText()) }.bodyAsText())["calls"]!!.jsonArray
                .map { it.jsonObject }.filter { it["visit_uuid"]!!.jsonPrimitive.content in visits }.sortedBy { it["opened_at"]!!.jsonPrimitive.content }.map { it["status"]!!.jsonPrimitive.content }
            assertEquals(listOf("new_sale", "new_sale", "new_sale", "no_sale"), statuses(), "switch off (the shipped state): the value is never emitted")
            exec("INSERT INTO app.cfg_value (key, scope_type, scope_id, value, effective_from, config_version, reason) SELECT 'cfg.sale.journey_repeat_sale_enabled', 'global', 0, 'true'::jsonb, TIMESTAMPTZ '2000-01-01 00:00+00', max(config_version), 'test' FROM app.cfg_version")
            now.updateAndGet { it.plusSeconds(40) }   // the server's config snapshot lives 30 s
            try {
                assertEquals(listOf("new_sale", "new_sale", "repeat_sale", "no_sale"), statuses(), "on: a sold call at an outlet already sold earlier that day is repeat_sale, a call without a memo stays no_sale")
            } finally {
                exec("UPDATE app.cfg_value SET effective_to = TIMESTAMPTZ '2000-01-02 00:00+00' WHERE key = 'cfg.sale.journey_repeat_sale_enabled' AND effective_to IS NULL")
            }
        }
    }

    @Test
    fun thePosmSurveyReportListsAnswersInReachOnlyWithoutAMediaId() = world.withApi {
        with(world) {
            val route = one("SELECT id FROM app.route WHERE code = 'MIR-SR-D'")
            val outlet = one("SELECT id FROM app.outlet WHERE route_id = $route AND status = 'active' ORDER BY id LIMIT 1")
            val srId = one("SELECT id FROM app.app_user WHERE username = 'sr1001'")
            val d = today().toString()
            val survey = one("INSERT INTO app.survey (kind, valid_from, status) VALUES ('posm', DATE '$d' - 5, 'active') RETURNING id")
            exec("INSERT INTO app.survey_version (survey_id, version, title_en, questions) VALUES ($survey, 1, 'POSM', '[{\"question_id\":7,\"answer_type\":\"bool\",\"label_en\":\"Is the display up?\"}]'::jsonb)")
            val visit = UUID.randomUUID().toString(); val resp = UUID.randomUUID().toString(); val photo = UUID.randomUUID().toString()
            fresh.db.jdbi.useHandle<Exception> { h ->
                h.execute("SET session_replication_role = replica")
                try {
                    h.execute(
                        """INSERT INTO app.visit (client_uuid, family_uuid, business_date, user_id, route_id, captured_at, config_version, visit_kind, outlet_id, opened_at, sequence_no, planned,
                                                  verdict, radius_m_used, max_accuracy_m_used, location_basis, geo_action)
                           VALUES ('$visit'::uuid, '$visit'::uuid, DATE '$d', $srId, $route, now(), 0, 'sr_call', $outlet, now(), 1, true, 'in_range', 100, 50, 'master', 'sale_allowed')""",
                    )
                    h.execute(
                        """INSERT INTO app.survey_response (client_uuid, family_uuid, business_date, user_id, route_id, captured_at, config_version, visit_client_uuid, survey_id, survey_version, question_id,
                                                           answer_type, answer_bool, photo_uuid)
                           VALUES ('$resp'::uuid, '$resp'::uuid, DATE '$d', $srId, $route, now(), 0, '$visit'::uuid, $survey, 1, 7, 'bool', true, '$photo'::uuid)""",
                    )
                } finally { h.execute("SET session_replication_role = origin") }
            }
            suspend fun rowsFor(user: String): List<JsonObject> {
                val r = postWeb(user, "/v1/reports/posm-survey/query", """{"period":{"date":"$d"},"output":{"format":"json","page_size":100}}""")
                assertEquals(HttpStatusCode.OK, r.status, r.bodyAsText())
                return json(r.bodyAsText())["rows"]!!.jsonArray.map { it.jsonObject }
            }
            val seen = rowsFor("tso1001").filter { it["question"]?.jsonPrimitive?.content == "Is the display up?" }
            assertEquals(1, seen.size, "the TSO of the zone sees the answer")
            assertEquals("yes", seen.single()["answer"]!!.jsonPrimitive.content)
            assertEquals("true", seen.single()["photo_taken"]!!.jsonPrimitive.content)
            assertFalse(rowsFor("tso1001").toString().contains(photo), "the media id is never in the report")
            // another division's TSO sees nothing of it
            assertTrue(rowsFor("lkxtso1001").none { it["question"]?.jsonPrimitive?.content == "Is the display up?" }, "scope: another territory's TSO")
            // a voided answer disappears
            exec("UPDATE app.survey_response SET voided_at = now() WHERE client_uuid = '$resp'")
            assertTrue(rowsFor("tso1001").none { it["question"]?.jsonPrimitive?.content == "Is the display up?" })
        }
    }
    @Test
    fun anOutletYoungerThanTheWindowDividesByTheDaysSinceItsFirstMemoAndAHalfRoundsUp() = world.withApi {
        with(world) {
            val d = today()
            val outlet = longs("SELECT o.id FROM app.outlet o JOIN app.route_assignment a ON a.route_id = o.route_id JOIN app.app_user u ON u.id = a.user_id WHERE u.username = 'sr1001' AND o.status = 'active' ORDER BY o.id DESC LIMIT 1").single()
            val a = rows("SELECT code FROM app.sku WHERE status = 'active' ORDER BY id LIMIT 1").single()["code"] as String
            // first memo 10 days ago (10 sticks), a second 2 days ago (3 sticks): elapsed = 10, ADS = 13 x 100 / 10 = 130; a third outlet-less check of rounding below
            memo(this, UUID.randomUUID().toString(), d.minusDays(10).toString(), outlet, 21, listOf(Triple(a, "sale", 10)))
            memo(this, UUID.randomUUID().toString(), d.minusDays(2).toString(), outlet, 22, listOf(Triple(a, "sale", 3)))
            val skuA = one("SELECT id FROM app.sku WHERE code = '$a'")
            suspend fun ads(): Long {
                val e = json(getAs(sr, "/v1/sync/bundle").bodyAsText())["outlet_sku_stats"]!!.jsonArray.map { it.jsonObject }.single { it["outlet_id"]!!.jsonPrimitive.content.toLong() == outlet }
                var id = 0L
                return e["items"]!!.jsonArray.map { q -> q.jsonArray.map { it.jsonPrimitive.content.toLong() } }.map { q -> id += q[0]; listOf(id) + q.drop(1) }.single { it[0] == skuA }[2]
            }
            assertEquals(130L, ads(), "13 sticks over the 10 elapsed days since the first memo")
            memo(this, UUID.randomUUID().toString(), d.minusDays(1).toString(), outlet, 23, listOf(Triple(a, "sale", 1)))
            memo(this, UUID.randomUUID().toString(), d.minusDays(4).toString(), outlet, 24, listOf(Triple(a, "sale", 2)))
            assertEquals(160L, ads(), "16 sticks over 10 days")
            // half up: a younger outlet, 5 sticks since its first memo 8 days ago = 62.5 -> 63
            val young = longs("SELECT o.id FROM app.outlet o JOIN app.route_assignment x ON x.route_id = o.route_id JOIN app.app_user u ON u.id = x.user_id WHERE u.username = 'sr1001' AND o.status = 'active' AND o.id <> $outlet ORDER BY o.id DESC LIMIT 1").single()
            memo(this, UUID.randomUUID().toString(), d.minusDays(8).toString(), young, 25, listOf(Triple(a, "sale", 2)))
            memo(this, UUID.randomUUID().toString(), d.minusDays(3).toString(), young, 26, listOf(Triple(a, "sale", 3)))
            val e = json(getAs(sr, "/v1/sync/bundle").bodyAsText())["outlet_sku_stats"]!!.jsonArray.map { it.jsonObject }.single { it["outlet_id"]!!.jsonPrimitive.content.toLong() == young }
            assertEquals(63L, e["items"]!!.jsonArray.single().jsonArray[2].jsonPrimitive.content.toLong(), "62.5 rounds half up")
        }
    }
    @Test
    fun aSupersededMemoAndAVoidedLineAreLeftOutAnSkuDeltaLargerThanOneDecodesAndTwoBuildsOfUnchangedDataGiveTheSameVersion() = world.withApi {
        with(world) {
            val d = today()
            val outlet = longs("SELECT o.id FROM app.outlet o JOIN app.route_assignment a ON a.route_id = o.route_id JOIN app.app_user u ON u.id = a.user_id WHERE u.username = 'sr1001' AND o.status = 'active' ORDER BY o.id LIMIT 1 OFFSET 1").single()
            val codes = rows("SELECT code, id FROM app.sku WHERE status = 'active' ORDER BY id")
            val first = codes[0]; val third = codes[2]   // ids with a gap: the delta of the second item is larger than 1
            assertTrue((third["id"] as Number).toLong() - (first["id"] as Number).toLong() > 1)
            val superseded = UUID.randomUUID().toString(); val replacement = UUID.randomUUID().toString(); val withVoidedLine = UUID.randomUUID().toString()
            memo(this, superseded, d.minusDays(5).toString(), outlet, 31, listOf(Triple(first["code"] as String, "sale", 40)))
            memo(this, replacement, d.minusDays(5).toString(), outlet, 32, listOf(Triple(first["code"] as String, "sale", 5)))
            memo(this, withVoidedLine, d.minusDays(2).toString(), outlet, 33, listOf(Triple(first["code"] as String, "sale", 2), Triple(third["code"] as String, "sale", 7), Triple(third["code"] as String, "sale", 900)))
            fresh.db.jdbi.useHandle<Exception> { h ->
                h.execute("SET session_replication_role = replica")
                try {
                    h.execute("UPDATE app.memo SET status = 'superseded', superseded_by_client_uuid = CAST('$replacement' AS uuid) WHERE client_uuid = CAST('$superseded' AS uuid)")
                    h.execute("UPDATE app.memo_line SET voided_at = now() WHERE memo_client_uuid = CAST('$withVoidedLine' AS uuid) AND qty_base = 900")
                } finally { h.execute("SET session_replication_role = origin") }
            }
            val bundle = json(getAs(sr, "/v1/sync/bundle").bodyAsText())
            val e = bundle["outlet_sku_stats"]!!.jsonArray.map { it.jsonObject }.single { it["outlet_id"]!!.jsonPrimitive.content.toLong() == outlet }
            val raw = e["items"]!!.jsonArray.map { q -> q.jsonArray.map { it.jsonPrimitive.content.toLong() } }
            assertEquals(2, raw.size)
            assertEquals((third["id"] as Number).toLong() - (first["id"] as Number).toLong(), raw[1][0], "the second item carries the DIFFERENCE of the ids, larger than 1")
            var id = 0L
            val decoded = raw.map { q -> id += q[0]; listOf(id) + q.drop(1) }
            assertEquals(listOf((first["id"] as Number).toLong(), (third["id"] as Number).toLong()), decoded.map { it[0] })
            assertEquals(d.minusDays(2).toString(), e["previous_day"]!!.jsonPrimitive.content)
            // window d-30..d-1, first selling memo d-5 -> elapsed 5; first: 5 (replacement) + 2 = 7 -> 140; third: 7 only (the 900 line is voided) -> 140
            assertEquals(listOf(2L, 140L, 0L), decoded[0].drop(1), "the superseded memo (40) is out; the replacement (5) and the 2 count")
            assertEquals(listOf(7L, 140L, 0L), decoded[1].drop(1), "a voided line (900) is out")
            // digest stability: unchanged data gives the same bundle version and a 304 on the cursor (two builds)
            val again = json(getAs(sr, "/v1/sync/bundle").bodyAsText())
            assertEquals(bundle["meta"]!!.jsonObject["bundle_version"], again["meta"]!!.jsonObject["bundle_version"], "two builds of unchanged data: one version")
            assertEquals(bundle["outlet_sku_stats"], again["outlet_sku_stats"]); assertEquals(bundle["clusters"], again["clusters"])
        }
    }
    @Test
    fun theCapsLeaveTheSectionInsideTheContractAndSayTheyTruncated() {
        val six = (1L..601L).map { it }
        val capped = six.take(com.aktcl.aron.backend.sync.BundleService.MAX_CLUSTERS)
        assertEquals(600, capped.size)
        assertEquals(600, com.aktcl.aron.backend.sync.BundleService.MAX_CLUSTERS)
        assertEquals(400, com.aktcl.aron.backend.sync.BundleService.MAX_STATS_OUTLETS)
        val c = com.aktcl.aron.backend.sync.BundleService.capRows(six, 600)
        assertEquals(600, c.first.size); assertTrue(c.second, "601 rows: truncated"); assertEquals(1L, c.first.first(), "the first rows stay (fixed order)")
        val ok = com.aktcl.aron.backend.sync.BundleService.capRows(six.take(600), 600)
        assertEquals(600, ok.first.size); assertFalse(ok.second, "exactly at the cap: not truncated")
    }
    @Test
    fun theWorstCaseStatsAndClustersSectionsStayInsideTheirBudgets() {
        // docs/04: outlet_sku_stats at most 150 KB raw and 20 KB gzip at 70 outlets x 40 SKUs, every pair dense (every member present and non-zero); clusters small.
        val rnd = java.util.Random(7)
        val rows = (1L..70L).map { o -> com.aktcl.aron.backend.sync.OutletSkuStat(1000 + o, "2026-11-10", (1L..40L).map { k ->
            listOf(if (k == 1L) 201L else 1L, 1L + rnd.nextInt(200), 1L + rnd.nextInt(20000), 1L + rnd.nextInt(200)) }) }
        val enc = com.aktcl.aron.backend.platform.ResponseJson
        val json = enc.encodeToString(kotlinx.serialization.builtins.ListSerializer(com.aktcl.aron.backend.sync.OutletSkuStat.serializer()), rows)
        fun gz(t: String) = java.io.ByteArrayOutputStream().also { java.util.zip.GZIPOutputStream(it).use { z -> z.write(t.toByteArray()) } }.size()
        println("MEASURED outlet_sku_stats 70 outlets x 40 SKUs dense: raw=${json.length} bytes, gzip=${gz(json)} bytes")
        assertTrue(json.length <= 150_000, "raw ${json.length}")
        assertTrue(gz(json) <= 20_000, "gzip ${gz(json)}")
        val clusters = (1L..600L).map { com.aktcl.aron.backend.sync.BundleCluster(it, "Cluster number $it of the zone", 1 + it % 6, "Route name $it") }
        val cj = enc.encodeToString(kotlinx.serialization.builtins.ListSerializer(com.aktcl.aron.backend.sync.BundleCluster.serializer()), clusters)
        println("MEASURED clusters 600 rows (contract maximum): raw=${cj.length} bytes, gzip=${gz(cj)} bytes")
        assertTrue(cj.length <= 80_000 && gz(cj) <= 8_000, "clusters raw ${cj.length} gzip ${gz(cj)}")
    }
    @Test
    fun aShopThatNamesARefusedClusterRequestCannotBeApprovedUntilTheApproverChoosesAnotherCluster() = world.withApi {
        with(world) {
            val route = one("SELECT id FROM app.route WHERE code = 'MIR-SR-D'")
            val sub = one("SELECT id FROM app.sub_channel WHERE status = 'active' ORDER BY id LIMIT 1")
            val cr = UUID.randomUUID().toString(); val shopReq = UUID.randomUUID().toString()
            val name = "Refused Bazar " + UUID.randomUUID().toString().take(5)
            val c = sync(sr, listOf(record("outlet_change_request", """{"request_type":"cluster_create","proposed":{"name":"$name"},"photo_uuids":[],"fix":$fix}""", route, cu = cr, family = cr)))
            assertEquals("accepted", ack(c, cr).status())
            val shop = """{"request_type":"new","proposed":{"name":"Refused Shop ${UUID.randomUUID().toString().take(4)}","owner_name":"Rahim Uddin","contact_number":"01966778899","cluster_request_uuid":"$cr"},"photo_uuids":[],"fix":$fix}"""
            assertEquals("accepted", ack(sync(sr, listOf(record("outlet_change_request", shop, route, cu = shopReq, family = shopReq))), shopReq).status(), "the shop record is kept whatever the cluster request does")
            postWeb("admin1001", "/v1/outlet-requests/$shopReq/verify", """{"sub_channel_id":$sub}""")
            val rej = postWeb("superadmin1001", "/v1/outlet-requests/$cr/reject", """{"reason":"not a real cluster, use the existing one"}""")
            assertEquals(HttpStatusCode.OK, rej.status, rej.bodyAsText())
            assertEquals(0L, scalar("SELECT count(*) FROM app.cluster WHERE name = '$name'")!!.toLong(), "a refused request makes no cluster")
            val r = postWeb("superadmin1001", "/v1/outlet-requests/$shopReq/approve", """{"route_id":$route,"change_reason":"approve the shop with its refused cluster"}""")
            assertEquals(HttpStatusCode.Conflict, r.status, r.bodyAsText())
            assertTrue(r.bodyAsText().contains("cluster_request_refused"), r.bodyAsText())
            assertEquals("verified", scalar("SELECT status FROM app.outlet_change_request WHERE client_uuid = '$shopReq'"), "the shop request is unchanged and waits for the approver")
        }
    }

    // ---------------------------------------------------------------------------------------------- review R3: no shop request can be stuck

    private val rnd = java.util.Random(11)
    private fun phoneNo() = "0191" + (1000000 + rnd.nextInt(8999999))
    private fun shopJson(link: String? = null, clusterId: Long? = null) =
        """{"request_type":"new","proposed":{"name":"Stuck Shop ${UUID.randomUUID().toString().take(6)}","owner_name":"Rahim Uddin","contact_number":"${phoneNo()}"${if (clusterId != null) ",\"cluster_id\":$clusterId" else ""}${if (link != null) ",\"cluster_request_uuid\":\"$link\"" else ""}},"photo_uuids":[],"fix":$fix}"""
    private fun events(w: TsoWorld, u: String) = w.rows("SELECT event, note FROM app.outlet_request_event WHERE request_uuid = '$u' ORDER BY id").map { it["event"] as String to it["note"] as String? }
    private fun proposedOf(w: TsoWorld, u: String): String = w.scalar("SELECT proposed::text FROM app.outlet_change_request WHERE client_uuid = '$u'")!!

    @Test
    fun aShopWhoseClusterRequestWasRefusedLapsedOrDiscardedIsApprovedWithTheApproversClusterAndTheTrailSaysWho() = world.withApi {
        with(world) {
            val route = one("SELECT id FROM app.route WHERE code = 'MIR-SR-D'")
            val zone = one("SELECT zone_id FROM app.route WHERE id = $route")
            val sub = one("SELECT id FROM app.sub_channel WHERE status = 'active' ORDER BY id LIMIT 1")
            val live = one("SELECT id FROM app.cluster WHERE zone_id = $zone AND status = 'active' ORDER BY id LIMIT 1")
            for (state in listOf("rejected", "lapsed", "discarded")) {
                val cr = UUID.randomUUID().toString(); val shopReq = UUID.randomUUID().toString()
                assertEquals("accepted", ack(sync(sr, listOf(record("outlet_change_request", """{"request_type":"cluster_create","proposed":{"name":"Gone Bazar $state ${UUID.randomUUID().toString().take(5)}"},"photo_uuids":[],"fix":$fix}""", route, cu = cr, family = cr))), cr).status())
                assertEquals("accepted", ack(sync(sr, listOf(record("outlet_change_request", shopJson(link = cr), route, cu = shopReq, family = shopReq))), shopReq).status(), "$state: the shop is stored whatever happens to its cluster request")
                postWeb("admin1001", "/v1/outlet-requests/$shopReq/verify", """{"sub_channel_id":$sub}""")
                // pending first: the 409 for a pending cluster request also says the approver may give a cluster
                val pending = postWeb("superadmin1001", "/v1/outlet-requests/$shopReq/approve", """{"route_id":$route,"change_reason":"approve while the cluster request is pending"}""")
                assertEquals(HttpStatusCode.Conflict, pending.status, pending.bodyAsText()); assertTrue(pending.bodyAsText().contains("cluster_request_pending") && pending.bodyAsText().contains("cluster_id"), pending.bodyAsText())
                when (state) {
                    "rejected" -> assertEquals(HttpStatusCode.OK, postWeb("superadmin1001", "/v1/outlet-requests/$cr/reject", """{"reason":"not a real cluster, use the existing one"}""").status)
                    else -> fresh.db.jdbi.useHandle<Exception> { h -> h.execute("SET session_replication_role = replica"); try { h.execute("UPDATE app.outlet_change_request SET status = '$state' WHERE client_uuid = '$cr'") } finally { h.execute("SET session_replication_role = origin") } }
                }
                val stuck = postWeb("superadmin1001", "/v1/outlet-requests/$shopReq/approve", """{"route_id":$route,"change_reason":"approve with the $state cluster request"}""")
                assertEquals(HttpStatusCode.Conflict, stuck.status, stuck.bodyAsText())
                assertTrue(stuck.bodyAsText().contains("cluster_request_refused") && stuck.bodyAsText().contains("give `cluster_id`"), "the 409 says what to do: ${stuck.bodyAsText()}")
                assertEquals("verified", scalar("SELECT status FROM app.outlet_change_request WHERE client_uuid = '$shopReq'"))
                // another zone's, an inactive and an unknown cluster are refused as 400 and change nothing
                val elsewhere = one("SELECT id FROM app.cluster WHERE zone_id <> $zone AND status = 'active' ORDER BY id LIMIT 1")
                val inactive = one("INSERT INTO app.cluster (zone_id, name, status) VALUES ($zone, 'Retired $state ${UUID.randomUUID().toString().take(5)}', 'inactive') RETURNING id")
                for (bad in listOf(elsewhere, inactive, 987654321L)) {
                    val r = postWeb("superadmin1001", "/v1/outlet-requests/$shopReq/approve", """{"route_id":$route,"cluster_id":$bad,"change_reason":"approve with a bad cluster"}""")
                    assertEquals(HttpStatusCode.BadRequest, r.status, "$bad: ${r.bodyAsText()}"); assertTrue(r.bodyAsText().contains("cluster_id"), r.bodyAsText())
                }
                assertEquals("verified", scalar("SELECT status FROM app.outlet_change_request WHERE client_uuid = '$shopReq'"))
                val ok = postWeb("superadmin1001", "/v1/outlet-requests/$shopReq/approve", """{"route_id":$route,"cluster_id":$live,"change_reason":"approve with the approver's cluster"}""")
                assertEquals(HttpStatusCode.OK, ok.status, ok.bodyAsText())
                assertEquals(live, one("SELECT o.cluster_id FROM app.outlet o JOIN app.outlet_change_request q ON q.created_outlet_id = o.id WHERE q.client_uuid = '$shopReq'"), "$state: the shop sits in the approver's cluster")
                val approvedEvent = events(this, shopReq).single { it.first == "approved" }
                assertTrue(approvedEvent.second!!.startsWith("cluster_id=$live chosen by the approver"), "the trail names the cluster the approver chose: ${approvedEvent.second}")
                assertEquals(1L, scalar("SELECT count(*) FROM app.outlet_request_event WHERE request_uuid = '$shopReq' AND event = 'approved' AND actor_user_id IS NOT NULL")!!.toLong(), "with the approver recorded")
                // a replay of the approval changes nothing
                assertEquals(HttpStatusCode.OK, postWeb("superadmin1001", "/v1/outlet-requests/$shopReq/approve", """{"route_id":$route,"cluster_id":$live,"change_reason":"approve with the approver's cluster"}""").status)
                assertEquals(1L, scalar("SELECT count(*) FROM app.outlet_change_request q JOIN app.outlet o ON o.id = q.created_outlet_id WHERE q.client_uuid = '$shopReq'")!!.toLong())
            }
            // cluster_id belongs to a new shop and a cluster move only
            val cr = UUID.randomUUID().toString()
            sync(sr, listOf(record("outlet_change_request", """{"request_type":"cluster_create","proposed":{"name":"Typed Bazar ${UUID.randomUUID().toString().take(5)}"},"photo_uuids":[],"fix":$fix}""", route, cu = cr, family = cr)))
            postWeb("admin1001", "/v1/outlet-requests/$cr/verify", """{"sub_channel_id":$sub}""")
            val na = postWeb("superadmin1001", "/v1/outlet-requests/$cr/approve", """{"route_id":$route,"cluster_id":$live,"change_reason":"a cluster request takes no cluster"}""")
            assertEquals(HttpStatusCode.BadRequest, na.status, na.bodyAsText()); assertTrue(na.bodyAsText().contains("not_applicable"), na.bodyAsText())
        }
    }

    @Test
    fun aShopWhoseClusterLinkOrClusterTheServerCannotHonourIsStoredFlaggedAndApprovableWithTheApproversCluster() = world.withApi {
        with(world) {
            val route = one("SELECT id FROM app.route WHERE code = 'MIR-SR-D'")
            val zone = one("SELECT zone_id FROM app.route WHERE id = $route")
            val sub = one("SELECT id FROM app.sub_channel WHERE status = 'active' ORDER BY id LIMIT 1")
            val live = one("SELECT id FROM app.cluster WHERE zone_id = $zone AND status = 'active' ORDER BY id LIMIT 1")
            suspend fun approvedThroughTheApprover(shopReq: String) {
                postWeb("admin1001", "/v1/outlet-requests/$shopReq/verify", """{"sub_channel_id":$sub}""")
                val none = postWeb("superadmin1001", "/v1/outlet-requests/$shopReq/approve", """{"route_id":$route,"change_reason":"approve with no cluster at all"}""")
                assertEquals(HttpStatusCode.Conflict, none.status, none.bodyAsText()); assertTrue(none.bodyAsText().contains("cluster_required") || none.bodyAsText().contains("cluster_request_refused"), none.bodyAsText())
                val ok = postWeb("superadmin1001", "/v1/outlet-requests/$shopReq/approve", """{"route_id":$route,"cluster_id":$live,"change_reason":"approve with the approver's cluster"}""")
                assertEquals(HttpStatusCode.OK, ok.status, ok.bodyAsText())
                assertEquals(live, one("SELECT o.cluster_id FROM app.outlet o JOIN app.outlet_change_request q ON q.created_outlet_id = o.id WHERE q.client_uuid = '$shopReq'"))
            }
            // (1) the cluster request is refused AT INGEST (name of an existing cluster) in the same offline batch as the shop that names it
            val existing = scalar("SELECT name FROM app.cluster WHERE zone_id = $zone ORDER BY id LIMIT 1")!!
            val cr = UUID.randomUUID().toString(); val shop1 = UUID.randomUUID().toString()
            val batch = sync(sr, listOf(
                record("outlet_change_request", """{"request_type":"cluster_create","proposed":{"name":"${existing.lowercase()}"},"photo_uuids":[],"fix":$fix}""", route, cu = cr, family = cr),
                record("outlet_change_request", shopJson(link = cr), route, cu = shop1, family = shop1),
            ))
            assertEquals("rejected", ack(batch, cr).status()); assertEquals("accepted", ack(batch, shop1).status(), "the shop is never refused for its cluster link")
            assertFalse(proposedOf(this, shop1).contains("cluster_request_uuid"), "the dead link is dropped from the stored proposal")
            assertTrue(events(this, shop1).any { it.first == "flag_cluster_dropped" && it.second!!.contains("cluster_request_uuid=$cr") }, events(this, shop1).toString())
            approvedThroughTheApprover(shop1)
            // (2) a cluster id the server does not know is dropped and flagged (train 1), the shop is approvable the same way
            val shop2 = UUID.randomUUID().toString()
            assertEquals("accepted", ack(sync(sr, listOf(record("outlet_change_request", shopJson(clusterId = 987654399L), route, cu = shop2, family = shop2))), shop2).status())
            assertTrue(events(this, shop2).any { it.first == "flag_cluster_dropped" }, events(this, shop2).toString())
            approvedThroughTheApprover(shop2)
            // (3) another division's SR made a cluster request: its uuid is not a Mirpur SR's to use (other user, other zone): stored, link dropped, flagged
            val lkx = phone("lkxsr1001", "sr")
            val lkxRoute = one("SELECT id FROM app.route WHERE code LIKE 'LKX-%' AND kind = 'sr' ORDER BY id LIMIT 1")
            val foreign = UUID.randomUUID().toString()
            assertEquals("accepted", ack(sync(lkx, listOf(record("outlet_change_request", """{"request_type":"cluster_create","proposed":{"name":"Foreign Bazar ${UUID.randomUUID().toString().take(5)}"},"photo_uuids":[],"fix":$fix}""", lkxRoute, cu = foreign, family = foreign))), foreign).status())
            val shop3 = UUID.randomUUID().toString()
            assertEquals("accepted", ack(sync(sr, listOf(record("outlet_change_request", shopJson(link = foreign), route, cu = shop3, family = shop3))), shop3).status())
            assertFalse(proposedOf(this, shop3).contains("cluster_request_uuid")); assertTrue(events(this, shop3).any { it.first == "flag_cluster_dropped" })
            // (4) a link that names a request which is not a cluster_create: dropped
            val notCluster = UUID.randomUUID().toString()
            val shop4 = UUID.randomUUID().toString()
            assertEquals("accepted", ack(sync(sr, listOf(record("outlet_change_request", shopJson(), route, cu = notCluster, family = notCluster), record("outlet_change_request", shopJson(link = notCluster), route, cu = shop4, family = shop4))), shop4).status())
            assertFalse(proposedOf(this, shop4).contains("cluster_request_uuid"))
            // (5) both sent: a usable cluster_id WINS, the link is dropped and flagged; the shop is approved straight away with that cluster
            val own = UUID.randomUUID().toString(); val shop5 = UUID.randomUUID().toString()
            sync(sr, listOf(record("outlet_change_request", """{"request_type":"cluster_create","proposed":{"name":"Both Bazar ${UUID.randomUUID().toString().take(5)}"},"photo_uuids":[],"fix":$fix}""", route, cu = own, family = own)))
            assertEquals("accepted", ack(sync(sr, listOf(record("outlet_change_request", shopJson(link = own, clusterId = live), route, cu = shop5, family = shop5))), shop5).status())
            val p5 = proposedOf(this, shop5)
            assertTrue(p5.contains("\"cluster_id\": $live") || p5.contains("\"cluster_id\":$live"), p5); assertFalse(p5.contains("cluster_request_uuid"), p5)
            assertTrue(events(this, shop5).any { it.first == "flag_cluster_dropped" && it.second!!.contains("cluster_request_uuid=$own") })
            postWeb("admin1001", "/v1/outlet-requests/$shop5/verify", """{"sub_channel_id":$sub}""")
            assertEquals(HttpStatusCode.OK, postWeb("superadmin1001", "/v1/outlet-requests/$shop5/approve", """{"route_id":$route,"change_reason":"approve with the cluster id that won"}""").status)
            // (6) out of order: the shop arrives BEFORE its own cluster request: the link is kept (not seen yet) and works once that request is approved
            val late = UUID.randomUUID().toString(); val shop6 = UUID.randomUUID().toString()
            assertEquals("accepted", ack(sync(sr, listOf(record("outlet_change_request", shopJson(link = late), route, cu = shop6, family = shop6))), shop6).status())
            assertTrue(proposedOf(this, shop6).contains(late), "an unseen uuid is kept: the cluster request may arrive in a later batch")
            assertTrue(events(this, shop6).none { it.first == "flag_cluster_dropped" })
            assertEquals("accepted", ack(sync(sr, listOf(record("outlet_change_request", """{"request_type":"cluster_create","proposed":{"name":"Late Bazar ${UUID.randomUUID().toString().take(5)}"},"photo_uuids":[],"fix":$fix}""", route, cu = late, family = late))), late).status())
            postWeb("admin1001", "/v1/outlet-requests/$late/verify", """{"sub_channel_id":$sub}""")
            postWeb("admin1001", "/v1/outlet-requests/$shop6/verify", """{"sub_channel_id":$sub}""")
            assertEquals(HttpStatusCode.OK, postWeb("superadmin1001", "/v1/outlet-requests/$late/approve", """{"route_id":$route,"change_reason":"approve the late cluster"}""").status)
            assertEquals(HttpStatusCode.OK, postWeb("superadmin1001", "/v1/outlet-requests/$shop6/approve", """{"route_id":$route,"change_reason":"approve the shop after its cluster"}""").status)
            assertEquals(one("SELECT created_cluster_id FROM app.outlet_change_request WHERE client_uuid = '$late'"), one("SELECT o.cluster_id FROM app.outlet o JOIN app.outlet_change_request q ON q.created_outlet_id = o.id WHERE q.client_uuid = '$shop6'"))
        }
    }

    @Test
    fun clusterNamesAreUniquePerZoneInTheDatabaseWhateverTheCaseSpacingOrComposition() = world.withApi {
        with(world) {
            val zone = one("SELECT zone_id FROM app.route WHERE code = 'MIR-SR-D'")
            val base = "Norm Test " + UUID.randomUUID().toString().take(5)
            fresh.db.jdbi.useHandle<Exception> { h -> h.execute("INSERT INTO app.cluster (zone_id, name) VALUES ($zone, '$base')") }
            for (variant in listOf(base.uppercase(), "  $base  ", base.replace(" ", "\u00a0"), base.replace(" ", " \u2003 "))) {
                val e = org.junit.jupiter.api.assertThrows<Exception> { fresh.db.jdbi.useHandle<Exception> { h -> h.createUpdate("INSERT INTO app.cluster (zone_id, name) VALUES (:z, :n)").bind("z", zone).bind("n", variant).execute() } }
                assertTrue(generateSequence<Throwable>(e) { it.cause }.any { it.message?.contains("cluster_zone_name_norm_key") == true }, "$variant: ${e.message}")
            }
            // composed and decomposed forms of the same Bangla / accented letter are one name; another zone may use the name
            fresh.db.jdbi.useHandle<Exception> { h -> h.execute("INSERT INTO app.cluster (zone_id, name) VALUES ($zone, 'Caf\u00e9 $base')") }
            assertTrue(runCatching { fresh.db.jdbi.useHandle<Exception> { h -> h.execute("INSERT INTO app.cluster (zone_id, name) VALUES ($zone, 'Cafe\u0301 $base')") } }.isFailure, "NFC: composed and decomposed are the same name")
            val other = one("SELECT id FROM app.zone WHERE id <> $zone ORDER BY id LIMIT 1")
            fresh.db.jdbi.useHandle<Exception> { h -> h.execute("INSERT INTO app.cluster (zone_id, name) VALUES ($other, '${base.uppercase()}')") }
            // the approval of a cluster request whose name was taken in between answers cluster_name_exists (409), not a 500
            val route = one("SELECT id FROM app.route WHERE code = 'MIR-SR-D'"); val sub = one("SELECT id FROM app.sub_channel WHERE status = 'active' ORDER BY id LIMIT 1")
            val taken = "Taken Later " + UUID.randomUUID().toString().take(5)
            val cr = UUID.randomUUID().toString()
            assertEquals("accepted", ack(sync(sr, listOf(record("outlet_change_request", """{"request_type":"cluster_create","proposed":{"name":"$taken"},"photo_uuids":[],"fix":$fix}""", route, cu = cr, family = cr))), cr).status())
            postWeb("admin1001", "/v1/outlet-requests/$cr/verify", """{"sub_channel_id":$sub}""")
            fresh.db.jdbi.useHandle<Exception> { h -> h.execute("INSERT INTO app.cluster (zone_id, name) VALUES ($zone, '  ${taken.uppercase()} ')") }
            val r = postWeb("superadmin1001", "/v1/outlet-requests/$cr/approve", """{"route_id":$route,"change_reason":"approve a cluster whose name was taken meanwhile"}""")
            assertEquals(HttpStatusCode.Conflict, r.status, r.bodyAsText()); assertTrue(r.bodyAsText().contains("cluster_name_exists"), r.bodyAsText())
        }
    }

    @Test
    fun aRetryWithAnotherClusterIsAConflictAnInactiveNameTellsTheApproverToReactivateAndThePortalReportsTheNameField() = world.withApi {
        with(world) {
            val route = one("SELECT id FROM app.route WHERE code = 'MIR-SR-D'"); val zone = one("SELECT zone_id FROM app.route WHERE id = $route")
            val sub = one("SELECT id FROM app.sub_channel WHERE status = 'active' ORDER BY id LIMIT 1")
            val clusters = longs("SELECT id FROM app.cluster WHERE zone_id = $zone AND status = 'active' ORDER BY id LIMIT 2")
            // (1) approve a shop with cluster A; the same approver retrying with cluster B is a 409, with A (or none) it is the same 200
            val shop = UUID.randomUUID().toString()
            assertEquals("accepted", ack(sync(sr, listOf(record("outlet_change_request", shopJson(clusterId = 987654398L), route, cu = shop, family = shop))), shop).status())
            postWeb("admin1001", "/v1/outlet-requests/$shop/verify", """{"sub_channel_id":$sub}""")
            val first = postWeb("superadmin1001", "/v1/outlet-requests/$shop/approve", """{"route_id":$route,"cluster_id":${clusters[0]},"change_reason":"approve with the first cluster"}""")
            assertEquals(HttpStatusCode.OK, first.status, first.bodyAsText())
            assertEquals(HttpStatusCode.OK, postWeb("superadmin1001", "/v1/outlet-requests/$shop/approve", """{"route_id":$route,"cluster_id":${clusters[0]},"change_reason":"approve with the first cluster"}""").status)
            assertEquals(HttpStatusCode.OK, postWeb("superadmin1001", "/v1/outlet-requests/$shop/approve", """{"route_id":$route,"change_reason":"approve with the first cluster"}""").status)
            val other = postWeb("superadmin1001", "/v1/outlet-requests/$shop/approve", """{"route_id":$route,"cluster_id":${clusters[1]},"change_reason":"approve with the second cluster"}""")
            assertEquals(HttpStatusCode.Conflict, other.status, other.bodyAsText()); assertTrue(other.bodyAsText().contains("already_approved_other_cluster"), other.bodyAsText())
            assertEquals(clusters[0], one("SELECT o.cluster_id FROM app.outlet o JOIN app.outlet_change_request q ON q.created_outlet_id = o.id WHERE q.client_uuid = '$shop'"), "the outlet kept the first cluster")
            // (2) a cluster request whose made cluster went inactive is not a usable cluster for the shop
            val cr = UUID.randomUUID().toString(); val shop2 = UUID.randomUUID().toString()
            sync(sr, listOf(record("outlet_change_request", """{"request_type":"cluster_create","proposed":{"name":"Soon Inactive ${UUID.randomUUID().toString().take(5)}"},"photo_uuids":[],"fix":$fix}""", route, cu = cr, family = cr)))
            sync(sr, listOf(record("outlet_change_request", shopJson(link = cr), route, cu = shop2, family = shop2)))
            postWeb("admin1001", "/v1/outlet-requests/$cr/verify", """{"sub_channel_id":$sub}"""); postWeb("admin1001", "/v1/outlet-requests/$shop2/verify", """{"sub_channel_id":$sub}""")
            assertEquals(HttpStatusCode.OK, postWeb("superadmin1001", "/v1/outlet-requests/$cr/approve", """{"route_id":$route,"change_reason":"approve the soon inactive cluster"}""").status)
            fresh.db.jdbi.useHandle<Exception> { h -> h.execute("UPDATE app.cluster SET status = 'inactive' WHERE id = (SELECT created_cluster_id FROM app.outlet_change_request WHERE client_uuid = '$cr')") }
            val dead = postWeb("superadmin1001", "/v1/outlet-requests/$shop2/approve", """{"route_id":$route,"change_reason":"approve with an inactive cluster"}""")
            assertEquals(HttpStatusCode.Conflict, dead.status, dead.bodyAsText()); assertTrue(dead.bodyAsText().contains("give `cluster_id`"), dead.bodyAsText())
            // (3) a name that belongs to an INACTIVE cluster: the SR's request is stored (the bundle never shows an inactive cluster), the approver is told to reactivate it
            val inactiveName = "Dormant Bazar " + UUID.randomUUID().toString().take(5)
            fresh.db.jdbi.useHandle<Exception> { h -> h.execute("INSERT INTO app.cluster (zone_id, name, status) VALUES ($zone, '$inactiveName', 'inactive')") }
            val cr3 = UUID.randomUUID().toString()
            assertEquals("accepted", ack(sync(sr, listOf(record("outlet_change_request", """{"request_type":"cluster_create","proposed":{"name":"${inactiveName.uppercase()}"},"photo_uuids":[],"fix":$fix}""", route, cu = cr3, family = cr3))), cr3).status(), "no refusal naming a cluster the SR never sees")
            postWeb("admin1001", "/v1/outlet-requests/$cr3/verify", """{"sub_channel_id":$sub}""")
            val re = postWeb("superadmin1001", "/v1/outlet-requests/$cr3/approve", """{"route_id":$route,"change_reason":"approve a name that an inactive cluster holds"}""")
            assertEquals(HttpStatusCode.Conflict, re.status, re.bodyAsText()); assertTrue(re.bodyAsText().contains("cluster_name_inactive") && re.bodyAsText().contains("reactivate"), re.bodyAsText())
            // (4) the portal's own cluster create reports a normalised duplicate on the name field
            val dup = postWeb("superadmin1001", "/v1/admin/clusters", """{"zone_id":$zone,"name":"  ${inactiveName.lowercase()} "}""")
            assertTrue(dup.status == HttpStatusCode.Conflict || dup.status == HttpStatusCode.BadRequest, "${dup.status} ${dup.bodyAsText()}")
            assertTrue(dup.bodyAsText().contains("body.name"), dup.bodyAsText())
        }
    }
}
