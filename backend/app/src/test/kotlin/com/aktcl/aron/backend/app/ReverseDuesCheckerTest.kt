package com.aktcl.aron.backend.app

import com.aktcl.aron.backend.auth.PasswordHasher
import com.aktcl.aron.backend.platform.AronClock
import com.aktcl.aron.backend.platform.FreshDb
import com.aktcl.aron.backend.platform.Settings
import com.aktcl.aron.backend.sync.DuesLedger
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
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
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

/**
 * Independent T1 (money) checker of DataVoidApi.reverseDues (commit "Data void nets late-collection give-back rows"). Each test is a ledger
 * state in which a data void leaves a tombstoned record on the dues ledger or makes the outlet balance depend on upload order.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ReverseDuesCheckerTest {
    private lateinit var fresh: FreshDb
    private lateinit var wiring: Wiring
    private val password = "throwaway-" + System.nanoTime()
    private val clock = AronClock { Instant.parse("2027-01-03T04:00:00Z") }
    private var routeId = 0L
    private var outletId = 0L
    private var sr = 0L
    private val skus = mutableListOf<Pair<Long, Long>>()
    private val seq = AtomicInteger(500)

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
            h.execute("INSERT INTO app.cfg_value (key, scope_type, scope_id, value, effective_from, config_version, reason) SELECT 'cfg.auth.mfa_required_roles', 'global', 0, '[]'::jsonb, TIMESTAMPTZ '${ApiWorld.CFG_FROM}', max(config_version), 'test' FROM app.cfg_version")
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
    private fun memoSum(memo: String) = count("SELECT COALESCE(sum(amount_mtk),0) FROM app.due_ledger WHERE memo_client_uuid = '$memo'")
    private fun outletSum() = count("SELECT COALESCE(sum(amount_mtk),0) FROM app.due_ledger WHERE outlet_id = $outletId")

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

    private suspend fun HttpClient.void(token: String, date: String): HttpResponse = post("/v1/admin/data-void") {
        bearerAuth(token); contentType(ContentType.Application.Json)
        setBody("""{"client_uuid":"${uuid()}","route_id":$routeId,"business_date":"$date","scope":"all","reason":"Wrong day keyed by mistake"}""")
    }

    /** A synced due_collection row on [date] (so a data void of that route-day tombstones it), without its ledger row. */
    private fun collectionRow(cu: String, date: String, memo: String, amount: Long, capturedAt: String = "${date}T03:00:00Z") = fresh.db.jdbi.useHandle<Exception> { h ->
        h.execute(
            "INSERT INTO app.due_collection (client_uuid, family_uuid, business_date, user_id, route_id, captured_at, config_version, outlet_id, against_memo_client_uuid, against_memo_no, " +
                "against_memo_business_date, amount_mtk, is_full_settlement, outstanding_before_mtk) VALUES (CAST('$cu' AS uuid), CAST('$cu' AS uuid), DATE '$date', $sr, $routeId, " +
                "TIMESTAMPTZ '$capturedAt', 0, $outletId, CAST('$memo' AS uuid), 'x', DATE '$date', $amount, false, 0)",
        )
    }

    private fun ledger(kind: String, date: String, amount: Long, memo: String?, source: String, note: String? = null) = fresh.db.jdbi.useHandle<Exception> { h ->
        h.execute(
            "INSERT INTO app.due_ledger (outlet_id, business_date, entry_kind, amount_mtk, memo_client_uuid, source_client_uuid, note) VALUES ($outletId, DATE '$date', '$kind', $amount, " +
                (if (memo == null) "NULL" else "CAST('$memo' AS uuid)") + ", CAST('$source' AS uuid), " + (if (note == null) "NULL" else "'$note'") + ")",
        )
    }

    /**
     * (b) The SR collected half, voided the memo (closure = the other half), then a collection captured before the void arrived late and overpaid:
     * its give-back is capped at the closure, so give-back (source = the collection, kind adjustment) < collection. A data void of the
     * collection's route-day must take off the collection AND its give-back; the collection reversal (same source, same kind) hits
     * ON CONFLICT DO NOTHING and the give-back stays, so the overpayment credit survives the void.
     */
    @Test
    fun aVoidedLateCollectionWithAPartialGiveBackLeavesTheMemoAtZero() = testApplication {
        application { aronApi(wiring) }
        val admin = client.webToken("admin1001")
        val memo = client.keyMemo(admin, "2026-12-28")
        val due = memoSum(memo); val half = due / 2
        val base = outletSum() - due
        val inOrder = uuid(); val sv = uuid(); val late = uuid()
        fresh.db.jdbi.useHandle<Exception> { it.execute("UPDATE app.memo SET status = 'voided', status_changed_at = now(), voided_by_client_uuid = CAST('$sv' AS uuid) WHERE client_uuid = CAST('$memo' AS uuid)") }
        ledger("collection", "2026-12-28", -half, memo, inOrder)
        ledger("memo_void", "2026-12-28", -(due - half), memo, sv)
        collectionRow(late, "2026-12-29", memo, due)
        ledger("collection", "2026-12-29", -due, memo, late)
        ledger("adjustment", "2026-12-28", due - half, memo, late, DuesLedger.LATE_COLLECTION)
        assertEquals(-half, memoSum(memo), "the late overpayment is a credit before the void")
        assertEquals(HttpStatusCode.OK, client.void(admin, "2026-12-29").status)
        assertEquals(1, count("SELECT count(*) FROM app.due_collection WHERE client_uuid = '$late' AND voided_at IS NOT NULL"))
        assertEquals(0, memoSum(memo), "the tombstoned late collection and its give-back are both off the ledger")
        assertEquals(base, outletSum())
    }

    /**
     * (a)/(d) Memo on day 1, a collection against it on day 2 (already synced). The void of day 1 nets the day-2 collection into the memo's reversal;
     * a later void of day 2 reverses that collection again (its memo is not tombstoned in that void), so the dead memo ends at +collection.
     */
    @Test
    fun voidingTheMemoDayThenTheCollectionDayDoesNotReverseTheCollectionTwice() = testApplication {
        application { aronApi(wiring) }
        val admin = client.webToken("admin1001")
        val memo = client.keyMemo(admin, "2026-12-30")
        val due = memoSum(memo)
        val base = outletSum() - due
        val c = uuid()
        collectionRow(c, "2026-12-31", memo, 30_000)
        ledger("collection", "2026-12-31", -30_000, memo, c)
        assertEquals(HttpStatusCode.OK, client.void(admin, "2026-12-30").status)
        assertEquals(-30_000, memoSum(memo), "after the memo-day void the day-2 cash stays as a credit (policy: cash of an untouched day is real and independent of upload order)")
        assertEquals(HttpStatusCode.OK, client.void(admin, "2026-12-31").status)
        assertEquals(0, memoSum(memo), "after the collection-day void the dead memo still nets to zero")
        assertEquals(base, outletSum())
    }

    /**
     * (d) Same facts, two upload orders: a day-2 collection against a day-1 memo, captured before the data void of day 1. Synced before the void it is
     * netted into the memo's reversal (the outlet loses the credit); synced after it (DuesLedger.afterStored: the memo is still 'active', no closure
     * to reopen) it stays as a credit. The outlet balance depends on upload order (idempotent-sync rule).
     */
    @Test
    fun theOutletBalanceAfterADataVoidDoesNotDependOnWhenTheCollectionSynced() = testApplication {
        application { aronApi(wiring) }
        val admin = client.webToken("admin1001")
        // Order A: collection synced, then the memo-day void.
        val a = client.keyMemo(admin, "2027-01-01")
        val ca = uuid()
        collectionRow(ca, "2027-01-02", a, 30_000)
        ledger("collection", "2027-01-02", -30_000, a, ca)
        assertEquals(HttpStatusCode.OK, client.void(admin, "2027-01-01").status)
        // Order B: the memo-day void, then the same kind of collection syncs (captured before the void, on a route-day that is not voided).
        val b = client.keyMemo(admin, "2027-01-03")
        assertEquals(HttpStatusCode.OK, client.void(admin, "2027-01-03").status)
        val cb = uuid()
        collectionRow(cb, "2026-12-27", b, 30_000)
        fresh.db.jdbi.useTransaction<Exception> { h ->
            DuesLedger.afterStored(
                h, "due_collection",
                buildJsonObject { put("business_date", "2026-12-27"); put("client_uuid", cb); put("captured_at", "2026-12-27T03:00:00Z") },
                buildJsonObject { put("amount_mtk", 30_000); put("against_memo_client_uuid", b); put("against_memo_no", "x"); put("outlet_id", outletId) },
            )
        }
        assertEquals(memoSum(a), memoSum(b), "order A leaves ${memoSum(a)} on the dead memo, order B leaves ${memoSum(b)}")
    }
}
