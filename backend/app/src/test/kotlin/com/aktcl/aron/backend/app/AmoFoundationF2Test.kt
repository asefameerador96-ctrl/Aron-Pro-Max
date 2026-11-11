package com.aktcl.aron.backend.app

import com.aktcl.aron.backend.analytics.AggregationWorker
import com.aktcl.aron.backend.analytics.Aggregator
import com.aktcl.aron.backend.app.TsoWorld.Companion.json
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * AMO manual build, foundation train F2 (docs/requests/amo-manual-build-2026-11.md s3, s4.4; D-AMO-MAN-09, 10, 11):
 * the Sales Journey, the SR list with vacant routes, the previous sale data of an outlet and the AMO's Live Dashboard counts,
 * through the production wiring and the db lane's seed (the Mirpur zone: amo1001, sr1001, tso1001, dmo1001; the mirrored
 * LKX division with lkxamo1001 and lkxsr1001 is the "other" reach). Dates come from the clock (no constant dates).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AmoFoundationF2Test {
    private val repoRoot = File(System.getProperty("aron.repoRoot") ?: "../..")
    private lateinit var world: TsoWorld
    private lateinit var sr: TsoPhone
    private lateinit var amo: TsoPhone
    private lateinit var date: String
    private var routeD = 0L
    private var route3F = 0L
    private var outletA = 0L
    private var outletB = 0L
    private var outletC = 0L
    private var cigaretteSku = 0L
    private var matchSku = 0L
    private var cigarettePack = 0

    private fun JsonObject.s(k: String): String? = this[k]?.let { if (it is JsonNull) null else it.jsonPrimitive.content }
    private fun JsonObject.l(k: String): Long? = s(k)?.toLong()
    private fun JsonObject.d(k: String): Double? = s(k)?.toDouble()

    @BeforeAll
    fun setUp() {
        world = TsoWorld(repoRoot, mirrors = listOf("LKX"))
        with(world) {
            exec("UPDATE app.app_user SET phone = '01712345678' WHERE username = 'sr1001'")
            exec("UPDATE app.app_user SET phone = '01912345678' WHERE username = 'lkxsr1001'")
            date = today().toString()
            sr = phone("sr1001", "sr"); amo = phone("amo1001", "amo")
            routeD = one("SELECT id FROM app.route WHERE code = 'MIR-SR-D'"); route3F = one("SELECT id FROM app.route WHERE code = 'MIR-SR-3F'")
            val o = longs("SELECT id FROM app.outlet WHERE route_id = $routeD AND status = 'active' ORDER BY id LIMIT 3")
            outletA = o[0]; outletB = o[1]; outletC = o[2]
            // A fixed trio of base points: A to B is 344.70 m (1 mm of latitude arithmetic below), B to C 1017.31 m.
            exec("UPDATE app.outlet SET lat = 23.8069, lng = 90.3687, contact_number = '01811111111' WHERE id = $outletA")
            exec("UPDATE app.outlet SET lat = 23.8100, lng = 90.3687, contact_number = '01822222222' WHERE id = $outletB")
            exec("UPDATE app.outlet SET lat = 23.8100, lng = 90.3787, contact_number = NULL WHERE id = $outletC")
            cigaretteSku = one("SELECT id FROM app.sku WHERE entry_unit_default = 'pack' AND category_code = 'cigarette' ORDER BY id LIMIT 1")
            cigarettePack = one("SELECT base_per_pack FROM app.sku WHERE id = $cigaretteSku").toInt()
            matchSku = one("SELECT id FROM app.sku WHERE entry_unit_default = 'dozen' ORDER BY id LIMIT 1")
            seedDay()
        }
    }

    @AfterAll
    fun tearDown() = world.close()

    // ------------------------------------------------------------------------------------------------ data

    private fun visit(uuid: String, userId: Long, route: Long, outlet: Long, kind: String, openedHhmmss: String, endedHhmmss: String?, day: String = date, voided: Boolean = false, geo: String = "sale_allowed", photo: Boolean = false, seq: Int = 1) =
        world.exec(
            """
            INSERT INTO app.visit (client_uuid, family_uuid, business_date, user_id, route_id, captured_at, config_version, visit_kind, outlet_id, opened_at, sequence_no, planned,
                                   verdict, radius_m_used, max_accuracy_m_used, location_basis, geo_action, force_photo_uuid, outcome_code, close_client_uuid, ended_at, voided_at)
            VALUES (CAST('$uuid' AS uuid), CAST('$uuid' AS uuid), DATE '$day', $userId, $route, TIMESTAMPTZ '$day $openedHhmmss+06', 0, '$kind', $outlet, TIMESTAMPTZ '$day $openedHhmmss+06', $seq, false,
                    '${if (geo == "force_sale") "out_of_range" else "in_range"}', 100, 50, 'master', '$geo', ${if (photo) "gen_random_uuid()" else "NULL"},
                    ${if (endedHhmmss != null) "'closed'" else "NULL"}, ${if (endedHhmmss != null) "gen_random_uuid()" else "NULL"}, ${endedHhmmss?.let { "TIMESTAMPTZ '$day $it+06'" } ?: "NULL"},
                    ${if (voided) "now()" else "NULL"})
            """.trimIndent(),
        )

    private fun memo(uuid: String, visit: String, userId: Long, route: Long, outlet: Long, no: String, status: String = "active", supersedes: String? = null, supersededBy: String? = null, day: String = date) =
        world.exec(
            """
            INSERT INTO app.memo (client_uuid, family_uuid, business_date, user_id, route_id, captured_at, config_version, visit_client_uuid, outlet_id, memo_no, memo_kind, committed_at,
                                  price_list_date, price_type, gross_mtk, offer_discount_mtk, drp_discount_mtk, qc_deduction_mtk, round_adj_mtk, net_mtk, paid_mtk, due_mtk, is_credit,
                                  line_count, discount_line_count, qc_line_count, status, supersedes_client_uuid, edit_reason_code, superseded_by_client_uuid, received_at)
            VALUES (CAST('$uuid' AS uuid), CAST('$uuid' AS uuid), DATE '$day', $userId, $route, TIMESTAMPTZ '$day 10:05:00+06', 0, CAST('$visit' AS uuid), $outlet, '$no', 'sale', TIMESTAMPTZ '$day 10:05:00+06',
                    DATE '$day', 'outlet', 6250, 0, 0, 0, 0, 6250, 6250, 0, false, 1, 0, 0, '$status', ${supersedes?.let { "CAST('$it' AS uuid)" } ?: "NULL"}, ${if (supersedes != null) "'wrong_quantity'" else "NULL"}, ${supersededBy?.let { "CAST('$it' AS uuid)" } ?: "NULL"}, now())
            """.trimIndent(),
        )

    private fun line(memo: String, no: Int, sku: Long, entered: Int, unit: String, factor: Int, base: Int, gross: Long, userId: Long, route: Long, voided: Boolean = false, kind: String = "sale") =
        world.exec(
            """
            INSERT INTO app.memo_line (client_uuid, family_uuid, business_date, user_id, route_id, captured_at, config_version, memo_client_uuid, line_no, sku_id, line_kind, qty_entered, unit_entered,
                                       pack_factor, qty_base, price_type, price_valid_from, base_price_mtk, price_per_qty, gross_mtk, voided_at)
            VALUES (gen_random_uuid(), CAST('$memo' AS uuid), DATE '$date', $userId, $route, TIMESTAMPTZ '$date 10:05:00+06', 0, CAST('$memo' AS uuid), $no, $sku, '$kind', $entered, '$unit', $factor, $base,
                    'outlet', DATE '$date', ${if (kind == "sale") 100 else 0}, 1, ${if (kind == "sale") base * 100L else 0L}, ${if (voided) "now()" else "NULL"})
            """.trimIndent(),
        )

    private val v1 = "00000000-0000-4000-8000-0000000f2001"
    private val v2 = "00000000-0000-4000-8000-0000000f2002"
    private val v3 = "00000000-0000-4000-8000-0000000f2003"
    private val m1 = "00000000-0000-4000-8000-0000000f2101"
    private val m2old = "00000000-0000-4000-8000-0000000f2102"
    private val m2new = "00000000-0000-4000-8000-0000000f2103"
    private val mOther = "00000000-0000-4000-8000-0000000f2104"
    private val mVoidedLine = "00000000-0000-4000-8000-0000000f2105"

    /** The SR's day on the daily route: A (new sale), B (edited sale), C (no sale), plus rows the journey must leave out. */
    private fun seedDay() = with(world) {
        val srId = sr.userId
        // out of order on purpose: the journey orders by opened_at, not by insertion
        visit(v3, srId, routeD, outletC, "sr_call", "10:30:00", "10:31:00", geo = "force_sale", photo = true, seq = 3)
        visit(v1, srId, routeD, outletA, "sr_call", "10:00:00", "10:07:46", seq = 1)
        visit(v2, srId, routeD, outletB, "sr_call", "10:20:00", "10:25:00", seq = 2)
        // left out: a voided call, a call on another route, another day, and the AMO's own control call
        visit("00000000-0000-4000-8000-0000000f2010", srId, routeD, outletA, "sr_call", "10:40:00", "10:41:00", voided = true, seq = 4)
        visit("00000000-0000-4000-8000-0000000f2011", srId, route3F, outletA, "sr_call", "10:50:00", "10:51:00", seq = 5)
        visit("00000000-0000-4000-8000-0000000f2012", srId, routeD, outletA, "sr_call", "10:00:00", "10:01:00", day = today().minusDays(1).toString(), seq = 6)
        visit("00000000-0000-4000-8000-0000000f2013", amo.userId, routeD, outletA, "amo_control_call", "11:00:00", "11:05:00", seq = 7)
        memo(m1, v1, srId, routeD, outletA, "sr1001-270103-001")
        memo(m2new, v2, srId, routeD, outletB, "sr1001-270103-003", supersedes = m2old)
        memo(m2old, v2, srId, routeD, outletB, "sr1001-270103-002", status = "superseded", supersededBy = m2new)
        // previous sale data of outlet A: 3 packets of a cigarette and 2 dozen of a match by the SR, 2 packets more by the LKX SR (any SR counts), a voided line and a free line that do not
        line(m1, 1, cigaretteSku, 3, "pack", cigarettePack, 3 * cigarettePack, 3000, srId, routeD)
        line(m1, 2, matchSku, 2, "dozen", 1, 2, 400, srId, routeD)
        line(m1, 3, cigaretteSku, 9, "pack", cigarettePack, 9 * cigarettePack, 9000, srId, routeD, voided = true)
        line(m1, 4, cigaretteSku, 1, "pack", cigarettePack, cigarettePack, 0, srId, routeD, kind = "promo_free")
        val lkxSr = one("SELECT id FROM app.app_user WHERE username = 'lkxsr1001'")
        val lkxRoute = one("SELECT id FROM app.route WHERE code LIKE 'LKX-SR-%' ORDER BY id LIMIT 1")
        visit("00000000-0000-4000-8000-0000000f2020", lkxSr, lkxRoute, outletA, "sr_call", "12:00:00", "12:05:00", seq = 8)
        memo(mOther, "00000000-0000-4000-8000-0000000f2020", lkxSr, lkxRoute, outletA, "lkxsr1001-270103-001")
        line(mOther, 1, cigaretteSku, 2, "pack", cigarettePack, 2 * cigarettePack, 2000, lkxSr, lkxRoute)
        // a vacant route in the Mirpur zone (no assignment at all)
        exec(
            """
            INSERT INTO app.route (code, name, display_label, zone_id, kind, visit_kind, visit_days_mask, sequence_no)
            SELECT 'MIR-SR-VAC', 'Vacant test route', '(Mon, Thu)', z.id, 'sr', '2f', 36, 9 FROM app.zone z WHERE z.code = 'Z-MIR'
            """.trimIndent(),
        )
        // the project's dw rows (the AMO's Live Dashboard reads dw.fact_visit)
        AggregationWorker(fresh.db, clock).also { w ->
            w.runUntilIdle(); fresh.db.jdbi.useHandle<Exception> { Aggregator.refreshDimensions(it) }; w.requestRebuild(today()); w.runUntilIdle()
        }
    }

    private fun calls(o: JsonObject): List<JsonObject> = o["calls"]!!.jsonArray.map { it.jsonObject }

    // ------------------------------------------------------------------------------------------------ GET /v1/team/journey

    @Test
    fun journeyOrdersCallsAndComputesStatusDurationsGapsAndHaversineDistances() = world.withApi {
        with(world) {
            val r = getAs(amo, "/v1/team/journey?user_id=${sr.userId}&route_id=$routeD&business_date=$date")
            assertEquals(HttpStatusCode.OK, r.status, r.bodyAsText())
            val o = json(r.bodyAsText())
            assertEquals(sr.userId, o["user"]!!.jsonObject.l("user_id")); assertEquals(routeD, o["route"]!!.jsonObject.l("route_id")); assertEquals(date, o.s("business_date"))
            val c = calls(o)
            assertEquals(listOf(v1, v2, v3), c.map { it.s("visit_uuid") }, "ordered by opened_at; voided, other-route, other-day and AMO calls left out")
            assertEquals(listOf("new_sale", "edited_sale", "no_sale"), c.map { it.s("status") })
            // durations: closed_at - opened_at
            assertEquals(listOf(466L, 300L, 60L), c.map { it.l("duration_s") })
            // gaps: next.opened_at - this.closed_at (10:20:00 - 10:07:46 = 734 s; 10:30:00 - 10:25:00 = 300 s); the last call has none
            assertEquals(listOf(734L, 300L, null), c.map { it.l("gap_to_next_s") })
            // haversine on the fixed pair (R = 6 371 000 m): A to B 344.7042..., B to C 1017.3103...; two decimals, running total of the rounded legs
            assertNull(c[0].s("distance_from_prev_m")); assertEquals(0.0, c[0].d("distance_so_far_m"))
            assertEquals(344.70, c[1].d("distance_from_prev_m")); assertEquals(344.70, c[1].d("distance_so_far_m"))
            assertEquals(1017.31, c[2].d("distance_from_prev_m")); assertEquals(1362.01, c[2].d("distance_so_far_m"))
            assertEquals(1362.01, o.d("distance_m"))
            // outlet block: masked phone (never the clear number), cluster, code
            val a = c[0]["outlet"]!!.jsonObject
            assertEquals(outletA, a.l("id")); assertEquals("018*****111", a.s("phone_masked")); assertTrue(a.s("code")!!.isNotBlank())
            assertNull(c[2]["outlet"]!!.jsonObject.s("phone_masked"), "an outlet without a phone has none to mask")
            assertTrue(!r.bodyAsText().contains("01811111111"), "the clear phone never leaves")
            // an edited sale is the visit whose memo was superseded; zero memos is a no sale
            assertEquals("no_sale", c[2].s("status"))
        }
    }

    @Test
    fun journeyOfAnEmptyDayIsAnEmptyListAndAVacantRouteHasNoSrToAsk() = world.withApi {
        with(world) {
            val yesterday = today().minusDays(2).toString()
            val e = json(getAs(amo, "/v1/team/journey?user_id=${sr.userId}&route_id=$routeD&business_date=$yesterday").bodyAsText())
            assertEquals(emptyList(), e["calls"]!!.jsonArray); assertEquals(0.0, e.d("distance_m"))
            // no business_date: today
            assertEquals(date, json(getAs(amo, "/v1/team/journey?user_id=${sr.userId}&route_id=$routeD").bodyAsText()).s("business_date"))
            // validation and unknown ids
            assertEquals(HttpStatusCode.BadRequest, getAs(amo, "/v1/team/journey?route_id=$routeD").status)
            assertEquals(HttpStatusCode.BadRequest, getAs(amo, "/v1/team/journey?user_id=${sr.userId}").status)
            assertEquals(HttpStatusCode.BadRequest, getAs(amo, "/v1/team/journey?user_id=${sr.userId}&route_id=$routeD&business_date=soon").status)
            assertEquals(HttpStatusCode.NotFound, getAs(amo, "/v1/team/journey?user_id=987654&route_id=$routeD").status)
            assertEquals(HttpStatusCode.NotFound, getAs(amo, "/v1/team/journey?user_id=${amo.userId}&route_id=$routeD").status, "only an SR has a journey")
            val lkxSrId = one("SELECT id FROM app.app_user WHERE username = 'lkxsr1001'")
            assertEquals(HttpStatusCode.NotFound, getAs(amo, "/v1/team/journey?user_id=$lkxSrId&route_id=$routeD").status, "an SR outside the route is never named")
        }
    }

    @Test
    fun journeyScopeSupervisorsInsideTheirReachOnlyAndNeverAnSrOrAnotherDivision() = world.withApi {
        with(world) {
            val path = "/v1/team/journey?user_id=${sr.userId}&route_id=$routeD&business_date=$date"
            assertEquals(HttpStatusCode.Forbidden, getAs(sr, path).status, "SR")
            for (u in listOf("tso1001", "dmo1001", "admin1001")) assertEquals(HttpStatusCode.OK, getWeb(u, path).status, u)
            assertEquals(HttpStatusCode.Forbidden, getWeb("support1001", path).status, "SUPPORT")
            // the AMO of the mirrored LKX zone and the LKX SR's own phone: the Mirpur route is outside their reach
            val lkxAmo = phone("lkxamo1001", "amo")
            assertEquals(HttpStatusCode.Forbidden, getAs(lkxAmo, path).status, "another zone's AMO")
            val lkxRoute = one("SELECT id FROM app.route WHERE code LIKE 'LKX-SR-%' ORDER BY id LIMIT 1")
            val lkxSrId = one("SELECT id FROM app.app_user WHERE username = 'lkxsr1001'")
            assertEquals(HttpStatusCode.OK, getAs(lkxAmo, "/v1/team/journey?user_id=$lkxSrId&route_id=$lkxRoute&business_date=$date").status, "their own zone")
            assertEquals(HttpStatusCode.Forbidden, getAs(amo, "/v1/team/journey?user_id=$lkxSrId&route_id=$lkxRoute&business_date=$date").status, "the Mirpur AMO on the LKX route")
            // the client cannot widen: a zone_id query member changes nothing
            assertEquals(HttpStatusCode.Forbidden, getAs(lkxAmo, "$path&zone_id=${one("SELECT home_zone_id FROM app.app_user WHERE id = ${amo.userId}")}").status)
        }
    }

    // ------------------------------------------------------------------------------------------------ GET /v1/team/members

    @Test
    fun membersListEveryRouteWithItsSrVacantRoutesOutletCountsDaysAndThePhoneOnlyForTheOwnZoneAmo() = world.withApi {
        with(world) {
            val r = getAs(amo, "/v1/team/members?business_date=$date")
            assertEquals(HttpStatusCode.OK, r.status, r.bodyAsText())
            val items = json(r.bodyAsText())["items"]!!.jsonArray.map { it.jsonObject }
            assertEquals(4, items.size, "three seeded routes of sr1001 and the vacant one: ${items.map { it.s("route_name") }}")
            val byRoute = items.associateBy { it.l("route_id") }
            val d = byRoute.getValue(routeD)
            assertEquals("MIR-SR-D", d.s("route_code")); assertEquals(sr.userId, d.l("user_id")); assertEquals(false, d["vacant"]!!.jsonPrimitive.content.toBoolean()); assertEquals(20, d["total_outlets"]!!.jsonPrimitive.content.toInt())
            assertEquals(listOf(1, 2, 3, 4, 5, 6, 7), d["route_days"]!!.jsonArray.map { it.jsonPrimitive.content.toInt() }, "a daily route: every ISO weekday")
            assertEquals("01712345678", d.s("phone")); assertEquals("017*****678", d.s("phone_masked"))
            assertEquals(listOf(2, 4, 7), byRoute.getValue(route3F)["route_days"]!!.jsonArray.map { it.jsonPrimitive.content.toInt() }, "Sun, Tue, Thu = ISO 7, 2, 4")
            val vac = items.single { it.s("route_name") == "Vacant test route" }
            assertEquals(true, vac["vacant"]!!.jsonPrimitive.content.toBoolean()); assertNull(vac.s("user_id")); assertNull(vac.s("full_name")); assertNull(vac.s("phone")); assertNull(vac.s("phone_masked"))
            assertEquals(0, vac["total_outlets"]!!.jsonPrimitive.content.toInt()); assertEquals(listOf(1, 4), vac["route_days"]!!.jsonArray.map { it.jsonPrimitive.content.toInt() }, "mask 36 = Monday and Thursday = ISO 1, 4")
            // a TSO and a DMO read the same rows without the clear phone (masked only)
            for (u in listOf("tso1001", "dmo1001")) {
                val rows = json(getWeb(u, "/v1/team/members?business_date=$date").bodyAsText())["items"]!!.jsonArray.map { it.jsonObject }
                assertTrue(rows.any { it.l("route_id") == routeD }, u)
                assertTrue(rows.all { it.s("phone") == null }, "$u reads no clear phone")
                assertEquals("017*****678", rows.first { it.l("route_id") == routeD }.s("phone_masked"))
            }
        }
    }

    @Test
    fun membersScopeSrAndOtherRolesAreRefusedAndAnAmoSeesOnlyTheOwnZone() = world.withApi {
        with(world) {
            assertEquals(HttpStatusCode.Forbidden, getAs(sr, "/v1/team/members").status)
            assertEquals(HttpStatusCode.Forbidden, getWeb("support1001", "/v1/team/members").status)
            val lkxAmo = phone("lkxamo1001", "amo")
            val theirs = json(getAs(lkxAmo, "/v1/team/members?business_date=$date").bodyAsText())["items"]!!.jsonArray.map { it.jsonObject }
            assertTrue(theirs.isNotEmpty() && theirs.none { it.l("route_id") in listOf(routeD, route3F) }, "the Mirpur routes are not in the LKX AMO's reach")
            assertTrue(theirs.any { it.s("phone") == "01912345678" }, "an AMO reads the clear phone of the SR of the own zone")
            assertTrue(json(getAs(amo, "/v1/team/members?business_date=$date").bodyAsText())["items"]!!.jsonArray.none { it.jsonObject.s("phone") == "01912345678" })
            assertEquals(HttpStatusCode.BadRequest, getAs(amo, "/v1/team/members?business_date=tomorrow").status)
        }
    }

    // ------------------------------------------------------------------------------------------------ GET /v1/outlets/{id}/sales-by-date

    @Test
    fun salesByDateLabelsLightersInBoxesOf25() = world.withApi {
        with(world) {
            val lighter = one("SELECT id FROM app.sku WHERE code = 'Aster'")
            line(m1, 9, lighter, 2, "pack", 25, 50, 5000, sr.userId, routeD)
            try {
                val lines = json(getAs(amo, "/v1/outlets/$outletA/sales-by-date?business_date=$date").bodyAsText())["lines"]!!.jsonArray.map { it.jsonObject }
                val l = lines.single { it["sku"]!!.jsonObject.l("sku_id") == lighter }
                assertEquals("box", l.s("report_unit")); assertEquals("2.000", l.s("qty_report")); assertEquals(50L, l.l("qty_base"))
            } finally {
                exec("UPDATE app.memo_line SET voided_at = now() WHERE memo_client_uuid = CAST('$m1' AS uuid) AND line_no = 9")
            }
        }
    }

    @Test
    fun salesByDateTotalsEverySrsActiveSaleLinesPerSkuInFieldUnits() = world.withApi {
        with(world) {
            val r = getAs(amo, "/v1/outlets/$outletA/sales-by-date?business_date=$date")
            assertEquals(HttpStatusCode.OK, r.status, r.bodyAsText())
            val o = json(r.bodyAsText())
            assertEquals(outletA, o.l("outlet_id")); assertEquals(date, o.s("business_date"))
            val lines = o["lines"]!!.jsonArray.map { it.jsonObject }
            assertEquals(2, lines.size, "a voided line and a free line are not sales: $lines")
            val cig = lines.single { it["sku"]!!.jsonObject.l("sku_id") == cigaretteSku }
            assertEquals((5 * cigarettePack).toLong(), cig.l("qty_base"), "3 packets of the SR and 2 of the other SR")
            assertEquals("5.000", cig.s("qty_report")); assertEquals("pack", cig.s("report_unit")); assertEquals(5L * cigarettePack * 100, cig.l("value_mtk"))
            assertTrue(cig["sku"]!!.jsonObject.s("sku_code")!!.isNotBlank())
            val match = lines.single { it["sku"]!!.jsonObject.l("sku_id") == matchSku }
            assertEquals("2.000", match.s("qty_report")); assertEquals("dozen", match.s("report_unit")); assertEquals(2L, match.l("qty_base")); assertEquals(200L, match.l("value_mtk"))
            assertEquals((5 * cigarettePack + 2).toLong(), o.l("total_qty_base")); assertEquals(5L * cigarettePack * 100 + 200, o.l("total_value_mtk"))
            // the superseded memo of the edited call at outlet B is not double counted; a day without sales has no lines
            assertEquals(emptyList(), json(getAs(amo, "/v1/outlets/$outletC/sales-by-date?business_date=$date").bodyAsText())["lines"]!!.jsonArray)
        }
    }

    @Test
    fun salesByDateWindowIsNinetyDaysBackToTodayAndNothingFuture() = world.withApi {
        with(world) {
            suspend fun at(d: java.time.LocalDate) = getAs(amo, "/v1/outlets/$outletA/sales-by-date?business_date=$d").status
            assertEquals(HttpStatusCode.OK, at(today()))
            assertEquals(HttpStatusCode.OK, at(today().minusDays(90)), "day 90 is inside")
            assertEquals(HttpStatusCode.BadRequest, at(today().minusDays(91)), "day 91 is outside")
            assertEquals(HttpStatusCode.BadRequest, at(today().plusDays(1)), "the future has no sales")
            assertEquals(HttpStatusCode.BadRequest, getAs(amo, "/v1/outlets/$outletA/sales-by-date?business_date=x").status)
            assertEquals(HttpStatusCode.BadRequest, getAs(amo, "/v1/outlets/zero/sales-by-date").status)
            assertEquals(HttpStatusCode.NotFound, getAs(amo, "/v1/outlets/987654/sales-by-date").status)
            // default date: today
            assertEquals(date, json(getAs(amo, "/v1/outlets/$outletA/sales-by-date").bodyAsText()).s("business_date"))
        }
    }

    @Test
    fun salesByDateScopeSrAndForeignDivisionsAreRefusedTsoAndDmoInsideTheirReachAreServed() = world.withApi {
        with(world) {
            val path = "/v1/outlets/$outletA/sales-by-date?business_date=$date"
            assertEquals(HttpStatusCode.Forbidden, getAs(sr, path).status, "SR")
            assertEquals(HttpStatusCode.Forbidden, getWeb("support1001", path).status, "SUPPORT")
            for (u in listOf("tso1001", "dmo1001", "admin1001")) assertEquals(HttpStatusCode.OK, getWeb(u, path).status, u)
            val lkxAmo = phone("lkxamo1001", "amo")
            assertEquals(HttpStatusCode.Forbidden, getAs(lkxAmo, path).status, "the LKX AMO on a Mirpur outlet")
            val lkxOutlet = one("SELECT o.id FROM app.outlet o JOIN app.route r ON r.id = o.route_id WHERE r.code LIKE 'LKX-SR-%' ORDER BY o.id LIMIT 1")
            assertEquals(HttpStatusCode.OK, getAs(lkxAmo, "/v1/outlets/$lkxOutlet/sales-by-date?business_date=$date").status)
            assertEquals(HttpStatusCode.Forbidden, getAs(amo, "/v1/outlets/$lkxOutlet/sales-by-date?business_date=$date").status, "the Mirpur AMO on an LKX outlet")
        }
    }

    // ------------------------------------------------------------------------------------------------ GET /v1/dashboards/login-submit (AMO)

    @Test
    fun loginSubmitAmoReadsTheOwnZoneWithCountsAndCategoryVolumesAndNationalEndpointsStayClosed() = world.withApi {
        with(world) {
            val r = getAs(amo, "/v1/dashboards/login-submit?business_date=$date")
            assertEquals(HttpStatusCode.OK, r.status, r.bodyAsText())
            val o = json(r.bodyAsText())
            // the day's SR calls on the Mirpur routes: v1, v2 (in range), v3 (force sale with a photo) on the daily route and the 3F call = 4 calls; one with a photo
            assertEquals(4, o["visits_total"]!!.jsonPrimitive.content.toInt()); assertEquals(3, o["geo_valid_visits"]!!.jsonPrimitive.content.toInt()); assertEquals(1, o["photo_valid_visits"]!!.jsonPrimitive.content.toInt())
            assertTrue(o["sales_by_category"] is JsonArray)
            for (m in o["sales_by_category"]!!.jsonArray.map { it.jsonObject }) assertTrue(m.s("category_code") != null && m.s("base_unit") != null && m.l("qty_base") != null)
            // another zone's AMO never sees the Mirpur calls
            val lkx = json(getAs(phone("lkxamo1001", "amo"), "/v1/dashboards/login-submit?business_date=$date").bodyAsText())
            assertEquals(1, lkx["visits_total"]!!.jsonPrimitive.content.toInt(), "only the LKX SR's call")
            // the same endpoint for a TSO is unchanged: no AMO-only members
            val tso = json(getWeb("tso1001", "/v1/dashboards/login-submit?business_date=$date").bodyAsText())
            for (k in listOf("visits_total", "geo_valid_visits", "photo_valid_visits", "sales_by_category")) assertTrue(tso[k] == null || tso[k] is JsonNull, "TSO response has no $k")
            // SR, SUPPORT and the national endpoints: closed to the AMO
            assertEquals(HttpStatusCode.Forbidden, getAs(sr, "/v1/dashboards/login-submit?business_date=$date").status)
            assertEquals(HttpStatusCode.Forbidden, getAs(amo, "/v1/dashboards/sync-health?business_date=$date").status, "other dashboards stay closed to the AMO")
            assertEquals(HttpStatusCode.Forbidden, postAs(amo, "/v1/dashboards/national/login-submit", """{"date":"$date"}""").status, "national endpoints stay closed to the AMO")
        }
    }

    // ------------------------------------------------------------------------------------------------ F2.1: CategoryVolume.memos (contract 1.11.1, D-AMO-MAN-18)

    @Test
    fun categoryMemosCountAMemoOncePerCategoryNotPerLineAndLeaveVoidedFreeAndOtherZonesOut() = world.withApi {
        with(world) {
            suspend fun memos(): Map<String, Int?> = json(getAs(amo, "/v1/dashboards/login-submit?business_date=$date").bodyAsText())["sales_by_category"]!!.jsonArray
                .map { it.jsonObject }.associate { it.s("category_code")!! to it.s("memos")?.toInt() }
            val before = memos()
            // seeded: m1 has a cigarette sale line (plus a voided and a free cigarette line) and a match sale line; the LKX memo is another zone
            assertEquals(1, before["cigarette"], "one memo with a cigarette sale line (voided and free lines do not count, the LKX memo is another zone): $before")
            assertEquals(1, before["match"])
            // one new memo with TWO cigarette SKUs (two lines) and a match line: +1 cigarette (not +2), +1 match
            val secondCigarette = one("SELECT id FROM app.sku WHERE category_code = 'cigarette' AND id <> $cigaretteSku ORDER BY id LIMIT 1")
            val m3 = "00000000-0000-4000-8000-0000000f2106"
            memo(m3, "00000000-0000-4000-8000-0000000f2013", sr.userId, routeD, outletB, "sr1001-270103-004")
            val per = one("SELECT base_per_pack FROM app.sku WHERE id = $secondCigarette").toInt()
            line(m3, 1, cigaretteSku, 1, "pack", cigarettePack, cigarettePack, 0, sr.userId, routeD)
            line(m3, 2, secondCigarette, 2, "pack", per, 2 * per, 0, sr.userId, routeD)
            line(m3, 3, matchSku, 1, "dozen", 1, 1, 0, sr.userId, routeD)
            // the dw projector (as the worker role runs it) writes dw.fact_memo, whose trigger fills dw.fact_memo_line
            AggregationWorker(fresh.db, clock).also { w -> w.runUntilIdle(); w.requestRebuild(today()); w.runUntilIdle() }
            now.updateAndGet { it.plusSeconds(10) }   // the dashboard summary is cached 5 s on the API clock
            val after = memos()
            assertEquals(2, after["cigarette"], "a memo with two cigarette lines counts once: $after")
            assertEquals(2, after["match"])
            // every category row carries the figure, a category the zone did not sell has no row
            assertTrue(after.values.all { it != null && it >= 0 })
        }
    }
}
