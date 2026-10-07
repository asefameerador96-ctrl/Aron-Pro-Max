package com.aktcl.aron.backend.masterdata

import com.aktcl.aron.contract.Role
import io.ktor.client.request.get
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** F-API-035: the generic /v1/admin framework on geography, clusters, the calendar and the code lists. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AdminMasterTest {
    private lateinit var env: AdminEnv

    @BeforeAll fun setUp() { env = AdminEnv() }
    @AfterAll fun tearDown() = env.close()

    private val admin get() = env.token("admin1001", Role.ADMIN)
    private fun id(table: String, code: String) = env.sql("SELECT id FROM app.$table WHERE code = '$code'")!!.toLong()

    private val reason = "Pilot geography set-up for the test"

    @Test
    fun geographyChainCreateReadPatchWithVersionConflictAndAuditPerWrite() = env.app {
        val w = sendA1(HttpMethod.Post, "/admin/geo/wing", admin, """{"code":"W-T1","name":"Test Wing","name_bn":"টেস্ট","change_reason":"$reason"}""")
        assertEquals(HttpStatusCode.Created, w.status)
        assertEquals("\"1\"", w.headers["ETag"])
        val wing = w.objA1()
        assertEquals("wing", wing.strA1("level")); assertEquals(1, wing.lng("version").toInt()); assertNull(wing.strA1("parent_id"))
        val wid = wing.lng("id")
        assertEquals(1, env.audit("geo_wing", wid, "create"))
        assertEquals(reason, env.sql("SELECT reason FROM app.audit_log WHERE entity = 'geo_wing' AND entity_id = '$wid'"))

        val d = sendA1(HttpMethod.Post, "/admin/geo/division", admin, """{"code":"D-T1","name":"Test Division","parent_id":$wid}""")
        assertEquals(HttpStatusCode.Created, d.status)
        val did = d.objA1().lng("id")
        val t = sendA1(HttpMethod.Post, "/admin/geo/territory", admin, """{"code":"T-T1","name":"Test Territory","parent_id":$did}""")
        val tid = t.objA1().lng("id")
        val z = sendA1(HttpMethod.Post, "/admin/geo/zone", admin, """{"code":"Z-T1","name":"Test Zone","parent_id":$tid,"dep_name":"T DEP","email":"zone@aktcl.example","pda_contact_no":"+8801711000000"}""")
        assertEquals(HttpStatusCode.Created, z.status)
        assertEquals("T DEP", z.objA1().strA1("dep_name"))
        val h = sendA1(HttpMethod.Post, "/admin/geo/house", admin, """{"code":"H-T1","name":"Test House","parent_id":$tid}""")
        assertEquals(HttpStatusCode.Created, h.status)

        val get = sendA1(HttpMethod.Get, "/admin/geo/wing/$wid", admin)
        assertEquals("\"1\"", get.headers["ETag"])
        // PATCH with the right version bumps it; the same stale If-Match then fails with 412 ERR_PRECONDITION_FAILED.
        val p1 = sendA1(HttpMethod.Patch, "/admin/geo/wing/$wid", admin, """{"name":"Renamed Wing","change_reason":"Rename after the review"}""", ifMatch = 1)
        assertEquals(HttpStatusCode.OK, p1.status)
        assertEquals(2, p1.objA1().lng("version").toInt()); assertEquals("\"2\"", p1.headers["ETag"])
        val stale = sendA1(HttpMethod.Patch, "/admin/geo/wing/$wid", admin, """{"name":"Other name"}""", ifMatch = 1)
        assertEquals(HttpStatusCode.PreconditionFailed, stale.status)
        assertEquals("ERR_PRECONDITION_FAILED", stale.code())
        assertEquals("Renamed Wing", env.sql("SELECT name FROM app.wing WHERE id = $wid"))
        // A missing If-Match is a 400, never a blind overwrite.
        assertEquals(HttpStatusCode.BadRequest, sendA1(HttpMethod.Patch, "/admin/geo/wing/$wid", admin, """{"name":"x"}""").status)
        // A patch that changes nothing is a no-op: same version, no new audit row (replay-safe).
        val before = env.audit("geo_wing", wid)
        val noop = sendA1(HttpMethod.Patch, "/admin/geo/wing/$wid", admin, """{"name":"Renamed Wing"}""", ifMatch = 2)
        assertEquals(HttpStatusCode.OK, noop.status); assertEquals(2, noop.objA1().lng("version").toInt())
        assertEquals(before, env.audit("geo_wing", wid))
        assertEquals(1, env.audit("geo_wing", wid, "update"))
        val after = env.sql("SELECT after::text FROM app.audit_log WHERE entity = 'geo_wing' AND action = 'update' AND entity_id = '$wid'")!!
        assertTrue(after.contains("Renamed Wing"))
    }

    @Test
    fun duplicateCodeIs409AndWritesNoAuditRow() = env.app {
        val first = sendA1(HttpMethod.Post, "/admin/geo/wing", admin, """{"code":"W-DUP","name":"Dup"}""")
        assertEquals(HttpStatusCode.Created, first.status)
        val audits = env.count("SELECT count(*) FROM app.audit_log")
        val dup = sendA1(HttpMethod.Post, "/admin/geo/wing", admin, """{"code":"W-DUP","name":"Dup again"}""")
        assertEquals(HttpStatusCode.Conflict, dup.status)
        assertEquals("ERR_MASTER_DUPLICATE_CODE", dup.code())
        assertEquals(audits, env.count("SELECT count(*) FROM app.audit_log"))
        assertEquals("1", env.sql("SELECT count(*) FROM app.wing WHERE code = 'W-DUP'"))
    }

    @Test
    fun softStatusInsteadOfDeleteAndInUseGuard() = env.app {
        val wing = sendA1(HttpMethod.Post, "/admin/geo/wing", admin, """{"code":"W-USE","name":"Use"}""").objA1()
        val div = sendA1(HttpMethod.Post, "/admin/geo/division", admin, """{"code":"D-USE","name":"Use div","parent_id":${wing.lng("id")}}""").objA1()
        val busy = sendA1(HttpMethod.Patch, "/admin/geo/wing/${wing.lng("id")}", admin, """{"status":"inactive"}""", ifMatch = 1)
        assertEquals(HttpStatusCode.Conflict, busy.status)
        assertEquals("ERR_MASTER_IN_USE", busy.code())
        assertEquals("active", env.sql("SELECT status FROM app.wing WHERE id = ${wing.lng("id")}"))
        // Leaf first, then the parent: nothing is ever deleted, the rows stay with status inactive.
        assertEquals(HttpStatusCode.OK, sendA1(HttpMethod.Patch, "/admin/geo/division/${div.lng("id")}", admin, """{"status":"inactive"}""", ifMatch = 1).status)
        assertEquals(HttpStatusCode.OK, sendA1(HttpMethod.Patch, "/admin/geo/wing/${wing.lng("id")}", admin, """{"status":"inactive"}""", ifMatch = 1).status)
        val inactive = sendA1(HttpMethod.Get, "/admin/geo/wing?status=inactive", admin).objA1().itemsA1().map { it.strA1("code") }
        assertTrue("W-USE" in inactive)
        // A child cannot be created under an inactive parent.
        val under = sendA1(HttpMethod.Post, "/admin/geo/division", admin, """{"code":"D-USE2","name":"x","parent_id":${wing.lng("id")}}""")
        assertEquals(HttpStatusCode.BadRequest, under.status)
        // A used zone cannot be deactivated while it has outlets and clusters.
        val zid = id("zone", "Z-MIR")
        val zv = env.sql("SELECT version FROM app.zone WHERE id = $zid")!!
        assertEquals("ERR_MASTER_IN_USE", sendA1(HttpMethod.Patch, "/admin/geo/zone/$zid", admin, """{"status":"inactive"}""", ifMatch = zv).code())
    }

    @Test
    fun validationIs400WithPointers() = env.app {
        suspend fun post(body: String) = sendA1(HttpMethod.Post, "/admin/geo/wing", admin, body)
        assertEquals(HttpStatusCode.BadRequest, post("""{"code":"bad code","name":"x"}""").status)
        assertEquals(HttpStatusCode.BadRequest, post("""{"name":"no code"}""").status)
        assertEquals(HttpStatusCode.BadRequest, post("""{"code":"W-X","name":"x","surprise":1}""").status)
        assertEquals(HttpStatusCode.BadRequest, post("""{"code":"W-X","name":"x","parent_id":3}""").status)
        assertEquals(HttpStatusCode.BadRequest, post("""{"code":"W-X","name":"x","email":"nope"}""").status)
        assertEquals(HttpStatusCode.BadRequest, post("""{"code":"W-X","name":"x","change_reason":"short"}""").status)
        assertEquals(HttpStatusCode.BadRequest, sendA1(HttpMethod.Post, "/admin/geo/division", admin, """{"code":"D-X","name":"x","parent_id":99999}""").status)
        assertEquals(HttpStatusCode.BadRequest, sendA1(HttpMethod.Post, "/admin/geo/division", admin, """{"code":"D-X","name":"x"}""").status)
        assertEquals(HttpStatusCode.BadRequest, sendA1(HttpMethod.Get, "/admin/geo/planet", admin).status)
        assertEquals(HttpStatusCode.BadRequest, sendA1(HttpMethod.Get, "/admin/geo/wing?cursor=@@", admin).status)
        assertEquals(HttpStatusCode.BadRequest, sendA1(HttpMethod.Patch, "/admin/geo/wing/1", admin, """{}""", ifMatch = 1).status)
        val problem = post("""{"code":"bad code","name":"x"}""").objA1()
        assertEquals("ERR_VALIDATION", problem.strA1("code"))
        assertEquals("body.code", problem.arr("errors")[0].let { (it as kotlinx.serialization.json.JsonObject).strA1("pointer") })
        assertEquals(HttpStatusCode.NotFound, sendA1(HttpMethod.Get, "/admin/geo/wing/99999", admin).status)
    }

    @Test
    fun requireReasonSwitchMakesTheReasonMandatory() = env.app(requireReason = true) {
        assertEquals(HttpStatusCode.BadRequest, sendA1(HttpMethod.Post, "/admin/geo/wing", admin, """{"code":"W-RR","name":"x"}""").status)
        assertEquals(HttpStatusCode.Created, sendA1(HttpMethod.Post, "/admin/geo/wing", admin, """{"code":"W-RR","name":"x","change_reason":"$reason"}""").status)
    }

    @Test
    fun readersSeeOnlyTheirReachAndOnlyAdminsWrite() = env.app {
        val tso1 = env.token("tso1001", Role.TSO)
        val tso2 = env.token("tso2001", Role.TSO)
        val zones1 = sendA1(HttpMethod.Get, "/admin/geo/zone", tso1).objA1().itemsA1().map { it.strA1("code") }
        assertTrue("Z-MIR" in zones1 && "Z-OTHER" !in zones1, "tso1001 sees $zones1")
        val zones2 = sendA1(HttpMethod.Get, "/admin/geo/zone", tso2).objA1().itemsA1().map { it.strA1("code") }
        assertEquals(listOf("Z-OTHER"), zones2)
        val terr2 = sendA1(HttpMethod.Get, "/admin/geo/territory", tso2).objA1().itemsA1().map { it.strA1("code") }
        assertEquals(listOf("T-OTHER"), terr2)
        // Another territory's node reads as not found (no existence leak); a selector outside the reach is 403.
        assertEquals(HttpStatusCode.NotFound, sendA1(HttpMethod.Get, "/admin/geo/zone/${id("zone", "Z-MIR")}", tso2).status)
        assertEquals(HttpStatusCode.Forbidden, sendA1(HttpMethod.Get, "/admin/clusters?zone_id=${id("zone", "Z-MIR")}", tso2).status)
        assertEquals("ERR_OUT_OF_SCOPE", sendA1(HttpMethod.Get, "/admin/clusters?zone_id=${id("zone", "Z-MIR")}", tso2).code())
        assertEquals(emptyList(), sendA1(HttpMethod.Get, "/admin/clusters", tso2).objA1().itemsA1())
        // Writes: a TSO, SUPPORT and ANALYST are read-only; a field role has no access at all.
        val body = """{"code":"W-NOPE","name":"x"}"""
        for ((u, r) in listOf("tso1001" to Role.TSO, "support1001" to Role.SUPPORT, "dmo1001" to Role.DMO, "admin1001" to Role.ANALYST)) {
            val res = sendA1(HttpMethod.Post, "/admin/geo/wing", env.token(u, r), body)
            assertEquals(HttpStatusCode.Forbidden, res.status, "$r")
            assertEquals("ERR_FORBIDDEN", res.code())
        }
        assertEquals(HttpStatusCode.Forbidden, sendA1(HttpMethod.Get, "/admin/geo/wing", env.token("sr1001", Role.SR)).status)
        assertEquals(HttpStatusCode.Forbidden, sendA1(HttpMethod.Patch, "/admin/geo/zone/${id("zone", "Z-MIR")}", tso1, """{"name":"Hijack"}""", ifMatch = 1).status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/v1/admin/geo/wing").status)
        assertEquals("0", env.sql("SELECT count(*) FROM app.wing WHERE code = 'W-NOPE'"))
    }

    @Test
    fun clustersKeysetPagingDuplicateNameAndMove() = env.app {
        val zid = id("zone", "Z-MIR")
        val names = listOf("Alpha", "Bravo", "Charlie")
        names.forEach { n -> assertEquals(HttpStatusCode.Created, sendA1(HttpMethod.Post, "/admin/clusters", admin, """{"zone_id":$zid,"name":"PG $n","cluster_type":"market"}""").status) }
        val dup = sendA1(HttpMethod.Post, "/admin/clusters", admin, """{"zone_id":$zid,"name":"PG Alpha"}""")
        assertEquals("ERR_MASTER_DUPLICATE_CODE", dup.code())
        val p1 = sendA1(HttpMethod.Get, "/admin/clusters?zone_id=$zid&q=PG&limit=2", admin).objA1()
        assertEquals(2, p1.itemsA1().size)
        val next = p1.strA1("next_cursor")!!
        val p2 = sendA1(HttpMethod.Get, "/admin/clusters?zone_id=$zid&q=PG&limit=2&cursor=$next", admin).objA1()
        assertEquals(1, p2.itemsA1().size); assertNull(p2.strA1("next_cursor"))
        val all = (p1.itemsA1() + p2.itemsA1()).map { it.lng("id") }
        assertEquals(3, all.toSet().size)
        // updated_since keyset: a row updated after the instant comes back, with its status.
        val c = p1.itemsA1()[0]
        val patched = sendA1(HttpMethod.Patch, "/admin/clusters/${c.lng("id")}", admin, """{"cluster_type":"mall","change_reason":"Reclassified by survey"}""", ifMatch = c.lng("version"))
        assertEquals("mall", patched.objA1().strA1("cluster_type"))
        val since = sendA1(HttpMethod.Get, "/admin/clusters?zone_id=$zid&updated_since=${patched.objA1().strA1("updated_at")!!.let { java.time.Instant.parse(it).minusMillis(1) }}&limit=1", admin).objA1()
        assertEquals(1, since.itemsA1().size)
        // A cluster with active outlets cannot be deactivated or moved to another zone.
        val busy = env.sql("SELECT cluster_id FROM app.outlet WHERE status = 'active' LIMIT 1")!!
        val v = env.sql("SELECT version FROM app.cluster WHERE id = $busy")!!
        assertEquals("ERR_MASTER_IN_USE", sendA1(HttpMethod.Patch, "/admin/clusters/$busy", admin, """{"status":"inactive"}""", ifMatch = v).code())
        val other = id("zone", "Z-OTHER")
        assertEquals("ERR_MASTER_IN_USE", sendA1(HttpMethod.Patch, "/admin/clusters/$busy", admin, """{"zone_id":$other}""", ifMatch = v).code())
        assertNotEquals(0, env.audit("cluster", c.lng("id")))
    }

    @Test
    fun holidaysDeclareListOverlapAndPastDate() = env.app {
        val zid = id("zone", "Z-MIR")
        val day = env.today.plusDays(30)
        val body = """{"date":"$day","scope_type":"zone","scope_id":$zid,"kind":"holiday","name_en":"Local fair","reason":"Declared by the zone office"}"""
        val ok = sendA1(HttpMethod.Post, "/admin/calendar/holidays", admin, body)
        assertEquals(HttpStatusCode.Created, ok.status)
        assertEquals(false, ok.objA1().getValue("selling_day").jsonPrimitive.content.toBoolean())
        assertEquals(1, env.audit("calendar_holiday", ok.objA1().lng("id"), "create"))
        // The same date and scope again overlaps.
        val dup = sendA1(HttpMethod.Post, "/admin/calendar/holidays", admin, body)
        assertEquals(HttpStatusCode.Conflict, dup.status); assertEquals("ERR_MASTER_OVERLAP", dup.code())
        // A make-up day is a selling day; a holiday in the past is refused, an emergency off-day is effective today.
        val mk = sendA1(HttpMethod.Post, "/admin/calendar/holidays", admin, """{"date":"${day.plusDays(1)}","scope_type":"global","scope_id":0,"kind":"makeup_day","name_en":"Make-up","reason":"Make up the fair day"}""")
        assertEquals(true, mk.objA1().getValue("selling_day").jsonPrimitive.content.toBoolean())
        val past = sendA1(HttpMethod.Post, "/admin/calendar/holidays", admin, """{"date":"${env.today.minusDays(2)}","scope_type":"global","scope_id":0,"kind":"holiday","name_en":"Past","reason":"Backdated holiday attempt"}""")
        assertEquals("ERR_MASTER_EFFECTIVE_DATE_PAST", past.code())
        val emergency = sendA1(HttpMethod.Post, "/admin/calendar/holidays", admin, """{"date":"${env.today}","scope_type":"zone","scope_id":$zid,"kind":"emergency_off","name_en":"Flood","reason":"Flooding in the zone today"}""")
        assertEquals(HttpStatusCode.Created, emergency.status)
        assertEquals(HttpStatusCode.BadRequest, sendA1(HttpMethod.Post, "/admin/calendar/holidays", admin, """{"date":"$day","scope_type":"global","scope_id":$zid,"kind":"holiday","name_en":"Bad","reason":"Global with a node id"}""").status)
        assertEquals(HttpStatusCode.BadRequest, sendA1(HttpMethod.Post, "/admin/calendar/holidays", admin, """{"date":"$day","scope_type":"zone","scope_id":$zid,"kind":"holiday","name_en":"No reason"}""").status)
        // Reads are reach-filtered: the other territory's TSO sees global entries only.
        val range = "from=${env.today}&to=${day.plusDays(5)}"
        val mine = sendA1(HttpMethod.Get, "/admin/calendar/holidays?$range", env.token("tso1001", Role.TSO)).objA1().itemsA1().map { it.strA1("kind") }
        assertTrue("holiday" in mine && "emergency_off" in mine && "makeup_day" in mine)
        val theirs = sendA1(HttpMethod.Get, "/admin/calendar/holidays?$range", env.token("tso2001", Role.TSO)).objA1().itemsA1().map { it.strA1("scope_type") }
        assertEquals(listOf("global"), theirs)
        assertEquals(HttpStatusCode.Forbidden, sendA1(HttpMethod.Post, "/admin/calendar/holidays", env.token("tso1001", Role.TSO), body).status)
    }

    @Test
    fun codeListsAddEditReplayIsIdempotentAndBumpsTheContentVersionOnce() = env.app {
        val list = sendA1(HttpMethod.Get, "/admin/code-lists", admin).objA1().arr("lists")
        assertEquals(14, list.size)
        val put = """{"items":[{"code":"fault_seal","label_en":"Broken seal","label_bn":"সিল ভাঙা","sort":10,"attrs":{"group":"MFC"}}],"change_reason":"Add the seal fault type"}"""
        val versions0 = env.count("SELECT count(*) FROM app.cfg_version WHERE kind = 'content'")
        val r1 = sendA1(HttpMethod.Put, "/admin/code-lists/qc_fault_type", admin, put)
        assertEquals(HttpStatusCode.OK, r1.status)
        val item = r1.objA1().arr("items").map { it as kotlinx.serialization.json.JsonObject }.first { it.strA1("code") == "fault_seal" }
        assertEquals("MFC", (item.getValue("attrs") as kotlinx.serialization.json.JsonObject).strA1("group"))
        assertEquals(versions0 + 1, env.count("SELECT count(*) FROM app.cfg_version WHERE kind = 'content'"))
        assertEquals(1, env.audit("code_list", "qc_fault_type", "put"))
        // The same request replayed changes nothing: no new audit row, no new config version.
        val r2 = sendA1(HttpMethod.Put, "/admin/code-lists/qc_fault_type", admin, put)
        assertEquals(HttpStatusCode.OK, r2.status); assertEquals(r1.objA1(), r2.objA1())
        assertEquals(1, env.audit("code_list", "qc_fault_type", "put"))
        assertEquals(versions0 + 1, env.count("SELECT count(*) FROM app.cfg_version WHERE kind = 'content'"))
        // Labels are editable; the code and valid_from are not; retire with valid_to.
        val edit = sendA1(HttpMethod.Put, "/admin/code-lists/qc_fault_type", admin, """{"items":[{"code":"fault_seal","label_en":"Seal broken","sort":11,"valid_to":"${env.today.plusDays(10)}"}],"change_reason":"Rename and retire later"}""")
        assertEquals(HttpStatusCode.OK, edit.status)
        assertEquals("Seal broken", env.sql("SELECT label_en FROM app.code_list_item WHERE code = 'fault_seal'"))
        assertEquals("সিল ভাঙা", env.sql("SELECT label_bn FROM app.code_list_item WHERE code = 'fault_seal'"))
        assertEquals(HttpStatusCode.BadRequest, sendA1(HttpMethod.Put, "/admin/code-lists/qc_fault_type", admin, """{"items":[{"code":"fault_seal","label_en":"x","sort":1,"valid_from":"2020-01-01"}],"change_reason":"Try to move the date"}""").status)
        val seeded = env.sql("SELECT list_key || '/' || code FROM app.code_list_item WHERE valid_from < current_date - 5 AND valid_to IS NULL ORDER BY id LIMIT 1")!!.split("/")
        assertEquals("ERR_MASTER_EFFECTIVE_DATE_PAST", sendA1(HttpMethod.Put, "/admin/code-lists/${seeded[0]}", admin, """{"items":[{"code":"${seeded[1]}","label_en":"x","sort":1,"valid_to":"${env.today.minusDays(1)}"}],"change_reason":"Retire in the past"}""").code())
        assertEquals(HttpStatusCode.BadRequest, sendA1(HttpMethod.Put, "/admin/code-lists/channel", admin, put).status)
        assertEquals(HttpStatusCode.BadRequest, sendA1(HttpMethod.Put, "/admin/code-lists/qc_fault_type", admin, """{"items":[{"code":"Bad-Code","label_en":"x","sort":1}],"change_reason":"Bad code pattern"}""").status)
        assertEquals(HttpStatusCode.BadRequest, sendA1(HttpMethod.Put, "/admin/code-lists/qc_fault_type", admin, """{"items":[{"code":"fault_x","label_en":"x","sort":1}]}""").status)
        assertEquals(HttpStatusCode.Forbidden, sendA1(HttpMethod.Put, "/admin/code-lists/qc_fault_type", env.token("tso1001", Role.TSO), put).status)
        assertEquals(HttpStatusCode.OK, sendA1(HttpMethod.Get, "/admin/code-lists", env.token("tso1001", Role.TSO)).status)
    }
}
