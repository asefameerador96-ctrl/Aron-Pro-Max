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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
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

/** T1 checker of BA-33 (data void vs approved dues correction): concurrency and dead-memo paths. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DataVoidDuesCheckerTest {
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

    private suspend fun HttpClient.propose(maker: String, memo: String): Long {
        val c = post("/v1/admin/dues-adjustments") {
            bearerAuth(maker); contentType(ContentType.Application.Json)
            setBody("""{"client_uuid":"${uuid()}","outlet_id":$outletId,"memo_client_uuid":"$memo","kind":"correction","amount_mtk":-1000,"reason":"Retailer returned two packs"}""")
        }
        assertEquals(HttpStatusCode.Created, c.status, c.bodyAsText())
        return Json.parseToJsonElement(c.bodyAsText()).jsonObject["adjustment_id"]!!.jsonPrimitive.content.toLong()
    }

    private suspend fun HttpClient.approve(checker: String, id: Long): HttpResponse =
        post("/v1/admin/dues-adjustments/$id/decision") { bearerAuth(checker); contentType(ContentType.Application.Json); setBody("""{"decision":"approve"}""") }

    private suspend fun waitForLockWait(like: String) {
        withTimeout(10_000) {
            while (count("SELECT count(*) FROM pg_stat_activity WHERE wait_event_type = 'Lock' AND query LIKE '$like'") == 0L) delay(20)
        }
    }

    private suspend fun HttpClient.void(token: String, date: String): HttpResponse = post("/v1/admin/data-void") {
        bearerAuth(token); contentType(ContentType.Application.Json)
        setBody("""{"client_uuid":"${uuid()}","route_id":$routeId,"business_date":"$date","scope":"all","reason":"Wrong day keyed by mistake"}""")
    }

    /**
     * Lock order: the approval takes the outlet FOR UPDATE and then the memo FOR SHARE; the void takes the memo (UPDATE) and then,
     * in reverseDues, inserts a due_ledger row whose FK takes FOR KEY SHARE on the same outlet. Racing, they deadlock (40P01) and one
     * of them is answered 503. Money stays right, but a correct pair must serialise, not abort. Fix: in decideAdjustment take the memo
     * FOR SHARE before the 'dues-outlet' lock and the outlet FOR UPDATE.
     */
    @Test
    fun aVoidRacingAnApprovalOfAnAttachedCorrectionDoesNotDeadlock() = testApplication {
        application { aronApi(wiring) }
        val admin = client.webToken("admin1001"); val sup = client.webToken("superadmin1001")
        val memo = client.keyMemo(admin, "2027-01-01")
        val adj = client.propose(admin, memo)
        // Park the approval after it holds the outlet FOR UPDATE: an outside transaction holds the adjustment row it locks next.
        val gate = fresh.dataSource.connection
        gate.autoCommit = false
        gate.createStatement().use { it.execute("SELECT 1 FROM app.dues_adjustment WHERE id = $adj FOR UPDATE") }
        val (approval, void) = coroutineScope {
            val a = async(Dispatchers.IO) { client.approve(sup, adj).let { it.status to it.bodyAsText() } }
            waitForLockWait("%FROM app.dues_adjustment a WHERE a.id%FOR UPDATE%")
            val v = async(Dispatchers.IO) { client.void(admin, "2027-01-01").let { it.status to it.bodyAsText() } }
            // The void has tombstoned the memo and waits on the outlet for its ledger reversal.
            waitForLockWait("%data void%")
            gate.commit(); gate.close()
            a.await() to v.await()
        }
        assertTrue(approval.first != HttpStatusCode.ServiceUnavailable && void.first != HttpStatusCode.ServiceUnavailable,
            "approval and void deadlocked: approval=${approval.first} ${approval.second.take(200)} void=${void.first} ${void.second.take(200)}")
        // Whatever the order, never both: an approved correction on a tombstoned memo.
        assertEquals(0, count("SELECT count(*) FROM app.dues_adjustment a JOIN app.memo m ON m.client_uuid = a.memo_client_uuid WHERE a.status = 'approved' AND m.voided_at IS NOT NULL"))
    }

    /**
     * A memo cancelled by the SR (memo_void synced: status 'voided', its open balance closed by a memo_void ledger row) is dead too,
     * but the approval only looks at voided_at: the correction is approved and its ledger row moves the outlet balance for a sale
     * that no longer exists (in the other order, closeMemoBalance absorbs it). Same for status 'superseded'.
     * Fix: refuse approval when the memo's status <> 'active' as well as when voided_at IS NOT NULL.
     */
    @Test
    fun anApprovalOnAMemoTheSrVoidedIsRefused() = testApplication {
        application { aronApi(wiring) }
        val admin = client.webToken("admin1001"); val sup = client.webToken("superadmin1001")
        val memo = client.keyMemo(admin, "2027-01-02")
        val adj = client.propose(admin, memo)
        // What DuesLedger.afterStored does for a synced memo_void.
        val v = uuid()
        fresh.db.jdbi.useTransaction<Exception> { h ->
            h.createUpdate("UPDATE app.memo SET status = 'voided', status_changed_at = now(), voided_by_client_uuid = CAST(:v AS uuid) WHERE client_uuid = CAST(:m AS uuid) AND status = 'active'")
                .bind("v", v).bind("m", memo).execute()
            h.createUpdate(
                "INSERT INTO app.due_ledger (outlet_id, business_date, entry_kind, amount_mtk, memo_client_uuid, source_client_uuid) " +
                    "SELECT outlet_id, DATE '2027-01-02', 'memo_void', -sum(amount_mtk), memo_client_uuid, CAST(:v AS uuid) FROM app.due_ledger " +
                    "WHERE memo_client_uuid = CAST(:m AS uuid) GROUP BY outlet_id, memo_client_uuid HAVING sum(amount_mtk) > 0",
            ).bind("v", v).bind("m", memo).execute()
        }
        assertEquals(0, count("SELECT COALESCE(sum(amount_mtk),0) FROM app.due_ledger WHERE memo_client_uuid = '$memo'"), "the memo_void closed the memo")
        val r = client.approve(sup, adj)
        assertEquals(HttpStatusCode.Conflict, r.status, "a correction was approved on a memo the SR voided: ${r.bodyAsText().take(200)}")
    }
}
