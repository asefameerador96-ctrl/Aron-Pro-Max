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
            val stats = bundle["outlet_sku_stats"]!!.jsonArray.map { it.jsonObject }.filter { it["outlet_id"]!!.jsonPrimitive.content.toLong() == outlet }
            fun row(code: String): JsonObject = stats.single { it["sku_id"]!!.jsonPrimitive.content.toLong() == skuId(code) }
            // 3 selling days (d-7, d-3, d-1): A total 19 -> ADS 633.33 -> 633 hundredths; B total 4 -> 133
            assertEquals(day(1), row(a)["previous_day"]!!.jsonPrimitive.content)
            assertEquals(3L, row(a)["previous_day_qty_base"]!!.jsonPrimitive.content.toLong(), "previous sale day quantity in base units")
            assertEquals(633L, row(a)["ads_hundredths"]!!.jsonPrimitive.content.toLong())
            assertEquals(10L, row(a)["sdlw_qty_base"]!!.jsonPrimitive.content.toLong(), "same weekday last week")
            assertEquals(0L, row(b)["previous_day_qty_base"]!!.jsonPrimitive.content.toLong(), "B was not sold on the previous sale day")
            assertEquals(133L, row(b)["ads_hundredths"]!!.jsonPrimitive.content.toLong())
            assertEquals(0L, row(b)["sdlw_qty_base"]!!.jsonPrimitive.content.toLong())
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
            assertEquals(0L, scalar("SELECT count(*) FROM app.outlet_change_request WHERE client_uuid IN ('$twin', '$dup')")!!.toLong(), "nothing stored for a refused name")
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
    fun theWorstCaseStatsSectionStaysSmallOnTheWire() {
        // docs/04 sets no byte figure for the bundle ("compact, gzip it"); the section is measured and bounded here: 70 outlets x 40 SKUs, every pair with history (the real section is sparser).
        val rnd = java.util.Random(7)
        val rows = (1L..70L).flatMap { o -> (1L..40L).map { k -> com.aktcl.aron.backend.sync.OutletSkuStat(1000 + o, 200 + k, "2026-11-10", rnd.nextInt(500).toLong(), rnd.nextInt(60000).toLong(), rnd.nextInt(500).toLong()) } }
        val json = com.aktcl.aron.backend.platform.ResponseJson.encodeToString(kotlinx.serialization.builtins.ListSerializer(com.aktcl.aron.backend.sync.OutletSkuStat.serializer()), rows)
        val gz = java.io.ByteArrayOutputStream().also { java.util.zip.GZIPOutputStream(it).use { z -> z.write(json.toByteArray()) } }.size()
        println("MEASURED outlet_sku_stats 2800 rows: raw=${json.length} bytes, gzip=$gz bytes")
        assertTrue(json.length < 400_000, "raw ${json.length}")
        assertTrue(gz < 50_000, "gzip $gz")
    }
}
