package com.aktcl.aron.backend.masterdata

import com.aktcl.aron.backend.platform.AccessTokenVerifier
import com.aktcl.aron.backend.platform.AronClock
import com.aktcl.aron.backend.platform.AuthGuardDeps
import com.aktcl.aron.backend.platform.PlatformContext
import com.aktcl.aron.backend.platform.RegistryDefaults
import com.aktcl.aron.backend.platform.ScopeVersionLookup
import com.aktcl.aron.backend.platform.ServerConfig
import com.aktcl.aron.backend.platform.installAronPlatform
import com.aktcl.aron.contract.Role
import com.aktcl.aron.rules.BusinessDate
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
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
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.time.Instant
import java.time.LocalDate
import java.util.Date
import java.util.UUID

/** Seeded database plus the F-API-035c / F-API-080 routes, a movable cfg.price.max_change_pct and tokens with permissions (tests only). */
internal class AdminHarness : AutoCloseable {
    val env = SeededAdminDb()

    /** The value `cfg.price.max_change_pct` answers in the tests; a test sets it, the default is the registry's 15. */
    @Volatile var maxPct: JsonElement = JsonPrimitive(15)

    private val base = RegistryDefaults(overrides = mapOf("cfg.api.rl.user_per_min" to JsonPrimitive(5000)))
    val priceConfig = object : ServerConfig {
        override fun value(key: String): JsonElement = if (key == "cfg.price.max_change_pct") maxPct else base.value(key)
        override fun configVersion(): Long = 0
    }

    init {
        env.fresh.dataSource.connection.use { c -> c.createStatement().use { it.execute(PriceBatchSchema.DDL) } }
        env.fresh.db.jdbi.useHandle<Exception> { h ->
            h.execute("INSERT INTO app.app_user (username, full_name, role, locale, pilot, must_change_password) VALUES ('superadmin2001', 'Second Super Admin', 'SUPERADMIN', 'en', true, false), ('admin2001', 'Second Admin', 'ADMIN', 'en', true, false)")
        }
    }

    private val ids: Map<String, Long> = env.fresh.db.jdbi.withHandle<Map<String, Long>, Exception> { h -> h.createQuery("SELECT username, id FROM app.app_user").map { rs, _ -> rs.getString(1) to rs.getLong(2) }.list().toMap() }
    fun id(user: String) = ids.getValue(user)

    fun tok(user: String, role: Role, vararg perms: String): String {
        // wall-clock-ok: token validity window against the app guard, which verifies with the system clock
        val now = Instant.now()
        val c = JWTClaimsSet.Builder().issuer("aron").audience("aron-api").subject(id(user).toString()).claim("uname", user)
            .claim("role", role.wire).claim("sv", 1L).claim("flv", "web").claim("perm", perms.toList()).claim("pii", false)
            .claim("amr", listOf("pwd")).issueTime(Date.from(now)).notBeforeTime(Date.from(now)).expirationTime(Date.from(now.plusSeconds(900)))
            .jwtID(UUID.randomUUID().toString()).build()
        return SignedJWT(JWSHeader.Builder(JWSAlgorithm.ES256).keyID("t").build(), c).apply { sign(TestTokens.keys.signer) }.serialize()
    }

    val admin get() = tok("admin1001", Role.ADMIN)
    val admin2 get() = tok("admin2001", Role.ADMIN)
    val sup1 get() = tok("superadmin1001", Role.SUPERADMIN)
    val sup2 get() = tok("superadmin2001", Role.SUPERADMIN)

    fun app(requirePreview: Boolean = true, block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        application {
            installAronPlatform(PlatformContext(AronClock.SYSTEM, base, { "00000000-0000-0000-0000-000000000000" }))
            val guard = AuthGuardDeps(AccessTokenVerifier(TestTokens.keys), ScopeVersionLookup { 1 }, base)
            routing {
                route("/v1") {
                    adminProductsRoutes(AdminProductsDeps(env.fresh.db, guard))
                    adminPricesRoutes(AdminPricesDeps(env.fresh.db, priceConfig, guard, requirePreview = requirePreview))
                }
            }
        }
        block()
    }

    fun scalar(sql: String): String = env.scalar(sql) ?: error("no row for: $sql")
    fun count(sql: String): Int = scalar(sql).toInt()
    fun exec(sql: String) = env.fresh.db.jdbi.useHandle<Exception> { it.execute(sql) }
    // wall-clock-ok: the app under test runs on AronClock.SYSTEM, so its Dhaka "today" is the real one
    val today: LocalDate get() = BusinessDate.of(System.currentTimeMillis()).toJavaLocalDate()

    /** A fresh SKU under the seeded variant with one open row per price type from 2026-01-01; returns its id. */
    fun newSku(code: String, outlet: Long, cc: Long = outlet, distributor: Long = outlet, reporting: Long = outlet, nto: Long = outlet): Long {
        exec(
            "INSERT INTO app.sku (code, variant_id, category_code, name, short_name, base_unit, base_per_pack, entry_unit_default, report_unit) " +
                "SELECT '$code', (SELECT id FROM app.product_node WHERE level = 'variant' ORDER BY id LIMIT 1), 'cigarette', '$code name', '$code', 'stick', 10, 'stick', 'stick'",
        )
        val id = scalar("SELECT id FROM app.sku WHERE code = '$code'").toLong()
        exec("INSERT INTO app.sku_price (sku_id, price_type, amount_mtk, per_base_qty, valid_from) VALUES ($id,'outlet',$outlet,1,DATE '2026-01-01'),($id,'cc',$cc,1,DATE '2026-01-01'),($id,'distributor',$distributor,1,DATE '2026-01-01'),($id,'reporting',$reporting,1,DATE '2026-01-01'),($id,'nto',$nto,1,DATE '2026-01-01')")
        return id
    }

    override fun close() = env.close()
}

internal suspend fun ApplicationTestBuilder.send(method: HttpMethod, path: String, token: String, body: String? = null, ifMatch: Int? = null): HttpResponse =
    client.request(path) {
        this.method = method
        bearerAuth(token)
        if (body != null) { contentType(ContentType.Application.Json); setBody(body) }
        if (ifMatch != null) header("If-Match", "\"$ifMatch\"")
    }

internal suspend fun HttpResponse.obj(): JsonObject = Json.parseToJsonElement(bodyAsText()).jsonObject
