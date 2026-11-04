package com.aktcl.aron.backend.app

import com.aktcl.aron.backend.auth.PasswordHasher
import com.aktcl.aron.backend.platform.AronClock
import com.aktcl.aron.backend.platform.FreshDb
import com.aktcl.aron.backend.platform.Settings
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import java.io.File
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import java.time.Instant
import java.util.Base64
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * D-ROLES-02 rows 2 and 6 over the production wiring (docs/34 s1): an ADMIN's web token carries `modules` from
 * `app.user_module_grant`, every admin route checks its module and access (403 otherwise), a grant change reaches the
 * next token through the scope version (401 ERR_SCOPE_CHANGED, then the refresh carries the new grants), SUPERADMIN holds
 * every module, and a BRAND token carries `brands`.
 */
class ModuleGrantsTest {
    private lateinit var fresh: FreshDb
    private lateinit var wiring: Wiring
    private val now = AtomicReference(Instant.now())   // wall-clock-ok: the config rows are dated by the database's now()
    private val clock = AronClock { now.get() }
    private val password = "Admin-pass-00001"
    private val ids = mutableMapOf<String, Long>()

    @BeforeEach
    fun setUp() {
        fresh = FreshDb.create()
        val key = File.createTempFile("aron-jwt", ".pem").apply {
            deleteOnExit()
            val k = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair().private
            writeText("-----BEGIN " + "PRIVATE KEY-----\n" + Base64.getMimeEncoder().encodeToString(k.encoded) + "\n-----END PRIVATE KEY-----\n")
        }
        fresh.db.jdbi.useHandle<Exception> { h ->
            val hash = PasswordHasher().hash(password)
            for ((name, role) in listOf("adminm1" to "ADMIN", "superm1" to "SUPERADMIN", "brandm1" to "BRAND")) {
                ids[name] = h.createQuery("INSERT INTO app.app_user (username, full_name, role, password_hash, must_change_password) VALUES (:n, 'Test', :r, :p, false) RETURNING id")
                    .bind("n", name).bind("r", role).bind("p", hash).mapTo(Long::class.java).one()
            }
            h.execute("INSERT INTO app.cfg_value (key, scope_type, scope_id, value, effective_from, config_version, reason) SELECT 'cfg.auth.mfa_required_roles', 'global', 0, '[]'::jsonb, now() - interval '1 day', max(config_version), 'test: MFA off' FROM app.cfg_version")
            h.createUpdate("INSERT INTO app.user_module_grant (user_id, module, access) VALUES (:u, 'users', 'r'), (:u, 'prices', 'w')").bind("u", ids["adminm1"]).execute()
            val brand = h.createQuery("SELECT id FROM app.product_node WHERE level = 'brand' ORDER BY id LIMIT 1").mapTo(Long::class.java).findOne().orElse(null)
                ?: h.createQuery(
                    """WITH c AS (INSERT INTO app.product_node (level, code, name) VALUES ('category', 'TC1', 'Test Category') RETURNING id),
                            s AS (INSERT INTO app.product_node (level, parent_id, code, name) SELECT 'segment', id, 'TS1', 'Test Segment' FROM c RETURNING id)
                       INSERT INTO app.product_node (level, parent_id, code, name) SELECT 'brand', id, 'TB1', 'Test Brand' FROM s RETURNING id""",
                ).mapTo(Long::class.java).one()
            ids["brand"] = brand
            h.createUpdate("INSERT INTO app.user_brand_scope (user_id, brand_id) VALUES (:u, :b)").bind("u", ids["brandm1"]).bind("b", brand).execute()
        }
        wiring = Wiring.production(Settings.load(mapOf("ARON_ROLE" to "api", "ARON_DB_URL" to fresh.url, "ARON_JWT_SIGNING_KEY_FILE" to key.absolutePath)), clock)
    }

    @AfterEach
    fun tearDown() { wiring.securityStore?.close(); wiring.database?.close(); fresh.close() }

    private fun json(r: String): JsonObject = Json.parseToJsonElement(r.ifBlank { "{}" }).jsonObject
    private fun claims(token: String) = Json.parseToJsonElement(String(Base64.getUrlDecoder().decode(token.split('.')[1]))).jsonObject

    private suspend fun ApplicationTestBuilder.login(user: String): JsonObject {
        val r = client.post("/v1/auth/login") { contentType(ContentType.Application.Json); setBody("""{"username":"$user","password":"$password","client":"web"}""") }
        return json(r.bodyAsText()).also { assertEquals("ok", it["status"]?.jsonPrimitive?.content, it.toString()) }
    }

    private suspend fun ApplicationTestBuilder.get(path: String, token: String): HttpResponse = client.get(path) { bearerAuth(token) }

    @Test
    fun anAdminIsHeldToItsModulesAndAGrantChangeReachesTheNextToken() = testApplication {
        application { aronApi(wiring) }
        val login = login("adminm1")
        val token = login["access_token"]!!.jsonPrimitive.content
        assertEquals(mapOf("users" to "r", "prices" to "w"), claims(token)["modules"]!!.jsonObject.mapValues { it.value.jsonPrimitive.content })
        val me = json(get("/v1/me", token).bodyAsText())
        assertEquals(mapOf("users" to "r", "prices" to "w"), me["modules"]!!.jsonObject.mapValues { it.value.jsonPrimitive.content })
        assertEquals(JsonNull, me["brands"])

        // users: read only.
        assertEquals(HttpStatusCode.OK, get("/v1/admin/users", token).status)
        val patch = client.patch("/v1/admin/users/${ids["brandm1"]}") { bearerAuth(token); contentType(ContentType.Application.Json); setBody("""{"full_name":"X","change_reason":"test"}""") }
        assertEquals(HttpStatusCode.Forbidden, patch.status, patch.bodyAsText())
        assertEquals("read-only access to module users", json(patch.bodyAsText())["detail"]?.jsonPrimitive?.content)
        // prices: write; products, config: none.
        assertNotEquals(HttpStatusCode.Forbidden, get("/v1/admin/prices", token).status)
        for (path in listOf("/v1/admin/skus", "/v1/admin/config/values", "/v1/admin/%73kus")) {
            val r = get(path, token)
            assertEquals(HttpStatusCode.Forbidden, r.status, "$path ${r.bodyAsText()}")
        }

        // A SUPERADMIN widens the grant: the write bumps the scope version, the old token refreshes, the new one carries it.
        fresh.db.jdbi.useHandle<Exception> { h ->
            h.createUpdate("INSERT INTO app.user_module_grant (user_id, module, access) VALUES (:u, 'products', 'r')").bind("u", ids["adminm1"]).execute()
            h.createUpdate("UPDATE app.app_user SET scope_version = scope_version + 1 WHERE id = :u").bind("u", ids["adminm1"]).execute()
        }
        now.set(now.get().plusSeconds(11))   // past the 10 s scope-version cache
        val stale = get("/v1/admin/skus", token)
        assertEquals(HttpStatusCode.Unauthorized, stale.status, stale.bodyAsText())
        assertEquals("ERR_SCOPE_CHANGED", json(stale.bodyAsText())["code"]?.jsonPrimitive?.content)
        val again = login("adminm1")["access_token"]!!.jsonPrimitive.content
        assertEquals(HttpStatusCode.OK, get("/v1/admin/skus", again).status)
    }

    @Test
    fun superadminHoldsEveryModuleAndBrandCarriesItsBrands() = testApplication {
        application { aronApi(wiring) }
        val sup = login("superm1")["access_token"]!!.jsonPrimitive.content
        assertEquals(null, claims(sup)["modules"])
        val me = json(get("/v1/me", sup).bodyAsText())
        assertEquals(com.aktcl.aron.backend.platform.AdminModules.ALL.size, me["modules"]!!.jsonObject.size) // tso-manual B1: 16 with support (R-F-11)
        assertEquals(16, me["modules"]!!.jsonObject.size)
        assertEquals(setOf("w"), me["modules"]!!.jsonObject.values.map { it.jsonPrimitive.content }.toSet())
        assertEquals(HttpStatusCode.OK, get("/v1/admin/skus", sup).status)

        val brand = login("brandm1")["access_token"]!!.jsonPrimitive.content
        assertEquals(listOf(ids["brand"]), claims(brand)["brands"]!!.jsonArray.map { it.jsonPrimitive.content.toLong() })
        val bme = json(get("/v1/me", brand).bodyAsText())
        assertEquals(listOf(ids["brand"]), bme["brands"]!!.jsonArray.map { it.jsonPrimitive.content.toLong() })
        assertEquals(JsonNull, bme["modules"])
    }
}
