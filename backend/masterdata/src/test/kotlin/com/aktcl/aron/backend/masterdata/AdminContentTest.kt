package com.aktcl.aron.backend.masterdata

import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpMethod
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
import java.util.UUID
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** F-API-035a (trimmed per docs/27): surveys, rubrics, AV and KV content, tutorials, print templates and the asset SAS; plus F-API-027 GET /v1/tutorials. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AdminContentTest {
    private lateinit var env: BackendAdminEnv

    @BeforeAll fun setUp() { env = BackendAdminEnv() }
    @AfterAll fun tearDown() = env.close()

    private val reason = "Quarterly content refresh"
    private fun uuid() = UUID.randomUUID().toString()
    private fun etag(r: HttpResponse) = r.headers["ETag"]!!

    private suspend fun io.ktor.server.testing.ApplicationTestBuilder.asset(purpose: String, mime: String, bytes: Long = 1000, id: String = uuid(), sha: String = "c".repeat(64)): String {
        val r = sendA3(HttpMethod.Post, "/v1/admin/assets", env.tok("admin1001"), """{"asset_id":"$id","purpose":"$purpose","mime":"$mime","bytes":$bytes,"sha256":"$sha"}""")
        assertEquals(HttpStatusCode.OK, r.status, r.bodyAsText())
        return id
    }

    private fun audits(entity: String, id: Any, action: String? = null) =
        env.count("SELECT count(*) FROM app.audit_log WHERE entity = '$entity' AND entity_id = '$id'" + (action?.let { " AND action = '$it'" } ?: ""))

    // ---------------------------------------------------------------------------------------------- assets

    @Test
    fun assetSasIsWriteOnlyIdempotentSizeCappedAndTypeChecked() = env.app {
        val id = uuid(); val adm = env.tok("admin1001")
        fun body(purpose: String = "content_av", mime: String = "video/mp4", bytes: Long = 5_000_000, sha: String = "c".repeat(64)) = """{"asset_id":"$id","purpose":"$purpose","mime":"$mime","bytes":$bytes,"sha256":"$sha"}"""
        val r1 = sendA3(HttpMethod.Post, "/v1/admin/assets", adm, body())
        assertEquals(HttpStatusCode.OK, r1.status)
        val j1 = r1.objA3()
        assertEquals("assets/content_av/$id.mp4", j1.strA3("blob_path")); assertTrue(j1.strA3("upload_url").contains("sp=w&max=5000000"))
        val j2 = sendA3(HttpMethod.Post, "/v1/admin/assets", adm, body()).objA3()
        assertEquals(j1.strA3("blob_path"), j2.strA3("blob_path")); assertNotEquals(j1.strA3("upload_url"), j2.strA3("upload_url"))
        assertEquals(1, env.count("SELECT count(*) FROM app.admin_asset WHERE asset_id = '$id'"))
        assertEquals(1, audits("admin_asset", id))
        assertEquals(HttpStatusCode.Conflict, sendA3(HttpMethod.Post, "/v1/admin/assets", adm, body(bytes = 5_000_001)).status)
        // Caps: AV at cfg.content.max_item_mb (20), images at 300 KB; purpose and mime must agree.
        assertEquals(HttpStatusCode.PayloadTooLarge, sendA3(HttpMethod.Post, "/v1/admin/assets", adm, body(bytes = 21L * 1_048_576).replace(id, uuid())).status)
        assertEquals(HttpStatusCode.PayloadTooLarge, sendA3(HttpMethod.Post, "/v1/admin/assets", adm, body("content_kv", "image/jpeg", 307_201).replace(id, uuid())).status)
        assertEquals(HttpStatusCode.OK, sendA3(HttpMethod.Post, "/v1/admin/assets", adm, body("content_kv", "image/png", 307_200).replace(id, uuid())).status)
        assertEquals(HttpStatusCode.BadRequest, sendA3(HttpMethod.Post, "/v1/admin/assets", adm, body("content_kv", "video/mp4").replace(id, uuid())).status)
        assertEquals(HttpStatusCode.BadRequest, sendA3(HttpMethod.Post, "/v1/admin/assets", adm, body("nonsense").replace(id, uuid())).status)
        assertEquals(HttpStatusCode.BadRequest, sendA3(HttpMethod.Post, "/v1/admin/assets", adm, body(sha = "zz")).status)
        for (u in listOf("tso1001", "dmo1001", "support1001", "sr1001")) assertEquals(HttpStatusCode.Forbidden, sendA3(HttpMethod.Post, "/v1/admin/assets", env.tok(u), body()).status, u)
    }

    // ---------------------------------------------------------------------------------------------- tutorials

    @Test
    fun tutorialLifecycleVersionedWithReasonAuditAndTheRoleListMatchesTheSeededData() = env.app {
        val adm = env.tok("admin1001")
        val video = asset("tutorial_video", "video/mp4", 4_000_000); val manual = asset("tutorial_manual", "application/pdf", 80_000)
        fun body(kind: String, asset: String, roles: String, sort: Int, status: String = "active", why: String = reason) =
            """{"kind":"$kind","title_en":"How to sell","title_bn":"বিক্রয়","asset_id":"$asset","roles":$roles,"sort":$sort,"status":"$status","change_reason":"$why"}"""
        val c1 = sendA3(HttpMethod.Post, "/v1/admin/tutorials", adm, body("video", video, """["SR","TSO"]""", 2))
        assertEquals(HttpStatusCode.Created, c1.status); assertEquals("\"1\"", etag(c1))
        val t1 = c1.objA3(); val id1 = t1.strA3("tutorial_id")
        assertEquals("https://blob.test/assets/tutorial_video/$video.mp4", t1.strA3("url"))
        val id2 = sendA3(HttpMethod.Post, "/v1/admin/tutorials", adm, body("manual", manual, """["SR"]""", 1)).objA3().strA3("tutorial_id")
        assertEquals(1, audits("tutorial", id1, "create"))
        // Audit row carries the reason and was written with the row.
        assertEquals(reason, env.scalar("SELECT reason FROM app.audit_log WHERE entity = 'tutorial' AND entity_id = '$id1' AND action = 'create'"))

        // GET /v1/tutorials: the caller's role from the token, ordered by sort; a role without tutorials gets none.
        fun ids(r: JsonObject) = r.itemsA3().map { it.strA3("tutorial_id") }.filter { it == id1 || it == id2 }
        assertEquals(listOf(id2, id1), ids(sendA3(HttpMethod.Get, "/v1/tutorials", env.tok("sr1001")).objA3()))
        assertEquals(listOf(id1), ids(sendA3(HttpMethod.Get, "/v1/tutorials", env.tok("tso1001")).objA3()))
        assertEquals(emptyList(), ids(sendA3(HttpMethod.Get, "/v1/tutorials", env.tok("dmo1001")).objA3()))
        assertEquals(HttpStatusCode.Unauthorized, sendA3(HttpMethod.Get, "/v1/tutorials", null).status)

        // PATCH: If-Match is the quoted version; stale 412, missing 400, unknown 404; each save bumps the version and audits.
        val path = "/v1/admin/tutorials/$id1"
        assertEquals(HttpStatusCode.BadRequest, sendA3(HttpMethod.Patch, path, adm, body("video", video, """["SR"]""", 2)).status)
        assertEquals(HttpStatusCode.PreconditionFailed, sendA3(HttpMethod.Patch, path, adm, body("video", video, """["SR"]""", 2), mapOf("If-Match" to "\"7\"")).status)
        assertEquals(HttpStatusCode.NotFound, sendA3(HttpMethod.Patch, "/v1/admin/tutorials/987654", adm, body("video", video, """["SR"]""", 2), mapOf("If-Match" to "\"1\"")).status)
        val p1 = sendA3(HttpMethod.Patch, path, adm, body("video", video, """["SR","DMO"]""", 5), mapOf("If-Match" to "\"1\""))
        assertEquals(HttpStatusCode.OK, p1.status); assertEquals("\"2\"", etag(p1)); assertEquals("2", p1.objA3().getValue("version").jsonPrimitive.content)
        assertEquals(listOf(id1), ids(sendA3(HttpMethod.Get, "/v1/tutorials", env.tok("dmo1001")).objA3()))
        assertEquals(emptyList(), ids(sendA3(HttpMethod.Get, "/v1/tutorials", env.tok("tso1001")).objA3()))
        assertEquals(HttpStatusCode.PreconditionFailed, sendA3(HttpMethod.Patch, path, adm, body("video", video, """["SR"]""", 2), mapOf("If-Match" to "\"1\"")).status)
        assertEquals(1, audits("tutorial", id1, "update"))
        // Retiring it hides it from every phone and the web.
        assertEquals(HttpStatusCode.OK, sendA3(HttpMethod.Patch, path, adm, body("video", video, """["SR","DMO"]""", 5, "inactive", "Retired, replaced by a new video"), mapOf("If-Match" to "\"2\"")).status)
        assertEquals(listOf(id2), ids(sendA3(HttpMethod.Get, "/v1/tutorials", env.tok("sr1001")).objA3()))
        assertTrue(sendA3(HttpMethod.Get, "/v1/admin/tutorials", env.tok("support1001")).objA3().itemsA3().map { it.strA3("tutorial_id") }.containsAll(listOf(id1, id2)))
        assertTrue(sendA3(HttpMethod.Get, "/v1/admin/tutorials?status=inactive", adm).objA3().itemsA3().any { it.strA3("tutorial_id") == id1 })

        // Validation: reason, kind/asset agreement, roles, immutability of the kind.
        assertEquals(HttpStatusCode.BadRequest, sendA3(HttpMethod.Post, "/v1/admin/tutorials", adm, body("video", video, """["SR"]""", 1, why = "short")).status)
        assertEquals(HttpStatusCode.BadRequest, sendA3(HttpMethod.Post, "/v1/admin/tutorials", adm, body("video", manual, """["SR"]""", 1)).status)
        assertEquals(HttpStatusCode.BadRequest, sendA3(HttpMethod.Post, "/v1/admin/tutorials", adm, body("video", uuid(), """["SR"]""", 1)).status)
        assertEquals(HttpStatusCode.BadRequest, sendA3(HttpMethod.Post, "/v1/admin/tutorials", adm, body("video", video, "[]", 1)).status)
        assertEquals(HttpStatusCode.BadRequest, sendA3(HttpMethod.Post, "/v1/admin/tutorials", adm, body("video", video, """["PRESIDENT"]""", 1)).status)
        assertEquals(HttpStatusCode.BadRequest, sendA3(HttpMethod.Post, "/v1/admin/tutorials", adm, body("video", video, """["SR"]""", -1)).status)
        assertEquals(HttpStatusCode.BadRequest, sendA3(HttpMethod.Patch, "/v1/admin/tutorials/$id2", adm, body("video", video, """["SR"]""", 1), mapOf("If-Match" to "\"1\"")).status)
    }

    @Test
    fun randomisedTutorialListsNeverShowAnotherRolesContent() = env.app {
        val adm = env.tok("admin1001"); val rnd = Random(27)
        val all = env.roles.values.map { it.wire }.distinct()
        val published = mutableListOf<Pair<String, Set<String>>>()
        repeat(12) { n ->
            val roles = all.shuffled(rnd).take(rnd.nextInt(1, 4)).toSet()
            val a = asset("tutorial_manual", "application/pdf", 1000L + n)
            val r = sendA3(HttpMethod.Post, "/v1/admin/tutorials", adm, """{"kind":"manual","title_en":"M$n","asset_id":"$a","roles":[${roles.joinToString(",") { "\"$it\"" }}],"sort":$n,"status":"${if (n % 4 == 0) "inactive" else "active"}","change_reason":"$reason"}""")
            assertEquals(HttpStatusCode.Created, r.status)
            if (n % 4 != 0) published += r.objA3().strA3("tutorial_id") to roles
        }
        repeat(80) {
            val user = env.roles.keys.random(rnd); val role = env.roles.getValue(user).wire
            val got = sendA3(HttpMethod.Get, "/v1/tutorials", env.tok(user)).objA3().itemsA3().map { it.strA3("tutorial_id") }.toSet()
            val mine = published.filter { role in it.second }.map { it.first }.toSet()
            // Everything created by this test and visible must be for this role; everything for this role must be visible.
            assertEquals(emptySet(), got.filter { id -> published.any { it.first == id } }.toSet() - mine, "$user ($role) saw foreign tutorials")
            assertTrue(mine.all { it in got }, "$user ($role) misses tutorials")
        }
    }

    // ---------------------------------------------------------------------------------------------- surveys

    private fun q(key: String, type: String = "bool", extra: String = "") = """{"key":"$key","answer_type":"$type","label_en":"Is $key present","label_bn":"আছে?"$extra}"""
    private fun survey(questions: List<String>, kind: String = "posm", from: String = "2026-11-01", to: String? = null, why: String = reason, title: String = "POSM check") =
        """{"kind":"$kind","title_en":"$title","title_bn":"পোসম","questions":[${questions.joinToString(",")}],"valid_from":"$from",${to?.let { """"valid_to":"$it",""" } ?: ""}"points_per_photo":50,"change_reason":"$why"}"""

    @Test
    fun surveyVersionsKeepQuestionIdsAndEveryWriteIsAudited() = env.app {
        val adm = env.tok("admin1001")
        val c = sendA3(HttpMethod.Post, "/v1/admin/surveys", adm, survey(listOf(q("shelf"), q("price_tag", extra = ""","show_if_key":"shelf","show_if_bool":true"""), q("photo", "photo_only"))))
        assertEquals(HttpStatusCode.Created, c.status, c.bodyAsText()); assertEquals("\"1\"", etag(c))
        val s1 = c.objA3(); val id = s1.strA3("survey_id")
        assertEquals(listOf("1", "2", "3"), s1.getValue("questions").jsonArray.map { it.strA3("question_id") })
        assertEquals("true", s1.getValue("questions").jsonArray[2].jsonObject.getValue("requires_photo").jsonPrimitive.content)
        assertEquals(1, audits("survey", id, "create")); assertEquals(reason, env.scalar("SELECT reason FROM app.audit_log WHERE entity = 'survey' AND entity_id = '$id'"))
        // Version 2: drop `price_tag`, keep `shelf` and `photo` (ids stable), add `display` (next id, never reusing 2).
        val p = sendA3(HttpMethod.Patch, "/v1/admin/surveys/$id", adm, survey(listOf(q("photo", "photo_only"), q("shelf"), q("display", "num")), to = "2026-12-31", why = "Display count added for November"), mapOf("If-Match" to "\"1\""))
        assertEquals(HttpStatusCode.OK, p.status, p.bodyAsText()); assertEquals("\"2\"", etag(p))
        val s2 = p.objA3()
        assertEquals(listOf("3", "1", "4"), s2.getValue("questions").jsonArray.map { it.strA3("question_id") }); assertEquals("2026-12-31", s2.strA3("valid_to"))
        assertEquals(2, env.count("SELECT count(*) FROM app.survey_version WHERE survey_id = $id"))
        assertEquals(1, audits("survey", id, "update"))
        assertEquals(HttpStatusCode.PreconditionFailed, sendA3(HttpMethod.Patch, "/v1/admin/surveys/$id", adm, survey(listOf(q("shelf"))), mapOf("If-Match" to "\"1\"")).status)
        assertEquals(HttpStatusCode.BadRequest, sendA3(HttpMethod.Patch, "/v1/admin/surveys/$id", adm, survey(listOf(q("shelf")), kind = "amo_survey"), mapOf("If-Match" to "\"2\"")).status)
        val deact = sendA3(HttpMethod.Patch, "/v1/admin/surveys/$id", adm, survey(listOf(q("shelf")), why = "Survey ended, deactivating").replace("\"change_reason\"", "\"status\":\"inactive\",\"change_reason\""), mapOf("If-Match" to "\"2\""))
        assertEquals("inactive", deact.objA3().strA3("status"))
        // List with the status filter and paging; support reads, tso/dmo/sr do not.
        assertTrue(sendA3(HttpMethod.Get, "/v1/admin/surveys?status=inactive", env.tok("support1001")).objA3().itemsA3().any { it.strA3("survey_id") == id })
        assertTrue(sendA3(HttpMethod.Get, "/v1/admin/surveys?status=active", adm).objA3().itemsA3().none { it.strA3("survey_id") == id })
        for (u in listOf("tso1001", "dmo1001", "sr1001", "amo1001")) assertEquals(HttpStatusCode.Forbidden, sendA3(HttpMethod.Get, "/v1/admin/surveys", env.tok(u)).status, u)
        assertEquals(HttpStatusCode.Forbidden, sendA3(HttpMethod.Post, "/v1/admin/surveys", env.tok("support1001"), survey(listOf(q("aa")))).status)
        assertEquals(HttpStatusCode.Forbidden, sendA3(HttpMethod.Patch, "/v1/admin/surveys/$id", env.tok("tso1001"), survey(listOf(q("aa"))), mapOf("If-Match" to "\"3\"")).status)
    }

    @Test
    fun surveyValidationUsesContractErrorCodes() = env.app {
        val adm = env.tok("admin1001")
        suspend fun post(b: String) = sendA3(HttpMethod.Post, "/v1/admin/surveys", adm, b)
        assertEquals(HttpStatusCode.BadRequest, post(survey(emptyList())).status)
        assertEquals(HttpStatusCode.BadRequest, post(survey((1..51).map { q("q$it") })).status)
        assertEquals(HttpStatusCode.BadRequest, post(survey(listOf(q("dup"), q("dup")))).status)
        assertEquals(HttpStatusCode.BadRequest, post(survey(listOf(q("Bad Key")))).status)
        assertEquals(HttpStatusCode.BadRequest, post(survey(listOf(q("aa", "scale")))).status)
        assertEquals(HttpStatusCode.BadRequest, post(survey(listOf(q("aa", extra = ""","show_if_key":"later","show_if_bool":true""")))).status)
        assertEquals(HttpStatusCode.BadRequest, post(survey(listOf(q("aa", "num"), q("bb", extra = ""","show_if_key":"aa","show_if_bool":true""")))).status)
        assertEquals(HttpStatusCode.BadRequest, post(survey(listOf(q("aa")), kind = "quiz")).status)
        assertEquals(HttpStatusCode.BadRequest, post(survey(listOf(q("aa")), from = "2026-11-10", to = "2026-11-01")).status)
        assertEquals(HttpStatusCode.BadRequest, post(survey(listOf(q("aa")), why = "too short")).status)
        assertEquals(HttpStatusCode.BadRequest, post(survey(listOf(q("aa")), title = "t".repeat(121))).status)
        val r = post(survey(listOf(q("aa")), why = "short")); val problem = r.objA3()
        assertEquals("ERR_VALIDATION", problem.strA3("code")); assertTrue(problem.getValue("errors").jsonArray.any { it.strA3("pointer") == "body.change_reason" })
        assertEquals(HttpStatusCode.BadRequest, post("""{"kind":"posm"}""").status)
    }

    // ---------------------------------------------------------------------------------------------- rubrics

    private fun rubric(criteria: List<String>, kind: String = "joint_call", why: String = reason) =
        """{"kind":"$kind","criteria":[${criteria.joinToString(",")}],"change_reason":"$why"}"""
    private fun crit(key: String, type: String = "stars_1_5") = """{"key":"$key","label_en":"Criterion $key","label_bn":"মানদণ্ড","answer_type":"$type"}"""

    @Test
    fun rubricsAreVersionedAndAudited() = env.app {
        val adm = env.tok("superadmin1001")
        val c = sendA3(HttpMethod.Post, "/v1/admin/rubrics", adm, rubric(listOf(crit("greeting"), crit("stock_check", "bool"), crit("notes", "text"))))
        assertEquals(HttpStatusCode.Created, c.status, c.bodyAsText()); assertEquals("\"1\"", etag(c))
        val r1 = c.objA3(); val id = r1.strA3("rubric_id")
        assertEquals(listOf("score_1_5", "bool", "text"), r1.getValue("criteria").jsonArray.map { it.strA3("answer_type") })
        assertEquals(listOf("1", "2", "3"), r1.getValue("criteria").jsonArray.map { it.strA3("criterion_id") })
        val p = sendA3(HttpMethod.Patch, "/v1/admin/rubrics/$id", adm, rubric(listOf(crit("stock_check", "bool"), crit("closing")), why = "Replace notes with closing"), mapOf("If-Match" to "\"1\""))
        assertEquals(HttpStatusCode.OK, p.status); assertEquals("\"2\"", etag(p))
        assertEquals(listOf("2", "4"), p.objA3().getValue("criteria").jsonArray.map { it.strA3("criterion_id") })
        assertEquals(1, audits("rubric", id, "create")); assertEquals(1, audits("rubric", id, "update"))
        assertEquals(HttpStatusCode.PreconditionFailed, sendA3(HttpMethod.Patch, "/v1/admin/rubrics/$id", adm, rubric(listOf(crit("aa"))), mapOf("If-Match" to "\"1\"")).status)
        assertEquals(HttpStatusCode.NotFound, sendA3(HttpMethod.Patch, "/v1/admin/rubrics/987654", adm, rubric(listOf(crit("aa"))), mapOf("If-Match" to "\"1\"")).status)
        assertEquals(HttpStatusCode.BadRequest, sendA3(HttpMethod.Post, "/v1/admin/rubrics", adm, rubric(emptyList())).status)
        assertEquals(HttpStatusCode.BadRequest, sendA3(HttpMethod.Post, "/v1/admin/rubrics", adm, rubric(listOf(crit("aa", "score_1_5")))).status)
        assertEquals(HttpStatusCode.BadRequest, sendA3(HttpMethod.Post, "/v1/admin/rubrics", adm, rubric(listOf(crit("aa"), crit("aa")))).status)
        assertEquals(HttpStatusCode.BadRequest, sendA3(HttpMethod.Post, "/v1/admin/rubrics", adm, rubric(listOf(crit("aa")), kind = "exam")).status)
        assertEquals(HttpStatusCode.BadRequest, sendA3(HttpMethod.Post, "/v1/admin/rubrics", adm, rubric(listOf(crit("aa")), why = "tiny")).status)
        assertTrue(sendA3(HttpMethod.Get, "/v1/admin/rubrics", env.tok("support1001")).objA3().itemsA3().any { it.strA3("rubric_id") == id })
        assertEquals(HttpStatusCode.Forbidden, sendA3(HttpMethod.Get, "/v1/admin/rubrics", env.tok("tso1001")).status)
        assertEquals(HttpStatusCode.Forbidden, sendA3(HttpMethod.Post, "/v1/admin/rubrics", env.tok("dmo1001"), rubric(listOf(crit("aa")))).status)
    }

    // ---------------------------------------------------------------------------------------------- AV and KV content

    private fun content(kind: String, asset: String, scope: String = "[]", seq: Int = 1, why: String = reason, status: String = "active") =
        """{"kind":"$kind","title_en":"Summer film","asset_id":"$asset","valid_from":"2026-11-01","valid_to":"2026-11-30","sequence":$seq,"assigned_scope":$scope,"status":"$status","change_reason":"$why"}"""

    @Test
    fun contentTakesItsAssetExpandsTheScopeAndBumpsTheVersion() = env.app {
        val adm = env.tok("admin1001")
        val av = asset("content_av", "video/mp4", 3_000_000); val kv = asset("content_kv", "image/jpeg", 90_000)
        val zoneId = env.scalar("SELECT id FROM app.zone WHERE code = 'Z-MIR'")!!.toLong()
        val c = sendA3(HttpMethod.Post, "/v1/admin/content", adm, content("av", av, """[{"node_type":"zone","node_id":$zoneId}]"""))
        assertEquals(HttpStatusCode.Created, c.status, c.bodyAsText()); assertEquals("\"1\"", etag(c))
        val c1 = c.objA3(); val id = c1.strA3("content_id")
        assertEquals("https://blob.test/assets/content_av/$av.mp4", c1.strA3("asset_url")); assertEquals("3000000", c1.getValue("bytes").jsonPrimitive.content); assertEquals("c".repeat(64), c1.strA3("sha256"))
        assertEquals(60, c1.getValue("outlet_ids").jsonArray.size)
        assertEquals(1, audits("content_item", id, "create")); assertEquals(reason, env.scalar("SELECT reason FROM app.audit_log WHERE entity = 'content_item' AND entity_id = '$id'"))
        // Narrow to one route, replace the asset (kind av needs an AV asset): version 2.
        val av2 = asset("content_av", "video/mp4", 2_000_000)
        val routeId = env.routeIds.getValue("MIR-SR-D")
        val p = sendA3(HttpMethod.Patch, "/v1/admin/content/$id", adm, content("av", av2, """[{"node_type":"route","node_id":$routeId}]""", seq = 2, why = "Route-specific film for Mirpur 10"), mapOf("If-Match" to "\"1\""))
        assertEquals(HttpStatusCode.OK, p.status, p.bodyAsText()); assertEquals("\"2\"", etag(p))
        val c2 = p.objA3()
        assertEquals(20, c2.getValue("outlet_ids").jsonArray.size); assertEquals("2", c2.getValue("version").jsonPrimitive.content); assertEquals("2000000", c2.getValue("bytes").jsonPrimitive.content)
        assertEquals(1, audits("content_item", id, "update"))
        assertEquals(HttpStatusCode.PreconditionFailed, sendA3(HttpMethod.Patch, "/v1/admin/content/$id", adm, content("av", av2), mapOf("If-Match" to "\"1\"")).status)
        // Validation and refusals.
        assertEquals(HttpStatusCode.BadRequest, sendA3(HttpMethod.Post, "/v1/admin/content", adm, content("av", kv)).status)
        assertEquals(HttpStatusCode.BadRequest, sendA3(HttpMethod.Post, "/v1/admin/content", adm, content("av", uuid())).status)
        assertEquals(HttpStatusCode.BadRequest, sendA3(HttpMethod.Post, "/v1/admin/content", adm, content("av", av, seq = 21)).status)
        assertEquals(HttpStatusCode.BadRequest, sendA3(HttpMethod.Post, "/v1/admin/content", adm, content("av", av, why = "nope")).status)
        assertEquals(HttpStatusCode.BadRequest, sendA3(HttpMethod.Post, "/v1/admin/content", adm, content("av", av, """[{"node_type":"planet","node_id":1}]""")).status)
        assertEquals(HttpStatusCode.BadRequest, sendA3(HttpMethod.Post, "/v1/admin/content", adm, content("av", av).replace("2026-11-30", "2026-10-01")).status)
        assertEquals(HttpStatusCode.Created, sendA3(HttpMethod.Post, "/v1/admin/content", adm, content("kv", kv, seq = 3)).status)
        assertTrue(sendA3(HttpMethod.Get, "/v1/admin/content?status=active&limit=1", env.tok("support1001")).objA3().itemsA3().size == 1)
        for (u in listOf("tso1001", "dmo1001", "sr1001")) assertEquals(HttpStatusCode.Forbidden, sendA3(HttpMethod.Get, "/v1/admin/content", env.tok(u)).status, u)
        assertEquals(HttpStatusCode.Forbidden, sendA3(HttpMethod.Post, "/v1/admin/content", env.tok("support1001"), content("av", av)).status)
    }

    @Test
    fun anAssetAboveTheConfiguredItemCapIsRefusedAtTheContentWrite() {
        var big = ""
        env.app { big = asset("content_av", "video/mp4", 6_000_000) }
        env.app(mapOf("cfg.content.max_item_mb" to kotlinx.serialization.json.JsonPrimitive(5))) {
            val r = sendA3(HttpMethod.Post, "/v1/admin/content", env.tok("admin1001"), content("av", big))
            assertEquals(HttpStatusCode.PayloadTooLarge, r.status); assertTrue(r.bodyAsText().contains("ERR_PAYLOAD_TOO_LARGE"))
            // The asset request itself is capped by the same key.
            assertEquals(HttpStatusCode.PayloadTooLarge, sendA3(HttpMethod.Post, "/v1/admin/assets", env.tok("admin1001"), """{"asset_id":"${uuid()}","purpose":"content_av","mime":"video/mp4","bytes":6000000,"sha256":"${"d".repeat(64)}"}""").status)
        }
    }

    // ---------------------------------------------------------------------------------------------- print templates

    private fun tpl(kind: String = "cash_memo", cols: Int = 32, json: String = """{"lines":["{{memo_no}}"]}""", from: String = "2099-01-01", why: String = reason) =
        """{"kind":"$kind","font_columns":$cols,"template_json":${jsonString(json)},"effective_from":"$from","change_reason":"$why"}"""

    private fun jsonString(s: String) = kotlinx.serialization.json.JsonPrimitive(s).toString()

    @Test
    fun printTemplatesArePublishedAsNewVersionsAndAudited() = env.app {
        val adm = env.tok("admin1001")
        val a = sendA3(HttpMethod.Post, "/v1/admin/print-templates", adm, tpl(kind = "void_slip"))
        assertEquals(HttpStatusCode.Created, a.status, a.bodyAsText())
        assertEquals("1", a.objA3().getValue("version").jsonPrimitive.content)
        val b = sendA3(HttpMethod.Post, "/v1/admin/print-templates", adm, tpl(kind = "void_slip", cols = 42, json = """{"lines":["v2"]}"""))
        assertEquals("2", b.objA3().getValue("version").jsonPrimitive.content)
        assertEquals(2, audits("print_template", "void_slip:1") + audits("print_template", "void_slip:2"))
        val list = sendA3(HttpMethod.Get, "/v1/admin/print-templates", env.tok("support1001")).objA3().itemsA3().filter { it.strA3("kind") == "void_slip" }
        assertEquals(listOf("2", "1"), list.map { it.getValue("version").jsonPrimitive.content })
        assertEquals(setOf("kind", "version", "font_columns", "template_json"), list.first().keys)
        assertEquals(HttpStatusCode.BadRequest, sendA3(HttpMethod.Post, "/v1/admin/print-templates", adm, tpl(json = "not json")).status)
        assertEquals(HttpStatusCode.BadRequest, sendA3(HttpMethod.Post, "/v1/admin/print-templates", adm, tpl(json = "[1,2]")).status)
        assertEquals(HttpStatusCode.BadRequest, sendA3(HttpMethod.Post, "/v1/admin/print-templates", adm, tpl(cols = 40)).status)
        assertEquals(HttpStatusCode.BadRequest, sendA3(HttpMethod.Post, "/v1/admin/print-templates", adm, tpl(kind = "invoice")).status)
        assertEquals(HttpStatusCode.BadRequest, sendA3(HttpMethod.Post, "/v1/admin/print-templates", adm, tpl(from = "2020-01-01")).status)
        assertEquals(HttpStatusCode.BadRequest, sendA3(HttpMethod.Post, "/v1/admin/print-templates", adm, tpl(why = "short")).status)
        assertEquals(HttpStatusCode.BadRequest, sendA3(HttpMethod.Post, "/v1/admin/print-templates", adm, tpl(json = "{\"x\":\"" + "a".repeat(20001) + "\"}")).status)
        for (u in listOf("tso1001", "dmo1001", "sr1001")) {
            assertEquals(HttpStatusCode.Forbidden, sendA3(HttpMethod.Get, "/v1/admin/print-templates", env.tok(u)).status, u)
            assertEquals(HttpStatusCode.Forbidden, sendA3(HttpMethod.Post, "/v1/admin/print-templates", env.tok(u), tpl()).status, u)
        }
        assertEquals(HttpStatusCode.Forbidden, sendA3(HttpMethod.Post, "/v1/admin/print-templates", env.tok("support1001"), tpl()).status)
    }
}
