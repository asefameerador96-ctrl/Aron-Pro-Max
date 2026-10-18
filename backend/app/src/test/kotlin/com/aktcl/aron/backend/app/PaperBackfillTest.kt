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

/** F-API-038 (T1): POST /v1/admin/data-entry prices qty-only lines on the server and stores one manual memo through ingestManual, once. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PaperBackfillTest {
    private lateinit var fresh: FreshDb
    private lateinit var wiring: Wiring
    private val password = "throwaway-" + System.nanoTime()
    private val clock = AronClock { Instant.parse("2027-01-03T04:00:00Z") }
    private val day = "2027-01-03"
    private var routeId = 0L
    private var outletId = 0L
    private var sr = 0L
    private val skus = mutableListOf<Pair<Long, Long>>()
    private val seq = AtomicInteger(300)

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

    private fun body(cu: String, memoNo: String, qty1: Long = 20, qty2: Long = 10, paid: Long? = null, date: String = day, extra: String = "", lineKind: String = "sale", user: Long = sr) =
        """{"client_uuid":"$cu","memo_no":"$memoNo","business_date":"$date","user_id":$user,"route_id":$routeId,"outlet_id":$outletId,
           "lines":[{"sku_id":${skus[0].first},"qty_base":$qty1,"line_kind":"$lineKind"},{"sku_id":${skus[1].first},"qty_base":$qty2,"line_kind":"sale"}],
           "paid_mtk":${paid ?: 0},"reason":"Phone died, keyed from the printed memo"$extra}"""

    private suspend fun HttpClient.entry(token: String, b: String): HttpResponse = post("/v1/admin/data-entry") { bearerAuth(token); contentType(ContentType.Application.Json); setBody(b) }
    private fun js(r: String) = Json.parseToJsonElement(r).jsonObject

    @Test
    fun pricedOnTheServerStoredOnceAndReplayedWithOneAuditRow() = testApplication {
        application { aronApi(wiring) }
        val admin = client.webToken("admin1001")
        val cu = uuid(); val no = memoNo()
        val gross = 20 * skus[0].second + 10 * skus[1].second
        val net = (gross + 5) / 10 * 10
        val r = client.entry(admin, body(cu, no, paid = net))
        assertEquals(HttpStatusCode.OK, r.status, r.bodyAsText())
        assertEquals("created", js(r.bodyAsText())["status"]!!.jsonPrimitive.content); assertEquals(net.toString(), js(r.bodyAsText())["net_mtk"]!!.jsonPrimitive.content)
        assertEquals(cu, js(r.bodyAsText())["memo_client_uuid"]!!.jsonPrimitive.content)
        assertEquals(1, count("SELECT count(*) FROM app.memo WHERE client_uuid = '$cu' AND entry_source = 'manual' AND gross_mtk = $gross AND net_mtk = $net AND user_id = $sr"))
        assertEquals(2, count("SELECT count(*) FROM app.memo_line WHERE memo_client_uuid = '$cu'"))
        assertEquals(gross, count("SELECT sum(gross_mtk) FROM app.memo_line WHERE memo_client_uuid = '$cu'"))
        // Replay: same answer shape, nothing doubles, one audit row with the reason.
        val again = client.entry(admin, body(cu, no, paid = net))
        assertEquals(HttpStatusCode.OK, again.status, again.bodyAsText()); assertEquals("replayed", js(again.bodyAsText())["status"]!!.jsonPrimitive.content)
        assertEquals(1, count("SELECT count(*) FROM app.memo WHERE client_uuid = '$cu'"))
        assertEquals(1, count("SELECT count(*) FROM app.audit_log WHERE entity = 'paper_backfill' AND entity_id = '$cu' AND action = 'create' AND reason = 'Phone died, keyed from the printed memo'"))
        // Same uuid with other content is a conflict and changes nothing.
        val other = client.entry(admin, body(cu, no, qty1 = 21, paid = net))
        assertEquals(HttpStatusCode.Conflict, other.status, other.bodyAsText())
        assertEquals(2, count("SELECT count(*) FROM app.memo_line WHERE memo_client_uuid = '$cu'"))
        assertEquals(gross, count("SELECT gross_mtk FROM app.memo WHERE client_uuid = '$cu'"))
        // A credit memo: dues raise the outlet balance by the due.
        val cu2 = uuid()
        assertEquals(HttpStatusCode.OK, client.entry(admin, body(cu2, memoNo(), paid = 0)).status)
        assertEquals(net, count("SELECT COALESCE(sum(amount_mtk),0) FROM app.due_ledger WHERE memo_client_uuid = '$cu2' AND entry_kind = 'memo_due'"))
    }

    @Test
    fun aReplayAfterABackDatedPriceChangeGetsTheStoredAnswer() = testApplication {
        // Replay first: the retry is answered from the stored entry, not re-priced into payload_conflict.
        application { aronApi(wiring) }
        val admin = client.webToken("admin1001")
        val cu = uuid(); val no = memoNo()
        val first = client.entry(admin, body(cu, no))
        assertEquals(HttpStatusCode.OK, first.status, first.bodyAsText())
        val net = js(first.bodyAsText())["net_mtk"]!!.jsonPrimitive.content
        val sku = skus[0].first
        fun price(sql: String) = fresh.db.jdbi.withHandle<List<Long>, Exception> { h -> h.createQuery(sql).bind("s", sku).bind("d", java.time.LocalDate.parse(day)).mapTo(Long::class.java).list() }
        // A price published today, valid from before the memo's business date (the old row is closed at that date).
        val closed = price("UPDATE app.sku_price SET valid_to = :d WHERE sku_id = :s AND price_type = 'outlet' AND valid_from < :d AND (valid_to IS NULL OR valid_to > :d) RETURNING id")
        val added = price("INSERT INTO app.sku_price (sku_id, price_type, amount_mtk, valid_from) VALUES (:s, 'outlet', ${skus[0].second + 1_000}, :d) RETURNING id")
        try {
            val again = client.entry(admin, body(cu, no))
            assertEquals(HttpStatusCode.OK, again.status, again.bodyAsText())
            assertEquals("replayed", js(again.bodyAsText())["status"]!!.jsonPrimitive.content)
            assertEquals(net, js(again.bodyAsText())["net_mtk"]!!.jsonPrimitive.content)
            assertEquals(1, count("SELECT count(*) FROM app.audit_log WHERE entity = 'paper_backfill' AND entity_id = '$cu' AND action = 'create'"))
            // Other content under the same uuid is still a conflict.
            assertEquals(HttpStatusCode.Conflict, client.entry(admin, body(cu, no, qty2 = 11)).status)
            // Another keyer gets no stored answer: the ingest path with its reach check (re-priced, so a conflict, as before).
            assertEquals(HttpStatusCode.Conflict, client.entry(client.webToken("support1001"), body(cu, no)).status)
        } finally {
            fresh.db.jdbi.useHandle<Exception> { h ->
                h.createUpdate("DELETE FROM app.sku_price WHERE id = ANY(:a)").bindArray("a", Long::class.javaObjectType, added).execute()
                h.createUpdate("UPDATE app.sku_price SET valid_to = NULL WHERE id = ANY(:c)").bindArray("c", Long::class.javaObjectType, closed).execute()
            }
        }
    }

    @Test
    fun aMemoNumberAlreadyStoredAnswersDuplicateAndWritesNothing() = testApplication {
        application { aronApi(wiring) }
        val admin = client.webToken("admin1001")
        val first = uuid(); val no = memoNo()
        assertEquals(HttpStatusCode.OK, client.entry(admin, body(first, no)).status)
        val dup = client.entry(admin, body(uuid(), no))
        assertEquals(HttpStatusCode.OK, dup.status, dup.bodyAsText())
        assertEquals("duplicate_memo_no", js(dup.bodyAsText())["status"]!!.jsonPrimitive.content); assertEquals(first, js(dup.bodyAsText())["memo_client_uuid"]!!.jsonPrimitive.content)
        assertEquals(1, count("SELECT count(*) FROM app.memo WHERE memo_no = '$no'"))
    }

    @Test
    fun rolesValidationAndTheWindow() = testApplication {
        application { aronApi(wiring) }
        val admin = client.webToken("admin1001")
        for (u in listOf("tso1001", "dmo1001")) assertEquals(HttpStatusCode.Forbidden, client.entry(client.webToken(u), body(uuid(), memoNo())).status, u)
        assertEquals(HttpStatusCode.OK, client.entry(client.webToken("superadmin1001"), body(uuid(), memoNo())).status)
        suspend fun status(b: String) = client.entry(admin, b).status
        assertEquals(HttpStatusCode.BadRequest, status(body(uuid(), memoNo(), extra = ""","discount_mtk":1000""")), "discounts are deferred")
        assertEquals(HttpStatusCode.BadRequest, status(body(uuid(), memoNo(), lineKind = "drp_reward")))
        assertEquals(HttpStatusCode.BadRequest, status(body(uuid(), memoNo(), paid = 999_999_999_999)), "paid above net")
        assertEquals(HttpStatusCode.BadRequest, status(body(uuid(), memoNo(), qty1 = 0)))
        assertEquals(HttpStatusCode.BadRequest, status(body(uuid(), "bad memo number")))
        assertEquals(HttpStatusCode.BadRequest, status(body(uuid(), memoNo(), extra = ""","price_mtk":1""")), "the browser never sends a price")
        assertEquals(HttpStatusCode.BadRequest, status(body(uuid(), memoNo(), user = fresh.db.jdbi.withHandle<Long, Exception> { it.createQuery("SELECT id FROM app.app_user WHERE username = 'tso1001'").mapTo(Long::class.java).one() })), "not an SR")
        assertEquals(HttpStatusCode.Conflict, status(body(uuid(), "sr1001-261201-${seq.incrementAndGet()}", date = "2026-12-01")), "past the paper window")
        assertEquals(0, count("SELECT count(*) FROM app.memo WHERE business_date = DATE '2026-12-01'"))
    }
}
