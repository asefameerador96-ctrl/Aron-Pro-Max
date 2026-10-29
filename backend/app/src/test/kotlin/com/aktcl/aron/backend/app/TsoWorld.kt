package com.aktcl.aron.backend.app

import com.aktcl.aron.backend.auth.PasswordHasher
import com.aktcl.aron.backend.notify.PushSender
import com.aktcl.aron.backend.platform.AronClock
import com.aktcl.aron.backend.platform.FreshDb
import com.aktcl.aron.backend.platform.RecordHandler
import com.aktcl.aron.backend.platform.Settings
import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.ByteArrayContent
import io.ktor.http.contentType
import io.ktor.server.testing.TestApplication
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals

/**
 * tso-manual B3: the world of the lane's backend:app tests, through the PRODUCTION wiring (as [ApiWorld], plus a push
 * recorder in place of FCM and a movable clock): a migrated database with the db lane's seed, optional mirrored
 * divisions ([ApiWorld.mirrored]: division D-<TAG>, territory T-<TAG>, zone Z-<TAG>, routes <TAG>-SR-*, users
 * `<tag>sr1001`, `<tag>tso1001`, ...), the per-minute limits lifted and TOTP off for the web roles. Phones are created
 * with [phone] (a bound, active device with the seed's placeholder key, so no device proof is asked).
 */
class TsoWorld(
    repoRoot: File,
    mirrors: List<String> = emptyList(),
    start: Instant = Instant.parse("2027-01-03T04:00:00Z"),
    extraRecordHandlers: List<RecordHandler> = emptyList(),
) : AutoCloseable {
    val fresh: FreshDb = FreshDb.create()
    val now = AtomicReference(start)
    val clock = AronClock { now.get() }
    val password = "throwaway-" + System.nanoTime()
    /** Every push the API sent: the FCM token and the data map. */
    val pushes = CopyOnWriteArrayList<Pair<String, Map<String, String>>>()
    val wiring: Wiring
    private val tokens = ConcurrentHashMap<String, String>()
    private val switches = AtomicInteger(0)

    init {
        val seed = repoRoot.resolve("db/seed").listFiles { f -> f.name.matches(Regex("0\\d_.*\\.sql")) }!!.sortedBy { it.name }
        fresh.dataSource.connection.use { c ->
            seed.forEach { f -> c.createStatement().use { it.execute(f.readText()) } }
            for (tag in mirrors) listOf("01_geography_people.sql", "03_outlets_and_plans.sql").forEach { n ->
                c.createStatement().use { it.execute(ApiWorld.mirrored(repoRoot.resolve("db/seed/$n").readText(), tag)) }
            }
        }
        fresh.db.jdbi.useHandle<Exception> { h ->
            h.createUpdate("UPDATE app.app_user SET password_hash = :h, must_change_password = false WHERE pilot").bind("h", PasswordHasher().hash(password)).execute()
            for ((k, v) in listOf("cfg.api.rl.device_per_min" to "100000", "cfg.api.rl.user_per_min" to "100000", "cfg.auth.mfa_required_roles" to "[]")) {
                h.execute("INSERT INTO app.cfg_value (key, scope_type, scope_id, value, effective_from, config_version, reason) SELECT '$k', 'global', 0, '$v'::jsonb, TIMESTAMPTZ '${ApiWorld.CFG_FROM}', max(config_version), 'tso world' FROM app.cfg_version")
            }
        }
        val key = File.createTempFile("aron-jwt", ".pem").apply {
            deleteOnExit()
            val k = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair().private
            writeText("-----BEGIN " + "PRIVATE KEY-----\n" + Base64.getMimeEncoder().encodeToString(k.encoded) + "\n-----END PRIVATE KEY-----\n")
        }
        val recorder = PushSender { token, data -> pushes += token to data; true }
        wiring = Wiring.production(
            Settings.load(mapOf("ARON_ROLE" to "api", "ARON_DB_URL" to fresh.url, "ARON_JWT_SIGNING_KEY_FILE" to key.absolutePath)), clock, extraRecordHandlers,
            pushSender = recorder, blobPorts = FakeBlobs().ports(),
        )
    }

    override fun close() { runCatching { wiring.securityStore?.close() }; wiring.database?.close(); fresh.close() }

    /** The Dhaka business date of the clock. */
    fun today(): LocalDate = LocalDate.ofInstant(now.get(), DHAKA)

    fun one(sql: String): Long = fresh.db.jdbi.withHandle<Long, Exception> { h -> h.createQuery(sql).mapTo(Long::class.java).one() }
    fun longs(sql: String): List<Long> = fresh.db.jdbi.withHandle<List<Long>, Exception> { h -> h.createQuery(sql).mapTo(Long::class.java).list() }
    fun scalar(sql: String): String? = fresh.db.jdbi.withHandle<String?, Exception> { h -> h.createQuery(sql).mapTo(String::class.java).findOne().orElse(null) }
    fun rows(sql: String): List<Map<String, Any?>> = fresh.db.jdbi.withHandle<List<Map<String, Any?>>, Exception> { h -> h.createQuery(sql).mapToMap().list() }
    fun exec(sql: String) = fresh.db.jdbi.useHandle<Exception> { h -> h.execute(sql) }

    /** A global config switch as the portal sets it: the open row closes and the new one starts at the same instant. */
    fun cfg(key: String, json: String) {
        val n = switches.incrementAndGet()
        fresh.db.jdbi.useTransaction<Exception> { h ->
            h.execute("INSERT INTO app.cfg_version (config_version, kind, committed_by, summary) SELECT max(config_version) + 1, 'change', (SELECT id FROM app.app_user WHERE username = 'aron.system'), 'tso world switch' FROM app.cfg_version")
            h.execute("UPDATE app.cfg_value SET effective_to = TIMESTAMPTZ '${ApiWorld.CFG_SWITCH}' + interval '1 second' * $n WHERE key = '$key' AND scope_type = 'global' AND effective_to IS NULL")
            h.createUpdate(
                "INSERT INTO app.cfg_value (key, scope_type, scope_id, value, effective_from, config_version, reason) " +
                    "SELECT :k, 'global', 0, CAST(:v AS jsonb), TIMESTAMPTZ '${ApiWorld.CFG_SWITCH}' + interval '1 second' * $n, max(config_version), 'test' FROM app.cfg_version",
            ).bind("k", key).bind("v", json).execute()
        }
        // DbServerConfig caches 30 s on the API clock.
        now.updateAndGet { it.plusSeconds(31) }
    }

    /** A bound, active phone of [username] (flavour sr, amo or tso) with the seed's placeholder key. */
    fun phone(username: String, flavour: String): TsoPhone = fresh.db.jdbi.withHandle<TsoPhone, Exception> { h ->
        val device = UUID.randomUUID().toString()
        h.execute(
            """
            INSERT INTO app.device (device_uuid, flavour, app_package, status, device_owner, lockdown_level, trust_level, public_key_jwk, public_key_thumbprint, attestation_summary, app_signing_cert_sha256, zone_id, device_info, app_version)
            SELECT CAST('$device' AS uuid), '$flavour', 'com.aktcl.aron.$flavour', d.status, d.device_owner, d.lockdown_level, d.trust_level,
                   CAST('{"kty": "EC", "crv": "P-256", "x": "seed-placeholder", "y": "seed-placeholder"}' AS jsonb), 'tso-world-$device', d.attestation_summary, d.app_signing_cert_sha256,
                   u.home_zone_id, d.device_info, d.app_version
              FROM app.device d, app.app_user u WHERE d.device_uuid = '${ApiWorld.SR_PHONE}' AND u.username = '$username'
            """.trimIndent(),
        )
        // One active binding per user and ordinal: the user's earlier phone (the seed's dev phone of sr1001) is unbound.
        h.execute("UPDATE app.device_binding SET status = 'revoked', unbound_at = now() WHERE user_id = (SELECT id FROM app.app_user WHERE username = '$username') AND status = 'active'")
        h.execute("INSERT INTO app.device_binding (device_id, user_id, bind_ordinal, bound_via) SELECT d.id, u.id, 0, 'support' FROM app.device d, app.app_user u WHERE d.device_uuid = '$device' AND u.username = '$username'")
        TsoPhone(
            username, h.createQuery("SELECT id FROM app.app_user WHERE username = :u").bind("u", username).mapTo(Long::class.java).one(), device,
            h.createQuery("SELECT id FROM app.device WHERE device_uuid = CAST(:d AS uuid)").bind("d", device).mapTo(Long::class.java).one(), flavour,
        )
    }

    /** The production wiring in process, without testApplication's one-minute limit. */
    fun withApi(block: suspend HttpClient.() -> Unit) = runBlocking {
        val app = TestApplication { application { aronApi(wiring) } }
        app.start()
        try { app.client.block() } finally { app.stop() }
    }

    /** Forgets every token (after a clock jump past their validity). */
    fun forgetTokens() = tokens.clear()

    suspend fun HttpClient.login(p: TsoPhone): String = tokens.getOrPut("phone:${p.deviceUuid}") {
        val r = post("/v1/auth/login") {
            contentType(ContentType.Application.Json); header("X-App-Version", ApiWorld.APP_VERSION)
            setBody("""{"username":"${p.username}","password":"$password","client":"app_${p.flavour}","device_uuid":"${p.deviceUuid}"}""")
        }
        assertEquals(HttpStatusCode.OK, r.status, "login ${p.username}: ${r.bodyAsText()}")
        Json.parseToJsonElement(r.bodyAsText()).jsonObject["access_token"]!!.jsonPrimitive.content
    }

    suspend fun HttpClient.web(user: String): String = tokens.getOrPut("web:$user") {
        val r = post("/v1/auth/login") {
            contentType(ContentType.Application.Json); header("X-App-Version", ApiWorld.APP_VERSION)
            setBody("""{"username":"$user","password":"$password","client":"web"}""")
        }
        assertEquals(HttpStatusCode.OK, r.status, "login $user: ${r.bodyAsText()}")
        Json.parseToJsonElement(r.bodyAsText()).jsonObject["access_token"]!!.jsonPrimitive.content
    }

    /** A GET as the phone (token and X-Device-Id). */
    suspend fun HttpClient.getAs(p: TsoPhone, path: String): HttpResponse =
        get(path) { bearerAuth(login(p)); header("X-Device-Id", p.deviceUuid); header("X-App-Version", ApiWorld.APP_VERSION) }

    /** A JSON POST as the phone. */
    suspend fun HttpClient.postAs(p: TsoPhone, path: String, body: String): HttpResponse = post(path) {
        bearerAuth(login(p)); header("X-Device-Id", p.deviceUuid); header("X-App-Version", ApiWorld.APP_VERSION); contentType(ContentType.Application.Json); setBody(body)
    }

    /** A JSON POST with a web token. */
    suspend fun HttpClient.postWeb(user: String, path: String, body: String): HttpResponse = post(path) {
        bearerAuth(web(user)); header("X-App-Version", ApiWorld.APP_VERSION); contentType(ContentType.Application.Json); setBody(body)
    }

    suspend fun HttpClient.getWeb(user: String, path: String): HttpResponse = get(path) { bearerAuth(web(user)); header("X-App-Version", ApiWorld.APP_VERSION) }

    /** Registers an FCM token for the phone; returns it. */
    suspend fun HttpClient.registerPush(p: TsoPhone): String {
        val fcm = "fcm-${p.flavour}-" + p.deviceUuid.replace("-", "")
        val r = put("/v1/devices/me/push-token") {
            bearerAuth(login(p)); header("X-Device-Id", p.deviceUuid); header("X-App-Version", ApiWorld.APP_VERSION); contentType(ContentType.Application.Json)
            setBody("""{"provider":"fcm","token":"$fcm","app_flavour":"${p.flavour}"}""")
        }
        assertEquals(HttpStatusCode.NoContent, r.status, r.bodyAsText())
        return fcm
    }

    /** POST /v1/sync/batch of [records] from [p]; returns the answer (200 asserted). */
    suspend fun HttpClient.sync(p: TsoPhone, records: List<JsonObject>, batchUuid: String = UUID.randomUUID().toString()): JsonObject {
        val body = buildJsonObject {
            put("batch_uuid", batchUuid); put("device_uuid", p.deviceUuid); put("schema_version", 1); put("app_version", ApiWorld.APP_VERSION)
            put("trigger", "manual"); put("sent_at_device", now.get().toString()); put("pending_rows", 0)
            put("time_anchors", JsonArray(emptyList())); put("device_counts", buildJsonObject {}); put("records", JsonArray(records))
        }.toString()
        val r = post("/v1/sync/batch") {
            bearerAuth(login(p)); header("X-Device-Id", p.deviceUuid); header("X-App-Version", ApiWorld.APP_VERSION); header("Content-Encoding", "gzip")
            setBody(ByteArrayContent(gz(body), ContentType.Application.Json))
        }
        assertEquals(HttpStatusCode.OK, r.status, r.bodyAsText())
        return Json.parseToJsonElement(r.bodyAsText()).jsonObject
    }

    /** One device record: the envelope as the phones build it, captured [secondsAgo] before the clock on its Dhaka date. */
    fun record(type: String, payload: String, routeId: Long? = null, cu: String = UUID.randomUUID().toString(), family: String = cu, rank: Int = 0, secondsAgo: Long = 60): JsonObject {
        val at = now.get().minusSeconds(secondsAgo)
        return buildJsonObject {
            put("type", type); put("client_uuid", cu); put("family_uuid", family); put("rank", rank); put("schema_version", 1)
            put("business_date", LocalDate.ofInstant(at, DHAKA).toString()); put("captured_at", at.toString()); put("captured_elapsed_ms", 18_330_000); put("boot_count", 9)
            put("clock_offset_ms", 0); put("captured_offline", true); routeId?.let { put("route_id", it) }; put("bundle_version", "${LocalDate.ofInstant(at, DHAKA)}:1")
            put("bundle_stale", false); put("config_version", 1)
            put("payload", Json.parseToJsonElement(payload))
        }
    }

    companion object {
        val DHAKA: ZoneId = ZoneId.of("Asia/Dhaka")

        /** The ack of [cu] in a batch answer. */
        fun ack(answer: JsonObject, cu: String): JsonObject = answer["acks"]!!.jsonArray.map { it.jsonObject }.single { it["client_uuid"]!!.jsonPrimitive.content == cu }
        fun JsonObject.status(): String = this["status"]!!.jsonPrimitive.content
        fun JsonObject.code(): String? = this["code"]?.let { if (it is kotlinx.serialization.json.JsonNull) null else it.jsonPrimitive.content }
        fun json(s: String): JsonObject = Json.parseToJsonElement(s).jsonObject
    }
}

/** One phone of a [TsoWorld]: the user and the bound device. */
data class TsoPhone(val username: String, val userId: Long, val deviceUuid: String, val deviceId: Long, val flavour: String)
