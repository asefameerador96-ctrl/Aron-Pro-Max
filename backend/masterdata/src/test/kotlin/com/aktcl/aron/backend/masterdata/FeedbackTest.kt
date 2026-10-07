package com.aktcl.aron.backend.masterdata

import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import java.util.UUID
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** F-API-032: feedback stored once per client uuid with an optional image link; the inbox status is set with a reason and an audit row. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FeedbackTest {
    private lateinit var env: BackendAdminEnv

    @BeforeAll fun setUp() { env = BackendAdminEnv() }
    @AfterAll fun tearDown() = env.close()

    private fun uuid() = UUID.randomUUID().toString()
    private fun fb(id: String, category: String = "app_issue", title: String = "Sync is slow", desc: String = "Takes minutes on 2G", photo: String? = null) =
        """{"feedback_uuid":"$id","category_code":"$category","title":"$title","description":"$desc"${photo?.let { ""","photo_uuid":"$it"""" } ?: ""}}"""

    @Test
    fun submitReplayListAndTriageWithAnAuditRow() = env.app {
        val id = uuid(); val photo = uuid()
        val r1 = sendA3(HttpMethod.Post, "/v1/feedback", env.tok("tso1001"), fb(id, photo = photo))
        assertEquals(HttpStatusCode.Created, r1.status)
        val first = r1.bodyAsText(); val j = r1.objA3()
        assertEquals("new", j.strA3("status")); assertEquals(photo, j.strA3("photo_uuid")); assertEquals(env.ids.getValue("tso1001").toString(), j.getValue("user_id").jsonPrimitive.content)
        // Replay: one row, same body; other content under the same uuid is a conflict.
        val r2 = sendA3(HttpMethod.Post, "/v1/feedback", env.tok("tso1001"), fb(id, photo = photo))
        assertEquals(HttpStatusCode.OK, r2.status); assertEquals(first, r2.bodyAsText())
        assertEquals(1, env.count("SELECT count(*) FROM app.feedback WHERE client_uuid = '$id'"))
        assertEquals(HttpStatusCode.Conflict, sendA3(HttpMethod.Post, "/v1/feedback", env.tok("tso1001"), fb(id, title = "Other", photo = photo)).status)
        assertEquals(HttpStatusCode.Conflict, sendA3(HttpMethod.Post, "/v1/feedback", env.tok("tso2001"), fb(id, photo = photo)).status)

        // Reads: own, the division's DMO, support; not another division's DMO.
        assertTrue(sendA3(HttpMethod.Get, "/v1/feedback", env.tok("tso1001")).objA3().itemsA3().any { it.strA3("feedback_uuid") == id })
        assertTrue(sendA3(HttpMethod.Get, "/v1/feedback", env.tok("dmo1001")).objA3().itemsA3().any { it.strA3("feedback_uuid") == id })
        assertTrue(sendA3(HttpMethod.Get, "/v1/feedback", env.tok("support1001")).objA3().itemsA3().any { it.strA3("feedback_uuid") == id })
        assertTrue(sendA3(HttpMethod.Get, "/v1/feedback", env.tok("dmo3001")).objA3().itemsA3().none { it.strA3("feedback_uuid") == id })
        assertTrue(sendA3(HttpMethod.Get, "/v1/feedback", env.tok("tso2001")).objA3().itemsA3().none { it.strA3("feedback_uuid") == id })

        // Triage: needs a role, a status of the contract and a reason of 10+ characters; the audit row commits with the update.
        val path = "/v1/feedback/$id"
        for (u in listOf("tso1001", "dmo1001", "sr1001")) assertEquals(HttpStatusCode.Forbidden, sendA3(HttpMethod.Patch, path, env.tok(u), """{"status":"resolved","reason":"Looked into this today"}""").status, u)
        assertEquals(HttpStatusCode.BadRequest, sendA3(HttpMethod.Patch, path, env.tok("admin1001"), """{"status":"resolved","reason":"short"}""").status)
        assertEquals(HttpStatusCode.BadRequest, sendA3(HttpMethod.Patch, path, env.tok("admin1001"), """{"status":"done","reason":"Looked into this today"}""").status)
        assertEquals(HttpStatusCode.BadRequest, sendA3(HttpMethod.Patch, path, env.tok("admin1001"), """{"status":"resolved"}""").status)
        assertEquals(0, env.count("SELECT count(*) FROM app.audit_log WHERE entity = 'feedback' AND entity_id = '$id'"))
        val p1 = sendA3(HttpMethod.Patch, path, env.tok("support1001"), """{"status":"in_progress","reason":"Picked up by support"}""")
        assertEquals(HttpStatusCode.OK, p1.status); assertEquals("in_progress", p1.objA3().strA3("status"))
        assertEquals("in_progress", sendA3(HttpMethod.Get, "/v1/feedback", env.tok("tso1001")).objA3().itemsA3().first { it.strA3("feedback_uuid") == id }.strA3("status"))
        // The same status again is a replay: no second audit row.
        assertEquals(HttpStatusCode.OK, sendA3(HttpMethod.Patch, path, env.tok("support1001"), """{"status":"in_progress","reason":"Picked up by support"}""").status)
        assertEquals(1, env.count("SELECT count(*) FROM app.audit_log WHERE entity = 'feedback' AND entity_id = '$id' AND action = 'set_status' AND reason = 'Picked up by support'"))
        assertEquals("closed", sendA3(HttpMethod.Patch, path, env.tok("admin1001"), """{"status":"closed","reason":"Fixed in 1.2.0, closing"}""").objA3().strA3("status"))
        assertEquals(2, env.count("SELECT count(*) FROM app.audit_log WHERE entity = 'feedback' AND entity_id = '$id'"))
        assertEquals(HttpStatusCode.NotFound, sendA3(HttpMethod.Patch, "/v1/feedback/${uuid()}", env.tok("admin1001"), """{"status":"closed","reason":"Fixed in 1.2.0, closing"}""").status)
    }

    @Test
    fun validationAndRoles() = env.app {
        val tso = env.tok("tso1001")
        suspend fun post(b: String, t: String = tso) = sendA3(HttpMethod.Post, "/v1/feedback", t, b)
        assertEquals(HttpStatusCode.BadRequest, post(fb("nope")).status)
        assertEquals(HttpStatusCode.BadRequest, post(fb(uuid(), category = "Not A Code")).status)
        assertEquals(HttpStatusCode.BadRequest, post(fb(uuid(), category = "unlisted")).status)
        assertEquals(HttpStatusCode.BadRequest, post(fb(uuid(), title = "t".repeat(121))).status)
        assertEquals(HttpStatusCode.BadRequest, post(fb(uuid(), desc = "d".repeat(2001))).status)
        assertEquals(HttpStatusCode.BadRequest, post(fb(uuid(), photo = "not-a-uuid")).status)
        assertEquals(HttpStatusCode.BadRequest, post("""{"feedback_uuid":"${uuid()}","category_code":"idea","title":"t","description":"d","status":"closed"}""").status)
        for (u in listOf("sr1001", "amo1001", "dmo1001", "admin1001")) assertEquals(HttpStatusCode.Forbidden, post(fb(uuid()), env.tok(u)).status, u)
        for (u in listOf("sr1001", "amo1001")) assertEquals(HttpStatusCode.Forbidden, sendA3(HttpMethod.Get, "/v1/feedback", env.tok(u)).status, u)
        assertEquals(HttpStatusCode.BadRequest, sendA3(HttpMethod.Get, "/v1/feedback?limit=501", tso).status)
        assertEquals(HttpStatusCode.BadRequest, sendA3(HttpMethod.Get, "/v1/feedback?cursor=zz.zz", tso).status)
    }

    @Test
    fun randomisedListsNeverLeakFeedbackOutsideTheCallersReach() = env.app {
        val rnd = Random(32)
        val owners = listOf("tso1001", "tso2001", "tso3001")
        val owner = mutableMapOf<String, String>()
        for (o in owners) repeat(7) { val id = uuid(); assertEquals(HttpStatusCode.Created, sendA3(HttpMethod.Post, "/v1/feedback", env.tok(o), fb(id, title = "t$it")).status); owner[id] = o }
        val sees = mapOf(
            "tso1001" to setOf("tso1001"), "tso2001" to setOf("tso2001"), "tso3001" to setOf("tso3001"), "dmo1001" to setOf("tso1001", "tso2001"),
            "dmo3001" to setOf("tso3001"), "admin1001" to owners.toSet(), "support1001" to owners.toSet(), "superadmin1001" to owners.toSet(),
        )
        repeat(80) {
            val caller = sees.keys.random(rnd); val limit = rnd.nextInt(1, 9)
            val got = mutableSetOf<String>(); var cursor: String? = null; var pages = 0
            do {
                val r = sendA3(HttpMethod.Get, "/v1/feedback?limit=$limit&scope_ids=1,2,3&zone_ids=1" + (cursor?.let { "&cursor=$it" } ?: ""), env.tok(caller))
                assertEquals(HttpStatusCode.OK, r.status)
                val b = r.objA3(); assertTrue(b.itemsA3().size <= limit)
                b.itemsA3().forEach { assertTrue(got.add(it.strA3("feedback_uuid"))) }
                cursor = b.getValue("next_cursor").let { if (it is JsonNull) null else it.jsonPrimitive.content }; pages++
            } while (cursor != null && pages < 100)
            val mine = got.filter { it in owner }.toSet()
            val expected = owner.filter { it.value in sees.getValue(caller) }.keys
            assertEquals(emptySet(), mine - expected, "$caller leaked"); assertEquals(emptySet(), expected - mine, "$caller missing")
        }
    }
}
