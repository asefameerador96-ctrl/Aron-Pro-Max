package com.aktcl.aron.backend.app

import com.aktcl.aron.backend.app.TsoWorld.Companion.ack
import com.aktcl.aron.backend.app.TsoWorld.Companion.code
import com.aktcl.aron.backend.app.TsoWorld.Companion.json
import com.aktcl.aron.backend.app.TsoWorld.Companion.status
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import java.io.File
import java.util.UUID
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * AMO manual build, foundation train F1 (docs/requests/amo-manual-build-2026-11.md s3; D-AMO-MAN-06, 08, 09, 10, 12):
 * the force-sale reason through ingest, the call-target cards, the team-stock members and the rubric fields, through the
 * production wiring and the db lane's seed (the Mirpur zone: amo1001, sr1001, tso1001, dmo1001).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AmoFoundationF1Test {
    private val repoRoot = File(System.getProperty("aron.repoRoot") ?: "../..")
    private lateinit var world: TsoWorld
    private lateinit var sr: TsoPhone
    private lateinit var amo: TsoPhone

    @BeforeAll
    fun setUp() {
        world = TsoWorld(repoRoot, mirrors = listOf("LKX"))
        world.exec("UPDATE app.app_user SET phone = '01712345678' WHERE username = 'sr1001'")
        sr = world.phone("sr1001", "sr")
        amo = world.phone("amo1001", "amo")
    }

    @AfterAll
    fun tearDown() = world.close()

    private fun visitPayload(outlet: Long, action: String, reason: String?, seq: Int = 1): String {
        val verdict = if (action == "force_sale") "out_of_range" else "in_range"
        val extra = if (reason == null) "" else ",\"force_sale_reason\":\"$reason\""
        return """{"visit_kind":"sr_call","outlet_id":$outlet,"opened_at":"${world.now.get().minusSeconds(600)}","sequence_no":$seq,"planned":true,
            "fix":{"purpose":"visit_open","fix_status":"ok","lat":23.8069,"lng":90.3687,"accuracy_m":12.0,"provider":"fused","is_mock":false,"reused":false,
                   "device":{"device_owner":true,"dev_options_enabled":false,"adb_enabled":false,"auto_time_enabled":true,"mock_app_present":false}},
            "geo":{"verdict":"$verdict","distance_m":${if (action == "force_sale") 250.0 else 38.4},"radius_m_used":100,"max_accuracy_m_used":100,"location_basis":"master","action":"$action"}$extra}"""
    }

    private fun closePayload(visit: String) =
        """{"visit_client_uuid":"$visit","outcome_code":"sold","call_declined":false,"ended_at":"${world.now.get().minusSeconds(300)}","is_zero_sale":false}"""

    private fun routeAndOutlets(n: Int): Pair<Long, List<Long>> = with(world) {
        val route = one("SELECT id FROM app.route WHERE code = 'MIR-SR-D'")
        route to longs("SELECT id FROM app.outlet WHERE route_id = $route AND status = 'active' ORDER BY id LIMIT $n")
    }

    // ------------------------------------------------------------------------------------------------ force_sale_reason

    @Test
    fun duplicateAndOutOfOrderUploadsKeepOneVisitAndOneForceSaleReason() = world.withApi {
        with(world) {
            val (route, outlets) = routeAndOutlets(5)
            for (seed in 1..5) {
                val rnd = Random(seed)
                val cu = UUID.randomUUID().toString()
                val reason = if (seed % 2 == 0) "internet_problem" else "location_change"
                val visit = record("visit", visitPayload(outlets[seed - 1], "force_sale", reason, seed), route, cu = cu, family = cu)
                val close = record("visit_close", closePayload(cu), route, family = cu, rank = 1)
                // two to four batches, each a random slice of {visit, visit, close, close} in a random order: duplicates and out of order
                repeat(2 + rnd.nextInt(3)) { sync(sr, listOf(visit, visit, close, close).shuffled(rnd).take(1 + rnd.nextInt(4))) }
                sync(sr, listOf(close, visit))
                val rows = rows("SELECT force_sale_reason, geo_action, count(*) OVER () AS n FROM app.visit WHERE client_uuid = CAST('$cu' AS uuid)")
                assertEquals(1, rows.size, "seed $seed: one visit")
                assertEquals(reason, rows.single()["force_sale_reason"], "seed $seed: the reason is stored once and kept")
                assertEquals("force_sale", rows.single()["geo_action"])
            }
        }
    }

    @Test
    fun aReasonWithoutAForceSaleOrWithAnUnknownCodeIsRejectedAndNothingIsStored() = world.withApi {
        with(world) {
            val (route, outlets) = routeAndOutlets(8)
            val cases = listOf(
                Triple("sale_allowed", "internet_problem", outlets[5]),
                Triple("blocked", "location_change", outlets[6]),
                Triple("force_sale", "battery_dead", outlets[7]),
            )
            for ((action, reason, outlet) in cases) {
                val cu = UUID.randomUUID().toString()
                val answer = sync(sr, listOf(record("visit", visitPayload(outlet, action, reason), route, cu = cu, family = cu)))
                val a = ack(answer, cu)
                assertEquals("rejected", a.status(), "$action/$reason: $a")
                assertEquals("schema_invalid", a.code(), "$action/$reason: $a")
                assertEquals(0L, one("SELECT count(*) FROM app.visit WHERE client_uuid = CAST('$cu' AS uuid)"))
            }
            // a force sale without a reason, and one with an explicit null, stay valid (older apps)
            val plain = UUID.randomUUID().toString()
            assertEquals("accepted", ack(sync(sr, listOf(record("visit", visitPayload(outlets[0], "force_sale", null, 9), route, cu = plain, family = plain))), plain).status())
            assertNull(scalar("SELECT force_sale_reason FROM app.visit WHERE client_uuid = CAST('$plain' AS uuid)"))
        }
    }

    // ------------------------------------------------------------------------------------------------ GET /v1/supervisor/call-targets

    private fun amoVisit(kind: String, outcome: String?, outlet: Long, date: String, seq: Int, voided: Boolean = false) = with(world) {
        val amoId = amo.userId
        exec(
            """
            INSERT INTO app.visit (client_uuid, family_uuid, business_date, user_id, captured_at, config_version, visit_kind, outlet_id, opened_at, sequence_no, planned,
                                   verdict, radius_m_used, max_accuracy_m_used, location_basis, geo_action, outcome_code, voided_at)
            SELECT u, u, DATE '$date', $amoId, TIMESTAMPTZ '$date 10:00+06', 0, '$kind', $outlet, TIMESTAMPTZ '$date 10:00+06', $seq, false,
                   'in_range', 100, 50, 'master', 'sale_allowed', ${outcome?.let { "'$it'" } ?: "NULL"}, ${if (voided) "now()" else "NULL"}
              FROM (SELECT gen_random_uuid() AS u) x
            """.trimIndent(),
        )
    }

    @Test
    fun getSupervisorCallTargetsGivesTheFourCardsFromTheCallersOwnVisitsAndUnsetTargetsAreNull() = world.withApi {
        with(world) {
            val d = today().toString()
            val (_, outlets) = routeAndOutlets(3)
            // before any target is set: the cards carry done counts and null targets
            val empty = json(getAs(amo, "/v1/supervisor/call-targets").also { assertEquals(HttpStatusCode.OK, it.status, it.bodyAsText()) }.bodyAsText())
            assertEquals(d, empty["business_date"]!!.jsonPrimitive.content); assertEquals(d.take(7), empty["period"]!!.jsonPrimitive.content)
            for (card in listOf("today", "month", "control", "joint")) {
                assertEquals(0, empty[card]!!.jsonObject["done"]!!.jsonPrimitive.content.toInt(), card)
                assertEquals(JsonNull, empty[card]!!.jsonObject["target"], "$card target is null while unset")
            }
            // the AMO's own visits: 2 control + 1 joint today (closed), one abandoned, one voided, one open, one of another day; the SR's call is not theirs
            amoVisit("amo_control_call", "sold", outlets[0], d, 1); amoVisit("amo_control_call", "refused", outlets[1], d, 2)
            amoVisit("amo_joint_call", "sold", outlets[2], d, 3)
            amoVisit("amo_control_call", "abandoned", outlets[0], d, 4); amoVisit("amo_control_call", "sold", outlets[0], d, 5, voided = true)
            amoVisit("amo_control_call", null, outlets[1], d, 6)
            amoVisit("amo_joint_call", "sold", outlets[0], today().withDayOfMonth(1).toString(), 7)
            exec("UPDATE app.visit SET user_id = ${sr.userId} WHERE sequence_no = 99")
            cfg("cfg.amo.call_target_month", "260"); cfg("cfg.amo.control_call_target_month", "120"); cfg("cfg.amo.joint_call_target_month", "40")
            val a = json(getAs(amo, "/v1/supervisor/call-targets").bodyAsText())
            fun card(n: String) = a[n]!!.jsonObject["done"]!!.jsonPrimitive.content.toInt() to a[n]!!.jsonObject["target"]!!.jsonPrimitive.content.toInt()
            val firstOfMonthIsToday = today().dayOfMonth == 1
            assertEquals(3, card("today").first)
            assertEquals(if (firstOfMonthIsToday) 3 else 4, card("month").first)
            assertEquals(2, card("control").first); assertEquals(if (firstOfMonthIsToday) 1 else 2, card("joint").first)
            assertEquals(260, card("month").second); assertEquals(120, card("control").second); assertEquals(40, card("joint").second)
            assertTrue(card("today").second in 1..260, "today's share of the monthly target: ${card("today")}")
            // a past month: the month cards are empty, today's card still counts today
            val past = json(getAs(amo, "/v1/supervisor/call-targets?month=2026-12").bodyAsText())
            assertEquals("2026-12", past["period"]!!.jsonPrimitive.content); assertEquals(0, past["month"]!!.jsonObject["done"]!!.jsonPrimitive.content.toInt())
            // validation
            assertEquals(HttpStatusCode.BadRequest, getAs(amo, "/v1/supervisor/call-targets?month=2027-13").status)
            assertEquals(HttpStatusCode.BadRequest, getAs(amo, "/v1/supervisor/call-targets?month=2099-01").status)
            assertEquals(HttpStatusCode.BadRequest, getAs(amo, "/v1/supervisor/call-targets?month=next").status)
        }
    }

    @Test
    fun getSupervisorCallTargetsIsForAnAmoOnlyAndNeverReadsAnotherAmosVisits() = world.withApi {
        with(world) {
            // SR phone, TSO, DMO and a national role: 403 (the cards are the AMO's own work)
            assertEquals(HttpStatusCode.Forbidden, getAs(sr, "/v1/supervisor/call-targets").status)
            for (u in listOf("tso1001", "dmo1001", "admin1001", "support1001")) assertEquals(HttpStatusCode.Forbidden, getWeb(u, "/v1/supervisor/call-targets").status, u)
            // an AMO of the mirrored division reads their own counts: the Mirpur AMO's visits are not in them
            val other = phone("lkxamo1001", "amo")
            val r = getAs(other, "/v1/supervisor/call-targets")
            assertEquals(HttpStatusCode.OK, r.status, r.bodyAsText())
            val o = json(r.bodyAsText())
            assertEquals(0, o["month"]!!.jsonObject["done"]!!.jsonPrimitive.content.toInt(), "another AMO's visits are not counted")
        }
    }

    // ------------------------------------------------------------------------------------------------ GET /v1/team/stock members

    @Test
    fun getTeamStockListsEverySrOfTheZoneWithRoutesOutletsAndThePhoneOnlyForTheAmo() = world.withApi {
        with(world) {
            val d = today().toString()
            val asAmo = getAs(amo, "/v1/team/stock?business_date=$d")
            assertEquals(HttpStatusCode.OK, asAmo.status, asAmo.bodyAsText())
            val items = json(asAmo.bodyAsText())["items"]!!.jsonArray.map { it.jsonObject }
            val me = items.single { it["user_id"]!!.jsonPrimitive.content.toLong() == sr.userId }
            assertEquals(emptyList(), me["by_sku"]!!.jsonArray, "an SR with no stock rows is still listed")
            assertEquals(3, me["route_names"]!!.jsonArray.size, "sr1001 is primary on the three Mirpur routes: ${me["route_names"]}")
            assertEquals(60, me["total_outlets"]!!.jsonPrimitive.content.toInt())
            val phone = scalar("SELECT phone FROM app.app_user WHERE id = ${sr.userId}")
            assertEquals(phone, me["phone"]?.let { if (it is JsonNull) null else it.jsonPrimitive.content }, "the AMO of the zone reads the SR's phone in clear")
            assertTrue(items.none { it["user_id"]!!.jsonPrimitive.content.toLong() == amo.userId }, "the AMO is not an SR of the team")
            // a TSO reading the same zone gets the same members without the phone; the LKX division is not in the Mirpur AMO's reach
            val tso = json(getWeb("tso1001", "/v1/team/stock?business_date=$d").bodyAsText())["items"]!!.jsonArray.map { it.jsonObject }
            assertTrue(tso.isNotEmpty() && tso.all { it["phone"] is JsonNull }, "only an AMO reads phones: $tso")
            assertTrue(items.none { it["full_name"]!!.jsonPrimitive.content.startsWith("LKX", ignoreCase = true) })
            // SR 403; an AMO asking for the other division's zone 403
            assertEquals(HttpStatusCode.Forbidden, getAs(sr, "/v1/team/stock?business_date=$d").status)
            val lkxZone = one("SELECT id FROM app.zone WHERE code = 'Z-LKX'")
            assertEquals(HttpStatusCode.Forbidden, getAs(amo, "/v1/team/stock?business_date=$d&zone_id=$lkxZone").status)
            // a DMO of the division reads the whole division with no phone
            val dmo = getWeb("dmo1001", "/v1/team/stock?business_date=$d")
            assertEquals(HttpStatusCode.OK, dmo.status); assertTrue(json(dmo.bodyAsText())["items"]!!.jsonArray.all { it.jsonObject["phone"] is JsonNull })
        }
    }

    // ------------------------------------------------------------------------------------------------ rubric fields

    @Test
    fun theAdminRubricApiRoundTripsSectionsAndGuidanceAndTheSeededJointCallVersionTwoIsReadable() = world.withApi {
        with(world) {
            val seeded = json(getWeb("admin1001", "/v1/admin/rubrics").bodyAsText())["items"]!!.jsonArray.map { it.jsonObject }.single { it["kind"]!!.jsonPrimitive.content == "joint_call" }
            assertEquals(2, seeded["version"]!!.jsonPrimitive.content.toInt())
            val cs = seeded["criteria"]!!.jsonArray.map { it.jsonObject }
            assertEquals(7, cs.size)
            assertEquals(listOf("5-step sales call assessment", "Relationship assessment", "Service quality assessment"), cs.map { it["section"]!!.jsonPrimitive.content }.distinct())
            assertTrue(cs.all { (it["guidance_bn"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.startsWith("(যা দেখতে হবে:") == true && it["guidance_en"] !is JsonNull })
            val body = """{"kind":"joint_call","change_reason":"Add a section","criteria":[
                {"key":"greeting","label_en":"Greeting","label_bn":"সম্ভাষণ","answer_type":"score_1_5","section":"Relationship","section_bn":"সম্পর্ক","guidance_en":"Warm hello","guidance_bn":"আন্তরিক"},
                {"key":"closing","label_en":"Closing","answer_type":"stars_1_5"}]}"""
            val created = postWeb("admin1001", "/v1/admin/rubrics", body)
            assertEquals(HttpStatusCode.Created, created.status, created.bodyAsText())
            val c = json(created.bodyAsText())["criteria"]!!.jsonArray.map { it.jsonObject }
            assertEquals("Relationship", c[0]["section"]!!.jsonPrimitive.content); assertEquals("সম্পর্ক", c[0]["section_bn"]!!.jsonPrimitive.content)
            assertEquals("Warm hello", c[0]["guidance_en"]!!.jsonPrimitive.content); assertEquals("আন্তরিক", c[0]["guidance_bn"]!!.jsonPrimitive.content)
            assertEquals(JsonNull, c[1]["section"]); assertEquals("score_1_5", c[1]["answer_type"]!!.jsonPrimitive.content)
            // lengths
            val long = "x".repeat(501)
            assertEquals(HttpStatusCode.BadRequest, postWeb("admin1001", "/v1/admin/rubrics", """{"kind":"joint_call","change_reason":"Too long","criteria":[{"key":"a1","label_en":"A","answer_type":"bool","guidance_en":"$long"}]}""").status)
            assertEquals(HttpStatusCode.BadRequest, postWeb("admin1001", "/v1/admin/rubrics", """{"kind":"joint_call","change_reason":"Too long","criteria":[{"key":"a1","label_en":"A","answer_type":"bool","section":"${"y".repeat(121)}"}]}""").status)
        }
    }

    @Test
    fun theViewerOfAnUnknownMemberIsAProblemNotAServerError() = world.withApi {
        with(world) {
            val r = postWeb("admin1001", "/v1/admin/rubrics", """{"kind":"joint_call","change_reason":"Unknown member","criteria":[{"key":"a1","label_en":"A","answer_type":"bool","colour":"red"}]}""")
            assertTrue(r.status.value in 400..499, r.bodyAsText())
            assertNotNull(r.bodyAsText())
        }
    }
}
