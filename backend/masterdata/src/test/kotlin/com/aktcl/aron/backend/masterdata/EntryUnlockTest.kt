package com.aktcl.aron.backend.masterdata

import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpMethod
import kotlinx.datetime.toJavaLocalDate
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** F-API-049: entry-unlock grants are created and expired by the configured roles, audited with a reason, bounded in days and lifetime. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EntryUnlockTest {
    private lateinit var env: BackendAdminEnv

    @BeforeAll fun setUp() { env = BackendAdminEnv() }
    @AfterAll fun tearDown() = env.close()

    // wall-clock-ok: the app under test runs on AronClock.SYSTEM, so its Dhaka "today" is the real one
    private val today = com.aktcl.aron.rules.BusinessDate.of(System.currentTimeMillis()).toJavaLocalDate()

    private fun body(scope: String, id: Long, from: Any = today.minusDays(2), to: Any = today.minusDays(1), reason: String = "Paper memos arrived late", extra: String = "") =
        """{"scope_type":"$scope","scope_id":$id,"from":"$from","to":"$to","reason":"$reason"$extra}"""

    @Test
    fun grantsAreCreatedListedExpiredAndAudited() = env.app {
        val zone = env.scalar("SELECT id FROM app.zone WHERE code = 'Z-MIR'")!!.toLong()
        val route = env.routeIds.getValue("MIR-SR-D")
        val admin = env.tok("admin1001")
        val c = sendA3(HttpMethod.Post, "/v1/admin/entry-unlocks", admin, body("zone", zone))
        assertEquals(HttpStatusCode.Created, c.status, c.bodyAsText())
        val g = c.objA3(); val id = g.strA3("unlock_id")
        assertEquals("active", g.strA3("status")); assertEquals("zone", g.strA3("scope_type")); assertEquals(env.ids.getValue("admin1001").toString(), g.getValue("created_by_user_id").jsonPrimitive.content)
        assertEquals(1, env.count("SELECT count(*) FROM app.audit_log WHERE entity = 'entry_unlock' AND entity_id = '$id' AND action = 'create' AND reason = 'Paper memos arrived late'"))
        // A route grant, a shorter lifetime, and the list (active only, by zone).
        val r = sendA3(HttpMethod.Post, "/v1/admin/entry-unlocks", env.tok("support1001"), body("route", route, extra = ""","ttl_h":1""")).objA3()
        val listed = sendA3(HttpMethod.Get, "/v1/admin/entry-unlocks?active=true&zone_id=$zone", admin).objA3().itemsA3().map { it.strA3("unlock_id") }
        assertTrue(id in listed && r.strA3("unlock_id") in listed)
        // Expire now: no longer active, the grant stays listed as expired, a second expire is a no-op with one audit row.
        val e = sendA3(HttpMethod.Post, "/v1/admin/entry-unlocks/$id/expire", admin, "{}")
        assertEquals(HttpStatusCode.OK, e.status, e.bodyAsText()); assertEquals("expired", e.objA3().strA3("status"))
        assertEquals("expired", sendA3(HttpMethod.Post, "/v1/admin/entry-unlocks/$id/expire", admin, "{}").objA3().strA3("status"))
        assertEquals(1, env.count("SELECT count(*) FROM app.audit_log WHERE entity = 'entry_unlock' AND entity_id = '$id' AND action = 'expire'"))
        assertTrue(id !in sendA3(HttpMethod.Get, "/v1/admin/entry-unlocks?active=true", admin).objA3().itemsA3().map { it.strA3("unlock_id") })
        assertTrue(id in sendA3(HttpMethod.Get, "/v1/admin/entry-unlocks?active=false", admin).objA3().itemsA3().map { it.strA3("unlock_id") })
        assertEquals(HttpStatusCode.NotFound, sendA3(HttpMethod.Post, "/v1/admin/entry-unlocks/987654/expire", admin, "{}").status)
    }

    @Test
    fun boundsRolesAndScopeAreEnforced() = env.app {
        val zone = env.scalar("SELECT id FROM app.zone WHERE code = 'Z-MIR'")!!.toLong()
        val admin = env.tok("admin1001")
        suspend fun post(b: String, t: String = admin) = sendA3(HttpMethod.Post, "/v1/admin/entry-unlocks", t, b)
        assertEquals(HttpStatusCode.BadRequest, post(body("zone", zone, from = today.minusDays(20), to = today.minusDays(1))).status, "more than cfg.web.entry_unlock_max_days (7)")
        assertEquals(HttpStatusCode.BadRequest, post(body("zone", zone, from = today.plusDays(1), to = today.plusDays(2))).status, "no future dates")
        assertEquals(HttpStatusCode.BadRequest, post(body("zone", zone, from = today.minusDays(1), to = today.minusDays(2))).status)
        assertEquals(HttpStatusCode.BadRequest, post(body("zone", zone, reason = "short")).status)
        assertEquals(HttpStatusCode.BadRequest, post(body("zone", zone, extra = ""","ttl_h":169""")).status)
        assertEquals(HttpStatusCode.BadRequest, post(body("cluster", zone)).status)
        assertEquals(HttpStatusCode.Forbidden, post(body("zone", 987654)).status)
        for (u in listOf("tso1001", "dmo1001", "sr1001")) assertEquals(HttpStatusCode.Forbidden, post(body("zone", zone), env.tok(u)).status, u)
        assertEquals(HttpStatusCode.Forbidden, sendA3(HttpMethod.Get, "/v1/admin/entry-unlocks", env.tok("tso1001")).status)
        assertEquals(HttpStatusCode.OK, sendA3(HttpMethod.Get, "/v1/admin/entry-unlocks", env.tok("support1001")).status)
    }
}
