package com.aktcl.aron.backend.app

import com.aktcl.aron.backend.auth.PasswordHasher
import com.aktcl.aron.backend.masterdata.GeoRepository
import com.aktcl.aron.backend.masterdata.SqlRoutePlanner
import com.aktcl.aron.backend.platform.DbServerConfig
import com.aktcl.aron.backend.platform.FreshDb
import com.aktcl.aron.backend.platform.RegistryDefaults
import com.aktcl.aron.backend.platform.Settings
import com.nimbusds.jwt.SignedJWT
import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import java.io.File
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import java.time.LocalDate
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Day 1 acceptance on the db lane's seed (db/seed, N-008) through the production wiring: F-SYS-001/F-API-001 (seeded
 * SR login), F-SYS-002/F-API-002 (refresh on the seeded dev phone), F-SYS-005 (seeded TSO sees only its territory)
 * and N-017 (seeded Sunday and Friday). The seed commits no password; the test sets a throwaway one.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SeededAcceptanceTest {
    private lateinit var fresh: FreshDb
    private lateinit var wiring: Wiring
    private val password = "throwaway-" + System.nanoTime()
    private val devPhone = "00000000-0000-4000-8000-000000000001"

    @BeforeAll
    fun setUp() {
        fresh = FreshDb.create()
        val seedDir = File(System.getProperty("aron.repoRoot"), "db/seed")
        fresh.dataSource.connection.use { c ->
            seedDir.listFiles { f -> f.name.matches(Regex("0\\d_.*\\.sql")) }!!.sortedBy { it.name }.forEach { f ->
                c.createStatement().use { it.execute(f.readText()) }
            }
        }
        val hash = PasswordHasher().hash(password)
        fresh.db.jdbi.useHandle<Exception> { h ->
            h.createUpdate("UPDATE app.app_user SET password_hash = :h WHERE pilot").bind("h", hash).execute()
            // A second territory with outlets, so "only its territory" is a real filter.
            h.execute("INSERT INTO app.territory (code, name, division_id) SELECT 'T-OTHER', 'Other', division_id FROM app.territory WHERE code = 'T-DHK-N'")
            h.execute("INSERT INTO app.zone (code, name, territory_id) SELECT 'Z-OTHER', 'Other zone', id FROM app.territory WHERE code = 'T-OTHER'")
            h.execute("INSERT INTO app.cluster (zone_id, name) SELECT id, 'Other cluster' FROM app.zone WHERE code = 'Z-OTHER'")
            for (i in 1..5) h.execute("INSERT INTO app.outlet (code, name, owner_name, zone_id, cluster_id, channel) SELECT 'OTH-$i', 'Other $i', 'Owner', z.id, c.id, 'GT' FROM app.zone z JOIN app.cluster c ON c.zone_id = z.id WHERE z.code = 'Z-OTHER'")
        }
        val key = File.createTempFile("aron-jwt", ".pem").apply {
            deleteOnExit()
            val k = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair().private
            writeText("-----BEGIN " + "PRIVATE KEY-----\n" + Base64.getMimeEncoder().encodeToString(k.encoded) + "\n-----END PRIVATE KEY-----\n")
        }
        wiring = Wiring.production(Settings.load(mapOf("ARON_ROLE" to "api", "ARON_DB_URL" to fresh.url, "ARON_JWT_SIGNING_KEY_FILE" to key.absolutePath)))
    }

    @AfterAll
    fun tearDown() { wiring.database?.close(); fresh.close() }

    private fun json(s: String): JsonObject = Json.parseToJsonElement(s).jsonObject

    private suspend fun HttpClient.login(user: String, client: String, device: String?) = post("/v1/auth/login") {
        contentType(ContentType.Application.Json); header("X-App-Version", "1.0.0+1")
        setBody("""{"username":"$user","password":"$password","client":"$client"${device?.let { ",\"device_uuid\":\"$it\"" } ?: ""}}""")
    }

    @Test
    fun theSeededSrLogsInOnTheSeededPhoneAndItsRefreshTokenRotatesWithGraceAndReuseDetection() = testApplication {
        application { aronApi(wiring) }
        val r = client.login("sr1001", "app_sr", devPhone)
        assertEquals(HttpStatusCode.OK, r.status, r.bodyAsText())
        val b = json(r.bodyAsText())
        assertEquals("ok", b["status"]!!.jsonPrimitive.content)
        val claims = SignedJWT.parse(b["access_token"]!!.jsonPrimitive.content).also { assertEquals("ES256", it.header.algorithm.name) }.jwtClaimsSet
        assertEquals("SR", claims.getStringClaim("role"))
        assertEquals(devPhone, claims.getStringClaim("dvu"))
        val scope = b["scope"]!!.jsonObject
        assertEquals(claims.getLongClaim("sv"), scope["scope_version"]!!.jsonPrimitive.long)
        assertEquals(setOf("MIR-SR-D", "MIR-SR-3F", "MIR-SR-2F"), scope["nodes"]!!.jsonArray.map { it.jsonObject["code"]!!.jsonPrimitive.content }.toSet())
        assertEquals(0, b["device"]!!.jsonObject["bind_ordinal"]!!.jsonPrimitive.content.toInt())

        suspend fun refresh(t: String) = client.post("/v1/auth/refresh") {
            contentType(ContentType.Application.Json); header("X-Device-Id", devPhone); setBody("""{"grant":"full","refresh_token":"$t"}""")
        }
        val rt = b["refresh_token"]!!.jsonPrimitive.content
        val first = refresh(rt)
        assertEquals(HttpStatusCode.OK, first.status, first.bodyAsText())
        val rt2 = json(first.bodyAsText())["refresh_token"]!!.jsonPrimitive.content
        assertEquals(rt2, json(refresh(rt).bodyAsText())["refresh_token"]!!.jsonPrimitive.content, "grace replay")
        // Reuse after 60 s: the family is revoked (time moved in the database instead of the clock).
        fresh.db.jdbi.useHandle<Exception> { h -> h.execute("UPDATE app.refresh_token SET used_at = used_at - interval '61 seconds' WHERE used_at IS NOT NULL") }
        assertEquals("ERR_AUTH_REFRESH_REUSED", json(refresh(rt).bodyAsText())["code"]!!.jsonPrimitive.content)
        assertEquals("ERR_AUTH_REFRESH_REUSED", json(refresh(rt2).bodyAsText())["code"]!!.jsonPrimitive.content)

        val wrong = client.post("/v1/auth/login") {
            contentType(ContentType.Application.Json); setBody("""{"username":"sr1001","password":"nope","client":"app_sr","device_uuid":"$devPhone"}""")
        }
        assertEquals("ERR_AUTH_INVALID_CREDENTIALS", json(wrong.bodyAsText())["code"]!!.jsonPrimitive.content)
    }

    @Test
    fun theSeededTsoSeesOnlyOutletsOfItsTerritoryAndClientScopeIdsAreIgnored() = testApplication {
        application { aronApi(wiring) }
        val b = json(client.login("tso1001", "web", null).bodyAsText())
        val token = b["access_token"]!!.jsonPrimitive.content
        val other = fresh.db.jdbi.withHandle<Long, Exception> { h -> h.createQuery("SELECT id FROM app.zone WHERE code = 'Z-OTHER'").mapTo(Long::class.java).one() }
        val territoryZones = fresh.db.jdbi.withHandle<Set<Long>, Exception> { h ->
            h.createQuery("SELECT z.id FROM app.zone z JOIN app.territory t ON t.id = z.territory_id WHERE t.code = 'T-DHK-N'").mapTo(Long::class.java).set()
        }
        val r = json(client.get("/v1/admin/outlets?limit=500&zone_ids=$other&scope_ids=$other") { bearerAuth(token) }.bodyAsText())
        val zones = r["items"]!!.jsonArray.map { it.jsonObject["zone_id"]!!.jsonPrimitive.long }
        assertEquals(60, zones.size, "the 60 seeded outlets")
        assertTrue(zones.all { it in territoryZones })
        assertEquals(JsonNull, r["next_cursor"])
        assertEquals(HttpStatusCode.Forbidden, client.get("/v1/admin/outlets?zone_id=$other") { bearerAuth(token) }.status)
    }

    @Test
    fun onTheSeededSundayDailyAnd3fArePlannedAnd2fIsNotFridayNoneAndTargetIsThePlannedOutlets() {
        val planner = SqlRoutePlanner(fresh.db, GeoRepository(fresh.db), DbServerConfig(fresh.db, RegistryDefaults()))
        val sr = fresh.db.jdbi.withHandle<Long, Exception> { h -> h.createQuery("SELECT id FROM app.app_user WHERE username = 'sr1001'").mapTo(Long::class.java).one() }
        val sunday = LocalDate.parse("2026-10-04"); val friday = LocalDate.parse("2026-10-09")
        assertEquals(listOf("MIR-SR-D", "MIR-SR-3F"), planner.routesFor(sr, sunday).filter { it.plannedToday }.map { it.code }.sorted().reversed().sortedBy { it != "MIR-SR-D" })
        assertEquals(emptyList(), planner.routesFor(sr, friday).filter { it.plannedToday })
        assertEquals(40, planner.targetOutlets(sr, sunday))
        assertEquals(0, planner.targetOutlets(sr, friday))
    }
}
