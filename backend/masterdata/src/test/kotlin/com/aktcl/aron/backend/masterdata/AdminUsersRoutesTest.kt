package com.aktcl.aron.backend.masterdata

import com.aktcl.aron.contract.Role
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** F-API-035b: users, scope, credentials, routes and route assignments. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AdminUsersRoutesTest {
    private lateinit var env: AdminEnv

    @BeforeAll fun setUp() { env = AdminEnv() }
    @AfterAll fun tearDown() = env.close()

    private val admin get() = env.token("admin1001", Role.ADMIN)
    private val superadmin get() = env.token("superadmin1001", Role.SUPERADMIN)
    private fun zone(code: String) = env.sql("SELECT id FROM app.zone WHERE code = '$code'")!!.toLong()
    private fun uid(name: String) = env.db.ids.getValue(name)
    private fun scopeVersion(u: Long) = env.count("SELECT scope_version FROM app.app_user WHERE id = $u")

    /** docs/19 `cfg.auth.temp_password_ttl_h` defaults to 24 hours (F-TSO-023). */
    private fun assertTtl24h(at: String) {
        // wall-clock-ok: the app under test runs on AronClock.SYSTEM; compares its expiry to now within 2 minutes (elapsed, not date-dependent)
        val h = java.time.Duration.between(java.time.Instant.now(), java.time.Instant.parse(at)).toMinutes()
        assertTrue(h in 24 * 60 - 2..24 * 60, "temporary password lives 24 h, got $h min")
    }

    private fun userBody(name: String, role: String = "TSO", extra: String = "") =
        """{"username":"$name","full_name":"Created $name","role":"$role","locale":"bn","home_zone_id":${zone("Z-MIR")}$extra}"""

    @Test
    fun createUserShowsTheTemporaryPasswordOnceNeverReturnsAHashAndAuditsTheWrite() = env.app {
        val res = sendA1(HttpMethod.Post, "/admin/users", admin, userBody("newtso1", "TSO", ""","phone":"01711223344""""))
        assertEquals(HttpStatusCode.Created, res.status)
        val o = res.objA1()
        val temp = o.strA1("temporary_password")!!
        assertTrue(temp.length in 12..32)
        assertNotNull(o.strA1("temporary_password_expires_at"))
        assertTtl24h(o.strA1("temporary_password_expires_at")!!)
        val user = o["user"] as JsonObject
        assertFalse(user.keys.any { it.contains("password") })
        assertNull(user.strA1("phone"), "PII only with the pii claim")
        val id = user.lng("id")
        assertEquals("argon2id\$test\$" + temp.reversed(), env.sql("SELECT password_hash FROM app.app_user WHERE id = $id"))
        assertEquals("true", env.sql("SELECT must_change_password::text FROM app.app_user WHERE id = $id"))
        assertEquals(1, env.audit("user", id, "create"))
        assertEquals("0", env.sql("SELECT count(*) FROM app.audit_log WHERE after::text LIKE '%$temp%' OR after::text LIKE '%argon2id%'"))
        // Reads never expose the hash; the phone shows with the pii claim.
        val get = sendA1(HttpMethod.Get, "/admin/users/$id", env.token("admin1001", Role.ADMIN, pii = true))
        assertEquals("\"1\"", get.headers["ETag"])
        assertFalse(get.objA1().keys.any { it.contains("password") })
        assertEquals("01711223344", get.objA1().strA1("phone"))
        // The same username again is a duplicate (case-insensitive), nothing is written.
        val audits = env.count("SELECT count(*) FROM app.audit_log")
        val dup = sendA1(HttpMethod.Post, "/admin/users", admin, userBody("NEWTSO1"))
        assertEquals(HttpStatusCode.Conflict, dup.status); assertEquals("ERR_MASTER_DUPLICATE_CODE", dup.code())
        assertEquals(audits, env.count("SELECT count(*) FROM app.audit_log"))
    }

    @Test
    fun userValidationAndRoleRules() = env.app {
        suspend fun post(body: String, token: String = admin) = sendA1(HttpMethod.Post, "/admin/users", token, body)
        assertEquals(HttpStatusCode.BadRequest, post(userBody("SR-1", "SR")).status)
        assertEquals(HttpStatusCode.BadRequest, post(userBody("x", "TSO")).status)
        assertEquals(HttpStatusCode.BadRequest, post(userBody("okname1", "BOSS")).status)
        assertEquals(HttpStatusCode.BadRequest, post(userBody("okname2", "TSO", ""","phone":"12345"""")).status)
        assertEquals(HttpStatusCode.BadRequest, post(userBody("okname3", "TSO", ""","password":"hunter2hunter2"""")).status)
        assertEquals(HttpStatusCode.BadRequest, post("""{"username":"okname4","full_name":"x","role":"TSO","locale":"bn","home_zone_id":99999}""").status)
        assertEquals(HttpStatusCode.Created, post(userBody("newsr001", "SR")).status)
        // An ADMIN cannot create ADMIN or SUPERADMIN users; a SUPERADMIN can.
        assertEquals(HttpStatusCode.Forbidden, post(userBody("newadmin1", "ADMIN")).status)
        assertEquals(HttpStatusCode.Created, post(userBody("newadmin1", "ADMIN"), superadmin).status)
        // TSO, SUPPORT and field roles never write users.
        assertEquals(HttpStatusCode.Forbidden, post(userBody("newtso2"), env.token("tso1001", Role.TSO)).status)
        assertEquals(HttpStatusCode.Forbidden, post(userBody("newtso2"), env.token("support1001", Role.SUPPORT)).status)
        assertEquals("0", env.sql("SELECT count(*) FROM app.app_user WHERE username = 'newtso2'"))
    }

    @Test
    fun patchUserVersionConflictDisableBumpsScopeVersionAndRevokesFullGrants() = env.app {
        val created = sendA1(HttpMethod.Post, "/admin/users", admin, userBody("patchme1", "SR")).objA1()["user"] as JsonObject
        val id = created.lng("id")
        env.db.fresh.db.jdbi.useHandle<Exception> { h ->
            h.execute("INSERT INTO app.refresh_family (user_id, client, grant_kind, sliding_expires_at, absolute_expires_at) VALUES ($id, 'web', 'full', now() + interval '1 day', now() + interval '2 day')")
            h.execute("INSERT INTO app.refresh_family (user_id, client, grant_kind, sliding_expires_at, absolute_expires_at) VALUES ($id, 'web', 'upload', now() + interval '1 day', now() + interval '2 day')")
        }
        val sv0 = scopeVersion(id)
        val p1 = sendA1(HttpMethod.Patch, "/admin/users/$id", admin, """{"designation":"Senior SR","change_reason":"Promotion confirmed by the TSO"}""", ifMatch = 1)
        assertEquals(HttpStatusCode.OK, p1.status); assertEquals(2, p1.objA1().lng("version").toInt())
        assertEquals(sv0, scopeVersion(id), "a designation change does not touch scope")
        val stale = sendA1(HttpMethod.Patch, "/admin/users/$id", admin, """{"designation":"Other"}""", ifMatch = 1)
        assertEquals(HttpStatusCode.PreconditionFailed, stale.status); assertEquals("ERR_PRECONDITION_FAILED", stale.code())
        val v = env.sql("SELECT version FROM app.app_user WHERE id = $id")!!
        val off = sendA1(HttpMethod.Patch, "/admin/users/$id", admin, """{"status":"disabled","change_reason":"Left the company in October"}""", ifMatch = v)
        assertEquals("disabled", off.objA1().strA1("status"))
        assertEquals(sv0 + 1, scopeVersion(id))
        assertNotNull(env.sql("SELECT disabled_at FROM app.app_user WHERE id = $id"))
        // Disabling keeps the upload grant and revokes the full ones.
        assertEquals("1", env.sql("SELECT count(*) FROM app.refresh_family WHERE user_id = $id AND grant_kind = 'full' AND revoke_reason = 'user_disabled'"))
        assertEquals("0", env.sql("SELECT count(*) FROM app.refresh_family WHERE user_id = $id AND grant_kind = 'upload' AND revoked_at IS NOT NULL"))
        assertEquals(2, env.audit("user", id, "update"))
        // Nobody disables themselves or changes their own role.
        val me = uid("admin1001"); val mv = env.sql("SELECT version FROM app.app_user WHERE id = $me")!!
        assertEquals(HttpStatusCode.Forbidden, sendA1(HttpMethod.Patch, "/admin/users/$me", admin, """{"status":"disabled"}""", ifMatch = mv).status)
        // An ADMIN cannot edit a SUPERADMIN.
        val su = uid("superadmin1001"); val sv = env.sql("SELECT version FROM app.app_user WHERE id = $su")!!
        assertEquals(HttpStatusCode.Forbidden, sendA1(HttpMethod.Patch, "/admin/users/$su", admin, """{"designation":"x"}""", ifMatch = sv).status)
        assertEquals(HttpStatusCode.NotFound, sendA1(HttpMethod.Patch, "/admin/users/99999", admin, """{"designation":"x"}""", ifMatch = 1).status)
    }

    @Test
    fun userListsAreReachScopedAndOtherTerritoriesAreNotFound() = env.app {
        val tso1 = env.token("tso1001", Role.TSO); val tso2 = env.token("tso2001", Role.TSO)
        val seen1 = sendA1(HttpMethod.Get, "/admin/users?limit=200", tso1).objA1().itemsA1().map { it.strA1("username") }
        assertTrue("sr1001" in seen1 && "sr2001" !in seen1 && "tso2001" !in seen1, "$seen1")
        val seen2 = sendA1(HttpMethod.Get, "/admin/users?limit=200", tso2).objA1().itemsA1().map { it.strA1("username") }
        assertTrue("sr2001" in seen2 && "sr1001" !in seen2, "$seen2")
        assertEquals(HttpStatusCode.NotFound, sendA1(HttpMethod.Get, "/admin/users/${uid("sr1001")}", tso2).status)
        assertEquals(HttpStatusCode.NotFound, sendA1(HttpMethod.Get, "/admin/users/${uid("sr1001")}/scope", tso2).status)
        assertEquals(HttpStatusCode.OK, sendA1(HttpMethod.Get, "/admin/users/${uid("sr1001")}", tso1).status)
        assertEquals("ERR_OUT_OF_SCOPE", sendA1(HttpMethod.Get, "/admin/users?zone_id=${zone("Z-MIR")}", tso2).code())
        val srs = sendA1(HttpMethod.Get, "/admin/users?role=SR&limit=200", admin).objA1().itemsA1().map { it.strA1("role") }.toSet()
        assertEquals(setOf("SR"), srs)
        assertEquals(HttpStatusCode.Forbidden, sendA1(HttpMethod.Get, "/admin/users", env.token("sr1001", Role.SR)).status)
        assertEquals(HttpStatusCode.Forbidden, sendA1(HttpMethod.Get, "/admin/users", env.token("admin1001", Role.ANALYST)).status)
    }

    @Test
    fun putScopeReplacesFromADateAndAReplayChangesNothing() = env.app {
        val u = (sendA1(HttpMethod.Post, "/admin/users", admin, userBody("scoped01", "DMO")).objA1()["user"] as JsonObject).lng("id")
        val body = """{"valid_from":"${env.today}","nodes":[{"node_type":"territory","node_id":${env.sql("SELECT id FROM app.territory WHERE code = 'T-DHK-N'")}},{"node_type":"zone","node_id":${zone("Z-OTHER")}}],"change_reason":"Cover both territories"}"""
        val sv0 = scopeVersion(u)
        val r1 = sendA1(HttpMethod.Put, "/admin/users/$u/scope", admin, body)
        assertEquals(HttpStatusCode.OK, r1.status)
        assertEquals(2, r1.objA1().arr("nodes").size); assertEquals(sv0 + 1, r1.objA1().lng("scope_version"))
        assertEquals(1, env.audit("user_scope", u, "put"))
        val r2 = sendA1(HttpMethod.Put, "/admin/users/$u/scope", admin, body)
        assertEquals(r1.objA1(), r2.objA1())
        assertEquals(sv0 + 1, scopeVersion(u)); assertEquals(1, env.audit("user_scope", u, "put"))
        // Replacing with one node closes the other from the date (never deleted when it started earlier).
        val later = env.today.plusDays(7)
        val r3 = sendA1(HttpMethod.Put, "/admin/users/$u/scope", admin, """{"valid_from":"$later","nodes":[{"node_type":"zone","node_id":${zone("Z-OTHER")}}],"change_reason":"Narrow to one zone later"}""")
        assertEquals(HttpStatusCode.OK, r3.status)
        assertEquals("1", env.sql("SELECT count(*) FROM app.user_scope WHERE user_id = $u AND node_type = 'territory' AND valid_to = date '$later'"))
        assertEquals(sv0 + 2, scopeVersion(u))
        assertEquals(setOf("territory", "zone"), sendA1(HttpMethod.Get, "/admin/users/$u/scope", env.token("dmo1001", Role.DMO)).objA1().arr("nodes").map { (it as JsonObject).strA1("node_type") }.toSet())
        // Validation, role and reach.
        suspend fun put(b: String, t: String = admin) = sendA1(HttpMethod.Put, "/admin/users/$u/scope", t, b)
        assertEquals("ERR_MASTER_EFFECTIVE_DATE_PAST", put("""{"valid_from":"${env.today.minusDays(3)}","nodes":[],"change_reason":"Backdated scope change"}""").code())
        assertEquals(HttpStatusCode.BadRequest, put("""{"valid_from":"${env.today}","nodes":[{"node_type":"zone","node_id":0}],"change_reason":"Zone zero is invalid"}""").status)
        assertEquals(HttpStatusCode.BadRequest, put("""{"valid_from":"${env.today}","nodes":[{"node_type":"zone","node_id":99999}],"change_reason":"Unknown zone node"}""").status)
        assertEquals(HttpStatusCode.BadRequest, put("""{"valid_from":"${env.today}","nodes":[]}""").status)
        assertEquals(HttpStatusCode.BadRequest, put("""{"valid_from":"${env.today}","nodes":[{"node_type":"national","node_id":0},{"node_type":"national","node_id":0}],"change_reason":"Duplicate national"}""").status)
        assertEquals(HttpStatusCode.BadRequest, sendA1(HttpMethod.Put, "/admin/users/${uid("sr1001")}/scope", admin, """{"valid_from":"${env.today}","nodes":[],"change_reason":"SR scope is not a thing"}""").status)
        assertEquals(HttpStatusCode.Forbidden, put(body, env.token("tso1001", Role.TSO)).status)
        assertEquals(HttpStatusCode.Forbidden, put(body, env.token("dmo1001", Role.DMO)).status)
    }

    @Test
    fun credentialActionsAndTheTsoLimits() = env.app {
        val tso1 = env.token("tso1001", Role.TSO)
        val sr = uid("sr1001")
        val hash0 = env.sql("SELECT coalesce(password_hash, '-') FROM app.app_user WHERE id = $sr")
        env.db.fresh.db.jdbi.useHandle<Exception> { h ->
            h.execute("INSERT INTO app.refresh_family (user_id, client, grant_kind, sliding_expires_at, absolute_expires_at) VALUES ($sr, 'web', 'full', now() + interval '1 day', now() + interval '2 day')")
            h.execute("INSERT INTO app.auth_lockout (lock_key, failures, locked_until) VALUES ('sr1001|dev1|1.2.3', 10, now() + interval '1 hour'), ('sr1001', 3, NULL), ('sr10010', 1, NULL)")
        }
        val sv0 = scopeVersion(sr)
        val reset = sendA1(HttpMethod.Post, "/admin/users/$sr/credentials", tso1, """{"action":"reset_password","reason":"Rep forgot the password"}""")
        assertEquals(HttpStatusCode.OK, reset.status)
        val temp = reset.objA1().strA1("temporary_password")!!
        assertTtl24h(reset.objA1().strA1("temporary_password_expires_at")!!)
        assertEquals("argon2id\$test\$" + temp.reversed(), env.sql("SELECT password_hash FROM app.app_user WHERE id = $sr"))
        assertTrue(hash0 != env.sql("SELECT password_hash FROM app.app_user WHERE id = $sr"))
        assertEquals("true", env.sql("SELECT must_change_password::text FROM app.app_user WHERE id = $sr"))
        assertEquals("0", env.sql("SELECT count(*) FROM app.refresh_family WHERE user_id = $sr AND grant_kind = 'full' AND revoked_at IS NULL"))
        assertEquals(sv0 + 1, scopeVersion(sr))
        assertEquals("1", env.sql("SELECT count(*) FROM app.auth_lockout WHERE lock_key = 'sr10010'"), "another user's lockout key is untouched")
        assertEquals("0", env.sql("SELECT count(*) FROM app.auth_lockout WHERE lock_key LIKE 'sr1001|%' OR lock_key = 'sr1001'"))
        assertEquals(1, env.audit("user", sr, "credentials.reset_password"))
        assertEquals("0", env.sql("SELECT count(*) FROM app.audit_log WHERE after::text LIKE '%$temp%'"))
        assertEquals(HttpStatusCode.OK, sendA1(HttpMethod.Post, "/admin/users/$sr/credentials", tso1, """{"action":"unlock","reason":"Locked after many tries"}""").status)
        // A TSO: only reset and unlock, only SR and AMO, only in own zones, always with a reason.
        assertEquals(HttpStatusCode.Forbidden, sendA1(HttpMethod.Post, "/admin/users/$sr/credentials", tso1, """{"action":"force_logout","reason":"Not a TSO action"}""").status)
        assertEquals(HttpStatusCode.NotFound, sendA1(HttpMethod.Post, "/admin/users/${uid("sr2001")}/credentials", tso1, """{"action":"reset_password","reason":"Other territory's rep"}""").status)
        assertEquals(HttpStatusCode.Forbidden, sendA1(HttpMethod.Post, "/admin/users/${uid("dmo1001")}/credentials", env.token("tso1001", Role.TSO), """{"action":"unlock","reason":"A DMO is not an SR"}""").status)
        assertEquals(HttpStatusCode.BadRequest, sendA1(HttpMethod.Post, "/admin/users/$sr/credentials", tso1, """{"action":"unlock","reason":"short"}""").status)
        assertEquals(HttpStatusCode.BadRequest, sendA1(HttpMethod.Post, "/admin/users/$sr/credentials", tso1, """{"action":"explode","reason":"Not an action at all"}""").status)
        assertEquals(HttpStatusCode.Forbidden, sendA1(HttpMethod.Post, "/admin/users/$sr/credentials", env.token("sr1001", Role.SR), """{"action":"unlock","reason":"Self service attempt"}""").status)
        // SUPPORT may force a logout of any user in reach; only a SUPERADMIN acts on ADMIN users.
        assertEquals(HttpStatusCode.OK, sendA1(HttpMethod.Post, "/admin/users/${uid("tso1001")}/credentials", env.token("support1001", Role.SUPPORT), """{"action":"force_logout","reason":"Phone was lost today"}""").status)
        assertEquals(HttpStatusCode.Forbidden, sendA1(HttpMethod.Post, "/admin/users/${uid("superadmin1001")}/credentials", env.token("support1001", Role.SUPPORT), """{"action":"reset_password","reason":"Support reset a superadmin"}""").status)
        assertEquals(HttpStatusCode.OK, sendA1(HttpMethod.Post, "/admin/users/${uid("admin1001")}/credentials", superadmin, """{"action":"reset_mfa","reason":"Admin lost the authenticator"}""").status)
        assertEquals(HttpStatusCode.Forbidden, sendA1(HttpMethod.Post, "/admin/users/$sr/credentials", env.token("support1001", Role.SUPPORT), """{"action":"reset_mfa","reason":"Support may not reset MFA"}""").status)
    }

    @Test
    fun routesCreatePatchEffectiveDatedVisitDaysDuplicateAndScope() = env.app {
        val zid = zone("Z-MIR")
        val c = sendA1(HttpMethod.Post, "/admin/routes", admin, """{"code":"RT-A1","name":"Test route","zone_id":$zid,"kind":"sr","visit_kind":"3f","visit_days_mask":42,"sequence_no":9}""")
        assertEquals(HttpStatusCode.Created, c.status)
        val rid = c.objA1().lng("id")
        assertEquals("1", env.sql("SELECT count(*) FROM app.route_planned WHERE route_id = $rid AND valid_to IS NULL"))
        assertEquals(1, env.audit("route", rid, "create"))
        assertEquals("ERR_MASTER_DUPLICATE_CODE", sendA1(HttpMethod.Post, "/admin/routes", admin, """{"code":"RT-A1","name":"Again","zone_id":$zid,"kind":"sr","visit_days_mask":127}""").code())
        assertEquals(HttpStatusCode.BadRequest, sendA1(HttpMethod.Post, "/admin/routes", admin, """{"code":"RT-A2","name":"Mismatch","zone_id":$zid,"kind":"sr","visit_kind":"3f","visit_days_mask":127}""").status)
        assertEquals(HttpStatusCode.BadRequest, sendA1(HttpMethod.Post, "/admin/routes", admin, """{"code":"RT-A3","name":"No zone","zone_id":99999,"kind":"sr","visit_days_mask":127}""").status)
        assertEquals(HttpStatusCode.BadRequest, sendA1(HttpMethod.Post, "/admin/routes", admin, """{"code":"RT-A4","name":"Mask","zone_id":$zid,"kind":"sr","visit_days_mask":128}""").status)
        // Plain fields patch with If-Match.
        val p = sendA1(HttpMethod.Patch, "/admin/routes/$rid", admin, """{"name":"Renamed route","change_reason":"Rename after market survey"}""", ifMatch = 1)
        assertEquals(2, p.objA1().lng("version").toInt())
        assertEquals("ERR_PRECONDITION_FAILED", sendA1(HttpMethod.Patch, "/admin/routes/$rid", admin, """{"name":"Stale"}""", ifMatch = 1).code())
        // A visit-days change is effective-dated: it needs a future date and leaves the route row alone until then.
        assertEquals(HttpStatusCode.BadRequest, sendA1(HttpMethod.Patch, "/admin/routes/$rid", admin, """{"visit_kind":"2f","visit_days_mask":36}""", ifMatch = 2).status)
        assertEquals("ERR_MASTER_EFFECTIVE_DATE_PAST", sendA1(HttpMethod.Patch, "/admin/routes/$rid", admin, """{"visit_kind":"2f","visit_days_mask":36,"effective_from":"${env.today}"}""", ifMatch = 2).code())
        val eff = env.today.plusDays(10)
        val sched = sendA1(HttpMethod.Patch, "/admin/routes/$rid", admin, """{"visit_kind":"2f","visit_days_mask":36,"effective_from":"$eff","change_reason":"New call pattern from next week"}""", ifMatch = 2)
        assertEquals(HttpStatusCode.OK, sched.status)
        assertEquals(3, sched.objA1().lng("version").toInt())
        assertEquals(42, sched.objA1().lng("visit_days_mask").toInt())
        assertEquals("2", env.sql("SELECT count(*) FROM app.route_planned WHERE route_id = $rid"))
        assertEquals("1", env.sql("SELECT count(*) FROM app.route_planned WHERE route_id = $rid AND valid_to = date '$eff'"))
        assertEquals("36", env.sql("SELECT visit_days_mask FROM app.route_planned WHERE route_id = $rid AND valid_from = date '$eff'"))
        assertTrue(env.sql("SELECT after::text FROM app.audit_log WHERE entity = 'route' AND entity_id = '$rid' AND action = 'update' ORDER BY id DESC LIMIT 1")!!.contains(eff.toString()))
        // Reach: the other territory's TSO neither reads nor lists this route, and no TSO writes.
        val tso2 = env.token("tso2001", Role.TSO)
        assertEquals(HttpStatusCode.NotFound, sendA1(HttpMethod.Get, "/admin/routes/$rid", tso2).status)
        assertTrue(sendA1(HttpMethod.Get, "/admin/routes", tso2).objA1().itemsA1().isEmpty())
        assertEquals(HttpStatusCode.OK, sendA1(HttpMethod.Get, "/admin/routes/$rid", env.token("tso1001", Role.TSO)).status)
        assertEquals(HttpStatusCode.Forbidden, sendA1(HttpMethod.Post, "/admin/routes", env.token("tso1001", Role.TSO), """{"code":"RT-X","name":"x","zone_id":$zid,"kind":"sr","visit_days_mask":127}""").status)
        assertEquals(HttpStatusCode.Forbidden, sendA1(HttpMethod.Patch, "/admin/routes/$rid", env.token("tso1001", Role.TSO), """{"name":"x"}""", ifMatch = 3).status)
        assertTrue(sendA1(HttpMethod.Get, "/admin/routes?q=RT-A1&territory_id=${env.sql("SELECT territory_id FROM app.zone WHERE id = $zid")}", admin).objA1().itemsA1().isNotEmpty())
    }

    @Test
    fun assignmentsCreateOverlapEndReplayAndInUseDeactivation() = env.app {
        val zid = zone("Z-MIR")
        val rid = sendA1(HttpMethod.Post, "/admin/routes", admin, """{"code":"RT-AS1","name":"Assign route","zone_id":$zid,"kind":"sr","visit_days_mask":127}""").objA1().lng("id")
        val sr2 = uid("sr2001"); val sr1 = uid("sr1001")
        val from = env.today.plusDays(1)
        val sv0 = scopeVersion(sr2)
        val a = sendA1(HttpMethod.Post, "/admin/route-assignments", admin, """{"route_id":$rid,"user_id":$sr2,"kind":"primary","valid_from":"$from","reason":"New rep takes over"}""")
        assertEquals(HttpStatusCode.Created, a.status)
        val aid = a.objA1().lng("id")
        assertEquals(sv0 + 1, scopeVersion(sr2)); assertEquals(1, env.audit("route_assignment", aid, "create"))
        // One primary per route-day: a second primary overlapping is 409 ERR_MASTER_OVERLAP; a cover is fine.
        val overlap = sendA1(HttpMethod.Post, "/admin/route-assignments", admin, """{"route_id":$rid,"user_id":$sr1,"kind":"primary","valid_from":"${from.plusDays(2)}"}""")
        assertEquals(HttpStatusCode.Conflict, overlap.status); assertEquals("ERR_MASTER_OVERLAP", overlap.code())
        assertEquals(HttpStatusCode.Created, sendA1(HttpMethod.Post, "/admin/route-assignments", admin, """{"route_id":$rid,"user_id":$sr1,"kind":"cover","valid_from":"${from.plusDays(2)}","valid_to":"${from.plusDays(5)}"}""").status)
        assertEquals(HttpStatusCode.BadRequest, sendA1(HttpMethod.Post, "/admin/route-assignments", admin, """{"route_id":$rid,"user_id":${uid("tso1001")},"kind":"cover","valid_from":"$from"}""").status)
        assertEquals("ERR_MASTER_EFFECTIVE_DATE_PAST", sendA1(HttpMethod.Post, "/admin/route-assignments", admin, """{"route_id":$rid,"user_id":$sr1,"kind":"cover","valid_from":"${env.today.minusDays(1)}"}""").code())
        assertEquals(HttpStatusCode.BadRequest, sendA1(HttpMethod.Post, "/admin/route-assignments", admin, """{"route_id":$rid,"user_id":$sr1,"kind":"cover","valid_from":"$from","valid_to":"$from"}""").status)
        assertEquals(HttpStatusCode.BadRequest, sendA1(HttpMethod.Post, "/admin/route-assignments", admin, """{"route_id":99999,"user_id":$sr1,"kind":"cover","valid_from":"$from"}""").status)
        // The route has a live assignment, so it cannot be deactivated.
        val v = env.sql("SELECT version FROM app.route WHERE id = $rid")!!
        assertEquals("ERR_MASTER_IN_USE", sendA1(HttpMethod.Patch, "/admin/routes/$rid", admin, """{"status":"inactive"}""", ifMatch = v).code())
        // List with filters, reach and keyset paging.
        val list = sendA1(HttpMethod.Get, "/admin/route-assignments?route_id=$rid&valid_on=${from.plusDays(3)}", admin).objA1().itemsA1()
        assertEquals(setOf("primary", "cover"), list.map { it.strA1("kind") }.toSet())
        assertEquals(1, sendA1(HttpMethod.Get, "/admin/route-assignments?route_id=$rid&limit=1", admin).objA1().itemsA1().size)
        assertNotNull(sendA1(HttpMethod.Get, "/admin/route-assignments?route_id=$rid&limit=1", admin).objA1().strA1("next_cursor"))
        assertTrue(sendA1(HttpMethod.Get, "/admin/route-assignments?route_id=$rid", env.token("tso2001", Role.TSO)).objA1().itemsA1().isEmpty())
        assertEquals(HttpStatusCode.OK, sendA1(HttpMethod.Get, "/admin/route-assignments?route_id=$rid", env.token("tso1001", Role.TSO)).status)
        assertEquals(HttpStatusCode.Forbidden, sendA1(HttpMethod.Post, "/admin/route-assignments", env.token("tso1001", Role.TSO), """{"route_id":$rid,"user_id":$sr1,"kind":"cover","valid_from":"${from.plusDays(9)}"}""").status)
        // End: sets valid_to and ended_at, bumps the user's scope_version; the same request replays, another date conflicts.
        val sv1 = scopeVersion(sr2)
        val end = "${from.plusDays(20)}"
        val e1 = sendA1(HttpMethod.Post, "/admin/route-assignments/$aid/end", admin, """{"valid_to":"$end","reason":"Rep transferred to another zone"}""")
        assertEquals(HttpStatusCode.OK, e1.status); assertEquals(end, e1.objA1().strA1("valid_to"))
        assertEquals(sv1 + 1, scopeVersion(sr2)); assertEquals(1, env.audit("route_assignment", aid, "end"))
        val e2 = sendA1(HttpMethod.Post, "/admin/route-assignments/$aid/end", admin, """{"valid_to":"$end","reason":"Rep transferred to another zone"}""")
        assertEquals(HttpStatusCode.OK, e2.status); assertEquals(e1.objA1(), e2.objA1())
        assertEquals(sv1 + 1, scopeVersion(sr2)); assertEquals(1, env.audit("route_assignment", aid, "end"))
        assertEquals("ERR_CONFLICT", sendA1(HttpMethod.Post, "/admin/route-assignments/$aid/end", admin, """{"valid_to":"${from.plusDays(21)}","reason":"Trying another end date"}""").code())
        assertEquals(HttpStatusCode.NotFound, sendA1(HttpMethod.Post, "/admin/route-assignments/99999/end", admin, """{"valid_to":"$end","reason":"Unknown assignment id"}""").status)
        assertEquals(HttpStatusCode.Forbidden, sendA1(HttpMethod.Post, "/admin/route-assignments/$aid/end", env.token("tso1001", Role.TSO), """{"valid_to":"$end","reason":"A TSO may not end it"}""").status)
        assertEquals(HttpStatusCode.BadRequest, sendA1(HttpMethod.Post, "/admin/route-assignments/$aid/end", admin, """{"valid_to":"$end"}""").status)
    }
}
