package com.aktcl.aron.backend.masterdata

import com.aktcl.aron.contract.Role
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** F-API-035b (outlets) and F-API-045 (bulk outlet kind, idempotent by batch_uuid). */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AdminOutletsTest {
    private lateinit var env: AdminEnv

    @BeforeAll fun setUp() { env = AdminEnv() }
    @AfterAll fun tearDown() = env.close()

    private val admin get() = env.token("admin1001", Role.ADMIN)
    private fun zone(code: String) = env.sql("SELECT id FROM app.zone WHERE code = '$code'")!!.toLong()
    private fun cluster() = env.sql("SELECT id FROM app.cluster WHERE zone_id = ${zone("Z-MIR")} ORDER BY id LIMIT 1")!!.toLong()
    private fun cluster2() = env.sql("SELECT id FROM app.cluster WHERE zone_id = ${zone("Z-MIR")} ORDER BY id DESC LIMIT 1")!!.toLong()
    private fun route() = env.sql("SELECT id FROM app.route WHERE code = 'MIR-SR-D'")!!.toLong()

    private fun body(name: String, extra: String = "") =
        """{"name":"$name","owner_name":"Owner of $name","zone_id":${zone("Z-MIR")},"cluster_id":${cluster()},"channel":"GT","outlet_kind":"retail"$extra}"""

    @Test
    fun createOutletWritesPlacementAndLocationHistoryAuditsAndMasksPii() = env.app {
        val res = send(HttpMethod.Post, "/admin/outlets", admin, body("Admin Store 1", ""","contact_number":"01712345678","route_id":${route()},"lat":23.8103,"lng":90.4125,"geo_class":"Urban","change_reason":"Added from the paper survey""""))
        assertEquals(HttpStatusCode.Created, res.status)
        val o = res.obj()
        val id = o.lng("id")
        assertEquals("AD-$id", o.str("code"))
        assertNull(o.str("contact_number"), "PII only with the pii claim")
        assertEquals("false", o.getValue("location_confirmed").toString())
        assertEquals("\"1\"", res.headers["ETag"])
        assertEquals(1, env.audit("outlet", id, "create"))
        assertEquals("Added from the paper survey", env.sql("SELECT reason FROM app.audit_log WHERE entity = 'outlet' AND entity_id = '$id'"))
        assertEquals("master", env.sql("SELECT location_basis FROM app.outlet WHERE id = $id"))
        assertEquals("1", env.sql("SELECT count(*) FROM app.outlet_placement_history WHERE outlet_id = $id AND valid_to IS NULL"))
        assertEquals("web_edit", env.sql("SELECT source FROM app.outlet_location_history WHERE outlet_id = $id"))
        val detail = send(HttpMethod.Get, "/admin/outlets/$id", env.token("admin1001", Role.ADMIN, pii = true))
        assertEquals("01712345678", detail.obj().str("contact_number"))
        assertEquals(1, detail.obj().arr("placement_history").size); assertEquals(1, detail.obj().arr("location_history").size)
        // An explicit code is kept; a duplicate code is 409 and writes nothing.
        assertEquals(HttpStatusCode.Created, send(HttpMethod.Post, "/admin/outlets", admin, body("Admin Store 2", ""","code":"ADM/2"""")).status)
        val audits = env.count("SELECT count(*) FROM app.audit_log")
        val dup = send(HttpMethod.Post, "/admin/outlets", admin, body("Admin Store 3", ""","code":"ADM/2""""))
        assertEquals("ERR_MASTER_DUPLICATE_CODE", dup.code()); assertEquals(audits, env.count("SELECT count(*) FROM app.audit_log"))
    }

    @Test
    fun outletValidation() = env.app {
        suspend fun post(b: String) = send(HttpMethod.Post, "/admin/outlets", admin, b)
        assertEquals(HttpStatusCode.BadRequest, post(body("x")).status) // name shorter than 2
        assertEquals(HttpStatusCode.BadRequest, post(body("Lat only", ""","lat":23.8""")).status)
        assertEquals(HttpStatusCode.BadRequest, post(body("Bad lat", ""","lat":123.8,"lng":90.4""")).status)
        assertEquals(HttpStatusCode.BadRequest, post(body("Bad kind").replace("retail", "mega")).status)
        assertEquals(HttpStatusCode.BadRequest, post(body("Bad phone", ""","contact_number":"123"""")).status)
        assertEquals(HttpStatusCode.BadRequest, post(body("Unknown member", ""","nid":"123456"""")).status)
        assertEquals(HttpStatusCode.BadRequest, post(body("Wrong zone").replace("\"zone_id\":${zone("Z-MIR")}", "\"zone_id\":${zone("Z-OTHER")}")).status)
        assertEquals(HttpStatusCode.BadRequest, post(body("Bad cluster").replace("\"cluster_id\":${cluster()}", "\"cluster_id\":99999")).status)
        assertEquals(HttpStatusCode.BadRequest, post(body("Bad sub", ""","sub_channel_id":${env.sql("SELECT id FROM app.sub_channel WHERE channel = 'Astha' LIMIT 1")}""")).status)
        assertEquals(HttpStatusCode.Created, post(body("Good sub", ""","sub_channel_id":${env.sql("SELECT id FROM app.sub_channel WHERE channel = 'GT' LIMIT 1")}""")).status)
        assertEquals(HttpStatusCode.BadRequest, post(body("Bad route", ""","route_id":99999""")).status)
    }

    @Test
    fun patchOutletVersionConflictPlacementHistoryCloseAndPinChange() = env.app {
        val o = send(HttpMethod.Post, "/admin/outlets", admin, body("Patch Store", ""","route_id":${route()}""")).obj()
        val id = o.lng("id")
        val p1 = send(HttpMethod.Patch, "/admin/outlets/$id", admin, """{"name":"Patch Store Renamed","visit_sequence":4,"change_reason":"Owner changed the signboard"}""", ifMatch = 1)
        assertEquals(HttpStatusCode.OK, p1.status); assertEquals(2, p1.obj().lng("version").toInt())
        val stale = send(HttpMethod.Patch, "/admin/outlets/$id", admin, """{"name":"Stale"}""", ifMatch = 1)
        assertEquals(HttpStatusCode.PreconditionFailed, stale.status); assertEquals("ERR_PRECONDITION_FAILED", stale.code())
        assertEquals("Patch Store Renamed", env.sql("SELECT name FROM app.outlet WHERE id = $id"))
        assertEquals(1, env.audit("outlet", id, "update"))
        // A no-op patch keeps the version and writes no audit row.
        val noop = send(HttpMethod.Patch, "/admin/outlets/$id", admin, """{"visit_sequence":4}""", ifMatch = 2)
        assertEquals(2, noop.obj().lng("version").toInt()); assertEquals(1, env.audit("outlet", id, "update"))
        // A cluster move keeps history: same-day correction rewrites the open row.
        val c2 = cluster2()
        val moved = send(HttpMethod.Patch, "/admin/outlets/$id", admin, """{"cluster_id":$c2,"route_id":null}""", ifMatch = 2)
        assertEquals(HttpStatusCode.OK, moved.status); assertEquals(c2, moved.obj().lng("cluster_id"))
        assertEquals("1", env.sql("SELECT count(*) FROM app.outlet_placement_history WHERE outlet_id = $id"))
        assertEquals(c2.toString(), env.sql("SELECT cluster_id FROM app.outlet_placement_history WHERE outlet_id = $id AND valid_to IS NULL"))
        // An older placement row is closed and a new one opened.
        env.db.fresh.db.jdbi.useHandle<Exception> { h -> h.execute("UPDATE app.outlet_placement_history SET valid_from = valid_from - 5 WHERE outlet_id = $id") }
        val v3 = env.sql("SELECT version FROM app.outlet WHERE id = $id")!!
        assertEquals(HttpStatusCode.OK, send(HttpMethod.Patch, "/admin/outlets/$id", admin, """{"route_id":${route()}}""", ifMatch = v3).status)
        assertEquals("2", env.sql("SELECT count(*) FROM app.outlet_placement_history WHERE outlet_id = $id"))
        assertEquals("1", env.sql("SELECT count(*) FROM app.outlet_placement_history WHERE outlet_id = $id AND valid_to IS NOT NULL"))
        // The pin: a new coordinate pair is appended to the location history; lat without lng is refused.
        val v4 = env.sql("SELECT version FROM app.outlet WHERE id = $id")!!
        assertEquals(HttpStatusCode.BadRequest, send(HttpMethod.Patch, "/admin/outlets/$id", admin, """{"lat":23.7}""", ifMatch = v4).status)
        val pin = send(HttpMethod.Patch, "/admin/outlets/$id", admin, """{"lat":23.7,"lng":90.3}""", ifMatch = v4)
        assertEquals(HttpStatusCode.OK, pin.status)
        assertEquals("1", env.sql("SELECT count(*) FROM app.outlet_location_history WHERE outlet_id = $id"))
        assertEquals("master", env.sql("SELECT location_basis FROM app.outlet WHERE id = $id"))
        // Closing is a status change (never a delete) and stamps closed_at; reopening clears it.
        val v5 = env.sql("SELECT version FROM app.outlet WHERE id = $id")!!
        val closed = send(HttpMethod.Patch, "/admin/outlets/$id", admin, """{"status":"closed","change_reason":"Shop shut permanently"}""", ifMatch = v5)
        assertEquals("closed", closed.obj().str("status")); assertNotNull(env.sql("SELECT closed_at FROM app.outlet WHERE id = $id"))
        assertEquals(HttpStatusCode.BadRequest, send(HttpMethod.Patch, "/admin/outlets/$id", admin, """{"status":"merged"}""", ifMatch = env.sql("SELECT version FROM app.outlet WHERE id = $id")!!).status)
        val reopened = send(HttpMethod.Patch, "/admin/outlets/$id", admin, """{"status":"active"}""", ifMatch = env.sql("SELECT version FROM app.outlet WHERE id = $id")!!)
        assertEquals("active", reopened.obj().str("status")); assertNull(env.sql("SELECT closed_at FROM app.outlet WHERE id = $id"))
        assertEquals("1", env.sql("SELECT count(*) FROM app.outlet WHERE id = $id"))
        assertEquals(HttpStatusCode.NotFound, send(HttpMethod.Patch, "/admin/outlets/99999", admin, """{"name":"Nope"}""", ifMatch = 1).status)
    }

    @Test
    fun outletScopeAndRoles() = env.app {
        val o = send(HttpMethod.Post, "/admin/outlets", admin, body("Scope Store")).obj().lng("id")
        val tso1 = env.token("tso1001", Role.TSO); val tso2 = env.token("tso2001", Role.TSO)
        assertEquals(HttpStatusCode.OK, send(HttpMethod.Get, "/admin/outlets/$o", tso1).status)
        assertEquals(HttpStatusCode.NotFound, send(HttpMethod.Get, "/admin/outlets/$o", tso2).status)
        // No TSO writes; ANALYST and SUPPORT neither; a field role has no access.
        for ((u, r) in listOf("tso1001" to Role.TSO, "tso2001" to Role.TSO, "support1001" to Role.SUPPORT, "dmo1001" to Role.DMO)) {
            assertEquals(HttpStatusCode.Forbidden, send(HttpMethod.Post, "/admin/outlets", env.token(u, r), body("Denied $u")).status, "$r create")
            assertEquals(HttpStatusCode.Forbidden, send(HttpMethod.Patch, "/admin/outlets/$o", env.token(u, r), """{"name":"Denied"}""", ifMatch = 1).status, "$r patch")
        }
        assertEquals(HttpStatusCode.Forbidden, send(HttpMethod.Get, "/admin/outlets/$o", env.token("sr1001", Role.SR)).status)
        assertEquals("Scope Store", env.sql("SELECT name FROM app.outlet WHERE id = $o"))
    }

    // ---- F-API-045 ----

    private fun outletIds(n: Int) = env.sql("SELECT string_agg(id::text, ',') FROM (SELECT id FROM app.outlet WHERE outlet_kind = 'retail' ORDER BY id LIMIT $n) x")!!

    @Test
    fun bulkOutletKindIsIdempotentByBatchUuidWithOneAuditRowPerOutlet() = env.app {
        val ids = outletIds(3)
        val batch = UUID.randomUUID()
        val req = """{"batch_uuid":"$batch","outlet_kind":"wholesale","outlet_ids":[$ids],"reason":"Wholesale list from the DMO"}"""
        val versions = env.sql("SELECT string_agg(version::text, ',' ORDER BY id) FROM app.outlet WHERE id IN ($ids)")
        val r1 = send(HttpMethod.Post, "/admin/outlets/outlet-kind", admin, req)
        assertEquals(HttpStatusCode.OK, r1.status)
        assertEquals(3, r1.obj().lng("updated")); assertEquals(0, r1.obj().lng("unchanged")); assertEquals("false", r1.obj().getValue("replayed").toString())
        assertEquals("3", env.sql("SELECT count(*) FROM app.outlet WHERE id IN ($ids) AND outlet_kind = 'wholesale'"))
        assertEquals("3", env.sql("SELECT count(*) FROM app.audit_log WHERE entity = 'outlet' AND action = 'set_outlet_kind' AND after::text LIKE '%$batch%'"))
        assertEquals(1, env.audit("outlet_kind_batch", batch, "apply"))
        val auditTotal = env.count("SELECT count(*) FROM app.audit_log")
        val bumped = env.sql("SELECT string_agg(version::text, ',' ORDER BY id) FROM app.outlet WHERE id IN ($ids)")
        // The same request replayed changes nothing and answers the stored result.
        val r2 = send(HttpMethod.Post, "/admin/outlets/outlet-kind", admin, req)
        assertEquals(HttpStatusCode.OK, r2.status)
        assertEquals(r1.obj().lng("updated"), r2.obj().lng("updated")); assertEquals("true", r2.obj().getValue("replayed").toString())
        assertEquals(r1.obj().str("batch_uuid"), r2.obj().str("batch_uuid"))
        assertEquals(auditTotal, env.count("SELECT count(*) FROM app.audit_log"))
        assertEquals(bumped, env.sql("SELECT string_agg(version::text, ',' ORDER BY id) FROM app.outlet WHERE id IN ($ids)"))
        assertTrue(versions != bumped)
        // The same uuid with another outlet set or kind is refused, nothing changes.
        val other = send(HttpMethod.Post, "/admin/outlets/outlet-kind", admin, """{"batch_uuid":"$batch","outlet_kind":"retail","outlet_ids":[$ids],"reason":"Different kind, same batch"}""")
        assertEquals(HttpStatusCode.Conflict, other.status); assertEquals("ERR_SYNC_BATCH_UUID_REUSED", other.code())
        assertEquals("3", env.sql("SELECT count(*) FROM app.outlet WHERE id IN ($ids) AND outlet_kind = 'wholesale'"))
        // A new batch over a mixed set counts updated and unchanged (and still audits every outlet).
        val mixed = "$ids,${outletIds(5).split(",").last()}"
        val b2 = UUID.randomUUID()
        val r3 = send(HttpMethod.Post, "/admin/outlets/outlet-kind", admin, """{"batch_uuid":"$b2","outlet_kind":"wholesale","outlet_ids":[$mixed],"reason":"Add one more outlet"}""")
        assertEquals(1, r3.obj().lng("updated")); assertEquals(3, r3.obj().lng("unchanged"))
        assertEquals("4", env.sql("SELECT count(*) FROM app.audit_log WHERE entity = 'outlet' AND action = 'set_outlet_kind' AND after::text LIKE '%$b2%'"))
    }

    @Test
    fun bulkOutletKindValidationRolesAndScope() = env.app {
        val ids = outletIds(2)
        suspend fun post(b: String, t: String = admin) = send(HttpMethod.Post, "/admin/outlets/outlet-kind", t, b)
        val ok = """{"batch_uuid":"${UUID.randomUUID()}","outlet_kind":"wholesale","outlet_ids":[$ids],"reason":"Mark the wholesalers"}"""
        assertEquals(HttpStatusCode.BadRequest, post("""{"batch_uuid":"nope","outlet_kind":"wholesale","outlet_ids":[$ids],"reason":"Bad uuid in the batch"}""").status)
        assertEquals(HttpStatusCode.BadRequest, post("""{"batch_uuid":"${UUID.randomUUID()}","outlet_kind":"wholesale","outlet_ids":[],"reason":"Empty id list given"}""").status)
        assertEquals(HttpStatusCode.BadRequest, post("""{"batch_uuid":"${UUID.randomUUID()}","outlet_kind":"mega","outlet_ids":[$ids],"reason":"Unknown outlet kind"}""").status)
        assertEquals(HttpStatusCode.BadRequest, post("""{"batch_uuid":"${UUID.randomUUID()}","outlet_kind":"wholesale","outlet_ids":[$ids]}""").status)
        assertEquals(HttpStatusCode.BadRequest, post("""{"batch_uuid":"${UUID.randomUUID()}","outlet_kind":"wholesale","outlet_ids":[$ids,99999999],"reason":"One id does not exist"}""").status)
        assertEquals("0", env.sql("SELECT count(*) FROM app.outlet WHERE id IN ($ids) AND outlet_kind = 'wholesale'"), "an invalid batch changes nothing")
        assertEquals(HttpStatusCode.Forbidden, post(ok, env.token("tso1001", Role.TSO)).status)
        assertEquals(HttpStatusCode.Forbidden, post(ok, env.token("support1001", Role.SUPPORT)).status)
        assertEquals(HttpStatusCode.Forbidden, post(ok, env.token("sr1001", Role.SR)).status)
        assertEquals(HttpStatusCode.OK, post(ok).status)
    }
}
