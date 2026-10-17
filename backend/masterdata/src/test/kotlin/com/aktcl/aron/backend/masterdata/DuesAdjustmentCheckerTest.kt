package com.aktcl.aron.backend.masterdata

import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import java.io.File
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * T1 checker regressions for F-ADM-036 (dues adjustments). Each test demonstrates one defect found in review; they fail
 * against commit 0a8497b0 and pass once the defect is fixed.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DuesAdjustmentCheckerTest {
    private lateinit var env: BackendAdminEnv
    private lateinit var outlets: List<Long>

    @BeforeAll fun setUp() { env = BackendAdminEnv(); outlets = env.outletIds("MIR-SR-D", 6) }
    @AfterAll fun tearDown() = env.close()

    private fun body(u: UUID, outlet: Long, kind: String = "correction", amount: Long = 5_000, reason: String = "Retailer paid in cash at the depot") =
        """{"client_uuid":"$u","outlet_id":$outlet,"kind":"$kind","amount_mtk":$amount,"reason":"$reason"}"""

    private fun balanceText(outlet: Long) = env.scalar("SELECT COALESCE(SUM(amount_mtk),0)::text FROM app.due_ledger WHERE outlet_id = $outlet")!!
    private suspend fun code(r: HttpResponse) = r.bodyAsText().substringAfter("\"code\":\"", "").substringBefore('"')
    private fun ledgerRows(u: UUID) = env.count("SELECT count(*) FROM app.due_ledger WHERE source_client_uuid = '$u' AND entry_kind = 'adjustment'")

    /** Defect 1: `-row.amount_mtk` overflows for Long.MIN_VALUE, so the write-off bound passes and a 9.2e15 Tk write-off is booked. */
    @Test
    fun writeOffOfLongMinValueIsNeverApproved() = env.app {
        val outlet = outlets[0]
        val admin = env.tok("admin1001"); val sup = env.tok("superadmin1001")
        val u = UUID.randomUUID()
        val c = sendA3(HttpMethod.Post, "/v1/admin/dues-adjustments", admin, body(u, outlet, "write_off", Long.MIN_VALUE))
        if (c.status == HttpStatusCode.Created) {
            val d = sendA3(HttpMethod.Post, "/v1/admin/dues-adjustments/${c.objA3().strA3("adjustment_id")}/decision", sup, """{"decision":"approve"}""")
            assertEquals(HttpStatusCode.Conflict, d.status, "a write-off of Long.MIN_VALUE exceeds every balance: ${d.bodyAsText()}")
        }
        assertEquals(0, ledgerRows(u), "no ledger row for an impossible write-off")
    }

    /** Defect 2: amounts are unbounded int64; two approved corrections make the outlet's SUM leave bigint and every later write-off decision is a 500. */
    @Test
    fun hugeCorrectionsCannotBreakTheOutletBalance() = env.app {
        val outlet = outlets[1]
        val admin = env.tok("admin1001"); val sup = env.tok("superadmin1001")
        repeat(2) {
            val c = sendA3(HttpMethod.Post, "/v1/admin/dues-adjustments", admin, body(UUID.randomUUID(), outlet, amount = Long.MAX_VALUE))
            if (c.status == HttpStatusCode.Created) sendA3(HttpMethod.Post, "/v1/admin/dues-adjustments/${c.objA3().strA3("adjustment_id")}/decision", sup, """{"decision":"approve"}""")
        }
        assertTrue(balanceText(outlet).toBigInteger() <= Long.MAX_VALUE.toBigInteger(), "outlet balance left int64: ${balanceText(outlet)}")
        val w = sendA3(HttpMethod.Post, "/v1/admin/dues-adjustments", admin, body(UUID.randomUUID(), outlet, "write_off", -1_000))
        assertEquals(HttpStatusCode.Created, w.status)
        val d = sendA3(HttpMethod.Post, "/v1/admin/dues-adjustments/${w.objA3().strA3("adjustment_id")}/decision", sup, """{"decision":"approve"}""")
        assertTrue(d.status.value < 500, "decision must not 500: ${d.status} ${d.bodyAsText()}")
    }

    /**
     * Defect 3: the proposal's client_uuid becomes the ledger row's source_client_uuid, but other writers (data void: source = the memo's
     * client_uuid; late collection: source = the collection) already post `adjustment` rows. A proposal whose uuid is already a ledger
     * source is accepted and its approval dies on the unique key with a 500 (and in the other order the data void's
     * ON CONFLICT DO NOTHING silently drops the reversal).
     */
    @Test
    fun aClientUuidAlreadyUsedAsALedgerSourceIsRefusedAtCreate() = env.app {
        val outlet = outlets[2]
        val admin = env.tok("admin1001"); val sup = env.tok("superadmin1001")
        val taken = UUID.randomUUID()
        env.exec("INSERT INTO app.due_ledger (outlet_id, business_date, entry_kind, amount_mtk, memo_client_uuid, source_client_uuid, note) VALUES ($outlet, app.dhaka_date(now()), 'adjustment', 3000, '$taken', '$taken', 'data void x')")
        val c = sendA3(HttpMethod.Post, "/v1/admin/dues-adjustments", admin, body(taken, outlet))
        if (c.status == HttpStatusCode.Created) {
            val d = sendA3(HttpMethod.Post, "/v1/admin/dues-adjustments/${c.objA3().strA3("adjustment_id")}/decision", sup, """{"decision":"approve"}""")
            assertTrue(d.status.value < 500, "approval must not 500 on a uuid another ledger writer owns: ${d.status} ${d.bodyAsText()}")
        } else {
            assertEquals(HttpStatusCode.Conflict, c.status, c.bodyAsText())
        }
    }

    /**
     * Defect 4: the write-off bound reads the balance under a lock only this module takes; a collection the sync path commits
     * concurrently is not serialised with it, so the outlet ends below zero (the retailer's cash becomes credit on top of the write-off).
     */
    @Test
    fun aWriteOffCannotRaceAConcurrentCollectionBelowZero() = env.app {
        val outlet = outlets[3]
        val admin = env.tok("admin1001"); val sup = env.tok("superadmin1001")
        val up = sendA3(HttpMethod.Post, "/v1/admin/dues-adjustments", admin, body(UUID.randomUUID(), outlet, amount = 10_000)).objA3().strA3("adjustment_id")
        assertEquals(HttpStatusCode.OK, sendA3(HttpMethod.Post, "/v1/admin/dues-adjustments/$up/decision", sup, """{"decision":"approve"}""").status)
        val x = balanceText(outlet).toLong()
        val wo = sendA3(HttpMethod.Post, "/v1/admin/dues-adjustments", admin, body(UUID.randomUUID(), outlet, "write_off", -x)).objA3().strA3("adjustment_id")
        env.seeded.fresh.dataSource.connection.use { c ->
            c.autoCommit = false
            // The sync path's collection insert (DuesLedger.post), still in flight when the checker approves.
            c.createStatement().use { it.execute("INSERT INTO app.due_ledger (outlet_id, business_date, entry_kind, amount_mtk, source_client_uuid) VALUES ($outlet, app.dhaka_date(now()), 'collection', ${-x}, '${UUID.randomUUID()}')") }
            val t = Thread { runCatching { Thread.sleep(3_000); c.commit() } }.apply { start() }
            sendA3(HttpMethod.Post, "/v1/admin/dues-adjustments/$wo/decision", sup, """{"decision":"approve"}""")
            t.join()
        }
        assertTrue(balanceText(outlet).toLong() >= 0, "write-off plus concurrent collection left the outlet at ${balanceText(outlet)}")
    }

    /** Defect 5 (docs/24 s4 rule 3): a replayed create returns the first result with the same status code (201), not 200. */
    @Test
    fun aReplayedCreateAnswersTheFirstStatusCode() = env.app {
        val admin = env.tok("admin1001")
        val u = UUID.randomUUID()
        assertEquals(HttpStatusCode.Created, sendA3(HttpMethod.Post, "/v1/admin/dues-adjustments", admin, body(u, outlets[4])).status)
        assertEquals(HttpStatusCode.Created, sendA3(HttpMethod.Post, "/v1/admin/dues-adjustments", admin, body(u, outlets[4])).status)
    }

    /** Defect 6 (docs/24 s4 rule 3): the same decision repeated by the same checker is 200 with the stored outcome; the other one is 409 ERR_REQUEST_STATE. */
    @Test
    fun aRepeatedDecisionIsIdempotentAndTheOtherIsRequestState() = env.app {
        val admin = env.tok("admin1001"); val sup = env.tok("superadmin1001")
        val u = UUID.randomUUID()
        val id = sendA3(HttpMethod.Post, "/v1/admin/dues-adjustments", admin, body(u, outlets[5])).objA3().strA3("adjustment_id")
        assertEquals(HttpStatusCode.OK, sendA3(HttpMethod.Post, "/v1/admin/dues-adjustments/$id/decision", sup, """{"decision":"approve"}""").status)
        val again = sendA3(HttpMethod.Post, "/v1/admin/dues-adjustments/$id/decision", sup, """{"decision":"approve"}""")
        assertEquals(HttpStatusCode.OK, again.status, "a browser retry of the same approval: ${again.bodyAsText()}")
        assertEquals(1, ledgerRows(u))
        val other = sendA3(HttpMethod.Post, "/v1/admin/dues-adjustments/$id/decision", sup, """{"decision":"reject"}""")
        assertEquals(HttpStatusCode.Conflict, other.status); assertEquals("ERR_REQUEST_STATE", code(other))
    }

    /** Defect 7: the contract omits responses the server sends (create: replay and 409; list: 400). */
    @Test
    fun theContractListsEveryStatusTheServerSends() {
        val ops = File(System.getProperty("aron.repoRoot"), "contract/slices/operations")
        val create = File(ops, "createDuesAdjustment.yaml").readText()
        val list = File(ops, "listDuesAdjustments.yaml").readText()
        assertTrue("'409':" in create, "createDuesAdjustment: 409 ERR_CONFLICT (uuid reused) is not in the contract")
        assertTrue("'400':" in list, "listDuesAdjustments: 400 (bad status/cursor/limit/outlet_id) is not in the contract")
    }
}
