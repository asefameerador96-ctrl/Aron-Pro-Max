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

/** F-API-022: TSO leave applied idempotently, listed in reach, decided by the manager with an audit row. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LeaveTest {
    private lateinit var env: BackendAdminEnv

    @BeforeAll fun setUp() { env = BackendAdminEnv() }
    @AfterAll fun tearDown() = env.close()

    private fun uuid() = UUID.randomUUID().toString()
    private fun apply(id: String, from: String = "2027-03-01", days: Int = 2, type: String = "casual", reason: String = "Family event") =
        """{"leave_uuid":"$id","leave_type_code":"$type","from_date":"$from","days":$days,"reason":"$reason"}"""

    @Test
    fun applyReplayDecideAndAuditEndToEnd() = env.app {
        val id = uuid()
        val r1 = sendA3(HttpMethod.Post, "/v1/leave", env.tok("tso1001"), apply(id))
        assertEquals(HttpStatusCode.Created, r1.status)
        val first = r1.bodyAsText()
        val j = r1.objA3()
        assertEquals("pending", j.strA3("status")); assertEquals("2027-03-02", j.strA3("to_date")); assertEquals(env.ids.getValue("tso1001").toString(), j.getValue("user_id").jsonPrimitive.content)
        assertTrue(j["decided_at"] == null || j["decided_at"] is JsonNull)
        // Replay by client uuid stores one row and returns the same body.
        val r2 = sendA3(HttpMethod.Post, "/v1/leave", env.tok("tso1001"), apply(id))
        assertEquals(HttpStatusCode.OK, r2.status); assertEquals(first, r2.bodyAsText())
        assertEquals(1, env.count("SELECT count(*) FROM app.leave_application WHERE client_uuid = '$id'"))
        // The same uuid with other content is a conflict, never an overwrite.
        assertEquals(HttpStatusCode.Conflict, sendA3(HttpMethod.Post, "/v1/leave", env.tok("tso1001"), apply(id, days = 5)).status)
        assertEquals(HttpStatusCode.Conflict, sendA3(HttpMethod.Post, "/v1/leave", env.tok("tso2001"), apply(id)).status)

        // The TSO sees it; the DMO of the division sees it; the DMO of another division does not.
        assertEquals(listOf(id), sendA3(HttpMethod.Get, "/v1/leave?status=pending&from=2027-03-01&to=2027-03-31", env.tok("tso1001")).objA3().itemsA3().map { it.strA3("leave_uuid") })
        assertTrue(sendA3(HttpMethod.Get, "/v1/leave", env.tok("dmo1001")).objA3().itemsA3().any { it.strA3("leave_uuid") == id })
        assertTrue(sendA3(HttpMethod.Get, "/v1/leave", env.tok("dmo3001")).objA3().itemsA3().none { it.strA3("leave_uuid") == id })

        // Decide: out-of-reach DMO, the TSO himself and an SR are refused; the DMO approves; a replay changes nothing.
        val path = "/v1/leave/$id/decision"
        assertEquals(HttpStatusCode.NotFound, sendA3(HttpMethod.Post, path, env.tok("dmo3001"), """{"decision":"approve"}""").status)
        assertEquals(HttpStatusCode.Forbidden, sendA3(HttpMethod.Post, path, env.tok("tso1001"), """{"decision":"approve"}""").status)
        assertEquals(HttpStatusCode.Forbidden, sendA3(HttpMethod.Post, path, env.tok("sr1001"), """{"decision":"approve"}""").status)
        assertEquals(0, env.count("SELECT count(*) FROM app.audit_log WHERE entity = 'leave_application' AND entity_id = '$id'"))
        val d1 = sendA3(HttpMethod.Post, path, env.tok("dmo1001"), """{"decision":"approve","note":"Enjoy"}""")
        assertEquals(HttpStatusCode.OK, d1.status)
        val dj = d1.objA3()
        assertEquals("approved", dj.strA3("status")); assertEquals(env.ids.getValue("dmo1001").toString(), dj.getValue("decided_by_user_id").jsonPrimitive.content)
        val d2 = sendA3(HttpMethod.Post, path, env.tok("dmo1001"), """{"decision":"approve"}""")
        assertEquals(HttpStatusCode.OK, d2.status); assertEquals(dj.toString(), d2.objA3().toString())
        assertEquals(1, env.count("SELECT count(*) FROM app.audit_log WHERE entity = 'leave_application' AND entity_id = '$id' AND action = 'decide'"))
        // The opposite decision after the fact is a conflict; the decision reaches the TSO on the next list.
        assertEquals(HttpStatusCode.Conflict, sendA3(HttpMethod.Post, path, env.tok("admin1001"), """{"decision":"reject"}""").status)
        assertEquals("approved", sendA3(HttpMethod.Get, "/v1/leave", env.tok("tso1001")).objA3().itemsA3().first { it.strA3("leave_uuid") == id }.strA3("status"))
        assertEquals(HttpStatusCode.NotFound, sendA3(HttpMethod.Post, "/v1/leave/${uuid()}/decision", env.tok("dmo1001"), """{"decision":"approve"}""").status)
    }

    @Test
    fun validationAndRoles() = env.app {
        val tso = env.tok("tso1001")
        suspend fun post(b: String, t: String = tso) = sendA3(HttpMethod.Post, "/v1/leave", t, b)
        assertEquals(HttpStatusCode.BadRequest, post(apply("nope")).status)
        assertEquals(HttpStatusCode.BadRequest, post(apply(uuid(), days = 0)).status)
        assertEquals(HttpStatusCode.BadRequest, post(apply(uuid(), days = 366)).status)
        assertEquals(HttpStatusCode.BadRequest, post(apply(uuid(), from = "2027-02-30")).status)
        assertEquals(HttpStatusCode.BadRequest, post(apply(uuid(), type = "Bad Code")).status)
        assertEquals(HttpStatusCode.BadRequest, post(apply(uuid(), type = "unlisted_type")).status)
        assertEquals(HttpStatusCode.BadRequest, post(apply(uuid(), reason = "")).status)
        assertEquals(HttpStatusCode.BadRequest, post("""{"leave_uuid":"${uuid()}","leave_type_code":"casual","from_date":"2027-03-01","days":1,"reason":"x","user_id":9}""").status)
        for (u in listOf("sr1001", "amo1001", "dmo1001", "admin1001")) assertEquals(HttpStatusCode.Forbidden, post(apply(uuid()), env.tok(u)).status, u)
        for (u in listOf("sr1001", "amo1001", "support1001")) assertEquals(HttpStatusCode.Forbidden, sendA3(HttpMethod.Get, "/v1/leave", env.tok(u)).status, u)
        val id = uuid()
        post(apply(id, from = "2027-04-01"))
        assertEquals(HttpStatusCode.BadRequest, sendA3(HttpMethod.Post, "/v1/leave/$id/decision", env.tok("dmo1001"), """{"decision":"maybe"}""").status)
        assertEquals(HttpStatusCode.BadRequest, sendA3(HttpMethod.Post, "/v1/leave/$id/decision", env.tok("dmo1001"), """{"decision":"approve","note":"${"n".repeat(501)}"}""").status)
        assertEquals(HttpStatusCode.BadRequest, sendA3(HttpMethod.Post, "/v1/leave/not-a-uuid/decision", env.tok("dmo1001"), """{"decision":"approve"}""").status)
        assertEquals(HttpStatusCode.BadRequest, sendA3(HttpMethod.Get, "/v1/leave?status=done", tso).status)
        assertEquals(HttpStatusCode.BadRequest, sendA3(HttpMethod.Get, "/v1/leave?from=2027-01-01&to=2027-12-31", tso).status)
        // A reject is audited and final.
        assertEquals("rejected", sendA3(HttpMethod.Post, "/v1/leave/$id/decision", env.tok("dmo1001"), """{"decision":"reject"}""").objA3().strA3("status"))
        assertEquals(HttpStatusCode.Conflict, sendA3(HttpMethod.Post, "/v1/leave/$id/decision", env.tok("dmo1001"), """{"decision":"approve"}""").status)
    }

    @Test
    fun randomisedListsNeverLeakLeaveOutsideTheCallersReach() = env.app {
        val rnd = Random(22)
        val owners = listOf("tso1001", "tso2001", "tso3001")
        val dates = (1..8).map { "2027-06-%02d".format(it * 3) }
        val owner = mutableMapOf<String, String>()
        for (o in owners) for (d in dates.shuffled(rnd).take(5)) {
            val id = uuid(); assertEquals(HttpStatusCode.Created, sendA3(HttpMethod.Post, "/v1/leave", env.tok(o), apply(id, from = d, days = 1)).status); owner[id] = o
        }
        val sees = mapOf(
            "tso1001" to setOf("tso1001"), "tso2001" to setOf("tso2001"), "tso3001" to setOf("tso3001"), "dmo1001" to setOf("tso1001", "tso2001"), "dmo3001" to setOf("tso3001"),
            "admin1001" to owners.toSet(), "superadmin1001" to owners.toSet(),
        )
        repeat(100) {
            val caller = sees.keys.random(rnd); val from = dates.random(rnd); val to = dates.filter { it >= from }.random(rnd); val limit = rnd.nextInt(2, 9)
            val expected = owner.filter { (id, who) -> who in sees.getValue(caller) && env.scalar("SELECT from_date FROM app.leave_application WHERE client_uuid = '$id'")!! in from..to }.keys
            val got = mutableSetOf<String>(); var cursor: String? = null; var pages = 0
            do {
                val r = sendA3(HttpMethod.Get, "/v1/leave?from=$from&to=$to&limit=$limit&zone_ids=1,2&scope_ids=3" + (cursor?.let { "&cursor=$it" } ?: ""), env.tok(caller))
                assertEquals(HttpStatusCode.OK, r.status)
                val b = r.objA3(); assertTrue(b.itemsA3().size <= limit)
                b.itemsA3().forEach { assertTrue(got.add(it.strA3("leave_uuid"))) }
                cursor = b.getValue("next_cursor").let { if (it is JsonNull) null else it.jsonPrimitive.content }; pages++
            } while (cursor != null && pages < 100)
            val inWindow = got.filter { it in owner }.toSet()
            assertEquals(emptySet(), inWindow - expected, "$caller leaked")
            assertEquals(emptySet(), expected - inWindow, "$caller missing")
        }
    }
}
