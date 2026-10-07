package com.aktcl.aron.backend.masterdata

import com.aktcl.aron.contract.Role
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertTrue

/** Independent checker (T2) probes for F-API-035 / 035b / 045. Failures here are candidate defects. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CheckerAdminTest {
    private lateinit var env: AdminEnv
    @BeforeAll fun setUp() { env = AdminEnv() }
    @AfterAll fun tearDown() = env.close()

    private val admin get() = env.token("admin1001", Role.ADMIN)
    private val sup get() = env.token("superadmin1001", Role.SUPERADMIN)
    private fun ckZone(c: String) = env.sql("SELECT id FROM app.zone WHERE code = '$c'")!!.toLong()
    private fun ckVer(table: String, id: Any) = env.sql("SELECT version FROM $table WHERE id = $id")!!

    private class CkSoft {
        val f = mutableListOf<String>()
        fun chk(ok: Boolean, m: String) { if (!ok) f += m }
        fun done() = assertTrue(f.isEmpty(), "\n" + f.joinToString("\n"))
    }

    @Test
    fun ckOddInputNever5xx() = env.app {
        val s = CkSoft(); val z = ckZone("Z-MIR")
        suspend fun try5(label: String, method: HttpMethod, path: String, body: String?, tok: String = admin, im: Any? = 1) {
            val r = sendA1(method, path, tok, body, ifMatch = im)
            s.chk(r.status.value < 500, "$label -> ${r.status.value} ${r.bodyAsText().take(120)}")
        }
        try5("nul in cluster name", HttpMethod.Post, "/admin/clusters", """{"zone_id":$z,"name":"a\u0000b"}""")
        try5("lone surrogate", HttpMethod.Post, "/admin/clusters", """{"zone_id":$z,"name":"a\ud800b"}""")
        try5("huge zone id", HttpMethod.Post, "/admin/clusters", """{"zone_id":9223372036854775807,"name":"Huge"}""")
        try5("array body", HttpMethod.Post, "/admin/clusters", """[1,2]""")
        try5("string body", HttpMethod.Post, "/admin/clusters", "\"x\"")
        try5("null body", HttpMethod.Post, "/admin/clusters", "null")
        try5("bad json", HttpMethod.Post, "/admin/clusters", "{")
        try5("scope far date", HttpMethod.Put, "/admin/users/${env.db.ids.getValue("tso1001")}/scope", """{"valid_from":"+6000000-01-01","nodes":[],"change_reason":"far future date test"}""")
        try5("holiday far date", HttpMethod.Post, "/admin/calendar/holidays", """{"date":"+6000000-01-01","scope_type":"global","scope_id":0,"kind":"holiday","name_en":"Far","reason":"far future date test"}""")
        try5("holidays list overflow", HttpMethod.Get, "/admin/calendar/holidays?from=%2B999999999-12-31", null)
        try5("holidays list neg", HttpMethod.Get, "/admin/calendar/holidays?from=-999999999-01-01&to=-999999999-02-01", null)
        try5("assignment far date", HttpMethod.Post, "/admin/route-assignments", """{"route_id":1,"user_id":${env.db.ids.getValue("sr1001")},"kind":"primary","valid_from":"+6000000-01-01"}""")
        try5("valid_on far", HttpMethod.Get, "/admin/route-assignments?valid_on=%2B6000000-01-01", null)
        try5("ifmatch overflow", HttpMethod.Patch, "/admin/clusters/1", """{"name":"x"}""", im = "99999999999")
        try5("ifmatch weak", HttpMethod.Patch, "/admin/clusters/1", """{"name":"x"}""", im = "W/1")
        try5("cursor junk", HttpMethod.Get, "/admin/users?cursor=!!!", null)
        try5("cursor huge secs", HttpMethod.Get, "/admin/users?updated_since=2020-01-01T00:00:00Z&cursor=" + java.util.Base64.getUrlEncoder().withoutPadding().encodeToString("9223372036854775807.999999999|5".toByteArray()), null)
        try5("limit text", HttpMethod.Get, "/admin/users?limit=abc", null)
        try5("q percent", HttpMethod.Get, "/admin/users?q=%25%25", null)
        try5("zone selector huge", HttpMethod.Get, "/admin/users?zone_id=99999999999999999999", null)
        try5("role selector sql", HttpMethod.Get, "/admin/users?role=TSO'%20OR%201=1--", null)
        try5("geo level bogus", HttpMethod.Get, "/admin/geo/zone%27--", null)
        try5("id huge", HttpMethod.Get, "/admin/users/99999999999999999999", null)
        try5("user phone number type", HttpMethod.Post, "/admin/users", """{"username":"ckodd0001","full_name":"x","role":"TSO","locale":"en","phone":1712345678}""")
        try5("user email nul", HttpMethod.Post, "/admin/users", """{"username":"ckodd0002","full_name":"x","role":"TSO","locale":"en","email":"a\u0000b"}""")
        try5("outlet lat precision", HttpMethod.Post, "/admin/outlets", """{"name":"Prec","owner_name":"Own","zone_id":$z,"cluster_id":1,"channel":"GT","outlet_kind":"retail","lat":1e-300,"lng":1e-300}""")
        try5("code list nul", HttpMethod.Put, "/admin/code-lists/leave_type", """{"items":[{"code":"ck_nul","label_en":"a\u0000b","sort":1}],"change_reason":"nul label test here"}""")
        try5("bulk huge id", HttpMethod.Post, "/admin/outlets/outlet-kind", """{"batch_uuid":"${UUID.randomUUID()}","outlet_kind":"retail","outlet_ids":[9223372036854775807],"reason":"huge outlet id test"}""")
        try5("bulk string ids", HttpMethod.Post, "/admin/outlets/outlet-kind", """{"batch_uuid":"${UUID.randomUUID()}","outlet_kind":"retail","outlet_ids":["1"],"reason":"string outlet id test"}""")
        try5("bulk float id", HttpMethod.Post, "/admin/outlets/outlet-kind", """{"batch_uuid":"${UUID.randomUUID()}","outlet_kind":"retail","outlet_ids":[1.0],"reason":"float outlet id test"}""")
        s.done()
    }

    @Test
    fun ckReactivateChildUnderInactiveParent() = env.app {
        val s = CkSoft()
        val div = env.sql("SELECT id FROM app.division WHERE code = 'D-DHK'")!!
        val t = sendA1(HttpMethod.Post, "/admin/geo/territory", admin, """{"code":"CKT1","name":"Ck terr","parent_id":$div,"change_reason":"checker territory probe"}""").objA1().lng("id")
        val zid = sendA1(HttpMethod.Post, "/admin/geo/zone", admin, """{"code":"CKZ1","name":"Ck zone","parent_id":$t,"change_reason":"checker zone probe"}""").objA1().lng("id")
        s.chk(sendA1(HttpMethod.Patch, "/admin/geo/zone/$zid", admin, """{"status":"inactive"}""", ifMatch = ckVer("app.zone", zid)).status == HttpStatusCode.OK, "deactivate zone")
        s.chk(sendA1(HttpMethod.Patch, "/admin/geo/territory/$t", admin, """{"status":"inactive"}""", ifMatch = ckVer("app.territory", t)).status == HttpStatusCode.OK, "deactivate territory")
        val re = sendA1(HttpMethod.Patch, "/admin/geo/zone/$zid", admin, """{"status":"active"}""", ifMatch = ckVer("app.zone", zid))
        s.chk(re.status.value in 400..499, "zone reactivated under inactive territory -> ${re.status.value}")
        s.done()
    }

    @Test
    fun ckRevertPendingVisitDaysChange() = env.app {
        val s = CkSoft(); val z = ckZone("Z-MIR")
        val r = sendA1(HttpMethod.Post, "/admin/routes", admin, """{"code":"CKR1","name":"Ck route","zone_id":$z,"kind":"sr","visit_kind":"daily","visit_days_mask":127}""").objA1().lng("id")
        val eff = env.today.plusDays(10)
        val p1 = sendA1(HttpMethod.Patch, "/admin/routes/$r", admin, """{"visit_kind":"3f","visit_days_mask":21,"effective_from":"$eff"}""", ifMatch = ckVer("app.route", r))
        s.chk(p1.status == HttpStatusCode.OK, "schedule change ${p1.status} ${p1.bodyAsText().take(150)}")
        s.chk(env.sql("SELECT visit_days_mask FROM app.route WHERE id = $r") == "127", "route row keeps today's mask until effective date")
        val p2 = sendA1(HttpMethod.Patch, "/admin/routes/$r", admin, """{"visit_kind":"daily","visit_days_mask":127,"effective_from":"$eff"}""", ifMatch = ckVer("app.route", r))
        val planned = env.sql("SELECT string_agg(visit_days_mask::text || '@' || valid_from::text, ',' ORDER BY valid_from) FROM app.route_planned WHERE route_id = $r")
        s.chk(p2.status == HttpStatusCode.OK && planned!!.endsWith("127@$eff"), "revert of a pending change: ${p2.status.value} planned=$planned ${p2.bodyAsText().take(150)}")
        s.done()
    }

    @Test
    fun ckUserRulesAndScopeVersion() = env.app {
        val s = CkSoft(); val z = ckZone("Z-MIR")
        suspend fun mk(u: String, role: String) = sendA1(HttpMethod.Post, "/admin/users", admin, """{"username":"$u","full_name":"Ck $u","role":"$role","locale":"en","home_zone_id":$z,"phone":"01712345678","email":"x@y.z"}""")
        s.chk(mk("ckadm0001", "ADMIN").status == HttpStatusCode.Forbidden, "ADMIN creates ADMIN")
        s.chk(mk("cksup0001", "SUPERADMIN").status == HttpStatusCode.Forbidden, "ADMIN creates SUPERADMIN")
        val c = mk("cktso0001", "TSO"); s.chk(c.status == HttpStatusCode.Created, "create TSO ${c.status}")
        val body = c.bodyAsText()
        s.chk(!body.contains("password_hash") && !body.contains("argon2"), "hash exposed on create")
        s.chk(!body.contains("01712345678"), "phone exposed to non-pii caller on create")
        val u = (json(body).getValue("user") as JsonObject).lng("id")
        val audits = env.sql("SELECT string_agg(coalesce(after::text,''), ' ') FROM app.audit_log WHERE entity = 'user' AND entity_id = '$u'")!!
        s.chk(!audits.contains("01712345678") && !audits.contains("argon2"), "SUSPICION: audit row holds phone/hash ($audits)")
        val sa = env.db.ids.getValue("superadmin1001")
        s.chk(sendA1(HttpMethod.Patch, "/admin/users/$sa", admin, """{"full_name":"Hacked"}""", ifMatch = ckVer("app.app_user", sa)).status == HttpStatusCode.Forbidden, "ADMIN edits SUPERADMIN")
        s.chk(sendA1(HttpMethod.Post, "/admin/users/$sa/credentials", admin, """{"action":"reset_password","reason":"takeover attempt xx"}""").status == HttpStatusCode.Forbidden, "ADMIN resets SUPERADMIN pw")
        s.chk(sendA1(HttpMethod.Put, "/admin/users/$sa/scope", admin, """{"valid_from":"${env.today}","nodes":[],"change_reason":"takeover attempt xx"}""").status == HttpStatusCode.Forbidden, "ADMIN edits SUPERADMIN scope")
        s.chk(sendA1(HttpMethod.Patch, "/admin/users/$u", admin, """{"role":"ADMIN"}""", ifMatch = ckVer("app.app_user", u)).status == HttpStatusCode.Forbidden, "ADMIN promotes TSO to ADMIN")
        fun sv() = env.sql("SELECT scope_version FROM app.app_user WHERE id = $u")!!.toLong()
        val v0 = sv()
        sendA1(HttpMethod.Patch, "/admin/users/$u", admin, """{"role":"DMO"}""", ifMatch = ckVer("app.app_user", u)); s.chk(sv() == v0 + 1, "role change bumps scope_version")
        sendA1(HttpMethod.Put, "/admin/users/$u/scope", admin, """{"valid_from":"${env.today}","nodes":[{"node_type":"zone","node_id":$z}],"change_reason":"scope for the checker"}""")
        s.chk(sv() == v0 + 2, "scope put bumps scope_version")
        sendA1(HttpMethod.Patch, "/admin/users/$u", admin, """{"status":"disabled"}""", ifMatch = ckVer("app.app_user", u)); s.chk(sv() == v0 + 3, "disable bumps scope_version")
        s.chk(env.sql("SELECT count(*) FROM app.audit_log WHERE entity='user_scope' AND entity_id='$u'") == "1", "scope audit row")
        val supMe = sendA1(HttpMethod.Patch, "/admin/users/$sa", sup, """{"status":"disabled"}""", ifMatch = ckVer("app.app_user", sa))
        s.chk(supMe.status == HttpStatusCode.Forbidden, "SUPERADMIN disables self ${supMe.status}")
        s.done()
    }

    @Test
    fun ckAssignmentsAndRoleChangeWithLiveAssignment() = env.app {
        val s = CkSoft(); val z = ckZone("Z-MIR")
        val u = (sendA1(HttpMethod.Post, "/admin/users", admin, """{"username":"cksr0001","full_name":"Ck SR","role":"SR","locale":"en","home_zone_id":$z}""").objA1()["user"] as JsonObject).lng("id")
        val r = sendA1(HttpMethod.Post, "/admin/routes", admin, """{"code":"CKR2","name":"Ck route 2","zone_id":$z,"kind":"sr","visit_kind":"daily","visit_days_mask":127}""").objA1().lng("id")
        val a = sendA1(HttpMethod.Post, "/admin/route-assignments", admin, """{"route_id":$r,"user_id":$u,"kind":"primary","valid_from":"${env.today.plusDays(1)}"}""")
        s.chk(a.status == HttpStatusCode.Created, "assign ${a.status}")
        val aid = a.objA1().lng("id")
        val u2 = (sendA1(HttpMethod.Post, "/admin/users", admin, """{"username":"cksr0002","full_name":"Ck SR2","role":"SR","locale":"en","home_zone_id":$z}""").objA1()["user"] as JsonObject).lng("id")
        val o = sendA1(HttpMethod.Post, "/admin/route-assignments", admin, """{"route_id":$r,"user_id":$u2,"kind":"primary","valid_from":"${env.today.plusDays(3)}"}""")
        s.chk(o.status == HttpStatusCode.Conflict, "overlapping primary on a route ${o.status}")
        val sameDay = sendA1(HttpMethod.Post, "/admin/route-assignments", admin, """{"route_id":$r,"user_id":$u2,"kind":"primary","valid_from":"${env.today.plusDays(3)}","valid_to":"${env.today.plusDays(3)}"}""")
        s.chk(sameDay.status == HttpStatusCode.BadRequest, "valid_to == valid_from ${sameDay.status}")
        val r2 = sendA1(HttpMethod.Post, "/admin/routes", admin, """{"code":"CKR3","name":"Ck route 3","zone_id":$z,"kind":"sr","visit_kind":"daily","visit_days_mask":127}""").objA1().lng("id")
        val two = sendA1(HttpMethod.Post, "/admin/route-assignments", admin, """{"route_id":$r2,"user_id":$u,"kind":"primary","valid_from":"${env.today.plusDays(2)}"}""")
        s.chk(two.status.value in 200..299, "one SR holds several routes at once (Daily, 3F, 2F routes of the seeded SR) -> ${two.status}") // ruling: valid, not an overlap
        val ends = sendA1(HttpMethod.Post, "/admin/route-assignments/$aid/end", admin, """{"valid_to":"${env.today.plusDays(5)}","reason":"end it for checker"}""")
        s.chk(ends.status == HttpStatusCode.OK, "end ${ends.status}")
        val replay = sendA1(HttpMethod.Post, "/admin/route-assignments/$aid/end", admin, """{"valid_to":"${env.today.plusDays(5)}","reason":"end it for checker"}""")
        s.chk(replay.status == HttpStatusCode.OK && env.audit("route_assignment", aid, "end") == 1L, "end replay audits once: ${replay.status} ${env.audit("route_assignment", aid, "end")}")
        val ar = sendA1(HttpMethod.Post, "/admin/route-assignments", admin, """{"route_id":$r2,"user_id":$u,"kind":"primary","valid_from":"${env.today.plusDays(20)}"}""")
        val role = sendA1(HttpMethod.Patch, "/admin/users/$u", admin, """{"role":"TSO"}""", ifMatch = ckVer("app.app_user", u))
        s.chk(ar.status != HttpStatusCode.Created || role.status.value in 400..499, "SUSPICION: SR with live assignment turned into TSO -> ${role.status.value}")
        s.done()
    }

    @Test
    fun ckConcurrentPatchLostUpdate() = env.app {
        val s = CkSoft(); val z = ckZone("Z-MIR")
        val c = sendA1(HttpMethod.Post, "/admin/clusters", admin, """{"zone_id":$z,"name":"Ck conc"}""").objA1().lng("id")
        val rs = kotlinx.coroutines.coroutineScope { (1..8).map { i -> async { sendA1(HttpMethod.Patch, "/admin/clusters/$c", admin, """{"name":"Ck conc $i"}""", ifMatch = 1).status.value } }.awaitAll() }
        s.chk(rs.count { it == 200 } == 1 && rs.count { it == 412 } == 7, "statuses $rs")
        s.chk(env.audit("cluster", c, "update") == 1L, "one audit row for the one winner")
        s.chk(ckVer("app.cluster", c) == "2", "version 2")
        val b = UUID.randomUUID(); val ids = env.sql("SELECT string_agg(id::text, ',') FROM (SELECT id FROM app.outlet ORDER BY id LIMIT 4) x")!!
        val body = """{"batch_uuid":"$b","outlet_kind":"wholesale","outlet_ids":[$ids],"reason":"parallel replay batch"}"""
        val bs = kotlinx.coroutines.coroutineScope { (1..6).map { async { sendA1(HttpMethod.Post, "/admin/outlets/outlet-kind", admin, body).objA1().getValue("replayed").toString() } }.awaitAll() }
        s.chk(bs.count { it == "false" } == 1, "parallel same batch: $bs")
        s.chk(env.sql("SELECT count(*) FROM app.audit_log WHERE entity='outlet' AND action='set_outlet_kind' AND after::text LIKE '%$b%'") == "4", "4 outlet audit rows")
        s.done()
    }

    @Test
    fun ckBulkKindOnMergedAndShape() = env.app {
        val s = CkSoft()
        val id = env.sql("SELECT id FROM app.outlet WHERE outlet_kind = 'retail' ORDER BY id DESC LIMIT 1")!!
        val ok = runCatching { env.db.fresh.db.jdbi.useHandle<Exception> { it.execute("UPDATE app.outlet SET status = 'merged' WHERE id = $id") } }.isSuccess
        if (ok) {
            val r = sendA1(HttpMethod.Post, "/admin/outlets/outlet-kind", admin, """{"batch_uuid":"${UUID.randomUUID()}","outlet_kind":"wholesale","outlet_ids":[$id],"reason":"merged outlet kind test"}""")
            val k = env.sql("SELECT outlet_kind FROM app.outlet WHERE id = $id")
            s.chk(r.status.value in 400..499 || k == "retail", "bulk changed a merged (read-only) outlet: ${r.status.value} kind=$k")
        }
        val ids = env.sql("SELECT string_agg(id::text, ',') FROM (SELECT id FROM app.outlet WHERE status='active' ORDER BY id LIMIT 3) x")!!
        val b = UUID.randomUUID().toString().uppercase()
        val r = sendA1(HttpMethod.Post, "/admin/outlets/outlet-kind", admin, """{"batch_uuid":"$b","outlet_kind":"retail","outlet_ids":[$ids,$ids],"reason":"duplicate ids in a batch"}""")
        s.chk(r.status == HttpStatusCode.OK && r.objA1().keys == setOf("batch_uuid", "updated", "unchanged", "replayed"), "bulk body ${r.bodyAsText()}")
        val r2 = sendA1(HttpMethod.Post, "/admin/outlets/outlet-kind", admin, """{"batch_uuid":"${b.lowercase()}","outlet_kind":"retail","outlet_ids":[$ids],"reason":"lower-case replay of it"}""")
        s.chk(r2.status == HttpStatusCode.OK && r2.objA1().getValue("replayed").toString() == "true", "case-different uuid replay: ${r2.bodyAsText()}")
        s.done()
    }

    @Test
    fun ckTsoOfOtherTerritoryAndPii() = env.app {
        val s = CkSoft(); val t2 = env.token("tso2001", Role.TSO)
        val sr = env.db.ids.getValue("sr1001"); val z = ckZone("Z-MIR")
        val o = env.sql("SELECT id FROM app.outlet WHERE zone_id = $z ORDER BY id LIMIT 1")!!
        val route = env.sql("SELECT id FROM app.route WHERE code = 'MIR-SR-D'")!!
        for (p in listOf("/admin/users/$sr", "/admin/users/$sr/scope", "/admin/outlets/$o", "/admin/routes/$route", "/admin/geo/zone/$z"))
            s.chk(sendA1(HttpMethod.Get, p, t2).status == HttpStatusCode.NotFound, "TSO2 GET $p")
        s.chk(sendA1(HttpMethod.Post, "/admin/users/$sr/credentials", t2, """{"action":"reset_password","reason":"cross territory reset"}""").status == HttpStatusCode.NotFound, "TSO2 credentials on foreign SR")
        s.chk(sendA1(HttpMethod.Get, "/admin/users?zone_id=$z", t2).status == HttpStatusCode.Forbidden, "TSO2 zone selector of foreign zone")
        s.chk(!sendA1(HttpMethod.Get, "/admin/users", t2).bodyAsText().contains("\"sr1001\""), "TSO2 user list leaks sr1001")
        val all = sendA1(HttpMethod.Get, "/admin/users", admin).bodyAsText()
        s.chk(!all.contains("password") && !all.contains("argon2"), "hash in list")
        s.chk(!sendA1(HttpMethod.Get, "/admin/outlets/$o", admin).bodyAsText().contains("\"contact_number\":\"0"), "outlet phone without pii")
        s.chk(!all.contains("\"phone\":\"0"), "user phone without pii")
        val u = sendA1(HttpMethod.Get, "/admin/users/$sr", admin).objA1()
        s.chk(u.keys.containsAll(setOf("id", "username", "full_name", "role", "status", "version", "locale")), "user keys ${u.keys}")
        s.done()
    }

    @Test
    fun ckGenericIfMatchAndAudit() = env.app {
        val s = CkSoft(); val z = ckZone("Z-MIR")
        val id = sendA1(HttpMethod.Post, "/admin/clusters", admin, """{"zone_id":$z,"name":"Ck im"}""").objA1().lng("id")
        val noHeader = sendA1(HttpMethod.Patch, "/admin/clusters/$id", admin, """{"name":"Ck im2"}""")
        s.chk(noHeader.status.value in setOf(400, 428), "missing If-Match ${noHeader.status}")
        val stale = sendA1(HttpMethod.Patch, "/admin/clusters/$id", admin, """{"name":"Ck im2"}""", ifMatch = 7)
        s.chk(stale.status == HttpStatusCode.PreconditionFailed && stale.headers["ETag"] == "\"1\"", "stale ${stale.status} etag=${stale.headers["ETag"]}")
        val unknownKey = sendA1(HttpMethod.Patch, "/admin/clusters/$id", admin, """{"name":"Ck im3","version":9}""", ifMatch = 1)
        s.chk(unknownKey.status == HttpStatusCode.BadRequest, "version in body ${unknownKey.status}")
        val before = env.count("SELECT count(*) FROM app.audit_log")
        sendA1(HttpMethod.Patch, "/admin/clusters/$id", admin, """{"zone_id":999999}""", ifMatch = 1)
        s.chk(before == env.count("SELECT count(*) FROM app.audit_log") && ckVer("app.cluster", id) == "1", "failed patch left traces")
        s.chk(env.count("SELECT count(*) FROM app.audit_log WHERE entity='cluster' AND entity_id='$id' AND (actor_user_id IS NULL OR request_id IS NULL)") == 0L, "audit actor/request id")
        s.done()
    }
}
