package com.aktcl.aron.backend.app

import com.aktcl.aron.backend.app.TsoWorld.Companion.ack
import com.aktcl.aron.backend.app.TsoWorld.Companion.json
import com.aktcl.aron.backend.app.TsoWorld.Companion.status
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * SR manual build, sr-server train 1 (D-SV1-01 to 12) over HTTP, through the production wiring and the db seed (Mirpur zone: sr1001,
 * amo1001, tso1001, dmo1001): the TSO's list of today's device OTPs, the call-photo review read, the update-check release notes and the
 * end-to-end approval of an SR's route_add request that carries no cluster.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SrManualSv1ApiTest {
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

    // ------------------------------------------------------------------------------------------------ getTeamDeviceOtps

    @Test
    fun theTsoSeesTodaysPendingCodesOfItsOwnSrsAndNobodyElseDoes() = world.withApi {
        with(world) {
            val srId = one("SELECT id FROM app.app_user WHERE username = 'sr1001'")
            // nothing issued yet: an empty page
            val empty = json(getAs(tso, "/v1/supervisor/device-otps").also { assertEquals(HttpStatusCode.OK, it.status, it.bodyAsText()) }.bodyAsText())
            assertEquals(0, empty["items"]!!.jsonArray.size)
            assertEquals(today().toString(), empty["business_date"]!!.jsonPrimitive.content)
            val issued = postAs(tso, "/v1/admin/device-otps", """{"user_id":$srId,"reason":"New phone for the route today"}""")
            assertEquals(HttpStatusCode.Created, issued.status, issued.bodyAsText())
            val code = json(issued.bodyAsText())["otp"]!!.jsonPrimitive.content
            val page = json(getAs(tso, "/v1/supervisor/device-otps").bodyAsText())
            val row = page["items"]!!.jsonArray.map { it.jsonObject }.single { it["user_id"]!!.jsonPrimitive.content.toLong() == srId }
            assertEquals(code, row["otp"]!!.jsonPrimitive.content, "the live code")
            assertEquals("sr1001", row["username"]!!.jsonPrimitive.content)
            assertTrue(row["route_names"]!!.jsonArray.isNotEmpty(), "the SR's routes")
            assertEquals(setOf("user_id", "username", "full_name", "route_names", "otp", "created_at", "expires_at", "attempts"), row.keys, "the shape is closed")
            // roles other than the TSO are refused
            assertEquals(HttpStatusCode.Forbidden, getAs(amo, "/v1/supervisor/device-otps").status)
            assertEquals(HttpStatusCode.Forbidden, getAs(sr, "/v1/supervisor/device-otps").status)
            assertEquals(HttpStatusCode.BadRequest, getAs(tso, "/v1/supervisor/device-otps?limit=0").status)
            // a code that is revoked or consumed, or issued on another business day, is not listed
            exec("UPDATE app.device_otp SET revoked_at = now() WHERE user_id = $srId")
            assertEquals(0, json(getAs(tso, "/v1/supervisor/device-otps").bodyAsText())["items"]!!.jsonArray.size)
            // the audit trail holds counts only, never the code
            val audit = rows("SELECT * FROM app.audit_log WHERE entity = 'device_otp' ORDER BY id").joinToString("\n")
            assertFalse(audit.contains(code), "the code is never written to the audit trail")
        }
    }

    // ------------------------------------------------------------------------------------------------ getCallPhotoReview

    @Test
    fun theCallPhotoReviewListsTheVisitsOfTheDayInReachWithAndWithoutAPhoto() = world.withApi {
        with(world) {
            val route = one("SELECT id FROM app.route WHERE code = 'MIR-SR-D'")
            val outlets = longs("SELECT id FROM app.outlet WHERE route_id = $route AND status = 'active' ORDER BY id LIMIT 3")
            val photo = UUID.randomUUID().toString()
            fun visit(outlet: Long, extra: String, seq: Int) = """{"visit_kind":"sr_call","outlet_id":$outlet,"opened_at":"${now.get().minusSeconds(600)}","sequence_no":$seq,"planned":true,
                "fix":{"purpose":"visit_open","fix_status":"ok","lat":23.8069,"lng":90.3687,"accuracy_m":12.0,"provider":"fused","is_mock":false,"reused":false,
                       "device":{"device_owner":true,"dev_options_enabled":false,"adb_enabled":false,"auto_time_enabled":true,"mock_app_present":false}},
                "geo":{"verdict":"in_range","distance_m":38.4,"radius_m_used":100,"max_accuracy_m_used":100,"location_basis":"master","action":"sale_allowed"}$extra}"""
            val a = UUID.randomUUID().toString(); val b = UUID.randomUUID().toString(); val c = UUID.randomUUID().toString()
            val answer = sync(sr, listOf(
                record("visit", visit(outlets[0], ""","call_photo_uuid":"$photo"""", 1), route, cu = a, family = a),
                record("visit", visit(outlets[1], ""","call_photo_skip_reason":"permission_denied"""", 2), route, cu = b, family = b),
                record("visit", visit(outlets[2], "", 3), route, cu = c, family = c),
            ))
            listOf(a, b, c).forEach { assertEquals("accepted", ack(answer, it).status()) }
            val d = today().toString()
            val page = json(getAs(amo, "/v1/supervisor/call-photos?business_date=$d").also { assertEquals(HttpStatusCode.OK, it.status, it.bodyAsText()) }.bodyAsText())
            val items = page["items"]!!.jsonArray.map { it.jsonObject }.associateBy { it["visit_uuid"]!!.jsonPrimitive.content }
            assertEquals(photo, items.getValue(a)["photo_uuid"]!!.jsonPrimitive.content)
            assertEquals("permission_denied", items.getValue(b)["skip_reason"]!!.jsonPrimitive.content)
            assertNull(items.getValue(c)["photo_uuid"]!!.let { if (it is kotlinx.serialization.json.JsonNull) null else it }, "a visit with neither is listed so a reviewer sees it")
            assertNotNull(items.getValue(a)["outlet_name"]); assertEquals("sr1001", items.getValue(a)["user_name"]?.let { world.scalar("SELECT username FROM app.app_user WHERE id = ${items.getValue(a)["user_id"]!!.jsonPrimitive.content}") })
            // paging and the route filter
            val p1 = json(getAs(tso, "/v1/supervisor/call-photos?business_date=$d&limit=2").bodyAsText())
            assertEquals(2, p1["items"]!!.jsonArray.size); assertNotNull(p1["next_cursor"]!!.jsonPrimitive.content)
            val none = json(getAs(tso, "/v1/supervisor/call-photos?business_date=$d&route_id=${route + 100000}").bodyAsText())
            assertEquals(0, none["items"]!!.jsonArray.size)
            // the SR has no review, a missing date is a 400
            assertEquals(HttpStatusCode.Forbidden, getAs(sr, "/v1/supervisor/call-photos?business_date=$d").status)
            assertEquals(HttpStatusCode.BadRequest, getAs(amo, "/v1/supervisor/call-photos").status)
        }
    }

    // ------------------------------------------------------------------------------------------------ checkForUpdate

    @Test
    fun theUpdateCheckCarriesTheReleaseNotesAtTopLevelToo() = world.withApi {
        with(world) {
            exec(
                """INSERT INTO app.app_release (flavour, version_name, version_code, abi, sha256, size_bytes, download_url, signing_cert_sha256, status, rollout_pct, notes_en, notes_bn, published_at, created_by)
                   SELECT 'sr', '9.9.9', 99999, 'universal', decode(repeat('ab', 32), 'hex'), 1000, 'https://example.invalid/sr.apk', decode(repeat('cd', 32), 'hex'), 'published', 100,
                          'Faster sync', 'দ্রুত সিঙ্ক', now(), id FROM app.app_user WHERE username = 'aron.system'""",
            )
            val r = json(getAs(sr, "/v1/app/update-check?flavour=sr&version_code=1&abi=universal").also { assertEquals(HttpStatusCode.OK, it.status, it.bodyAsText()) }.bodyAsText())
            assertEquals("Faster sync", r["notes_en"]!!.jsonPrimitive.content); assertEquals("দ্রুত সিঙ্ক", r["notes_bn"]!!.jsonPrimitive.content)
            assertEquals(r["notes_en"], r["latest"]!!.jsonObject["notes_en"])
            val none = json(getAs(sr, "/v1/app/update-check?flavour=sr&version_code=99999&abi=universal").bodyAsText())
            assertEquals(false, none["update_available"]!!.jsonPrimitive.content.toBoolean())
            assertEquals("null", none["notes_en"].toString())
        }
    }

    // ------------------------------------------------------------------------------------------------ route_add end to end

    @Test
    fun anSrRouteAddWithoutAClusterIsVerifiedAndApprovedAndTheOutletKeepsItsCluster() = world.withApi {
        with(world) {
            val route = one("SELECT id FROM app.route WHERE code = 'MIR-SR-D'")
            // an outlet of the zone that is on another route
            val other = longs("SELECT o.id FROM app.outlet o WHERE o.zone_id = (SELECT zone_id FROM app.route WHERE id = $route) AND o.route_id IS DISTINCT FROM $route AND o.status = 'active' AND o.cluster_id IS NOT NULL ORDER BY o.id LIMIT 1")
            if (other.isEmpty()) return@with // the seed has no such outlet: nothing to move
            val outlet = other.single()
            val cluster = one("SELECT cluster_id FROM app.outlet WHERE id = $outlet")
            val req = UUID.randomUUID().toString()
            val payload = """{"request_type":"route_add","outlet_id":$outlet,"proposed":{},"photo_uuids":[],
                "fix":{"purpose":"outlet_capture","fix_status":"ok","lat":23.8069,"lng":90.3687,"accuracy_m":12.0,"provider":"fused","is_mock":false,"reused":false,
                       "device":{"device_owner":true,"dev_options_enabled":false,"adb_enabled":false,"auto_time_enabled":true,"mock_app_present":false}}}"""
            val answer = sync(sr, listOf(record("outlet_change_request", payload, route, cu = req, family = req)))
            assertEquals("accepted", ack(answer, req).status(), answer.toString())
            val sub = one("SELECT id FROM app.sub_channel WHERE status = 'active' ORDER BY id LIMIT 1")
            val verified = postWeb("amo1001", "/v1/outlet-requests/$req/verify", """{"sub_channel_id":$sub}""")
            assertEquals(HttpStatusCode.OK, verified.status, verified.bodyAsText())
            val approved = postWeb("dmo1001", "/v1/outlet-requests/$req/approve", """{"route_id":$route,"change_reason":"SR asked to add this outlet to the route"}""")
            assertEquals(HttpStatusCode.OK, approved.status, approved.bodyAsText())
            assertEquals(route, one("SELECT route_id FROM app.outlet WHERE id = $outlet"), "the outlet is on the SR's route")
            assertEquals(cluster, one("SELECT cluster_id FROM app.outlet WHERE id = $outlet"), "it keeps the cluster it had")
        }
    }
}
