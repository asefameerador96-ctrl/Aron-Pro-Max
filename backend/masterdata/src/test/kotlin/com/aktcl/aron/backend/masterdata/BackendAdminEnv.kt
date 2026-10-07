package com.aktcl.aron.backend.masterdata

import com.aktcl.aron.backend.platform.AccessTokenVerifier
import com.aktcl.aron.backend.platform.AronClock
import com.aktcl.aron.backend.platform.AuthGuardDeps
import com.aktcl.aron.backend.platform.Database
import com.aktcl.aron.backend.platform.PlatformContext
import com.aktcl.aron.backend.platform.RegistryDefaults
import com.aktcl.aron.backend.platform.ScopeVersionLookup
import com.aktcl.aron.backend.platform.ServerConfig
import com.aktcl.aron.backend.platform.installAronPlatform
import com.aktcl.aron.contract.Role
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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant
import java.util.Date
import java.util.UUID

/** Fake Blob issuer: no Azure SDK, no secret; records every write URL it hands out. */
class FakeBlob : BlobSasIssuer {
    val writes = mutableListOf<Triple<String, Long, Instant>>()
    override fun writeSas(blobPath: String, maxBytes: Long, expiresAt: Instant): String {
        writes += Triple(blobPath, maxBytes, expiresAt)
        return "https://blob.test/$blobPath?sp=w&max=$maxBytes&exp=${expiresAt.epochSecond}&sig=fake${writes.size}"
    }
    override fun readUrl(blobPath: String) = "https://blob.test/$blobPath"
}

/**
 * The db lane's seed plus what the backend-admin rows need: the proposed V0014 tables, a second division with its own TSO and DMO
 * (a caller genuinely outside the first division's reach), an AMO route, an outlet and a route outside the seeded zone, and the
 * leave_type and feedback_category code lists.
 */
class BackendAdminEnv : AutoCloseable {
    val seeded = SeededAdminDb()
    val db: Database = seeded.fresh.db
    val blob = FakeBlob()
    val ids: Map<String, Long>
    val routeIds: Map<String, Long>

    init {
        val ddl = BackendAdminEnv::class.java.getResource("/proposed_V0014_backend_admin.sql")!!.readText()
        seeded.fresh.dataSource.connection.use { c -> c.createStatement().use { it.execute(ddl) } }
        db.jdbi.useHandle<Exception> { h ->
            h.execute("INSERT INTO app.division (code, name, wing_id) SELECT 'D-FAR', 'Far division', wing_id FROM app.division WHERE code = 'D-DHK'")
            h.execute("INSERT INTO app.territory (code, name, division_id) SELECT 'T-FAR', 'Far territory', id FROM app.division WHERE code = 'D-FAR'")
            h.execute("INSERT INTO app.zone (code, name, territory_id) SELECT 'Z-FAR', 'Far zone', id FROM app.territory WHERE code = 'T-FAR'")
            for ((u, role, type, code) in listOf(listOf("tso3001", "TSO", "territory", "T-FAR"), listOf("dmo3001", "DMO", "division", "D-FAR"))) {
                h.execute("INSERT INTO app.app_user (username, full_name, role, locale, home_zone_id, pilot, must_change_password) SELECT '$u', '$u', '$role', 'en', z.id, true, false FROM app.zone z WHERE z.code = 'Z-FAR'")
                h.execute("INSERT INTO app.user_scope (user_id, node_type, node_id, valid_from) SELECT u.id, '$type', n.id, date '2026-01-01' FROM app.app_user u, app.$type n WHERE u.username = '$u' AND n.code = '$code'")
            }
            // Routes and outlets outside the seeded zone: one in Z-OTHER (tso2001), one in Z-FAR (tso3001); an AMO route in Z-MIR.
            h.execute("INSERT INTO app.route (code, name, zone_id, kind, visit_kind, visit_days_mask) SELECT 'OTH-SR-1', 'Other route', id, 'sr', 'daily', 127 FROM app.zone WHERE code = 'Z-OTHER'")
            h.execute("INSERT INTO app.route (code, name, zone_id, kind, visit_kind, visit_days_mask) SELECT 'FAR-SR-1', 'Far route', id, 'sr', 'daily', 127 FROM app.zone WHERE code = 'Z-FAR'")
            h.execute("INSERT INTO app.route (code, name, zone_id, kind, visit_kind, visit_days_mask) SELECT 'MIR-AMO-1', 'Mirpur AMO route', id, 'amo', 'daily', 127 FROM app.zone WHERE code = 'Z-MIR'")
            for (z in listOf("Z-OTHER", "Z-FAR")) {
                h.execute("INSERT INTO app.cluster (zone_id, name) SELECT id, 'C-$z' FROM app.zone WHERE code = '$z'")
                h.execute(
                    "INSERT INTO app.outlet (code, name, owner_name, zone_id, route_id, cluster_id, channel) SELECT 'X-$z', 'Outlet $z', 'Owner', z.id, r.id, c.id, 'GT' FROM app.zone z " +
                        "JOIN app.cluster c ON c.zone_id = z.id JOIN app.route r ON r.zone_id = z.id WHERE z.code = '$z'",
                )
            }
            h.execute("INSERT INTO app.route_assignment (route_id, user_id, kind, valid_from, reason) SELECT r.id, u.id, 'primary', date '2026-01-01', 'seed' FROM app.route r, app.app_user u WHERE r.code = 'MIR-AMO-1' AND u.username = 'amo1001'")
            h.execute("INSERT INTO app.route_assignment (route_id, user_id, kind, valid_from, reason) SELECT r.id, u.id, 'primary', date '2026-01-01', 'seed' FROM app.route r, app.app_user u WHERE r.code = 'OTH-SR-1' AND u.username = 'sr2001'")
            h.execute("INSERT INTO app.code_list_item (list_key, code, label_en) VALUES ('leave_type', 'casual', 'Casual'), ('leave_type', 'sick', 'Sick'), ('feedback_category', 'app_issue', 'App issue'), ('feedback_category', 'idea', 'Idea')")
        }
        ids = db.jdbi.withHandle<Map<String, Long>, Exception> { h -> h.createQuery("SELECT username, id FROM app.app_user").map { rs, _ -> rs.getString(1) to rs.getLong(2) }.list().toMap() }
        routeIds = db.jdbi.withHandle<Map<String, Long>, Exception> { h -> h.createQuery("SELECT code, id FROM app.route").map { rs, _ -> rs.getString(1) to rs.getLong(2) }.list().toMap() }
    }

    fun scalar(sql: String): String? = seeded.scalar(sql)
    fun count(sql: String): Int = scalar(sql)!!.toInt()
    fun exec(sql: String) = db.jdbi.useHandle<Exception> { it.execute(sql) }
    fun outletIds(routeCode: String, n: Int): List<Long> =
        db.jdbi.withHandle<List<Long>, Exception> { h -> h.createQuery("SELECT o.id FROM app.outlet o JOIN app.route r ON r.id = o.route_id WHERE r.code = :c ORDER BY o.id LIMIT :n").bind("c", routeCode).bind("n", n).mapTo(Long::class.java).list() }

    val roles = mapOf(
        "sr1001" to Role.SR, "amo1001" to Role.AMO, "tso1001" to Role.TSO, "tso2001" to Role.TSO, "tso3001" to Role.TSO, "dmo1001" to Role.DMO, "dmo3001" to Role.DMO,
        "admin1001" to Role.ADMIN, "superadmin1001" to Role.SUPERADMIN, "support1001" to Role.SUPPORT, "sr2001" to Role.SR,
    )

    fun tok(user: String): String = TestTokens.web(ids.getValue(user), roles.getValue(user))

    /** A phone token bound to [device]. */
    fun phone(user: String, device: String): String {
        val now = Instant.now()
        val c = JWTClaimsSet.Builder().issuer("aron").audience("aron-api").subject(ids.getValue(user).toString()).claim("uname", user)
            .claim("role", roles.getValue(user).wire).claim("sv", 1L).claim("flv", roles.getValue(user).wire.lowercase()).claim("did", 1L).claim("dvu", device).claim("perm", emptyList<String>())
            .claim("pii", false).claim("amr", listOf("pwd", "device")).issueTime(Date.from(now)).notBeforeTime(Date.from(now)).expirationTime(Date.from(now.plusSeconds(900))).jwtID(UUID.randomUUID().toString()).build()
        return SignedJWT(JWSHeader.Builder(JWSAlgorithm.ES256).keyID("t").build(), c).apply { sign(TestTokens.keys.signer) }.serialize()
    }

    fun config(overrides: Map<String, JsonElement> = emptyMap()): ServerConfig =
        RegistryDefaults(overrides = mapOf("cfg.api.rl.user_per_min" to JsonPrimitive(100000), "cfg.api.rl.device_per_min" to JsonPrimitive(100000)) + overrides)

    fun app(overrides: Map<String, JsonElement> = emptyMap(), block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        val cfg = config(overrides)
        application {
            installAronPlatform(PlatformContext(AronClock.SYSTEM, cfg, { "00000000-0000-0000-0000-000000000000" }))
            val guard = AuthGuardDeps(AccessTokenVerifier(TestTokens.keys), ScopeVersionLookup { 1 }, cfg)
            val reach = SqlReachResolver(db, GeoRepository(db))
            routing {
                route("/v1") {
                    visitPlanRoutes(VisitPlanDeps(db, reach, cfg, guard))
                    routeAssignmentRoutes(RouteAssignmentsDeps(db, reach, guard))
                    leaveRoutes(LeaveDeps(db, reach, cfg, guard))
                    tutorialRoutes(TutorialsDeps(db, blob, guard))
                    supportUploadRoutes(SupportUploadDeps(db, blob, cfg, guard))
                    feedbackRoutes(FeedbackDeps(db, reach, cfg, guard))
                    adminContentRoutes(AdminContentDeps(db, blob, cfg, guard))
                }
            }
        }
        block()
    }

    override fun close() = seeded.close()
}

suspend fun ApplicationTestBuilder.sendA3(method: HttpMethod, path: String, token: String?, body: String? = null, headers: Map<String, String> = emptyMap()): HttpResponse =
    client.request(path) {
        this.method = method
        token?.let { bearerAuth(it) }
        headers.forEach { (k, v) -> header(k, v) }
        if (body != null) { contentType(ContentType.Application.Json); setBody(body) }
    }

suspend fun HttpResponse.objA3(): JsonObject = Json.parseToJsonElement(bodyAsText()).jsonObject
fun JsonElement.strA3(key: String): String = jsonObject.getValue(key).jsonPrimitive.content
fun JsonObject.itemsA3() = getValue("items").jsonArray.map { it.jsonObject }
