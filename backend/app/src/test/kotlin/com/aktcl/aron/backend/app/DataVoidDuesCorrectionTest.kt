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

/** Ruling 2026-10-15 (ii): a data void is refused while an approved dues correction is attached to one of its memos (T1, money). */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DataVoidDuesCorrectionTest {
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
    private fun memoNo(d: String) = "sr1001-${d.substring(2, 4)}${d.substring(5, 7)}${d.substring(8, 10)}-${seq.incrementAndGet()}"

    private suspend fun HttpClient.webToken(user: String): String {
        val r = post("/v1/auth/login") { contentType(ContentType.Application.Json); setBody("""{"username":"$user","password":"$password","client":"web"}""") }
        assertEquals(HttpStatusCode.OK, r.status, r.bodyAsText())
        return Json.parseToJsonElement(r.bodyAsText()).jsonObject["access_token"]!!.jsonPrimitive.content
    }

    private suspend fun HttpClient.keyMemo(token: String, date: String): String {
        val cu = uuid()
        val r = post("/v1/admin/data-entry") {
            bearerAuth(token); contentType(ContentType.Application.Json)
            setBody("""{"client_uuid":"$cu","memo_no":"${memoNo(date)}","business_date":"$date","user_id":$sr,"route_id":$routeId,"outlet_id":$outletId,
                "lines":[{"sku_id":${skus[0].first},"qty_base":5,"line_kind":"sale"}],"paid_mtk":0,"reason":"Phone died, keyed from the printed memo"}""")
        }
        assertEquals(HttpStatusCode.OK, r.status, r.bodyAsText())
        return cu
    }

    private suspend fun HttpClient.correction(maker: String, checker: String, memo: String): HttpResponse {
        val c = post("/v1/admin/dues-adjustments") {
            bearerAuth(maker); contentType(ContentType.Application.Json)
            setBody("""{"client_uuid":"${uuid()}","outlet_id":$outletId,"memo_client_uuid":"$memo","kind":"correction","amount_mtk":-1000,"reason":"Retailer returned two packs"}""")
        }
        assertEquals(HttpStatusCode.Created, c.status, c.bodyAsText())
        val id = Json.parseToJsonElement(c.bodyAsText()).jsonObject["adjustment_id"]!!.jsonPrimitive.content
        return post("/v1/admin/dues-adjustments/$id/decision") { bearerAuth(checker); contentType(ContentType.Application.Json); setBody("""{"decision":"approve"}""") }
    }

    private suspend fun HttpClient.void(token: String, date: String): HttpResponse = post("/v1/admin/data-void") {
        bearerAuth(token); contentType(ContentType.Application.Json)
        setBody("""{"client_uuid":"${uuid()}","route_id":$routeId,"business_date":"$date","scope":"all","reason":"Wrong day keyed by mistake"}""")
    }

    @Test
    fun aVoidIsRefusedWhileAnApprovedCorrectionIsAttachedAndAnApprovalOnAVoidedMemoIsRefused() = testApplication {
        application { aronApi(wiring) }
        val admin = client.webToken("admin1001"); val sup = client.webToken("superadmin1001")
        // Day A: memo with an approved correction -> the void is refused, nothing is tombstoned, the ledger is untouched.
        val a = client.keyMemo(admin, "2027-01-03")
        val ok = client.correction(admin, sup, a)
        assertEquals(HttpStatusCode.OK, ok.status, ok.bodyAsText())
        val before = count("SELECT COALESCE(sum(amount_mtk),0) FROM app.due_ledger WHERE outlet_id = $outletId")
        val v = client.void(admin, "2027-01-03")
        assertEquals(HttpStatusCode.Conflict, v.status, v.bodyAsText())
        assertEquals("ERR_DAY_DATA_VOID_NOT_ALLOWED", Json.parseToJsonElement(v.bodyAsText()).jsonObject["code"]!!.jsonPrimitive.content)
        assertEquals(0, count("SELECT count(*) FROM app.memo WHERE client_uuid = '$a' AND voided_at IS NOT NULL"))
        assertEquals(before, count("SELECT COALESCE(sum(amount_mtk),0) FROM app.due_ledger WHERE outlet_id = $outletId"))
        // Day B: no correction -> the void works; a correction proposed for the voided memo cannot be approved.
        val b = client.keyMemo(admin, "2027-01-02")
        fresh.db.jdbi.useHandle<Exception> { it.execute("DELETE FROM app.dirty_key WHERE kind = 'route_day_agg' AND subject_id = $routeId AND business_date = DATE '2027-01-02'") }
        assertEquals(HttpStatusCode.OK, client.void(admin, "2027-01-02").status)
        assertEquals(1, count("SELECT count(*) FROM app.dirty_key WHERE kind = 'route_day_agg' AND subject_id = $routeId AND business_date = DATE '2027-01-02'"), "a void marks the route-day dirty (F-SYS-086)")
        val late = client.correction(admin, sup, b)
        assertEquals(HttpStatusCode.Conflict, late.status, late.bodyAsText())
    }

    /**
     * Residual (found by the Opus checker of BA-33): the SR voided the memo, then a collection captured before the void arrived late and gave back part
     * of the closure (an `adjustment` row, note late_collection_reopens_closure). A data void of the memo's route-day netted every ledger row of the memo
     * except `adjustment` rows, so it posted a reversal that ignored the give-back and left that amount on the outlet balance.
     */
    @Test
    fun aDataVoidNetsTheLateCollectionGiveBackToo() = testApplication {
        application { aronApi(wiring) }
        val admin = client.webToken("admin1001")
        val memo = client.keyMemo(admin, "2027-01-01")
        val due = count("SELECT sum(amount_mtk) FROM app.due_ledger WHERE memo_client_uuid = '$memo'")
        val before = count("SELECT COALESCE(sum(amount_mtk),0) FROM app.due_ledger WHERE outlet_id = $outletId") - due
        val collection = uuid(); val giveBack = uuid(); val sv = uuid()
        fresh.db.jdbi.useTransaction<Exception> { h ->
            // The ledger as DuesLedger leaves it: due, memo_void closure, a late collection of 30 and its give-back of 30.
            h.execute("UPDATE app.memo SET status = 'voided', status_changed_at = now(), voided_by_client_uuid = CAST('$sv' AS uuid) WHERE client_uuid = CAST('$memo' AS uuid)")
            h.execute("INSERT INTO app.due_ledger (outlet_id, business_date, entry_kind, amount_mtk, memo_client_uuid, source_client_uuid) VALUES ($outletId, DATE '2027-01-01', 'memo_void', -$due, CAST('$memo' AS uuid), CAST('$sv' AS uuid))")
            h.execute("INSERT INTO app.due_ledger (outlet_id, business_date, entry_kind, amount_mtk, memo_client_uuid, source_client_uuid) VALUES ($outletId, DATE '2027-01-01', 'collection', -30, CAST('$memo' AS uuid), CAST('$collection' AS uuid))")
            h.execute("INSERT INTO app.due_ledger (outlet_id, business_date, entry_kind, amount_mtk, memo_client_uuid, source_client_uuid, note) VALUES ($outletId, DATE '2027-01-01', 'adjustment', 30, CAST('$memo' AS uuid), CAST('$giveBack' AS uuid), 'late_collection_reopens_closure')")
        }
        assertEquals(before, count("SELECT COALESCE(sum(amount_mtk),0) FROM app.due_ledger WHERE outlet_id = $outletId"), "the memo nets to zero before the data void")
        assertEquals(HttpStatusCode.OK, client.void(admin, "2027-01-01").status)
        assertEquals(0, count("SELECT COALESCE(sum(amount_mtk),0) FROM app.due_ledger WHERE memo_client_uuid = '$memo'"), "the voided memo leaves nothing on the ledger")
        assertEquals(before, count("SELECT COALESCE(sum(amount_mtk),0) FROM app.due_ledger WHERE outlet_id = $outletId"))
    }
}
