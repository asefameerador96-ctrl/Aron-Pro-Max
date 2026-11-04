package com.aktcl.aron.backend.app

import com.aktcl.aron.backend.auth.PasswordHasher
import com.aktcl.aron.backend.platform.AronClock
import com.aktcl.aron.backend.platform.FreshDb
import com.aktcl.aron.backend.platform.Settings
import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.spec.ECGenParameterSpec
import java.time.Instant
import java.util.Base64
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.GZIPOutputStream
import kotlin.test.assertEquals

/**
 * The world of the support-ticket tests (tso-manual B1): a migrated database with db/seed, the PRODUCTION wiring with a
 * recording push sender and the in-memory blob store, and the phones that raise tickets: tso1001 and a second TSO
 * (tsoq9001, its own territory) on TSO phones, sr1001 on the seeded SR phone (a role outside the default requester
 * roles). The support desk is admin1001 and superadmin1001 (`support.ticket.manage`, V0134); support1001 holds no desk
 * permission. MFA and the rate limits are lifted as in [ApiWorld]. The API clock is fixed; config switches are rows of
 * `app.cfg_value` that the ticket code reads on its own handle (no config cache in the way).
 */
class TicketHarness : AutoCloseable {
    val fresh: FreshDb = FreshDb.create()
    val now = AtomicReference(Instant.parse("2027-01-03T04:00:00Z"))
    val clock = AronClock { now.get() }
    val password = "throwaway-" + System.nanoTime()
    val tsoPhone: String = UUID.randomUUID().toString()
    val tso2Phone: String = UUID.randomUUID().toString()
    val srPhone = ApiWorld.SR_PHONE
    val day = "2027-01-03"
    val ids = HashMap<String, Long>()
    /** Every push the wiring sent: (FCM token, data). */
    val sent = CopyOnWriteArrayList<Pair<String, Map<String, String>>>()
    val wiring: Wiring
    private val minute = AtomicInteger(0)
    private val cfgStep = AtomicInteger(0)

    init {
        val seedDir = File(System.getProperty("aron.repoRoot"), "db/seed")
        fresh.dataSource.connection.use { c ->
            seedDir.listFiles { f -> f.name.matches(Regex("0\\d_.*\\.sql")) }!!.sortedBy { it.name }.forEach { f -> c.createStatement().use { it.execute(f.readText()) } }
        }
        fresh.db.jdbi.useHandle<Exception> { h ->
            val hash = PasswordHasher().hash(password)
            h.createUpdate("UPDATE app.app_user SET password_hash = :h WHERE pilot").bind("h", hash).execute()
            for ((k, v) in listOf("cfg.api.rl.device_per_min" to "100000", "cfg.api.rl.user_per_min" to "100000", "cfg.auth.mfa_required_roles" to "[]")) {
                h.execute("INSERT INTO app.cfg_value (key, scope_type, scope_id, value, effective_from, config_version, reason) SELECT '$k', 'global', 0, '$v'::jsonb, TIMESTAMPTZ '${ApiWorld.CFG_FROM}', max(config_version), 'ticket tests' FROM app.cfg_version")
            }
            // A second TSO in a territory of its own (not tso1001's), with its own phone.
            h.execute("INSERT INTO app.territory (code, name, division_id) SELECT 'T-TKT', 'Ticket territory', division_id FROM app.territory WHERE code = 'T-DHK-N'")
            h.execute("INSERT INTO app.zone (code, name, territory_id) SELECT 'Z-TKT', 'Ticket zone', id FROM app.territory WHERE code = 'T-TKT'")
            h.createUpdate("INSERT INTO app.app_user (username, full_name, role, designation, locale, home_zone_id, pilot, must_change_password, password_hash) SELECT 'tsoq9001', 'Second Ticket TSO', 'TSO', 'Territory Sales Officer', 'bn', id, false, false, :h FROM app.zone WHERE code = 'Z-TKT'")
                .bind("h", hash).execute()
            h.execute("INSERT INTO app.user_scope (user_id, node_type, node_id, valid_from, reason) SELECT u.id, 'territory', t.id, DATE '2026-01-01', 'ticket tests' FROM app.app_user u, app.territory t WHERE u.username = 'tsoq9001' AND t.code = 'T-TKT'")
            for ((uuid, user) in listOf(tsoPhone to "tso1001", tso2Phone to "tsoq9001")) {
                h.execute(
                    """
                    INSERT INTO app.device (device_uuid, flavour, app_package, status, device_owner, lockdown_level, public_key_jwk, public_key_thumbprint, app_signing_cert_sha256, zone_id)
                    SELECT CAST('$uuid' AS uuid), 'tso', 'com.aktcl.aron.tso', 'active', false, 'dev', '{"kty":"placeholder"}', 'tso-$uuid', decode(repeat('00', 32), 'hex'), z.id
                    FROM app.zone z WHERE z.code = 'Z-MIR'
                    """.trimIndent(),
                )
                h.execute("INSERT INTO app.device_binding (device_id, user_id, bind_ordinal, bound_via) SELECT d.id, u.id, 0, 'support' FROM app.device d, app.app_user u WHERE d.device_uuid = '$uuid' AND u.username = '$user'")
            }
            for (u in listOf("tso1001", "tsoq9001", "sr1001", "admin1001", "superadmin1001", "support1001", "dmo1001")) {
                ids[u] = h.createQuery("SELECT id FROM app.app_user WHERE username = :u").bind("u", u).mapTo(Long::class.java).one()
            }
        }
        val key = File.createTempFile("aron-jwt", ".pem").apply {
            deleteOnExit()
            val k = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair().private
            writeText("-----BEGIN " + "PRIVATE KEY-----\n" + Base64.getMimeEncoder().encodeToString(k.encoded) + "\n-----END PRIVATE KEY-----\n")
        }
        val recorder = com.aktcl.aron.backend.notify.PushSender { token, data -> sent += token to data; true }
        wiring = Wiring.production(
            Settings.load(mapOf("ARON_ROLE" to "api", "ARON_DB_URL" to fresh.url, "ARON_JWT_SIGNING_KEY_FILE" to key.absolutePath)), clock,
            pushSender = recorder, blobPorts = FakeBlobs().ports(),
        )
    }

    override fun close() { wiring.database?.close(); fresh.close() }

    // ---- callers -------------------------------------------------------------------------------------------------

    /** A signed-in caller: its access token and the phone it calls from (null on the web). */
    data class Caller(val token: String, val phone: String?)

    private val tokens = HashMap<String, Caller>()

    /** [user] on its phone ([phone] given) or on the web; one login per user and channel. */
    suspend fun login(client: HttpClient, user: String, phone: String? = null): Caller = tokens.getOrPut("$user/$phone") {
        val clientName = when { phone == null -> "web"; user.startsWith("sr") -> "app_sr"; else -> "app_tso" }
        val r = client.request("/v1/auth/login") {
            method = HttpMethod.Post; contentType(ContentType.Application.Json); header("X-App-Version", ApiWorld.APP_VERSION)
            setBody("""{"username":"$user","password":"$password","client":"$clientName"${phone?.let { ",\"device_uuid\":\"$it\"" } ?: ""}}""")
        }
        assertEquals(HttpStatusCode.OK, r.status, "login $user: ${r.bodyAsText()}")
        Caller(json(r.bodyAsText())["access_token"]!!.jsonPrimitive.content, phone)
    }

    suspend fun HttpClient.call(c: Caller, method: HttpMethod, path: String, body: String? = null): HttpResponse = request(path) {
        this.method = method
        bearerAuth(c.token); header("X-App-Version", ApiWorld.APP_VERSION)
        c.phone?.let { header("X-Device-Id", it) }
        if (body != null) { contentType(ContentType.Application.Json); setBody(body) }
    }

    // ---- sync records --------------------------------------------------------------------------------------------

    fun uuid(): String = UUID.randomUUID().toString()

    fun record(type: String, payload: JsonObject, uuid: String = uuid()): JsonObject = buildJsonObject {
        val m = minute.incrementAndGet()
        put("type", type); put("client_uuid", uuid); put("family_uuid", uuid); put("rank", 0); put("schema_version", 1)
        put("business_date", day); put("captured_at", "2027-01-03T03:%02d:%02d.000Z".format((m / 60) % 60, m % 60)); put("captured_elapsed_ms", 18_000_000 + m); put("boot_count", 412)
        put("clock_offset_ms", 0); put("captured_offline", true); put("bundle_version", "$day:3"); put("bundle_stale", false); put("config_version", 1)
        put("payload", payload)
    }

    fun ticket(uuid: String = uuid(), issue: String = "printer_connection", title: String = "Printer will not pair", description: String = "", affected: String? = null, source: String = "manual") =
        record("support_ticket", buildJsonObject {
            put("issue_type_code", issue); put("title", title); put("description", description); put("source", source)
            affected?.let { put("affected_username", it) }
        }, uuid)

    fun comment(ticket: String, body: String = "Still not working after a restart", uuid: String = uuid()) =
        record("support_ticket_event", buildJsonObject { put("ticket_uuid", ticket); put("kind", "comment"); put("body", body) }, uuid)

    fun closeReview(ticket: String, uuid: String = uuid()) =
        record("support_ticket_event", buildJsonObject { put("ticket_uuid", ticket); put("kind", "close_review") }, uuid)

    /** Sends [records] in one batch from [c]'s phone; the acks as `status` or `status code`, in order. */
    suspend fun HttpClient.send(c: Caller, records: List<JsonObject>): List<String> {
        val body = buildJsonObject {
            put("batch_uuid", uuid()); put("device_uuid", c.phone!!); put("schema_version", 1); put("app_version", ApiWorld.APP_VERSION)
            put("trigger", "manual"); put("sent_at_device", "2027-01-03T04:00:00.000Z"); put("pending_rows", 0)
            put("time_anchors", JsonArray(emptyList())); put("device_counts", buildJsonObject {}); put("records", JsonArray(records))
        }.toString()
        val gz = ByteArrayOutputStream().also { o -> GZIPOutputStream(o).use { it.write(body.toByteArray()) } }.toByteArray()
        val r = request("/v1/sync/batch") {
            method = HttpMethod.Post
            bearerAuth(c.token); header("X-Device-Id", c.phone); header("X-App-Version", ApiWorld.APP_VERSION); header("Content-Encoding", "gzip")
            setBody(io.ktor.http.content.ByteArrayContent(gz, ContentType.Application.Json))
        }
        assertEquals(HttpStatusCode.OK, r.status, r.bodyAsText())
        val acks = json(r.bodyAsText())["acks"]!!.jsonArray.map { it.jsonObject }.associateBy { it["client_uuid"]!!.jsonPrimitive.content }
        return records.map { rec ->
            val a = acks.getValue(rec["client_uuid"]!!.jsonPrimitive.content)
            a["status"]!!.jsonPrimitive.content + ((a["code"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.let { " $it" } ?: "")
        }
    }

    // ---- desk ----------------------------------------------------------------------------------------------------

    /** PATCH by [c] with [members] plus `op_uuid` (new unless given) and `expected_version` (the ticket's current one unless given). */
    suspend fun HttpClient.desk(c: Caller, ticket: String, vararg members: Pair<String, Any?>, op: String = uuid(), expected: Int? = null): HttpResponse {
        val v = expected ?: version(ticket)
        val body = buildJsonObject {
            put("op_uuid", op); put("expected_version", v)
            members.forEach { (k, v) -> put(k, js(v)) }
        }
        return call(c, HttpMethod.Patch, "/v1/support/tickets/$ticket", body.toString())
    }

    suspend fun HttpClient.reply(c: Caller, ticket: String, body: String, event: String = uuid()): HttpResponse =
        call(c, HttpMethod.Post, "/v1/support/tickets/$ticket/events", buildJsonObject { put("event_uuid", event); put("body", body) }.toString())

    fun js(v: Any?): JsonElement = when (v) {
        null -> kotlinx.serialization.json.JsonNull
        is JsonElement -> v
        is Number -> JsonPrimitive(v)
        is Boolean -> JsonPrimitive(v)
        else -> JsonPrimitive(v.toString())
    }

    // ---- database ------------------------------------------------------------------------------------------------

    fun version(ticket: String): Int = scalar("SELECT version FROM app.support_ticket_state WHERE ticket_uuid = '$ticket'")?.toInt() ?: 1

    fun status(ticket: String): String? = scalar("SELECT status FROM app.support_ticket_state WHERE ticket_uuid = '$ticket'")

    fun scalar(sql: String): String? = fresh.db.jdbi.withHandle<String?, Exception> { h -> h.createQuery(sql).mapTo(String::class.java).findOne().orElse(null) }

    fun count(sql: String): Long = scalar(sql)!!.toLong()

    fun rows(sql: String): List<String> = fresh.db.jdbi.withHandle<List<String>, Exception> { h -> h.createQuery(sql).mapTo(String::class.java).list() }

    /**
     * Sets a global config value from now on: closes the open global row of [key] and opens a new one, each switch
     * effective a minute after the previous (all before the API clock), so the newest row is the one in force.
     */
    fun setCfg(key: String, jsonValue: String) = fresh.db.jdbi.useTransaction<Exception> { h ->
        val at = Instant.parse("2026-02-01T00:00:00Z").plusSeconds(60L * cfgStep.incrementAndGet()).toString()
        h.execute("UPDATE app.cfg_value SET effective_to = TIMESTAMPTZ '$at' WHERE key = '$key' AND scope_type = 'global' AND effective_to IS NULL")
        h.execute("INSERT INTO app.cfg_value (key, scope_type, scope_id, value, effective_from, config_version, reason) SELECT '$key', 'global', 0, '$jsonValue'::jsonb, TIMESTAMPTZ '$at', max(config_version), 'ticket tests' FROM app.cfg_version")
    }

    /** Waits up to 30 s (the push bound of N-037) for [n] pushes. */
    fun awaitSent(n: Int) {
        val until = System.nanoTime() + 30_000_000_000L
        while (sent.size < n && System.nanoTime() < until) Thread.sleep(20)
    }

    companion object {
        fun json(s: String): JsonObject = Json.parseToJsonElement(s).jsonObject

        /** The contract's name-based UUID (StableIds.v4), written again here as the test's own oracle. */
        fun stableV4(name: String): String {
            val b = MessageDigest.getInstance("SHA-256").digest(name.toByteArray(Charsets.UTF_8)).copyOf(16)
            b[6] = ((b[6].toInt() and 0x0f) or 0x40).toByte()
            b[8] = ((b[8].toInt() and 0x3f) or 0x80).toByte()
            val x = b.joinToString("") { "%02x".format(it) }
            return "${x.substring(0, 8)}-${x.substring(8, 12)}-${x.substring(12, 16)}-${x.substring(16, 20)}-${x.substring(20)}"
        }

        fun sha256Hex(s: String): String = MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
}
