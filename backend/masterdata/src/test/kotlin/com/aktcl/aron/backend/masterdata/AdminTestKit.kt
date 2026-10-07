package com.aktcl.aron.backend.masterdata

import com.aktcl.aron.backend.platform.AccessTokenVerifier
import com.aktcl.aron.backend.platform.AronClock
import com.aktcl.aron.backend.platform.AuthGuardDeps
import com.aktcl.aron.backend.platform.PlatformContext
import com.aktcl.aron.backend.platform.RegistryDefaults
import com.aktcl.aron.backend.platform.ScopeVersionLookup
import com.aktcl.aron.backend.platform.installAronPlatform
import com.aktcl.aron.contract.Role
import com.aktcl.aron.rules.BusinessDate
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.datetime.toJavaLocalDate
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.LocalDate

/** A seeded database plus the `/v1/admin` master-data routes under test (tests of F-API-035, 035b, 045). */
class AdminEnv : AutoCloseable {
    val db = SeededAdminDb()
    val today: LocalDate = BusinessDate.of(System.currentTimeMillis()).toJavaLocalDate()

    fun app(requireReason: Boolean = false, block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        val cfg = RegistryDefaults(overrides = mapOf("cfg.api.rl.user_per_min" to JsonPrimitive(5000)))
        application {
            installAronPlatform(PlatformContext(AronClock.SYSTEM, cfg, { "00000000-0000-0000-0000-000000000000" }))
            val guard = AuthGuardDeps(AccessTokenVerifier(TestTokens.keys), ScopeVersionLookup { 1 }, cfg)
            val geo = GeoRepository(db.fresh.db)
            val deps = AdminMasterDeps(db.fresh.db, geo, SqlReachResolver(db.fresh.db, geo), guard, { "argon2id\$test\$" + it.reversed() }, requireReason = requireReason)
            routing { route("/v1") { adminMasterRoutes(deps) } }
        }
        block()
    }

    fun token(user: String, role: Role, pii: Boolean = false) = TestTokens.web(db.ids.getValue(user), role, pii = pii)

    fun sql(q: String): String? = db.scalar(q)
    fun count(q: String): Long = db.scalar(q)!!.toLong()
    fun audit(entity: String, id: Any, action: String? = null): Long =
        count("SELECT count(*) FROM app.audit_log WHERE entity = '$entity' AND entity_id = '$id'" + (action?.let { " AND action = '$it'" } ?: ""))

    override fun close() = db.close()
}

suspend fun ApplicationTestBuilder.sendA1(
    method: HttpMethod, path: String, token: String, body: String? = null, ifMatch: Any? = null,
): HttpResponse = client.request("/v1$path") {
    this.method = method
    bearerAuth(token)
    if (ifMatch != null) header("If-Match", "\"$ifMatch\"")
    if (body != null) { contentType(ContentType.Application.Json); setBody(body) }
}

fun json(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

suspend fun HttpResponse.objA1(): JsonObject = json(bodyAsText())

fun JsonObject.strA1(k: String): String? = this[k]?.takeIf { it !is kotlinx.serialization.json.JsonNull }?.jsonPrimitive?.content
fun JsonObject.lng(k: String): Long = this.getValue(k).jsonPrimitive.content.toLong()
fun JsonObject.itemsA1(): List<JsonObject> = getValue("items").jsonArray.map { it.jsonObject }
fun JsonObject.arr(k: String): JsonArray = getValue(k).jsonArray

/** Problem code of an `application/problem+json` answer. */
suspend fun HttpResponse.code(): String = objA1().strA1("code")!!
