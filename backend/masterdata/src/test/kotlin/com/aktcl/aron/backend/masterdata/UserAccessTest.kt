package com.aktcl.aron.backend.masterdata

import com.aktcl.aron.contract.Role
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** `GET/PUT /v1/admin/users/{id}/access` (docs/34 s1): ADMIN module grants and BRAND brand scopes, versioned, SUPERADMIN-only writes, audited. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class UserAccessTest {
    private lateinit var env: AdminEnv

    @BeforeAll fun setUp() {
        env = AdminEnv()
        applyPending(env)
        env.exec("INSERT INTO app.app_user (username, full_name, role, locale, pilot, must_change_password) VALUES ('adminx1', 'Admin X', 'ADMIN', 'en', true, false), ('brandx1', 'Brand X', 'BRAND', 'en', true, false), ('adminx2', 'Admin Y', 'ADMIN', 'en', true, false)")
        env.exec("INSERT INTO app.product_node (level, name) VALUES ('category', 'UA Category')")
        env.exec("INSERT INTO app.product_node (level, parent_id, name) SELECT 'segment', id, 'UA Segment' FROM app.product_node WHERE name = 'UA Category'")
        env.exec("INSERT INTO app.product_node (level, parent_id, name) SELECT 'brand', id, n FROM app.product_node, (VALUES ('UA Brand 1'), ('UA Brand 2'), ('UA Brand 3')) AS b(n) WHERE name = 'UA Segment'")
    }
    @AfterAll fun tearDown() = env.close()

    private fun applyPending(e: AdminEnv) {
        val sql = UserAccessTest::class.java.getResourceAsStream("/pending-user-access.sql")!!.bufferedReader().readText()
        e.db.fresh.dataSource.connection.use { c -> c.createStatement().use { it.execute(sql) } }
    }

    private val super_ get() = env.token("superadmin1001", Role.SUPERADMIN)
    private fun uid(name: String) = env.sql("SELECT id FROM app.app_user WHERE username = '$name'")!!.toLong()
    private fun brand(n: Int) = env.sql("SELECT id FROM app.product_node WHERE name = 'UA Brand $n'")!!.toLong()
    private fun version(name: String) = env.sql("SELECT scope_version FROM app.app_user WHERE username = '$name'")!!.toInt()
    private val reason = "Narrowed to what the role needs"

    private suspend fun io.ktor.server.testing.ApplicationTestBuilder.put(user: String, body: String, token: String = super_, v: Any? = version(user)) =
        sendA1(HttpMethod.Put, "/admin/users/${uid(user)}/access", token, body, ifMatch = v)

    @Test
    fun getReturnsTheAccessAndTheVersionAsETag() = env.app {
        val r = sendA1(HttpMethod.Get, "/admin/users/${uid("adminx1")}/access", super_)
        assertEquals(HttpStatusCode.OK, r.status)
        assertEquals("\"${version("adminx1")}\"", r.headers["ETag"])
        val o = r.objA1()
        assertEquals("ADMIN", o.strA1("role")); assertEquals(0, (o.getValue("modules") as JsonObject).size); assertEquals(0, o.arr("brand_ids").size)
        assertEquals(HttpStatusCode.NotFound, sendA1(HttpMethod.Get, "/admin/users/9999999/access", super_).status)
    }

    @Test
    fun superadminSetsModulesWithIfMatchAuditAndVersionBump() = env.app {
        val v0 = version("adminx1")
        val r = put("adminx1", """{"modules":{"outlets":"w","reports":"r"},"change_reason":"$reason"}""")
        assertEquals(HttpStatusCode.OK, r.status)
        val o = r.objA1()
        assertEquals((v0 + 1).toLong(), o.lng("version")); assertEquals("\"${v0 + 1}\"", r.headers["ETag"])
        assertEquals("w", (o.getValue("modules") as JsonObject).strA1("outlets")); assertEquals("r", (o.getValue("modules") as JsonObject).strA1("reports"))
        assertEquals(v0 + 1, version("adminx1"))
        assertEquals(1, env.audit("user_access", uid("adminx1"), "set_modules"))
        assertEquals(reason, env.sql("SELECT reason FROM app.audit_log WHERE entity = 'user_access' AND entity_id = '${uid("adminx1")}' ORDER BY id DESC LIMIT 1"))
        assertEquals(uid("superadmin1001").toString(), env.sql("SELECT DISTINCT granted_by FROM app.user_module_grant WHERE user_id = ${uid("adminx1")}"))
        // A replace, not a merge; the old version is now stale.
        assertEquals(HttpStatusCode.PreconditionFailed, put("adminx1", """{"modules":{"config":"r"},"change_reason":"$reason"}""", v = v0).status)
        val r2 = put("adminx1", """{"modules":{"config":"r"},"change_reason":"$reason"}""")
        assertEquals(HttpStatusCode.OK, r2.status)
        assertEquals(setOf("config"), (r2.objA1().getValue("modules") as JsonObject).keys)
        assertEquals(HttpStatusCode.OK, put("adminx1", """{"modules":{},"change_reason":"$reason"}""").status)
        assertEquals("0", env.sql("SELECT count(*) FROM app.user_module_grant WHERE user_id = ${uid("adminx1")}"))
    }

    @Test
    fun brandScopeIsSetReplacedAndMustNameRealBrands() = env.app {
        val ok = put("brandx1", """{"brand_ids":[${brand(2)},${brand(1)}],"change_reason":"$reason"}""")
        assertEquals(HttpStatusCode.OK, ok.status)
        assertEquals(listOf(brand(1), brand(2)), ok.objA1().arr("brand_ids").map { it.toString().toLong() })
        assertEquals(1, env.audit("user_access", uid("brandx1"), "set_brands"))
        assertEquals(HttpStatusCode.OK, put("brandx1", """{"brand_ids":[${brand(3)}],"change_reason":"$reason"}""").status)
        assertEquals("1", env.sql("SELECT count(*) FROM app.user_brand_scope WHERE user_id = ${uid("brandx1")}"))
        val category = env.sql("SELECT id FROM app.product_node WHERE name = 'UA Category'")
        val notBrand = put("brandx1", """{"brand_ids":[$category],"change_reason":"$reason"}""")
        assertEquals(HttpStatusCode.BadRequest, notBrand.status); assertTrue(notBrand.bodyAsText().contains("body.brand_ids/0") && notBrand.bodyAsText().contains("unknown"))
        assertEquals(HttpStatusCode.BadRequest, put("brandx1", """{"brand_ids":[999999],"change_reason":"$reason"}""").status)
        val empty = put("brandx1", """{"brand_ids":[],"change_reason":"$reason"}""")
        assertEquals(HttpStatusCode.BadRequest, empty.status); assertTrue(empty.bodyAsText().contains("required"))
        assertEquals(HttpStatusCode.BadRequest, put("brandx1", """{"brand_ids":[${brand(1)},${brand(1)}],"change_reason":"$reason"}""").status)
        assertEquals("1", env.sql("SELECT count(*) FROM app.user_brand_scope WHERE user_id = ${uid("brandx1")}"), "a refused write changes nothing")
    }

    @Test
    fun bodyRulesRoleMismatchAndReason() = env.app {
        suspend fun code(user: String, body: String): String { val r = put(user, body); assertEquals(HttpStatusCode.BadRequest, r.status, body); return r.bodyAsText() }
        assertTrue(code("brandx1", """{"modules":{"outlets":"r"},"change_reason":"$reason"}""").contains("role_mismatch"))
        assertTrue(code("adminx1", """{"brand_ids":[${brand(1)}],"change_reason":"$reason"}""").contains("role_mismatch"))
        assertTrue(code("adminx1", """{"modules":{"nonsense":"r"},"change_reason":"$reason"}""").contains("unknown_member"))
        assertTrue(code("adminx1", """{"modules":{"outlets":"x"},"change_reason":"$reason"}""").contains("body.modules/outlets"))
        code("adminx1", """{"modules":{"outlets":"r"},"brand_ids":[${brand(1)}],"change_reason":"$reason"}""")
        code("adminx1", """{"change_reason":"$reason"}""")
        code("adminx1", """{"modules":{"outlets":"r"}}""")
        code("adminx1", """{"modules":{"outlets":"r"},"change_reason":"short"}""")
        code("adminx1", """{"modules":{"outlets":"r"},"change_reason":"$reason","extra":1}""")
        val noIfMatch = sendA1(HttpMethod.Put, "/admin/users/${uid("adminx1")}/access", super_, """{"modules":{"outlets":"r"},"change_reason":"$reason"}""")
        assertEquals(HttpStatusCode.BadRequest, noIfMatch.status)
        assertEquals(HttpStatusCode.NotFound, sendA1(HttpMethod.Put, "/admin/users/9999999/access", super_, """{"modules":{},"change_reason":"$reason"}""", ifMatch = 1).status)
        // A user of another role (TSO) has no grants at all.
        val tso = sendA1(HttpMethod.Put, "/admin/users/${uid("tso1001")}/access", super_, """{"modules":{"outlets":"r"},"change_reason":"$reason"}""", ifMatch = version("tso1001"))
        assertEquals(HttpStatusCode.BadRequest, tso.status); assertTrue(tso.bodyAsText().contains("role_mismatch"))
    }

    @Test
    fun roleMatrix() = env.app {
        val body = """{"modules":{"outlets":"r"},"change_reason":"$reason"}"""
        for ((user, role) in listOf("admin1001" to Role.ADMIN, "tso1001" to Role.TSO, "dmo1001" to Role.DMO, "support1001" to Role.SUPPORT)) {
            assertEquals(HttpStatusCode.Forbidden, put("adminx2", body, env.token(user, role)).status, "PUT $user")
        }
        assertEquals("0", env.sql("SELECT count(*) FROM app.user_module_grant WHERE user_id = ${uid("adminx2")}"))
        for ((user, role) in listOf("tso1001" to Role.TSO, "dmo1001" to Role.DMO, "support1001" to Role.SUPPORT)) {
            assertEquals(HttpStatusCode.Forbidden, sendA1(HttpMethod.Get, "/admin/users/${uid("adminx2")}/access", env.token(user, role)).status, "GET $user")
        }
        // An ADMIN reads its own access and a non-admin user's, never another ADMIN's.
        assertEquals(HttpStatusCode.OK, sendA1(HttpMethod.Get, "/admin/users/${uid("admin1001")}/access", env.token("admin1001", Role.ADMIN)).status)
        assertEquals(HttpStatusCode.Forbidden, sendA1(HttpMethod.Get, "/admin/users/${uid("adminx2")}/access", env.token("admin1001", Role.ADMIN)).status)
        assertEquals(HttpStatusCode.OK, sendA1(HttpMethod.Get, "/admin/users/${uid("brandx1")}/access", env.token("admin1001", Role.ADMIN)).status)
    }

    @Test
    fun answers503WhileTheTablesAreNotDeployed() {
        val bare = AdminEnv()
        try {
            bare.app {
                val id = bare.sql("SELECT id FROM app.app_user WHERE username = 'admin1001'")!!
                assertEquals(HttpStatusCode.ServiceUnavailable, sendA1(HttpMethod.Get, "/admin/users/$id/access", bare.token("superadmin1001", Role.SUPERADMIN)).status)
                assertEquals(HttpStatusCode.ServiceUnavailable, sendA1(HttpMethod.Put, "/admin/users/$id/access", bare.token("superadmin1001", Role.SUPERADMIN), """{"modules":{},"change_reason":"$reason"}""", ifMatch = 1).status)
            }
        } finally { bare.close() }
    }
}
