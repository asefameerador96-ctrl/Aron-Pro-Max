package com.aktcl.aron.backend.app

import com.aktcl.aron.backend.auth.PasswordHasher
import com.aktcl.aron.backend.platform.AronClock
import com.aktcl.aron.backend.platform.FreshDb
import com.aktcl.aron.backend.platform.Settings
import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import java.io.File
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import java.time.Instant
import java.util.Base64
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** T1 checker regressions for F-API-038 (POST /v1/admin/data-entry). Setup copied from [PaperBackfillTest]. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PaperBackfillCheckerTest {
    private lateinit var fresh: FreshDb
    private lateinit var wiring: Wiring
    private val password = "throwaway-" + System.nanoTime()
    private val clock = AronClock { Instant.parse("2027-01-03T04:00:00Z") }
    private val day = "2027-01-03"
    private var routeId = 0L
    private var outletId = 0L
    private var sr = 0L
    private val skus = mutableListOf<Pair<Long, Long>>()
    private val seq = AtomicInteger(700)

    @BeforeAll
    fun setUp() {
        fresh = FreshDb.create()
        val seedDir = File(System.getProperty("aron.repoRoot"), "db/seed")
        fresh.dataSource.connection.use { c ->
            seedDir.listFiles { f -> f.name.matches(Regex("0\\d_.*\\.sql")) }!!.sortedBy { it.name }.forEach { f -> c.createStatement().use { it.execute(f.readText()) } }
        }
        fresh.db.jdbi.useHandle<Exception> { h ->
            h.createUpdate("UPDATE app.app_user SET password_hash = :h WHERE pilot").bind("h", PasswordHasher().hash(password)).execute()
            routeId = h.createQuery("SELECT id FROM app.route WHERE code = 'MIR-SR-D'").mapTo(Long::class.java).one()
            outletId = h.createQuery("SELECT min(id) FROM app.outlet WHERE route_id = :r").bind("r", routeId).mapTo(Long::class.java).one()
            sr = h.createQuery("SELECT id FROM app.app_user WHERE username = 'sr1001'").mapTo(Long::class.java).one()
            skus += h.createQuery("SELECT s.id, p.amount_mtk FROM app.sku s JOIN app.sku_price p ON p.sku_id = s.id AND p.price_type = 'outlet' WHERE p.amount_mtk > 0 ORDER BY s.id LIMIT 2")
                .map { rs, _ -> rs.getLong(1) to rs.getLong(2) }.list()
            h.execute("INSERT INTO app.cfg_version (config_version, kind, committed_by, summary) SELECT max(config_version) + 1, 'change', (SELECT id FROM app.app_user WHERE username = 'aron.system'), 'test mfa' FROM app.cfg_version")
            h.execute("INSERT INTO app.cfg_value (key, scope_type, scope_id, value, effective_from, config_version, reason) SELECT 'cfg.auth.mfa_required_roles', 'global', 0, '[]'::jsonb, now() - interval '1 day', max(config_version), 'test' FROM app.cfg_version")
        }
        val keyFile = File.createTempFile("aron-jwt", ".pem").apply {
            deleteOnExit()
            val k = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair().private
            writeText("-----BEGIN " + "PRIVATE KEY-----\n" + Base64.getMimeEncoder().encodeToString(k.encoded) + "\n-----END PRIVATE KEY-----\n")
        }
        wiring = Wiring.production(Settings.load(mapOf("ARON_ROLE" to "api", "ARON_DB_URL" to fresh.url, "ARON_JWT_SIGNING_KEY_FILE" to keyFile.absolutePath)), clock)
    }

    @AfterAll
    fun tearDown() { wiring.database?.close(); fresh.close() }

    private fun uuid() = UUID.randomUUID().toString()
    private fun count(sql: String): Long = fresh.db.jdbi.withHandle<Long, Exception> { h -> h.createQuery(sql).mapTo(Long::class.java).one() }
    private fun memoNo() = "sr1001-270103-${seq.incrementAndGet()}"

    private suspend fun HttpClient.webToken(user: String): String {
        val r = post("/v1/auth/login") { contentType(ContentType.Application.Json); setBody("""{"username":"$user","password":"$password","client":"web"}""") }
        assertEquals(HttpStatusCode.OK, r.status, r.bodyAsText())
        return Json.parseToJsonElement(r.bodyAsText()).jsonObject["access_token"]!!.jsonPrimitive.content
    }

    private fun body(cu: String, memoNo: String, qty1: Long = 20, qty2: Long = 10, paid: Long = 0, date: String = day, user: Long = sr) =
        """{"client_uuid":"$cu","memo_no":"$memoNo","business_date":"$date","user_id":$user,"route_id":$routeId,"outlet_id":$outletId,
           "lines":[{"sku_id":${skus[0].first},"qty_base":$qty1,"line_kind":"sale"},{"sku_id":${skus[1].first},"qty_base":$qty2,"line_kind":"sale"}],
           "paid_mtk":$paid,"reason":"Phone died, keyed from the printed memo"}"""

    private suspend fun HttpClient.entry(token: String, b: String): HttpResponse = post("/v1/admin/data-entry") { bearerAuth(token); contentType(ContentType.Application.Json); setBody(b) }

    /**
     * The memo number embeds its business date and app.memo is unique on (memo_no, business_date) only. A printed memo
     * keyed under another business date than the one in its number is stored a second time: the same sale counts twice,
     * and duplicate_memo_no never fires. docs/24 s7.5: a number that does not match the business date is memo_no_invalid.
     */
    @Test
    fun aMemoNumberWhoseDateIsNotTheBusinessDateIsRefusedNotStoredTwice() = testApplication {
        application { aronApi(wiring) }
        val admin = client.webToken("admin1001")
        val no = memoNo() // ...-270103-...
        assertEquals(HttpStatusCode.OK, client.entry(admin, body(uuid(), no)).status)
        val second = client.entry(admin, body(uuid(), no, date = "2027-01-02"))
        val status = if (second.status == HttpStatusCode.OK) Json.parseToJsonElement(second.bodyAsText()).jsonObject["status"]!!.jsonPrimitive.content else null
        assertTrue(second.status == HttpStatusCode.BadRequest || status == "duplicate_memo_no", "a second copy of printed memo $no was ${second.status} ${second.bodyAsText()}")
        assertEquals(1, count("SELECT count(*) FROM app.memo WHERE memo_no = '$no'"), "the same printed memo is stored once")
    }

    /** docs/24 s7.5: `<username>-<yyMMdd>-<seq>`; a number of another login keyed for this SR is memo_no_invalid (400), not stored. */
    @Test
    fun aMemoNumberOfAnotherLoginIsRefused() = testApplication {
        application { aronApi(wiring) }
        val admin = client.webToken("admin1001")
        val no = "zz9999-270103-${seq.incrementAndGet()}"
        val r = client.entry(admin, body(uuid(), no))
        assertEquals(HttpStatusCode.BadRequest, r.status, r.bodyAsText())
        assertEquals(0, count("SELECT count(*) FROM app.memo WHERE memo_no = '$no'"))
    }

    /** The operation lists 200, 400, 401, 403, 409 only; an unknown user_id answers 404 (ERR_NOT_FOUND from ingestManual). */
    @Test
    fun anUnknownUserAnswersAStatusTheContractLists() = testApplication {
        application { aronApi(wiring) }
        val admin = client.webToken("admin1001")
        val r = client.entry(admin, body(uuid(), memoNo(), user = 987_654_321))
        assertTrue(r.status in setOf(HttpStatusCode.BadRequest, HttpStatusCode.Forbidden), "contract statuses only, got ${r.status} ${r.bodyAsText()}")
    }

    /** docs/24 s7.2: memo_line.pack_factor is the SKU's base units per pack at capture; the backfill stores 1 for every SKU. */
    @Test
    fun linesCarryTheSkusPackFactor() = testApplication {
        application { aronApi(wiring) }
        val admin = client.webToken("admin1001")
        val cu = uuid()
        assertEquals(HttpStatusCode.OK, client.entry(admin, body(cu, memoNo())).status)
        assertEquals(0, count("SELECT count(*) FROM app.memo_line l JOIN app.sku s ON s.id = l.sku_id WHERE l.memo_client_uuid = '$cu' AND l.pack_factor <> s.base_per_pack"),
            "pack_factor of every line equals sku.base_per_pack")
    }
}
