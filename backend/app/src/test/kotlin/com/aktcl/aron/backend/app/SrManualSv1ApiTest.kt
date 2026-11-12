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

    @Test
    fun aTsoOfAnotherTerritoryNeverSeesTheCodesAndConsumedExpiredOrOtherDayCodesAreNotListed() = world.withApi {
        with(world) {
            val lkxTso = phone("lkxtso1001", "tso")
            val srId = one("SELECT id FROM app.app_user WHERE username = 'sr1001'")
            val lkxSrId = one("SELECT id FROM app.app_user WHERE username = 'lkxsr1001'")
            suspend fun ids(p: TsoPhone): Set<Long> {
                val r = getAs(p, "/v1/supervisor/device-otps")
                assertEquals(HttpStatusCode.OK, r.status, r.bodyAsText())
                assertEquals("no-store", r.headers["Cache-Control"], "a live code is never cached")
                return json(r.bodyAsText())["items"]!!.jsonArray.map { it.jsonObject["user_id"]!!.jsonPrimitive.content.toLong() }.toSet()
            }
            val mine = postAs(tso, "/v1/admin/device-otps", """{"user_id":$srId,"reason":"New phone for the Mirpur route"}""")
            assertEquals(HttpStatusCode.Created, mine.status, mine.bodyAsText())
            val theirs = postAs(lkxTso, "/v1/admin/device-otps", """{"user_id":$lkxSrId,"reason":"New phone for the other route"}""")
            assertEquals(HttpStatusCode.Created, theirs.status, theirs.bodyAsText())
            val mineCode = json(mine.bodyAsText())["otp"]!!.jsonPrimitive.content
            val theirCode = json(theirs.bodyAsText())["otp"]!!.jsonPrimitive.content
            assertEquals(setOf(srId), ids(tso), "the Mirpur TSO sees its own SR only")
            assertEquals(setOf(lkxSrId), ids(lkxTso), "and the reverse")
            val mineBody = getAs(tso, "/v1/supervisor/device-otps").bodyAsText()
            assertFalse(mineBody.contains(theirCode) || mineBody.contains("lkxsr1001"), "nothing of the other territory in the body")
            assertTrue(getAs(lkxTso, "/v1/supervisor/device-otps").bodyAsText().let { !it.contains(mineCode) || mineCode == theirCode })
            // the admin panel read carries no-store too
            assertEquals("no-store", getAs(tso, "/v1/admin/device-otps").headers["Cache-Control"])
            // consumed, expired and other-day codes are not listed
            exec("UPDATE app.device_otp SET consumed_at = created_at WHERE user_id = $srId")
            assertEquals(emptySet(), ids(tso), "a consumed code")
            exec("UPDATE app.device_otp SET consumed_at = NULL, created_at = created_at - interval '10 minutes', expires_at = created_at - interval '5 minutes' WHERE user_id = $srId")
            assertEquals(emptySet(), ids(tso), "an expired code")
            exec("UPDATE app.device_otp SET created_at = created_at - interval '1 day', expires_at = expires_at + interval '1 day' WHERE user_id = $lkxSrId")
            assertEquals(emptySet(), ids(lkxTso), "a code created on another business day")
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
            fun close(v: String, extra: String) = """{"visit_client_uuid":"$v","outcome_code":"sold","call_declined":false,"ended_at":"${now.get().minusSeconds(300)}","is_zero_sale":false$extra}"""
            val answer = sync(sr, listOf(
                record("visit", visit(outlets[0], "", 1), route, cu = a, family = a),
                record("visit_close", close(a, ""","call_photo_uuid":"$photo""""), route, family = a, rank = 1),
                record("visit", visit(outlets[1], "", 2), route, cu = b, family = b),
                record("visit_close", close(b, ""","call_photo_skip_reason_code":"permission_denied""""), route, family = b, rank = 1),
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

    @Test
    fun aCallPhotoOfAnotherTerritoryIsNeverReturnedToTheTsoOrAmoButItsOwnTsoSeesIt() = world.withApi {
        with(world) {
            // ScopeLeak: a call photo in the second (LKX) division. The reach comes from the token; no query value can widen it.
            val lkxSr = one("SELECT id FROM app.app_user WHERE username = 'lkxsr1001'")
            val route = one("SELECT id FROM app.route WHERE code LIKE 'LKX-%' AND kind = 'sr' AND status = 'active' ORDER BY id LIMIT 1")
            val outlet = one("SELECT id FROM app.outlet WHERE route_id = $route AND status = 'active' ORDER BY id LIMIT 1")
            val visit = UUID.randomUUID().toString(); val photo = UUID.randomUUID().toString()
            val d = today().toString()
            fresh.db.jdbi.useHandle<Exception> { h ->
                h.execute("SET session_replication_role = replica")
                try {
                    h.execute(
                        """INSERT INTO app.visit (client_uuid, family_uuid, business_date, user_id, route_id, captured_at, config_version, visit_kind, outlet_id, opened_at, sequence_no, planned,
                                                  verdict, radius_m_used, max_accuracy_m_used, location_basis, geo_action, call_photo_uuid)
                           VALUES ('$visit'::uuid, '$visit'::uuid, DATE '$d', $lkxSr, $route, now(), 0, 'sr_call', $outlet, now(), 1, false, 'in_range', 100, 50, 'master', 'sale_allowed', '$photo'::uuid)""",
                    )
                } finally { h.execute("SET session_replication_role = origin") }
            }
            val lkxTso = phone("lkxtso1001", "tso"); val lkxAmo = phone("lkxamo1001", "amo")
            for (own in listOf(lkxTso, lkxAmo)) {
                val seen = json(getAs(own, "/v1/supervisor/call-photos?business_date=$d").also { assertEquals(HttpStatusCode.OK, it.status) }.bodyAsText())["items"]!!.jsonArray
                assertTrue(seen.any { it.jsonObject["visit_uuid"]!!.jsonPrimitive.content == visit }, "the territory's own reviewer sees its photo")
            }
            for (other in listOf(tso, amo)) {
                for (q in listOf("", "&route_id=$route", "&limit=500")) {
                    val text = getAs(other, "/v1/supervisor/call-photos?business_date=$d$q").also { assertEquals(HttpStatusCode.OK, it.status) }.bodyAsText()
                    assertFalse(text.contains(visit) || text.contains(photo) || text.contains("\"outlet_id\":$outlet,"), "another territory's call photo leaked: $q")
                }
            }
        }
    }

    // ------------------------------------------------------------------------------------------------ checkForUpdate

    @Test
    fun theUpdateCheckCarriesTheReleaseNotesAtTopLevelToo() = world.withApi {
        with(world) {
            exec(
                """INSERT INTO app.app_release (flavour, version_name, version_code, abi, sha256, size_bytes, download_url, signing_cert_sha256, status, rollout_pct, notes_en, notes_bn, published_at, created_by, published_by)
                   SELECT 'sr', '9.9.9', 99999, 'universal', decode(repeat('ab', 32), 'hex'), 1000, 'https://example.invalid/sr.apk', decode(repeat('cd', 32), 'hex'), 'published', 100,
                          'Faster sync', 'দ্রুত সিঙ্ক', now(), a.id, p.id FROM app.app_user a, app.app_user p WHERE a.username = 'admin1001' AND p.username = 'superadmin1001'""",
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
            val verified = postWeb("admin1001", "/v1/outlet-requests/$req/verify", """{"sub_channel_id":$sub}""")
            assertEquals(HttpStatusCode.OK, verified.status, verified.bodyAsText())
            val approved = postWeb("superadmin1001", "/v1/outlet-requests/$req/approve", """{"route_id":$route,"change_reason":"SR asked to add this outlet to the route"}""")
            assertEquals(HttpStatusCode.OK, approved.status, approved.bodyAsText())
            assertEquals(route, one("SELECT route_id FROM app.outlet WHERE id = $outlet"), "the outlet is on the SR's route")
            assertEquals(cluster, one("SELECT cluster_id FROM app.outlet WHERE id = $outlet"), "it keeps the cluster it had")
        }
    }

    @Test
    fun aVerificationWhoseDecisionIsAServerFlagKindIsRefusedAndShowsNoFlag() = world.withApi {
        with(world) {
            val route = one("SELECT id FROM app.route WHERE code = 'MIR-SR-D'")
            val outlet = longs("SELECT id FROM app.outlet WHERE route_id = $route AND status = 'active' ORDER BY id LIMIT 1").single()
            val req = UUID.randomUUID().toString()
            val fix = """{"purpose":"outlet_capture","fix_status":"ok","lat":23.8069,"lng":90.3687,"accuracy_m":12.0,"provider":"fused","is_mock":false,"reused":false,
                       "device":{"device_owner":true,"dev_options_enabled":false,"adb_enabled":false,"auto_time_enabled":true,"mock_app_present":false}}"""
            val filed = sync(sr, listOf(record("outlet_change_request", """{"request_type":"info","outlet_id":$outlet,"proposed":{"name":"Flag Probe Store"},"photo_uuids":[],"fix":$fix}""", route, cu = req, family = req)))
            assertEquals("accepted", ack(filed, req).status(), filed.toString())
            for (bad in listOf("flag_possible_duplicate", "flag_cluster_dropped", "approved")) {
                val cu = UUID.randomUUID().toString()
                val a = ack(sync(amo, listOf(record("outlet_request_verification", """{"request_uuid":"$req","decision":"$bad","fix":$fix}""", route, cu = cu))), cu)
                assertEquals("rejected", a.status(), "decision $bad: $a")
            }
            assertEquals("0", scalar("SELECT count(*) FROM app.outlet_request_event WHERE request_uuid = '$req' AND event LIKE 'flag_%'"), "no flag event was written")
            val read = json(getWeb("admin1001", "/v1/outlet-requests/$req").also { assertEquals(HttpStatusCode.OK, it.status, it.bodyAsText()) }.bodyAsText())
            assertTrue(read["flags"]!!.jsonArray.none { it.jsonPrimitive.content.startsWith("possible") || it.jsonPrimitive.content == "cluster_dropped" }, "and none is shown")
            assertTrue(read["possible_duplicate_of"] == null || read["possible_duplicate_of"] is kotlinx.serialization.json.JsonNull)
        }
    }

    // ------------------------------------------------------------------------------------------------ call photo media join

    @Test
    fun theCallPhotoReviewJoinsTheMediaWhetherItArrivesBeforeOrAfterItsVisitAndOnlyOfTheSameUser() = world.withApi {
        with(world) {
            val route = one("SELECT id FROM app.route WHERE code = 'MIR-SR-D'")
            val outlets = longs("SELECT id FROM app.outlet WHERE route_id = $route AND status = 'active' ORDER BY id DESC LIMIT 4")
            val d = today().toString()
            fun visit(outlet: Long, seq: Int) = """{"visit_kind":"sr_call","outlet_id":$outlet,"opened_at":"${now.get().minusSeconds(600)}","sequence_no":$seq,"planned":true,
                "fix":{"purpose":"visit_open","fix_status":"ok","lat":23.8069,"lng":90.3687,"accuracy_m":12.0,"provider":"fused","is_mock":false,"reused":false,
                       "device":{"device_owner":true,"dev_options_enabled":false,"adb_enabled":false,"auto_time_enabled":true,"mock_app_present":false}},
                "geo":{"verdict":"in_range","distance_m":38.4,"radius_m_used":100,"max_accuracy_m_used":100,"location_basis":"master","action":"sale_allowed"}}"""
            fun close(v: String, photo: String) = """{"visit_client_uuid":"$v","outcome_code":"sold","call_photo_uuid":"$photo","call_declined":false,"ended_at":"${now.get().minusSeconds(300)}","is_zero_sale":false}"""
            fun media(photo: String, visit: String) = record(
                "media_meta",
                """{"purpose":"outlet_call","ref_type":"visit","ref_client_uuid":"$visit","sha256":"${"ab".repeat(32)}","bytes":2000,"width":640,"height":480,"mime":"image/jpeg",
                   "blob_path":"photos/$d/${UUID.randomUUID()}/$photo.jpg","taken_at":"${now.get().minusSeconds(700)}"}""",
                route, cu = photo, family = photo,
            )
            fun family(v: String, photo: String, outlet: Long, seq: Int) = listOf(
                record("visit", visit(outlet, seq), route, cu = v, family = v),
                record("visit_close", close(v, photo), route, family = v, rank = 1),
            )
            suspend fun rowsOf(): Map<String, kotlinx.serialization.json.JsonObject> =
                json(getAs(tso, "/v1/supervisor/call-photos?business_date=$d&limit=500").also { assertEquals(HttpStatusCode.OK, it.status, it.bodyAsText()) }.bodyAsText())["items"]!!.jsonArray
                    .map { it.jsonObject }.associateBy { it["visit_uuid"]!!.jsonPrimitive.content }
            suspend fun statusOf(v: String): String? = rowsOf().getValue(v)["media_status"]!!.let { if (it is kotlinx.serialization.json.JsonNull) null else it.jsonPrimitive.content }

            // (1a) the media record arrives BEFORE its visit family
            val vBefore = UUID.randomUUID().toString(); val photoBefore = UUID.randomUUID().toString()
            val mediaBefore = media(photoBefore, vBefore)
            val mAck = ack(sync(sr, listOf(mediaBefore)), photoBefore)
            val famBefore = family(vBefore, photoBefore, outlets[0], 51)
            val answerBefore = sync(sr, famBefore)
            assertEquals("accepted", ack(answerBefore, vBefore).status(), answerBefore.toString())
            // an out-of-order media may be held, then released by its visit; or stored at once. Either way it is joined once the row exists.
            val resend = if (scalar("SELECT count(*) FROM app.media WHERE client_uuid = '$photoBefore'") == "0") ack(sync(sr, listOf(mediaBefore)), photoBefore) else null
            assertEquals("1", scalar("SELECT count(*) FROM app.media WHERE client_uuid = '$photoBefore'"), "the media row exists (first answer was ${mAck.status()} ${mAck["code"]} ${mAck["retryable"]}; resend $resend)")
            assertNotNull(statusOf(vBefore), "(1a) media sent before its visit is joined")

            // (1b) the media record arrives AFTER its visit family
            val vAfter = UUID.randomUUID().toString(); val photoAfter = UUID.randomUUID().toString()
            val answerAfter = sync(sr, family(vAfter, photoAfter, outlets[1], 52))
            assertEquals("accepted", ack(answerAfter, vAfter).status(), answerAfter.toString())
            assertNull(statusOf(vAfter), "no media row yet: nothing to join")
            assertEquals("accepted", ack(sync(sr, listOf(media(photoAfter, vAfter))), photoAfter).status())
            assertNotNull(statusOf(vAfter), "(1b) media sent after its visit is joined")

            // (2) a media row of ANOTHER user under the same uuid is never joined
            val vForeign = UUID.randomUUID().toString(); val photoForeign = UUID.randomUUID().toString()
            val answerForeign = sync(sr, family(vForeign, photoForeign, outlets[2], 53))
            assertEquals("accepted", ack(answerForeign, vForeign).status(), answerForeign.toString())
            val amoId = one("SELECT id FROM app.app_user WHERE username = 'amo1001'")
            fresh.db.jdbi.useHandle<Exception> { h ->
                h.execute("SET session_replication_role = replica")
                try {
                    h.execute(
                        "INSERT INTO app.media (client_uuid, family_uuid, business_date, user_id, captured_at, config_version, purpose, ref_type, ref_client_uuid, sha256, bytes, width, height, blob_path, taken_at, status, stored_at, blob_bytes, blob_sha256) " +
                            "VALUES ('$photoForeign', gen_random_uuid(), DATE '$d', $amoId, now(), 0, 'outlet_capture', 'visit', '$vForeign', decode(repeat('ab', 32), 'hex'), 1000, 640, 480, " +
                            "'photos/$d/${UUID.randomUUID()}/$photoForeign.jpg', now(), 'stored', now(), 1000, decode(repeat('ab', 32), 'hex'))",
                    )
                } finally { h.execute("SET session_replication_role = origin") }
            }
            assertEquals("1", scalar("SELECT count(*) FROM app.media WHERE client_uuid = '$photoForeign'"))
            val foreignRow = rowsOf().getValue(vForeign)
            assertTrue(foreignRow["photo_uuid"] is kotlinx.serialization.json.JsonNull, "(2) the uuid names another user's media: it is not offered as this visit's photo")
            assertNull(statusOf(vForeign), "(2) a media row owned by another user is not joined")
            assertTrue(foreignRow["taken_at"] is kotlinx.serialization.json.JsonNull, "none of its fields leak either")
            // (4) the SR's own media of another purpose (a force-sale photo) is no call photo either
            val vOther = UUID.randomUUID().toString(); val photoOther = UUID.randomUUID().toString()
            val answerOther = sync(sr, family(vOther, photoOther, outlets[3], 54))
            assertEquals("accepted", ack(answerOther, vOther).status(), answerOther.toString())
            val srId = one("SELECT id FROM app.app_user WHERE username = 'sr1001'")
            fresh.db.jdbi.useHandle<Exception> { h ->
                h.execute("SET session_replication_role = replica")
                try {
                    h.execute(
                        "INSERT INTO app.media (client_uuid, family_uuid, business_date, user_id, captured_at, config_version, purpose, ref_type, ref_client_uuid, sha256, bytes, width, height, blob_path, taken_at, status, stored_at, blob_bytes, blob_sha256) " +
                            "VALUES ('$photoOther', gen_random_uuid(), DATE '$d', $srId, now(), 0, 'force_sale', 'visit', '$vOther', decode(repeat('ab', 32), 'hex'), 1000, 640, 480, " +
                            "'photos/$d/${UUID.randomUUID()}/$photoOther.jpg', now(), 'stored', now(), 1000, decode(repeat('ab', 32), 'hex'))",
                    )
                } finally { h.execute("SET session_replication_role = origin") }
            }
            val otherRow = rowsOf().getValue(vOther)
            assertTrue(otherRow["photo_uuid"] is kotlinx.serialization.json.JsonNull, "(4) a force-sale photo uuid is not offered as the call photo")
            assertNull(statusOf(vOther), "(4) and not joined")
            // (3) a TSO whose reach is a route only: not built here, no helper creates such a user in TsoWorld
        }
    }
}
